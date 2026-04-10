# 단일 Redis 병목 재현 실험 시나리오

## 목적

현재 WebSocket Listener 구조에서 단일 Redis가 병목이 되는 지점을 재현한다.

검증하려는 대상:

- Redis Pub/Sub ingress 처리 한계
- watcher key 쓰기/TTL churn 한계
- 특정 hot stock 집중 시 hot key/channel 압력
- listener 2대 환경에서 이벤트 중복 소비 비용
- Redis 병목이 listener fanout 지연으로 어떻게 전파되는지

---

## 현재 구조 기준 Redis 역할

### A. 가격 이벤트 ingress

- 채널: `market:price-updated`
- 경로:
  - Redis Pub/Sub
  - `PriceEventRedisSubscriber`
  - `MarketEventBroadcaster`

관련 코드:

- `src/main/java/depth/finvibe/listener/config/RedisPubSubConfig.java`
- `src/main/java/depth/finvibe/listener/redis/PriceEventRedisSubscriber.java`

### B. watcher 상태 저장

- key 패턴: `market:current-watcher:{stockId}`
- 연산:
  - `SADD`
  - `SREM`
  - `EXPIRE`
  - `HASKEY`
  - `DEL`

관련 코드:

- `src/main/java/depth/finvibe/listener/redis/CurrentWatcherRedisRepository.java`
- `src/main/java/depth/finvibe/listener/websocket/MarketQuoteWebSocketHandler.java`
- `src/main/java/depth/finvibe/listener/websocket/WatchRenewScheduler.java`

### C. renew 트래픽

- `WatchRenewScheduler`
- 구독 유지용 TTL 갱신

---

## 실험 환경

### 구성

- WebSocket Listener: 2대
- Redis: 1대
- Nginx LB: 1대
- 부하 생성기:
  - K6: WebSocket 클라이언트 부하
  - 별도 publisher: Redis price event 발행기

### 고정 조건

- listener replica: 2
- Redis는 단일 인스턴스 유지
- Grafana / Prometheus / Redis INFO 수집 가능해야 함

---

## 핵심 메트릭

### Redis 측

- CPU usage
- used_memory
- mem_fragmentation_ratio
- instantaneous_ops_per_sec
- connected_clients
- network input/output
- pubsub channels / subscribers
- commandstats
  - `publish`
  - `sadd`
  - `srem`
  - `expire`
  - `del`
- latency / slowlog

### Listener 측

- `redisEventConsumed`
- `redisEventFailed`
- `watcherOp(save/renew/remove)`
- active connections
- total subscriptions
- session queue overflow
- event delivered / failed
- fanout p50 / p95 / p99
- subscribe latency p50 / p95 / p99

---

## 부하 시나리오

## 시나리오 A. Baseline

### 목적

기준 성능 측정

### 부하

- WebSocket 1,000 연결
- 평균 구독 수: 세션당 3개
- Zipf skew 약하게
- publish rate: 100 msg/sec
- renew interval: 기본값 유지

### 기대

- Redis / Listener 모두 안정
- 기준 latency 확보

---

## 시나리오 B. Hot stock Pub/Sub flood

### 목적

단일 채널/종목 hot key 상황에서 Redis Pub/Sub ingress 한계 확인

### 부하

- WebSocket 10,000 연결
- 상위 1개 stock에 40~60% 구독 집중
- publish rate:
  - 500 msg/sec
  - 1,000 msg/sec
  - 2,000 msg/sec
  - 5,000 msg/sec
  단계적으로 증가

### 관찰 포인트

- Redis CPU 급상승 여부
- `publish` command 증가
- listener의 `redisEventConsumed` 처리 지연
- fanout p95 / p99 증가 시점
- Redis network out 증가

### 실패 기준 예시

- Redis CPU 80~90% 이상 지속
- fanout p99 급격 증가
- Redis latency spike
- listener backlog / queue overflow 증가

---

## 시나리오 C. Subscribe/Unsubscribe churn

### 목적

watcher key write churn이 단일 Redis에 미치는 영향 확인

### 부하

- 연결 수: 10,000
- 매초 일정 비율이:
  - subscribe
  - unsubscribe
  - reconnect
  반복

예시:

- 초당 500 ops
- 초당 1,000 ops
- 초당 2,000 ops

### Redis 부하 포인트

- `SADD`
- `SREM`
- `EXPIRE`
- `DEL`

### 관찰 포인트

- Redis ops/sec 증가
- memory fragmentation
- watcher 관련 command latency
- remove / save burst 때 latency spike
- subscribe latency 상승 여부

---

## 시나리오 D. Renew storm

### 목적

renew 주기성 burst가 Redis를 압박하는지 확인

### 부하

- WebSocket 10,000 연결
- 세션당 평균 10~30 종목 구독
- renew interval을 낮춰 테스트
  - 120s
  - 30s
  - 10s
  - 5s

### Redis 부하 포인트

- `HASKEY`
- `EXPIRE`

### 관찰 포인트

- renew 시점 주기적 CPU spike
- commandstats에서 expire 계열 급증
- listener renew task backlog
- 같은 시점 fanout latency 증가 여부

### 실패 기준 예시

- renew 타이밍마다 Redis latency가 튐
- fanout p95 / p99가 renew 주기와 동기화되어 악화됨

---

## 시나리오 E. Hot watcher key stress

### 목적

특정 stock watcher key 하나가 hot key가 될 때의 한계 측정

### 부하

- 전체 세션 10,000
- 70~90%가 동일 stock 구독
- subscribe / unsubscribe / renew도 그 stock 위주

### Redis key 예시

- `market:current-watcher:005930`

### 관찰 포인트

- 특정 stock key 관련 command 집중
- hot key 조작 latency
- Redis CPU와 memory pressure
- listener fanout과 Redis watcher op의 상관관계

---

## 시나리오 F. Mixed realistic scenario (추천 1순위)

### 목적

실제 서비스와 가장 유사한 단일 Redis 압박 상황 재현

### 부하

- WebSocket 10,000 연결
- Zipf 기반 구독 분포
- 상위 1개 stock 30~50% 집중
- 나머지는 롱테일 분포
- publish rate: 1,000~3,000 msg/sec
- subscribe / unsubscribe churn 소량 포함
- renew 기본값 또는 30s
- listener 2대 / Redis 1대 유지

### 왜 추천하나

이 시나리오는 동시에 아래를 모두 포함한다.

- hot key
- Pub/Sub ingress
- watcher key churn
- renew
- listener 2대 중복 소비

즉 단일 Redis가 실제 서비스처럼 버거워지는 상황을 가장 잘 보여준다.

---

## 실험 단계

### Step 1. Baseline

- 시나리오 A

### Step 2. Pub/Sub 한계 확인

- 시나리오 B만 독립 수행

### Step 3. watcher churn 한계 확인

- 시나리오 C, D 독립 수행

### Step 4. hot key 집중 확인

- 시나리오 E 수행

### Step 5. 현실 통합 시나리오

- 시나리오 F 수행

---

## 결과 해석 포인트

### Redis가 먼저 병목인 경우

- Redis CPU / latency가 먼저 올라감
- listener보다 Redis가 먼저 흔들림
- Pub/Sub 처리 지연이 listener fanout에 전파됨

### listener가 먼저 병목인 경우

- Redis는 버티는데
- listener queue / send latency가 먼저 증가
- Redis Cluster로 바꿔도 문제 핵심은 안 바뀔 가능성이 높음

### hot key 문제인 경우

- 전체 부하보다 특정 stock/channel 집중 때 급격히 악화
- 균등 분포보다 Zipf 분포에서 훨씬 빨리 무너짐

---

## 이 실험으로 얻고 싶은 결론

최종적으로 아래 질문에 답할 수 있어야 한다.

1. 단일 Redis가 실제로 먼저 병목이 되는가?
2. 병목이 Pub/Sub인가, watcher churn인가, renew storm인가?
3. hot stock 집중이 Redis에서 먼저 문제를 만드는가?
4. Redis Cluster가 필요한 상황인가?
5. 아니면 listener fanout 구조 개선이 우선인가?

---

## 추천 시작값

처음 돌릴 때는 아래부터 시작 추천:

- listener: 2대
- Redis: 1대
- WebSocket 연결: 5,000 → 10,000
- 평균 구독: 5~10개
- hot stock 집중도: 30%
- publish rate: 500 → 1,000 → 2,000 msg/sec
- renew interval: 기본값 → 30초
- churn: 초당 300 → 500 ops

---

## 최종 추천 시나리오

가장 먼저 돌려볼 시나리오는 아래다.

> 10,000 WebSocket 연결 + Zipf 기반 hot stock 집중 + 1,000~3,000 msg/sec publish + 소량 subscribe/unsubscribe churn + renew 동시 실행

이 시나리오가 단일 Redis가 실제로 어디서 아파지는지 가장 현실적으로 보여줄 가능성이 높다.

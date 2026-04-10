# WebSocket Hot Key Fanout 구조 메모

## 목적

현재 WebSocket Listener의 hot key(특정 종목 구독 집중) 문제를 코드/아키텍처 관점에서 정리하고,
Redis Cluster 전환이 실제 병목 해결책인지 판단하기 위한 메모다.

---

## 현재 구조 요약

현재 가격 이벤트 fanout 경로는 아래와 같다.

```text
Redis Pub/Sub
→ PriceEventRedisSubscriber
→ MarketEventBroadcaster.broadcastCurrentPrice(...)
→ SessionRegistry.getSubscribers(stockId)
→ clientSession.enqueueSessionTask(...)
→ ClientSession queue drain
→ WebSocketSession.sendMessage(...)
```

핵심 포인트:

- Redis Pub/Sub로 가격 이벤트를 수신한다.
- 각 Listener 인스턴스는 자기 로컬 세션만 관리한다.
- fanout은 `stockId` 기준 구독 세션 목록을 조회한 뒤 각 세션 queue에 enqueue한다.
- 실제 송신은 세션 queue에서 직렬 실행된다.

관련 코드:

- `src/main/java/depth/finvibe/listener/config/RedisPubSubConfig.java`
- `src/main/java/depth/finvibe/listener/redis/PriceEventRedisSubscriber.java`
- `src/main/java/depth/finvibe/listener/websocket/MarketEventBroadcaster.java`
- `src/main/java/depth/finvibe/listener/websocket/SessionRegistry.java`
- `src/main/java/depth/finvibe/listener/websocket/ClientSession.java`

---

## 현재 적용된 fanout 개선

### 1. 세션 단위 직렬화

- 세션별 `ArrayBlockingQueue<Runnable>` 사용
- 같은 세션에 대한 send/ack/ping/renew 작업은 직렬 처리
- `TEXT_PARTIAL_WRITING` 계열 동시 송신 충돌을 막기 위한 구조

### 2. Virtual Thread 기반 세션 작업 실행

- 세션 queue drain은 `listenerVirtualTaskExecutor`에서 실행
- inbound / fanout / heartbeat / renew 작업이 세션 queue를 타는 구조

### 3. chunk + 제한된 병렬 enqueue

현재 `MarketEventBroadcaster`는 subscriber 전체를 한 스레드로 순회하지 않고,
설정 기반 chunk로 나눈 뒤 제한된 병렬 executor에서 enqueue를 수행한다.

```text
subscribers
→ chunk 분할
→ chunk별 병렬 enqueue
→ 각 세션 queue에 task 적재
→ 실제 send는 세션 queue에서 직렬 수행
```

관련 설정:

- `listener.websocket.fanout-chunk-size`
- `listener.websocket.fanout-chunk-parallelism`

기본값:

- `fanout-chunk-size: 256`
- `fanout-chunk-parallelism: 4`

이 최적화는 hot key 상황에서 **fanout scheduling 병목**을 줄이기 위한 것이다.
즉, 실제 느린 클라이언트 send 비용 자체를 없애는 것은 아니다.

---

## hot key 상황에서 실제 병목

현재 구조에서 hot key 압력은 크게 두 단계로 나뉜다.

### A. fanout scheduling 병목

- subscriber 수가 매우 많을 때 broadcaster가 enqueue를 많이 수행
- 이벤트 1건당 세션 queue 적재 작업 수가 매우 커짐
- hot stock 하나가 broadcaster 경로를 오래 점유할 수 있음

현재 chunk + 병렬 enqueue는 이 부분을 완화한다.

### B. 실제 WebSocket 송신 병목

- `WebSocketSession.sendMessage(...)`
- 느린 클라이언트 / send buffer 적체 / backpressure
- 세션 queue backlog 누적
- `ConcurrentWebSocketSessionDecorator`의 시간/버퍼 제한 초과

이 부분은 여전히 남아 있다.

즉, 지금 최적화는 **fanout 시작 단계**를 개선한 것이고,
**slow consumer가 만드는 실제 송신 병목**은 별도 관리가 필요하다.

---

## 멀티 인스턴스(Listener 2대) 구조에서의 의미

배포는 이미 다중 인스턴스를 고려하고 있다.

- `deploy/docker-compose.yml` 에서 `listener` scale 가능
- `deploy/nginx.conf` 에서 Nginx upstream으로 다중 listener 연결 분산
- `README.md` 에서 `--scale listener=N` 운영 전제 명시

하지만 현재 구조는 **stock ownership 기반 분산**은 아니다.

### 현재 동작 방식

```text
Redis price event 1개
→ listener A도 수신
→ listener B도 수신

listener A: 자기 로컬 세션에만 fanout
listener B: 자기 로컬 세션에만 fanout
```

즉:

- 각 인스턴스가 같은 Pub/Sub 이벤트를 모두 소비한다.
- 세션 상태는 로컬(`SessionRegistry`)이다.
- 인스턴스 수가 늘어도 이벤트 처리 시작점 비용은 인스턴스마다 반복된다.

### 잠재 문제

1. **이벤트 처리 중복 비용**
   - payload serialize
   - `getSubscribers(stockId)`
   - chunk enqueue
   - 이 작업이 인스턴스마다 반복됨

2. **hot stock 불균형 가능성**
   - 현재 LB는 `least_conn` 기반
   - stock-aware 분산이 아니므로 특정 종목 세션이 한쪽 listener에 더 많이 몰릴 수 있음

3. **watcher 저장 모델의 애매함**
   - `CurrentWatcherRedisRepository`는 `market:current-watcher:{stockId}` key에 `userId`를 저장함
   - 동일 user가 여러 세션/여러 listener에 걸쳐 같은 stock을 보고 있으면,
     한쪽 세션 종료 시 `remove(userId, stockId)`가 다른 세션 존재를 충분히 표현하지 못할 수 있음

즉, 현재 구조는 **멀티 인스턴스 운영 가능**하지만,
**hot key를 구조적으로 shard/ownership 분산하는 설계까지는 아님**.

---

## Redis Cluster 전환에 대한 판단

### 결론

Redis Cluster는 **지금 당장 1순위 성능 해법은 아니다**.

의미가 있는 영역:

- Redis SPOF 제거
- Redis 전체 용량/가용성 개선
- watcher key가 전체적으로 많이 퍼져 있을 때 aggregate capacity 확보

한계:

- hot stock fanout 자체는 그대로 남음
- listener 로컬 `SessionRegistry` / 세션 queue / `sendMessage()` 병목은 그대로
- 느린 클라이언트 문제는 전혀 해결하지 못함

### 왜 1순위가 아닌가

현재 진짜 무거운 경로는 아래다.

```text
Redis Pub/Sub 수신
→ Listener 로컬 subscriber 조회
→ 세션 queue enqueue
→ 실제 websocket send
```

즉 Redis는 ingress 역할을 하지만,
hot key 압력의 본체는 **listener 이후 fanout egress** 쪽이다.

### Pub/Sub 관점 주의점

Redis Cluster가 곧바로 Pub/Sub fanout 병목을 선형 확장해주지는 않는다.

- classic Pub/Sub는 기대만큼 쉽게 scale-out되지 않음
- sharded Pub/Sub 같은 재설계가 필요할 수 있음
- 매우 인기 많은 stock은 결국 여전히 hot shard/channel이 될 수 있음

### watcher key 관점 주의점

현재 watcher key는 `market:current-watcher:{stockId}` 형태라,
전체 stock 분산에는 도움이 될 수 있어도,
특정 인기 stock 하나는 여전히 hot key가 될 수 있다.

### Redis Cluster를 먼저 고려해도 되는 경우

1. Redis 자체가 SPOF라서 운영 리스크가 큼
2. Redis CPU/메모리/네트워크가 실제 선행 병목임
3. watcher key 부하가 전체적으로 매우 큼
4. HA 목적이 성능 개선보다 더 중요함

### Redis Cluster보다 먼저 볼 것

1. hot stock coalescing
2. slow-consumer isolation 강화
3. stock ownership / shard-by-stock
4. 필요하면 sharded/partitioned Pub/Sub

즉 Redis Cluster는 **해도 되는 투자**지만,
현재 hot key WebSocket fanout 병목의 직접적인 1순위 해법으로 보기 어렵다.

---

## 아키텍처 관점 추천 우선순위

### 1. stock-keyed mailbox + latest-value coalescing

가장 먼저 고려할 구조 개선.

개념:

- stockId별 mailbox를 둔다.
- 아직 fanout되지 않은 이전 이벤트가 있으면 최신값으로 덮어쓴다.
- 같은 stock에 대해 중복 fanout되는 낡은 이벤트를 줄인다.

장점:

- hot key에서 fanout 이벤트 수 자체를 줄일 수 있음
- current price 성격(최신값 우선)에 잘 맞음
- 기존 per-session queue 구조와 공존 가능

주의:

- 모든 tick을 반드시 보내야 하는 요구사항이면 부적합

### 2. slow-consumer isolation 강화

- queue backlog threshold
- lag threshold
- 더 공격적인 drop / disconnect / degrade 정책

핫키 상황에서 소수 느린 세션이 전체를 망치지 않도록 하는 장치다.

### 3. shard-by-stock listener ownership

개념:

- stock hash 기준으로 특정 listener/shard가 특정 stock을 담당
- 같은 stock 이벤트를 모든 listener가 다 처리하지 않도록 ownership 부여

장점:

- 이벤트 처리 중복 비용 감소
- stock 기준 scale-out 용이

### 4. partitioned / sharded Pub/Sub

이건 독립 해법이라기보다,
shard-by-stock 구조를 구현하기 위한 transport 측면의 보조 수단에 가깝다.

---

## 최종 정리

### 현재 개선의 의미

현재 적용된 chunk + 병렬 enqueue는,
hot key 상황에서 **fanout scheduling 병목**을 줄이는 개선이다.

### 아직 남는 것

- 실제 `sendMessage()` 병목
- 느린 클라이언트 backlog
- 멀티 인스턴스에서의 이벤트 처리 중복
- stock ownership 부재

### Redis Cluster 판단

Redis Cluster는:

- **HA / aggregate Redis capacity** 측면에서는 의미 있음
- **hot key websocket fanout 해결** 측면에서는 1순위는 아님

### 추천 순서

1. 관측 강화
2. stock-keyed mailbox + latest-value coalescing
3. slow-consumer isolation 강화
4. shard-by-stock + 필요 시 partitioned Pub/Sub
5. Redis Cluster는 HA/Redis saturation 필요가 명확할 때 검토

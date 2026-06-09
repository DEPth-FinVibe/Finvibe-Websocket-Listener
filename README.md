# Finvibe WebSocket Listener

Monolith가 발행한 가격 이벤트를 Redis Pub/Sub으로 수신하여 브라우저 WebSocket 세션에 실시간 전달하는 서비스입니다.

---

## 병목 추적 로드맵 — 종단간 latency 34,000ms → 240ms

하나의 병목을 해결하면 다음 병목이 드러나는 방식으로 단계적으로 개선했습니다.

### Step 1. WebSocket 연결 자원 최적화 — 1,000 OOM → 10,000 연결

- **문제**: 1,000 연결에서 JVM 힙 3GB 초과 OOM (목표: DAU 1만 기준 10,000 연결)
- **원인**: 연결마다 heartbeat·auth timeout·pong 용 `ScheduledFuture` 생성 → 스케줄러 큐에 O(N) 태스크 상주
- **해결**:
  - Per-connection 스케줄 태스크 → 단일 sweep 루프 통합 (O(N) → O(1))
  - 구독 인덱스 `Map<String, Set<String>>` → `Map<Long, Set<String>>` 정규화
  - 세션당 메시지 버퍼 512KB → 64KB 축소
- **→ 다음 병목**: 연결은 충분해졌지만, fanout 성능 측정이 가능해지면서 전송 지연이 드러남

### Step 2. Virtual Thread 기반 Fanout — p99 21,000ms → 650ms

- **문제**: 5,000 연결 + 유저당 20종목에서 fanout p99 21초
- **원인**: 고정 스레드풀 4개가 5,000 세션에 순차 전송. `sendMessage()`가 blocking I/O
- **해결**: Virtual Thread 도입. 단, Spring WebSocket `sendMessage()` 내부 `synchronized`에서 JDK 21 VT가 carrier thread에 pinning → JDK 25(JEP 491) 적용
- **→ 다음 병목**: fanout은 해결됐지만 종단간 latency가 여전히 3,400ms — Redis 구간이 병목

### Step 3. Redis Cluster Sharded Pub/Sub — Ping p95 700ms → 25ms

- **문제**: 현재가 캐싱 1초당 450,000건 + Pub/Sub이 단일 Redis에 집중. Ping p95 700ms
- **해결**: 일반 PUBLISH는 전체 노드 브로드캐스트 → **Sharded Pub/Sub** 채택 (채널이 해시 슬롯에 바인딩, 해당 노드에서만 처리)
- **at-most-once 보완**: 현재가를 Redis key에 별도 저장 → 재연결 시 catch-up하는 이중 구조
- **결과**: 종단간 p95 3,400ms → **240ms**

### Step 3-1. 재연결 폭풍 대응 — 파이프라인 불일치 발견

- **발견**: 롤링 업데이트 시나리오 점검 중 같은 코드베이스에서 `batchRenew()`는 파이프라인, `save()`/`remove()`는 개별 실행하는 불일치 확인
- **영향**: 유저 2,500명 × 종목 20개 = ~135,000건 개별 Redis 왕복이 수 초 안에 집중
- **해결**: 전 경로에 파이프라인 적용. 유저당 왕복 40~60회 → **1~2회** (97.5% 감소)

### Step 3-2. 측정 왜곡 검증 — 잘못된 전제 방지

- **상황**: 스케일 2배(10,000 WS + 1,000종목) 테스트에서 publish→consume p99가 1.2초. Redis ops 수준에 비해 설명 안 됨
- **검증**: 타임스탬프를 세 지점(publishedAt, arrivedAt, consumedAt)으로 분리
- **결과**: 1.2초 대부분이 테스트 publisher의 tick 시각 재사용에서 온 **측정 왜곡**. 이 검증 없이는 Redis가 느리다는 잘못된 전제로 불필요한 최적화를 진행했을 것

### 최종 결과

| 지표 | Before | After |
|---|---|---|
| 동시 연결 | 1,000 OOM | **10,000 유지** |
| Fanout p99 | 21,000ms | **650ms** |
| Redis Ping p95 | 700ms | **25ms** |
| **종단간 전달 p95** | **34,000ms** | **240ms** |

---

## 실행

```bash
./gradlew bootRun   # 포트 8080, WebSocket 경로: /market/ws
```

## 배포

Caddy(80/443) → Nginx(내부 LB) → Listener N개 (`docker compose up -d --scale listener=N`)

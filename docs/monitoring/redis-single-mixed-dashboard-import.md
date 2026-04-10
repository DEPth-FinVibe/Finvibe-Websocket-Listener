# Redis Single-Node Mixed Dashboard Import Guide

## 파일

- `docs/monitoring/grafana-redis-single-mixed-dashboard.json`

## 용도

아래 시나리오를 볼 때 쓰는 전용 대시보드다.

- WebSocket 10k 연결 유지
- Zipf 기반 hot stock 집중
- 일부 subscribe/unsubscribe churn
- listener 2대 + Redis 1대
- Redis Pub/Sub + watcher churn + renew 동시 관찰

## Grafana Import 방법

1. Grafana → **Dashboards** → **New** → **Import**
2. `grafana-redis-single-mixed-dashboard.json` 업로드
3. Prometheus datasource 선택
4. Import

## 전제

### Listener 메트릭

다음 메트릭이 Prometheus에 수집되고 있어야 한다.

- `finvibe_ws_active_connections`
- `finvibe_ws_total_subscriptions`
- `finvibe_ws_redis_events_consumed_total`
- `finvibe_ws_events_broadcast_total`
- `finvibe_ws_event_deliveries_total`
- `finvibe_ws_event_delivery_failures_by_reason_total`
- `finvibe_ws_watcher_ops_total`
- `finvibe_ws_session_queue_overflow_total`
- `finvibe_ws_session_task_failures_total`

### Redis exporter 메트릭

아래 계열이 있어야 한다.

- `redis_connected_clients`
- `redis_commands_processed_total`
- `redis_commands_total{cmd=...}`
- `redis_memory_used_bytes`
- `redis_mem_fragmentation_ratio`
- `redis_net_input_bytes_total`
- `redis_net_output_bytes_total`

## 패널 해석 우선순위

### 1. Redis 이벤트 소비 vs 브로드캐스트

- `redis consumed/s`는 오르는데
- `broadcast/s`, `deliveries/s`가 못 따라가면

listener fanout 경로가 먼저 막히는 신호다.

### 2. Watcher 작업량(save/renew/remove)

- `renew`가 주기적으로 튀면 renew storm 의심
- `save/remove`가 함께 오르면 churn 부하가 강한 상황

### 3. Redis commands/s

- `publish` 증가: ingress pressure
- `sadd/srem/expire/del` 증가: watcher churn pressure

### 4. Session queue overflow / task failure

- `broadcast_event_drop`
- `watch_renew_drop`
- `heartbeat_sweep_drop`

같은 stage가 증가하면 그 경로가 밀리는 중이다.

### 5. Redis memory / fragmentation

- memory는 안정적이지만 latency가 튀면 CPU/command contention 가능성
- fragmentation이 같이 오르면 TTL churn 영향 가능성

## 권장 사용법

1. k6 `redis-single-mixed-*` 시나리오 실행
2. price event publisher 또는 mock-market/TesterProvider 동시 구동
3. 이 대시보드로 listener + Redis를 동시에 확인
4. 결과는 아래 순서로 해석
   - Redis가 먼저 흔들렸는지
   - listener가 먼저 흔들렸는지
   - churn/renew 중 어느 쪽이 더 아픈지

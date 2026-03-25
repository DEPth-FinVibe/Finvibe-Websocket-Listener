# Finvibe WebSocket Listener

브라우저 대상 WebSocket(`/market/ws`) 트래픽을 모놀리스에서 분리하기 위한 리스너 서비스입니다.

## 역할

- 브라우저와 WebSocket 연결/인증/JWT 검증 처리
- 구독/해제/유지(renew) 상태를 Redis watcher 인덱스에 직접 반영
- Monolith가 발행한 가격 이벤트를 Redis Pub/Sub로 수신 후 브라우저로 전파

## Redis 채널

- `market:price-updated`: Monolith -> Listener
- `market:current-watcher:{stockId}`: Listener -> Redis key 관리

## 실행

```bash
./gradlew bootRun
```

기본 포트는 `8090`이며, WebSocket 경로는 `/market/ws` 입니다.

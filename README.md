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

## GitHub Actions 배포 설정

`CD` 워크플로우(`.github/workflows/cd.yml`)를 사용하려면 아래 값을 설정해야 합니다.

### GitHub Secrets (필수)

- `DOCKERHUB_USERNAME`
- `DOCKERHUB_TOKEN`
- `SSH_HOST`
- `SSH_USER`
- `SSH_PORT`
- `SSH_PRIVATE_KEY`
- `REDIS_HOST`
- `REDIS_PORT`
- `REDIS_PASSWORD` (비밀번호 미사용이면 빈 문자열 가능)
- `JWT_SECRET`
- `JWT_ISSUER`

### GitHub Variables (선택)

- `LISTENER_IMAGE_NAME` (기본: `<dockerhub-user>/finvibe-websocket-listener`)
- `LISTENER_CONTAINER_NAME` (기본: `finvibe-websocket-listener`)
- `LISTENER_PORT` (기본: `8090`)
- `LISTENER_LOG_DIR_HOST` (기본: `/var/log/finvibe-websocket-listener`)
- `PRIMARY_DOCKER_NETWORK` (기본: `infra_bridge`)
- `SECONDARY_DOCKER_NETWORK` (기본: `monitoring_net`)
- `WS_ALLOWED_ORIGINS` (기본: `*`)
- `WS_AUTH_TIMEOUT_MS` (기본: `10000`)
- `WS_HEARTBEAT_INTERVAL_MS` (기본: `15000`)
- `WS_PONG_TIMEOUT_MS` (기본: `15000`)
- `WS_MAX_MISSED_PONGS` (기본: `2`)
- `WS_RENEW_INTERVAL_MS` (기본: `60000`)
- `WS_PRICE_TOPIC` (기본: `market:price-updated`)

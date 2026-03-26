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
- `LISTENER_CONTAINER_NAME` (기본: `finvibe-websocket-listener`, 기존 단일 컨테이너 정리용)
- `LISTENER_LOG_DIR_HOST` (기본: `/var/log/finvibe-websocket-listener`)
- `PRIMARY_DOCKER_NETWORK` (기본: `infra_bridge`)
- `SECONDARY_DOCKER_NETWORK` (기본: `monitoring_net`)
- `LISTENER_INTERNAL_LB_PORT` (기본: `18090`, Caddy가 붙는 내부 LB 포트)
- `INTERNAL_LB_NETWORK` (기본: `finvibe_ws_lb`, Nginx-Listener 내부 라우팅 네트워크)
- `LISTENER_REPLICAS` (기본: `2`)
- `LISTENER_JAVA_TOOL_OPTIONS` (기본: `-Xms512m -Xmx2g -XX:+UseG1GC`)
- `LISTENER_MEMORY_LIMIT` (기본: `2560m`, JVM heap 외 native/metaspace 여유 포함)
- `WS_ALLOWED_ORIGINS` (기본: `*`)
- `WS_AUTH_TIMEOUT_MS` (기본: `10000`)
- `WS_HEARTBEAT_INTERVAL_MS` (기본: `15000`)
- `WS_PONG_TIMEOUT_MS` (기본: `15000`)
- `WS_MAX_MISSED_PONGS` (기본: `2`)
- `WS_RENEW_INTERVAL_MS` (기본: `60000`)
- `WS_PRICE_TOPIC` (기본: `market:price-updated`)

## 배포 토폴로지 (Compose + Nginx)

- 외부 진입점: Caddy (`80/443`)
- 내부 LB: Nginx (`LISTENER_INTERNAL_LB_PORT`, 기본 `18090`)
- 앱: Listener 인스턴스 N개 (`LISTENER_REPLICAS`)

Nginx는 Compose 내부 `lb` 네트워크에만 붙고, Listener가 `lb` 네트워크로 Nginx와 통신합니다.
`PRIMARY_DOCKER_NETWORK`/`SECONDARY_DOCKER_NETWORK`는 Listener가 외부 인프라(예: Redis, Prometheus)와 통신할 때 사용합니다.

`/market/ws` 는 Caddy -> Nginx -> Listener(여러 인스턴스) 순서로 전달됩니다.

예시 Caddy 설정:

```caddy
your-domain.example.com {
    reverse_proxy /market/ws* 127.0.0.1:18090
}
```

배포 워크플로우는 `deploy/docker-compose.yml` 을 서버로 복사한 뒤 `docker compose up -d --scale listener=N` 으로 적용합니다.

배포 성공 판단 기준:

- Nginx 컨테이너가 running 상태
- Listener 컨테이너 수가 `LISTENER_REPLICAS` 와 일치
- 모든 Listener의 Docker healthcheck가 `healthy`
- Nginx 경유 `http://localhost:${LISTENER_INTERNAL_LB_PORT}/actuator/health` 응답은 추가 검증(실패해도 Listener가 healthy면 배포는 성공 처리)

package depth.finvibe.listener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "listener.websocket")
public record WebSocketProperties(
		String allowedOrigins,
		long authTimeoutMs,
		long heartbeatIntervalMs,
		long pongTimeoutMs,
		int maxMissedPongs,
		long renewIntervalMs,
		long reconnectJitterMinMs,
		long reconnectJitterMaxMs,
		long slowConsumerGraceMs,
		int eventDispatchParallelism,
		int eventDispatchQueueCapacity,
		int fanoutChunkSize,
		int fanoutChunkParallelism,
		int fanoutChunkQueueCapacity,
		int sendTimeLimitMs,
		int sendBufferSizeBytes,
		String sendOverflowStrategy,
		int sessionQueueCapacity,
		Boolean anonymousSubscribeEnabled
) {

	/**
	 * 비로그인(익명) 세션의 구독 허용 여부. 설정이 없으면 허용한다.
	 */
	public boolean isAnonymousSubscribeEnabled() {
		return anonymousSubscribeEnabled == null || anonymousSubscribeEnabled;
	}
}

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
		Boolean anonymousSubscribeEnabled,
		// 세션별로 보내지 못하고 쌓인 틱 한도. 넘으면 연결을 끊는다(#17 D24). 0이면 기본값.
		int sessionDataBacklogLimit,
		// 프레임 하나에 담을 최대 틱 수. 0이면 기본값.
		int sessionDataMaxItemsPerFrame
) {

	/**
	 * 비로그인(익명) 세션의 구독 허용 여부. 설정이 없으면 허용한다.
	 */
	public boolean isAnonymousSubscribeEnabled() {
		return anonymousSubscribeEnabled == null || anonymousSubscribeEnabled;
	}
}

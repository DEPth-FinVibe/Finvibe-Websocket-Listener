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
		long slowConsumerGraceMs,
		int eventDispatchParallelism,
		int eventDispatchQueueCapacity,
		int fanoutChunkSize,
		int fanoutChunkParallelism,
		int fanoutChunkQueueCapacity,
		int sendTimeLimitMs,
		int sendBufferSizeBytes,
		String sendOverflowStrategy,
		int sessionQueueCapacity
) {
}

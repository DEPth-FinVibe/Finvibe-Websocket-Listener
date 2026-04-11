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
		int eventDispatchParallelism,
		int fanoutChunkSize,
		int fanoutChunkParallelism,
		int sendTimeLimitMs,
		int sendBufferSizeBytes,
		String sendOverflowStrategy,
		int sessionQueueCapacity
) {
}

package depth.finvibe.listener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "listener.redis.topic")
public record RedisTopicsProperties(
		String currentPriceUpdated,
		int currentPriceUpdatedPartitionCount
) {
}

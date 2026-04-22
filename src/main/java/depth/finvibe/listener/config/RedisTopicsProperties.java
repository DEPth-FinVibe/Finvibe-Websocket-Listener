package depth.finvibe.listener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "listener.redis.topic")
public record RedisTopicsProperties(
		String currentPriceUpdated,
		int currentPriceUpdatedPartitionCount,
		String mode
) {
	public List<String> currentPriceUpdatedTopics() {
		List<String> topics = new ArrayList<>();
		String baseTopic = currentPriceUpdated;
		topics.add(baseTopic);

		if (currentPriceUpdatedPartitionCount <= 1) {
			return topics;
		}

		for (int partition = 0; partition < currentPriceUpdatedPartitionCount; partition++) {
			if (sharded()) {
				topics.add(baseTopic + ":{" + partition + "}");
			} else {
				topics.add(baseTopic + ":" + partition);
			}
		}

		return topics;
	}

	public boolean sharded() {
		return "sharded".equalsIgnoreCase(mode);
	}
}

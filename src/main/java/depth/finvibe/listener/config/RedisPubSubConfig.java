package depth.finvibe.listener.config;

import depth.finvibe.listener.redis.PriceEventRedisSubscriber;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.ArrayList;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class RedisPubSubConfig {

	private final RedisTopicsProperties redisTopicsProperties;

	@Bean
	public RedisMessageListenerContainer redisMessageListenerContainer(
			RedisConnectionFactory connectionFactory,
			PriceEventRedisSubscriber priceEventRedisSubscriber
	) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);

		for (ChannelTopic topic : currentPriceUpdatedTopics()) {
			container.addMessageListener(priceEventRedisSubscriber, topic);
		}
		return container;
	}

	private List<ChannelTopic> currentPriceUpdatedTopics() {
		List<ChannelTopic> topics = new ArrayList<>();
		String baseTopic = redisTopicsProperties.currentPriceUpdated();
		topics.add(ChannelTopic.of(baseTopic));

		int partitionCount = redisTopicsProperties.currentPriceUpdatedPartitionCount();
		if (partitionCount <= 1) {
			return topics;
		}

		for (int partition = 0; partition < partitionCount; partition++) {
			topics.add(ChannelTopic.of(baseTopic + ":" + partition));
		}

		return topics;
	}
}

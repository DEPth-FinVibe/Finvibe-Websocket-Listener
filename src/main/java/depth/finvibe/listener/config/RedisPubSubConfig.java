package depth.finvibe.listener.config;

import depth.finvibe.listener.redis.PriceEventRedisSubscriber;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
	@ConditionalOnProperty(prefix = "listener.redis.topic", name = "mode", havingValue = "classic", matchIfMissing = true)
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
		for (String topic : redisTopicsProperties.currentPriceUpdatedTopics()) {
			topics.add(ChannelTopic.of(topic));
		}
		return topics;
	}
}

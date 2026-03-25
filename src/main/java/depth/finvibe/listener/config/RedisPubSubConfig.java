package depth.finvibe.listener.config;

import depth.finvibe.listener.redis.PriceEventRedisSubscriber;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

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
		container.addMessageListener(
				priceEventRedisSubscriber,
				ChannelTopic.of(redisTopicsProperties.currentPriceUpdated())
		);
		return container;
	}
}

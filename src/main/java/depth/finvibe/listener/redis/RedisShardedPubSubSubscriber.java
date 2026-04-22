package depth.finvibe.listener.redis;

import depth.finvibe.listener.config.RedisTopicsProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.pubsub.RedisClusterPubSubAdapter;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "listener.redis.topic", name = "mode", havingValue = "sharded")
public class RedisShardedPubSubSubscriber {
	private static final Logger log = LoggerFactory.getLogger(RedisShardedPubSubSubscriber.class);

	private final LettuceConnectionFactory lettuceConnectionFactory;
	private final RedisTopicsProperties redisTopicsProperties;
	private final PriceEventRedisSubscriber priceEventRedisSubscriber;

	private StatefulRedisClusterPubSubConnection<String, String> pubSubConnection;

	@PostConstruct
	void start() {
		Object nativeClient = lettuceConnectionFactory.getRequiredNativeClient();
		if (!(nativeClient instanceof RedisClusterClient redisClusterClient)) {
			throw new IllegalStateException("Sharded pub/sub mode requires RedisClusterClient");
		}

		pubSubConnection = redisClusterClient.connectPubSub();
		pubSubConnection.addListener(new RedisClusterPubSubAdapter<>() {
			@Override
			public void smessage(RedisClusterNode node, String channel, String message) {
				priceEventRedisSubscriber.handle(message);
			}
		});

		for (String topic : redisTopicsProperties.currentPriceUpdatedTopics()) {
			pubSubConnection.sync().ssubscribe(topic);
			log.info("Subscribed sharded redis channel={}", topic);
		}
	}

	@PreDestroy
	void stop() {
		if (pubSubConnection != null) {
			pubSubConnection.close();
		}
	}
}

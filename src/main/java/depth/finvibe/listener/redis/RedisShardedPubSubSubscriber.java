package depth.finvibe.listener.redis;

import depth.finvibe.listener.config.RedisTopicsProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.pubsub.RedisClusterPubSubAdapter;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "listener.redis.topic", name = "mode", havingValue = "sharded")
public class RedisShardedPubSubSubscriber {
	private static final Logger log = LoggerFactory.getLogger(RedisShardedPubSubSubscriber.class);

	private final RedisTopicsProperties redisTopicsProperties;
	private final PriceEventRedisSubscriber priceEventRedisSubscriber;
	@Value("${spring.data.redis.cluster.nodes:${SPRING_DATA_REDIS_CLUSTER_NODES:${REDIS_CLUSTER_NODES:}}}")
	private String redisClusterNodes;
	@Value("${spring.data.redis.password:${REDIS_PASSWORD:}}")
	private String redisPassword;

	private RedisClusterClient clusterClient;
	private StatefulRedisClusterPubSubConnection<String, String> pubSubConnection;

	@PostConstruct
	void start() {
		if (redisClusterNodes == null || redisClusterNodes.isBlank()) {
			throw new IllegalStateException("Sharded pub/sub mode requires redis cluster nodes");
		}

		List<RedisURI> redisUris = Arrays.stream(redisClusterNodes.split(","))
				.map(String::trim)
				.filter(node -> !node.isEmpty())
				.map(this::toRedisUri)
				.toList();

		clusterClient = RedisClusterClient.create(redisUris);
		pubSubConnection = clusterClient.connectPubSub();
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
		if (clusterClient != null) {
			clusterClient.shutdown();
		}
	}

	private RedisURI toRedisUri(String node) {
		String[] parts = node.split(":", 2);
		if (parts.length != 2) {
			throw new IllegalArgumentException("Invalid redis cluster node: " + node);
		}

		RedisURI.Builder builder = RedisURI.builder()
				.withHost(parts[0])
				.withPort(Integer.parseInt(parts[1]))
				.withTimeout(Duration.ofSeconds(3));

		if (redisPassword != null && !redisPassword.isBlank()) {
			builder.withPassword(redisPassword.toCharArray());
		}

		return builder.build();
	}
}

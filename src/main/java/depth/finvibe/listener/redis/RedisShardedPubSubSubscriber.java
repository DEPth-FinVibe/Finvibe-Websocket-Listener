package depth.finvibe.listener.redis;

import depth.finvibe.listener.config.RedisTopicsProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.pubsub.RedisClusterPubSubAdapter;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.event.connection.ReconnectAttemptEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "listener.redis.topic", name = "mode", havingValue = "sharded")
public class RedisShardedPubSubSubscriber {
	private static final Logger log = LoggerFactory.getLogger(RedisShardedPubSubSubscriber.class);
	private static final long RESUBSCRIBE_DELAY_MILLIS = 1_000;

	private final RedisTopicsProperties redisTopicsProperties;
	private final PriceEventRedisSubscriber priceEventRedisSubscriber;
	private final MeterRegistry meterRegistry;
	@Value("${spring.data.redis.cluster.nodes:${SPRING_DATA_REDIS_CLUSTER_NODES:${REDIS_CLUSTER_NODES:}}}")
	private String redisClusterNodes;
	@Value("${spring.data.redis.password:${REDIS_PASSWORD:}}")
	private String redisPassword;

	private RedisClusterClient clusterClient;
	private StatefulRedisClusterPubSubConnection<String, String> pubSubConnection;
	private ScheduledExecutorService resubscribeScheduler;
	private Disposable connectionEvents;

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
		clusterClient.setOptions(ClusterClientOptions.builder()
				.topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
						.enableAllAdaptiveRefreshTriggers()
						.enablePeriodicRefresh(Duration.ofSeconds(30))
						.build())
				.build());
		pubSubConnection = clusterClient.connectPubSub();

		List<String> topics = redisTopicsProperties.currentPriceUpdatedTopics();
		Counter resubscribed = meterRegistry.counter("finvibe.ws.redis.resubscribed");
		resubscribeScheduler = Executors.newSingleThreadScheduledExecutor(
				Thread.ofPlatform().name("redis-resubscribe").daemon(true).factory());
		ShardedChannelResubscriber resubscriber = new ShardedChannelResubscriber(
				topics,
				clusterClient::refreshPartitions,
				// 채널마다 슬롯이 달라 하나씩 구독한다. 한 번에 보내면 CROSSSLOT으로 거절된다.
				channels -> channels.forEach(channel -> pubSubConnection.sync().ssubscribe(channel)),
				resubscribeScheduler,
				RESUBSCRIBE_DELAY_MILLIS,
				resubscribed::increment);

		pubSubConnection.addListener(new RedisClusterPubSubAdapter<>() {
			@Override
			public void smessage(RedisClusterNode node, String channel, String message) {
				priceEventRedisSubscriber.handle(message);
			}

			// 마스터가 복제본으로 내려가면 Redis가 그 노드의 샤드 구독을 해제한다.
			@Override
			public void sunsubscribed(RedisClusterNode node, String channel, long count) {
				resubscriber.request("sunsubscribed " + channel);
			}
		});

		for (String topic : topics) {
			pubSubConnection.sync().ssubscribe(topic);
			log.info("Subscribed sharded redis channel={}", topic);
		}

		// 파드가 재시작되면 Lettuce는 예전 노드에 다시 붙어 구독을 복원하는데, 그 노드는 이제 복제본일 수 있다.
		// 연결 활성화 이벤트는 토폴로지 갱신의 임시 연결에도 발생해 재구독이 끝없이 반복되므로, 끊긴 연결의 재연결 시도에만 반응한다.
		connectionEvents = clusterClient.getResources().eventBus().get()
				.filter(ReconnectAttemptEvent.class::isInstance)
				.subscribe(event -> resubscriber.request("reconnecting " + ((ReconnectAttemptEvent) event).remoteAddress()));
	}

	@PreDestroy
	void stop() {
		if (connectionEvents != null) {
			connectionEvents.dispose();
		}
		if (resubscribeScheduler != null) {
			resubscribeScheduler.shutdownNow();
		}
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

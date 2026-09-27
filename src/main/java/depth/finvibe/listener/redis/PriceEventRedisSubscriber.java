package depth.finvibe.listener.redis;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import depth.finvibe.listener.websocket.MarketEventIngressDispatcher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

@Component
public class PriceEventRedisSubscriber implements MessageListener {
	private static final Logger log = LoggerFactory.getLogger(PriceEventRedisSubscriber.class);

	private final ObjectMapper objectMapper;
	private final MarketEventIngressDispatcher marketEventIngressDispatcher;
	private final WebSocketMetrics webSocketMetrics;
	private final Executor ingressExecutor;

	public PriceEventRedisSubscriber(
			ObjectMapper objectMapper,
			MarketEventIngressDispatcher marketEventIngressDispatcher,
			WebSocketMetrics webSocketMetrics,
			@Qualifier("listenerPriceIngressExecutor") Executor ingressExecutor
	) {
		this.objectMapper = objectMapper;
		this.marketEventIngressDispatcher = marketEventIngressDispatcher;
		this.webSocketMetrics = webSocketMetrics;
		this.ingressExecutor = ingressExecutor;
	}

	@Override
	public void onMessage(Message message, byte[] pattern) {
		long arrivedAt = System.currentTimeMillis();
		String payload = new String(message.getBody(), StandardCharsets.UTF_8);
		handle(payload, arrivedAt);
	}

	public void handle(String payload) {
		handle(payload, System.currentTimeMillis());
	}

	public void handle(String payload, long arrivedAt) {
		ingressExecutor.execute(() -> processMessage(payload, arrivedAt));
	}

	private void processMessage(String payload, long arrivedAt) {
		JsonNode root;
		try {
			root = objectMapper.readTree(payload);
		} catch (Exception ex) {
			webSocketMetrics.redisEventFailed();
			log.warn("Failed to parse current-price redis payload.", ex);
			return;
		}
		// 모놀리식은 여러 틱을 배열 하나로 묶어 발행한다. 배열이 아니면 이전 형식(틱 1건)이다.
		if (root.isArray()) {
			for (JsonNode event : root) {
				processEvent(event, arrivedAt);
			}
			return;
		}
		processEvent(root, arrivedAt);
	}

	private void processEvent(JsonNode event, long arrivedAt) {
		try {
			long consumedAt = System.currentTimeMillis();
			Long sourceTs = longOrNull(event.path("ts"));
			Long publishedAt = longOrNull(event.path("publishedAt"));
			if (event instanceof tools.jackson.databind.node.ObjectNode objectNode) {
				if (sourceTs != null) {
					objectNode.put("consumedAt", consumedAt);
				}
				objectNode.put("arrivedAt", arrivedAt);
			}
			webSocketMetrics.redisEventConsumed();
			if (sourceTs != null) {
				webSocketMetrics.redisEventSourceToConsumeLatency(consumedAt - sourceTs);
			}
			if (publishedAt != null) {
				webSocketMetrics.redisEventPublishToArrivalLatency(arrivedAt - publishedAt);
				webSocketMetrics.redisEventArrivalToConsumeLatency(consumedAt - arrivedAt);
			}
			marketEventIngressDispatcher.submit(event);
		} catch (Exception ex) {
			webSocketMetrics.redisEventFailed();
			log.warn("Failed to consume current-price redis payload.", ex);
		}
	}

	private Long longOrNull(JsonNode node) {
		if (node == null || node.isNull() || !node.isNumber()) {
			return null;
		}
		return node.asLong();
	}
}

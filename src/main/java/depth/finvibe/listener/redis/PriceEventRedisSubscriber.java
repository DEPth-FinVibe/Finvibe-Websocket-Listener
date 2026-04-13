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
	private final Executor virtualTaskExecutor;

	public PriceEventRedisSubscriber(
			ObjectMapper objectMapper,
			MarketEventIngressDispatcher marketEventIngressDispatcher,
			WebSocketMetrics webSocketMetrics,
			@Qualifier("listenerVirtualTaskExecutor") Executor virtualTaskExecutor
	) {
		this.objectMapper = objectMapper;
		this.marketEventIngressDispatcher = marketEventIngressDispatcher;
		this.webSocketMetrics = webSocketMetrics;
		this.virtualTaskExecutor = virtualTaskExecutor;
	}

	@Override
	public void onMessage(Message message, byte[] pattern) {
		String payload = new String(message.getBody(), StandardCharsets.UTF_8);
		virtualTaskExecutor.execute(() -> processMessage(payload));
	}

	private void processMessage(String payload) {
		try {
			JsonNode event = objectMapper.readTree(payload);
			long consumedAt = System.currentTimeMillis();
			Long sourceTs = longOrNull(event.path("ts"));
			webSocketMetrics.redisEventConsumed();
			if (sourceTs != null) {
				webSocketMetrics.redisEventSourceToConsumeLatency(consumedAt - sourceTs);
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

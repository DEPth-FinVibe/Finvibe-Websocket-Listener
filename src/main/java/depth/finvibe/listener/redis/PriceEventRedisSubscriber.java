package depth.finvibe.listener.redis;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import depth.finvibe.listener.websocket.MarketEventBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriceEventRedisSubscriber implements MessageListener {

	private final ObjectMapper objectMapper;
	private final MarketEventBroadcaster marketEventBroadcaster;
	private final WebSocketMetrics webSocketMetrics;

	@Override
	public void onMessage(Message message, byte[] pattern) {
		String payload = new String(message.getBody(), StandardCharsets.UTF_8);
		try {
			JsonNode event = objectMapper.readTree(payload);
			long consumedAt = System.currentTimeMillis();
			Long sourceTs = longOrNull(event.path("ts"));
			webSocketMetrics.redisEventConsumed();
			if (sourceTs != null) {
				webSocketMetrics.redisEventSourceToConsumeLatency(consumedAt - sourceTs);
			}
			marketEventBroadcaster.broadcastCurrentPrice(event);
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

package depth.finvibe.listener.websocket;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketEventBroadcaster {

	private final SessionRegistry sessionRegistry;
	private final ObjectMapper objectMapper;
	private final WebSocketMetrics webSocketMetrics;

	public void broadcastCurrentPrice(JsonNode currentPriceEvent) {
		Long stockId = longOrNull(currentPriceEvent.path("stockId"));
		if (stockId == null) {
			return;
		}

		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("type", "event");
		payload.put("topic", "quote:" + stockId);
		payload.put("ts", System.currentTimeMillis());

		ObjectNode data = payload.putObject("data");
		data.put("stockId", stockId);
		copyNumber(currentPriceEvent, data, "close", "price");
		copyNumber(currentPriceEvent, data, "prevDayChangePct", "prevDayChangePct");
		copyNumber(currentPriceEvent, data, "volume", "volume");

		String serialized;
		try {
			serialized = objectMapper.writeValueAsString(payload);
		} catch (Exception ex) {
			log.warn("Failed to serialize event payload for stockId={}", stockId, ex);
			return;
		}

		TextMessage message = new TextMessage(serialized);
		webSocketMetrics.eventBroadcasted();
		for (ClientSession clientSession : sessionRegistry.getSubscribers(stockId)) {
			WebSocketSession webSocketSession = clientSession.getWebSocketSession();
			if (!webSocketSession.isOpen() || !clientSession.isAuthenticated()) {
				continue;
			}
			try {
				webSocketSession.sendMessage(message);
				webSocketMetrics.eventDelivered();
			} catch (Exception ex) {
				webSocketMetrics.eventDeliveryFailed();
				log.debug("Failed to deliver event. sessionId={}, stockId={}", webSocketSession.getId(), stockId);
			}
		}
	}

	private Long longOrNull(JsonNode node) {
		if (node == null || node.isNull() || !node.isNumber()) {
			return null;
		}
		return node.asLong();
	}

	private void copyNumber(JsonNode source, ObjectNode target, String sourceField, String targetField) {
		JsonNode value = source.path(sourceField);
		if (value == null || value.isMissingNode() || value.isNull()) {
			return;
		}
		if (value.isNumber()) {
			target.set(targetField, value);
		}
	}
}

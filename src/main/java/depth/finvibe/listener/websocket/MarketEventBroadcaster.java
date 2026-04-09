package depth.finvibe.listener.websocket;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class MarketEventBroadcaster {
	private static final Logger log = LoggerFactory.getLogger(MarketEventBroadcaster.class);

	private final SessionRegistry sessionRegistry;
	private final ObjectMapper objectMapper;
	private final WebSocketMetrics webSocketMetrics;

	public MarketEventBroadcaster(
			SessionRegistry sessionRegistry,
			ObjectMapper objectMapper,
			WebSocketMetrics webSocketMetrics
	) {
		this.sessionRegistry = sessionRegistry;
		this.objectMapper = objectMapper;
		this.webSocketMetrics = webSocketMetrics;
	}

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

			boolean accepted = clientSession.enqueueSessionTask(() -> deliverEvent(webSocketSession, message, stockId));
			if (!accepted) {
				webSocketMetrics.sessionQueueOverflow("broadcast_event_drop");
				webSocketMetrics.eventDeliveryFailed("queue_overflow_drop");
			}
		}
	}

	private void deliverEvent(WebSocketSession webSocketSession, TextMessage message, long stockId) {
		try {
			webSocketSession.sendMessage(message);
			webSocketMetrics.eventDelivered();
		} catch (SessionLimitExceededException ex) {
			webSocketMetrics.eventDeliveryFailed();
			webSocketMetrics.eventDeliveryFailed("buffer_limit_exceeded");
			safeClose(webSocketSession, CloseStatus.SESSION_NOT_RELIABLE.withReason("send_buffer_exceeded"), "broadcast_buffer_limit");
		} catch (Exception ex) {
			webSocketMetrics.eventDeliveryFailed();
			webSocketMetrics.eventDeliveryFailed(classifyDeliveryFailure(ex, webSocketSession));
			webSocketMetrics.sessionTaskFailure("broadcast_event");
			log.debug("Failed to deliver event. sessionId={}, stockId={}", webSocketSession.getId(), stockId, ex);
		}
	}

	private String classifyDeliveryFailure(Exception ex, WebSocketSession session) {
		String message = ex.getMessage();
		if (message != null && message.contains("TEXT_PARTIAL_WRITING")) {
			return "concurrent_write";
		}

		if (!session.isOpen()) {
			return "session_closed";
		}

		if (ex instanceof IllegalStateException) {
			return "illegal_state";
		}

		return "send_exception";
	}

	private void safeClose(WebSocketSession session, CloseStatus closeStatus, String source) {
		try {
			if (!session.isOpen()) {
				return;
			}
			webSocketMetrics.closeInitiated(source, closeStatus.getCode());
			session.close(closeStatus);
		} catch (Exception ex) {
			log.debug("Failed to close websocket session after delivery failure. sessionId={}", session.getId(), ex);
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

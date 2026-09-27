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

import java.util.List;

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

	/**
	 * 틱 하나를 구독 세션마다 대기열에 덧붙인다. 합치거나 버리지 않는다(#17 D19).
	 * 틱 JSON은 한 번만 만들고, 세션은 쌓인 틱을 {@code events} 프레임 하나로 묶어 보낸다.
	 */
	public void broadcastCurrentPrice(JsonNode currentPriceEvent) {
		Long stockId = longOrNull(currentPriceEvent.path("stockId"));
		if (stockId == null) {
			return;
		}
		long broadcastedAt = System.currentTimeMillis();
		Long sourceTs = longOrNull(currentPriceEvent.path("ts"));
		Long consumedAt = longOrNull(currentPriceEvent.path("consumedAt"));

		ObjectNode item = objectMapper.createObjectNode();
		item.put("topic", "quote:" + stockId);
		item.put("ts", broadcastedAt);

		ObjectNode data = item.putObject("data");
		data.put("stockId", stockId);
		if (sourceTs != null) {
			data.put("eventTs", sourceTs);
		}
		data.put("emittedAt", broadcastedAt);
		copyNumber(currentPriceEvent, data, "close", "price");
		copyNumber(currentPriceEvent, data, "prevDayChangePct", "prevDayChangePct");
		copyNumber(currentPriceEvent, data, "volume", "volume");
		copyNumber(currentPriceEvent, data, "value", "value");
		// 클라이언트가 스냅샷·실시간 틱·수익률 근거 중 최신 값을 버전으로 고른다.
		copyNumber(currentPriceEvent, data, "priceVersion", "priceVersion");

		String serialized;
		try {
			serialized = objectMapper.writeValueAsString(item);
		} catch (Exception ex) {
			log.warn("Failed to serialize event payload for stockId={}", stockId, ex);
			return;
		}

		webSocketMetrics.eventBroadcasted();
		if (consumedAt != null) {
			webSocketMetrics.eventConsumeToBroadcastLatency(broadcastedAt - consumedAt);
		}
		for (ClientSession clientSession : sessionRegistry.getSubscribers(stockId)) {
			WebSocketSession webSocketSession = clientSession.getWebSocketSession();
			if (!webSocketSession.isOpen() || !clientSession.isEstablished()) {
				continue;
			}
			ClientSession.DataEnqueueResult result = clientSession.enqueueData(serialized, this::deliverFrame);
			if (result == ClientSession.DataEnqueueResult.BACKLOG_EXCEEDED) {
				webSocketMetrics.sessionBacklogExceeded();
				safeClose(webSocketSession, CloseStatus.SESSION_NOT_RELIABLE.withReason("backlog_exceeded"), "broadcast_backlog_exceeded");
			}
		}
		webSocketMetrics.eventSourceToBroadcastLatency(System.currentTimeMillis() - broadcastedAt);
	}

	void deliverFrame(ClientSession clientSession, List<String> items) {
		WebSocketSession webSocketSession = clientSession.getWebSocketSession();
		long writeStartedAt = System.currentTimeMillis();
		StringBuilder frame = new StringBuilder(64 + items.size() * 192)
				.append("{\"type\":\"events\",\"ts\":").append(writeStartedAt).append(",\"items\":[");
		for (int i = 0; i < items.size(); i++) {
			if (i > 0) {
				frame.append(',');
			}
			frame.append(items.get(i));
		}
		frame.append("]}");
		TextMessage message = new TextMessage(frame.toString());
		try {
			webSocketSession.sendMessage(message);
			long deliveredAt = System.currentTimeMillis();
			clientSession.markOutboundSent(deliveredAt);
			webSocketMetrics.eventsDelivered(items.size());
			webSocketMetrics.dataFrameSent(items.size());
			webSocketMetrics.outboundDataWriteDuration(deliveredAt - writeStartedAt);
			webSocketMetrics.outboundDataBytesSent(message.getPayloadLength());
		} catch (SessionLimitExceededException ex) {
			webSocketMetrics.eventDeliveryFailed();
			webSocketMetrics.eventDeliveryFailed("buffer_limit_exceeded");
			safeClose(webSocketSession, CloseStatus.SESSION_NOT_RELIABLE.withReason("send_buffer_exceeded"), "broadcast_buffer_limit");
		} catch (Exception ex) {
			webSocketMetrics.eventDeliveryFailed();
			webSocketMetrics.eventDeliveryFailed(classifyDeliveryFailure(ex, webSocketSession));
			webSocketMetrics.sessionTaskFailure("broadcast_event");
			log.debug("Failed to deliver event frame. sessionId={}, items={}", webSocketSession.getId(), items.size(), ex);
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

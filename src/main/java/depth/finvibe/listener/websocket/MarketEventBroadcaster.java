package depth.finvibe.listener.websocket;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import depth.finvibe.listener.config.WebSocketProperties;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.Executor;

@Component
public class MarketEventBroadcaster {
	private static final Logger log = LoggerFactory.getLogger(MarketEventBroadcaster.class);

	private final SessionRegistry sessionRegistry;
	private final ObjectMapper objectMapper;
	private final WebSocketMetrics webSocketMetrics;
	private final WebSocketProperties webSocketProperties;
	private final Executor fanoutChunkExecutor;

	public MarketEventBroadcaster(
			SessionRegistry sessionRegistry,
			ObjectMapper objectMapper,
			WebSocketMetrics webSocketMetrics,
			WebSocketProperties webSocketProperties,
			@Qualifier("listenerFanoutChunkExecutor") Executor fanoutChunkExecutor
	) {
		this.sessionRegistry = sessionRegistry;
		this.objectMapper = objectMapper;
		this.webSocketMetrics = webSocketMetrics;
		this.webSocketProperties = webSocketProperties;
		this.fanoutChunkExecutor = fanoutChunkExecutor;
	}

	public void broadcastCurrentPrice(JsonNode currentPriceEvent) {
		Long stockId = longOrNull(currentPriceEvent.path("stockId"));
		if (stockId == null) {
			return;
		}
		long broadcastedAt = System.currentTimeMillis();
		Long sourceTs = longOrNull(currentPriceEvent.path("ts"));
		Long consumedAt = longOrNull(currentPriceEvent.path("consumedAt"));

		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("type", "event");
		payload.put("topic", "quote:" + stockId);
		payload.put("ts", broadcastedAt);

		ObjectNode data = payload.putObject("data");
		data.put("stockId", stockId);
		if (sourceTs != null) {
			data.put("eventTs", sourceTs);
		}
		data.put("emittedAt", broadcastedAt);
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
		if (sourceTs != null) {
			webSocketMetrics.eventSourceToBroadcastLatency(broadcastedAt - sourceTs);
		}
		if (consumedAt != null) {
			webSocketMetrics.eventConsumeToBroadcastLatency(broadcastedAt - consumedAt);
		}
		var subscribers = sessionRegistry.getSubscribers(stockId);
		int chunkSize = Math.max(1, webSocketProperties.fanoutChunkSize());
		int chunkParallelism = Math.max(1, webSocketProperties.fanoutChunkParallelism());
		if (subscribers.size() <= chunkSize || chunkParallelism == 1) {
			enqueueChunk(subscribers, 0, subscribers.size(), message, stockId, sourceTs);
			return;
		}
		for (int start = 0; start < subscribers.size(); start += chunkSize) {
			int end = Math.min(start + chunkSize, subscribers.size());
			int chunkStart = start;
			int chunkEnd = end;
			fanoutChunkExecutor.execute(() -> enqueueChunk(subscribers, chunkStart, chunkEnd, message, stockId, sourceTs));
		}
	}

	private void enqueueChunk(List<ClientSession> subscribers, int start, int end, TextMessage message, long stockId, Long sourceTs) {
		for (int index = start; index < end; index++) {
			ClientSession clientSession = subscribers.get(index);
			WebSocketSession webSocketSession = clientSession.getWebSocketSession();
			if (!webSocketSession.isOpen() || !clientSession.isAuthenticated()) {
				continue;
			}
			if (sourceTs != null) {
				webSocketMetrics.eventSourceToEnqueueLatency(System.currentTimeMillis() - sourceTs);
			}
			webSocketMetrics.eventBroadcastToEnqueueLatency(System.currentTimeMillis() - broadcastedAt(message));

			boolean replaced = clientSession.upsertLatestDataTask(
					"quote:" + stockId,
					() -> deliverEvent(webSocketSession, message, stockId, sourceTs, System.currentTimeMillis())
			);
			if (replaced) {
				webSocketMetrics.eventOutboundCoalesced();
				webSocketMetrics.eventOutboundStaleDrop();
			}
		}
	}

	private void deliverEvent(WebSocketSession webSocketSession, TextMessage message, long stockId, Long sourceTs, long enqueuedAt) {
		try {
			long writeStartedAt = System.currentTimeMillis();
			webSocketMetrics.outboundDataEnqueueToWriteStartLatency(writeStartedAt - enqueuedAt);
			webSocketSession.sendMessage(message);
			ClientSession clientSession = sessionRegistry.get(webSocketSession.getId());
			if (clientSession != null) {
				clientSession.markOutboundSent(System.currentTimeMillis());
			}
			webSocketMetrics.eventDelivered();
			webSocketMetrics.outboundDataWriteDuration(System.currentTimeMillis() - writeStartedAt);
			webSocketMetrics.outboundDataBytesSent(message.getPayloadLength());
			if (sourceTs != null) {
				long latencyMs = System.currentTimeMillis() - sourceTs;
				webSocketMetrics.eventSourceToDeliveryLatency(latencyMs);
				webSocketMetrics.outboundDataDeliveryLatency(latencyMs);
			}
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

	private long broadcastedAt(TextMessage message) {
		try {
			JsonNode root = objectMapper.readTree(message.getPayload());
			JsonNode ts = root.path("ts");
			if (ts.isNumber()) {
				return ts.asLong();
			}
		} catch (Exception ignored) {
		}
		return System.currentTimeMillis();
	}

}

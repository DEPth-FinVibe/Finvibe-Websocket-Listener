package depth.finvibe.listener.websocket;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import depth.finvibe.listener.redis.CurrentWatcherRedisRepository;
import depth.finvibe.listener.security.JwtTokenVerifier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class MarketQuoteWebSocketHandler extends TextWebSocketHandler {
	private static final Logger log = LoggerFactory.getLogger(MarketQuoteWebSocketHandler.class);

	private final SessionRegistry sessionRegistry;
	private final JwtTokenVerifier jwtTokenVerifier;
	private final CurrentWatcherRedisRepository currentWatcherRedisRepository;
	private final WebSocketMetrics webSocketMetrics;
	private final ObjectMapper objectMapper;

	public MarketQuoteWebSocketHandler(
			SessionRegistry sessionRegistry,
			JwtTokenVerifier jwtTokenVerifier,
			CurrentWatcherRedisRepository currentWatcherRedisRepository,
			WebSocketMetrics webSocketMetrics,
			ObjectMapper objectMapper
	) {
		this.sessionRegistry = sessionRegistry;
		this.jwtTokenVerifier = jwtTokenVerifier;
		this.currentWatcherRedisRepository = currentWatcherRedisRepository;
		this.webSocketMetrics = webSocketMetrics;
		this.objectMapper = objectMapper;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		sessionRegistry.add(session, System.currentTimeMillis());
		webSocketMetrics.connectionOpened();
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
		JsonNode payload;
		try {
			payload = objectMapper.readTree(message.getPayload());
		} catch (Exception ex) {
			webSocketMetrics.invalidMessage("invalid_json");
			enqueueSessionTask(session, "inbound_invalid_json", () -> sendError(session, "BAD_REQUEST", "Invalid JSON payload."));
			return;
		}

		String type = payload.path("type").asText("");
			switch (type) {
			case "auth" -> enqueueSessionTask(session, "inbound_auth", () -> handleAuth(session, payload));
			case "subscribe" -> enqueueSessionTask(session, "inbound_subscribe", () -> handleSubscribe(session, payload));
			case "unsubscribe" -> enqueueSessionTask(session, "inbound_unsubscribe", () -> handleUnsubscribe(session, payload));
			case "pong" -> handlePong(session);
			default -> {
				webSocketMetrics.invalidMessage("unsupported_type");
				enqueueSessionTask(session, "inbound_unsupported_type", () -> sendError(session, "BAD_REQUEST", "Unsupported message type."));
			}
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		webSocketMetrics.connectionClosed("closed_" + closeCode(status));
		webSocketMetrics.connectionClosedCode(closeCodeInt(status), closeLabel(status));
		removeAndPublishUnregister(session.getId());
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		webSocketMetrics.connectionClosed("transport_error");
		webSocketMetrics.connectionClosedCode(1011, "transport_error");
		removeAndPublishUnregister(session.getId());
	}

	private void handleAuth(WebSocketSession webSocketSession, JsonNode payload) throws Exception {
		webSocketMetrics.authAttempt();
		String token = payload.path("token").asText("");
		if (token.isBlank()) {
			webSocketMetrics.authFailure("token_missing");
			sendErrorAndClose(webSocketSession, "UNAUTHORIZED", "Token is required.");
			return;
		}

		UUID userId;
		try {
			userId = jwtTokenVerifier.verifyAndGetUserId(token);
		} catch (Exception ex) {
			webSocketMetrics.authFailure("invalid_token");
			sendErrorAndClose(webSocketSession, "UNAUTHORIZED", "Invalid token.");
			return;
		}

		if (!sessionRegistry.authenticate(webSocketSession.getId(), userId)) {
			webSocketMetrics.authFailure("session_not_found");
			sendErrorAndClose(webSocketSession, "UNAUTHORIZED", "Session not found.");
			return;
		}
		webSocketMetrics.authSuccess();

		ObjectNode authAck = objectMapper.createObjectNode();
		authAck.put("type", "auth");
		authAck.put("ok", true);
		authAck.put("ts", System.currentTimeMillis());
		sendJson(webSocketSession, authAck);
	}

	private void handleSubscribe(WebSocketSession webSocketSession, JsonNode payload) throws Exception {
		webSocketMetrics.subscribeRequest();
		ClientSession clientSession = sessionRegistry.get(webSocketSession.getId());
		if (clientSession == null || !clientSession.isAuthenticated()) {
			sendError(webSocketSession, "UNAUTHORIZED", "Auth is required before subscribe.");
			return;
		}

		List<String> subscribed = new ArrayList<>();
		List<String> rejected = new ArrayList<>();
		for (Long stockId : parseTopics(payload.path("topics"), rejected)) {
			boolean added = sessionRegistry.subscribe(webSocketSession.getId(), stockId);
			subscribed.add("quote:" + stockId);
			if (added) {
				webSocketMetrics.subscriptionAdded();
				currentWatcherRedisRepository.save(clientSession.getUserId(), stockId);
			}
		}

		ObjectNode subscribeAck = objectMapper.createObjectNode();
		subscribeAck.put("type", "subscribe");
		subscribeAck.put("request_id", payload.path("request_id").asText(""));
		ArrayNode subscribedNode = subscribeAck.putArray("subscribed");
		subscribed.forEach(subscribedNode::add);
		ArrayNode rejectedNode = subscribeAck.putArray("rejected");
		rejected.forEach(rejectedNode::add);
		sendJson(webSocketSession, subscribeAck);
	}

	private void handleUnsubscribe(WebSocketSession webSocketSession, JsonNode payload) throws Exception {
		webSocketMetrics.unsubscribeRequest();
		ClientSession clientSession = sessionRegistry.get(webSocketSession.getId());
		if (clientSession == null || !clientSession.isAuthenticated()) {
			sendError(webSocketSession, "UNAUTHORIZED", "Auth is required before unsubscribe.");
			return;
		}

		List<String> unsubscribed = new ArrayList<>();
		List<String> rejected = new ArrayList<>();
		for (Long stockId : parseTopics(payload.path("topics"), rejected)) {
			boolean removed = sessionRegistry.unsubscribe(webSocketSession.getId(), stockId);
			if (removed) {
				webSocketMetrics.subscriptionsRemoved(1);
				currentWatcherRedisRepository.remove(clientSession.getUserId(), stockId);
			}
			unsubscribed.add("quote:" + stockId);
		}

		ObjectNode unsubscribeAck = objectMapper.createObjectNode();
		unsubscribeAck.put("type", "unsubscribe");
		unsubscribeAck.put("request_id", payload.path("request_id").asText(""));
		ArrayNode unsubscribedNode = unsubscribeAck.putArray("unsubscribed");
		unsubscribed.forEach(unsubscribedNode::add);
		ArrayNode rejectedNode = unsubscribeAck.putArray("rejected");
		rejected.forEach(rejectedNode::add);
		sendJson(webSocketSession, unsubscribeAck);
	}

	private void handlePong(WebSocketSession webSocketSession) {
		ClientSession clientSession = sessionRegistry.get(webSocketSession.getId());
		if (clientSession != null) {
			webSocketMetrics.pongReceived();
			clientSession.markPongReceived(System.currentTimeMillis());
		}
	}

	private List<Long> parseTopics(JsonNode topicsNode, List<String> rejected) {
		if (topicsNode == null || !topicsNode.isArray()) {
			return List.of();
		}

		List<Long> stockIds = new ArrayList<>();
		for (JsonNode node : topicsNode) {
			String topic = node.asText("");
			Long stockId = parseStockId(topic);
			if (stockId == null) {
				rejected.add(topic);
				continue;
			}
			stockIds.add(stockId);
		}
		return stockIds;
	}

	private Long parseStockId(String topic) {
		if (topic == null || !topic.startsWith("quote:")) {
			return null;
		}
		String raw = topic.substring("quote:".length());
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private void sendError(WebSocketSession session, String code, String message) throws Exception {
		ObjectNode errorPayload = objectMapper.createObjectNode();
		errorPayload.put("type", "error");
		errorPayload.put("code", code);
		errorPayload.put("message", message);
		sendJson(session, errorPayload);
	}

	private void sendErrorAndClose(WebSocketSession session, String code, String message) throws Exception {
		sendError(session, code, message);
		safeClose(resolveManagedSession(session), CloseStatus.POLICY_VIOLATION.withReason("auth_error"), "handler_auth_error");
	}

	private void removeAndPublishUnregister(String sessionId) {
		SessionRegistry.RemovedSession removedSession = sessionRegistry.remove(sessionId);
		if (removedSession.userId() == null || removedSession.subscribedStockIds().isEmpty()) {
			return;
		}
		webSocketMetrics.subscriptionsRemoved(removedSession.subscribedStockIds().size());

		for (Long stockId : removedSession.subscribedStockIds()) {
			currentWatcherRedisRepository.remove(removedSession.userId(), stockId);
		}
	}

	private String closeCode(CloseStatus status) {
		if (status == null) {
			return "-1";
		}
		return String.valueOf(status.getCode());
	}

	private int closeCodeInt(CloseStatus status) {
		if (status == null) {
			return -1;
		}
		return status.getCode();
	}

	private String closeLabel(CloseStatus status) {
		int code = closeCodeInt(status);
		return switch (code) {
			case 1000 -> "normal";
			case 1008 -> "policy_violation";
			case 1011 -> "server_error";
			case 4500 -> "session_not_reliable";
			default -> "other";
		};
	}

	private void sendJson(WebSocketSession inboundSession, JsonNode payload) throws Exception {
		WebSocketSession managedSession = resolveManagedSession(inboundSession);
		managedSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
	}

	private WebSocketSession resolveManagedSession(WebSocketSession fallbackSession) {
		ClientSession clientSession = sessionRegistry.get(fallbackSession.getId());
		if (clientSession == null) {
			return fallbackSession;
		}
		return clientSession.getWebSocketSession();
	}

	private void enqueueSessionTask(WebSocketSession session, String stage, QueueTask task) {
		boolean accepted = sessionRegistry.enqueueSessionTask(session.getId(), () -> {
			try {
				task.run();
			} catch (Exception ex) {
				webSocketMetrics.sessionTaskFailure(stage);
				log.debug("Session task failed. stage={}, sessionId={}", stage, session.getId(), ex);
				safeClose(resolveManagedSession(session), CloseStatus.SERVER_ERROR.withReason("session_task_failed"), "handler_task_failed");
			}
		});

		if (!accepted) {
			if ("inbound_auth".equals(stage)) {
				try {
					task.run();
					return;
				} catch (Exception ex) {
					webSocketMetrics.sessionTaskFailure("inbound_auth_fallback");
					log.debug("Auth fallback task failed. sessionId={}", session.getId(), ex);
					safeClose(resolveManagedSession(session), CloseStatus.SERVER_ERROR.withReason("auth_fallback_failed"), "handler_auth_fallback_failed");
					return;
				}
			}

			webSocketMetrics.sessionQueueOverflow(stage);
			safeClose(resolveManagedSession(session), CloseStatus.SESSION_NOT_RELIABLE.withReason("session_queue_overflow"), "handler_queue_overflow");
		}
	}

	private void safeClose(WebSocketSession session, CloseStatus status, String source) {
		try {
			if (!session.isOpen()) {
				return;
			}
			webSocketMetrics.closeInitiated(source, status.getCode());
			session.close(status);
		} catch (Exception ex) {
			log.debug("Failed to close session. sessionId={}", session.getId(), ex);
		}
	}

	@FunctionalInterface
	private interface QueueTask {
		void run() throws Exception;
	}
}

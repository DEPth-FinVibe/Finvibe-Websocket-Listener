package depth.finvibe.listener.websocket;

import depth.finvibe.listener.config.WebSocketProperties;
import depth.finvibe.listener.metrics.WebSocketMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class WebSocketSweepScheduler {
	private static final Logger log = LoggerFactory.getLogger(WebSocketSweepScheduler.class);

	private final SessionRegistry sessionRegistry;
	private final WebSocketProperties webSocketProperties;
	private final WebSocketMetrics webSocketMetrics;
	private final ObjectMapper objectMapper;

	public WebSocketSweepScheduler(
			SessionRegistry sessionRegistry,
			WebSocketProperties webSocketProperties,
			WebSocketMetrics webSocketMetrics,
			ObjectMapper objectMapper
	) {
		this.sessionRegistry = sessionRegistry;
		this.webSocketProperties = webSocketProperties;
		this.webSocketMetrics = webSocketMetrics;
		this.objectMapper = objectMapper;
	}

	@Scheduled(fixedDelayString = "${listener.websocket.heartbeat-interval-ms:15000}")
	public void sweepConnections() {
		long now = System.currentTimeMillis();

		for (ClientSession clientSession : sessionRegistry.getAllSessions()) {
			boolean accepted = clientSession.enqueueSessionTask(() -> sweepSingleSession(clientSession, now));
			if (!accepted) {
				webSocketMetrics.sessionQueueOverflow("heartbeat_sweep");
				safeClose(
						clientSession.getWebSocketSession(),
						CloseStatus.SESSION_NOT_RELIABLE.withReason("session_queue_overflow"),
						"sweep_queue_overflow"
				);
			}
		}
	}

	private void sweepSingleSession(ClientSession clientSession, long now) {
		WebSocketSession webSocketSession = clientSession.getWebSocketSession();
		if (!webSocketSession.isOpen()) {
			safeClose(webSocketSession, CloseStatus.NORMAL.withReason("session_not_open"), "sweep_not_open");
			return;
		}

		if (!clientSession.isAuthenticated()) {
			if (now - clientSession.getConnectedAtEpochMs() > webSocketProperties.authTimeoutMs()) {
				safeClose(webSocketSession, CloseStatus.POLICY_VIOLATION.withReason("auth_timeout"), "sweep_auth_timeout");
			}
			return;
		}

		if (clientSession.isPingPending()) {
			long pingElapsed = now - clientSession.getLastPingAtEpochMs();
			if (pingElapsed > webSocketProperties.pongTimeoutMs()) {
				webSocketMetrics.pingTimeout();
				int missed = clientSession.incrementMissedPong();
				if (missed >= webSocketProperties.maxMissedPongs()) {
					safeClose(
							webSocketSession,
							CloseStatus.SESSION_NOT_RELIABLE.withReason("pong_timeout"),
							"sweep_pong_timeout"
					);
					return;
				}
			}
		}

		sendPing(webSocketSession, clientSession, now);
	}

	private void sendPing(WebSocketSession webSocketSession, ClientSession clientSession, long now) {
		ObjectNode pingPayload = objectMapper.createObjectNode();
		pingPayload.put("type", "ping");
		pingPayload.put("ts", now);

		try {
			webSocketSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(pingPayload)));
			webSocketMetrics.pingSent();
			clientSession.markPingSent(now);
		} catch (Exception ex) {
			safeClose(webSocketSession, CloseStatus.SERVER_ERROR.withReason("ping_send_failed"), "sweep_ping_send_failed");
		}
	}

	private void safeClose(WebSocketSession webSocketSession, CloseStatus status, String source) {
		try {
			webSocketMetrics.closeInitiated(source, status.getCode());
			webSocketSession.close(status);
		} catch (Exception ex) {
			log.debug("Failed to close websocket session. sessionId={}", webSocketSession.getId());
		}
	}
}

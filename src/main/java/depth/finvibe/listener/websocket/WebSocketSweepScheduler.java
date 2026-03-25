package depth.finvibe.listener.websocket;

import depth.finvibe.listener.config.WebSocketProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketSweepScheduler {

	private final SessionRegistry sessionRegistry;
	private final WebSocketProperties webSocketProperties;
	private final ObjectMapper objectMapper;

	@Scheduled(fixedDelayString = "${listener.websocket.heartbeat-interval-ms:15000}")
	public void sweepConnections() {
		long now = System.currentTimeMillis();

		for (ClientSession clientSession : sessionRegistry.getAllSessions()) {
			WebSocketSession webSocketSession = clientSession.getWebSocketSession();
			if (!webSocketSession.isOpen()) {
				safeClose(webSocketSession, CloseStatus.NORMAL);
				continue;
			}

			if (!clientSession.isAuthenticated()) {
				if (now - clientSession.getConnectedAtEpochMs() > webSocketProperties.authTimeoutMs()) {
					safeClose(webSocketSession, CloseStatus.POLICY_VIOLATION);
				}
				continue;
			}

			if (clientSession.isPingPending()) {
				long pingElapsed = now - clientSession.getLastPingAtEpochMs();
				if (pingElapsed > webSocketProperties.pongTimeoutMs()) {
					int missed = clientSession.incrementMissedPong();
					if (missed >= webSocketProperties.maxMissedPongs()) {
						safeClose(webSocketSession, CloseStatus.SESSION_NOT_RELIABLE);
						continue;
					}
				}
			}

			sendPing(webSocketSession, clientSession, now);
		}
	}

	private void sendPing(WebSocketSession webSocketSession, ClientSession clientSession, long now) {
		ObjectNode pingPayload = objectMapper.createObjectNode();
		pingPayload.put("type", "ping");
		pingPayload.put("ts", now);

		try {
			webSocketSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(pingPayload)));
			clientSession.markPingSent(now);
		} catch (Exception ex) {
			safeClose(webSocketSession, CloseStatus.SERVER_ERROR);
		}
	}

	private void safeClose(WebSocketSession webSocketSession, CloseStatus status) {
		try {
			webSocketSession.close(status);
		} catch (Exception ex) {
			log.debug("Failed to close websocket session. sessionId={}", webSocketSession.getId());
		}
	}
}

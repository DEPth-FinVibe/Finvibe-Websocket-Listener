package depth.finvibe.listener.websocket;

import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClientSession {

	private final WebSocketSession webSocketSession;
	private final long connectedAtEpochMs;
	private volatile UUID userId;
	private volatile boolean authenticated;
	private volatile long lastPongAtEpochMs;
	private volatile long lastPingAtEpochMs;
	private volatile boolean pingPending;
	private volatile int missedPongCount;
	private final Set<Long> subscribedStockIds = ConcurrentHashMap.newKeySet();

	public ClientSession(WebSocketSession webSocketSession, long nowEpochMs) {
		this.webSocketSession = webSocketSession;
		this.connectedAtEpochMs = nowEpochMs;
		this.lastPongAtEpochMs = nowEpochMs;
	}

	public String getSessionId() {
		return webSocketSession.getId();
	}

	public WebSocketSession getWebSocketSession() {
		return webSocketSession;
	}

	public long getConnectedAtEpochMs() {
		return connectedAtEpochMs;
	}

	public UUID getUserId() {
		return userId;
	}

	public boolean isAuthenticated() {
		return authenticated;
	}

	public long getLastPongAtEpochMs() {
		return lastPongAtEpochMs;
	}

	public long getLastPingAtEpochMs() {
		return lastPingAtEpochMs;
	}

	public boolean isPingPending() {
		return pingPending;
	}

	public int getMissedPongCount() {
		return missedPongCount;
	}

	public void authenticate(UUID userId) {
		this.userId = userId;
		this.authenticated = true;
	}

	public void markPongReceived(long nowEpochMs) {
		this.lastPongAtEpochMs = nowEpochMs;
		this.pingPending = false;
		this.missedPongCount = 0;
	}

	public void markPingSent(long nowEpochMs) {
		this.lastPingAtEpochMs = nowEpochMs;
		this.pingPending = true;
	}

	public int incrementMissedPong() {
		this.pingPending = false;
		this.missedPongCount += 1;
		return missedPongCount;
	}

	public boolean addSubscription(Long stockId) {
		return subscribedStockIds.add(stockId);
	}

	public boolean removeSubscription(Long stockId) {
		return subscribedStockIds.remove(stockId);
	}

	public Set<Long> getSubscribedStockIds() {
		return Set.copyOf(subscribedStockIds);
	}
}

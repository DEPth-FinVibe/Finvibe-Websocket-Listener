package depth.finvibe.listener.websocket;

import depth.finvibe.listener.config.WebSocketProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

@Component
public class SessionRegistry {

	private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
	private final Map<Long, Set<String>> stockSubscribers = new ConcurrentHashMap<>();
	private final WebSocketProperties webSocketProperties;
	private final ExecutorService virtualTaskExecutor;

	public SessionRegistry(WebSocketProperties webSocketProperties, ExecutorService listenerVirtualTaskExecutor) {
		this.webSocketProperties = webSocketProperties;
		this.virtualTaskExecutor = listenerVirtualTaskExecutor;
	}

	public ClientSession add(WebSocketSession webSocketSession, long nowEpochMs) {
		ClientSession clientSession = new ClientSession(
				wrapForConcurrentSend(webSocketSession),
				nowEpochMs,
				virtualTaskExecutor,
				webSocketProperties.sessionQueueCapacity()
		);
		sessions.put(webSocketSession.getId(), clientSession);
		return clientSession;
	}

	public RemovedSession remove(String sessionId) {
		ClientSession clientSession = sessions.remove(sessionId);
		if (clientSession == null) {
			return RemovedSession.empty();
		}
		clientSession.closeQueue();

		for (Long stockId : clientSession.getSubscribedStockIds()) {
			Set<String> subscribers = stockSubscribers.get(stockId);
			if (subscribers == null) {
				continue;
			}
			subscribers.remove(sessionId);
			if (subscribers.isEmpty()) {
				stockSubscribers.remove(stockId);
			}
		}

		return new RemovedSession(clientSession.getUserId(), clientSession.getSubscribedStockIds());
	}

	public ClientSession get(String sessionId) {
		return sessions.get(sessionId);
	}

	public boolean authenticate(String sessionId, UUID userId) {
		ClientSession session = sessions.get(sessionId);
		if (session == null) {
			return false;
		}
		session.authenticate(userId);
		return true;
	}

	public boolean subscribe(String sessionId, Long stockId) {
		ClientSession session = sessions.get(sessionId);
		if (session == null) {
			return false;
		}
		boolean added = session.addSubscription(stockId);
		if (added) {
			stockSubscribers.computeIfAbsent(stockId, key -> ConcurrentHashMap.newKeySet()).add(sessionId);
		}
		return added;
	}

	public boolean unsubscribe(String sessionId, Long stockId) {
		ClientSession session = sessions.get(sessionId);
		if (session == null) {
			return false;
		}
		boolean removed = session.removeSubscription(stockId);
		if (removed) {
			Set<String> subscribers = stockSubscribers.get(stockId);
			if (subscribers != null) {
				subscribers.remove(sessionId);
				if (subscribers.isEmpty()) {
					stockSubscribers.remove(stockId);
				}
			}
		}
		return removed;
	}

	public List<ClientSession> getSubscribers(Long stockId) {
		Set<String> sessionIds = stockSubscribers.get(stockId);
		if (sessionIds == null || sessionIds.isEmpty()) {
			return List.of();
		}

		List<ClientSession> result = new ArrayList<>(sessionIds.size());
		for (String sessionId : sessionIds) {
			ClientSession session = sessions.get(sessionId);
			if (session != null) {
				result.add(session);
			}
		}
		return result;
	}

	public List<ClientSession> getAllSessions() {
		return new ArrayList<>(sessions.values());
	}

	public boolean enqueueSessionTask(String sessionId, Runnable task) {
		ClientSession clientSession = sessions.get(sessionId);
		if (clientSession == null) {
			return false;
		}
		return clientSession.enqueueSessionTask(task);
	}

	public int getActiveSessionCount() {
		return sessions.size();
	}

	public int getSubscribedStockCount() {
		return stockSubscribers.size();
	}

	public int getTotalSubscriptions() {
		int total = 0;
		for (ClientSession session : sessions.values()) {
			total += session.getSubscribedStockIds().size();
		}
		return total;
	}

	private WebSocketSession wrapForConcurrentSend(WebSocketSession webSocketSession) {
		if (webSocketSession instanceof ConcurrentWebSocketSessionDecorator) {
			return webSocketSession;
		}

		return new ConcurrentWebSocketSessionDecorator(
				webSocketSession,
				webSocketProperties.sendTimeLimitMs(),
				webSocketProperties.sendBufferSizeBytes(),
				resolveOverflowStrategy(webSocketProperties.sendOverflowStrategy())
		);
	}

	private ConcurrentWebSocketSessionDecorator.OverflowStrategy resolveOverflowStrategy(String configuredValue) {
		if (configuredValue == null || configuredValue.isBlank()) {
			return ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE;
		}

		try {
			return ConcurrentWebSocketSessionDecorator.OverflowStrategy.valueOf(configuredValue.trim().toUpperCase());
		} catch (IllegalArgumentException ex) {
			return ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE;
		}
	}

	public record RemovedSession(UUID userId, Set<Long> subscribedStockIds) {
		static RemovedSession empty() {
			return new RemovedSession(null, Set.of());
		}
	}
}

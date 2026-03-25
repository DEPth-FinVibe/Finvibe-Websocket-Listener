package depth.finvibe.listener.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRegistry {

	private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
	private final Map<Long, Set<String>> stockSubscribers = new ConcurrentHashMap<>();

	public ClientSession add(WebSocketSession webSocketSession, long nowEpochMs) {
		ClientSession clientSession = new ClientSession(webSocketSession, nowEpochMs);
		sessions.put(webSocketSession.getId(), clientSession);
		return clientSession;
	}

	public RemovedSession remove(String sessionId) {
		ClientSession clientSession = sessions.remove(sessionId);
		if (clientSession == null) {
			return RemovedSession.empty();
		}

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

	public record RemovedSession(UUID userId, Set<Long> subscribedStockIds) {
		static RemovedSession empty() {
			return new RemovedSession(null, Set.of());
		}
	}
}

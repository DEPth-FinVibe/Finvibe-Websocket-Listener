package depth.finvibe.listener.websocket;

import depth.finvibe.listener.redis.CurrentWatcherRedisRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class WatchRenewScheduler {

	private final SessionRegistry sessionRegistry;
	private final CurrentWatcherRedisRepository currentWatcherRedisRepository;

	public WatchRenewScheduler(
			SessionRegistry sessionRegistry,
			CurrentWatcherRedisRepository currentWatcherRedisRepository
	) {
		this.sessionRegistry = sessionRegistry;
		this.currentWatcherRedisRepository = currentWatcherRedisRepository;
	}

	@Scheduled(fixedDelayString = "${listener.websocket.renew-interval-ms:60000}")
	public void renewSubscriptions() {
		Map<Long, Set<UUID>> watchersByStock = new HashMap<>();

		for (ClientSession clientSession : sessionRegistry.getAllSessions()) {
			if (!clientSession.isAuthenticated() || clientSession.getUserId() == null) {
				continue;
			}
			UUID userId = clientSession.getUserId();
			for (Long stockId : clientSession.getSubscribedStockIds()) {
				watchersByStock.computeIfAbsent(stockId, k -> new HashSet<>()).add(userId);
			}
		}

		if (watchersByStock.isEmpty()) {
			return;
		}

		currentWatcherRedisRepository.batchRenew(watchersByStock);
	}
}

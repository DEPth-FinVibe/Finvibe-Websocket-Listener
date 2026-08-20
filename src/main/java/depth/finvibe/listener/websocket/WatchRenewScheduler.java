package depth.finvibe.listener.websocket;

import depth.finvibe.listener.redis.CurrentWatcherRedisRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
		Map<Long, Set<String>> watchersByStock = new HashMap<>();

		for (ClientSession clientSession : sessionRegistry.getAllSessions()) {
			// 익명 세션도 구독 중이면 watcher index를 유지해야 시세가 계속 발행된다.
			if (!clientSession.isEstablished()) {
				continue;
			}
			String watcherId = clientSession.getWatcherId();
			for (Long stockId : clientSession.getSubscribedStockIds()) {
				watchersByStock.computeIfAbsent(stockId, k -> new HashSet<>()).add(watcherId);
			}
		}

		if (watchersByStock.isEmpty()) {
			return;
		}

		currentWatcherRedisRepository.batchRenew(watchersByStock);
	}
}

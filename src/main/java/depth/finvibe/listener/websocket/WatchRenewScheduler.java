package depth.finvibe.listener.websocket;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import depth.finvibe.listener.redis.CurrentWatcherRedisRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WatchRenewScheduler {

	private final SessionRegistry sessionRegistry;
	private final CurrentWatcherRedisRepository currentWatcherRedisRepository;
	private final WebSocketMetrics webSocketMetrics;

	public WatchRenewScheduler(
			SessionRegistry sessionRegistry,
			CurrentWatcherRedisRepository currentWatcherRedisRepository,
			WebSocketMetrics webSocketMetrics
	) {
		this.sessionRegistry = sessionRegistry;
		this.currentWatcherRedisRepository = currentWatcherRedisRepository;
		this.webSocketMetrics = webSocketMetrics;
	}

	@Scheduled(fixedDelayString = "${listener.websocket.renew-interval-ms:60000}")
	public void renewSubscriptions() {
		for (ClientSession clientSession : sessionRegistry.getAllSessions()) {
			if (!clientSession.isAuthenticated() || clientSession.getUserId() == null) {
				continue;
			}

			if (clientSession.getSubscribedStockIds().isEmpty()) {
				continue;
			}

			boolean accepted = clientSession.enqueueSessionTask(() -> renewSingleSession(clientSession));
			if (!accepted) {
				webSocketMetrics.sessionQueueOverflow("watch_renew_drop");
			}
		}
	}

	private void renewSingleSession(ClientSession clientSession) {
		if (!clientSession.isAuthenticated() || clientSession.getUserId() == null) {
			return;
		}

		for (Long stockId : clientSession.getSubscribedStockIds()) {
			currentWatcherRedisRepository.renew(clientSession.getUserId(), stockId);
		}
	}
}

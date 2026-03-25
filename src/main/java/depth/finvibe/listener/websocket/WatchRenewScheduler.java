package depth.finvibe.listener.websocket;

import depth.finvibe.listener.redis.CurrentWatcherRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WatchRenewScheduler {

	private final SessionRegistry sessionRegistry;
	private final CurrentWatcherRedisRepository currentWatcherRedisRepository;

	@Scheduled(fixedDelayString = "${listener.websocket.renew-interval-ms:60000}")
	public void renewSubscriptions() {
		for (ClientSession clientSession : sessionRegistry.getAllSessions()) {
			if (!clientSession.isAuthenticated() || clientSession.getUserId() == null) {
				continue;
			}
			for (Long stockId : clientSession.getSubscribedStockIds()) {
				currentWatcherRedisRepository.renew(clientSession.getUserId(), stockId);
			}
		}
	}
}

package depth.finvibe.listener.redis;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class CurrentWatcherRedisRepository {

	private static final String KEY_PREFIX = "market:current-watcher:";
	private static final Duration INDEX_TTL = Duration.ofMinutes(10);

	private final StringRedisTemplate redisTemplate;
	private final WebSocketMetrics webSocketMetrics;

	public void save(UUID userId, Long stockId) {
		try {
			String key = keyForStock(stockId);
			redisTemplate.opsForSet().add(key, userId.toString());
			redisTemplate.expire(key, INDEX_TTL);
			webSocketMetrics.watcherOp("save");
		} catch (Exception ex) {
			webSocketMetrics.watcherError("save");
			throw ex;
		}
	}

	public void renew(UUID userId, Long stockId) {
		try {
			String key = keyForStock(stockId);
			if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
				redisTemplate.expire(key, INDEX_TTL);
				webSocketMetrics.watcherOp("renew");
				return;
			}
			save(userId, stockId);
			webSocketMetrics.watcherOp("renew");
		} catch (Exception ex) {
			webSocketMetrics.watcherError("renew");
			throw ex;
		}
	}

	public void remove(UUID userId, Long stockId) {
		try {
			String key = keyForStock(stockId);
			redisTemplate.opsForSet().remove(key, userId.toString());
			Long remaining = redisTemplate.opsForSet().size(key);
			if (remaining != null && remaining == 0L) {
				redisTemplate.delete(key);
			}
			webSocketMetrics.watcherOp("remove");
		} catch (Exception ex) {
			webSocketMetrics.watcherError("remove");
			throw ex;
		}
	}

	private String keyForStock(Long stockId) {
		return KEY_PREFIX + stockId;
	}
}

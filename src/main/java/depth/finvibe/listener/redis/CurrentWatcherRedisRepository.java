package depth.finvibe.listener.redis;

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

	public void save(UUID userId, Long stockId) {
		String key = keyForStock(stockId);
		redisTemplate.opsForSet().add(key, userId.toString());
		redisTemplate.expire(key, INDEX_TTL);
	}

	public void renew(UUID userId, Long stockId) {
		String key = keyForStock(stockId);
		if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
			redisTemplate.expire(key, INDEX_TTL);
			return;
		}
		save(userId, stockId);
	}

	public void remove(UUID userId, Long stockId) {
		String key = keyForStock(stockId);
		redisTemplate.opsForSet().remove(key, userId.toString());
		Long remaining = redisTemplate.opsForSet().size(key);
		if (remaining != null && remaining == 0L) {
			redisTemplate.delete(key);
		}
	}

	private String keyForStock(Long stockId) {
		return KEY_PREFIX + stockId;
	}
}

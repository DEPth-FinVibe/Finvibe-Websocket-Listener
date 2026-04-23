package depth.finvibe.listener.redis;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
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

	public void batchRenew(Map<Long, Set<UUID>> watchersByStock) {
		if (watchersByStock.isEmpty()) {
			return;
		}
		try {
			redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
				for (Map.Entry<Long, Set<UUID>> entry : watchersByStock.entrySet()) {
					byte[] key = keyForStock(entry.getKey()).getBytes(StandardCharsets.UTF_8);
					byte[][] members = entry.getValue().stream()
							.map(uuid -> uuid.toString().getBytes(StandardCharsets.UTF_8))
							.toArray(byte[][]::new);
					if (members.length > 0) {
						connection.setCommands().sAdd(key, members);
					}
					connection.keyCommands().expire(key, INDEX_TTL.getSeconds());
				}
				return null;
			});
			webSocketMetrics.watcherOp("batch_renew");
		} catch (Exception ex) {
			webSocketMetrics.watcherError("batch_renew");
			throw ex;
		}
	}

 	private String keyForStock(Long stockId) {
		return KEY_PREFIX + "{stock:" + stockId + "}";
	}
}

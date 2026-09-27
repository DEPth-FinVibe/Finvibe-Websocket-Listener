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

@Repository
@RequiredArgsConstructor
public class CurrentWatcherRedisRepository {

	private static final String KEY_PREFIX = "market:current-watcher:";
	private static final Duration INDEX_TTL = Duration.ofMinutes(10);
	// 감시 중인 종목 인덱스(member = 종목 ID, score = 만료 시각 epoch ms). 모놀리식이 KEYS 대신 이 집합으로 찾는다(#17 D25).
	static final String ACTIVE_INDEX_KEY = "market:current-watcher-index";

	private final StringRedisTemplate redisTemplate;
	private final WebSocketMetrics webSocketMetrics;

	public void save(String watcherId, Long stockId) {
		try {
			String key = keyForStock(stockId);
			redisTemplate.opsForSet().add(key, watcherId);
			redisTemplate.expire(key, INDEX_TTL);
			touchIndex(stockId);
			webSocketMetrics.watcherOp("save");
		} catch (Exception ex) {
			webSocketMetrics.watcherError("save");
			throw ex;
		}
	}

	public void renew(String watcherId, Long stockId) {
		try {
			String key = keyForStock(stockId);
			if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
				redisTemplate.expire(key, INDEX_TTL);
				touchIndex(stockId);
				webSocketMetrics.watcherOp("renew");
				return;
			}
			save(watcherId, stockId);
			webSocketMetrics.watcherOp("renew");
		} catch (Exception ex) {
			webSocketMetrics.watcherError("renew");
			throw ex;
		}
	}

	public void remove(String watcherId, Long stockId) {
		try {
			String key = keyForStock(stockId);
			redisTemplate.opsForSet().remove(key, watcherId);
			Long remaining = redisTemplate.opsForSet().size(key);
			if (remaining != null && remaining == 0L) {
				redisTemplate.delete(key);
				redisTemplate.opsForZSet().remove(ACTIVE_INDEX_KEY, String.valueOf(stockId));
			}
			webSocketMetrics.watcherOp("remove");
		} catch (Exception ex) {
			webSocketMetrics.watcherError("remove");
			throw ex;
		}
	}

	public void batchRenew(Map<Long, Set<String>> watchersByStock) {
		if (watchersByStock.isEmpty()) {
			return;
		}
		try {
			byte[] indexKey = ACTIVE_INDEX_KEY.getBytes(StandardCharsets.UTF_8);
			double expiresAt = expiresAtMillis();
			redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
				for (Map.Entry<Long, Set<String>> entry : watchersByStock.entrySet()) {
					byte[] key = keyForStock(entry.getKey()).getBytes(StandardCharsets.UTF_8);
					byte[][] members = entry.getValue().stream()
							.map(watcherId -> watcherId.getBytes(StandardCharsets.UTF_8))
							.toArray(byte[][]::new);
					if (members.length > 0) {
						connection.setCommands().sAdd(key, members);
					}
					connection.keyCommands().expire(key, INDEX_TTL.getSeconds());
					connection.zSetCommands().zAdd(indexKey, expiresAt,
							String.valueOf(entry.getKey()).getBytes(StandardCharsets.UTF_8));
				}
				return null;
			});
			webSocketMetrics.watcherOp("batch_renew");
		} catch (Exception ex) {
			webSocketMetrics.watcherError("batch_renew");
			throw ex;
		}
	}

	private void touchIndex(Long stockId) {
		redisTemplate.opsForZSet().add(ACTIVE_INDEX_KEY, String.valueOf(stockId), expiresAtMillis());
	}

	private static double expiresAtMillis() {
		return System.currentTimeMillis() + INDEX_TTL.toMillis();
	}

 	private String keyForStock(Long stockId) {
		return KEY_PREFIX + "{stock:" + stockId + "}";
	}
}

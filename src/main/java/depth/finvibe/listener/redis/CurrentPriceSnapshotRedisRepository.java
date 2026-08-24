package depth.finvibe.listener.redis;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class CurrentPriceSnapshotRedisRepository {

	private static final Logger log = LoggerFactory.getLogger(CurrentPriceSnapshotRedisRepository.class);
	private static final String KEY_PREFIX = "market:current-price:";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;
	private final WebSocketMetrics webSocketMetrics;

	public Map<Long, ObjectNode> findByStockIds(List<Long> stockIds) {
		if (stockIds == null || stockIds.isEmpty()) {
			return Map.of();
		}

		List<Long> requestedStockIds = stockIds.stream()
				.distinct()
				.toList();
		List<String> keys = requestedStockIds.stream()
				.map(this::keyForStock)
				.toList();

		long startedAt = System.currentTimeMillis();
		try {
			List<String> values = redisTemplate.opsForValue().multiGet(keys);
			Map<Long, ObjectNode> snapshots = deserializeSnapshots(requestedStockIds, values);
			webSocketMetrics.initialSnapshotResult("hit", snapshots.size());
			webSocketMetrics.initialSnapshotResult("miss", requestedStockIds.size() - snapshots.size());
			return snapshots;
		} catch (Exception ex) {
			webSocketMetrics.initialSnapshotResult("error", requestedStockIds.size());
			log.debug("Failed to read current-price snapshots from Redis. stockIds={}", requestedStockIds, ex);
			return Map.of();
		} finally {
			webSocketMetrics.initialSnapshotReadLatency(System.currentTimeMillis() - startedAt);
		}
	}

	private Map<Long, ObjectNode> deserializeSnapshots(List<Long> stockIds, List<String> values) {
		if (values == null || values.isEmpty()) {
			return Map.of();
		}

		Map<Long, ObjectNode> snapshots = new LinkedHashMap<>();
		int resultSize = Math.min(stockIds.size(), values.size());
		for (int index = 0; index < resultSize; index++) {
			String rawSnapshot = values.get(index);
			if (rawSnapshot == null || rawSnapshot.isBlank()) {
				continue;
			}

			try {
				JsonNode snapshot = objectMapper.readTree(rawSnapshot);
				if (snapshot instanceof ObjectNode objectNode) {
					Long stockId = stockIds.get(index);
					objectNode.put("stockId", stockId);
					snapshots.put(stockId, objectNode);
				}
			} catch (Exception ex) {
				log.debug("Failed to deserialize current-price snapshot. stockId={}", stockIds.get(index), ex);
			}
		}
		return snapshots;
	}

	private String keyForStock(Long stockId) {
		return KEY_PREFIX + "{stock:" + stockId + "}";
	}
}

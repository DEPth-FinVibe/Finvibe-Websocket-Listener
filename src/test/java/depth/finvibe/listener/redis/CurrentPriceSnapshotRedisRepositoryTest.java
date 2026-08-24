package depth.finvibe.listener.redis;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentPriceSnapshotRedisRepositoryTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	@Mock
	private WebSocketMetrics webSocketMetrics;

	private CurrentPriceSnapshotRedisRepository repository;

	@BeforeEach
	void setUp() {
		ObjectMapper objectMapper = JsonMapper.builder().build();
		repository = new CurrentPriceSnapshotRedisRepository(redisTemplate, objectMapper, webSocketMetrics);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
	}

	@Test
	@DisplayName("요청한 종목의 현재가 스냅샷을 Redis에서 한 번에 조회한다")
	void findByStockIds_cachedSnapshots_returnsByStockId() {
		// given
		List<String> keys = List.of(
				"market:current-price:{stock:1}",
				"market:current-price:{stock:2}"
		);
		when(valueOperations.multiGet(keys)).thenReturn(List.of(
				"{\"stockId\":1,\"close\":71000,\"prevDayChangePct\":1.2}",
				"{\"stockId\":2,\"close\":125000,\"prevDayChangePct\":-0.4}"
		));

		// when
		Map<Long, ObjectNode> result = repository.findByStockIds(List.of(1L, 2L));

		// then
		assertThat(result).containsOnlyKeys(1L, 2L);
		assertThat(result.get(1L).path("close").asLong()).isEqualTo(71000L);
		assertThat(result.get(2L).path("close").asLong()).isEqualTo(125000L);
		verify(webSocketMetrics).initialSnapshotResult("hit", 2L);
	}

	@Test
	@DisplayName("일부 종목의 캐시가 없으면 조회된 스냅샷만 반환한다")
	void findByStockIds_partialCache_returnsAvailableSnapshots() {
		// given
		List<String> keys = List.of(
				"market:current-price:{stock:1}",
				"market:current-price:{stock:2}"
		);
		when(valueOperations.multiGet(keys)).thenReturn(java.util.Arrays.asList(
				"{\"stockId\":1,\"close\":71000}",
				null
		));

		// when
		Map<Long, ObjectNode> result = repository.findByStockIds(List.of(1L, 2L));

		// then
		assertThat(result).containsOnlyKeys(1L);
		verify(webSocketMetrics).initialSnapshotResult("hit", 1L);
		verify(webSocketMetrics).initialSnapshotResult("miss", 1L);
	}

	@Test
	@DisplayName("Redis 조회가 실패해도 구독 흐름을 깨지 않고 빈 결과를 반환한다")
	void findByStockIds_redisFailure_returnsEmptyMap() {
		// given
		List<String> keys = List.of("market:current-price:{stock:1}");
		when(valueOperations.multiGet(keys)).thenThrow(new IllegalStateException("redis unavailable"));

		// when
		Map<Long, ObjectNode> result = repository.findByStockIds(List.of(1L));

		// then
		assertThat(result).isEmpty();
		verify(webSocketMetrics).initialSnapshotResult("error", 1L);
	}
}

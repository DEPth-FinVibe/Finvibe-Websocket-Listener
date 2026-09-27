package depth.finvibe.listener.redis;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentWatcherRedisRepositoryTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private WebSocketMetrics webSocketMetrics;

	@Mock
	private SetOperations<String, String> setOperations;

	@Mock
	private ZSetOperations<String, String> zSetOperations;

	private CurrentWatcherRedisRepository repository;

	@BeforeEach
	void setUp() {
		repository = new CurrentWatcherRedisRepository(redisTemplate, webSocketMetrics);
		lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
		lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
	}

	@Test
	@DisplayName("감시를 등록하면 종목을 만료 시각과 함께 인덱스에 넣는다")
	void save_addsStockToIndexWithExpiry() {
		long before = System.currentTimeMillis();

		repository.save("anon:1", 7L);

		ArgumentCaptor<Double> score = ArgumentCaptor.forClass(Double.class);
		verify(zSetOperations).add(eq(CurrentWatcherRedisRepository.ACTIVE_INDEX_KEY), eq("7"), score.capture());
		assertThat(score.getValue()).isGreaterThanOrEqualTo(before + 600_000.0);
	}

	@Test
	@DisplayName("마지막 감시자가 빠지면 인덱스에서 종목을 뺀다")
	void remove_lastWatcher_removesFromIndex() {
		when(setOperations.size(anyString())).thenReturn(0L);

		repository.remove("anon:1", 7L);

		verify(zSetOperations).remove(CurrentWatcherRedisRepository.ACTIVE_INDEX_KEY, "7");
	}

	@Test
	@DisplayName("다른 감시자가 남아 있으면 인덱스에서 빼지 않는다")
	void remove_remainingWatcher_keepsIndex() {
		when(setOperations.size(anyString())).thenReturn(1L);

		repository.remove("anon:1", 7L);

		verify(zSetOperations, never()).remove(anyString(), any());
	}
}

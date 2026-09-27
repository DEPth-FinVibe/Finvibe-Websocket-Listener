package depth.finvibe.listener.websocket;

import depth.finvibe.listener.config.WebSocketProperties;
import depth.finvibe.listener.metrics.WebSocketMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class MarketEventIngressDispatcherTest {

	private MarketEventIngressDispatcher dispatcher;

	@AfterEach
	void tearDown() {
		if (dispatcher != null) {
			dispatcher.stop();
		}
	}

	@Test
	@DisplayName("모든 틱을 합치지 않고 전달하며, 같은 종목은 넣은 순서대로 전달한다")
	void submit_deliversEveryTickInOrderPerStock() throws Exception {
		int stocks = 10;
		int ticksPerStock = 300;
		CountDownLatch done = new CountDownLatch(stocks * ticksPerStock);
		Map<Long, List<Long>> seen = new ConcurrentHashMap<>();
		MarketEventBroadcaster broadcaster = mock(MarketEventBroadcaster.class);
		doAnswer(invocation -> {
			JsonNode event = invocation.getArgument(0);
			seen.computeIfAbsent(event.path("stockId").asLong(), ignored -> Collections.synchronizedList(new ArrayList<>()))
					.add(event.path("seq").asLong());
			done.countDown();
			return null;
		}).when(broadcaster).broadcastCurrentPrice(any());
		WebSocketProperties properties = mock(WebSocketProperties.class);
		when(properties.eventDispatchParallelism()).thenReturn(4);
		when(properties.eventDispatchQueueCapacity()).thenReturn(16);
		dispatcher = new MarketEventIngressDispatcher(broadcaster, mock(WebSocketMetrics.class), properties);
		dispatcher.start();

		JsonMapper mapper = JsonMapper.builder().build();
		for (int seq = 0; seq < ticksPerStock; seq++) {
			for (long stockId = 1; stockId <= stocks; stockId++) {
				dispatcher.submit(mapper.createObjectNode().put("stockId", stockId).put("seq", seq));
			}
		}

		assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(seen).hasSize(stocks);
		seen.values().forEach(sequence -> assertThat(sequence).hasSize(ticksPerStock).isSorted());
	}
}

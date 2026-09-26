package depth.finvibe.listener.redis;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import depth.finvibe.listener.websocket.MarketEventIngressDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PriceEventRedisSubscriberTest {

	@Mock
	private MarketEventIngressDispatcher marketEventIngressDispatcher;

	@Mock
	private WebSocketMetrics webSocketMetrics;

	private PriceEventRedisSubscriber subscriber;

	@BeforeEach
	void setUp() {
		subscriber = new PriceEventRedisSubscriber(
				JsonMapper.builder().build(),
				marketEventIngressDispatcher,
				webSocketMetrics,
				Runnable::run
		);
	}

	@Test
	@DisplayName("배열로 묶인 발행은 틱마다 하나씩 전달한다")
	void handle_array_submitsEveryTick() {
		subscriber.handle("[{\"stockId\":1,\"close\":100,\"ts\":10},{\"stockId\":2,\"close\":200,\"ts\":11},{\"stockId\":1,\"close\":101,\"ts\":12}]", 20L);

		ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
		verify(marketEventIngressDispatcher, times(3)).submit(captor.capture());
		List<JsonNode> events = captor.getAllValues();
		assertThat(events).extracting(event -> event.path("close").asInt()).containsExactly(100, 200, 101);
		assertThat(events).allSatisfy(event -> assertThat(event.path("arrivedAt").asLong()).isEqualTo(20L));
		verify(webSocketMetrics, times(3)).redisEventConsumed();
	}

	@Test
	@DisplayName("이전 형식(틱 1건)도 그대로 전달한다")
	void handle_singleObject_submitsOnce() {
		subscriber.handle("{\"stockId\":1,\"close\":100}", 20L);

		verify(marketEventIngressDispatcher).submit(org.mockito.ArgumentMatchers.any());
		verify(webSocketMetrics).redisEventConsumed();
	}

	@Test
	@DisplayName("파싱할 수 없는 메시지는 실패로 센다")
	void handle_invalidPayload_countsFailure() {
		subscriber.handle("not-json", 20L);

		verify(webSocketMetrics).redisEventFailed();
	}
}

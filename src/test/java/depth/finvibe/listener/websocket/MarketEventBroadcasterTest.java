package depth.finvibe.listener.websocket;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketEventBroadcasterTest {

	@Mock
	private SessionRegistry sessionRegistry;

	@Mock
	private WebSocketMetrics webSocketMetrics;

	@Mock
	private WebSocketSession webSocketSession;

	private final ObjectMapper objectMapper = JsonMapper.builder().build();
	private final List<Runnable> scheduledDrains = new ArrayList<>();
	private MarketEventBroadcaster broadcaster;

	@BeforeEach
	void setUp() {
		broadcaster = new MarketEventBroadcaster(sessionRegistry, objectMapper, webSocketMetrics);
		lenient().when(webSocketSession.isOpen()).thenReturn(true);
		lenient().when(webSocketSession.getId()).thenReturn("session-1");
	}

	@Test
	@DisplayName("실시간 현재가 이벤트에 누적 거래대금과 priceVersion을 정밀도 손실 없이 담는다")
	void broadcastCurrentPrice_includesValueAndVersion() throws Exception {
		ClientSession session = session(Runnable::run, 100);
		when(sessionRegistry.getSubscribers(1L)).thenReturn(List.of(session));

		broadcaster.broadcastCurrentPrice(tick(1L, 71_000L, 1_790_298_001_000_002L));

		JsonNode frame = sentFrames(1).getFirst();
		assertThat(frame.path("type").asText()).isEqualTo("events");
		JsonNode item = frame.path("items").get(0);
		assertThat(item.path("topic").asText()).isEqualTo("quote:1");
		assertThat(item.path("data").path("price").asLong()).isEqualTo(71_000L);
		assertThat(item.path("data").path("value").asLong()).isEqualTo(87_654_321L);
		assertThat(item.path("data").path("priceVersion").asLong()).isEqualTo(1_790_298_001_000_002L);
	}

	@Test
	@DisplayName("같은 종목의 틱을 합치지 않고, 쌓인 틱을 도착 순서대로 프레임 하나에 담는다")
	void broadcastCurrentPrice_keepsEveryTickInOrder() throws Exception {
		ClientSession session = session(scheduledDrains::add, 100);
		when(sessionRegistry.getSubscribers(1L)).thenReturn(List.of(session));

		broadcaster.broadcastCurrentPrice(tick(1L, 100L, 1L));
		broadcaster.broadcastCurrentPrice(tick(1L, 101L, 2L));
		broadcaster.broadcastCurrentPrice(tick(1L, 102L, 3L));
		scheduledDrains.forEach(Runnable::run);

		JsonNode frame = sentFrames(1).getFirst();
		assertThat(frame.path("items")).hasSize(3);
		assertThat(frame.path("items")).extracting(item -> item.path("data").path("price").asLong())
				.containsExactly(100L, 101L, 102L);
		verify(webSocketMetrics).eventsDelivered(3);
		// 종단간 지연은 묶음 안의 틱마다 기록한다.
		verify(webSocketMetrics, times(3)).eventSourceToSendMessageLatency(org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	@DisplayName("보내지 못한 틱이 한도를 넘으면 버리지 않고 연결을 끊는다")
	void broadcastCurrentPrice_backlogExceeded_closesSession() throws Exception {
		ClientSession session = session(scheduledDrains::add, 2);
		when(sessionRegistry.getSubscribers(1L)).thenReturn(List.of(session));

		broadcaster.broadcastCurrentPrice(tick(1L, 100L, 1L));
		broadcaster.broadcastCurrentPrice(tick(1L, 101L, 2L));
		broadcaster.broadcastCurrentPrice(tick(1L, 102L, 3L));

		verify(webSocketMetrics).sessionBacklogExceeded();
		ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
		verify(webSocketSession).close(status.capture());
		assertThat(status.getValue().getCode()).isEqualTo(CloseStatus.SESSION_NOT_RELIABLE.getCode());
		verify(webSocketSession, never()).sendMessage(any());
	}

	private ClientSession session(java.util.concurrent.Executor executor, int backlogLimit) {
		return new ClientSession(webSocketSession, System.currentTimeMillis(), executor, 32, true, backlogLimit, 256);
	}

	private List<JsonNode> sentFrames(int expected) throws Exception {
		ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
		verify(webSocketSession, times(expected)).sendMessage(captor.capture());
		List<JsonNode> frames = new ArrayList<>();
		for (TextMessage message : captor.getAllValues()) {
			frames.add(objectMapper.readTree(message.getPayload()));
		}
		return frames;
	}

	private ObjectNode tick(long stockId, long close, long priceVersion) {
		ObjectNode event = objectMapper.createObjectNode();
		event.put("stockId", stockId);
		event.put("ts", System.currentTimeMillis());
		event.put("close", close);
		event.put("prevDayChangePct", 1.2);
		event.put("volume", 12_345L);
		event.put("value", 87_654_321L);
		event.put("priceVersion", priceVersion);
		return event;
	}
}

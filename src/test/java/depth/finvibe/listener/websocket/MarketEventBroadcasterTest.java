package depth.finvibe.listener.websocket;

import depth.finvibe.listener.config.WebSocketProperties;
import depth.finvibe.listener.metrics.WebSocketMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketEventBroadcasterTest {

	@Mock
	private SessionRegistry sessionRegistry;

	@Mock
	private WebSocketMetrics webSocketMetrics;

	@Mock
	private WebSocketProperties webSocketProperties;

	@Mock
	private ClientSession clientSession;

	@Mock
	private WebSocketSession webSocketSession;

	private ObjectMapper objectMapper;
	private MarketEventBroadcaster broadcaster;

	@BeforeEach
	void setUp() {
		objectMapper = JsonMapper.builder().build();
		broadcaster = new MarketEventBroadcaster(
				sessionRegistry,
				objectMapper,
				webSocketMetrics,
				webSocketProperties,
				Runnable::run
		);
	}

	@Test
	@DisplayName("실시간 현재가 이벤트에 누적 거래대금을 포함한다")
	void broadcastCurrentPrice_includesTradingValue() throws Exception {
		when(webSocketProperties.fanoutChunkSize()).thenReturn(128);
		when(webSocketProperties.fanoutChunkParallelism()).thenReturn(1);
		when(sessionRegistry.getSubscribers(1L)).thenReturn(List.of(clientSession));
		when(clientSession.getWebSocketSession()).thenReturn(webSocketSession);
		when(clientSession.isEstablished()).thenReturn(true);
		when(webSocketSession.isOpen()).thenReturn(true);
		when(webSocketSession.getId()).thenReturn("session-1");
		when(sessionRegistry.get("session-1")).thenReturn(clientSession);
		when(clientSession.upsertLatestDataTask(eq("quote:1"), any())).thenAnswer(invocation -> {
			invocation.getArgument(1, Runnable.class).run();
			return false;
		});

		ObjectNode event = objectMapper.createObjectNode();
		event.put("stockId", 1L);
		event.put("ts", System.currentTimeMillis());
		event.put("close", 71_000L);
		event.put("prevDayChangePct", 1.2);
		event.put("volume", 12_345L);
		event.put("value", 87_654_321L);

		broadcaster.broadcastCurrentPrice(event);

		ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
		verify(webSocketSession).sendMessage(messageCaptor.capture());
		JsonNode payload = objectMapper.readTree(messageCaptor.getValue().getPayload());
		assertThat(payload.path("data").path("price").asLong()).isEqualTo(71_000L);
		assertThat(payload.path("data").path("value").asLong()).isEqualTo(87_654_321L);
	}

	@Test
	@DisplayName("실시간 현재가 이벤트의 priceVersion을 정밀도 손실 없이 전달한다")
	void broadcastCurrentPrice_includesPriceVersion() throws Exception {
		when(webSocketProperties.fanoutChunkSize()).thenReturn(128);
		when(webSocketProperties.fanoutChunkParallelism()).thenReturn(1);
		when(sessionRegistry.getSubscribers(1L)).thenReturn(List.of(clientSession));
		when(clientSession.getWebSocketSession()).thenReturn(webSocketSession);
		when(clientSession.isEstablished()).thenReturn(true);
		when(webSocketSession.isOpen()).thenReturn(true);
		when(webSocketSession.getId()).thenReturn("session-1");
		when(sessionRegistry.get("session-1")).thenReturn(clientSession);
		when(clientSession.upsertLatestDataTask(eq("quote:1"), any())).thenAnswer(invocation -> {
			invocation.getArgument(1, Runnable.class).run();
			return false;
		});

		ObjectNode event = objectMapper.createObjectNode();
		event.put("stockId", 1L);
		event.put("ts", System.currentTimeMillis());
		event.put("close", 71_000L);
		event.put("prevDayChangePct", 1.2);
		event.put("volume", 12_345L);
		event.put("value", 87_654_321L);
		event.put("priceVersion", 1_790_298_001_000_002L);

		broadcaster.broadcastCurrentPrice(event);

		ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
		verify(webSocketSession).sendMessage(messageCaptor.capture());
		JsonNode payload = objectMapper.readTree(messageCaptor.getValue().getPayload());
		assertThat(payload.path("data").path("priceVersion").asLong()).isEqualTo(1_790_298_001_000_002L);
	}
}

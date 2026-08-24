package depth.finvibe.listener.websocket;

import depth.finvibe.listener.config.WebSocketProperties;
import depth.finvibe.listener.metrics.WebSocketMetrics;
import depth.finvibe.listener.redis.CurrentPriceSnapshotRedisRepository;
import depth.finvibe.listener.redis.CurrentWatcherRedisRepository;
import depth.finvibe.listener.security.JwtTokenVerifier;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketQuoteWebSocketHandlerTest {

	@Mock
	private SessionRegistry sessionRegistry;

	@Mock
	private JwtTokenVerifier jwtTokenVerifier;

	@Mock
	private CurrentWatcherRedisRepository currentWatcherRedisRepository;

	@Mock
	private CurrentPriceSnapshotRedisRepository currentPriceSnapshotRedisRepository;

	@Mock
	private WebSocketMetrics webSocketMetrics;

	@Mock
	private WebSocketProperties webSocketProperties;

	@Mock
	private WebSocketSession webSocketSession;

	@Mock
	private ClientSession clientSession;

	private ObjectMapper objectMapper;
	private MarketQuoteWebSocketHandler handler;

	@BeforeEach
	void setUp() {
		objectMapper = JsonMapper.builder().build();
		handler = new MarketQuoteWebSocketHandler(
				sessionRegistry,
				jwtTokenVerifier,
				currentWatcherRedisRepository,
				currentPriceSnapshotRedisRepository,
				webSocketMetrics,
				objectMapper,
				webSocketProperties,
				Runnable::run
		);
	}

	@Test
	@DisplayName("종목 구독 직후 Redis 현재가 스냅샷을 초기 이벤트로 전송한다")
	void subscribe_cachedCurrentPrice_sendsInitialEventAfterAck() throws Exception {
		// given
		when(webSocketSession.getId()).thenReturn("session-1");
		when(sessionRegistry.get("session-1")).thenReturn(clientSession);
		when(clientSession.isEstablished()).thenReturn(true);
		when(clientSession.isGuest()).thenReturn(true);
		when(clientSession.getWatcherId()).thenReturn("anon:session-1");
		when(clientSession.getWebSocketSession()).thenReturn(webSocketSession);
		when(sessionRegistry.subscribe("session-1", 1L)).thenReturn(true);
		when(sessionRegistry.enqueueSessionTask(eq("session-1"), any())).thenAnswer(invocation -> {
			invocation.getArgument(1, Runnable.class).run();
			return true;
		});

		ObjectNode snapshot = objectMapper.createObjectNode();
		snapshot.put("stockId", 1L);
		snapshot.put("close", 71000L);
		snapshot.put("prevDayChangePct", 1.2);
		snapshot.put("volume", 12345L);
		snapshot.put("value", 87654321L);
		when(currentPriceSnapshotRedisRepository.findByStockIds(List.of(1L)))
				.thenReturn(Map.of(1L, snapshot));

		// when
		handler.handleTextMessage(
				webSocketSession,
				new TextMessage("{\"type\":\"subscribe\",\"topics\":[\"quote:1\"]}")
		);

		// then
		ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
		verify(webSocketSession, times(2)).sendMessage(messageCaptor.capture());

		JsonNode ack = objectMapper.readTree(messageCaptor.getAllValues().get(0).getPayload());
		JsonNode initialEvent = objectMapper.readTree(messageCaptor.getAllValues().get(1).getPayload());
		assertThat(ack.path("type").asText()).isEqualTo("subscribe");
		assertThat(initialEvent.path("type").asText()).isEqualTo("event");
		assertThat(initialEvent.path("topic").asText()).isEqualTo("quote:1");
		assertThat(initialEvent.path("data").path("close").asLong()).isEqualTo(71000L);
		assertThat(initialEvent.path("data").path("initial").asBoolean()).isTrue();
		verify(currentWatcherRedisRepository).save("anon:session-1", 1L);
	}
}

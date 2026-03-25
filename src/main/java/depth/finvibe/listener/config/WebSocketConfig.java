package depth.finvibe.listener.config;

import depth.finvibe.listener.websocket.MarketQuoteWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
@EnableConfigurationProperties({WebSocketProperties.class, RedisTopicsProperties.class})
public class WebSocketConfig implements WebSocketConfigurer {

	private final MarketQuoteWebSocketHandler marketQuoteWebSocketHandler;
	private final WebSocketProperties webSocketProperties;

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(marketQuoteWebSocketHandler, "/market/ws")
				.setAllowedOrigins(webSocketProperties.allowedOrigins());
	}
}

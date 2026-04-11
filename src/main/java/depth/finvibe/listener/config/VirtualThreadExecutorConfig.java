package depth.finvibe.listener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class VirtualThreadExecutorConfig {

	@Bean(name = "listenerVirtualTaskExecutor", destroyMethod = "close")
	public ExecutorService listenerVirtualTaskExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

	@Bean(name = "listenerFanoutChunkExecutor", destroyMethod = "close")
	public ExecutorService listenerFanoutChunkExecutor(WebSocketProperties webSocketProperties) {
		return Executors.newFixedThreadPool(Math.max(1, webSocketProperties.fanoutChunkParallelism()));
	}

	@Bean(name = "listenerMarketEventExecutor", destroyMethod = "close")
	public ExecutorService listenerMarketEventExecutor(WebSocketProperties webSocketProperties) {
		return Executors.newFixedThreadPool(Math.max(1, webSocketProperties.eventDispatchParallelism()));
	}
}

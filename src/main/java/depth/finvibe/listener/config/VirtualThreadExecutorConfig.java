package depth.finvibe.listener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class VirtualThreadExecutorConfig {

	@Bean(name = "listenerVirtualTaskExecutor", destroyMethod = "close")
	public ExecutorService listenerVirtualTaskExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

	@Bean(name = "listenerFanoutChunkExecutor", destroyMethod = "close")
	public ExecutorService listenerFanoutChunkExecutor(WebSocketProperties webSocketProperties) {
		int parallelism = Math.max(1, webSocketProperties.fanoutChunkParallelism());
		int queueCapacity = Math.max(parallelism, webSocketProperties.fanoutChunkQueueCapacity());
		return new ThreadPoolExecutor(
				parallelism, parallelism,
				0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(queueCapacity),
				new ThreadPoolExecutor.DiscardOldestPolicy()
		);
	}

	@Bean(name = "listenerMarketEventExecutor", destroyMethod = "close")
	public ExecutorService listenerMarketEventExecutor(WebSocketProperties webSocketProperties) {
		int parallelism = Math.max(1, webSocketProperties.eventDispatchParallelism());
		int queueCapacity = Math.max(parallelism, webSocketProperties.eventDispatchQueueCapacity());
		return new ThreadPoolExecutor(
				parallelism, parallelism,
				0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(queueCapacity),
				new ThreadPoolExecutor.DiscardOldestPolicy()
		);
	}
}

package depth.finvibe.listener.config;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class VirtualThreadExecutorConfig {

	@Bean(name = "listenerVirtualTaskExecutor", destroyMethod = "close")
	public ExecutorService listenerVirtualTaskExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

	@Bean(name = "listenerFanoutChunkExecutor", destroyMethod = "close")
	public ExecutorService listenerFanoutChunkExecutor(WebSocketProperties webSocketProperties, WebSocketMetrics webSocketMetrics) {
		int parallelism = Math.max(1, webSocketProperties.fanoutChunkParallelism());
		int queueCapacity = Math.max(parallelism, webSocketProperties.fanoutChunkQueueCapacity());
		return new ThreadPoolExecutor(
				parallelism, parallelism,
				0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(queueCapacity),
				droppingHandler("fanout_chunk", webSocketMetrics)
		);
	}

	@Bean(name = "listenerMarketEventExecutor", destroyMethod = "close")
	public ExecutorService listenerMarketEventExecutor(WebSocketProperties webSocketProperties, WebSocketMetrics webSocketMetrics) {
		int parallelism = Math.max(1, webSocketProperties.eventDispatchParallelism());
		int queueCapacity = Math.max(parallelism, webSocketProperties.eventDispatchQueueCapacity());
		return new ThreadPoolExecutor(
				parallelism, parallelism,
				0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(queueCapacity),
				droppingHandler("market_event", webSocketMetrics)
		);
	}

	private RejectedExecutionHandler droppingHandler(String executorName, WebSocketMetrics metrics) {
		return (task, executor) -> {
			metrics.executorTaskDropped(executorName);
			if (!executor.isShutdown()) {
				executor.getQueue().poll();
				executor.execute(task);
			}
		};
	}
}

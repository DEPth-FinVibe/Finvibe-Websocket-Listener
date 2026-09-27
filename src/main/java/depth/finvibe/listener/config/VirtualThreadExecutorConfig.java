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

	/**
	 * Redis 현재가 메시지를 도착 순서대로 한 스레드에서 풀어 레인에 넘긴다. 메시지 간 순서가 바뀌지 않게 한다(#17 D19).
	 */
	@Bean(name = "listenerPriceIngressExecutor", destroyMethod = "close")
	public ExecutorService listenerPriceIngressExecutor() {
		return Executors.newSingleThreadExecutor(Thread.ofPlatform().name("price-ingress").daemon(true).factory());
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

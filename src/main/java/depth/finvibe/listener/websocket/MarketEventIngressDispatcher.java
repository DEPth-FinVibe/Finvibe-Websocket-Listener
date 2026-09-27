package depth.finvibe.listener.websocket;

import depth.finvibe.listener.config.WebSocketProperties;
import depth.finvibe.listener.metrics.WebSocketMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Redis에서 받은 틱을 종목별 레인에 나눠 순서대로 브로드캐스트한다.
 * <p>
 * 같은 종목은 늘 같은 레인이라 도착 순서가 유지된다. 종목별 최신값만 남기던 합치기는 없앴다(#17 D19).
 * 레인 큐가 가득 차면 넣는 쪽(Redis 메시지 처리 스레드)을 기다리게 해 틱을 버리지 않는다.
 */
@Component
public class MarketEventIngressDispatcher {
	private static final Logger log = LoggerFactory.getLogger(MarketEventIngressDispatcher.class);

	private final MarketEventBroadcaster marketEventBroadcaster;
	private final WebSocketMetrics webSocketMetrics;
	private final int laneCount;
	private final int queueCapacity;
	private final List<Lane> lanes = new ArrayList<>();
	private volatile boolean running;

	public MarketEventIngressDispatcher(
			MarketEventBroadcaster marketEventBroadcaster,
			WebSocketMetrics webSocketMetrics,
			WebSocketProperties webSocketProperties
	) {
		this.marketEventBroadcaster = marketEventBroadcaster;
		this.webSocketMetrics = webSocketMetrics;
		this.laneCount = Math.max(1, webSocketProperties.eventDispatchParallelism());
		this.queueCapacity = Math.max(1, webSocketProperties.eventDispatchQueueCapacity());
	}

	@PostConstruct
	void start() {
		running = true;
		for (int i = 0; i < laneCount; i++) {
			Lane lane = new Lane(i, new ArrayBlockingQueue<>(queueCapacity));
			lane.thread.start();
			lanes.add(lane);
		}
	}

	@PreDestroy
	void stop() {
		running = false;
		lanes.forEach(lane -> lane.thread.interrupt());
	}

	public void submit(JsonNode event) {
		JsonNode stockIdNode = event.path("stockId");
		if (stockIdNode == null || !stockIdNode.isNumber()) {
			return;
		}
		Lane lane = lanes.get(Math.floorMod(Long.hashCode(stockIdNode.asLong()), lanes.size()));
		try {
			lane.queue.put(event);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			webSocketMetrics.executorTaskDropped("market_event_interrupted");
		}
	}

	private final class Lane {
		private final BlockingQueue<JsonNode> queue;
		private final Thread thread;

		private Lane(int index, BlockingQueue<JsonNode> queue) {
			this.queue = queue;
			this.thread = Thread.ofPlatform().name("market-event-lane-" + index).daemon(true).unstarted(this::run);
		}

		private void run() {
			while (running) {
				try {
					JsonNode event = queue.poll(200, TimeUnit.MILLISECONDS);
					if (event != null) {
						marketEventBroadcaster.broadcastCurrentPrice(event);
					}
				} catch (InterruptedException ex) {
					return;
				} catch (RuntimeException ex) {
					log.warn("Failed to broadcast market event.", ex);
				}
			}
		}
	}
}

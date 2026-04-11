package depth.finvibe.listener.websocket;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MarketEventIngressDispatcher {

	private final MarketEventBroadcaster marketEventBroadcaster;
	private final WebSocketMetrics webSocketMetrics;
	private final Executor marketEventExecutor;
	private final Map<Long, JsonNode> latestEventsByStock = new ConcurrentHashMap<>();
	private final Set<Long> drainingStocks = ConcurrentHashMap.newKeySet();

	public MarketEventIngressDispatcher(
			MarketEventBroadcaster marketEventBroadcaster,
			WebSocketMetrics webSocketMetrics,
			@Qualifier("listenerMarketEventExecutor") Executor marketEventExecutor
	) {
		this.marketEventBroadcaster = marketEventBroadcaster;
		this.webSocketMetrics = webSocketMetrics;
		this.marketEventExecutor = marketEventExecutor;
	}

	public void submit(JsonNode event) {
		Long stockId = longOrNull(event.path("stockId"));
		if (stockId == null) {
			return;
		}

		JsonNode previous = latestEventsByStock.put(stockId, event.deepCopy());
		if (previous != null) {
			webSocketMetrics.eventIngressCoalesced();
		}

		if (drainingStocks.add(stockId)) {
			marketEventExecutor.execute(() -> drainStock(stockId));
		}
	}

	private void drainStock(Long stockId) {
		try {
			while (true) {
				JsonNode event = latestEventsByStock.remove(stockId);
				if (event == null) {
					return;
				}
				marketEventBroadcaster.broadcastCurrentPrice(event);
			}
		} finally {
			drainingStocks.remove(stockId);
			if (latestEventsByStock.containsKey(stockId) && drainingStocks.add(stockId)) {
				marketEventExecutor.execute(() -> drainStock(stockId));
			}
		}
	}

	private Long longOrNull(JsonNode node) {
		if (node == null || node.isNull() || !node.isNumber()) {
			return null;
		}
		return node.asLong();
	}
}

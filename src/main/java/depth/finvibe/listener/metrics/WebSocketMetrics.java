package depth.finvibe.listener.metrics;

import depth.finvibe.listener.websocket.SessionRegistry;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketMetrics {
	private static final Duration LATENCY_MIN_EXPECTED = Duration.ofMillis(1);
	private static final Duration LATENCY_MAX_EXPECTED = Duration.ofMinutes(10);
	private static final Duration[] LATENCY_SLOS = new Duration[]{
			Duration.ofMillis(1),
			Duration.ofMillis(5),
			Duration.ofMillis(10),
			Duration.ofMillis(25),
			Duration.ofMillis(50),
			Duration.ofMillis(100),
			Duration.ofMillis(250),
			Duration.ofMillis(500),
			Duration.ofSeconds(1),
			Duration.ofSeconds(2),
			Duration.ofSeconds(5),
			Duration.ofSeconds(10),
			Duration.ofSeconds(15),
			Duration.ofSeconds(30),
			Duration.ofSeconds(45),
			Duration.ofSeconds(60),
			Duration.ofSeconds(90),
			Duration.ofSeconds(120),
			Duration.ofSeconds(180),
			Duration.ofSeconds(300),
			Duration.ofSeconds(600)
	};

	private final MeterRegistry meterRegistry;
	private final Map<String, Timer> timers = new ConcurrentHashMap<>();
	private final Map<String, DistributionSummary> summaries = new ConcurrentHashMap<>();

	public WebSocketMetrics(MeterRegistry meterRegistry, SessionRegistry sessionRegistry) {
		this.meterRegistry = meterRegistry;

		Gauge.builder("finvibe_ws_active_connections", sessionRegistry, SessionRegistry::getActiveSessionCount)
				.description("Active websocket connections on this instance")
				.register(meterRegistry);

		Gauge.builder("finvibe_ws_subscribed_stocks", sessionRegistry, SessionRegistry::getSubscribedStockCount)
				.description("Unique subscribed stock ids on this instance")
				.register(meterRegistry);

		Gauge.builder("finvibe_ws_total_subscriptions", sessionRegistry, SessionRegistry::getTotalSubscriptions)
				.description("Total websocket subscriptions on this instance")
				.register(meterRegistry);

		for (String executorName : new String[]{"fanout_chunk", "market_event"}) {
			io.micrometer.core.instrument.Counter.builder("finvibe_ws_executor_task_dropped_total")
					.description("Number of tasks dropped by executor due to queue saturation")
					.tag("executor", executorName)
					.register(meterRegistry);
		}
	}

	public void connectionOpened() {
		meterRegistry.counter("finvibe_ws_connections_opened_total").increment();
	}

	public void connectionClosed(String reason) {
		meterRegistry.counter("finvibe_ws_connections_closed_total", "reason", reason).increment();
	}

	public void connectionClosedCode(int code, String label) {
		meterRegistry.counter(
				"finvibe_ws_connections_closed_code_total",
				"code",
				String.valueOf(code),
				"label",
				label
		).increment();
	}

	public void authAttempt() {
		meterRegistry.counter("finvibe_ws_auth_attempts_total").increment();
	}

	public void authSuccess() {
		meterRegistry.counter("finvibe_ws_auth_success_total").increment();
	}

	public void authFailure(String reason) {
		meterRegistry.counter("finvibe_ws_auth_failure_total", "reason", reason).increment();
	}

	public void invalidMessage(String type) {
		meterRegistry.counter("finvibe_ws_invalid_messages_total", "type", type).increment();
	}

	public void subscribeRequest() {
		meterRegistry.counter("finvibe_ws_subscribe_requests_total").increment();
	}

	public void unsubscribeRequest() {
		meterRegistry.counter("finvibe_ws_unsubscribe_requests_total").increment();
	}

	public void subscriptionAdded() {
		meterRegistry.counter("finvibe_ws_subscriptions_added_total").increment();
	}

	public void subscriptionsRemoved(long count) {
		if (count <= 0) {
			return;
		}
		meterRegistry.counter("finvibe_ws_subscriptions_removed_total").increment(count);
	}

	public void pingSent() {
		meterRegistry.counter("finvibe_ws_ping_sent_total").increment();
	}

	public void pongReceived() {
		meterRegistry.counter("finvibe_ws_pong_received_total").increment();
	}

	public void pingTimeout() {
		meterRegistry.counter("finvibe_ws_ping_timeout_total").increment();
	}

	public void redisEventConsumed() {
		meterRegistry.counter("finvibe_ws_redis_events_consumed_total").increment();
	}

	public void redisEventSourceToConsumeLatency(long latencyMs) {
		recordLatency("finvibe_ws_event_source_to_consume_latency", latencyMs);
	}

	public void redisEventFailed() {
		meterRegistry.counter("finvibe_ws_redis_events_failed_total").increment();
	}

	public void eventBroadcasted() {
		meterRegistry.counter("finvibe_ws_events_broadcast_total").increment();
	}

	public void eventIngressCoalesced() {
		meterRegistry.counter("finvibe_ws_event_ingress_coalesced_total").increment();
	}

	public void eventOutboundCoalesced() {
		meterRegistry.counter("finvibe_ws_event_outbound_coalesced_total").increment();
	}

	public void eventOutboundStaleDrop() {
		meterRegistry.counter("finvibe_ws_event_outbound_stale_drop_total").increment();
	}

	public void eventSourceToBroadcastLatency(long latencyMs) {
		recordLatency("finvibe_ws_event_source_to_broadcast_latency", latencyMs);
	}

	public void eventConsumeToBroadcastLatency(long latencyMs) {
		recordLatency("finvibe_ws_event_consume_to_broadcast_latency", latencyMs);
	}

	public void eventSourceToEnqueueLatency(long latencyMs) {
		recordLatency("finvibe_ws_event_source_to_enqueue_latency", latencyMs);
	}

	public void eventBroadcastToEnqueueLatency(long latencyMs) {
		recordLatency("finvibe_ws_event_broadcast_to_enqueue_latency", latencyMs);
	}

	public void eventDelivered() {
		meterRegistry.counter("finvibe_ws_event_deliveries_total").increment();
	}

	public void eventSourceToDeliveryLatency(long latencyMs) {
		recordLatency("finvibe_ws_event_source_to_delivery_latency", latencyMs);
	}

	public void eventSourceToSendMessageLatency(long latencyMs) {
		recordLatency("finvibe_ws_event_source_to_send_message_latency", latencyMs);
	}

	public void outboundDataDeliveryLatency(long latencyMs) {
		recordLatency("finvibe_ws_outbound_data_delivery_latency", latencyMs);
	}

	public void outboundControlDeliveryLatency(long latencyMs) {
		recordLatency("finvibe_ws_outbound_control_delivery_latency", latencyMs);
	}

	public void eventDeliveryFailed() {
		meterRegistry.counter("finvibe_ws_event_delivery_failures_total").increment();
	}

	public void eventDeliveryFailed(String reason) {
		meterRegistry.counter("finvibe_ws_event_delivery_failures_by_reason_total", "reason", reason).increment();
	}

	public void closeInitiated(String source, int code) {
		meterRegistry.counter(
				"finvibe_ws_close_initiated_total",
				"source",
				source,
				"code",
				String.valueOf(code)
		).increment();
	}

	public void watcherOp(String operation) {
		meterRegistry.counter("finvibe_ws_watcher_ops_total", "operation", operation).increment();
	}

	public void watcherError(String operation) {
		meterRegistry.counter("finvibe_ws_watcher_errors_total", "operation", operation).increment();
	}

	public void sessionQueueOverflow(String stage) {
		meterRegistry.counter("finvibe_ws_session_queue_overflow_total", "stage", stage).increment();
	}

	public void sessionTaskFailure(String stage) {
		meterRegistry.counter("finvibe_ws_session_task_failures_total", "stage", stage).increment();
	}

	public void sessionTaskQueueWait(String stage, long latencyMs) {
		recordLatency("finvibe_ws_session_task_queue_wait_latency", stage, latencyMs);
	}

	public void maintenanceSkipped(String operation, String reason) {
		meterRegistry.counter("finvibe_ws_maintenance_skipped_total", "operation", operation, "reason", reason).increment();
	}

	public void slowConsumerClosed(String reason) {
		meterRegistry.counter("finvibe_ws_slow_consumer_closed_total", "reason", reason).increment();
	}

	public void executorTaskDropped(String executorName) {
		meterRegistry.counter("finvibe_ws_executor_task_dropped_total", "executor", executorName).increment();
	}

	public void outboundDataWriteDuration(long latencyMs) {
		recordLatency("finvibe_ws_outbound_data_write_duration", latencyMs);
	}

	public void outboundDataEnqueueToWriteStartLatency(long latencyMs) {
		recordLatency("finvibe_ws_outbound_data_enqueue_to_write_start_latency", latencyMs);
	}

	public void outboundControlWriteDuration(long latencyMs) {
		recordLatency("finvibe_ws_outbound_control_write_duration", latencyMs);
	}

	public void outboundControlEnqueueToWriteStartLatency(long latencyMs) {
		recordLatency("finvibe_ws_outbound_control_enqueue_to_write_start_latency", latencyMs);
	}

	public void outboundDataBytesSent(int bytes) {
		recordSummary("finvibe_ws_outbound_data_bytes_sent", bytes);
	}

	public void outboundControlBytesSent(int bytes) {
		recordSummary("finvibe_ws_outbound_control_bytes_sent", bytes);
	}

	public void redisPingLatency(long latencyMs) {
		recordLatency("finvibe_ws_redis_ping_latency", latencyMs);
	}

	public void sessionQueueDepthOnEnqueue(int depth, String stage) {
		recordSummary("finvibe_ws_session_queue_depth_on_enqueue", stage, depth);
	}

	private void recordLatency(String metricName, long latencyMs) {
		long safeLatencyMs = Math.max(0, latencyMs);
		timer(metricName).record(Duration.ofMillis(safeLatencyMs));
	}

	private void recordLatency(String metricName, String stage, long latencyMs) {
		long safeLatencyMs = Math.max(0, latencyMs);
		timer(metricName, stage).record(Duration.ofMillis(safeLatencyMs));
	}

	private void recordSummary(String metricName, int value) {
		summary(metricName).record(Math.max(0, value));
	}

	private void recordSummary(String metricName, String stage, int value) {
		summary(metricName, stage).record(Math.max(0, value));
	}

	private Timer timer(String metricName) {
		return timers.computeIfAbsent(metricName, key -> Timer.builder(key)
				.description("WebSocket event latency in listener pipeline")
				.minimumExpectedValue(LATENCY_MIN_EXPECTED)
				.maximumExpectedValue(LATENCY_MAX_EXPECTED)
				.serviceLevelObjectives(LATENCY_SLOS)
				.publishPercentileHistogram()
				.register(meterRegistry));
	}

	private Timer timer(String metricName, String stage) {
		String cacheKey = metricName + "|stage=" + stage;
		return timers.computeIfAbsent(cacheKey, key -> Timer.builder(metricName)
				.description("WebSocket stage latency in listener pipeline")
				.tag("stage", stage)
				.minimumExpectedValue(LATENCY_MIN_EXPECTED)
				.maximumExpectedValue(LATENCY_MAX_EXPECTED)
				.serviceLevelObjectives(LATENCY_SLOS)
				.publishPercentileHistogram()
				.register(meterRegistry));
	}

	private DistributionSummary summary(String metricName) {
		return summaries.computeIfAbsent(metricName, key -> DistributionSummary.builder(key)
				.baseUnit("bytes")
				.publishPercentileHistogram()
				.register(meterRegistry));
	}

	private DistributionSummary summary(String metricName, String stage) {
		String cacheKey = metricName + "|stage=" + stage;
		return summaries.computeIfAbsent(cacheKey, key -> DistributionSummary.builder(metricName)
				.baseUnit("tasks")
				.tag("stage", stage)
				.publishPercentileHistogram()
				.register(meterRegistry));
	}
}

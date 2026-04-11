package depth.finvibe.listener.metrics;

import depth.finvibe.listener.websocket.SessionRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class WebSocketMetrics {

	private final MeterRegistry meterRegistry;

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

	public void eventSourceToBroadcastLatency(long latencyMs) {
		recordLatency("finvibe_ws_event_source_to_broadcast_latency", latencyMs);
	}

	public void eventSourceToEnqueueLatency(long latencyMs) {
		recordLatency("finvibe_ws_event_source_to_enqueue_latency", latencyMs);
	}

	public void eventDelivered() {
		meterRegistry.counter("finvibe_ws_event_deliveries_total").increment();
	}

	public void eventSourceToDeliveryLatency(long latencyMs) {
		recordLatency("finvibe_ws_event_source_to_delivery_latency", latencyMs);
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

	private void recordLatency(String metricName, long latencyMs) {
		long safeLatencyMs = Math.max(0, latencyMs);
		Timer.builder(metricName)
				.description("WebSocket event latency in listener pipeline")
				.publishPercentileHistogram()
				.register(meterRegistry)
				.record(Duration.ofMillis(safeLatencyMs));
	}
}

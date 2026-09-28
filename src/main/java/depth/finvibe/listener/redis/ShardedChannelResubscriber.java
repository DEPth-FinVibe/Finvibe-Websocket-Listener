package depth.finvibe.listener.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Redis 마스터가 바뀐 뒤 샤드 채널 구독을 현재 마스터로 되살립니다.
 * <p>
 * 샤드 채널은 슬롯을 가진 마스터에서만 구독됩니다. 마스터가 복제본으로 내려가면 Redis가 구독을 해제하고,
 * 파드가 재시작되면 연결이 끊긴 뒤 예전 노드로만 다시 붙습니다. 두 경우 모두 토폴로지를 새로 읽고 모든 채널을
 * 다시 구독해야 합니다. 이미 구독된 채널을 다시 구독해도 Redis에서는 아무 일도 일어나지 않습니다.
 * <p>
 * 전환 중에는 이벤트가 몰려 오므로 짧게 기다렸다 한 번만 실행하고, 실패하면 다시 예약합니다.
 */
class ShardedChannelResubscriber {
	private static final Logger log = LoggerFactory.getLogger(ShardedChannelResubscriber.class);

	private final List<String> channels;
	private final Runnable refreshTopology;
	private final Consumer<List<String>> subscribe;
	private final ScheduledExecutorService scheduler;
	private final long delayMillis;
	private final Runnable onResubscribed;
	private final AtomicBoolean scheduled = new AtomicBoolean();

	ShardedChannelResubscriber(
			List<String> channels,
			Runnable refreshTopology,
			Consumer<List<String>> subscribe,
			ScheduledExecutorService scheduler,
			long delayMillis,
			Runnable onResubscribed
	) {
		this.channels = List.copyOf(channels);
		this.refreshTopology = refreshTopology;
		this.subscribe = subscribe;
		this.scheduler = scheduler;
		this.delayMillis = delayMillis;
		this.onResubscribed = onResubscribed;
	}

	void request(String reason) {
		if (!scheduled.compareAndSet(false, true)) {
			return;
		}
		log.info("Scheduling sharded redis resubscribe. reason={}", reason);
		scheduler.schedule(this::resubscribe, delayMillis, TimeUnit.MILLISECONDS);
	}

	private void resubscribe() {
		// 실행 중에 들어온 요청도 놓치지 않도록 먼저 비운다.
		scheduled.set(false);
		try {
			refreshTopology.run();
			subscribe.accept(channels);
			onResubscribed.run();
			log.info("Resubscribed sharded redis channels={}", channels);
		} catch (RuntimeException ex) {
			log.warn("Failed to resubscribe sharded redis channels. Retrying.", ex);
			request("retry");
		}
	}
}

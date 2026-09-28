package depth.finvibe.listener.redis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ShardedChannelResubscriberTest {

	private final ManualScheduler scheduler = new ManualScheduler();
	private final List<String> events = new ArrayList<>();
	private final AtomicInteger resubscribed = new AtomicInteger();

	@Test
	void 몰려온_요청은_한_번만_토폴로지를_갱신하고_모든_채널을_다시_구독한다() {
		ShardedChannelResubscriber resubscriber = resubscriber(channels -> events.add("subscribe " + channels));

		resubscriber.request("sunsubscribed a:{0}");
		resubscriber.request("sunsubscribed a:{1}");
		resubscriber.request("connection activated");

		assertThat(scheduler.pending).hasSize(1);
		scheduler.runAll();

		assertThat(events).containsExactly("refresh", "subscribe [a, a:{0}, a:{1}]");
		assertThat(resubscribed).hasValue(1);
	}

	@Test
	void 실행이_끝난_뒤_들어온_요청은_다시_예약한다() {
		ShardedChannelResubscriber resubscriber = resubscriber(channels -> events.add("subscribe"));

		resubscriber.request("first");
		scheduler.runAll();
		resubscriber.request("second");
		scheduler.runAll();

		assertThat(events).containsExactly("refresh", "subscribe", "refresh", "subscribe");
	}

	@Test
	void 구독에_실패하면_다시_시도한다() {
		AtomicInteger attempts = new AtomicInteger();
		ShardedChannelResubscriber resubscriber = resubscriber(channels -> {
			if (attempts.incrementAndGet() == 1) {
				throw new IllegalStateException("MOVED");
			}
			events.add("subscribe");
		});

		resubscriber.request("sunsubscribed");
		scheduler.runAll();
		assertThat(resubscribed).hasValue(0);
		assertThat(scheduler.pending).hasSize(1);

		scheduler.runAll();
		assertThat(events).containsExactly("refresh", "refresh", "subscribe");
		assertThat(resubscribed).hasValue(1);
	}

	private ShardedChannelResubscriber resubscriber(java.util.function.Consumer<List<String>> subscribe) {
		return new ShardedChannelResubscriber(
				List.of("a", "a:{0}", "a:{1}"),
				() -> events.add("refresh"),
				subscribe,
				scheduler,
				1_000,
				resubscribed::incrementAndGet);
	}

	/**
	 * 예약된 작업을 테스트가 직접 실행합니다.
	 */
	private static final class ManualScheduler extends ScheduledThreadPoolExecutor implements ScheduledExecutorService {
		private final List<Runnable> pending = new ArrayList<>();

		private ManualScheduler() {
			super(1);
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
			pending.add(command);
			return new DoneFuture();
		}

		void runAll() {
			List<Runnable> tasks = new ArrayList<>(pending);
			pending.clear();
			tasks.forEach(Runnable::run);
		}
	}

	private static final class DoneFuture implements ScheduledFuture<Object> {
		@Override
		public long getDelay(TimeUnit unit) {
			return 0;
		}

		@Override
		public int compareTo(Delayed other) {
			return 0;
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return false;
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public boolean isDone() {
			return true;
		}

		@Override
		public Object get() {
			return null;
		}

		@Override
		public Object get(long timeout, TimeUnit unit) {
			return null;
		}
	}
}

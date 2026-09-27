package depth.finvibe.listener.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ClientSession {
	private static final Logger log = LoggerFactory.getLogger(ClientSession.class);
	private static final int CONTROL_TASK_BURST_LIMIT = 2;
	private static final String ANONYMOUS_WATCHER_PREFIX = "anon:";
	static final int DEFAULT_DATA_BACKLOG_LIMIT = 10_000;
	static final int DEFAULT_MAX_ITEMS_PER_FRAME = 256;

	/**
	 * 쌓인 틱 여러 건을 웹소켓 프레임 하나로 보낸다.
	 */
	@FunctionalInterface
	public interface DataFrameSender {
		void send(ClientSession clientSession, List<PendingItem> items);
	}

	/**
	 * 보낼 틱 하나. sourceTs는 틱이 만들어진 시각(epoch ms)이고, 없으면 0이다. 종단간 지연을 재는 데 쓴다.
	 */
	public record PendingItem(String json, long sourceTs) {
	}

	public enum DataEnqueueResult {
		ENQUEUED,
		// 받는 속도가 느려 보내지 못한 틱이 한도를 넘었다. 틱을 버리지 않고 연결을 끊는다(#17 D24).
		BACKLOG_EXCEEDED,
		CLOSED
	}

	private final WebSocketSession webSocketSession;
	private final long connectedAtEpochMs;
	private final Executor virtualTaskExecutor;
	private final ArrayBlockingQueue<Runnable> sessionTaskQueue;
	// 보낼 틱(JSON 항목)을 도착 순서대로 쌓는다. 합치거나 버리지 않고, 보낼 때 여러 건을 프레임 하나로 묶는다.
	private final ConcurrentLinkedQueue<PendingItem> pendingDataItems = new ConcurrentLinkedQueue<>();
	private final AtomicInteger pendingDataCount = new AtomicInteger();
	private final int dataBacklogLimit;
	private final int maxItemsPerFrame;
	private volatile DataFrameSender dataFrameSender;
	private final AtomicBoolean queueDraining = new AtomicBoolean(false);
	private volatile boolean queueClosed;
	private volatile Long userId;
	private volatile boolean authenticated;
	/**
	 * 세션이 데이터 수신/구독 가능한 상태인지 여부.
	 * 익명 구독이 허용되면 연결 직후부터 true, 아니면 auth 성공 시 true가 된다.
	 */
	private volatile boolean established;
	private volatile long lastPongAtEpochMs;
	private volatile long lastPingAtEpochMs;
	private volatile long lastOutboundAtEpochMs;
	private volatile long pendingDataSinceEpochMs;
	private volatile boolean pingPending;
	private volatile int missedPongCount;
	private final Set<Long> subscribedStockIds = ConcurrentHashMap.newKeySet();

	public ClientSession(
			WebSocketSession webSocketSession,
			long nowEpochMs,
			Executor virtualTaskExecutor,
			int queueCapacity,
			boolean establishedOnConnect
	) {
		this(webSocketSession, nowEpochMs, virtualTaskExecutor, queueCapacity, establishedOnConnect,
				DEFAULT_DATA_BACKLOG_LIMIT, DEFAULT_MAX_ITEMS_PER_FRAME);
	}

	public ClientSession(
			WebSocketSession webSocketSession,
			long nowEpochMs,
			Executor virtualTaskExecutor,
			int queueCapacity,
			boolean establishedOnConnect,
			int dataBacklogLimit,
			int maxItemsPerFrame
	) {
		this.dataBacklogLimit = dataBacklogLimit > 0 ? dataBacklogLimit : DEFAULT_DATA_BACKLOG_LIMIT;
		this.maxItemsPerFrame = maxItemsPerFrame > 0 ? maxItemsPerFrame : DEFAULT_MAX_ITEMS_PER_FRAME;
		this.webSocketSession = webSocketSession;
		this.established = establishedOnConnect;
		this.connectedAtEpochMs = nowEpochMs;
		this.lastPongAtEpochMs = nowEpochMs;
		this.lastOutboundAtEpochMs = nowEpochMs;
		this.pendingDataSinceEpochMs = 0L;
		this.virtualTaskExecutor = virtualTaskExecutor;
		this.sessionTaskQueue = new ArrayBlockingQueue<>(Math.max(32, queueCapacity));
	}

	public String getSessionId() {
		return webSocketSession.getId();
	}

	public WebSocketSession getWebSocketSession() {
		return webSocketSession;
	}

	public long getConnectedAtEpochMs() {
		return connectedAtEpochMs;
	}

	public Long getUserId() {
		return userId;
	}

	public boolean isAuthenticated() {
		return authenticated;
	}

	public boolean isEstablished() {
		return established;
	}

	public boolean isGuest() {
		return !authenticated;
	}

	/**
	 * watcher index(Redis)에 기록할 식별자.
	 * 로그인 세션은 userId, 익명 세션은 세션 단위 식별자를 사용한다.
	 */
	public String getWatcherId() {
		Long currentUserId = userId;
		if (currentUserId != null) {
			return currentUserId.toString();
		}
		return ANONYMOUS_WATCHER_PREFIX + getSessionId();
	}

	public long getLastPongAtEpochMs() {
		return lastPongAtEpochMs;
	}

	public long getLastPingAtEpochMs() {
		return lastPingAtEpochMs;
	}

	public long getLastOutboundAtEpochMs() {
		return lastOutboundAtEpochMs;
	}

	public boolean isPingPending() {
		return pingPending;
	}

	public int getMissedPongCount() {
		return missedPongCount;
	}

	public void authenticate(Long userId) {
		this.userId = userId;
		this.authenticated = true;
		this.established = true;
	}

	public synchronized void markPongReceived(long nowEpochMs) {
		this.lastPongAtEpochMs = nowEpochMs;
		this.pingPending = false;
		this.missedPongCount = 0;
	}

	public synchronized void markPingSent(long nowEpochMs) {
		this.lastPingAtEpochMs = nowEpochMs;
		this.pingPending = true;
	}

	public void markOutboundSent(long nowEpochMs) {
		this.lastOutboundAtEpochMs = nowEpochMs;
	}

	public synchronized int incrementMissedPong() {
		this.pingPending = false;
		this.missedPongCount += 1;
		return missedPongCount;
	}

	public boolean addSubscription(Long stockId) {
		return subscribedStockIds.add(stockId);
	}

	public boolean removeSubscription(Long stockId) {
		return subscribedStockIds.remove(stockId);
	}

	public Set<Long> getSubscribedStockIds() {
		return Set.copyOf(subscribedStockIds);
	}

	public boolean enqueueSessionTask(Runnable task) {
		if (queueClosed) {
			return false;
		}

		boolean offered = sessionTaskQueue.offer(task);
		if (!offered) {
			return false;
		}

		scheduleQueueDrain();
		return true;
	}

	public DataEnqueueResult enqueueData(String item, DataFrameSender sender) {
		return enqueueData(item, 0L, sender);
	}

	public DataEnqueueResult enqueueData(String item, long sourceTs, DataFrameSender sender) {
		if (queueClosed) {
			return DataEnqueueResult.CLOSED;
		}
		if (pendingDataCount.incrementAndGet() > dataBacklogLimit) {
			pendingDataCount.decrementAndGet();
			return DataEnqueueResult.BACKLOG_EXCEEDED;
		}
		dataFrameSender = sender;
		pendingDataItems.offer(new PendingItem(item, sourceTs));
		if (pendingDataSinceEpochMs == 0L) {
			pendingDataSinceEpochMs = System.currentTimeMillis();
		}

		scheduleQueueDrain();
		return DataEnqueueResult.ENQUEUED;
	}

	public int getPendingDataCount() {
		return pendingDataCount.get();
	}

	public int getQueuedTaskCount() {
		return sessionTaskQueue.size();
	}

	public boolean hasPendingDataTasks() {
		return pendingDataCount.get() > 0;
	}

	public long getPendingDataSinceEpochMs() {
		return pendingDataSinceEpochMs;
	}

	public long getConnectedDurationMs(long nowEpochMs) {
		return Math.max(0, nowEpochMs - connectedAtEpochMs);
	}

	public void closeQueue() {
		queueClosed = true;
		sessionTaskQueue.clear();
		pendingDataItems.clear();
		pendingDataCount.set(0);
	}

	private void scheduleQueueDrain() {
		if (!queueDraining.compareAndSet(false, true)) {
			return;
		}

		virtualTaskExecutor.execute(this::drainQueueSafely);
	}

	private void drainQueueSafely() {
		int consecutiveControlTasks = 0;
		try {
			while (!queueClosed) {
				Runnable task = null;

				if (consecutiveControlTasks < CONTROL_TASK_BURST_LIMIT) {
					task = sessionTaskQueue.poll();
					if (task != null) {
						consecutiveControlTasks += 1;
					}
				}

				if (task == null) {
					List<PendingItem> frame = pollDataFrame();
					if (!frame.isEmpty()) {
						task = () -> dataFrameSender.send(this, frame);
						consecutiveControlTasks = 0;
					}
				}

				if (task == null) {
					task = sessionTaskQueue.poll();
					if (task != null) {
						consecutiveControlTasks += 1;
					}
				}

			if (task == null) {
				break;
			}

				try {
					task.run();
					// 대기가 비면 0, 남아 있으면 방금 보냈으므로 지금부터 다시 잰다.
					// 그래서 느린 소비자 판정(sweep)은 "쌓인 채 진전이 없는 시간"을 본다.
					pendingDataSinceEpochMs = pendingDataCount.get() == 0 ? 0L : System.currentTimeMillis();
				} catch (Exception ex) {
					log.debug("Session task failed. sessionId={}", getSessionId(), ex);
				}
			}
		} finally {
			queueDraining.set(false);
			if (!queueClosed && (!sessionTaskQueue.isEmpty() || !pendingDataItems.isEmpty())) {
				scheduleQueueDrain();
			}
		}
	}

	private List<PendingItem> pollDataFrame() {
		List<PendingItem> frame = new ArrayList<>();
		PendingItem item;
		while (frame.size() < maxItemsPerFrame && (item = pendingDataItems.poll()) != null) {
			frame.add(item);
		}
		if (!frame.isEmpty()) {
			pendingDataCount.addAndGet(-frame.size());
		}
		return frame;
	}
}

package depth.finvibe.listener.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientSession {
	private static final Logger log = LoggerFactory.getLogger(ClientSession.class);
	private static final int CONTROL_TASK_BURST_LIMIT = 2;
	private static final String ANONYMOUS_WATCHER_PREFIX = "anon:";

	private final WebSocketSession webSocketSession;
	private final long connectedAtEpochMs;
	private final Executor virtualTaskExecutor;
	private final ArrayBlockingQueue<Runnable> sessionTaskQueue;
	private final ConcurrentHashMap<String, Runnable> latestDataTasksByTopic = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<String> pendingDataTopics = new ConcurrentLinkedQueue<>();
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

	public boolean upsertLatestDataTask(String topic, Runnable task) {
		if (queueClosed) {
			return false;
		}

		Runnable previous = latestDataTasksByTopic.put(topic, task);
		if (previous == null) {
			pendingDataTopics.offer(topic);
			if (pendingDataSinceEpochMs == 0L) {
				pendingDataSinceEpochMs = System.currentTimeMillis();
			}
		}

		scheduleQueueDrain();
		return previous != null;
	}

	public int getQueuedTaskCount() {
		return sessionTaskQueue.size();
	}

	public boolean hasPendingDataTasks() {
		return !latestDataTasksByTopic.isEmpty();
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
		pendingDataTopics.clear();
		latestDataTasksByTopic.clear();
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
					String topic = pendingDataTopics.poll();
					if (topic != null) {
						task = latestDataTasksByTopic.remove(topic);
						if (task == null) {
							continue;
						}
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
					if (latestDataTasksByTopic.isEmpty() && pendingDataTopics.isEmpty()) {
						pendingDataSinceEpochMs = 0L;
					}
				} catch (Exception ex) {
					log.debug("Session task failed. sessionId={}", getSessionId(), ex);
				}
			}
		} finally {
			queueDraining.set(false);
			if (!queueClosed && (!sessionTaskQueue.isEmpty() || !pendingDataTopics.isEmpty())) {
				scheduleQueueDrain();
			}
		}
	}
}

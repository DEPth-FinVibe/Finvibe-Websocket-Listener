package depth.finvibe.listener.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientSession {
	private static final Logger log = LoggerFactory.getLogger(ClientSession.class);

	private final WebSocketSession webSocketSession;
	private final long connectedAtEpochMs;
	private final Executor virtualTaskExecutor;
	private final ArrayBlockingQueue<Runnable> sessionTaskQueue;
	private final AtomicBoolean queueDraining = new AtomicBoolean(false);
	private volatile boolean queueClosed;
	private volatile UUID userId;
	private volatile boolean authenticated;
	private volatile long lastPongAtEpochMs;
	private volatile long lastPingAtEpochMs;
	private volatile boolean pingPending;
	private volatile int missedPongCount;
	private final Set<Long> subscribedStockIds = ConcurrentHashMap.newKeySet();

	public ClientSession(WebSocketSession webSocketSession, long nowEpochMs, Executor virtualTaskExecutor, int queueCapacity) {
		this.webSocketSession = webSocketSession;
		this.connectedAtEpochMs = nowEpochMs;
		this.lastPongAtEpochMs = nowEpochMs;
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

	public UUID getUserId() {
		return userId;
	}

	public boolean isAuthenticated() {
		return authenticated;
	}

	public long getLastPongAtEpochMs() {
		return lastPongAtEpochMs;
	}

	public long getLastPingAtEpochMs() {
		return lastPingAtEpochMs;
	}

	public boolean isPingPending() {
		return pingPending;
	}

	public int getMissedPongCount() {
		return missedPongCount;
	}

	public void authenticate(UUID userId) {
		this.userId = userId;
		this.authenticated = true;
	}

	public void markPongReceived(long nowEpochMs) {
		this.lastPongAtEpochMs = nowEpochMs;
		this.pingPending = false;
		this.missedPongCount = 0;
	}

	public void markPingSent(long nowEpochMs) {
		this.lastPingAtEpochMs = nowEpochMs;
		this.pingPending = true;
	}

	public int incrementMissedPong() {
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

	public int getQueuedTaskCount() {
		return sessionTaskQueue.size();
	}

	public void closeQueue() {
		queueClosed = true;
		sessionTaskQueue.clear();
	}

	private void scheduleQueueDrain() {
		if (!queueDraining.compareAndSet(false, true)) {
			return;
		}

		virtualTaskExecutor.execute(this::drainQueueSafely);
	}

	private void drainQueueSafely() {
		try {
			while (!queueClosed) {
				Runnable task = sessionTaskQueue.poll();
				if (task == null) {
					break;
				}
				try {
					task.run();
				} catch (Exception ex) {
					log.debug("Session task failed. sessionId={}", getSessionId(), ex);
				}
			}
		} finally {
			queueDraining.set(false);
			if (!queueClosed && !sessionTaskQueue.isEmpty()) {
				scheduleQueueDrain();
			}
		}
	}
}

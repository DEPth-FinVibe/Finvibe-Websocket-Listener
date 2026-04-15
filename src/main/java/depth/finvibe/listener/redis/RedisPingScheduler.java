package depth.finvibe.listener.redis;

import depth.finvibe.listener.metrics.WebSocketMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisPingScheduler {

	private final StringRedisTemplate redisTemplate;
	private final WebSocketMetrics webSocketMetrics;

	@Scheduled(fixedRate = 1000)
	public void ping() {
		try {
			long start = System.currentTimeMillis();
			redisTemplate.execute((connection) -> connection.serverCommands().ping());
			webSocketMetrics.redisPingLatency(System.currentTimeMillis() - start);
		} catch (Exception ex) {
			log.warn("Redis ping failed", ex);
		}
	}
}

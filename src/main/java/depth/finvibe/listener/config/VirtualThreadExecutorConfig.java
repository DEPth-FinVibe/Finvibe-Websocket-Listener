package depth.finvibe.listener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class VirtualThreadExecutorConfig {

	@Bean(name = "listenerVirtualTaskExecutor", destroyMethod = "close")
	public ExecutorService listenerVirtualTaskExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}
}

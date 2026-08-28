package io.github.dockndevai.gateway.sample;

import io.github.dockndevai.gateway.usage.LlmUsageEvent;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A standalone LLM gateway in front of vLLM and Ollama.
 * <p>
 * There is no gateway wiring in this class on purpose: everything comes from the one dependency
 * plus {@code application.yml}. Dropping the same dependency into an existing Spring Cloud Gateway
 * app gets the same behaviour.
 */
@SpringBootApplication
public class LlmGatewaySampleApplication {

	public static void main(String[] args) {
		SpringApplication.run(LlmGatewaySampleApplication.class, args);
	}

	/**
	 * The hook to replace with a real billing sink. Every proxied request produces one of these,
	 * whether it was streamed or not, and whether it was served by the primary upstream or the
	 * failover one.
	 */
	@Component
	static class UsageLogger {

		private static final Log log = LogFactory.getLog(UsageLogger.class);

		@EventListener
		void onUsage(LlmUsageEvent event) {
			log.info(String.format(
					"usage tenant=%s key=%s model=%s route=%s status=%d streamed=%s "
							+ "prompt=%d completion=%d total=%d latency=%dms ttft=%s cost=%s",
					event.getTenant(), event.getPrincipalId(), event.getModel(), event.getRouteId(),
					event.getStatusCode(), event.isStreamed(), event.getUsage().promptTokens(),
					event.getUsage().completionTokens(), event.getUsage().totalTokens(),
					event.getLatency().toMillis(),
					(event.getTimeToFirstToken() == null) ? "n/a" : event.getTimeToFirstToken().toMillis() + "ms",
					(event.getCost() == null) ? "n/a" : String.format("%.6f", event.getCost())));
		}

	}

}

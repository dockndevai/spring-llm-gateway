package io.github.llmgw.gateway.metrics;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import io.github.llmgw.gateway.usage.LlmUsageEvent;

import org.springframework.context.ApplicationListener;

/**
 * Turns {@link LlmUsageEvent}s into Micrometer meters. Registered only when micrometer-core is on
 * the classpath, so the optional dependency stays optional; the event itself is always published,
 * which is the hook to use for billing.
 */
public class LlmMetricsListener implements ApplicationListener<LlmUsageEvent> {

	public static final String TOKENS = "llm.tokens";

	public static final String REQUESTS = "llm.requests";

	public static final String LATENCY = "llm.request.duration";

	public static final String TTFT = "llm.time.to.first.token";

	public static final String COST = "llm.cost";

	private final MeterRegistry registry;

	public LlmMetricsListener(MeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void onApplicationEvent(LlmUsageEvent event) {
		Tags base = Tags.of("model", event.getModel(), "tenant", event.getTenant(), "route", event.getRouteId());

		Counter.builder(REQUESTS)
			.tags(base.and("status", Integer.toString(event.getStatusCode()), "streamed",
					Boolean.toString(event.isStreamed())))
			.description("LLM requests proxied by the gateway")
			.register(this.registry)
			.increment();

		if (!event.getUsage().isEmpty()) {
			Counter.builder(TOKENS)
				.tags(base.and("type", "prompt"))
				.description("LLM tokens consumed")
				.baseUnit("tokens")
				.register(this.registry)
				.increment(event.getUsage().promptTokens());
			Counter.builder(TOKENS)
				.tags(base.and("type", "completion"))
				.description("LLM tokens consumed")
				.baseUnit("tokens")
				.register(this.registry)
				.increment(event.getUsage().completionTokens());
		}

		Timer.builder(LATENCY)
			.tags(base)
			.description("End to end latency of an LLM request")
			.register(this.registry)
			.record(event.getLatency().toNanos(), TimeUnit.NANOSECONDS);

		if (event.getTimeToFirstToken() != null) {
			Timer.builder(TTFT)
				.tags(base)
				.description("Delay before the first response chunk reached the client")
				.register(this.registry)
				.record(event.getTimeToFirstToken().toNanos(), TimeUnit.NANOSECONDS);
		}

		if (event.getCost() != null) {
			Counter.builder(COST)
				.tags(base)
				.description("Attributed cost of LLM usage")
				.register(this.registry)
				.increment(event.getCost());
		}
	}

}

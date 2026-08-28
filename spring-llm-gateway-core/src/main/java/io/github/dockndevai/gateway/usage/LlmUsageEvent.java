package io.github.dockndevai.gateway.usage;

import java.time.Duration;

import org.springframework.context.ApplicationEvent;
import org.springframework.lang.Nullable;

/**
 * Published once per completed LLM request. Listen for it to write billing records; the
 * built-in Micrometer meters are themselves just a listener on this event.
 */
public class LlmUsageEvent extends ApplicationEvent {

	private final String model;

	private final String tenant;

	private final String principalId;

	private final String routeId;

	private final int statusCode;

	private final boolean streamed;

	private final TokenUsage usage;

	private final Duration latency;

	@Nullable
	private final Duration timeToFirstToken;

	@Nullable
	private final Double cost;

	public LlmUsageEvent(Object source, String model, String tenant, String principalId, String routeId,
			int statusCode, boolean streamed, TokenUsage usage, Duration latency,
			@Nullable Duration timeToFirstToken, @Nullable Double cost) {
		super(source);
		this.model = model;
		this.tenant = tenant;
		this.principalId = principalId;
		this.routeId = routeId;
		this.statusCode = statusCode;
		this.streamed = streamed;
		this.usage = usage;
		this.latency = latency;
		this.timeToFirstToken = timeToFirstToken;
		this.cost = cost;
	}

	public String getModel() {
		return this.model;
	}

	public String getTenant() {
		return this.tenant;
	}

	public String getPrincipalId() {
		return this.principalId;
	}

	public String getRouteId() {
		return this.routeId;
	}

	public int getStatusCode() {
		return this.statusCode;
	}

	public boolean isStreamed() {
		return this.streamed;
	}

	public TokenUsage getUsage() {
		return this.usage;
	}

	public Duration getLatency() {
		return this.latency;
	}

	@Nullable
	public Duration getTimeToFirstToken() {
		return this.timeToFirstToken;
	}

	/** Cost derived from {@code llm.gateway.models.<model>} rates, or {@code null} when unpriced. */
	@Nullable
	public Double getCost() {
		return this.cost;
	}

	@Override
	public String toString() {
		return "LlmUsageEvent{model=" + this.model + ", tenant=" + this.tenant + ", status=" + this.statusCode
				+ ", streamed=" + this.streamed + ", usage=" + this.usage + ", latency=" + this.latency + '}';
	}

}

package io.github.llmgw.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import io.github.llmgw.gateway.auth.LlmPrincipal;
import io.github.llmgw.gateway.config.LlmGatewayProperties;
import io.github.llmgw.gateway.support.LlmAttributes;
import io.github.llmgw.gateway.support.LlmRequest;
import io.github.llmgw.gateway.usage.LlmUsageEvent;
import io.github.llmgw.gateway.usage.TailBuffer;
import io.github.llmgw.gateway.usage.TokenUsage;
import io.github.llmgw.gateway.usage.UsageParser;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;

/**
 * Observes the upstream response without consuming it, scrapes the token usage out of its tail
 * and publishes an {@link LlmUsageEvent}.
 * <p>
 * Ordering matters: this runs at {@code -2}, one step ahead of
 * {@code NettyWriteResponseFilter} ({@code -1}). That filter captures its own
 * {@code exchange.getResponse()} reference before writing, so a decorator installed any later in
 * the chain would simply never be written into and every request would meter as zero tokens.
 * <p>
 * Chunks are read with {@code DataBuffer.toString(Charset)}, which does not advance the read
 * position, so the bytes still reach the client untouched. Only the trailing
 * {@code metering.tail-buffer-size} characters are retained, because the usage object is last in
 * both streamed and non-streamed responses.
 */
public class LlmMeteringGlobalFilter implements GlobalFilter, Ordered {

	/** Ahead of {@code NettyWriteResponseFilter}, which sits at -1. */
	public static final int ORDER = -2;

	private static final Log log = LogFactory.getLog(LlmMeteringGlobalFilter.class);

	private final LlmGatewayProperties properties;

	private final ApplicationEventPublisher eventPublisher;

	public LlmMeteringGlobalFilter(LlmGatewayProperties properties, ApplicationEventPublisher eventPublisher) {
		this.properties = properties;
		this.eventPublisher = eventPublisher;
	}

	@Override
	public int getOrder() {
		return ORDER;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		if (!this.properties.getMetering().isEnabled()) {
			return chain.filter(exchange);
		}

		long startNanos = System.nanoTime();
		TailBuffer tail = new TailBuffer(this.properties.getMetering().getTailBufferSize());
		AtomicLong firstChunkNanos = new AtomicLong(0);
		AtomicBoolean recorded = new AtomicBoolean(false);

		ServerHttpResponse decorated = new ServerHttpResponseDecorator(exchange.getResponse()) {

			@Override
			public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
				return super.writeWith(Flux.from(body).doOnNext(this::observe));
			}

			@Override
			public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
				// Streaming responses arrive here. Map each inner publisher rather than
				// flattening, so flush boundaries — and therefore SSE framing — survive.
				return super.writeAndFlushWith(
						Flux.from(body).map(inner -> Flux.from(inner).doOnNext(this::observe)));
			}

			private void observe(DataBuffer buffer) {
				firstChunkNanos.compareAndSet(0, System.nanoTime());
				try {
					tail.append(buffer.toString(StandardCharsets.UTF_8));
				}
				catch (RuntimeException ex) {
					if (log.isDebugEnabled()) {
						log.debug("Could not read response chunk for metering: " + ex.getMessage());
					}
				}
			}
		};

		return chain.filter(exchange.mutate().response(decorated).build())
			.doFinally(signal -> {
				if (recorded.compareAndSet(false, true)) {
					record(exchange, tail, startNanos, firstChunkNanos.get());
				}
			});
	}

	private void record(ServerWebExchange exchange, TailBuffer tail, long startNanos, long firstChunkNanos) {
		TokenUsage usage;
		try {
			usage = UsageParser.parse(tail.tail()).orElse(TokenUsage.NONE);
		}
		catch (RuntimeException ex) {
			log.warn("Usage parsing failed", ex);
			usage = TokenUsage.NONE;
		}
		exchange.getAttributes().put(LlmAttributes.LLM_USAGE_ATTR, usage);

		// Route filters complete before this global filter does, so quota reconciliation is
		// registered as a callback here rather than reading the attribute from its own
		// terminal signal.
		runUsageCallbacks(exchange, usage);

		if (!this.properties.getMetering().isPublishEvents()) {
			return;
		}

		LlmRequest request = exchange.getAttribute(LlmAttributes.LLM_REQUEST_ATTR);
		LlmPrincipal principal = exchange.getAttribute(LlmAttributes.LLM_PRINCIPAL_ATTR);
		Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);

		String model = modelOf(exchange, request);
		Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
		Duration ttft = (firstChunkNanos > 0) ? Duration.ofNanos(firstChunkNanos - startNanos) : null;
		Integer status = (exchange.getResponse().getStatusCode() != null)
				? exchange.getResponse().getStatusCode().value() : 0;

		LlmUsageEvent event = new LlmUsageEvent(this, model,
				(principal != null) ? principal.tenant() : "anonymous",
				(principal != null) ? principal.id() : "anonymous", (route != null) ? route.getId() : "unrouted",
				status, request != null && request.stream(), usage, latency, ttft, cost(model, usage));
		try {
			this.eventPublisher.publishEvent(event);
		}
		catch (RuntimeException ex) {
			log.warn("LlmUsageEvent listener failed", ex);
		}
	}

	private String modelOf(ServerWebExchange exchange, LlmRequest request) {
		String routed = exchange.getAttribute(LlmAttributes.LLM_ROUTED_MODEL_ATTR);
		if (routed != null) {
			return routed;
		}
		return (request != null) ? request.modelOrUnknown() : "unknown";
	}

	@SuppressWarnings("unchecked")
	private void runUsageCallbacks(ServerWebExchange exchange, TokenUsage usage) {
		Object raw = exchange.getAttributes().remove(LlmAttributes.LLM_USAGE_CALLBACKS_ATTR);
		if (raw == null) {
			return;
		}
		for (Consumer<TokenUsage> callback : new ArrayList<>((List<Consumer<TokenUsage>>) raw)) {
			try {
				callback.accept(usage);
			}
			catch (RuntimeException ex) {
				log.warn("Usage callback failed", ex);
			}
		}
	}

	private Double cost(String model, TokenUsage usage) {
		LlmGatewayProperties.Model config = this.properties.getModels().get(model);
		if (config == null || usage.isEmpty()) {
			return null;
		}
		Double promptRate = config.getPromptCostPer1k();
		Double completionRate = config.getCompletionCostPer1k();
		if (promptRate == null && completionRate == null) {
			return null;
		}
		double total = 0.0;
		if (promptRate != null) {
			total += usage.promptTokens() / 1000.0 * promptRate;
		}
		if (completionRate != null) {
			total += usage.completionTokens() / 1000.0 * completionRate;
		}
		return total;
	}

	/** Registers a callback invoked once usage has been scraped for this exchange. */
	@SuppressWarnings("unchecked")
	public static void onUsage(ServerWebExchange exchange, Consumer<TokenUsage> callback) {
		Optional.ofNullable((List<Consumer<TokenUsage>>) exchange.getAttributes()
			.computeIfAbsent(LlmAttributes.LLM_USAGE_CALLBACKS_ATTR, k -> new ArrayList<Consumer<TokenUsage>>()))
			.ifPresent(list -> list.add(callback));
	}

}

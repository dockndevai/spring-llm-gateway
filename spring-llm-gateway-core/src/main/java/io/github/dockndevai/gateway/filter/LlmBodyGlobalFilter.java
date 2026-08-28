package io.github.dockndevai.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import io.github.dockndevai.gateway.config.LlmGatewayProperties;
import io.github.dockndevai.gateway.support.LlmAttributes;
import io.github.dockndevai.gateway.support.LlmRequest;
import io.github.dockndevai.gateway.support.LlmRequestParser;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;

/**
 * Parses the request body once per exchange and republishes it in a form the rest of the chain
 * can read repeatedly.
 * <p>
 * Ordering matters here. This filter sits at {@code HIGHEST_PRECEDENCE + 1500}, immediately
 * after {@code AdaptCachedBodyGlobalFilter} ({@code HIGHEST_PRECEDENCE + 1000}). If it ran any
 * earlier, a body already consumed by the {@code LlmModel} predicate would read as empty,
 * because the cached bytes are only swapped back onto the exchange by that filter. It stays well
 * ahead of the route filters (which are ordered 1, 2, 3...), so {@code LlmAuth} and
 * {@code LlmQuota} can count on {@link LlmAttributes#LLM_REQUEST_ATTR} being populated.
 * <p>
 * The decorator's {@code getBody()} is wrapped in {@link Flux#defer}, wrapping the byte array
 * afresh on every subscription. A {@code DataBuffer} can only be read once, and both retries and
 * the circuit breaker re-subscribe to the request body.
 */
public class LlmBodyGlobalFilter implements GlobalFilter, Ordered {

	/** Just after {@code AdaptCachedBodyGlobalFilter}, well before any route filter. */
	public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1500;

	private final ObjectMapper objectMapper;

	private final LlmGatewayProperties properties;

	public LlmBodyGlobalFilter(ObjectMapper objectMapper, LlmGatewayProperties properties) {
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	@Override
	public int getOrder() {
		return ORDER;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		if (!carriesJsonBody(exchange.getRequest())) {
			return chain.filter(exchange);
		}

		// Captured now because the circuit breaker replaces the request URI with the fallback
		// path, losing the path the fallback handler needs to call the secondary upstream with.
		exchange.getAttributes()
			.putIfAbsent(LlmAttributes.LLM_ORIGINAL_PATH_ATTR, exchange.getRequest().getURI().getRawPath());

		LlmRequest alreadyParsed = exchange.getAttribute(LlmAttributes.LLM_REQUEST_ATTR);
		if (alreadyParsed != null) {
			// The LlmModel predicate parsed it during route selection.
			return chain.filter(withRepeatableBody(exchange, alreadyParsed));
		}

		return DataBufferUtils.join(exchange.getRequest().getBody()).map(buffer -> {
			byte[] bytes = new byte[buffer.readableByteCount()];
			buffer.read(bytes);
			DataBufferUtils.release(buffer);
			return bytes;
		}).defaultIfEmpty(new byte[0]).flatMap(bytes -> {
			LlmRequest parsed = LlmRequestParser.parse(bytes, this.objectMapper);
			return chain.filter(withRepeatableBody(exchange, parsed));
		});
	}

	private ServerWebExchange withRepeatableBody(ServerWebExchange exchange, LlmRequest parsed) {
		byte[] body = parsed.body();
		if (this.properties.getMetering().isEnabled() && this.properties.getMetering().isInjectStreamUsage()
				&& parsed.stream()) {
			body = LlmRequestParser.injectStreamUsage(body, this.objectMapper);
		}

		LlmRequest effective = parsed.withBody(body);
		exchange.getAttributes().put(LlmAttributes.LLM_REQUEST_ATTR, effective);

		byte[] finalBody = body;
		HttpHeaders headers = new HttpHeaders();
		headers.putAll(exchange.getRequest().getHeaders());
		// Injecting stream_options changes the length, and a stale Content-Length makes the
		// upstream hang waiting for bytes that never arrive.
		headers.setContentLength(finalBody.length);
		headers.remove(HttpHeaders.TRANSFER_ENCODING);

		ServerHttpRequest decorated = new ServerHttpRequestDecorator(exchange.getRequest()) {
			@Override
			public HttpHeaders getHeaders() {
				return headers;
			}

			@Override
			public Flux<DataBuffer> getBody() {
				return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(finalBody)));
			}
		};
		return exchange.mutate().request(decorated).build();
	}

	private boolean carriesJsonBody(ServerHttpRequest request) {
		HttpMethod method = request.getMethod();
		if (!HttpMethod.POST.equals(method) && !HttpMethod.PUT.equals(method) && !HttpMethod.PATCH.equals(method)) {
			return false;
		}
		MediaType contentType = request.getHeaders().getContentType();
		return contentType == null || MediaType.APPLICATION_JSON.isCompatibleWith(contentType);
	}

}

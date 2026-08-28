package io.github.llmgw.gateway.fallback;

import java.net.URI;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import io.github.llmgw.gateway.config.LlmGatewayProperties;
import io.github.llmgw.gateway.support.LlmAttributes;
import io.github.llmgw.gateway.support.LlmRequest;
import io.github.llmgw.gateway.support.LlmRequestParser;
import io.github.llmgw.gateway.support.OpenAiError;

import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Handles {@code fallbackUri: forward:/__llm/fallback} on the stock {@code CircuitBreaker} filter.
 * <p>
 * Rather than reimplementing failover, this leans on the circuit breaker for the state machine and
 * only supplies the replay: it takes the request body the LLM body filter already cached,
 * optionally rewrites {@code model} to something smaller, and streams the secondary upstream's
 * response straight back to the client. With no fallback configured it answers with an
 * OpenAI-shaped 503, so clients see a structured error rather than a gateway stack trace.
 * <p>
 * This is a plain {@link WebHandler} writing directly to the exchange rather than a
 * {@code RouterFunction} returning a {@code ServerResponse}. That is deliberate:
 * {@code exchangeToMono} releases the upstream connection as soon as the Mono it returns
 * terminates, so handing the body off to a {@code ServerResponse} to be written later delivers an
 * empty body. Writing inside the exchange callback keeps the connection alive for exactly as long
 * as the body is streaming, which is what an SSE response needs.
 * <p>
 * The response still passes through the metering decorator, which was installed before the circuit
 * breaker ran, so failed-over requests are metered like any other.
 */
public class LlmFallbackHandler implements WebHandler {

	public static final String PATH = "/__llm/fallback";

	private static final String DEFAULT_PATH = "/v1/chat/completions";

	private static final Log log = LogFactory.getLog(LlmFallbackHandler.class);

	private final WebClient webClient;

	private final LlmGatewayProperties properties;

	private final ObjectMapper objectMapper;

	public LlmFallbackHandler(WebClient webClient, LlmGatewayProperties properties, ObjectMapper objectMapper) {
		this.webClient = webClient;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@Override
	public Mono<Void> handle(ServerWebExchange exchange) {
		LlmGatewayProperties.Fallback fallback = this.properties.getFallback();

		if (!StringUtils.hasText(fallback.getUri())) {
			return unavailable(exchange, " and no fallback upstream is configured");
		}

		LlmRequest llmRequest = exchange.getAttribute(LlmAttributes.LLM_REQUEST_ATTR);
		if (llmRequest == null || llmRequest.body().length == 0) {
			return unavailable(exchange, " and the original request body was not available to replay");
		}

		byte[] body = llmRequest.body();
		if (StringUtils.hasText(fallback.getModel())) {
			body = LlmRequestParser.withModel(body, fallback.getModel(), this.objectMapper);
			// So metering tags the request with the model that actually served it.
			exchange.getAttributes().put(LlmAttributes.LLM_ROUTED_MODEL_ATTR, fallback.getModel());
		}

		URI target = UriComponentsBuilder.fromUriString(fallback.getUri())
			.path(originalPath(exchange))
			.query(exchange.getRequest().getURI().getRawQuery())
			.build(true)
			.toUri();

		LlmGatewayProperties.Upstream upstream = this.properties.getUpstreams().get(fallback.getUpstream());

		return this.webClient.post()
			.uri(target)
			.contentType(MediaType.APPLICATION_JSON)
			.headers(headers -> {
				String accept = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ACCEPT);
				if (StringUtils.hasText(accept)) {
					headers.set(HttpHeaders.ACCEPT, accept);
				}
				if (upstream != null && StringUtils.hasText(upstream.getApiKey())) {
					String prefix = (upstream.getPrefix() == null) ? "" : upstream.getPrefix();
					headers.set(upstream.getHeader(), prefix + upstream.getApiKey());
				}
			})
			.bodyValue(body)
			.exchangeToMono(response -> {
				ServerHttpResponse out = exchange.getResponse();
				out.setStatusCode(response.statusCode());
				MediaType contentType = response.headers().asHttpHeaders().getContentType();
				if (contentType != null) {
					out.getHeaders().setContentType(contentType);
				}
				return out.writeWith(response.bodyToFlux(DataBuffer.class));
			})
			.onErrorResume(ex -> {
				log.warn("LLM fallback to " + target + " failed", ex);
				return unavailable(exchange, " and the fallback upstream also failed");
			});
	}

	/**
	 * The path the client originally asked for. Captured by the body filter; the fall-backs cover
	 * a non-JSON request and an exchange where that filter never ran.
	 */
	private String originalPath(ServerWebExchange exchange) {
		String captured = exchange.getAttribute(LlmAttributes.LLM_ORIGINAL_PATH_ATTR);
		if (StringUtils.hasText(captured) && !PATH.equals(captured)) {
			return captured;
		}
		Object originals = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
		if (originals instanceof Iterable<?> iterable) {
			for (Object candidate : iterable) {
				if (candidate instanceof URI uri && !PATH.equals(uri.getRawPath())) {
					return uri.getRawPath();
				}
			}
		}
		return DEFAULT_PATH;
	}

	private Mono<Void> unavailable(ServerWebExchange exchange, String detail) {
		if (exchange.getResponse().isCommitted()) {
			return Mono.empty();
		}
		Object cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
		String because = (cause instanceof Throwable throwable && StringUtils.hasText(throwable.getMessage()))
				? " (" + throwable.getMessage() + ")" : "";
		return OpenAiError.write(exchange, HttpStatus.SERVICE_UNAVAILABLE,
				"Upstream inference server is unavailable" + detail + because, "server_error",
				"upstream_unavailable");
	}

}

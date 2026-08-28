package io.github.llmgw.gateway.filter;

import java.util.List;
import java.util.Optional;

import reactor.core.publisher.Mono;

import io.github.llmgw.gateway.auth.LlmPrincipal;
import io.github.llmgw.gateway.auth.PrincipalResolver;
import io.github.llmgw.gateway.config.LlmGatewayProperties;
import io.github.llmgw.gateway.support.LlmAttributes;
import io.github.llmgw.gateway.support.LlmRequest;
import io.github.llmgw.gateway.support.OpenAiError;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

/**
 * Virtual keys. The client presents {@code Authorization: Bearer <token>}; the filter resolves it
 * to an {@link LlmPrincipal}, enforces that principal's model allow-list, and then replaces the
 * header with the real upstream credential so the client's token never reaches the inference
 * server.
 *
 * <pre>
 * filters:
 *   - LlmAuth
 * </pre>
 *
 * Which upstream credential goes out is derived from the requested model:
 * {@code llm.gateway.models.&lt;model&gt;.upstream} names an entry in
 * {@code llm.gateway.upstreams}. Pass an argument ({@code - LlmAuth=vllm}) to pin a route to one
 * upstream regardless of the model.
 * <p>
 * This is a plain route filter, so Spring Cloud Gateway orders it by its position in the
 * {@code filters:} (or {@code default-filters:}) list — 1, 2, 3... — which is after both LLM
 * global filters, so {@link LlmAttributes#LLM_REQUEST_ATTR} is already populated when it runs.
 * List {@code LlmAuth} before {@code LlmQuota}: the quota bucket is keyed on the principal this
 * filter resolves.
 */
public class LlmAuthGatewayFilterFactory extends AbstractGatewayFilterFactory<LlmAuthGatewayFilterFactory.Config> {

	private static final String BEARER = "Bearer ";

	private final PrincipalResolver principalResolver;

	private final LlmGatewayProperties properties;

	public LlmAuthGatewayFilterFactory(PrincipalResolver principalResolver, LlmGatewayProperties properties) {
		super(Config.class);
		this.principalResolver = principalResolver;
		this.properties = properties;
	}

	@Override
	public List<String> shortcutFieldOrder() {
		return List.of("upstream");
	}

	@Override
	public GatewayFilter apply(Config config) {
		return (exchange, chain) -> {
			String token = bearerToken(exchange);
			if (token == null) {
				return unauthorized(exchange, "Missing bearer token. Send 'Authorization: Bearer <key>'.");
			}
			// Deliberately not switchIfEmpty: chain.filter() returns Mono<Void>, which always
			// completes empty, so a switchIfEmpty placed after the flatMap would fire on every
			// successful request and write a 401 over a response already served upstream.
			return this.principalResolver.resolve(exchange, token)
				.map(Optional::of)
				.defaultIfEmpty(Optional.empty())
				.flatMap(resolved -> resolved
					.map(principal -> authorize(exchange, chain, config, principal))
					.orElseGet(() -> unauthorized(exchange, "Invalid API key.")));
		};
	}

	private Mono<Void> authorize(ServerWebExchange exchange,
			org.springframework.cloud.gateway.filter.GatewayFilterChain chain, Config config,
			LlmPrincipal principal) {
		LlmRequest request = exchange.getAttribute(LlmAttributes.LLM_REQUEST_ATTR);
		String model = (request != null) ? request.model() : null;

		if (!principal.allows(model)) {
			return OpenAiError.write(exchange, HttpStatus.FORBIDDEN,
					"The model '" + model + "' is not available to this key.", "invalid_request_error",
					"model_not_allowed");
		}

		exchange.getAttributes().put(LlmAttributes.LLM_PRINCIPAL_ATTR, principal);
		return chain.filter(withUpstreamCredential(exchange, config, model));
	}

	private ServerWebExchange withUpstreamCredential(ServerWebExchange exchange, Config config, String model) {
		LlmGatewayProperties.Upstream upstream = resolveUpstream(config, model);
		return exchange.mutate().request(builder -> builder.headers(headers -> {
			// Always drop the caller's token, even when no upstream credential is configured,
			// so a virtual key is never forwarded to the inference server.
			headers.remove(HttpHeaders.AUTHORIZATION);
			if (upstream != null && StringUtils.hasText(upstream.getApiKey())) {
				String prefix = (upstream.getPrefix() == null) ? "" : upstream.getPrefix();
				headers.set(upstream.getHeader(), prefix + upstream.getApiKey());
			}
		})).build();
	}

	private LlmGatewayProperties.Upstream resolveUpstream(Config config, String model) {
		if (StringUtils.hasText(config.getUpstream())) {
			return this.properties.getUpstreams().get(config.getUpstream());
		}
		if (model == null) {
			return null;
		}
		LlmGatewayProperties.Model modelConfig = this.properties.getModels().get(model);
		if (modelConfig == null || !StringUtils.hasText(modelConfig.getUpstream())) {
			return null;
		}
		return this.properties.getUpstreams().get(modelConfig.getUpstream());
	}

	private String bearerToken(ServerWebExchange exchange) {
		String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
		if (!StringUtils.hasText(header)) {
			return null;
		}
		if (header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
			String token = header.substring(BEARER.length()).trim();
			return token.isEmpty() ? null : token;
		}
		return null;
	}

	private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
		exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		return OpenAiError.write(exchange, HttpStatus.UNAUTHORIZED, message, "invalid_request_error",
				"invalid_api_key");
	}

	public static class Config {

		/** Optional. Pins this route to one entry in {@code llm.gateway.upstreams}. */
		private String upstream;

		public String getUpstream() {
			return this.upstream;
		}

		public Config setUpstream(String upstream) {
			this.upstream = upstream;
			return this;
		}

	}

}

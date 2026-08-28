package io.github.dockndevai.gateway.predicate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import io.github.dockndevai.gateway.support.LlmAttributes;
import io.github.dockndevai.gateway.support.LlmRequest;
import io.github.dockndevai.gateway.support.LlmRequestParser;

import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.handler.predicate.AbstractRoutePredicateFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.util.PatternMatchUtils;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;

/**
 * Selects a route on the {@code model} field of the JSON request body:
 *
 * <pre>
 * predicates:
 *   - Path=/v1/**
 *   - LlmModel=llama-3.1-70b*
 * </pre>
 *
 * Patterns are simple {@code *} globs, and several may be listed
 * ({@code LlmModel=llama-3.1-70b*,mixtral*}).
 * <p>
 * Modelled on {@code ReadBodyRoutePredicateFactory}: the body is read through
 * {@link ServerWebExchangeUtils#cacheRequestBodyAndRequest}, which caches it so that
 * {@code AdaptCachedBodyGlobalFilter} can hand the same bytes to the rest of the chain. The
 * parsed result is cached in an exchange attribute so that evaluating this predicate against
 * several candidate routes still only parses once.
 */
public class LlmModelRoutePredicateFactory extends AbstractRoutePredicateFactory<LlmModelRoutePredicateFactory.Config> {

	private final List<HttpMessageReader<?>> messageReaders;

	private final ObjectMapper objectMapper;

	public LlmModelRoutePredicateFactory(ObjectMapper objectMapper) {
		this(objectMapper, HandlerStrategies.withDefaults().messageReaders());
	}

	public LlmModelRoutePredicateFactory(ObjectMapper objectMapper, List<HttpMessageReader<?>> messageReaders) {
		super(Config.class);
		this.objectMapper = objectMapper;
		this.messageReaders = messageReaders;
	}

	@Override
	public List<String> shortcutFieldOrder() {
		return List.of("patterns");
	}

	@Override
	public ShortcutType shortcutType() {
		return ShortcutType.GATHER_LIST;
	}

	@Override
	public AsyncPredicate<ServerWebExchange> applyAsync(Config config) {
		return new AsyncPredicate<>() {
			@Override
			public Publisher<Boolean> apply(ServerWebExchange exchange) {
				LlmRequest cached = exchange.getAttribute(LlmAttributes.LLM_REQUEST_ATTR);
				if (cached != null) {
					return Mono.just(matches(config, cached));
				}
				return ServerWebExchangeUtils.cacheRequestBodyAndRequest(exchange,
						request -> ServerRequest
							.create(exchange.mutate().request(request).build(), messageReaders)
							.bodyToMono(byte[].class)
							.defaultIfEmpty(new byte[0])
							.map(bytes -> {
								LlmRequest parsed = LlmRequestParser.parse(bytes, objectMapper);
								exchange.getAttributes().put(LlmAttributes.LLM_REQUEST_ATTR, parsed);
								return matches(config, parsed);
							}))
					.defaultIfEmpty(false);
			}

			@Override
			public Object getConfig() {
				return config;
			}

			@Override
			public String toString() {
				return "LlmModel: " + config.getPatterns();
			}
		};
	}

	private static boolean matches(Config config, LlmRequest request) {
		String model = request.model();
		if (model == null || model.isBlank() || config.getPatterns().isEmpty()) {
			return false;
		}
		return PatternMatchUtils.simpleMatch(config.getPatterns().toArray(String[]::new), model);
	}

	@Override
	public Predicate<ServerWebExchange> apply(Config config) {
		throw new UnsupportedOperationException("LlmModelRoutePredicateFactory is only async.");
	}

	public static class Config {

		/** Model patterns to match, e.g. {@code llama-3.1-70b*}. */
		private List<String> patterns = new ArrayList<>();

		public List<String> getPatterns() {
			return this.patterns;
		}

		public Config setPatterns(List<String> patterns) {
			this.patterns = patterns;
			return this;
		}

	}

}

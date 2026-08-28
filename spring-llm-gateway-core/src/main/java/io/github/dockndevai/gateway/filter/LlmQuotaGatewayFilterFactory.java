package io.github.dockndevai.gateway.filter;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import io.github.dockndevai.gateway.auth.LlmPrincipal;
import io.github.dockndevai.gateway.config.LlmGatewayProperties;
import io.github.dockndevai.gateway.quota.QuotaDecision;
import io.github.dockndevai.gateway.quota.TokenBucket;
import io.github.dockndevai.gateway.support.LlmAttributes;
import io.github.dockndevai.gateway.support.LlmRequest;
import io.github.dockndevai.gateway.support.OpenAiError;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;

/**
 * Token-per-minute quotas, as opposed to the request-per-minute limiting the stock
 * {@code RequestRateLimiter} does. A single request can be worth thousands of tokens, so counting
 * requests says very little about load or spend.
 *
 * <pre>
 * filters:
 *   - LlmQuota
 *   - LlmQuota=120000   # override the budget for this route
 * </pre>
 *
 * Because the true cost is only known after the fact, the filter reserves an estimate
 * ({@code prompt_chars / chars-per-token + max_tokens}) before calling upstream, then reconciles
 * against the usage the metering filter scrapes off the response: over-reservations are refunded
 * and under-reservations charged. Reserving up front is what stops a burst of concurrent requests
 * from all passing a check that each of them individually would fail.
 * <p>
 * List this after {@code LlmAuth}; the bucket is keyed on the principal that filter resolves.
 */
public class LlmQuotaGatewayFilterFactory extends AbstractGatewayFilterFactory<LlmQuotaGatewayFilterFactory.Config> {

	private static final Log log = LogFactory.getLog(LlmQuotaGatewayFilterFactory.class);

	private static final String ANONYMOUS = "anonymous";

	private final TokenBucket tokenBucket;

	private final LlmGatewayProperties properties;

	public LlmQuotaGatewayFilterFactory(TokenBucket tokenBucket, LlmGatewayProperties properties) {
		super(Config.class);
		this.tokenBucket = tokenBucket;
		this.properties = properties;
	}

	@Override
	public List<String> shortcutFieldOrder() {
		return List.of("tokensPerMinute");
	}

	@Override
	public GatewayFilter apply(Config config) {
		return (exchange, chain) -> {
			LlmGatewayProperties.Quota quota = this.properties.getQuota();
			if (!quota.isEnabled()) {
				return chain.filter(exchange);
			}

			LlmPrincipal principal = exchange.getAttribute(LlmAttributes.LLM_PRINCIPAL_ATTR);
			if (principal == null && log.isDebugEnabled()) {
				log.debug("LlmQuota ran without a principal; bucketing as '" + ANONYMOUS
						+ "'. List LlmAuth before LlmQuota to bucket per key.");
			}

			String bucketKey = (principal != null) ? principal.id() : ANONYMOUS;
			long limit = limitFor(config, principal, quota);
			long estimate = estimate(exchange.getAttribute(LlmAttributes.LLM_REQUEST_ATTR), quota);

			return this.tokenBucket.tryConsume(bucketKey, estimate, limit)
				.flatMap(decision -> decision.allowed()
						? proceed(exchange, chain, bucketKey, limit, estimate, decision)
						: reject(exchange, decision));
		};
	}

	private Mono<Void> proceed(ServerWebExchange exchange,
			org.springframework.cloud.gateway.filter.GatewayFilterChain chain, String bucketKey, long limit,
			long estimate, QuotaDecision decision) {
		writeRateLimitHeaders(exchange, decision);

		// The real cost is only known once the response has been read, and the metering filter
		// completes after this one does, so reconciliation is registered as a callback there.
		LlmMeteringGlobalFilter.onUsage(exchange, usage -> {
			long actual = (usage.totalTokens() > 0) ? usage.totalTokens()
					: usage.promptTokens() + usage.completionTokens();
			if (actual <= 0) {
				// Upstream reported nothing; keep the estimate charged rather than giving back
				// tokens that may well have been spent.
				return;
			}
			long delta = estimate - actual;
			if (delta != 0) {
				this.tokenBucket.refund(bucketKey, delta, limit)
					.subscribe(null, ex -> log.warn("Quota reconciliation failed for " + bucketKey, ex));
			}
		});

		return chain.filter(exchange);
	}

	private Mono<Void> reject(ServerWebExchange exchange, QuotaDecision decision) {
		long retryAfter = Math.max(1, decision.retryAfter().toSeconds());
		writeRateLimitHeaders(exchange, decision);
		return OpenAiError.write(exchange, HttpStatus.TOO_MANY_REQUESTS,
				"Token quota exceeded: limit is " + decision.limit()
						+ " tokens per minute. Retry in " + retryAfter + "s.",
				"rate_limit_error", "rate_limit_exceeded", retryAfter);
	}

	private void writeRateLimitHeaders(ServerWebExchange exchange, QuotaDecision decision) {
		exchange.getResponse().getHeaders().set("X-RateLimit-Limit-Tokens", Long.toString(decision.limit()));
		exchange.getResponse()
			.getHeaders()
			.set("X-RateLimit-Remaining-Tokens", Long.toString(Math.max(0, decision.remaining())));
	}

	private long limitFor(Config config, LlmPrincipal principal, LlmGatewayProperties.Quota quota) {
		if (config.getTokensPerMinute() != null) {
			return config.getTokensPerMinute();
		}
		if (principal != null && principal.tokensPerMinute() != null) {
			return principal.tokensPerMinute();
		}
		return quota.getDefaults().getTokensPerMinute();
	}

	/** {@code prompt_chars / chars-per-token + max_tokens}, floored at one token. */
	private long estimate(LlmRequest request, LlmGatewayProperties.Quota quota) {
		if (request == null) {
			return 1;
		}
		double charsPerToken = (quota.getCharsPerToken() > 0) ? quota.getCharsPerToken() : 4.0;
		long promptTokens = (long) Math.ceil(request.promptChars() / charsPerToken);
		long completionTokens = (request.maxTokens() != null) ? request.maxTokens() : quota.getDefaultMaxTokens();
		return Math.max(1, promptTokens + completionTokens);
	}

	public static class Config {

		/** Overrides the principal's budget and {@code quota.defaults.tokens-per-minute}. */
		private Long tokensPerMinute;

		public Long getTokensPerMinute() {
			return this.tokensPerMinute;
		}

		public Config setTokensPerMinute(Long tokensPerMinute) {
			this.tokensPerMinute = tokensPerMinute;
			return this;
		}

	}

}

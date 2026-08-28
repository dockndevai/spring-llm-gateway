package io.github.dockndevai.gateway.support;

/**
 * {@link org.springframework.web.server.ServerWebExchange} attribute keys shared between the
 * predicate, the global filters and the route filters.
 */
public final class LlmAttributes {

	private LlmAttributes() {
	}

	/** The parsed {@link LlmRequest} for the current exchange. */
	public static final String LLM_REQUEST_ATTR = "io.github.dockndevai.gateway.request";

	/** The authenticated {@link io.github.dockndevai.gateway.auth.LlmPrincipal}. */
	public static final String LLM_PRINCIPAL_ATTR = "io.github.dockndevai.gateway.principal";

	/** The {@link io.github.dockndevai.gateway.usage.TokenUsage} scraped from the upstream response. */
	public static final String LLM_USAGE_ATTR = "io.github.dockndevai.gateway.usage";

	/**
	 * A list of {@code Consumer<TokenUsage>} callbacks invoked by the metering filter once the
	 * response has completed and usage has been parsed.
	 * <p>
	 * This exists because the filter chain completes inside-out: a route filter's
	 * {@code doFinally} runs <em>before</em> the metering global filter's, so quota
	 * reconciliation cannot simply read {@link #LLM_USAGE_ATTR} from its own terminal signal.
	 */
	public static final String LLM_USAGE_CALLBACKS_ATTR = "io.github.dockndevai.gateway.usageCallbacks";

	/**
	 * The request path as it arrived, captured before the circuit breaker rewrites the request
	 * URI to the fallback path.
	 */
	public static final String LLM_ORIGINAL_PATH_ATTR = "io.github.dockndevai.gateway.originalPath";

	/** Set when the model in the body was rewritten (fallback to a smaller model). */
	public static final String LLM_ROUTED_MODEL_ATTR = "io.github.dockndevai.gateway.routedModel";

}

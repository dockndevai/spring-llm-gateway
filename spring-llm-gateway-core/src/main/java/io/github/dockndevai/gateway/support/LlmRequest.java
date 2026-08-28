package io.github.dockndevai.gateway.support;

import java.util.Objects;

/**
 * The interesting parts of an OpenAI-compatible request body, parsed once per exchange by
 * {@link io.github.dockndevai.gateway.filter.LlmBodyGlobalFilter} (or, when a route selects on the
 * model, by {@link io.github.dockndevai.gateway.predicate.LlmModelRoutePredicateFactory}).
 *
 * @param model the {@code model} field, or {@code null} when absent
 * @param stream the {@code stream} field, defaulting to {@code false}
 * @param promptChars total characters of prompt content, used to estimate prompt tokens
 * @param maxTokens the {@code max_tokens} / {@code max_completion_tokens} field, or {@code null}
 * @param body the raw request body, possibly rewritten to inject {@code stream_options}
 */
public record LlmRequest(String model, boolean stream, int promptChars, Integer maxTokens, byte[] body) {

	public LlmRequest {
		Objects.requireNonNull(body, "body");
	}

	public LlmRequest withBody(byte[] newBody) {
		return new LlmRequest(model, stream, promptChars, maxTokens, newBody);
	}

	public String modelOrUnknown() {
		return (model == null || model.isBlank()) ? "unknown" : model;
	}

}

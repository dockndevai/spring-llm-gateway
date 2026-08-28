package io.github.dockndevai.gateway.usage;

/**
 * Token counts reported by an OpenAI-compatible upstream.
 *
 * @param promptTokens tokens in the prompt
 * @param completionTokens tokens generated
 * @param totalTokens total reported by the upstream, or the sum when it reported no total
 */
public record TokenUsage(long promptTokens, long completionTokens, long totalTokens) {

	public static final TokenUsage NONE = new TokenUsage(0, 0, 0);

	public static TokenUsage of(long promptTokens, long completionTokens, Long totalTokens) {
		long total = (totalTokens != null) ? totalTokens : promptTokens + completionTokens;
		return new TokenUsage(promptTokens, completionTokens, total);
	}

	public boolean isEmpty() {
		return this.promptTokens == 0 && this.completionTokens == 0 && this.totalTokens == 0;
	}

}

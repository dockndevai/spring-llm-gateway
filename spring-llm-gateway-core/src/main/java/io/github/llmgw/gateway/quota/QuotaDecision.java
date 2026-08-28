package io.github.llmgw.gateway.quota;

import java.time.Duration;

/**
 * Outcome of a reservation attempt.
 *
 * @param allowed whether the tokens were reserved
 * @param remaining tokens left in the bucket afterwards
 * @param limit the bucket's capacity, i.e. the tokens-per-minute budget
 * @param retryAfter how long until the bucket would hold enough; {@link Duration#ZERO} when allowed
 */
public record QuotaDecision(boolean allowed, long remaining, long limit, Duration retryAfter) {

	public static QuotaDecision allowed(long remaining, long limit) {
		return new QuotaDecision(true, remaining, limit, Duration.ZERO);
	}

	public static QuotaDecision denied(long remaining, long limit, Duration retryAfter) {
		return new QuotaDecision(false, remaining, limit, retryAfter);
	}

}

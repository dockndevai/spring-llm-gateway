package io.github.llmgw.gateway.quota;

import reactor.core.publisher.Mono;

/**
 * A continuously refilling token bucket, measured in LLM tokens rather than requests.
 * <p>
 * Capacity is the per-minute budget and the bucket refills at {@code capacity / 60} tokens per
 * second, so a caller can burst up to a full minute's budget and then settles into the rate.
 */
public interface TokenBucket {

	/**
	 * Reserve tokens ahead of the upstream call.
	 * @param key bucket identity, normally the principal id
	 * @param tokens how many to reserve
	 * @param capacity the per-minute budget
	 */
	Mono<QuotaDecision> tryConsume(String key, long tokens, long capacity);

	/**
	 * Return over-reserved tokens once actual usage is known. Passing a negative amount charges
	 * extra instead, for the case where the estimate came in under actual usage.
	 */
	Mono<Void> refund(String key, long tokens, long capacity);

}

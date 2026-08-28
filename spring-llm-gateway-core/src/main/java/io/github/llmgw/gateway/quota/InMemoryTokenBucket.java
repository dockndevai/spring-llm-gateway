package io.github.llmgw.gateway.quota;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import reactor.core.publisher.Mono;

/**
 * Per-instance {@link TokenBucket}. The default backend: correct for a single gateway process,
 * and for a fleet it divides the effective budget by the number of instances. Switch
 * {@code llm.gateway.quota.backend} to {@code redis} when that matters.
 */
public class InMemoryTokenBucket implements TokenBucket {

	private static final double NANOS_PER_MINUTE = 60_000_000_000.0;

	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	private final LongSupplier nanoTime;

	public InMemoryTokenBucket() {
		this(System::nanoTime);
	}

	/** Lets tests drive time without sleeping. */
	public InMemoryTokenBucket(LongSupplier nanoTime) {
		this.nanoTime = nanoTime;
	}

	@Override
	public Mono<QuotaDecision> tryConsume(String key, long tokens, long capacity) {
		return Mono.fromSupplier(() -> bucket(key, capacity).consume(tokens, capacity, this.nanoTime.getAsLong()));
	}

	@Override
	public Mono<Void> refund(String key, long tokens, long capacity) {
		if (tokens == 0) {
			return Mono.empty();
		}
		return Mono.fromRunnable(() -> bucket(key, capacity).consume(-tokens, capacity, this.nanoTime.getAsLong()));
	}

	private Bucket bucket(String key, long capacity) {
		return this.buckets.computeIfAbsent(key, k -> new Bucket(capacity, this.nanoTime.getAsLong()));
	}

	/** Visible for tests and for operational introspection. */
	public int size() {
		return this.buckets.size();
	}

	public void clear() {
		this.buckets.clear();
	}

	private static final class Bucket {

		private double tokens;

		private long lastRefillNanos;

		Bucket(long capacity, long nowNanos) {
			this.tokens = capacity;
			this.lastRefillNanos = nowNanos;
		}

		synchronized QuotaDecision consume(long requested, long capacity, long nowNanos) {
			refill(capacity, nowNanos);
			if (this.tokens >= requested) {
				// min() also caps refunds, which arrive as a negative request.
				this.tokens = Math.min(capacity, this.tokens - requested);
				return QuotaDecision.allowed((long) Math.floor(this.tokens), capacity);
			}
			double deficit = requested - this.tokens;
			double perSecond = capacity / 60.0;
			long retrySeconds = (perSecond <= 0) ? 60 : (long) Math.ceil(deficit / perSecond);
			return QuotaDecision.denied((long) Math.floor(this.tokens), capacity,
					Duration.ofSeconds(Math.max(1, retrySeconds)));
		}

		private void refill(long capacity, long nowNanos) {
			long elapsed = nowNanos - this.lastRefillNanos;
			this.lastRefillNanos = nowNanos;
			if (elapsed <= 0) {
				return;
			}
			this.tokens = Math.min(capacity, this.tokens + (elapsed / NANOS_PER_MINUTE) * capacity);
		}

	}

}

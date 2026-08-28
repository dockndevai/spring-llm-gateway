package io.github.llmgw.gateway.quota;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTokenBucketTests {

	private static final long ONE_SECOND = 1_000_000_000L;

	private final AtomicLong clock = new AtomicLong(0);

	private InMemoryTokenBucket bucket;

	@BeforeEach
	void setUp() {
		this.bucket = new InMemoryTokenBucket(this.clock::get);
	}

	private void advanceSeconds(long seconds) {
		this.clock.addAndGet(seconds * ONE_SECOND);
	}

	private QuotaDecision consume(long tokens) {
		return this.bucket.tryConsume("key", tokens, 600).block();
	}

	@Test
	void startsFull() {
		QuotaDecision decision = consume(600);

		assertThat(decision.allowed()).isTrue();
		assertThat(decision.remaining()).isZero();
		assertThat(decision.limit()).isEqualTo(600);
	}

	@Test
	void deniesOnceDrained() {
		consume(600);

		QuotaDecision decision = consume(1);

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.retryAfter()).isPositive();
	}

	@Test
	void refillsAtCapacityPerMinute() {
		consume(600);
		// 600 tokens per minute is 10 per second.
		advanceSeconds(30);

		QuotaDecision decision = consume(300);

		assertThat(decision.allowed()).isTrue();
		assertThat(decision.remaining()).isZero();
	}

	@Test
	void neverRefillsAboveCapacity() {
		consume(600);
		advanceSeconds(600);

		assertThat(consume(600).allowed()).isTrue();
		assertThat(consume(1).allowed()).isFalse();
	}

	@Test
	void retryAfterReflectsTheDeficit() {
		consume(600);

		// 100 tokens short at 10 tokens per second.
		QuotaDecision decision = consume(100);

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.retryAfter().toSeconds()).isEqualTo(10);
	}

	@Test
	void deniedRequestsDoNotConsume() {
		consume(500);
		consume(500);

		// The second call was denied, so 100 should still be there.
		assertThat(consume(100).allowed()).isTrue();
	}

	@Test
	void refundReturnsOverReservedTokens() {
		consume(600);

		this.bucket.refund("key", 400, 600).block();

		assertThat(consume(400).allowed()).isTrue();
		assertThat(consume(1).allowed()).isFalse();
	}

	@Test
	void refundIsCappedAtCapacity() {
		consume(100);

		this.bucket.refund("key", 5000, 600).block();

		assertThat(consume(600).allowed()).isTrue();
		assertThat(consume(1).allowed()).isFalse();
	}

	@Test
	void negativeRefundChargesTheDifference() {
		consume(100);

		// Actual usage came in above the estimate, so charge the extra 200.
		this.bucket.refund("key", -200, 600).block();

		assertThat(consume(300).allowed()).isTrue();
		assertThat(consume(1).allowed()).isFalse();
	}

	@Test
	void bucketsAreIsolatedPerKey() {
		this.bucket.tryConsume("tenant-a", 600, 600).block();

		assertThat(this.bucket.tryConsume("tenant-b", 600, 600).block().allowed()).isTrue();
		assertThat(this.bucket.size()).isEqualTo(2);
	}

	@Test
	void requestLargerThanTheWholeBudgetIsDenied() {
		QuotaDecision decision = this.bucket.tryConsume("key", 10_000, 600).block();

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.retryAfter()).isPositive();
	}

}

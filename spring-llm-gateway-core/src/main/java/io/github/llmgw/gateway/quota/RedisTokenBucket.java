package io.github.llmgw.gateway.quota;

import java.time.Duration;
import java.util.List;

import reactor.core.publisher.Mono;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link TokenBucket} shared across gateway instances.
 * <p>
 * The refill-and-consume step runs as a single Lua script so it is atomic on the Redis side;
 * doing it with separate GET/SET round trips would let two instances both see a full bucket.
 * The script returns a {@code allowed|remaining|retrySeconds} string rather than a Lua table so
 * that it deserializes cleanly through the template's String serializer.
 */
public class RedisTokenBucket implements TokenBucket {

	private static final String SCRIPT = """
			local key = KEYS[1]
			local capacity = tonumber(ARGV[1])
			local requested = tonumber(ARGV[2])
			local now = tonumber(ARGV[3])

			local state = redis.call('HMGET', key, 'tokens', 'ts')
			local tokens = tonumber(state[1])
			local ts = tonumber(state[2])
			if tokens == nil or ts == nil then
			  tokens = capacity
			  ts = now
			end

			local elapsed = now - ts
			if elapsed < 0 then elapsed = 0 end
			tokens = math.min(capacity, tokens + (elapsed / 60000.0) * capacity)

			local allowed = 0
			local retry = 0
			if tokens >= requested then
			  allowed = 1
			  tokens = math.min(capacity, tokens - requested)
			else
			  local deficit = requested - tokens
			  local perSecond = capacity / 60.0
			  if perSecond <= 0 then
			    retry = 60
			  else
			    retry = math.ceil(deficit / perSecond)
			  end
			  if retry < 1 then retry = 1 end
			end

			redis.call('HMSET', key, 'tokens', tokens, 'ts', now)
			redis.call('PEXPIRE', key, 120000)
			return string.format('%d|%d|%d', allowed, math.floor(tokens), retry)
			""";

	private final ReactiveStringRedisTemplate redis;

	private final RedisScript<String> script;

	private final String keyPrefix;

	public RedisTokenBucket(ReactiveStringRedisTemplate redis, String keyPrefix) {
		this.redis = redis;
		this.keyPrefix = (keyPrefix == null) ? "" : keyPrefix;
		this.script = RedisScript.of(SCRIPT, String.class);
	}

	@Override
	public Mono<QuotaDecision> tryConsume(String key, long tokens, long capacity) {
		return run(key, tokens, capacity).map(result -> {
			String[] parts = result.split("\\|");
			boolean allowed = "1".equals(parts[0]);
			long remaining = Long.parseLong(parts[1]);
			long retry = Long.parseLong(parts[2]);
			return allowed ? QuotaDecision.allowed(remaining, capacity)
					: QuotaDecision.denied(remaining, capacity, Duration.ofSeconds(retry));
		})
			// A Redis outage should not take the gateway down with it; fail open and let the
			// upstream's own limits apply.
			.onErrorReturn(QuotaDecision.allowed(capacity, capacity));
	}

	@Override
	public Mono<Void> refund(String key, long tokens, long capacity) {
		if (tokens == 0) {
			return Mono.empty();
		}
		return run(key, -tokens, capacity).onErrorReturn("1|0|0").then();
	}

	private Mono<String> run(String key, long tokens, long capacity) {
		List<String> keys = List.of(this.keyPrefix + key);
		List<String> args = List.of(Long.toString(capacity), Long.toString(tokens),
				Long.toString(System.currentTimeMillis()));
		return this.redis.execute(this.script, keys, args).singleOrEmpty();
	}

}

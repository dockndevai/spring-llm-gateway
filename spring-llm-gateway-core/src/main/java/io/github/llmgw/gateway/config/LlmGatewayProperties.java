package io.github.llmgw.gateway.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration surface for the LLM gateway. All properties live under {@code llm.gateway}.
 */
@ConfigurationProperties(LlmGatewayProperties.PREFIX)
public class LlmGatewayProperties {

	public static final String PREFIX = "llm.gateway";

	/** Master switch for every LLM gateway component. */
	private boolean enabled = true;

	private Auth auth = new Auth();

	/** Upstream inference servers, keyed by a logical name referenced from {@code models}. */
	private Map<String, Upstream> upstreams = new LinkedHashMap<>();

	/** Known models, keyed by the value clients send in the {@code model} field. */
	private Map<String, Model> models = new LinkedHashMap<>();

	private Quota quota = new Quota();

	private Metering metering = new Metering();

	private Fallback fallback = new Fallback();

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Auth getAuth() {
		return this.auth;
	}

	public void setAuth(Auth auth) {
		this.auth = auth;
	}

	public Map<String, Upstream> getUpstreams() {
		return this.upstreams;
	}

	public void setUpstreams(Map<String, Upstream> upstreams) {
		this.upstreams = upstreams;
	}

	public Map<String, Model> getModels() {
		return this.models;
	}

	public void setModels(Map<String, Model> models) {
		this.models = models;
	}

	public Quota getQuota() {
		return this.quota;
	}

	public void setQuota(Quota quota) {
		this.quota = quota;
	}

	public Metering getMetering() {
		return this.metering;
	}

	public void setMetering(Metering metering) {
		this.metering = metering;
	}

	public Fallback getFallback() {
		return this.fallback;
	}

	public void setFallback(Fallback fallback) {
		this.fallback = fallback;
	}

	/**
	 * How a caller's bearer token is turned into a tenant, a model allow-list and a quota.
	 */
	public static class Auth {

		public enum Mode {

			/** Virtual keys declared under {@code llm.gateway.auth.keys}. */
			STATIC,

			/** Keycloak / OIDC issued JWTs, validated by spring-security-oauth2-resource-server. */
			JWT

		}

		/** Where virtual keys come from. */
		private Mode mode = Mode.STATIC;

		/** Virtual keys, keyed by key id. Only used when {@code mode=static}. */
		private Map<String, Key> keys = new LinkedHashMap<>();

		private Jwt jwt = new Jwt();

		public Mode getMode() {
			return this.mode;
		}

		public void setMode(Mode mode) {
			this.mode = mode;
		}

		public Map<String, Key> getKeys() {
			return this.keys;
		}

		public void setKeys(Map<String, Key> keys) {
			this.keys = keys;
		}

		public Jwt getJwt() {
			return this.jwt;
		}

		public void setJwt(Jwt jwt) {
			this.jwt = jwt;
		}

	}

	/** A virtual key: what the client presents, and what it is allowed to do. */
	public static class Key {

		/**
		 * The secret the client presents as {@code Authorization: Bearer <secret>}. Either a
		 * plaintext value (development) or {@code sha256:<hex>} of the real secret.
		 */
		private String secret;

		/** Tenant this key bills to. Used as a metric tag and as part of the quota key. */
		private String tenant = "default";

		/** Model patterns this key may call, matched with {@code PatternMatchUtils.simpleMatch}. */
		private List<String> models = new ArrayList<>(List.of("*"));

		/** Token budget per minute. Falls back to {@code llm.gateway.quota.defaults.tokens-per-minute}. */
		private Long tokensPerMinute;

		/** Set to false to revoke the key without deleting its configuration. */
		private boolean enabled = true;

		public String getSecret() {
			return this.secret;
		}

		public void setSecret(String secret) {
			this.secret = secret;
		}

		public String getTenant() {
			return this.tenant;
		}

		public void setTenant(String tenant) {
			this.tenant = tenant;
		}

		public List<String> getModels() {
			return this.models;
		}

		public void setModels(List<String> models) {
			this.models = models;
		}

		public Long getTokensPerMinute() {
			return this.tokensPerMinute;
		}

		public void setTokensPerMinute(Long tokensPerMinute) {
			this.tokensPerMinute = tokensPerMinute;
		}

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

	}

	/** Which JWT claims carry the gateway entitlements. Only used when {@code auth.mode=jwt}. */
	public static class Jwt {

		/** Claim holding the tenant id. */
		private String tenantClaim = "tenant";

		/** Claim holding allowed model patterns: a string array, or a space/comma separated string. */
		private String modelsClaim = "llm_models";

		/** Claim holding the per-minute token budget. */
		private String tokensPerMinuteClaim = "llm_tpm";

		/** Claim used as the principal id, and therefore as the quota bucket key. */
		private String principalClaim = "sub";

		/** Model patterns applied when the token carries no models claim. */
		private List<String> defaultModels = new ArrayList<>(List.of("*"));

		public String getTenantClaim() {
			return this.tenantClaim;
		}

		public void setTenantClaim(String tenantClaim) {
			this.tenantClaim = tenantClaim;
		}

		public String getModelsClaim() {
			return this.modelsClaim;
		}

		public void setModelsClaim(String modelsClaim) {
			this.modelsClaim = modelsClaim;
		}

		public String getTokensPerMinuteClaim() {
			return this.tokensPerMinuteClaim;
		}

		public void setTokensPerMinuteClaim(String tokensPerMinuteClaim) {
			this.tokensPerMinuteClaim = tokensPerMinuteClaim;
		}

		public String getPrincipalClaim() {
			return this.principalClaim;
		}

		public void setPrincipalClaim(String principalClaim) {
			this.principalClaim = principalClaim;
		}

		public List<String> getDefaultModels() {
			return this.defaultModels;
		}

		public void setDefaultModels(List<String> defaultModels) {
			this.defaultModels = defaultModels;
		}

	}

	/** A self-hosted OpenAI-compatible inference server. */
	public static class Upstream {

		/** The real API key to present upstream. Omit for servers that need no auth (Ollama). */
		private String apiKey;

		/** Header the upstream key is written to. */
		private String header = "Authorization";

		/** Prefix prepended to the key. Set to an empty string for raw-token headers. */
		private String prefix = "Bearer ";

		public String getApiKey() {
			return this.apiKey;
		}

		public void setApiKey(String apiKey) {
			this.apiKey = apiKey;
		}

		public String getHeader() {
			return this.header;
		}

		public void setHeader(String header) {
			this.header = header;
		}

		public String getPrefix() {
			return this.prefix;
		}

		public void setPrefix(String prefix) {
			this.prefix = prefix;
		}

	}

	/** A model clients may ask for, and what it costs. */
	public static class Model {

		/** Key into {@code llm.gateway.upstreams} that serves this model. */
		private String upstream;

		/** Cost per 1000 prompt tokens, in whatever currency you report in. */
		private Double promptCostPer1k;

		/** Cost per 1000 completion tokens. */
		private Double completionCostPer1k;

		public String getUpstream() {
			return this.upstream;
		}

		public void setUpstream(String upstream) {
			this.upstream = upstream;
		}

		public Double getPromptCostPer1k() {
			return this.promptCostPer1k;
		}

		public void setPromptCostPer1k(Double promptCostPer1k) {
			this.promptCostPer1k = promptCostPer1k;
		}

		public Double getCompletionCostPer1k() {
			return this.completionCostPer1k;
		}

		public void setCompletionCostPer1k(Double completionCostPer1k) {
			this.completionCostPer1k = completionCostPer1k;
		}

	}

	/** Token-per-minute quotas. */
	public static class Quota {

		public enum Backend {

			/** Per-instance buckets held in a ConcurrentHashMap. */
			MEMORY,

			/** Buckets in Redis, updated by a Lua script so gateway instances share them. */
			REDIS

		}

		private boolean enabled = true;

		private Backend backend = Backend.MEMORY;

		/** Divisor turning prompt characters into an estimated prompt-token count. */
		private double charsPerToken = 4.0;

		/** Completion allowance assumed when a request omits {@code max_tokens}. */
		private long defaultMaxTokens = 512;

		/** Redis key prefix, only used when {@code backend=redis}. */
		private String redisKeyPrefix = "llmgw:quota:";

		private Defaults defaults = new Defaults();

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public Backend getBackend() {
			return this.backend;
		}

		public void setBackend(Backend backend) {
			this.backend = backend;
		}

		public double getCharsPerToken() {
			return this.charsPerToken;
		}

		public void setCharsPerToken(double charsPerToken) {
			this.charsPerToken = charsPerToken;
		}

		public long getDefaultMaxTokens() {
			return this.defaultMaxTokens;
		}

		public void setDefaultMaxTokens(long defaultMaxTokens) {
			this.defaultMaxTokens = defaultMaxTokens;
		}

		public String getRedisKeyPrefix() {
			return this.redisKeyPrefix;
		}

		public void setRedisKeyPrefix(String redisKeyPrefix) {
			this.redisKeyPrefix = redisKeyPrefix;
		}

		public Defaults getDefaults() {
			return this.defaults;
		}

		public void setDefaults(Defaults defaults) {
			this.defaults = defaults;
		}

		public static class Defaults {

			/** Budget applied to any principal that does not declare its own. */
			private long tokensPerMinute = 60_000;

			public long getTokensPerMinute() {
				return this.tokensPerMinute;
			}

			public void setTokensPerMinute(long tokensPerMinute) {
				this.tokensPerMinute = tokensPerMinute;
			}

		}

	}

	/** Usage scraping, metrics and billing events. */
	public static class Metering {

		private boolean enabled = true;

		/**
		 * Add {@code "stream_options":{"include_usage":true}} to streamed requests that omit it.
		 * Without this vLLM never emits a usage block for a stream.
		 */
		private boolean injectStreamUsage = true;

		/**
		 * How many trailing characters of the response to retain while looking for the usage
		 * object. The usage block is last in both streamed and non-streamed responses, so the
		 * whole body never needs to be buffered.
		 */
		private int tailBufferSize = 8192;

		/** Publish an {@code LlmUsageEvent} for each completed request. */
		private boolean publishEvents = true;

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public boolean isInjectStreamUsage() {
			return this.injectStreamUsage;
		}

		public void setInjectStreamUsage(boolean injectStreamUsage) {
			this.injectStreamUsage = injectStreamUsage;
		}

		public int getTailBufferSize() {
			return this.tailBufferSize;
		}

		public void setTailBufferSize(int tailBufferSize) {
			this.tailBufferSize = tailBufferSize;
		}

		public boolean isPublishEvents() {
			return this.publishEvents;
		}

		public void setPublishEvents(boolean publishEvents) {
			this.publishEvents = publishEvents;
		}

	}

	/** Where {@code forward:/__llm/fallback} sends a request when the primary upstream trips. */
	public static class Fallback {

		/** Base URI of the secondary upstream, e.g. {@code http://ollama:11434}. */
		private String uri;

		/** Model to rewrite the request to, typically something smaller. Unset keeps the original. */
		private String model;

		/** Upstream credential for the fallback call, keyed into {@code llm.gateway.upstreams}. */
		private String upstream;

		public String getUri() {
			return this.uri;
		}

		public void setUri(String uri) {
			this.uri = uri;
		}

		public String getModel() {
			return this.model;
		}

		public void setModel(String model) {
			this.model = model;
		}

		public String getUpstream() {
			return this.upstream;
		}

		public void setUpstream(String upstream) {
			this.upstream = upstream;
		}

	}

}

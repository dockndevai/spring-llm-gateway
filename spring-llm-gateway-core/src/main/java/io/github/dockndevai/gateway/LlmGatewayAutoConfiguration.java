package io.github.dockndevai.gateway;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;

import io.github.dockndevai.gateway.auth.ApiKeyStore;
import io.github.dockndevai.gateway.auth.JwtPrincipalResolver;
import io.github.dockndevai.gateway.auth.PrincipalResolver;
import io.github.dockndevai.gateway.auth.PropertiesApiKeyStore;
import io.github.dockndevai.gateway.auth.StaticKeyPrincipalResolver;
import io.github.dockndevai.gateway.config.LlmGatewayProperties;
import io.github.dockndevai.gateway.fallback.LlmFallbackHandler;
import io.github.dockndevai.gateway.filter.LlmAuthGatewayFilterFactory;
import io.github.dockndevai.gateway.filter.LlmBodyGlobalFilter;
import io.github.dockndevai.gateway.filter.LlmMeteringGlobalFilter;
import io.github.dockndevai.gateway.filter.LlmQuotaGatewayFilterFactory;
import io.github.dockndevai.gateway.metrics.LlmMetricsListener;
import io.github.dockndevai.gateway.predicate.LlmModelRoutePredicateFactory;
import io.github.dockndevai.gateway.quota.InMemoryTokenBucket;
import io.github.dockndevai.gateway.quota.RedisTokenBucket;
import io.github.dockndevai.gateway.quota.TokenBucket;
import io.github.dockndevai.gateway.security.AesGcmSecretCipher;
import io.github.dockndevai.gateway.security.CachingSecretCipher;
import io.github.dockndevai.gateway.security.NoOpSecretCipher;
import io.github.dockndevai.gateway.security.SecretCipher;
import io.github.dockndevai.gateway.security.UpstreamCredentials;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.config.GatewayAutoConfiguration;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

/**
 * Wires the LLM gateway on top of whatever Spring Cloud Gateway is already doing.
 * <p>
 * Everything here is conditional, so adding the dependency to an existing gateway changes nothing
 * until routes actually reference {@code LlmModel}, {@code LlmAuth} or {@code LlmQuota}. The
 * optional dependencies — micrometer, Redis, resilience4j, oauth2-resource-server — are each
 * guarded by {@code @ConditionalOnClass}, so none of them has to be on the classpath.
 */
@AutoConfiguration(after = { GatewayAutoConfiguration.class, JacksonAutoConfiguration.class },
		// Referenced by name because oauth2-resource-server is optional. Ordering after it is
		// required, not cosmetic: JwtAuthConfiguration consumes the ReactiveJwtDecoder that
		// auto-configuration registers, and without this the decoder does not exist yet.
		afterName = "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive."
				+ "ReactiveOAuth2ResourceServerAutoConfiguration")
@ConditionalOnClass({ GlobalFilter.class, DispatcherHandler.class })
@ConditionalOnProperty(prefix = LlmGatewayProperties.PREFIX, name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(LlmGatewayProperties.class)
public class LlmGatewayAutoConfiguration {

	/**
	 * Decrypts {@code {enc}} configuration values. Falls back to a no-op that passes plaintext
	 * through when no key is set, so encryption is opt-in and can be adopted one value at a time.
	 */
	@Bean
	@ConditionalOnMissingBean
	public SecretCipher llmSecretCipher(LlmGatewayProperties properties) {
		String key = properties.getSecrets().getKey();
		SecretCipher cipher = StringUtils.hasText(key) ? new AesGcmSecretCipher(key) : new NoOpSecretCipher();
		return new CachingSecretCipher(cipher);
	}

	@Bean
	@ConditionalOnMissingBean
	public UpstreamCredentials llmUpstreamCredentials(SecretCipher secretCipher) {
		return new UpstreamCredentials(secretCipher);
	}

	@Bean
	@ConditionalOnMissingBean
	public ApiKeyStore llmApiKeyStore(LlmGatewayProperties properties, SecretCipher secretCipher) {
		return new PropertiesApiKeyStore(properties, secretCipher);
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = LlmGatewayProperties.PREFIX + ".auth", name = "mode", havingValue = "static",
			matchIfMissing = true)
	public PrincipalResolver llmStaticPrincipalResolver(ApiKeyStore apiKeyStore) {
		return new StaticKeyPrincipalResolver(apiKeyStore);
	}

	@Bean
	public LlmModelRoutePredicateFactory llmModelRoutePredicateFactory(ObjectMapper objectMapper) {
		return new LlmModelRoutePredicateFactory(objectMapper);
	}

	@Bean
	public LlmBodyGlobalFilter llmBodyGlobalFilter(ObjectMapper objectMapper, LlmGatewayProperties properties) {
		return new LlmBodyGlobalFilter(objectMapper, properties);
	}

	/**
	 * Registered unconditionally even when metering is switched off — the filter short-circuits
	 * itself. Making the bean conditional would also silently disable quota reconciliation, which
	 * rides on the same completion hook.
	 */
	@Bean
	public LlmMeteringGlobalFilter llmMeteringGlobalFilter(LlmGatewayProperties properties,
			ApplicationEventPublisher eventPublisher) {
		return new LlmMeteringGlobalFilter(properties, eventPublisher);
	}

	@Bean
	public LlmAuthGatewayFilterFactory llmAuthGatewayFilterFactory(PrincipalResolver principalResolver,
			LlmGatewayProperties properties, UpstreamCredentials credentials) {
		return new LlmAuthGatewayFilterFactory(principalResolver, properties, credentials);
	}

	@Bean
	public LlmQuotaGatewayFilterFactory llmQuotaGatewayFilterFactory(TokenBucket tokenBucket,
			LlmGatewayProperties properties) {
		return new LlmQuotaGatewayFilterFactory(tokenBucket, properties);
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = LlmGatewayProperties.PREFIX + ".quota", name = "backend", havingValue = "memory",
			matchIfMissing = true)
	public TokenBucket llmInMemoryTokenBucket() {
		return new InMemoryTokenBucket();
	}

	@Bean
	public LlmFallbackHandler llmFallbackHandler(LlmGatewayProperties properties, ObjectMapper objectMapper,
			UpstreamCredentials credentials,
			org.springframework.beans.factory.ObjectProvider<WebClient.Builder> webClientBuilder) {
		WebClient.Builder builder = webClientBuilder.getIfAvailable(WebClient::builder);
		return new LlmFallbackHandler(builder.build(), properties, objectMapper, credentials);
	}

	/**
	 * Maps the fallback handler ahead of {@code RoutePredicateHandlerMapping} (order 1), so a
	 * request forwarded by the circuit breaker lands here instead of being matched against the
	 * routes a second time. {@code SimpleHandlerAdapter}, part of stock WebFlux, invokes it.
	 */
	@Bean
	public HandlerMapping llmFallbackHandlerMapping(LlmFallbackHandler handler) {
		SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
		mapping.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
		mapping.setUrlMap(Map.of(LlmFallbackHandler.PATH, handler));
		return mapping;
	}

	/** Keycloak / OIDC virtual keys. Needs spring-boot-starter-oauth2-resource-server. */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(ReactiveJwtDecoder.class)
	@ConditionalOnProperty(prefix = LlmGatewayProperties.PREFIX + ".auth", name = "mode", havingValue = "jwt")
	public static class JwtAuthConfiguration {

		/**
		 * Deliberately not {@code @ConditionalOnBean(ReactiveJwtDecoder.class)}. That backs the
		 * bean off silently when no decoder is configured, and the context then fails several
		 * beans later complaining that {@code PrincipalResolver} is missing, which points
		 * nowhere useful. Requiring the decoder directly makes the failure name the thing that
		 * is actually absent, so the fix — setting
		 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} — is obvious.
		 */
		@Bean
		@ConditionalOnMissingBean
		public PrincipalResolver llmJwtPrincipalResolver(ReactiveJwtDecoder jwtDecoder,
				LlmGatewayProperties properties) {
			return new JwtPrincipalResolver(jwtDecoder, properties);
		}

	}

	/** Cross-instance quotas. Needs spring-boot-starter-data-redis-reactive. */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(ReactiveStringRedisTemplate.class)
	@ConditionalOnProperty(prefix = LlmGatewayProperties.PREFIX + ".quota", name = "backend", havingValue = "redis")
	public static class RedisQuotaConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public TokenBucket llmRedisTokenBucket(ReactiveStringRedisTemplate redisTemplate,
				LlmGatewayProperties properties) {
			return new RedisTokenBucket(redisTemplate, properties.getQuota().getRedisKeyPrefix());
		}

	}

	/** Meters. Needs micrometer-core. */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(MeterRegistry.class)
	@ConditionalOnBean(MeterRegistry.class)
	@ConditionalOnProperty(prefix = LlmGatewayProperties.PREFIX + ".metering", name = "enabled",
			matchIfMissing = true)
	public static class MetricsConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public LlmMetricsListener llmMetricsListener(MeterRegistry meterRegistry) {
			return new LlmMetricsListener(meterRegistry);
		}

	}

}

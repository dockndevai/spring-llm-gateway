package io.github.llmgw.gateway.sample;

import org.junit.jupiter.api.Test;

import io.github.llmgw.gateway.config.LlmGatewayProperties;
import io.github.llmgw.gateway.filter.LlmAuthGatewayFilterFactory;
import io.github.llmgw.gateway.filter.LlmBodyGlobalFilter;
import io.github.llmgw.gateway.filter.LlmMeteringGlobalFilter;
import io.github.llmgw.gateway.filter.LlmQuotaGatewayFilterFactory;
import io.github.llmgw.gateway.predicate.LlmModelRoutePredicateFactory;
import io.github.llmgw.gateway.quota.InMemoryTokenBucket;
import io.github.llmgw.gateway.quota.TokenBucket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sample must start on its own, with no backend reachable: routes are resolved lazily, so
 * neither vLLM nor Ollama needs to be running for the gateway to come up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LlmGatewaySampleApplicationTests {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private LlmGatewayProperties properties;

	@Autowired
	private RouteLocator routeLocator;

	@Test
	void contextLoadsWithEveryLlmComponent() {
		assertThat(this.context.getBean(LlmModelRoutePredicateFactory.class)).isNotNull();
		assertThat(this.context.getBean(LlmBodyGlobalFilter.class)).isNotNull();
		assertThat(this.context.getBean(LlmMeteringGlobalFilter.class)).isNotNull();
		assertThat(this.context.getBean(LlmAuthGatewayFilterFactory.class)).isNotNull();
		assertThat(this.context.getBean(LlmQuotaGatewayFilterFactory.class)).isNotNull();
	}

	@Test
	void usesTheInMemoryBucketUntilRedisIsSelected() {
		assertThat(this.context.getBean(TokenBucket.class)).isInstanceOf(InMemoryTokenBucket.class);
	}

	@Test
	void routesAreConfiguredForBothBackends() {
		assertThat(this.routeLocator.getRoutes().map(route -> route.getId()).collectList().block())
			.contains("vllm-llama", "ollama-small");
	}

	@Test
	void sampleKeysAreBoundIncludingTheHashedOne() {
		assertThat(this.properties.getAuth().getKeys()).containsKeys("demo", "analytics");
		assertThat(this.properties.getAuth().getKeys().get("analytics").getSecret()).startsWith("sha256:");
		// Model ids with dots survived YAML binding, which the bracketed keys are there for.
		assertThat(this.properties.getModels()).containsKey("llama-3.1-70b-instruct");
		assertThat(this.properties.getModels().get("llama-3.1-70b-instruct").getUpstream()).isEqualTo("vllm");
	}

	@Test
	void everyModelPointsAtADeclaredUpstream() {
		this.properties.getModels()
			.forEach((model, config) -> assertThat(this.properties.getUpstreams())
				.as("model '%s' references upstream '%s'", model, config.getUpstream())
				.containsKey(config.getUpstream()));
	}

	@Test
	void hashedSampleKeyMatchesItsDocumentedSecret() {
		assertThat(io.github.llmgw.gateway.auth.PropertiesApiKeyStore.hash("sk-analytics-team"))
			.isEqualTo(this.properties.getAuth().getKeys().get("analytics").getSecret());
	}

}

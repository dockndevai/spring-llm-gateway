package io.github.llmgw.gateway.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failover: the stock {@code CircuitBreaker} filter trips on an unreachable primary and forwards
 * to {@code /__llm/fallback}, which replays the cached body against the configured secondary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive",
				"spring.autoconfigure.exclude="
						+ "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
						+ "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration,"
						+ "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration" })
class LlmFailoverIntegrationTests {

	/** Stands in for a healthy smaller model to fail over to. */
	private static final StubUpstream SECONDARY = StubUpstream.start("ollama");

	/** Nothing listens here, so the primary call fails immediately. */
	private static final String DEAD_PRIMARY = "http://localhost:1";

	@Autowired
	private WebTestClient webTestClient;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		String routes = "spring.cloud.gateway.server.webflux.routes";
		registry.add(routes + "[0].id", () -> "vllm-llama");
		registry.add(routes + "[0].uri", () -> DEAD_PRIMARY);
		registry.add(routes + "[0].predicates[0]", () -> "Path=/v1/**");
		registry.add(routes + "[0].predicates[1]", () -> "LlmModel=llama-3.1-70b*");
		registry.add(routes + "[0].filters[0].name", () -> "LlmAuth");
		// CircuitBreaker takes two args, so it needs the expanded form: its shortcut binds only
		// the first positional value, which would leave fallbackUri silently unset.
		registry.add(routes + "[0].filters[1].name", () -> "CircuitBreaker");
		registry.add(routes + "[0].filters[1].args.name", () -> "llm");
		registry.add(routes + "[0].filters[1].args.fallbackUri", () -> "forward:/__llm/fallback");

		registry.add("llm.gateway.auth.keys.team.secret", () -> "sk-team");
		registry.add("llm.gateway.auth.keys.team.models[0]", () -> "*");

		registry.add("llm.gateway.fallback.uri", SECONDARY::uri);
		registry.add("llm.gateway.fallback.model", () -> "mixtral-8x7b");
	}

	@AfterAll
	static void stop() {
		SECONDARY.stop();
	}

	@Test
	void failsOverToTheSecondaryUpstream() {
		this.webTestClient.post()
			.uri("/v1/chat/completions")
			.header(HttpHeaders.AUTHORIZATION, "Bearer sk-team")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
					{"model":"llama-3.1-70b-instruct","max_tokens":16,
					 "messages":[{"role":"user","content":"hello"}]}""")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.choices[0].message.content")
			.isEqualTo("served by ollama");

		assertThat(SECONDARY.received()).hasSize(1);
		// The request body was replayed, with the model swapped for the smaller one.
		assertThat(SECONDARY.lastReceived().body()).contains("\"model\":\"mixtral-8x7b\"");
		assertThat(SECONDARY.lastReceived().path()).isEqualTo("/v1/chat/completions");
	}

	@SpringBootApplication
	static class TestApplication {

	}

}

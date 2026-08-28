package io.github.dockndevai.gateway.integration;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * With no {@code llm.gateway.fallback.uri} configured, a tripped circuit breaker should still
 * produce something an OpenAI client can parse rather than a bare gateway error.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive",
				"spring.autoconfigure.exclude="
						+ "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
						+ "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration,"
						+ "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration" })
class LlmFallbackWithoutSecondaryTests {

	/** A port that was bound then released, so connections to it are refused immediately. */
	private static final int DEAD_PORT = findDeadPort();

	@Autowired
	private WebTestClient webTestClient;

	private static int findDeadPort() {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException("could not reserve a closed port", ex);
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		String routes = "spring.cloud.gateway.server.webflux.routes";
		registry.add(routes + "[0].id", () -> "vllm-llama");
		registry.add(routes + "[0].uri", () -> "http://localhost:" + DEAD_PORT);
		registry.add(routes + "[0].predicates[0]", () -> "Path=/v1/**");
		registry.add(routes + "[0].filters[0].name", () -> "LlmAuth");
		// CircuitBreaker takes two args, so it needs the expanded form: its shortcut binds only
		// the first positional value, which would leave fallbackUri silently unset.
		registry.add(routes + "[0].filters[1].name", () -> "CircuitBreaker");
		registry.add(routes + "[0].filters[1].args.name", () -> "llm");
		registry.add(routes + "[0].filters[1].args.fallbackUri", () -> "forward:/__llm/fallback");

		registry.add("llm.gateway.auth.keys.team.secret", () -> "sk-team");
		registry.add("llm.gateway.auth.keys.team.models[0]", () -> "*");
	}

	@Test
	void returnsAnOpenAiShaped503() {
		this.webTestClient.post()
			.uri("/v1/chat/completions")
			.header(HttpHeaders.AUTHORIZATION, "Bearer sk-team")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"model\":\"llama-3.1-70b-instruct\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
			.exchange()
			.expectStatus()
			.isEqualTo(503)
			.expectBody()
			.jsonPath("$.error.code")
			.isEqualTo("upstream_unavailable")
			.jsonPath("$.error.type")
			.isEqualTo("server_error");
	}

	@SpringBootApplication
	static class TestApplication {

	}

}

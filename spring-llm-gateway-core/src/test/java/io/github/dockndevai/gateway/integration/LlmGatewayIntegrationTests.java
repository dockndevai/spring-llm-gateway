package io.github.dockndevai.gateway.integration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.dockndevai.gateway.usage.LlmUsageEvent;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end coverage against stubbed OpenAI-compatible upstreams: model routing, key rejection,
 * the model allow-list, quota exhaustion, and usage captured from a streamed response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive",
				// spring-boot-starter-oauth2-resource-server is on this module's test classpath
				// for the JWT resolver tests; without this it would lock down every route with
				// Spring Security's default chain. It is <optional> so it never reaches users.
				"spring.autoconfigure.exclude="
						+ "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
						+ "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration,"
						+ "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration" })
class LlmGatewayIntegrationTests {

	private static final StubUpstream VLLM = StubUpstream.start("vllm");

	private static final StubUpstream OLLAMA = StubUpstream.start("ollama");

	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	private RecordedUsage recordedUsage;

	@DynamicPropertySource
	static void gatewayRoutes(DynamicPropertyRegistry registry) {
		String routes = "spring.cloud.gateway.server.webflux.routes";

		registry.add(routes + "[0].id", () -> "vllm-llama");
		registry.add(routes + "[0].uri", VLLM::uri);
		registry.add(routes + "[0].predicates[0]", () -> "Path=/v1/**");
		registry.add(routes + "[0].predicates[1]", () -> "LlmModel=llama-3.1-70b*");
		registry.add(routes + "[0].filters[0]", () -> "LlmAuth");
		registry.add(routes + "[0].filters[1]", () -> "LlmQuota");

		registry.add(routes + "[1].id", () -> "ollama-mixtral");
		registry.add(routes + "[1].uri", OLLAMA::uri);
		registry.add(routes + "[1].predicates[0]", () -> "Path=/v1/**");
		registry.add(routes + "[1].predicates[1]", () -> "LlmModel=mixtral*");
		registry.add(routes + "[1].filters[0]", () -> "LlmAuth");
		registry.add(routes + "[1].filters[1]", () -> "LlmQuota");

		registry.add("llm.gateway.upstreams.vllm.api-key", () -> "real-vllm-key");
		// Model ids contain dots, so the map key needs bracket notation; dotted form would be
		// read as nested properties and never match the model in the request body.
		registry.add("llm.gateway.models[llama-3.1-70b-instruct].upstream", () -> "vllm");
		registry.add("llm.gateway.models[mixtral-8x7b].upstream", () -> "ollama");

		// Full access, generous budget.
		registry.add("llm.gateway.auth.keys.team.secret", () -> "sk-team");
		registry.add("llm.gateway.auth.keys.team.tenant", () -> "acme");
		registry.add("llm.gateway.auth.keys.team.models[0]", () -> "*");
		registry.add("llm.gateway.auth.keys.team.tokens-per-minute", () -> "1000000");

		// Llama only, so a mixtral request is rejected by the allow-list.
		registry.add("llm.gateway.auth.keys.llama-only.secret", () -> "sk-llama-only");
		registry.add("llm.gateway.auth.keys.llama-only.models[0]", () -> "llama-3.1-70b*");
		registry.add("llm.gateway.auth.keys.llama-only.tokens-per-minute", () -> "1000000");

		// Budget small enough that a single request drains it.
		registry.add("llm.gateway.auth.keys.tiny.secret", () -> "sk-tiny");
		registry.add("llm.gateway.auth.keys.tiny.models[0]", () -> "*");
		registry.add("llm.gateway.auth.keys.tiny.tokens-per-minute", () -> "600");

		registry.add("llm.gateway.quota.default-max-tokens", () -> "500");
	}

	@AfterAll
	static void stopUpstreams() {
		VLLM.stop();
		OLLAMA.stop();
	}

	@BeforeEach
	void reset() {
		VLLM.reset();
		OLLAMA.reset();
		this.recordedUsage.events().clear();
	}

	private WebTestClient.ResponseSpec post(String key, String body) {
		return this.webTestClient.post()
			.uri("/v1/chat/completions")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange();
	}

	private static String chatRequest(String model, boolean stream) {
		return """
				{"model":"%s","stream":%s,"max_tokens":16,
				 "messages":[{"role":"user","content":"hello there"}]}""".formatted(model, stream);
	}

	@Test
	void routesOnTheModelInTheRequestBody() {
		post("sk-team", chatRequest("llama-3.1-70b-instruct", false)).expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.choices[0].message.content")
			.isEqualTo("served by vllm");

		post("sk-team", chatRequest("mixtral-8x7b", false)).expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.choices[0].message.content")
			.isEqualTo("served by ollama");

		assertThat(VLLM.received()).hasSize(1);
		assertThat(OLLAMA.received()).hasSize(1);
	}

	@Test
	void replacesTheVirtualKeyWithTheUpstreamCredential() {
		post("sk-team", chatRequest("llama-3.1-70b-instruct", false)).expectStatus().isOk();

		assertThat(VLLM.lastReceived().authorization()).isEqualTo("Bearer real-vllm-key");
	}

	@Test
	void dropsTheVirtualKeyWhenTheUpstreamNeedsNoCredential() {
		post("sk-team", chatRequest("mixtral-8x7b", false)).expectStatus().isOk();

		// Ollama has no api-key configured, so nothing should be forwarded at all.
		assertThat(OLLAMA.lastReceived().authorization()).isNull();
	}

	@Test
	void rejectsAnUnknownKey() {
		post("sk-nope", chatRequest("llama-3.1-70b-instruct", false)).expectStatus()
			.isUnauthorized()
			.expectBody()
			.jsonPath("$.error.code")
			.isEqualTo("invalid_api_key");

		assertThat(VLLM.received()).isEmpty();
	}

	@Test
	void rejectsAMissingAuthorizationHeader() {
		this.webTestClient.post()
			.uri("/v1/chat/completions")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(chatRequest("llama-3.1-70b-instruct", false))
			.exchange()
			.expectStatus()
			.isUnauthorized();

		assertThat(VLLM.received()).isEmpty();
	}

	@Test
	void rejectsAModelOutsideTheKeysAllowList() {
		post("sk-llama-only", chatRequest("mixtral-8x7b", false)).expectStatus()
			.isForbidden()
			.expectBody()
			.jsonPath("$.error.code")
			.isEqualTo("model_not_allowed");

		assertThat(OLLAMA.received()).isEmpty();

		// The same key is fine on a model it does own.
		post("sk-llama-only", chatRequest("llama-3.1-70b-instruct", false)).expectStatus().isOk();
	}

	@Test
	void returnsTooManyRequestsOnceTheTokenBudgetIsSpent() {
		// 600 tokens per minute; each request reserves ~16 completion plus prompt estimate, so
		// a large max_tokens drains it in one call.
		String greedy = """
				{"model":"llama-3.1-70b-instruct","max_tokens":590,
				 "messages":[{"role":"user","content":"hello there"}]}""";

		post("sk-tiny", greedy).expectStatus().isOk();

		post("sk-tiny", greedy).expectStatus()
			.isEqualTo(429)
			.expectHeader()
			.exists(HttpHeaders.RETRY_AFTER)
			.expectBody()
			.jsonPath("$.error.code")
			.isEqualTo("rate_limit_exceeded");
	}

	@Test
	void capturesUsageFromAStreamedResponse() {
		String body = this.webTestClient.post()
			.uri("/v1/chat/completions")
			.header(HttpHeaders.AUTHORIZATION, "Bearer sk-team")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(chatRequest("llama-3.1-70b-instruct", true))
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();

		// The stream still reaches the client intact.
		assertThat(body).contains("served ").contains("[DONE]");

		// stream_options was injected, which is the only reason the upstream sent usage at all.
		assertThat(VLLM.lastReceived().body()).contains("\"include_usage\":true");

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(this.recordedUsage.events()).hasSize(1);
			LlmUsageEvent event = this.recordedUsage.events().get(0);
			assertThat(event.isStreamed()).isTrue();
			assertThat(event.getModel()).isEqualTo("llama-3.1-70b-instruct");
			assertThat(event.getTenant()).isEqualTo("acme");
			assertThat(event.getUsage().promptTokens()).isEqualTo(11);
			assertThat(event.getUsage().completionTokens()).isEqualTo(3);
			assertThat(event.getUsage().totalTokens()).isEqualTo(14);
			assertThat(event.getTimeToFirstToken()).isNotNull();
		});
	}

	@Test
	void capturesUsageFromANonStreamedResponse() {
		post("sk-team", chatRequest("llama-3.1-70b-instruct", false)).expectStatus().isOk();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(this.recordedUsage.events()).hasSize(1);
			LlmUsageEvent event = this.recordedUsage.events().get(0);
			assertThat(event.isStreamed()).isFalse();
			assertThat(event.getUsage().totalTokens()).isEqualTo(26);
			assertThat(event.getRouteId()).isEqualTo("vllm-llama");
		});
	}

	@SpringBootApplication
	static class TestApplication {

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RecordingConfiguration {

		@Bean
		RecordedUsage recordedUsage() {
			return new RecordedUsage();
		}

	}

	static class RecordedUsage {

		private final List<LlmUsageEvent> events = new CopyOnWriteArrayList<>();

		@EventListener
		void on(LlmUsageEvent event) {
			this.events.add(event);
		}

		List<LlmUsageEvent> events() {
			return this.events;
		}

	}

}

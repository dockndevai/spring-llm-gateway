package io.github.dockndevai.gateway.integration;

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
 * End-to-end proof that {@code {enc}} credentials work: the upstream key and the virtual key
 * secret are both stored encrypted, and the upstream still receives the correct plaintext
 * credential while the caller's own token never reaches it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive",
				// Present at environment-prepared time, as they would be in application.yml.
				"llm.gateway.secrets.key=" + LlmEncryptedSecretsIntegrationTests.KEY,
				"llm.gateway.upstreams.vllm.api-key="
						+ LlmEncryptedSecretsIntegrationTests.ENCRYPTED_UPSTREAM_KEY,
				"llm.gateway.auth.keys.team.secret="
						+ LlmEncryptedSecretsIntegrationTests.ENCRYPTED_VIRTUAL_KEY,
				"spring.autoconfigure.exclude="
						+ "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
						+ "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration,"
						+ "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration" })
class LlmEncryptedSecretsIntegrationTests {

	private static final StubUpstream UPSTREAM = StubUpstream.start("vllm");

	/**
	 * Fixed rather than generated, so they can appear in {@code @SpringBootTest(properties=...)}.
	 * That matters: values added by {@code @DynamicPropertySource} arrive after the environment
	 * has been prepared, which is exactly when spring-cloud-context inspects properties for its
	 * own {@code {cipher}} prefix. A generated-at-runtime value therefore never exercised the
	 * startup path that a real application.yml takes.
	 */
	static final String KEY = "FwXYvnmLVPO0jb88wz4qYJ9lMvCUiHzWy0LyjKFXtco=";

	static final String ENCRYPTED_UPSTREAM_KEY = "{enc}z7XT/ozozXqttW8lFU+3LAK9hIr3TZw88PurHdmMxqoq/an2BoFThuk=";

	static final String ENCRYPTED_VIRTUAL_KEY = "{enc}iPucTb9sl4foafzXFH/e34sW2gdICezDCmmtPW5izQK4+pQ=";

	@Autowired
	private WebTestClient webTestClient;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("llm.gateway.auth.keys.team.models[0]", () -> "*");
		registry.add("llm.gateway.auth.keys.team.tokens-per-minute", () -> "1000000");
		registry.add("llm.gateway.models[llama-3.1-70b-instruct].upstream", () -> "vllm");

		String routes = "spring.cloud.gateway.server.webflux.routes";
		registry.add(routes + "[0].id", () -> "vllm-llama");
		registry.add(routes + "[0].uri", UPSTREAM::uri);
		registry.add(routes + "[0].predicates[0]", () -> "Path=/v1/**");
		registry.add(routes + "[0].filters[0]", () -> "LlmAuth");
	}

	@AfterAll
	static void stop() {
		UPSTREAM.stop();
	}

	private WebTestClient.ResponseSpec post(String token) {
		return this.webTestClient.post()
			.uri("/v1/chat/completions")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"model\":\"llama-3.1-70b-instruct\","
					+ "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
			.exchange();
	}

	@Test
	void decryptsBothTheVirtualKeyAndTheUpstreamCredential() {
		post("sk-team").expectStatus().isOk();

		// The upstream saw the decrypted real key, never the ciphertext and never the caller's.
		assertThat(UPSTREAM.lastReceived().authorization()).isEqualTo("Bearer real-vllm-key");
		assertThat(UPSTREAM.lastReceived().authorization()).doesNotContain("{enc}");
		assertThat(UPSTREAM.lastReceived().authorization()).doesNotContain("sk-team");
	}

	@Test
	void stillRejectsAWrongKeyWhenSecretsAreEncrypted() {
		post("sk-not-the-team-key").expectStatus().isUnauthorized();
	}

	@SpringBootApplication
	static class TestApplication {

	}

}

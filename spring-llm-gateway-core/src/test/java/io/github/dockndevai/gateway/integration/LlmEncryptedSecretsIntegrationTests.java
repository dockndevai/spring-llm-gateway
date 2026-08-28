package io.github.dockndevai.gateway.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import io.github.dockndevai.gateway.security.AesGcmSecretCipher;

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
 * End-to-end proof that {@code {cipher}} credentials work: the upstream key and the virtual key
 * secret are both stored encrypted, and the upstream still receives the correct plaintext
 * credential while the caller's own token never reaches it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive",
				"spring.autoconfigure.exclude="
						+ "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
						+ "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration,"
						+ "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration" })
class LlmEncryptedSecretsIntegrationTests {

	private static final StubUpstream UPSTREAM = StubUpstream.start("vllm");

	private static final String KEY = AesGcmSecretCipher.generateKey();

	private static final AesGcmSecretCipher CIPHER = new AesGcmSecretCipher(KEY);

	@Autowired
	private WebTestClient webTestClient;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("llm.gateway.secrets.key", () -> KEY);

		// Both the upstream credential and the client's virtual key are stored encrypted.
		registry.add("llm.gateway.upstreams.vllm.api-key", () -> CIPHER.encrypt("real-vllm-key"));
		registry.add("llm.gateway.auth.keys.team.secret", () -> CIPHER.encrypt("sk-team"));
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
		assertThat(UPSTREAM.lastReceived().authorization()).doesNotContain("{cipher}");
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

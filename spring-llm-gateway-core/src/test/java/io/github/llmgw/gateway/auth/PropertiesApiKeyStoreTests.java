package io.github.llmgw.gateway.auth;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.llmgw.gateway.config.LlmGatewayProperties;

import static org.assertj.core.api.Assertions.assertThat;

class PropertiesApiKeyStoreTests {

	private final LlmGatewayProperties properties = new LlmGatewayProperties();

	private final PropertiesApiKeyStore store = new PropertiesApiKeyStore(this.properties);

	private LlmGatewayProperties.Key addKey(String id, String secret) {
		LlmGatewayProperties.Key key = new LlmGatewayProperties.Key();
		key.setSecret(secret);
		key.setTenant("acme");
		key.setModels(List.of("llama-3.1-70b*"));
		key.setTokensPerMinute(1234L);
		this.properties.getAuth().getKeys().put(id, key);
		return key;
	}

	@Test
	void resolvesPlaintextSecret() {
		addKey("dev", "sk-plaintext");

		LlmPrincipal principal = this.store.findBySecret("sk-plaintext").block();

		assertThat(principal).isNotNull();
		assertThat(principal.id()).isEqualTo("dev");
		assertThat(principal.tenant()).isEqualTo("acme");
		assertThat(principal.tokensPerMinute()).isEqualTo(1234L);
	}

	@Test
	void resolvesHashedSecret() {
		addKey("prod", PropertiesApiKeyStore.hash("sk-real-secret"));

		assertThat(this.store.findBySecret("sk-real-secret").block()).isNotNull();
		assertThat(this.store.findBySecret("sk-wrong").block()).isNull();
	}

	@Test
	void rejectsUnknownAndDisabledKeys() {
		addKey("dev", "sk-plaintext").setEnabled(false);

		assertThat(this.store.findBySecret("sk-plaintext").block()).isNull();
		assertThat(this.store.findBySecret("sk-nothing").block()).isNull();
		assertThat(this.store.findBySecret("").block()).isNull();
		assertThat(this.store.findBySecret(null).block()).isNull();
	}

	@Test
	void enforcesModelAllowList() {
		addKey("dev", "sk-plaintext");

		LlmPrincipal principal = this.store.findBySecret("sk-plaintext").block();

		assertThat(principal.allows("llama-3.1-70b-instruct")).isTrue();
		assertThat(principal.allows("mixtral-8x7b")).isFalse();
		// A body with no model at all stays allowed: not every endpoint names one.
		assertThat(principal.allows(null)).isTrue();
	}

	@Test
	void malformedHashDoesNotMatch() {
		addKey("broken", "sha256:not-hex");

		assertThat(this.store.findBySecret("anything").block()).isNull();
	}

}

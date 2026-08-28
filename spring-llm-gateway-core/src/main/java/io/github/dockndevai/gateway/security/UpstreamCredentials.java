package io.github.dockndevai.gateway.security;

import java.util.function.BiConsumer;

import io.github.dockndevai.gateway.config.LlmGatewayProperties;

import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Turns an upstream's configured credential into the header to send, decrypting it if it is
 * stored in {@code {cipher}} form.
 * <p>
 * Shared by the auth filter and the failover handler so that the rule "never forward the caller's
 * own token upstream" is implemented exactly once.
 */
public class UpstreamCredentials {

	private final SecretCipher cipher;

	public UpstreamCredentials(SecretCipher cipher) {
		this.cipher = cipher;
	}

	/**
	 * Rewrites the outbound headers for one upstream.
	 * <p>
	 * The caller's {@code Authorization} is always removed, even when the upstream needs no
	 * credential of its own — otherwise a virtual key or a Keycloak token would be handed to the
	 * inference server, which has no business seeing either.
	 * @param upstream the resolved upstream, or {@code null} when the model maps to none
	 */
	public void apply(HttpHeaders headers, @Nullable LlmGatewayProperties.Upstream upstream) {
		write(upstream, headers::remove, headers::set);
	}

	private void write(@Nullable LlmGatewayProperties.Upstream upstream, java.util.function.Consumer<String> remove,
			BiConsumer<String, String> set) {
		remove.accept(HttpHeaders.AUTHORIZATION);
		if (upstream == null || !StringUtils.hasText(upstream.getApiKey())) {
			return;
		}
		String prefix = (upstream.getPrefix() == null) ? "" : upstream.getPrefix();
		set.accept(upstream.getHeader(), prefix + resolve(upstream.getApiKey()));
	}

	/** Decrypts a configured credential. */
	public String resolve(String configuredValue) {
		return this.cipher.decrypt(configuredValue);
	}

}

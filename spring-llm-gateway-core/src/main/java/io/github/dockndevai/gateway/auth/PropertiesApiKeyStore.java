package io.github.dockndevai.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import reactor.core.publisher.Mono;

import io.github.dockndevai.gateway.config.LlmGatewayProperties;
import io.github.dockndevai.gateway.security.SecretCipher;

/**
 * {@link ApiKeyStore} backed by {@code llm.gateway.auth.keys}.
 * <p>
 * A key's {@code secret} is either a plaintext value (fine for development) or
 * {@code sha256:<hex>} of the real secret. Both comparisons go through
 * {@link MessageDigest#isEqual} so they do not leak the position of the first differing byte.
 */
public class PropertiesApiKeyStore implements ApiKeyStore {

	private static final String SHA256_PREFIX = "sha256:";

	private final LlmGatewayProperties properties;

	private final SecretCipher cipher;

	public PropertiesApiKeyStore(LlmGatewayProperties properties, SecretCipher cipher) {
		this.properties = properties;
		this.cipher = cipher;
	}

	@Override
	public Mono<LlmPrincipal> findBySecret(String presentedSecret) {
		if (presentedSecret == null || presentedSecret.isBlank()) {
			return Mono.empty();
		}
		byte[] presentedBytes = presentedSecret.getBytes(StandardCharsets.UTF_8);
		byte[] presentedSha = sha256(presentedBytes);

		LlmPrincipal match = null;
		for (Map.Entry<String, LlmGatewayProperties.Key> entry : this.properties.getAuth().getKeys().entrySet()) {
			LlmGatewayProperties.Key key = entry.getValue();
			if (!key.isEnabled() || key.getSecret() == null) {
				continue;
			}
			// Deliberately no early exit: every configured key is compared so that lookup
			// time does not depend on where the matching key sits in the map.
			if (matches(key.getSecret(), presentedBytes, presentedSha) && match == null) {
				match = new LlmPrincipal(entry.getKey(), key.getTenant(), key.getModels(), key.getTokensPerMinute());
			}
		}
		return (match == null) ? Mono.empty() : Mono.just(match);
	}

	private boolean matches(String configuredValue, byte[] presentedBytes, byte[] presentedSha) {
		// A {enc} secret is decrypted first; sha256: is preferred for client keys because a
		// hash cannot be reversed even by whoever holds the encryption key.
		String configured = this.cipher.decrypt(configuredValue);
		if (configured.startsWith(SHA256_PREFIX)) {
			byte[] expected;
			try {
				expected = HexFormat.of().parseHex(configured.substring(SHA256_PREFIX.length()).trim());
			}
			catch (IllegalArgumentException ex) {
				return false;
			}
			return MessageDigest.isEqual(expected, presentedSha);
		}
		return MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8), presentedBytes);
	}

	private static byte[] sha256(byte[] input) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(input);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}

	/** Helper for turning a secret into the {@code sha256:...} form used in configuration. */
	public static String hash(String secret) {
		return SHA256_PREFIX + HexFormat.of().formatHex(sha256(secret.getBytes(StandardCharsets.UTF_8)));
	}

}

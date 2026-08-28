package io.github.dockndevai.gateway.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decorates a {@link SecretCipher} so each distinct ciphertext is decrypted once rather than on
 * every request. Keyed on the ciphertext, so the map holds only values already present in
 * configuration and cannot grow from request traffic.
 */
public class CachingSecretCipher implements SecretCipher {

	private final SecretCipher delegate;

	private final Map<String, String> cache = new ConcurrentHashMap<>();

	public CachingSecretCipher(SecretCipher delegate) {
		this.delegate = delegate;
	}

	@Override
	public String decrypt(String value) {
		if (!SecretCipher.isEncrypted(value)) {
			return value;
		}
		return this.cache.computeIfAbsent(value, this.delegate::decrypt);
	}

	@Override
	public String encrypt(String plaintext) {
		return this.delegate.encrypt(plaintext);
	}

	@Override
	public boolean isEnabled() {
		return this.delegate.isEnabled();
	}

}

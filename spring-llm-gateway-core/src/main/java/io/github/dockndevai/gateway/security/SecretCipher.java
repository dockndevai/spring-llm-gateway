package io.github.dockndevai.gateway.security;

/**
 * Decrypts configuration values so credentials need not sit in plaintext in {@code application.yml}
 * or in a Git repository.
 * <p>
 * Values are marked with a {@value #CIPHER_PREFIX} prefix. Anything without the prefix is returned
 * unchanged, so encryption can be adopted one value at a time and a deployment with no key
 * configured keeps working exactly as before.
 * <p>
 * <strong>This protects secrets at rest, not in memory.</strong> A decrypted credential lives in
 * the heap for as long as the process does, and anyone who can read the process, a heap dump, or
 * the key itself can recover it. Encrypted config is a defence against leaked config files and
 * repository history — it is not a substitute for a real secret manager, and for production the
 * better answer is usually to inject credentials from Vault, a cloud secret manager, or a
 * Kubernetes secret and leave this untouched.
 */
public interface SecretCipher {

	/** Marks a configuration value as encrypted. */
	String CIPHER_PREFIX = "{cipher}";

	/**
	 * Decrypt a configuration value.
	 * @param value a possibly {@value #CIPHER_PREFIX} prefixed value; {@code null} and unprefixed
	 * values are returned unchanged
	 * @return the plaintext
	 * @throws IllegalStateException if the value is encrypted but this cipher has no key, or the
	 * ciphertext fails authentication
	 */
	String decrypt(String value);

	/**
	 * Encrypt a value into the {@value #CIPHER_PREFIX} form for pasting into configuration.
	 * @throws IllegalStateException if this cipher has no key
	 */
	String encrypt(String plaintext);

	/** Whether a key is configured. False for the no-op cipher used when encryption is off. */
	boolean isEnabled();

	/** Whether a configuration value is marked as encrypted. */
	static boolean isEncrypted(String value) {
		return value != null && value.startsWith(CIPHER_PREFIX);
	}

}

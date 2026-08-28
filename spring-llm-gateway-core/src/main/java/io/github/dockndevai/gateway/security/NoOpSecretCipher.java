package io.github.dockndevai.gateway.security;

/**
 * The cipher used when no key is configured. Passes plaintext through untouched.
 * <p>
 * An encrypted value reaching this cipher is a configuration mistake — the credential would
 * otherwise be forwarded upstream as the literal string {@code {cipher}...} and fail in a way that
 * looks like an upstream auth problem — so it fails loudly and names the missing property instead.
 */
public class NoOpSecretCipher implements SecretCipher {

	@Override
	public String decrypt(String value) {
		if (SecretCipher.isEncrypted(value)) {
			throw new IllegalStateException(
					"Encountered an encrypted configuration value but no decryption key is set. "
							+ "Set llm.gateway.secrets.key (ideally from the LLM_GATEWAY_SECRETS_KEY "
							+ "environment variable) or replace the value with plaintext.");
		}
		return value;
	}

	@Override
	public String encrypt(String plaintext) {
		throw new IllegalStateException("No encryption key configured; set llm.gateway.secrets.key.");
	}

	@Override
	public boolean isEnabled() {
		return false;
	}

}

package io.github.dockndevai.gateway.security;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class AesGcmSecretCipherTests {

	private final String key = AesGcmSecretCipher.generateKey();

	private final AesGcmSecretCipher cipher = new AesGcmSecretCipher(this.key);

	@Test
	void roundTripsAValue() {
		String encrypted = this.cipher.encrypt("sk-real-upstream-key");

		assertThat(encrypted).startsWith(SecretCipher.CIPHER_PREFIX);
		assertThat(this.cipher.decrypt(encrypted)).isEqualTo("sk-real-upstream-key");
	}

	@Test
	void plaintextPassesThroughUntouched() {
		assertThat(this.cipher.decrypt("sk-plaintext")).isEqualTo("sk-plaintext");
		assertThat(this.cipher.decrypt(null)).isNull();
	}

	@Test
	void encryptingTwiceProducesDifferentCiphertext() {
		// A fresh IV per encryption. Identical output would leak that two upstreams share a key.
		assertThat(this.cipher.encrypt("same")).isNotEqualTo(this.cipher.encrypt("same"));
	}

	@Test
	void rejectsAValueEncryptedUnderADifferentKey() {
		String fromElsewhere = new AesGcmSecretCipher(AesGcmSecretCipher.generateKey()).encrypt("secret");

		assertThatIllegalStateException().isThrownBy(() -> this.cipher.decrypt(fromElsewhere))
			.withMessageContaining("wrong key");
	}

	@Test
	void rejectsTamperedCiphertext() {
		String encrypted = this.cipher.encrypt("sk-real-upstream-key");
		byte[] blob = Base64.getDecoder().decode(encrypted.substring(SecretCipher.CIPHER_PREFIX.length()));
		// Flip a bit in the ciphertext body; GCM authentication must reject it.
		blob[blob.length - 1] ^= 0x01;
		String tampered = SecretCipher.CIPHER_PREFIX + Base64.getEncoder().encodeToString(blob);

		assertThatIllegalStateException().isThrownBy(() -> this.cipher.decrypt(tampered));
	}

	@Test
	void neverLeaksThePlaintextOrCiphertextInErrors() {
		String fromElsewhere = new AesGcmSecretCipher(AesGcmSecretCipher.generateKey()).encrypt("topsecret");

		assertThatIllegalStateException().isThrownBy(() -> this.cipher.decrypt(fromElsewhere))
			.matches(ex -> !ex.getMessage().contains("topsecret"))
			.matches(ex -> !ex.getMessage().contains(fromElsewhere));
	}

	@Test
	void rejectsTruncatedValues() {
		String tooShort = SecretCipher.CIPHER_PREFIX + Base64.getEncoder().encodeToString(new byte[4]);

		assertThatIllegalStateException().isThrownBy(() -> this.cipher.decrypt(tooShort))
			.withMessageContaining("truncated");
	}

	@Test
	void rejectsAKeyOfTheWrongLength() {
		String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

		assertThatIllegalArgumentException().isThrownBy(() -> new AesGcmSecretCipher(shortKey))
			.withMessageContaining("32 bytes");
	}

	@Test
	void rejectsAKeyThatIsNotBase64() {
		assertThatIllegalArgumentException().isThrownBy(() -> new AesGcmSecretCipher("not base64 !!"))
			.withMessageContaining("base64");
	}

	@Test
	void generatedKeysAre32Bytes() {
		assertThat(Base64.getDecoder().decode(AesGcmSecretCipher.generateKey())).hasSize(32);
		assertThat(AesGcmSecretCipher.generateKey()).isNotEqualTo(AesGcmSecretCipher.generateKey());
	}

	@Test
	void noOpCipherFailsLoudlyOnAnEncryptedValue() {
		NoOpSecretCipher noOp = new NoOpSecretCipher();

		assertThat(noOp.isEnabled()).isFalse();
		assertThat(noOp.decrypt("plain")).isEqualTo("plain");
		assertThatIllegalStateException().isThrownBy(() -> noOp.decrypt(this.cipher.encrypt("x")))
			.withMessageContaining("llm.gateway.secrets.key");
	}

	@Test
	void cachingCipherReturnsTheSameResultAndOnlyDecryptsOnce() {
		String encrypted = this.cipher.encrypt("cached-value");
		int[] calls = { 0 };
		SecretCipher counting = new SecretCipher() {
			@Override
			public String decrypt(String value) {
				calls[0]++;
				return AesGcmSecretCipherTests.this.cipher.decrypt(value);
			}

			@Override
			public String encrypt(String plaintext) {
				return AesGcmSecretCipherTests.this.cipher.encrypt(plaintext);
			}

			@Override
			public boolean isEnabled() {
				return true;
			}
		};
		CachingSecretCipher caching = new CachingSecretCipher(counting);

		assertThat(caching.decrypt(encrypted)).isEqualTo("cached-value");
		assertThat(caching.decrypt(encrypted)).isEqualTo("cached-value");
		assertThat(calls[0]).isEqualTo(1);
	}

}

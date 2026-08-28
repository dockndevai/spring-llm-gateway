package io.github.dockndevai.gateway.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM {@link SecretCipher}.
 * <p>
 * GCM is authenticated encryption: tampering with a stored ciphertext makes decryption fail rather
 * than silently yielding different plaintext, which matters when the value is a credential about to
 * be sent to an upstream server. A fresh 12-byte IV is generated per encryption from
 * {@link SecureRandom} and prepended to the ciphertext, so encrypting the same value twice produces
 * different output — reusing an IV under GCM is catastrophic, so it is never derived or reused.
 * <p>
 * Wire format, base64 encoded after the {@code {enc}} prefix:
 * <pre>
 *   [ 12-byte IV ][ ciphertext ][ 16-byte GCM tag ]
 * </pre>
 *
 * The key is 32 raw bytes, base64 encoded in configuration. Generate one and encrypt values with:
 * <pre>
 *   java -cp spring-llm-gateway-core.jar \
 *     io.github.dockndevai.gateway.security.AesGcmSecretCipher genkey
 *   java -cp spring-llm-gateway-core.jar \
 *     io.github.dockndevai.gateway.security.AesGcmSecretCipher encrypt &lt;key&gt; &lt;value&gt;
 * </pre>
 */
public class AesGcmSecretCipher implements SecretCipher {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	private static final int KEY_BYTES = 32;

	private static final int IV_BYTES = 12;

	private static final int TAG_BITS = 128;

	private final SecretKey key;

	private final SecureRandom random = new SecureRandom();

	/**
	 * @param base64Key 32 raw bytes, base64 encoded
	 * @throws IllegalArgumentException if the key is not valid base64 or not 32 bytes
	 */
	public AesGcmSecretCipher(String base64Key) {
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(base64Key.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("llm.gateway.secrets.key is not valid base64", ex);
		}
		if (raw.length != KEY_BYTES) {
			// Deliberately does not echo the key or its contents into the exception.
			throw new IllegalArgumentException("llm.gateway.secrets.key must decode to exactly " + KEY_BYTES
					+ " bytes (AES-256); got " + raw.length);
		}
		this.key = new SecretKeySpec(raw, "AES");
		Arrays.fill(raw, (byte) 0);
	}

	@Override
	public String decrypt(String value) {
		if (!SecretCipher.isEncrypted(value)) {
			return value;
		}
		byte[] blob;
		try {
			blob = Base64.getDecoder().decode(value.substring(CIPHER_PREFIX.length()).trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("Encrypted configuration value is not valid base64", ex);
		}
		if (blob.length <= IV_BYTES) {
			throw new IllegalStateException("Encrypted configuration value is truncated");
		}
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, this.key,
					new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
			byte[] plaintext = cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
			return new String(plaintext, StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException ex) {
			// Never include the ciphertext or any partial plaintext in the message.
			throw new IllegalStateException(
					"Failed to decrypt a configuration value: wrong key, or the value was tampered with", ex);
		}
	}

	@Override
	public String encrypt(String plaintext) {
		byte[] iv = new byte[IV_BYTES];
		this.random.nextBytes(iv);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] blob = new byte[iv.length + ciphertext.length];
			System.arraycopy(iv, 0, blob, 0, iv.length);
			System.arraycopy(ciphertext, 0, blob, iv.length, ciphertext.length);
			return CIPHER_PREFIX + Base64.getEncoder().encodeToString(blob);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Failed to encrypt a configuration value", ex);
		}
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

	/** Generates a fresh base64 AES-256 key. */
	public static String generateKey() {
		byte[] raw = new byte[KEY_BYTES];
		new SecureRandom().nextBytes(raw);
		String encoded = Base64.getEncoder().encodeToString(raw);
		Arrays.fill(raw, (byte) 0);
		return encoded;
	}

	/**
	 * Small operator tool for generating a key and encrypting values.
	 * <p>
	 * Takes the value as an argument for convenience; be aware that command-line arguments are
	 * visible to other processes and land in shell history, so prefer running this on a workstation
	 * rather than a shared host.
	 */
	public static void main(String[] args) {
		if (args.length == 1 && "genkey".equals(args[0])) {
			System.out.println(generateKey());
			return;
		}
		if (args.length == 3 && "encrypt".equals(args[0])) {
			System.out.println(new AesGcmSecretCipher(args[1]).encrypt(args[2]));
			return;
		}
		if (args.length == 3 && "decrypt".equals(args[0])) {
			System.out.println(new AesGcmSecretCipher(args[1]).decrypt(args[2]));
			return;
		}
		System.err.println("""
				usage:
				  genkey                      generate a base64 AES-256 key
				  encrypt <base64Key> <value> print the {enc}... form for application.yml
				  decrypt <base64Key> <value> print the plaintext, to verify a value""");
		System.exit(2);
	}

}

package io.github.dockndevai.gateway.auth;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import io.github.dockndevai.gateway.config.LlmGatewayProperties;

import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the Keycloak path with real RSA signature verification: tokens are minted locally and
 * validated against a JWK set served over HTTP, the same way {@code jwk-set-uri} works against a
 * real realm — no container, and the build stays offline.
 */
class JwtPrincipalResolverTests {

	private static RSAKey signingKey;

	private static RSAKey otherKey;

	private static DisposableServer jwksServer;

	private static ReactiveJwtDecoder decoder;

	private final LlmGatewayProperties properties = new LlmGatewayProperties();

	private final JwtPrincipalResolver resolver = new JwtPrincipalResolver(decoder, this.properties);

	@BeforeAll
	static void startJwks() throws Exception {
		signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
		otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
		String jwks = new JWKSet(signingKey.toPublicJWK()).toString();

		jwksServer = HttpServer.create()
			.port(0)
			.handle((request, response) -> response.header("Content-Type", "application/json")
				.sendString(Mono.just(jwks))
				.then())
			.bindNow();

		decoder = NimbusReactiveJwtDecoder.withJwkSetUri("http://localhost:" + jwksServer.port() + "/jwks").build();
	}

	@AfterAll
	static void stopJwks() {
		jwksServer.disposeNow();
	}

	private static String mint(RSAKey key, JWTClaimsSet claims) throws Exception {
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
				claims);
		jwt.sign(new RSASSASigner(key));
		return jwt.serialize();
	}

	private static JWTClaimsSet.Builder claims() {
		return new JWTClaimsSet.Builder().subject("service-account-chatbot")
			.issueTime(new Date())
			.expirationTime(Date.from(Instant.now().plusSeconds(300)));
	}

	@Test
	void mapsClaimsToAPrincipal() throws Exception {
		String token = mint(signingKey, claims().claim("tenant", "acme")
			.claim("llm_models", List.of("llama-3.1-70b*", "mixtral*"))
			.claim("llm_tpm", 25_000)
			.build());

		LlmPrincipal principal = this.resolver.resolve(null, token).block();

		assertThat(principal).isNotNull();
		assertThat(principal.id()).isEqualTo("service-account-chatbot");
		assertThat(principal.tenant()).isEqualTo("acme");
		assertThat(principal.tokensPerMinute()).isEqualTo(25_000L);
		assertThat(principal.allows("llama-3.1-70b-instruct")).isTrue();
		assertThat(principal.allows("gpt-4")).isFalse();
	}

	@Test
	void acceptsSpaceSeparatedModelsClaim() throws Exception {
		// Keycloak protocol mappers commonly emit a single delimited string rather than an array.
		String token = mint(signingKey, claims().claim("llm_models", "llama-3.1-70b* mixtral*").build());

		LlmPrincipal principal = this.resolver.resolve(null, token).block();

		assertThat(principal.allowedModels()).containsExactly("llama-3.1-70b*", "mixtral*");
	}

	@Test
	void honoursConfiguredClaimNames() throws Exception {
		this.properties.getAuth().getJwt().setTenantClaim("org_id");
		this.properties.getAuth().getJwt().setModelsClaim("allowed_models");
		this.properties.getAuth().getJwt().setTokensPerMinuteClaim("budget");
		this.properties.getAuth().getJwt().setPrincipalClaim("azp");

		String token = mint(signingKey, claims().claim("org_id", "globex")
			.claim("allowed_models", List.of("mixtral*"))
			.claim("budget", "9000")
			.claim("azp", "chatbot-client")
			.build());

		LlmPrincipal principal = this.resolver.resolve(null, token).block();

		assertThat(principal.id()).isEqualTo("chatbot-client");
		assertThat(principal.tenant()).isEqualTo("globex");
		assertThat(principal.tokensPerMinute()).isEqualTo(9000L);
		assertThat(principal.allowedModels()).containsExactly("mixtral*");
	}

	@Test
	void fallsBackToDefaultsWhenClaimsAreAbsent() throws Exception {
		this.properties.getAuth().getJwt().setDefaultModels(List.of("llama*"));

		String token = mint(signingKey, claims().build());

		LlmPrincipal principal = this.resolver.resolve(null, token).block();

		assertThat(principal.tenant()).isEqualTo("default");
		assertThat(principal.tokensPerMinute()).isNull();
		assertThat(principal.allowedModels()).containsExactly("llama*");
	}

	@Test
	void rejectsATokenSignedByTheWrongKey() throws Exception {
		String token = mint(otherKey, claims().claim("tenant", "attacker").build());

		assertThat(this.resolver.resolve(null, token).block()).isNull();
	}

	@Test
	void rejectsAnExpiredToken() throws Exception {
		String token = mint(signingKey, new JWTClaimsSet.Builder().subject("stale")
			.issueTime(Date.from(Instant.now().minusSeconds(7200)))
			.expirationTime(Date.from(Instant.now().minusSeconds(3600)))
			.build());

		assertThat(this.resolver.resolve(null, token).block()).isNull();
	}

	@Test
	void rejectsGarbage() {
		assertThat(this.resolver.resolve(null, "not-a-jwt").block()).isNull();
	}

}

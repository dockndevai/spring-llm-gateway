package io.github.dockndevai.gateway.integration;

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
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole gateway in {@code auth.mode=jwt} against a JWK set served over HTTP, which is
 * how it behaves against a Keycloak realm.
 * <p>
 * This exists because unit-testing {@link io.github.dockndevai.gateway.auth.JwtPrincipalResolver} in
 * isolation missed a wiring bug: the resolver bean was guarded by
 * {@code @ConditionalOnBean(ReactiveJwtDecoder.class)}, which was evaluated before
 * oauth2-resource-server auto-configuration had registered that decoder, so in jwt mode the
 * application failed to start with "no qualifying bean of type PrincipalResolver".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "spring.main.web-application-type=reactive")
class LlmJwtAuthIntegrationTests {

	private static final StubUpstream UPSTREAM = StubUpstream.start("vllm");

	private static final RSAKey SIGNING_KEY = generateKey();

	private static final DisposableServer JWKS_SERVER = startJwks();

	@Autowired
	private WebTestClient webTestClient;

	private static RSAKey generateKey() {
		try {
			return new RSAKeyGenerator(2048).keyID("kc-test").generate();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static DisposableServer startJwks() {
		String jwks = new JWKSet(SIGNING_KEY.toPublicJWK()).toString();
		return HttpServer.create()
			.port(0)
			.handle((request, response) -> response.header("Content-Type", "application/json")
				.sendString(Mono.just(jwks))
				.then())
			.bindNow();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
				() -> "http://localhost:" + JWKS_SERVER.port() + "/certs");

		registry.add("llm.gateway.auth.mode", () -> "jwt");
		registry.add("llm.gateway.quota.defaults.tokens-per-minute", () -> "1000000");

		String routes = "spring.cloud.gateway.server.webflux.routes";
		registry.add(routes + "[0].id", () -> "vllm-llama");
		registry.add(routes + "[0].uri", UPSTREAM::uri);
		registry.add(routes + "[0].predicates[0]", () -> "Path=/v1/**");
		registry.add(routes + "[0].filters[0]", () -> "LlmAuth");
		registry.add(routes + "[0].filters[1]", () -> "LlmQuota");
	}

	@AfterAll
	static void stop() {
		UPSTREAM.stop();
		JWKS_SERVER.disposeNow();
	}

	private static String mint(JWTClaimsSet claims) {
		try {
			SignedJWT jwt = new SignedJWT(
					new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(), claims);
			jwt.sign(new RSASSASigner(SIGNING_KEY));
			return jwt.serialize();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static JWTClaimsSet.Builder claims() {
		return new JWTClaimsSet.Builder().subject("service-account-chatbot")
			.issueTime(new Date())
			.expirationTime(Date.from(Instant.now().plusSeconds(300)));
	}

	private WebTestClient.ResponseSpec post(String token, String model) {
		return this.webTestClient.post()
			.uri("/v1/chat/completions")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
			.exchange();
	}

	@Test
	void acceptsAKeycloakIssuedToken() {
		String token = mint(claims().claim("tenant", "acme")
			.claim("llm_models", List.of("llama-3.1-70b*"))
			.claim("llm_tpm", 50_000)
			.build());

		post(token, "llama-3.1-70b-instruct").expectStatus().isOk();

		assertThat(UPSTREAM.received()).isNotEmpty();
		// The caller's JWT must not be forwarded upstream.
		assertThat(UPSTREAM.lastReceived().authorization()).isNull();
	}

	@Test
	void enforcesTheModelAllowListFromClaims() {
		String token = mint(claims().claim("tenant", "acme").claim("llm_models", List.of("mixtral*")).build());

		post(token, "llama-3.1-70b-instruct").expectStatus()
			.isForbidden()
			.expectBody()
			.jsonPath("$.error.code")
			.isEqualTo("model_not_allowed");
	}

	@Test
	void rejectsAnExpiredToken() {
		String token = mint(new JWTClaimsSet.Builder().subject("stale")
			.issueTime(Date.from(Instant.now().minusSeconds(7200)))
			.expirationTime(Date.from(Instant.now().minusSeconds(3600)))
			.build());

		post(token, "llama-3.1-70b-instruct").expectStatus()
			.isUnauthorized()
			.expectBody()
			.jsonPath("$.error.code")
			.isEqualTo("invalid_api_key");
	}

	@Test
	void rejectsGarbage() {
		post("not-a-jwt", "llama-3.1-70b-instruct").expectStatus().isUnauthorized();
	}

	@SpringBootApplication
	static class TestApplication {

	}

	/**
	 * Authorization is LlmAuth's job; Spring Security is here only to supply the decoder. Without
	 * this, its default chain rejects every request before the gateway sees it. Mirrors the
	 * sample's SampleSecurityConfiguration.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class PermitAllSecurity {

		@Bean
		SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
			return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
				.build();
		}

	}

}

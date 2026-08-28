package io.github.dockndevai.gateway.auth;

import reactor.core.publisher.Mono;

import org.springframework.web.server.ServerWebExchange;

/**
 * Turns the bearer token on an exchange into an {@link LlmPrincipal}.
 * <p>
 * Two implementations ship: {@link StaticKeyPrincipalResolver} for the configured virtual keys
 * and {@link JwtPrincipalResolver} for Keycloak-issued tokens. Which one is active is decided
 * by {@code llm.gateway.auth.mode}.
 */
public interface PrincipalResolver {

	/**
	 * @param exchange the current exchange
	 * @param bearerToken the token stripped of its {@code Bearer } prefix
	 * @return the caller, or an empty {@link Mono} when the token is not valid
	 */
	Mono<LlmPrincipal> resolve(ServerWebExchange exchange, String bearerToken);

}

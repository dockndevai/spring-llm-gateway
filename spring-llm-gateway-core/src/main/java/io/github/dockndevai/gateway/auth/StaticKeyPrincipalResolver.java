package io.github.dockndevai.gateway.auth;

import reactor.core.publisher.Mono;

import org.springframework.web.server.ServerWebExchange;

/** Resolves callers against an {@link ApiKeyStore}. Active when {@code llm.gateway.auth.mode=static}. */
public class StaticKeyPrincipalResolver implements PrincipalResolver {

	private final ApiKeyStore keyStore;

	public StaticKeyPrincipalResolver(ApiKeyStore keyStore) {
		this.keyStore = keyStore;
	}

	@Override
	public Mono<LlmPrincipal> resolve(ServerWebExchange exchange, String bearerToken) {
		return this.keyStore.findBySecret(bearerToken);
	}

}

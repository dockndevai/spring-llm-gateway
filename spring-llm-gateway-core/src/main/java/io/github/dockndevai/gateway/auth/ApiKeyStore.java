package io.github.dockndevai.gateway.auth;

import reactor.core.publisher.Mono;

/**
 * Looks up the virtual key a client presented. The default implementation reads
 * {@code llm.gateway.auth.keys}; replace this bean to back keys with a database instead.
 */
public interface ApiKeyStore {

	/**
	 * Resolve a presented secret to a principal.
	 * @param presentedSecret the raw token from the {@code Authorization: Bearer} header
	 * @return the matching principal, or an empty {@link Mono} when nothing matches
	 */
	Mono<LlmPrincipal> findBySecret(String presentedSecret);

}

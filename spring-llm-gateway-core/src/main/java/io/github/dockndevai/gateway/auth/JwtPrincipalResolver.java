package io.github.dockndevai.gateway.auth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import io.github.dockndevai.gateway.config.LlmGatewayProperties;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

/**
 * Resolves callers from a Keycloak-issued JWT. Active when {@code llm.gateway.auth.mode=jwt}.
 * <p>
 * Signature, issuer and expiry are validated by the {@link ReactiveJwtDecoder} that
 * spring-security-oauth2-resource-server auto-configures from
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} (or {@code jwk-set-uri}).
 * Entitlements are then read off the token's claims; the claim names are configurable, so a
 * realm can expose them through whatever protocol mapper it already uses.
 */
public class JwtPrincipalResolver implements PrincipalResolver {

	private static final Log log = LogFactory.getLog(JwtPrincipalResolver.class);

	private final ReactiveJwtDecoder jwtDecoder;

	private final LlmGatewayProperties properties;

	public JwtPrincipalResolver(ReactiveJwtDecoder jwtDecoder, LlmGatewayProperties properties) {
		this.jwtDecoder = jwtDecoder;
		this.properties = properties;
	}

	@Override
	public Mono<LlmPrincipal> resolve(ServerWebExchange exchange, String bearerToken) {
		return this.jwtDecoder.decode(bearerToken).map(this::toPrincipal).onErrorResume(ex -> {
			if (log.isDebugEnabled()) {
				log.debug("Rejecting token: " + ex.getMessage());
			}
			return Mono.empty();
		});
	}

	private LlmPrincipal toPrincipal(Jwt jwt) {
		LlmGatewayProperties.Jwt cfg = this.properties.getAuth().getJwt();

		String id = asString(jwt.getClaim(cfg.getPrincipalClaim()));
		if (!StringUtils.hasText(id)) {
			id = jwt.getSubject();
		}

		String tenant = asString(jwt.getClaim(cfg.getTenantClaim()));

		List<String> models = asStringList(jwt.getClaim(cfg.getModelsClaim()));
		if (models.isEmpty()) {
			models = cfg.getDefaultModels();
		}

		Long tpm = asLong(jwt.getClaim(cfg.getTokensPerMinuteClaim()));

		return new LlmPrincipal(id, tenant, models, tpm);
	}

	private static String asString(Object claim) {
		return (claim == null) ? null : String.valueOf(claim);
	}

	/** Accepts a JSON array, or a single space/comma separated string, which Keycloak mappers often emit. */
	private static List<String> asStringList(Object claim) {
		List<String> out = new ArrayList<>();
		if (claim instanceof Collection<?> collection) {
			for (Object o : collection) {
				if (o != null && StringUtils.hasText(o.toString())) {
					out.add(o.toString().trim());
				}
			}
		}
		else if (claim instanceof String s && StringUtils.hasText(s)) {
			for (String part : s.split("[,\\s]+")) {
				if (StringUtils.hasText(part)) {
					out.add(part.trim());
				}
			}
		}
		return out;
	}

	private static Long asLong(Object claim) {
		if (claim instanceof Number n) {
			return n.longValue();
		}
		if (claim instanceof String s && StringUtils.hasText(s)) {
			try {
				return Long.parseLong(s.trim());
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}
		return null;
	}

}

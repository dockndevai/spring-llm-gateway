package io.github.dockndevai.gateway.auth;

import java.util.List;

import org.springframework.util.PatternMatchUtils;

/**
 * A resolved caller. Produced either from a configured virtual key or from a Keycloak JWT,
 * and carried on the exchange for the quota and metering filters to use.
 *
 * @param id stable identifier, used as the quota bucket key
 * @param tenant tenant this caller bills to
 * @param allowedModels model patterns this caller may call, matched with simple {@code *} globs
 * @param tokensPerMinute this caller's token budget, or {@code null} to use the configured default
 */
public record LlmPrincipal(String id, String tenant, List<String> allowedModels, Long tokensPerMinute) {

	public LlmPrincipal {
		allowedModels = (allowedModels == null || allowedModels.isEmpty()) ? List.of("*") : List.copyOf(allowedModels);
		tenant = (tenant == null || tenant.isBlank()) ? "default" : tenant;
	}

	/**
	 * Whether this caller may use the given model. A request with no model in the body is
	 * allowed through: model-less endpoints such as {@code /v1/models} are still useful.
	 */
	public boolean allows(String model) {
		if (model == null || model.isBlank()) {
			return true;
		}
		return PatternMatchUtils.simpleMatch(this.allowedModels.toArray(String[]::new), model);
	}

}

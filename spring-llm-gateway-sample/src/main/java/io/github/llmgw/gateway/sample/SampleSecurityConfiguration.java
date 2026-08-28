package io.github.llmgw.gateway.sample;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Hands authorization to the {@code LlmAuth} filter.
 * <p>
 * spring-boot-starter-oauth2-resource-server is on the classpath so that the {@code jwt} profile
 * gets a {@code ReactiveJwtDecoder} from {@code spring.security.oauth2.resourceserver.jwt.*}.
 * Its default filter chain would otherwise reject every request before the gateway sees it, and
 * it would reject with an empty body rather than an OpenAI-shaped error. Spring Security is here
 * purely to validate and decode tokens; {@code LlmAuth} decides what a token is allowed to do.
 */
@Configuration(proxyBeanMethods = false)
class SampleSecurityConfiguration {

	@Bean
	SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
		return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
			.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
			.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
			.authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
			.build();
	}

}

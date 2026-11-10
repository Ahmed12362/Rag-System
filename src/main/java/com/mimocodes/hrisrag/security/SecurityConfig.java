package com.mimocodes.hrisrag.security;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP Basic over a stateless API with simulated users (decision D35). The caller's identity comes only from the
 * authenticated principal; no endpoint accepts a user or role field. Health stays public for container probes, without
 * details. CSRF protection is off because there are no cookies or sessions (every request carries its credentials).
 */
@Configuration
public class SecurityConfig {

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	/** Only in a web application: command-line runs (benchmark, ingestion) start without a servlet stack. */
	@Bean
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
	SecurityFilterChain api(HttpSecurity http) throws Exception {
		http.csrf(c -> c.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a
						.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/error").permitAll()
						.requestMatchers("/api/v1/experiments", "/api/v1/experiments/**").hasRole("HR_ADMIN")
						.requestMatchers("/api/**").authenticated()
						.anyRequest().denyAll())
				.httpBasic(b -> b.realmName("hris-rag").authenticationEntryPoint((req, res, e) -> {
					res.setHeader("WWW-Authenticate", "Basic realm=\"hris-rag\"");
					problem(res, HttpStatus.UNAUTHORIZED, "Unauthorized", "Valid credentials are required.");
				}))
				.exceptionHandling(x -> x.accessDeniedHandler((req, res, e) -> problem(res, HttpStatus.FORBIDDEN,
						"Forbidden", "You are not allowed to access this resource.")));
		return http.build();
	}

	/** RFC 9457 body, consistent with the MVC error responses. Never echoes the submitted credentials. */
	private static void problem(HttpServletResponse res, HttpStatus status, String title, String detail)
			throws java.io.IOException {
		res.setStatus(status.value());
		res.setContentType("application/problem+json");
		res.setCharacterEncoding(StandardCharsets.UTF_8.name());
		res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status.value()
				+ ",\"detail\":\"" + detail + "\"}");
	}
}

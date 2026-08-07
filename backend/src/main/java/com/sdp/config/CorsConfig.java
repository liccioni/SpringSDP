package com.sdp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * The WebSocket connection isn't subject to the browser's CORS model, but
 * POST /login is a plain HTTP fetch from a different origin (the frontend's
 * own port, even in local dev) - the first thing in this app that needs it.
 */
@Configuration
public class CorsConfig {

	@Bean
	public CorsWebFilter corsWebFilter(@Value("${app.frontend-origin}") String frontendOrigin) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.addAllowedOrigin(frontendOrigin);
		configuration.addAllowedMethod("POST");
		configuration.addAllowedHeader("Content-Type");

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/login", configuration);
		return new CorsWebFilter(source);
	}
}

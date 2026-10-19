package com.pidea.security;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless HTTP Basic security with role-based access.
 *
 * <p>Authorisation is enforced on the server with {@code @PreAuthorize} on controller methods
 * (method security). Users and their BCrypt password hashes come from configuration/environment,
 * never from source code. Basic auth over TLS is a deliberate, simple choice for a portfolio
 * service-to-service API; see the README security notes for the trade-offs versus JWT.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityUsersProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ApiAuthenticationEntryPoint authenticationEntryPoint)
            throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)   // stateless API, no cookies/session -> no CSRF surface
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(Customizer.withDefaults())
            .headers(h -> h.frameOptions(f -> f.deny()))
            .exceptionHandling(e -> e.authenticationEntryPoint(authenticationEntryPoint))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);    // adaptive hash; cost factor 12
    }

    @Bean
    UserDetailsService userDetailsService(SecurityUsersProperties props) {
        List<UserDetails> users = props.users().stream()
            .filter(u -> u.passwordHash() != null && !u.passwordHash().isBlank())
            .map(u -> (UserDetails) User.withUsername(u.username()).password(u.passwordHash()).roles(u.role()).build())
            .toList();
        return new InMemoryUserDetailsManager(users);
    }

    /** Restricted CORS: only explicitly configured origins; none by default. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityUsersProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(props.allowedOrigins());
        c.setAllowedMethods(List.of("GET", "POST", "PATCH"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/api/**", c);
        return s;
    }
}

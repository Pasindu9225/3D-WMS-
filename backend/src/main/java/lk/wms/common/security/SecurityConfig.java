package lk.wms.common.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * SEC-09 baseline: deny by default. Only health and the error page are public; every other
 * route needs a logged-in user, and every controller method needs @PreAuthorize (ArchUnit).
 * Security errors go to GlobalExceptionHandler so 401/403 use the same ProblemDetail shape.
 * JWT cookie auth (task 4) and CSRF/CORS/headers (task 5) plug in here.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> resolver.resolveException(req, res, null, ex))
                        .accessDeniedHandler((req, res, ex) -> resolver.resolveException(req, res, null, ex)));
        return http.build();
    }
}

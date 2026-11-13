package lk.wms.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.wms.auth.api.AuthCookies;
import lk.wms.auth.service.SessionService;
import lk.wms.auth.service.TokenService;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.tenant.TenantFilter;
import lk.wms.common.web.RateLimiter;
import lk.wms.common.web.UserRateLimitFilter;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.web.access.IpAddressAuthorizationManager;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.WebUtils;

/**
 * Deny by default (SEC-09). The access JWT is read ONLY from its HttpOnly cookie (SEC-01), verified
 * with the RS256 public key (SEC-02), then its session is checked on every request (SEC-05).
 * Security errors go to GlobalExceptionHandler so 401/403 use the same ProblemDetail shape.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    static final String CSRF_PATH = "/api/v1/auth/csrf";
    static final String[] PUBLIC_POSTS = {"/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
        "/api/v1/auth/invite/accept"};

    /** Public auth endpoints. The access cookie is ignored here so a stale one cannot block login. */
    static final Set<String> PUBLIC_AUTH = Set.of(PUBLIC_POSTS[0], PUBLIC_POSTS[1], PUBLIC_POSTS[2], PUBLIC_POSTS[3],
        CSRF_PATH);

    /** SEC-61. React sets styles through the DOM, which CSP allows, so no 'unsafe-inline' is needed. */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:;"
            + " font-src 'self'; connect-src 'self'; worker-src 'self' blob:; object-src 'none';"
            + " base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SessionService sessions, JwtDecoder decoder,
            RateLimiter rateLimiter, @Value("${wms.cors.allowed-origins:}") List<String> allowedOrigins,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        AuthenticationEntryPoint entryPoint = (req, res, ex) -> resolver.resolveException(req, res, null, ex);
        AccessDeniedHandler denied = (req, res, ex) -> resolver.resolveException(req, res, null, ex);
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // SEC-11: SPA mode = readable XSRF-TOKEN cookie, sent back as the X-XSRF-TOKEN header.
                .csrf(c -> c.spa().csrfTokenRepository(csrfCookie()))
                .cors(c -> c.configurationSource(cors(allowedOrigins)))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000).includeSubDomains(true))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .frameOptions(f -> f.deny())
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                                "camera=(self), microphone=(), geolocation=(), payment=()")))
                .authorizeHttpRequests(a -> a
                        // SEC-64: health only from the server itself (Nginx never forwards /actuator)
                        .requestMatchers("/actuator/health").access(AuthorizationManagers.anyOf(
                                IpAddressAuthorizationManager.hasIpAddress("127.0.0.1"),
                                IpAddressAuthorizationManager.hasIpAddress("::1")))
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POSTS).permitAll()
                        .requestMatchers(HttpMethod.GET, CSRF_PATH).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(cookieTokenResolver())
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(sessionChecked(sessions)))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
                .addFilterAfter(new TenantFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new UserRateLimitFilter(rateLimiter), TenantFilter.class);
        return http.build();
    }

    private static CookieCsrfTokenRepository csrfCookie() {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse(); // JS must read it
        repo.setCookieCustomizer(c -> c.secure(true).sameSite("Strict").path("/"));
        return repo;
    }

    /** SEC-62: exact origins only, credentials allowed. Empty list = no cross-origin access at all. */
    public static CorsConfigurationSource cors(List<String> allowedOrigins) {
        List<String> origins = allowedOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).toList();
        if (origins.stream().anyMatch(o -> o.contains("*"))) {
            throw new IllegalStateException("wms.cors.allowed-origins must list exact origins, no wildcards");
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private static BearerTokenResolver cookieTokenResolver() {
        return request -> {
            if (PUBLIC_AUTH.contains(request.getRequestURI())) {
                return null;
            }
            var cookie = WebUtils.getCookie(request, AuthCookies.ACCESS);
            return cookie == null || cookie.getValue().isBlank() ? null : cookie.getValue();
        };
    }

    /** SEC-05: a valid signature is not enough; the session must still be live and match the token. */
    private static Converter<Jwt, AbstractAuthenticationToken> sessionChecked(SessionService sessions) {
        return jwt -> {
            String sid = jwt.getClaimAsString("sid");
            CurrentUser user = sid == null ? null : sessions.verify(sid).orElse(null);
            if (user == null || !user.userPublicId().equals(jwt.getSubject())) {
                throw new InvalidBearerTokenException("invalid session");
            }
            List<GrantedAuthority> authorities = new ArrayList<>();
            user.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
            user.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
        };
    }

    // ------------------------------------------------------------ keys and encoders

    /** SEC-02: RS256 key pair from PEM files outside Git (PKCS#8 private, X.509 public). */
    @Bean
    JwtEncoder jwtEncoder(@Value("${wms.jwt.private-key}") Resource privateKey,
            @Value("${wms.jwt.public-key}") Resource publicKey) throws IOException {
        RSAKey jwk = new RSAKey.Builder(readPublic(publicKey)).privateKey(readPrivate(privateKey)).keyID("wms-1").build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${wms.jwt.public-key}") Resource publicKey) throws IOException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(readPublic(publicKey))
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TokenService.ISSUER));
        return decoder;
    }

    /** SEC-29: Argon2id; the {id} prefix lets us move to stronger settings later without a reset. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("argon2",
                Map.of("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }

    private static RSAPublicKey readPublic(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return RsaKeyConverters.x509().convert(in);
        }
    }

    private static RSAPrivateKey readPrivate(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return RsaKeyConverters.pkcs8().convert(in);
        }
    }
}

package lk.wms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lk.wms.IntegrationTest;
import lk.wms.auth.api.AuthCookies;
import lk.wms.auth.domain.AppUser;
import lk.wms.auth.repo.AppUserRepository;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** SEC-01..08, SEC-25, SEC-29 end to end through HTTP. */
@AutoConfigureMockMvc
class AuthFlowTest extends IntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final AtomicInteger IP_SEQ = new AtomicInteger(1);

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppUserRepository users;
    @Autowired
    PasswordEncoder passwords;
    @Autowired
    JwtEncoder jwtEncoder;

    String ip;
    long tenantId;
    AppUser user;

    @BeforeEach
    void freshTenantUserAndIp() {
        ip = "10.0." + (IP_SEQ.get() / 250) + "." + (IP_SEQ.getAndIncrement() % 250 + 1);
        tenantId = newTenant();
        user = newUser(tenantId, AppUser.Status.ACTIVE);
    }

    // ---------------------------------------------------------------- login

    @Test
    void loginSetsSecureHttpOnlyCookiesAndNoTokenInBody() throws Exception {
        MvcResult r = login(user.getEmail(), PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();
        List<String> setCookies = r.getResponse().getHeaders("Set-Cookie");
        assertThat(setCookies).anySatisfy(c -> assertThat(c).startsWith(AuthCookies.ACCESS + "=")
                .contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/;", "Max-Age=900"));
        assertThat(setCookies).anySatisfy(c -> assertThat(c).startsWith(AuthCookies.REFRESH + "=")
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=604800"));
        String body = r.getResponse().getContentAsString();
        assertThat(body).doesNotContain(access(r).getValue()).doesNotContain(refresh(r).getValue());
    }

    @Test
    void passwordIsStoredAsArgon2id() {
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, user.getId());
        assertThat(hash).startsWith("{argon2}$argon2id$");
    }

    @Test
    void meWorksWithCookieAndNeedsIt() throws Exception {
        MvcResult r = login(user.getEmail(), PASSWORD).andReturn();
        mvc.perform(get("/api/v1/auth/me").cookie(access(r)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getPublicId()));
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordAndUnknownEmailLookIdentical() throws Exception {
        String wrong = login(user.getEmail(), "wrong-password").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED")).andReturn().getResponse().getContentAsString();
        String unknown = login("nobody-" + Ulid.next() + "@x.lk", PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED")).andReturn().getResponse().getContentAsString();
        assertThat(withoutCid(wrong)).isEqualTo(withoutCid(unknown));
    }

    @Test
    void disabledUserGetsTheSameGenericFailure() throws Exception {
        AppUser disabled = newUser(tenantId, AppUser.Status.DISABLED);
        login(disabled.getEmail(), PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Authentication failed"));
    }

    @Test
    void staleAccessCookieDoesNotBlockLogin() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(csrf()).with(fromIp())
                        .cookie(new Cookie(AuthCookies.ACCESS, "garbage.token.value"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- lockout (SEC-06)

    @Test
    void fiveFailuresLockTheAccountEvenForTheRightPassword() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(user.getEmail(), "wrong-" + i).andExpect(status().isUnauthorized());
        }
        login(user.getEmail(), PASSWORD).andExpect(status().isUnauthorized());
        Instant lockedUntil = lockedUntil();
        assertThat(lockedUntil).isBetween(Instant.now().plusSeconds(14 * 60), Instant.now().plusSeconds(16 * 60));

        jdbc.update("UPDATE app_user SET locked_until = NOW(3) - INTERVAL 1 SECOND WHERE id = ?", user.getId());
        login(user.getEmail(), PASSWORD).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT failed_logins FROM app_user WHERE id = ?", Integer.class, user.getId()))
                .isZero();
    }

    @Test
    void secondLockLastsLonger() throws Exception {
        jdbc.update("UPDATE app_user SET failed_logins = 9 WHERE id = ?", user.getId());
        login(user.getEmail(), "wrong").andExpect(status().isUnauthorized());
        Instant lockedUntil = lockedUntil();
        assertThat(lockedUntil).isAfter(Instant.now().plusSeconds(29 * 60));
    }

    @Test
    void tooManyFailuresFromOneIpBlockThatIp() throws Exception {
        for (int i = 0; i < 20; i++) {
            login("ghost-" + i + "@x.lk", "x").andExpect(status().isUnauthorized());
        }
        login(user.getEmail(), PASSWORD).andExpect(status().isUnauthorized());
        ip = "10.99.99." + (IP_SEQ.getAndIncrement() % 250 + 1); // another IP still works
        login(user.getEmail(), PASSWORD).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- refresh (SEC-03, SEC-04)

    @Test
    void refreshRotatesAndStoresOnlyHashes() throws Exception {
        MvcResult first = login(user.getEmail(), PASSWORD).andReturn();
        MvcResult second = refresh(refresh(first)).andExpect(status().isOk()).andReturn();
        assertThat(refresh(second).getValue()).isNotEqualTo(refresh(first).getValue());
        mvc.perform(get("/api/v1/auth/me").cookie(access(second))).andExpect(status().isOk());

        Integer rawStored = jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE token_hash = ?",
                Integer.class, refresh(second).getValue());
        assertThat(rawStored).isZero();
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeFamily() throws Exception {
        MvcResult first = login(user.getEmail(), PASSWORD).andReturn();
        MvcResult second = refresh(refresh(first)).andExpect(status().isOk()).andReturn();

        refresh(refresh(first)).andExpect(status().isUnauthorized());          // stolen old token replayed
        refresh(refresh(second)).andExpect(status().isUnauthorized());         // legit newest token now dead too
        mvc.perform(get("/api/v1/auth/me").cookie(access(second))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'TOKEN_REUSE' AND user_id = ?",
                Integer.class, user.getId())).isEqualTo(1);
    }

    @Test
    void failedRefreshClearsCookies() throws Exception {
        MvcResult r = refresh(new Cookie(AuthCookies.REFRESH, "not-a-real-token"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(r.getResponse().getHeaders("Set-Cookie")).allSatisfy(c -> assertThat(c).contains("Max-Age=0"));
    }

    @Test
    void expiredRefreshTokenIsRejected() throws Exception {
        MvcResult first = login(user.getEmail(), PASSWORD).andReturn();
        jdbc.update("UPDATE refresh_token SET expires_at = NOW(3) - INTERVAL 1 SECOND WHERE session_id IN"
                + " (SELECT id FROM user_session WHERE user_id = ?)", user.getId());
        refresh(refresh(first)).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- access token checks (SEC-02, SEC-05)

    @Test
    void expiredAccessTokenIsRejected() throws Exception {
        MvcResult r = login(user.getEmail(), PASSWORD).andReturn();
        String sid = jdbc.queryForObject("SELECT id FROM user_session WHERE user_id = ?", String.class, user.getId());
        Instant past = Instant.now().minusSeconds(3600);
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),
                JwtClaimsSet.builder().issuer("wms").subject(user.getPublicId()).issuedAt(past)
                        .expiresAt(past.plusSeconds(900)).claim("sid", sid).claim("tid", tenantId).build()))
                .getTokenValue();
        mvc.perform(get("/api/v1/auth/me").cookie(new Cookie(AuthCookies.ACCESS, expired)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(access(r))).andExpect(status().isOk());
    }

    @Test
    void tamperedAccessTokenIsRejected() throws Exception {
        String token = access(login(user.getEmail(), PASSWORD).andReturn()).getValue();
        String[] parts = token.split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"someone-else\",\"iss\":\"wms\",\"exp\":9999999999}").getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];
        mvc.perform(get("/api/v1/auth/me").cookie(new Cookie(AuthCookies.ACCESS, forged)))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- logout (SEC-08)

    @Test
    void logoutKillsAccessAndRefreshImmediately() throws Exception {
        MvcResult r = login(user.getEmail(), PASSWORD).andReturn();
        mvc.perform(get("/api/v1/auth/me").cookie(access(r))).andExpect(status().isOk()); // now cached

        MvcResult out = mvc.perform(post("/api/v1/auth/logout").with(csrf()).cookie(access(r), refresh(r)))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(out.getResponse().getHeaders("Set-Cookie")).hasSize(2)
                .allSatisfy(c -> assertThat(c).contains("Max-Age=0"));

        mvc.perform(get("/api/v1/auth/me").cookie(access(r))).andExpect(status().isUnauthorized());
        refresh(refresh(r)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutAllEndsEveryDevice() throws Exception {
        MvcResult laptop = login(user.getEmail(), PASSWORD).andReturn();
        MvcResult phone = login(user.getEmail(), PASSWORD).andReturn();
        mvc.perform(post("/api/v1/auth/logout-all").with(csrf()).cookie(access(laptop)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").cookie(access(phone))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(access(laptop))).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- password change

    @Test
    void changePasswordKeepsThisSessionAndEndsTheOthers() throws Exception {
        MvcResult laptop = login(user.getEmail(), PASSWORD).andReturn();
        MvcResult phone = login(user.getEmail(), PASSWORD).andReturn();
        String newPassword = "a-brand-new-passphrase";

        changePassword(laptop, "wrong-current", newPassword).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_INCORRECT"));
        changePassword(laptop, PASSWORD, "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        changePassword(laptop, PASSWORD, newPassword).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/me").cookie(access(laptop))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(access(phone))).andExpect(status().isUnauthorized());
        login(user.getEmail(), PASSWORD).andExpect(status().isUnauthorized());
        login(user.getEmail(), newPassword).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(csrf()).with(fromIp())
                .contentType(MediaType.APPLICATION_JSON).content(json(email, password)));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(Cookie refreshCookie) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").with(csrf()).cookie(refreshCookie));
    }

    private org.springframework.test.web.servlet.ResultActions changePassword(MvcResult session, String current,
            String next) throws Exception {
        return mvc.perform(post("/api/v1/auth/password/change").with(csrf()).cookie(access(session))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor fromIp() {
        String current = ip;
        return request -> {
            request.setRemoteAddr(current);
            return request;
        };
    }

    private static Cookie access(MvcResult r) {
        return cookie(r, AuthCookies.ACCESS);
    }

    private static Cookie refresh(MvcResult r) {
        return cookie(r, AuthCookies.REFRESH);
    }

    private static Cookie cookie(MvcResult r, String name) {
        Cookie c = r.getResponse().getCookie(name);
        assertThat(c).as("cookie " + name).isNotNull();
        return new Cookie(name, c.getValue());
    }

    private static String json(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String withoutCid(String body) {
        return body.replaceAll("\"correlationId\":\"[0-9A-Z]{26}\"", "");
    }

    /** DATETIME columns hold UTC (hibernate.jdbc.time_zone=UTC). */
    private Instant lockedUntil() {
        return jdbc.queryForObject("SELECT locked_until FROM app_user WHERE id = ?",
                java.time.LocalDateTime.class, user.getId()).toInstant(java.time.ZoneOffset.UTC);
    }

    private long newTenant() {
        String publicId = Ulid.next();
        jdbc.update("INSERT INTO tenant (public_id, name, plan_code, created_at) VALUES (?, 'T', 'TEST', NOW(3))",
                publicId);
        return jdbc.queryForObject("SELECT id FROM tenant WHERE public_id = ?", Long.class, publicId);
    }

    private AppUser newUser(long tenant, AppUser.Status status) {
        return TenantContext.callAs(tenant, () -> users.save(
                new AppUser("u-" + Ulid.next() + "@test.lk", "Test User", passwords.encode(PASSWORD), status)));
    }

}

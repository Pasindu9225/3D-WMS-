package lk.wms.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lk.wms.auth.domain.AppUser;
import lk.wms.auth.domain.SignupRequest;
import lk.wms.auth.repo.SignupRequestRepository;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.mail.Mailer;
import lk.wms.common.tenant.TenantContext;
import lk.wms.tenant.service.PlatformTenants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Self-service sign-up (v2.0 task 2). Nothing exists until the email is confirmed: the request waits with the password
 * already hashed and only a hash of the link token. The answer to a sign-up is always the same, whether or not the
 * email already has an account (SEC-07): an existing account gets a "you already have an account" email instead.
 */
@Service
public class SignupService {

    static final Duration LINK_TTL = Duration.ofHours(24);
    static final Duration KEEP = Duration.ofDays(7);

    private final SignupRequestRepository requests;
    private final UserService users;
    private final AuthService auth;
    private final PlatformTenants tenants;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final Mailer mailer;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final String plan;
    private final String baseUrl;

    public SignupService(SignupRequestRepository requests, UserService users, AuthService auth, PlatformTenants tenants,
            PasswordEncoder passwords, TokenService tokens, Mailer mailer, AuditService audit, PlatformTransactionManager tm,
            @Value("${wms.signup.plan}") String plan, @Value("${wms.app.base-url}") String baseUrl) {
        this.requests = requests;
        this.users = users;
        this.auth = auth;
        this.tenants = tenants;
        this.passwords = passwords;
        this.tokens = tokens;
        this.mailer = mailer;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
        this.plan = plan;
        this.baseUrl = baseUrl;
    }

    /** Stores the request and emails the link; same outcome for the caller whatever the email. */
    public void request(String companyName, String fullName, String email, String password, String ip) {
        String normalized = AppUser.normalizeEmail(email);
        if (!users.emailFree(normalized)) {
            passwords.encode(password); // the same work as a real sign-up: no timing hint
            mailer.send(normalized, "Your 3D WMS account", "Hello,\n\nSomeone (maybe you) tried to sign up for 3D WMS with this email address, "
                    + "but it already has an account. Sign in here: " + baseUrl + "/login\n\nIf you forgot your password, ask your company admin to "
                    + "send you a new invite. If this was not you, you can ignore this email.\n");
            TenantContext.callAsSystem(() -> {
                tx.executeWithoutResult(s -> audit.record(null, null, "SIGNUP_EXISTING_EMAIL", "signup_request", null));
                return null;
            });
            return;
        }
        String raw = tokens.newRefreshToken(); // 256 random bits
        String hash = passwords.encode(password);
        TenantContext.callAsSystem(() -> {
            tx.executeWithoutResult(s -> {
                requests.deletePending(normalized);
                requests.save(new SignupRequest(normalized, companyName.trim(), fullName.trim(), hash, TokenService.sha256(raw), ip,
                        Instant.now().plus(LINK_TTL)));
                audit.record(null, null, "SIGNUP_REQUESTED", "signup_request", null, null, Map.of("company", companyName.trim()));
                mailer.send(normalized, "Confirm your email to start using 3D WMS", "Hello " + fullName.trim() + ",\n\n"
                        + "Thank you for signing up " + companyName.trim() + " for 3D WMS. Open this link within 24 hours to confirm your "
                        + "email and start setting up your warehouse:\n\n" + baseUrl + "/verify#" + raw + "\n\n"
                        + "If you did not sign up, ignore this email and nothing will be created.\n");
            });
            return null;
        });
    }

    /** The link was opened: makes the company and its admin, then signs them in. One generic error for every bad link. */
    public AuthService.LoginResult verify(String rawToken, String ip, String userAgent) {
        String hash = TokenService.sha256(rawToken);
        SignupRequest r = TenantContext.callAsSystem(() -> tx.execute(s -> {
            SignupRequest found = requests.lockByHash(hash).orElse(null);
            if (found == null || !found.isUsable(Instant.now())) {
                return null;
            }
            found.use(Instant.now()); // used first: the same link opened twice at once makes one company
            return found;
        }));
        if (r == null || !users.emailFree(r.getEmail())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNUP_LINK_INVALID", "This link is not valid any more; sign up again");
        }
        // ponytail: company, admin and session are three steps in three transactions (each in its own company context)
        long tenantId = tenants.create(r.getCompanyName(), plan).id();
        String userId = TenantContext.callAs(tenantId, () -> users.createSignedUpAdmin(tenantId, r.getEmail(), r.getFullName(), r.getPasswordHash()));
        return auth.signInConfirmed(tenantId, userId, ip, userAgent);
    }
}

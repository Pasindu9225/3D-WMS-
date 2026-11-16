package lk.wms.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lk.wms.auth.domain.AppUser;
import lk.wms.auth.domain.Permission;
import lk.wms.auth.domain.Role;
import lk.wms.auth.domain.UserInvite;
import lk.wms.auth.domain.UserRole;
import lk.wms.auth.repo.AppUserRepository;
import lk.wms.auth.repo.RoleRepository;
import lk.wms.auth.repo.UserInviteRepository;
import lk.wms.auth.repo.UserRoleRepository;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Users and roles of one company. Every method runs in the caller's tenant (set by TenantFilter),
 * so a user ID from another company is simply "not found" (SEC-10). Each change is audited (SEC-28).
 */
@Service
public class UserService {

    static final Duration INVITE_TTL = Duration.ofHours(72);

    /** API view of a user: no internal ID, no password hash (SEC-25). */
    /** {@code lockedUntil} is set only while a lockout is running (F5). */
    public record UserView(String id, String email, String fullName, String status, List<String> roles,
            Instant createdAt, Instant lockedUntil) {
    }

    public record Invite(UserView user, String inviteUrl, Instant expiresAt) {
    }

    public record RoleView(String code, String name, List<String> permissions) {
    }

    /** Published inside the invite transaction, so listeners (warehouse access) commit or roll back with it. */
    public record UserInvited(long tenantId, long userId) {
    }

    /** Audit snapshot: what changed, never secrets. */
    record Snapshot(String email, String fullName, String status, List<String> roles) {
    }

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final UserInviteRepository invites;
    private final SessionService sessions;
    private final TokenService tokens;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final String appBaseUrl;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public UserService(AppUserRepository users, RoleRepository roles, UserRoleRepository userRoles,
            UserInviteRepository invites, SessionService sessions, TokenService tokens, PasswordEncoder passwords,
            AuditService audit, @Value("${wms.app.base-url}") String appBaseUrl, PlatformTransactionManager tm,
            ApplicationEventPublisher events) {
        this.users = users;
        this.roles = roles;
        this.userRoles = userRoles;
        this.invites = invites;
        this.sessions = sessions;
        this.tokens = tokens;
        this.passwords = passwords;
        this.audit = audit;
        this.appBaseUrl = appBaseUrl.replaceAll("/+$", "");
        this.tx = new TransactionTemplate(tm);
        this.events = events;
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public Page<UserView> list(String search, Pageable pageable) {
        Page<AppUser> page = search == null || search.isBlank()
                ? users.findAll(pageable)
                : users.findByFullNameContainingIgnoreCaseOrEmailContainingIgnoreCase(search.trim(), search.trim(),
                        pageable);
        Map<Long, List<String>> codes = roleCodes(page.getContent().stream().map(AppUser::getId).toList());
        return page.map(u -> view(u, codes.getOrDefault(u.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public List<RoleView> roles() {
        return roles.findAllWithPermissions().stream()
                .map(r -> new RoleView(r.getCode(), r.getName(),
                        r.getPermissions().stream().map(Permission::getCode).sorted().toList()))
                .toList();
    }

    /** Internal ID -> public ID and name, for showing who did something in the audit log. */
    @Transactional(readOnly = true)
    public Map<Long, UserView> viewsByInternalId(Collection<Long> ids) {
        return ids.isEmpty() ? Map.of() : users.findByIdIn(ids).stream()
                .collect(Collectors.toMap(AppUser::getId, u -> view(u, List.of())));
    }

    /** An active user who holds a permission; admin = company admin (works in every warehouse). */
    public record Holder(long id, String publicId, String fullName, boolean admin) {
    }

    @Transactional(readOnly = true)
    public List<Holder> holdersOf(long tenantId, String permission) {
        return users.holdersOf(tenantId, permission).stream().map(r -> new Holder(((Number) r[0]).longValue(), (String) r[1],
                (String) r[2], ((Number) r[3]).intValue() == 1)).toList();
    }

    /** Every user of the caller's company (internal IDs), e.g. to give everyone the first warehouse. */
    @Transactional(readOnly = true)
    public List<Long> allUserIds() {
        return users.findAll().stream().map(AppUser::getId).toList();
    }

    @Transactional(readOnly = true)
    public Long internalId(String publicId) {
        return users.findByPublicId(publicId).map(AppUser::getId).orElse(null);
    }

    // ---------------------------------------------------------------- invite

    @Transactional
    public Invite invite(CurrentUser actor, String email, String fullName, List<String> roleCodes) {
        List<Role> chosen = resolveRoles(roleCodes);
        String normalized = AppUser.normalizeEmail(email);
        AppUser user;
        try {
            // Emails are unique across all companies (login is by email). The unique key is the check:
            // it sees every company and cannot race. The tenant filter would hide other companies.
            user = users.saveAndFlush(new AppUser(normalized, fullName.trim(),
                    passwords.encode(tokens.newRefreshToken()), AppUser.Status.INVITED)); // random, never usable
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_IN_USE", "This email already has an account");
        }
        chosen.forEach(r -> userRoles.save(new UserRole(user.getId(), r.getId())));
        events.publishEvent(new UserInvited(actor.tenantId(), user.getId()));
        Invite invite = newInvite(user, actor.userId(), codes(chosen));
        audit.record(actor.tenantId(), actor.userId(), "USER_INVITED", "app_user", user.getPublicId(),
                null, snapshot(user, codes(chosen)));
        return invite;
    }

    @Transactional
    public Invite reissueInvite(CurrentUser actor, String userPublicId) {
        AppUser user = find(userPublicId);
        if (user.getStatus() != AppUser.Status.INVITED) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_INVITED", "This user has already set a password");
        }
        invites.revokeOpenForUser(user.getId(), Instant.now());
        audit.record(actor.tenantId(), actor.userId(), "USER_INVITE_REISSUED", "app_user", user.getPublicId());
        return newInvite(user, actor.userId(), currentCodes(user));
    }

    /** First admin of a new company (bootstrap command). Runs in that company's tenant. */
    @Transactional
    public Invite createFirstAdmin(long tenantId, String email, String fullName) {
        Role admin = roles.findByCode(Role.TENANT_ADMIN).orElseThrow();
        AppUser user = users.save(new AppUser(email, fullName, passwords.encode(tokens.newRefreshToken()),
                AppUser.Status.INVITED));
        userRoles.save(new UserRole(user.getId(), admin.getId()));
        audit.record(tenantId, null, "USER_INVITED", "app_user", user.getPublicId(), null,
                snapshot(user, List.of(Role.TENANT_ADMIN)));
        return newInvite(user, null, List.of(Role.TENANT_ADMIN));
    }

    /**
     * Public: the invited person sets a password. The visitor has no session, so the token is looked
     * up across tenants; one generic error for unknown, used, revoked or expired links.
     */
    public void acceptInvite(String rawToken, String newPassword) {
        String hash = TokenService.sha256(rawToken);
        Boolean ok = TenantContext.callAsSystem(() -> tx.execute(status -> {
            Instant now = Instant.now();
            UserInvite invite = invites.lockByHash(hash).orElse(null);
            if (invite == null || !invite.isUsable(now) || invite.getUser().getStatus() != AppUser.Status.INVITED) {
                return false;
            }
            AppUser user = invite.getUser();
            invite.markUsed(now);
            user.acceptInvite(passwords.encode(newPassword));
            audit.record(user.getTenantId(), user.getId(), "INVITE_ACCEPTED", "app_user", user.getPublicId());
            return true;
        }));
        if (!Boolean.TRUE.equals(ok)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVITE_INVALID", "This invite link is invalid or has expired");
        }
    }

    // ---------------------------------------------------------------- changes

    @Transactional
    public UserView rename(CurrentUser actor, String userPublicId, String fullName) {
        AppUser user = find(userPublicId);
        List<String> codes = currentCodes(user);
        Snapshot before = snapshot(user, codes);
        user.rename(fullName.trim());
        audit.record(actor.tenantId(), actor.userId(), "USER_RENAMED", "app_user", user.getPublicId(),
                before, snapshot(user, codes));
        return view(user, codes);
    }

    @Transactional
    public UserView disable(CurrentUser actor, String userPublicId) {
        AppUser user = find(userPublicId);
        if (user.getId() == actor.userId()) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_DISABLE_SELF", "You cannot disable your own account");
        }
        List<String> codes = currentCodes(user);
        if (codes.contains(Role.TENANT_ADMIN)) {
            requireAnotherActiveAdmin(user.getId());
        }
        Snapshot before = snapshot(user, codes);
        user.disable();
        sessions.revokeAll(user.getId(), null); // signed out everywhere at once
        audit.record(actor.tenantId(), actor.userId(), "USER_DISABLED", "app_user", user.getPublicId(),
                before, snapshot(user, codes));
        return view(user, codes);
    }

    @Transactional
    public UserView enable(CurrentUser actor, String userPublicId) {
        AppUser user = find(userPublicId);
        List<String> codes = currentCodes(user);
        Snapshot before = snapshot(user, codes);
        user.enable();
        audit.record(actor.tenantId(), actor.userId(), "USER_ENABLED", "app_user", user.getPublicId(),
                before, snapshot(user, codes));
        return view(user, codes);
    }

    /** F5: clear a lockout (anyone who knows an email can trigger one on purpose). */
    @Transactional
    public UserView unlock(CurrentUser actor, String userPublicId) {
        AppUser user = find(userPublicId);
        List<String> codes = currentCodes(user);
        user.unlock();
        audit.record(actor.tenantId(), actor.userId(), "USER_UNLOCKED", "app_user", user.getPublicId());
        return view(user, codes);
    }

    @Transactional
    public UserView setRoles(CurrentUser actor, String userPublicId, List<String> roleCodes) {
        AppUser user = find(userPublicId);
        List<Role> chosen = resolveRoles(roleCodes);
        List<String> before = currentCodes(user);
        List<String> after = codes(chosen);
        if (before.contains(Role.TENANT_ADMIN) && !after.contains(Role.TENANT_ADMIN)
                && user.getStatus() == AppUser.Status.ACTIVE) {
            requireAnotherActiveAdmin(user.getId());
        }
        userRoles.deleteAllForUser(user.getId());
        chosen.forEach(r -> userRoles.save(new UserRole(user.getId(), r.getId())));
        sessions.refreshPermissions(user.getId()); // their open sessions get the new permissions now
        audit.record(actor.tenantId(), actor.userId(), "USER_ROLES_CHANGED", "app_user", user.getPublicId(),
                snapshot(user, before), snapshot(user, after));
        return view(user, after);
    }

    // ---------------------------------------------------------------- helpers

    private AppUser find(String publicId) {
        return users.findByPublicId(publicId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
    }

    /** Locks all active admins first, so two admins removing each other cannot both succeed. */
    private void requireAnotherActiveAdmin(long userId) {
        Role admin = roles.findByCode(Role.TENANT_ADMIN).orElseThrow();
        boolean another = users.lockActiveWithRole(admin.getId()).stream().anyMatch(u -> u.getId() != userId);
        if (!another) {
            throw new ApiException(HttpStatus.CONFLICT, "LAST_ADMIN", "The company must keep at least one active admin");
        }
    }

    private List<Role> resolveRoles(List<String> requested) {
        Set<String> wanted = new LinkedHashSet<>(requested);
        List<Role> found = roles.findByCodeIn(wanted);
        if (found.size() != wanted.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROLE_UNKNOWN", "One or more roles do not exist");
        }
        return found;
    }

    private Invite newInvite(AppUser user, Long createdBy, List<String> codes) {
        String raw = tokens.newRefreshToken(); // 256 random bits
        Instant expires = Instant.now().plus(INVITE_TTL);
        invites.save(new UserInvite(user, TokenService.sha256(raw), expires, createdBy));
        // In the #fragment: browsers never send it to a server, so it stays out of access logs and Referer.
        return new Invite(view(user, codes), appBaseUrl + "/invite#" + raw, expires);
    }

    private List<String> currentCodes(AppUser user) {
        return roleCodes(List.of(user.getId())).getOrDefault(user.getId(), List.of());
    }

    private Map<Long, List<String>> roleCodes(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> out = new HashMap<>();
        for (UserRoleRepository.UserRoleCode row : userRoles.findCodesForUsers(userIds)) {
            out.computeIfAbsent(row.getUserId(), k -> new java.util.ArrayList<>()).add(row.getCode());
        }
        return out;
    }

    private static List<String> codes(List<Role> chosen) {
        return chosen.stream().map(Role::getCode).sorted().toList();
    }

    private static UserView view(AppUser u, List<String> codes) {
        return new UserView(u.getPublicId(), u.getEmail(), u.getFullName(), u.getStatus().name(), codes,
                u.getCreatedAt(), u.isLocked(Instant.now()) ? u.getLockedUntil() : null);
    }

    private static Snapshot snapshot(AppUser u, List<String> codes) {
        return new Snapshot(u.getEmail(), u.getFullName(), u.getStatus().name(), codes);
    }
}

package lk.wms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.List;
import lk.wms.IntegrationTest;
import lk.wms.auth.api.AuthCookies;
import lk.wms.auth.domain.AppUser;
import lk.wms.auth.domain.UserRole;
import lk.wms.auth.repo.AppUserRepository;
import lk.wms.auth.repo.RoleRepository;
import lk.wms.auth.repo.UserRoleRepository;
import lk.wms.auth.service.TokenService;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Task 6: users, roles, invites and audit (SEC-09, SEC-10, SEC-22, SEC-25, SEC-28). */
@AutoConfigureMockMvc
class UserManagementTest extends IntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppUserRepository users;
    @Autowired
    RoleRepository roles;
    @Autowired
    UserRoleRepository userRoles;
    @Autowired
    PasswordEncoder passwords;

    long tenantA;
    long tenantB;
    AppUser adminA;
    Cookie adminCookie;

    @BeforeEach
    void companyWithAdmin() throws Exception {
        tenantA = newTenant();
        tenantB = newTenant();
        adminA = newUser(tenantA, "TENANT_ADMIN");
        adminCookie = login(adminA);
    }

    // ---------------------------------------------------------------- invite flow

    @Test
    void inviteThenAcceptThenLogin() throws Exception {
        String email = "new-" + Ulid.next() + "@test.lk";
        String body = as(adminCookie, post("/api/v1/users")
                .content("{\"email\":\"" + email + "\",\"fullName\":\"New Picker\",\"roles\":[\"PICKER\",\"PACKER\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.status").value("INVITED"))
                .andExpect(jsonPath("$.user.roles[0]").value("PACKER"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(body, "$.inviteUrl");
        assertThat(url).startsWith("http://localhost:5180/invite#");
        String token = url.substring(url.indexOf('#') + 1);

        // only the hash is stored
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_invite WHERE token_hash = ?", Integer.class,
                TokenService.sha256(token))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_invite WHERE token_hash = ?", Integer.class, token))
                .isZero();

        login(email, PASSWORD).andExpect(status().isUnauthorized()); // invited users cannot log in yet
        accept(token, PASSWORD).andExpect(status().isNoContent());
        login(email, PASSWORD).andExpect(status().isOk());
        accept(token, "another-password-1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITE_INVALID"));
    }

    @Test
    void expiredOrReissuedInviteLinksStopWorking() throws Exception {
        String token = inviteToken("old-" + Ulid.next() + "@test.lk");
        jdbc.update("UPDATE user_invite SET expires_at = NOW(3) - INTERVAL 1 MINUTE WHERE token_hash = ?",
                TokenService.sha256(token));
        accept(token, PASSWORD).andExpect(status().isBadRequest());

        String email = "re-" + Ulid.next() + "@test.lk";
        String first = inviteToken(email);
        String userId = jdbc.queryForObject("SELECT public_id FROM app_user WHERE email = ?", String.class, email);
        String body = as(adminCookie, post("/api/v1/users/" + userId + "/invite")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = ((String) JsonPath.read(body, "$.inviteUrl")).split("#")[1];
        accept(first, PASSWORD).andExpect(status().isBadRequest());
        accept(second, PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void inviteRejectsUnknownRolesBadInputAndTakenEmails() throws Exception {
        as(adminCookie, post("/api/v1/users").content("{\"email\":\"x" + Ulid.next() + "@t.lk\",\"fullName\":\"X\",\"roles\":[\"OWNER\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ROLE_UNKNOWN"));
        as(adminCookie, post("/api/v1/users").content("{\"email\":\"not-an-email\",\"fullName\":\"X\",\"roles\":[\"PICKER\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        as(adminCookie, post("/api/v1/users").content("{\"email\":\"y@t.lk\",\"fullName\":\"Y\",\"roles\":[]}"))
                .andExpect(status().isBadRequest());
        AppUser other = newUser(tenantB, "VIEWER"); // email taken in ANOTHER company
        as(adminCookie, post("/api/v1/users").content("{\"email\":\"" + other.getEmail() + "\",\"fullName\":\"Z\",\"roles\":[\"PICKER\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_IN_USE"));
    }

    // ---------------------------------------------------------------- list

    @Test
    void listIsPaginatedSearchableAndOwnCompanyOnly() throws Exception {
        newUser(tenantA, "PICKER");
        AppUser outsider = newUser(tenantB, "TENANT_ADMIN");
        as(adminCookie, get("/api/v1/users?size=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.total").value(2));
        as(adminCookie, get("/api/v1/users?size=1000")).andExpect(jsonPath("$.size").value(100));
        String all = as(adminCookie, get("/api/v1/users")).andReturn().getResponse().getContentAsString();
        assertThat(all).contains(adminA.getPublicId()).doesNotContain(outsider.getPublicId()).doesNotContain("argon2");
        as(adminCookie, get("/api/v1/users?q=" + adminA.getEmail()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].roles[0]").value("TENANT_ADMIN"));
        as(adminCookie, get("/api/v1/users?sort=passwordHash,asc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SORT_NOT_ALLOWED"));
    }

    // ---------------------------------------------------------------- permissions (SEC-09) and tenants (SEC-10)

    @Test
    void eachEndpointChecksItsPermission() throws Exception {
        Cookie viewer = login(newUser(tenantA, "VIEWER"));
        Cookie supervisor = login(newUser(tenantA, "SUPERVISOR"));
        as(viewer, get("/api/v1/users")).andExpect(status().isForbidden());
        as(viewer, get("/api/v1/audit-logs")).andExpect(status().isForbidden());
        as(supervisor, get("/api/v1/users")).andExpect(status().isOk());
        as(supervisor, post("/api/v1/users").content("{\"email\":\"s@t.lk\",\"fullName\":\"S\",\"roles\":[\"PICKER\"]}"))
                .andExpect(status().isForbidden());
        as(supervisor, put("/api/v1/users/" + adminA.getPublicId() + "/roles").content("{\"roles\":[\"VIEWER\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/roles")).andExpect(status().isUnauthorized());
    }

    @Test
    void otherCompanysUsersAreNotFound() throws Exception {
        AppUser outsider = newUser(tenantB, "PICKER");
        String id = outsider.getPublicId();
        as(adminCookie, patch("/api/v1/users/" + id).content("{\"fullName\":\"Hijacked\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        as(adminCookie, post("/api/v1/users/" + id + "/disable")).andExpect(status().isNotFound());
        as(adminCookie, put("/api/v1/users/" + id + "/roles").content("{\"roles\":[\"TENANT_ADMIN\"]}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, outsider.getId()))
                .isEqualTo("ACTIVE");
    }

    // ---------------------------------------------------------------- disable / enable / roles

    @Test
    void disablingEndsTheirSessionAtOnceAndEnablingRestoresLogin() throws Exception {
        AppUser picker = newUser(tenantA, "PICKER");
        Cookie pickerCookie = login(picker);
        as(pickerCookie, get("/api/v1/auth/me")).andExpect(status().isOk());

        as(adminCookie, post("/api/v1/users/" + picker.getPublicId() + "/disable")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        as(pickerCookie, get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        login(picker.getEmail(), PASSWORD).andExpect(status().isUnauthorized());

        as(adminCookie, post("/api/v1/users/" + picker.getPublicId() + "/enable"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        login(picker.getEmail(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void reEnablingSomeoneWhoNeverAcceptedKeepsThemInvited() throws Exception {
        String email = "pending-" + Ulid.next() + "@test.lk";
        String token = inviteToken(email);
        String id = jdbc.queryForObject("SELECT public_id FROM app_user WHERE email = ?", String.class, email);
        as(adminCookie, post("/api/v1/users/" + id + "/disable")).andExpect(jsonPath("$.status").value("DISABLED"));
        as(adminCookie, post("/api/v1/users/" + id + "/enable")).andExpect(jsonPath("$.status").value("INVITED"));
        accept(token, PASSWORD).andExpect(status().isNoContent()); // the open link still works
    }

    @Test
    void adminSeesAndClearsALockout() throws Exception {
        AppUser picker = newUser(tenantA, "PICKER");
        for (int i = 0; i < 5; i++) {
            login(picker.getEmail(), "wrong-" + i).andExpect(status().isUnauthorized());
        }
        login(picker.getEmail(), PASSWORD).andExpect(status().isUnauthorized()); // locked
        String list = as(adminCookie, get("/api/v1/users?q=" + picker.getEmail())).andReturn().getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(list, "$.items[0].lockedUntil")).isNotNull(); // F5: visible

        as(adminCookie, post("/api/v1/users/" + picker.getPublicId() + "/unlock")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedUntil").doesNotExist());
        login(picker.getEmail(), PASSWORD).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'USER_UNLOCKED' AND entity_id = ?",
                Integer.class, picker.getPublicId())).isOne();
    }

    @Test
    void cannotDisableYourselfOrRemoveTheLastAdmin() throws Exception {
        as(adminCookie, post("/api/v1/users/" + adminA.getPublicId() + "/disable"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANNOT_DISABLE_SELF"));
        as(adminCookie, put("/api/v1/users/" + adminA.getPublicId() + "/roles").content("{\"roles\":[\"VIEWER\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));

        AppUser second = newUser(tenantA, "TENANT_ADMIN");
        as(adminCookie, put("/api/v1/users/" + adminA.getPublicId() + "/roles").content("{\"roles\":[\"WAREHOUSE_MANAGER\"]}"))
                .andExpect(status().isOk());
        Cookie secondCookie = login(second);
        as(secondCookie, put("/api/v1/users/" + second.getPublicId() + "/roles").content("{\"roles\":[\"VIEWER\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
    }

    @Test
    void roleChangeAppliesToOpenSessionsImmediately() throws Exception {
        AppUser picker = newUser(tenantA, "PICKER");
        Cookie pickerCookie = login(picker);
        as(pickerCookie, get("/api/v1/users")).andExpect(status().isForbidden()); // now cached as picker
        as(adminCookie, put("/api/v1/users/" + picker.getPublicId() + "/roles").content("{\"roles\":[\"WAREHOUSE_MANAGER\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("WAREHOUSE_MANAGER"));
        as(pickerCookie, get("/api/v1/users")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- roles matrix

    @Test
    void builtInRolesMatchTheApprovedMatrix() throws Exception {
        String body = as(adminCookie, get("/api/v1/roles")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8)).andReturn().getResponse().getContentAsString();
        List<String> admin = JsonPath.read(body, "$[?(@.code=='TENANT_ADMIN')].permissions[*]");
        List<String> manager = JsonPath.read(body, "$[?(@.code=='WAREHOUSE_MANAGER')].permissions[*]");
        List<String> viewer = JsonPath.read(body, "$[?(@.code=='VIEWER')].permissions[*]");
        List<String> picker = JsonPath.read(body, "$[?(@.code=='PICKER')].permissions[*]");
        assertThat(admin).hasSize(28);
        assertThat(manager).hasSize(27).doesNotContain("users:manage");
        assertThat(viewer).allMatch(p -> p.endsWith(":read") || p.equals("reports:export"))
                .doesNotContain("users:read", "audit:read");
        assertThat(picker).contains("picking:execute").doesNotContain("inventory:adjust", "users:read");
    }

    // ---------------------------------------------------------------- audit log (SEC-28)

    @Test
    void auditLogShowsBeforeAndAfterForOwnCompanyOnly() throws Exception {
        AppUser picker = newUser(tenantA, "PICKER");
        as(adminCookie, post("/api/v1/users/" + picker.getPublicId() + "/disable")).andExpect(status().isOk());
        String body = as(adminCookie, get("/api/v1/audit-logs?entity=app_user&user=" + adminA.getPublicId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> actions = JsonPath.read(body, "$.items[*].action");
        assertThat(actions).contains("USER_DISABLED");
        String before = JsonPath.<List<String>>read(body, "$.items[?(@.action=='USER_DISABLED')].before").get(0);
        String after = JsonPath.<List<String>>read(body, "$.items[?(@.action=='USER_DISABLED')].after").get(0);
        assertThat(before).contains("\"ACTIVE\"");
        assertThat(after).contains("\"DISABLED\"");
        List<String> actor = JsonPath.read(body, "$.items[?(@.action=='USER_DISABLED')].user.id");
        assertThat(actor).containsOnly(adminA.getPublicId());
        assertThat(body).doesNotContain("argon2").doesNotContain("passwordHash");

        Cookie adminB = login(newUser(tenantB, "TENANT_ADMIN"));
        String other = as(adminB, get("/api/v1/audit-logs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(other).doesNotContain(picker.getPublicId());
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions as(Cookie cookie, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        return mvc.perform(req.with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON));
    }


    private String inviteToken(String email) throws Exception {
        String body = as(adminCookie, post("/api/v1/users")
                .content("{\"email\":\"" + email + "\",\"fullName\":\"Invitee\",\"roles\":[\"PICKER\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((String) JsonPath.read(body, "$.inviteUrl")).split("#")[1];
    }

    private ResultActions accept(String token, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/invite/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private Cookie login(AppUser user) throws Exception {
        String token = login(user.getEmail(), PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getCookie(AuthCookies.ACCESS).getValue();
        return new Cookie(AuthCookies.ACCESS, token);
    }

    private long newTenant() {
        String publicId = Ulid.next();
        jdbc.update("INSERT INTO tenant (public_id, name, plan_code, created_at) VALUES (?, 'T', 'TEST', NOW(3))",
                publicId);
        return jdbc.queryForObject("SELECT id FROM tenant WHERE public_id = ?", Long.class, publicId);
    }

    private AppUser newUser(long tenant, String role) {
        long roleId = roles.findByCode(role).orElseThrow().getId();
        AppUser user = TenantContext.callAs(tenant, () -> users.save(new AppUser("u-" + Ulid.next() + "@test.lk",
                "User " + role, passwords.encode(PASSWORD), AppUser.Status.ACTIVE)));
        TenantContext.callAs(tenant, () -> userRoles.save(new UserRole(user.getId(), roleId)));
        return user;
    }
}

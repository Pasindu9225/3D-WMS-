package lk.wms;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import lk.wms.auth.api.AuthCookies;
import lk.wms.auth.domain.AppUser;
import lk.wms.auth.domain.UserRole;
import lk.wms.auth.repo.AppUserRepository;
import lk.wms.auth.repo.RoleRepository;
import lk.wms.auth.repo.UserRoleRepository;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Base for HTTP tests: companies, users with a role, sign-in cookies and JSON requests. */
@AutoConfigureMockMvc
public abstract class ApiTest extends IntegrationTest {

    protected static final String PASSWORD = "correct-horse-battery";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected AppUserRepository users;
    @Autowired
    protected RoleRepository roles;
    @Autowired
    protected UserRoleRepository userRoles;
    @Autowired
    protected PasswordEncoder passwords;

    protected long newTenant(int maxWarehouses) {
        String publicId = Ulid.next();
        jdbc.update("INSERT INTO tenant (public_id, name, plan_code, max_warehouses, created_at) VALUES (?, 'T', 'TEST', ?, NOW(3))",
                publicId, maxWarehouses);
        return jdbc.queryForObject("SELECT id FROM tenant WHERE public_id = ?", Long.class, publicId);
    }

    protected AppUser newUser(long tenantId, String role) {
        long roleId = roles.findByCode(role).orElseThrow().getId();
        AppUser user = TenantContext.callAs(tenantId, () -> users.save(new AppUser("u-" + Ulid.next() + "@test.lk",
                "User " + role, passwords.encode(PASSWORD), AppUser.Status.ACTIVE)));
        TenantContext.callAs(tenantId, () -> userRoles.save(new UserRole(user.getId(), roleId)));
        return user;
    }

    protected Cookie login(AppUser user) throws Exception {
        String token = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(AuthCookies.ACCESS).getValue();
        return new Cookie(AuthCookies.ACCESS, token);
    }

    protected ResultActions send(Cookie who, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.with(csrf()).cookie(who).contentType(MediaType.APPLICATION_JSON));
    }

    protected static <T> T read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }
}

package lk.wms.layout;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** v0.2 task 1: warehouses, plan limit and per-warehouse access (SEC-09, SEC-10, SEC-28). */
@AutoConfigureMockMvc
class WarehouseTest extends IntegrationTest {

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

    long tenant;
    Cookie admin;
    AppUser picker;
    Cookie pickerCookie;

    @BeforeEach
    void companyWithAdminAndPicker() throws Exception {
        tenant = newTenant(2);
        admin = login(newUser(tenant, "TENANT_ADMIN"));
        picker = newUser(tenant, "PICKER");
        pickerCookie = login(picker);
    }

    @Test
    void createsAWarehouseWithARectangularFloor() throws Exception {
        String body = create(admin, "WH1", "Main warehouse", "40.5", "25", "8").andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("WH1"))
                .andExpect(jsonPath("$.layoutStatus").value("DRAFT"))
                .andExpect(jsonPath("$.widthMm").value(40500))
                .andExpect(jsonPath("$.lengthMm").value(25000))
                .andExpect(jsonPath("$.wallHeightMm").value(8000))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"tenantId\"");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'WAREHOUSE_CREATED' AND tenant_id = ?",
                Integer.class, tenant)).isOne();
    }

    @Test
    void planLimitCountsActiveWarehousesOnly() throws Exception {
        String first = id(create(admin, "WH1", "One", "10", "10", "6"));
        create(admin, "WH2", "Two", "10", "10", "6").andExpect(status().isCreated());
        create(admin, "WH3", "Three", "10", "10", "6").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_LIMIT"));
        send(admin, post("/api/v1/warehouses/" + first + "/archive")).andExpect(status().isNoContent());
        create(admin, "WH3", "Three", "10", "10", "6").andExpect(status().isCreated());
        send(admin, get("/api/v1/warehouses/" + first)).andExpect(status().isNotFound()); // archived is hidden
    }

    @Test
    void codesAreUniquePerCompanyAndValidated() throws Exception {
        create(admin, "WH1", "One", "10", "10", "6").andExpect(status().isCreated());
        create(admin, "WH1", "Again", "10", "10", "6").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODE_IN_USE"));
        create(admin, "wh 2", "Bad code", "10", "10", "6").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        create(admin, "WH2", "Too wide", "1000.5", "10", "6").andExpect(status().isBadRequest());
        create(admin, "WH2", "Too precise", "10.0001", "10", "6").andExpect(status().isBadRequest());
        // another company may use the same code
        Cookie otherAdmin = login(newUser(newTenant(1), "TENANT_ADMIN"));
        create(otherAdmin, "WH1", "Theirs", "10", "10", "6").andExpect(status().isCreated());
    }

    @Test
    void firstWarehouseIsOpenToEveryoneLaterOnesOnlyToAssignedUsers() throws Exception {
        String first = id(create(admin, "WH1", "One", "10", "10", "6"));
        String second = id(create(admin, "WH2", "Two", "10", "10", "6"));

        List<String> seen = JsonPath.read(send(pickerCookie, get("/api/v1/warehouses")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.items[*].id");
        assertThat(seen).containsExactly(first);
        send(pickerCookie, get("/api/v1/warehouses/" + second)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_NOT_FOUND")); // 404, not 403: nothing leaks

        send(admin, put("/api/v1/warehouses/" + second + "/users").content("{\"users\":[\"" + picker.getPublicId() + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(picker.getPublicId()));
        send(pickerCookie, get("/api/v1/warehouses/" + second)).andExpect(status().isOk());
    }

    @Test
    void creatorIsAssignedAndInviteesJoinTheOnlyWarehouse() throws Exception {
        Cookie manager = login(newUser(tenant, "WAREHOUSE_MANAGER"));
        String wh = id(create(manager, "WH1", "Managed", "10", "10", "6"));
        send(manager, get("/api/v1/warehouses/" + wh)).andExpect(status().isOk());

        String email = "new-" + Ulid.next() + "@t.lk";
        send(admin, post("/api/v1/users").content("{\"email\":\"" + email + "\",\"fullName\":\"New\",\"roles\":[\"PICKER\"]}"))
                .andExpect(status().isCreated());
        Long newUserId = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_warehouse WHERE user_id = ?", Integer.class, newUserId))
                .isOne();
    }

    @Test
    void otherCompaniesAndMissingPermissionsGetNothing() throws Exception {
        String wh = id(create(admin, "WH1", "Ours", "10", "10", "6"));
        Cookie otherAdmin = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(otherAdmin, get("/api/v1/warehouses/" + wh)).andExpect(status().isNotFound());
        send(otherAdmin, patch("/api/v1/warehouses/" + wh).content("{\"name\":\"Hijacked\"}")).andExpect(status().isNotFound());
        send(otherAdmin, put("/api/v1/warehouses/" + wh + "/users").content("{\"users\":[]}")).andExpect(status().isNotFound());

        create(pickerCookie, "WH9", "Picker cannot", "10", "10", "6").andExpect(status().isForbidden());
        send(pickerCookie, patch("/api/v1/warehouses/" + wh).content("{\"name\":\"No\"}")).andExpect(status().isForbidden());
        Cookie manager = login(newUser(tenant, "WAREHOUSE_MANAGER"));
        send(manager, put("/api/v1/warehouses/" + wh + "/users").content("{\"users\":[]}"))
                .andExpect(status().isForbidden()); // access is a user-management action
        mvc.perform(get("/api/v1/warehouses")).andExpect(status().isUnauthorized());
    }

    @Test
    void cannotAssignAnotherCompanysUser() throws Exception {
        String wh = id(create(admin, "WH1", "Ours", "10", "10", "6"));
        AppUser outsider = newUser(newTenant(1), "PICKER");
        send(admin, put("/api/v1/warehouses/" + wh + "/users").content("{\"users\":[\"" + outsider.getPublicId() + "\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("USER_UNKNOWN"));
    }

    @Test
    void renameAndWallHeightAreAudited() throws Exception {
        String wh = id(create(admin, "WH1", "Old name", "10", "10", "6"));
        send(admin, patch("/api/v1/warehouses/" + wh).content("{\"name\":\"New name\",\"wallHeightM\":\"9.5\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("New name"))
                .andExpect(jsonPath("$.wallHeightMm").value(9500));
        String after = jdbc.queryForObject("SELECT after_json FROM audit_log WHERE action = 'WAREHOUSE_UPDATED' AND entity_id = ?",
                String.class, wh);
        assertThat(after).contains("New name").contains("9500");
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions create(Cookie who, String code, String name, String w, String l, String h) throws Exception {
        return send(who, post("/api/v1/warehouses").content("{\"code\":\"" + code + "\",\"name\":\"" + name
                + "\",\"widthM\":" + w + ",\"lengthM\":" + l + ",\"wallHeightM\":" + h + "}"));
    }

    private ResultActions send(Cookie who, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.with(csrf()).cookie(who).contentType(MediaType.APPLICATION_JSON));
    }

    private static String id(ResultActions created) throws Exception {
        return JsonPath.read(created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private Cookie login(AppUser user) throws Exception {
        String token = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(AuthCookies.ACCESS).getValue();
        return new Cookie(AuthCookies.ACCESS, token);
    }

    private long newTenant(int maxWarehouses) {
        String publicId = Ulid.next();
        jdbc.update("INSERT INTO tenant (public_id, name, plan_code, max_warehouses, created_at) VALUES (?, 'T', 'TEST', ?, NOW(3))",
                publicId, maxWarehouses);
        return jdbc.queryForObject("SELECT id FROM tenant WHERE public_id = ?", Long.class, publicId);
    }

    private AppUser newUser(long tenantId, String role) {
        long roleId = roles.findByCode(role).orElseThrow().getId();
        AppUser user = TenantContext.callAs(tenantId, () -> users.save(new AppUser("u-" + Ulid.next() + "@test.lk",
                "User " + role, passwords.encode(PASSWORD), AppUser.Status.ACTIVE)));
        TenantContext.callAs(tenantId, () -> userRoles.save(new UserRole(user.getId(), roleId)));
        return user;
    }
}

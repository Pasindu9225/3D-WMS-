package lk.wms.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** v0.2 task 2: rack type library and layout drafts through HTTP. Floor 40 x 20 m. */
@AutoConfigureMockMvc
class LayoutApiTest extends IntegrationTest {

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
    String warehouse;
    String template;

    @BeforeEach
    void warehouseAndRackType() throws Exception {
        tenant = newTenant();
        admin = login(newUser(tenant, "TENANT_ADMIN"));
        warehouse = JsonPath.read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        template = JsonPath.read(createTemplate(admin, "PAL-27").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    // ---------------------------------------------------------------- rack types

    @Test
    void rackTypesAreAManagedLibrary() throws Exception {
        createTemplate(admin, "PAL-27").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODE_IN_USE"));
        send(admin, post("/api/v1/rack-templates").content(templateJson("TALL", 2000, 20))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEVELS_TOO_LOW")); // 20 levels in 2 m: 100 mm each
        send(admin, put("/api/v1/rack-templates/" + template).content(templateJson(null, 6000, 5)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.levels").value(5)).andExpect(jsonPath("$.code").value("PAL-27"));
        send(admin, get("/api/v1/rack-templates")).andExpect(jsonPath("$.items[0].code").value("PAL-27"));

        Cookie viewer = login(newUser(tenant, "VIEWER"));
        send(viewer, get("/api/v1/rack-templates")).andExpect(status().isOk());
        createTemplate(viewer, "NOPE").andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- drafts

    @Test
    void newWarehouseStartsWithABlankLayoutOnItsFloor() throws Exception {
        send(admin, get("/api/v1/warehouses/" + warehouse + "/layout")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.layout.floor.length()").value(4))
                .andExpect(jsonPath("$.layout.floor[2].x").value(40000))
                .andExpect(jsonPath("$.layout.settings.minAisleMm").value(1200));
    }

    @Test
    void saveReturnsWarningsAndBumpsTheRevision() throws Exception {
        // R1 and R2 face each other with an 800 mm gap: saved, with a warning
        save(admin, 0, racks(rack("R1", 5000, 5000), rack("R2", 5000, 6900))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.warnings[0].code").value("AISLE_TOO_NARROW"))
                .andExpect(jsonPath("$.templates." + template + ".code").value("PAL-27"));
        send(admin, get("/api/v1/warehouses/" + warehouse + "/layout"))
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.layout.racks.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'LAYOUT_DRAFT_SAVED' AND entity_id = ?",
                Integer.class, warehouse)).isOne();
    }

    @Test
    void staleSaveIsRefusedSoTwoPlannersCannotOverwriteEachOther() throws Exception {
        save(admin, 0, racks(rack("R1", 5000, 5000))).andExpect(status().isOk());
        save(admin, 0, racks(rack("R9", 9000, 9000))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAYOUT_CHANGED"));
        save(admin, 1, racks(rack("R1", 5000, 5000), rack("R2", 15000, 5000))).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));
    }

    @Test
    void doorsAndDocksKeepTheirHeight() throws Exception {
        // Layouts without a height (saved before v2.1) still load: layoutJson sends none.
        save(admin, 0, racks()).andExpect(status().isOk());
        save(admin, 1, racks().replace("\"widthMm\":3000}", "\"widthMm\":3000,\"heightMm\":4500}"))
                .andExpect(status().isOk());
        send(admin, get("/api/v1/warehouses/" + warehouse + "/layout"))
                .andExpect(jsonPath("$.layout.openings[0].heightMm").value(4500));
        save(admin, 2, racks().replace("\"widthMm\":3000}", "\"widthMm\":3000,\"heightMm\":900}"))
                .andExpect(status().isBadRequest()); // lower than any doorway
    }

    @Test
    void invalidLayoutIsRefusedWithEveryProblemListedAndTheDraftKept() throws Exception {
        save(admin, 0, racks(rack("R1", 5000, 5000))).andExpect(status().isOk());
        String body = save(admin, 1, racks(rack("R1", 5000, 5000), rack("R2", 6000, 5000), rack("R3", 39500, 5000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LAYOUT_INVALID"))
                .andReturn().getResponse().getContentAsString();
        List<String> codes = JsonPath.read(body, "$.issues[*].code");
        List<String> refs = JsonPath.read(body, "$.issues[*].ref");
        assertThat(codes).containsExactlyInAnyOrder("RACKS_OVERLAP", "RACK_OUTSIDE_ZONE");
        assertThat(refs).contains("R2", "R3");
        send(admin, get("/api/v1/warehouses/" + warehouse + "/layout"))
                .andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.layout.racks.length()").value(1));
    }

    @Test
    void anotherCompanysRackTypeCannotBeUsed() throws Exception {
        Cookie otherAdmin = login(newUser(newTenant(), "TENANT_ADMIN"));
        String theirs = JsonPath.read(createTemplate(otherAdmin, "THEIRS").andReturn().getResponse().getContentAsString(), "$.id");
        save(admin, 0, racks("{\"code\":\"R1\",\"templateId\":\"" + theirs + "\",\"zone\":\"A\",\"x\":5000,\"y\":5000,\"rotation\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.issues[0].code").value("RACK_UNKNOWN_TEMPLATE"));
        send(otherAdmin, get("/api/v1/warehouses/" + warehouse + "/layout")).andExpect(status().isNotFound());
    }

    @Test
    void readersCanViewButNotEditAndStrangersSeeNothing() throws Exception {
        Cookie picker = login(newUser(tenant, "PICKER")); // created after WH1: not assigned
        send(picker, get("/api/v1/warehouses/" + warehouse + "/layout")).andExpect(status().isNotFound());
        Cookie manager = login(newUser(tenant, "WAREHOUSE_MANAGER"));
        String managerId = jdbc.queryForObject("SELECT public_id FROM app_user WHERE tenant_id = ? AND full_name = 'User WAREHOUSE_MANAGER'",
                String.class, tenant);
        send(admin, put("/api/v1/warehouses/" + warehouse + "/users").content("{\"users\":[\"" + managerId + "\"]}"))
                .andExpect(status().isOk());
        save(manager, 0, racks(rack("R1", 5000, 5000))).andExpect(status().isOk());

        Cookie viewer = login(newUser(tenant, "VIEWER"));
        String viewerId = jdbc.queryForObject("SELECT public_id FROM app_user WHERE tenant_id = ? AND full_name = 'User VIEWER'",
                String.class, tenant);
        send(admin, put("/api/v1/warehouses/" + warehouse + "/users").content("{\"users\":[\"" + viewerId + "\"]}"))
                .andExpect(status().isOk());
        send(viewer, get("/api/v1/warehouses/" + warehouse + "/layout")).andExpect(status().isOk());
        save(viewer, 1, racks(rack("R1", 5000, 5000))).andExpect(status().isForbidden());
    }

    @Test
    void unknownFieldsAndBadValuesAreRejectedBeforeAnyGeometry() throws Exception {
        String extra = layoutJson(rack("R1", 5000, 5000)).replace("\"settings\"", "\"owner\":\"x\",\"settings\"");
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":" + extra + "}"))
                .andExpect(status().isBadRequest());
        save(admin, 0, racks("{\"code\":\"r1\",\"templateId\":\"" + template + "\",\"zone\":\"A\",\"x\":5000,\"y\":5000,\"rotation\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("layout.racks[0].code"));
        send(admin, get("/api/v1/warehouses/" + "X".repeat(40) + "/layout")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.errors[0].field").value("id"));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions save(Cookie who, int base, String layout) throws Exception {
        return send(who, put("/api/v1/warehouses/" + warehouse + "/layout")
                .content("{\"baseRevision\":" + base + ",\"layout\":" + layout + "}"));
    }

    /** One STORAGE zone A covering the whole 40 x 20 m floor, plus the given racks. */
    private String racks(String... rackJson) {
        return layoutJson(rackJson);
    }

    private static String layoutJson(String... rackJson) {
        String floor = "[{\"x\":0,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":20000},{\"x\":0,\"y\":20000}]";
        return "{\"floor\":" + floor + ",\"openings\":[{\"kind\":\"DOCK\",\"label\":\"Dock 1\",\"x\":20000,\"y\":20000,\"widthMm\":3000}],"
                + "\"zones\":[{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"Storage A\",\"polygon\":" + floor + "}],"
                + "\"racks\":[" + String.join(",", rackJson) + "],\"settings\":{\"minAisleMm\":1200}}";
    }

    private String rack(String code, int x, int y) {
        return "{\"code\":\"" + code + "\",\"templateId\":\"" + template + "\",\"zone\":\"A\",\"x\":" + x + ",\"y\":" + y
                + ",\"rotation\":0}";
    }

    private ResultActions createTemplate(Cookie who, String code) throws Exception {
        return send(who, post("/api/v1/rack-templates").content(templateJson(code, 6000, 4)));
    }

    /** 2.7 m wide x 1.1 m deep pallet racking, 1 t per level. */
    private static String templateJson(String code, int heightMm, int levels) {
        return "{" + (code == null ? "" : "\"code\":\"" + code + "\",") + "\"name\":\"Pallet 2.7 m\",\"widthMm\":2700,"
                + "\"depthMm\":1100,\"heightMm\":" + heightMm + ",\"levels\":" + levels + ",\"binsPerLevel\":3,"
                + "\"maxWeightPerLevelG\":1000000}";
    }

    private ResultActions send(Cookie who, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.with(csrf()).cookie(who).contentType(MediaType.APPLICATION_JSON));
    }

    private Cookie login(AppUser user) throws Exception {
        String token = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(AuthCookies.ACCESS).getValue();
        return new Cookie(AuthCookies.ACCESS, token);
    }

    private long newTenant() {
        String publicId = Ulid.next();
        jdbc.update("INSERT INTO tenant (public_id, name, plan_code, max_warehouses, created_at) VALUES (?, 'T', 'TEST', 3, NOW(3))",
                publicId);
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

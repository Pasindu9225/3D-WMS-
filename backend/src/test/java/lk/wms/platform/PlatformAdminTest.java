package lk.wms.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import lk.wms.ApiTest;
import lk.wms.auth.domain.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

/** v2.0 task 1: the Super Admin portal; only SUPER_ADMIN reaches it, and it sees counts, never company data (SEC-09/10, SEC-24, SEC-28). */
class PlatformAdminTest extends ApiTest {

    private long platformTenant() {
        Long existing = jdbc.query("SELECT id FROM tenant WHERE platform = TRUE", rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            return existing;
        }
        long id = newTenant(0);
        jdbc.update("UPDATE tenant SET platform = TRUE, name = 'Platform', plan_code = 'PLATFORM' WHERE id = ?", id);
        return id;
    }

    @Test
    void superAdminRunsCompaniesAndPlans() throws Exception {
        Cookie sa = login(newUser(platformTenant(), "SUPER_ADMIN"));
        long acme = newTenant(2);
        jdbc.update("UPDATE tenant SET name = ? WHERE id = ?", "Acme Traders " + acme, acme);
        AppUser acmeAdmin = newUser(acme, "TENANT_ADMIN");
        Cookie owner = login(acmeAdmin);
        String acmeId = jdbc.queryForObject("SELECT public_id FROM tenant WHERE id = ?", String.class, acme);
        send(owner, post("/api/v1/warehouses").content("{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}"))
                .andExpect(status().isCreated());

        // only the Super Admin
        send(owner, get("/api/v1/admin/tenants")).andExpect(status().isForbidden());
        send(owner, post("/api/v1/admin/tenants/" + acmeId + "/suspend").content("{\"version\":0,\"reason\":\"x\"}")).andExpect(status().isForbidden());
        send(owner, get("/api/v1/roles")).andExpect(jsonPath("$[?(@.code == 'SUPER_ADMIN')]").isEmpty());
        send(owner, post("/api/v1/users").content("{\"email\":\"sneaky@acme.lk\",\"fullName\":\"S\",\"roles\":[\"SUPER_ADMIN\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ROLE_UNKNOWN"));

        // list and usage: counts only
        send(sa, get("/api/v1/admin/tenants?q=Acme Traders " + acme)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].warehouses").value(1))
                .andExpect(jsonPath("$.items[0].users").value(1)).andExpect(jsonPath("$.items[0].maxWarehouses").value(2));
        send(sa, get("/api/v1/admin/tenants?q=Platform")).andExpect(jsonPath("$.items[?(@.name == 'Platform')]").isEmpty()); // never listed
        send(sa, get("/api/v1/admin/tenants/" + acmeId)).andExpect(jsonPath("$.usage.warehouses").value(1))
                .andExpect(jsonPath("$.usage.activeUsers").value(1)).andExpect(jsonPath("$.usage.products").value(0))
                .andExpect(jsonPath("$.usage.lastActive").isNotEmpty()).andExpect(jsonPath("$.status").value("ACTIVE"));
        String platformId = jdbc.queryForObject("SELECT public_id FROM tenant WHERE platform = TRUE", String.class);
        send(sa, get("/api/v1/admin/tenants/" + platformId)).andExpect(status().isNotFound());
        send(sa, post("/api/v1/admin/tenants/" + platformId + "/suspend").content("{\"version\":0,\"reason\":\"x\"}")).andExpect(status().isNotFound());

        // plans
        send(sa, post("/api/v1/admin/plans").content("{\"code\":\"pro\",\"name\":\"Pro\",\"pricePerWarehouseMinor\":500000,\"defaultMaxWarehouses\":3}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("PRO"));
        send(sa, post("/api/v1/admin/plans").content("{\"code\":\"PRO\",\"name\":\"Pro\",\"pricePerWarehouseMinor\":1,\"defaultMaxWarehouses\":3}"))
                .andExpect(jsonPath("$.code").value("PLAN_EXISTS"));
        send(sa, get("/api/v1/admin/plans")).andExpect(jsonPath("$[?(@.code == 'PILOT')].pricePerWarehouseMinor").value(0));

        // plan and limit; the limit cannot go below what is in use, and is enforced on warehouse create
        int v = jdbc.queryForObject("SELECT version FROM tenant WHERE id = ?", Integer.class, acme);
        send(sa, put("/api/v1/admin/tenants/" + acmeId + "/plan").content("{\"version\":" + v + ",\"planCode\":\"PRO\",\"maxWarehouses\":0}"))
                .andExpect(status().isBadRequest());
        send(sa, put("/api/v1/admin/tenants/" + acmeId + "/plan").content("{\"version\":" + v + ",\"planCode\":\"NOPE\",\"maxWarehouses\":3}"))
                .andExpect(jsonPath("$.code").value("PLAN_NOT_FOUND"));
        send(sa, put("/api/v1/admin/tenants/" + acmeId + "/plan").content("{\"version\":" + v + ",\"planCode\":\"PRO\",\"maxWarehouses\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planCode").value("PRO")).andExpect(jsonPath("$.maxWarehouses").value(1));
        send(owner, post("/api/v1/warehouses").content("{\"code\":\"WH2\",\"name\":\"Two\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_LIMIT"));
        send(sa, put("/api/v1/admin/tenants/" + acmeId + "/plan").content("{\"version\":" + v + ",\"planCode\":\"PRO\",\"maxWarehouses\":3}"))
                .andExpect(jsonPath("$.code").value("COMPANY_CHANGED")); // stale version

        // suspend: signed-in people are signed out at once and nobody can sign in; reactivate: back
        v = jdbc.queryForObject("SELECT version FROM tenant WHERE id = ?", Integer.class, acme);
        send(sa, post("/api/v1/admin/tenants/" + acmeId + "/suspend").content("{\"version\":" + v + ",\"reason\":\"Unpaid invoice\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED")).andExpect(jsonPath("$.suspendedReason").value("Unpaid invoice"));
        send(owner, get("/api/v1/warehouses")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").with(SecurityMockMvcRequestPostProcessors.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + acmeAdmin.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_session WHERE tenant_id = ? AND revoked_at IS NULL", Integer.class, acme)).isZero();
        v = jdbc.queryForObject("SELECT version FROM tenant WHERE id = ?", Integer.class, acme);
        send(sa, post("/api/v1/admin/tenants/" + acmeId + "/reactivate").content("{\"version\":" + v + "}")).andExpect(jsonPath("$.status").value("ACTIVE"));
        Cookie again = login(acmeAdmin);
        send(again, get("/api/v1/warehouses")).andExpect(status().isOk());

        // a company you sign up yourself: company + first admin invite (link once); email must be free
        send(sa, post("/api/v1/admin/tenants").content("{\"name\":\"Kandy Foods\",\"planCode\":\"PRO\",\"adminEmail\":\"" + acmeAdmin.getEmail()
                + "\",\"adminName\":\"K\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        String created = send(sa, post("/api/v1/admin/tenants").content("{\"name\":\"Kandy Foods\",\"planCode\":\"PRO\",\"adminEmail\":\"owner-"
                + acme + "@kandy.lk\",\"adminName\":\"Kandy Owner\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.company.maxWarehouses").value(3)).andExpect(jsonPath("$.inviteUrl").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(created).contains("/invite#");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user u JOIN user_role ur ON ur.user_id = u.id JOIN role r ON r.id = ur.role_id"
                + " WHERE u.email = ? AND r.code = 'TENANT_ADMIN' AND u.status = 'INVITED'", Integer.class, "owner-" + acme + "@kandy.lk")).isEqualTo(1);

        // audited in both logs
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action IN ('COMPANY_SUSPENDED','COMPANY_REACTIVATED','COMPANY_PLAN_CHANGED')",
                Integer.class, acme)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'COMPANY_SUSPENDED' AND entity_id = ?",
                Integer.class, platformTenant(), acmeId)).isEqualTo(1);
    }
}

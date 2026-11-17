package lk.wms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import lk.wms.ApiTest;
import org.junit.jupiter.api.Test;

/** v1.1 task 4: ABC classes from the last 90 days of pick lines. */
class AbcTest extends ApiTest {

    private long product(Cookie admin, long tenant, String sku) throws Exception {
        send(admin, post("/api/v1/products").content("{\"sku\":\"" + sku + "\",\"name\":\"" + sku + "\",\"baseUom\":\"EA\","
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = ?", Long.class, tenant, sku);
    }

    /** Pick lines straight into the ledger (bin to dispatch area), dated daysAgo. */
    private void picks(long tenant, long warehouse, long product, int lines, int daysAgo, long user) {
        for (int i = 0; i < lines; i++) {
            jdbc.update("INSERT INTO stock_movement (tenant_id, warehouse_id, type, product_id, to_dispatch, qty, user_id, created_at)"
                    + " VALUES (?, ?, 'PICK', ?, TRUE, 1, ?, NOW(3) - INTERVAL ? DAY)", tenant, warehouse, product, user, daysAgo);
        }
    }

    private String abc(long tenant, String sku) {
        return jdbc.queryForObject("SELECT abc_class FROM product WHERE tenant_id = ? AND sku = ?", String.class, tenant, sku);
    }

    @Test
    void mostPickedAreATheNextUpTo95PercentBAndTheRestC() throws Exception {
        long tenant = newTenant(2);
        var adminUser = newUser(tenant, "TENANT_ADMIN");
        Cookie admin = login(adminUser);
        String wh = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
        long warehouse = jdbc.queryForObject("SELECT id FROM warehouse WHERE public_id = ?", Long.class, wh);
        Map<String, Integer> lines = Map.of("P1", 50, "P2", 30, "P3", 10, "P4", 6, "P5", 4);
        for (String sku : List.of("P1", "P2", "P3", "P4", "P5", "P6")) {
            long id = product(admin, tenant, sku);
            picks(tenant, warehouse, id, lines.getOrDefault(sku, 0), 5, adminUser.getId());
        }
        picks(tenant, warehouse, jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = 'P6'", Long.class, tenant),
                500, 120, adminUser.getId()); // older than 90 days: does not count

        long other = newTenant(1);
        Cookie otherAdmin = login(newUser(other, "TENANT_ADMIN"));
        product(otherAdmin, other, "X1");

        int v = read(send(admin, get("/api/v1/products?q=P1")), "$.items[0].version");
        send(admin, post("/api/v1/abc/recalculate")).andExpect(status().isOk())
                .andExpect(jsonPath("$.a").value(2)).andExpect(jsonPath("$.b").value(2)).andExpect(jsonPath("$.c").value(2));
        assertThat(List.of(abc(tenant, "P1"), abc(tenant, "P2"), abc(tenant, "P3"), abc(tenant, "P4"), abc(tenant, "P5"), abc(tenant, "P6")))
                .containsExactly("A", "A", "B", "B", "C", "C");
        assertThat(abc(other, "X1")).isNull(); // another company is not touched
        send(admin, get("/api/v1/products?q=P1")).andExpect(jsonPath("$.items[0].abcClass").value("A"))
                .andExpect(jsonPath("$.items[0].version").value(v)); // a running edit is not made stale

        Cookie supervisor = login(newUser(tenant, "SUPERVISOR"));
        send(supervisor, post("/api/v1/abc/recalculate")).andExpect(status().isForbidden());
    }

    @Test
    void theWeeklyJobClassifiesEveryActiveCompany() throws Exception {
        long tenant = newTenant(1);
        Cookie admin = login(newUser(tenant, "TENANT_ADMIN"));
        product(admin, tenant, "Q1");
        abcService.weekly();
        assertThat(abc(tenant, "Q1")).isEqualTo("C");
    }

    @org.springframework.beans.factory.annotation.Autowired
    lk.wms.inventory.service.AbcService abcService;
}

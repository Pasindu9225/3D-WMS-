package lk.wms.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.List;
import lk.wms.ApiTest;
import lk.wms.auth.domain.AppUser;
import lk.wms.common.tenant.TenantContext;
import lk.wms.inventory.service.StockMovementService;
import lk.wms.inventory.service.StockMovementService.Kind;
import lk.wms.inventory.service.StockMovementService.Move;
import lk.wms.inventory.service.StockMovementService.Place;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** v1.2 task 4: putaway rules (fixed bin, zone, only-zone, by product and category); SEC-09/10, SEC-20. */
class PutawayRuleTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    long tenant;
    AppUser adminUser;
    Cookie admin;
    String warehouse;
    long warehouseId;

    private String product(String sku, String category) throws Exception {
        return read(send(admin, post("/api/v1/products").content("{\"sku\":\"" + sku + "\",\"name\":\"" + sku + "\",\"baseUom\":\"EA\",\"trackBatch\":false,"
                + "\"trackExpiry\":false,\"units\":[]" + (category == null ? "" : ",\"category\":\"" + category + "\"") + "}"))
                .andExpect(status().isCreated()), "$.id");
    }

    private void received(String sku, int qty) {
        long id = jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = ?", Long.class, tenant, sku);
        TenantContext.callAs(tenant, () -> moves.move(new Move(Kind.RECEIVE, warehouseId, id, null, null, null, Place.RECEIVING,
                BigDecimal.valueOf(qty), null, null, null, adminUser.getId())));
    }

    private ResultActions suggest(String productId) throws Exception {
        return send(admin, post("/api/v1/putaway/suggestions").content("{\"warehouseId\":\"" + warehouse + "\",\"productId\":\"" + productId + "\",\"qty\":1}"));
    }

    private ResultActions confirm(String key, String productId, String bin) throws Exception {
        return send(admin, post("/api/v1/putaway").header("Idempotency-Key", key).content("{\"warehouseId\":\"" + warehouse
                + "\",\"productId\":\"" + productId + "\",\"qty\":1,\"binCode\":\"" + bin + "\"}"));
    }

    private ResultActions rule(Cookie who, String fields) throws Exception {
        return send(who, post("/api/v1/putaway-rules").content("{\"warehouseId\":\"" + warehouse + "\"," + fields + "}"));
    }

    @Test
    void rulesSendGoodsToFixedBinsAndZones() throws Exception {
        tenant = newTenant(3);
        adminUser = newUser(tenant, "TENANT_ADMIN");
        admin = login(adminUser);
        warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
        warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE public_id = ?", Long.class, warehouse);
        String type = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"SH\",\"name\":\"Shelf\",\"widthMm\":2700,"
                + "\"depthMm\":1100,\"heightMm\":3000,\"levels\":1,\"binsPerLevel\":3,\"maxWeightPerLevelG\":3000000}"))
                .andExpect(status().isCreated()), "$.id");
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":"
                + "[{\"x\":0,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":20000},{\"x\":0,\"y\":20000}],"
                + "\"openings\":[],\"zones\":[{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"Dry\",\"polygon\":"
                + "[{\"x\":0,\"y\":0},{\"x\":20000,\"y\":0},{\"x\":20000,\"y\":20000},{\"x\":0,\"y\":20000}]},"
                + "{\"code\":\"C\",\"type\":\"COLD\",\"name\":\"Cold\",\"polygon\":"
                + "[{\"x\":20000,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":20000},{\"x\":20000,\"y\":20000}]}],\"racks\":["
                + "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":5000,\"y\":8000,\"rotation\":0},"
                + "{\"code\":\"R02\",\"templateId\":\"" + type + "\",\"zone\":\"C\",\"x\":25000,\"y\":8000,\"rotation\":0}"
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        List<String> a = jdbc.queryForList("SELECT code FROM bin WHERE warehouse_id = ? AND code LIKE 'A-%' ORDER BY code", String.class, warehouseId);
        List<String> c = jdbc.queryForList("SELECT code FROM bin WHERE warehouse_id = ? AND code LIKE 'C-%' ORDER BY code", String.class, warehouseId);

        String fish = product("FISH-1KG", " Frozen ");
        String rice = product("RICE-5KG", null);
        send(admin, get("/api/v1/products/" + fish)).andExpect(jsonPath("$.category").value("Frozen"));
        received("FISH-1KG", 10);
        received("RICE-5KG", 10);

        // no rules: the built-in order (nearest empty storage bin; the cold zone is not plain storage)
        suggest(fish).andExpect(jsonPath("$[0].why").value("EMPTY_STORAGE")).andExpect(jsonPath("$[*].code", Matchers.everyItem(Matchers.startsWith("A-"))));

        // checks on what a rule may say
        rule(admin, "\"priority\":10,\"sku\":\"RICE-5KG\",\"category\":\"Dry\",\"zoneCode\":\"A\"").andExpect(jsonPath("$.code").value("RULE_TARGET"));
        rule(admin, "\"priority\":10,\"category\":\"Dry\"").andExpect(jsonPath("$.code").value("RULE_DESTINATION"));
        rule(admin, "\"priority\":10,\"category\":\"Dry\",\"binCode\":\"" + a.get(0) + "\",\"zoneCode\":\"A\"").andExpect(jsonPath("$.code").value("RULE_DESTINATION"));
        rule(admin, "\"priority\":10,\"category\":\"Dry\",\"zoneCode\":\"X\"").andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));

        // frozen goods only in the cold zone (category matched without case)
        String cold = read(rule(admin, "\"priority\":10,\"category\":\"frozen\",\"zoneCode\":\"c\",\"onlyZone\":true").andExpect(status().isCreated())
                .andExpect(jsonPath("$.appliesTo").value("CATEGORY")).andExpect(jsonPath("$.zoneCode").value("C")), "$.id");
        suggest(fish).andExpect(jsonPath("$[0].why").value("RULE_ZONE")).andExpect(jsonPath("$[*].code", Matchers.everyItem(Matchers.startsWith("C-"))));
        confirm("key-00001", fish, a.get(1)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OUTSIDE_ZONE"));
        confirm("key-00002", fish, c.get(0)).andExpect(status().isOk());
        suggest(rice).andExpect(jsonPath("$[0].why").value("EMPTY_STORAGE")); // not frozen: rule does not apply

        // a fixed bin for rice; and a fixed bin for this one fish product beats its category's zone
        rule(admin, "\"priority\":5,\"sku\":\"RICE-5KG\",\"binCode\":\"" + a.get(2).toLowerCase() + "\"").andExpect(status().isCreated());
        suggest(rice).andExpect(jsonPath("$[0].why").value("FIXED_BIN")).andExpect(jsonPath("$[0].code").value(a.get(2)))
                .andExpect(jsonPath("$[1].why").value("EMPTY_STORAGE"));
        rule(admin, "\"priority\":50,\"sku\":\"FISH-1KG\",\"binCode\":\"" + a.get(1) + "\"").andExpect(jsonPath("$.appliesTo").value("PRODUCT"));
        suggest(fish).andExpect(jsonPath("$[0].why").value("FIXED_BIN")).andExpect(jsonPath("$[0].code").value(a.get(1)))
                .andExpect(jsonPath("$[1].why").value("RULE_ZONE"));
        confirm("key-00003", fish, a.get(1)).andExpect(status().isOk()); // the rule names this bin
        confirm("key-00004", fish, a.get(0)).andExpect(jsonPath("$.code").value("OUTSIDE_ZONE"));

        // switched off: no longer only the cold zone
        int version = jdbc.queryForObject("SELECT version FROM putaway_rule WHERE public_id = ?", Integer.class, cold);
        send(admin, put("/api/v1/putaway-rules/" + cold).content("{\"version\":" + version + ",\"priority\":10,\"onlyZone\":true,\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        confirm("key-00005", fish, a.get(0)).andExpect(status().isOk());
        send(admin, put("/api/v1/putaway-rules/" + cold).content("{\"version\":" + version + ",\"priority\":10,\"onlyZone\":true,\"active\":true}"))
                .andExpect(jsonPath("$.code").value("RULE_CHANGED"));
        send(admin, get("/api/v1/putaway-rules?warehouse=" + warehouse)).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].sku").value("RICE-5KG"));

        // who may do what
        Cookie picker = login(newUser(tenant, "PICKER"));
        rule(picker, "\"priority\":1,\"zoneCode\":\"A\"").andExpect(status().isForbidden());
        send(picker, delete("/api/v1/putaway-rules/" + cold)).andExpect(status().isForbidden());
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/putaway-rules?warehouse=" + warehouse)).andExpect(status().isNotFound());
        send(other, delete("/api/v1/putaway-rules/" + cold)).andExpect(status().isNotFound());
        send(admin, delete("/api/v1/putaway-rules/" + cold)).andExpect(status().isNoContent());
        send(admin, get("/api/v1/putaway-rules?warehouse=" + warehouse)).andExpect(jsonPath("$.length()").value(2));
    }
}

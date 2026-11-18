package lk.wms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.wms.ApiTest;
import lk.wms.auth.domain.AppUser;
import lk.wms.common.tenant.TenantContext;
import lk.wms.inventory.service.StockMovementService;
import lk.wms.inventory.service.StockMovementService.Kind;
import lk.wms.inventory.service.StockMovementService.Move;
import lk.wms.inventory.service.StockMovementService.Place;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * v2.1 handling rules through the API: product handling fields, zone options, and putaway following them. Zones:
 * R receiving, A storage, Z freezer, H storage for class 8, F storage with a floor row 3 pallets high.
 */
class HandlingRulesApiTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    long tenant;
    AppUser adminUser;
    Cookie admin;
    String warehouse;
    long warehouseId;

    @BeforeEach
    void warehouseWithFreezerHazmatAndFloorRow() throws Exception {
        tenant = newTenant(3);
        adminUser = newUser(tenant, "TENANT_ADMIN");
        admin = login(adminUser);
        warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":60,\"lengthM\":20,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
        warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE public_id = ?", Long.class, warehouse);
        String shelf = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"SH\",\"name\":\"Shelf\",\"widthMm\":2700,"
                + "\"depthMm\":1100,\"heightMm\":3000,\"levels\":2,\"binsPerLevel\":3,\"maxWeightPerLevelG\":30000}")), "$.id");
        String floor = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"FL\",\"name\":\"Floor\",\"widthMm\":1200,"
                + "\"depthMm\":1000,\"heightMm\":4500,\"levels\":3,\"binsPerLevel\":1,\"maxWeightPerLevelG\":1000000,\"style\":\"FLOOR\"}")), "$.id");
        String zones = zone("R", "RECEIVING", 0, 5000, "") + "," + zone("A", "STORAGE", 5000, 20000, "") + ","
                + zone("Z", "FROZEN", 20000, 35000, "") + "," + zone("H", "STORAGE", 35000, 50000, ",\"hazardClasses\":[\"8\"]") + ","
                + zone("F", "STORAGE", 50000, 60000, "");
        String racks = rack("A01", shelf, "A", 9000) + "," + rack("Z01", shelf, "Z", 27000) + "," + rack("H01", shelf, "H", 42000) + ","
                + rack("FL1", floor, "F", 55000);
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":"
                + poly(0, 0, 60000, 20000) + ",\"openings\":[],\"zones\":[" + zones + "],\"racks\":[" + racks
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
    }

    private static String poly(int x1, int y1, int x2, int y2) {
        return "[{\"x\":" + x1 + ",\"y\":" + y1 + "},{\"x\":" + x2 + ",\"y\":" + y1 + "},{\"x\":" + x2 + ",\"y\":" + y2
                + "},{\"x\":" + x1 + ",\"y\":" + y2 + "}]";
    }

    private static String zone(String code, String type, int x1, int x2, String extra) {
        return "{\"code\":\"" + code + "\",\"type\":\"" + type + "\",\"name\":\"" + code + "\",\"polygon\":" + poly(x1, 0, x2, 20000) + extra + "}";
    }

    private static String rack(String code, String type, String zone, int x) {
        return "{\"code\":\"" + code + "\",\"templateId\":\"" + type + "\",\"zone\":\"" + zone + "\",\"x\":" + x + ",\"y\":10000,\"rotation\":0}";
    }

    /** A product with these handling fields and 10 units waiting in receiving. */
    private String product(String sku, String handling) throws Exception {
        String id = read(send(admin, post("/api/v1/products").content("{\"sku\":\"" + sku + "\",\"name\":\"" + sku + "\",\"baseUom\":\"EA\","
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[],\"handling\":" + handling + "}")).andExpect(status().isCreated()), "$.id");
        long pid = jdbc.queryForObject("SELECT id FROM product WHERE public_id = ?", Long.class, id);
        TenantContext.callAs(tenant, () -> moves.move(new Move(Kind.RECEIVE, warehouseId, pid, null, null, null, Place.RECEIVING,
                new BigDecimal("10"), null, null, null, adminUser.getId())));
        return id;
    }

    private ResultActions suggest(String product) throws Exception {
        return send(admin, post("/api/v1/putaway/suggestions").content("{\"warehouseId\":\"" + warehouse + "\",\"productId\":\"" + product
                + "\",\"qty\":1}")).andExpect(status().isOk());
    }

    private ResultActions confirm(String product, String bin) throws Exception {
        return send(admin, post("/api/v1/putaway").header("Idempotency-Key", UUID.randomUUID().toString()).content("{\"warehouseId\":\""
                + warehouse + "\",\"productId\":\"" + product + "\",\"qty\":1,\"binCode\":\"" + bin + "\"}"));
    }

    @SuppressWarnings("unchecked")
    private List<String> codes(ResultActions r) throws Exception {
        return (List<String>) read(r, "$[*].code");
    }

    @Test
    void handlingIsKeptAndChecked() throws Exception {
        String fish = product("FISH", "{\"hsCode\":\"030389\",\"storageTemp\":\"FROZEN\",\"stackMax\":4,\"fragile\":false}");
        send(admin, get("/api/v1/products/" + fish)).andExpect(jsonPath("$.handling.hsCode").value("030389"))
                .andExpect(jsonPath("$.handling.storageTemp").value("FROZEN")).andExpect(jsonPath("$.handling.stackMax").value(4));
        send(admin, post("/api/v1/products").content("{\"sku\":\"BAD\",\"name\":\"Bad\",\"baseUom\":\"EA\",\"trackBatch\":false,"
                + "\"trackExpiry\":false,\"units\":[],\"handling\":{\"hsCode\":\"12\",\"hazardClass\":\"10\"}}")).andExpect(status().isBadRequest());
        send(admin, get("/api/v1/warehouses/" + warehouse + "/scene"))
                .andExpect(jsonPath("$.zones[?(@.code=='H')].hazardClasses").value(Matchers.contains("8")))
                .andExpect(jsonPath("$.zones[?(@.code=='Z')].type").value(Matchers.contains("FROZEN")));
    }

    @Test
    void frozenGoodsGoOnlyToTheFreezer() throws Exception {
        String fish = product("FISH", "{\"storageTemp\":\"FROZEN\"}");
        assertThat(codes(suggest(fish))).isNotEmpty().allMatch(c -> c.startsWith("Z-"));
        confirm(fish, "A-A01-01-01").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WRONG_TEMPERATURE"));
        confirm(fish, "Z-Z01-01-01").andExpect(status().isOk());
    }

    @Test
    void dangerousGoodsGoOnlyWhereTheirClassIsAllowed() throws Exception {
        String bleach = product("BLEACH", "{\"hazardClass\":\"8\"}");
        assertThat(codes(suggest(bleach))).isNotEmpty().allMatch(c -> c.startsWith("H-"));
        confirm(bleach, "A-A01-01-01").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HAZARD_ZONE"));
        String rice = product("RICE", "null");
        assertThat(codes(suggest(rice))).noneMatch(c -> c.startsWith("Z-")); // ambient goods not into the freezer by default
    }

    @Test
    void floorStacksFollowTheStackLimit() throws Exception {
        String eggs = product("EGGS", "{\"stackMax\":1,\"fragile\":true}");
        confirm(eggs, "F-FL1-02-01").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STACK_LIMIT"));
        confirm(eggs, "F-FL1-01-01").andExpect(status().isOk());
        String rice = product("RICE", "{\"stackMax\":3}");
        confirm(rice, "F-FL1-02-01").andExpect(status().isConflict()).andExpect(jsonPath("$.detail", Matchers.containsString("fragile")));
        assertThat(codes(suggest(rice))).doesNotContain("F-FL1-02-01", "F-FL1-03-01");
    }
}

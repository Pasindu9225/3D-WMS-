package lk.wms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.UUID;
import lk.wms.ApiTest;
import lk.wms.auth.domain.AppUser;
import lk.wms.common.tenant.TenantContext;
import lk.wms.inventory.service.StockMovementService;
import lk.wms.inventory.service.StockMovementService.Kind;
import lk.wms.inventory.service.StockMovementService.Move;
import lk.wms.inventory.service.StockMovementService.Place;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** v0.4 task 5: putaway suggestions and scan-to-confirm. */
class PutawayApiTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    long tenant;
    AppUser adminUser;
    Cookie admin;
    String warehouse;
    long warehouseId;
    String oil;
    long oilId;

    @BeforeEach
    void warehouseWithReceivingAndTwoRacks() throws Exception {
        tenant = newTenant(3);
        adminUser = newUser(tenant, "TENANT_ADMIN");
        admin = login(adminUser);
        warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
        warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE public_id = ?", Long.class, warehouse);
        String type = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"SH\",\"name\":\"Shelf\",\"widthMm\":2700,"
                + "\"depthMm\":1100,\"heightMm\":3000,\"levels\":2,\"binsPerLevel\":3,\"maxWeightPerLevelG\":30000}")), "$.id");
        String rcv = poly(0, 0, 5000, 20000);
        String store = poly(5000, 0, 40000, 20000);
        String racks = rack("R01", type, 9000) + "," + rack("R02", type, 35000); // R01 near receiving, R02 far
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":"
                + poly(0, 0, 40000, 20000) + ",\"openings\":[],\"zones\":[{\"code\":\"R\",\"type\":\"RECEIVING\",\"name\":\"In\",\"polygon\":"
                + rcv + "},{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":" + store + "}],\"racks\":[" + racks
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        oil = read(send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Oil\",\"baseUom\":\"EA\","
                + "\"weightG\":950,\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated()), "$.id");
        oilId = jdbc.queryForObject("SELECT id FROM product WHERE public_id = ?", Long.class, oil);
        TenantContext.callAs(tenant, () -> moves.move(new Move(Kind.RECEIVE, warehouseId, oilId, null, null, null, Place.RECEIVING,
                new BigDecimal("24"), null, null, null, adminUser.getId())));
    }

    private static String poly(int x1, int y1, int x2, int y2) {
        return "[{\"x\":" + x1 + ",\"y\":" + y1 + "},{\"x\":" + x2 + ",\"y\":" + y1 + "},{\"x\":" + x2 + ",\"y\":" + y2
                + "},{\"x\":" + x1 + ",\"y\":" + y2 + "}]";
    }

    private static String rack(String code, String type, int x) {
        return "{\"code\":\"" + code + "\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":" + x + ",\"y\":10000,\"rotation\":0}";
    }

    private ResultActions suggest(Cookie who, String qty) throws Exception {
        return send(who, post("/api/v1/putaway/suggestions").content("{\"warehouseId\":\"" + warehouse + "\",\"productId\":\"" + oil
                + "\",\"qty\":" + qty + "}"));
    }

    private ResultActions confirm(Cookie who, String key, String bin, String qty) throws Exception {
        return send(who, post("/api/v1/putaway").header("Idempotency-Key", key).content("{\"warehouseId\":\"" + warehouse
                + "\",\"productId\":\"" + oil + "\",\"qty\":" + qty + ",\"binCode\":\"" + bin + "\"}"));
    }

    @Test
    void suggestsNearestEmptyStorageBinsThenTheSameProductAndConfirmsByScan() throws Exception {
        send(admin, get("/api/v1/putaway?warehouse=" + warehouse)).andExpect(jsonPath("$.items[0].sku").value("OIL-1L"))
                .andExpect(jsonPath("$.items[0].qty").value(24));
        String first = read(suggest(admin, "10").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].why").value("EMPTY_STORAGE")), "$[0].code");
        assertThat(first).contains("R01"); // the rack nearer the receiving zone
        String key = UUID.randomUUID().toString();
        confirm(admin, key, first.toLowerCase(), "10").andExpect(status().isOk()) // labels scan in any case
                .andExpect(jsonPath("$.binCode").value(first)).andExpect(jsonPath("$.leftInReceiving").value(14))
                .andExpect(jsonPath("$.alreadyDone").value(false));
        confirm(admin, key, first, "10").andExpect(jsonPath("$.alreadyDone").value(true)) // the phone retried
                .andExpect(jsonPath("$.leftInReceiving").value(14));

        suggest(admin, "0.5").andExpect(jsonPath("$[0].code").value(first)).andExpect(jsonPath("$[0].why").value("SAME_PRODUCT"));
        String[] notFirst = read(suggest(admin, "5"), "$[*].code").toString().split(",");
        assertThat(String.join(",", notFirst)).doesNotContain(first); // 5 more would not fit there (9.5 kg of 10)
        confirm(admin, UUID.randomUUID().toString(), first, "1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BIN_FULL"));
        confirm(admin, UUID.randomUUID().toString(), "NOPE-1", "1").andExpect(jsonPath("$.code").value("BIN_NOT_FOUND"));
        confirm(admin, UUID.randomUUID().toString(), first, "100").andExpect(jsonPath("$.code").value("NOT_ENOUGH_STOCK"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE tenant_id = ? AND type = 'PUTAWAY'",
                Integer.class, tenant)).isEqualTo(1);
    }

    @Test
    void blockedBinsAreNotSuggested() throws Exception {
        String first = read(suggest(admin, "1"), "$[0].code");
        String binPublic = jdbc.queryForObject("SELECT public_id FROM bin WHERE warehouse_id = ? AND code = ?", String.class, warehouseId, first);
        send(admin, patch("/api/v1/bins/" + binPublic).content("{\"blocked\":true}")).andExpect(status().isOk());
        suggest(admin, "1").andExpect(jsonPath("$[0].code").value(org.hamcrest.Matchers.not(first)));
        confirm(admin, UUID.randomUUID().toString(), first, "1").andExpect(jsonPath("$.code").value("BIN_BLOCKED"));
    }

    @Test
    void onlyPeopleWhoPutAwayInThatWarehouse() throws Exception {
        Cookie viewer = login(newUser(tenant, "VIEWER"));
        send(viewer, get("/api/v1/putaway?warehouse=" + warehouse)).andExpect(status().isForbidden());
        confirm(viewer, UUID.randomUUID().toString(), "X", "1").andExpect(status().isForbidden());
        var receiverUser = newUser(tenant, "RECEIVER");
        Cookie receiver = login(receiverUser);
        send(receiver, get("/api/v1/putaway?warehouse=" + warehouse)).andExpect(status().isNotFound()); // not assigned yet
        send(admin, put("/api/v1/warehouses/" + warehouse + "/users").content("{\"users\":[\"" + receiverUser.getPublicId() + "\"]}"))
                .andExpect(status().isOk());
        send(receiver, get("/api/v1/putaway?warehouse=" + warehouse)).andExpect(status().isOk());
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/putaway?warehouse=" + warehouse)).andExpect(status().isNotFound());
        suggest(other, "1").andExpect(status().isNotFound());
        send(admin, post("/api/v1/putaway").content("{\"warehouseId\":\"" + warehouse + "\",\"productId\":\"" + oil
                + "\",\"qty\":1,\"binCode\":\"X\"}")).andExpect(status().isBadRequest()); // no Idempotency-Key
    }
}

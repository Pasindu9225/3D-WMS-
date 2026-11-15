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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** v0.4 task 7 (transfers, adjustments with approval) and the v0.4 exit test: receive, put away, transfer. */
class StockChangeApiTest extends ApiTest {

    long tenant;
    Cookie admin;
    String warehouse;
    long warehouseId;
    String oil;
    String binA;
    String binB;

    @BeforeEach
    void stockedWarehouse() throws Exception {
        tenant = newTenant(3);
        admin = login(newUser(tenant, "TENANT_ADMIN"));
        warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
        warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE public_id = ?", Long.class, warehouse);
        String type = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"SH\",\"name\":\"Shelf\",\"widthMm\":2700,"
                + "\"depthMm\":1100,\"heightMm\":3000,\"levels\":2,\"binsPerLevel\":3,\"maxWeightPerLevelG\":300000}")), "$.id");
        String floor = poly(0, 0, 40000, 20000);
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":" + floor
                + ",\"openings\":[],\"zones\":[{\"code\":\"R\",\"type\":\"RECEIVING\",\"name\":\"In\",\"polygon\":" + poly(0, 0, 5000, 20000)
                + "},{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":" + poly(5000, 0, 40000, 20000) + "}],\"racks\":["
                + "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":9000,\"y\":10000,\"rotation\":0}"
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        List<String> codes = jdbc.queryForList("SELECT code FROM bin WHERE warehouse_id = ? ORDER BY code", String.class, warehouseId);
        binA = codes.get(0);
        binB = codes.get(1);
        oil = read(send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Oil\",\"baseUom\":\"EA\",\"barcode\":\"4791\","
                + "\"weightG\":950,\"costMinor\":45000,\"trackBatch\":false,\"trackExpiry\":false,\"units\":[{\"uom\":\"CASE\",\"factor\":12}]}"))
                .andExpect(status().isCreated()), "$.id");
        receiveThroughTheApp();
    }

    private static String poly(int x1, int y1, int x2, int y2) {
        return "[{\"x\":" + x1 + ",\"y\":" + y1 + "},{\"x\":" + x2 + ",\"y\":" + y1 + "},{\"x\":" + x2 + ",\"y\":" + y2
                + "},{\"x\":" + x1 + ",\"y\":" + y2 + "}]";
    }

    /** 2 cases (24 EA) ordered, received on the phone flow and closed: they wait in the receiving area. */
    private void receiveThroughTheApp() throws Exception {
        send(admin, post("/api/v1/suppliers").content("{\"code\":\"S\",\"name\":\"S\"}")).andExpect(status().isCreated());
        String po = read(send(admin, post("/api/v1/purchase-orders").content("{\"supplierCode\":\"S\",\"warehouseId\":\"" + warehouse
                + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"CASE\",\"qty\":2}]}")), "$.id");
        send(admin, post("/api/v1/purchase-orders/" + po + "/approve").content("{\"version\":0}")).andExpect(status().isOk());
        String grn = read(send(admin, post("/api/v1/receipts").content("{\"poId\":\"" + po + "\"}")), "$.id");
        send(admin, post("/api/v1/receipts/" + grn + "/lines").header("Idempotency-Key", key()).content("{\"code\":\"OIL-1L\",\"uom\":\"CASE\",\"qty\":2}"))
                .andExpect(status().isOk());
        int v = read(send(admin, get("/api/v1/receipts/" + grn)), "$.version");
        send(admin, post("/api/v1/receipts/" + grn + "/close").content("{\"version\":" + v + "}")).andExpect(status().isOk());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private ResultActions transfer(Cookie who, String key, String from, String to, String qty) throws Exception {
        return send(who, post("/api/v1/transfers").header("Idempotency-Key", key).content("{\"warehouseId\":\"" + warehouse
                + "\",\"productId\":\"" + oil + "\",\"qty\":" + qty + (from == null ? "" : ",\"fromBin\":\"" + from + "\"")
                + ",\"toBin\":\"" + to + "\"}"));
    }

    private ResultActions adjust(Cookie who, String key, String bin, String direction, String qty, String reason) throws Exception {
        return send(who, post("/api/v1/adjustments").header("Idempotency-Key", key).content("{\"warehouseId\":\"" + warehouse
                + "\",\"productId\":\"" + oil + "\"" + (bin == null ? "" : ",\"binCode\":\"" + bin + "\"") + ",\"direction\":\"" + direction
                + "\",\"qty\":" + qty + ",\"reason\":\"" + reason + "\",\"note\":\"shelf check\"}"));
    }

    private BigDecimal qty(String binCode) {
        List<BigDecimal> q = binCode == null
                ? jdbc.queryForList("SELECT qty_on_hand FROM stock WHERE warehouse_id = ? AND bin_id IS NULL", BigDecimal.class, warehouseId)
                : jdbc.queryForList("SELECT s.qty_on_hand FROM stock s JOIN bin b ON b.id = s.bin_id WHERE b.warehouse_id = ? AND b.code = ?",
                        BigDecimal.class, warehouseId, binCode);
        return q.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void assertStockEqualsMovements() {
        BigDecimal stock = jdbc.queryForObject("SELECT COALESCE(SUM(qty_on_hand), 0) FROM stock WHERE tenant_id = ?", BigDecimal.class, tenant);
        BigDecimal in = jdbc.queryForObject("SELECT COALESCE(SUM(qty), 0) FROM stock_movement WHERE tenant_id = ? AND from_bin_id IS NULL AND NOT from_receiving",
                BigDecimal.class, tenant);
        BigDecimal out = jdbc.queryForObject("SELECT COALESCE(SUM(qty), 0) FROM stock_movement WHERE tenant_id = ? AND to_bin_id IS NULL AND NOT to_receiving",
                BigDecimal.class, tenant);
        assertThat(stock).isEqualByComparingTo(in.subtract(out));
    }

    @Test
    void receivePutAwayAndTransferEndToEnd() throws Exception {
        assertThat(qty(null)).isEqualByComparingTo("24");
        send(admin, post("/api/v1/putaway").header("Idempotency-Key", key()).content("{\"warehouseId\":\"" + warehouse + "\",\"productId\":\""
                + oil + "\",\"qty\":10,\"binCode\":\"" + binA + "\"}")).andExpect(status().isOk());
        String k = key();
        transfer(admin, k, binA, binB, "4").andExpect(status().isOk()).andExpect(jsonPath("$.fromBin").value(binA))
                .andExpect(jsonPath("$.toBin").value(binB)).andExpect(jsonPath("$.alreadyDone").value(false));
        transfer(admin, k, binA, binB, "4").andExpect(jsonPath("$.alreadyDone").value(true)); // the phone retried
        transfer(admin, key(), null, binB, "2").andExpect(status().isOk()); // straight from receiving
        assertThat(qty(null)).isEqualByComparingTo("12");
        assertThat(qty(binA)).isEqualByComparingTo("6");
        assertThat(qty(binB)).isEqualByComparingTo("6");
        transfer(admin, key(), binA, binA, "1").andExpect(jsonPath("$.code").value("SAME_PLACE"));
        transfer(admin, key(), binA, binB, "7").andExpect(jsonPath("$.code").value("NOT_ENOUGH_STOCK"));
        transfer(admin, key(), binA, "NOPE", "1").andExpect(jsonPath("$.code").value("BIN_NOT_FOUND"));
        assertThat(jdbc.queryForList("SELECT type FROM stock_movement WHERE tenant_id = ? ORDER BY id", String.class, tenant))
                .containsExactly("RECEIVE", "PUTAWAY", "TRANSFER", "TRANSFER");
        assertStockEqualsMovements();
    }

    @Test
    void smallAdjustmentsApplyAtOnceBigOnesWaitForAManager() throws Exception {
        AppUser controllerUser = newUser(tenant, "INVENTORY_CONTROLLER");
        AppUser managerUser = newUser(tenant, "WAREHOUSE_MANAGER");
        send(admin, put("/api/v1/warehouses/" + warehouse + "/users").content("{\"users\":[\"" + controllerUser.getPublicId() + "\",\""
                + managerUser.getPublicId() + "\"]}")).andExpect(status().isOk());
        Cookie controller = login(controllerUser);
        Cookie manager = login(managerUser);

        String retry = key();
        adjust(controller, retry, null, "OUT", "1", "DAMAGED").andExpect(status().isOk()) // LKR 450 <= 10,000: applied
                .andExpect(jsonPath("$.status").value("APPLIED")).andExpect(jsonPath("$.number").value("ADJ-000001"))
                .andExpect(jsonPath("$.valueMinor").value(45000));
        adjust(controller, retry, null, "OUT", "1", "DAMAGED").andExpect(jsonPath("$.number").value("ADJ-000001")); // counted once
        assertThat(qty(null)).isEqualByComparingTo("23");

        ResultActions big = adjust(controller, key(), null, "OUT", "23", "LOST") // LKR 10,350: waits
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.valueMinor").value(1035000));
        String bigId = read(big, "$.id");
        int bigVersion = read(big, "$.version");
        assertThat(qty(null)).isEqualByComparingTo("23");
        send(controller, post("/api/v1/adjustments/" + bigId + "/approve").content("{\"version\":" + bigVersion + "}"))
                .andExpect(status().isForbidden());
        adjust(controller, key(), null, "OUT", "1", "DAMAGED").andExpect(jsonPath("$.status").value("APPLIED")); // 22 left
        send(manager, post("/api/v1/adjustments/" + bigId + "/approve").content("{\"version\":" + bigVersion + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_ENOUGH_STOCK"));
        send(manager, get("/api/v1/adjustments?status=PENDING")).andExpect(jsonPath("$.total").value(1));
        send(manager, post("/api/v1/adjustments/" + bigId + "/reject").content("{\"version\":" + bigVersion + ",\"note\":\"Count again\"}"))
                .andExpect(jsonPath("$.status").value("REJECTED")).andExpect(jsonPath("$.decisionNote").value("Count again"));
        send(manager, post("/api/v1/adjustments/" + bigId + "/approve").content("{\"version\":" + (bigVersion + 1) + "}"))
                .andExpect(jsonPath("$.code").value("ADJUSTMENT_DECIDED"));

        ResultActions found = adjust(controller, key(), binA, "IN", "30", "FOUND").andExpect(jsonPath("$.status").value("PENDING"));
        send(manager, post("/api/v1/adjustments/" + read(found, "$.id") + "/approve").content("{\"version\":" + read(found, "$.version") + "}"))
                .andExpect(jsonPath("$.status").value("APPLIED")).andExpect(jsonPath("$.decidedBy").value("User WAREHOUSE_MANAGER"));
        assertThat(qty(binA)).isEqualByComparingTo("30");
        adjust(manager, key(), binB, "IN", "100", "COUNT_CORRECTION").andExpect(jsonPath("$.status").value("APPLIED")); // an approver
        assertThat(qty(binB)).isEqualByComparingTo("100");

        String noCost = read(send(admin, post("/api/v1/products").content("{\"sku\":\"SALT\",\"name\":\"Salt\",\"baseUom\":\"EA\","
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")), "$.id");
        send(controller, post("/api/v1/adjustments").header("Idempotency-Key", key()).content("{\"warehouseId\":\"" + warehouse
                + "\",\"productId\":\"" + noCost + "\",\"direction\":\"IN\",\"qty\":1,\"reason\":\"FOUND\"}"))
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.valueMinor").doesNotExist());
        assertStockEqualsMovements();
    }

    @Test
    void viewersSeeAdjustmentsButCannotMoveStock() throws Exception {
        Cookie viewer = login(newUser(tenant, "VIEWER"));
        adjust(admin, key(), null, "OUT", "1", "EXPIRED").andExpect(status().isOk());
        send(viewer, get("/api/v1/adjustments")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0)); // not assigned to WH1
        adjust(viewer, key(), null, "OUT", "1", "EXPIRED").andExpect(status().isForbidden());
        transfer(viewer, key(), null, binA, "1").andExpect(status().isForbidden());
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/adjustments")).andExpect(jsonPath("$.total").value(0));
        transfer(other, key(), null, binA, "1").andExpect(status().isNotFound());
        send(admin, post("/api/v1/transfers").content("{\"warehouseId\":\"" + warehouse + "\",\"productId\":\"" + oil
                + "\",\"qty\":1,\"toBin\":\"" + binA + "\"}")).andExpect(status().isBadRequest()); // no Idempotency-Key
    }
}

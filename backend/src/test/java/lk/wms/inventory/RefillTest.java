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
import java.time.LocalDate;
import java.util.List;
import lk.wms.ApiTest;
import lk.wms.auth.domain.AppUser;
import lk.wms.common.tenant.TenantContext;
import lk.wms.inventory.service.StockMovementService;
import lk.wms.inventory.service.StockMovementService.Kind;
import lk.wms.inventory.service.StockMovementService.Move;
import lk.wms.inventory.service.StockMovementService.Place;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** v1.2 task 3: pick bins kept between min and max; refill tasks bring stock from bulk storage (SEC-26). */
class RefillTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    long tenant;
    AppUser adminUser;
    Cookie admin;
    String warehouse;
    long warehouseId;
    long oil;
    List<Long> a; // storage bins A-..
    long hold;    // a quarantine bin

    private void in(long bin, int qty, LocalDate expiry) {
        TenantContext.callAs(tenant, () -> {
            moves.move(new Move(Kind.RECEIVE, warehouseId, oil, null, expiry, null, Place.RECEIVING, BigDecimal.valueOf(qty), null, null, null, adminUser.getId()));
            moves.move(new Move(Kind.PUTAWAY, warehouseId, oil, null, expiry, Place.RECEIVING, Place.bin(bin), BigDecimal.valueOf(qty), null, null, null, adminUser.getId()));
            return null;
        });
    }

    /** Takes stock out of a bin (as a pick or transfer would): the refill check runs in the same transaction. */
    private void out(long from, int qty, LocalDate expiry) {
        TenantContext.callAs(tenant, () -> {
            moves.move(new Move(Kind.ADJUST, warehouseId, oil, null, expiry, Place.bin(from), null, BigDecimal.valueOf(qty), "test", null, null, adminUser.getId()));
            return null;
        });
    }

    private String code(long bin) {
        return jdbc.queryForObject("SELECT code FROM bin WHERE id = ?", String.class, bin);
    }

    private BigDecimal onHand(long bin) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(qty_on_hand), 0) FROM stock WHERE bin_id = ?", BigDecimal.class, bin);
    }

    private void pickFace(long bin, boolean on) throws Exception {
        String id = jdbc.queryForObject("SELECT public_id FROM bin WHERE id = ?", String.class, bin);
        send(admin, patch("/api/v1/bins/" + id).content("{\"pickFace\":" + on + "}")).andExpect(status().isOk());
    }

    @Test
    void belowMinMakesOneTaskWhichBringsStockFromTheRightBulkBin() throws Exception {
        tenant = newTenant(3);
        adminUser = newUser(tenant, "TENANT_ADMIN");
        admin = login(adminUser);
        warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
        warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE public_id = ?", Long.class, warehouse);
        String type = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"SH\",\"name\":\"Shelf\",\"widthMm\":2700,"
                + "\"depthMm\":1100,\"heightMm\":3000,\"levels\":2,\"binsPerLevel\":3,\"maxWeightPerLevelG\":3000000}"))
                .andExpect(status().isCreated()), "$.id");
        String poly = "[{\"x\":0,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":20000},{\"x\":0,\"y\":20000}]";
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":" + poly
                + ",\"openings\":[],\"zones\":[{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":"
                + "[{\"x\":0,\"y\":0},{\"x\":20000,\"y\":0},{\"x\":20000,\"y\":20000},{\"x\":0,\"y\":20000}]},"
                + "{\"code\":\"Q\",\"type\":\"QUARANTINE\",\"name\":\"Hold\",\"polygon\":"
                + "[{\"x\":20000,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":20000},{\"x\":20000,\"y\":20000}]}],\"racks\":["
                + "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":5000,\"y\":8000,\"rotation\":0},"
                + "{\"code\":\"R02\",\"templateId\":\"" + type + "\",\"zone\":\"Q\",\"x\":25000,\"y\":8000,\"rotation\":0}"
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        a = jdbc.queryForList("SELECT id FROM bin WHERE warehouse_id = ? AND code LIKE 'A-%' ORDER BY code", Long.class, warehouseId);
        hold = jdbc.queryForObject("SELECT id FROM bin WHERE warehouse_id = ? AND code LIKE 'Q-%' ORDER BY code LIMIT 1", Long.class, warehouseId);
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Oil\",\"baseUom\":\"EA\",\"barcode\":\"4791\",\"trackBatch\":false,"
                + "\"trackExpiry\":false,\"units\":[{\"uom\":\"CASE\",\"factor\":12,\"barcode\":\"14791\"}]}")).andExpect(status().isCreated());
        oil = jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = 'OIL-1L'", Long.class, tenant);

        long pick = a.get(0);
        LocalDate soon = LocalDate.now().plusDays(30);
        LocalDate later = LocalDate.now().plusDays(300);
        in(pick, 8, later);
        in(a.get(1), 12, later);   // bulk, expires later
        in(a.get(2), 12, soon);    // bulk, expires sooner: the one to take
        in(a.get(3), 5, null);     // will be blocked
        in(a.get(4), 9, null);     // another pick face: never a source
        in(hold, 7, null);         // quarantine: never a source

        send(admin, post("/api/v1/refill-rules").content("{\"warehouseId\":\"" + warehouse + "\",\"binCode\":\"" + code(pick)
                + "\",\"sku\":\"OIL-1L\",\"minQty\":5,\"maxQty\":20}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_PICK_FACE"));
        pickFace(pick, true);
        pickFace(a.get(4), true);
        String binPublic = jdbc.queryForObject("SELECT public_id FROM bin WHERE id = ?", String.class, a.get(3));
        send(admin, patch("/api/v1/bins/" + binPublic).content("{\"blocked\":true}")).andExpect(status().isOk());
        send(admin, post("/api/v1/refill-rules").content("{\"warehouseId\":\"" + warehouse + "\",\"binCode\":\"" + code(pick)
                + "\",\"sku\":\"OIL-1L\",\"minQty\":20,\"maxQty\":20}")).andExpect(jsonPath("$.code").value("BAD_MIN_MAX"));
        send(admin, post("/api/v1/refill-rules").content("{\"warehouseId\":\"" + warehouse + "\",\"binCode\":\"" + code(pick).toLowerCase()
                + "\",\"sku\":\"4791\",\"minQty\":5,\"maxQty\":20}")).andExpect(status().isCreated()).andExpect(jsonPath("$.onHand").value(8));
        send(admin, post("/api/v1/refill-rules").content("{\"warehouseId\":\"" + warehouse + "\",\"binCode\":\"" + code(pick)
                + "\",\"sku\":\"OIL-1L\",\"minQty\":1,\"maxQty\":2}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RULE_EXISTS"));

        out(pick, 2, later); // 6: still at or above min, no task
        send(admin, get("/api/v1/refill-tasks")).andExpect(jsonPath("$.items.length()").value(0));
        out(pick, 2, later); // 4 < 5: one task for 20 - 4 = 16
        out(pick, 1, later); // 3: still one task
        String task = read(send(admin, get("/api/v1/refill-tasks")).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].number").value("RF-000001")).andExpect(jsonPath("$.items[0].qty").value(16))
                .andExpect(jsonPath("$.items[0].from.binCode").value(code(a.get(2)))) // sooner expiry; not blocked, pick face or quarantine
                .andExpect(jsonPath("$.items[0].from.free").value(12)), "$.items[0].id");
        send(admin, get("/api/v1/task-board?warehouse=" + warehouse)).andExpect(jsonPath("$.toRefill").value(1));

        String confirm = "/api/v1/refill-tasks/" + task + "/confirm";
        send(admin, post(confirm).content("{\"fromBinCode\":\"" + code(a.get(2)) + "\",\"toBinCode\":\"" + code(a.get(1)) + "\",\"code\":\"OIL-1L\",\"qty\":12}"))
                .andExpect(jsonPath("$.code").value("WRONG_BIN"));
        send(admin, post(confirm).content("{\"fromBinCode\":\"" + code(a.get(4)) + "\",\"toBinCode\":\"" + code(pick) + "\",\"code\":\"OIL-1L\",\"qty\":9}"))
                .andExpect(jsonPath("$.code").value("NOT_BULK_BIN"));
        send(admin, post(confirm).content("{\"fromBinCode\":\"" + code(a.get(2)) + "\",\"toBinCode\":\"" + code(pick) + "\",\"code\":\"14791\",\"qty\":2}"))
                .andExpect(jsonPath("$.code").value("MORE_THAN_ASKED")); // 2 cases = 24 > 16
        send(admin, post(confirm).content("{\"fromBinCode\":\"" + code(a.get(2)) + "\",\"toBinCode\":\"" + code(pick) + "\",\"code\":\"OIL-1L\",\"qty\":12}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.movedQty").value(12));
        assertThat(onHand(pick)).isEqualByComparingTo("15");
        assertThat(onHand(a.get(2))).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE tenant_id = ? AND type = 'TRANSFER' AND ref_type = 'REFILL'",
                Integer.class, tenant)).isEqualTo(1);
        send(admin, post(confirm).content("{\"fromBinCode\":\"" + code(a.get(1)) + "\",\"toBinCode\":\"" + code(pick) + "\",\"code\":\"OIL-1L\",\"qty\":1}"))
                .andExpect(jsonPath("$.code").value("REFILL_DONE"));

        // below min again: a new task (the old one is done)
        out(pick, 11, soon);
        out(pick, 1, later);
        send(admin, get("/api/v1/refill-tasks")).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].number").value("RF-000002"));

        // check now: a rule set while the bin is already below min
        pickFace(a.get(5), true);
        send(admin, post("/api/v1/refill-rules").content("{\"warehouseId\":\"" + warehouse + "\",\"binCode\":\"" + code(a.get(5))
                + "\",\"sku\":\"OIL-1L\",\"minQty\":2,\"maxQty\":6}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/refill-rules/check").content("{\"warehouseId\":\"" + warehouse + "\"}")).andExpect(jsonPath("$.made").value(1));
        send(admin, post("/api/v1/refill-rules/check").content("{\"warehouseId\":\"" + warehouse + "\"}")).andExpect(jsonPath("$.made").value(0));

        // who may do what
        Cookie picker = login(newUser(tenant, "PICKER"));
        send(picker, post("/api/v1/refill-rules").content("{\"warehouseId\":\"" + warehouse + "\",\"binCode\":\"" + code(pick)
                + "\",\"sku\":\"OIL-1L\",\"minQty\":1,\"maxQty\":2}")).andExpect(status().isForbidden());
        send(picker, get("/api/v1/refill-tasks")).andExpect(status().isOk()); // tasks:read
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/refill-tasks/" + task)).andExpect(status().isNotFound());
        send(other, get("/api/v1/refill-rules?warehouse=" + warehouse)).andExpect(status().isNotFound());
    }
}

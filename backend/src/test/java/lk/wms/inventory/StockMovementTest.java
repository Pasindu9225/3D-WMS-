package lk.wms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lk.wms.ApiTest;
import lk.wms.auth.domain.AppUser;
import lk.wms.common.errors.ApiException;
import lk.wms.common.tenant.TenantContext;
import lk.wms.inventory.service.StockMovementService.Kind;
import lk.wms.inventory.service.StockMovementService;
import lk.wms.inventory.service.StockMovementService.Move;
import lk.wms.inventory.service.StockMovementService.Place;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** v0.4 task 6: StockMovementService is the only writer of stock; it locks, checks and records every move (SEC-26). */
class StockMovementTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    long tenant;
    AppUser adminUser;
    Cookie admin;
    String warehouse;
    long warehouseId;
    long oil;
    String type;
    List<Long> bins;

    @BeforeEach
    void warehouseWithBinsAndProducts() throws Exception {
        tenant = newTenant(3);
        adminUser = newUser(tenant, "TENANT_ADMIN");
        admin = login(adminUser);
        warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
        warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE public_id = ?", Long.class, warehouse);
        // 2 levels x 3 bins, 30 kg per level: every bin takes 10 kg
        type = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"SH\",\"name\":\"Shelf\",\"widthMm\":2700,"
                + "\"depthMm\":1100,\"heightMm\":3000,\"levels\":2,\"binsPerLevel\":3,\"maxWeightPerLevelG\":30000}"))
                .andExpect(status().isCreated()), "$.id");
        saveLayout(0, true);
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        bins = jdbc.queryForList("SELECT id FROM bin WHERE warehouse_id = ? ORDER BY code", Long.class, warehouseId);
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Oil\",\"baseUom\":\"EA\",\"barcode\":\"4791\","
                + "\"weightG\":950,\"trackBatch\":false,\"trackExpiry\":false,\"units\":[{\"uom\":\"CASE\",\"factor\":12}]}"))
                .andExpect(status().isCreated());
        oil = jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = 'OIL-1L'", Long.class, tenant);
    }

    private void saveLayout(int base, boolean withRack) throws Exception {
        String poly = "[{\"x\":0,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":20000},{\"x\":0,\"y\":20000}]";
        String rack = withRack ? "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":5000,\"y\":5000,\"rotation\":0}" : "";
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":" + base + ",\"layout\":{\"floor\":"
                + poly + ",\"openings\":[],\"zones\":[{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":" + poly
                + "}],\"racks\":[" + rack + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
    }

    private void move(Kind t, Place from, Place to, String qty) {
        TenantContext.callAs(tenant, () -> {
            moves.move(new Move(t, warehouseId, oil, null, null, from, to, new BigDecimal(qty), null, null, null, adminUser.getId()));
            return null;
        });
    }

    private void receive(int eaches) {
        move(Kind.RECEIVE, null, Place.RECEIVING, Integer.toString(eaches));
    }

    private BigDecimal qty(Long bin) {
        List<BigDecimal> q = bin == null
                ? jdbc.queryForList("SELECT qty_on_hand FROM stock WHERE warehouse_id = ? AND bin_id IS NULL AND product_id = ?", BigDecimal.class, warehouseId, oil)
                : jdbc.queryForList("SELECT qty_on_hand FROM stock WHERE bin_id = ? AND product_id = ?", BigDecimal.class, bin, oil);
        return q.isEmpty() ? BigDecimal.ZERO : q.get(0);
    }

    /** SPEC v0.4 exit: stock always equals the sum of movements, place by place. */
    private void assertLedgerBalances() {
        List<String> off = jdbc.queryForList("""
                SELECT CONCAT(IFNULL(s.bin_id, 0), ':', s.product_id) FROM stock s
                WHERE s.tenant_id = ? AND s.qty_on_hand <> (
                  SELECT COALESCE(SUM(CASE WHEN (m.to_bin_id <=> s.bin_id AND (s.bin_id IS NOT NULL OR m.to_receiving)) THEN m.qty ELSE 0 END)
                       - SUM(CASE WHEN (m.from_bin_id <=> s.bin_id AND (s.bin_id IS NOT NULL OR m.from_receiving)) THEN m.qty ELSE 0 END), 0)
                  FROM stock_movement m WHERE m.tenant_id = s.tenant_id AND m.warehouse_id = s.warehouse_id
                    AND m.product_id = s.product_id AND m.batch_no <=> s.batch_no AND m.expiry_date <=> s.expiry_date)""",
                String.class, tenant);
        assertThat(off).as("stock rows that differ from their movements").isEmpty();
    }

    @Test
    void closingAReceiptPutsGoodsInTheReceivingAreaAndBatchesAreKept() throws Exception {
        send(admin, post("/api/v1/suppliers").content("{\"code\":\"S\",\"name\":\"S\"}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/products").content("{\"sku\":\"MED\",\"name\":\"Medicine\",\"baseUom\":\"EA\","
                + "\"trackBatch\":true,\"trackExpiry\":true,\"units\":[]}")).andExpect(status().isCreated());
        String po = read(send(admin, post("/api/v1/purchase-orders").content("{\"supplierCode\":\"S\",\"warehouseId\":\"" + warehouse
                + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"CASE\",\"qty\":2},{\"sku\":\"MED\",\"uom\":\"EA\",\"qty\":10}]}")), "$.id");
        send(admin, post("/api/v1/purchase-orders/" + po + "/approve").content("{\"version\":0}")).andExpect(status().isOk());
        String grn = read(send(admin, post("/api/v1/receipts").content("{\"poId\":\"" + po + "\"}")), "$.id");
        String scan = "/api/v1/receipts/" + grn + "/lines";
        send(admin, post(scan).header("Idempotency-Key", UUID.randomUUID().toString()).content("{\"code\":\"OIL-1L\",\"uom\":\"CASE\",\"qty\":2}"))
                .andExpect(status().isOk());
        send(admin, post(scan).header("Idempotency-Key", UUID.randomUUID().toString()).content("{\"code\":\"MED\",\"qty\":6}"))
                .andExpect(jsonPath("$.code").value("BATCH_REQUIRED"));
        send(admin, post(scan).header("Idempotency-Key", UUID.randomUUID().toString()).content("{\"code\":\"MED\",\"qty\":6,\"batchNo\":\"b-1\"}"))
                .andExpect(jsonPath("$.code").value("EXPIRY_REQUIRED"));
        send(admin, post(scan).header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"code\":\"MED\",\"qty\":6,\"batchNo\":\"b-1\",\"expiryDate\":\"2027-06-30\"}")).andExpect(status().isOk());
        send(admin, post(scan).header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"code\":\"MED\",\"qty\":4,\"batchNo\":\"B-2\",\"expiryDate\":\"2027-09-30\"}")).andExpect(status().isOk());
        int version = read(send(admin, get("/api/v1/receipts/" + grn)), "$.version");
        send(admin, post("/api/v1/receipts/" + grn + "/close").content("{\"version\":" + version + "}")).andExpect(status().isOk());

        assertThat(qty(null)).isEqualByComparingTo("24");
        assertThat(jdbc.queryForList("SELECT CONCAT(batch_no, ' ', expiry_date, ' ', qty_on_hand) FROM stock s JOIN product p ON p.id = s.product_id"
                + " WHERE p.sku = 'MED' AND s.tenant_id = ? ORDER BY batch_no", String.class, tenant))
                .containsExactly("B-1 2027-06-30 6.000", "B-2 2027-09-30 4.000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE tenant_id = ? AND type = 'RECEIVE' AND ref_type = 'receipt'",
                Integer.class, tenant)).isEqualTo(3);
        assertLedgerBalances();
    }

    @Test
    void movesCheckCapacityBlockedBinsAndWhatIsThere() throws Exception {
        receive(30);
        move(Kind.PUTAWAY, Place.RECEIVING, Place.bin(bins.get(0)), "10"); // 9.5 kg of 10
        assertThatThrownBy(() -> move(Kind.PUTAWAY, Place.RECEIVING, Place.bin(bins.get(0)), "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("BIN_FULL"));
        assertThatThrownBy(() -> move(Kind.PUTAWAY, Place.RECEIVING, Place.bin(bins.get(1)), "31"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("NOT_ENOUGH_STOCK"));
        String binPublic = jdbc.queryForObject("SELECT public_id FROM bin WHERE id = ?", String.class, bins.get(1));
        send(admin, patch("/api/v1/bins/" + binPublic).content("{\"blocked\":true}")).andExpect(status().isOk());
        assertThatThrownBy(() -> move(Kind.PUTAWAY, Place.RECEIVING, Place.bin(bins.get(1)), "1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("BIN_BLOCKED"));
        move(Kind.TRANSFER, Place.bin(bins.get(0)), Place.bin(bins.get(2)), "4");
        move(Kind.ADJUST, Place.bin(bins.get(2)), null, "1"); // one broken
        assertThat(qty(null)).isEqualByComparingTo("20");
        assertThat(qty(bins.get(0))).isEqualByComparingTo("6");
        assertThat(qty(bins.get(2))).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock WHERE bin_id = ?", Integer.class, bins.get(1))).isZero(); // failed moves left nothing
        assertLedgerBalances();
    }

    @Test
    void twoWorkersPuttingAwayIntoTheSameBinNeverOverfillIt() throws Exception {
        receive(24);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> tries = new ArrayList<>();
            for (int i = 0; i < 16; i++) { // 16 x 1 EA offered; the bin takes 10 (9.5 kg)
                Callable<Boolean> job = () -> {
                    try {
                        move(Kind.PUTAWAY, Place.RECEIVING, Place.bin(bins.get(0)), "1");
                        return true;
                    } catch (ApiException e) {
                        assertThat(e.getCode()).isEqualTo("BIN_FULL");
                        return false;
                    }
                };
                tries.add(pool.submit(job));
            }
            int ok = 0;
            for (Future<Boolean> f : tries) {
                ok += f.get() ? 1 : 0;
            }
            assertThat(ok).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
        assertThat(qty(bins.get(0))).isEqualByComparingTo("10");
        assertThat(qty(null)).isEqualByComparingTo("14");
        assertLedgerBalances();
    }

    @Test
    void oppositeTransfersAtTheSameTimeDoNotDeadlock() throws Exception {
        receive(10);
        move(Kind.PUTAWAY, Place.RECEIVING, Place.bin(bins.get(3)), "5");
        move(Kind.PUTAWAY, Place.RECEIVING, Place.bin(bins.get(4)), "5");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                boolean forth = i % 2 == 0;
                jobs.add(pool.submit(() -> {
                    try {
                        move(Kind.TRANSFER, Place.bin(bins.get(forth ? 3 : 4)), Place.bin(bins.get(forth ? 4 : 3)), "1");
                    } catch (ApiException e) {
                        assertThat(e.getCode()).isIn("NOT_ENOUGH_STOCK", "BIN_FULL");
                    }
                    return null;
                }));
            }
            for (Future<?> f : jobs) {
                f.get(); // a deadlock or lock timeout would surface here as a non-API exception
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(qty(bins.get(3)).add(qty(bins.get(4)))).isEqualByComparingTo("10");
        assertLedgerBalances();
    }

    @Test
    void theLedgerCannotBeEditedAndPublishKeepsBinsThatHoldStock() throws Exception {
        receive(5);
        move(Kind.PUTAWAY, Place.RECEIVING, Place.bin(bins.get(5)), "5");
        assertThatThrownBy(() -> jdbc.update("UPDATE stock_movement SET qty = 1 WHERE tenant_id = ?", tenant))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM stock_movement WHERE tenant_id = ?", tenant))
                .hasMessageContaining("append-only");

        saveLayout(0, false); // a new draft without the rack: its bins would go
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BINS_HOLD_STOCK"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bin WHERE warehouse_id = ?", Integer.class, warehouseId)).isEqualTo(6);
    }
}

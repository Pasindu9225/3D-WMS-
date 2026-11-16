package lk.wms.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import lk.wms.common.tenant.TenantContext;
import lk.wms.inventory.service.StockMovementService;
import lk.wms.inventory.service.StockMovementService.Kind;
import lk.wms.inventory.service.StockMovementService.Move;
import lk.wms.inventory.service.StockMovementService.Place;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** v1.0 task 3: pick lists — scan bin and product, stock to the dispatch area, short picks (SEC-26). */
class PickingTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    long tenant;
    AppUser adminUser;
    Cookie admin;
    Cookie picker;
    String warehouse;
    long warehouseId;
    long oil;
    List<Long> bins;
    List<String> codes;

    @BeforeEach
    void warehouseWithStockAndPicker() throws Exception {
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
                + ",\"openings\":[],\"zones\":[{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":" + poly + "}],\"racks\":["
                + "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":5000,\"y\":5000,\"rotation\":0}"
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        bins = jdbc.queryForList("SELECT id FROM bin WHERE warehouse_id = ? ORDER BY code", Long.class, warehouseId);
        codes = jdbc.queryForList("SELECT code FROM bin WHERE warehouse_id = ? ORDER BY code", String.class, warehouseId);
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Oil\",\"baseUom\":\"EA\",\"barcode\":\"4791\","
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[{\"uom\":\"CASE\",\"factor\":12,\"barcode\":\"14791\"}]}"))
                .andExpect(status().isCreated());
        send(admin, post("/api/v1/products").content("{\"sku\":\"RICE\",\"name\":\"Rice\",\"baseUom\":\"BAG\",\"trackBatch\":false,"
                + "\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());
        oil = jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = 'OIL-1L'", Long.class, tenant);
        send(admin, post("/api/v1/customers").content("{\"code\":\"C1\",\"name\":\"Kandy Super\"}")).andExpect(status().isCreated());
        AppUser pickerUser = newUser(tenant, "PICKER");
        send(admin, put("/api/v1/warehouses/" + warehouse + "/users").content("{\"users\":[\"" + pickerUser.getPublicId() + "\"]}"))
                .andExpect(status().isOk());
        picker = login(pickerUser);
    }

    private void stockIn(long product, long bin, int qty, String receivedAt) {
        TenantContext.callAs(tenant, () -> {
            moves.move(new Move(Kind.RECEIVE, warehouseId, product, null, null, null, Place.RECEIVING, new BigDecimal(qty), null, null, null, adminUser.getId()));
            moves.move(new Move(Kind.PUTAWAY, warehouseId, product, null, null, Place.RECEIVING, Place.bin(bin), new BigDecimal(qty), null, null, null, adminUser.getId()));
            return null;
        });
        jdbc.update("UPDATE stock SET received_at = ? WHERE bin_id = ? AND product_id = ?", receivedAt, bin, product);
    }

    /** Releases an order and returns its pick list id. */
    private String released(String lines) throws Exception {
        String so = read(send(admin, post("/api/v1/sales-orders").content("{\"customerCode\":\"C1\",\"warehouseId\":\"" + warehouse
                + "\",\"lines\":[" + lines + "]}")).andExpect(status().isCreated()), "$.id");
        return read(send(admin, post("/api/v1/sales-orders/" + so + "/release").content("{\"version\":0}")).andExpect(status().isOk()), "$.pick.id");
    }

    private ResultActions confirm(Cookie who, String list, int seq, String bin, String code, String qty, String key) throws Exception {
        return send(who, post("/api/v1/pick-lists/" + list + "/lines/" + seq + "/confirm").header("Idempotency-Key", key)
                .content("{\"binCode\":\"" + bin + "\",\"code\":\"" + code + "\",\"qty\":" + qty + "}"));
    }

    private BigDecimal at(String area, Long bin, long product) {
        List<BigDecimal> q = jdbc.queryForList("SELECT qty_on_hand FROM stock WHERE tenant_id = ? AND area = ? AND bin_id <=> ? AND product_id = ?",
                BigDecimal.class, tenant, area, bin, product);
        return q.isEmpty() ? BigDecimal.ZERO : q.get(0);
    }

    /** Stock equals the sum of movements in every place: bins, receiving area and dispatch area. */
    private void assertLedgerBalances() {
        List<String> off = jdbc.queryForList("""
                SELECT CONCAT(s.area, ':', IFNULL(s.bin_id, 0), ':', s.product_id) FROM stock s
                WHERE s.tenant_id = ? AND s.qty_on_hand <> (
                  SELECT COALESCE(SUM(CASE WHEN (s.area = 'BIN' AND m.to_bin_id <=> s.bin_id) OR (s.area = 'RECEIVING' AND m.to_receiving)
                                             OR (s.area = 'DISPATCH' AND m.to_dispatch) THEN m.qty ELSE 0 END)
                       - SUM(CASE WHEN (s.area = 'BIN' AND m.from_bin_id <=> s.bin_id) OR (s.area = 'RECEIVING' AND m.from_receiving)
                                             OR (s.area = 'DISPATCH' AND m.from_dispatch) THEN m.qty ELSE 0 END), 0)
                  FROM stock_movement m WHERE m.tenant_id = s.tenant_id AND m.warehouse_id = s.warehouse_id
                    AND m.product_id = s.product_id AND m.batch_no <=> s.batch_no AND m.expiry_date <=> s.expiry_date)""",
                String.class, tenant);
        assertThat(off).as("stock places that differ from their movements").isEmpty();
    }

    @Test
    void pickerScansBinAndProductAndGoodsMoveToDispatch() throws Exception {
        stockIn(oil, bins.get(2), 10, "2026-11-01 10:00:00"); // oldest: first
        stockIn(oil, bins.get(0), 30, "2026-11-05 10:00:00");
        String list = released("{\"sku\":\"OIL-1L\",\"uom\":\"CASE\",\"qty\":2}"); // 24 EA: 10 from bin 2, 14 from bin 0

        send(picker, get("/api/v1/pick-lists")).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].openLines").value(2));
        send(picker, get("/api/v1/pick-lists/" + list)).andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.lines[0].binCode").value(codes.get(0))).andExpect(jsonPath("$.lines[0].qtyToPick").value(14))
                .andExpect(jsonPath("$.lines[1].binCode").value(codes.get(2))).andExpect(jsonPath("$.lines[1].qtyToPick").value(10));

        String key = UUID.randomUUID().toString();
        confirm(picker, list, 1, codes.get(1), "4791", "1", key).andExpect(jsonPath("$.code").value("WRONG_BIN"));
        confirm(picker, list, 1, codes.get(0), "RICE", "1", key).andExpect(jsonPath("$.code").value("WRONG_PRODUCT"));
        confirm(picker, list, 1, codes.get(0), "14791", "2", key).andExpect(jsonPath("$.code").value("OVER_PICK")); // 24 > 14
        confirm(picker, list, 1, codes.get(0).toLowerCase(), "14791", "1", key).andExpect(status().isOk()) // a case = 12
                .andExpect(jsonPath("$.status").value("IN_PROGRESS")).andExpect(jsonPath("$.lines[0].qtyPicked").value(12));
        confirm(picker, list, 1, codes.get(0), "14791", "1", key).andExpect(status().isOk()) // phone retry: nothing more
                .andExpect(jsonPath("$.lines[0].qtyPicked").value(12));
        assertThat(at("DISPATCH", null, oil)).isEqualByComparingTo("12");
        String so = read(send(admin, get("/api/v1/sales-orders?status=PICKING")).andExpect(jsonPath("$.total").value(1)), "$.items[0].id");

        confirm(picker, list, 1, codes.get(0), "OIL-1L", "2", UUID.randomUUID().toString()).andExpect(jsonPath("$.lines[0].status").value("PICKED"));
        confirm(picker, list, 1, codes.get(0), "OIL-1L", "1", UUID.randomUUID().toString()).andExpect(jsonPath("$.code").value("PICK_LINE_DONE"));
        confirm(picker, list, 2, codes.get(2), "4791", "10", UUID.randomUUID().toString()).andExpect(jsonPath("$.status").value("DONE"));

        assertThat(at("BIN", bins.get(0), oil)).isEqualByComparingTo("16");
        assertThat(at("BIN", bins.get(2), oil)).isZero();
        assertThat(at("DISPATCH", null, oil)).isEqualByComparingTo("24");
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(qty_reserved), 0) FROM stock WHERE tenant_id = ?", BigDecimal.class, tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE tenant_id = ?", Integer.class, tenant)).isZero();
        send(admin, get("/api/v1/sales-orders/" + so)).andExpect(jsonPath("$.status").value("PICKED"))
                .andExpect(jsonPath("$.pick.status").value("DONE"));
        send(admin, post("/api/v1/sales-orders/" + so + "/cancel").content("{\"version\":5,\"reason\":\"x\"}"))
                .andExpect(status().isConflict());
        send(admin, get("/api/v1/stock?warehouse=" + warehouse)).andExpect(jsonPath("$.items[?(@.binCode == 'DISPATCH')].qtyOnHand").value(24.0));
        assertLedgerBalances();
    }

    @Test
    void shortPickClosesTheRestAndFreesItsReservation() throws Exception {
        stockIn(oil, bins.get(0), 10, "2026-11-01 10:00:00");
        String list = released("{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":10}");
        confirm(picker, list, 1, codes.get(0), "4791", "7", UUID.randomUUID().toString()).andExpect(status().isOk());
        send(picker, post("/api/v1/pick-lists/" + list + "/lines/1/short").content("{\"reason\":\"LOST\"}")).andExpect(status().isBadRequest());
        send(picker, post("/api/v1/pick-lists/" + list + "/lines/1/short").content("{\"reason\":\"NOT_THERE\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.lines[0].status").value("SHORT"))
                .andExpect(jsonPath("$.lines[0].qtyPicked").value(7)).andExpect(jsonPath("$.lines[0].shortReason").value("NOT_THERE"));
        assertThat(jdbc.queryForObject("SELECT qty_reserved FROM stock WHERE bin_id = ?", BigDecimal.class, bins.get(0))).isZero();
        assertThat(at("BIN", bins.get(0), oil)).isEqualByComparingTo("3"); // the books still say 3: the manager counts or adjusts
        send(admin, get("/api/v1/sales-orders?status=PICKED")).andExpect(jsonPath("$.total").value(1));
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE tenant_id = ? AND action LIKE 'PICK!_%' ESCAPE '!'", String.class, tenant))
                .containsExactlyInAnyOrder("PICK_CONFIRMED", "PICK_SHORT");
        assertLedgerBalances();
    }

    @Test
    void cancelBeforePickingDropsTheListAndConcurrentPicksNeverOverPick() throws Exception {
        stockIn(oil, bins.get(0), 20, "2026-11-01 10:00:00");
        String first = released("{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":5}");
        String so = read(send(admin, get("/api/v1/sales-orders?status=RELEASED")), "$.items[0].id");
        send(admin, post("/api/v1/sales-orders/" + so + "/cancel").content("{\"version\":1,\"reason\":\"Changed mind\"}")).andExpect(status().isOk());
        send(picker, get("/api/v1/pick-lists/" + first)).andExpect(status().isNotFound());

        String list = released("{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":6}");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                Callable<Integer> job = () -> confirm(picker, list, 1, codes.get(0), "4791", "2", UUID.randomUUID().toString())
                        .andReturn().getResponse().getStatus();
                results.add(pool.submit(job));
            }
            int ok = 0;
            for (Future<Integer> f : results) {
                int code = f.get();
                assertThat(code).isIn(200, 409);
                ok += code == 200 ? 1 : 0;
            }
            assertThat(ok).isEqualTo(3); // 3 x 2 = the 6 to pick
        } finally {
            pool.shutdownNow();
        }
        assertThat(at("DISPATCH", null, oil)).isEqualByComparingTo("6");
        assertLedgerBalances();
    }

    @Test
    void picksFollowRolesWarehousesAndCompany() throws Exception {
        stockIn(oil, bins.get(0), 5, "2026-11-01 10:00:00");
        String list = released("{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}");
        Cookie receiver = login(newUser(tenant, "RECEIVER"));
        send(receiver, get("/api/v1/pick-lists")).andExpect(status().isForbidden());
        Cookie unassigned = login(newUser(tenant, "PICKER"));
        send(unassigned, get("/api/v1/pick-lists")).andExpect(jsonPath("$.total").value(0));
        send(unassigned, get("/api/v1/pick-lists/" + list)).andExpect(status().isNotFound());
        Cookie manager = login(newUser(tenant, "VIEWER"));
        confirm(manager, list, 1, codes.get(0), "4791", "1", UUID.randomUUID().toString()).andExpect(status().isForbidden());
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/pick-lists/" + list)).andExpect(status().isNotFound());
    }
}

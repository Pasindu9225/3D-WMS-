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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * v1.0 task 5: dispatch — packed orders leave on a shipment, stock leaves the dispatch area. Also the whole outbound
 * loop through the API: receive, put away, release, pick, pack, ship, with stock = sum of movements throughout.
 */
class DispatchTest extends ApiTest {

    long tenant;
    AppUser adminUser;
    Cookie admin;
    String warehouse;
    long warehouseId;
    List<String> codes;

    @BeforeEach
    void warehouse() throws Exception {
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
                + ",\"openings\":[],\"zones\":[{\"code\":\"R\",\"type\":\"RECEIVING\",\"name\":\"In\",\"polygon\":"
                + "[{\"x\":0,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":3000},{\"x\":0,\"y\":3000}]},"
                + "{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":"
                + "[{\"x\":0,\"y\":3000},{\"x\":40000,\"y\":3000},{\"x\":40000,\"y\":20000},{\"x\":0,\"y\":20000}]}],\"racks\":["
                + "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":5000,\"y\":8000,\"rotation\":0}"
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        codes = jdbc.queryForList("SELECT code FROM bin WHERE warehouse_id = ? ORDER BY code", String.class, warehouseId);
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Coconut oil 1L\",\"baseUom\":\"EA\",\"barcode\":\"4791\","
                + "\"costMinor\":45000,\"trackBatch\":false,\"trackExpiry\":false,\"units\":[{\"uom\":\"CASE\",\"factor\":12,\"barcode\":\"14791\"}]}"))
                .andExpect(status().isCreated());
        send(admin, post("/api/v1/suppliers").content("{\"code\":\"S1\",\"name\":\"Lanka Oils\"}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/customers").content("{\"code\":\"C1\",\"name\":\"Kandy Super\"}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/customers").content("{\"code\":\"C2\",\"name\":\"Galle Stores\"}")).andExpect(status().isCreated());
        receiveAndPutAway(48);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    /** Through the app: a PO, a receipt scanned and closed, everything put away into the first bin. */
    private void receiveAndPutAway(int eaches) throws Exception {
        String po = read(send(admin, post("/api/v1/purchase-orders").content("{\"supplierCode\":\"S1\",\"warehouseId\":\"" + warehouse
                + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":" + eaches + "}]}")).andExpect(status().isCreated()), "$.id");
        send(admin, post("/api/v1/purchase-orders/" + po + "/approve").content("{\"version\":0}")).andExpect(status().isOk());
        String grn = read(send(admin, post("/api/v1/receipts").content("{\"poId\":\"" + po + "\"}")), "$.id");
        send(admin, post("/api/v1/receipts/" + grn + "/lines").header("Idempotency-Key", key())
                .content("{\"code\":\"4791\",\"qty\":" + eaches + "}")).andExpect(status().isOk());
        int v = read(send(admin, get("/api/v1/receipts/" + grn)), "$.version");
        send(admin, post("/api/v1/receipts/" + grn + "/close").content("{\"version\":" + v + "}")).andExpect(status().isOk());
        String productId = read(send(admin, get("/api/v1/products?q=OIL-1L")), "$.items[0].id");
        send(admin, post("/api/v1/putaway").header("Idempotency-Key", key()).content("{\"warehouseId\":\"" + warehouse
                + "\",\"productId\":\"" + productId + "\",\"qty\":" + eaches + ",\"binCode\":\"" + codes.get(0) + "\"}")).andExpect(status().isOk());
    }

    /** Order, release, pick everything, pack: returns the packed order's id. */
    private String packedOrder(String customer, int cases) throws Exception {
        String so = read(send(admin, post("/api/v1/sales-orders").content("{\"customerCode\":\"" + customer + "\",\"warehouseId\":\""
                + warehouse + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"CASE\",\"qty\":" + cases + "}]}")).andExpect(status().isCreated()), "$.id");
        String list = read(send(admin, post("/api/v1/sales-orders/" + so + "/release").content("{\"version\":0}")).andExpect(status().isOk()), "$.pick.id");
        send(admin, post("/api/v1/pick-lists/" + list + "/lines/1/confirm").header("Idempotency-Key", key())
                .content("{\"binCode\":\"" + codes.get(0) + "\",\"code\":\"14791\",\"qty\":" + cases + "}")).andExpect(status().isOk());
        int v = read(send(admin, get("/api/v1/sales-orders/" + so)), "$.version");
        send(admin, post("/api/v1/sales-orders/" + so + "/pack").content("{\"version\":" + v + ",\"cartons\":1,\"counts\":[{\"sku\":\"OIL-1L\",\"qty\":"
                + cases * 12 + "}]}")).andExpect(status().isOk());
        return so;
    }

    private ResultActions ship(Cookie who, String... orders) throws Exception {
        return send(who, post("/api/v1/shipments").content("{\"warehouseId\":\"" + warehouse + "\",\"orderIds\":[\"" + String.join("\",\"", orders)
                + "\"],\"vehicleNo\":\"WP CAB-1234\",\"driverName\":\"Sunil\"}"));
    }

    private BigDecimal at(String area) {
        List<BigDecimal> q = jdbc.queryForList("SELECT qty_on_hand FROM stock WHERE tenant_id = ? AND area = ?", BigDecimal.class, tenant, area);
        return q.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void assertLedgerBalances() {
        List<String> off = jdbc.queryForList("""
                SELECT CONCAT(s.area, ':', IFNULL(s.bin_id, 0)) FROM stock s
                WHERE s.tenant_id = ? AND s.qty_on_hand <> (
                  SELECT COALESCE(SUM(CASE WHEN (s.area = 'BIN' AND m.to_bin_id <=> s.bin_id) OR (s.area = 'RECEIVING' AND m.to_receiving)
                                             OR (s.area = 'DISPATCH' AND m.to_dispatch) THEN m.qty ELSE 0 END)
                       - SUM(CASE WHEN (s.area = 'BIN' AND m.from_bin_id <=> s.bin_id) OR (s.area = 'RECEIVING' AND m.from_receiving)
                                             OR (s.area = 'DISPATCH' AND m.from_dispatch) THEN m.qty ELSE 0 END), 0)
                  FROM stock_movement m WHERE m.tenant_id = s.tenant_id AND m.warehouse_id = s.warehouse_id
                    AND m.product_id = s.product_id AND m.batch_no <=> s.batch_no AND m.expiry_date <=> s.expiry_date)""",
                String.class, tenant);
        assertThat(off).as("stock places that differ from their movements").isEmpty();
        BigDecimal in = jdbc.queryForObject("SELECT COALESCE(SUM(qty), 0) FROM stock_movement WHERE tenant_id = ? AND type = 'RECEIVE'", BigDecimal.class, tenant);
        BigDecimal out = jdbc.queryForObject("SELECT COALESCE(SUM(qty), 0) FROM stock_movement WHERE tenant_id = ? AND type = 'SHIP'", BigDecimal.class, tenant);
        BigDecimal onHand = jdbc.queryForObject("SELECT COALESCE(SUM(qty_on_hand), 0) FROM stock WHERE tenant_id = ?", BigDecimal.class, tenant);
        assertThat(onHand).as("everything received minus everything shipped").isEqualByComparingTo(in.subtract(out));
    }

    @Test
    void wholeLoopReceivePickPackShipLeavesTheBooksExact() throws Exception {
        String first = packedOrder("C1", 2);  // 24 EA
        String second = packedOrder("C2", 1); // 12 EA
        assertThat(at("DISPATCH")).isEqualByComparingTo("36");
        send(admin, get("/api/v1/sales-orders/to-dispatch")).andExpect(jsonPath("$.total").value(2));

        String shipment = read(ship(admin, first, second).andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("SH-000001")).andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.vehicleNo").value("WP CAB-1234")), "$.id");

        assertThat(at("DISPATCH")).isZero();
        assertThat(at("BIN")).isEqualByComparingTo("12"); // 48 received, 36 shipped
        send(admin, get("/api/v1/sales-orders/" + first)).andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.shipped.number").value("SH-000001")).andExpect(jsonPath("$.shipped.driverName").value("Sunil"));
        send(admin, get("/api/v1/sales-orders/to-dispatch")).andExpect(jsonPath("$.total").value(0));
        send(admin, get("/api/v1/shipments/" + shipment)).andExpect(jsonPath("$.orders[0].customerName").value("Kandy Super"));
        send(admin, get("/api/v1/shipments")).andExpect(jsonPath("$.total").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE tenant_id = ? AND type = 'SHIP' AND from_dispatch",
                Integer.class, tenant)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE tenant_id = ? AND action = 'SHIPPED'", String.class, tenant)).hasSize(1);
        ship(admin, first).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SO_NOT_PACKED"));
        assertLedgerBalances();
    }

    @Test
    void allOrNothingAndOneOrderShipsOnlyOnceEvenAtTheSameTime() throws Exception {
        String packed = packedOrder("C1", 1);
        String draft = read(send(admin, post("/api/v1/sales-orders").content("{\"customerCode\":\"C2\",\"warehouseId\":\"" + warehouse
                + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}]}")), "$.id");
        ship(admin, packed, draft).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SO_NOT_PACKED"));
        send(admin, get("/api/v1/sales-orders/" + packed)).andExpect(jsonPath("$.status").value("PACKED"));
        assertThat(at("DISPATCH")).isEqualByComparingTo("12");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Callable<Integer> job = () -> ship(admin, packed).andReturn().getResponse().getStatus();
                results.add(pool.submit(job));
            }
            int ok = 0;
            for (Future<Integer> f : results) {
                int code = f.get();
                assertThat(code).isIn(201, 409);
                ok += code == 201 ? 1 : 0;
            }
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(at("DISPATCH")).isZero();
        assertLedgerBalances();
    }

    @Test
    void dispatchFollowsRolesWarehousesAndCompany() throws Exception {
        String packed = packedOrder("C1", 1);
        Cookie picker = login(newUser(tenant, "PICKER"));
        ship(picker, packed).andExpect(status().isForbidden());
        AppUser packerUser = newUser(tenant, "PACKER"); // packers also dispatch, but only in their warehouses
        Cookie packer = login(packerUser);
        ship(packer, packed).andExpect(status().isNotFound());
        send(admin, put("/api/v1/warehouses/" + warehouse + "/users").content("{\"users\":[\"" + packerUser.getPublicId() + "\"]}"))
                .andExpect(status().isOk());
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, post("/api/v1/shipments").content("{\"warehouseId\":\"" + warehouse + "\",\"orderIds\":[\"" + packed + "\"]}"))
                .andExpect(status().isNotFound());
        ship(packer, packed).andExpect(status().isCreated());
        send(admin, post("/api/v1/shipments").content("{\"warehouseId\":\"" + warehouse + "\",\"orderIds\":[]}")).andExpect(status().isBadRequest());
    }
    @Test
    void dashboardShowsValueLowStockTodayBinsAndKpis() throws Exception {
        String productId = read(send(admin, get("/api/v1/products?q=OIL-1L")), "$.items[0].id");
        int v = read(send(admin, get("/api/v1/products?q=OIL-1L")), "$.items[0].version");
        send(admin, put("/api/v1/products/" + productId).content("{\"name\":\"Coconut oil 1L\",\"baseUom\":\"EA\",\"barcode\":\"4791\","
                + "\"costMinor\":45000,\"reorderPoint\":30,\"trackBatch\":false,\"trackExpiry\":false,"
                + "\"units\":[{\"uom\":\"CASE\",\"factor\":12,\"barcode\":\"14791\"}],\"version\":" + v + "}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reorderPoint").value(30));
        send(admin, post("/api/v1/products").content("{\"sku\":\"RICE\",\"name\":\"Rice\",\"baseUom\":\"BAG\",\"reorderPoint\":5,"
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/products").content("{\"sku\":\"BAD\",\"name\":\"B\",\"baseUom\":\"EA\",\"reorderPoint\":-1,"
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isBadRequest());
        ship(admin, packedOrder("C1", 2)).andExpect(status().isCreated()); // 24 of the 48 leave

        send(admin, get("/api/v1/dashboard?warehouse=" + warehouse)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stockValueMinor").value(24 * 45000))
                .andExpect(jsonPath("$.productsWithoutCost").value(0))
                .andExpect(jsonPath("$.lowStock.length()").value(2))
                .andExpect(jsonPath("$.lowStock[0].sku").value("OIL-1L")).andExpect(jsonPath("$.lowStock[0].onHand").value(24))
                .andExpect(jsonPath("$.lowStock[1].sku").value("RICE")).andExpect(jsonPath("$.lowStock[1].onHand").value(0))
                .andExpect(jsonPath("$.today.receipts").value(1)).andExpect(jsonPath("$.today.unitsReceived").value(48))
                .andExpect(jsonPath("$.today.shipments").value(1)).andExpect(jsonPath("$.today.unitsShipped").value(24))
                .andExpect(jsonPath("$.today.ordersReleased").value(1)).andExpect(jsonPath("$.today.ordersShipped").value(1))
                .andExpect(jsonPath("$.bins").value(6)).andExpect(jsonPath("$.binsInUse").value(1)).andExpect(jsonPath("$.binUsePercent").value(17))
                .andExpect(jsonPath("$.dockToStockMinutes").value(0))
                .andExpect(jsonPath("$.linesPerHour").value(4.0)); // one pick line in the minimum 15-minute window

        Cookie receiver = login(newUser(tenant, "RECEIVER"));
        send(receiver, get("/api/v1/dashboard?warehouse=" + warehouse)).andExpect(status().isForbidden());
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/dashboard?warehouse=" + warehouse)).andExpect(status().isNotFound());
    }
}

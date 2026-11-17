package lk.wms.outbound;

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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lk.wms.ApiTest;
import lk.wms.auth.domain.AppUser;
import lk.wms.common.errors.ApiException;
import lk.wms.common.tenant.TenantContext;
import lk.wms.inventory.service.StockMovementService;
import lk.wms.inventory.service.StockMovementService.Kind;
import lk.wms.inventory.service.StockMovementService.Move;
import lk.wms.inventory.service.StockMovementService.Place;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** v1.0 task 2: releasing a sales order reserves its stock, FIFO by receive date, all or nothing (SEC-26). */
class ReservationTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    long tenant;
    AppUser adminUser;
    Cookie admin;
    String warehouse;
    long warehouseId;
    long oil;
    List<Long> bins;

    @BeforeEach
    void warehouseWithStock() throws Exception {
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
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Oil\",\"baseUom\":\"EA\",\"trackBatch\":false,"
                + "\"trackExpiry\":false,\"units\":[{\"uom\":\"CASE\",\"factor\":12}]}")).andExpect(status().isCreated());
        oil = jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = 'OIL-1L'", Long.class, tenant);
        send(admin, post("/api/v1/customers").content("{\"code\":\"C1\",\"name\":\"Kandy Super\"}")).andExpect(status().isCreated());
    }

    private void move(Kind t, Place from, Place to, String qty) {
        TenantContext.callAs(tenant, () -> {
            moves.move(new Move(t, warehouseId, oil, null, null, from, to, new BigDecimal(qty), null, null, null, adminUser.getId()));
            return null;
        });
    }

    /** Puts eaches into a bin and dates that place's first arrival, so FIFO order is known. */
    private void stockIn(long bin, int eaches, String receivedAt) {
        move(Kind.RECEIVE, null, Place.RECEIVING, Integer.toString(eaches));
        move(Kind.PUTAWAY, Place.RECEIVING, Place.bin(bin), Integer.toString(eaches));
        jdbc.update("UPDATE stock SET received_at = ? WHERE bin_id = ? AND product_id = ?", receivedAt, bin, oil);
    }

    /** A lot with an expiry date (null = none) put into a bin, its arrival dated. */
    private void lotIn(long bin, int eaches, String receivedAt, java.time.LocalDate expiry) {
        TenantContext.callAs(tenant, () -> {
            BigDecimal q = BigDecimal.valueOf(eaches);
            moves.move(new Move(Kind.RECEIVE, warehouseId, oil, null, expiry, null, Place.RECEIVING, q, null, null, null, adminUser.getId()));
            moves.move(new Move(Kind.PUTAWAY, warehouseId, oil, null, expiry, Place.RECEIVING, Place.bin(bin), q, null, null, null, adminUser.getId()));
            return null;
        });
        jdbc.update("UPDATE stock SET received_at = ? WHERE bin_id = ? AND product_id = ? AND expiry_date <=> ?", receivedAt, bin, oil, expiry);
    }

    private String order(String uom, int qty) throws Exception {
        return read(send(admin, post("/api/v1/sales-orders").content("{\"customerCode\":\"C1\",\"warehouseId\":\"" + warehouse
                + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"" + uom + "\",\"qty\":" + qty + "}]}")).andExpect(status().isCreated()), "$.id");
    }

    private BigDecimal reserved() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(qty_reserved), 0) FROM stock WHERE tenant_id = ?", BigDecimal.class, tenant);
    }

    @Test
    void releaseReservesOldestFirstAllOrNothingAndCancelFreesIt() throws Exception {
        stockIn(bins.get(0), 5, "2026-11-10 10:00:00");
        stockIn(bins.get(1), 5, "2026-11-01 10:00:00"); // older: taken first
        move(Kind.RECEIVE, null, Place.RECEIVING, "50");  // still in receiving: never reserved
        String first = order("EA", 7);

        send(admin, post("/api/v1/sales-orders/" + first + "/release").content("{\"version\":0}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.reserved.length()").value(2))
                .andExpect(jsonPath("$.reserved[0].binCode").value(binCode(1))).andExpect(jsonPath("$.reserved[0].qty").value(5))
                .andExpect(jsonPath("$.reserved[1].binCode").value(binCode(0))).andExpect(jsonPath("$.reserved[1].qty").value(2));
        assertThat(reserved()).isEqualByComparingTo("7");
        send(admin, post("/api/v1/sales-orders/" + first + "/release").content("{\"version\":1}"))
                .andExpect(jsonPath("$.code").value("SO_NOT_DRAFT"));
        send(admin, put("/api/v1/sales-orders/" + first).content("{\"customerCode\":\"C1\",\"warehouseId\":\"" + warehouse
                + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}],\"version\":1}")).andExpect(jsonPath("$.code").value("SO_NOT_DRAFT"));

        String second = order("EA", 4); // only 3 left free in bins
        send(admin, post("/api/v1/sales-orders/" + second + "/release").content("{\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_ENOUGH_STOCK"))
                .andExpect(jsonPath("$.detail").value("OIL-1L (EA): need 4, 3 free in bins"));
        assertThat(reserved()).isEqualByComparingTo("7"); // nothing half-reserved
        send(admin, get("/api/v1/sales-orders/" + second)).andExpect(jsonPath("$.status").value("DRAFT"));

        // reserved units cannot be moved away
        assertThatThrownBy(() -> move(Kind.TRANSFER, Place.bin(bins.get(0)), Place.bin(bins.get(2)), "4"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Only 3 available");
        move(Kind.TRANSFER, Place.bin(bins.get(0)), Place.bin(bins.get(2)), "3");

        // a blocked bin does not count
        String binPublic = jdbc.queryForObject("SELECT public_id FROM bin WHERE id = ?", String.class, bins.get(2));
        send(admin, patch("/api/v1/bins/" + binPublic).content("{\"blocked\":true}")).andExpect(status().isOk());
        String third = order("EA", 1);
        send(admin, post("/api/v1/sales-orders/" + third + "/release").content("{\"version\":0}"))
                .andExpect(jsonPath("$.code").value("NOT_ENOUGH_STOCK"));

        send(admin, post("/api/v1/sales-orders/" + first + "/cancel").content("{\"version\":1,\"reason\":\"Customer cancelled\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.reserved.length()").value(0));
        assertThat(reserved()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE tenant_id = ?", Integer.class, tenant)).isZero();
        send(admin, post("/api/v1/sales-orders/" + second + "/release").content("{\"version\":0}")).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE tenant_id = ? AND action = 'SO_RELEASED'", String.class, tenant))
                .hasSize(2);
        send(admin, get("/api/v1/stock/products?warehouse=" + warehouse)).andExpect(jsonPath("$.items[0].reserved").value(4));
    }

    /** v1.1 task 2 (FEFO): the lot that expires first goes first, whatever arrived first; expired stock never goes. */
    @Test
    void releaseTakesEarliestExpiryFirstAndNeverExpiredStock() throws Exception {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Colombo"));
        lotIn(bins.get(0), 5, "2026-11-01 10:00:00", today.plusDays(200)); // arrived first, expires last
        lotIn(bins.get(1), 5, "2026-11-10 10:00:00", today.plusDays(20));  // arrived later, expires first
        lotIn(bins.get(2), 4, "2026-10-01 10:00:00", today.minusDays(1));  // expired: never reserved
        lotIn(bins.get(3), 3, "2026-09-01 10:00:00", null);                // no expiry: after every dated lot

        String first = order("EA", 7);
        send(admin, post("/api/v1/sales-orders/" + first + "/release").content("{\"version\":0}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reserved.length()").value(2))
                .andExpect(jsonPath("$.reserved[0].binCode").value(binCode(1))).andExpect(jsonPath("$.reserved[0].qty").value(5))
                .andExpect(jsonPath("$.reserved[1].binCode").value(binCode(0))).andExpect(jsonPath("$.reserved[1].qty").value(2));

        String second = order("EA", 7); // 3 dated + 3 undated free, 4 expired
        send(admin, post("/api/v1/sales-orders/" + second + "/release").content("{\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_ENOUGH_STOCK"))
                .andExpect(jsonPath("$.detail").value("OIL-1L (EA): need 7, 6 free in bins (4 more expired)"));
        String third = order("EA", 6);
        send(admin, post("/api/v1/sales-orders/" + third + "/release").content("{\"version\":0}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reserved[0].binCode").value(binCode(0))).andExpect(jsonPath("$.reserved[0].qty").value(3))
                .andExpect(jsonPath("$.reserved[1].binCode").value(binCode(3))).andExpect(jsonPath("$.reserved[1].qty").value(3));
        assertThat(jdbc.queryForObject("SELECT qty_reserved FROM stock WHERE bin_id = ?", BigDecimal.class, bins.get(2))).isZero();
    }

    /** v1.1 task 2: the dashboard lists lots by expiry with band counts; the 3D status gives days left per bin. */
    @Test
    void dashboardAndBinStatusShowWhatExpiresSoon() throws Exception {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Colombo"));
        lotIn(bins.get(0), 5, "2026-11-01 10:00:00", today.plusDays(200)); // beyond 90 days: not listed
        lotIn(bins.get(1), 5, "2026-11-10 10:00:00", today.plusDays(20));
        lotIn(bins.get(2), 4, "2026-10-01 10:00:00", today.minusDays(3));
        lotIn(bins.get(3), 2, "2026-10-01 10:00:00", today.plusDays(75));
        lotIn(bins.get(4), 3, "2026-09-01 10:00:00", null);

        send(admin, get("/api/v1/dashboard?warehouse=" + warehouse)).andExpect(status().isOk())
                .andExpect(jsonPath("$.expiry.expired").value(1)).andExpect(jsonPath("$.expiry.within30").value(1))
                .andExpect(jsonPath("$.expiry.within60").value(0)).andExpect(jsonPath("$.expiry.within90").value(1))
                .andExpect(jsonPath("$.expiry.lots.length()").value(3))
                .andExpect(jsonPath("$.expiry.lots[0].place").value(binCode(2))).andExpect(jsonPath("$.expiry.lots[0].daysLeft").value(-3))
                .andExpect(jsonPath("$.expiry.lots[0].sku").value("OIL-1L")).andExpect(jsonPath("$.expiry.lots[0].qty").value(4))
                .andExpect(jsonPath("$.expiry.lots[1].daysLeft").value(20)).andExpect(jsonPath("$.expiry.lots[2].daysLeft").value(75));

        String body = send(admin, get("/api/v1/warehouses/" + warehouse + "/bin-status")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> order = jdbc.queryForList("SELECT code FROM bin WHERE warehouse_id = ? ORDER BY id", String.class, warehouseId);
        List<Object> days = com.jayway.jsonpath.JsonPath.read(body, "$.expiryDays");
        assertThat(days.get(order.indexOf(binCode(0)))).isEqualTo(200);
        assertThat(days.get(order.indexOf(binCode(2)))).isEqualTo(-3);
        assertThat(days.get(order.indexOf(binCode(4)))).isNull(); // nothing dated there
        assertThat(days.get(order.indexOf(binCode(5)))).isNull(); // empty
    }

    @Test
    void ordersReleasedAtOnceNeverReserveMoreThanIsThere() throws Exception {
        stockIn(bins.get(0), 6, "2026-11-01 10:00:00");
        stockIn(bins.get(3), 4, "2026-11-02 10:00:00");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ids.add(order("EA", 3));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String id : ids) {
                Callable<Integer> job = () -> send(admin, post("/api/v1/sales-orders/" + id + "/release").content("{\"version\":0}"))
                        .andReturn().getResponse().getStatus();
                results.add(pool.submit(job));
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : results) {
                codes.add(f.get());
            }
            assertThat(codes).containsOnly(200, 409);
            assertThat(codes.stream().filter(c -> c == 200).count()).isEqualTo(3); // 3 x 3 of 10
        } finally {
            pool.shutdownNow();
        }
        assertThat(reserved()).isEqualByComparingTo("9");
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(qty), 0) FROM reservation WHERE tenant_id = ?", BigDecimal.class, tenant))
                .isEqualByComparingTo("9");
    }

    @Test
    void casesAreReservedInBaseUnitsAndOnlyManagersRelease() throws Exception {
        stockIn(bins.get(0), 30, "2026-11-01 10:00:00");
        String id = order("CASE", 2);
        Cookie receiver = login(newUser(tenant, "RECEIVER"));
        send(receiver, post("/api/v1/sales-orders/" + id + "/release").content("{\"version\":0}")).andExpect(status().isForbidden());
        send(admin, post("/api/v1/sales-orders/" + id + "/release").content("{\"version\":0}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reserved[0].qty").value(24));
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, post("/api/v1/sales-orders/" + id + "/release").content("{\"version\":1}")).andExpect(status().isNotFound());
    }

    private String binCode(int i) {
        return jdbc.queryForObject("SELECT code FROM bin WHERE id = ?", String.class, bins.get(i));
    }
}

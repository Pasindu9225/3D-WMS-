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

/** v1.1 task 3: blind cycle counts on the phone, expected read at count time, differences approved (SEC-26). */
class CountTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    long tenant;
    AppUser adminUser;
    Cookie admin;
    Cookie counter;
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
        String a = "[{\"x\":0,\"y\":0},{\"x\":20000,\"y\":0},{\"x\":20000,\"y\":20000},{\"x\":0,\"y\":20000}]";
        String b = "[{\"x\":20000,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":20000},{\"x\":20000,\"y\":20000}]";
        String poly = "[{\"x\":0,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":20000},{\"x\":0,\"y\":20000}]";
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":" + poly
                + ",\"openings\":[],\"zones\":[{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":" + a + "},"
                + "{\"code\":\"B\",\"type\":\"STORAGE\",\"name\":\"B\",\"polygon\":" + b + "}],\"racks\":["
                + "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":5000,\"y\":5000,\"rotation\":0},"
                + "{\"code\":\"R02\",\"templateId\":\"" + type + "\",\"zone\":\"B\",\"x\":25000,\"y\":5000,\"rotation\":0}"
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        bins = jdbc.queryForList("SELECT id FROM bin WHERE warehouse_id = ? ORDER BY code", Long.class, warehouseId); // A-.. then B-..
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Oil\",\"baseUom\":\"EA\",\"barcode\":\"4791\",\"costMinor\":45000,"
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[{\"uom\":\"CASE\",\"factor\":12,\"barcode\":\"14791\"}]}")).andExpect(status().isCreated());
        oil = jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = 'OIL-1L'", Long.class, tenant);
        AppUser counterUser = newUser(tenant, "INVENTORY_CONTROLLER");
        send(admin, put("/api/v1/warehouses/" + warehouse + "/users").content("{\"users\":[\"" + counterUser.getPublicId() + "\"]}"))
                .andExpect(status().isOk());
        counter = login(counterUser);
    }

    private void move(Kind t, Place from, Place to, int qty) {
        TenantContext.callAs(tenant, () -> {
            moves.move(new Move(t, warehouseId, oil, null, null, from, to, BigDecimal.valueOf(qty), null, null, null, adminUser.getId()));
            return null;
        });
    }

    private void stockIn(long bin, int qty) {
        move(Kind.RECEIVE, null, Place.RECEIVING, qty);
        move(Kind.PUTAWAY, Place.RECEIVING, Place.bin(bin), qty);
    }

    private String code(int i) {
        return jdbc.queryForObject("SELECT code FROM bin WHERE id = ?", String.class, bins.get(i));
    }

    private BigDecimal onHand(int i) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(qty_on_hand), 0) FROM stock WHERE bin_id = ?", BigDecimal.class, bins.get(i));
    }

    private String count(String plan, int seq, String bin, String code, String qty) throws Exception {
        return send(counter, post("/api/v1/count-plans/" + plan + "/lines/" + seq + "/count").content(
                "{\"binCode\":\"" + bin + "\",\"code\":\"" + code + "\",\"qty\":" + qty + "}")).andReturn().getResponse().getContentAsString();
    }

    @Test
    void zoneCountIsBlindReadsExpectedAtCountTimeAndBooksTheDifferences() throws Exception {
        stockIn(bins.get(0), 5);
        stockIn(bins.get(1), 5);
        stockIn(bins.get(2), 3);
        stockIn(bins.get(6), 9); // zone B: not in this count

        String plan = read(send(admin, post("/api/v1/count-plans").content("{\"warehouseId\":\"" + warehouse + "\",\"scope\":\"ZONE\",\"scopeValue\":\"a\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.number").value("CC-000001"))
                .andExpect(jsonPath("$.lines.length()").value(3)).andExpect(jsonPath("$.lines[0].binCode").value(code(0))), "$.id");

        String blind = send(counter, get("/api/v1/count-plans/" + plan + "/count")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3)).andReturn().getResponse().getContentAsString();
        assertThat(blind).doesNotContain("expected").doesNotContain("difference");
        send(counter, get("/api/v1/count-plans/" + plan)).andExpect(status().isForbidden()); // the review is for managers

        assertThat(count(plan, 1, code(1), "OIL-1L", "5")).contains("WRONG_BIN");
        assertThat(count(plan, 1, code(0), "RICE", "5")).contains("CODE_NOT_FOUND");
        count(plan, 1, code(0).toLowerCase(), "4791", "5");                // as expected
        move(Kind.TRANSFER, Place.bin(bins.get(1)), Place.bin(bins.get(3)), 2); // work goes on during the count
        count(plan, 2, code(1), "OIL-1L", "3");                            // 3 left there now: no false difference
        count(plan, 3, code(2), "OIL-1L", "2");                            // one missing
        assertThat(count(plan, 3, code(2), "OIL-1L", "2")).contains("\"status\":\"REVIEW\""); // a retry changes nothing
        assertThat(count(plan, 3, code(2), "OIL-1L", "1")).contains("COUNT_LINE_DONE");
        send(counter, post("/api/v1/count-plans/" + plan + "/found").content("{\"binCode\":\"" + code(4) + "\",\"code\":\"14791\",\"qty\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COUNT_NOT_OPEN")); // all counted: in review

        send(admin, get("/api/v1/count-plans/" + plan)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW")).andExpect(jsonPath("$.differences").value(1))
                .andExpect(jsonPath("$.valueMinor").value(-45000))
                .andExpect(jsonPath("$.lines[1].expected").value(3)).andExpect(jsonPath("$.lines[1].difference").value(0))
                .andExpect(jsonPath("$.lines[2].difference").value(-1));
        send(admin, post("/api/v1/count-plans/" + plan + "/approve").content("{\"version\":0}"))
                .andExpect(jsonPath("$.code").value("COUNT_CHANGED"));
        int v = read(send(admin, get("/api/v1/count-plans/" + plan)), "$.version");
        send(admin, post("/api/v1/count-plans/" + plan + "/approve").content("{\"version\":" + v + "}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(onHand(2)).isEqualByComparingTo("2");
        assertThat(onHand(1)).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE tenant_id = ? AND type = 'COUNT' AND from_bin_id = ? AND qty = 1",
                Integer.class, tenant, bins.get(2))).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE tenant_id = ? AND action LIKE 'COUNT%'", String.class, tenant))
                .contains("COUNT_CREATED", "COUNT_LINE", "COUNT_APPROVED");
    }

    @Test
    void foundGoodsRecountReservedShortagesAndAccess() throws Exception {
        stockIn(bins.get(0), 4);
        String plan = read(send(admin, post("/api/v1/count-plans").content("{\"warehouseId\":\"" + warehouse + "\",\"scope\":\"ALL\"}"))
                .andExpect(status().isCreated()), "$.id");
        // a case found in an empty bin: a new line, counted at once (12 EA, the system has 0)
        send(counter, post("/api/v1/count-plans/" + plan + "/found").content("{\"binCode\":\"" + code(4) + "\",\"code\":\"14791\",\"qty\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[1].added").value(true));
        send(counter, post("/api/v1/count-plans/" + plan + "/found").content("{\"binCode\":\"" + code(0) + "\",\"code\":\"OIL-1L\",\"qty\":1}"))
                .andExpect(jsonPath("$.code").value("COUNT_LINE_LISTED"));
        count(plan, 1, code(0), "OIL-1L", "1"); // 3 missing, but 2 of the 4 are reserved below

        TenantContext.callAs(tenant, () -> moves.reserve(warehouseId, "TEST", 1L,
                List.of(new StockMovementService.Need(1, oil, new BigDecimal("2"), "OIL-1L")), adminUser.getId()));
        int v = read(send(admin, get("/api/v1/count-plans/" + plan)), "$.version");
        send(admin, post("/api/v1/count-plans/" + plan + "/approve").content("{\"version\":" + v + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_ENOUGH_STOCK"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(code(0) + " OIL-1L: ")));
        assertThat(onHand(4)).isEqualByComparingTo("0"); // all or nothing: the found case was not booked either

        send(admin, post("/api/v1/count-plans/" + plan + "/recount").content("{\"version\":" + v + ",\"lines\":[1]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.lines[0].counted").isEmpty());
        count(plan, 1, code(0), "OIL-1L", "4");
        v = read(send(admin, get("/api/v1/count-plans/" + plan)), "$.version");
        send(admin, post("/api/v1/count-plans/" + plan + "/approve").content("{\"version\":" + v + "}")).andExpect(status().isOk());
        assertThat(onHand(4)).isEqualByComparingTo("12");

        // rules
        send(admin, post("/api/v1/count-plans").content("{\"warehouseId\":\"" + warehouse + "\",\"scope\":\"ZONE\",\"scopeValue\":\"Z\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTHING_TO_COUNT"));
        send(admin, post("/api/v1/count-plans").content("{\"warehouseId\":\"" + warehouse + "\",\"scope\":\"ABC\",\"scopeValue\":\"D\"}"))
                .andExpect(jsonPath("$.code").value("ABC_REQUIRED"));
        send(counter, post("/api/v1/count-plans").content("{\"warehouseId\":\"" + warehouse + "\",\"scope\":\"ALL\"}"))
                .andExpect(status().isForbidden());
        Cookie picker = login(newUser(tenant, "PICKER"));
        send(picker, get("/api/v1/count-plans/" + plan + "/count")).andExpect(status().isForbidden());
        Cookie unassigned = login(newUser(tenant, "INVENTORY_CONTROLLER"));
        send(unassigned, get("/api/v1/count-plans/" + plan + "/count")).andExpect(status().isNotFound());
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/count-plans/" + plan)).andExpect(status().isNotFound());
    }
}

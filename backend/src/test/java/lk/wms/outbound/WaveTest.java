package lk.wms.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/** v1.2 task 1: batch picking. Several orders in one walk, each into its own tote; stock still moves per order. */
class WaveTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    @Autowired
    lk.wms.inventory.repo.StockMovementRepository ledger;

    long tenant;
    AppUser adminUser;
    Cookie admin;
    String warehouse;
    long warehouseId;

    @BeforeEach
    void threeRacksFarApart() throws Exception {
        tenant = newTenant(3);
        adminUser = newUser(tenant, "TENANT_ADMIN");
        admin = login(adminUser);
        warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":60,\"lengthM\":20,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
        warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE public_id = ?", Long.class, warehouse);
        String type = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"SH\",\"name\":\"Shelf\",\"widthMm\":2700,"
                + "\"depthMm\":1100,\"heightMm\":3000,\"levels\":1,\"binsPerLevel\":1,\"maxWeightPerLevelG\":3000000}"))
                .andExpect(status().isCreated()), "$.id");
        String poly = "[{\"x\":0,\"y\":0},{\"x\":60000,\"y\":0},{\"x\":60000,\"y\":20000},{\"x\":0,\"y\":20000}]";
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":" + poly
                + ",\"openings\":[],\"zones\":[{\"code\":\"D\",\"type\":\"DISPATCH\",\"name\":\"Out\",\"polygon\":"
                + "[{\"x\":0,\"y\":0},{\"x\":4000,\"y\":0},{\"x\":4000,\"y\":20000},{\"x\":0,\"y\":20000}]},"
                + "{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":"
                + "[{\"x\":4000,\"y\":0},{\"x\":60000,\"y\":0},{\"x\":60000,\"y\":20000},{\"x\":4000,\"y\":20000}]}],\"racks\":["
                + "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":10000,\"y\":9000,\"rotation\":0},"
                + "{\"code\":\"R02\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":30000,\"y\":9000,\"rotation\":0},"
                + "{\"code\":\"R03\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":50000,\"y\":9000,\"rotation\":0}"
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        List<Long> bins = jdbc.queryForList("SELECT id FROM bin WHERE warehouse_id = ? ORDER BY code", Long.class, warehouseId);
        send(admin, post("/api/v1/customers").content("{\"code\":\"C1\",\"name\":\"Kandy Super\"}")).andExpect(status().isCreated());
        for (int i = 0; i < 3; i++) { // P1 in rack 1, P2 in rack 2, P3 in rack 3
            String sku = "P" + (i + 1);
            send(admin, post("/api/v1/products").content("{\"sku\":\"" + sku + "\",\"name\":\"" + sku + "\",\"baseUom\":\"EA\","
                    + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());
            long product = jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = ?", Long.class, tenant, sku);
            long bin = bins.get(i);
            TenantContext.callAs(tenant, () -> {
                moves.move(new Move(Kind.RECEIVE, warehouseId, product, null, null, null, Place.RECEIVING, new BigDecimal("100"), null, null, null, adminUser.getId()));
                moves.move(new Move(Kind.PUTAWAY, warehouseId, product, null, null, Place.RECEIVING, Place.bin(bin), new BigDecimal("100"), null, null, null, adminUser.getId()));
                return null;
            });
        }
    }

    /** An order of one of each product, released: returns its pick list id. */
    private String releasedList(String wantedBy) throws Exception {
        String so = read(send(admin, post("/api/v1/sales-orders").content("{\"customerCode\":\"C1\",\"warehouseId\":\"" + warehouse + "\""
                + (wantedBy == null ? "" : ",\"requestedDate\":\"" + wantedBy + "\"")
                + ",\"lines\":[{\"sku\":\"P1\",\"uom\":\"EA\",\"qty\":1},{\"sku\":\"P2\",\"uom\":\"EA\",\"qty\":1},{\"sku\":\"P3\",\"uom\":\"EA\",\"qty\":1}]}"))
                .andExpect(status().isCreated()), "$.id");
        return read(send(admin, post("/api/v1/sales-orders/" + so + "/release").content("{\"version\":0}")).andExpect(status().isOk()), "$.pick.id");
    }

    @Test
    void twoOrdersInOneWalkEachIntoItsToteThenDone() throws Exception {
        String a = releasedList(null);
        String b = releasedList(null);
        String wave = read(send(admin, post("/api/v1/pick-waves").content("{\"warehouseId\":\"" + warehouse + "\",\"listIds\":[\"" + a + "\",\"" + b + "\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.number").value("WV-000001"))
                .andExpect(jsonPath("$.orders.length()").value(2)).andExpect(jsonPath("$.orders[1].tote").value(2))
                .andExpect(jsonPath("$.walk.length()").value(6)), "$.id");

        // single lists in a wave are not offered on their own; the wave is
        AppUser pickerUser = newUser(tenant, "PICKER");
        send(admin, put("/api/v1/warehouses/" + warehouse + "/users").content("{\"users\":[\"" + pickerUser.getPublicId() + "\"]}")).andExpect(status().isOk());
        Cookie picker = login(pickerUser);
        send(picker, get("/api/v1/pick-lists")).andExpect(jsonPath("$.items.length()").value(0));
        send(picker, get("/api/v1/pick-waves")).andExpect(jsonPath("$.items[0].number").value("WV-000001"))
                .andExpect(jsonPath("$.items[0].orders").value(2)).andExpect(jsonPath("$.items[0].openLines").value(6));
        send(picker, post("/api/v1/pick-waves").content("{\"warehouseId\":\"" + warehouse + "\",\"listIds\":[\"" + a + "\",\"" + b + "\"]}"))
                .andExpect(status().isForbidden());

        // the combined walk: bin by bin, tote 1 then tote 2 at each bin
        String body = send(picker, get("/api/v1/pick-waves/" + wave)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> walk = JsonPath.read(body, "$.walk");
        assertThat(walk).extracting(l -> l.get("binCode") + "/" + l.get("tote"))
                .isSorted();
        assertThat(walk.get(0).get("tote")).isEqualTo(1);
        assertThat(walk.get(1).get("tote")).isEqualTo(2);

        for (Map<String, Object> l : walk) { // picked through each order's own list, as always
            send(picker, post("/api/v1/pick-lists/" + l.get("listId") + "/lines/" + l.get("seq") + "/confirm").header("Idempotency-Key", UUID.randomUUID().toString())
                    .content("{\"binCode\":\"" + l.get("binCode") + "\",\"code\":\"" + l.get("sku") + "\",\"qty\":1}")).andExpect(status().isOk());
            if (walk.indexOf(l) == 0) {
                send(picker, get("/api/v1/pick-waves/" + wave)).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
                send(admin, delete("/api/v1/pick-waves/" + wave)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WAVE_STARTED"));
            }
        }
        send(picker, get("/api/v1/pick-waves/" + wave)).andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.openLines").value(0));
        assertThat(jdbc.queryForList("SELECT status FROM sales_order WHERE tenant_id = ?", String.class, tenant)).containsOnly("PICKED");

        // v1.2 heat map: lines picked per bin, last 30 days, in scene order (each rack's bin was picked twice)
        String scene = send(admin, get("/api/v1/warehouses/" + warehouse + "/scene")).andReturn().getResponse().getContentAsString();
        List<Integer> picks = JsonPath.read(scene, "$.picks");
        assertThat(picks).containsExactly(2, 2, 2);
        String status = send(picker, get("/api/v1/warehouses/" + warehouse + "/bin-status")).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(status, "$.picks")).containsExactly(2, 2, 2);
        // only picks after the cut-off count (the ledger cannot be back-dated, so ask from a moment after the picks)
        assertThat(TenantContext.callAs(tenant, () -> ledger.picksByBin(warehouseId, java.time.Instant.now().plusSeconds(1)))).isEmpty();
        assertThat(TenantContext.callAs(tenant, () -> ledger.picksByBin(warehouseId, java.time.Instant.now().minusSeconds(3600)))).hasSize(3);

        // v2.1 real cartons: a carton size needs all three sides; the scene sends each place's cartons (98 units / 12 = 9)
        send(admin, post("/api/v1/products").content("{\"sku\":\"HALF\",\"name\":\"Half\",\"baseUom\":\"EA\",\"trackBatch\":false,"
                + "\"trackExpiry\":false,\"units\":[],\"cartonLengthMm\":400}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CARTON_SIZE"));
        send(admin, post("/api/v1/products").content("{\"sku\":\"BOX\",\"name\":\"Box\",\"baseUom\":\"EA\",\"trackBatch\":false,"
                + "\"trackExpiry\":false,\"units\":[{\"uom\":\"CASE\",\"factor\":6}],\"cartonLengthMm\":400,\"cartonWidthMm\":300,\"cartonHeightMm\":250}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.cartonHeightMm").value(250));
        jdbc.update("UPDATE product SET carton_length_mm = 600, carton_width_mm = 400, carton_height_mm = 300, units_per_carton = 12 WHERE tenant_id = ? AND sku = 'P1'", tenant);
        String withCartons = send(admin, get("/api/v1/warehouses/" + warehouse + "/scene")).andReturn().getResponse().getContentAsString();
        List<List<Integer>> cartons = JsonPath.read(withCartons, "$.cartons");
        assertThat(cartons.get(0)).containsExactly(600, 400, 300, 9, 0); // 0: no stack limit
        assertThat(cartons.get(1)).isNull(); // P2 has no carton size: the simple stack
        String status2 = send(picker, get("/api/v1/warehouses/" + warehouse + "/bin-status")).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(status2, "$.cartons[0]")).containsExactly(600, 400, 300, 9, 0); // 0: no stack limit

        String c = releasedList(null);
        send(admin, post("/api/v1/pick-waves").content("{\"warehouseId\":\"" + warehouse + "\",\"listIds\":[\"" + a + "\",\"" + c + "\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LIST_NOT_FREE"));
        send(admin, post("/api/v1/pick-waves").content("{\"warehouseId\":\"" + warehouse + "\",\"listIds\":[\"" + c + "\"]}"))
                .andExpect(status().isBadRequest());
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/pick-waves/" + wave)).andExpect(status().isNotFound());
    }

    /** SPEC exit criterion: batch picking of 10 orders shortens the route versus single-order picking. */
    @Test
    void tenOrdersByCutOffWalkFarLessThanOneByOne() throws Exception {
        LocalDate today = LocalDate.now();
        List<String> early = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            early.add(releasedList(today.plusDays(1).toString()));
        }
        String late = releasedList(today.plusDays(9).toString()); // wanted later: not in the wave

        String wave = read(send(admin, post("/api/v1/pick-waves/by-cut-off").content("{\"warehouseId\":\"" + warehouse + "\",\"cutOff\":\""
                + today.plusDays(2) + "\",\"maxOrders\":10}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders.length()").value(10)).andExpect(jsonPath("$.walk.length()").value(30)), "$.id");
        String body = send(admin, get("/api/v1/pick-waves/" + wave)).andReturn().getResponse().getContentAsString();
        List<String> inWave = JsonPath.read(body, "$.orders[*].listId");
        assertThat(inWave).containsExactlyElementsOf(early.subList(0, 10)).doesNotContain(late); // oldest first, max 10
        double together = ((Number) JsonPath.read(body, "$.walkMetres")).doubleValue();
        double oneByOne = ((Number) JsonPath.read(body, "$.singleWalksMetres")).doubleValue();
        assertThat(together).isPositive();
        assertThat(oneByOne).isGreaterThan(together * 9); // the same three racks, once instead of ten times

        send(admin, delete("/api/v1/pick-waves/" + wave)).andExpect(status().isNoContent()); // nothing picked yet: dissolve
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pick_list WHERE tenant_id = ? AND wave_id IS NOT NULL", Integer.class, tenant)).isZero();
        send(admin, post("/api/v1/pick-waves/by-cut-off").content("{\"warehouseId\":\"" + warehouse + "\",\"cutOff\":\"" + today.minusDays(30)
                + "\",\"maxOrders\":10}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WAVE_TOO_SMALL"));
    }
}

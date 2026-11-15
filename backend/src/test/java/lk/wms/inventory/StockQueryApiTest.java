package lk.wms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** v0.4 task 8: stock by place and product, the movement history, and 3D fill levels. */
class StockQueryApiTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    long tenant;
    AppUser adminUser;
    Cookie admin;
    String warehouse;
    long warehouseId;
    String oil;
    long oilId;
    String binA;
    long binAId;

    @BeforeEach
    void stock() throws Exception {
        tenant = newTenant(3);
        adminUser = newUser(tenant, "TENANT_ADMIN");
        admin = login(adminUser);
        warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
        warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE public_id = ?", Long.class, warehouse);
        // 2 levels x 3 bins, 300 kg per level: 100 kg per bin
        String type = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"SH\",\"name\":\"Shelf\",\"widthMm\":2700,"
                + "\"depthMm\":1100,\"heightMm\":3000,\"levels\":2,\"binsPerLevel\":3,\"maxWeightPerLevelG\":300000}")), "$.id");
        String poly = "[{\"x\":0,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":20000},{\"x\":0,\"y\":20000}]";
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":" + poly
                + ",\"openings\":[],\"zones\":[{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":" + poly + "}],\"racks\":["
                + "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":9000,\"y\":10000,\"rotation\":0}"
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        binA = jdbc.queryForObject("SELECT code FROM bin WHERE warehouse_id = ? ORDER BY code LIMIT 1", String.class, warehouseId);
        binAId = jdbc.queryForObject("SELECT id FROM bin WHERE warehouse_id = ? AND code = ?", Long.class, warehouseId, binA);
        oil = read(send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Coconut oil\",\"baseUom\":\"EA\","
                + "\"weightG\":950,\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated()), "$.id");
        oilId = jdbc.queryForObject("SELECT id FROM product WHERE public_id = ?", Long.class, oil);
        move(Kind.RECEIVE, null, Place.RECEIVING, "24");
        move(Kind.PUTAWAY, Place.RECEIVING, Place.bin(binAId), "10");
    }

    private void move(Kind k, Place from, Place to, String qty) {
        TenantContext.callAs(tenant, () -> moves.move(new Move(k, warehouseId, oilId, null, null, from, to, new BigDecimal(qty),
                null, null, null, adminUser.getId())));
    }

    @Test
    void stockByPlaceAndByProduct() throws Exception {
        String base = "/api/v1/stock?warehouse=" + warehouse;
        send(admin, get(base)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        send(admin, get(base + "&receiving=true")).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].binCode").value("RECEIVING")).andExpect(jsonPath("$.items[0].qtyOnHand").value(14));
        String binPublic = jdbc.queryForObject("SELECT public_id FROM bin WHERE id = ?", String.class, binAId);
        send(admin, get(base + "&bin=" + binPublic)).andExpect(jsonPath("$.items[0].binCode").value(binA))
                .andExpect(jsonPath("$.items[0].sku").value("OIL-1L")).andExpect(jsonPath("$.items[0].qtyOnHand").value(10));
        send(admin, get(base + "&binCode=" + binA.substring(0, 3))).andExpect(jsonPath("$.total").value(1));
        send(admin, get(base + "&product=" + oil)).andExpect(jsonPath("$.total").value(2));

        send(admin, get("/api/v1/stock/products?warehouse=" + warehouse)).andExpect(jsonPath("$.items[0].onHand").value(24))
                .andExpect(jsonPath("$.items[0].inReceiving").value(14)).andExpect(jsonPath("$.items[0].bins").value(1));
        send(admin, get("/api/v1/stock/products?warehouse=" + warehouse + "&q=coconut")).andExpect(jsonPath("$.total").value(1));
        send(admin, get("/api/v1/stock/products?warehouse=" + warehouse + "&q=zzz")).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void movementHistoryNewestFirstWithFilters() throws Exception {
        String base = "/api/v1/stock-movements?warehouse=" + warehouse;
        send(admin, get(base)).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[0].type").value("PUTAWAY"))
                .andExpect(jsonPath("$.items[0].from").value("RECEIVING")).andExpect(jsonPath("$.items[0].to").value(binA))
                .andExpect(jsonPath("$.items[0].user").value("User TENANT_ADMIN")).andExpect(jsonPath("$.items[1].type").value("RECEIVE"));
        send(admin, get(base + "&type=RECEIVE")).andExpect(jsonPath("$.total").value(1));
        String binPublic = jdbc.queryForObject("SELECT public_id FROM bin WHERE id = ?", String.class, binAId);
        send(admin, get(base + "&bin=" + binPublic)).andExpect(jsonPath("$.total").value(1));
        send(admin, get(base + "&from=" + LocalDate.now().plusDays(2))).andExpect(jsonPath("$.total").value(0));
        send(admin, get(base + "&to=" + LocalDate.now().plusDays(1))).andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void theThreeDViewShowsHowFullEachBinIs() throws Exception {
        List<String> codes = read(send(admin, get("/api/v1/warehouses/" + warehouse + "/scene")), "$.bins.codes");
        int i = codes.indexOf(binA);
        List<Integer> fill = read(send(admin, get("/api/v1/warehouses/" + warehouse + "/bin-status")), "$.fill");
        assertThat(fill.get(i)).isEqualTo(10); // 10 x 950 g of 100 kg
        assertThat(fill.stream().filter(f -> f > 0).count()).isEqualTo(1);
        List<Integer> sceneFill = read(send(admin, get("/api/v1/warehouses/" + warehouse + "/scene")), "$.fill");
        assertThat(sceneFill).isEqualTo(fill);
    }

    @Test
    void onlyPeopleWhoMaySeeTheWarehouse() throws Exception {
        Cookie picker = login(newUser(tenant, "PACKER")); // packers cannot read stock
        send(picker, get("/api/v1/stock?warehouse=" + warehouse)).andExpect(status().isForbidden());
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/stock?warehouse=" + warehouse)).andExpect(status().isNotFound());
        send(other, get("/api/v1/stock-movements?warehouse=" + warehouse)).andExpect(status().isNotFound());
        send(other, get("/api/v1/stock/products?warehouse=" + warehouse)).andExpect(status().isNotFound());
    }
}

package lk.wms.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.beans.factory.annotation.Autowired;

/** One warehouse with one rack of six bins, one product (OIL-1L, cost 450.00) and a counter assigned to it. */
abstract class StockFixture extends ApiTest {

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
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":20,\"lengthM\":20,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
        warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE public_id = ?", Long.class, warehouse);
        String type = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"SH\",\"name\":\"Shelf\",\"widthMm\":2700,"
                + "\"depthMm\":1100,\"heightMm\":3000,\"levels\":2,\"binsPerLevel\":3,\"maxWeightPerLevelG\":3000000}"))
                .andExpect(status().isCreated()), "$.id");
        String poly = "[{\"x\":0,\"y\":0},{\"x\":20000,\"y\":0},{\"x\":20000,\"y\":20000},{\"x\":0,\"y\":20000}]";
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":" + poly
                + ",\"openings\":[],\"zones\":[{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":" + poly + "}],\"racks\":["
                + "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":5000,\"y\":5000,\"rotation\":0}"
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        bins = jdbc.queryForList("SELECT id FROM bin WHERE warehouse_id = ? ORDER BY code", Long.class, warehouseId);
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Oil\",\"baseUom\":\"EA\",\"costMinor\":45000,"
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());
        oil = jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = 'OIL-1L'", Long.class, tenant);
        AppUser counterUser = newUser(tenant, "INVENTORY_CONTROLLER");
        send(admin, put("/api/v1/warehouses/" + warehouse + "/users").content("{\"users\":[\"" + counterUser.getPublicId() + "\"]}"))
                .andExpect(status().isOk());
        counter = login(counterUser);
    }

    void stockIn(int bin, int qty) {
        TenantContext.callAs(tenant, () -> {
            moves.move(new Move(Kind.RECEIVE, warehouseId, oil, null, null, null, Place.RECEIVING, BigDecimal.valueOf(qty), null, null, null, adminUser.getId()));
            moves.move(new Move(Kind.PUTAWAY, warehouseId, oil, null, null, Place.RECEIVING, Place.bin(bins.get(bin)), BigDecimal.valueOf(qty),
                    null, null, null, adminUser.getId()));
            return null;
        });
    }

    String code(int i) {
        return jdbc.queryForObject("SELECT code FROM bin WHERE id = ?", String.class, bins.get(i));
    }

    BigDecimal onHand(int i) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(qty_on_hand), 0) FROM stock WHERE bin_id = ?", BigDecimal.class, bins.get(i));
    }

}

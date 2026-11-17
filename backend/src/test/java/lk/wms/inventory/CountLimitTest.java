package lk.wms.inventory;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Security review v1.1 F1: a count bigger than the limit is refused without loading every stock row first. */
@TestPropertySource(properties = "wms.count.max-lines=2")
class CountLimitTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    @Test
    void aCountLargerThanTheLimitIsRefused() throws Exception {
        long tenant = newTenant(3);
        AppUser adminUser = newUser(tenant, "TENANT_ADMIN");
        Cookie admin = login(adminUser);
        String warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
        long warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE public_id = ?", Long.class, warehouse);
        String type = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"SH\",\"name\":\"Shelf\",\"widthMm\":2700,"
                + "\"depthMm\":1100,\"heightMm\":3000,\"levels\":2,\"binsPerLevel\":3,\"maxWeightPerLevelG\":3000000}"))
                .andExpect(status().isCreated()), "$.id");
        String poly = "[{\"x\":0,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":20000},{\"x\":0,\"y\":20000}]";
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":" + poly
                + ",\"openings\":[],\"zones\":[{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":" + poly + "}],\"racks\":["
                + "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":5000,\"y\":5000,\"rotation\":0}"
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        List<Long> bins = jdbc.queryForList("SELECT id FROM bin WHERE warehouse_id = ? ORDER BY code", Long.class, warehouseId);
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Oil\",\"baseUom\":\"EA\",\"trackBatch\":false,"
                + "\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());
        long oil = jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = 'OIL-1L'", Long.class, tenant);
        for (int i = 0; i < 3; i++) {
            long bin = bins.get(i);
            TenantContext.callAs(tenant, () -> {
                moves.move(new Move(Kind.RECEIVE, warehouseId, oil, null, null, null, Place.RECEIVING, BigDecimal.ONE, null, null, null, adminUser.getId()));
                moves.move(new Move(Kind.PUTAWAY, warehouseId, oil, null, null, Place.RECEIVING, Place.bin(bin), BigDecimal.ONE, null, null, null, adminUser.getId()));
                return null;
            });
        }
        send(admin, post("/api/v1/count-plans").content("{\"warehouseId\":\"" + warehouse + "\",\"scope\":\"ALL\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COUNT_TOO_LARGE"));
        TenantContext.callAs(tenant, () -> { // one place emptied into another: 2 places left, within the limit
            moves.move(new Move(Kind.TRANSFER, warehouseId, oil, null, null, Place.bin(bins.get(2)), Place.bin(bins.get(0)), BigDecimal.ONE,
                    null, null, null, adminUser.getId()));
            return null;
        });
        send(admin, post("/api/v1/count-plans").content("{\"warehouseId\":\"" + warehouse + "\",\"scope\":\"ALL\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.lines.length()").value(2));
    }
}

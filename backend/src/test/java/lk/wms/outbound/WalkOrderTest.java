package lk.wms.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import lk.wms.layout.service.BinService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** v1.2 task 2: pick lines follow the floor (nearest rack first from dispatch), not the rack codes; the route is shown. */
class WalkOrderTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    @Autowired
    BinService bins;

    @Test
    void linesFollowTheFloorNotTheCodesAndTheRouteShowsIt() throws Exception {
        long tenant = newTenant(3);
        AppUser adminUser = newUser(tenant, "TENANT_ADMIN");
        Cookie admin = login(adminUser);
        String warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":60,\"lengthM\":20,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
        long warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE public_id = ?", Long.class, warehouse);
        String type = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"SH\",\"name\":\"Shelf\",\"widthMm\":2700,"
                + "\"depthMm\":1100,\"heightMm\":3000,\"levels\":1,\"binsPerLevel\":1,\"maxWeightPerLevelG\":3000000}"))
                .andExpect(status().isCreated()), "$.id");
        String poly = "[{\"x\":0,\"y\":0},{\"x\":60000,\"y\":0},{\"x\":60000,\"y\":20000},{\"x\":0,\"y\":20000}]";
        // codes against the floor: R01 is the farthest from dispatch, R02 the nearest, R03 in the middle
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":" + poly
                + ",\"openings\":[],\"zones\":[{\"code\":\"D\",\"type\":\"DISPATCH\",\"name\":\"Out\",\"polygon\":"
                + "[{\"x\":0,\"y\":0},{\"x\":4000,\"y\":0},{\"x\":4000,\"y\":20000},{\"x\":0,\"y\":20000}]},"
                + "{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":"
                + "[{\"x\":4000,\"y\":0},{\"x\":60000,\"y\":0},{\"x\":60000,\"y\":20000},{\"x\":4000,\"y\":20000}]}],\"racks\":["
                + "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":50000,\"y\":9000,\"rotation\":0},"
                + "{\"code\":\"R02\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":10000,\"y\":9000,\"rotation\":0},"
                + "{\"code\":\"R03\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":30000,\"y\":9000,\"rotation\":0}"
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        List<Long> byCode = jdbc.queryForList("SELECT id FROM bin WHERE warehouse_id = ? ORDER BY code", Long.class, warehouseId);
        List<String> codes = jdbc.queryForList("SELECT code FROM bin WHERE warehouse_id = ? ORDER BY code", String.class, warehouseId);
        send(admin, post("/api/v1/customers").content("{\"code\":\"C1\",\"name\":\"Kandy Super\"}")).andExpect(status().isCreated());
        for (int i = 0; i < 3; i++) { // P1 in R01 (far), P2 in R02 (near), P3 in R03 (middle)
            String sku = "P" + (i + 1);
            send(admin, post("/api/v1/products").content("{\"sku\":\"" + sku + "\",\"name\":\"" + sku + "\",\"baseUom\":\"EA\","
                    + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());
            long product = jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = ?", Long.class, tenant, sku);
            long bin = byCode.get(i);
            TenantContext.callAs(tenant, () -> {
                moves.move(new Move(Kind.RECEIVE, warehouseId, product, null, null, null, Place.RECEIVING, BigDecimal.TEN, null, null, null, adminUser.getId()));
                moves.move(new Move(Kind.PUTAWAY, warehouseId, product, null, null, Place.RECEIVING, Place.bin(bin), BigDecimal.TEN, null, null, null, adminUser.getId()));
                return null;
            });
        }
        String so = read(send(admin, post("/api/v1/sales-orders").content("{\"customerCode\":\"C1\",\"warehouseId\":\"" + warehouse + "\","
                + "\"lines\":[{\"sku\":\"P1\",\"uom\":\"EA\",\"qty\":1},{\"sku\":\"P2\",\"uom\":\"EA\",\"qty\":1},{\"sku\":\"P3\",\"uom\":\"EA\",\"qty\":1}]}"))
                .andExpect(status().isCreated()), "$.id");
        String list = read(send(admin, post("/api/v1/sales-orders/" + so + "/release").content("{\"version\":0}")).andExpect(status().isOk()), "$.pick.id");

        // near (R02) -> middle (R03) -> far (R01), whatever the codes say
        String body = send(admin, get("/api/v1/pick-lists/" + list)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> walked = JsonPath.read(body, "$.lines[*].binCode");
        assertThat(walked).containsExactly(codes.get(1), codes.get(2), codes.get(0));

        double floorOrder = TenantContext.callAs(tenant, () -> bins.walkMetres(warehouseId, List.of(byCode.get(1), byCode.get(2), byCode.get(0))));
        double codeOrder = TenantContext.callAs(tenant, () -> bins.walkMetres(warehouseId, byCode));
        assertThat(floorOrder).isLessThan(codeOrder);

        send(admin, get("/api/v1/pick-lists/" + list + "/route")).andExpect(status().isOk())
                .andExpect(jsonPath("$.warehouseId").value(warehouse))
                .andExpect(jsonPath("$.startX").value(2000)).andExpect(jsonPath("$.startY").value(10000))
                .andExpect(jsonPath("$.stops.length()").value(3))
                .andExpect(jsonPath("$.stops[0].step").value(1)).andExpect(jsonPath("$.stops[0].x").value(10000))
                .andExpect(jsonPath("$.stops[0].binCodes[0]").value(codes.get(1)))
                .andExpect(jsonPath("$.stops[2].x").value(50000))
                .andExpect(jsonPath("$.metres").value(floorOrder));

        Cookie unassigned = login(newUser(tenant, "PICKER"));
        send(unassigned, get("/api/v1/pick-lists/" + list + "/route")).andExpect(status().isNotFound());
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/pick-lists/" + list + "/route")).andExpect(status().isNotFound());
        Cookie receiver = login(newUser(tenant, "RECEIVER"));
        send(receiver, get("/api/v1/pick-lists/" + list + "/route")).andExpect(status().isForbidden());
    }
}

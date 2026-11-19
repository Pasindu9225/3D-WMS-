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

/**
 * v2.1, 3PL stock owners: clients with allowed HS sections, products owned by a client, one owner per order,
 * client putaway rules and one client per place. Zones: R receiving, A and C storage, one shelf rack in each.
 */
class StockOwnersApiTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    long tenant;
    AppUser adminUser;
    Cookie admin;
    String warehouse;
    long warehouseId;

    @BeforeEach
    void warehouseAndTwoClients() throws Exception {
        tenant = newTenant(3);
        adminUser = newUser(tenant, "TENANT_ADMIN");
        admin = login(adminUser);
        warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
        warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE public_id = ?", Long.class, warehouse);
        String shelf = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"SH\",\"name\":\"Shelf\",\"widthMm\":2700,"
                + "\"depthMm\":1100,\"heightMm\":3000,\"levels\":2,\"binsPerLevel\":3,\"maxWeightPerLevelG\":30000}")), "$.id");
        String zones = zone("R", "RECEIVING", 0, 5000) + "," + zone("A", "STORAGE", 5000, 20000) + "," + zone("C", "STORAGE", 20000, 40000);
        String racks = rack("A01", shelf, "A", 9000) + "," + rack("C01", shelf, "C", 30000);
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":"
                + poly(0, 0, 40000, 20000) + ",\"openings\":[],\"zones\":[" + zones + "],\"racks\":[" + racks
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());

        send(admin, post("/api/v1/clients").content("{\"code\":\"FOOD\",\"name\":\"Ceylon Foods\",\"allowedHsSections\":[4,1,2,3]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("CLIENT"))
                .andExpect(jsonPath("$.allowedHsSections").value(org.hamcrest.Matchers.contains(1, 2, 3, 4)));
        send(admin, post("/api/v1/clients").content("{\"code\":\"HOME\",\"name\":\"Lanka Home Care\"}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/suppliers").content("{\"code\":\"SUP\",\"name\":\"Supplier\"}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/customers").content("{\"code\":\"CUS\",\"name\":\"Customer\",\"address\":\"Kandy\"}")).andExpect(status().isCreated());
    }

    private static String poly(int x1, int y1, int x2, int y2) {
        return "[{\"x\":" + x1 + ",\"y\":" + y1 + "},{\"x\":" + x2 + ",\"y\":" + y1 + "},{\"x\":" + x2 + ",\"y\":" + y2
                + "},{\"x\":" + x1 + ",\"y\":" + y2 + "}]";
    }

    private static String zone(String code, String type, int x1, int x2) {
        return "{\"code\":\"" + code + "\",\"type\":\"" + type + "\",\"name\":\"" + code + "\",\"polygon\":" + poly(x1, 0, x2, 20000) + "}";
    }

    private static String rack(String code, String type, String zone, int x) {
        return "{\"code\":\"" + code + "\",\"templateId\":\"" + type + "\",\"zone\":\"" + zone + "\",\"x\":" + x + ",\"y\":10000,\"rotation\":0}";
    }

    private org.springframework.test.web.servlet.ResultActions product(String sku, String owner, String hs) throws Exception {
        return send(admin, post("/api/v1/products").content("{\"sku\":\"" + sku + "\",\"name\":\"" + sku + "\",\"baseUom\":\"EA\","
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[],\"ownerCode\":\"" + owner + "\""
                + (hs == null ? "" : ",\"handling\":{\"hsCode\":\"" + hs + "\"}") + "}"));
    }

    private void receive(String productId) {
        long pid = jdbc.queryForObject("SELECT id FROM product WHERE public_id = ?", Long.class, productId);
        TenantContext.callAs(tenant, () -> moves.move(new Move(Kind.RECEIVE, warehouseId, pid, null, null, null, Place.RECEIVING,
                new BigDecimal("5"), null, null, null, adminUser.getId())));
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String productId, String bin) throws Exception {
        return send(admin, post("/api/v1/putaway").header("Idempotency-Key", UUID.randomUUID().toString()).content("{\"warehouseId\":\""
                + warehouse + "\",\"productId\":\"" + productId + "\",\"qty\":1,\"binCode\":\"" + bin + "\"}"));
    }

    @Test
    void clientsLimitTheirCommoditiesAndOrdersAreForOneClient() throws Exception {
        product("BLEACH", "FOOD", "282890").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMODITY_NOT_ALLOWED"));
        product("NOHS", "FOOD", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMODITY_NOT_ALLOWED"));
        product("TEA", "FOOD", "090230").andExpect(status().isCreated()).andExpect(jsonPath("$.ownerCode").value("FOOD"))
                .andExpect(jsonPath("$.ownerName").value("Ceylon Foods"));
        product("SOAP", "HOME", null).andExpect(status().isCreated()); // HOME takes any commodity
        product("X", "NOPE", null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
        send(admin, post("/api/v1/products").content("{\"sku\":\"OWN\",\"name\":\"Own\",\"baseUom\":\"EA\",\"trackBatch\":false,"
                + "\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());

        send(admin, get("/api/v1/products?owner=FOOD")).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].sku").value("TEA"));
        send(admin, get("/api/v1/products?owner=-")).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].sku").value("OWN"));
        send(admin, get("/api/v1/products")).andExpect(jsonPath("$.total").value(3));

        send(admin, post("/api/v1/purchase-orders").content("{\"supplierCode\":\"SUP\",\"warehouseId\":\"" + warehouse + "\",\"lines\":["
                + "{\"sku\":\"TEA\",\"uom\":\"EA\",\"qty\":1},{\"sku\":\"SOAP\",\"uom\":\"EA\",\"qty\":1}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MIXED_OWNERS"));
        send(admin, post("/api/v1/purchase-orders").content("{\"supplierCode\":\"SUP\",\"warehouseId\":\"" + warehouse + "\",\"lines\":["
                + "{\"sku\":\"TEA\",\"uom\":\"EA\",\"qty\":1}]}")).andExpect(status().isCreated()).andExpect(jsonPath("$.ownerCode").value("FOOD"));
        send(admin, post("/api/v1/sales-orders").content("{\"customerCode\":\"CUS\",\"warehouseId\":\"" + warehouse + "\",\"lines\":["
                + "{\"sku\":\"SOAP\",\"uom\":\"EA\",\"qty\":1}]}")).andExpect(status().isCreated()).andExpect(jsonPath("$.ownerCode").value("HOME"));
        send(admin, get("/api/v1/sales-orders")).andExpect(jsonPath("$.items[0].ownerCode").value("HOME"));

        String food = read(send(admin, get("/api/v1/clients?q=FOOD")), "$.items[0].id");
        send(admin, post("/api/v1/clients/" + food + "/archive")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_IN_USE")); // TEA still belongs to it

        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/clients")).andExpect(jsonPath("$.total").value(0));
        send(other, post("/api/v1/products").content("{\"sku\":\"T\",\"name\":\"T\",\"baseUom\":\"EA\",\"trackBatch\":false,"
                + "\"trackExpiry\":false,\"units\":[],\"ownerCode\":\"FOOD\"}")).andExpect(status().isNotFound());
    }

    @Test
    void putawayFollowsClientRulesAndKeepsClientsApart() throws Exception {
        String tea = read(product("TEA", "FOOD", "090230").andExpect(status().isCreated()), "$.id");
        String soap = read(product("SOAP", "HOME", null).andExpect(status().isCreated()), "$.id");
        receive(tea);
        receive(soap);
        send(admin, post("/api/v1/putaway-rules").content("{\"warehouseId\":\"" + warehouse + "\",\"priority\":10,\"clientCode\":\"HOME\","
                + "\"zoneCode\":\"C\",\"onlyZone\":true}")).andExpect(status().isCreated()).andExpect(jsonPath("$.appliesTo").value("CLIENT"))
                .andExpect(jsonPath("$.clientCode").value("HOME"));
        @SuppressWarnings("unchecked")
        List<String> soapPlaces = (List<String>) read(send(admin, post("/api/v1/putaway/suggestions").content("{\"warehouseId\":\""
                + warehouse + "\",\"productId\":\"" + soap + "\",\"qty\":1}")), "$[*].code");
        assertThat(soapPlaces).isNotEmpty().allMatch(c -> c.startsWith("C-"));

        confirm(tea, "C-C01-01-01").andExpect(status().isOk()); // no rule for FOOD: any storage place
        confirm(soap, "C-C01-01-01").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OWNER_MIXED"));
        @SuppressWarnings("unchecked")
        List<String> again = (List<String>) read(send(admin, post("/api/v1/putaway/suggestions").content("{\"warehouseId\":\""
                + warehouse + "\",\"productId\":\"" + soap + "\",\"qty\":1}")), "$[*].code");
        assertThat(again).doesNotContain("C-C01-01-01");
        confirm(soap, "C-C01-01-02").andExpect(status().isOk());

        send(admin, get("/api/v1/stock/products?warehouse=" + warehouse + "&owner=FOOD")).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sku").value("TEA"));
        send(admin, get("/api/v1/stock/products?warehouse=" + warehouse + "&owner=-")).andExpect(jsonPath("$.total").value(0));
    }
}

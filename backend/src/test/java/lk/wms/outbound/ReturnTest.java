package lk.wms.outbound;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** v1.1 tasks 5 and 6: customer returns (restock / quarantine / scrap) and stock in quarantine never picked. */
class ReturnTest extends ApiTest {

    long tenant;
    AppUser adminUser;
    Cookie admin;
    String warehouse;
    long warehouseId;
    String productId;
    List<String> storage;
    List<String> quarantine;

    @BeforeEach
    void warehouseWithShippedOrder() throws Exception {
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
                + "[{\"x\":0,\"y\":3000},{\"x\":20000,\"y\":3000},{\"x\":20000,\"y\":20000},{\"x\":0,\"y\":20000}]},"
                + "{\"code\":\"Q\",\"type\":\"QUARANTINE\",\"name\":\"Hold\",\"polygon\":"
                + "[{\"x\":20000,\"y\":3000},{\"x\":40000,\"y\":3000},{\"x\":40000,\"y\":20000},{\"x\":20000,\"y\":20000}]}],\"racks\":["
                + "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":5000,\"y\":8000,\"rotation\":0},"
                + "{\"code\":\"R02\",\"templateId\":\"" + type + "\",\"zone\":\"Q\",\"x\":25000,\"y\":8000,\"rotation\":0}"
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        storage = jdbc.queryForList("SELECT code FROM bin WHERE warehouse_id = ? AND code LIKE 'A-%' ORDER BY code", String.class, warehouseId);
        quarantine = jdbc.queryForList("SELECT code FROM bin WHERE warehouse_id = ? AND code LIKE 'Q-%' ORDER BY code", String.class, warehouseId);
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Coconut oil 1L\",\"baseUom\":\"EA\",\"barcode\":\"4791\","
                + "\"costMinor\":45000,\"trackBatch\":false,\"trackExpiry\":false,\"units\":[{\"uom\":\"CASE\",\"factor\":12,\"barcode\":\"14791\"}]}"))
                .andExpect(status().isCreated());
        productId = read(send(admin, get("/api/v1/products?q=OIL-1L")), "$.items[0].id");
        send(admin, post("/api/v1/suppliers").content("{\"code\":\"S1\",\"name\":\"Lanka Oils\"}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/customers").content("{\"code\":\"C1\",\"name\":\"Kandy Super\"}")).andExpect(status().isCreated());
        receiveAndPutAway(48, storage.get(0));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private void receiveAndPutAway(int eaches, String bin) throws Exception {
        String po = read(send(admin, post("/api/v1/purchase-orders").content("{\"supplierCode\":\"S1\",\"warehouseId\":\"" + warehouse
                + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":" + eaches + "}]}")).andExpect(status().isCreated()), "$.id");
        send(admin, post("/api/v1/purchase-orders/" + po + "/approve").content("{\"version\":0}")).andExpect(status().isOk());
        String grn = read(send(admin, post("/api/v1/receipts").content("{\"poId\":\"" + po + "\"}")), "$.id");
        send(admin, post("/api/v1/receipts/" + grn + "/lines").header("Idempotency-Key", key())
                .content("{\"code\":\"4791\",\"qty\":" + eaches + "}")).andExpect(status().isOk());
        int v = read(send(admin, get("/api/v1/receipts/" + grn)), "$.version");
        send(admin, post("/api/v1/receipts/" + grn + "/close").content("{\"version\":" + v + "}")).andExpect(status().isOk());
        send(admin, post("/api/v1/putaway").header("Idempotency-Key", key()).content("{\"warehouseId\":\"" + warehouse
                + "\",\"productId\":\"" + productId + "\",\"qty\":" + eaches + ",\"binCode\":\"" + bin + "\"}")).andExpect(status().isOk());
    }

    /** Order, release, pick, pack and ship one order of this many cases; returns the order id. */
    private String shippedOrder(int cases) throws Exception {
        String so = read(send(admin, post("/api/v1/sales-orders").content("{\"customerCode\":\"C1\",\"warehouseId\":\""
                + warehouse + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"CASE\",\"qty\":" + cases + "}]}")).andExpect(status().isCreated()), "$.id");
        String list = read(send(admin, post("/api/v1/sales-orders/" + so + "/release").content("{\"version\":0}")).andExpect(status().isOk()), "$.pick.id");
        send(admin, post("/api/v1/pick-lists/" + list + "/lines/1/confirm").header("Idempotency-Key", key())
                .content("{\"binCode\":\"" + storage.get(0) + "\",\"code\":\"14791\",\"qty\":" + cases + "}")).andExpect(status().isOk());
        int v = read(send(admin, get("/api/v1/sales-orders/" + so)), "$.version");
        send(admin, post("/api/v1/sales-orders/" + so + "/pack").content("{\"version\":" + v + ",\"cartons\":1,\"counts\":[{\"sku\":\"OIL-1L\",\"qty\":"
                + cases * 12 + "}]}")).andExpect(status().isOk());
        send(admin, post("/api/v1/shipments").content("{\"warehouseId\":\"" + warehouse + "\",\"orderIds\":[\"" + so + "\"]}"))
                .andExpect(status().isCreated());
        return so;
    }

    private BigDecimal inBin(String code) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(s.qty_on_hand), 0) FROM stock s JOIN bin b ON b.id = s.bin_id WHERE s.tenant_id = ? AND b.code = ?",
                BigDecimal.class, tenant, code);
    }

    private String inspect(String ret, int seq, String outcome, String bin) throws Exception {
        return send(admin, post("/api/v1/returns/" + ret + "/lines/" + seq + "/inspect").content("{\"outcome\":\"" + outcome + "\""
                + (bin == null ? "" : ",\"binCode\":\"" + bin + "\"") + "}")).andReturn().getResponse().getContentAsString();
    }

    @Test
    void returnsComeFromShippedOrdersAndEachLineIsRestockedQuarantinedOrScrapped() throws Exception {
        String so = shippedOrder(2); // 24 EA left
        send(admin, get("/api/v1/sales-orders/" + so + "/returnable")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].shipped").value(24)).andExpect(jsonPath("$[0].left").value(24));

        send(admin, post("/api/v1/returns").content("{\"orderId\":\"" + so + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"CASE\",\"qty\":3,\"reason\":\"DAMAGED\"}]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MORE_THAN_SHIPPED"));
        String ret = read(send(admin, post("/api/v1/returns").content("{\"orderId\":\"" + so + "\",\"note\":\"Driver brought back\",\"lines\":["
                + "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":6,\"reason\":\"NOT_WANTED\"},"
                + "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":4,\"reason\":\"DAMAGED\"},"
                + "{\"sku\":\"oil-1l\",\"uom\":\"EA\",\"qty\":2,\"reason\":\"EXPIRED\"}]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.number").value("RT-000001")).andExpect(jsonPath("$.open").value(3)), "$.id");
        send(admin, get("/api/v1/sales-orders/" + so + "/returnable")).andExpect(jsonPath("$[0].returned").value(12))
                .andExpect(jsonPath("$[0].left").value(12));
        assertThat(inBin(storage.get(1))).isZero(); // nothing moves before inspection

        assertThat(inspect(ret, 1, "RESTOCK", quarantine.get(0))).contains("QUARANTINE_BIN");
        assertThat(inspect(ret, 2, "QUARANTINE", storage.get(1))).contains("NOT_QUARANTINE_BIN");
        assertThat(inspect(ret, 1, "RESTOCK", null)).contains("BIN_REQUIRED");
        inspect(ret, 1, "RESTOCK", storage.get(1).toLowerCase());
        inspect(ret, 2, "QUARANTINE", quarantine.get(0));
        assertThat(inspect(ret, 2, "SCRAP", null)).contains("RETURN_LINE_DONE");
        int rv = read(send(admin, get("/api/v1/returns/" + ret)), "$.version");
        send(admin, post("/api/v1/returns/" + ret + "/cancel").content("{\"version\":" + rv + ",\"reason\":\"x\"}"))
                .andExpect(jsonPath("$.code").value("RETURN_CANNOT_CANCEL"));
        assertThat(inspect(ret, 3, "SCRAP", null)).contains("\"status\":\"CLOSED\"");

        assertThat(inBin(storage.get(1))).isEqualByComparingTo("6");
        assertThat(inBin(quarantine.get(0))).isEqualByComparingTo("4");
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(qty), 0) FROM stock_movement WHERE tenant_id = ? AND type = 'RETURN'",
                BigDecimal.class, tenant)).isEqualByComparingTo("10"); // scrap books nothing
        send(admin, get("/api/v1/returns?done=true")).andExpect(jsonPath("$.items[0].number").value("RT-000001"));

        // task 6: quarantine stock is never reserved, and putaway never suggests a quarantine bin
        send(admin, get("/api/v1/stock/products?warehouse=" + warehouse)).andExpect(jsonPath("$.items[0].inQuarantine").value(4));
        String big = read(send(admin, post("/api/v1/sales-orders").content("{\"customerCode\":\"C1\",\"warehouseId\":\"" + warehouse
                + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":31}]}")).andExpect(status().isCreated()), "$.id");
        send(admin, post("/api/v1/sales-orders/" + big + "/release").content("{\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("OIL-1L (EA): need 31, 30 free in bins (4 more in quarantine)"));
        String suggestions = send(admin, post("/api/v1/putaway/suggestions").content("{\"warehouseId\":\"" + warehouse + "\",\"productId\":\""
                + productId + "\",\"qty\":1}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(suggestions).doesNotContain(quarantine.get(0)).contains(storage.get(1));
    }

    @Test
    void onlyShippedOrdersRulesAndAccess() throws Exception {
        String draft = read(send(admin, post("/api/v1/sales-orders").content("{\"customerCode\":\"C1\",\"warehouseId\":\"" + warehouse
                + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}]}")).andExpect(status().isCreated()), "$.id");
        send(admin, post("/api/v1/returns").content("{\"orderId\":\"" + draft + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1,\"reason\":\"OTHER\"}]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SO_NOT_SHIPPED"));
        String so = shippedOrder(1);
        String ret = read(send(admin, post("/api/v1/returns").content("{\"orderId\":\"" + so + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":12,\"reason\":\"WRONG_ITEM\"}]}"))
                .andExpect(status().isCreated()), "$.id");
        send(admin, post("/api/v1/returns").content("{\"orderId\":\"" + so + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1,\"reason\":\"OTHER\"}]}"))
                .andExpect(jsonPath("$.code").value("MORE_THAN_SHIPPED")); // all 12 already on the first return
        send(admin, post("/api/v1/returns/" + ret + "/cancel").content("{\"version\":0,\"reason\":\"Customer kept it\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        send(admin, get("/api/v1/sales-orders/" + so + "/returnable")).andExpect(jsonPath("$[0].left").value(12)); // cancelled frees it

        AppUser receiverUser = newUser(tenant, "RECEIVER");
        send(admin, put("/api/v1/warehouses/" + warehouse + "/users").content("{\"users\":[\"" + receiverUser.getPublicId() + "\"]}"))
                .andExpect(status().isOk());
        Cookie receiver = login(receiverUser);
        send(receiver, post("/api/v1/returns").content("{\"orderId\":\"" + so + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1,\"reason\":\"OTHER\"}]}"))
                .andExpect(status().isForbidden());
        String second = read(send(admin, post("/api/v1/returns").content("{\"orderId\":\"" + so + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":2,\"reason\":\"DAMAGED\"}]}"))
                .andExpect(status().isCreated()), "$.id");
        send(receiver, post("/api/v1/returns/" + second + "/lines/1/inspect").content("{\"outcome\":\"SCRAP\"}")).andExpect(status().isOk());
        Cookie picker = login(newUser(tenant, "PICKER"));
        send(picker, post("/api/v1/returns/" + second + "/lines/1/inspect").content("{\"outcome\":\"SCRAP\"}")).andExpect(status().isForbidden());
        Cookie unassigned = login(newUser(tenant, "RECEIVER"));
        send(unassigned, get("/api/v1/returns/" + second)).andExpect(status().isNotFound());
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/returns/" + second)).andExpect(status().isNotFound());
        send(other, get("/api/v1/sales-orders/" + so + "/returnable")).andExpect(status().isNotFound());
    }
}

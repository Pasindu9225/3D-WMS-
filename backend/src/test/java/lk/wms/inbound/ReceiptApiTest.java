package lk.wms.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** v0.4 task 4: receiving against purchase orders (GRN). */
class ReceiptApiTest extends ApiTest {

    long tenant;
    Cookie admin;
    String wh;
    String po;

    @BeforeEach
    void company() throws Exception {
        tenant = newTenant(3);
        admin = login(newUser(tenant, "TENANT_ADMIN"));
        wh = read(send(admin, post("/api/v1/warehouses").content("{\"code\":\"MAIN\",\"name\":\"W\",\"widthM\":20,"
                + "\"lengthM\":30,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
        send(admin, post("/api/v1/suppliers").content("{\"code\":\"SUP-1\",\"name\":\"Lanka Oils\"}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Coconut oil 1L\",\"baseUom\":\"EA\","
                + "\"barcode\":\"4791\",\"costMinor\":45000,\"trackBatch\":false,\"trackExpiry\":false,"
                + "\"units\":[{\"uom\":\"CASE\",\"factor\":12,\"barcode\":\"14791\"}]}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/products").content("{\"sku\":\"RICE-5KG\",\"name\":\"Samba rice\",\"baseUom\":\"BAG\","
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/products").content("{\"sku\":\"SALT\",\"name\":\"Salt\",\"baseUom\":\"EA\","
                + "\"barcode\":\"999\",\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());
        po = order("{\"sku\":\"OIL-1L\",\"uom\":\"CASE\",\"qty\":10},{\"sku\":\"RICE-5KG\",\"uom\":\"BAG\",\"qty\":2}", true);
    }

    private String order(String lines, boolean approve) throws Exception {
        String id = read(send(admin, post("/api/v1/purchase-orders").content("{\"supplierCode\":\"SUP-1\",\"warehouseId\":\""
                + wh + "\",\"lines\":[" + lines + "]}")).andExpect(status().isCreated()), "$.id");
        if (approve) {
            send(admin, post("/api/v1/purchase-orders/" + id + "/approve").content("{\"version\":0}")).andExpect(status().isOk());
        }
        return id;
    }

    private ResultActions open(Cookie who, String poId) throws Exception {
        return send(who, post("/api/v1/receipts").content("{\"poId\":\"" + poId + "\"}"));
    }

    private ResultActions scan(Cookie who, String receipt, String key, String body) throws Exception {
        return send(who, post("/api/v1/receipts/" + receipt + "/lines").header("Idempotency-Key", key).content(body));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void receiveInTwoDeliveriesByCaseBarcodeItemBarcodeAndSku() throws Exception {
        String grn = read(open(admin, po).andExpect(status().isOk()).andExpect(jsonPath("$.number").value("GRN-000001"))
                .andExpect(jsonPath("$.lines[0].ordered").value(120)), "$.id");
        open(admin, po).andExpect(jsonPath("$.id").value(grn)); // the open receipt is reused

        scan(admin, grn, key(), "{\"code\":\"14791\",\"qty\":4}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].thisReceipt").value(48)); // 4 cases x 12
        String retry = key();
        scan(admin, grn, retry, "{\"code\":\"4791\",\"qty\":12,\"damagedQty\":2,\"damageNote\":\"Crushed\"}")
                .andExpect(jsonPath("$.lines[0].thisReceipt").value(60)).andExpect(jsonPath("$.lines[0].damagedThisReceipt").value(2));
        scan(admin, grn, retry, "{\"code\":\"4791\",\"qty\":12,\"damagedQty\":2}") // phone retried: counted once
                .andExpect(jsonPath("$.lines[0].thisReceipt").value(60)).andExpect(jsonPath("$.scans.length()").value(2));
        String rice = read(scan(admin, grn, key(), "{\"code\":\"rice-5kg\",\"qty\":1}").andExpect(status().isOk()), "$.scans[2].id");
        scan(admin, grn, key(), "{\"code\":\"14791\",\"qty\":6}") // 72 more, only 60 expected
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OVER_RECEIPT"));
        send(admin, delete("/api/v1/receipts/" + grn + "/lines/" + rice)).andExpect(jsonPath("$.lines[1].thisReceipt").value(0));

        int version = read(send(admin, get("/api/v1/receipts/" + grn)), "$.version");
        send(admin, post("/api/v1/receipts/" + grn + "/close").content("{\"version\":" + version + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        send(admin, get("/api/v1/purchase-orders/" + po)).andExpect(jsonPath("$.status").value("PARTLY_RECEIVED"))
                .andExpect(jsonPath("$.lines[0].qtyReceived").value(60));
        scan(admin, grn, key(), "{\"code\":\"999\",\"qty\":1}").andExpect(jsonPath("$.code").value("RECEIPT_CLOSED"));

        String second = read(open(admin, po).andExpect(jsonPath("$.number").value("GRN-000002"))
                .andExpect(jsonPath("$.lines[0].receivedBefore").value(60)), "$.id");
        scan(admin, second, key(), "{\"code\":\"OIL-1L\",\"uom\":\"CASE\",\"qty\":5}").andExpect(jsonPath("$.lines[0].thisReceipt").value(60));
        scan(admin, second, key(), "{\"code\":\"RICE-5KG\",\"qty\":2}").andExpect(status().isOk());
        send(admin, post("/api/v1/receipts/" + second + "/close").content("{\"version\":" + read(send(admin, get("/api/v1/receipts/" + second)), "$.version") + "}"))
                .andExpect(jsonPath("$.lines[0].receivedBefore").value(60)).andExpect(jsonPath("$.lines[0].thisReceipt").value(60));
        send(admin, get("/api/v1/purchase-orders/" + po)).andExpect(jsonPath("$.status").value("RECEIVED"));
        open(admin, po).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PO_NOT_RECEIVABLE"));
        send(admin, get("/api/v1/receipts?po=" + po)).andExpect(jsonPath("$.length()").value(2));
        send(admin, get("/api/v1/purchase-orders?receivable=true")).andExpect(jsonPath("$.total").value(0));
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE tenant_id = ? AND action LIKE 'RECEIPT!_%' ESCAPE '!'",
                String.class, tenant)).contains("RECEIPT_OPENED", "RECEIPT_SCANNED", "RECEIPT_SCAN_REMOVED", "RECEIPT_CLOSED");
    }

    @Test
    void scansAreCheckedAndOnlyApprovedOrdersOpen() throws Exception {
        String draft = order("{\"sku\":\"SALT\",\"uom\":\"EA\",\"qty\":1}", false);
        open(admin, draft).andExpect(jsonPath("$.code").value("PO_NOT_RECEIVABLE"));
        String grn = read(open(admin, po), "$.id");
        scan(admin, grn, key(), "{\"code\":\"999\",\"qty\":1}").andExpect(jsonPath("$.code").value("NOT_ON_ORDER"));
        scan(admin, grn, key(), "{\"code\":\"nope\",\"qty\":1}").andExpect(jsonPath("$.code").value("CODE_NOT_FOUND"));
        scan(admin, grn, key(), "{\"code\":\"4791\",\"uom\":\"PAL\",\"qty\":1}").andExpect(jsonPath("$.code").value("UOM_NOT_FOUND"));
        scan(admin, grn, key(), "{\"code\":\"4791\",\"qty\":1,\"damagedQty\":2}").andExpect(jsonPath("$.code").value("DAMAGED_TOO_MANY"));
        scan(admin, grn, key(), "{\"code\":\"4791\",\"qty\":0}").andExpect(status().isBadRequest());
        send(admin, post("/api/v1/receipts/" + grn + "/lines").content("{\"code\":\"4791\",\"qty\":1}")).andExpect(status().isBadRequest());
        send(admin, post("/api/v1/receipts/" + grn + "/close").content("{\"version\":0}")).andExpect(jsonPath("$.code").value("RECEIPT_EMPTY"));
        // an order with a receipt open cannot be cancelled under the receiver
        send(admin, post("/api/v1/purchase-orders/" + po + "/cancel").content("{\"version\":1,\"reason\":\"x\"}"))
                .andExpect(jsonPath("$.code").value("PO_CANNOT_CANCEL"));
    }

    @Test
    void receiversWorkOnlyInTheirWarehousesAndViewersOnlyLook() throws Exception {
        var receiverUser = newUser(tenant, "RECEIVER");
        Cookie viewer = login(newUser(tenant, "VIEWER"));
        Cookie receiver = login(receiverUser);
        open(receiver, po).andExpect(status().isNotFound()); // not assigned to MAIN yet
        send(admin, put("/api/v1/warehouses/" + wh + "/users").content("{\"users\":[\"" + receiverUser.getPublicId() + "\"]}"))
                .andExpect(status().isOk());
        String grn = read(open(receiver, po).andExpect(status().isOk()), "$.id");
        scan(receiver, grn, key(), "{\"code\":\"4791\",\"qty\":1}").andExpect(status().isOk());
        send(viewer, get("/api/v1/receipts/" + grn)).andExpect(status().isNotFound()); // viewer not assigned either
        scan(viewer, grn, key(), "{\"code\":\"4791\",\"qty\":1}").andExpect(status().isForbidden());
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/receipts/" + grn)).andExpect(status().isNotFound());
        scan(other, grn, key(), "{\"code\":\"4791\",\"qty\":1}").andExpect(status().isNotFound());
    }

    @Test
    void twoPhonesScanningTheSameDeliveryNeverReceiveMoreThanOrdered() throws Exception {
        String grn = read(open(admin, po), "$.id");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) { // 20 x 7 = 140 EA offered, 120 ordered
                Callable<Integer> job = () -> scan(admin, grn, key(), "{\"code\":\"4791\",\"qty\":7}").andReturn().getResponse().getStatus();
                results.add(pool.submit(job));
            }
            int ok = 0;
            for (Future<Integer> f : results) {
                ok += f.get() == 200 ? 1 : 0;
            }
            assertThat(ok).isEqualTo(17); // 17 x 7 = 119; an 18th would make 126
        } finally {
            pool.shutdownNow();
        }
        BigDecimal total = jdbc.queryForObject("SELECT SUM(qty_base) FROM receipt_line WHERE tenant_id = ?", BigDecimal.class, tenant);
        assertThat(total).isEqualByComparingTo("119");
    }
}

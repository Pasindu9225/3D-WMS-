package lk.wms.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lk.wms.ApiTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** v0.4 task 3: purchase orders — create, edit drafts, approve, cancel; numbering; access. */
class PurchaseOrderApiTest extends ApiTest {

    long tenant;
    Cookie admin;
    String wh;

    @BeforeEach
    void company() throws Exception {
        tenant = newTenant(3);
        admin = login(newUser(tenant, "TENANT_ADMIN"));
        wh = warehouse(admin, "MAIN");
        send(admin, post("/api/v1/suppliers").content("{\"code\":\"SUP-1\",\"name\":\"Lanka Oils\"}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Coconut oil 1L\",\"baseUom\":\"EA\","
                + "\"costMinor\":45000,\"trackBatch\":false,\"trackExpiry\":false,\"units\":[{\"uom\":\"CASE\",\"factor\":12}]}"))
                .andExpect(status().isCreated());
        send(admin, post("/api/v1/products").content("{\"sku\":\"RICE-5KG\",\"name\":\"Samba rice\",\"baseUom\":\"BAG\","
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());
    }

    private String warehouse(Cookie who, String code) throws Exception {
        return read(send(who, post("/api/v1/warehouses").content("{\"code\":\"" + code + "\",\"name\":\"W\",\"widthM\":20,"
                + "\"lengthM\":30,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
    }

    private String body(String warehouse, String lines) {
        return "{\"supplierCode\":\"sup-1\",\"warehouseId\":\"" + warehouse + "\",\"expectedDate\":null,\"note\":\"Urgent\","
                + "\"lines\":[" + lines + "]}";
    }

    private ResultActions create(Cookie who, String lines) throws Exception {
        return send(who, post("/api/v1/purchase-orders").content(body(wh, lines)));
    }

    @Test
    void draftIsNumberedPricedFromTheCatalogEditedThenApproved() throws Exception {
        ResultActions made = create(admin, "{\"sku\":\"oil-1l\",\"uom\":\"CASE\",\"qty\":10},"
                + "{\"sku\":\"RICE-5KG\",\"uom\":\"BAG\",\"qty\":2.5,\"unitCostMinor\":125000}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("PO-000001")).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.supplierName").value("Lanka Oils"))
                .andExpect(jsonPath("$.lines[0].factor").value(12))
                .andExpect(jsonPath("$.lines[0].unitCostMinor").value(540000)) // 450.00 x 12
                .andExpect(jsonPath("$.totalMinor").value(5400000 + 312500));
        String id = read(made, "$.id");
        create(admin, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}").andExpect(jsonPath("$.number").value("PO-000002"));

        send(admin, put("/api/v1/purchase-orders/" + id).content(body(wh, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":3}")
                .replace("}]}", "}],\"version\":1}"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PO_CHANGED"));
        send(admin, put("/api/v1/purchase-orders/" + id).content(body(wh, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":3}")
                .replace("}]}", "}],\"version\":0}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1)).andExpect(jsonPath("$.totalMinor").value(135000));

        send(admin, post("/api/v1/purchase-orders/" + id + "/approve").content("{\"version\":1}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        send(admin, post("/api/v1/purchase-orders/" + id + "/approve").content("{\"version\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PO_NOT_DRAFT"));
        send(admin, put("/api/v1/purchase-orders/" + id).content(body(wh, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":4}")
                .replace("}]}", "}],\"version\":2}"))).andExpect(jsonPath("$.code").value("PO_NOT_DRAFT"));

        send(admin, get("/api/v1/purchase-orders?status=APPROVED")).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].lineCount").value(1));
        send(admin, get("/api/v1/purchase-orders?q=lanka")).andExpect(jsonPath("$.total").value(2));
        send(admin, get("/api/v1/purchase-orders?status=NOPE")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE tenant_id = ? AND action LIKE 'PO!_%' ESCAPE '!'",
                String.class, tenant)).containsExactlyInAnyOrder("PO_CREATED", "PO_CREATED", "PO_UPDATED", "PO_APPROVED");
    }

    @Test
    void cancelNeedsAReasonAndIsFinal() throws Exception {
        String id = read(create(admin, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}"), "$.id");
        send(admin, post("/api/v1/purchase-orders/" + id + "/approve").content("{\"version\":0}")).andExpect(status().isOk());
        send(admin, post("/api/v1/purchase-orders/" + id + "/cancel").content("{\"version\":1,\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        send(admin, post("/api/v1/purchase-orders/" + id + "/cancel").content("{\"version\":1,\"reason\":\"Supplier out of stock\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Supplier out of stock"));
        send(admin, post("/api/v1/purchase-orders/" + id + "/cancel").content("{\"version\":2,\"reason\":\"again\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PO_CANNOT_CANCEL"));
        send(admin, post("/api/v1/purchase-orders/" + id + "/approve").content("{\"version\":2}"))
                .andExpect(jsonPath("$.code").value("PO_NOT_DRAFT"));
    }

    @Test
    void linesAreCheckedAgainstTheCatalog() throws Exception {
        create(admin, "{\"sku\":\"NOPE\",\"uom\":\"EA\",\"qty\":1}").andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        create(admin, "{\"sku\":\"OIL-1L\",\"uom\":\"PAL\",\"qty\":1}").andExpect(jsonPath("$.code").value("UOM_NOT_FOUND"));
        create(admin, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1},{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":2}")
                .andExpect(jsonPath("$.code").value("DUPLICATE_LINE"));
        create(admin, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":0}").andExpect(status().isBadRequest());
        create(admin, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1.0001}").andExpect(status().isBadRequest());
        create(admin, "").andExpect(status().isBadRequest());
        send(admin, post("/api/v1/purchase-orders").content(body(wh, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}")
                .replace("sup-1", "SUP-9"))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
        send(admin, post("/api/v1/purchase-orders").content(body(wh, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}")
                .replace("\"expectedDate\":null", "\"expectedDate\":\"2099-01-01\""))).andExpect(jsonPath("$.code").value("BAD_DATE"));
        send(admin, get("/api/v1/purchase-orders")).andExpect(jsonPath("$.total").value(0)); // nothing half-saved
    }

    @Test
    void ordersFollowWarehouseAccessRolesAndCompany() throws Exception {
        var managerUser = newUser(tenant, "WAREHOUSE_MANAGER");
        var receiverUser = newUser(tenant, "RECEIVER");
        send(admin, put("/api/v1/warehouses/" + wh + "/users").content("{\"users\":[\"" + managerUser.getPublicId() + "\",\""
                + receiverUser.getPublicId() + "\"]}")).andExpect(status().isOk());
        Cookie manager = login(managerUser);
        Cookie receiver = login(receiverUser);
        String second = warehouse(admin, "SECOND"); // only the admin is assigned to it
        String inSecond = read(send(admin, post("/api/v1/purchase-orders").content(body(second,
                "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}"))).andExpect(status().isCreated()), "$.id");
        String inMain = read(create(manager, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}").andExpect(status().isCreated()), "$.id");

        send(manager, get("/api/v1/purchase-orders")).andExpect(jsonPath("$.total").value(1));
        send(manager, get("/api/v1/purchase-orders/" + inSecond)).andExpect(status().isNotFound());
        send(manager, post("/api/v1/purchase-orders/" + inSecond + "/approve").content("{\"version\":0}")).andExpect(status().isNotFound());
        send(manager, post("/api/v1/purchase-orders").content(body(second, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}")))
                .andExpect(status().isNotFound());
        send(admin, get("/api/v1/purchase-orders")).andExpect(jsonPath("$.total").value(2));

        send(receiver, get("/api/v1/purchase-orders/" + inMain)).andExpect(status().isOk());
        create(receiver, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}").andExpect(status().isForbidden());
        send(receiver, post("/api/v1/purchase-orders/" + inMain + "/approve").content("{\"version\":0}")).andExpect(status().isForbidden());

        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/purchase-orders/" + inMain)).andExpect(status().isNotFound());
        send(other, get("/api/v1/purchase-orders")).andExpect(jsonPath("$.total").value(0));
        String otherWh = warehouse(other, "MAIN");
        send(other, post("/api/v1/suppliers").content("{\"code\":\"SUP-1\",\"name\":\"Other\"}")).andExpect(status().isCreated());
        send(other, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"O\",\"baseUom\":\"EA\","
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());
        send(other, post("/api/v1/purchase-orders").content(body(otherWh, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}")))
                .andExpect(jsonPath("$.number").value("PO-000001")); // numbering is per company
        send(other, post("/api/v1/purchase-orders").content(body(wh, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}")))
                .andExpect(status().isNotFound()); // another company's warehouse
    }

    @Test
    void numbersNeverRepeatUnderConcurrentCreates() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> made = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                Callable<String> job = () -> read(create(admin, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}")
                        .andExpect(status().isCreated()), "$.number");
                made.add(pool.submit(job));
            }
            List<String> numbers = new ArrayList<>();
            for (Future<String> f : made) {
                numbers.add(f.get());
            }
            assertThat(numbers).doesNotHaveDuplicates().hasSize(12).contains("PO-000001", "PO-000012");
        } finally {
            pool.shutdownNow();
        }
    }
}

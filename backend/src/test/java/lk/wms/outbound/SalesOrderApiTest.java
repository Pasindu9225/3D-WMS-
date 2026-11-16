package lk.wms.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import lk.wms.ApiTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** v1.0 task 1: customers and sales orders — create, edit drafts, cancel, CSV import; access. */
class SalesOrderApiTest extends ApiTest {

    long tenant;
    Cookie admin;
    String wh;

    @BeforeEach
    void company() throws Exception {
        tenant = newTenant(3);
        admin = login(newUser(tenant, "TENANT_ADMIN"));
        wh = warehouse(admin, "MAIN");
        send(admin, post("/api/v1/customers").content("{\"code\":\"CUS-1\",\"name\":\"Kandy Super\",\"address\":\"45 Peradeniya Rd, Kandy\"}"))
                .andExpect(status().isCreated());
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Coconut oil 1L\",\"baseUom\":\"EA\","
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[{\"uom\":\"CASE\",\"factor\":12}]}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/products").content("{\"sku\":\"RICE-5KG\",\"name\":\"Samba rice\",\"baseUom\":\"BAG\","
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());
    }

    private String warehouse(Cookie who, String code) throws Exception {
        return read(send(who, post("/api/v1/warehouses").content("{\"code\":\"" + code + "\",\"name\":\"W\",\"widthM\":20,"
                + "\"lengthM\":30,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
    }

    private String body(String warehouse, String ref, String lines) {
        return "{\"customerCode\":\"cus-1\",\"customerRef\":" + (ref == null ? "null" : "\"" + ref + "\"") + ",\"warehouseId\":\""
                + warehouse + "\",\"requestedDate\":null,\"note\":\"Morning\",\"lines\":[" + lines + "]}";
    }

    private ResultActions create(Cookie who, String ref, String lines) throws Exception {
        return send(who, post("/api/v1/sales-orders").content(body(wh, ref, lines)));
    }

    private ResultActions importCsv(Cookie who, String text) throws Exception {
        return mvc.perform(multipart("/api/v1/sales-orders/import")
                .file(new MockMultipartFile("file", "orders.csv", "text/csv", text.getBytes(StandardCharsets.UTF_8)))
                .with(csrf()).cookie(who));
    }

    @Test
    void draftIsNumberedEditedAndCancelledWithAReason() throws Exception {
        String id = read(create(admin, "KSM-1", "{\"sku\":\"oil-1l\",\"uom\":\"CASE\",\"qty\":10},{\"sku\":\"RICE-5KG\",\"uom\":\"BAG\",\"qty\":4}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("SO-000001")).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.customerName").value("Kandy Super"))
                .andExpect(jsonPath("$.shipTo").value("45 Peradeniya Rd, Kandy")) // the customer's address by default
                .andExpect(jsonPath("$.lines[0].factor").value(12)), "$.id");

        send(admin, put("/api/v1/sales-orders/" + id).content(body(wh, "KSM-1", "{\"sku\":\"RICE-5KG\",\"uom\":\"BAG\",\"qty\":3}")
                .replace("}]}", "}],\"version\":5}"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SO_CHANGED"));
        send(admin, put("/api/v1/sales-orders/" + id).content(body(wh, "KSM-1", "{\"sku\":\"RICE-5KG\",\"uom\":\"BAG\",\"qty\":3}")
                .replace("\"note\"", "\"shipTo\":\"Gate 2\",\"note\"").replace("}]}", "}],\"version\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(1)).andExpect(jsonPath("$.shipTo").value("Gate 2"));

        send(admin, post("/api/v1/sales-orders/" + id + "/cancel").content("{\"version\":1,\"reason\":\" \"}")).andExpect(status().isBadRequest());
        send(admin, post("/api/v1/sales-orders/" + id + "/cancel").content("{\"version\":1,\"reason\":\"Customer changed mind\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Customer changed mind"));
        send(admin, put("/api/v1/sales-orders/" + id).content(body(wh, null, "{\"sku\":\"RICE-5KG\",\"uom\":\"BAG\",\"qty\":1}")
                .replace("}]}", "}],\"version\":2}"))).andExpect(jsonPath("$.code").value("SO_NOT_DRAFT"));
        send(admin, post("/api/v1/sales-orders/" + id + "/cancel").content("{\"version\":2,\"reason\":\"again\"}"))
                .andExpect(jsonPath("$.code").value("SO_CANNOT_CANCEL"));

        send(admin, get("/api/v1/sales-orders?status=CANCELLED")).andExpect(jsonPath("$.total").value(1));
        send(admin, get("/api/v1/sales-orders?q=ksm")).andExpect(jsonPath("$.total").value(1)); // customer ref
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE tenant_id = ? AND action LIKE 'SO!_%' ESCAPE '!'",
                String.class, tenant)).containsExactlyInAnyOrder("SO_CREATED", "SO_UPDATED", "SO_CANCELLED");
    }

    @Test
    void linesCustomerAndRefAreChecked() throws Exception {
        create(admin, null, "{\"sku\":\"NOPE\",\"uom\":\"EA\",\"qty\":1}").andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        create(admin, null, "{\"sku\":\"OIL-1L\",\"uom\":\"PAL\",\"qty\":1}").andExpect(jsonPath("$.code").value("UOM_NOT_FOUND"));
        create(admin, null, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1},{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":2}")
                .andExpect(jsonPath("$.code").value("DUPLICATE_LINE"));
        create(admin, null, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":0}").andExpect(status().isBadRequest());
        send(admin, post("/api/v1/sales-orders").content(body(wh, null, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}")
                .replace("cus-1", "CUS-9"))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
        // a supplier with the same code is not a customer
        send(admin, post("/api/v1/suppliers").content("{\"code\":\"CUS-2\",\"name\":\"Supplier\"}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/sales-orders").content(body(wh, null, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}")
                .replace("cus-1", "CUS-2"))).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));

        create(admin, "PO-77", "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}").andExpect(status().isCreated());
        create(admin, "PO-77", "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_REF_EXISTS"));
        create(admin, null, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}").andExpect(status().isCreated());
        create(admin, null, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}").andExpect(status().isCreated()); // no ref, no clash
        send(admin, get("/api/v1/sales-orders")).andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void csvImportGroupsRowsIntoOrdersAllOrNothing() throws Exception {
        String header = "order_ref,customer_code,warehouse_code,requested_date,ship_to,note,sku,uom,qty\n";
        importCsv(admin, header
                + "A-1,cus-1,main,2026-12-20,,,OIL-1L,CASE,2\n"
                + "A-1,CUS-1,MAIN,,,,RICE-5KG,BAG,5\n"
                + "A-2,CUS-1,MAIN,,,,NOPE,EA,1\n"
                + "A-3,CUS-1,MAIN,someday,,,OIL-1L,EA,1\n"
                + "A-4,CUS-9,MAIN,,,,OIL-1L,EA,1\n")
                .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].row").value(4)).andExpect(jsonPath("$.errors[0].field").value("sku"))
                .andExpect(jsonPath("$.errors[1].field").value("requested_date"))
                .andExpect(jsonPath("$.errors[2].field").value("customer_code"));
        send(admin, get("/api/v1/sales-orders")).andExpect(jsonPath("$.total").value(0)); // nothing saved

        importCsv(admin, header
                + "A-1,cus-1,main,2026-12-20,Gate 2,,OIL-1L,CASE,2\n"
                + "A-1,CUS-1,MAIN,,,,RICE-5KG,BAG,5\n"
                + "A-2,CUS-1,MAIN,,,,OIL-1L,EA,6\n")
                .andExpect(jsonPath("$.saved").value(true)).andExpect(jsonPath("$.created").value(2));
        String first = read(send(admin, get("/api/v1/sales-orders?q=A-1")).andExpect(jsonPath("$.total").value(1)), "$.items[0].id");
        send(admin, get("/api/v1/sales-orders/" + first)).andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.shipTo").value("Gate 2")).andExpect(jsonPath("$.requestedDate").value("2026-12-20"));

        importCsv(admin, header + "A-2,CUS-1,MAIN,,,,OIL-1L,EA,1\n") // already imported
                .andExpect(jsonPath("$.saved").value(false)).andExpect(jsonPath("$.errors[0].field").value("order_ref"));
        importCsv(admin, header + "B-1,CUS-1,MAIN,,,,OIL-1L,EA,1\nB-1,CUS-1,OTHER,,,,RICE-5KG,BAG,1\n") // one order, two warehouses
                .andExpect(jsonPath("$.saved").value(false)).andExpect(jsonPath("$.errors[0].row").value(3));
        importCsv(admin, "ref,customer\nx,y\n").andExpect(jsonPath("$.code").value("CSV_BAD_HEADER"));
        send(admin, get("/api/v1/sales-orders/import-template.csv")).andExpect(status().isOk());
        send(admin, get("/api/v1/sales-orders")).andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void ordersFollowWarehouseAccessRolesAndCompany() throws Exception {
        var receiverUser = newUser(tenant, "RECEIVER");
        send(admin, put("/api/v1/warehouses/" + wh + "/users").content("{\"users\":[\"" + receiverUser.getPublicId() + "\"]}"))
                .andExpect(status().isOk());
        Cookie receiver = login(receiverUser);
        Cookie viewer = login(newUser(tenant, "VIEWER"));
        String id = read(create(admin, null, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}").andExpect(status().isCreated()), "$.id");

        send(receiver, get("/api/v1/sales-orders")).andExpect(status().isForbidden()); // receivers do not see outbound
        create(receiver, null, "{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}").andExpect(status().isForbidden());
        send(receiver, post("/api/v1/customers").content("{\"code\":\"CUS-3\",\"name\":\"X\"}")).andExpect(status().isForbidden());
        send(viewer, get("/api/v1/sales-orders")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0)); // not assigned
        send(viewer, post("/api/v1/sales-orders/" + id + "/cancel").content("{\"version\":0,\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());

        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/sales-orders/" + id)).andExpect(status().isNotFound());
        send(other, get("/api/v1/customers")).andExpect(jsonPath("$.total").value(0));
        send(other, post("/api/v1/customers").content("{\"code\":\"CUS-1\",\"name\":\"Other\"}")).andExpect(status().isCreated());
        send(admin, get("/api/v1/customers")).andExpect(jsonPath("$.total").value(1));
        send(admin, get("/api/v1/suppliers")).andExpect(jsonPath("$.total").value(0)); // customers are a separate list
    }
}

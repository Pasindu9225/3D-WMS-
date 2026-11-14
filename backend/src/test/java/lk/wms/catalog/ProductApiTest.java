package lk.wms.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import lk.wms.ApiTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** v0.4 task 1: product master with units of measure and barcodes. */
class ProductApiTest extends ApiTest {

    long tenant;
    Cookie admin;

    @BeforeEach
    void company() throws Exception {
        tenant = newTenant(1);
        admin = login(newUser(tenant, "TENANT_ADMIN"));
    }

    private static String product(String sku, String barcode, String units) {
        return "{" + (sku == null ? "" : "\"sku\":\"" + sku + "\",") + "\"name\":\"Coconut oil 1L\","
                + (barcode == null ? "" : "\"barcode\":\"" + barcode + "\",")
                + "\"baseUom\":\"EA\",\"weightG\":950,\"volumeCm3\":1100,\"trackBatch\":true,\"trackExpiry\":true,"
                + "\"costMinor\":45000,\"units\":[" + units + "]}";
    }

    private String create(String sku, String barcode, String units) throws Exception {
        return read(send(admin, post("/api/v1/products").content(product(sku, barcode, units)))
                .andExpect(status().isCreated()), "$.id");
    }

    @Test
    void createsAProductWithUnitsAndFindsItByEitherBarcode() throws Exception {
        String id = create("OIL-1L", "4791234000011", "{\"uom\":\"CASE\",\"factor\":12,\"barcode\":\"14791234000018\"}");
        send(admin, get("/api/v1/products/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("OIL-1L"))
                .andExpect(jsonPath("$.units[0].uom").value("CASE"))
                .andExpect(jsonPath("$.units[0].factor").value(12))
                .andExpect(jsonPath("$.version").value(0));
        send(admin, get("/api/v1/products/by-barcode/4791234000011")).andExpect(status().isOk())
                .andExpect(jsonPath("$.uom").value("EA")).andExpect(jsonPath("$.factor").value(1));
        send(admin, get("/api/v1/products/by-barcode/14791234000018")).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.sku").value("OIL-1L"))
                .andExpect(jsonPath("$.uom").value("CASE")).andExpect(jsonPath("$.factor").value(12));
        send(admin, get("/api/v1/products/by-barcode/999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BARCODE_NOT_FOUND"));
    }

    @Test
    void searchMatchesSkuBarcodeOrNameAndTreatsWildcardsAsText() throws Exception {
        create("OIL-1L", "4791234000011", "");
        send(admin, post("/api/v1/products").content(product("RICE-5KG", null, "").replace("Coconut oil 1L", "Samba rice 5kg")))
                .andExpect(status().isCreated());
        send(admin, get("/api/v1/products?q=oil")).andExpect(jsonPath("$.total").value(1));
        send(admin, get("/api/v1/products?q=4791")).andExpect(jsonPath("$.items[0].sku").value("OIL-1L"));
        send(admin, get("/api/v1/products?q=%25")).andExpect(jsonPath("$.total").value(0)); // a literal %
        send(admin, get("/api/v1/products")).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].sku").value("OIL-1L"));
    }

    @Test
    void aBarcodeMeansOneThingAcrossProductsAndUnits() throws Exception {
        create("OIL-1L", "4791234000011", "{\"uom\":\"CASE\",\"factor\":12,\"barcode\":\"14791234000018\"}");
        send(admin, post("/api/v1/products").content(product("OIL-2L", "14791234000018", "")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_CONFLICT"));
        send(admin, post("/api/v1/products").content(product("OIL-2L", null,
                "{\"uom\":\"CASE\",\"factor\":6,\"barcode\":\"4791234000011\"}")))
                .andExpect(status().isConflict());
        send(admin, post("/api/v1/products").content(product("OIL-1L", null, "")))
                .andExpect(status().isConflict()); // same SKU
        send(admin, post("/api/v1/products").content(product("OIL-3L", "5555",
                "{\"uom\":\"CASE\",\"factor\":6,\"barcode\":\"5555\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BARCODE_REPEATED"));
        send(admin, post("/api/v1/products").content(product("OIL-4L", null,
                "{\"uom\":\"EA\",\"factor\":6}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UOM_REPEATED"));
    }

    @Test
    void updatesNeedTheCurrentVersionAndArchivedProductsKeepTheirBarcode() throws Exception {
        String id = create("OIL-1L", "4791234000011", "{\"uom\":\"CASE\",\"factor\":12}");
        String edit = product(null, "4791234000011", "{\"uom\":\"CASE\",\"factor\":24},{\"uom\":\"PAL\",\"factor\":480}");
        send(admin, put("/api/v1/products/" + id).content(edit.replace("}]}", "}],\"version\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.units[0].factor").value(24))
                .andExpect(jsonPath("$.units[1].uom").value("PAL")).andExpect(jsonPath("$.version").value(1));
        send(admin, put("/api/v1/products/" + id).content(edit.replace("}]}", "}],\"version\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_CHANGED"));

        send(admin, post("/api/v1/products/" + id + "/archive")).andExpect(status().isNoContent());
        send(admin, get("/api/v1/products/" + id)).andExpect(status().isNotFound());
        send(admin, get("/api/v1/products/by-barcode/4791234000011")).andExpect(status().isNotFound());
        send(admin, post("/api/v1/products").content(product("OIL-NEW", "4791234000011", "")))
                .andExpect(status().isConflict()); // the archived product still owns it
    }

    @Test
    void viewersReadOnlyAndOtherCompaniesSeeNothing() throws Exception {
        String id = create("OIL-1L", null, "");
        Cookie viewer = login(newUser(tenant, "VIEWER"));
        send(viewer, get("/api/v1/products/" + id)).andExpect(status().isOk());
        send(viewer, post("/api/v1/products").content(product("X1", null, ""))).andExpect(status().isForbidden());
        send(viewer, post("/api/v1/products/" + id + "/archive")).andExpect(status().isForbidden());

        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/products/" + id)).andExpect(status().isNotFound());
        send(other, get("/api/v1/products")).andExpect(jsonPath("$.total").value(0));
        send(other, post("/api/v1/products/" + id + "/archive")).andExpect(status().isNotFound());
        // same SKU is fine in another company
        send(other, post("/api/v1/products").content(product("OIL-1L", null, ""))).andExpect(status().isCreated());
    }

    @Test
    void badInputIsRefusedBeforeAnythingIsSaved() throws Exception {
        send(admin, post("/api/v1/products").content(product("oil lower", null, ""))).andExpect(status().isBadRequest());
        send(admin, post("/api/v1/products").content(product(null, null, ""))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SKU_REQUIRED"));
        send(admin, post("/api/v1/products").content(product("OK1", "bad code!", ""))).andExpect(status().isBadRequest());
        send(admin, post("/api/v1/products").content(product("OK2", null, "{\"uom\":\"CASE\",\"factor\":1}")))
                .andExpect(status().isBadRequest());
        send(admin, post("/api/v1/products").content(product("OK3", null, "").replace("\"weightG\":950", "\"weightG\":-1")))
                .andExpect(status().isBadRequest());
        send(admin, get("/api/v1/products")).andExpect(jsonPath("$.total").value(0));
    }
}

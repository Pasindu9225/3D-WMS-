package lk.wms.catalog;

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

/** v0.4 task 2: suppliers and CSV imports of products and suppliers (SEC-46). */
class ImportApiTest extends ApiTest {

    long tenant;
    Cookie admin;

    @BeforeEach
    void company() throws Exception {
        tenant = newTenant(1);
        admin = login(newUser(tenant, "TENANT_ADMIN"));
    }

    private ResultActions importCsv(Cookie who, String what, String name, String text) throws Exception {
        return mvc.perform(multipart("/api/v1/" + what + "/import")
                .file(new MockMultipartFile("file", name, "text/csv", text.getBytes(StandardCharsets.UTF_8)))
                .with(csrf()).cookie(who));
    }

    @Test
    void suppliersAreAManagedListPerCompany() throws Exception {
        String id = read(send(admin, post("/api/v1/suppliers").content("{\"code\":\"SUP-1\",\"name\":\"Lanka Oils\","
                + "\"phone\":\"+94 11 234 5678\",\"email\":\"orders@lankaoils.lk\"}")).andExpect(status().isCreated()), "$.id");
        send(admin, post("/api/v1/suppliers").content("{\"code\":\"SUP-1\",\"name\":\"Copy\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODE_IN_USE"));
        send(admin, post("/api/v1/suppliers").content("{\"code\":\"SUP-2\",\"name\":\"Bad\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
        send(admin, put("/api/v1/suppliers/" + id).content("{\"name\":\"Lanka Oils (Pvt) Ltd\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Lanka Oils (Pvt) Ltd"));
        send(admin, get("/api/v1/suppliers?q=lanka")).andExpect(jsonPath("$.total").value(1));

        Cookie viewer = login(newUser(tenant, "VIEWER"));
        send(viewer, get("/api/v1/suppliers")).andExpect(status().isOk());
        send(viewer, post("/api/v1/suppliers").content("{\"code\":\"X\",\"name\":\"X\"}")).andExpect(status().isForbidden());
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/suppliers")).andExpect(jsonPath("$.total").value(0));
        send(other, post("/api/v1/suppliers/" + id + "/archive")).andExpect(status().isNotFound());
        send(admin, post("/api/v1/suppliers/" + id + "/archive")).andExpect(status().isNoContent());
        send(admin, get("/api/v1/suppliers")).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void productsImportCreatesAndUpdatesBySku() throws Exception {
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Old name\",\"baseUom\":\"EA\","
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());
        String csv = "﻿sku,name,barcode,base_uom,weight_kg,volume_l,cost,track_batch,track_expiry,units\r\n"
                + "oil-1l,\"Coconut oil, 1L\",4791234000011,EA,0.95,1.1,\"1,250.50\",no,yes,CASE=12:14791234000018;PAL=480\r\n"
                + "RICE-5KG,Samba rice 5kg,,bag,5,6.5,1250,yes,no,\r\n";
        importCsv(admin, "products", "products.csv", csv).andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true)).andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(1));
        send(admin, get("/api/v1/products/by-barcode/14791234000018")).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.name").value("Coconut oil, 1L"))
                .andExpect(jsonPath("$.product.costMinor").value(125050))
                .andExpect(jsonPath("$.product.weightG").value(950))
                .andExpect(jsonPath("$.factor").value(12));
        send(admin, get("/api/v1/products?q=RICE")).andExpect(jsonPath("$.items[0].baseUom").value("BAG"))
                .andExpect(jsonPath("$.items[0].trackBatch").value(true));
    }

    @Test
    void anyBadRowMeansNothingIsSavedAndEveryProblemIsListedByRow() throws Exception {
        String csv = "sku,name,base_uom,weight_kg,track_expiry,units,barcode\n"
                + "GOOD-1,Fine,EA,1,no,,111\n"
                + "bad sku,Wrong sku,EA,,,,\n"            // row 3
                + "GOOD-2,,EA,heavy,maybe,,\n"            // row 4: no name, weight and yes/no wrong
                + "GOOD-1,Again,EA,,,,\n"                 // row 5: SKU repeated
                + "GOOD-3,Units,EA,,,CASE=x,\n"           // row 6: units malformed
                + "GOOD-4,Same barcode,EA,,,,111\n";      // row 7: barcode repeated
        String body = importCsv(admin, "products", "p.csv", csv).andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"row\":3", "\"row\":4", "\"row\":5", "\"row\":6", "\"row\":7")
                .contains("\"field\":\"weight_kg\"", "\"field\":\"track_expiry\"", "\"field\":\"units\"");
        send(admin, get("/api/v1/products")).andExpect(jsonPath("$.total").value(0)); // GOOD-1 was not saved either
    }

    @Test
    void onlyRealCsvTextWithKnownColumnsIsRead() throws Exception {
        importCsv(admin, "products", "p.xlsx", "sku,name,base_uom\nA,B,EA\n").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
        mvc.perform(multipart("/api/v1/products/import").file(new MockMultipartFile("file", "p.csv", "text/csv",
                new byte[] {'P', 'K', 3, 4, (byte) 0xFF, 0})).with(csrf()).cookie(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CSV_NOT_TEXT"));
        importCsv(admin, "products", "p.csv", "sku,name,base_uom,price\nA,B,EA,1\n").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CSV_BAD_HEADER"));
        importCsv(admin, "products", "p.csv", "sku,name\nA,B\n").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CSV_BAD_HEADER"));
        importCsv(admin, "suppliers", "s.csv", "code,name\n").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CSV_NO_ROWS"));
    }

    @Test
    void suppliersImportAndViewersCannotImport() throws Exception {
        importCsv(admin, "suppliers", "s.csv", "code,name,contact,phone,email,address\n"
                + "sup-001,Lanka Oils,Nimal,+94 11 234 5678,orders@lankaoils.lk,\"12 Galle Road, Colombo 03\"\n"
                + "SUP-002,Rice Mill,,,,\n").andExpect(status().isOk()).andExpect(jsonPath("$.created").value(2));
        importCsv(admin, "suppliers", "s.csv", "code,name\nSUP-002,Rice Mill Ltd\n").andExpect(jsonPath("$.updated").value(1));
        send(admin, get("/api/v1/suppliers?q=SUP-002")).andExpect(jsonPath("$.items[0].name").value("Rice Mill Ltd"));
        Cookie viewer = login(newUser(tenant, "VIEWER"));
        importCsv(viewer, "suppliers", "s.csv", "code,name\nX,Y\n").andExpect(status().isForbidden());
    }

    @Test
    void templatesDownloadAndExportedCellsAreFormulaSafe() throws Exception {
        String products = send(admin, get("/api/v1/products/import-template.csv")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(products).startsWith("﻿\"sku\",\"name\"").contains("CASE=12:14791234000018");
        // the same file imports cleanly: what we hand out, we accept
        importCsv(admin, "products", "t.csv", products.substring(1)).andExpect(jsonPath("$.saved").value(true));
        String suppliers = send(admin, get("/api/v1/suppliers/import-template.csv")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(suppliers).contains("\"'+94 11 234 5678\""); // the phone starts with + so it is escaped
        importCsv(admin, "suppliers", "t.csv", suppliers.substring(1)).andExpect(jsonPath("$.saved").value(true));
        send(admin, get("/api/v1/suppliers")).andExpect(jsonPath("$.items[0].phone").value("+94 11 234 5678"));
    }

    @Test
    void aBarcodeOwnedByAnotherProductIsAnErrorButItsOwnBarcodeIsFine() throws Exception {
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Oil\",\"barcode\":\"111\",\"baseUom\":\"EA\","
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[{\"uom\":\"CASE\",\"factor\":12,\"barcode\":\"222\"}]}"))
                .andExpect(status().isCreated());
        importCsv(admin, "products", "p.csv", "sku,name,base_uom,barcode,units\nOIL-1L,Oil renamed,EA,111,CASE=12:222\n")
                .andExpect(jsonPath("$.saved").value(true)).andExpect(jsonPath("$.updated").value(1));
        importCsv(admin, "products", "p.csv", "sku,name,base_uom,barcode,units\nNEW-1,New,EA,,BOX=6:222\n")
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.errors[0].message").value("Barcode 222 belongs to OIL-1L"));
    }
}

package lk.wms.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
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

/** v1.0 task 4: packing — scans checked against what was picked, delivery note number, packing slip and delivery note PDFs. */
class PackingTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    long tenant;
    AppUser adminUser;
    Cookie admin;
    Cookie packer;
    String warehouse;
    long warehouseId;
    List<Long> bins;
    List<String> codes;

    @BeforeEach
    void pickedOrderWaiting() throws Exception {
        tenant = newTenant(3);
        jdbc.update("UPDATE tenant SET name = 'Lanka Distributors' WHERE id = ?", tenant);
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
                + ",\"openings\":[],\"zones\":[{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":" + poly + "}],\"racks\":["
                + "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":5000,\"y\":5000,\"rotation\":0}"
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        bins = jdbc.queryForList("SELECT id FROM bin WHERE warehouse_id = ? ORDER BY code", Long.class, warehouseId);
        codes = jdbc.queryForList("SELECT code FROM bin WHERE warehouse_id = ? ORDER BY code", String.class, warehouseId);
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Coconut oil 1L\",\"baseUom\":\"EA\",\"barcode\":\"4791\","
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[{\"uom\":\"CASE\",\"factor\":12,\"barcode\":\"14791\"}]}"))
                .andExpect(status().isCreated());
        send(admin, post("/api/v1/products").content("{\"sku\":\"RICE\",\"name\":\"Samba rice\",\"baseUom\":\"BAG\",\"trackBatch\":false,"
                + "\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());
        stockIn("OIL-1L", bins.get(0), 30);
        stockIn("RICE", bins.get(1), 10);
        send(admin, post("/api/v1/customers").content("{\"code\":\"C1\",\"name\":\"Kandy Super\",\"address\":\"45 Peradeniya Rd, Kandy\"}"))
                .andExpect(status().isCreated());
        AppUser packerUser = newUser(tenant, "PACKER");
        send(admin, put("/api/v1/warehouses/" + warehouse + "/users").content("{\"users\":[\"" + packerUser.getPublicId() + "\"]}"))
                .andExpect(status().isOk());
        packer = login(packerUser);
    }

    private void stockIn(String sku, long bin, int qty) {
        long product = jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = ?", Long.class, tenant, sku);
        TenantContext.callAs(tenant, () -> {
            moves.move(new Move(Kind.RECEIVE, warehouseId, product, null, null, null, Place.RECEIVING, new BigDecimal(qty), null, null, null, adminUser.getId()));
            moves.move(new Move(Kind.PUTAWAY, warehouseId, product, null, null, Place.RECEIVING, Place.bin(bin), new BigDecimal(qty), null, null, null, adminUser.getId()));
            return null;
        });
    }

    /** An order of 2 cases of oil and 4 bags of rice, released and picked (rice one short); returns its id. */
    private String pickedOrder(String ref) throws Exception {
        String so = read(send(admin, post("/api/v1/sales-orders").content("{\"customerCode\":\"C1\",\"customerRef\":\"" + ref
                + "\",\"warehouseId\":\"" + warehouse + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"CASE\",\"qty\":2},"
                + "{\"sku\":\"RICE\",\"uom\":\"BAG\",\"qty\":4}]}")).andExpect(status().isCreated()), "$.id");
        String list = read(send(admin, post("/api/v1/sales-orders/" + so + "/release").content("{\"version\":0}")), "$.pick.id");
        send(admin, post("/api/v1/pick-lists/" + list + "/lines/1/confirm").header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"binCode\":\"" + codes.get(0) + "\",\"code\":\"14791\",\"qty\":2}")).andExpect(status().isOk());
        send(admin, post("/api/v1/pick-lists/" + list + "/lines/2/confirm").header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"binCode\":\"" + codes.get(1) + "\",\"code\":\"RICE\",\"qty\":3}")).andExpect(status().isOk());
        send(admin, post("/api/v1/pick-lists/" + list + "/lines/2/short").content("{\"reason\":\"DAMAGED\"}")).andExpect(status().isOk());
        return so;
    }

    private static String text(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        StringBuilder all = new StringBuilder();
        PdfTextExtractor ex = new PdfTextExtractor(reader);
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            all.append(ex.getTextFromPage(page)).append('\n');
        }
        return all.toString();
    }

    @Test
    void scansMustMatchWhatWasPickedThenTheOrderGetsItsDeliveryNote() throws Exception {
        String so = pickedOrder("KSM-1");
        send(packer, get("/api/v1/sales-orders/to-pack")).andExpect(jsonPath("$.total").value(1));
        send(packer, get("/api/v1/sales-orders/" + so + "/pack")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sku").value("OIL-1L")).andExpect(jsonPath("$.items[0].picked").value(24))
                .andExpect(jsonPath("$.items[0].codes[?(@.code == '14791')].factor").value(12))
                .andExpect(jsonPath("$.items[1].ordered").value(4)).andExpect(jsonPath("$.items[1].picked").value(3));
        send(packer, get("/api/v1/sales-orders/" + so + "/delivery-note.pdf")).andExpect(jsonPath("$.code").value("SO_NOT_PACKED"));
        int version = read(send(admin, get("/api/v1/sales-orders/" + so)), "$.version");

        send(packer, post("/api/v1/sales-orders/" + so + "/pack").content("{\"version\":" + version + ",\"cartons\":2,\"counts\":["
                + "{\"sku\":\"OIL-1L\",\"qty\":23},{\"sku\":\"MILK\",\"qty\":1},{\"sku\":\"RICE\",\"qty\":3}]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PACK_MISMATCH"))
                .andExpect(jsonPath("$.detail").value("OIL-1L: scanned 23, picked 24 EA; MILK: scanned 1, not on this order"));
        send(packer, post("/api/v1/sales-orders/" + so + "/pack").content("{\"version\":" + version + ",\"cartons\":0,\"counts\":["
                + "{\"sku\":\"OIL-1L\",\"qty\":24}]}")).andExpect(status().isBadRequest());
        send(packer, post("/api/v1/sales-orders/" + so + "/pack").content("{\"version\":" + version + ",\"cartons\":2,\"weightG\":32500,"
                + "\"counts\":[{\"sku\":\"oil-1l\",\"qty\":12},{\"sku\":\"OIL-1L\",\"qty\":12},{\"sku\":\"RICE\",\"qty\":3}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PACKED"));
        send(admin, get("/api/v1/sales-orders/" + so)).andExpect(jsonPath("$.status").value("PACKED"))
                .andExpect(jsonPath("$.deliveryNoteNo").value("DN-000001")).andExpect(jsonPath("$.cartons").value(2));
        send(packer, post("/api/v1/sales-orders/" + so + "/pack").content("{\"version\":" + (version + 1) + ",\"cartons\":2,\"counts\":["
                + "{\"sku\":\"OIL-1L\",\"qty\":24},{\"sku\":\"RICE\",\"qty\":3}]}")).andExpect(jsonPath("$.code").value("SO_NOT_PICKED"));
        send(packer, get("/api/v1/sales-orders/to-pack")).andExpect(jsonPath("$.total").value(0));

        byte[] note = send(packer, get("/api/v1/sales-orders/" + so + "/delivery-note.pdf")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"DN-000001.pdf\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; object-src 'self'; frame-ancestors 'none'"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(note, 0, 5)).isEqualTo("%PDF-");
        assertThat(text(note)).contains("Lanka Distributors", "DELIVERY NOTE", "DN-000001", "Kandy Super", "45 Peradeniya Rd, Kandy",
                "KSM-1", "OIL-1L", "24", "RICE", "Received in good condition");
        byte[] slip = send(packer, get("/api/v1/sales-orders/" + so + "/packing-slip.pdf")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(text(slip)).contains("PACKING SLIP", "DN-000001", "Coconut oil 1L", "32.5 kg");
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE tenant_id = ? AND action = 'SO_PACKED'", String.class, tenant)).hasSize(1);
    }

    @Test
    void onlyPickedOrdersPackAndAccessFollowsRolesAndCompany() throws Exception {
        String draft = read(send(admin, post("/api/v1/sales-orders").content("{\"customerCode\":\"C1\",\"warehouseId\":\"" + warehouse
                + "\",\"lines\":[{\"sku\":\"RICE\",\"uom\":\"BAG\",\"qty\":1}]}")), "$.id");
        send(packer, post("/api/v1/sales-orders/" + draft + "/pack").content("{\"version\":0,\"cartons\":1,\"counts\":[{\"sku\":\"RICE\",\"qty\":1}]}"))
                .andExpect(jsonPath("$.code").value("SO_NOT_PICKED"));
        String so = pickedOrder("KSM-2");
        Cookie receiver = login(newUser(tenant, "RECEIVER"));
        send(receiver, get("/api/v1/sales-orders/to-pack")).andExpect(status().isForbidden());
        send(receiver, get("/api/v1/sales-orders/" + so + "/pack")).andExpect(status().isForbidden());
        Cookie picker = login(newUser(tenant, "PICKER"));
        send(picker, post("/api/v1/sales-orders/" + so + "/pack").content("{\"version\":3,\"cartons\":1,\"counts\":[{\"sku\":\"RICE\",\"qty\":3}]}"))
                .andExpect(status().isForbidden());
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/sales-orders/" + so + "/pack")).andExpect(status().isNotFound());
        send(other, get("/api/v1/sales-orders/" + so + "/delivery-note.pdf")).andExpect(status().isNotFound());
    }
}

package lk.wms.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import lk.wms.ApiTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

/** v0.2 task 4: bin label PDFs through HTTP. One rack R01 in zone A: 4 levels x 3 bins = 12 labels. */
class LabelApiTest extends ApiTest {

    long tenant;
    Cookie admin;
    String warehouse;

    @BeforeEach
    void publishedRack() throws Exception {
        tenant = newTenant(2);
        admin = login(newUser(tenant, "TENANT_ADMIN"));
        warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}"))
                .andExpect(status().isCreated()), "$.id");
        String type = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"PAL\",\"name\":\"Pallet\","
                + "\"widthMm\":2700,\"depthMm\":1100,\"heightMm\":6000,\"levels\":4,\"binsPerLevel\":3,"
                + "\"maxWeightPerLevelG\":1000000}")).andExpect(status().isCreated()), "$.id");
        String floor = "[{\"x\":0,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":20000},{\"x\":0,\"y\":20000}]";
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":"
                + floor + ",\"openings\":[],\"zones\":[{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":" + floor
                + "}],\"racks\":[{\"code\":\"R01\",\"templateId\":\"" + type
                + "\",\"zone\":\"A\",\"x\":5000,\"y\":5000,\"rotation\":0}],\"settings\":{\"minAisleMm\":1200}}}"))
                .andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}"))
                .andExpect(status().isOk());
    }

    @Test
    void downloadsAPdfWithEveryMatchingLabel() throws Exception {
        byte[] pdf = send(admin, get("/api/v1/warehouses/" + warehouse + "/labels.pdf?code=A-R01-02"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"labels-WH1.pdf\""))
                .andReturn().getResponse().getContentAsByteArray();
        PdfReader reader = new PdfReader(pdf);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        assertThat(text).contains("A-R01-02-01", "A-R01-02-03").doesNotContain("A-R01-01-01"); // level 2 only

        byte[] singles = send(admin, get("/api/v1/warehouses/" + warehouse + "/labels.pdf?format=single"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new PdfReader(singles).getNumberOfPages()).isEqualTo(12);
    }

    @Test
    void refusesWhenNothingMatchesOrTheFormatIsUnknown() throws Exception {
        send(admin, get("/api/v1/warehouses/" + warehouse + "/labels.pdf?code=Z-")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_BINS"));
        send(admin, get("/api/v1/warehouses/" + warehouse + "/labels.pdf?format=poster")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void needsLabelPermissionAndWarehouseAccess() throws Exception {
        Cookie picker = login(newUser(tenant, "PICKER")); // no labels:print
        send(picker, get("/api/v1/warehouses/" + warehouse + "/labels.pdf")).andExpect(status().isForbidden());
        Cookie supervisor = login(newUser(tenant, "SUPERVISOR")); // has labels:print, not assigned to WH1
        send(supervisor, get("/api/v1/warehouses/" + warehouse + "/labels.pdf")).andExpect(status().isNotFound());
        Cookie otherAdmin = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(otherAdmin, get("/api/v1/warehouses/" + warehouse + "/labels.pdf")).andExpect(status().isNotFound());
    }
}

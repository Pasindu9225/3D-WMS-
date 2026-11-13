package lk.wms.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import lk.wms.ApiTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * v0.2 task 3: publish turns the draft into zones, racks, levels and bins.
 * Floor 40 x 20 m, zone A = left half, zone B = right half. Rack type: 4 levels x 3 bins, 6 m high,
 * 2.7 x 1.1 m, 1 t per level.
 */
class LayoutPublishTest extends ApiTest {

    long tenant;
    Cookie admin;
    String warehouse;
    String type;

    @BeforeEach
    void warehouseWithRackType() throws Exception {
        tenant = newTenant(3);
        admin = login(newUser(tenant, "TENANT_ADMIN"));
        warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}"))
                .andExpect(status().isCreated()), "$.id");
        type = read(send(admin, post("/api/v1/rack-templates").content(typeJson("PAL", 4))).andExpect(status().isCreated()),
                "$.id");
    }

    @Test
    void firstPublishCreatesEveryBinWithCodeAndCapacity() throws Exception {
        save(0, rack("R01", "A", 5000, 5000), rack("R02", "A", 5000, 8000));
        publish(1).andExpect(status().isOk())
                .andExpect(jsonPath("$.racks").value(2)).andExpect(jsonPath("$.levels").value(8))
                .andExpect(jsonPath("$.bins").value(24)).andExpect(jsonPath("$.binsAdded").value(24));

        assertThat(count("bin WHERE warehouse_id = " + warehouseId())).isEqualTo(24);
        String first = read(send(admin, get("/api/v1/warehouses/" + warehouse + "/bins?code=A-R01-01-01")), "$.items[0].code");
        assertThat(first).isEqualTo("A-R01-01-01");
        // 1 t per level / 3 bins; 900 x 1100 mm footprint x 1500 mm level height = 1,485,000 cm³
        send(admin, get("/api/v1/warehouses/" + warehouse + "/bins?code=A-R02-04-03"))
                .andExpect(jsonPath("$.items[0].maxWeightG").value(333333))
                .andExpect(jsonPath("$.items[0].maxVolumeCm3").value(1485000));
        assertThat(jdbc.queryForObject("SELECT height_from_floor_mm FROM rack_level l JOIN rack r ON r.id = l.rack_id"
                + " WHERE r.code = 'R01' AND l.level_no = 3 AND r.warehouse_id = ?", Integer.class, warehouseId())).isEqualTo(3000);

        send(admin, get("/api/v1/warehouses/" + warehouse)).andExpect(jsonPath("$.layoutStatus").value("PUBLISHED"));
        send(admin, get("/api/v1/warehouses/" + warehouse + "/layout"))
                .andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.revision").value(0));
        assertThat(count("audit_log WHERE action = 'LAYOUT_PUBLISHED' AND entity_id = '" + warehouse + "'")).isOne();
    }

    @Test
    void binsKeepTheirIdAndHandEditsWhenARackMovesToAnotherZone() throws Exception {
        save(0, rack("R01", "A", 5000, 5000));
        publish(1).andExpect(status().isOk());
        String binId = read(send(admin, get("/api/v1/warehouses/" + warehouse + "/bins?code=A-R01-01-01")), "$.items[0].id");
        send(admin, patch("/api/v1/bins/" + binId).content("{\"blocked\":true,\"maxWeightG\":500000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customCapacity").value(true));

        save(0, rack("R01", "B", 25000, 5000)); // new draft from the published layout
        publish(1).andExpect(status().isOk())
                .andExpect(jsonPath("$.binsAdded").value(0)).andExpect(jsonPath("$.binsRemoved").value(0))
                .andExpect(jsonPath("$.binsRenamed").value(12));

        send(admin, get("/api/v1/warehouses/" + warehouse + "/bins?code=B-R01-01-01"))
                .andExpect(jsonPath("$.items[0].id").value(binId)) // same bin, new code
                .andExpect(jsonPath("$.items[0].blocked").value(true))
                .andExpect(jsonPath("$.items[0].maxWeightG").value(500000)); // hand edit kept
        send(admin, get("/api/v1/warehouses/" + warehouse + "/bins?code=A-")).andExpect(jsonPath("$.total").value(0));
        assertThat(count("zone WHERE warehouse_id = " + warehouseId())).isEqualTo(2);
    }

    @Test
    void fewerLevelsAndRemovedRacksRemoveTheirBins() throws Exception {
        save(0, rack("R01", "A", 5000, 5000), rack("R02", "A", 5000, 8000));
        publish(1).andExpect(status().isOk());
        send(admin, put("/api/v1/rack-templates/" + type).content(typeJson(null, 2))).andExpect(status().isOk());

        save(0, rack("R01", "A", 5000, 5000), rack("R02", "A", 5000, 8000));
        publish(1).andExpect(jsonPath("$.bins").value(12)).andExpect(jsonPath("$.binsRemoved").value(12));
        save(0, rack("R01", "A", 5000, 5000));
        publish(1).andExpect(jsonPath("$.bins").value(6)).andExpect(jsonPath("$.binsRemoved").value(6));
        assertThat(count("bin WHERE warehouse_id = " + warehouseId())).isEqualTo(6);
        assertThat(count("rack WHERE warehouse_id = " + warehouseId())).isOne();
    }

    @Test
    void publishNeedsTheCurrentValidDraft() throws Exception {
        publish(1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_DRAFT"));
        save(0, rack("R01", "A", 5000, 5000));
        publish(7).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAYOUT_CHANGED"));
        send(admin, post("/api/v1/rack-templates/" + type + "/archive")).andExpect(status().isNoContent());
        publish(1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.issues[0].code").value("RACK_UNKNOWN_TEMPLATE"));
        assertThat(count("bin WHERE warehouse_id = " + warehouseId())).isZero(); // nothing half-published
    }

    @Test
    void binsAreVisibleToAssignedReadersAndEditableOnlyByManagers() throws Exception {
        save(0, rack("R01", "A", 5000, 5000));
        publish(1).andExpect(status().isOk());
        String binId = read(send(admin, get("/api/v1/warehouses/" + warehouse + "/bins?size=1")), "$.items[0].id");

        Cookie stranger = login(newUser(tenant, "PICKER")); // created after the warehouse: not assigned
        send(stranger, get("/api/v1/warehouses/" + warehouse + "/bins")).andExpect(status().isNotFound());
        Cookie viewer = login(newUser(tenant, "VIEWER"));
        String viewerPublicId = jdbc.queryForObject("SELECT public_id FROM app_user WHERE tenant_id = ? AND full_name = 'User VIEWER'",
                String.class, tenant);
        send(admin, put("/api/v1/warehouses/" + warehouse + "/users").content("{\"users\":[\"" + viewerPublicId + "\"]}"))
                .andExpect(status().isOk());
        send(viewer, get("/api/v1/warehouses/" + warehouse + "/bins")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(12));
        send(viewer, patch("/api/v1/bins/" + binId).content("{\"blocked\":true}")).andExpect(status().isForbidden());

        Cookie otherAdmin = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(otherAdmin, patch("/api/v1/bins/" + binId).content("{\"blocked\":true}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BIN_NOT_FOUND"));
        send(admin, patch("/api/v1/bins/" + binId).content("{\"maxWeightG\":0}")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- helpers

    private void save(int base, String... racks) throws Exception {
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":" + base + ",\"layout\":"
                + layout(racks) + "}")).andExpect(status().isOk());
    }

    private ResultActions publish(int base) throws Exception {
        return send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":" + base + "}"));
    }

    private static String layout(String... racks) {
        String floor = poly(0, 0, 40000, 20000);
        return "{\"floor\":" + floor + ",\"openings\":[],\"zones\":["
                + "{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"Left\",\"polygon\":" + poly(0, 0, 20000, 20000) + "},"
                + "{\"code\":\"B\",\"type\":\"PICKING\",\"name\":\"Right\",\"polygon\":" + poly(20000, 0, 40000, 20000) + "}],"
                + "\"racks\":[" + String.join(",", racks) + "],\"settings\":{\"minAisleMm\":1200}}";
    }

    private static String poly(int x1, int y1, int x2, int y2) {
        return "[{\"x\":" + x1 + ",\"y\":" + y1 + "},{\"x\":" + x2 + ",\"y\":" + y1 + "},{\"x\":" + x2 + ",\"y\":" + y2
                + "},{\"x\":" + x1 + ",\"y\":" + y2 + "}]";
    }

    private String rack(String code, String zone, int x, int y) {
        return "{\"code\":\"" + code + "\",\"templateId\":\"" + type + "\",\"zone\":\"" + zone + "\",\"x\":" + x
                + ",\"y\":" + y + ",\"rotation\":0}";
    }

    private static String typeJson(String code, int levels) {
        return "{" + (code == null ? "" : "\"code\":\"" + code + "\",") + "\"name\":\"Pallet\",\"widthMm\":2700,"
                + "\"depthMm\":1100,\"heightMm\":6000,\"levels\":" + levels + ",\"binsPerLevel\":3,\"maxWeightPerLevelG\":1000000}";
    }

    private long warehouseId() {
        return jdbc.queryForObject("SELECT id FROM warehouse WHERE public_id = ?", Long.class, warehouse);
    }

    private int count(String fromWhere) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + fromWhere, Integer.class);
    }
}

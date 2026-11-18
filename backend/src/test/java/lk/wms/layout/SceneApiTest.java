package lk.wms.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import lk.wms.ApiTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v0.3 task 1: the 3D scene. One rack R01 (2.7 x 1.1 x 6 m, turned 30°) in zone A: 4 levels x 3 bins = 12 bins,
 * and a front door.
 */
class SceneApiTest extends ApiTest {

    long tenant;
    Cookie admin;
    String warehouse;
    String type;

    @BeforeEach
    void warehouseWithLayout() throws Exception {
        tenant = newTenant(2);
        admin = login(newUser(tenant, "TENANT_ADMIN"));
        warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}"))
                .andExpect(status().isCreated()), "$.id");
        type = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"PAL\",\"name\":\"Pallet\","
                + "\"widthMm\":2700,\"depthMm\":1100,\"heightMm\":6000,\"levels\":4,\"binsPerLevel\":3,"
                + "\"maxWeightPerLevelG\":1000000}")).andExpect(status().isCreated()), "$.id");
        String floor = "[{\"x\":0,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":20000},{\"x\":0,\"y\":20000}]";
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":"
                + floor + ",\"openings\":[{\"kind\":\"DOOR\",\"label\":\"Front\",\"x\":20000,\"y\":20000,\"widthMm\":1200}],"
                + "\"zones\":[{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"Bulk\",\"polygon\":" + floor
                + "}],\"racks\":[{\"code\":\"R01\",\"templateId\":\"" + type
                + "\",\"zone\":\"A\",\"x\":5000,\"y\":5000,\"rotation\":30}],\"settings\":{\"minAisleMm\":1200}}}"))
                .andExpect(status().isOk());
    }

    @Test
    void sceneNeedsAPublishedLayoutThenReturnsCompactArrays() throws Exception {
        send(admin, get("/api/v1/warehouses/" + warehouse + "/scene")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_PUBLISHED"));
        publish();

        String body = send(admin, get("/api/v1/warehouses/" + warehouse + "/scene")).andExpect(status().isOk())
                .andExpect(jsonPath("$.floor.length()").value(8))
                .andExpect(jsonPath("$.wallHeightMm").value(8000))
                .andExpect(jsonPath("$.openings[0].label").value("Front"))
                .andExpect(jsonPath("$.zones[0].code").value("A"))
                .andExpect(jsonPath("$.zones[0].polygon[2]").value(40000))
                .andExpect(jsonPath("$.racks[0].code").value("R01"))
                .andExpect(jsonPath("$.racks[0].rotation").value(30))
                .andExpect(jsonPath("$.racks[0].binsPerLevel").value(3))
                .andExpect(jsonPath("$.racks[0].levels.length()").value(4))
                .andExpect(jsonPath("$.racks[0].levels[1]").value(1500)) // 6 m over 4 levels
                .andExpect(jsonPath("$.bins.ids.length()").value(12))
                .andExpect(jsonPath("$.flags").value("000000000000"))
                .andReturn().getResponse().getContentAsString();
        // v2.1: each rack carries its type's 3D look (classic pallet racking by default) and follows changes without a new publish
        send(admin, get("/api/v1/warehouses/" + warehouse + "/scene")).andExpect(jsonPath("$.racks[0].style").value("PALLET"))
                .andExpect(jsonPath("$.racks[0].uprightColor").value("#1d4ed8")).andExpect(jsonPath("$.racks[0].beamColor").value("#f97316"));
        String look = "{\"name\":\"Shelving\",\"widthMm\":2700,\"depthMm\":1100,\"heightMm\":6000,\"levels\":4,\"binsPerLevel\":3,\"maxWeightPerLevelG\":1000000,";
        send(admin, put("/api/v1/rack-templates/" + type).content(look + "\"style\":\"SHELF\",\"uprightColor\":\"red\"}")).andExpect(status().isBadRequest());
        send(admin, put("/api/v1/rack-templates/" + type).content(look + "\"style\":\"CANTILEVER\"}")).andExpect(status().isBadRequest());
        send(admin, put("/api/v1/rack-templates/" + type).content(look + "\"style\":\"SHELF\",\"uprightColor\":\"#22C55E\",\"beamColor\":\"#64748b\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.style").value("SHELF")).andExpect(jsonPath("$.uprightColor").value("#22c55e"));
        send(admin, get("/api/v1/warehouses/" + warehouse + "/scene")).andExpect(jsonPath("$.racks[0].style").value("SHELF"))
                .andExpect(jsonPath("$.racks[0].uprightColor").value("#22c55e")).andExpect(jsonPath("$.racks[0].beamColor").value("#64748b"));
        // v2.1, light on the internet: the browser keeps the layout; asking again with its ETag gets 304 and no body
        var first = send(admin, get("/api/v1/warehouses/" + warehouse + "/scene")).andExpect(status().isOk()).andReturn().getResponse();
        String etag = first.getHeader("ETag");
        assertThat(etag).isNotBlank();
        assertThat(first.getHeader("Cache-Control")).contains("private").contains("no-cache").doesNotContain("no-store");
        var again = send(admin, get("/api/v1/warehouses/" + warehouse + "/scene").header("If-None-Match", etag)).andReturn().getResponse();
        assertThat(again.getStatus()).isEqualTo(304);
        assertThat(again.getContentAsByteArray()).isEmpty();
        List<String> codes = jsonList(body, "$.bins.codes");
        List<Integer> level = jsonList(body, "$.bins.level");
        List<Integer> pos = jsonList(body, "$.bins.pos");
        int i = codes.indexOf("A-R01-03-02");
        assertThat(level.get(i)).isEqualTo(2); // 0-based: third level
        assertThat(pos.get(i)).isEqualTo(1); // second slot
    }

    @Test
    void binStatusFollowsBinEditsInTheSceneOrder() throws Exception {
        publish();
        String scene = send(admin, get("/api/v1/warehouses/" + warehouse + "/scene")).andReturn().getResponse()
                .getContentAsString();
        List<String> ids = jsonList(scene, "$.bins.ids");
        send(admin, patch("/api/v1/bins/" + ids.get(4)).content("{\"blocked\":true,\"pickFace\":true}"))
                .andExpect(status().isOk());
        send(admin, patch("/api/v1/bins/" + ids.get(7)).content("{\"pickFace\":true}")).andExpect(status().isOk());
        send(admin, get("/api/v1/warehouses/" + warehouse + "/bin-status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.flags").value("000030020000"));
    }

    @Test
    void onlyPeopleWhoCanSeeTheWarehouseGetItsScene() throws Exception {
        publish();
        Cookie stranger = login(newUser(tenant, "PICKER")); // created after the warehouse: not assigned
        send(stranger, get("/api/v1/warehouses/" + warehouse + "/scene")).andExpect(status().isNotFound());
        send(stranger, get("/api/v1/warehouses/" + warehouse + "/bin-status")).andExpect(status().isNotFound());
        Cookie otherAdmin = login(newUser(newTenant(3), "TENANT_ADMIN"));
        send(otherAdmin, get("/api/v1/warehouses/" + warehouse + "/scene")).andExpect(status().isNotFound());
        send(otherAdmin, get("/api/v1/warehouses/" + warehouse + "/bin-status")).andExpect(status().isNotFound());
    }

    @Test
    void floorStorageRowsArePlacesWithoutARack() throws Exception {
        // v2.1: a FLOOR rack type = 4 pallet positions side by side, stacked 3 high; the layout places it like a rack
        String floorType = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"FL\",\"name\":\"Floor row\","
                + "\"widthMm\":5000,\"depthMm\":1200,\"heightMm\":4500,\"levels\":3,\"binsPerLevel\":4,"
                + "\"maxWeightPerLevelG\":4000000,\"style\":\"FLOOR\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.style").value("FLOOR")), "$.id");
        String floor = "[{\"x\":0,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":20000},{\"x\":0,\"y\":20000}]";
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":1,\"layout\":{\"floor\":" + floor
                + ",\"openings\":[],\"zones\":[{\"code\":\"F\",\"type\":\"STORAGE\",\"name\":\"Floor\",\"polygon\":" + floor
                + "}],\"racks\":[{\"code\":\"01\",\"templateId\":\"" + floorType + "\",\"zone\":\"F\",\"x\":10000,\"y\":10000,\"rotation\":0}],"
                + "\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":2}")).andExpect(status().isOk());
        String body = send(admin, get("/api/v1/warehouses/" + warehouse + "/scene")).andExpect(status().isOk())
                .andExpect(jsonPath("$.racks[0].style").value("FLOOR"))
                .andExpect(jsonPath("$.racks[0].levels.length()").value(3))
                .andExpect(jsonPath("$.bins.ids.length()").value(12)) // 4 positions x 3 high
                .andReturn().getResponse().getContentAsString();
        assertThat(jsonList(body, "$.bins.codes")).contains("F-01-01-01", "F-01-03-04"); // same code scheme as racks
        send(admin, get("/api/v1/warehouses/" + warehouse + "/bins?code=F-01-01")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4));
    }

    private void publish() throws Exception {
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}"))
                .andExpect(status().isOk());
    }

    private static <T> List<T> jsonList(String json, String path) {
        return com.jayway.jsonpath.JsonPath.read(json, path);
    }
}

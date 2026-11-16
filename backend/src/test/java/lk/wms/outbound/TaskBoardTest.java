package lk.wms.outbound;

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
import lk.wms.common.tenant.TenantContext;
import lk.wms.inventory.service.StockMovementService;
import lk.wms.inventory.service.StockMovementService.Kind;
import lk.wms.inventory.service.StockMovementService.Move;
import lk.wms.inventory.service.StockMovementService.Place;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** v1.0 task 6: task board — give pick lists to pickers, priorities, who sees what, live counts. */
class TaskBoardTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    long tenant;
    AppUser adminUser;
    Cookie admin;
    Cookie supervisor;
    AppUser nimal;
    AppUser kamal;
    Cookie nimalC;
    Cookie kamalC;
    String warehouse;
    long warehouseId;
    List<String> codes;

    @BeforeEach
    void warehouseWithPickers() throws Exception {
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
                + ",\"openings\":[],\"zones\":[{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":" + poly + "}],\"racks\":["
                + "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":5000,\"y\":5000,\"rotation\":0}"
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        List<Long> bins = jdbc.queryForList("SELECT id FROM bin WHERE warehouse_id = ? ORDER BY code", Long.class, warehouseId);
        codes = jdbc.queryForList("SELECT code FROM bin WHERE warehouse_id = ? ORDER BY code", String.class, warehouseId);
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Oil\",\"baseUom\":\"EA\",\"barcode\":\"4791\","
                + "\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}")).andExpect(status().isCreated());
        long oil = jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = 'OIL-1L'", Long.class, tenant);
        TenantContext.callAs(tenant, () -> {
            moves.move(new Move(Kind.RECEIVE, warehouseId, oil, null, null, null, Place.RECEIVING, new BigDecimal(50), null, null, null, adminUser.getId()));
            moves.move(new Move(Kind.PUTAWAY, warehouseId, oil, null, null, Place.RECEIVING, Place.bin(bins.get(0)), new BigDecimal(40), null, null, null, adminUser.getId()));
            return null;
        });
        send(admin, post("/api/v1/customers").content("{\"code\":\"C1\",\"name\":\"Kandy Super\"}")).andExpect(status().isCreated());
        nimal = newUser(tenant, "PICKER");
        kamal = newUser(tenant, "PICKER");
        AppUser sup = newUser(tenant, "SUPERVISOR");
        send(admin, put("/api/v1/warehouses/" + warehouse + "/users").content("{\"users\":[\"" + nimal.getPublicId() + "\",\""
                + kamal.getPublicId() + "\",\"" + sup.getPublicId() + "\"]}")).andExpect(status().isOk());
        nimalC = login(nimal);
        kamalC = login(kamal);
        supervisor = login(sup);
    }

    private String released(int qty) throws Exception {
        String so = read(send(admin, post("/api/v1/sales-orders").content("{\"customerCode\":\"C1\",\"warehouseId\":\"" + warehouse
                + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":" + qty + "}]}")).andExpect(status().isCreated()), "$.id");
        return read(send(admin, post("/api/v1/sales-orders/" + so + "/release").content("{\"version\":0}")).andExpect(status().isOk()), "$.pick.id");
    }

    private void confirm(Cookie who, String list, int status) throws Exception {
        send(who, post("/api/v1/pick-lists/" + list + "/lines/1/confirm").header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"binCode\":\"" + codes.get(0) + "\",\"code\":\"4791\",\"qty\":1}")).andExpect(status().is(status));
    }

    @Test
    void givenListsBelongToTheirPickerAndUrgentComesFirst() throws Exception {
        String first = released(2);
        String second = released(2);
        send(nimalC, get("/api/v1/pick-lists")).andExpect(jsonPath("$.total").value(2)); // nobody's lists: everyone sees them

        send(supervisor, get("/api/v1/task-board/pickers?warehouse=" + warehouse))
                .andExpect(jsonPath("$[?(@.id == '" + nimal.getPublicId() + "')]").exists());
        send(supervisor, post("/api/v1/pick-lists/" + second + "/assign").content("{\"userId\":\"" + nimal.getPublicId() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignee.id").value(nimal.getPublicId()));
        send(supervisor, post("/api/v1/pick-lists/" + second + "/priority").content("{\"priority\":\"URGENT\"}"))
                .andExpect(jsonPath("$.priority").value("URGENT"));

        send(nimalC, get("/api/v1/pick-lists")).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(second)); // urgent first
        send(kamalC, get("/api/v1/pick-lists")).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(first));
        confirm(kamalC, second, 409);
        confirm(nimalC, second, 200);

        // re-assign mid-way: Kamal carries on, Nimal is out
        send(supervisor, post("/api/v1/pick-lists/" + second + "/assign").content("{\"userId\":\"" + kamal.getPublicId() + "\"}"))
                .andExpect(status().isOk());
        confirm(nimalC, second, 409);
        confirm(kamalC, second, 200);
        send(supervisor, post("/api/v1/pick-lists/" + first + "/assign").content("{\"userId\":null}")).andExpect(jsonPath("$.assignee").isEmpty());
        send(admin, get("/api/v1/audit-logs?size=50")).andExpect(jsonPath("$.items[?(@.action == 'PICK_ASSIGNED')]").exists())
                .andExpect(jsonPath("$.items[?(@.action == 'PICK_PRIORITY')]").exists());
    }

    @Test
    void onlyPickersOfTheWarehouseCanBeGivenAListAndOnlySupervisorsGiveThem() throws Exception {
        String list = released(1);
        AppUser receiver = newUser(tenant, "RECEIVER");
        send(admin, put("/api/v1/warehouses/" + warehouse + "/users").content("{\"users\":[\"" + nimal.getPublicId() + "\",\""
                + receiver.getPublicId() + "\"]}")).andExpect(status().isOk());
        send(supervisor, post("/api/v1/pick-lists/" + list + "/assign").content("{\"userId\":\"" + receiver.getPublicId() + "\"}"))
                .andExpect(status().isNotFound()); // the supervisor is no longer assigned to the warehouse
        send(admin, post("/api/v1/pick-lists/" + list + "/assign").content("{\"userId\":\"" + receiver.getPublicId() + "\"}"))
                .andExpect(jsonPath("$.code").value("USER_NOT_PICKER"));
        send(admin, post("/api/v1/pick-lists/" + list + "/assign").content("{\"userId\":\"" + kamal.getPublicId() + "\"}"))
                .andExpect(jsonPath("$.code").value("USER_NOT_PICKER")); // a picker, but not in this warehouse any more
        send(nimalC, post("/api/v1/pick-lists/" + list + "/assign").content("{\"userId\":\"" + nimal.getPublicId() + "\"}"))
                .andExpect(status().isForbidden());
        send(nimalC, get("/api/v1/task-board?warehouse=" + warehouse)).andExpect(status().isOk()); // pickers may look (tasks:read)
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, post("/api/v1/pick-lists/" + list + "/assign").content("{\"userId\":null}")).andExpect(status().isNotFound());
        send(other, get("/api/v1/task-board?warehouse=" + warehouse)).andExpect(status().isNotFound());
    }

    @Test
    void boardCountsEveryStage() throws Exception {
        String a = released(1);
        released(1);
        send(admin, post("/api/v1/sales-orders").content("{\"customerCode\":\"C1\",\"warehouseId\":\"" + warehouse
                + "\",\"lines\":[{\"sku\":\"OIL-1L\",\"uom\":\"EA\",\"qty\":1}]}")).andExpect(status().isCreated()); // a draft
        confirm(admin, a, 200); // a done: picked
        send(supervisor, get("/api/v1/task-board?warehouse=" + warehouse)).andExpect(status().isOk())
                .andExpect(jsonPath("$.toRelease").value(1)).andExpect(jsonPath("$.toPick").value(1))
                .andExpect(jsonPath("$.picking").value(0)).andExpect(jsonPath("$.pickedToday").value(1))
                .andExpect(jsonPath("$.toPack").value(1)).andExpect(jsonPath("$.toDispatch").value(0))
                .andExpect(jsonPath("$.inReceiving").value(1)).andExpect(jsonPath("$.lists.length()").value(1));
    }
}

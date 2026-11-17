package lk.wms.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lk.wms.ApiTest;
import lk.wms.auth.domain.AppUser;
import lk.wms.common.tenant.TenantContext;
import lk.wms.inventory.service.StockMovementService;
import lk.wms.inventory.service.StockMovementService.Kind;
import lk.wms.inventory.service.StockMovementService.Move;
import lk.wms.inventory.service.StockMovementService.Place;
import lk.wms.notify.service.NotificationTriggers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** v1.2 task 5: in-app notifications (task assigned, low stock, expiry); each person only reaches their own (SEC-09/10). */
class NotificationTest extends ApiTest {

    @Autowired
    StockMovementService moves;

    @Autowired
    NotificationTriggers triggers;

    long tenant;
    AppUser adminUser;
    Cookie admin;
    String warehouse;
    long warehouseId;
    List<Long> bins;

    private void stockIn(String sku, int qty, LocalDate expiry, int bin) {
        long product = jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = ?", Long.class, tenant, sku);
        TenantContext.callAs(tenant, () -> {
            moves.move(new Move(Kind.RECEIVE, warehouseId, product, null, expiry, null, Place.RECEIVING, BigDecimal.valueOf(qty), null, null, null, adminUser.getId()));
            moves.move(new Move(Kind.PUTAWAY, warehouseId, product, null, expiry, Place.RECEIVING, Place.bin(bins.get(bin)), BigDecimal.valueOf(qty), null, null, null, adminUser.getId()));
            return null;
        });
    }

    private void takeOut(String sku, int qty, int bin) {
        long product = jdbc.queryForObject("SELECT id FROM product WHERE tenant_id = ? AND sku = ?", Long.class, tenant, sku);
        TenantContext.callAs(tenant, () -> moves.move(new Move(Kind.ADJUST, warehouseId, product, null, null, Place.bin(bins.get(bin)), null,
                BigDecimal.valueOf(qty), "test", null, null, adminUser.getId())));
    }

    private int count(AppUser u, String kind) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ? AND kind = ?", Integer.class, u.getId(), kind);
    }

    @Test
    void theRightPeopleAreToldOnceAndOnlyReachTheirOwn() throws Exception {
        tenant = newTenant(3);
        adminUser = newUser(tenant, "TENANT_ADMIN");
        admin = login(adminUser);
        warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}")).andExpect(status().isCreated()), "$.id");
        warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE public_id = ?", Long.class, warehouse);
        String type = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"SH\",\"name\":\"Shelf\",\"widthMm\":2700,"
                + "\"depthMm\":1100,\"heightMm\":3000,\"levels\":1,\"binsPerLevel\":3,\"maxWeightPerLevelG\":3000000}"))
                .andExpect(status().isCreated()), "$.id");
        String floor = "[{\"x\":0,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":20000},{\"x\":0,\"y\":20000}]";
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":" + floor
                + ",\"openings\":[],\"zones\":[{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":" + floor + "}],\"racks\":["
                + "{\"code\":\"R01\",\"templateId\":\"" + type + "\",\"zone\":\"A\",\"x\":5000,\"y\":8000,\"rotation\":0}"
                + "],\"settings\":{\"minAisleMm\":1200}}}")).andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}")).andExpect(status().isOk());
        bins = jdbc.queryForList("SELECT id FROM bin WHERE warehouse_id = ? ORDER BY code", Long.class, warehouseId);
        send(admin, post("/api/v1/customers").content("{\"code\":\"C1\",\"name\":\"Kandy Super\"}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/products").content("{\"sku\":\"OIL\",\"name\":\"Coconut oil\",\"baseUom\":\"EA\",\"trackBatch\":false,"
                + "\"trackExpiry\":true,\"reorderPoint\":20,\"units\":[]}")).andExpect(status().isCreated());
        send(admin, post("/api/v1/products").content("{\"sku\":\"RICE\",\"name\":\"Rice\",\"baseUom\":\"EA\",\"trackBatch\":false,"
                + "\"trackExpiry\":true,\"units\":[]}")).andExpect(status().isCreated());

        AppUser pickerUser = newUser(tenant, "PICKER");
        AppUser managerHere = newUser(tenant, "WAREHOUSE_MANAGER");
        AppUser managerElsewhere = newUser(tenant, "WAREHOUSE_MANAGER"); // not given this warehouse
        send(admin, put("/api/v1/warehouses/" + warehouse + "/users").content("{\"users\":[\"" + pickerUser.getPublicId() + "\",\""
                + managerHere.getPublicId() + "\"]}")).andExpect(status().isOk());

        // low stock: told once per product and day, only to people who see this warehouse's dashboard
        stockIn("OIL", 30, null, 0);
        stockIn("RICE", 5, null, 1);
        takeOut("OIL", 5, 0);  // 25 > 20: nothing
        assertThat(count(managerHere, "LOW_STOCK")).isZero();
        takeOut("OIL", 5, 0);  // 20: at the reorder point
        takeOut("OIL", 5, 0);  // 15: same day, not again
        takeOut("RICE", 4, 1); // no reorder point: nothing
        assertThat(count(managerHere, "LOW_STOCK")).isEqualTo(1);
        assertThat(count(adminUser, "LOW_STOCK")).isEqualTo(1); // company admins see every warehouse
        assertThat(count(managerElsewhere, "LOW_STOCK")).isZero();
        assertThat(count(pickerUser, "LOW_STOCK")).isZero(); // no dashboard
        Cookie manager = login(managerHere);
        send(manager, get("/api/v1/notifications")).andExpect(jsonPath("$.unread").value(1)).andExpect(jsonPath("$.items[0].kind").value("LOW_STOCK"))
                .andExpect(jsonPath("$.items[0].params.sku").value("OIL")).andExpect(jsonPath("$.items[0].params.onHand").value("20"))
                .andExpect(jsonPath("$.items[0].params.warehouse").value("WH1")).andExpect(jsonPath("$.items[0].link").value("/app/dashboard"));

        // a pick list given to the picker; a wave of two lists tells them once
        String[] lists = new String[3];
        for (int i = 0; i < 3; i++) {
            String so = read(send(admin, post("/api/v1/sales-orders").content("{\"customerCode\":\"C1\",\"warehouseId\":\"" + warehouse
                    + "\",\"lines\":[{\"sku\":\"OIL\",\"uom\":\"EA\",\"qty\":1}]}")).andExpect(status().isCreated()), "$.id");
            lists[i] = read(send(admin, post("/api/v1/sales-orders/" + so + "/release").content("{\"version\":0}")).andExpect(status().isOk()), "$.pick.id");
        }
        send(admin, post("/api/v1/pick-lists/" + lists[0] + "/assign").content("{\"userId\":\"" + pickerUser.getPublicId() + "\"}")).andExpect(status().isOk());
        send(admin, post("/api/v1/pick-lists/" + lists[0] + "/assign").content("{\"userId\":\"" + pickerUser.getPublicId() + "\"}")).andExpect(status().isOk());
        send(admin, post("/api/v1/pick-lists/" + lists[0] + "/assign").content("{\"userId\":null}")).andExpect(status().isOk());
        assertThat(count(pickerUser, "TASK_ASSIGNED")).isEqualTo(1); // same person again, or nobody: no new alert
        send(admin, post("/api/v1/pick-waves").content("{\"warehouseId\":\"" + warehouse + "\",\"listIds\":[\"" + lists[1] + "\",\"" + lists[2]
                + "\"],\"pickerId\":\"" + pickerUser.getPublicId() + "\"}")).andExpect(status().isCreated());
        assertThat(count(pickerUser, "TASK_ASSIGNED")).isEqualTo(2);
        Cookie picker = login(pickerUser);
        String first = read(send(picker, get("/api/v1/notifications")).andExpect(jsonPath("$.unread").value(2))
                .andExpect(jsonPath("$.items[0].params.task").value("WAVE")).andExpect(jsonPath("$.items[0].params.number").value("WV-000001"))
                .andExpect(jsonPath("$.items[1].params.task").value("PICK_LIST"))
                .andExpect(jsonPath("$.items[1].link").value("/worker/pick/" + lists[0])), "$.items[1].id");

        // expiry: one summary per warehouse and day
        stockIn("RICE", 3, LocalDate.now().minusDays(1), 2);
        stockIn("RICE", 3, LocalDate.now().plusDays(10), 2);
        stockIn("RICE", 3, LocalDate.now().plusDays(90), 2);
        assertThat((int) TenantContext.callAs(tenant, () -> triggers.expiry(warehouseId, LocalDate.now()))).isEqualTo(2); // manager + admin
        assertThat((int) TenantContext.callAs(tenant, () -> triggers.expiry(warehouseId, LocalDate.now()))).isZero();
        send(manager, get("/api/v1/notifications")).andExpect(jsonPath("$.items[0].kind").value("EXPIRY"))
                .andExpect(jsonPath("$.items[0].params.expired").value(1)).andExpect(jsonPath("$.items[0].params.soon").value(1));

        // marking read: one, then all; only my own
        send(manager, post("/api/v1/notifications/" + first + "/read")).andExpect(status().isNotFound()); // the picker's
        send(picker, post("/api/v1/notifications/" + first + "/read")).andExpect(status().isOk()).andExpect(jsonPath("$.read").value(true));
        send(picker, get("/api/v1/notifications")).andExpect(jsonPath("$.unread").value(1));
        send(manager, post("/api/v1/notifications/read-all")).andExpect(jsonPath("$.read").value(2));
        send(manager, get("/api/v1/notifications")).andExpect(jsonPath("$.unread").value(0)).andExpect(jsonPath("$.total").value(2));
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, post("/api/v1/notifications/" + first + "/read")).andExpect(status().isNotFound());
        send(other, get("/api/v1/notifications")).andExpect(jsonPath("$.total").value(0));
    }
}

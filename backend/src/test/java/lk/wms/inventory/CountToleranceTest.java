package lk.wms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import lk.wms.common.tenant.TenantContext;
import lk.wms.inventory.service.StockMovementService;
import org.junit.jupiter.api.Test;

/** v2.1: allowed differences in counts: over the limit is counted again once, within it is booked with no manager. */
class CountToleranceTest extends StockFixture {

    private String plan(String tolerance) throws Exception {
        return read(send(admin, post("/api/v1/count-plans").content("{\"warehouseId\":\"" + warehouse + "\",\"scope\":\"ALL\""
                + (tolerance == null ? "" : ",\"tolerancePct\":" + tolerance) + "}")).andExpect(status().isCreated()), "$.id");
    }

    private String count(String plan, int seq, int bin, String qty) throws Exception {
        return send(counter, post("/api/v1/count-plans/" + plan + "/lines/" + seq + "/count").content(
                "{\"binCode\":\"" + code(bin) + "\",\"code\":\"OIL-1L\",\"qty\":" + qty + "}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String planStatus(String plan) throws Exception {
        return read(send(admin, get("/api/v1/count-plans/" + plan)), "$.status");
    }

    @Test
    void withinTheLimitIsBookedWithNoManager() throws Exception {
        stockIn(0, 100);
        stockIn(1, 50);
        String plan = plan("2");
        count(plan, 1, 0, "98"); // 2% off: allowed
        count(plan, 2, 1, "50");
        assertThat(planStatus(plan)).isEqualTo("APPROVED");
        assertThat(onHand(0)).isEqualByComparingTo("98");
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE tenant_id = ? AND action LIKE 'COUNT%'", String.class, tenant))
                .contains("COUNT_AUTO_APPROVED").doesNotContain("COUNT_APPROVED");
    }

    @Test
    void overTheLimitIsCountedAgainOnceThenWaitsForTheManager() throws Exception {
        stockIn(0, 100);
        String plan = plan("2");
        String phone = count(plan, 1, 0, "90"); // 10% off: count it again, still blind
        assertThat(phone).contains("\"again\":true").contains("\"status\":\"OPEN\"").doesNotContain("expected");
        count(plan, 1, 0, "91"); // the second count stands
        send(admin, get("/api/v1/count-plans/" + plan)).andExpect(jsonPath("$.status").value("REVIEW"))
                .andExpect(jsonPath("$.tolerancePct").value(2)).andExpect(jsonPath("$.lines[0].firstCount").value(90))
                .andExpect(jsonPath("$.lines[0].counted").value(91)).andExpect(jsonPath("$.lines[0].overLimit").value(true));
        assertThat(onHand(0)).isEqualByComparingTo("100");
        int v = read(send(admin, get("/api/v1/count-plans/" + plan)), "$.version");
        send(admin, post("/api/v1/count-plans/" + plan + "/approve").content("{\"version\":" + v + "}")).andExpect(status().isOk());
        assertThat(onHand(0)).isEqualByComparingTo("91");
    }

    @Test
    void foundGoodsAreCountedAgainAndNoLimitMeansTheManagerReviews() throws Exception {
        stockIn(0, 10);
        String plan = plan("5");
        String phone = send(counter, post("/api/v1/count-plans/" + plan + "/found").content(
                "{\"binCode\":\"" + code(3) + "\",\"code\":\"OIL-1L\",\"qty\":2}")).andReturn().getResponse().getContentAsString();
        assertThat(phone).contains("\"again\":true"); // none expected there

        String plain = plan(null);
        count(plain, 1, 0, "10"); // exact, but no limit was set: the manager approves as before
        assertThat(planStatus(plain)).isEqualTo("REVIEW");
        send(admin, post("/api/v1/count-plans").content("{\"warehouseId\":\"" + warehouse + "\",\"scope\":\"ALL\",\"tolerancePct\":101}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aShortfallThatCannotBeBookedWaitsForTheManager() throws Exception {
        stockIn(0, 100);
        String plan = plan("2");
        TenantContext.callAs(tenant, () -> moves.reserve(warehouseId, "TEST", 1L,
                List.of(new StockMovementService.Need(1, oil, new BigDecimal("100"), "OIL-1L")), adminUser.getId()));
        count(plan, 1, 0, "99"); // within the limit, but every unit is reserved
        assertThat(planStatus(plan)).isEqualTo("REVIEW");
        assertThat(onHand(0)).isEqualByComparingTo("100");
    }
}

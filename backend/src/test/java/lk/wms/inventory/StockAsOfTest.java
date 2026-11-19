package lk.wms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import lk.wms.common.tenant.TenantContext;
import lk.wms.inventory.service.StockMovementService.Kind;
import lk.wms.inventory.service.StockMovementService.Move;
import lk.wms.inventory.service.StockMovementService.Place;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** v2.1: stock on a past day, summed from the ledger; in-warehouse moves change nothing. */
class StockAsOfTest extends StockFixture {

    private void move(Kind t, Place from, Place to, int qty) {
        TenantContext.callAs(tenant, () -> moves.move(new Move(t, warehouseId, oil, null, null, from, to, BigDecimal.valueOf(qty),
                null, null, null, adminUser.getId())));
    }

    @Test
    void stockOnADayFromTheLedger() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        stockIn(0, 10);                                                    // in: +10
        move(Kind.TRANSFER, Place.bin(bins.get(0)), Place.bin(bins.get(1)), 4); // inside: nothing
        move(Kind.PICK, Place.bin(bins.get(1)), Place.DISPATCH, 3);
        move(Kind.SHIP, Place.DISPATCH, null, 3);                           // out: -3

        send(admin, get("/api/v1/stock/as-of?warehouse=" + warehouse + "&date=" + today)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].sku").value("OIL-1L"))
                .andExpect(jsonPath("$.items[0].onHand").value(7)).andExpect(jsonPath("$.items[0].valueMinor").value(315000));
        send(admin, get("/api/v1/stock/as-of?warehouse=" + warehouse + "&date=" + today.minusDays(1)))
                .andExpect(jsonPath("$.total").value(0));
        send(admin, get("/api/v1/stock/as-of?warehouse=" + warehouse + "&date=" + today.plusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DATE_IN_FUTURE"));

        byte[] xlsx = send(admin, get("/api/v1/exports/stock.xlsx?warehouse=" + warehouse + "&asOf=" + today)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("On hand");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("OIL-1L");
            assertThat(sheet.getRow(2).getCell(2).getNumericCellValue()).isEqualTo(7.0);
        }

        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/stock/as-of?warehouse=" + warehouse + "&date=" + today)).andExpect(status().isNotFound());
    }
}

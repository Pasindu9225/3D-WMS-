package lk.wms.inventory.api;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Map;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.inventory.domain.StockMovement;
import lk.wms.inventory.service.StockService;
import lk.wms.inventory.service.StockService.MovementRow;
import lk.wms.inventory.service.StockService.ProductTotal;
import lk.wms.inventory.service.StockService.StockRow;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stock screens (v0.4 task 8). All need inventory:read and access to the warehouse. */
@RestController
@RequestMapping("/api/v1")
public class StockController {

    private final StockService stock;

    public StockController(StockService stock) {
        this.stock = stock;
    }

    /** What is where: one row per place, product and lot. */
    @GetMapping("/stock")
    @PreAuthorize("hasAuthority('inventory:read')")
    public PageResponse<StockRow> rows(@RequestParam("warehouse") @Size(max = 26) String warehouseId,
            @RequestParam(required = false) @Size(max = 26) String product,
            @RequestParam(required = false) @Size(max = 26) String bin,
            @RequestParam(required = false) @Size(max = 40) String binCode, // code prefix, e.g. A-03
            @RequestParam(required = false) Boolean receiving,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return PageResponse.of(stock.rows(CurrentUser.require(), warehouseId, product, bin, binCode,
                Boolean.TRUE.equals(receiving), PageResponse.request(page, size, null, Map.of(), Sort.unsorted())), r -> r);
    }

    /** Totals per product. */
    @GetMapping("/stock/products")
    @PreAuthorize("hasAuthority('inventory:read')")
    public PageResponse<ProductTotal> byProduct(@RequestParam("warehouse") @Size(max = 26) String warehouseId,
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return PageResponse.of(stock.byProduct(CurrentUser.require(), warehouseId, q,
                PageResponse.request(page, size, null, Map.of(), Sort.unsorted())), r -> r);
    }

    /** The movement history, newest first. */
    @GetMapping("/stock-movements")
    @PreAuthorize("hasAuthority('inventory:read')")
    public PageResponse<MovementRow> movements(@RequestParam("warehouse") @Size(max = 26) String warehouseId,
            @RequestParam(required = false) @Size(max = 26) String product,
            @RequestParam(required = false) @Size(max = 26) String bin,
            @RequestParam(required = false) StockMovement.Type type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return PageResponse.of(stock.movements(CurrentUser.require(), warehouseId, product, bin, type, from, to,
                PageResponse.request(page, size, null, Map.of(), Sort.unsorted())), r -> r);
    }
}

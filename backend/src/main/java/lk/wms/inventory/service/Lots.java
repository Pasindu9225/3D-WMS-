package lk.wms.inventory.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.inventory.domain.Stock;
import lk.wms.inventory.repo.StockRepository;
import lk.wms.layout.service.BinService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Works out the place and lot a worker means. A bin code (as scanned) or none for the receiving area; batch and
 * expiry as typed, or, when left out, the only lot of that product there (several lots: the worker must say which).
 */
@Component
class Lots {

    record Lot(String batchNo, LocalDate expiryDate) {
    }

    private final StockRepository stock;
    private final BinService bins;

    Lots(StockRepository stock, BinService bins) {
        this.stock = stock;
        this.bins = bins;
    }

    /** The bin for a scanned code, or null (= receiving area) when no code is given. */
    BinService.BinRef bin(CurrentUser cu, long warehouseId, String code) {
        return code == null || code.isBlank() ? null
                : bins.byCode(cu, warehouseId, code.trim().toUpperCase(Locale.ROOT));
    }

    /** The lot to take from: as given, or the only one there. */
    Lot existing(long warehouseId, Long binId, long productId, String batchNo, LocalDate expiry) {
        String batch = normal(batchNo);
        if (batch != null || expiry != null) {
            return new Lot(batch, expiry);
        }
        List<Stock> lots = stock.lots(warehouseId, binId, productId);
        if (lots.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_ENOUGH_STOCK", "There is none of this product there");
        }
        long distinct = lots.stream().map(s -> new Lot(s.getBatchNo(), s.getExpiryDate())).distinct().count();
        if (distinct > 1) {
            String which = String.join(", ", lots.stream()
                    .map(s -> Objects.toString(s.getBatchNo(), "-") + (s.getExpiryDate() == null ? "" : " exp " + s.getExpiryDate()))
                    .distinct().toList());
            throw new ApiException(HttpStatus.BAD_REQUEST, "LOT_REQUIRED", "Several lots are there (" + which + "); say which");
        }
        return new Lot(lots.get(0).getBatchNo(), lots.get(0).getExpiryDate());
    }

    static String normal(String batchNo) {
        return batchNo == null || batchNo.isBlank() ? null : batchNo.trim().toUpperCase(Locale.ROOT);
    }
}

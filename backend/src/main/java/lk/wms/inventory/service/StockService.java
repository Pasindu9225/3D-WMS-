package lk.wms.inventory.service;

import java.util.Collection;
import java.util.List;
import lk.wms.inventory.repo.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only stock questions for other modules (screens come in task 8). */
@Service
public class StockService {

    private final StockRepository stock;

    public StockService(StockRepository stock) {
        this.stock = stock;
    }

    /** Which of these bins hold stock (internal ids); a layout publish may not remove them. */
    @Transactional(readOnly = true)
    public List<Long> binsHoldingStock(Collection<Long> binIds) {
        return binIds.isEmpty() ? List.of() : stock.binsWithStock(binIds);
    }
}

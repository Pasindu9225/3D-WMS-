package lk.wms.inventory.service;

import lk.wms.common.errors.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Books a count within its limit once the counter's last count has committed; a failure leaves it for the manager. */
@Component
class CountAutoApprove {

    private static final Logger log = LoggerFactory.getLogger(CountAutoApprove.class);

    private final CountService counts;

    CountAutoApprove(CountService counts) {
        this.counts = counts;
    }

    @TransactionalEventListener
    void on(CountService.CountWithinLimit e) {
        try {
            counts.autoApprove(e);
        } catch (ApiException ex) {
            log.info("Count {} left for a manager: {}", e.planId(), ex.getCode());
        }
    }
}

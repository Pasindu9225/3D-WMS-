package lk.wms.inbound.service;

import lk.wms.common.security.CurrentUser;
import lk.wms.inbound.repo.DocCounterRepository;
import org.springframework.stereotype.Component;

/**
 * Running document numbers per company: PO-000001, GRN-000001 ... Call inside the transaction that saves the
 * document: the counter row stays locked until commit, so numbers never repeat (a rolled-back document leaves a gap).
 */
@Component
class DocNumbers {

    private final DocCounterRepository counters;

    DocNumbers(DocCounterRepository counters) {
        this.counters = counters;
    }

    String next(CurrentUser cu, String kind) {
        counters.bump(cu.tenantId(), kind);
        return String.format("%s-%06d", kind, counters.taken(kind));
    }
}

package lk.wms.catalog.api;

import jakarta.validation.Validator;
import java.util.List;
import lk.wms.catalog.domain.Partner;
import lk.wms.catalog.service.PartnerService;
import lk.wms.common.web.RateLimiter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customers (v1.0 task 1): who the company ships to. Endpoints in {@link PartnerEndpoints}. */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController extends PartnerEndpoints {

    public CustomerController(PartnerService partners, Validator validator, RateLimiter limiter) {
        super(Partner.Type.CUSTOMER, List.of("CUS-001", "Kandy Super Mart", "Sunil Silva", "+94 81 222 3344",
                "buying@kandysuper.lk", "45 Peradeniya Road, Kandy"), partners, validator, limiter);
    }
}

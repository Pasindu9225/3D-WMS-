package lk.wms.catalog.api;

import jakarta.validation.Validator;
import java.util.List;
import lk.wms.catalog.domain.Partner;
import lk.wms.catalog.service.PartnerService;
import lk.wms.common.web.RateLimiter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Suppliers (v0.4 task 2): who the company buys from. Endpoints in {@link PartnerEndpoints}. */
@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController extends PartnerEndpoints {

    public SupplierController(PartnerService partners, Validator validator, RateLimiter limiter) {
        super(Partner.Type.SUPPLIER, List.of("SUP-001", "Lanka Oils (Pvt) Ltd", "Nimal Perera", "+94 11 234 5678",
                "orders@lankaoils.lk", "12 Galle Road, Colombo 03"), partners, validator, limiter);
    }
}

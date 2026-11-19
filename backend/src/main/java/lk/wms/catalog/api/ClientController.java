package lk.wms.catalog.api;

import jakarta.validation.Validator;
import java.util.List;
import lk.wms.catalog.domain.Partner;
import lk.wms.catalog.service.PartnerService;
import lk.wms.common.web.RateLimiter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Clients (v2.1, 3PL): the companies whose goods are stored here. Endpoints in {@link PartnerEndpoints}. */
@RestController
@RequestMapping("/api/v1/clients")
public class ClientController extends PartnerEndpoints {

    public ClientController(PartnerService partners, Validator validator, RateLimiter limiter) {
        super(Partner.Type.CLIENT, List.of("CLI-001", "Ceylon Foods Ltd", "Nimal Perera", "+94 11 234 5678",
                "stores@ceylonfoods.lk", "12 Galle Road, Colombo 3"), partners, validator, limiter);
    }
}

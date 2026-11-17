package lk.wms.report.service;

import java.util.List;
import lk.wms.auth.service.UserService;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.security.CurrentUser;
import lk.wms.inbound.service.ReceiptService;
import lk.wms.layout.service.RackTemplateService;
import lk.wms.layout.service.WarehouseService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * The setup wizard for a new company (v2.0): each step is worked out from the company's own data, so nothing is stored
 * and doing a step anywhere in the app ticks it.
 */
@Service
public class SetupService {

    public record Step(String key, boolean done) {
    }

    public record Status(List<Step> steps, boolean complete, String warehouseId) {
    }

    private final WarehouseService warehouses;
    private final RackTemplateService templates;
    private final ProductService products;
    private final ReceiptService receipts;
    private final UserService users;

    public SetupService(WarehouseService warehouses, RackTemplateService templates, ProductService products, ReceiptService receipts,
            UserService users) {
        this.warehouses = warehouses;
        this.templates = templates;
        this.products = products;
        this.receipts = receipts;
        this.users = users;
    }

    public Status status(CurrentUser cu) {
        var list = warehouses.list(cu, PageRequest.of(0, 100)).getContent();
        boolean published = list.stream().anyMatch(w -> "PUBLISHED".equals(w.layoutStatus()));
        List<Step> steps = List.of(
                new Step("WAREHOUSE", !list.isEmpty()),
                new Step("RACK_TYPE", templates.list(PageRequest.of(0, 1)).getTotalElements() > 0),
                new Step("LAYOUT", published),
                new Step("PRODUCTS", products.usage()[0] > 0),
                new Step("RECEIVE", receipts.anyReceipt()),
                new Step("TEAM", users.totalCount() > 1));
        return new Status(steps, steps.stream().allMatch(Step::done), list.isEmpty() ? null : list.get(0).id());
    }
}

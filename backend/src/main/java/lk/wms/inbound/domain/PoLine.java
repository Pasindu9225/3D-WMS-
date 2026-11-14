package lk.wms.inbound.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lk.wms.common.tenant.TenantScoped;

/** One product on a purchase order, in the unit it is ordered in. */
@Entity
@Table(name = "po_line")
public class PoLine extends TenantScoped {

    /** A line as the service has checked it: product copied from the catalog, unit factor fixed now. */
    public record Spec(long productId, String sku, String productName, String uom, int factor, BigDecimal qty,
            long unitCostMinor) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "po_id", nullable = false, updatable = false)
    private PurchaseOrder order;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false, length = 40)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(nullable = false, length = 10)
    private String uom;

    @Column(nullable = false)
    private int factor;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal qty;

    @Column(name = "unit_cost_minor", nullable = false)
    private long unitCostMinor;

    @Column(name = "qty_received", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyReceived = BigDecimal.ZERO;

    protected PoLine() {
    }

    PoLine(PurchaseOrder order, int lineNo, Spec s) {
        this.order = order;
        this.lineNo = lineNo;
        set(s);
    }

    void set(Spec s) {
        this.productId = s.productId();
        this.sku = s.sku();
        this.productName = s.productName();
        this.uom = s.uom();
        this.factor = s.factor();
        this.qty = s.qty();
        this.unitCostMinor = s.unitCostMinor();
    }

    /** qty x unit cost, in cents, rounded half up. */
    public long totalMinor() {
        return qty.multiply(BigDecimal.valueOf(unitCostMinor)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public int getLineNo() {
        return lineNo;
    }

    public Long getProductId() {
        return productId;
    }

    public String getSku() {
        return sku;
    }

    public String getProductName() {
        return productName;
    }

    public String getUom() {
        return uom;
    }

    public int getFactor() {
        return factor;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public long getUnitCostMinor() {
        return unitCostMinor;
    }

    public BigDecimal getQtyReceived() {
        return qtyReceived;
    }
}

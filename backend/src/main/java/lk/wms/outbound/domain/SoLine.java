package lk.wms.outbound.domain;

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
import lk.wms.common.tenant.TenantScoped;

/** One product on a sales order, in the unit it is ordered in. */
@Entity
@Table(name = "so_line")
public class SoLine extends TenantScoped {

    /** A line as the service has checked it: product copied from the catalog, unit factor fixed now. */
    public record Spec(long productId, String sku, String productName, String uom, int factor, BigDecimal qty) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "so_id", nullable = false, updatable = false)
    private SalesOrder order;

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

    protected SoLine() {
    }

    SoLine(SalesOrder order, int lineNo, Spec s) {
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
    }

    /** What was ordered, in base units (qty x factor). */
    public BigDecimal orderedBase() {
        return qty.multiply(BigDecimal.valueOf(factor));
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
}

package lk.wms.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lk.wms.common.tenant.TenantScoped;

/** A larger unit of a product: {@code factor} base units, optionally with its own barcode. */
@Entity
@Table(name = "product_uom")
public class ProductUom extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(nullable = false, length = 10, updatable = false)
    private String uom;

    @Column(nullable = false)
    private int factor;

    @Column(length = 64)
    private String barcode;

    protected ProductUom() {
    }

    ProductUom(Product product, String uom, int factor, String barcode) {
        this.product = product;
        this.uom = uom;
        set(factor, barcode);
    }

    final void set(int factor, String barcode) {
        this.factor = factor;
        this.barcode = barcode;
    }

    public Product getProduct() {
        return product;
    }

    public String getUom() {
        return uom;
    }

    public int getFactor() {
        return factor;
    }

    public String getBarcode() {
        return barcode;
    }
}

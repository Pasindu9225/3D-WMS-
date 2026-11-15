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
import java.time.Instant;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One scan on a receipt: so many of a unit of a product on an order line, part of it possibly damaged. */
@Entity
@Table(name = "receipt_line")
public class ReceiptLine extends TenantScoped {

    /** A scan the service has checked: order line found, unit factor fixed, base quantities worked out. */
    public record Spec(PoLine poLine, String uom, int factor, BigDecimal qty, BigDecimal qtyBase, BigDecimal damagedBase,
            String damageNote, String batchNo, java.time.LocalDate expiryDate, String clientRef) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false, updatable = false)
    private Receipt receipt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "po_line_id", nullable = false, updatable = false)
    private PoLine poLine;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, length = 10, updatable = false)
    private String uom;

    @Column(nullable = false, updatable = false)
    private int factor;

    @Column(nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal qty;

    @Column(name = "qty_base", nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal qtyBase;

    @Column(name = "damaged_base", nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal damagedBase;

    @Column(name = "damage_note", length = 200, updatable = false)
    private String damageNote;

    @Column(name = "batch_no", length = 40, updatable = false)
    private String batchNo;

    @Column(name = "expiry_date", updatable = false)
    private java.time.LocalDate expiryDate;

    @Column(name = "client_ref", nullable = false, length = 64, updatable = false)
    private String clientRef;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReceiptLine() {
    }

    ReceiptLine(Receipt receipt, Spec s, long userId) {
        this.publicId = Ulid.next();
        this.receipt = receipt;
        this.poLine = s.poLine();
        this.productId = s.poLine().getProductId();
        this.uom = s.uom();
        this.factor = s.factor();
        this.qty = s.qty();
        this.qtyBase = s.qtyBase();
        this.damagedBase = s.damagedBase();
        this.damageNote = s.damageNote();
        this.batchNo = s.batchNo();
        this.expiryDate = s.expiryDate();
        this.clientRef = s.clientRef();
        this.createdBy = userId;
        this.createdAt = Instant.now();
    }

    public String getPublicId() {
        return publicId;
    }

    public PoLine getPoLine() {
        return poLine;
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

    public BigDecimal getQtyBase() {
        return qtyBase;
    }

    public BigDecimal getDamagedBase() {
        return damagedBase;
    }

    public String getDamageNote() {
        return damageNote;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public java.time.LocalDate getExpiryDate() {
        return expiryDate;
    }

    public Long getProductId() {
        return productId;
    }

    public String getClientRef() {
        return clientRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

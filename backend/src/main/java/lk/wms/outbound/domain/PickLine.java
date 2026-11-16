package lk.wms.outbound.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lk.wms.common.tenant.TenantScoped;

/** Fetch this much of one product (lot) from one bin, for one sales order line. Quantities in base units. */
@Entity
@Table(name = "pick_line")
public class PickLine extends TenantScoped {

    public enum Status { OPEN, PICKED, SHORT }

    public record Spec(int soLineNo, long productId, String sku, String productName, String baseUom, long binId,
            String binCode, String batchNo, LocalDate expiryDate, BigDecimal qty) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pick_list_id", nullable = false, updatable = false)
    private PickList list;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Column(name = "so_line_no", nullable = false, updatable = false)
    private int soLineNo;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, length = 40, updatable = false)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 200, updatable = false)
    private String productName;

    @Column(name = "base_uom", nullable = false, length = 10, updatable = false)
    private String baseUom;

    @Column(name = "bin_id", nullable = false, updatable = false)
    private Long binId;

    @Column(name = "bin_code", nullable = false, length = 60, updatable = false)
    private String binCode;

    @Column(name = "batch_no", length = 40, updatable = false)
    private String batchNo;

    @Column(name = "expiry_date", updatable = false)
    private LocalDate expiryDate;

    @Column(name = "qty_to_pick", nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal qtyToPick;

    @Column(name = "qty_picked", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyPicked = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    @Column(name = "short_reason", length = 20)
    private String shortReason;

    protected PickLine() {
    }

    PickLine(PickList list, int seq, Spec s) {
        this.list = list;
        this.seq = seq;
        this.soLineNo = s.soLineNo();
        this.productId = s.productId();
        this.sku = s.sku();
        this.productName = s.productName();
        this.baseUom = s.baseUom();
        this.binId = s.binId();
        this.binCode = s.binCode();
        this.batchNo = s.batchNo();
        this.expiryDate = s.expiryDate();
        this.qtyToPick = s.qty();
    }

    public BigDecimal left() {
        return qtyToPick.subtract(qtyPicked);
    }

    public void picked(BigDecimal qty) {
        qtyPicked = qtyPicked.add(qty);
        if (left().signum() == 0) {
            status = Status.PICKED;
        }
    }

    public void shorted(String reason) {
        status = Status.SHORT;
        shortReason = reason;
    }

    public int getSeq() {
        return seq;
    }

    public int getSoLineNo() {
        return soLineNo;
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

    public String getBaseUom() {
        return baseUom;
    }

    public Long getBinId() {
        return binId;
    }

    public String getBinCode() {
        return binCode;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public BigDecimal getQtyToPick() {
        return qtyToPick;
    }

    public BigDecimal getQtyPicked() {
        return qtyPicked;
    }

    public Status getStatus() {
        return status;
    }

    public String getShortReason() {
        return shortReason;
    }
}

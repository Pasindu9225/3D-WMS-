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
import java.time.Instant;
import java.time.LocalDate;
import lk.wms.common.tenant.TenantScoped;

/** One product coming back, in base units, with why it came back and (once inspected) what happens to it. */
@Entity
@Table(name = "return_line")
public class ReturnLine extends TenantScoped {

    public enum Reason { DAMAGED, WRONG_ITEM, NOT_WANTED, EXPIRED, OTHER }

    public enum Outcome { RESTOCK, QUARANTINE, SCRAP }

    public record Spec(long productId, String sku, String productName, String baseUom, BigDecimal qty, Reason reason) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false, updatable = false)
    private ReturnOrder returnOrder;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, length = 40, updatable = false)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 200, updatable = false)
    private String productName;

    @Column(name = "base_uom", nullable = false, length = 10, updatable = false)
    private String baseUom;

    @Column(nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal qty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Reason reason;

    @Enumerated(EnumType.STRING)
    private Outcome outcome;

    @Column(name = "bin_id")
    private Long binId;

    @Column(name = "bin_code", length = 60)
    private String binCode;

    @Column(name = "batch_no", length = 40)
    private String batchNo;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "inspected_by")
    private Long inspectedBy;

    @Column(name = "inspected_at")
    private Instant inspectedAt;

    protected ReturnLine() {
    }

    ReturnLine(ReturnOrder returnOrder, int seq, Spec s) {
        this.returnOrder = returnOrder;
        this.seq = seq;
        this.productId = s.productId();
        this.sku = s.sku();
        this.productName = s.productName();
        this.baseUom = s.baseUom();
        this.qty = s.qty();
        this.reason = s.reason();
    }

    public void inspected(Outcome outcome, Long binId, String binCode, String batchNo, LocalDate expiry, long userId) {
        this.outcome = outcome;
        this.binId = binId;
        this.binCode = binCode;
        this.batchNo = batchNo;
        this.expiryDate = expiry;
        this.inspectedBy = userId;
        this.inspectedAt = Instant.now();
    }

    public int getSeq() {
        return seq;
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

    public BigDecimal getQty() {
        return qty;
    }

    public Reason getReason() {
        return reason;
    }

    public Outcome getOutcome() {
        return outcome;
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

    public Long getInspectedBy() {
        return inspectedBy;
    }

    public Instant getInspectedAt() {
        return inspectedAt;
    }
}

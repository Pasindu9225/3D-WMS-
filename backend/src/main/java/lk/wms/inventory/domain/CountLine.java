package lk.wms.inventory.domain;

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

/** One place to count: a bin, a product and its lot. Expected is read when it is counted, never shown to counters. */
@Entity
@Table(name = "count_line")
public class CountLine extends TenantScoped {

    public enum Status { OPEN, COUNTED }

    public record Spec(long binId, String binCode, long productId, String sku, String productName, String baseUom,
            String batchNo, LocalDate expiry) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false, updatable = false)
    private CountPlan plan;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Column(name = "bin_id", nullable = false, updatable = false)
    private Long binId;

    @Column(name = "bin_code", nullable = false, length = 60, updatable = false)
    private String binCode;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, length = 40, updatable = false)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 200, updatable = false)
    private String productName;

    @Column(name = "base_uom", nullable = false, length = 10, updatable = false)
    private String baseUom;

    @Column(name = "batch_no", length = 40, updatable = false)
    private String batchNo;

    @Column(name = "expiry_date", updatable = false)
    private LocalDate expiryDate;

    @Column(nullable = false, updatable = false)
    private boolean added;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "expected_qty", precision = 14, scale = 3)
    private BigDecimal expectedQty;

    @Column(name = "counted_qty", precision = 14, scale = 3)
    private BigDecimal countedQty;

    @Column(name = "counted_by")
    private Long countedBy;

    @Column(name = "counted_at")
    private Instant countedAt;

    protected CountLine() {
    }

    CountLine(CountPlan plan, int seq, Spec s, boolean added) {
        this.plan = plan;
        this.seq = seq;
        this.binId = s.binId();
        this.binCode = s.binCode();
        this.productId = s.productId();
        this.sku = s.sku();
        this.productName = s.productName();
        this.baseUom = s.baseUom();
        this.batchNo = s.batchNo();
        this.expiryDate = s.expiry();
        this.added = added;
        this.status = Status.OPEN;
    }

    public void counted(BigDecimal counted, BigDecimal expected, long userId) {
        this.countedQty = counted;
        this.expectedQty = expected;
        this.countedBy = userId;
        this.countedAt = Instant.now();
        this.status = Status.COUNTED;
    }

    /** Back to the counter: the earlier figures are dropped. */
    public void recount() {
        this.countedQty = null;
        this.expectedQty = null;
        this.countedBy = null;
        this.countedAt = null;
        this.status = Status.OPEN;
    }

    /** Counted minus expected (positive = found more); zero until counted. */
    public BigDecimal difference() {
        return countedQty == null ? BigDecimal.ZERO : countedQty.subtract(expectedQty);
    }

    public Long getId() {
        return id;
    }

    public int getSeq() {
        return seq;
    }

    public Long getBinId() {
        return binId;
    }

    public String getBinCode() {
        return binCode;
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

    public String getBatchNo() {
        return batchNo;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public boolean isAdded() {
        return added;
    }

    public Status getStatus() {
        return status;
    }

    public BigDecimal getExpectedQty() {
        return expectedQty;
    }

    public BigDecimal getCountedQty() {
        return countedQty;
    }

    public Long getCountedBy() {
        return countedBy;
    }

    public Instant getCountedAt() {
        return countedAt;
    }
}

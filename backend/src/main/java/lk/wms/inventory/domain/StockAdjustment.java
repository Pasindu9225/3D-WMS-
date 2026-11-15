package lk.wms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A stock correction with a reason. Applied at once below the company's limit, otherwise after a manager approves. */
@Entity
@Table(name = "stock_adjustment")
public class StockAdjustment extends TenantScoped {

    public enum Direction { IN, OUT }

    public enum Reason { DAMAGED, LOST, FOUND, EXPIRED, COUNT_CORRECTION, OTHER }

    public enum Status { PENDING, APPLIED, REJECTED }

    /** What was asked for, already checked: place, lot and value worked out by the service. */
    public record Spec(long warehouseId, Long binId, String binCode, long productId, String sku, String batchNo,
            LocalDate expiryDate, Direction direction, BigDecimal qty, Reason reason, String note, Long valueMinor,
            String clientRef) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(nullable = false, length = 20, updatable = false)
    private String number;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Column(name = "bin_id", updatable = false)
    private Long binId;

    @Column(name = "bin_code", length = 40, updatable = false)
    private String binCode;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, length = 40, updatable = false)
    private String sku;

    @Column(name = "batch_no", length = 40, updatable = false)
    private String batchNo;

    @Column(name = "expiry_date", updatable = false)
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Direction direction;

    @Column(nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal qty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Reason reason;

    @Column(length = 200, updatable = false)
    private String note;

    @Column(name = "value_minor", updatable = false)
    private Long valueMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 200)
    private String decisionNote;

    @Column(name = "client_ref", nullable = false, length = 64, updatable = false)
    private String clientRef;

    @Version
    private int version;

    protected StockAdjustment() {
    }

    public StockAdjustment(String number, Spec s, long requestedBy) {
        this.publicId = Ulid.next();
        this.number = number;
        this.warehouseId = s.warehouseId();
        this.binId = s.binId();
        this.binCode = s.binCode();
        this.productId = s.productId();
        this.sku = s.sku();
        this.batchNo = s.batchNo();
        this.expiryDate = s.expiryDate();
        this.direction = s.direction();
        this.qty = s.qty();
        this.reason = s.reason();
        this.note = s.note();
        this.valueMinor = s.valueMinor();
        this.clientRef = s.clientRef();
        this.status = Status.PENDING;
        this.requestedBy = requestedBy;
        this.requestedAt = Instant.now();
    }

    public void decide(Status outcome, long userId, String note) {
        this.status = outcome;
        this.decidedBy = userId;
        this.decidedAt = Instant.now();
        this.decisionNote = note;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getNumber() {
        return number;
    }

    public Long getWarehouseId() {
        return warehouseId;
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

    public String getBatchNo() {
        return batchNo;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public Direction getDirection() {
        return direction;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public Reason getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public Long getValueMinor() {
        return valueMinor;
    }

    public Status getStatus() {
        return status;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Long getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public int getVersion() {
        return version;
    }
}

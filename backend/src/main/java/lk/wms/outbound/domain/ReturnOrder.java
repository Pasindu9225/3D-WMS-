package lk.wms.outbound.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Goods a customer sends back from a shipped order (v1.1); each line is inspected: restock, quarantine or scrap. */
@Entity
@Table(name = "return_order")
public class ReturnOrder extends TenantScoped {

    public enum Status { OPEN, CLOSED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(nullable = false, length = 20, updatable = false)
    private String number;

    @Column(name = "so_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private int version;

    @OneToMany(mappedBy = "returnOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq")
    private List<ReturnLine> lines = new ArrayList<>();

    protected ReturnOrder() {
    }

    public ReturnOrder(String number, long orderId, long warehouseId, String note, long userId) {
        this.publicId = Ulid.next();
        this.number = number;
        this.orderId = orderId;
        this.warehouseId = warehouseId;
        this.note = note;
        this.status = Status.OPEN;
        this.createdBy = userId;
        this.createdAt = Instant.now();
    }

    public void add(ReturnLine.Spec s) {
        lines.add(new ReturnLine(this, lines.size() + 1, s));
    }

    /** Closed once every line has an outcome. */
    public void progress() {
        if (lines.stream().allMatch(l -> l.getOutcome() != null)) {
            status = Status.CLOSED;
            closedAt = Instant.now();
        }
    }

    public void cancel(String reason) {
        status = Status.CANCELLED;
        cancelReason = reason;
        closedAt = Instant.now();
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

    public Long getOrderId() {
        return orderId;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public Status getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public int getVersion() {
        return version;
    }

    public List<ReturnLine> getLines() {
        return lines;
    }
}

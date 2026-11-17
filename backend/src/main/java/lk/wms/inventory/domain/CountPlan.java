package lk.wms.inventory.domain;

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

/** A cycle count (v1.1): the places to count, then counted, reviewed and approved. */
@Entity
@Table(name = "count_plan")
public class CountPlan extends TenantScoped {

    public enum Scope { ALL, ZONE, ABC }

    public enum Status { OPEN, REVIEW, APPROVED, CANCELLED }

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Scope scope;

    @Column(name = "scope_value", length = 20, updatable = false)
    private String scopeValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    @Version
    private int version;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq")
    private List<CountLine> lines = new ArrayList<>();

    protected CountPlan() {
    }

    public CountPlan(String number, long warehouseId, Scope scope, String scopeValue, long userId) {
        this.publicId = Ulid.next();
        this.number = number;
        this.warehouseId = warehouseId;
        this.scope = scope;
        this.scopeValue = scopeValue;
        this.status = Status.OPEN;
        this.createdBy = userId;
        this.createdAt = Instant.now();
    }

    public CountLine add(CountLine.Spec s, boolean added) {
        CountLine line = new CountLine(this, lines.size() + 1, s, added);
        lines.add(line);
        return line;
    }

    /** OPEN while a line is left to count, REVIEW once all are counted. */
    public void progress() {
        status = lines.stream().anyMatch(l -> l.getStatus() == CountLine.Status.OPEN) ? Status.OPEN : Status.REVIEW;
    }

    public void approve(long userId) {
        status = Status.APPROVED;
        approvedBy = userId;
        approvedAt = Instant.now();
    }

    public void cancel(String reason) {
        status = Status.CANCELLED;
        cancelReason = reason;
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

    public Scope getScope() {
        return scope;
    }

    public String getScopeValue() {
        return scopeValue;
    }

    public Status getStatus() {
        return status;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public int getVersion() {
        return version;
    }

    public List<CountLine> getLines() {
        return lines;
    }
}

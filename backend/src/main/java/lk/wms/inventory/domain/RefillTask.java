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
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** "Bring this much of the product to the pick bin" (v1.2 refill). One open task per rule at most. */
@Entity
@Table(name = "refill_task")
public class RefillTask extends TenantScoped {

    public enum Status { OPEN, DONE, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(nullable = false, length = 20, updatable = false)
    private String number;

    @Column(name = "rule_id", nullable = false, updatable = false)
    private Long ruleId;

    @Column(name = "open_rule_id")
    private Long openRuleId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Column(name = "bin_id", nullable = false, updatable = false)
    private Long binId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal qty;

    @Column(name = "moved_qty", precision = 14, scale = 3)
    private BigDecimal movedQty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "done_by")
    private Long doneBy;

    @Column(name = "done_at")
    private Instant doneAt;

    @Version
    private int version;

    protected RefillTask() {
    }

    public RefillTask(String number, RefillRule rule, BigDecimal qty) {
        this.publicId = Ulid.next();
        this.number = number;
        this.ruleId = rule.getId();
        this.openRuleId = rule.getId();
        this.warehouseId = rule.getWarehouseId();
        this.binId = rule.getBinId();
        this.productId = rule.getProductId();
        this.qty = qty;
        this.status = Status.OPEN;
        this.createdAt = Instant.now();
    }

    public void done(BigDecimal moved, long userId) {
        this.movedQty = moved;
        this.doneBy = userId;
        this.doneAt = Instant.now();
        this.status = Status.DONE;
        this.openRuleId = null;
    }

    public void cancel(long userId) {
        this.doneBy = userId;
        this.doneAt = Instant.now();
        this.status = Status.CANCELLED;
        this.openRuleId = null;
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

    public Long getRuleId() {
        return ruleId;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public Long getBinId() {
        return binId;
    }

    public Long getProductId() {
        return productId;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public BigDecimal getMovedQty() {
        return movedQty;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getDoneBy() {
        return doneBy;
    }

    public Instant getDoneAt() {
        return doneAt;
    }
}

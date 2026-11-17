package lk.wms.outbound.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Several orders' pick lists walked together (v1.2 batch picking); each order goes into its own tote. */
@Entity
@Table(name = "pick_wave")
public class PickWave extends TenantScoped {

    public enum Status { OPEN, IN_PROGRESS, DONE }

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
    @Column(nullable = false)
    private Status status;

    @Column(name = "cut_off", updatable = false)
    private LocalDate cutOff;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "done_at")
    private Instant doneAt;

    @Version
    private int version;

    protected PickWave() {
    }

    public PickWave(String number, long warehouseId, LocalDate cutOff, long userId) {
        this.publicId = Ulid.next();
        this.number = number;
        this.warehouseId = warehouseId;
        this.cutOff = cutOff;
        this.status = Status.OPEN;
        this.createdBy = userId;
        this.createdAt = Instant.now();
    }

    /** From its lists: done when all are done, in progress when any has started. */
    public void follow(boolean anyStarted, boolean allDone) {
        if (allDone) {
            status = Status.DONE;
            doneAt = Instant.now();
        } else {
            status = anyStarted ? Status.IN_PROGRESS : Status.OPEN;
        }
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

    public Status getStatus() {
        return status;
    }

    public LocalDate getCutOff() {
        return cutOff;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getVersion() {
        return version;
    }
}

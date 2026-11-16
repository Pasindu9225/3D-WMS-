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

/** What to fetch for one released sales order, line by line in walking (bin) order. */
@Entity
@Table(name = "pick_list")
public class PickList extends TenantScoped {

    public enum Status { OPEN, IN_PROGRESS, DONE }

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_by")
    private Long startedBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "done_at")
    private Instant doneAt;

    @Version
    private int version;

    @OneToMany(mappedBy = "list", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq")
    private List<PickLine> lines = new ArrayList<>();

    protected PickList() {
    }

    public PickList(String number, long orderId, long warehouseId) {
        this.publicId = Ulid.next();
        this.number = number;
        this.orderId = orderId;
        this.warehouseId = warehouseId;
        this.status = Status.OPEN;
        this.createdAt = Instant.now();
    }

    public void add(PickLine.Spec s) {
        lines.add(new PickLine(this, lines.size() + 1, s));
    }

    /** After a line is picked or shorted: started on the first, done when no line is open. */
    public void progress(long userId) {
        if (startedAt == null) {
            startedBy = userId;
            startedAt = Instant.now();
        }
        boolean open = lines.stream().anyMatch(l -> l.getStatus() == PickLine.Status.OPEN);
        status = open ? Status.IN_PROGRESS : Status.DONE;
        if (!open) {
            doneAt = Instant.now();
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

    public Long getOrderId() {
        return orderId;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<PickLine> getLines() {
        return lines;
    }
}

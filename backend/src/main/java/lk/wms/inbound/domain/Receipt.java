package lk.wms.inbound.domain;

import jakarta.persistence.CascadeType;
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

/** A goods received note: scans against one purchase order while OPEN; closing adds them to the order. */
@Entity
@Table(name = "receipt")
public class Receipt extends TenantScoped {

    public enum Status { OPEN, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(nullable = false, length = 20, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "po_id", nullable = false, updatable = false)
    private PurchaseOrder order;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(length = 500)
    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private int version;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<ReceiptLine> lines = new ArrayList<>();

    protected Receipt() {
    }

    public Receipt(String number, PurchaseOrder order, long createdBy) {
        this.publicId = Ulid.next();
        this.number = number;
        this.order = order;
        this.warehouseId = order.getWarehouseId();
        this.status = Status.OPEN;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public ReceiptLine add(ReceiptLine.Spec s, long userId) {
        ReceiptLine line = new ReceiptLine(this, s, userId);
        lines.add(line);
        return line;
    }

    public void close(long userId, String note) {
        this.status = Status.CLOSED;
        this.closedBy = userId;
        this.closedAt = Instant.now();
        this.note = note;
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

    public PurchaseOrder getOrder() {
        return order;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public int getVersion() {
        return version;
    }

    public List<ReceiptLine> getLines() {
        return lines;
    }
}

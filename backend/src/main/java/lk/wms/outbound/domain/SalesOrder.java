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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A sales order: DRAFT (editable) → RELEASED → PICKING → PICKED → PACKED → SHIPPED, or CANCELLED. */
@Entity
@Table(name = "sales_order")
public class SalesOrder extends TenantScoped {

    public enum Status { DRAFT, RELEASED, PICKING, PICKED, PACKED, SHIPPED, CANCELLED }

    /** The header as the service has checked it: customer and warehouse copied onto the document. */
    public record Header(long customerId, String customerCode, String customerName, String customerRef,
            long warehouseId, String warehouseCode, String shipTo, LocalDate requestedDate, String note) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Column(nullable = false, length = 20, updatable = false)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_code", nullable = false, length = 20)
    private String customerCode;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    @Column(name = "customer_ref", length = 40)
    private String customerRef;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "warehouse_code", nullable = false, length = 20)
    private String warehouseCode;

    @Column(name = "ship_to", length = 500)
    private String shipTo;

    @Column(name = "requested_date")
    private LocalDate requestedDate;

    @Column(length = 500)
    private String note;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    @Version
    private int version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<SoLine> lines = new ArrayList<>();

    protected SalesOrder() {
    }

    public SalesOrder(String number, long createdBy) {
        this.publicId = Ulid.next();
        this.number = number;
        this.status = Status.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /** Header and the whole set of lines (drafts only; the service checks the status). */
    public void set(Header h, List<SoLine.Spec> specs) {
        this.customerId = h.customerId();
        this.customerCode = h.customerCode();
        this.customerName = h.customerName();
        this.customerRef = h.customerRef();
        this.warehouseId = h.warehouseId();
        this.warehouseCode = h.warehouseCode();
        this.shipTo = h.shipTo();
        this.requestedDate = h.requestedDate();
        this.note = h.note();
        // Rows are reused by position: Hibernate inserts before it deletes, so replacing them would clash on line_no.
        while (lines.size() > specs.size()) {
            lines.remove(lines.size() - 1);
        }
        for (int i = 0; i < specs.size(); i++) {
            if (i < lines.size()) {
                lines.get(i).set(specs.get(i));
            } else {
                lines.add(new SoLine(this, i + 1, specs.get(i)));
            }
        }
        this.lineCount = lines.size();
        if (id != null) {
            this.updatedAt = Instant.now();
        }
    }

    public void cancel(long userId, String reason) {
        this.status = Status.CANCELLED;
        this.cancelledBy = userId;
        this.cancelledAt = Instant.now();
        this.cancelReason = reason;
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

    public Status getStatus() {
        return status;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getCustomerCode() {
        return customerCode;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public String getWarehouseCode() {
        return warehouseCode;
    }

    public String getShipTo() {
        return shipTo;
    }

    public LocalDate getRequestedDate() {
        return requestedDate;
    }

    public String getNote() {
        return note;
    }

    public int getLineCount() {
        return lineCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public int getVersion() {
        return version;
    }

    public List<SoLine> getLines() {
        return lines;
    }
}

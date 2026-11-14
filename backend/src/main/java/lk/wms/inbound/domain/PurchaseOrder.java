package lk.wms.inbound.domain;

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

/** A purchase order: DRAFT (editable) → APPROVED → received in task 4, or CANCELLED. */
@Entity
@Table(name = "purchase_order")
public class PurchaseOrder extends TenantScoped {

    public enum Status { DRAFT, APPROVED, PARTLY_RECEIVED, RECEIVED, CANCELLED }

    /** A supplier or warehouse as copied onto the document. */
    public record Party(long id, String code, String name) {
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

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Column(name = "supplier_code", nullable = false, length = 20)
    private String supplierCode;

    @Column(name = "supplier_name", nullable = false, length = 200)
    private String supplierName;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "warehouse_code", nullable = false, length = 20)
    private String warehouseCode;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(length = 500)
    private String note;

    @Column(name = "total_minor", nullable = false)
    private long totalMinor;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

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
    private List<PoLine> lines = new ArrayList<>();

    protected PurchaseOrder() {
    }

    public PurchaseOrder(String number, long createdBy) {
        this.publicId = Ulid.next();
        this.number = number;
        this.status = Status.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /** Header and the whole set of lines (drafts only; the service checks the status). */
    public void set(Party supplier, Party warehouse, LocalDate expectedDate, String note, List<PoLine.Spec> specs) {
        this.supplierId = supplier.id();
        this.supplierCode = supplier.code();
        this.supplierName = supplier.name();
        this.warehouseId = warehouse.id();
        this.warehouseCode = warehouse.code();
        this.expectedDate = expectedDate;
        this.note = note;
        // Rows are reused by position: Hibernate inserts before it deletes, so replacing them would clash on line_no.
        while (lines.size() > specs.size()) {
            lines.remove(lines.size() - 1);
        }
        long total = 0;
        for (int i = 0; i < specs.size(); i++) {
            if (i < lines.size()) {
                lines.get(i).set(specs.get(i));
            } else {
                lines.add(new PoLine(this, i + 1, specs.get(i)));
            }
            total = Math.addExact(total, lines.get(i).totalMinor());
        }
        this.totalMinor = total;
        this.lineCount = lines.size();
        if (id != null) {
            this.updatedAt = Instant.now();
        }
    }

    public void approve(long userId) {
        this.status = Status.APPROVED;
        this.approvedBy = userId;
        this.approvedAt = Instant.now();
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

    public Long getSupplierId() {
        return supplierId;
    }

    public String getSupplierCode() {
        return supplierCode;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public String getWarehouseCode() {
        return warehouseCode;
    }

    public LocalDate getExpectedDate() {
        return expectedDate;
    }

    public String getNote() {
        return note;
    }

    public long getTotalMinor() {
        return totalMinor;
    }

    public int getLineCount() {
        return lineCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
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

    public List<PoLine> getLines() {
        return lines;
    }
}

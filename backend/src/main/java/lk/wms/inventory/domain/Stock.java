package lk.wms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lk.wms.common.tenant.TenantScoped;

/**
 * How much of one product (batch, expiry) is in one place: a bin, the receiving area or the dispatch area.
 * Rows are created by StockMovementService's upsert and changed only there, under a row lock (SEC-26).
 */
@Entity
@Table(name = "stock")
public class Stock extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Column(name = "bin_id", updatable = false)
    private Long binId;

    /** BIN, RECEIVING or DISPATCH (the two areas have no bin). */
    @Column(nullable = false, length = 10, updatable = false)
    private String area;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "batch_no", length = 40, updatable = false)
    private String batchNo;

    @Column(name = "expiry_date", updatable = false)
    private LocalDate expiryDate;

    @Column(name = "qty_on_hand", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyOnHand;

    @Column(name = "qty_reserved", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyReserved;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected Stock() {
    }

    public void add(BigDecimal qty) {
        qtyOnHand = qtyOnHand.add(qty);
    }

    public void take(BigDecimal qty) {
        qtyOnHand = qtyOnHand.subtract(qty);
    }

    /** Sets units aside for an order line (the caller checked available()). */
    public void reserve(BigDecimal qty) {
        qtyReserved = qtyReserved.add(qty);
    }

    /** Frees units set aside (never below zero). */
    public void unreserve(BigDecimal qty) {
        qtyReserved = qtyReserved.subtract(qty).max(BigDecimal.ZERO);
    }

    /** What can be moved: on hand minus what picking has reserved. */
    public BigDecimal available() {
        return qtyOnHand.subtract(qtyReserved);
    }

    public boolean isEmpty() {
        return qtyOnHand.signum() == 0 && qtyReserved.signum() == 0;
    }

    public Long getId() {
        return id;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public Long getBinId() {
        return binId;
    }

    public String getArea() {
        return area;
    }

    public Long getProductId() {
        return productId;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public BigDecimal getQtyOnHand() {
        return qtyOnHand;
    }

    public BigDecimal getQtyReserved() {
        return qtyReserved;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}

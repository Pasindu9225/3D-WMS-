package lk.wms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.Immutable;

/** One line of the stock ledger. Append-only: never updated or deleted (database triggers refuse it). */
@Entity
@Immutable
@Table(name = "stock_movement")
public class StockMovement extends TenantScoped {

    public enum Type { RECEIVE, PUTAWAY, TRANSFER, ADJUST, PICK, SHIP, RETURN, COUNT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "batch_no", length = 40)
    private String batchNo;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "from_bin_id")
    private Long fromBinId;

    @Column(name = "to_bin_id")
    private Long toBinId;

    @Column(name = "from_receiving", nullable = false)
    private boolean fromReceiving;

    @Column(name = "to_receiving", nullable = false)
    private boolean toReceiving;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal qty;

    @Column(length = 200)
    private String reason;

    @Column(name = "ref_type", length = 30)
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "client_ref", length = 64)
    private String clientRef;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StockMovement() {
    }

    public StockMovement(Type type, long warehouseId, long productId, String batchNo, LocalDate expiryDate, Long fromBinId,
            boolean fromReceiving, Long toBinId, boolean toReceiving, BigDecimal qty, String reason, String refType,
            Long refId, String clientRef, long userId) {
        this.type = type;
        this.warehouseId = warehouseId;
        this.productId = productId;
        this.batchNo = batchNo;
        this.expiryDate = expiryDate;
        this.fromBinId = fromBinId;
        this.fromReceiving = fromReceiving;
        this.toBinId = toBinId;
        this.toReceiving = toReceiving;
        this.qty = qty;
        this.reason = reason;
        this.refType = refType;
        this.refId = refId;
        this.clientRef = clientRef;
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public Long getWarehouseId() {
        return warehouseId;
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

    public Long getFromBinId() {
        return fromBinId;
    }

    public Long getToBinId() {
        return toBinId;
    }

    public boolean isFromReceiving() {
        return fromReceiving;
    }

    public boolean isToReceiving() {
        return toReceiving;
    }

    public String getReason() {
        return reason;
    }

    public String getRefType() {
        return refType;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

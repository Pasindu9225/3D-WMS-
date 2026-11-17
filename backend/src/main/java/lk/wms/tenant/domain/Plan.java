package lk.wms.tenant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Something that can be sold (v2.0): a price per warehouse per month and how many warehouses come with it. Platform-wide, not per company. */
@Entity
@Table(name = "plan")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30, updatable = false)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "price_per_warehouse_minor", nullable = false)
    private long pricePerWarehouseMinor;

    @Column(name = "default_max_warehouses", nullable = false)
    private int defaultMaxWarehouses;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private int version;

    protected Plan() {
    }

    public Plan(String code, String name, long pricePerWarehouseMinor, int defaultMaxWarehouses) {
        this.code = code;
        set(name, pricePerWarehouseMinor, defaultMaxWarehouses, true);
        this.createdAt = Instant.now();
    }

    public void set(String name, long pricePerWarehouseMinor, int defaultMaxWarehouses, boolean active) {
        this.name = name;
        this.pricePerWarehouseMinor = pricePerWarehouseMinor;
        this.defaultMaxWarehouses = defaultMaxWarehouses;
        this.active = active;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public long getPricePerWarehouseMinor() {
        return pricePerWarehouseMinor;
    }

    public int getDefaultMaxWarehouses() {
        return defaultMaxWarehouses;
    }

    public boolean isActive() {
        return active;
    }

    public int getVersion() {
        return version;
    }
}

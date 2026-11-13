package lk.wms.layout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Published zone. Written only by LayoutPublisher. */
@Entity
@Table(name = "zone")
public class Zone extends TenantScoped {

    public enum Type { RECEIVING, STORAGE, PICKING, DISPATCH, QUARANTINE, COLD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Column(nullable = false, length = 10, updatable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false, length = 60)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String polygon;

    protected Zone() {
    }

    public Zone(long warehouseId, String code) {
        this.warehouseId = warehouseId;
        this.code = code;
    }

    public void set(String type, String name, String polygonJson) {
        this.type = Type.valueOf(type);
        this.name = name;
        this.polygon = polygonJson;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Type getType() {
        return type;
    }
}

package lk.wms.layout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lk.wms.common.tenant.TenantScoped;

/** Published rack. Its size is a copy of the template's at publish time. Written only by LayoutPublisher. */
@Entity
@Table(name = "rack")
public class Rack extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(nullable = false, length = 10, updatable = false)
    private String code;

    @Column(name = "pos_x_mm", nullable = false)
    private int posXMm;

    @Column(name = "pos_y_mm", nullable = false)
    private int posYMm;

    @Column(name = "rotation_deg", nullable = false)
    private short rotationDeg;

    @Column(name = "width_mm", nullable = false)
    private int widthMm;

    @Column(name = "depth_mm", nullable = false)
    private int depthMm;

    @Column(name = "height_mm", nullable = false)
    private int heightMm;

    protected Rack() {
    }

    public Rack(long warehouseId, String code) {
        this.warehouseId = warehouseId;
        this.code = code;
    }

    public void place(long zoneId, RackTemplate template, int xMm, int yMm, int rotationDeg) {
        this.zoneId = zoneId;
        this.templateId = template.getId();
        this.posXMm = xMm;
        this.posYMm = yMm;
        this.rotationDeg = (short) rotationDeg;
        this.widthMm = template.getWidthMm();
        this.depthMm = template.getDepthMm();
        this.heightMm = template.getHeightMm();
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }
}

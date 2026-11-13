package lk.wms.layout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lk.wms.common.tenant.TenantScoped;

/** A shelf level of a published rack; level 1 is on the floor. Written only by LayoutPublisher. */
@Entity
@Table(name = "rack_level")
public class RackLevel extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rack_id", nullable = false, updatable = false)
    private Long rackId;

    @Column(name = "level_no", nullable = false, updatable = false)
    private short levelNo;

    @Column(name = "height_from_floor_mm", nullable = false)
    private int heightFromFloorMm;

    @Column(name = "max_weight_g", nullable = false)
    private long maxWeightG;

    protected RackLevel() {
    }

    public RackLevel(long rackId, int levelNo) {
        this.rackId = rackId;
        this.levelNo = (short) levelNo;
    }

    public void set(int heightFromFloorMm, long maxWeightG) {
        this.heightFromFloorMm = heightFromFloorMm;
        this.maxWeightG = maxWeightG;
    }

    public Long getId() {
        return id;
    }

    public Long getRackId() {
        return rackId;
    }

    public int getLevelNo() {
        return levelNo;
    }
}

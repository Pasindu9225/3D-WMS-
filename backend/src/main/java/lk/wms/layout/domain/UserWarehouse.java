package lk.wms.layout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import lk.wms.common.tenant.TenantScoped;

/** A user may work in a warehouse (SEC-10). Tenant admins need no rows: they see every warehouse. */
@Entity
@IdClass(UserWarehouse.Key.class)
@Table(name = "user_warehouse")
public class UserWarehouse extends TenantScoped {

    public static class Key implements Serializable {
        private Long userId;
        private Long warehouseId;

        protected Key() {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(userId, k.userId) && Objects.equals(warehouseId, k.warehouseId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, warehouseId);
        }
    }

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "warehouse_id")
    private Long warehouseId;

    protected UserWarehouse() {
    }

    public UserWarehouse(long userId, long warehouseId) {
        this.userId = userId;
        this.warehouseId = warehouseId;
    }

    public Long getUserId() {
        return userId;
    }
}

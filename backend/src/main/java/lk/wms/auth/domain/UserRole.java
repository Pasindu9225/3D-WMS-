package lk.wms.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import lk.wms.common.tenant.TenantScoped;

/** A role given to a user. Tenant-scoped, so one tenant can never see or change another's assignments. */
@Entity
@IdClass(UserRole.Key.class)
@Table(name = "user_role")
public class UserRole extends TenantScoped {

    public static class Key implements Serializable {
        private Long userId;
        private Long roleId;

        protected Key() {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(userId, k.userId) && Objects.equals(roleId, k.roleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, roleId);
        }
    }

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "role_id")
    private Long roleId;

    protected UserRole() {
    }

    public UserRole(long userId, long roleId) {
        this.userId = userId;
        this.roleId = roleId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getRoleId() {
        return roleId;
    }
}

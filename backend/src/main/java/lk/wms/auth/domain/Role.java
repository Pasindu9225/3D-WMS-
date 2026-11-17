package lk.wms.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.Set;
import org.hibernate.annotations.Immutable;

/** Built-in role, global to all tenants and seeded by Flyway (V2). Read only in code. */
@Entity
@Immutable
@Table(name = "role")
public class Role {

    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    /** Platform operator (v2.0); only users of the platform company, never given by a company admin. */
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    @Id
    private Long id;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @ManyToMany
    @JoinTable(name = "role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions;

    protected Role() {
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }
}

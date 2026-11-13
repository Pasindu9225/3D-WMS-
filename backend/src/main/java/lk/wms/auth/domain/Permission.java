package lk.wms.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Permission such as {@code users:manage}; checked with @PreAuthorize("hasAuthority('...')"). */
@Entity
@Immutable
@Table(name = "permission")
public class Permission {

    @Id
    private Long id;

    @Column(nullable = false, length = 60)
    private String code;

    @Column(nullable = false, length = 200)
    private String description;

    protected Permission() {
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}

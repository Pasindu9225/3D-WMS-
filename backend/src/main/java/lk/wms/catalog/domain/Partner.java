package lk.wms.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lk.wms.common.Ulid;
import lk.wms.common.tenant.TenantScoped;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

/** A supplier (and later a customer) of the company. */
@Entity
@Table(name = "partner")
@SQLRestriction("deleted_at is null")
public class Partner extends TenantScoped {

    public enum Type { SUPPLIER, CUSTOMER, CLIENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 26, nullable = false, updatable = false)
    private String publicId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Type type;

    @Column(nullable = false, length = 20, updatable = false)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "contact_name", length = 120)
    private String contactName;

    @Column(length = 30)
    private String phone;

    @Column(length = 254)
    private String email;

    @Column(length = 500)
    private String address;

    @Version
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Clients (3PL, v2.1): HS sections 1-21 they may store, "2,4"; null = any. */
    @Column(name = "allowed_hs_sections", length = 80)
    private String allowedHsSections;

    protected Partner() {
    }

    public Partner(Type type, String code) {
        this.publicId = Ulid.next();
        this.type = type;
        this.code = code;
        this.createdAt = Instant.now();
    }

    public void set(String name, String contactName, String phone, String email, String address) {
        this.name = name;
        this.contactName = contactName;
        this.phone = phone;
        this.email = email;
        this.address = address;
        this.updatedAt = Instant.now();
    }

    public void setAllowedHsSections(String sections) {
        this.allowedHsSections = sections;
    }

    public String getAllowedHsSections() {
        return allowedHsSections;
    }

    public void archive() {
        this.deletedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Type getType() {
        return type;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getContactName() {
        return contactName;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getAddress() {
        return address;
    }

    public int getVersion() {
        return version;
    }
}

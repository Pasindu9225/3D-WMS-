package lk.wms.inbound.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lk.wms.common.tenant.TenantScoped;

/** The company's next running number for one kind of document (PO, later GRN). Changed only by the repo's bump. */
@Entity
@Table(name = "doc_counter")
public class DocCounter extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10, updatable = false)
    private String kind;

    @Column(name = "next_no", nullable = false)
    private int nextNo;

    protected DocCounter() {
    }
}

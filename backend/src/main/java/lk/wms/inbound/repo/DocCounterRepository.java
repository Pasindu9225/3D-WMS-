package lk.wms.inbound.repo;

import lk.wms.inbound.domain.DocCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocCounterRepository extends JpaRepository<DocCounter, Long> {

    /**
     * Takes a number: creates the counter on the company's first document, else adds one. Either way the row is
     * write-locked until commit, so concurrent requests queue here and never share a number (no gap-lock deadlock,
     * unlike SELECT ... FOR UPDATE on a row that does not exist yet).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO doc_counter (tenant_id, kind, next_no) VALUES (:tenantId, :kind, 2)"
            + " ON DUPLICATE KEY UPDATE next_no = next_no + 1", nativeQuery = true)
    int bump(@Param("tenantId") long tenantId, @Param("kind") String kind);

    /** The number just taken by bump (read in the same transaction). */
    @Query("select c.nextNo - 1 from DocCounter c where c.kind = :kind")
    int taken(@Param("kind") String kind);
}

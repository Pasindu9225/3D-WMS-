package lk.wms.inventory.repo;

import java.util.List;
import lk.wms.inventory.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant filter by @TenantId. */
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByRefTypeAndRefIdOrderByRefLineAscIdAsc(String refType, Long refId);
}

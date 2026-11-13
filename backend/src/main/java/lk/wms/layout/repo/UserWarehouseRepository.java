package lk.wms.layout.repo;

import java.util.List;
import lk.wms.layout.domain.UserWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserWarehouseRepository extends JpaRepository<UserWarehouse, UserWarehouse.Key> {

    @Query("select uw.userId from UserWarehouse uw where uw.warehouseId = :warehouseId")
    List<Long> findUserIds(@Param("warehouseId") long warehouseId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserWarehouse uw where uw.warehouseId = :warehouseId")
    int deleteAllForWarehouse(@Param("warehouseId") long warehouseId);
}

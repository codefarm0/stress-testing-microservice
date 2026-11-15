package in.codefarm.stress_testing_microservice.inventory;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /**
     * SELECT ... FOR UPDATE: the row stays locked until the surrounding transaction commits.
     * Concurrent orders for the same product serialize here -> this is where lock waits show up.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.productId = :productId")
    Optional<Inventory> findForUpdate(@Param("productId") Long productId);
}

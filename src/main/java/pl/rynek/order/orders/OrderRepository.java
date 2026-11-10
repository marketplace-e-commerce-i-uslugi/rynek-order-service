package pl.rynek.order.orders;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Query(value = "select nextval('order_number_seq')", nativeQuery = true)
    long nextNumber();

    @Query(value = "select * from orders where buyer_id = :buyerId and (cast(:status as varchar) is null or status = cast(:status as varchar))"
            + " order by created_at desc, id limit :limit offset :offset", nativeQuery = true)
    List<Order> findOfBuyer(UUID buyerId, String status, int offset, int limit);

    @Query(value = "select * from orders where seller_id = :sellerId and (cast(:status as varchar) is null or status = cast(:status as varchar))"
            + " order by created_at desc, id limit :limit offset :offset", nativeQuery = true)
    List<Order> findOfSeller(UUID sellerId, String status, int offset, int limit);

    List<Order> findByIdIn(Collection<UUID> ids);

    @Query("select o.id from Order o where o.status = pl.rynek.order.orders.OrderStatus.AWAITING_PAYMENT and o.createdAt < :deadline")
    List<UUID> findAwaitingPaymentBefore(java.time.Instant deadline);
}

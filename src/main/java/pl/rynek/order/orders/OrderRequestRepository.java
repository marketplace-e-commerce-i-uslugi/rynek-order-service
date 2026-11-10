package pl.rynek.order.orders;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRequestRepository extends JpaRepository<OrderRequest, OrderRequest.Key> {
}

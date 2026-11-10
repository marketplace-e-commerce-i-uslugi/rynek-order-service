package pl.rynek.order.orders;

import java.io.Serializable;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Zapamiętany wynik {@code POST /orders} dla pary (kupujący, Idempotency-Key). */
@Entity
@Table(name = "order_requests")
@IdClass(OrderRequest.Key.class)
public class OrderRequest {

    @Id
    @Column(name = "buyer_id")
    private UUID buyerId;

    @Id
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "order_ids")
    private String orderIds;

    @Column(name = "created_at")
    private Instant createdAt;

    protected OrderRequest() {
    }

    OrderRequest(UUID buyerId, String idempotencyKey, List<UUID> orderIds, Instant createdAt) {
        this.buyerId = buyerId;
        this.idempotencyKey = idempotencyKey;
        this.orderIds = orderIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        this.createdAt = createdAt;
    }

    List<UUID> orderIds() {
        return Arrays.stream(orderIds.split(",")).map(UUID::fromString).toList();
    }

    public record Key(UUID buyerId, String idempotencyKey) implements Serializable {

        public Key() {
            this(null, null);
        }
    }
}

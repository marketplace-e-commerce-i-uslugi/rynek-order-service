package pl.rynek.order.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.order.api.dto.OrderResponse;
import pl.rynek.order.orders.Order;
import pl.rynek.order.orders.OrderService;

@RestController
public class InternalOrdersController {

    private final OrderService orders;
    private final OrderViews views;

    public InternalOrdersController(OrderService orders, OrderViews views) {
        this.orders = orders;
        this.views = views;
    }

    /**
     * Kwota, status i kupujący przed rozpoczęciem płatności (payment-service). {@code status} to nazwa stałej,
     * np. {@code AWAITING_PAYMENT}. Dostęp: int.
     */
    @GetMapping("/internal/orders/{orderId}")
    public InternalOrder getOrder(@PathVariable UUID orderId) {
        return InternalOrder.of(orders.internal(orderId));
    }

    /** Wiele zamówień naraz ({@code ?ids=a,b}, najwyżej {@value #MAX_BATCH}), np. statusy do salda sprzedawcy. Dostęp: int. */
    @GetMapping("/internal/orders")
    public List<InternalOrder> getOrders(@RequestParam List<UUID> ids) {
        if (ids.size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Najwyżej " + MAX_BATCH + " zamówień naraz.");
        }
        return orders.internal(ids).stream().map(InternalOrder::of).toList();
    }

    static final int MAX_BATCH = 100;

    /**
     * Oznaczenie jako opłacone (po zdarzeniu z płatności): „Oczekuje na płatność” → „Do wysłania”. Ponowienie nic nie
     * zmienia, anulowane daje 409. Ciało {@code {paymentId}} jest opcjonalne (płatność do zwrotu środków). Dostęp: int.
     */
    @PostMapping("/internal/orders/{orderId}/mark-paid")
    public OrderResponse markPaid(@PathVariable UUID orderId, @RequestBody(required = false) MarkPaidRequest request) {
        return views.one(orders.markPaid(orderId, request == null ? null : request.paymentId()));
    }

    public record InternalOrder(UUID id, String number, UUID buyerId, UUID sellerId, String status, long total) {

        static InternalOrder of(Order o) {
            return new InternalOrder(o.getId(), "#" + o.getNumber(), o.getBuyerId(), o.getSellerId(), o.getStatus().name(),
                    o.getItemsTotal() + o.getShippingTotal());
        }
    }

    public record MarkPaidRequest(UUID paymentId) {
    }
}

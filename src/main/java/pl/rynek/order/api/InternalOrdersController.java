package pl.rynek.order.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.order.api.dto.OrderResponse;
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
     * Oznaczenie jako opłacone (po zdarzeniu z płatności): „Oczekuje na płatność” → „Do wysłania”. Ponowienie nic nie
     * zmienia, anulowane daje 409. Dostęp: int.
     */
    @PostMapping("/internal/orders/{orderId}/mark-paid")
    public OrderResponse markPaid(@PathVariable UUID orderId) {
        return views.one(orders.markPaid(orderId));
    }
}

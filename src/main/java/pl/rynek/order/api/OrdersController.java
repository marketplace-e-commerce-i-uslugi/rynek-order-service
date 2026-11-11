package pl.rynek.order.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.order.api.dto.CreateOrderRequest;
import pl.rynek.order.api.dto.CursorPage;
import pl.rynek.order.api.dto.OrderResponse;
import pl.rynek.order.api.dto.RequestReturnRequest;
import pl.rynek.order.api.dto.TimelineEntry;
import pl.rynek.order.orders.OrderService;
import pl.rynek.order.orders.OrderStatus;

import static pl.rynek.order.common.NotImplemented.notImplemented;

@RestController
public class OrdersController {

    private final OrderService orders;
    private final OrderViews views;

    public OrdersController(OrderService orders, OrderViews views) {
        this.orders = orders;
        this.views = views;
    }

    /**
     * Utworzenie zamówienia z koszyka lub „Kup teraz” ({@code Idempotency-Key}); rezerwuje zapas. Dostęp: user.
     * Zwraca listę: zakup z koszyka od kilku sprzedawców to kilka zamówień.
     */
    @PostMapping("/api/v1/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public List<OrderResponse> createOrder(@AuthenticationPrincipal Jwt token,
                                           @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                           @Valid @RequestBody CreateOrderRequest request) {
        return views.many(orders.create(userId(token), idempotencyKey, request));
    }

    /**
     * Moje zamówienia. Dostęp: user.
     */
    @GetMapping("/api/v1/orders/mine")
    public CursorPage<OrderResponse> listMyOrders(@AuthenticationPrincipal Jwt token, @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        OrderStatus filter = OrderViews.status(status);
        UUID buyer = userId(token);
        return views.page(cursor, limit, (offset, n) -> orders.ofBuyer(buyer, filter, offset, n));
    }

    /**
     * Szczegóły zamówienia. Dostęp: user / seller.
     */
    @GetMapping("/api/v1/orders/{orderId}")
    public OrderResponse getOrder(@AuthenticationPrincipal Jwt token, @PathVariable UUID orderId) {
        return views.one(orders.visible(userId(token), orderId));
    }

    /**
     * Oś statusów. Dostęp: user / seller.
     */
    @GetMapping("/api/v1/orders/{orderId}/timeline")
    public List<TimelineEntry> getTimeline(@AuthenticationPrincipal Jwt token, @PathVariable UUID orderId) {
        return orders.visible(userId(token), orderId).getHistory().stream().map(h -> new TimelineEntry(h.status(), h.at())).toList();
    }

    /**
     * Anulowanie (przed wysyłką): sztuki wracają do zapasu, a za opłacone zamówienie payment-service zwraca pieniądze.
     * Dostęp: user. Ciało ({@code reason}) jest opcjonalne i na razie nie jest zapisywane.
     */
    @PostMapping("/api/v1/orders/{orderId}/cancel")
    public OrderResponse cancelOrder(@AuthenticationPrincipal Jwt token, @PathVariable UUID orderId) {
        return views.one(orders.cancelByBuyer(userId(token), orderId));
    }

    /**
     * Zgłoszenie zwrotu. Dostęp: user.
     */
    @PostMapping("/api/v1/orders/{orderId}/returns")
    public void requestReturn(@PathVariable String orderId, @RequestBody RequestReturnRequest request) {
        throw notImplemented();
    }

    private static UUID userId(Jwt token) {
        return UUID.fromString(token.getSubject());
    }
}

package pl.rynek.order.api;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.order.api.dto.ChangeStatusRequest;
import pl.rynek.order.api.dto.CursorPage;
import pl.rynek.order.api.dto.MarkShippedRequest;
import pl.rynek.order.api.dto.OrderResponse;
import pl.rynek.order.api.dto.RejectReturnRequest;
import pl.rynek.order.orders.OrderService;
import pl.rynek.order.orders.OrderStatus;

/** Sprzedawca to właściciel kanału, z którego pochodzą oferty zamówienia ({@code seller_id} = sub z JWT). */
@RestController
public class SellerOrdersController {

    private final OrderService orders;
    private final OrderViews views;

    public SellerOrdersController(OrderService orders, OrderViews views) {
        this.orders = orders;
        this.views = views;
    }

    /**
     * Zamówienia sprzedawcy. Dostęp: seller.
     */
    @GetMapping("/api/v1/seller/orders")
    public CursorPage<OrderResponse> listOrders(@AuthenticationPrincipal Jwt token, @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        OrderStatus filter = OrderViews.status(status);
        UUID seller = userId(token);
        return views.page(cursor, limit, (offset, n) -> orders.ofSeller(seller, filter, offset, n));
    }

    /**
     * Oznaczenie jako wysłane (numer przesyłki). Dostęp: seller.
     */
    @PostMapping("/api/v1/seller/orders/{orderId}/ship")
    public OrderResponse markShipped(@AuthenticationPrincipal Jwt token, @PathVariable UUID orderId,
                                     @Valid @RequestBody MarkShippedRequest request) {
        return views.one(orders.ship(userId(token), orderId, request.trackingNumber()));
    }

    /**
     * Zmiana statusu: „Zrealizowane” (z „W drodze”) albo „Anulowane” (przed wysyłką). Dostęp: seller.
     */
    @PostMapping("/api/v1/seller/orders/{orderId}/status")
    public OrderResponse changeStatus(@AuthenticationPrincipal Jwt token, @PathVariable UUID orderId,
                                      @Valid @RequestBody ChangeStatusRequest request) {
        return views.one(orders.changeBySeller(userId(token), orderId, OrderViews.status(request.status())));
    }

    /**
     * Odrzucenie zgłoszonego zwrotu z uzasadnieniem dla kupującego. Dostęp: seller. Przyjęcie zwrotu to zwrot pieniędzy
     * ({@code POST /payments/{paymentId}/refund}).
     */
    @PostMapping("/api/v1/seller/orders/{orderId}/returns/reject")
    public OrderResponse rejectReturn(@AuthenticationPrincipal Jwt token, @PathVariable UUID orderId,
                                      @Valid @RequestBody RejectReturnRequest request) {
        return views.one(orders.rejectReturn(userId(token), orderId, request.reason()));
    }

    private static UUID userId(Jwt token) {
        return UUID.fromString(token.getSubject());
    }
}

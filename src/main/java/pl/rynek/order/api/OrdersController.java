package pl.rynek.order.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.order.api.dto.CancelOrderRequest;
import pl.rynek.order.api.dto.CreateOrderRequest;
import pl.rynek.order.api.dto.RequestReturnRequest;

import static pl.rynek.order.common.NotImplemented.notImplemented;

@RestController
public class OrdersController {

    /**
     * Utworzenie zamówienia z koszyka lub „Kup teraz” (Idempotency-Key); rezerwuje zapas. Dostęp: user.
     */
    @PostMapping("/api/v1/orders")
    public void createOrder(@RequestBody CreateOrderRequest request) {
        throw notImplemented();
    }

    /**
     * Moje zamówienia. Dostęp: user.
     */
    @GetMapping("/api/v1/orders/mine")
    public void listMyOrders(@RequestParam(required = false) String status, @RequestParam(required = false) String cursor) {
        throw notImplemented();
    }

    /**
     * Szczegóły zamówienia. Dostęp: user / seller.
     */
    @GetMapping("/api/v1/orders/{orderId}")
    public void getOrder(@PathVariable String orderId) {
        throw notImplemented();
    }

    /**
     * Oś statusów. Dostęp: user / seller.
     */
    @GetMapping("/api/v1/orders/{orderId}/timeline")
    public void getTimeline(@PathVariable String orderId) {
        throw notImplemented();
    }

    /**
     * Anulowanie (przed wysyłką). Dostęp: user.
     */
    @PostMapping("/api/v1/orders/{orderId}/cancel")
    public void cancelOrder(@PathVariable String orderId, @RequestBody CancelOrderRequest request) {
        throw notImplemented();
    }

    /**
     * Zgłoszenie zwrotu. Dostęp: user.
     */
    @PostMapping("/api/v1/orders/{orderId}/returns")
    public void requestReturn(@PathVariable String orderId, @RequestBody RequestReturnRequest request) {
        throw notImplemented();
    }
}

package pl.rynek.order.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.order.api.dto.ChangeStatusRequest;
import pl.rynek.order.api.dto.MarkShippedRequest;

import static pl.rynek.order.common.NotImplemented.notImplemented;

@RestController
public class SellerOrdersController {

    /**
     * Zamówienia sprzedawcy. Dostęp: seller.
     */
    @GetMapping("/api/v1/seller/orders")
    public void listOrders(@RequestParam(required = false) String status, @RequestParam(required = false) String cursor) {
        throw notImplemented();
    }

    /**
     * Oznaczenie jako wysłane (numer przesyłki). Dostęp: seller.
     */
    @PostMapping("/api/v1/seller/orders/{orderId}/ship")
    public void markShipped(@PathVariable String orderId, @RequestBody MarkShippedRequest request) {
        throw notImplemented();
    }

    /**
     * Zmiana statusu. Dostęp: seller.
     */
    @PostMapping("/api/v1/seller/orders/{orderId}/status")
    public void changeStatus(@PathVariable String orderId, @RequestBody ChangeStatusRequest request) {
        throw notImplemented();
    }
}

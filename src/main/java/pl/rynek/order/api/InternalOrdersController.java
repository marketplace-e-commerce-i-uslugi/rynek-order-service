package pl.rynek.order.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static pl.rynek.order.common.NotImplemented.notImplemented;

@RestController
public class InternalOrdersController {

    /**
     * Oznaczenie jako opłacone (po zdarzeniu z płatności). Dostęp: int.
     */
    @PostMapping("/internal/orders/{orderId}/mark-paid")
    public void markPaid(@PathVariable String orderId) {
        throw notImplemented();
    }
}

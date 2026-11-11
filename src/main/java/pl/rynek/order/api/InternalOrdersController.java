package pl.rynek.order.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    /**
     * Przesyłka utworzona w shipping-service: {@code shipmentId}, przewoźnik i numer przesyłki trafiają do zamówienia
     * (status się nie zmienia). Dostęp: int.
     */
    @PutMapping("/internal/orders/{orderId}/shipment")
    public OrderResponse attachShipment(@PathVariable UUID orderId, @RequestBody ShipmentRequest request) {
        if (request.shipmentId() == null || request.trackingNumber() == null || request.trackingNumber().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Podaj przesyłkę i jej numer.");
        }
        return views.one(orders.attachShipment(orderId, request.shipmentId(), request.carrier(), request.trackingNumber().trim()));
    }

    /**
     * Status od przewoźnika (zamiast zdarzenia {@code shipment.status-changed}): {@code PICKED_UP} → „W drodze”,
     * {@code DELIVERED} → „Zrealizowane”. Ponowienie niczego nie zmienia, anulowane zamówienie daje 409. Dostęp: int.
     */
    @PostMapping("/internal/orders/{orderId}/shipment-status")
    public OrderResponse carrierStatus(@PathVariable UUID orderId, @RequestBody CarrierStatusRequest request) {
        return views.one(orders.carrierStatus(orderId, request.status() == null ? "" : request.status()));
    }

    /**
     * payment-service zwrócił pieniądze za zamówienie (sprzedawca albo admin): zgłoszony zwrot jest przyjęty.
     * Ponowienie niczego nie zmienia. Dostęp: int.
     */
    @PostMapping("/internal/orders/{orderId}/refunded")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refunded(@PathVariable UUID orderId) {
        orders.refunded(orderId);
    }

    public record ShipmentRequest(UUID shipmentId, String carrier, String trackingNumber) {
    }

    public record CarrierStatusRequest(String status) {
    }

    /**
     * {@code receiver} i {@code shipping} są dla shipping-service (dane na etykietę). {@code status} to nazwa stałej.
     */
    public record InternalOrder(UUID id, String number, UUID buyerId, UUID sellerId, String status, long total, Receiver receiver,
                                Delivery shipping) {

        static InternalOrder of(Order o) {
            Order.Address a = o.getAddress();
            return new InternalOrder(o.getId(), "#" + o.getNumber(), o.getBuyerId(), o.getSellerId(), o.getStatus().name(),
                    o.getItemsTotal() + o.getShippingTotal(),
                    new Receiver(a.fullName(), a.phone(), a.street(), a.postalCode(), a.city()),
                    new Delivery(o.getShippingMethodId(), o.getPickupPointId(), o.getPickupPointLabel(), o.getShipmentId()));
        }
    }

    public record Receiver(String fullName, String phone, String street, String postalCode, String city) {
    }

    public record Delivery(String methodId, String pickupPointId, String pickupPoint, UUID shipmentId) {
    }

    public record MarkPaidRequest(UUID paymentId) {
    }
}

package pl.rynek.order.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import pl.rynek.order.clients.ChannelClient.ChannelRef;
import pl.rynek.order.orders.Order;
import pl.rynek.order.orders.OrderStatus;

/**
 * Kształt {@code Order} z rynek-frontend (commerce.ts) plus {@code itemsTotal}, {@code shippingTotal},
 * {@code shipping} i {@code trackingNumber}. {@code paymentId}: płatność, która opłaciła zamówienie. {@code shipmentId}: przesyłka
 * z shipping-service (etykieta, śledzenie); pusty, gdy sprzedawca wysłał paczkę bez etykiety z Rynku.
 */
public record OrderResponse(UUID id, String number, OrderStatus status, Instant createdAt, ChannelRef channel, String buyerName,
                            List<Line> lines, MoneyDto itemsTotal, MoneyDto shippingTotal, MoneyDto total, Address shippingAddress,
                            Shipping shipping, String trackingNumber, UUID shipmentId, UUID paymentId, boolean reviewed) {

    public static OrderResponse of(Order o, ChannelRef channel) {
        Order.Address a = o.getAddress();
        return new OrderResponse(o.getId(), "#" + o.getNumber(), o.getStatus(), o.getCreatedAt(), channel, a.fullName(),
                o.getLines().stream().map(l -> new Line(l.offerId(), l.title(), l.quantity(), MoneyDto.pln(l.unitPrice()))).toList(),
                MoneyDto.pln(o.getItemsTotal()), MoneyDto.pln(o.getShippingTotal()), MoneyDto.pln(o.getItemsTotal() + o.getShippingTotal()),
                new Address(a.id(), a.fullName(), a.phone(), a.street(), a.postalCode(), a.city()),
                new Shipping(o.getShippingMethodId(), o.getShippingMethodName(), o.getPickupPointId(), o.getPickupPointLabel()),
                o.getTrackingNumber(), o.getShipmentId(), o.getPaymentId(), false);
    }

    public record Line(UUID offerId, String title, int quantity, MoneyDto unitPrice) {
    }

    public record Address(UUID id, String fullName, String phone, String street, String postalCode, String city) {
    }

    public record Shipping(String methodId, String name, String pickupPointId, String pickupPoint) {
    }
}

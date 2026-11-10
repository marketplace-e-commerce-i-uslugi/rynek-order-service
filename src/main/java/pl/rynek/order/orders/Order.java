package pl.rynek.order.orders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Zamówienie u jednego sprzedawcy. {@code @Version}: kupujący anulujący zamówienie i sprzedawca oznaczający je
 * jako wysłane w tej samej chwili nie nadpiszą sobie statusu (drugi dostaje 409).
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    private long number;

    @Column(name = "buyer_id")
    private UUID buyerId;

    @Column(name = "seller_id")
    private UUID sellerId;

    @Column(name = "channel_id")
    private UUID channelId;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(name = "items_total")
    private long itemsTotal;

    @Column(name = "shipping_total")
    private long shippingTotal;

    private String currency;

    @Column(name = "shipping_method_id")
    private String shippingMethodId;

    @Column(name = "shipping_method_name")
    private String shippingMethodName;

    @Column(name = "pickup_point_id")
    private String pickupPointId;

    @Column(name = "pickup_point_label")
    private String pickupPointLabel;

    @Column(name = "address_id")
    private UUID addressId;

    @Column(name = "full_name")
    private String fullName;

    private String phone;

    private String street;

    @Column(name = "postal_code")
    private String postalCode;

    private String city;

    @Column(name = "tracking_number")
    private String trackingNumber;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private int version;

    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "order_lines", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "position")
    private List<Line> lines = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "order_status_history", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "position")
    private List<StatusChange> history = new ArrayList<>();

    protected Order() {
    }

    Order(long number, UUID buyerId, UUID sellerId, UUID channelId, List<Line> lines, Shipping shipping, Address address, Instant now) {
        this.id = UUID.randomUUID();
        this.number = number;
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.channelId = channelId;
        this.lines = new ArrayList<>(lines);
        this.itemsTotal = lines.stream().mapToLong(l -> l.unitPrice() * l.quantity()).sum();
        this.shippingTotal = shipping.price();
        this.currency = "PLN";
        this.shippingMethodId = shipping.methodId();
        this.shippingMethodName = shipping.methodName();
        this.pickupPointId = shipping.pickupPointId();
        this.pickupPointLabel = shipping.pickupPointLabel();
        this.addressId = address.id();
        this.fullName = address.fullName();
        this.phone = address.phone();
        this.street = address.street();
        this.postalCode = address.postalCode();
        this.city = address.city();
        this.createdAt = now;
        moveTo(OrderStatus.AWAITING_PAYMENT, now);
    }

    void markPaid(UUID paymentId, Instant now) {
        if (status == OrderStatus.CANCELLED) {
            throw conflict("Zamówienie zostało anulowane, więc nie można go opłacić.");
        }
        if (status == OrderStatus.AWAITING_PAYMENT) {
            this.paymentId = paymentId;
            moveTo(OrderStatus.TO_SHIP, now);
        }
    }

    void cancel(Instant now) {
        if (!status.cancellable()) {
            throw conflict(switch (status) {
                case CANCELLED -> "Zamówienie jest już anulowane.";
                case TO_SHIP -> "Opłaconego zamówienia nie można na razie anulować, bo nie obsługujemy jeszcze zwrotów pieniędzy.";
                default -> "Zamówienia w statusie „" + status.label() + "” nie można anulować.";
            });
        }
        moveTo(OrderStatus.CANCELLED, now);
    }

    void ship(String trackingNumber, Instant now) {
        if (status != OrderStatus.TO_SHIP) {
            throw conflict(status == OrderStatus.AWAITING_PAYMENT ? "Zamówienie czeka na płatność. Wyślij je po zaksięgowaniu wpłaty."
                    : "Wysłać można tylko zamówienie w statusie „Do wysłania”.");
        }
        this.trackingNumber = trackingNumber;
        moveTo(OrderStatus.SHIPPED, now);
    }

    void complete(Instant now) {
        if (status != OrderStatus.SHIPPED) {
            throw conflict("Zrealizować można tylko zamówienie w drodze.");
        }
        moveTo(OrderStatus.COMPLETED, now);
    }

    private void moveTo(OrderStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
        this.history.add(new StatusChange(next, now));
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public UUID getId() {
        return id;
    }

    public long getNumber() {
        return number;
    }

    public UUID getBuyerId() {
        return buyerId;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public UUID getChannelId() {
        return channelId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getItemsTotal() {
        return itemsTotal;
    }

    public long getShippingTotal() {
        return shippingTotal;
    }

    public String getShippingMethodId() {
        return shippingMethodId;
    }

    public String getShippingMethodName() {
        return shippingMethodName;
    }

    public String getPickupPointId() {
        return pickupPointId;
    }

    public String getPickupPointLabel() {
        return pickupPointLabel;
    }

    public Address getAddress() {
        return new Address(addressId, fullName, phone, street, postalCode, city);
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Line> getLines() {
        return List.copyOf(lines);
    }

    public List<StatusChange> getHistory() {
        return List.copyOf(history);
    }

    /** {@code id} pozycji jest zarazem id rezerwacji zapasu w catalog-service. */
    @Embeddable
    public record Line(UUID id, @Column(name = "offer_id") UUID offerId, String title, int quantity,
                       @Column(name = "unit_price") long unitPrice) {
    }

    @Embeddable
    public record StatusChange(@Enumerated(EnumType.STRING) OrderStatus status, Instant at) {
    }

    public record Address(UUID id, String fullName, String phone, String street, String postalCode, String city) {
    }

    record Shipping(String methodId, String methodName, long price, String pickupPointId, String pickupPointLabel) {
    }
}

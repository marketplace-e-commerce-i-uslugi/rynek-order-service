package pl.rynek.order.orders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.order.api.dto.CreateOrderRequest;
import pl.rynek.order.clients.CartClient;
import pl.rynek.order.clients.CartClient.CartSnapshot;
import pl.rynek.order.clients.CatalogClient;
import pl.rynek.order.clients.CatalogClient.OfferInfo;
import pl.rynek.order.clients.IdentityClient;
import pl.rynek.order.clients.PaymentClient;
import pl.rynek.order.clients.ShippingClient;
import pl.rynek.order.clients.ShippingClient.Method;
import pl.rynek.order.orders.Order.Line;

/**
 * Składanie zamówień i zmiany statusu. Złożenie rezerwuje zapas w catalog przed zapisem; jeśli cokolwiek się nie uda
 * (brak sztuk, błąd zapisu, ten sam Idempotency-Key równolegle), zarezerwowane sztuki wracają do zapasu.
 * Rezerwacja trwa do anulowania albo wygaśnięcia zamówienia. Anulowanie trafia do payment-service (zwrot pieniędzy
 * za opłacone zamówienie); gdy payment-service nie odpowiada, zgłoszenie jest ponawiane co minutę.
 */
@Service
public class OrderService {

    static final int MAX_LINES = 50;

    private final OrderRepository orders;
    private final OrderRequestRepository requests;
    private final CatalogClient catalog;
    private final CartClient cart;
    private final IdentityClient identity;
    private final ShippingClient shipping;
    private final PaymentClient payment;
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderNotifications notices;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration paymentTimeout;
    private final Duration returnWindow;

    public OrderService(OrderRepository orders, OrderRequestRepository requests, CatalogClient catalog, CartClient cart,
                        IdentityClient identity, ShippingClient shipping, PaymentClient payment, OrderNotifications notices,
                        PlatformTransactionManager transactions, Clock clock,
                        @Value("${rynek.orders.payment-timeout:30m}") Duration paymentTimeout,
                        @Value("${rynek.orders.return-window:14d}") Duration returnWindow) {
        this.orders = orders;
        this.requests = requests;
        this.catalog = catalog;
        this.cart = cart;
        this.identity = identity;
        this.shipping = shipping;
        this.payment = payment;
        this.notices = notices;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
        this.paymentTimeout = paymentTimeout;
        this.returnWindow = returnWindow;
    }

    /** Zakup z koszyka tworzy jedno zamówienie na sprzedawcę; „Kup teraz” jedno zamówienie z jedną pozycją. */
    public List<Order> create(UUID buyerId, String idempotencyKey, CreateOrderRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Brak nagłówka Idempotency-Key (do 100 znaków).");
        }
        Optional<List<Order>> earlier = earlier(buyerId, idempotencyKey);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        Order.Address address = identity.address(buyerId, request.addressId())
                .map(a -> new Order.Address(a.id(), a.fullName(), a.phone(), a.street(), a.postalCode(), a.city()))
                .orElseThrow(() -> unprocessable("Wybierz adres dostawy."));
        List<Package> packages = "BUY_NOW".equals(request.source()) ? buyNow(buyerId, request) : fromCart(buyerId);

        List<Line> reserved = new ArrayList<>();
        try {
            for (Package p : packages) {
                for (Line line : p.lines()) {
                    catalog.reserve(line.offerId(), line.id(), line.quantity(), line.title());
                    reserved.add(line);
                }
            }
            List<Order> created = save(buyerId, idempotencyKey, packages, address);
            if ("CART".equals(request.source())) {
                cart.removeOffers(buyerId, packages.stream().flatMap(p -> p.lines().stream()).map(Line::offerId).toList());
            }
            return created;
        } catch (DataIntegrityViolationException e) {
            release(reserved);
            // Ten sam klucz zapisał właśnie równoległy request: zwracamy jego zamówienia.
            return earlier(buyerId, idempotencyKey).orElseThrow(() -> e);
        } catch (RuntimeException e) {
            release(reserved);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<Order> ofBuyer(UUID buyerId, OrderStatus status, int offset, int limit) {
        return orders.findOfBuyer(buyerId, status == null ? null : status.name(), offset, limit);
    }

    @Transactional(readOnly = true)
    public List<Order> ofSeller(UUID sellerId, OrderStatus status, int offset, int limit) {
        return orders.findOfSeller(sellerId, status == null ? null : status.name(), offset, limit);
    }

    /** Kupujący i sprzedawca widzą zamówienie; dla pozostałych nie istnieje. */
    @Transactional(readOnly = true)
    public Order visible(UUID userId, UUID orderId) {
        return find(orderId, o -> o.getBuyerId().equals(userId) || o.getSellerId().equals(userId));
    }

    public Order cancelByBuyer(UUID buyerId, UUID orderId) {
        Order cancelled = cancel(orderId, o -> o.getBuyerId().equals(buyerId));
        notices.cancelledByBuyer(cancelled);
        return cancelled;
    }

    public Order ship(UUID sellerId, UUID orderId, String trackingNumber) {
        Order shipped = change(orderId, o -> o.getSellerId().equals(sellerId), o -> o.ship(trackingNumber.trim(), clock.instant()));
        notices.shipped(shipped);
        return shipped;
    }

    /** Sprzedawca może oznaczyć zamówienie w drodze jako zrealizowane albo anulować niewysłane. */
    public Order changeBySeller(UUID sellerId, UUID orderId, OrderStatus status) {
        Predicate<Order> own = o -> o.getSellerId().equals(sellerId);
        return switch (status) {
            case COMPLETED -> {
                Order completed = change(orderId, own, o -> o.complete(clock.instant()));
                notices.completed(completed);
                yield completed;
            }
            case CANCELLED -> {
                Order cancelled = cancel(orderId, own);
                notices.cancelledBySeller(cancelled);
                yield cancelled;
            }
            case SHIPPED -> throw unprocessable("Aby wysłać zamówienie, podaj numer przesyłki.");
            default -> throw unprocessable("Nie możesz ręcznie ustawić statusu „" + status.label() + "”.");
        };
    }

    /** Wywołuje payment-service po udanej płatności. Ponowienie dla opłaconego zamówienia niczego nie zmienia. */
    public Order markPaid(UUID orderId, UUID paymentId) {
        Order paid = change(orderId, o -> true, o -> o.markPaid(paymentId, clock.instant()));
        notices.paid(paid);
        return paid;
    }

    /** engagement-service zapisał opinię o zamówieniu. Ponowienie niczego nie zmienia. */
    public Order markReviewed(UUID orderId) {
        return change(orderId, o -> true, Order::markReviewed);
    }

    /** Zgłoszenie zwrotu przez kupującego (odstąpienie od umowy) w ciągu {@code rynek.orders.return-window} od realizacji. */
    public Order requestReturn(UUID buyerId, UUID orderId, String reason) {
        Order requested = change(orderId, o -> o.getBuyerId().equals(buyerId), o -> o.requestReturn(reason.trim(), returnWindow, clock.instant()));
        notices.returnRequested(requested);
        return requested;
    }

    /** Odrzucenie zgłoszonego zwrotu z uzasadnieniem. Przyjęcie to zwrot pieniędzy w payment-service. */
    public Order rejectReturn(UUID sellerId, UUID orderId, String reason) {
        Order rejected = change(orderId, o -> o.getSellerId().equals(sellerId), o -> o.rejectReturn(reason.trim(), clock.instant()));
        notices.returnRejected(rejected);
        return rejected;
    }

    /** payment-service zwrócił pieniądze za zamówienie: zgłoszony zwrot jest przyjęty. Ponowienie niczego nie zmienia. */
    public Order refunded(UUID orderId) {
        Order order = change(orderId, o -> true, o -> o.returnRefunded(clock.instant()));
        notices.refunded(order);
        return order.isReturnStockPending() ? restoreReturnedStock(orderId) : order;
    }

    /** Ponawia przywrócenie zapasu po przyjętych zwrotach, na które catalog jeszcze nie odpowiedział. */
    @Scheduled(fixedDelayString = "${rynek.orders.payment-retry:60s}", initialDelayString = "${rynek.orders.payment-retry:60s}")
    public void restoreReturnedStockPending() {
        orders.findReturnStockPending().forEach(this::restoreReturnedStock);
    }

    /**
     * Oddaje do zapasu sztuki z przyjętego zwrotu. Niedostępny catalog nie blokuje przyjęcia zwrotu: flaga zostaje, a
     * zgłoszenie (idempotentne po id pozycji) pójdzie ponownie. Odrzucenie (4xx) nie jest ponawiane.
     */
    private Order restoreReturnedStock(UUID orderId) {
        Order order = find(orderId, o -> true);
        boolean unavailable = false;
        for (Line line : order.getLines()) {
            CatalogClient.Result result = catalog.restore(line.offerId(), line.id(), line.quantity());
            unavailable |= result == CatalogClient.Result.UNAVAILABLE;
        }
        if (unavailable) {
            log.warn("Zamówienie {}: catalog nie odpowiada, ponowimy przywrócenie zapasu po zwrocie", orderId);
            return order;
        }
        try {
            return change(orderId, o -> true, Order::returnStockRestored);
        } catch (ResponseStatusException e) {
            return find(orderId, o -> true);
        }
    }

    /** Etykieta z shipping-service. Ponowienie z tą samą przesyłką niczego nie psuje. */
    public Order attachShipment(UUID orderId, UUID shipmentId, String carrier, String trackingNumber) {
        return change(orderId, o -> true, o -> o.attachShipment(shipmentId, carrier, trackingNumber, clock.instant()));
    }

    /** Status od przewoźnika (zamiast zdarzenia {@code shipment.status-changed}): odebrana → „W drodze”, doręczona → „Zrealizowane”. */
    public Order carrierStatus(UUID orderId, String status) {
        return switch (status) {
            case "PICKED_UP" -> {
                Order pickedUp = change(orderId, o -> true, o -> o.pickedUp(clock.instant()));
                notices.shipped(pickedUp);
                yield pickedUp;
            }
            case "DELIVERED" -> {
                Order delivered = change(orderId, o -> true, o -> o.delivered(clock.instant()));
                notices.completed(delivered);
                yield delivered;
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieznany status przesyłki.");
        };
    }

    @Transactional(readOnly = true)
    public Order internal(UUID orderId) {
        return find(orderId, o -> true);
    }

    /** Sprzedaż kanału dla analytics-service: zamówienia opłacone w przedziale {@code [from, to)} i nieanulowane. */
    @Transactional(readOnly = true)
    public List<Order> paidSales(UUID channelId, java.time.Instant from, java.time.Instant to) {
        return orders.findPaidOfChannel(channelId, from, to);
    }

    /** Zamówienia do salda sprzedawcy w payment-service; nieistniejące są pomijane. */
    @Transactional(readOnly = true)
    public List<Order> internal(List<UUID> orderIds) {
        return orders.findByIdIn(orderIds);
    }

    /**
     * Anuluje zamówienia nieopłacone dłużej niż {@code rynek.orders.payment-timeout} i zwalnia ich zapas, żeby porzucony
     * zakup nie blokował sztuk. Zamówienie opłacone w międzyczasie zostaje (anulowanie sprawdza status pod blokadą wersji).
     */
    @Scheduled(fixedDelayString = "${rynek.orders.expiry-check:60s}", initialDelayString = "${rynek.orders.expiry-check:60s}")
    public void expireUnpaid() {
        Instant deadline = clock.instant().minus(paymentTimeout);
        for (UUID id : orders.findAwaitingPaymentBefore(deadline)) {
            try {
                notices.expired(cancel(id, o -> o.getStatus() == OrderStatus.AWAITING_PAYMENT));
                log.info("Zamówienie {} wygasło bez płatności", id);
            } catch (ResponseStatusException e) {
                log.debug("Zamówienie {} nie wygasło: {}", id, e.getReason());
            }
        }
    }

    /** Ponawia zgłoszenia anulowania, których payment-service jeszcze nie przyjął. */
    @Scheduled(fixedDelayString = "${rynek.orders.payment-retry:60s}", initialDelayString = "${rynek.orders.payment-retry:60s}")
    public void notifyPaymentPending() {
        orders.findPaymentUnnotified().forEach(this::notifyPayment);
    }

    /** Ponawia zgłoszenia anulowania, których shipping-service jeszcze nie przyjął. */
    @Scheduled(fixedDelayString = "${rynek.orders.shipping-retry:60s}", initialDelayString = "${rynek.orders.shipping-retry:60s}")
    public void notifyShippingPending() {
        orders.findShippingUnnotified().forEach(this::notifyShipping);
    }

    private Order cancel(UUID orderId, Predicate<Order> allowed) {
        Order cancelled = change(orderId, allowed, o -> o.cancel(clock.instant()));
        release(cancelled.getLines());
        notifyPayment(cancelled.getId());
        return notifyShipping(cancelled.getId());
    }

    /**
     * Anulowanie przesyłki z etykietą (jeśli jest). Odrzucenie (4xx) nie jest ponawiane i trafia do logu; nieudane
     * zgłoszenie nie blokuje anulowania zamówienia ani zwrotu pieniędzy.
     */
    private Order notifyShipping(UUID orderId) {
        ShippingClient.Result result = shipping.orderCancelled(orderId);
        if (result == ShippingClient.Result.UNAVAILABLE) {
            log.warn("Zamówienie {}: shipping-service nie odpowiada, ponowimy zgłoszenie anulowania", orderId);
            return find(orderId, o -> true);
        }
        if (result == ShippingClient.Result.REJECTED) {
            log.error("Zamówienie {}: shipping-service odrzucił zgłoszenie anulowania, sprawdź przesyłkę ręcznie", orderId);
        }
        try {
            return change(orderId, o -> true, Order::shippingNotified);
        } catch (ResponseStatusException e) {
            return find(orderId, o -> true);
        }
    }

    /**
     * Zwrot pieniędzy za opłacone zamówienie i zamknięcie płatności w toku. Odrzucenie (4xx) nie jest ponawiane: oznacza
     * błąd danych, który wymaga ręcznego sprawdzenia, a nie chwilową awarię.
     */
    private Order notifyPayment(UUID orderId) {
        PaymentClient.Result result = payment.orderCancelled(orderId);
        if (result == PaymentClient.Result.UNAVAILABLE) {
            log.warn("Zamówienie {}: payment-service nie odpowiada, ponowimy zgłoszenie anulowania", orderId);
            return find(orderId, o -> true);
        }
        if (result == PaymentClient.Result.REJECTED) {
            log.error("Zamówienie {}: payment-service odrzucił zgłoszenie anulowania, sprawdź zwrot ręcznie", orderId);
        }
        try {
            return change(orderId, o -> true, Order::paymentNotified);
        } catch (ResponseStatusException e) {
            // Równoległa zmiana: flaga zostaje, zgłoszenie (idempotentne w payment-service) pójdzie jeszcze raz.
            return find(orderId, o -> true);
        }
    }

    private Order change(UUID orderId, Predicate<Order> allowed, java.util.function.Consumer<Order> action) {
        try {
            return transaction.execute(status -> {
                Order order = find(orderId, allowed);
                action.accept(order);
                return orders.saveAndFlush(order);
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Zamówienie właśnie się zmieniło. Odśwież i spróbuj ponownie.");
        }
    }

    private Order find(UUID orderId, Predicate<Order> allowed) {
        return orders.findById(orderId).filter(allowed)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleźliśmy tego zamówienia."));
    }

    private Optional<List<Order>> earlier(UUID buyerId, String idempotencyKey) {
        return transaction.execute(status -> requests.findById(new OrderRequest.Key(buyerId, idempotencyKey)).map(r -> {
            Map<UUID, Order> found = orders.findByIdIn(r.orderIds()).stream().collect(Collectors.toMap(Order::getId, o -> o));
            return r.orderIds().stream().map(found::get).toList();
        }));
    }

    private List<Order> save(UUID buyerId, String idempotencyKey, List<Package> packages, Order.Address address) {
        return transaction.execute(status -> {
            Instant now = clock.instant();
            List<Order> created = packages.stream()
                    .map(p -> new Order(orders.nextNumber(), buyerId, p.sellerId(), p.channelId(), p.lines(), p.shipping(), address, now))
                    .toList();
            orders.saveAll(created);
            requests.saveAndFlush(new OrderRequest(buyerId, idempotencyKey, created.stream().map(Order::getId).toList(), now));
            return created;
        });
    }

    private List<Package> buyNow(UUID buyerId, CreateOrderRequest request) {
        if (request.offerId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Podaj ofertę.");
        }
        int quantity = request.quantity() == null ? 1 : request.quantity();
        OfferInfo offer = catalog.offers(List.of(request.offerId())).get(request.offerId());
        if (offer == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleźliśmy tej oferty.");
        }
        requirePurchasable(buyerId, offer, quantity);
        String methodId = request.shippingMethodId() == null ? ShippingClient.DEFAULT_METHOD : request.shippingMethodId();
        return List.of(new Package(offer.ownerId(), offer.channelId(), List.of(line(offer, quantity)),
                shippingFor(methodId, request.pickupPointId())));
    }

    /** Z koszyka zamawiane są pozycje dostępne; niedostępne zostają w koszyku (tak samo liczy podsumowanie koszyka). */
    private List<Package> fromCart(UUID buyerId) {
        CartSnapshot snapshot = cart.snapshot(buyerId);
        if (snapshot.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Koszyk jest pusty.");
        }
        Map<UUID, OfferInfo> offers = catalog.offers(snapshot.items().stream().map(CartSnapshot.Item::offerId).toList());
        Map<UUID, List<Line>> lines = new LinkedHashMap<>();
        Map<UUID, OfferInfo> sellerOf = new LinkedHashMap<>();
        for (CartSnapshot.Item item : snapshot.items()) {
            OfferInfo offer = offers.get(item.offerId());
            if (offer == null || !purchasable(buyerId, offer, item.quantity())) {
                continue;
            }
            lines.computeIfAbsent(offer.channelId(), id -> new ArrayList<>()).add(line(offer, item.quantity()));
            sellerOf.putIfAbsent(offer.channelId(), offer);
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Żadna pozycja w koszyku nie jest teraz dostępna.");
        }
        if (lines.values().stream().mapToInt(List::size).sum() > MAX_LINES) {
            throw unprocessable("Jedno zamówienie może mieć najwyżej " + MAX_LINES + " pozycji.");
        }
        Map<UUID, CartSnapshot.Shipping> choices = snapshot.shipping().stream()
                .collect(Collectors.toMap(CartSnapshot.Shipping::channelId, s -> s, (a, b) -> a));
        return lines.entrySet().stream().map(e -> {
            CartSnapshot.Shipping choice = choices.get(e.getKey());
            Order.Shipping chosen = choice == null ? shippingFor(ShippingClient.DEFAULT_METHOD, null) : shippingFor(choice.methodId(), choice.pickupPointId());
            return new Package(sellerOf.get(e.getKey()).ownerId(), e.getKey(), e.getValue(), chosen);
        }).toList();
    }

    private Order.Shipping shippingFor(String methodId, String pickupPointId) {
        Method method = shipping.method(methodId).orElseThrow(() -> unprocessable("Nie ma takiego sposobu dostawy."));
        if (!method.needsPickupPoint()) {
            return new Order.Shipping(method.id(), method.name(), method.price().amount(), null, null);
        }
        if (pickupPointId == null || pickupPointId.isBlank()) {
            throw unprocessable("Wybierz paczkomat dla każdej paczki wysyłanej do paczkomatu.");
        }
        ShippingClient.PickupPoint point = shipping.pickupPoint(pickupPointId.trim())
                .orElseThrow(() -> unprocessable("Wybrany paczkomat nie istnieje. Wybierz inny."));
        return new Order.Shipping(method.id(), method.name(), method.price().amount(), point.id(), point.label());
    }

    private static void requirePurchasable(UUID buyerId, OfferInfo offer, int quantity) {
        if (!offer.isProduct()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Usługę rezerwujesz w kalendarzu na karcie oferty.");
        }
        if (buyerId.equals(offer.ownerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nie możesz kupić własnej oferty.");
        }
        if (!offer.available() || (offer.stock() != null && quantity > offer.stock())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Brakuje sztuk albo oferta nie jest już dostępna: " + offer.title() + ".");
        }
    }

    private static boolean purchasable(UUID buyerId, OfferInfo offer, int quantity) {
        return offer.isProduct() && !buyerId.equals(offer.ownerId()) && offer.available()
                && (offer.stock() == null || quantity <= offer.stock());
    }

    private static Line line(OfferInfo offer, int quantity) {
        return new Line(UUID.randomUUID(), offer.offerId(), offer.title(), quantity, offer.price().amount());
    }

    private void release(List<Line> lines) {
        lines.forEach(l -> catalog.release(l.offerId(), l.id()));
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }

    private record Package(UUID sellerId, UUID channelId, List<Line> lines, Order.Shipping shipping) {
    }
}

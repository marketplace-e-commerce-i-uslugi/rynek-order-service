package pl.rynek.order.orders;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
import pl.rynek.order.clients.ShippingClient;
import pl.rynek.order.clients.ShippingClient.Method;
import pl.rynek.order.orders.Order.Line;

/**
 * Składanie zamówień i zmiany statusu. Złożenie rezerwuje zapas w catalog przed zapisem; jeśli cokolwiek się nie uda
 * (brak sztuk, błąd zapisu, ten sam Idempotency-Key równolegle), zarezerwowane sztuki wracają do zapasu.
 * Rezerwacja trwa do anulowania zamówienia; wygasania nieopłaconych zamówień jeszcze nie ma (czeka na payment-service).
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
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OrderService(OrderRepository orders, OrderRequestRepository requests, CatalogClient catalog, CartClient cart,
                        IdentityClient identity, ShippingClient shipping, PlatformTransactionManager transactions, Clock clock) {
        this.orders = orders;
        this.requests = requests;
        this.catalog = catalog;
        this.cart = cart;
        this.identity = identity;
        this.shipping = shipping;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
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
        return cancel(orderId, o -> o.getBuyerId().equals(buyerId));
    }

    public Order ship(UUID sellerId, UUID orderId, String trackingNumber) {
        return change(orderId, o -> o.getSellerId().equals(sellerId), o -> o.ship(trackingNumber.trim(), clock.instant()));
    }

    /** Sprzedawca może oznaczyć zamówienie w drodze jako zrealizowane albo anulować niewysłane. */
    public Order changeBySeller(UUID sellerId, UUID orderId, OrderStatus status) {
        Predicate<Order> own = o -> o.getSellerId().equals(sellerId);
        return switch (status) {
            case COMPLETED -> change(orderId, own, o -> o.complete(clock.instant()));
            case CANCELLED -> cancel(orderId, own);
            case SHIPPED -> throw unprocessable("Aby wysłać zamówienie, podaj numer przesyłki.");
            default -> throw unprocessable("Nie możesz ręcznie ustawić statusu „" + status.label() + "”.");
        };
    }

    /** Wywołuje payment-service po udanej płatności. Ponowienie dla opłaconego zamówienia niczego nie zmienia. */
    public Order markPaid(UUID orderId) {
        return change(orderId, o -> true, o -> o.markPaid(clock.instant()));
    }

    private Order cancel(UUID orderId, Predicate<Order> allowed) {
        Order cancelled = change(orderId, allowed, o -> o.cancel(clock.instant()));
        release(cancelled.getLines());
        return cancelled;
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

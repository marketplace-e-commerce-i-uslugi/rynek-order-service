package pl.rynek.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.order.TestcontainersConfig;
import pl.rynek.order.clients.CartClient;
import pl.rynek.order.clients.CartClient.CartSnapshot;
import pl.rynek.order.clients.CatalogClient;
import pl.rynek.order.clients.CatalogClient.Money;
import pl.rynek.order.clients.CatalogClient.OfferInfo;
import pl.rynek.order.clients.ChannelClient;
import pl.rynek.order.clients.ChannelClient.ChannelRef;
import pl.rynek.order.clients.IdentityClient;
import pl.rynek.order.clients.IdentityClient.Address;
import pl.rynek.order.clients.ShippingClient;
import pl.rynek.order.clients.ShippingClient.Method;
import pl.rynek.order.clients.ShippingClient.PickupPoint;

/**
 * Zamówienia na prawdziwym PostgreSQL. Atrapa catalog trzyma zapas w mapie i rezerwuje go jak catalog-service
 * (idempotentnie po id rezerwacji), więc test widzi, ile sztuk zostało po złożeniu i anulowaniu zamówienia.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class OrderFlowTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    CatalogClient catalog;

    @MockitoBean
    CartClient cart;

    @MockitoBean
    IdentityClient identity;

    @MockitoBean
    ShippingClient shipping;

    @MockitoBean
    ChannelClient channels;

    final Map<UUID, OfferInfo> offers = new ConcurrentHashMap<>();
    final Map<UUID, Integer> stock = new ConcurrentHashMap<>();
    final Map<UUID, Integer> reservations = new ConcurrentHashMap<>();
    final Map<UUID, CartSnapshot> carts = new ConcurrentHashMap<>();

    @BeforeEach
    void stubs() {
        given(jwtDecoder.decode(anyString())).willAnswer(call -> {
            String token = call.getArgument(0);
            if (!token.startsWith("user-")) {
                throw new BadJwtException("Nieważny token");
            }
            return Jwt.withTokenValue(token).header("alg", "RS256").subject(token.substring(5))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build();
        });
        given(catalog.offers(any())).willAnswer(call -> call.<Collection<UUID>>getArgument(0).stream().distinct()
                .filter(offers::containsKey).collect(Collectors.toMap(Function.identity(), this::current)));
        willAnswer(call -> {
            UUID offerId = call.getArgument(0);
            UUID reservationId = call.getArgument(1);
            int quantity = call.getArgument(2);
            synchronized (stock) {
                if (reservations.containsKey(reservationId)) {
                    return null;
                }
                if (stock.getOrDefault(offerId, 0) < quantity) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Brakuje sztuk albo oferta nie jest już dostępna: " + call.getArgument(3) + ".");
                }
                stock.merge(offerId, -quantity, Integer::sum);
                reservations.put(reservationId, quantity);
            }
            return null;
        }).given(catalog).reserve(any(), any(), anyInt(), anyString());
        given(catalog.release(any(), any())).willAnswer(call -> {
            synchronized (stock) {
                Integer quantity = reservations.remove(call.<UUID>getArgument(1));
                if (quantity != null) {
                    stock.merge(call.getArgument(0), quantity, Integer::sum);
                }
            }
            return true;
        });
        given(cart.snapshot(any())).willAnswer(call -> carts.getOrDefault(call.<UUID>getArgument(0), new CartSnapshot(List.of(), List.of())));
        given(identity.address(any(), any())).willAnswer(call -> call.<UUID>getArgument(1).equals(addressOf(call.getArgument(0)))
                ? Optional.of(new Address(call.getArgument(1), null, "Anna Nowak", "+48600100200", "ul. Długa 5", "00-950", "Warszawa"))
                : Optional.empty());
        given(shipping.method(anyString())).willAnswer(call -> Map.of(
                "inpost", new Method("inpost", "Paczkomat InPost", new Money(1299, "PLN"), "1–2 dni", true),
                "courier", new Method("courier", "Kurier", new Money(1599, "PLN"), "1–2 dni", false)).entrySet().stream()
                .filter(e -> e.getKey().equals(call.getArgument(0))).map(Map.Entry::getValue).findFirst());
        given(shipping.pickupPoint(anyString())).willAnswer(call -> call.<String>getArgument(0).startsWith("WAW")
                ? Optional.of(new PickupPoint(call.getArgument(0), "Paczkomat " + call.getArgument(0), "ul. Marszałkowska 104, Warszawa"))
                : Optional.empty());
        given(channels.batch(any())).willAnswer(call -> call.<Collection<UUID>>getArgument(0).stream().distinct()
                .collect(Collectors.toMap(Function.identity(), id -> new ChannelRef(id, "kanal", "Sklep " + id.toString().substring(0, 4), null, true))));
    }

    @Test
    void cartOrderIsSplitPerSellerReservesStockAndEmptiesCart() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID sellerA = UUID.randomUUID();
        UUID sellerB = UUID.randomUUID();
        UUID drill = product(sellerA, 38900, 5);
        UUID bits = product(sellerA, 4900, 10);
        UUID mower = product(sellerB, 169900, 1);
        UUID paused = product(sellerB, 1000, 0);
        carts.put(buyer, new CartSnapshot(
                List.of(item(drill, 1), item(bits, 3), item(mower, 1), item(paused, 1)),
                List.of(new CartSnapshot.Shipping(channelOf(sellerA), "inpost", "WAW01M"),
                        new CartSnapshot.Shipping(channelOf(sellerB), "courier", null))));

        String body = order(buyer, "k1", fromCart(buyer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("Oczekuje na płatność"))
                .andExpect(jsonPath("$[0].number").value(org.hamcrest.Matchers.startsWith("#")))
                .andExpect(jsonPath("$[0].channel.id").value(channelOf(sellerA).toString()))
                .andExpect(jsonPath("$[0].lines", hasSize(2)))
                .andExpect(jsonPath("$[0].itemsTotal.amount").value(38900 + 3 * 4900))
                .andExpect(jsonPath("$[0].shippingTotal.amount").value(1299))
                .andExpect(jsonPath("$[0].total.amount").value(53600 + 1299))
                .andExpect(jsonPath("$[0].shipping.pickupPoint").value("Paczkomat WAW01M, ul. Marszałkowska 104, Warszawa"))
                .andExpect(jsonPath("$[0].buyerName").value("Anna Nowak"))
                .andExpect(jsonPath("$[0].shippingAddress.postalCode").value("00-950"))
                // Wstrzymana oferta zostaje w koszyku i nie trafia do zamówienia.
                .andExpect(jsonPath("$[1].lines[*].offerId", contains(mower.toString())))
                .andExpect(jsonPath("$[1].total.amount").value(169900 + 1599))
                .andReturn().getResponse().getContentAsString();
        assertThat(stock).containsEntry(drill, 4).containsEntry(bits, 7).containsEntry(mower, 0);
        verify(cart).removeOffers(buyer, List.of(drill, bits, mower));

        // Ponowienie z tym samym kluczem zwraca te same zamówienia i nie rezerwuje drugi raz.
        order(buyer, "k1", fromCart(buyer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[*].id", contains(JsonPath.<String>read(body, "$[0].id"), JsonPath.<String>read(body, "$[1].id"))));
        assertThat(stock).containsEntry(drill, 4);

        mvc.perform(get("/api/v1/orders/mine").header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        mvc.perform(get("/api/v1/seller/orders").header(HttpHeaders.AUTHORIZATION, bearer(sellerB)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].lines[0].title").value(offers.get(mower).title()));
    }

    @Test
    void failedReservationReleasesWhatWasReserved() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID drill = product(seller, 38900, 5);
        UUID last = product(seller, 1000, 1);
        carts.put(buyer, new CartSnapshot(List.of(item(drill, 2), item(last, 1)), List.of(new CartSnapshot.Shipping(channelOf(seller), "courier", null))));
        // Ktoś inny kupił ostatnią sztukę między odczytem oferty a rezerwacją.
        willAnswer(call -> Map.of(drill, current(drill), last, offers.get(last))).given(catalog).offers(any());
        stock.put(last, 0);

        order(buyer, "k1", fromCart(buyer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Brakuje sztuk albo oferta nie jest już dostępna: " + offers.get(last).title() + "."));
        assertThat(stock).containsEntry(drill, 5);
        assertThat(reservations).isEmpty();
        verify(cart, never()).removeOffers(any(), any());
    }

    @Test
    void validation() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID drill = product(seller, 38900, 5);
        order(buyer, "k1", fromCart(buyer)).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Koszyk jest pusty."));
        mvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(buyer)).contentType(MediaType.APPLICATION_JSON)
                        .content(fromCart(buyer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Brak nagłówka Idempotency-Key (do 100 znaków)."));
        order(buyer, "k2", "{\"source\":\"CART\",\"addressId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Wybierz adres dostawy."));
        order(buyer, "k3", buyNow(buyer, drill, 1, "inpost", null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Wybierz paczkomat dla każdej paczki wysyłanej do paczkomatu."));
        order(buyer, "k4", buyNow(buyer, drill, 6, "courier", null)).andExpect(status().isConflict());
        order(seller, "k5", buyNow(seller, drill, 1, "courier", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Nie możesz kupić własnej oferty."));
        assertThat(stock).containsEntry(drill, 5);
        mvc.perform(get("/api/v1/orders/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void buyNowLifecycleFromPaymentToCompletion() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID drill = product(seller, 38900, 2);
        String id = JsonPath.read(order(buyer, "k1", buyNow(buyer, drill, 2, "inpost", "WAW02M"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].total.amount").value(2 * 38900 + 1299))
                .andReturn().getResponse().getContentAsString(), "$[0].id");
        assertThat(stock).containsEntry(drill, 0);
        verify(cart, never()).removeOffers(any(), any());

        // Nieopłaconego nie da się wysłać; obcy nie widzi zamówienia.
        ship(seller, id, "{\"trackingNumber\":\"6200000000001\"}").andExpect(status().isConflict());
        mvc.perform(get("/api/v1/orders/" + id).header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID()))).andExpect(status().isNotFound());
        ship(buyer, id, "{\"trackingNumber\":\"6200000000001\"}").andExpect(status().isNotFound());

        mvc.perform(post("/internal/orders/" + id + "/mark-paid")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Do wysłania"));
        mvc.perform(post("/internal/orders/" + id + "/mark-paid")).andExpect(jsonPath("$.status").value("Do wysłania"));
        ship(seller, id, "{\"trackingNumber\":\" 6200000000001 \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("W drodze"))
                .andExpect(jsonPath("$.trackingNumber").value("6200000000001"));
        // Wysłanego nie można już anulować.
        cancel(buyer, id).andExpect(status().isConflict());
        setStatus(seller, id, "Zrealizowane").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Zrealizowane"));
        setStatus(seller, id, "Do wysłania").andExpect(status().isUnprocessableContent());

        mvc.perform(get("/api/v1/orders/" + id + "/timeline").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(jsonPath("$[*].status", contains("Oczekuje na płatność", "Do wysłania", "W drodze", "Zrealizowane")));
        mvc.perform(get("/api/v1/seller/orders").param("status", "Zrealizowane").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(jsonPath("$.items", hasSize(1)));
        mvc.perform(get("/api/v1/seller/orders").param("status", "W drodze").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(jsonPath("$.items", hasSize(0)));
        assertThat(stock).containsEntry(drill, 0);
    }

    @Test
    void cancellationReturnsStockOnce() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID drill = product(seller, 38900, 3);
        String byBuyer = JsonPath.read(order(buyer, "k1", buyNow(buyer, drill, 2, "courier", null)).andReturn().getResponse().getContentAsString(), "$[0].id");
        String bySeller = JsonPath.read(order(buyer, "k2", buyNow(buyer, drill, 1, "courier", null)).andReturn().getResponse().getContentAsString(), "$[0].id");
        assertThat(stock).containsEntry(drill, 0);

        cancel(buyer, byBuyer).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Anulowane"));
        cancel(buyer, byBuyer).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Zamówienie jest już anulowane."));
        cancel(seller, bySeller).andExpect(status().isNotFound());
        mvc.perform(post("/internal/orders/" + byBuyer + "/mark-paid")).andExpect(status().isConflict());

        mvc.perform(post("/internal/orders/" + bySeller + "/mark-paid")).andExpect(status().isOk());
        setStatus(seller, bySeller, "Anulowane").andExpect(status().isOk());
        assertThat(stock).containsEntry(drill, 3);
        assertThat(reservations).isEmpty();
    }

    @Test
    void pagination() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID drill = product(UUID.randomUUID(), 1000, 10);
        for (int i = 0; i < 3; i++) {
            order(buyer, "p" + i, buyNow(buyer, drill, 1, "courier", null)).andExpect(status().isCreated());
        }
        String first = mvc.perform(get("/api/v1/orders/mine").param("limit", "2").header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(first, "$.nextCursor");
        mvc.perform(get("/api/v1/orders/mine").param("limit", "2").param("cursor", cursor).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        Set<String> ids = Set.copyOf(JsonPath.read(first, "$.items[*].id"));
        assertThat(ids).hasSize(2);
        mvc.perform(get("/api/v1/orders/mine").param("cursor", "zły").header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isBadRequest());
    }

    private UUID product(UUID seller, long price, int available) {
        UUID id = UUID.randomUUID();
        offers.put(id, new OfferInfo(id, "PRODUCT", new Money(price, "PLN"), available, available > 0, "Produkt " + id.toString().substring(0, 6),
                channelOf(seller), seller, null, 1));
        stock.put(id, available);
        return id;
    }

    /** Oferta z aktualnym zapasem, tak jak zwraca ją catalog po rezerwacjach. */
    private OfferInfo current(UUID id) {
        OfferInfo o = offers.get(id);
        int left = stock.getOrDefault(id, 0);
        return new OfferInfo(id, o.type(), o.price(), left, left > 0, o.title(), o.channelId(), o.ownerId(), null, 1);
    }

    private CartSnapshot.Item item(UUID offerId, int quantity) {
        return new CartSnapshot.Item(offerId, offers.get(offerId).channelId(), quantity);
    }

    private ResultActions order(UUID buyer, String key, String json) throws Exception {
        return mvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(buyer)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions ship(UUID user, String orderId, String json) throws Exception {
        return mvc.perform(post("/api/v1/seller/orders/" + orderId + "/ship").header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions setStatus(UUID user, String orderId, String status) throws Exception {
        return mvc.perform(post("/api/v1/seller/orders/" + orderId + "/status").header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"));
    }

    private ResultActions cancel(UUID user, String orderId) throws Exception {
        return mvc.perform(post("/api/v1/orders/" + orderId + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Rozmyśliłem się\"}"));
    }

    private static String fromCart(UUID buyer) {
        return "{\"source\":\"CART\",\"addressId\":\"" + addressOf(buyer) + "\"}";
    }

    private static String buyNow(UUID buyer, UUID offerId, int quantity, String method, String point) {
        return "{\"source\":\"BUY_NOW\",\"offerId\":\"" + offerId + "\",\"quantity\":" + quantity + ",\"addressId\":\"" + addressOf(buyer)
                + "\",\"shippingMethodId\":\"" + method + "\"" + (point == null ? "" : ",\"pickupPointId\":\"" + point + "\"") + "}";
    }

    private static UUID addressOf(UUID user) {
        return UUID.nameUUIDFromBytes(("adres-" + user).getBytes());
    }

    private static UUID channelOf(UUID owner) {
        return UUID.nameUUIDFromBytes(("kanal-" + owner).getBytes());
    }

    private static String bearer(UUID userId) {
        return "Bearer user-" + userId;
    }
}

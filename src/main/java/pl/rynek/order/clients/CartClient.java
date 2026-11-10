package pl.rynek.order.clients;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Zawartość koszyka przy składaniu zamówienia z koszyka (cart-service). */
@Component
public class CartClient {

    private static final Logger log = LoggerFactory.getLogger(CartClient.class);

    private final RestClient http;

    public CartClient(@Value("${rynek.cart.url}") String cartUrl) {
        this.http = Http.client(cartUrl);
    }

    public CartSnapshot snapshot(UUID userId) {
        try {
            CartSnapshot cart = http.get().uri("/internal/carts/{userId}", userId).retrieve().body(CartSnapshot.class);
            return cart == null ? new CartSnapshot(List.of(), List.of()) : cart;
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw Http.unavailable("Serwis koszyka");
        }
    }

    /** Zamówienia są już zapisane, więc błąd tylko logujemy: najwyżej pozycje zostaną w koszyku. */
    public void removeOffers(UUID userId, Collection<UUID> offerIds) {
        try {
            http.delete().uri(uri -> uri.path("/internal/carts/{userId}/items")
                            .queryParam("offerIds", offerIds.stream().map(UUID::toString).collect(Collectors.joining(","))).build(userId))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException | ResourceAccessException e) {
            log.warn("Nie usunięto zamówionych ofert z koszyka {}: {}", userId, e.getMessage());
        }
    }

    public record CartSnapshot(List<Item> items, List<Shipping> shipping) {

        public record Item(UUID offerId, UUID channelId, int quantity) {
        }

        public record Shipping(UUID channelId, String methodId, String pickupPointId) {
        }
    }
}

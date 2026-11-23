package pl.rynek.order.clients;

import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** Cena uzgodniona w rozmowie („Zaproponuj cenę”): chat-service zużywa zaakceptowaną propozycję przy zamówieniu. */
@Component
public class ChatClient {

    private final RestClient http;

    public ChatClient(@Value("${rynek.chat.url}") String chatUrl) {
        this.http = Http.client(chatUrl);
    }

    /**
     * Zużywa zaakceptowaną propozycję kupującego dla oferty i zwraca uzgodnioną kwotę w groszach. Odmowa chatu (404, 409)
     * przechodzi do klienta z jego komunikatem; gdy chat nie odpowiada: 503.
     */
    public long redeem(UUID priceOfferId, UUID buyerId, UUID offerId) {
        try {
            Money price = http.post().uri("/internal/price-offers/{id}/redeem", priceOfferId)
                    .body(Map.of("buyerId", buyerId, "offerId", offerId)).retrieve().body(Money.class);
            if (price == null) {
                throw Http.unavailable("Rozmowy");
            }
            return price.amount();
        } catch (HttpClientErrorException e) {
            Map<?, ?> problem = e.getResponseBodyAs(Map.class);
            Object detail = problem == null ? null : problem.get("detail");
            throw new ResponseStatusException(HttpStatus.valueOf(e.getStatusCode().value()),
                    detail instanceof String text ? text : "Nie można użyć uzgodnionej ceny.");
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw Http.unavailable("Rozmowy");
        }
    }

    /** Oddaje cenę do użycia, gdy zamówienie nie powstało. Błąd nie jest groźny: cena po prostu pozostanie zużyta. */
    public void release(UUID priceOfferId) {
        try {
            http.post().uri("/internal/price-offers/{id}/release", priceOfferId).retrieve().toBodilessEntity();
        } catch (RestClientResponseException | ResourceAccessException ignored) {
            // Nic więcej nie zrobimy; sprzedawca może przyjąć nową propozycję.
        }
    }

    private record Money(long amount, String currency) {
    }
}

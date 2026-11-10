package pl.rynek.order.clients;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** Ceny i rezerwacja zapasu w catalog-service. */
@Component
public class CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

    private final RestClient http;

    public CatalogClient(@Value("${rynek.catalog.url}") String catalogUrl) {
        this.http = Http.client(catalogUrl);
    }

    /** Oferty po id; usunięte nie występują w mapie. */
    public Map<UUID, OfferInfo> offers(Collection<UUID> offerIds) {
        String ids = offerIds.stream().distinct().map(UUID::toString).collect(Collectors.joining(","));
        try {
            List<OfferInfo> found = http.get().uri(uri -> uri.path("/internal/offers/price-and-stock").queryParam("ids", ids).build())
                    .retrieve().body(new ParameterizedTypeReference<>() {
                    });
            return found == null ? Map.of() : found.stream().collect(Collectors.toMap(OfferInfo::offerId, Function.identity()));
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw Http.unavailable("Serwis ofert");
        }
    }

    /** 409 z catalog (brak sztuk) zamienia na 409 z tytułem oferty, żeby kupujący wiedział, której pozycji brakuje. */
    public void reserve(UUID offerId, UUID reservationId, int quantity, String title) {
        try {
            http.post().uri("/internal/offers/{id}/stock/reserve", offerId)
                    .body(Map.of("reservationId", reservationId, "quantity", quantity)).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.Conflict | HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Brakuje sztuk albo oferta nie jest już dostępna: " + title + ".");
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw Http.unavailable("Serwis ofert");
        }
    }

    /**
     * Zwolnienie jest idempotentne po stronie catalog. Błąd tylko logujemy: zamówienie i tak jest anulowane, a
     * rezerwację można zwolnić ponownie (to samo id).
     */
    public boolean release(UUID offerId, UUID reservationId) {
        try {
            http.post().uri("/internal/offers/{id}/stock/release", offerId)
                    .body(Map.of("reservationId", reservationId)).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException | ResourceAccessException e) {
            log.error("Nie zwolniono rezerwacji {} oferty {}: {}", reservationId, offerId, e.getMessage());
            return false;
        }
    }

    public record OfferInfo(UUID offerId, String type, Money price, Integer stock, boolean available, String title,
                            UUID channelId, UUID ownerId, String thumbnailUrl, int tone) {

        public boolean isProduct() {
            return "PRODUCT".equals(type);
        }
    }

    public record Money(long amount, String currency) {
    }
}

package pl.rynek.order.clients;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Cena dostawy i dane punktu odbioru do zapisania w zamówieniu (shipping-service). */
@Component
public class ShippingClient {

    public static final String DEFAULT_METHOD = "inpost";

    private final RestClient http;

    public ShippingClient(@Value("${rynek.shipping.url}") String shippingUrl) {
        this.http = Http.client(shippingUrl);
    }

    public Optional<Method> method(String methodId) {
        return get("/internal/shipping/methods/{id}", methodId, Method.class);
    }

    public Optional<PickupPoint> pickupPoint(String pointId) {
        return get("/internal/shipping/pickup-points/{id}", pointId, PickupPoint.class);
    }

    private <T> Optional<T> get(String path, String id, Class<T> type) {
        try {
            return Optional.ofNullable(http.get().uri(path, id).retrieve().body(type));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw Http.unavailable("Serwis dostaw");
        }
    }

    public record Method(String id, String name, CatalogClient.Money price, String eta, boolean needsPickupPoint) {
    }

    public record PickupPoint(String id, String name, String address) {

        public String label() {
            return name + ", " + address;
        }
    }
}

package pl.rynek.order.clients;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Adres dostawy kupującego z identity-service. */
@Component
public class IdentityClient {

    private final RestClient http;

    public IdentityClient(@Value("${rynek.identity.url}") String identityUrl) {
        this.http = Http.client(identityUrl);
    }

    /** Adres albo pusty, gdy nie istnieje lub należy do innego konta. */
    public Optional<Address> address(UUID userId, UUID addressId) {
        try {
            return Optional.ofNullable(http.get().uri("/internal/users/{userId}/addresses/{addressId}", userId, addressId)
                    .retrieve().body(Address.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw Http.unavailable("Serwis kont");
        }
    }

    public record Address(UUID id, String label, String fullName, String phone, String street, String postalCode, String city) {
    }
}

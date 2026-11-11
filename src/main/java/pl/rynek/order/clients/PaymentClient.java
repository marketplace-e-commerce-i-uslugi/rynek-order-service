package pl.rynek.order.clients;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Zgłoszenie anulowania zamówienia do payment-service (zamiast zdarzenia {@code order.cancelled}): payment-service
 * zwraca pieniądze za opłacone zamówienie i oznacza płatności w toku jako nieudane.
 */
@Component
public class PaymentClient {

    private final RestClient http;

    public PaymentClient(@Value("${rynek.payment.url}") String paymentUrl) {
        this.http = Http.client(paymentUrl);
    }

    /** {@code DONE}: przyjęte; {@code REJECTED}: błąd 4xx, ponawianie nic nie da; {@code UNAVAILABLE}: spróbować później. */
    public enum Result { DONE, REJECTED, UNAVAILABLE }

    public Result orderCancelled(UUID orderId) {
        try {
            http.post().uri("/internal/orders/{id}/cancelled", orderId).retrieve().toBodilessEntity();
            return Result.DONE;
        } catch (HttpClientErrorException e) {
            return Result.REJECTED;
        } catch (RestClientResponseException | ResourceAccessException e) {
            return Result.UNAVAILABLE;
        }
    }
}

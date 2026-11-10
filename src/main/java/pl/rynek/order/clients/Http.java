package pl.rynek.order.clients;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Wspólne ustawienia klientów {@code /internal} innych serwisów. */
final class Http {

    private Http() {
    }

    static RestClient client(String baseUrl) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory();
        requests.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build();
    }

    static ResponseStatusException unavailable(String what) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, what + " jest niedostępny. Spróbuj ponownie za chwilę.");
    }
}

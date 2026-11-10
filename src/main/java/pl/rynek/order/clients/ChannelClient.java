package pl.rynek.order.clients;

import java.time.Duration;
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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Sprzedawca zamówienia z channel-service ({@code /internal/channels?ids=}). */
@Component
public class ChannelClient {

    private static final Logger log = LoggerFactory.getLogger(ChannelClient.class);

    private final RestClient http;

    public ChannelClient(@Value("${rynek.channel.url}") String channelUrl) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory();
        requests.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().baseUrl(channelUrl).requestFactory(requests).build();
    }

    /**
     * Kanały po id. Gdy channel-service nie odpowiada, zamówienia i tak się pokazują (z zastępczą nazwą sprzedawcy),
     * bo pozycje i status są ważniejsze niż nagłówek.
     */
    public Map<UUID, ChannelRef> batch(Collection<UUID> channelIds) {
        if (channelIds.isEmpty()) {
            return Map.of();
        }
        String ids = channelIds.stream().distinct().map(UUID::toString).collect(Collectors.joining(","));
        try {
            List<ChannelRef> found = http.get().uri(uri -> uri.path("/internal/channels").queryParam("ids", ids).build())
                    .retrieve().body(new ParameterizedTypeReference<>() {
                    });
            return found == null ? Map.of() : found.stream().collect(Collectors.toMap(ChannelRef::id, Function.identity()));
        } catch (RestClientResponseException | ResourceAccessException e) {
            log.warn("Brak danych kanałów {}: {}", ids, e.getMessage());
            return Map.of();
        }
    }

    /** Kształt {@code ChannelRef} z rynek-frontend (common.ts). */
    public record ChannelRef(UUID id, String handle, String name, String avatarUrl, boolean verified) {

        public static ChannelRef missing(UUID id) {
            return new ChannelRef(id, "", "Sprzedawca", null, false);
        }
    }
}

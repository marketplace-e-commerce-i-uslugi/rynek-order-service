package pl.rynek.order.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.order.api.dto.CursorPage;
import pl.rynek.order.api.dto.OrderResponse;
import pl.rynek.order.clients.ChannelClient;
import pl.rynek.order.clients.ChannelClient.ChannelRef;
import pl.rynek.order.orders.Order;
import pl.rynek.order.orders.OrderStatus;

/** Zamówienia z danymi kanałów (channel-service) i paginacja kursorowa. Kursor to zakodowane przesunięcie. */
@Component
class OrderViews {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private final ChannelClient channels;

    OrderViews(ChannelClient channels) {
        this.channels = channels;
    }

    OrderResponse one(Order order) {
        return many(List.of(order)).getFirst();
    }

    List<OrderResponse> many(List<Order> orders) {
        Map<UUID, ChannelRef> refs = channels.batch(orders.stream().map(Order::getChannelId).toList());
        return orders.stream().map(o -> OrderResponse.of(o, refs.getOrDefault(o.getChannelId(), ChannelRef.missing(o.getChannelId())))).toList();
    }

    /** Pobiera o jeden element więcej, żeby wiedzieć, czy jest następna strona. */
    CursorPage<OrderResponse> page(String cursor, Integer limit, Offset fetch) {
        int offset = offset(cursor);
        int size = limit(limit);
        List<Order> rows = fetch.fetch(offset, size + 1);
        boolean more = rows.size() > size;
        String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(("o:" + (offset + size)).getBytes(StandardCharsets.UTF_8)) : null;
        return new CursorPage<>(many(more ? rows.subList(0, size) : rows), next);
    }

    static OrderStatus status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return OrderStatus.parse(value).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nie ma takiego statusu zamówienia."));
    }

    private static int offset(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int offset = decoded.startsWith("o:") ? Integer.parseInt(decoded.substring(2)) : -1;
            if (offset < 0) {
                throw new IllegalArgumentException();
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowy kursor. Zacznij od pierwszej strony.");
        }
    }

    private static int limit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit musi wynosić od 1 do " + MAX_LIMIT + ".");
        }
        return limit;
    }

    @FunctionalInterface
    interface Offset {
        List<Order> fetch(int offset, int limit);
    }
}

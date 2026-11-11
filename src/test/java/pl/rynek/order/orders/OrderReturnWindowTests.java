package pl.rynek.order.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Okno zwrotu liczone od realizacji (doręczenia) zamówienia, bez Springa i bazy. */
class OrderReturnWindowTests {

    static final Duration WINDOW = Duration.ofDays(14);
    final Instant delivered = Instant.parse("2026-11-01T10:00:00Z");

    @Test
    void returnIsAcceptedOnTheLastDayAndRejectedAfter() {
        assertThat(completed().getReturnRequest()).isNull();
        Order lastDay = completed();
        lastDay.requestReturn("Nie pasuje", WINDOW, delivered.plus(WINDOW));
        assertThat(lastDay.getReturnRequest().status()).isEqualTo(ReturnStatus.REQUESTED);

        assertThatThrownBy(() -> completed().requestReturn("Nie pasuje", WINDOW, delivered.plus(WINDOW).plusSeconds(1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Minęło 14 dni od doręczenia, więc zwrotu nie można już zgłosić.");
    }

    private Order completed() {
        Order order = new Order(1001, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(new Order.Line(UUID.randomUUID(), UUID.randomUUID(), "Wiertarka", 1, 38900)),
                new Order.Shipping("courier", "Kurier", 1599, null, null),
                new Order.Address(UUID.randomUUID(), "Anna Nowak", "+48600100200", "ul. Długa 5", "00-950", "Warszawa"),
                delivered.minus(Duration.ofDays(3)));
        order.markPaid(UUID.randomUUID(), delivered.minus(Duration.ofDays(2)));
        order.delivered(delivered);
        return order;
    }
}

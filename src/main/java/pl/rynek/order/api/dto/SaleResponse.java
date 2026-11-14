package pl.rynek.order.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Sprzedaż dla analytics-service: {@code at} to chwila opłacenia, {@code amount} wartość towarów w groszach (bez dostawy). */
public record SaleResponse(UUID id, Instant at, long amount, List<Line> lines) {

    public record Line(UUID offerId, int quantity) {
    }
}

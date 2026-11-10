package pl.rynek.order.orders;

import java.util.Arrays;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonValue;

/** W API statusy mają polskie etykiety ({@code OrderStatus} z rynek-frontend), w bazie nazwy stałych. */
public enum OrderStatus {

    AWAITING_PAYMENT("Oczekuje na płatność"),
    TO_SHIP("Do wysłania"),
    SHIPPED("W drodze"),
    COMPLETED("Zrealizowane"),
    CANCELLED("Anulowane");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    @JsonValue
    public String label() {
        return label;
    }

    /** Filtr {@code ?status=} przyjmuje etykietę (tak wysyła frontend) albo nazwę stałej. */
    public static Optional<OrderStatus> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(s -> s.label.equalsIgnoreCase(value.trim()) || s.name().equalsIgnoreCase(value.trim())).findFirst();
    }

    /** Anulować można, dopóki paczka nie wyjechała. */
    boolean cancellable() {
        return this == AWAITING_PAYMENT || this == TO_SHIP;
    }
}

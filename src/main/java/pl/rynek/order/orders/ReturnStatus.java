package pl.rynek.order.orders;

import com.fasterxml.jackson.annotation.JsonValue;

/** Stan zgłoszenia zwrotu. W API polskie etykiety, w bazie nazwy stałych. */
public enum ReturnStatus {

    REQUESTED("Zgłoszony"),
    REJECTED("Odrzucony"),
    REFUNDED("Pieniądze zwrócone");

    private final String label;

    ReturnStatus(String label) {
        this.label = label;
    }

    @JsonValue
    public String label() {
        return label;
    }
}

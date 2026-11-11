package pl.rynek.order.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Zgłoszenie zwrotu: powód od kupującego (kształt z rynek-frontend: {@code {reason}}). */
public record RequestReturnRequest(@NotBlank(message = "Napisz, dlaczego zwracasz zamówienie.")
                                   @Size(max = 500, message = "Powód zwrotu może mieć najwyżej 500 znaków.") String reason) {
}

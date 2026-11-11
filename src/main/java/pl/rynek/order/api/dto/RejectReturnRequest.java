package pl.rynek.order.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Odrzucenie zwrotu: uzasadnienie dla kupującego. */
public record RejectReturnRequest(@NotBlank(message = "Napisz kupującemu, dlaczego odrzucasz zwrot.")
                                  @Size(max = 500, message = "Uzasadnienie może mieć najwyżej 500 znaków.") String reason) {
}

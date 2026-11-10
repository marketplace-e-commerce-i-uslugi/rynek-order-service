package pl.rynek.order.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MarkShippedRequest(
        @NotBlank(message = "Podaj numer przesyłki.") @Size(max = 40, message = "Numer przesyłki może mieć najwyżej 40 znaków.") String trackingNumber) {
}

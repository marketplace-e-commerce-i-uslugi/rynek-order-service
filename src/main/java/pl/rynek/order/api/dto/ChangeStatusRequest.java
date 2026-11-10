package pl.rynek.order.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Status jako etykieta z API, np. „Zrealizowane” albo „Anulowane”. */
public record ChangeStatusRequest(@NotBlank(message = "Podaj status.") String status) {
}

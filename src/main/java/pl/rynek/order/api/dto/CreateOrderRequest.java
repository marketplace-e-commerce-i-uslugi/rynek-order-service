package pl.rynek.order.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * {@code CART}: zamawia dostępne pozycje koszyka z dostawą wybraną w koszyku. {@code BUY_NOW}: jedna oferta
 * ({@code offerId}, {@code quantity}) z dostawą z żądania ({@code shippingMethodId}, {@code pickupPointId}).
 * {@code priceOfferId}: zaakceptowana w rozmowie propozycja ceny (tylko „Kup teraz”, jedna sztuka; zużywa się raz).
 */
public record CreateOrderRequest(
        @NotNull(message = "Podaj źródło zamówienia.") @Pattern(regexp = "CART|BUY_NOW", message = "Źródło zamówienia to CART albo BUY_NOW.") String source,
        UUID offerId,
        @Positive(message = "Ilość musi być większa od zera.") @Max(value = 999, message = "Za dużo sztuk w jednej pozycji.") Integer quantity,
        @NotNull(message = "Wybierz adres dostawy.") UUID addressId,
        @Size(max = 20, message = "Nie ma takiego sposobu dostawy.") String shippingMethodId,
        @Size(max = 40, message = "Nie ma takiego punktu odbioru.") String pickupPointId,
        UUID priceOfferId) {
}

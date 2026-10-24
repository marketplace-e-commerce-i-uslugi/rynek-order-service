package pl.rynek.order.api.dto;

/**
 * Ciało żądania: Utworzenie zamówienia z koszyka lub „Kup teraz” (Idempotency-Key); rezerwuje zapas. Pola do uzupełnienia razem z kontraktem w rynek-contracts.
 */
public record CreateOrderRequest() {
}

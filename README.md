# rynek-order-service

`order-service` — zamówienia

> Status: **szkielet**. Wszystkie endpointy z tabeli istnieją w kontrolerach (`src/main/java/.../api/`) i zwracają `501 Not Implemented` (RFC 7807). Ciała żądań to puste rekordy w `api/dto/`.

## Uruchomienie

```bash
./mvnw spring-boot:run   # port 8091, zmienna PORT
./mvnw verify            # testy
```

- Swagger UI: http://localhost:8091/swagger-ui.html
- Zdrowie: http://localhost:8091/actuator/health

## Zakres i endpointy

_Skopiowane z `rynek-docs/architektura/rynek-endpointy-mikroserwisy.md`. Od teraz to repozytorium jest właścicielem tej specyfikacji._

Ekrany: 8 Płatność, Profil → Moje zamówienia, 9 Panel → Zamówienia. Statusy zamówień: `Do wysłania`, `W drodze`, `Zrealizowane` (oraz `Oczekuje na płatność`, `Anulowane`).

| Metoda | Ścieżka | Opis | Dostęp |
|---|---|---|---|
| POST | `/api/v1/orders` | Utworzenie zamówienia z koszyka lub „Kup teraz” (`Idempotency-Key`); rezerwuje zapas | user |
| GET | `/api/v1/orders/mine?status=&cursor=` | Moje zamówienia | user |
| GET | `/api/v1/orders/{orderId}` | Szczegóły zamówienia | user / seller |
| GET | `/api/v1/orders/{orderId}/timeline` | Oś statusów | user / seller |
| POST | `/api/v1/orders/{orderId}/cancel` | Anulowanie (przed wysyłką) | user |
| POST | `/api/v1/orders/{orderId}/returns` | Zgłoszenie zwrotu | user |
| GET | `/api/v1/seller/orders?status=&cursor=` | Zamówienia sprzedawcy | seller |
| POST | `/api/v1/seller/orders/{orderId}/ship` | Oznaczenie jako wysłane (numer przesyłki) | seller |
| POST | `/api/v1/seller/orders/{orderId}/status` | Zmiana statusu | seller |
| POST | `/internal/orders/{orderId}/mark-paid` | Oznaczenie jako opłacone (po zdarzeniu z płatności) | int |

## Zdarzenia Kafka

Wiersze z §17, w których występuje `order` (publikuje lub słucha).

| Zdarzenie | Publikuje | Słuchają |
|---|---|---|
| `order.created`, `order.paid`, `order.shipped`, `order.completed`, `order.cancelled` | order | catalog (zapas), notification, analytics, engagement |
| `payment.succeeded`, `payment.failed`, `payment.refunded` | payment | order, booking, notification |
| `shipment.status-changed` | shipping | order, notification |

## Dane i zależności

- **Magazyn danych:** PostgreSQL
- **Zależności:** cart-service, catalog-service (rezerwacja zapasu), payment-service
- **Kontrakty:** `rynek-contracts/openapi/` i `rynek-contracts/events/`
- **Lokalne uruchomienie:** `rynek-infra/local/`

## Kolejność budowy

Etap z §19: 5. `cart-service`, `order-service`, `payment-service`, `shipping-service` — sprzedaż produktów.

## Wyjątki routingu

Gateway (§2) kieruje ruch po prefiksie ścieżki, a poniższe endpointy leżą pod prefiksem innego serwisu. Gateway obsługuje je trasami szczegółowymi (`order: -1`), które mają pierwszeństwo przed trasami po prefiksie. Ścieżki pozostają bez zmian (decyzja z 2026-10-24). Test `RoutingTests` w `rynek-api-gateway` sprawdza, czy każda z nich trafia do właściwego serwisu.

- `/api/v1/orders/{orderId}/review`: należy do `engagement-service` (prefiks wskazuje `order-service`).

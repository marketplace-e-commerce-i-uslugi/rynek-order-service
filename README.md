# rynek-order-service

`order-service` — zamówienia

> Status: **zamówienia działają** (PostgreSQL + Flyway, JWT z JWKS identity): złożenie z koszyka i „Kup teraz” z rezerwacją zapasu, moje zamówienia, szczegóły, oś statusów, anulowanie, panel sprzedawcy (lista, wysyłka, zmiana statusu), `mark-paid` i wygasanie nieopłaconych zamówień po 30 minutach. Zgłoszenie zwrotu zwraca `501 Not Implemented`. Zdarzeń Kafka jeszcze nie ma; zależne serwisy są wołane synchronicznie przez `/internal`. Dla analytics-service: `GET /internal/analytics/sales?channelId=&from=&to=` oddaje zamówienia kanału opłacone w przedziale (chwila wejścia w „Do wysłania”), bez anulowanych: `{id, at, amount (towary, w groszach), lines[{offerId, quantity}]}`, przedział najwyżej 400 dni.

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
| POST | `/api/v1/seller/orders/{orderId}/returns/reject` | Odrzucenie zgłoszonego zwrotu z uzasadnieniem (dodane poza dokumentem architektury) | seller |
| GET | `/internal/orders/{orderId}` | Kwota, status i kupujący dla payment-service | int |
| GET | `/internal/orders?ids=` | Wiele zamówień naraz (najwyżej 100), np. statusy do salda sprzedawcy | int |
| PUT | `/internal/orders/{orderId}/shipment` | Przesyłka z shipping-service: `shipmentId`, przewoźnik i numer przesyłki | int |
| POST | `/internal/orders/{orderId}/refunded` | payment-service zwrócił pieniądze: zgłoszony zwrot jest przyjęty | int |
| POST | `/internal/orders/{orderId}/shipment-status` | Status od przewoźnika: `PICKED_UP` → „W drodze”, `DELIVERED` → „Zrealizowane” | int |
| POST | `/internal/orders/{orderId}/mark-paid` | Oznaczenie jako opłacone (po zdarzeniu z płatności) | int |

### Zasady

- `POST /orders {source, addressId, offerId?, quantity?, shippingMethodId?, pickupPointId?}` z nagłówkiem `Idempotency-Key` (wymagany, do 100 znaków). Ponowienie z tym samym kluczem zwraca te same zamówienia. Odpowiedź to lista: zakup z koszyka od kilku sprzedawców daje jedno zamówienie na sprzedawcę.
- `CART` zamawia dostępne pozycje koszyka z dostawą wybraną w koszyku (bez wyboru: paczkomat). Niedostępne zostają w koszyku, zamówione są z niego usuwane. `BUY_NOW` bierze dostawę z żądania.
- Paczkomat wymaga `pickupPointId` (422). Adres, ceny, tytuły i dostawa są kopią z chwili zakupu.
- Zapas: każda pozycja rezerwuje sztuki w catalog (id pozycji = id rezerwacji). Gdy któraś rezerwacja się nie uda, wcześniejsze są zwalniane i zamówienie nie powstaje (409 z tytułem oferty). Anulowanie zwalnia rezerwacje.
- Wygasanie: zamówienie nieopłacone dłużej niż `rynek.orders.payment-timeout` (domyślnie 30 min) jest anulowane, a jego zapas zwolniony. Sprawdzenie co `rynek.orders.expiry-check` (60 s).
- `GET /internal/orders/{id}` (dla payment-service): `{id, number, buyerId, sellerId, status, total}`, gdzie `status` to nazwa stałej (np. `AWAITING_PAYMENT`). `mark-paid` przyjmuje opcjonalne `{paymentId}`, które trafia do `Order.paymentId` (zwrot środków).
- Statusy: „Oczekuje na płatność” → (`mark-paid`) „Do wysłania” → (`/ship` z numerem przesyłki) „W drodze” → (`/status` „Zrealizowane”) „Zrealizowane”. Anulować może kupujący (`/cancel`) i sprzedawca (`/status` „Anulowane”) zamówienie przed wysyłką. Wysłanego nie można anulować (409).
- Przesyłki: etykieta z shipping-service ustawia `shipmentId` i `trackingNumber` bez zmiany statusu. Odebranie paczki przez przewoźnika daje „W drodze”, a doręczenie „Zrealizowane” (także bez wcześniejszego „odebrana”). Ponowienie niczego nie zmienia, a dla anulowanego zamówienia jest 409. Sprzedawca nadal może sam oznaczyć wysyłkę (`/ship`) i realizację. `GET /internal/orders/{id}` ma dla shipping-service także `receiver` i `shipping` (metoda, paczkomat, `shipmentId`).
- Zwroty (odstąpienie od umowy): `POST /orders/{id}/returns {reason}` (do 500 znaków) składa kupujący dla zamówienia „Zrealizowane”, najwyżej `rynek.orders.return-window` (14 dni) od realizacji. Na zamówienie przypada jedno zgłoszenie. Sprzedawca przyjmuje zwrot, oddając pieniądze (`POST /payments/{paymentId}/refund`, po czym payment-service woła `/internal/orders/{id}/refunded`), albo odrzuca go z uzasadnieniem (`/seller/orders/{id}/returns/reject {reason}`). Zamówienie ma `returnRequest {status: Zgłoszony | Odrzucony | Pieniądze zwrócone, reason, requestedAt, decision, decidedAt}`. Status zamówienia się nie zmienia. Gdy zwrot zostaje przyjęty (`refunded` przy zgłoszonym zwrocie), order-service oddaje sztuki do zapasu: dla każdej pozycji woła `POST /internal/offers/{id}/stock/return` w catalog (id rezerwacji = id pozycji, więc idempotentnie). Niedostępny catalog nie blokuje przyjęcia: flaga `return_stock_pending` zostaje, a zgłoszenie jest ponawiane co minutę (4xx nie jest ponawiane). Zwrot pieniędzy bez zgłoszenia zwrotu nie rusza zapasu. Odesłanie paczki i etykieta zwrotna to następny krok (shipping-service).
- Każde anulowanie (także wygaśnięcie) trafia do payment-service: `POST /internal/orders/{id}/cancelled` zamiast zdarzenia `order.cancelled`. Payment zwraca pieniądze za opłacone zamówienie i zamyka płatności w toku. Gdy payment-service nie odpowiada, anulowanie i tak się udaje, a zgłoszenie jest ponawiane co `rynek.orders.payment-retry` (60 s, flaga `payment_notified`). Odrzucenie 4xx nie jest ponawiane i trafia do logu jako błąd do ręcznego sprawdzenia.
- Anulowanie trafia też do shipping-service (`POST /internal/orders/{id}/cancelled`), który anuluje przesyłkę z samą etykietą (odebrana, w drodze albo doręczona zostaje bez zmian). Ponawianie co `rynek.orders.shipping-retry` (60 s, flaga `shipping_notified`); odrzucenie 4xx trafia do logu. Wynik nie wpływa na anulowanie ani zwrot pieniędzy.
- Zamówienie widzą tylko kupujący i sprzedawca (właściciel kanału); dla innych jest 404. Równoległe zmiany statusu rozstrzyga `@Version` (409).
- `Order` ma więcej pól niż kształt z frontendu: `itemsTotal`, `shippingTotal`, `shipping {methodId, name, pickupPointId, pickupPoint}`, `trackingNumber`. `number` ma postać `#1001`.

## Zdarzenia Kafka

Wiersze z §17, w których występuje `order` (publikuje lub słucha).

| Zdarzenie | Publikuje | Słuchają |
|---|---|---|
| `order.created`, `order.paid`, `order.shipped`, `order.completed`, `order.cancelled` | order | catalog (zapas), notification, analytics, engagement |
| `payment.succeeded`, `payment.failed`, `payment.refunded` | payment | order, booking, notification |
| `shipment.status-changed` | shipping | order, notification |

## Dane i zależności

- **Magazyn danych:** PostgreSQL (baza `orders`)
- **Zależności:** cart-service (`/internal/carts`), catalog-service (`price-and-stock`, rezerwacja i zwolnienie zapasu), identity-service (adres), shipping-service (cena dostawy, punkt odbioru), channel-service (nagłówek sprzedawcy), payment-service (`POST /internal/orders/{id}/cancelled`); notification-service (`POST /internal/notifications`, best effort: sprzedawca dostaje powiadomienie o opłaconym zamówieniu, anulowaniu i zgłoszonym zwrocie, kupujący o wysyłce, realizacji, anulowaniu i decyzji w sprawie zwrotu, klucz zdarzenia `order-<id>-<zdarzenie>` chroni przed dubletami; adres w `NOTIFICATION_SERVICE_URL`); payment-service woła `mark-paid` i `GET /internal/orders`
- **Kontrakty:** `rynek-contracts/openapi/` i `rynek-contracts/events/`
- **Lokalne uruchomienie:** `rynek-infra/local/`

## Kolejność budowy

Etap z §19: 5. `cart-service`, `order-service`, `payment-service`, `shipping-service` — sprzedaż produktów.

## Wyjątki routingu

Gateway (§2) kieruje ruch po prefiksie ścieżki, a poniższe endpointy leżą pod prefiksem innego serwisu. Gateway obsługuje je trasami szczegółowymi (`order: -1`), które mają pierwszeństwo przed trasami po prefiksie. Ścieżki pozostają bez zmian (decyzja z 2026-10-24). Test `RoutingTests` w `rynek-api-gateway` sprawdza, czy każda z nich trafia do właściwego serwisu.

- `/api/v1/orders/{orderId}/review`: należy do `engagement-service` (prefiks wskazuje `order-service`).

# rynek-order-service

Mikroserwis platformy **Rynek**: `order-service` — zamówienia

Zakres, endpointy i zdarzenia są opisane w `README.md`. Zamówienia działają (PostgreSQL + Flyway, Spring Security Resource Server z JWKS identity); zapas rezerwuje w catalog, nieopłacone zamówienia wygasają po 30 min. Zgłoszenie zwrotu zwraca 501. Nie dodawaj logiki bez wyraźnej prośby.

## Wspólne konwencje API (§1 dokumentu architektury)

| Temat | Zasada |
|---|---|
| Prefiks | `/api/v1/...`, gateway kieruje po prefiksie ścieżki |
| Auth | JWT Bearer wystawiany przez `identity-service`, walidowany w gateway i w serwisach (Spring Security Resource Server) |
| Role | `BUYER`, `SERVICE_PROVIDER`, `MERCHANT`, `ADMIN`; jeden użytkownik może mieć kilka |
| Paginacja | kursorowa: `?cursor=...&limit=20` → `{items, nextCursor}` |
| Kwoty | liczba całkowita w groszach + waluta: `{"amount": 38900, "currency": "PLN"}` |
| Błędy | RFC 7807 (`application/problem+json`) |
| Idempotencja | nagłówek `Idempotency-Key` na `POST /orders`, `POST /payments`, `POST /bookings` |
| Język | `Accept-Language: pl-PL`, treści błędów po polsku |
| Ścieżki wewnętrzne | `/internal/...` — tylko między serwisami, nieudostępnione przez gateway |
| Zdarzenia | Kafka, nazwy `domena.zdarzenie` (sekcja 17) |

Legenda kolumny **Dostęp**: `pub` = bez logowania, `user` = zalogowany, `seller` = właściciel kanału (usługodawca lub merchant), `admin`, `int` = wewnętrzny.

## Stack

- Java 25, Spring Boot 4.1 (Maven, `./mvnw`)
- Dane: PostgreSQL (Flyway); testy na Testcontainers
- Zdarzenia: Kafka, nazwy `domena.zdarzenie`

## Powiązane repozytoria

- `rynek-docs`: pełny dokument architektury, design system, koncepcja produktu
- `rynek-contracts`: OpenAPI i schematy zdarzeń (źródło prawdy dla interfejsów między serwisami)
- `rynek-infra`: lokalne środowisko i wdrożenia

## Struktura kodu

- `pl.rynek.order.api`: kontrolery REST (pełne ścieżki w adnotacjach), `api.dto`: rekordy żądań i odpowiedzi, `common.NotImplemented`: odpowiedź 501; `orders`: zamówienie, statusy, składanie z rezerwacją zapasu, idempotencja; `clients`: klienci cart, catalog, identity, shipping, channel, payment; `config`: security, błędy RFC 7807 po polsku
- Konfiguracja: `src/main/resources/application.yml`
- Port lokalny: 8091

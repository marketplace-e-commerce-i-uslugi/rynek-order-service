-- Zgłoszenie zwrotu przez kupującego (odstąpienie od umowy) i decyzja sprzedawcy. Jedno zgłoszenie na zamówienie.
-- return_status: REQUESTED (czeka na sprzedawcę), REJECTED (odrzucone z uzasadnieniem), REFUNDED (pieniądze zwrócone).
ALTER TABLE orders ADD COLUMN return_status varchar(20) CHECK (return_status IN ('REQUESTED', 'REJECTED', 'REFUNDED'));
ALTER TABLE orders ADD COLUMN return_reason varchar(500);
ALTER TABLE orders ADD COLUMN return_requested_at timestamptz;
ALTER TABLE orders ADD COLUMN return_decision varchar(500);
ALTER TABLE orders ADD COLUMN return_decided_at timestamptz;

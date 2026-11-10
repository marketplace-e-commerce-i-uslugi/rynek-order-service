-- Płatność, która opłaciła zamówienie (payment-service, mark-paid). Służy do zwrotu środków.
ALTER TABLE orders ADD COLUMN payment_id uuid;

-- Wygasanie nieopłaconych zamówień szuka po statusie i dacie utworzenia.
CREATE INDEX orders_awaiting_payment_idx ON orders (created_at) WHERE status = 'AWAITING_PAYMENT';

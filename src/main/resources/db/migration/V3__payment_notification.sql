-- Anulowanie zamówienia zgłaszamy payment-service: zwraca pieniądze za opłacone zamówienie i zamyka płatności w toku.
-- false = payment-service jeszcze nie przyjął zgłoszenia; ponawiane co minutę. Starsze zamówienia nie czekają na nic.
ALTER TABLE orders ADD COLUMN payment_notified boolean NOT NULL DEFAULT true;

CREATE INDEX orders_payment_unnotified_idx ON orders (updated_at) WHERE NOT payment_notified;

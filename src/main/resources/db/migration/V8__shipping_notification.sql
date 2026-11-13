-- Anulowanie zamówienia zgłaszamy shipping-service: anuluje przesyłkę, jeśli etykieta nie została jeszcze nadana.
-- false = shipping-service jeszcze nie przyjął zgłoszenia; ponawiane co minutę. Starsze zamówienia nie czekają na nic.
ALTER TABLE orders ADD COLUMN shipping_notified boolean NOT NULL DEFAULT true;

CREATE INDEX orders_shipping_unnotified_idx ON orders (updated_at) WHERE NOT shipping_notified;

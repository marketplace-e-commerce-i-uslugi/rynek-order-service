-- Przesyłka z shipping-service (etykieta u przewoźnika) i jej przewoźnik. Numer przesyłki jest w tracking_number.
ALTER TABLE orders ADD COLUMN shipment_id uuid;
ALTER TABLE orders ADD COLUMN carrier varchar(30);

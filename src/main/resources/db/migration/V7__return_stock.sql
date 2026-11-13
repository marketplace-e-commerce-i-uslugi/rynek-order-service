-- Przyjęty zwrot oddaje sztuki do zapasu w catalog. true = catalog jeszcze nie potwierdził; ponawiane co minutę.
ALTER TABLE orders ADD COLUMN return_stock_pending boolean NOT NULL DEFAULT false;

CREATE INDEX orders_return_stock_pending_idx ON orders (updated_at) WHERE return_stock_pending;

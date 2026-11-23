-- Cena uzgodniona w rozmowie (chat-service), zużyta przy tworzeniu zamówienia. Wraca do użycia, gdy nieopłacone zamówienie zostanie anulowane.
ALTER TABLE orders ADD COLUMN price_offer_id uuid;

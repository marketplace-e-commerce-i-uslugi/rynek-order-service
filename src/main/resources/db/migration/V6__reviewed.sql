-- Opinię o zamówieniu zapisuje engagement-service i zgłasza tutaj, żeby kupujący nie widział „Wystaw opinię” drugi raz.
ALTER TABLE orders ADD COLUMN reviewed boolean NOT NULL DEFAULT false;

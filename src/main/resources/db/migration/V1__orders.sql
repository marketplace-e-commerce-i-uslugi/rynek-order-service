-- Zamówienie u jednego sprzedawcy. Zakup z koszyka od kilku sprzedawców tworzy kilka zamówień.
-- Adres, ceny, tytuły i dostawa są kopią z chwili zakupu: późniejsze zmiany oferty czy adresu ich nie zmieniają.
CREATE SEQUENCE order_number_seq START WITH 1001;

CREATE TABLE orders (
    id                   uuid PRIMARY KEY,
    number               bigint       NOT NULL UNIQUE,
    buyer_id             uuid         NOT NULL,
    -- seller_id = właściciel kanału (sub z JWT), channel_id = kanał z channel-service (dane kanału przy odczycie).
    seller_id            uuid         NOT NULL,
    channel_id           uuid         NOT NULL,
    status               varchar(20)  NOT NULL
        CHECK (status IN ('AWAITING_PAYMENT', 'TO_SHIP', 'SHIPPED', 'COMPLETED', 'CANCELLED')),
    items_total          bigint       NOT NULL CHECK (items_total >= 0),
    shipping_total       bigint       NOT NULL CHECK (shipping_total >= 0),
    currency             varchar(3)   NOT NULL DEFAULT 'PLN',
    shipping_method_id   varchar(20)  NOT NULL,
    shipping_method_name varchar(60)  NOT NULL,
    pickup_point_id      varchar(40),
    pickup_point_label   varchar(200),
    address_id           uuid         NOT NULL,
    full_name            varchar(80)  NOT NULL,
    phone                varchar(16)  NOT NULL,
    street               varchar(120) NOT NULL,
    postal_code          varchar(6)   NOT NULL,
    city                 varchar(60)  NOT NULL,
    tracking_number      varchar(40),
    created_at           timestamptz  NOT NULL,
    updated_at           timestamptz  NOT NULL,
    version              int          NOT NULL DEFAULT 0
);

CREATE INDEX orders_buyer_idx ON orders (buyer_id, created_at DESC, id);
CREATE INDEX orders_seller_idx ON orders (seller_id, created_at DESC, id);

-- id pozycji jest zarazem id rezerwacji zapasu w catalog-service.
CREATE TABLE order_lines (
    id         uuid PRIMARY KEY,
    order_id   uuid         NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    position   int          NOT NULL,
    offer_id   uuid         NOT NULL,
    title      varchar(120) NOT NULL,
    quantity   int          NOT NULL CHECK (quantity > 0),
    unit_price bigint       NOT NULL CHECK (unit_price >= 0),
    UNIQUE (order_id, position)
);

CREATE TABLE order_status_history (
    order_id uuid        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    position int         NOT NULL,
    status   varchar(20) NOT NULL,
    at       timestamptz NOT NULL,
    PRIMARY KEY (order_id, position)
);

-- Idempotency-Key z POST /orders: ponowione żądanie zwraca te same zamówienia zamiast tworzyć nowe.
CREATE TABLE order_requests (
    buyer_id        uuid         NOT NULL,
    idempotency_key varchar(100) NOT NULL,
    order_ids       varchar(2000) NOT NULL,
    created_at      timestamptz  NOT NULL,
    PRIMARY KEY (buyer_id, idempotency_key)
);

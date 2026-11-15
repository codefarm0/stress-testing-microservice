CREATE TABLE customers (
    id         BIGINT       NOT NULL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    email      VARCHAR(150) NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB;

CREATE TABLE products (
    id     BIGINT         NOT NULL PRIMARY KEY,
    sku    VARCHAR(32)    NOT NULL UNIQUE,
    name   VARCHAR(150)   NOT NULL,
    price  DECIMAL(10, 2) NOT NULL,
    active BOOLEAN        NOT NULL DEFAULT TRUE
) ENGINE = InnoDB;

CREATE TABLE inventory (
    product_id BIGINT       NOT NULL PRIMARY KEY,
    available  INT          NOT NULL,
    reserved   INT          NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_inventory_product FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE = InnoDB;

CREATE TABLE orders (
    id          BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id  CHAR(36)       NULL,           -- idempotency key, used by the async flow (phase B)
    customer_id BIGINT         NOT NULL,
    status      VARCHAR(16)    NOT NULL,
    total       DECIMAL(12, 2) NOT NULL,
    created_at  TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_orders_request_id UNIQUE (request_id),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    INDEX idx_orders_customer (customer_id)
) ENGINE = InnoDB;

CREATE TABLE order_items (
    id         BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id   BIGINT         NOT NULL,
    product_id BIGINT         NOT NULL,
    quantity   INT            NOT NULL,
    unit_price DECIMAL(10, 2) NOT NULL,
    line_total DECIMAL(12, 2) NOT NULL,
    CONSTRAINT fk_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_items_product FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE = InnoDB;

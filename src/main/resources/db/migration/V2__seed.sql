-- 100,000 customers, 10,000 products, 10,000,000 units of stock per product.
-- Stock is deliberately huge so a long overload run does not turn into a 409 storm.
-- regular table on purpose: MySQL cannot reference a TEMPORARY table twice in one query (error 1137)
CREATE TABLE seed_digits (d INT NOT NULL);
INSERT INTO seed_digits VALUES (0), (1), (2), (3), (4), (5), (6), (7), (8), (9);

INSERT INTO customers (id, name, email, status)
SELECT n, CONCAT('Customer ', n), CONCAT('customer', n, '@example.com'), 'ACTIVE'
FROM (SELECT a.d + b.d * 10 + c.d * 100 + e.d * 1000 + f.d * 10000 + 1 AS n
      FROM seed_digits a CROSS JOIN seed_digits b CROSS JOIN seed_digits c CROSS JOIN seed_digits e CROSS JOIN seed_digits f) nums;

INSERT INTO products (id, sku, name, price)
SELECT n, CONCAT('SKU-', LPAD(n, 6, '0')), CONCAT('Product ', n), ROUND(5 + (n MOD 500) * 0.97, 2)
FROM (SELECT a.d + b.d * 10 + c.d * 100 + e.d * 1000 + 1 AS n
      FROM seed_digits a CROSS JOIN seed_digits b CROSS JOIN seed_digits c CROSS JOIN seed_digits e) nums;

INSERT INTO inventory (product_id, available)
SELECT id, 10000000 FROM products;

DROP TABLE seed_digits;

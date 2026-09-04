DROP ALL OBJECTS;

CREATE TABLE users (
    id BIGINT PRIMARY KEY,
    email VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(60),
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP
);

CREATE TABLE sim_account (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    initial_cash DOUBLE NOT NULL,
    cash DOUBLE NOT NULL,
    loan_balance DOUBLE,
    frozen_margin DOUBLE,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP
);

CREATE TABLE sim_position (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    quantity DOUBLE NOT NULL,
    avg_cost DOUBLE NOT NULL,
    leverage DOUBLE,
    loan_amount DOUBLE,
    margin_used DOUBLE,
    updated_at TIMESTAMP
);

-- 故意不包含 client_order_id，验证旧表兼容迁移。
CREATE TABLE sim_trade (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    type VARCHAR(10) NOT NULL,
    price DOUBLE NOT NULL,
    quantity DOUBLE NOT NULL,
    amount DOUBLE NOT NULL,
    leverage DOUBLE,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE price_snapshot (
    id BIGINT PRIMARY KEY,
    market VARCHAR(32) NOT NULL,
    price DOUBLE NOT NULL,
    change DOUBLE,
    change_pct DOUBLE,
    prev_close DOUBLE,
    open DOUBLE,
    high DOUBLE,
    low DOUBLE,
    ts TIMESTAMP NOT NULL
);

CREATE TABLE kline_daily (
    id BIGINT PRIMARY KEY,
    market VARCHAR(32) NOT NULL,
    date VARCHAR(16) NOT NULL,
    open DOUBLE NOT NULL,
    close DOUBLE NOT NULL,
    high DOUBLE NOT NULL,
    low DOUBLE NOT NULL,
    volume DOUBLE
);

INSERT INTO users VALUES
(7, 'migration@example.com', '$2a$10$example', 'Migration User', TRUE, TIMESTAMP '2026-08-31 12:00:00');

INSERT INTO sim_account VALUES
(11, 7, 100000.0, 98000.0, 1500.0, 1500.0, 'ACTIVE', TIMESTAMP '2026-08-31 12:00:01');

INSERT INTO sim_position VALUES
(21, 7, 'sh518850', 100.0, 9.5000, 2.0, 475.0, 475.0, TIMESTAMP '2026-09-04 10:00:00');

INSERT INTO sim_trade VALUES
(31, 7, 'sh518850', 'BUY', 9.5000, 100.0, 950.0, 2.0, TIMESTAMP '2026-09-04 10:00:00');

INSERT INTO price_snapshot VALUES
(41, 'gold_etf', 9.6000, 0.1, 1.05, 9.5, 9.5, 9.61, 9.49, TIMESTAMP '2026-09-04 10:00:30'),
(42, 'gold_etf', 9.6100, 0.11, 1.16, 9.5, 9.5, 9.62, 9.49, TIMESTAMP '2026-09-04 10:01:00');

INSERT INTO kline_daily VALUES
(51, 'gold_etf', '2026-09-03', 9.40, 9.50, 9.55, 9.35, 123456.0),
(52, 'gold_etf', '2026-09-04', 9.50, 9.61, 9.62, 9.49, 654321.0);

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(120) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(60),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP
);

CREATE TABLE sim_account (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    initial_cash NUMERIC(19,4) NOT NULL DEFAULT 100000.0000,
    cash NUMERIC(19,4) NOT NULL DEFAULT 100000.0000,
    loan_balance NUMERIC(19,4) DEFAULT 0,
    frozen_margin NUMERIC(19,4) DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP,
    CONSTRAINT fk_sim_account_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE sim_position (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    quantity NUMERIC(24,8) NOT NULL DEFAULT 0,
    avg_cost NUMERIC(24,8) NOT NULL DEFAULT 0,
    leverage NUMERIC(8,4) DEFAULT 1,
    loan_amount NUMERIC(19,4) DEFAULT 0,
    margin_used NUMERIC(19,4) DEFAULT 0,
    updated_at TIMESTAMP,
    CONSTRAINT uk_position_user_symbol UNIQUE (user_id, symbol),
    CONSTRAINT fk_sim_position_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE sim_trade (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    type VARCHAR(10) NOT NULL,
    client_order_id VARCHAR(64),
    price NUMERIC(24,8) NOT NULL,
    quantity NUMERIC(24,8) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    leverage NUMERIC(8,4) DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_trade_user_client_order UNIQUE (user_id, client_order_id),
    CONSTRAINT fk_sim_trade_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX idx_trade_user ON sim_trade(user_id);
CREATE INDEX idx_trade_symbol ON sim_trade(symbol);

CREATE TABLE price_snapshot (
    id BIGSERIAL PRIMARY KEY,
    market VARCHAR(32) NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    change DOUBLE PRECISION,
    change_pct DOUBLE PRECISION,
    prev_close DOUBLE PRECISION,
    open DOUBLE PRECISION,
    high DOUBLE PRECISION,
    low DOUBLE PRECISION,
    ts TIMESTAMP NOT NULL
);
CREATE INDEX idx_snap_market_ts ON price_snapshot(market, ts);

CREATE TABLE kline_daily (
    id BIGSERIAL PRIMARY KEY,
    market VARCHAR(32) NOT NULL,
    date VARCHAR(16) NOT NULL,
    open DOUBLE PRECISION NOT NULL,
    close DOUBLE PRECISION NOT NULL,
    high DOUBLE PRECISION NOT NULL,
    low DOUBLE PRECISION NOT NULL,
    volume DOUBLE PRECISION,
    CONSTRAINT uk_kline_market_date UNIQUE (market, date)
);
CREATE UNIQUE INDEX idx_kline_market_date ON kline_daily(market, date);

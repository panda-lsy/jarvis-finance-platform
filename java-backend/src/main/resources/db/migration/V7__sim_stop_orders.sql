CREATE TABLE sim_order (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(10) NOT NULL,
    order_type VARCHAR(20) NOT NULL,
    quantity NUMERIC(24,8) NOT NULL,
    leverage NUMERIC(8,4) NOT NULL DEFAULT 1,
    stop_price NUMERIC(24,8) NOT NULL,
    time_in_force VARCHAR(10) NOT NULL DEFAULT 'DAY',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    client_order_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    triggered_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_sim_order_user_client UNIQUE (user_id, client_order_id),
    CONSTRAINT fk_sim_order_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_sim_order_user_status ON sim_order(user_id, status);
CREATE INDEX idx_sim_order_status_created ON sim_order(status, created_at);

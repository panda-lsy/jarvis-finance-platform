CREATE TABLE audit_event (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    action VARCHAR(48) NOT NULL,
    target VARCHAR(64),
    client_ip VARCHAR(64),
    detail VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_audit_user_created ON audit_event(user_id, created_at DESC);
CREATE INDEX idx_audit_action_created ON audit_event(action, created_at DESC);

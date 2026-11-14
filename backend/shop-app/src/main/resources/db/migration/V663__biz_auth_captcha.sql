CREATE TABLE IF NOT EXISTS sys_auth_captcha_state (
    state_key VARCHAR(64) NOT NULL PRIMARY KEY,
    scope_hash VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    expires_at BIGINT NOT NULL,
    INDEX idx_auth_captcha_state_expiry (expires_at)
);

CREATE TABLE IF NOT EXISTS sys_auth_captcha_rate (
    rate_key VARCHAR(64) NOT NULL PRIMARY KEY,
    hits INT NOT NULL,
    expires_at BIGINT NOT NULL,
    INDEX idx_auth_captcha_rate_expiry (expires_at)
);

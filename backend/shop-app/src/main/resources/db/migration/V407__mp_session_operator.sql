-- Current mini-program operator can differ from the canonical phone account's permanent Wx credential.
CREATE TABLE IF NOT EXISTS usr_mp_session (
    token_hash VARCHAR(64) NOT NULL PRIMARY KEY,
    user_no VARCHAR(64) NOT NULL,
    openid VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL,
    KEY idx_mp_session_user (user_no)
);

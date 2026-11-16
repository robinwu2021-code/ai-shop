-- Self-service login credential changes and deregistrations (TDD-商户App统一登录与账号 AC22).
-- Values are stored masked only; plain phone numbers and emails never land here.
CREATE TABLE usr_account_change_log (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_no VARCHAR(64) NOT NULL,
 action VARCHAR(24) NOT NULL,
 before_masked VARCHAR(191) NULL,
 after_masked VARCHAR(191) NULL,
 actor_kind VARCHAR(16) NOT NULL,
 client_ip VARCHAR(64) NULL,
 tenant_no VARCHAR(64) NOT NULL DEFAULT 'MAIN',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 version BIGINT NOT NULL DEFAULT 0,
 deleted TINYINT NOT NULL DEFAULT 0,
 KEY idx_account_change_user(user_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

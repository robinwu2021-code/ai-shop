ALTER TABLE mch_account
    ADD COLUMN login_email VARCHAR(191) DEFAULT NULL,
    ADD COLUMN employee_code VARCHAR(8) DEFAULT NULL,
    ADD COLUMN login_user_no VARCHAR(64) DEFAULT NULL,
    ADD UNIQUE KEY uk_mch_account_entity_email (entity_no, login_email),
    ADD UNIQUE KEY uk_mch_account_entity_employee_code (entity_no, employee_code),
    ADD KEY idx_mch_account_login_email (login_email);

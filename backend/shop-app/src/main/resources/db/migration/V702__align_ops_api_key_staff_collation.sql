-- Match sys_ops_staff.staff_no so MySQL can resolve the credential-to-employee equality join.
ALTER TABLE sys_ops_api_key
    MODIFY COLUMN staff_no VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_520_ci NOT NULL;

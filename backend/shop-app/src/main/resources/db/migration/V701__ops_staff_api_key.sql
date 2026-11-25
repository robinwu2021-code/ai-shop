CREATE TABLE sys_ops_api_key (
    key_no CHAR(36) NOT NULL,
    staff_no VARCHAR(64) NOT NULL,
    secret_hash CHAR(64) NOT NULL,
    label VARCHAR(80) NOT NULL,
    access_level VARCHAR(16) NOT NULL,
    issued_by VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    revoked_at BIGINT NULL,
    PRIMARY KEY (key_no),
    UNIQUE KEY uk_ops_api_key_hash (secret_hash),
    KEY idx_ops_api_key_staff (staff_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 管理员代签的功能点通过角色授权，员工自助申请仍只使用本人会话。
INSERT INTO sys_function_point (
    point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
    backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at
)
SELECT 'ACT__IAM_STAFF_API_KEY_MANAGE', 'OPS_IAM', '员工 API 密钥管理', '账号', NULL,
       'iam:staff:api-key-manage', 'iam:staff:api-key-manage',
       'IMPLEMENTED', 0, NULL, 'ACTION', 930, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM sys_function_point
    WHERE point_code = 'ACT__IAM_STAFF_API_KEY_MANAGE' OR perm_code = 'iam:staff:api-key-manage'
);

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__IAM_STAFF_API_KEY_MANAGE', 'OPS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM DUAL
WHERE EXISTS (SELECT 1 FROM sys_function_point WHERE point_code = 'ACT__IAM_STAFF_API_KEY_MANAGE')
  AND EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'SUPER_ADMIN' AND end_code = 'OPS' AND deleted = 0)
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_point
      WHERE role_code = 'SUPER_ADMIN' AND point_code = 'ACT__IAM_STAFF_API_KEY_MANAGE' AND end_code = 'OPS'
  );

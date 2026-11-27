-- 运营账号的角色收成一处（TDD-基础能力库对齐 2a、Q3）：判权从 sys_ops_staff.roles（JSON）改读 sys_role_member，JSON 列停写。
-- 切换前把 sys_role_member 对齐到 JSON —— 此前判权以 JSON 为准，对齐后每个人的权限与切换前完全相同。
-- 只动 OPS 端；JSON 不合法或为空的账号按此前的口径算作没有角色。已删除的账号不动。

-- ① JSON 里有、sys_role_member 里没有的：补上
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_by, granted_at, created_at, updated_at)
SELECT 'OPS', s.staff_no, j.role_code, 'V721', UNIX_TIMESTAMP() * 1000, NOW(), NOW()
FROM sys_ops_staff s,
     JSON_TABLE(IF(JSON_VALID(s.roles), s.roles, '[]'), '$[*]' COLUMNS (role_code VARCHAR(32) PATH '$')) j
WHERE s.deleted = 0
  AND j.role_code IS NOT NULL AND j.role_code <> ''
  AND NOT EXISTS (SELECT 1 FROM sys_role_member m
                  WHERE m.end_code = 'OPS' AND m.subject_no = s.staff_no AND m.role_code = j.role_code AND m.deleted = 0);

-- ② sys_role_member 里有、JSON 里没有的：撤销（与应用里撤销角色同一做法，逻辑删除）
UPDATE sys_role_member m
SET m.deleted = 1, m.updated_at = NOW(), m.updated_by = 'V721'
WHERE m.end_code = 'OPS' AND m.deleted = 0
  AND EXISTS (SELECT 1 FROM sys_ops_staff s
              WHERE s.staff_no = m.subject_no AND s.deleted = 0
                AND NOT JSON_CONTAINS(IF(JSON_VALID(s.roles), s.roles, '[]'), JSON_QUOTE(m.role_code)));

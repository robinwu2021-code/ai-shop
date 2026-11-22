-- 开放 API 试点授权种子（TDD-支付中心运营页面 §2.17，2026-11-22 用户：「自动增加，做为种子数据」）。
-- 试点：robin@neargo.ai 提交接入申请、neara@neargo.ai 审核（near-pay 规定提交人不得审核，所以拆成两个角色、分给两个人）。
-- 只新增、可重复执行；不改任何已有角色的授权，也不删账号原有的角色。账号不存在时整段不生效（其他环境无副作用）。
--
-- 角色（非内置，可在「角色与权限」页改授权或删除）：
--   OPEN_API_SUBMIT 开放API·接入提交：pay:app:view / pay:app:manage、pay:credential:view / pay:credential:manage
--     （提交申请、核验回调、签发密钥 / 上传商家公钥、生成平台密钥草稿）
--   OPEN_API_REVIEW 开放API·接入审核：pay:app:view / pay:app:approve、pay:credential:view / pay:credential:approve
--     （批准 / 驳回申请、启用应用、两人启用密钥、泄露应急锁定）
-- 授权按 perm_code 取功能点（菜单点 + 动作点一起），与 V686–V688 用的是同一组码；权限仍由 near-pay 用令牌自判。
-- 账号的有效角色以 sys_ops_staff.roles（JSON 数组）为准，sys_role_member 是它的镜像（与 OpsServiceImpl#syncRoleMember 同口径）。

INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at)
SELECT 'OPEN_API_SUBMIT', '开放API·接入提交', 'OPS', 0, 0, 900, NOW(), NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'OPEN_API_SUBMIT' AND end_code = 'OPS');
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at)
SELECT 'OPEN_API_REVIEW', '开放API·接入审核', 'OPS', 0, 0, 901, NOW(), NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'OPEN_API_REVIEW' AND end_code = 'OPS');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'OPEN_API_SUBMIT', fp.point_code, 'OPS', NOW(), NOW() FROM sys_function_point fp
WHERE fp.perm_code IN ('pay:app:view', 'pay:app:manage', 'pay:credential:view', 'pay:credential:manage')
  AND NOT EXISTS (SELECT 1 FROM sys_role_point rp WHERE rp.role_code = 'OPEN_API_SUBMIT' AND rp.point_code = fp.point_code AND rp.end_code = 'OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'OPEN_API_REVIEW', fp.point_code, 'OPS', NOW(), NOW() FROM sys_function_point fp
WHERE fp.perm_code IN ('pay:app:view', 'pay:app:approve', 'pay:credential:view', 'pay:credential:approve')
  AND NOT EXISTS (SELECT 1 FROM sys_role_point rp WHERE rp.role_code = 'OPEN_API_REVIEW' AND rp.point_code = fp.point_code AND rp.end_code = 'OPS');

-- 追加到账号的角色列表（原有角色保留）；roles 是 VARCHAR(255)，留余量不足时不动（改到页面上手工加）
UPDATE sys_ops_staff
SET roles = CASE WHEN roles IS NULL OR TRIM(roles) IN ('', '[]') THEN '["OPEN_API_SUBMIT"]'
                 ELSE CONCAT(SUBSTRING(TRIM(roles), 1, CHAR_LENGTH(TRIM(roles)) - 1), ',"OPEN_API_SUBMIT"]') END,
    updated_at = NOW()
WHERE username = 'robin@neargo.ai' AND deleted = 0
  AND (roles IS NULL OR roles NOT LIKE '%"OPEN_API_SUBMIT"%') AND CHAR_LENGTH(COALESCE(roles, '')) <= 230;
UPDATE sys_ops_staff
SET roles = CASE WHEN roles IS NULL OR TRIM(roles) IN ('', '[]') THEN '["OPEN_API_REVIEW"]'
                 ELSE CONCAT(SUBSTRING(TRIM(roles), 1, CHAR_LENGTH(TRIM(roles)) - 1), ',"OPEN_API_REVIEW"]') END,
    updated_at = NOW()
WHERE username = 'neara@neargo.ai' AND deleted = 0
  AND (roles IS NULL OR roles NOT LIKE '%"OPEN_API_REVIEW"%') AND CHAR_LENGTH(COALESCE(roles, '')) <= 230;

INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_by, granted_at, created_at, updated_at)
SELECT 'OPS', s.staff_no, 'OPEN_API_SUBMIT', 'V689', UNIX_TIMESTAMP() * 1000, NOW(), NOW() FROM sys_ops_staff s
WHERE s.username = 'robin@neargo.ai' AND s.deleted = 0 AND s.roles LIKE '%"OPEN_API_SUBMIT"%'
  AND NOT EXISTS (SELECT 1 FROM sys_role_member m WHERE m.end_code = 'OPS' AND m.subject_no = s.staff_no AND m.role_code = 'OPEN_API_SUBMIT');
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_by, granted_at, created_at, updated_at)
SELECT 'OPS', s.staff_no, 'OPEN_API_REVIEW', 'V689', UNIX_TIMESTAMP() * 1000, NOW(), NOW() FROM sys_ops_staff s
WHERE s.username = 'neara@neargo.ai' AND s.deleted = 0 AND s.roles LIKE '%"OPEN_API_REVIEW"%'
  AND NOT EXISTS (SELECT 1 FROM sys_role_member m WHERE m.end_code = 'OPS' AND m.subject_no = s.staff_no AND m.role_code = 'OPEN_API_REVIEW');

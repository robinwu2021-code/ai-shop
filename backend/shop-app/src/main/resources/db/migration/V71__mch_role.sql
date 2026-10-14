-- 商家自定义角色（B-11.10.2 扩展）+ 员工备注名。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 这推翻了一条写下来的决定
-- ─────────────────────────────────────────────────────────────────────────────
-- 《三端角色权限功能对齐清单》§十一 原本写着「不让商家自定义权限码」，
-- 理由是「角色是答案，权限码矩阵是把问题还给他」。2026-08-12 产品决定放开：
-- 六个预置角色装不下所有小店的分工（夜班店长、只管收银的、只对账的会计）。
--
-- **放开的是角色，不是边界** —— 见下面 perms 列的注释。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 预置角色为什么用 entity_no = '*' 而不是给每个商家复制一份
-- ─────────────────────────────────────────────────────────────────────────────
-- 复制一份的话，**新增一个权限码时要回头刷全部商家的预置角色** ——
-- 刷漏一个，那家店的店长就少一样能力，而且不报错。
-- 全局一份 + 只读，语义永远与 BizPerms 一致；商家要改就「复制为自定义角色」，
-- 那是一个显式动作，改的也是他自己的副本。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 不注册数据域
-- ─────────────────────────────────────────────────────────────────────────────
-- 与 mch_account / mch_store_role / mch_staff_log 同处理：读写一律
-- DataScopeContext.executeWithoutScope + 显式按 entity_no 过滤。
-- ⚠️ 查询时必须带 `entity_no IN (当前商家, '*')` —— 少了 '*' 预置角色就全没了，
-- 少了当前商家则自定义角色全没了，两种漏法都表现为「权限突然变少」。
CREATE TABLE IF NOT EXISTS mch_role
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL COMMENT '商家主体号。预置角色用星号，全局共享且只读；自定义角色属于这家商家',
    role_code VARCHAR(32) NOT NULL COMMENT '角色码。预置：OWNER/MANAGER/CLERK/PICKER/COURIER/CS；自定义：R+业务键',
    name VARCHAR(32) NOT NULL COMMENT '显示名，如「夜班店长」。预置角色也有，端上直接展示',
    perms TEXT NOT NULL COMMENT 'JSON 数组：权限码。⚠️ **自定义角色不得含 biz:store:admin**（那是「管人」的码，授出去等于让人能改所有人的授权），后端校验 + 测试锁住；OWNER 的 ["*"] 只属于预置',
    builtin TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1 = 平台预置，只读；改要走「复制为自定义角色」',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mch_role (entity_no, role_code),
    KEY idx_mch_role_entity (entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='商家角色：6 个平台预置（只读）+ 商家自定义';

-- 预置六角色。**必须与 BizPerms.ROLE_PERMS 逐条相同** ——
-- 不一致的后果是「界面上说店长能改库存，实际打不通」，而两边各自看都对。
-- `BizRoleSeedTest` 解析这段 INSERT 与 BizPerms 对账，漏改一处就红。
INSERT INTO mch_role (entity_no, role_code, name, perms, builtin, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('*', 'OWNER', '老板', '["*"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('*', 'MANAGER', '店长', '["biz:receive","biz:verify","biz:ship","biz:order:view","biz:stock","biz:goods","biz:campaign","biz:review","biz:aftersale","biz:customer","biz:store"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('*', 'CLERK', '店员', '["biz:receive","biz:verify","biz:ship","biz:order:view","biz:stock"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('*', 'PICKER', '理货员', '["biz:receive","biz:stock"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('*', 'COURIER', '配送员', '["biz:ship","biz:order:view"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('*', 'CS', '客服', '["biz:review","biz:aftersale","biz:order:view"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

-- 员工备注名。
--
-- 列表此前只有脱敏号 `139****1111` —— **三个人以后谁也分不清**，
-- 而「把张三提成店长」这件事，事后翻审计看到的也只是一串尾号。
ALTER TABLE mch_account
    ADD COLUMN IF NOT EXISTS display_name VARCHAR(32) DEFAULT NULL
        COMMENT '备注名（老板自己写的，如「小张」）。列表与审计都显示它；为空时回落脱敏号';

-- 角色定义的变更也要留痕（改角色的权限 = 一次性改掉所有持有者的能力，
-- 比给某个人加角色影响更大）。而这类记录**没有「被操作的员工」**，
-- 所以把 V68 建表时的 NOT NULL 放开 —— 当时只想到了「对某个人做的事」。
ALTER TABLE mch_staff_log
    MODIFY COLUMN target_account_no VARCHAR(64) DEFAULT NULL
        COMMENT '被操作的员工。角色定义变更（ROLE_CREATE/UPDATE/DELETE）没有具体的人，为空',
    MODIFY COLUMN action VARCHAR(24) NOT NULL
        COMMENT 'STAFF_ADD/ENABLE/DISABLE 对人 · ROLE_GRANT/REVOKE 给某人授权 · ROLE_CREATE/UPDATE/DELETE 改角色定义本身',
    -- 与 mch_store_role.role 同样的理由：这一列也要放得下自定义角色码（22 字符）。
    -- **写审计是吞异常的**（业务已经成功，不能因为记账失败回滚），
    -- 所以列宽不够的表现是「日志里什么都没有」而不是报错 —— 测试是唯一能发现它的地方。
    MODIFY COLUMN role VARCHAR(32) DEFAULT NULL
        COMMENT '涉及的角色码，取值域同 mch_store_role.role。加人与启停为 NULL';

-- 授权行的 role 列要放得下自定义角色码。
--
-- 预置角色的码是 MANAGER/CLERK 这类词（最长 7 个字符），当初 VARCHAR(16) 绰绰有余；
-- 而自定义角色的码是业务键（R + 时间戳 + 序 + 随机 = 22 字符）——
-- **不放开的话自定义角色建得出来、授不出去**，报的还是「系统开小差」。
-- 与 mch_role.role_code 对齐到 32。
ALTER TABLE mch_store_role
    MODIFY COLUMN role VARCHAR(32) NOT NULL
        COMMENT '角色码。预置：MANAGER 店长 / CLERK 店员 / PICKER 理货员 / COURIER 配送员 / CS 线上客服；自定义：mch_role 里本商家的那些。OWNER 不在这里，他不需要逐店授权。一人一店可多行，权限取并集';

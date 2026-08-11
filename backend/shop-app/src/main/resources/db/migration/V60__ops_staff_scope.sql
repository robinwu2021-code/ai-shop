-- 运营员工的数据域三列（矩阵 §2.3：权限模型 = RBAC + 数据域）。
--
-- 此前后端**只有 RBAC 那一半**：sys_ops_staff 有 roles，没有任何归属键。
-- 而 ops-web 的 IAM 页一直有「配置数据域」的入口，auth.ts 的 scopeOf() 也一直
-- 带着它往后端发，注释还写着「越权拦截以后端为准」——
-- 那句话描述的是一个不存在的保护。
--
-- ⚠️ 本批**只存不用**：三列会被写入，但各域的查询还没有按它裁剪。
--    UI 上已标明「配置已保存，裁剪尚未生效」——
--    存了不用比不存更危险，运营会以为限定住了而实际没有。
--
-- 可空是语义的一部分：**空 = 不限定（全量）**，不是「还没配」。
-- 超管、商品运营本来就该是全量。
ALTER TABLE sys_ops_staff
    ADD COLUMN IF NOT EXISTS merchant_no VARCHAR(64) DEFAULT NULL COMMENT '商家数据域，空=不限定。商家运营（BD）用',
    ADD COLUMN IF NOT EXISTS community_no VARCHAR(64) DEFAULT NULL COMMENT '社区数据域，空=不限定。社区运营用',
    ADD COLUMN IF NOT EXISTS pickup_no VARCHAR(64) DEFAULT NULL COMMENT '自提点数据域，空=不限定',
    ADD COLUMN IF NOT EXISTS last_login_at BIGINT(20) DEFAULT NULL COMMENT '最近登录时刻（毫秒）。停用一个长期没登录的账号之前要知道这个';

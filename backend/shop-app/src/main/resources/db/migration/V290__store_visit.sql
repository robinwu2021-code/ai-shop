-- 门店访问埋点（append-only）：扫码落地那一刻记一行，**不要求登录**。
--
-- 为什么要有它：获客漏斗「扫码 → 进店 → 注册 → 首单」的后三段今天就能从
-- mkt_attribution_log 算出来（归因逐条留痕、首单回填），唯独第一段没有采集 ——
-- /mp/store/by-code 只解析不落行，而 /mp/store/{no}/enter 要求登录。
-- 于是「扫了码但还没注册的人」恒为 0，而那恰恰是「这批贴纸有没有用」的答案。
-- DashboardServiceImpl.funnel() 的注释写的就是这件事：前两环缺事件表。
--
-- ⚠️ 撞号风险：并行会话同一目录，本机 H2 测试不跑 Flyway，撞号只在下一次真库启动
-- 才暴露（Found more than one migration with version 290），届时改号并 clean package。
--
-- **为什么不复用现有的表**：
--   mkt_attribution_log —— 它回答「这个用户属于谁」（一人一条有效、30 天窗口）；
--     访问要回答「这家店最近被扫了多少次」。混用会让归因窗口被扫码反复刷新。
--   mbr_member_source  —— 形状最像（source_type/store_no/occurred_at/is_first），
--     但会员行必须挂在一个 user 上、且首笔支付或手工入会才建。匿名访客没有 user，
--     硬塞进去等于把「会员」的定义改成「扫过码的人」。
CREATE TABLE IF NOT EXISTS mkt_store_visit
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    visit_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL COMMENT '商家主体。**粒度是主体不是门店** —— 店铺码是一主体一码（mch_entity.store_code），物理上分不出是哪家分店',
    store_code VARCHAR(64) DEFAULT NULL COMMENT '扫的是哪个码。留着是为了将来一码多物料时能分辨来源',
    store_no VARCHAR(64) DEFAULT NULL COMMENT '为将来「一店一码」预留；现在恒为空，届时不用改表',
    -- ★ 可空是这张表的**要点不是缺陷**：空 = 还没登录的访客，
    --   而那正是漏斗最宽、也唯一没被采集过的那一层。设成 NOT NULL 等于把要测的东西测没。
    user_no VARCHAR(64) DEFAULT NULL COMMENT '登录用户；**为空 = 匿名访客**（漏斗第一层就是靠它区分）',
    device_id VARCHAR(64) DEFAULT NULL COMMENT '端上生成并持久化。UV 去重与防刷都靠它 —— 匿名访客没有 user_no，只能按它算人',
    ip VARCHAR(64) DEFAULT NULL,
    ua_hash VARCHAR(64) DEFAULT NULL COMMENT 'UA 摘要，不存原文（原文是可用于指纹的个人信息）',
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_store_visit_no (visit_no),
    -- 聚合恒按 (主体, 时间区间) 走，这条索引就是给它的
    KEY idx_store_visit_entity_at (entity_no,at),
    KEY idx_store_visit_device_at (device_id,at)
) COMMENT='门店访问埋点（append-only）：扫码落地即记，匿名也记';

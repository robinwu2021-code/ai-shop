-- 结算侧补币种（S6 · TDD-支付域-数据库设计（目标态）§2.4）
--
-- **这一条与多区域无关，今天就该补。**单币种下它也是对的，
-- 只是没有第二个币种时看不出错 —— 而错的形状是「把 100 台币
-- 当成 100 人民币加进合计」，不报错、只是数字不对。
--
-- 为什么现在补而不是等接第二个市场：
-- 越晚补，回填的行数越多，而回填期间新写入的行还在继续用默认值。
--
-- 回填 CNY 是安全的：2026-09-02 查过线上，现有数据确实全是人民币
-- （sys_setting 里的市场配置只有 CN 一条）。
-- **但回填之后要立刻让写入路径显式赋值** —— 靠默认值活着的列，
-- 在第二个币种出现时会静默地全部写成人民币。
-- stl_payment.currency 就是活例子：那一列 V1 就有、默认 CNY，
-- 而生产代码里没有任何一处给它赋值。
--
-- 不写 ENGINE / CHARSET / COLLATE：跟随库默认（同 V285、V286）。
ALTER TABLE stl_bill
    ADD COLUMN currency VARCHAR(8) NOT NULL DEFAULT 'CNY'
    COMMENT '记账币种。**决定这张单能不能与别的单相加** —— 跟随主体所在市场';

ALTER TABLE stl_settle_batch
    ADD COLUMN currency VARCHAR(8) NOT NULL DEFAULT 'CNY'
    COMMENT '记账币种。一批只能装同币种的结算单，见下面的唯一键';

-- 批次唯一键补上币种（S9 修正版）。
--
-- **这道键不是新加的，V280 建表时就有**：
--   uk_stl_batch_period (entity_no, pay_channel, period_from, tenant_no, deleted)
-- 它防的是「截批任务重跑一次就把同一区间的单开出两个批」。
--
-- ⚠️ 写这条迁移时我先断言「今天没有任何约束保证同一区间只有一个批次」，
-- 那是错的 —— 起因是用 `grep -A30` 看表定义，而这张表有 27 列，
-- 约束在 30 行之外。**窗口太小导致的假结论**，且它差点变成一条
-- 与现有约束同名的 ADD CONSTRAINT，那会让生产迁移直接失败。
--
-- 真正要做的是**把币种加进这道已有的键**：一批只能装同币种的单。
-- 保留 deleted 在键里 —— 那是软删除下让已删批次不占键位的既有语义，
-- 去掉它会让「删掉重开」这条路走不通。
--
-- 单币种下这一改不改变任何行为（所有单都是 CNY，还是同一批），
-- 它是接第二个市场之前必须先成立的前提。
-- 用 DROP INDEX ... ON / CREATE UNIQUE INDEX 而不是 ALTER ... ADD CONSTRAINT：
-- **测试库的 schema 生成器只认前者**。用 ADD CONSTRAINT 的话，
-- 这道键会在生成的 H2 schema 里<b>静默消失</b> —— 迁移在生产是对的，
-- 而测试库少一道约束，于是「防重复开批」这件事在测试里根本测不到。
-- （生成器自己的注释就写着「约束由 CREATE UNIQUE INDEX 重建」。）
DROP INDEX uk_stl_batch_period ON stl_settle_batch;

CREATE UNIQUE INDEX uk_stl_batch_period
    ON stl_settle_batch (entity_no, pay_channel, currency, period_from, tenant_no, deleted);

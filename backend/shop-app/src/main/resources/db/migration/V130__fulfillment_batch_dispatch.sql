-- 到货批次的**调度属性**（P-5.1.1 到货批次与配车）。
--
-- 为什么扩 ful_batch 而不是新建一张「平台批次」表：
-- ful_batch（V1 基线）已经是「一个自提点某一天到的一堆货 + 签收状态」，
-- 正是 P-5.1.1 要的对象，只缺三个调度属性。新建一张语义相同的表之后，
-- B 端将来做「站长按堆签收」时不知道该写哪一张 —— 同一件事两处真源，
-- 而两处的数字分岔时没有任何报错。
--
-- **件数与商家数刻意不加列**：它们从 ord_sub_order 现算。
-- 存一份计数器的代价是「总览说 3 单、点进去只有 2 单」（B-6.0 的原话），
-- 而这种不一致既不报错也无从复现。

ALTER TABLE ful_batch
    ADD COLUMN community_no VARCHAR(64) DEFAULT NULL COMMENT '目的社区。平台是跨社区调度的，按社区筛是这一页的主筛项',
    ADD COLUMN plan_arrive_at BIGINT(20) DEFAULT NULL COMMENT '计划到货时间戳。与 arrive_date（按天分批的自然键）分开：一天可能分早晚两车',
    ADD COLUMN vehicle VARCHAR(64) DEFAULT NULL COMMENT '车次/司机标识。一期人肉填，二期接运力系统（ADR-005 §5）';

-- 状态从两态扩到四态：PLANNED → DISPATCHED → ARRIVED → SIGNED。
--
-- 两个旧值是新四态的子集（PENDING≈PLANNED、RECEIVED≈SIGNED），下面把存量映射过去。
-- **今天这张表没有任何写入方**（FulBatch 实体在，Mapper 与调用方都不存在），
-- 所以实际零行 —— 但迁移照写：「今天没有行」不是不写的理由，下一个人不会去数。
--
-- 为什么必须四态而不是两态：中间两态是**责任分界**。
-- 只有 PLANNED/RECEIVED 时，「车还没发」与「车发了但没到」无法区分，
-- 而货丢在哪一段恰恰是自提履约里最常见的纠纷。
UPDATE ful_batch SET status = 'PLANNED' WHERE status = 'PENDING';
UPDATE ful_batch SET status = 'SIGNED' WHERE status = 'RECEIVED';

-- 缺省值与列注释一起改。**注释也是真源的一部分**：不改的话，
-- 建表语句上写着「PENDING/RECEIVED」而代码里跑的是四态，
-- 下一个读 DDL 的人会照着两态去写查询（改列注释是修正语义的主要手段，见 scripts/lib/ddl.mjs 抬头）。
ALTER TABLE ful_batch
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PLANNED' COMMENT 'PLANNED=计划中 / DISPATCHED=已发车 / ARRIVED=已到货 / SIGNED=已签收。有序推进，不许跳步';

-- 第 5 步 · 5.5：「一张订单同时只有一张进行中售后」由库保证（集群规则，方案 §4）
--
-- 此前靠「先查后插」：两个实例同时收到同一单的两次申请，都查不到进行中的那张，各插一张。
-- 改为申请时对子单做 CAS 占位：open_after_sale_no 为空（或就是自己）才写入，写 0 行即冲突；
-- 售后进 CLOSED / REJECTED / REFUNDED 时释放，被驳回后申请介入时重新占位。
--
-- **实体里故意不映射这一列**：订单流程会先读子单、再 updateById —— 实体若带着它，
-- 会把读到的旧占位原样写回去，占位就再也释放不掉。只由 AfterSaleSlotMapper 的专用 SQL 读写。
--
-- 加列 + 回填存量进行中的售后；向后兼容（旧代码不读不写），可重复执行。

ALTER TABLE ord_sub_order
    ADD COLUMN open_after_sale_no VARCHAR(40) DEFAULT NULL COMMENT '进行中的售后单号（CAS 占位）；空 = 可申请';

UPDATE ord_sub_order s
SET open_after_sale_no = (SELECT MAX(a.after_sale_no) FROM ord_after_sale a
                          WHERE a.sub_order_no = s.sub_order_no
                            AND a.status NOT IN ('CLOSED', 'REJECTED', 'REFUNDED'))
WHERE s.open_after_sale_no IS NULL;

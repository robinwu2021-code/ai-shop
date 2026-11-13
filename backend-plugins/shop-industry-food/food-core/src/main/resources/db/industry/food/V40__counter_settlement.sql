-- 柜台后付结账（TDD-餐饮柜台后付结账）：结算单区分来源。空或 H5 = 顾客自助先付（历史行全是这种）；COUNTER = 柜台整桌后付。
-- 顾客 H5 的恢复任务只扫非 COUNTER 的结算单。
ALTER TABLE fnb_settlement ADD COLUMN kind VARCHAR(16) NULL;
-- 柜台结账确认收齐后记下实收与找零（来自核心实收），读结算单时直接给出；其余结算单为空。
ALTER TABLE fnb_settlement ADD COLUMN received_minor BIGINT NULL;
ALTER TABLE fnb_settlement ADD COLUMN change_minor BIGINT NULL;

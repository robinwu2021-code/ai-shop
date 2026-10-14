-- 订单状态机 · 步 3：售后「退款中」拆义（TDD-订单状态机 AC9）
--
-- 此前 REFUNDING 一身三职：退货退款「同意后等寄回」「已寄回等确认」与真正的「钱在路上」都叫它，
-- 只能靠 express_no 有没有值来猜是哪一种 —— 三端各猜各的，财务队列与重试任务也要各写一遍排除。
-- 现在拆成 WAIT_RETURN（待寄回）/ RETURNING（已寄回待确认），REFUNDING 只剩「钱在路上」。
--
-- 只迁「退货退款 + 没有责任方」的存量：有责任方的是平台裁决过、在等财务执行回退分账的单，
-- 它们的钱已经决定要退，保持 REFUNDING 不动。纯数据迁移，可重复执行（条件已排除已迁的行）。

UPDATE ord_after_sale
SET status = 'WAIT_RETURN'
WHERE status = 'REFUNDING'
  AND type = 'RETURN_REFUND'
  AND liability IS NULL
  AND (express_no IS NULL OR express_no = '');

UPDATE ord_after_sale
SET status = 'RETURNING'
WHERE status = 'REFUNDING'
  AND type = 'RETURN_REFUND'
  AND liability IS NULL
  AND express_no IS NOT NULL
  AND express_no <> '';

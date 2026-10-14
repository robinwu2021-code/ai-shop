-- S15 · 运营手工动钱的写操作接幂等
--
-- 设计册里写的是「真正动钱的写操作一处都没有幂等」。量了一遍，**那句话不对**：
--   · 状态机型（放款、确认、提现审批、分账回退）—— 都先判终态再动手，已幂等；
--   · 源单驱动型（欠款计提按 source_no、积分发放按台账行、退款按售后单号）—— 也已幂等。
--
-- 真正的缺口窄得多，而且形状很具体：**运营手工输入金额的累加写**。
-- 它没有状态可守（流水表只增不改），也没有源单可依（金额是人当场填的），
-- 于是重复提交会实打实地记两笔：
--   · POST /ops/admission/deposits/{merchantNo}/txns —— 保证金缴纳/退还/扣划
--   · POST /ops/debts/{entityNo}/offset-by-deposit  —— 用保证金抵欠款
--
-- 前者最直接：金额由请求带进来，点两次就是两倍。
--
-- 幂等键放在流水行上而不是走 Idempotency-Key 头：
-- 那个执行器**没带 key 时直接放行**，接上了也可能一直不生效，
-- 而「以为接了其实没接」比没接更糟。放在列上有唯一索引兜着，漏传当场 400。

ALTER TABLE mch_deposit_txn
    ADD COLUMN request_no VARCHAR(64) DEFAULT NULL
        COMMENT '这次操作的幂等键，由发起方生成。重复提交撞唯一索引后按已完成返回';

ALTER TABLE mch_debt_txn
    ADD COLUMN request_no VARCHAR(64) DEFAULT NULL
        COMMENT '同上。欠款计提走 source_no 幂等，这一列只给手工抵扣用';

-- 唯一索引允许多个 NULL（两个方言一致），所以历史行不受影响。
-- 生产这两张表都是 0 行（2026-09-02 查过），这里没有存量要顾。
CREATE UNIQUE INDEX uk_deposit_txn_request ON mch_deposit_txn (merchant_no, request_no);
CREATE UNIQUE INDEX uk_debt_txn_request ON mch_debt_txn (entity_no, request_no);

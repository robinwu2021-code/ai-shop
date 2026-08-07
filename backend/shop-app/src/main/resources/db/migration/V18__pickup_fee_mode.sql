-- 自提点：补「平台提供」这一档，并给两种计费口径加判别列。
--
-- 背景（2026-08-06 决策）：自提点分两种承接方式 ——
--   · 商家自行解决（type=STORE）：用自己的门店，平台不收履约服务费
--   · 平台提供（type=PLATFORM）：**费率线下逐点协商，由运营平台录入**
-- 原来的 type 只有 STORE/NEIGHBOR，表达不了「这个点是平台提供的」。
--
-- 为什么加 fee_mode 而不是删掉其中一个费用列：
-- 既然费率是**逐点线下谈**的，就必然出现有的点谈成按件、有的谈成按成交额抽成。
-- 硬统一成一种，运营在谈判里就没有筹码了。但两列并存而没有判别列是明确的 bug ——
-- 结算侧只能猜用哪一个，猜错就是给自提点少付或多付钱。判别列把这件事显式化。

ALTER TABLE cmt_pickup_point
    ADD COLUMN fee_mode VARCHAR(16) NOT NULL DEFAULT 'NONE'
        COMMENT 'NONE/PER_ITEM/RATE：用哪一种计费口径。STORE 与 NEIGHBOR 恒为 NONE';

-- type 的取值域从 STORE/NEIGHBOR 扩到三档。列本身是 VARCHAR 无约束，改的是注释口径。
ALTER TABLE cmt_pickup_point
    MODIFY COLUMN type VARCHAR(16) NOT NULL DEFAULT 'STORE'
        COMMENT 'STORE=商家自有门店(不收费) / NEIGHBOR=邻居家(零报酬) / PLATFORM=平台提供(线下协商费率)';

-- owner_ref 同步扩档：平台提供的点没有外部承接方
ALTER TABLE cmt_pickup_point
    MODIFY COLUMN owner_ref VARCHAR(64) NULL
        COMMENT 'STORE=merchant_no / NEIGHBOR=user_no / PLATFORM=NULL';

-- 存量数据：现有点全是商家门店或邻居家，两者都不收平台履约费，保持 NONE 即可。
-- 不写 UPDATE —— DEFAULT 'NONE' 已经覆盖，多余的 UPDATE 只会在重放时多一次全表扫描。

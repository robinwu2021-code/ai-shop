-- 商家补录村级区划。
--
-- 背景：官方村级数据停在 2023-06-30（统计局 2024-10 起不再公开），
-- 之后新增或改名的村/社区没有任何官方渠道能拿到。而商家要按村圈经营范围，
-- 缺一个村就等于那一片做不了生意 —— 让他等平台下次更新，
-- 而平台的「下次更新」在源头停发之后根本不会到来。
--
-- 两个新列各解决一件事：
--
-- · source：这一行是官方的还是商家录的。**定期更新时只能动 OFFICIAL 那批** ——
--   把商家录的当过期数据清掉，是把他自己填的东西删了，而他不会收到任何通知。
--
-- · owner_entity_no：**待运营确认期间的可见范围**。非空 = 只有这家店看得到。
--   不这样做只有两个选择：录完立刻全网可见（一家店打错字污染全平台，
--   而其他商家看到一个不存在的村会以为是自己记错了），
--   或者压在待审队列里不给用（那商家今天就做不了这单生意）。
--   现在是「他自己马上能用，别人看不到」，运营确认后置 NULL 转为全网共享。
ALTER TABLE sys_region
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'OFFICIAL' COMMENT 'OFFICIAL(官方数据) / MERCHANT(商家补录)' AFTER level,
    ADD COLUMN owner_entity_no VARCHAR(64) DEFAULT NULL COMMENT '待运营确认期间只对这家店可见；确认通过后置 NULL 转为全网共享' AFTER source;

-- 查询侧每次下钻都要带上「官方的 + 我自己的」这个条件，走索引
CREATE INDEX idx_sys_region_owner ON sys_region (owner_entity_no);

-- 存量 44703 + 620573 行全是官方数据，DEFAULT 已经兜住，这里不需要 UPDATE。
-- 写出来是为了下一个人不用去翻 DEFAULT 是什么：**存量即 OFFICIAL**。

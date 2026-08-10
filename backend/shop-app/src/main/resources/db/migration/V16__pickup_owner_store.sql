-- 自提点归属改到门店。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 现状：`owner_ref` 在 type=STORE 时存的是 entity_no
-- ─────────────────────────────────────────────────────────────────────────────
-- 于是「这个自提点属于哪家店」表达不了。多门店之后这是个真问题：
-- 顾客在文三路店门口的自提点下单，货该从文三路店出，而系统只知道「属于这个主体」。
--
-- 更要紧的是它**卡住了下一步**：下单落哪家店今天恒为默认店
-- （OrderServiceImpl 里那句 defaultStoreNo）。要让它按履约来选，
-- 最自然的依据就是「顾客选的自提点属于哪家店」—— 而这条信息现在不存在。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 迁移到默认门店，而不是留空
-- ─────────────────────────────────────────────────────────────────────────────
-- 存量自提点全是单店商家建的，那家店就是它的默认店 —— 语义上是等价改写，
-- 不是猜。留空的话读侧要处处判空，而「空」在这里没有真实含义。
--
-- NEIGHBOR / PLATFORM 两类不动：前者存 user_no，后者本来就是 NULL。
-- 这一列本来就是多态的（列注释里写着），这次只改 STORE 那一支的含义。
UPDATE cmt_pickup_point p
JOIN mch_store s ON s.entity_no = p.owner_ref AND s.is_default = 1 AND s.deleted = 0
SET p.owner_ref = s.store_no
WHERE p.type = 'STORE' AND p.owner_ref IS NOT NULL AND p.deleted = 0;

ALTER TABLE cmt_pickup_point MODIFY COLUMN owner_ref VARCHAR(64) DEFAULT NULL
    COMMENT 'STORE=store_no（V16 起，此前是 entity_no）/ NEIGHBOR=user_no / PLATFORM=NULL';

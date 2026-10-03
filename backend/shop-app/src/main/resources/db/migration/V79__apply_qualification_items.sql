-- 入驻申请的资质改为结构化，把断了的那条链接上。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 断在哪
-- ─────────────────────────────────────────────────────────────────────────────
-- b-app 入驻收 licenses（图片 URL 数组）→ 存进 mch_entity_apply.qualifications（JSON）
--   ↓ ✗ 审核通过时**不转存**
-- mch_qualification（结构化表）  ← 实测 0 行
--   ↓
-- 上架闸门 hasExpiredQualification / 类目授权码  ← 恒不触发
--
-- 两个闸门都写好了，都从不生效。而唯一写 mch_qualification 的 ops 接口
-- 在 ops-web 里没有任何调用方，所以那张表实际恒空。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么要加一列，而不是直接转存旧列
-- ─────────────────────────────────────────────────────────────────────────────
-- 旧列 qualifications 是**纯 URL 数组**，而 mch_qualification 需要
-- 类型 / 证号 / 有效期 —— 光有图片 URL 填不出这些列，转存无从下手。
-- 有效期尤其关键：QualificationExpiryJob 扫的就是它，没有它资质永远不会过期。
--
-- **不删旧列**：存量申请单的数据在里面。删了之后，回看一张历史申请单
-- 「他当时到底传了什么」就再也答不上来 —— 而那正是审核纠纷要查的东西。

-- 用 TEXT 而不是 JSON：与同表的 qualifications 一致，也与全库其余「JSON 数组」列一致。
-- 用 JSON 类型踩过一次 —— H2（测试库）会把绑定进去的字符串当成一个 JSON **字符串值**
-- 再包一层引号，读出来是双重编码，解析必然失败。MariaDB 不会，所以这个故障
-- **只在测试里出现**，本地跑真库看不到。
ALTER TABLE mch_entity_apply ADD COLUMN qualification_items TEXT DEFAULT NULL
    COMMENT '结构化资质：[{type,code,imageUrl,expireAt,issuer}]。
             type 取值同 mch_qualification.qual_type（BUSINESS_LICENSE/FOOD_PERMIT/...）。
             expireAt 为 null = 长期有效（**不要用 0 或一个很大的数字冒充**，
             那会让过期扫描把它当成已过期或永不过期，两种都错）。
             与旧列 qualifications(URL数组) 并存 —— 存量在旧列里，不迁移不删除';

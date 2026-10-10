-- 进件授码合一（商品域-优化总方案 批 B）。
--
-- 在此之前，「审核通过」与「授予经营类目」是**两个动作**，中间存在一个状态：
-- 通过了，但一个码都没授。商家收到通过通知、进去建品、点上架被拒，
-- 看到的是「你还没有资质授权」—— 去哪申请没人告诉他。
-- 这与类目树补齐方案 §五① 记的是同一类事：**一个只会拒绝的校验比没有校验更糟，
-- 它看起来在工作。**
--
-- 根因在字段上：`mch_entity_apply.category` 是一段自由字符串（商家填「食品」），
-- 而上架闸门读的是 `sys_auth_code.code`（`FRESH_VEG`）。两者之间那次翻译
-- 发生在运营的脑子里，**没有任何留痕** —— 事后既查不出当初批的是什么，
-- 也没法回答「为什么这家店上不了架」。

-- ── 1. 申请单上记下「批了哪些码」 ──────────────────────────────
--
-- 与 `category` 并存而不是替换：那一列是**商家自己的说法**（「我卖食品」），
-- 这一列是**平台的裁定**（`["FRESH_VEG","PACKAGED_FOOD"]`）。
-- 合成一列的话，翻译前后就分不开了，而追溯要的恰恰是这两者的差。
ALTER TABLE mch_entity_apply
    ADD COLUMN category_codes TEXT DEFAULT NULL
        COMMENT 'JSON 数组：审核通过时授予的经营类目编码。与 mch_entity.category_codes 同一套值，这里留的是「当初批的」' AFTER category;

-- ── 2. 历史数据：只回填**能确定的那部分** ─────────────────────
--
-- 按 `category` 与授权码名称**精确相等**回填，其余留空。
--
-- 不做模糊匹配（LIKE '%食品%'）：把「食品包装机械」批成食品经营，
-- 是一次没人复核的越权授权 —— 而它看起来完全正常。留空的那些由运营
-- 在下一次进入审核页时逐个确认，那一步本来就该有人。
UPDATE mch_entity_apply a
JOIN sys_auth_code c ON c.name = a.category AND c.enabled = 1
SET a.category_codes = CONCAT('["', c.code, '"]')
WHERE a.category IS NOT NULL
  AND a.category <> ''
  AND a.category_codes IS NULL;

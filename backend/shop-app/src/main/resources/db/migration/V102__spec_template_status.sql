-- 规格模板的启停（矩阵 P-3.4 / E27，TDD-运营端商品治理补齐 §2.4）。
--
-- 为什么不用 BaseEntity 的逻辑删除（deleted）承载：模板停用是**常态操作**
-- （换季、类目调整、一套规格暂时不推），停了还要能恢复。
-- 逻辑删除做不了「恢复」这一半，而真删掉之后，历史商品记下的 template_no
-- 就再也解释不了「这个 optionCode 当初是什么意思」。
--
-- 商家侧 specTemplates() 同步只查 ACTIVE ——
-- **归档了商家还能选，等于没归档**，而运营会以为自己把那套错的规格下线了。

ALTER TABLE prd_spec_template
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED（归档）';

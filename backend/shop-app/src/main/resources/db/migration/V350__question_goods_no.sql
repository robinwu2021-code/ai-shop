-- 问答挂到商品上（TDD-C 端商品详情页·内容丰富度 §3.3）。
--
-- `cnt_question` 此前只有 `sku_no`：那是运营端回答列表的口径（一条问题针对哪一个规格），
-- 而买家是**在商品页上问**的 —— 他不知道也不关心自己看的是哪个 SKU。
-- 只按 sku 查的话，同一件商品的问答会按规格散开，详情页上「大家还问」只能显示其中一份。
--
-- 存量为 0（线上 cnt_question 一行都没有），所以不需要回填。
ALTER TABLE cnt_question
    ADD COLUMN goods_no VARCHAR(64) DEFAULT NULL
        COMMENT '所属商品。买家在商品页提问，按它查；sku_no 仍留着，运营端按规格看';

CREATE INDEX idx_cnt_question_goods ON cnt_question (goods_no, status);

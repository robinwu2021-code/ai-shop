-- 代商家进件（三期）：申请单上记「谁填的」与「商户本人什么时候同意的协议」。
--
-- **为什么是两列而不是一列。** 代填与未同意协议不是同一件事：
--   · 商户自己提交的单子 submitted_by 为 NULL、agreed_at 有值；
--   · 运营代填的单子 submitted_by 有值、agreed_at 为 NULL（运营不能替人勾）；
--   · 商户后来自己补勾了，submitted_by 仍然留着 —— 它是历史，不该被抹掉。
-- 合成一列的话，第三种状态就没地方放，而那恰恰是代建出来的店最终该到的状态。
--
-- **agreed_at 是这套系统第一次真的记录协议同意。** 今天 B 端登录页那一勾
-- 一路传到 AuthService.LoginCommand.agreed 就没了 —— AuthServiceImpl 一次都没引用它。
-- 所以这一列对商户自填的路也有意义，不只是给代填用的。
--
-- **刻意不回填存量。** 存量申请单谁也答不出「这个人当时勾没勾」——
-- 按「反正都是自己提交的所以算同意」回填，等于凭空造一条法律事实。
-- 代价是存量单子的 agreed_at 全为 NULL，与「代填未补勾」在数据上不可分；
-- 要分得开就看 submitted_by：它为 NULL 的才是存量自填单。

ALTER TABLE mch_entity_apply
    ADD COLUMN submitted_by VARCHAR(64) NULL
    COMMENT '代填人 user_no。商户自己提交时为 NULL；代填后即便商户补勾也保留';

ALTER TABLE mch_entity_apply
    ADD COLUMN agreed_at BIGINT NULL
    COMMENT '商户本人同意《商家服务协议》的时刻（毫秒）。NULL=尚未同意，运营不能代勾';

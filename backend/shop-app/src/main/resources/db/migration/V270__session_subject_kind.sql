-- 会话主体的「id 属于哪张表」，从约定变成结构。
--
-- 起因（2026-08-28 A7 前置）：B 端令牌池要独立出来（btk_），而 B 端登录有两条路：
--   /biz/auth/staff-login  主体是 mch_account.mch_account_no（形如 SF-M0001）
--   /biz/auth/login        主体是 usr_account.user_no（形如 U2026...）
-- 生产实测 9 个商家账号里 8 个只存在于 B 端、没有 usr_account —— 所以
-- 「统一用 user_no」行不通；而「还不是商家的人」又没有 mch_account_no，
-- 「统一用 mch_account_no」同样行不通。两种都得认。
--
-- 两种都认之后，解析靠什么区分？此前的答案是「号段恰好不撞」——
-- MerchantTokenAuthFilter 自己的注释就写着「那是约定不是结构保证」。
-- 在鉴权里，撞号意味着把会话解析成另一个人，是最坏的一类错误。
--
-- 所以显式记下来。realm（哪个池）与 subject_kind（id 在哪张表）此前挤在
-- LoginUser.realm 一个字段里 —— 它们是两件事：一个 btk_ 会话的主体既可能是
-- 商家账号，也可能是还没开店的人。
--
-- 默认 'USR' 使存量行保持现状：三张表现在都是空的（会话刚从 ehcache 切过来），
-- 所以这个默认值实际不会命中任何行；写它只是为了让这一列 NOT NULL 而不必回填。
ALTER TABLE usr_session ADD COLUMN subject_kind VARCHAR(8) NOT NULL DEFAULT 'USR'
  COMMENT '主体 id 属于哪张表：USR=usr_account MCH=mch_account OPS=sys_ops_staff';
ALTER TABLE mch_session ADD COLUMN subject_kind VARCHAR(8) NOT NULL DEFAULT 'USR'
  COMMENT '主体 id 属于哪张表：USR=usr_account MCH=mch_account OPS=sys_ops_staff';
ALTER TABLE ops_session ADD COLUMN subject_kind VARCHAR(8) NOT NULL DEFAULT 'USR'
  COMMENT '主体 id 属于哪张表：USR=usr_account MCH=mch_account OPS=sys_ops_staff';

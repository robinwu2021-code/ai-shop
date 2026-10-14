-- 用户逛过的门店（TDD-C端门店化与门店门户 §2.2）。一人一店一行，覆盖写。
--
-- 回答的是「这个人和这家店是什么关系」：
--   · C 端店铺页「我的店」—— 逛过 ∪ 买过，按最近一次接触倒序；只逛过的 30 天没再来就退出；
--   · B 端「分享效果」（二期）—— 首次来源是分享的有多少人。
--
-- **为什么不复用 mkt_store_visit**：那张是扫码漏斗的追加日志（匿名也记，一次扫码一行），
-- 回答「这家店最近被扫了多少次」。V290 的作者专门写了不与别的语义混用的理由；
-- 这里是登录用户与门店的关系表，形状和用途都不同。扫码进门户会两张都写，各答各的问题。
--
-- **first_source 只在插入时定**：它记的是「这家店是怎么进入他的列表的」，
-- 之后再从别的入口进来只刷新 last_at 与 view_count。要是每次覆盖，
-- 分享带来的人第二天从列表点进去，分享的功劳就被抹掉了。
CREATE TABLE IF NOT EXISTS usr_store_view
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL COMMENT '冗余：B 端按主体汇总分享效果时不用再回查门店',
    first_source VARCHAR(16) NOT NULL COMMENT '首次来源 SHARE / SCAN / LIST / SEARCH / GOODS —— 这家店是怎么进入他的列表的',
    first_inviter_no VARCHAR(64) DEFAULT NULL COMMENT '首次来源是分享时，分享人',
    first_at BIGINT(20) NOT NULL,
    last_at BIGINT(20) NOT NULL,
    view_count INT(11) NOT NULL DEFAULT 1,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_store_view_user_store (user_no,store_no),
    KEY idx_store_view_store_source (store_no,first_source)
) COMMENT='用户逛过的门店（我的店 · 分享效果）';

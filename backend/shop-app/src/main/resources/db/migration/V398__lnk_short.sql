-- =====================================================================
-- V398 短链表（TDD-收件人物流触达与分享裂变 §4.2）
--
-- 发货短信里放的是 https://s.hxmall.top/<code>，点开 302 跳到微信 URL Link，
-- 再由 URL Link 打开小程序看件页。短链只做一件事：**由短码查回长目标并 302**。
--
-- 为什么要这张表而不把目标直接编进短码：
--   1）微信 URL Link 很长（wxaurl.cn/ + 一长串），直接放进短信按 70 字一条会多花一条钱；
--      短码把每条压回一条。
--   2）URL Link 有有效期，过期要换新的 —— 短码不变、只改 target 这一列，
--      已经发出去的短信还能用。
--   3）hits 这一列顺带记点击量，运营看裂变效果。
--
-- code 用 BizKey.shortCode(7)：7 位 Crockford base32（去 I/L/O/U）= 35 bit，
-- 配唯一索引与「撞了重取」足够稀。biz_type 先只有 SHIP_TRACK（发货看件），
-- 留着将来别的短链场景复用这张表。
-- =====================================================================

CREATE TABLE IF NOT EXISTS lnk_short
(
    id          BIGINT(20)    NOT NULL AUTO_INCREMENT,
    code        VARCHAR(16)   NOT NULL COMMENT '短码。放进短信的就是它，s.hxmall.top/<code>',
    target      VARCHAR(1024) NOT NULL COMMENT '302 跳向的长链接（通常是微信 URL Link）',
    biz_type    VARCHAR(32)   NOT NULL COMMENT '业务类型：SHIP_TRACK=发货看件',
    biz_ref     VARCHAR(64)   DEFAULT NULL COMMENT '业务单号（子单号），排查用',
    hits        BIGINT(20)    NOT NULL DEFAULT 0 COMMENT '点击次数，每次 302 自增',
    expires_at  DATETIME      DEFAULT NULL COMMENT '过期时间。空=不过期；过期后 302 失败给提示页',
    tenant_no   VARCHAR(32)   NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME      NOT NULL,
    created_by  VARCHAR(64)   DEFAULT NULL,
    updated_at  DATETIME      NOT NULL,
    updated_by  VARCHAR(64)   DEFAULT NULL,
    version     BIGINT(20)    NOT NULL DEFAULT 0,
    deleted     TINYINT(4)    NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_lnk_short_code (code),
    KEY idx_lnk_short_ref (biz_ref)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='短链：短码查回长目标并 302（发货看件短信用）';

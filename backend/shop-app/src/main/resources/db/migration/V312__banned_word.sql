-- 建品前置约束：平台禁售词（商品①）。
--
-- **今天只有事后驳回。** 商家提交一个带违禁词的标题，它会进审核队列、
-- 占一个人的时间、再被驳回，而商家隔几天才知道要改哪个字。
-- 2026-09-03 线上 194 件卡在审核里 —— 这条链的入口没有任何前置检查。
--
-- 前置拦下来的好处不只是省审核工时：**报错当场指出是哪个词**，
-- 而驳回理由是人手写的一句话，商家常常读完还是不知道改哪儿。
--
-- ⚠️ **只做商品标题这一个场景，不预留 scene 列。**
-- 店招公告也该查，但那是 merchant 域的另一个落点 —— 现在加一列 scene
-- 而只有一个取值，就是又一张「建了表没人读」的表（inv_uom 的教训）。
-- 真要接店招时再加，那是一次小改动。

CREATE TABLE IF NOT EXISTS sys_banned_word
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    -- 词本身。**存小写**，匹配时两边都转小写 —— 否则「iPhone」配了拦不住「IPHONE」
    word VARCHAR(64) NOT NULL,
    -- 为什么禁。会**原样出现在给商家的报错里**，所以要写成他看得懂的一句话
    reason VARCHAR(255) DEFAULT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted INT(11) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_banned_word UNIQUE (word, tenant_no, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='平台禁售词：商品标题提审前置校验';

-- 权限码：读挂 product:category:read（配类目规则的那批人管这个），
-- 写单独一个码 —— 加一个词等于让存量商品下次提审全被拦，与「看一眼词表」不是一回事。
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_PRODUCT__TAB_BANNED_WORD', 'OPS_PRODUCT', '禁售词', '类目', '/products?tab=banned-word', 'product:category:read', 'product:category:read', 'IMPLEMENTED', 1, 'P-3.1', 'MENU', 66, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_PRODUCT__TAB_BANNED_WORD');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_PRODUCT__TAB_BANNED_WORD', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_PRODUCT__TAB_BANNED_WORD');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'GOODS_OPS', 'OPS_PRODUCT__TAB_BANNED_WORD', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code='OPS_PRODUCT__TAB_BANNED_WORD');

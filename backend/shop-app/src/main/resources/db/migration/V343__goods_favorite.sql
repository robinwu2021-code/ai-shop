-- 商品收藏（TDD-C端商品收藏与送达判断）。
--
-- 放商品域（prd_）：收藏列表要取商品的展示形状，放用户域就得为此开一个跨域 Port。
-- 取消收藏走物理删除，不走全局逻辑删除 —— 否则 deleted=1 的那一行还占着唯一键，
-- 同一件商品取消后再收藏会撞 uk_prd_goods_favorite。
CREATE TABLE IF NOT EXISTS prd_goods_favorite
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_no VARCHAR(64) NOT NULL COMMENT '买家',
    goods_no VARCHAR(64) NOT NULL COMMENT '商品',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prd_goods_favorite (user_no, goods_no),
    KEY idx_prd_goods_favorite_user (user_no, created_at)
) COMMENT='商品收藏：买家 × 商品';

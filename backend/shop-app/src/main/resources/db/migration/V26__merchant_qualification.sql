-- 商家资质：从「一堆图」变成结构化记录（落地清单 P1-7）。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 修的是什么
-- ─────────────────────────────────────────────────────────────────────────────
-- 现状的资质链路有三层断裂：
--   ① 资质图只存在 mch_entity_apply.qualifications（入驻申请单）上，
--      审核通过后**没有转存到主体**——运营看商家详情看不到「这家店有哪些证」
--   ② 那个字段是 JSON 数组的图片 URL，**没有证件类型、证号、有效期**
--   ③ 上架校验读的是 mch_entity.category_codes（审核通过时写死的编码），
--      证过期了这串编码不会变，商家照样上架、系统照样放行
--
-- 于是：某商家的食品经营许可证到期后，他什么都不用做，商品继续在架、继续能上新，
-- **平台在整个过程中收不到任何信号**。而自营模式下平台是销售主体，责任在平台。
--
-- 单独建表而不是给 mch_entity 加 JSON 列：定时扫到期要按 expire_at 做范围查询，
-- 存 JSON 就得全表扫描 + 逐行解析，商家一多这条任务就跑不动了。

CREATE TABLE IF NOT EXISTS mch_qualification
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    qual_no VARCHAR(64) NOT NULL COMMENT '平台内部单号',
    entity_no VARCHAR(64) NOT NULL COMMENT '所属商家主体',
    qual_type VARCHAR(32) NOT NULL
        COMMENT 'BUSINESS_LICENSE 营业执照 / FOOD_PERMIT 食品经营许可 / FOOD_WORKSHOP 小作坊登记 / OTHER',
    qual_name VARCHAR(128) NOT NULL COMMENT '证件名称，给人看的',
    qual_number VARCHAR(64) DEFAULT NULL COMMENT '证件编号',
    image_url VARCHAR(512) DEFAULT NULL COMMENT '证件影像',
    -- 长期有效的证（如部分营业执照）留空。**空不等于过期**，判定时要分开处理：
    -- 把「长期有效」当成「没填 = 已过期」会把一批正常商家误伤下架
    expire_at BIGINT(20) DEFAULT NULL COMMENT '有效期至；空 = 长期有效',
    status VARCHAR(16) NOT NULL DEFAULT 'VALID'
        COMMENT 'VALID 有效 / EXPIRED 已过期（由定时任务置）/ REVOKED 已撤销',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mch_qual_no (qual_no),
    KEY idx_qual_entity (entity_no),
    -- 定时任务按它扫「快到期」与「已过期」，所以要索引
    KEY idx_qual_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家资质。结构化存证件类型/编号/有效期，供到期扫描与上架校验';

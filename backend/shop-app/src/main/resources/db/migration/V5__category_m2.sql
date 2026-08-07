-- M2.3 类目树（db-design.md §4.3）。
-- 三级树用 parent_no 自关联而不是闭包表：类目只有三层且改动极少，
-- 闭包表的维护成本换不来任何查询收益。

CREATE TABLE IF NOT EXISTS prd_category
(
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_no            VARCHAR(64)  NOT NULL,
    parent_no              VARCHAR(64)   NULL COMMENT '一级类目为空',
    level                  INT          NOT NULL DEFAULT 1,
    name                   VARCHAR(64)  NOT NULL,
    icon                   VARCHAR(512)  NULL,
    sort                   INT          NOT NULL DEFAULT 0,
    attr_template          TEXT          NULL COMMENT 'JSON：五品类属性模板（P-3.1.2）',
    qualification_required VARCHAR(512)  NULL COMMENT 'JSON：经营该类目需要的资质（P-3.1.4）',
    status                 VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    tenant_no              VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at             DATETIME     NOT NULL,
    created_by             VARCHAR(64)   NULL,
    updated_at             DATETIME     NOT NULL,
    updated_by             VARCHAR(64)   NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    deleted                TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_category_no (category_no),
    KEY idx_parent_sort (parent_no, sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '三级类目树';

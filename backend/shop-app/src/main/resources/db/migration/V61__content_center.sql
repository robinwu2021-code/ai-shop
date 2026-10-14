-- 内容与素材域（P-15.1 素材中心 / P-15.2 种草内容审核与榜单问答）。
--
-- 这个域的业务规则**此前只存在于 ops-web 的契约注释里**，后端零端点。
-- 那些注释不是前端的实现细节，是需求本身（「批量 + 风险内容 = 事故」
-- 之类的判断连同理由都写下来了），后端逐条实现，一条都不能比它宽。
--
-- ⚠️ 本批只做**平台侧的审核与治理**：C 端还不能发种草内容、不能提问，
--    所以审核台会是空的。空态文案已写明「等 C 端发布链路接通」——
--    诚实的空态好过让人以为坏了。

CREATE TABLE IF NOT EXISTS cnt_post
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    post_no VARCHAR(64) NOT NULL,
    author_type VARCHAR(16) NOT NULL COMMENT 'USER 普通用户 / MERCHANT 商家。商家发的审核标准更严',
    author_name VARCHAR(64) DEFAULT NULL COMMENT '昵称或店名快照',
    title VARCHAR(128) DEFAULT NULL,
    content TEXT DEFAULT NULL,
    community_no VARCHAR(64) DEFAULT NULL COMMENT '归属社区。内容只在本社区露出',
    community_name VARCHAR(64) DEFAULT NULL COMMENT '社区名快照',
    sku_no VARCHAR(64) DEFAULT NULL COMMENT '关联商品；纯分享贴可以没有',
    risk_hits TEXT DEFAULT NULL COMMENT 'JSON 数组：命中的风险词。**落库而不是每次现算** —— 审核页要按它筛选，更要紧的是词库改了之后「当时是不是命中了」还查得到',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PASSED/REJECTED/OFFLINE。PASSED→OFFLINE 是单独一条路，不能退回待审',
    audit_remark VARCHAR(255) DEFAULT NULL COMMENT '审核意见或下架原因。**原样回作者**，所以驳回与下架都必须写',
    audited_by VARCHAR(64) DEFAULT NULL,
    audited_at BIGINT(20) DEFAULT NULL,
    like_count INT(11) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cnt_post_no (post_no),
    KEY idx_cnt_post_status (status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='种草内容与审核结果';

CREATE TABLE IF NOT EXISTS cnt_question
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    question_no VARCHAR(64) NOT NULL,
    sku_no VARCHAR(64) DEFAULT NULL,
    sku_title VARCHAR(128) DEFAULT NULL COMMENT '商品名快照：商品改名不该让历史问答对不上',
    content VARCHAR(500) DEFAULT NULL,
    asked_by VARCHAR(64) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ANSWERED/HIDDEN',
    answer VARCHAR(500) DEFAULT NULL,
    answered_by VARCHAR(64) DEFAULT NULL,
    answered_at BIGINT(20) DEFAULT NULL,
    hide_reason VARCHAR(255) DEFAULT NULL COMMENT '隐藏原因。导流/辱骂之类，同样要写',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cnt_question_no (question_no),
    KEY idx_cnt_question_status (status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品问答';

CREATE TABLE IF NOT EXISTS cnt_ranking
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    rank_no VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL COMMENT 'MANUAL 人工挑选 / 其余按规则算。两者的校验路径完全不同',
    size INT(11) NOT NULL DEFAULT 10 COMMENT '榜单容量。MANUAL 的条目数不能超过它',
    manual_skus TEXT DEFAULT NULL COMMENT 'JSON 数组，**仅 MANUAL 有值**。非 MANUAL 带了它直接拒绝 —— 传了就是调用方理解错了',
    enabled TINYINT(4) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cnt_ranking_no (rank_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='榜单配置';

CREATE TABLE IF NOT EXISTS cnt_material
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    material_no VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    kind VARCHAR(16) NOT NULL COMMENT 'COPY 文案 / IMAGE / POSTER / VIDEO',
    content TEXT DEFAULT NULL COMMENT '文案正文，或图片/视频地址',
    scope VARCHAR(16) NOT NULL DEFAULT 'ALL' COMMENT 'ALL / COMMUNITY / MERCHANT。**投给谁和素材本身是一件事**',
    scope_refs TEXT DEFAULT NULL COMMENT 'JSON 数组：COMMUNITY 时的社区列表、MERCHANT 时的商家列表。指定了范围就不能为空',
    langs TEXT DEFAULT NULL COMMENT 'JSON 数组：适用语言，空 = 不限',
    published TINYINT(4) NOT NULL DEFAULT 0 COMMENT '未发布的素材商家看不到',
    downloads INT(11) NOT NULL DEFAULT 0 COMMENT '衡量素材有没有人用',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cnt_material_no (material_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运营素材';

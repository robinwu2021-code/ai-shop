-- 图片资产记账（TDD-图片存储与空间回收 §L3-3）。
--
-- 在此之前，「谁占了多少存储」只能靠去服务器 du，而「哪些图没人用了」根本查不出来 ——
-- 上传只把字节写进磁盘，库里一行记录都没有。这张表就是那份缺失的账。
--
-- 三件事都靠它，不靠扫磁盘：
--   ① 门店占用统计 = 一条 GROUP BY
--   ② 待回收清单   = status='RECLAIMABLE' 的行
--   ③ 回收执行     = 按行删文件，删完只改 status，**不删行**
--
-- 为什么不删行：删除不可逆，「什么时候删了什么」必须永远查得到。
-- 与 sys_outbox 同一类基础设施表，因此同样不带 version / deleted / tenant_no
-- —— 尤其是 deleted：MyBatis-Plus 全局配了逻辑删除，这张表若带这一列，
-- 「保留已删记录以备审计」的设计当场失效（查出来的行会被自动过滤掉）。

CREATE TABLE IF NOT EXISTS sys_media_asset
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,

    -- 相对路径，形如 E0001/S0003/goods/202608/9f2c….jpg
    -- **这串字逐字就是将来的 COS object key**，切对象存储时不需要任何映射
    asset_key          VARCHAR(255) NOT NULL COMMENT '相对路径，也是将来的 COS object key',

    entity_no          VARCHAR(64)  NOT NULL COMMENT '经营主体',
    -- 上传时的当前门店。统计与回收都按它 —— 商品是主体级的，
    -- 一张图可能被多家店展示，所以这里记的是**归属**（谁传的算谁的），不是展示关系
    store_no           VARCHAR(64)  NOT NULL COMMENT '上传时的当前门店',

    -- GOODS 公开读；QUAL（证件）与 AFTERSALE（售后凭证）私有读，走签名 URL。
    -- 分开是为了让权限能按前缀走 —— 与 COS 的能力模型一致
    biz_type           VARCHAR(16)  NOT NULL COMMENT 'GOODS / QUAL / AFTERSALE',

    bytes              BIGINT       NOT NULL COMMENT '字节数，统计的唯一依据',
    -- 上传时顺手读出来存下。运营端要显示尺寸，而事后再读要把每个文件打开一遍
    width              INT                   DEFAULT NULL,
    height             INT                   DEFAULT NULL,
    content_type       VARCHAR(64)           DEFAULT NULL,

    -- PENDING  刚写行、字节还没落盘（先记账后落地，崩在中间只留可对账的 PENDING）
    -- ACTIVE   在用
    -- RECLAIMABLE  扫描判定无人引用，进待回收清单 —— **它不会自己往下走**，
    --              删除一律要运营在页面上勾选并强确认
    -- PURGED   文件已删，行永久保留
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING / ACTIVE / RECLAIMABLE / PURGED',

    -- ── 「可回收理由」全靠下面两列 ──
    -- 它们是扫描时落下的真实数据，不是事后推断：扫描遍历引用源时，
    -- 对**仍被引用**的资产刷新这两列。于是一旦失去引用，它们就停在最后一次被引用的那一刻。
    --   NULL  → 从未被引用（商家传了图但没保存商品）
    --   有值  → 曾被 last_ref_desc 引用，此后被替换掉了
    last_referenced_at DATETIME              DEFAULT NULL COMMENT '最后一次被扫到仍在引用的时刻；NULL=从未被引用',
    last_ref_desc      VARCHAR(128)          DEFAULT NULL COMMENT '最后一个引用者的人话描述，如「商品 G0012 · 主图」',

    -- 进待回收清单的时刻。**救回时必须置空** —— 留着的话第二次进清单
    -- 会用一个过期的起算点，「待了多少天」就是错的
    marked_at          DATETIME              DEFAULT NULL COMMENT '进待回收清单的时刻；救回时置空',

    uploaded_by        VARCHAR(64)           DEFAULT NULL COMMENT '商家侧账号，运营要追问时找得到人',
    purge_batch_no     VARCHAR(64)           DEFAULT NULL COMMENT '哪一批回收任务删的',

    created_at         DATETIME     NOT NULL,
    updated_at         DATETIME     NOT NULL,
    purged_at          DATETIME              DEFAULT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_asset_key (asset_key),
    -- 门店占用统计与「某店的待回收」都走它
    KEY idx_store_status (store_no, status),
    -- 待回收清单按进清单时间排序；扫描要捞出全部 ACTIVE 也走它
    KEY idx_status_marked (status, marked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci
    COMMENT='图片资产记账：空间统计与回收清单的唯一依据';

-- ── 回收批次：运营在页面上勾选、强确认之后生成的那一次任务 ──
--
-- 为什么要单独一张表而不是只在资产行上挂 purge_batch_no：
--   ① 运营端「回收记录」那一页要列出「谁、什么时候、删了多少、花了多久」，
--      这些是批次的属性，挂在每一行资产上就要聚合几万行才能答一个问题
--   ② 批次要能重跑 —— 失败的那几张留在批次里，运营点一下重试。
--      没有批次实体的话「重跑哪一批」无从表达
--   ③ 它是审计的锚：SysAuditLog 记一条汇总，明细靠这个号回捞
CREATE TABLE IF NOT EXISTS sys_media_purge_batch
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    batch_no      VARCHAR(64)  NOT NULL,
    -- 发起人。**不可逆动作必须记名**，而且要记当时的名字：
    -- 只存账号的话，人离职账号改名之后这条记录就再也说不清是谁了
    operator      VARCHAR(64)  NOT NULL COMMENT '运营账号',
    operator_name VARCHAR(64)           DEFAULT NULL COMMENT '发起时的显示名，快照',
    -- QUEUED   已提交，等任务捡起来
    -- RUNNING  正在删
    -- DONE     全部成功
    -- PARTIAL  有失败的，可重跑（失败的那几张仍留在批次里）
    status        VARCHAR(16)  NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED / RUNNING / DONE / PARTIAL',
    total_count   INT          NOT NULL DEFAULT 0,
    total_bytes   BIGINT       NOT NULL DEFAULT 0,
    purged_count  INT          NOT NULL DEFAULT 0,
    failed_count  INT          NOT NULL DEFAULT 0,
    started_at    DATETIME              DEFAULT NULL,
    finished_at   DATETIME              DEFAULT NULL,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_batch_no (batch_no),
    KEY idx_media_batch_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci
    COMMENT='图片回收批次：一次人工确认对应一行';

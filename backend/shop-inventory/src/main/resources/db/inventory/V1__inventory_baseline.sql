-- ============================================================================
-- 进销存独立库 · 基线
--
-- ── 审计列的规矩（全库一致，不逐表重复）──
-- ① **每张表都有 created_by**：「谁建的」是这套东西的领域数据，不是顺带记的审计。
-- ② **只追加 / 写一次的三张没有 updated_at / updated_by**：
--    inv_ledger（流水不可变）· inv_item_ref（引用是加/删不是改）· inv_reservation_line（全成或全败）。
--    **没有这两列，就没有「改一行」这个动作** —— 不变式 I3 由表结构本身兜住，
--    而不是靠代码里记得别写 update。实体那边也分两个基类，继承关系即是这条规矩。
-- ③ 其余十四张都有 updated_at / updated_by。
--
-- 这是**另一个数据库**的第一条迁移，与 ai_shop 的 Flyway 历史互不知情。
-- 分开的理由见 TDD-进销存领域模型：进销存要能独立交付，
-- 而带着平台 260 条迁移交付出去，第一句话就得是「请先装一个 ai_shop」。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 全库三条通则（每张表都遵守，不再逐表重复）
-- ─────────────────────────────────────────────────────────────────────────────
-- ① **没有 tenant_no。** owner_id 就是隔离维度。再加一层租户会让每个唯一键
--    多一列，而那一列在可预见的部署里只有一个取值 —— 独立领域的好处之一
--    就是不背平台的横切列。
--
-- ② **没有 deleted 软删列。** 主数据用 status=ARCHIVED，单据用 status=VOIDED，
--    流水**根本不能删**。三种"消失"各有各的语义，压成一个 deleted 之后，
--    「这行为什么不见了」就没人答得清；而流水一旦有了删除入口，
--    这张表的全部价值当场归零。
--
-- ③ **没有外键。** 完整性由聚合根在应用层保证 + 唯一键兜底。
--    建了外键，「哪些引用允许悬空」这个决定就分散在 DB 与代码两处，
--    两处不一致时以谁为准没人答得清。
--
-- 命名：inv_ 前缀。不用 prd_ —— 那是平台商品域的前缀，
-- 独立交付时客户库里出现 prd_ 会让人以为还有个商品系统。
-- ============================================================================


-- ─────────────────────────────────────────────────────────────────────────────
-- 1. 业主
-- ─────────────────────────────────────────────────────────────────────────────
-- 不叫 merchant：独立交付时业主可能是工厂、门店、个人。「商家」是平台视角。
CREATE TABLE IF NOT EXISTS inv_owner
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id     VARCHAR(32)  NOT NULL COMMENT '业主业务键，本域生成',
    name         VARCHAR(128) NOT NULL,
    external_ref VARCHAR(64)  DEFAULT NULL COMMENT '嵌入平台时 = mch_entity.entity_no；独立交付时为空',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(64)  DEFAULT NULL,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by   VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_owner (owner_id),
    -- 业主与平台主体 1:1（已定）。多执照商家仍然是一个业主 —— 货是同一批货
    UNIQUE KEY uk_owner_ext (external_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='业主：库存归谁所有';


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. 库位
-- ─────────────────────────────────────────────────────────────────────────────
-- 门店、仓、在途、报废区**都是库位**。分成两个词（门店 / 仓库）的话，
-- 调拨的中间态就没地方落脚，而"货在路上"的那几天总量不守恒 ——
-- 不守恒的账发现不了错误：差多少都能解释成「在路上」。
CREATE TABLE IF NOT EXISTS inv_location
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    location_id        VARCHAR(32)  NOT NULL,
    owner_id           VARCHAR(32)  NOT NULL,
    name               VARCHAR(128) NOT NULL,
    kind               VARCHAR(16)  NOT NULL DEFAULT 'STORE' COMMENT 'STORE 门店 / WAREHOUSE 仓 / TRANSIT 在途 / VIRTUAL 虚拟（报废区、样品、借出）',
    external_ref       VARCHAR(64)  DEFAULT NULL COMMENT '嵌入平台时 = mch_store.store_no；仓可以没有对应门店',
    source_location_id VARCHAR(32)  DEFAULT NULL COMMENT '这个点从哪里发货；空 = 发自己的。**不允许链式**（被指向者自己必须为空）',
    is_default         TINYINT      NOT NULL DEFAULT 0 COMMENT '一业主恰好一个，删不掉。存量「主体级库存」迁到它名下',
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(64)  DEFAULT NULL,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by         VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_loc (owner_id, location_id),
    UNIQUE KEY uk_loc_ext (owner_id, external_ref),
    KEY idx_loc_kind (owner_id, kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='库位：存货的地方，门店与仓与在途都在这里';


-- ─────────────────────────────────────────────────────────────────────────────
-- 3. 计量单位字典
-- ─────────────────────────────────────────────────────────────────────────────
-- **全局，不带 owner_id**：单位是物理量，不因业主而异。
-- 「本店叫法」这件事由规格档位负责，不该渗到计量单位上 ——
-- 一旦每个业主的「斤」可以是不同的东西，跨业主的任何汇总都失去意义。
CREATE TABLE IF NOT EXISTS inv_uom
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    uom_code   VARCHAR(16) NOT NULL,
    name       VARCHAR(32) NOT NULL,
    divisible  TINYINT     NOT NULL DEFAULT 0 COMMENT '1=可拆分（称重品）。称重品与计件品的分界',
    sort       INT         NOT NULL DEFAULT 0,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL COMMENT '谁建的。业务键：商家账号 / 运营账号 / SYSTEM',
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_uom (uom_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='计量单位字典';

-- 种子写成**可重入**形式（SELECT … FROM DUAL WHERE NOT EXISTS）。
-- 裸 VALUES 撞上唯一键就是 1062，而重跑不是异常情况：迁移中途失败、本地库来回切分支、
-- 测试用 H2 的 INIT=RUNSCRIPT（每建一条连接跑一遍）都会让它再跑一次。
-- 这一条是被测试环境逼出来的，但**它防的是生产上的重跑**。
INSERT INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'PIECE', '件', 0, 10 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'PIECE');
INSERT INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'BAG', '袋', 0, 20 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'BAG');
INSERT INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'BOX', '箱', 0, 30 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'BOX');
INSERT INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'BOTTLE', '瓶', 0, 40 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'BOTTLE');
INSERT INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'PORTION', '份', 0, 50 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'PORTION');
INSERT INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'JIN', '斤', 1, 60 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'JIN');
INSERT INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'KG', '公斤', 1, 70 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'KG');
INSERT INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'G', '克', 1, 80 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'G');


-- ─────────────────────────────────────────────────────────────────────────────
-- 4. 物料（零售形态下就是 SKU）
-- ─────────────────────────────────────────────────────────────────────────────
-- 不叫 SKU 是因为进销存要计数的还有原料、包材、五金件、赠品。
CREATE TABLE IF NOT EXISTS inv_item
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    item_id            VARCHAR(32)  NOT NULL COMMENT '本域生成。**不复用平台 sku_no** —— 复用之后，交付给没有 sku_no 的客户时主键要重设计',
    owner_id           VARCHAR(32)  NOT NULL,
    item_code          VARCHAR(64)  DEFAULT NULL COMMENT '业主自己的货号，给人看的。可空，且空是常态',
    name               VARCHAR(128) NOT NULL,
    spec_text          VARCHAR(128) DEFAULT NULL COMMENT '规格描述「5斤装·精选」。展示用，不参与任何计算',
    spu_id             VARCHAR(32)  DEFAULT NULL COMMENT '同款分组，仅用于报表归类。**只是一列，不是一张表** —— 做成实体会诱使人给 SPU 也算库存，而那个数没有意义',
    category_code      VARCHAR(64)  DEFAULT NULL COMMENT '报表分组',
    base_uom           VARCHAR(16)  NOT NULL DEFAULT 'PIECE' COMMENT '★ 一旦有流水不可改：从「件」改成「斤」，历史数字一个不变而含义全变，且没有任何地方会报错',
    weighed            TINYINT      NOT NULL DEFAULT 0 COMMENT '1=称重品',
    track_batch        TINYINT      NOT NULL DEFAULT 0 COMMENT '留位，一期恒 0。开启后余额要从一个数变成一组批次',
    shelf_life_days    INT          DEFAULT NULL COMMENT '留位',
    safety_stock       INT          NOT NULL DEFAULT 0 COMMENT '安全库存默认阈值，0=不预警。可被库位覆盖',
    cost_method        VARCHAR(16)  NOT NULL DEFAULT 'LATEST' COMMENT 'LATEST 最新进价 / MANUAL 手工价。留位 MOVING_AVG / FIFO —— 移动加权漏录一次之后所有历史毛利全错**且不报警**',
    default_cost_minor BIGINT       DEFAULT NULL COMMENT '当前成本，最小币种单位',
    data_source        VARCHAR(16)  NOT NULL DEFAULT 'SYNCED' COMMENT 'OWN 自有主数据 / SYNCED 从外部投影。**两种交付形态的唯一分叉点**',
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED。归档不删流水 —— 历史账要能查',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(64)  DEFAULT NULL,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by         VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_item (owner_id, item_id),
    -- 货号在业主自己的命名空间里唯一。**允许多行 NULL** —— 没填货号的货有的是，
    -- 做成 NOT NULL 会逼人编一个，而编出来的货号比没有更坏
    UNIQUE KEY uk_item_code (owner_id, item_code),
    KEY idx_item_spu (owner_id, spu_id),
    KEY idx_item_status (owner_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='物料（零售形态即 SKU）：被计数的东西';


-- ─────────────────────────────────────────────────────────────────────────────
-- 5. 外部引用
-- ─────────────────────────────────────────────────────────────────────────────
-- 同一件货在平台叫 SKU123、在商家 ERP 叫 LM-05、条码是 690…，三者都要能查到。
-- 一列存不下，所以单开一张表。
CREATE TABLE IF NOT EXISTS inv_item_ref
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    owner_id   VARCHAR(32) NOT NULL,
    ref_system VARCHAR(16) NOT NULL COMMENT 'AISHOP 平台 / ERP 商家自有 / BARCODE 条码 / POS 收银。列名不用 system —— 那是 MySQL 8 的保留字',
    ref        VARCHAR(64) NOT NULL,
    item_id    VARCHAR(32) NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    -- 不变式 I6。一个物料可以有多个条码（换包装还是同一件货），
    -- 但一个条码**不能指向两个物料** —— 否则收银台扫一次出来两件货，当场卡住
    UNIQUE KEY uk_ref (owner_id, ref_system, ref),
    KEY idx_ref_item (owner_id, item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='外部引用：与外部世界唯一的钩子';


-- ─────────────────────────────────────────────────────────────────────────────
-- 6. 库存余额　★ 核心
-- ─────────────────────────────────────────────────────────────────────────────
-- **全库唯一允许并发条件更新的对象。** 把并发面收到一张表上，
-- 「不超卖」这件事的正确性只需要证明一次。
CREATE TABLE IF NOT EXISTS inv_stock_balance
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    owner_id      VARCHAR(32) NOT NULL,
    item_id       VARCHAR(32) NOT NULL,
    location_id   VARCHAR(32) NOT NULL COMMENT '★ 不可空。主体级库存 = 一个 is_default 的库位，**一种表达而不是两种**',
    on_hand       INT         NOT NULL DEFAULT 0 COMMENT '实存。只有单据过账能改；不允许为负 —— 让错误停在录入处，而不是流进报表',
    reserved      INT         NOT NULL DEFAULT 0 COMMENT '已预留未出库。只有预留能改',
    safety_stock  INT         DEFAULT NULL COMMENT '本库位阈值覆盖；空 = 用 inv_item.safety_stock。城西店与仓库的安全线不可能一样',
    last_moved_at DATETIME    DEFAULT NULL COMMENT '最近一次变动。滞销判定直接读它，不必扫流水',
    version       BIGINT      NOT NULL DEFAULT 0 COMMENT '乐观锁',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL COMMENT '谁建的。业务键：商家账号 / 运营账号 / SYSTEM',
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    -- 身份三元组，无可空项。并发下靠这个唯一键 + 条件更新防超卖
    UNIQUE KEY uk_balance (owner_id, item_id, location_id),
    KEY idx_balance_loc (owner_id, location_id),
    -- 缺货与滞销两张报表都从这个组合出发
    KEY idx_balance_moved (owner_id, last_moved_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='库存余额：available = on_hand - reserved，派生不落库';


-- ─────────────────────────────────────────────────────────────────────────────
-- 7. 流水
-- ─────────────────────────────────────────────────────────────────────────────
-- **只追加。没有 updated_at、没有 updated_by、没有删除路径。**
-- 写错了就再写一行反向的 —— 单据作废走的正是这条路。
CREATE TABLE IF NOT EXISTS inv_ledger
(
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '★ 单调递增，兼作外部增量拉取的游标',
    owner_id        VARCHAR(32) NOT NULL,
    item_id         VARCHAR(32) NOT NULL,
    location_id     VARCHAR(32) NOT NULL,
    doc_kind        VARCHAR(8)  NOT NULL COMMENT 'IN / OUT',
    doc_no          VARCHAR(32) NOT NULL COMMENT '入库单号或出库单号',
    line_no         INT         NOT NULL,
    reason_code     VARCHAR(16) NOT NULL COMMENT '单据 source_type / purpose 的**快照**：报表按它分组免 join。它不是真源，与单据不一致时以单据为准',
    qty_delta       INT         NOT NULL COMMENT '带符号：入为正，出为负。不拆成 in/out 两列 —— 两列会把求和变成减法，漏减不会被发现',
    balance_after   INT         NOT NULL COMMENT '这一行之后的 on_hand。自校验：prev.balance_after + qty_delta 必须等于它',
    unit_cost_minor BIGINT      DEFAULT NULL COMMENT '过账那一刻的成本快照',
    occurred_at     DATETIME    NOT NULL COMMENT '业务发生时间，跟单据走（可回填）',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间。**排序一律用 id 不用它** —— 时钟回拨会让游标漏行',
    operator        VARCHAR(64) DEFAULT NULL COMMENT '谁干的。这一列是这张表存在的一半理由',
    created_by      VARCHAR(64) DEFAULT NULL COMMENT '谁建的。业务键：商家账号 / 运营账号 / SYSTEM',
    PRIMARY KEY (id),
    -- 幂等：过账重放不会写两行。与预留、单据同一手法，不发明第二套
    UNIQUE KEY uk_doc_line (doc_no, line_no),
    -- 自校验回放与「某个 SKU 的变动明细」都走这个组合
    KEY idx_ledger_seq (owner_id, item_id, location_id, id),
    -- 外部增量拉取
    KEY idx_ledger_cursor (owner_id, id),
    KEY idx_ledger_reason (owner_id, reason_code, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='库存流水：只追加，不修改，不删除';


-- ─────────────────────────────────────────────────────────────────────────────
-- 8. 预留
-- ─────────────────────────────────────────────────────────────────────────────
-- 跨库之后，超卖的强一致就靠它：available >= 0 只需要在**本库**里成立，
-- 一条件更新即可，与调用方在不在同一进程无关。
CREATE TABLE IF NOT EXISTS inv_reservation
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    reservation_id VARCHAR(32) NOT NULL,
    owner_id       VARCHAR(32) NOT NULL,
    external_ref   VARCHAR(64) NOT NULL COMMENT '调用方订单号。网络超时重试是常态，不幂等就会预留两次而第二次没人释放',
    status         VARCHAR(16) NOT NULL DEFAULT 'HELD' COMMENT 'HELD / COMMITTED / RELEASED / EXPIRED',
    expires_at     DATETIME    NOT NULL COMMENT '★ 到期自动回收。调用方可能永远不回来 —— 跨进程之后兜底必须在本领域内',
    committed_at   DATETIME    DEFAULT NULL,
    released_at    DATETIME    DEFAULT NULL,
    outbound_no    VARCHAR(32) DEFAULT NULL COMMENT 'commit 时生成的销售出库单',
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(64) DEFAULT NULL,
    updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation (owner_id, reservation_id),
    -- 重试幂等靠它：同一个订单号再来一次，返回原结果而不是再占一份
    UNIQUE KEY uk_reservation_ext (owner_id, external_ref),
    -- 回收任务扫这个组合
    KEY idx_reservation_expire (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='预留：占住一批货等人来取';

CREATE TABLE IF NOT EXISTS inv_reservation_line
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    reservation_id VARCHAR(32) NOT NULL,
    line_no        INT         NOT NULL,
    owner_id       VARCHAR(32) NOT NULL,
    item_id        VARCHAR(32) NOT NULL,
    location_id    VARCHAR(32) NOT NULL,
    qty            INT         NOT NULL,
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL COMMENT '谁建的。业务键：商家账号 / 运营账号 / SYSTEM',
    PRIMARY KEY (id),
    UNIQUE KEY uk_res_line (reservation_id, line_no),
    KEY idx_res_line_item (owner_id, item_id, location_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='预留行：全成功或全失败，不允许部分预留';


-- ─────────────────────────────────────────────────────────────────────────────
-- 9. 入库单
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inv_inbound_order
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    inbound_no       VARCHAR(32)  NOT NULL,
    owner_id         VARCHAR(32)  NOT NULL,
    location_id      VARCHAR(32)  NOT NULL COMMENT '入到哪个库位',
    source_type      VARCHAR(16)  NOT NULL COMMENT 'PURCHASE 采购 / RETURN 退货 / TRANSFER_IN 调拨入 / COUNT_GAIN 盘盈 / OTHER',
    source_ref       VARCHAR(64)  DEFAULT NULL COMMENT '来源单号：退货=售后单 / 调拨=调拨单 / 盘盈=盘点单；采购为空',
    supplier_name    VARCHAR(64)  DEFAULT NULL COMMENT '仅 PURCHASE。**自由文本，不建供应商档案** —— 小店的供应商是微信里那个人',
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / POSTED / VOIDED。只有 POSTED 改余额',
    total_qty        INT          NOT NULL DEFAULT 0,
    total_cost_minor BIGINT       NOT NULL DEFAULT 0 COMMENT '仅 PURCHASE 有意义',
    occurred_at      DATETIME     NOT NULL COMMENT '★ 实际入库时间，可回填。与 created_at 分开 —— 商家周一补录上周五的进货，按录入时间算会把上周的货算进本周报表',
    posted_at        DATETIME     DEFAULT NULL,
    voided_at        DATETIME     DEFAULT NULL,
    operator         VARCHAR(64)  DEFAULT NULL,
    remark           VARCHAR(255) DEFAULT NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(64)  DEFAULT NULL,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by       VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inbound (owner_id, inbound_no),
    KEY idx_inbound_src (owner_id, source_type, source_ref),
    KEY idx_inbound_time (owner_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='入库单：方向恒为正';

CREATE TABLE IF NOT EXISTS inv_inbound_line
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    inbound_no      VARCHAR(32) NOT NULL,
    line_no         INT         NOT NULL,
    owner_id        VARCHAR(32) NOT NULL,
    item_id         VARCHAR(32) NOT NULL,
    qty             INT         NOT NULL COMMENT '实收数量。留位：将来加 expected_qty 做部分收货',
    uom             VARCHAR(16) NOT NULL COMMENT '快照。base_uom 将来改了，历史行仍然可解释',
    unit_cost_minor BIGINT      DEFAULT NULL COMMENT '进货单价。**PURCHASE 必填** —— 允许空的话 cost_method=LATEST 会读到 NULL，毛利静默变成等于售价',
    batch_no        VARCHAR(32) DEFAULT NULL COMMENT '留位',
    expire_at       DATE        DEFAULT NULL COMMENT '留位',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL COMMENT '谁建的。业务键：商家账号 / 运营账号 / SYSTEM',
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inbound_line (inbound_no, line_no),
    KEY idx_inbound_line_item (owner_id, item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='入库单行';


-- ─────────────────────────────────────────────────────────────────────────────
-- 10. 出库单
-- ─────────────────────────────────────────────────────────────────────────────
-- **不带售价。** 售价是销售域的事，且同一件货在不同渠道售价不同 ——
-- 存进来就有了第二个销售真源，而两个数不一样时没人知道该信谁。
CREATE TABLE IF NOT EXISTS inv_outbound_order
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    outbound_no      VARCHAR(32)  NOT NULL,
    owner_id         VARCHAR(32)  NOT NULL,
    location_id      VARCHAR(32)  NOT NULL COMMENT '从哪个库位出',
    purpose          VARCHAR(16)  NOT NULL COMMENT 'SALE 销售 / TRANSFER_OUT 调拨出 / SCRAP 报损 / COUNT_LOSS 盘亏 / INTERNAL 领用 / OTHER',
    source_ref       VARCHAR(64)  DEFAULT NULL COMMENT 'SALE=订单号 / TRANSFER=调拨单 / COUNT_LOSS=盘点单',
    reservation_id   VARCHAR(32)  DEFAULT NULL COMMENT 'SALE 时必填：这一单来自哪张预留',
    reason_code      VARCHAR(16)  DEFAULT NULL COMMENT 'SCRAP 必填：BROKEN 损坏 / EXPIRED 过期 / GIFT 赠送 / OTHER。**枚举不是自由文本** —— 自由文本汇总不出「这个月报损了多少」',
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / POSTED / VOIDED',
    total_qty        INT          NOT NULL DEFAULT 0,
    total_cost_minor BIGINT       NOT NULL DEFAULT 0 COMMENT '结转出去的成本合计',
    occurred_at      DATETIME     NOT NULL,
    posted_at        DATETIME     DEFAULT NULL,
    voided_at        DATETIME     DEFAULT NULL,
    operator         VARCHAR(64)  DEFAULT NULL COMMENT '销售出库为 SYSTEM —— 「直接扣库存」在这个模型里不存在，一切变动都有单',
    remark           VARCHAR(255) DEFAULT NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(64)  DEFAULT NULL,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by       VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbound (owner_id, outbound_no),
    KEY idx_outbound_src (owner_id, purpose, source_ref),
    KEY idx_outbound_time (owner_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='出库单：方向恒为负，不带售价';

CREATE TABLE IF NOT EXISTS inv_outbound_line
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    outbound_no     VARCHAR(32) NOT NULL,
    line_no         INT         NOT NULL,
    owner_id        VARCHAR(32) NOT NULL,
    item_id         VARCHAR(32) NOT NULL,
    qty             INT         NOT NULL,
    uom             VARCHAR(16) NOT NULL,
    unit_cost_minor BIGINT      DEFAULT NULL COMMENT '★ 出库时结转的成本**快照**。成本会变，历史出库单跟着现价变等于历史毛利每天都在动',
    batch_no        VARCHAR(32) DEFAULT NULL COMMENT '留位',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL COMMENT '谁建的。业务键：商家账号 / 运营账号 / SYSTEM',
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbound_line (outbound_no, line_no),
    KEY idx_outbound_line_item (owner_id, item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='出库单行';


-- ─────────────────────────────────────────────────────────────────────────────
-- 11. 盘点单
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inv_stock_count
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    count_no    VARCHAR(32)  NOT NULL,
    owner_id    VARCHAR(32)  NOT NULL,
    location_id VARCHAR(32)  NOT NULL,
    scope       VARCHAR(16)  NOT NULL DEFAULT 'SELECTED' COMMENT 'ALL 全店 / CATEGORY 按类 / SELECTED 指定',
    status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / COUNTING / POSTED / VOIDED',
    started_at  DATETIME     DEFAULT NULL COMMENT '锁账面数的那一刻',
    posted_at   DATETIME     DEFAULT NULL,
    gain_inbound_no  VARCHAR(32) DEFAULT NULL COMMENT '过账时生成的盘盈入库单',
    loss_outbound_no VARCHAR(32) DEFAULT NULL COMMENT '过账时生成的盘亏出库单',
    operator    VARCHAR(64)  DEFAULT NULL,
    remark      VARCHAR(255) DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(64)  DEFAULT NULL,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_count (owner_id, count_no),
    KEY idx_count_loc (owner_id, location_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='盘点单：盘点自己不改余额，盈亏各生成一张单';

CREATE TABLE IF NOT EXISTS inv_stock_count_line
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    count_no    VARCHAR(32) NOT NULL,
    line_no     INT         NOT NULL,
    owner_id    VARCHAR(32) NOT NULL,
    item_id     VARCHAR(32) NOT NULL,
    book_qty    INT         NOT NULL COMMENT '★ 开始盘点那一刻的账面数**快照**。不快照的话，从开盘到过账之间正常卖掉的量会被算成盘亏',
    counted_qty INT         DEFAULT NULL COMMENT '实盘数。未录时为空',
    diff_qty    INT         DEFAULT NULL COMMENT '= counted_qty - book_qty。落库便于导出，diff=0 的行不生成任何单据',
    reason_code VARCHAR(16) DEFAULT NULL COMMENT '差异原因',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL COMMENT '谁建的。业务键：商家账号 / 运营账号 / SYSTEM',
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_count_line (count_no, line_no),
    KEY idx_count_line_item (owner_id, item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='盘点单行';


-- ─────────────────────────────────────────────────────────────────────────────
-- 12. 调拨单
-- ─────────────────────────────────────────────────────────────────────────────
-- 一定生成两张单，即使同城当场送到。省掉一张的话，将来加在途要改历史数据。
CREATE TABLE IF NOT EXISTS inv_transfer_order
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    transfer_no        VARCHAR(32)  NOT NULL,
    owner_id           VARCHAR(32)  NOT NULL,
    from_location_id   VARCHAR(32)  NOT NULL,
    to_location_id     VARCHAR(32)  NOT NULL COMMENT '与 from 不同，且必须同一个业主 —— 跨业主的移动不是调拨，是买卖',
    status             VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / SHIPPED / RECEIVED / VOIDED',
    shipped_outbound_no VARCHAR(32) DEFAULT NULL COMMENT '发出时生成：from -> TRANSIT',
    received_inbound_no VARCHAR(32) DEFAULT NULL COMMENT '收到时生成：TRANSIT -> to',
    shipped_at         DATETIME     DEFAULT NULL,
    received_at        DATETIME     DEFAULT NULL,
    operator           VARCHAR(64)  DEFAULT NULL,
    remark             VARCHAR(255) DEFAULT NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(64)  DEFAULT NULL,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_transfer (owner_id, transfer_no),
    KEY idx_transfer_status (owner_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='调拨单：编排一出一入，中间停在 TRANSIT';


-- ─────────────────────────────────────────────────────────────────────────────
-- 13. 事件出站
-- ─────────────────────────────────────────────────────────────────────────────
-- **独立库意味着用不了平台的 sys_outbox**，得自己带一份。
-- 这是"独立交付"这条约束顺带产生的成本，如实记在这里。
CREATE TABLE IF NOT EXISTS inv_outbox
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    event_no      VARCHAR(64) NOT NULL,
    owner_id      VARCHAR(32) NOT NULL,
    event_type    VARCHAR(32) NOT NULL COMMENT 'DocumentPosted / StockBalanceChanged / ReservationExpired / LowStockDetected',
    payload       TEXT        NOT NULL COMMENT 'JSON',
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENT / FAILED',
    retry_count   INT         NOT NULL DEFAULT 0,
    next_retry_at DATETIME    DEFAULT NULL,
    last_error    VARCHAR(512) DEFAULT NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at       DATETIME    DEFAULT NULL,
    created_by      VARCHAR(64) DEFAULT NULL COMMENT '谁建的。业务键：商家账号 / 运营账号 / SYSTEM',
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event (event_no),
    KEY idx_outbox_pending (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='领域事件出站：投递侧将来换 MQ，写入侧不动';

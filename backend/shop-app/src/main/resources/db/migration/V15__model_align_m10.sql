-- M10.1 契约 × 库 对齐：补齐已在三端实现、但库里没有承载的字段（待办 T-2 ~ T-5）。
--
-- 来源：`npm run gen:model-align` 报出的「契约有、库里没有列」。
-- 每一条都是**前端已经在渲染、后端却填不出来**的字段 —— 不补的话接后端时才会发现，
-- 而那时页面早已按契约写完。
--
-- 积分域（T-1）不在本次范围，单独一版落地：它要连账户表与流水表一起建，
-- 混在这里会让本次迁移既加列又建表，回滚粒度太粗。

-- ─────────────────────────────────────────── T-2 称重差价（生鲜核心）
--
-- 生鲜按重计价：下单按标称重量收钱，实际称重后产生差价（补款或退款）。
-- `prd_goods.weighed` 标记早就有，但订单行没有标称重量、没有实称结果、
-- 订单没有差价金额 —— 差价既算不出来也无处记账，而 C 端订单详情页已经在渲染它。
ALTER TABLE ord_item ADD COLUMN nominal_gram INT NULL COMMENT '标称克重：FRESH 按重计价时下单锁定的重量';
ALTER TABLE ord_item ADD COLUMN weighed TINYINT NOT NULL DEFAULT 0 COMMENT '是否已实际称重；未称重时差价为 0';

-- 买赠的赠品行：价格为 0、不参与计价，但**必须能认出来** ——
-- 认不出来的话，退款按行退会把赠品也退钱，对账就差了
ALTER TABLE ord_item ADD COLUMN is_gift TINYINT NOT NULL DEFAULT 0 COMMENT '赠品行：价格为 0，不参与计价，履约时随单发出';

-- 正=补款 负=退款。**允许为负**，所以不能用 UNSIGNED
-- 新列直接用 `_minor`（T-11 的收敛方向），不再延续 ord_* 的 `_amount` 旧写法 ——
-- 新增时就用目标命名，将来那次批量改名要动的列就少一个
ALTER TABLE ord_sub_order ADD COLUMN weigh_adjust_minor BIGINT NOT NULL DEFAULT 0 COMMENT '称重差价（分）：正=补款 负=退款';

-- ─────────────────────────────────────────── T-3 自提点作为成团单位
--
-- 团购拼的是「一车送到一个点」的成本，自提点就是成团范围（ADR-005）；
-- 求团的范围同样是自提点/小区。两张表都没有 pickup_no —— 团按什么范围成、
-- 货送到哪个点，库里表达不了。
ALTER TABLE mkt_group_buy ADD COLUMN pickup_no VARCHAR(64) NULL COMMENT '成团范围：自提点。团购拼的是一车送到一个点的成本';

-- 团列表按自提点查是**主查询路径**（C 端首页的团购楼层就是这个查询）
CREATE INDEX idx_group_pickup_status ON mkt_group_buy (pickup_no, status);

ALTER TABLE mkt_request ADD COLUMN pickup_no VARCHAR(64) NULL COMMENT '需求所属自提点/小区 —— 邻里的意义就在于此';
ALTER TABLE mkt_request ADD COLUMN budget_minor BIGINT NULL COMMENT '发起人心理价位（分），可不填；填了商家报价更有的放矢';
ALTER TABLE mkt_request ADD COLUMN group_no VARCHAR(64) NULL COMMENT 'MATCHED 后回填：选定报价转成的正式团';

CREATE INDEX idx_request_pickup_status ON mkt_request (pickup_no, status);

-- ─────────────────────────────────────────── T-4 商家服务范围（可见性核心约束）
--
-- 邻里购物最硬的约束是**商家有服务半径**：隔壁区的生鲜店送不到我的自提点。
-- service_scope 决定这家店的货在 C 端能被谁看到 —— 选错不是展示问题，
-- 是下单后提不了货 → 退款。此前库里一个范围字段都没有，可见性过滤没有依据。
ALTER TABLE usr_merchant ADD COLUMN service_scope VARCHAR(16) NOT NULL DEFAULT 'COMMUNITY' COMMENT 'COMMUNITY/CITY/PLATFORM：决定这家店的货在 C 端能被谁看到';
ALTER TABLE usr_merchant ADD COLUMN service_city_code VARCHAR(32) NULL COMMENT '仅 scope=CITY 时有意义';

-- 覆盖多社区是**多对多**，不能塞进 usr_merchant 的一个 JSON 列：
-- C 端「本社区可见商家」是高频查询，要能按 community_no 走索引反查。
CREATE TABLE IF NOT EXISTS usr_merchant_community
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_no  VARCHAR(64) NOT NULL,
    community_no VARCHAR(64) NOT NULL,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)  NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)  NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_merchant_community (merchant_no, community_no),
    -- 反查：这个社区能看到哪些商家 —— C 端首页每次都要问
    KEY idx_community (community_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商家覆盖的社区（scope=COMMUNITY 时生效）';

-- ─────────────────────────────────────────── T-5 订单履约字段
--
-- appointment_at 与 group_no 此前已在**请求体层**发现后端不认（契约漂移清单有记录），
-- 库侧确认列也没有 —— 同一个洞的第二处独立证据。
-- 接上去的后果是**静默丢数据**：参团单变普通单、预约单没有时段，不报错，就是数据没了。
ALTER TABLE ord_sub_order ADD COLUMN appointment_at BIGINT NULL COMMENT 'APPOINTMENT 履约：预约开始时间戳';
ALTER TABLE ord_sub_order ADD COLUMN group_no VARCHAR(64) NULL COMMENT '参团下单时的团号；邻里自提的核销作用域靠它裁剪';
ALTER TABLE ord_sub_order ADD COLUMN express_no VARCHAR(64) NULL COMMENT 'EXPRESS 履约：快递单号，发货后才有';
ALTER TABLE ord_sub_order ADD COLUMN buyer_nickname VARCHAR(64) NULL COMMENT '下单人昵称快照：团长视角（分拣单/核销台）要看得见是谁的单';
ALTER TABLE ord_sub_order ADD COLUMN reviewed TINYINT NOT NULL DEFAULT 0 COMMENT '是否已评价：一单一评的判据';

-- 核销作用域按团裁剪，是邻里自提核销台的主查询
CREATE INDEX idx_sub_order_group ON ord_sub_order (group_no);

-- redeem_code 不新增列：V6 已把 pickup_code 改名为 verify_code，
-- 且注释写明「自提码/核销码/兑换码三态共用一个字段」——
-- 再加一列 redeem_code 就是把同一个语义拆成两处，两处必然有一处忘了写。
-- 契约侧的 Order.redeemCode 与 Order.verifyCode 需要收敛成一个（见待办 T-16）。

-- ─────────────────────────────────────────── 零散缺列
--
-- 下面几条彼此无关，共同点是「契约在用、库里没有」。

-- 全市范围的商家靠它判定可达（Community.cityCode）
ALTER TABLE cmt_community ADD COLUMN city_code VARCHAR(32) NULL COMMENT '所属城市编码：scope=CITY 的商家靠它判定可达';

-- 用户把争议上升到平台时填的申诉理由。没有它，平台裁决台看不到用户的说法，
-- 只能看到商家的驳回理由 —— 单方面材料做不了裁决（AfterSale.disputeReason）
ALTER TABLE ord_after_sale ADD COLUMN dispute_reason VARCHAR(512) NULL COMMENT '上升平台时用户填的申诉理由：裁决要听双方';

-- 券的适用范围文案，如「仅限张记生鲜」。展示用，实际校验在服务端（Coupon.scopeDesc）
ALTER TABLE mkt_coupon ADD COLUMN scope_desc VARCHAR(255) NULL COMMENT '适用范围文案（展示用，实际校验在服务端）';

-- 邻居家不能一直堆着货（B15）：约定取货时段是 NEIGHBOR 类自提点的必要约束
ALTER TABLE cmt_pickup_point ADD COLUMN time_slot VARCHAR(64) NULL COMMENT '约定取货时段：邻居家不能一直堆着货（B15）';

-- 履约服务费按**件**计，与 service_fee_rate（费率）是两个口径，不能互相换算。
-- R15 未定哪种口径为准，两列并存，由结算侧按配置取其一（见待办 T-17）。
ALTER TABLE cmt_pickup_point ADD COLUMN service_fee_per_item_minor BIGINT NOT NULL DEFAULT 0 COMMENT '按件履约服务费（分）：邻里自提必须为 0，否则承接的邻居就是团长';

# 门店行业 Plan、B 端商城和端访问权益：数据库设计

日期：2026-11-03。状态：逻辑／字段级设计稿，不是可直接执行的迁移 SQL。

上游：[总体方案](../../superpowers/specs/2026-11-03-industry-plan-mall-design.md)。沿用现有 MySQL、业务号、租户隔离、审计和乐观锁约定。下列类型为设计类型，正式 DDL 需按项目数据库规范生成并验证。

详细阅读入口：[完整表清单与逐字段字典](./门店行业Plan商城-数据库字典.md)（33张候选表、621行展开字段）及其中的全局／5张分域ER图；本文保留事务、业务关系与设计决策。字段与图的结构化源为 [industry-plan-mall-dictionary.json](./schema/industry-plan-mall-dictionary.json)。

先评审整体结构可读 [数据库表清单与说明](./门店行业Plan商城-数据库字典.md)，按五个逻辑域列出表名、中文名、用途、主要关联和复用边界，再进入字段字典。

叠加规则已确认：同门店同 code 的所有来源有限时长累计。依总体方案 §5，Plan 保持固定组合周期，独立端包的未用时长排在该端空闲时间内；后续 Plan 变化可拆分并重排端包时间片，但不能减少已买时长。FREE 永久端不参与有限累计。

## 1. 通用约定

本模块共33项候选表定义：30张新建候选、3项优先复用的设施候选（平台库存、inbox、outbox），保留7张直接引用的既有表。第五逻辑域含4张，新增到期提醒仅负责候选与投递记录，不承载资金价值或端访问权威，也不新增账号体系。

- 每表默认有 `id BIGINT AUTO_INCREMENT` 库内主键、`tenant_no VARCHAR(32)`、`created_at/updated_at DATETIME`、`created_by/updated_by VARCHAR(64)`、`version BIGINT`、`deleted TINYINT DEFAULT 0`，与现有 BaseEntity 的自增主键、审计、乐观锁和逻辑删除约定对齐。id 不进入客户端URL或业务响应；业务号由业务编号生成器生成。系统审计人使用既有 SYSTEM 约定。
- deleted 是技术通用字段，不代表允许删除业务事实。仅无引用配置草稿可按归档流程置1；已发布或已被订单／权益引用的配置以状态停售／归档，仍可查历史。支付、订单、权益、排期历史与账本记录不得物理删除或置 deleted=1，纠错追加逆向记录或更新明确业务状态。
- tenant_no 的隔离是本目标合同的要求；当前 BaseEntity 注释记录一期为 MAIN、未开启行级隔离，正式接入时必须校验实现，不能把预留字段误认为已有完整多租户能力。
- 以下唯一键、引用与业务查询均隐含 `tenant_no`。所有业务号为 `VARCHAR(64)`，端／权益 code 为 `VARCHAR(64)`，业务类型为 `VARCHAR(32)`。
- 时间业务字段统一 `BIGINT` UTC epoch 毫秒，与项目已有 `start_at/expire_at` 接口保持一致。有效期为左闭右开 `[start_at, end_at)`；只有 FREE 等明确永久资格允许 `end_at=NULL`。
- 金额字段 `BIGINT` 最小货币单位，附 `currency VARCHAR(8)`；首期只允许同币种转换，不做汇兑。单位精度按项目市场配置处理，示例人民币按分。
- 不使用浮点金额。配置、订单、权益都保留业务号；引用同时校验租户、主体、门店、经营类型和币种。
- 发布版本、订单成交快照和金额流水不可原地修改；纠错以新版本、反向流水、来源撤销表达。
- 外键是否物理创建沿用现有迁移规范；逻辑引用必须存在，不能因为没有物理外键而免掉发布／交付校验。

## 2. 现有表调整与保留

| 表 | 变化 |
|---|---|
| `mch_store` | 增 `business_type_code VARCHAR(32)`、`commerce_model VARCHAR(16)`（LEGACY／STORE_V1）、`business_type_changed_at BIGINT`；先可空回填，新建强制非空 |
| `mch_entity` / `sys_industry` | 保留进件行业语义，不覆盖为产品经营类型 |
| `mch_entity_plan` / `sys_merchant_plan_def` | 兼容存量主体合同；增加迁移外部映射，不直接复用为每店订阅 |
| `sys_function_point` / `sys_role_point` | 保留端内岗位权限，不增加菜单／按钮商业权益 code |
| 登录／客户端／终端注册与审计基础表 | 优先复用现有设施；补业务端绑定与签发上下文的可信来源，不另建账号池 |

`commerce_model` 决定该门店哪套模型是权威，不能同时叠加两套续费。建店、付费和续费都必须校验该值。

## 3. 平台经营类型、业务端与 Plan：5 张表

### 3.1 `sys_business_type` 门店经营类型

字段：`business_type_code`、`name VARCHAR(64)`、`enabled TINYINT`、`sort INT`、`plan_catalog_version BIGINT`、`remark VARCHAR(512)`。

唯一键：`(business_type_code)`。建议种子 RETAIL／FOOD／BEAUTY，不能直接从 `sys_industry` 全量复制。发布某行业 Plan 时锁该行，整体校验三档和关联商品再增加目录版本。

### 3.2 `sys_business_end` 业务端与唯一访问权益 code

字段：`end_code`、`entitlement_code`、`name VARCHAR(64)`、`scope VARCHAR(16)`（STORE／SYSTEM）、`allowed_business_types JSON`、`release_status VARCHAR(16)`（PLANNED／VERIFIED／RETIRED）、`sale_enabled TINYINT`、`bootstrap_required TINYINT`、`enabled TINYINT`、`route_policy_ref VARCHAR(128)`。

唯一键：`(end_code)`、`(entitlement_code)`。本期一业务端恰好一个访问权益 code，两者一对一；保留两个名称以区分应用标识与商业授权标识。`bootstrap_required=1` 的经营台／商城入口不能被 FREE 移除。

`scope=SYSTEM` 不可关联商家可售 SKU；未 VERIFIED 的端不可发布销售。适用类型 JSON 仅为小型稳定配置集合，发布时校验类型存在。route_policy_ref 指向服务端可信路由策略，不是由运营任意填写可执行代码。

### 3.3 `sys_industry_plan` 行业 Plan 稳定定义

字段：`plan_no`、`business_type_code`、`tier_code VARCHAR(16)`（FREE／PRO／CHAIN）、`rank INT`（0／1／2）、`current_version_no`、`sale_status VARCHAR(16)`、`sort INT`。

唯一键：`(plan_no)`、`(business_type_code, tier_code)`、`(business_type_code, rank)`。每行业三个稳定定义；一个定义可有多个历史版本，停售不删除。

### 3.4 `sys_industry_plan_version` Plan 不可变发布版本

字段：`plan_version_no`、`plan_no`、`revision INT`、`display_name VARCHAR(64)`、`summary VARCHAR(512)`、`status VARCHAR(16)`（DRAFT／PUBLISHED／ARCHIVED）、`policy_version VARCHAR(64)`、`published_at BIGINT`、`published_by`、`change_note VARCHAR(1024)`。

唯一键：`(plan_version_no)`、`(plan_no, revision)`。Plan 身份、类型和档位可由稳定定义解析，并在订单保存快照；发布后内容不可变。FREE 发布同样有版本，不需要伪造免费支付单。

### 3.5 `sys_plan_version_end` Plan 版本与业务端权益关系

字段：`plan_version_no`、`end_code`、`entitlement_code`、`sort INT`。

唯一键：`(plan_version_no, entitlement_code)`。关联整端权益，不关联菜单、按钮、RBAC permission。端 code 与权益 code 必须与端目录一对一关系一致。没有独立的组件价格，价格归商城 SKU。同一 Plan 内重复配置 code 应拒绝；FREE 永久包含的 code 在付费 Plan 中不重复生成有限 grant。

## 4. 商城商品与库存：5 张表

### 4.1 `mall_product` 商品 SPU

字段：`product_no`、`category_code VARCHAR(32)`、`product_type VARCHAR(16)`（PLAN／END_ACCESS／HARDWARE／BUNDLE）、`name VARCHAR(128)`、`description TEXT`、`media JSON`、`allowed_business_types JSON`、`sale_status VARCHAR(16)`、`sort INT`。

唯一键：`(product_no)`。分类首期可用平台字典，不额外造分类树服务。SPU 图文用于列表；最终成交的关键内容仍冻结到 SKU 版本和订单。

### 4.2 `mall_sku` 商品规格稳定身份

字段：`sku_no`、`product_no`、`sku_name VARCHAR(128)`、`current_version_no`、`sale_status VARCHAR(16)`、`max_per_order INT`。

唯一键：`(sku_no)`。同一 Plan 的 30 天和 365 天是两个 SKU；不新增 Plan 档位。

### 4.3 `mall_sku_version` 可成交规格版本

字段：`sku_version_no`、`sku_no`、`revision INT`、`status VARCHAR(16)`、`currency`、`list_price_minor BIGINT`、`sale_price_minor BIGINT`、`allowed_business_types JSON`、`refund_policy_snapshot JSON`、`published_at BIGINT`。

唯一键：`(sku_version_no)`、`(sku_no, revision)`。停售不改变版本；改价创建新版本。发布 Plan 时须同时校验推荐 SKU 对应正确的 Plan 版本，切换 current 指针作为同一发布事务，避免详情和实际购买内容不一致。

### 4.4 `mall_sku_component` SKU 版本交付清单

字段：`component_no`、`sku_version_no`、`component_seq INT`、`component_type VARCHAR(16)`（PLAN／END_ACCESS／HARDWARE）、`plan_version_no NULL`、`entitlement_code NULL`、`hardware_sku_no NULL`、`period_days INT NULL`、`unit_count INT`、`allocation_weight BIGINT`、`is_gift TINYINT`、`activation_policy VARCHAR(24)`（ON_FULFILLMENT／AFTER_PREVIOUS）。

唯一键：`(component_no)`、`(sku_version_no, component_seq)`。三种目标字段按组件类型互斥且必填；数字组件期限必须正数，硬件不填期限。`unit_count` 对数字表示周期份数，对硬件表示件数，必须在商品说明明确。

一份基础 Plan 商品只能指向一个 Plan 版本；套餐最多一个基础 Plan 家族。禁止嵌套套餐，只保留发布时展开后的叶子组件。非赠送项的分摊权重大于 0；赠送项分摊金额为 0。

### 4.5 `mall_sku_stock` 平台硬件库存

字段：`hardware_sku_no`、`warehouse_no`、`on_hand_qty BIGINT`、`reserved_qty BIGINT`。

唯一键：`(hardware_sku_no, warehouse_no)`；约束 `0 <= reserved_qty <= on_hand_qty`。平台商城库存与门店对消费者销售的库存不是同一库存主体，优先复用库存域合同，以平台仓身份操作；若已有对应权威库存表，正式实现删除本候选表，不建立第二本库存账。

## 5. 报价、订单、交付与退款：10 张表

### 5.1 `mall_quote` 不可变报价

字段：`quote_no`、`store_no`、`entity_no`、`buyer_user_no`、`intent VARCHAR(16)`（PURCHASE／RENEW／UPGRADE）、`business_type_code`、`currency`、`valuation_at BIGINT`、`expires_at BIGINT`、`account_version BIGINT`、`credit_account_version BIGINT`、`pricing_policy_version VARCHAR(64)`、`stacking_policy_version VARCHAR(64)`（END_CODE_ACCUMULATE_V1）、`request_hash VARCHAR(128)`、`line_snapshot JSON`、`conversion_snapshot JSON`、`schedule_preview_snapshot JSON`、`price_minor BIGINT`、`new_conversion_minor BIGINT`、`credit_used_minor BIGINT`、`cash_due_minor BIGINT`、`status VARCHAR(16)`（OPEN／CONSUMED／EXPIRED）。

唯一键：`(quote_no)`；索引 `(store_no, created_at)`、`(status, expires_at)`。JSON 用于冻结报价输入与逐来源明细，不作为权益可用性的权威查询表。

line_snapshot 必须包含逐组件成交额、`creditEligibleMinor`（仅 Plan 组件折后额）、逐周期现金／抵扣分摊、历史批次消费顺序；报价中 `credit_used_minor <= creditEligibleMinor`，不能用整单金额作为混合套餐的抵扣上限。

schedule_preview_snapshot 按 code 返回原合计到期日、新增时长、预计合计到期日和受影响来源；首购起点随实际交付时刻确定，预览必须标明 estimated。订单沿 quote_no 冻结累计规则与预览，最终交付时间片另记权威版本。

account_version 明确取门店账户的 entitlement_version，而非占用写入也会变化的通用 version；创建占用不改变权益版本。报价创建检查该版本，交付时校验占用及关联来源，避免自己的占用更新被误判为外部权益变更。

### 5.2 `mall_order` 门店商城订单

字段：`order_no`、`store_no`、`entity_no`、`buyer_user_no`、`business_type_code`、`quote_no`、`intent`、`currency`、`gross_minor BIGINT`、`discount_minor BIGINT`、`deal_minor BIGINT`、`credit_used_minor BIGINT`、`cash_due_minor BIGINT`、`cash_paid_minor BIGINT`、`cash_refunded_minor BIGINT`、`pay_status VARCHAR(16)`、`fulfillment_status VARCHAR(16)`、`refund_status VARCHAR(24)`、`pay_deadline BIGINT`、`paid_at BIGINT NULL`、`closed_at BIGINT NULL`、`idempotency_key VARCHAR(128)`、`request_hash VARCHAR(128)`。

唯一键：`(order_no)`、`(quote_no)`、`(buyer_user_no, idempotency_key)`。索引 `(store_no, created_at, id)`、`(pay_status, pay_deadline)`。

守恒：`deal = gross - discount = credit_used + cash_due`；`0 <= cash_refunded <= cash_paid <= cash_due`（多付渠道异常进退款处理，不改成交额）。当前门店不得通过请求体换到其他主体；用户级幂等键重复且参数不同返回冲突。

### 5.3 `mall_order_item` 成交行

字段：`order_item_no`、`order_no`、`sku_no`、`sku_version_no`、`quantity INT`、`unit_price_minor BIGINT`、`gross_minor BIGINT`、`discount_minor BIGINT`、`deal_minor BIGINT`、`sku_snapshot JSON`。

唯一键：`(order_item_no)`。各行金额合计等于订单。

### 5.4 `mall_order_component` 实际交付与金额分摊叶子项

字段：`order_component_no`、`order_item_no`、`component_no`、`component_type`、`target_snapshot JSON`、`unit_count INT`、`allocated_deal_minor BIGINT`、`allocated_cash_minor BIGINT`、`allocated_credit_minor BIGINT`、`eligible_basis_minor BIGINT`、`is_gift TINYINT`、`fulfillment_status VARCHAR(16)`。

唯一键：`(order_component_no)`、`(order_item_no, component_no)`。组件分摊合计与行金额相等；只有 Plan 或可转换数字来源可以有资格价值，本稿只有 Plan 升级可转换。`eligible_basis_minor` 不得把同一份现金分别分给 Plan 内每个端。

### 5.5 `mall_payment_ref` 平台支付关联与多次尝试

字段：`payment_attempt_no`、`order_no`、`pay_order_no`、`receiver_account_no`、`payment_business_type VARCHAR(32)`（BIZ_MALL）、`currency`、`amount_minor BIGINT`、`status VARCHAR(24)`、`provider_paid_at BIGINT NULL`。

唯一键：`(payment_attempt_no)`、`(pay_order_no)`。索引 `(order_no, created_at)`。实际资金事实仍以支付域为准；同一订单可失败后重试，但任何时刻至多一个未关闭的支付意图。重复成功收款进入异常退款，不能再次交付。

### 5.6 `mall_fulfillment_task` 数字／实物交付任务

字段：`task_no`、`order_component_no`、`unit_seq INT`、`task_type VARCHAR(16)`（PLAN／END_ACCESS／SHIPMENT）、`status VARCHAR(16)`、`attempt_count INT`、`next_retry_at BIGINT`、`result_no`、`last_error_code VARCHAR(64)`、`lease_until BIGINT NULL`。

唯一键：`(task_no)`、`(order_component_no, unit_seq, task_type)`；索引 `(status, next_retry_at)`。数字组件每周期一任务；插入权益周期时再次以任务号唯一去重。硬件件数可合为同一发货任务，物流按实际包裹拆分。

### 5.7 `mall_shipment` 硬件包裹

字段：`shipment_no`、`order_no`、`shipment_items JSON`、`address_snapshot JSON`、`carrier_code VARCHAR(32)`、`tracking_no VARCHAR(128)`、`status VARCHAR(24)`、`shipped_at BIGINT NULL`、`signed_at BIGINT NULL`。

唯一键：`(shipment_no)`；索引 `(order_no)`。shipment_items 明确组件号和数量，事务内校验各包裹发货总量不超过购买件数；收货地址是成交快照并执行现有隐私规则。

### 5.8 `mall_refund` 退款协调单

字段：`refund_no`、`order_no`、`store_no`、`reason VARCHAR(1024)`、`policy_snapshot JSON`、`requested_cash_minor BIGINT`、`approved_cash_minor BIGINT`、`restore_credit_minor BIGINT`、`pay_refund_no NULL`、`status VARCHAR(24)`、`idempotency_key VARCHAR(128)`、`reviewed_by`、`reviewed_at BIGINT NULL`。

唯一键：`(refund_no)`、`(order_no, idempotency_key)`、`(pay_refund_no)`；索引 `(status, updated_at)`。状态覆盖 REQUESTED／APPROVED／PROCESSING／SUCCEEDED／REJECTED／UNKNOWN／FAILED，未知必须查原单。

### 5.9 `mall_refund_item` 退款来源明细

字段：`refund_item_no`、`refund_no`、`order_component_no`、`period_no NULL`、`quantity INT`、`cash_refund_minor BIGINT`、`credit_restore_minor BIGINT`、`rights_action VARCHAR(24)`（REVOKE／CLOSE／NONE）、`status VARCHAR(24)`、`adjustment_snapshot JSON`。

唯一键：`(refund_item_no)`；索引 `(order_component_no, status)`。同组件多次部分退款的合计不得超过原可退份额；需锁原组件和订阅账户核验，唯一键不能代替金额上限控制。

### 5.10 `commerce_event_inbox` 外部事实处理去重

字段：`source_system VARCHAR(32)`、`event_id VARCHAR(128)`、`event_type VARCHAR(64)`、`aggregate_no`、`payload_hash VARCHAR(128)`、`status VARCHAR(16)`、`received_at BIGINT`、`processed_at BIGINT NULL`。

唯一键：`(source_system, event_id)`。相同 event_id 不同内容按异常处理；支付回调与查单观察到同一支付事实，还需通过订单支付状态条件更新和交付唯一键消除跨事件重复。

## 6. 门店订阅、端权益、升级与抵扣：9 张表

### 6.1 `mch_subscription_account` 门店订阅串行化账户

字段：`store_no`、`entity_no`、`business_type_code`、`entitlement_version BIGINT`、`pending_change_order_no NULL`、`legacy_contract_ref NULL`、`migration_batch_no NULL`。

唯一键：`(store_no)`。同店 Plan、独立端包及升级排期都先锁此行，避免不存在时间片时无法加行锁。`pending_change_order_no` 保证同店同时只有一个数字权益订单结算占用，覆盖 Plan、独立端包和数字套餐；不阻止既有权益正常使用，纯硬件单不占用。

### 6.2 `mch_subscription_period` 有价值来源的订阅／端包周期

字段：`period_no`、`store_no`、`entity_no`、`business_type_code`、`stream_key VARCHAR(128)`、`source_type VARCHAR(24)`（ORDER／GRANT／FREE／MIGRATION）、`source_ref_no`、`fulfillment_task_no NULL`、`plan_no NULL`、`plan_version_no NULL`、`standalone_entitlement_code NULL`、`tier_code NULL`、`delivered_at BIGINT`、`delivery_seq BIGINT`、`granted_duration_ms BIGINT NULL`、`revoked_duration_ms BIGINT`、`original_start_at BIGINT`、`original_end_at BIGINT NULL`、`effective_start_at BIGINT`、`effective_end_at BIGINT NULL`、`eligible_basis_minor BIGINT`、`cash_basis_minor BIGINT`、`credit_basis_minor BIGINT`、`currency`、`state VARCHAR(24)`（NORMAL／FROZEN／CONVERTED／REVOKED／REFUNDED）、`converted_by_no NULL`、`source_policy_snapshot JSON`。

唯一键：`(period_no)`、`(fulfillment_task_no)`、`(source_type, source_ref_no, stream_key)`；索引 `(store_no, stream_key, state, effective_start_at, effective_end_at)`。ORDER 的 source_ref_no 指向每周期 fulfillment_task_no，不指向可能含多个周期的整组件；人工／迁移来源也必须细分到每周期唯一业务号。

`stream_key`：FREE、BASE_PLAN、`END_ACCESS:{entitlement_code}`。它区分来源类别，不代表同 code 可以分流重叠消耗；实际访问统一按 code 排期。FREE 不作为付费队列末尾。NORMAL 只代表未撤销，不能只看该状态放行。

Plan 的原始时间用于计算购买周期分母，effective 起止是当前固定 Plan 周期，派生端与它一致；退款可重排尚未开始的 Plan，必须保留原始时长和价格。独立端来源的 granted_duration_ms 是原授予量，original 起止只记首次排期，effective 起止仅为当前时间片包络，不保证连续，不能直接用于准入或估值。

Plan 折价分子取最新 effective_start/end，分母保留原周期时长；Plan 有效周期时长等于原周期时长。独立端包不参与 Plan 折价，剩余量按时间片精确计算并保留。每个 period 的现金／抵扣基数由组件逐份分配，所有同组件周期基数之和等于组件；赠送周期独立且基数为 0。

### 6.3 `mch_store_end_grant` 端访问来源时间片

字段：`grant_no`、`store_no`、`period_no`、`end_code`、`entitlement_code`、`schedule_revision BIGINT`、`segment_seq INT`、`schedule_event_no`、`start_at BIGINT`、`end_at BIGINT NULL`、`state VARCHAR(16)`（NORMAL／SUPERSEDED／REVOKED／FROZEN）、`revoked_at BIGINT NULL`、`reason VARCHAR(512)`。

唯一键：`(grant_no)`、`(period_no, entitlement_code, schedule_revision, segment_seq)`；索引 `(store_no, entitlement_code, state, start_at, end_at)`。没有可折价金额列，防止把 Plan 价值在多个端重复计算。有效性同时检查来源状态与时间片状态；同 code 当前排期的有限 NORMAL 时间片不得重叠，不靠覆盖合并来消耗两份时长。

一份独立端包可以被新 Plan 分成多个 grant 时间片，均关联同 period。重排只替换未使用部分；旧未来片标 SUPERSEDED，历史已用片保留，必要时在重排时刻截出已用前缀并记完整前后快照。NORMAL 历史片与未来片均参与时长守恒，SUPERSEDED 片不再计入。

Plan 一周期对每个非 FREE 永久 code 生成一条与父周期同起止的有限片；FREE 永久片明确 end_at=NULL。来源级 NORMAL 不意味着其 effective 包络内每一刻都可用，准入必须读对应时间片，避免分片间空档被误放行。

### 6.4 `mch_subscription_reservation` 报价结算占用

字段：`reservation_no`、`store_no`、`order_no`、`quote_no`、`subscription_version BIGINT`、`credit_account_version BIGINT`、`valuation_at BIGINT`、`pay_deadline BIGINT`、`status VARCHAR(24)`（HELD／PAYMENT_UNKNOWN／CONSUMED／RELEASED）、`source_periods_snapshot JSON`、`reserved_credit_minor BIGINT`。

唯一键：`(reservation_no)`、`(order_no)`；索引 `(status, pay_deadline)`。所有数字权益订单均持有该占用，直至本单全部数字组件及排期交付完成或支付域确认无付款关闭；独立端包也会影响同 code 合计时间，不能绕过。是否已有占用由锁住的 subscription_account.pending_change_order_no 判断；不要用 `(store_no,status)` 唯一键，否则无法保留多次历史 RELEASED。

### 6.5 `mch_subscription_conversion` 一次升级转换

字段：`conversion_no`、`store_no`、`order_no`、`valuation_at BIGINT`、`effective_at BIGINT`、`new_period_no`、`currency`、`converted_minor BIGINT`、`credit_consumed_minor BIGINT`、`cash_paid_minor BIGINT`、`policy_version VARCHAR(64)`、`status VARCHAR(16)`。

唯一键：`(conversion_no)`、`(order_no)`。所有未来续费周期都在转换明细覆盖后关闭，不只处理当前一条。

这里 cash_paid_minor 明确取新 Plan 组件分摊的现金金额，等于 new_period_no 对应周期的 cash_basis_minor；混合套餐的硬件／独立端包现金不计入此值。整单现金仍在 mall_order.cash_paid_minor 记录，两者不能混用。

首期升级只允许一个新 Plan 周期，因此 new_period_no 为单值；报价器拒绝升级数量不为 1 或套餐 Plan 组件 unit_count 不为 1。普通续费不受此限制。

### 6.6 `mch_subscription_conversion_item` 旧周期折价凭据

字段：`conversion_no`、`source_period_no`、`basis_minor BIGINT`、`original_duration_ms BIGINT`、`remaining_duration_ms BIGINT`、`converted_minor BIGINT`。

唯一键：`(conversion_no, source_period_no)`、`(source_period_no)`。一个旧周期最多被转换一次；若要撤销转换，用审计反向补偿，不删除这条唯一凭据重新套现。新升级产生新周期，允许其未来再作为来源。

### 6.7 `mch_subscription_credit_account` 门店订阅抵扣账户

字段：`store_no`、`currency`、`balance_minor BIGINT`、`reserved_minor BIGINT`。

唯一键：`(store_no, currency)`；约束 `0 <= reserved_minor <= balance_minor`。不是门店储值资金账户，不可提现、不可给消费者消费、不可购买硬件。

### 6.8 `mch_subscription_credit_lot` 抵扣余额来源批次

字段：`credit_lot_no`、`store_no`、`currency`、`source_type VARCHAR(24)`（CONVERSION／REFUND_RESTORE／MIGRATION_ADJUST）、`source_ref_no`、`issued_minor BIGINT`、`remaining_minor BIGINT`、`reserved_minor BIGINT`、`parent_provenance JSON`、`expires_at BIGINT NULL`。

唯一键：`(credit_lot_no)`、`(source_type, source_ref_no)`；索引 `(store_no, currency, expires_at, id)`。建议不过期。退款恢复的批次 parent_provenance 指向原消耗批次及额度，不能把优惠或赠送变成可提现现金。

### 6.9 `mch_subscription_credit_ledger` 抵扣账本及批次分配

字段：`ledger_no`、`store_no`、`currency`、`credit_lot_no`、`event_type VARCHAR(32)`（ISSUE／RESERVE／RELEASE／CONSUME／RESTORE／ADJUST）、`biz_ref_no`、`event_seq INT`、`amount_minor BIGINT`、`balance_delta_minor BIGINT`、`reserved_delta_minor BIGINT`、`order_component_no NULL`、`reversal_of_ledger_no NULL`、`reason VARCHAR(1024)`。

唯一键：`(ledger_no)`、`(event_type, biz_ref_no, credit_lot_no, event_seq)`；索引 `(store_no, created_at, id)`、`(order_component_no)`。逐批次消费同时充当抵扣来源分配表，不再维护另一份重复映射。

升级新生成旧价值先记 ISSUE，再与原有可用余额共同 CONSUME；已有余额的预留先 RELEASE 后 CONSUME，在同事务内完成。余额账户、批次和账本之和必须能相互核对。

先消耗本次转换批次，再消费报价锁定的已预留历史批次；逐组件抵扣不得超过 Plan 成交份额，硬件／端包为 0。历史余额预留额为 `max(0, 本单抵扣-本次转换价值)`，不能只预留金额却任意改用未预留批次。

## 7. 可靠事件、排期历史、迁移与到期提醒：4 张表

### 7.1 `commerce_outbox` 事务消息

字段：`event_no`、`aggregate_type VARCHAR(32)`、`aggregate_no`、`aggregate_version BIGINT`、`event_type VARCHAR(64)`、`payload JSON`、`status VARCHAR(16)`、`retry_count INT`、`next_retry_at BIGINT`。

唯一键：`(event_no)`、`(aggregate_type, aggregate_no, aggregate_version, event_type)`；索引 `(status, next_retry_at)`。与权益和订单状态同事务落库，提交后驱动缓存失效、通知和对账。项目若已有通用 outbox，使用既有表并增加事件类型。

### 7.2 `mch_subscription_migration_map` 旧合同门店分摊

字段：`migration_batch_no`、`legacy_entity_plan_id BIGINT`、`legacy_contract_ref`、`store_no`、`new_period_no`、`allocated_basis_minor BIGINT`、`evidence_ref VARCHAR(256) NULL`、`legacy_snapshot JSON`、`validation_status VARCHAR(24)`、`cutover_at BIGINT NULL`。

唯一键：`(legacy_entity_plan_id, store_no, migration_batch_no)`、`(new_period_no)`。锁旧合同核验全部门店分摊合计不超历史可核实价值；无金额证据时权益可迁入，抵扣基数置 0，保留待核验标记。

### 7.3 `mch_end_schedule_event` 同 code 排期变更历史

字段：`schedule_event_no`、`store_no`、`entitlement_code`、`entitlement_version BIGINT`、`operation_ref_no`、`reason_type VARCHAR(24)`（PURCHASE／RENEW／UPGRADE／REFUND／REVOKE／MIGRATION）、`effective_at BIGINT`、`before_snapshot JSON`、`after_snapshot JSON`、`duration_check_snapshot JSON`、`stacking_policy_version VARCHAR(64)`。

唯一键：`(schedule_event_no)`、`(store_no, entitlement_code, entitlement_version)`、`(operation_ref_no, entitlement_code)`。同一操作同 code 的全部分片重排聚合为一条事件。记录各来源已用、未用、撤销时长及重排前后片号，和数字交付／权益版本同事务写入；不可用只存最终到期日的日志替代。

### 7.4 `commerce_expiry_notice` 门店业务端到期提醒与投递记录

字段：`notice_no VARCHAR(64)`、`store_no VARCHAR(64)`、`entitlement_code VARCHAR(64)`、`coverage_end_at BIGINT`、`threshold_code VARCHAR(16)`（D7／D3／D1／D0）、`due_at BIGINT`、`expires_at BIGINT`、`policy_version VARCHAR(64)`、`observed_entitlement_version BIGINT`、`recipient_type VARCHAR(32)`、`recipient_no VARCHAR(64)`、`channel VARCHAR(16)`（INBOX／PUSH；SMS可选且默认关闭）、`dedup_key VARCHAR(128)`、`status VARCHAR(16)`（PENDING／SENDING／SENT／RETRY／SUPERSEDED／UNKNOWN／DEAD）、`attempts INT`、`next_attempt_at BIGINT NULL`、`lease_until BIGINT NULL`、`provider_message_no VARCHAR(128) NULL`、`last_error_code VARCHAR(64) NULL`、`sent_at BIGINT NULL`。

唯一键：`(notice_no)`、`(dedup_key)`；索引 `(status, next_attempt_at)`、`(store_no, entitlement_code, coverage_end_at)`、`(status, lease_until)`。所有键隐含 tenant_no。一行表示同门店、同code、同连续覆盖终点、同阈值、同收件人、同通道的一次提醒投递记录；不按单个Plan到期或来源首尾包络生成提醒。

`dedup_key` 是规范化逻辑身份 `(tenant_no, store_no, entitlement_code, coverage_end_at, threshold_code, recipient_type, recipient_no, channel)` 的安全摘要；**不包含 observed_entitlement_version**，避免无关端或不影响连续终点的重排递增权益版本后重复提醒。安全摘要须固定编码、字段顺序及算法，逐项身份仍保留明文业务字段供冲突核验，不把手机号、推送token等敏感地址塞入去重键。`observed_entitlement_version` 仅记扫描观察版本，不是提醒身份或无需重查的授权凭据。

由有效来源及实际时间片计算同code连续覆盖终点；若已过期，D0补发仍按最近已结束的连续覆盖区间终点核验，不能因“当前无有效片”误丢合法到期提醒，也不能使用更远的断续未来片末尾冒充连续覆盖。永久FREE端不生成到期提醒。默认阈值D7＝到期前168小时、D3＝72小时、D1＝24小时、D0＝到期瞬间，每15分钟扫描；错过多个阈值只补当前最紧迫一档，不补发整串旧档。`due_at` 为本档策略触发时刻，`expires_at` 为当前策略/通道允许的最后发送时刻（右端不含）；D0外部推送补发窗口默认24小时，其他窗口按策略明确，不用数据库默认值代替策略。

候选提醒记录与 `commerce_outbox` 在同一本地事务写入，通知发送在提交后执行，不持权益长事务调用渠道。发送前重新读取同code连续覆盖终点、来源是否已撤销和收件人当前身份；仅仍具老板或续费采购权限的收件人可接收。续费将终点延长、来源撤销、收件人失去资格或候选已过窗口时，旧候选置SUPERSEDED并保留审计；续费后的新终点用新身份生成候选，不能只对比整体权益版本。

每个收件人、每个通道独立重试、抢占租约和记录回执。已SENT的站内信不能因同人PUSH失败而重复发送；SENT仅代表该行对应通道按渠道合同完成，不代表其他通道成功。SENDING以租约条件更新抢占，崩溃/超时可能已被渠道接收时进入UNKNOWN核对，不能直接盲重发；支持幂等的渠道复用稳定dedup_key，不支持幂等的渠道必须先查回执或转受控人工处置，UNKNOWN不得无限重试。可确认未发送的暂时故障才转RETRY并按策略写next_attempt_at；超过重试/时间上限转DEAD，具体上限在policy_version中固定。

本表不承载资金、价值或权益权威；仅逻辑引用门店store_no与业务端目录entitlement_code。recipient_type/recipient_no通过宿主身份适配合同核验，不新增账号表、不画强用户FK。outbox的aggregate_no可按aggregate_type引用notice_no，仍是多态业务引用，不建无条件外键；提醒失败不改变门店已有权益或RBAC。

## 8. 关键事务与约束

### 8.1 不重叠的基础 Plan 时间线

MySQL 普通唯一索引无法表达区间不相交。所有基础 Plan 写入必须先 `SELECT ... FOR UPDATE` 锁门店 subscription_account，再查 BASE_PLAN 正常周期、验证交集并插入；FREE 排除。人工授予、退款重排、迁移同样走这一入口，禁止绕过直接写表。

同 code 所有有限时间片同样在该门店账户锁下排期。先固定 Plan 区间，再保留独立来源的已用片，按 delivery_seq 分配其未用时长到不与 Plan 及其他独立片重叠的最早空闲段；FREE 永久片排除。不得只按独立端包自身的尾部追加而忽略 Plan。

每事务取唯一 effectiveAt，各 code 使用同一切点；此前已消耗有效片不改，跨切点的片先拆分。每个来源的剩余量只能排入 `max(effectiveAt, delivered_at)` 之后，禁止将余量回填历史空档。“消耗”按有效日历时间流逝计算。已使用历史作为事实保留，即使父来源后来撤销，历史核算也不能将这些时长抹掉。

### 8.2 数字交付幂等

先读取订单定位门店 → 锁订阅账户 → 锁余额账户（涉及抵扣时）→ 锁来源周期／抵扣批次（涉及升级时）→ 锁订单组件／交付任务 → 确认服务端支付或零元结算 → 校验模型、经营类型和数字占用属于本单 → 插入本单全部数字来源及固定 Plan 周期 → 按 code 排期／重排端包 → 写排期事件和履约结果 → 增加权益版本、释放占用并写 outbox → 提交。统一锁顺序，不能另一条路径先锁任务再锁门店账户。

本单所有数字来源、各端时间片及既有端包的受影响排期一次本地事务提交，不能出现已购买 Plan 却只获得部分端或吞掉旧端包时长。硬件可以独立交付，数字已完成而硬件未完成时如实显示 PARTIAL。

多周期及多组件数字任务作为一组交付，全部完成才释放该数字订单占用；数字份数配置合理上限避免无限长事务。单个周期任务、部分端包任务或发货任务无权提前释放，支付成功本身也不释放。

### 8.3 升级原子转换

固定锁顺序：门店订阅账户 → 余额账户 → 旧周期按 period_no 排序 → 抵扣批次按 id 排序 → 订单交付任务。支付域事实已通过 inbox 接收，支付服务不参与本地数据库长事务。

转换流水、旧 Plan 周期关闭、新价值入账、原余额预留释放、抵扣消费、新周期创建、端时间片、独立端包重排、排期历史、版本及 outbox 必须同事务提交；任一失败全部回滚，交付任务重试。端包不转换价值，只保留余量后重新排期。

### 8.4 余额及套餐守恒

- `Σ订单行成交额 = 订单成交额`，`Σ叶子组件成交额 = 订单行成交额`。
- `Σ组件现金分摊 = 订单现金应付`，`Σ组件抵扣分摊 = 订单实际抵扣`。
- `余额账户.balance = Σ批次.remaining = Σ账本.balance_delta`。
- `余额账户.reserved = Σ批次.reserved = Σ账本.reserved_delta`。
- `本次转换总额 = Σ转换明细`，同一 source_period_no 不可在两个有效转换中出现。
- 一份已发周期的现金和转入价值总基数不得超过其成交组件分摊额；赠送为 0。
- 同一组件所有周期现金／抵扣／资格基数分别求和，必须等于该组件对应分摊，不能只核验单周期上限。
- 每个独立端来源在同 code 的切点前已消耗事实＋切点后有效未来片时长＋revoked_duration_ms＝granted_duration_ms；重排前后该总量不变，退款／撤销必须记录移除量。
- 不计永久 FREE 时，同 store/code 的当前有限 NORMAL 时间片无交集；SUPERSEDED 历史版本不重复累计。
- Plan 每个非永久端的时间片时长等于对应 Plan 周期时长；Plan 组合价值只在父周期记一次。

涉及跨行的守恒由服务事务校验和对账任务共同保证，不能声称普通 CHECK 约束可以保证。

## 9. 查询、归档与数据隔离

- 门店登录读取该店 grant，按时间片状态、父来源状态、当前时间、类型和治理状态计算；不能用独立端来源 effective 包络替代分片。可缓存已排期片的连续区间投影，但不能用最大 end_at 代替断续区间。
- `effectiveUntil` 只能返回从当前时刻开始连续覆盖的末尾。若未来存在空档，不得把最远的未来结束时间当作当前权益到期日；同时返回 `nextStartAt`。
- 查询订单、退款、周期、余额、物流均从鉴权上下文限定 tenant、store；可采购角色还须检查权限。
- 权益历史、折价及资金相关记录按现有保留策略归档，不随门店注销级联删除。
- 正式实施前补齐字段默认值、枚举种子、测试库 schema、回填与切换脚本；本文件不占用 Flyway 迁移号。

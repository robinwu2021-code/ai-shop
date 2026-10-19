# 活动卡券储值模块数据库设计

状态：目标逻辑模型草案，待评审 · 2026-10-18

执行状态与先后顺序见[Java后端独立任务表](./任务表-活动卡券储值-Java后端.md)，已实现目录内核直接复用，其余目标设计不视为已完成。

[总设计](./TDD-活动卡券储值-模块化系统设计.md) · [接口](./TDD-活动卡券储值-API契约.md)。活动类归核心服务数据源，券与卡和储值权益归pay数据源；HTTP入口不改变数据所有者。以下DDL用于完整字段评审，不可直接替代当前线上同名表。

## 一、共同约定与关系

每表id为内部自增主键，tenant_no参与业务唯一性；version条件更新（对外expectedRevision映射此列）；created/updated_at用UTC DATETIME(3)，created/updated_by留操作人。资金/核销事实不可物理删除；模板归档不删除历史。沿现有BaseEntity实际审计列对齐后再生成正式迁移，不借设计重命名所有现存列。

公共字段适用于基础38张及新增6张支撑表，DDL逐表展开：

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| id | BIGINT NOT NULL AUTO_INCREMENT | 内部主键，不能作为跨服务身份 |
| tenant_no | VARCHAR(32) NOT NULL | 租户隔离，所有业务唯一键含租户 |
| version | BIGINT NOT NULL DEFAULT 0 | 对齐BaseEntity乐观锁 |
| deleted | TINYINT NOT NULL DEFAULT 0 | 兼容BaseEntity；账务事实禁止删除 |
| created_at | DATETIME(3) NOT NULL | UTC创建时间 |
| updated_at | DATETIME(3) NOT NULL | UTC更新时间 |
| created_by | VARCHAR(64) NOT NULL | 创建操作者/系统任务 |
| updated_by | VARCHAR(64) NOT NULL | 最后操作者/系统任务 |

业务编号VARCHAR(64)上限须兼容BizKey；source_key为稳定来源复合键SHA256，原始sourceRef保存在快照内以支持碰撞/异参校验。JSON保存不可变规则/分摊，查询、排序、金额、额度与状态独立列；金额BIGINT、次数INT，计算比例乘法用BigInteger。pay与core之间只存号与快照，不跨库JOIN或外键。

活动版本引用pay权益模板版本；活动预留绑定源充值/发放操作；pay发放绑定资格凭证；pay实例绑定发放及原模板版本；付费卡实例额外保留原订单行/单件来源。模板后改不影响已获权益。储值批次保存core活动资格权益快照。

### 必须有的并发约束

- 活动预算held+used≤limit；个人held+used≤perUserLimit由锁定预算和个人行的同事务条件更新保证。
- pay发行额度及个人发行计数独立防超发；预算上限NULL不是0，不能误解释。
- 实例remaining≥reserved≥0；服务日used+reserved≤limit；储值双池余额均≥冻结。
- 同一grant.source_key只一条，quantity件每件unitIndex唯一；同一源支付意图settled_source_key只可有一个COMMITTED操作。
- 一个操作多张券的分配可有多行，但全部同pay事务过账；允许组合由业务白名单决定，首期储值不叠券/积分。
- MySQL唯一键对NULL允许多行，因此settled_source_key仅成功核销时赋值，不能用空串表示未成功。

基础目标逻辑表38张（核心12、支付26），补充支撑表6张（核心4、支付2），合计44张（核心16、支付28）。包含现有表重构和拟新增表，不表示已创建44张生产表；正式迁移V425为目录三表，V426为会员身份凭证，V427为授权与源决策，V428新增售卡权益快照。快照的order_line_no由core将已落库ord_item主键转换成仅内部使用的稳定行身份，不改动公共订单表；主体与门店按子单/订单行可信事实落快照，不能从整张支付主单推断。支撑表字典见第七节。

## 二、核心服务完整数据字典

[完整DDL](./design/活动权益-core-schema.sql)。公共审计字段见第一节；以下列出每表业务字段、索引与约束。

### pmt_preset_template

预置模板目录，仅默认草稿，40活动和6产品推荐标签。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| template_code | VARCHAR(32) NOT NULL | 稳定AP/EP编号 |
| template_version | INT NOT NULL | 模板目录版本 |
| catalog_kind | VARCHAR(24) NOT NULL | ACTIVITY或PAID_PRODUCT |
| category | VARCHAR(32) NOT NULL | 四类机制或产品 |
| name | VARCHAR(100) NOT NULL | 模板名 |
| industry_tags | JSON NOT NULL | 推荐标签非准入码 |
| draft_schema | JSON NOT NULL | 类型化可编辑草稿参数 |
| required_capabilities | JSON NOT NULL | 映射真实能力后的门槛 |
| enabled | TINYINT NOT NULL DEFAULT 0 | 默认不启用 |

索引：`UNIQUE KEY uk_preset (tenant_no,template_code,template_version)`。
### pmt_activity

活动管理头，历史规则列实施时保留直到所有消费者迁移。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| activity_no | VARCHAR(64) NOT NULL | 活动号 |
| entity_no | VARCHAR(64) NOT NULL | 归属商家或平台哨兵 |
| owner | VARCHAR(16) NOT NULL | MERCHANT/PLATFORM |
| scene | VARCHAR(24) NOT NULL | ORDER/ENTITLEMENT_GRANT/RECHARGE |
| name | VARCHAR(100) NOT NULL | 名称 |
| status | VARCHAR(16) NOT NULL | DRAFT/RUNNING/PAUSED/ENDED |
| current_version_no | VARCHAR(64) NULL | 当前已发布版本 |
| ended_reason | VARCHAR(32) NULL | 结束原因 |
| archived_at | DATETIME(3) NULL | 归档不删除历史 |

索引：`UNIQUE KEY uk_activity (tenant_no,activity_no)`；`KEY ix_activity_list (tenant_no,entity_no,scene,status,id)`。
### pmt_activity_version

不可变活动发布规则，不拥有权益余次。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| version_no | VARCHAR(64) NOT NULL | 版本号 |
| activity_no | VARCHAR(64) NOT NULL | 活动号 |
| version_index | INT NOT NULL | 递增版本 |
| schema_version | INT NOT NULL | 规则结构版本 |
| currency | VARCHAR(8) NOT NULL | 预算与充值币种 |
| schedule_type | VARCHAR(24) NOT NULL | ONE_OFF/ALWAYS_ON/RECURRING |
| starts_at | DATETIME(3) NOT NULL | UTC开始 |
| ends_at | DATETIME(3) NULL | UTC结束 |
| market_zone | VARCHAR(64) NOT NULL | 市场时区 |
| rule_snapshot | JSON NOT NULL | 触发/利益/引用pay模板版本 |
| rule_hash | CHAR(64) NOT NULL | 快照摘要非授权签名 |
| published_by | VARCHAR(64) NOT NULL | 发布人 |
| published_at | DATETIME(3) NOT NULL | 发布时间 |

索引：`UNIQUE KEY uk_activity_version (tenant_no,version_no)`；`UNIQUE KEY uk_activity_revision (tenant_no,activity_no,version_index)`。
### pmt_activity_scope

活动参与范围；权益使用范围在pay快照中。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| version_no | VARCHAR(64) NOT NULL | 活动版本 |
| scope_kind | VARCHAR(24) NOT NULL | STORE/GOODS/CATEGORY/ALL |
| ref_no | VARCHAR(64) NOT NULL | 范围对象，ALL固定* |

索引：`UNIQUE KEY uk_scope (tenant_no,version_no,scope_kind,ref_no)`。
### pmt_activity_audience

目标活动受众，替换前保留旧列兼容。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| version_no | VARCHAR(64) NOT NULL | 发布版本 |
| audience_kind | VARCHAR(24) NOT NULL | ALL/MEMBER_LEVEL/SEGMENT/NEW_CUSTOMER |
| ref_no | VARCHAR(64) NOT NULL | 对象编号或固定* |
| rule_snapshot | JSON NOT NULL | 资格定义快照 |

索引：`UNIQUE KEY uk_audience (tenant_no,version_no,audience_kind,ref_no)`。
### pmt_recharge_tier

储值活动精确档位规则。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| tier_no | VARCHAR(64) NOT NULL | 档位号 |
| version_no | VARCHAR(64) NOT NULL | 活动版本 |
| pay_amount_minor | BIGINT NOT NULL | 应实收本金 |
| bonus_amount_minor | BIGINT NOT NULL | 赠金额 |
| redemption_scope | JSON NOT NULL | 余额可用门店及项目快照 |

索引：`UNIQUE KEY uk_tier (tenant_no,version_no,tier_no)`；`UNIQUE KEY uk_tier_amount (tenant_no,version_no,pay_amount_minor)`。
约束：`pay_amount_minor>0 AND bonus_amount_minor>=0`。
### pmt_activity_budget

活动跨版本总名额与营销预算。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| activity_no | VARCHAR(64) NOT NULL | 活动号 |
| currency | VARCHAR(8) NOT NULL | 币种发布后不可换账 |
| quota_limit | BIGINT NULL | NULL不限 |
| quota_held | BIGINT NOT NULL DEFAULT 0 | 预留数量 |
| quota_used | BIGINT NOT NULL DEFAULT 0 | 实际参与 |
| budget_limit_minor | BIGINT NULL | 营销预算NULL不限 |
| budget_held_minor | BIGINT NOT NULL DEFAULT 0 | 预留营销额 |
| budget_used_minor | BIGINT NOT NULL DEFAULT 0 | 实际营销额 |
| per_user_limit | INT NOT NULL | 每人跨版本次数 |

索引：`UNIQUE KEY uk_budget (tenant_no,activity_no)`。
约束：`quota_held>=0 AND quota_used>=0 AND budget_held_minor>=0 AND budget_used_minor>=0`；`quota_limit IS NULL OR quota_held+quota_used<=quota_limit`；`budget_limit_minor IS NULL OR budget_held_minor+budget_used_minor<=budget_limit_minor`；`per_user_limit>0`。
### pmt_activity_participant

跨版本个人参与计数。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| activity_no | VARCHAR(64) NOT NULL | 活动号 |
| user_no | VARCHAR(64) NOT NULL | 本人用户 |
| held_count | INT NOT NULL DEFAULT 0 | 预留次数 |
| used_count | INT NOT NULL DEFAULT 0 | 成功次数 |

索引：`UNIQUE KEY uk_participant (tenant_no,activity_no,user_no)`。
约束：`held_count>=0 AND used_count>=0`。
### pmt_activity_reservation

营销参与凭证与预算预留。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| reservation_no | VARCHAR(64) NOT NULL | 参与预留号 |
| source_key | CHAR(64) NOT NULL | 稳定源业务与利益项摘要 |
| activity_no | VARCHAR(64) NOT NULL | 活动号 |
| version_no | VARCHAR(64) NOT NULL | 规则版本 |
| tier_no | VARCHAR(64) NULL | 储值档位 |
| user_no | VARCHAR(64) NOT NULL | 受益用户 |
| entity_no | VARCHAR(64) NOT NULL | 商家 |
| store_no | VARCHAR(64) NOT NULL | 参与门店 |
| currency | VARCHAR(8) NOT NULL | 币种 |
| quota_units | INT NOT NULL | 名额占用量 |
| budget_minor | BIGINT NOT NULL | 营销预算占用 |
| status | VARCHAR(16) NOT NULL | RESERVED/COMMITTED/RELEASED |
| permit_snapshot | JSON NOT NULL | 不可变权益约定 |
| payload_hash | CHAR(64) NOT NULL | 同键异参检查 |
| deadline | DATETIME(3) NOT NULL | 超时核查而非直接释放 |
| pay_fact_no | VARCHAR(64) NULL | 权威发放或终止事实 |

索引：`UNIQUE KEY uk_reservation (tenant_no,reservation_no)`；`UNIQUE KEY uk_reserve_source (tenant_no,source_key)`；`KEY ix_reserve_pending (tenant_no,status,deadline)`。
约束：`quota_units>0 AND budget_minor>=0`。
### pmt_effect_projection

非权威可重建营销统计，量纲分开。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| source_event_no | VARCHAR(64) NOT NULL | pay/core源事件号 |
| activity_no | VARCHAR(64) NOT NULL | 活动号 |
| entity_no | VARCHAR(64) NOT NULL | 商家 |
| store_no | VARCHAR(64) NULL | 源事实门店；历史未知不猜测，受限员工不可见未知门店 |
| effect_kind | VARCHAR(32) NOT NULL | ORDER_DISCOUNT/COUPON_ISSUED/RECHARGE/CONSUMED |
| amount_minor | BIGINT NULL | 金额类事实 |
| currency | VARCHAR(8) NULL | 金额币种 |
| units | BIGINT NULL | 数量类事实 |
| occurred_at | DATETIME(3) NOT NULL | 业务发生UTC |

索引：`UNIQUE KEY uk_effect (tenant_no,source_event_no,activity_no,effect_kind)`；`KEY ix_effect_summary (tenant_no,entity_no,effect_kind,occurred_at)`；`KEY ix_effect_store_time (tenant_no,entity_no,store_no,occurred_at)`。
### pmt_command_dedup

写接口幂等结果，与本域业务变更同事务。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| operation_kind | VARCHAR(64) NOT NULL | 接口/动作命名空间 |
| request_key | VARCHAR(64) COLLATE utf8mb4_bin NOT NULL | Idempotency-Key |
| payload_hash | CHAR(64) NOT NULL | 同键异参检测 |
| resource_no | VARCHAR(64) NOT NULL | 原资源或操作号 |
| result_status | VARCHAR(24) NOT NULL | PENDING/COMPLETED |
| response_snapshot | JSON NULL | 稳定业务响应，不含敏感token |

索引：`UNIQUE KEY uk_command (tenant_no,operation_kind,request_key)`。
### prd_entitlement_binding

CARD商品SKU引用支付权益版本，不重复保存余次规则。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| goods_no | VARCHAR(64) NOT NULL | 商品号 |
| sku_no | VARCHAR(64) NOT NULL | SKU号 |
| template_version_no | VARCHAR(64) NOT NULL | pay已发布权益定义版本 |
| units_per_item | INT NOT NULL DEFAULT 1 | 每销售件对应权益实例数 |
| currency | VARCHAR(8) NOT NULL | 与商品售价/pay定义一致 |
| status | VARCHAR(16) NOT NULL | ACTIVE/DISABLED |

索引：`UNIQUE KEY uk_product_entitlement (tenant_no,goods_no,sku_no)`。
约束：`units_per_item>0`。
## 三、支付服务完整数据字典

[完整DDL](./design/活动权益-pay-schema.sql)。公共审计字段见第一节；以下列出每表业务字段、索引与约束。

### ent_template

支付权益模板管理头。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| template_no | VARCHAR(64) NOT NULL | 模板号，保留迁移映射 |
| entity_no | VARCHAR(64) NOT NULL | 商家或平台主体 |
| title | VARCHAR(100) NOT NULL | 标题 |
| kind | VARCHAR(24) NOT NULL | ORDER_COUPON/STORE_EXCHANGE/PAID_CARD |
| funder | VARCHAR(16) NOT NULL | MERCHANT/PLATFORM |
| status | VARCHAR(16) NOT NULL | DRAFT/ACTIVE/PAUSED/ENDED |
| current_version_no | VARCHAR(64) NULL | 当前发布版本 |
| draft_snapshot | JSON NOT NULL | 可编辑草稿规则，发布时复制为不可变版本 |
| archived_at | DATETIME(3) NULL | 归档时间 |

索引：`UNIQUE KEY uk_template (tenant_no,template_no)`；`KEY ix_ent_templates (tenant_no,entity_no,kind,status,id)`。
### ent_template_version

发布权益不可变版本，券和付费卡都在pay。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| version_no | VARCHAR(64) NOT NULL | 版本号 |
| template_no | VARCHAR(64) NOT NULL | 模板头 |
| version_index | INT NOT NULL | 版本序号 |
| currency | VARCHAR(8) NOT NULL | 金额/商品售价需兼容 |
| benefit_mode | VARCHAR(24) NOT NULL | CASH/PERCENT/FREE_SHIP/GIFT/SERVICE_UNITS |
| benefit_value | BIGINT NULL | 面额或应付比例bp |
| benefit_cap_minor | BIGINT NULL | 折扣减免上限 |
| min_amount_minor | BIGINT NULL | 门槛 |
| usage_model | VARCHAR(24) NOT NULL | COUNT/PERIOD_COUNT/PERIOD_DAILY |
| total_units | INT NULL | 总次数，不适用NULL |
| daily_units | INT NULL | 每日上限 |
| validity_mode | VARCHAR(16) NOT NULL | ABSOLUTE/RELATIVE |
| valid_from | DATETIME(3) NULL | 绝对开始 |
| valid_until | DATETIME(3) NULL | 绝对结束，开区间 |
| valid_days | INT NULL | 滚动天数 |
| start_basis | VARCHAR(16) NOT NULL | GRANTED/PAID/FIRST_USE |
| period_basis | VARCHAR(24) NOT NULL | ROLLING_DAYS/CALENDAR_MONTH |
| market_zone | VARCHAR(64) NOT NULL | 时间窗口计算时区 |
| redemption_scope | JSON NOT NULL | 门店/商品/服务范围 |
| allowed_issue_modes | JSON NOT NULL | CENTER/TARGETED/ACTIVITY/PAID_ORDER |
| policy_snapshot | JSON NOT NULL | 完整规则及尾差/预约限制 |
| snapshot_hash | CHAR(64) NOT NULL | 完整性摘要 |
| published_by | VARCHAR(64) NOT NULL | 发布人 |
| published_at | DATETIME(3) NOT NULL | 发布时间 |

索引：`UNIQUE KEY uk_ent_version (tenant_no,version_no)`；`UNIQUE KEY uk_ent_version_seq (tenant_no,template_no,version_index)`。
约束：`total_units IS NULL OR total_units>0`；`daily_units IS NULL OR daily_units>0`；`benefit_cap_minor IS NULL OR benefit_cap_minor>=0`。
### ent_issue_policy

支付发行授权控制，不等同core营销预算。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| template_no | VARCHAR(64) NOT NULL | 跨版本模板发行控制 |
| limit_count | BIGINT NULL | 最大可发份数 |
| held_count | BIGINT NOT NULL DEFAULT 0 | 准备中份数 |
| issued_count | BIGINT NOT NULL DEFAULT 0 | 实际发行份数 |
| max_exposure_minor | BIGINT NULL | 发放授权敞口 |
| held_exposure_minor | BIGINT NOT NULL DEFAULT 0 | 占用敞口 |
| used_exposure_minor | BIGINT NOT NULL DEFAULT 0 | 已发敞口 |
| per_user_limit | INT NOT NULL | 独立领取/发放上限 |
| enabled | TINYINT NOT NULL DEFAULT 0 | 发行开关 |

索引：`UNIQUE KEY uk_issue_policy (tenant_no,template_no)`。
约束：`held_count>=0 AND issued_count>=0 AND held_exposure_minor>=0 AND used_exposure_minor>=0`；`limit_count IS NULL OR held_count+issued_count<=limit_count`；`max_exposure_minor IS NULL OR held_exposure_minor+used_exposure_minor<=max_exposure_minor`。
### ent_issue_recipient

支付模板个人发行限制，防并发多领。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| template_no | VARCHAR(64) NOT NULL | 模板号 |
| user_no | VARCHAR(64) NOT NULL | 用户 |
| held_count | INT NOT NULL DEFAULT 0 | 待发行份数 |
| issued_count | INT NOT NULL DEFAULT 0 | 累计发行份数 |

索引：`UNIQUE KEY uk_issue_recipient (tenant_no,template_no,user_no)`。
约束：`held_count>=0 AND issued_count>=0`。
### ent_issue_batch

发放批次汇总，逐项结果在grant。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| batch_no | VARCHAR(64) NOT NULL | 批次号 |
| entity_no | VARCHAR(64) NOT NULL | 商家 |
| template_version_no | VARCHAR(64) NOT NULL | 权益版本 |
| activity_ref | VARCHAR(64) NULL | core活动引用 |
| audience_fact_ref | VARCHAR(64) NOT NULL | core可信受众快照号 |
| planned_count | INT NOT NULL | 计划数量 |
| issued_count | INT NOT NULL DEFAULT 0 | 已发 |
| skipped_count | INT NOT NULL DEFAULT 0 | 资格跳过 |
| failed_count | INT NOT NULL DEFAULT 0 | 明确失败 |
| pending_count | INT NOT NULL | 待处理 |
| skip_reasons | JSON NOT NULL | 原因分项 |
| status | VARCHAR(24) NOT NULL | PREPARING/PROCESSING/COMPLETED/PARTIAL/FAILED |
| operator_no | VARCHAR(64) NOT NULL | 经办人 |

索引：`UNIQUE KEY uk_batch (tenant_no,batch_no)`；`KEY ix_batches (tenant_no,entity_no,created_at)`。
### ent_grant

单受益人发放操作及跨域幂等。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| grant_no | VARCHAR(64) NOT NULL | 发放操作号 |
| batch_no | VARCHAR(64) NULL | 批次 |
| source_key | CHAR(64) NOT NULL | 来源业务行利益项唯一键 |
| source_snapshot | JSON NOT NULL | 原始sourceRef，含订单行/单件序号策略 |
| template_version_no | VARCHAR(64) NOT NULL | 固定版本 |
| user_no | VARCHAR(64) NOT NULL | 受益人 |
| entity_no | VARCHAR(64) NOT NULL | 权益承担主体 |
| reservation_no | VARCHAR(64) NULL | core参与凭证 |
| paid_fact_ref | VARCHAR(64) NULL | 付费购卡支付事实 |
| quantity | INT NOT NULL | 本次发放实例数 |
| payload_hash | CHAR(64) NOT NULL | 同键异参检测 |
| status | VARCHAR(16) NOT NULL | PREPARED/COMMITTED/ABORTED/EXCEPTION |
| source_decision_no | VARCHAR(64) NULL | 提交或终止持久决策 |
| committed_at | DATETIME(3) NULL | 实际发放时间 |
| reason_code | VARCHAR(64) NULL | 单项跳过或明确失败原因 |

索引：`UNIQUE KEY uk_grant (tenant_no,grant_no)`；`UNIQUE KEY uk_grant_source (tenant_no,source_key)`；`KEY ix_grant_recovery (tenant_no,status,updated_at)`。
约束：`quantity>0`。
### ent_instance

用户实际权益和总次数余额。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| instance_no | VARCHAR(64) NOT NULL | 用户权益号 |
| template_version_no | VARCHAR(64) NOT NULL | 原版本 |
| grant_no | VARCHAR(64) NOT NULL | 原发放 |
| unit_index | INT NOT NULL | 本次发放第几件，从1起 |
| source_unit_key | CHAR(64) NOT NULL | 源订单行加单件序号或发放单件唯一 |
| user_no | VARCHAR(64) NOT NULL | 所有者 |
| entity_no | VARCHAR(64) NOT NULL | 主体 |
| kind | VARCHAR(24) NOT NULL | 三类权益 |
| status | VARCHAR(24) NOT NULL | PENDING_ACTIVATION/ACTIVE/FROZEN/EXHAUSTED/EXPIRED/REVOKED |
| total_units | INT NULL | 总次模式 |
| remaining_units | INT NULL | 剩余总次 |
| reserved_units | INT NOT NULL DEFAULT 0 | 冻结总次 |
| effective_from | DATETIME(3) NULL | 实际起算，首用激活前NULL |
| effective_until | DATETIME(3) NULL | 实际截止开区间 |
| activation_at | DATETIME(3) NULL | 激活时刻 |
| source_paid_at | DATETIME(3) NULL | 权威支付成功时间 |
| rule_snapshot | JSON NOT NULL | 已获权益完整规则 |
| asset_seq | BIGINT NOT NULL DEFAULT 0 | 单权益记账序号 |

索引：`UNIQUE KEY uk_instance (tenant_no,instance_no)`；`UNIQUE KEY uk_grant_unit (tenant_no,grant_no,unit_index)`；`UNIQUE KEY uk_source_unit (tenant_no,source_unit_key)`；`KEY ix_wallet (tenant_no,user_no,kind,status,id)`。
约束：`unit_index>0 AND reserved_units>=0`；`remaining_units IS NULL OR remaining_units>=reserved_units`。
### ent_period_bucket

服务日额度计数，跨日释放回原bucket。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| instance_no | VARCHAR(64) NOT NULL | 权益实例 |
| period_key | VARCHAR(32) NOT NULL | 实例有效期窗口标识 |
| service_day | DATE NOT NULL | 市场时区服务日 |
| limit_units | INT NOT NULL | 本日上限 |
| reserved_units | INT NOT NULL DEFAULT 0 | 本日预占 |
| used_units | INT NOT NULL DEFAULT 0 | 本日已用 |

索引：`UNIQUE KEY uk_bucket (tenant_no,instance_no,period_key,service_day)`。
约束：`limit_units>0 AND reserved_units>=0 AND used_units>=0 AND reserved_units+used_units<=limit_units`。
### ent_use_intent

统一权益使用意图，储值和券卡共用状态头。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| operation_no | VARCHAR(64) NOT NULL | 核销操作号即hold引用 |
| source_key | CHAR(64) NOT NULL | 订单/兑换/服务行使用意图唯一 |
| source_snapshot | JSON NOT NULL | 源订单版本/服务日等 |
| settled_source_key | CHAR(64) NULL | 成功订单支付维度唯一，改价重建仍一笔 |
| user_no | VARCHAR(64) NOT NULL | 使用者 |
| entity_no | VARCHAR(64) NOT NULL | 承担主体 |
| store_no | VARCHAR(64) NOT NULL | 核销门店 |
| asset_kind | VARCHAR(24) NOT NULL | ORDER_COUPON/STORE_EXCHANGE/PAID_CARD/STORED_VALUE |
| authorization_ref | VARCHAR(64) NOT NULL | 可信源本人确认/原有兑换核对凭证 |
| payload_hash | CHAR(64) NOT NULL | 重试内容检查 |
| status | VARCHAR(24) NOT NULL | HELD/COMMITTED/RELEASED/EXCEPTION |
| service_day | DATE NULL | 服务日期 |
| source_decision_no | VARCHAR(64) NULL | 源成功或取消决策 |
| expires_at | DATETIME(3) NOT NULL | 超时核查时刻 |
| committed_at | DATETIME(3) NULL | 成功核销时间 |

索引：`UNIQUE KEY uk_operation (tenant_no,operation_no)`；`UNIQUE KEY uk_use_source (tenant_no,source_key)`；`UNIQUE KEY uk_settled_source (tenant_no,settled_source_key)`；`KEY ix_use_pending (tenant_no,status,expires_at)`。
### ent_use_allocation

券卡预占分配，金额折扣分摊不是金额余额。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| operation_no | VARCHAR(64) NOT NULL | 意图 |
| instance_no | VARCHAR(64) NOT NULL | 实例 |
| period_key | VARCHAR(32) NOT NULL | 非周期固定NONE |
| service_day | DATE NOT NULL | 实际使用服务日 |
| units | INT NOT NULL | 本次预占次数 |
| discount_minor | BIGINT NOT NULL DEFAULT 0 | 订单券本次折扣 |
| currency | VARCHAR(8) NOT NULL | 折扣币种 |
| items_allocation | JSON NOT NULL | 商品/服务行分摊快照 |
| reversed_units | INT NOT NULL DEFAULT 0 | 累计恢复次数，条件不超原units |
| reversed_discount_minor | BIGINT NOT NULL DEFAULT 0 | 累计回补折扣，条件不超原discount |

索引：`UNIQUE KEY uk_ent_alloc (tenant_no,operation_no,instance_no,period_key,service_day)`。
约束：`units>0 AND discount_minor>=0`；`reversed_units>=0 AND reversed_units<=units AND reversed_discount_minor>=0 AND reversed_discount_minor<=discount_minor`。
### ent_usage

核销成功事实，消费者简明查询源。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| usage_no | VARCHAR(64) NOT NULL | 核销记录号 |
| operation_no | VARCHAR(64) NOT NULL | 原意图 |
| user_no | VARCHAR(64) NOT NULL | 本人 |
| entity_no | VARCHAR(64) NOT NULL | 主体 |
| store_no | VARCHAR(64) NOT NULL | 门店 |
| store_name | VARCHAR(100) NOT NULL | 发生时门店名 |
| asset_kind | VARCHAR(24) NOT NULL | 资产类别 |
| title | VARCHAR(100) NOT NULL | 项目摘要 |
| units | INT NULL | 核销次数 |
| amount_minor | BIGINT NULL | 储值支付额或订单券优惠额 |
| currency | VARCHAR(8) NULL | 对应金额币种 |
| items_snapshot | JSON NOT NULL | 服务/商品明细 |
| service_staff_name | VARCHAR(64) NULL | 服务人员 |
| source_order_no | VARCHAR(64) NULL | 源订单 |
| operator_no | VARCHAR(64) NOT NULL | 经办者 |
| status | VARCHAR(16) NOT NULL | COMMITTED，回补另记反向账 |
| occurred_at | DATETIME(3) NOT NULL | 业务成功时间 |

索引：`UNIQUE KEY uk_usage (tenant_no,usage_no)`；`UNIQUE KEY uk_usage_op (tenant_no,operation_no)`；`KEY ix_user_usage (tenant_no,user_no,store_no,asset_kind,occurred_at DESC,usage_no DESC)`；`KEY ix_store_usage (tenant_no,entity_no,store_no,occurred_at DESC)`。
### ent_ledger

权益次数不可变分录，预占变更也留痕。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| entry_no | VARCHAR(64) NOT NULL | 分录号 |
| instance_no | VARCHAR(64) NOT NULL | 实例 |
| asset_seq | BIGINT NOT NULL | 有序账序 |
| biz_type | VARCHAR(24) NOT NULL | GRANT/HOLD/USE/RELEASE/REVERSE/EXPIRE/FREEZE |
| biz_key | VARCHAR(128) NOT NULL | 每动作分录稳定键 |
| units_delta | INT NOT NULL | 总次余额变化 |
| reserved_delta | INT NOT NULL | 冻结变化 |
| after_units | INT NULL | 总次模式账后余次 |
| after_reserved | INT NOT NULL | 账后冻结 |
| bucket_key | VARCHAR(64) NULL | 每日计数分配引用 |
| origin_entry_no | VARCHAR(64) NULL | 受限内部回补原引用 |
| occurred_at | DATETIME(3) NOT NULL | 发生时间 |

索引：`UNIQUE KEY uk_ent_entry (tenant_no,entry_no)`；`UNIQUE KEY uk_asset_seq (tenant_no,instance_no,asset_seq)`；`UNIQUE KEY uk_ledger_biz (tenant_no,instance_no,biz_key)`。
约束：`after_reserved>=0`；`after_units IS NULL OR after_units>=after_reserved`。
### ent_code_token

券码定位凭证，存摘要不存明码。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| token_hash | CHAR(64) NOT NULL | 高熵token摘要 |
| instance_no | VARCHAR(64) NOT NULL | 仅对应指定用户券 |
| user_no | VARCHAR(64) NOT NULL | 持有人 |
| expires_at | DATETIME(3) NOT NULL | 有效期 |
| status | VARCHAR(16) NOT NULL | ACTIVE/USED/REVOKED |
| consumed_operation_no | VARCHAR(64) NULL | 被哪次意图消费 |

索引：`UNIQUE KEY uk_token (tenant_no,token_hash)`；`KEY ix_token_instance (tenant_no,instance_no,status)`。
### sv_account

金额储值账户，不持有活动预算。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| account_no | VARCHAR(64) NOT NULL | 账户号 |
| user_no | VARCHAR(64) NOT NULL | 本人 |
| entity_no | VARCHAR(64) NOT NULL | 主体 |
| currency | VARCHAR(8) NOT NULL | 币种 |
| status | VARCHAR(16) NOT NULL | ACTIVE/FROZEN |
| principal_minor | BIGINT NOT NULL DEFAULT 0 | 内部本金余额 |
| bonus_minor | BIGINT NOT NULL DEFAULT 0 | 内部赠金余额 |
| held_principal_minor | BIGINT NOT NULL DEFAULT 0 | 本金冻结 |
| held_bonus_minor | BIGINT NOT NULL DEFAULT 0 | 赠金冻结 |
| account_seq | BIGINT NOT NULL DEFAULT 0 | 账序 |

索引：`UNIQUE KEY uk_sv_account (tenant_no,account_no)`；`UNIQUE KEY uk_sv_owner (tenant_no,user_no,entity_no,currency)`。
约束：`principal_minor>=held_principal_minor AND held_principal_minor>=0 AND bonus_minor>=held_bonus_minor AND held_bonus_minor>=0`。
### sv_recharge

充值实收与活动凭证快照。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| recharge_no | VARCHAR(64) NOT NULL | 充值号 |
| request_id | VARCHAR(64) NOT NULL | 创建幂等键 |
| payload_hash | CHAR(64) NOT NULL | 内容摘要 |
| reservation_no | VARCHAR(64) NOT NULL | core参与凭证 |
| activity_no | VARCHAR(64) NOT NULL | core活动号 |
| activity_version_no | VARCHAR(64) NOT NULL | 固定活动版本 |
| user_no | VARCHAR(64) NOT NULL | 受益人 |
| entity_no | VARCHAR(64) NOT NULL | 主体 |
| store_no | VARCHAR(64) NOT NULL | 实际充值店 |
| store_name | VARCHAR(100) NOT NULL | 门店快照 |
| currency | VARCHAR(8) NOT NULL | 币种 |
| principal_minor | BIGINT NOT NULL | 应实收本金 |
| bonus_minor | BIGINT NOT NULL | 赠金快照 |
| status | VARCHAR(24) NOT NULL | WAIT_COLLECTION/SUBMITTED/RECEIVED/CREDITED/CANCELLED/REJECTED |
| collection_method | VARCHAR(16) NULL | CASH/TRANSFER |
| actual_amount_minor | BIGINT NULL | 人工实际收款 |
| receipt_ref | VARCHAR(128) NULL | 私有凭据 |
| receipt_digest | CHAR(64) NULL | 凭据摘要 |
| operator_no | VARCHAR(64) NOT NULL | 经办人 |
| received_by | VARCHAR(64) NULL | 有权确认人 |
| received_at | DATETIME(3) NULL | 实收确认时间 |
| credited_at | DATETIME(3) NULL | 到账时间 |
| expires_at | DATETIME(3) NOT NULL | 未实收截止 |
| permit_snapshot | JSON NOT NULL | 权威资格与权益范围 |

索引：`UNIQUE KEY uk_recharge (tenant_no,recharge_no)`；`UNIQUE KEY uk_recharge_request (tenant_no,request_id)`；`UNIQUE KEY uk_recharge_permit (tenant_no,reservation_no)`；`KEY ix_credit_pending (tenant_no,status,received_at)`。
约束：`principal_minor>0 AND bonus_minor>=0`。
### sv_lot

金额充值批次与历史使用约定。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| lot_no | VARCHAR(64) NOT NULL | 批次号 |
| account_no | VARCHAR(64) NOT NULL | 账户 |
| recharge_no | VARCHAR(64) NOT NULL | 每笔充值唯一 |
| activity_version_no | VARCHAR(64) NOT NULL | core规则引用 |
| principal_minor | BIGINT NOT NULL | 本金余量 |
| bonus_minor | BIGINT NOT NULL | 赠金余量 |
| held_principal_minor | BIGINT NOT NULL DEFAULT 0 | 本金预占 |
| held_bonus_minor | BIGINT NOT NULL DEFAULT 0 | 赠金预占 |
| scope_snapshot | JSON NOT NULL | 适用范围不可实时改变 |
| credited_at | DATETIME(3) NOT NULL | 入账排序 |

索引：`UNIQUE KEY uk_sv_lot (tenant_no,lot_no)`；`UNIQUE KEY uk_lot_recharge (tenant_no,recharge_no)`；`KEY ix_sv_allocate (tenant_no,account_no,credited_at,lot_no)`。
约束：`principal_minor>=held_principal_minor AND held_principal_minor>=0 AND bonus_minor>=held_bonus_minor AND held_bonus_minor>=0`。
### sv_hold_allocation

储值预占分配，状态统一在ent_use_intent。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| operation_no | VARCHAR(64) NOT NULL | 统一使用意图号 |
| lot_no | VARCHAR(64) NOT NULL | 批次 |
| principal_minor | BIGINT NOT NULL | 固化本金分配 |
| bonus_minor | BIGINT NOT NULL | 固化赠金分配 |
| items_allocation | JSON NOT NULL | 服务行分摊 |

索引：`UNIQUE KEY uk_sv_alloc (tenant_no,operation_no,lot_no)`。
约束：`principal_minor>=0 AND bonus_minor>=0`。
### sv_voucher

金额变化不可变凭证。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| voucher_no | VARCHAR(64) NOT NULL | 凭证号 |
| account_no | VARCHAR(64) NOT NULL | 账户 |
| account_seq | BIGINT NOT NULL | 账户账序 |
| biz_type | VARCHAR(24) NOT NULL | RECHARGE/CONSUME，首期无开放退款 |
| biz_no | VARCHAR(64) NOT NULL | 来源充值或使用意图 |
| payload_hash | CHAR(64) NOT NULL | 幂等内容 |
| principal_delta | BIGINT NOT NULL | 本金变化 |
| bonus_delta | BIGINT NOT NULL | 赠金变化 |
| after_principal_minor | BIGINT NOT NULL | 账后本金 |
| after_bonus_minor | BIGINT NOT NULL | 账后赠金 |
| occurred_at | DATETIME(3) NOT NULL | 业务时间 |

索引：`UNIQUE KEY uk_voucher (tenant_no,voucher_no)`；`UNIQUE KEY uk_sv_biz (tenant_no,biz_type,biz_no)`；`UNIQUE KEY uk_sv_seq (tenant_no,account_no,account_seq)`。
约束：`after_principal_minor>=0 AND after_bonus_minor>=0`。
### sv_entry

金额凭证的批次分录。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| voucher_no | VARCHAR(64) NOT NULL | 凭证 |
| lot_no | VARCHAR(64) NOT NULL | 批次 |
| principal_delta | BIGINT NOT NULL | 本金delta |
| bonus_delta | BIGINT NOT NULL | 赠金delta |

索引：`UNIQUE KEY uk_sv_entry (tenant_no,voucher_no,lot_no)`；`KEY ix_sv_lot_ledger (tenant_no,lot_no,id)`。
### sv_user_store_index

目录投影，无余额冗余。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| user_no | VARCHAR(64) NOT NULL | 本人 |
| entity_no | VARCHAR(64) NOT NULL | 主体 |
| store_no | VARCHAR(64) NOT NULL | 实际门店 |
| store_name | VARCHAR(100) NOT NULL | 目录显示名 |
| has_recharge | TINYINT NOT NULL DEFAULT 0 | 成功储值历史 |
| last_recharge_at | DATETIME(3) NULL | 最近实际充值 |
| last_consume_at | DATETIME(3) NULL | 最近实际核销 |
| last_activity_at | DATETIME(3) NOT NULL | 两时间MAX |
| last_activity_type | VARCHAR(16) NOT NULL | RECHARGE/CONSUME |
| store_status | VARCHAR(16) NOT NULL | 门店可用/历史状态 |

索引：`UNIQUE KEY uk_user_store (tenant_no,user_no,store_no)`；`KEY ix_sv_directory (tenant_no,user_no,has_recharge,last_activity_at DESC,store_no ASC)`。
### ent_owner_registry

迁移时明确唯一权威归属，不靠查不到兜底。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| source_system | VARCHAR(32) NOT NULL | MKT_LEGACY/PMT/PAY |
| source_kind | VARCHAR(24) NOT NULL | TEMPLATE/INSTANCE/USAGE |
| source_no | VARCHAR(64) NOT NULL | 原业务号 |
| target_no | VARCHAR(64) NULL | pay目标号 |
| owner_system | VARCHAR(16) NOT NULL | CORE_LEGACY/PAY |
| migration_batch_no | VARCHAR(64) NOT NULL | 迁移批次 |
| cutover_watermark | VARCHAR(128) NULL | 源截止水位 |
| status | VARCHAR(24) NOT NULL | PREPARING/VERIFIED/CUTOVER/ARCHIVED |

索引：`UNIQUE KEY uk_source_owner (tenant_no,source_system,source_kind,source_no)`。
### pay_ent_outbox

支付持久事件，同权益/资金事务。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| event_no | VARCHAR(64) NOT NULL | 事件号 |
| event_type | VARCHAR(64) NOT NULL | 事件种类 |
| schema_version | INT NOT NULL | 事件版本 |
| aggregate_no | VARCHAR(64) NOT NULL | 聚合号 |
| payload | JSON NOT NULL | 不含明码/完整凭据 |
| occurred_at | DATETIME(3) NOT NULL | 业务时间 |
| payload_hash | CHAR(64) NOT NULL | 事件类型/版本/聚合/载荷的稳定摘要 |
| status | VARCHAR(16) NOT NULL | PENDING/SENT/DEAD；SENT表示已写入统一sys_outbox |
| attempts | INT NOT NULL DEFAULT 0 | 桥接到统一投递箱的失败次数 |
| next_attempt_at | DATETIME(3) NOT NULL | 下次桥接时刻；启动及周期扫描恢复 |

索引：`UNIQUE KEY uk_pay_event (tenant_no,event_no)`；`KEY ix_pay_dispatch (status,next_attempt_at)`。支付事务内先持久化事件，提交后以稳定eventNo桥接至共享`sys_outbox`；pay与平台使用不同事务管理器，桥接通过幂等和恢复扫描处理崩溃窗口。`SENT`只表示共享投递箱已持久接管，最终投递、重试和消费确认仍由共享投递机制负责；达到10次桥接失败进入`DEAD`并告警处理。载荷由版本化白名单record生成，不含会员码或凭据。
### pay_ent_inbox

跨域事件消费防重，同账提交。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| consumer | VARCHAR(64) NOT NULL | 消费者 |
| event_no | VARCHAR(64) NOT NULL | 事件号 |
| payload_hash | CHAR(64) NOT NULL | 同事件异参拒绝 |
| processed_at | DATETIME(3) NOT NULL | 应用时间 |

索引：`UNIQUE KEY uk_pay_inbox (tenant_no,consumer,event_no)`。
### pay_ent_exception

受控异常处置与对账差异。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| exception_no | VARCHAR(64) NOT NULL | 异常号 |
| entity_no | VARCHAR(64) NOT NULL | 主体 |
| kind | VARCHAR(32) NOT NULL | 异常类型 |
| biz_no | VARCHAR(64) NOT NULL | 原业务 |
| status | VARCHAR(16) NOT NULL | OPEN/PROCESSING/RESOLVED |
| evidence | JSON NOT NULL | 脱敏源事实 |
| last_action | VARCHAR(64) NULL | 受控恢复动作 |
| handled_by | VARCHAR(64) NULL | 操作者 |
| handled_at | DATETIME(3) NULL | 处理时间 |

索引：`UNIQUE KEY uk_pay_exception (tenant_no,exception_no)`；`UNIQUE KEY uk_exception_source (tenant_no,kind,biz_no)`；`KEY ix_pay_exception_queue (tenant_no,entity_no,status,created_at)`。
### pay_ent_command_dedup

写接口幂等结果，与本域业务变更同事务。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| operation_kind | VARCHAR(64) NOT NULL | 接口/动作命名空间 |
| request_key | VARCHAR(64) COLLATE utf8mb4_bin NOT NULL | Idempotency-Key |
| payload_hash | CHAR(64) NOT NULL | 同键异参检测 |
| resource_no | VARCHAR(64) NOT NULL | 原资源或操作号 |
| result_status | VARCHAR(24) NOT NULL | PENDING/COMPLETED |
| response_snapshot | JSON NULL | 稳定业务响应，不含敏感token |

索引：`UNIQUE KEY uk_command (tenant_no,operation_kind,request_key)`。
### ent_use_reversal

既有订单券受限内部补偿，不开放储值退款。

| 字段 | 类型及空值 | 含义 |
|---|---|---|
| reverse_no | VARCHAR(64) NOT NULL | 反向操作号 |
| origin_operation_no | VARCHAR(64) NOT NULL | 原成功使用 |
| instance_no | VARCHAR(64) NOT NULL | 原用户券 |
| after_sale_no | VARCHAR(64) NOT NULL | 权威售后号 |
| source_decision_no | VARCHAR(64) NOT NULL | 源补偿决策 |
| units | INT NOT NULL | 本次恢复使用次数 |
| discount_minor | BIGINT NOT NULL | 原优惠的本次回补归属 |
| occurred_at | DATETIME(3) NOT NULL | 实际应用时间 |

索引：`UNIQUE KEY uk_reverse (tenant_no,reverse_no)`；`UNIQUE KEY uk_reverse_fact (tenant_no,after_sale_no,origin_operation_no,instance_no)`。
约束：`units>=0 AND discount_minor>=0`。

## 四、现有数据与迁移对应

| 现有表/草案 | 目标 | 处理 |
|---|---|---|
| pmt_activity及规则/范围/受众 | core同名头+version/范围 | 新增scene与版本关联，存量ORDER；旧列先保留，停止旧写路径后清理 |
| pmt_coupon、pmt_coupon_scope；旧mkt_coupon | pay ent_template/version | 保留面额、出资、币种、门槛、范围、效期、发行控制，精确来源映射 |
| pmt_user_coupon；旧用户券 | pay ent_instance | 余次、期限、使用状态和既有业务号迁移；不重发通知 |
| pmt_coupon_issue及旧发放 | pay ent_issue_batch/grant | 保留受众快照和跳过原因；无法细分单人时标明历史汇总，不编造精确记录 |
| pmt_apply中的券使用 | pay ent_usage/ledger | 迁移审计源，保留sourceSystem/sourceNo；core只保留营销派生投影 |
| pmt_apply中的ACTIVITY | core保留当前订单优惠事实 | 不作为pay用户权益余额；统计按来源去重 |
| sv_campaign/version/budget/participation旧设计 | core pmt活动/预留 | 未实施草案取消；不产生新sv活动表 |
| sv_hold、sv_consumption旧设计 | ent_use_intent、ent_usage | 统一操作头和简明查询，金额分配仍sv_hold_allocation |
| sv_account/lot/voucher/entry | pay同族 | 账户双池与批次账权威保留 |

切换遵循总设计§5：清点→冻结精确批次/水位→迁移→核对→更新唯一owner→目标唯一写。迁移后不能按新表查不到就回旧表；历史使用码需要摘要映射到目标实例，原码可用性按已承诺规则迁移，不重新生成而让旧用户失效。

设计阶段不分配Flyway版本号；本批已新增V425，仅目录三表。后续正式迁移继续先取当前最大版本，保留已应用脚本checksum；现有同名表用ALTER/回填，不执行本稿CREATE覆盖。新版本与源老列共存期，必须明确每列哪个写路径，禁止双向同步成为永久状态。

## 五、查询、对账与生命周期

储值门店投影按user/store唯一，has_recharge=1才入默认目录；时间用GREATEST，等时优先CONSUME，storeNo稳定排序。核销记录统一ent_usage，资产类别明确，金额为储值实付或券折扣，次数为兑换量；前端不可跨量纲求和。

核心活动预算与pay发行控制是不同约束，日切分别核对预留/已用与权威pay发放事实。pay核对账户=批次=分录净额，实例余次=发行次数+使用/受限回补，日额度=对应服务日分配之和，冻结=HELD分配。pmt_effect_projection可重建，不是第二本资金账。

所有期限制采用[effective_from,effective_until)半开区间；自然月按IANA时区月边界，滚动N天按模板定义的本地服务日规则。首用激活、有效窗口及日bucket同首次使用事务固化；不能在读接口激活或月底任务直接改历史次数。到期只阻止新用并留状态/审计，不删除余额。服务日改约必须取消原占用后重新prepare，未知结果不跨日补扣。

目标数据库验收：执行DDL与回填脚本，核实CHECK真正生效、JSON合法性、唯一性、非负条件更新、索引执行计划；H2不能替代。迁移前后按主体/用户/资产逐笔比对，不只比总数。


## 六、特定兼容与计量规则

ent_use_reversal仅用于原订单券受限内部补偿，累计恢复次数/优惠额不能超过原分配，既有售后决定是否恢复券，原效期不延长；不得每次部分退款恢复完整券。幂等响应表不保存token明文。ent_grant单项失败/跳过留reason_code；批次计数与逐项结果相符，不能用未知结果冒充失败。

prd_entitlement_binding售价沿既有商品SKU，不复制资金账。每件unitsPerItem个实例时，来源唯一键包含订单行、销售单件序号及件内权益序号。pmt_effect_projection的activityNo属于幂等键，一个事件可以分别更新多个活动效果。

旧券UNUSED/USED分别按规则映射ACTIVE/PENDING_ACTIVATION/EXHAUSTED，状态变换保留源审计；绝对有效期若原来是毫秒闭区间，迁移到半开区间须按原精度转换（截止+1ms），不能提前失效。自然月/每日额度的时区和服务日起算写进实例快照。

### 实施前对齐决定（2026-10-18）

目标公共字段按现有BaseEntity采用version BIGINT和deleted TINYINT，替代早期草案revision列；API的expectedRevision/revision仍是契约名，由映射层转换为实体version，不改sourceRevision等来源修订号。所有财务/使用事实禁止删除，模板用archived_at归档，不能调用通用deleteById删除历史。

ent_template新增draft_snapshot保存完整可编辑草稿，避免把尚未发布规则写进不可变版本。发布事务核对head版本，写version快照再推进current_version_no。停发与保存草稿分别控制，不改已发布规则。

AuditMetaObjectHandler当前使用LocalDateTime.now，不能直接宣称其符合本设计UTC约定。新表插入由领域工厂显式填UTC时间和可信actor（已有strictInsertFill保留非空值）；状态/计数CAS使用显式SQL记录UTC更新时间、操作者与version，不通过会覆盖操作者/时区的通用updateById。此决定需在后续Mapper集成用例验证，禁止为新模块修改全局历史时间语义。

幂等request_key采用utf8mb4_bin，区分大小写；应用拒绝首尾空白，避免PAD SPACE比较合并不同请求。正式V425仅创建ent_template、ent_template_version和pay_ent_command_dedup，不切换旧券归属，也不创建余额或使用账。逻辑模型中的FREE_SHIP保留旧包邮券定义，消费/报价仍须后续能力接通。


## 七、授权、售卡与对账支撑存储

[支撑DDL](./design/活动权益-support-schema.sql)独立于基础38表，用于设计校验；各表正式增量迁移另列。公共字段沿第一节。core四表仅core事务写，pay两表仅pay事务写；无跨库外键。授权/源决策由V427交付；售卡快照实体、Mapper、稳定订单行号及V428由JB-012交付；对账表归JB-046。

### ord_ent_authorization

| 字段 | 类型与约束 | 含义 |
|---|---|---|
| authorization_no | VARCHAR(64) NOT NULL | 本人授权引用 |
| request_key | VARCHAR(64) COLLATE utf8mb4_bin NOT NULL | 本人确认幂等键 |
| payload_hash | CHAR(64) NOT NULL | owner重算请求指纹 |
| source_key | CHAR(64) NOT NULL | 含修订号的使用意图键 |
| source_snapshot | JSON NOT NULL | 完整SourceRef及原订单定位 |
| source_revision | BIGINT NOT NULL | 源意图修订 |
| user_no | VARCHAR(64) NOT NULL | 本人消费者 |
| entity_no | VARCHAR(64) NOT NULL | 资产承担主体 |
| store_no | VARCHAR(64) NOT NULL | 真实履约门店 |
| quote_ref | VARCHAR(64) NOT NULL | 服务端可信pay报价引用，tenant内唯一 |
| selection_digest | CHAR(64) NOT NULL | 绑定资产及版本金额次数服务日 |
| order_snapshot_hash | CHAR(64) NOT NULL | 可信源价格明细摘要 |
| amount_minor | BIGINT NOT NULL | 本次确认金额可为零 |
| currency | VARCHAR(8) NOT NULL | 币种 |
| intent_key | VARCHAR(64) NOT NULL | 服务端预确认记录键 |
| order_no | VARCHAR(64) NOT NULL | 被确认的真实订单 |
| status | VARCHAR(16) NOT NULL | PENDING/AUTHORIZED/CANCELLED/EXPIRED |
| authorized_at | DATETIME(3) NULL | 仅本人确认后写入；PENDING不能视为授权 |
| expires_at | DATETIME(3) NOT NULL | 授权截止半开区间 |

索引：

```sql
UNIQUE KEY uk_order_auth_intent (tenant_no,intent_key),
UNIQUE KEY uk_order_auth_no (tenant_no,authorization_no),
UNIQUE KEY uk_order_auth_request (tenant_no,user_no,request_key),
KEY ix_order_auth_source (tenant_no,source_key,user_no,status),
```

### ord_ent_source_decision

| 字段 | 类型与约束 | 含义 |
|---|---|---|
| decision_no | VARCHAR(64) NOT NULL | 持久源决策引用 |
| purpose | VARCHAR(16) NOT NULL | USE/GRANT/REVERSE |
| source_key | CHAR(64) NOT NULL | USE用useIntentKey；发放用grantKey |
| source_snapshot | JSON NOT NULL | 完整SourceRef |
| settled_source_key | CHAR(64) NULL | 仅成功时写稳定成功键，取消为NULL |
| source_revision | BIGINT NOT NULL | 本次决策源修订 |
| decision_type | VARCHAR(24) NOT NULL | GRANT_COMMIT/GRANT_ABORT/USE_COMMIT/USE_CANCEL/ORDER_REVERSE |
| status | VARCHAR(16) NOT NULL | COMMITTED/CANCELLED |
| authorization_ref | VARCHAR(64) NULL | 使用决策绑定原本人授权 |
| paid_fact_ref | VARCHAR(64) NULL | 售卡真实付款事实引用 |
| user_no | VARCHAR(64) NOT NULL | 受益或使用本人 |
| entity_no | VARCHAR(64) NOT NULL | 资产主体 |
| store_no | VARCHAR(64) NULL | 真实门店 |
| currency | VARCHAR(8) NOT NULL | 币种 |
| amount_minor | BIGINT NOT NULL | 源真实金额非客户端报价 |
| snapshot_hash | CHAR(64) NOT NULL | 被冻结源事实摘要 |
| occurred_at | DATETIME(3) NOT NULL | 真实成功或取消时刻 |

索引：

```sql
UNIQUE KEY uk_source_decision (tenant_no,decision_no),
UNIQUE KEY uk_source_intent (tenant_no,purpose,source_key),
UNIQUE KEY uk_source_settled (tenant_no,purpose,settled_source_key),
```

### ord_card_sale_snapshot

| 字段 | 类型与约束 | 含义 |
|---|---|---|
| snapshot_no | VARCHAR(64) NOT NULL | 单件权益快照号 |
| source_key | CHAR(64) NOT NULL | SourceRef.grantKey |
| order_no | VARCHAR(64) NOT NULL | 主订单 |
| sub_order_no | VARCHAR(64) NOT NULL | 本主体子单 |
| order_line_no | VARCHAR(64) NOT NULL | 原订单行 |
| unit_index | INT NOT NULL | 从1开始的单件序号 |
| entitlement_index | INT NOT NULL | 从1开始的件内权益序号 |
| user_no | VARCHAR(64) NOT NULL | 消费者 |
| entity_no | VARCHAR(64) NOT NULL | 售价及资产主体 |
| store_no | VARCHAR(64) NOT NULL | 销售门店 |
| goods_no | VARCHAR(64) NOT NULL | 原商品 |
| sku_no | VARCHAR(64) NOT NULL | 原SKU |
| template_version_no | VARCHAR(64) NOT NULL | 固定pay定义版本 |
| unit_price_minor | BIGINT NOT NULL | 原单件售价；多权益行不得重复统计收入 |
| currency | VARCHAR(8) NOT NULL | 币种 |
| rule_snapshot | JSON NOT NULL | 原权益定义 |
| snapshot_hash | CHAR(64) NOT NULL | 固定定义摘要 |
| paid_fact_no | VARCHAR(64) NULL | 真实支付事实后仅单向固化 |
| paid_at | DATETIME(3) NULL | 真实付款时间，不用事件到达时间 |

索引：

```sql
UNIQUE KEY uk_card_sale_snapshot (tenant_no,snapshot_no),
UNIQUE KEY uk_card_sale_source (tenant_no,source_key),
UNIQUE KEY uk_card_sale_unit (tenant_no,order_line_no,unit_index,entitlement_index),
KEY ix_card_sale_order (tenant_no,order_no),
```

### pay_ent_recon_run

| 字段 | 类型与约束 | 含义 |
|---|---|---|
| recon_no | VARCHAR(64) NOT NULL | 对账批次号 |
| request_key | VARCHAR(64) COLLATE utf8mb4_bin NOT NULL | 调度或人工发起幂等键 |
| payload_hash | CHAR(64) NOT NULL | 对账参数指纹 |
| entity_no | VARCHAR(64) NOT NULL | 授权商家；ALL仅全域运营可用 |
| axis | VARCHAR(16) NOT NULL | ISSUANCE/RIGHTS/HOLD/MONEY |
| currency | VARCHAR(8) NULL | MONEY必须分币种 |
| biz_date | DATE NOT NULL | 本地市场日 |
| market_zone | VARCHAR(64) NOT NULL | IANA时区 |
| status | VARCHAR(16) NOT NULL | RUNNING/COMPLETED/FAILED |
| scan_cursor | VARCHAR(512) NULL | 重启续扫游标 |
| checked_count | BIGINT NOT NULL DEFAULT 0 | 已检查量 |
| diff_count | BIGINT NOT NULL DEFAULT 0 | 差异量 |
| started_at | DATETIME(3) NOT NULL | UTC开始 |
| completed_at | DATETIME(3) NULL | UTC完成 |

索引：

```sql
UNIQUE KEY uk_ent_recon_no (tenant_no,recon_no),
UNIQUE KEY uk_ent_recon_request (tenant_no,request_key),
KEY ix_ent_recon_query (tenant_no,entity_no,biz_date,axis,id),
```

### pay_ent_recon_diff

| 字段 | 类型与约束 | 含义 |
|---|---|---|
| diff_no | VARCHAR(64) NOT NULL | 差异号 |
| recon_no | VARCHAR(64) NOT NULL | 同域批次号 |
| entity_no | VARCHAR(64) NOT NULL | 实际差异主体 |
| resource_no | VARCHAR(64) NOT NULL | 账户或权益业务号 |
| diff_kind | VARCHAR(32) NOT NULL | 差异原因码 |
| expected_snapshot | JSON NOT NULL | 内部应有事实，不能直接回传页面 |
| actual_snapshot | JSON NOT NULL | 内部实有事实，不能直接回传页面 |
| exception_no | VARCHAR(64) NULL | 受控恢复关联原异常 |
| status | VARCHAR(16) NOT NULL | OPEN/ACKNOWLEDGED/RESOLVED |
| resolution_note | VARCHAR(500) NULL | 处理证据，不允许余额调平 |

索引：

```sql
UNIQUE KEY uk_ent_recon_diff (tenant_no,diff_no),
UNIQUE KEY uk_ent_recon_resource (tenant_no,recon_no,resource_no,diff_kind),
KEY ix_ent_recon_diff_query (tenant_no,entity_no,recon_no,status),
```

正式增量迁移为V427；订单确认前由可信core业务流将已核验的订单快照与pay报价引用写成PENDING意图。确认时锁定订单、子单、行与意图，比较订单修订、订单快照摘要、报价引用和选择摘要，再写入授权号、本人requestKey与authorizedAt。API只接收修订、报价引用、选择摘要和幂等键，用户号取登录态，金额/商家/门店来自已冻结服务端事实。`shop.core.entitlement.authorization.enabled` 默认关闭。数据库允许PENDING的授权字段为空，防止把意图误读为消费授权。

### 并发、状态与恢复裁定

- 授权按tenant/user/requestKey幂等；同键重放返回原结果，已过期也不刷新期限。重新确认使用新requestKey，core锁定真实源订单并同事务取消旧授权、插入新授权；不设sourceKey唯一，避免禁止合法重确认。修改价格、资产选择或源修订使旧授权失效，expiresAt为半开区间。
- 源决策的USE source_key使用useIntentKey（含修订），成功settled_source_key使用settledKey（不含修订）；取消必须NULL，不能用空串。因此允许修订1取消后修订2成功，同时阻止修订3重复成功。GRANT固定grantKey；用途隔离，取消不能携带提交decisionType。决策与源成功/取消在同core事务落库，pay只通过可信SPI/query核实，不以请求引用为凭据。
- 多商家订单按真实子单划分SUB_ORDER_USE，sourceNo为subOrderNo，不能把主单号作为不同商家的共同成功源。可多次履约的服务以真实履约单号作来源；来源由core推导。售卡保留订单行、unitIndex、entitlementIndex，2件×2权益产生4个稳定来源。
- 售卡规则与单件售价在下单时固化。paid_fact_no/paid_at仅在真实支付成功时单向关联，迟到事件不得替换付款时间。同一件多个权益行重复保存单价只为追溯，不得累加为收入。
- 对账按ISSUANCE/RIGHTS/HOLD/MONEY分量纲；MONEY必须指定币种。批次游标可续扫、差异唯一，RUNNING→COMPLETED/FAILED；差异OPEN→ACKNOWLEDGED→RESOLVED。受控重试仍作用于原异常/原操作，不开放自动调平余额。ALL主体仅授权全域运营可用。内部expected/actual快照不得直接返回页面。
- 恢复任务全局租约复用既有V91的shedlock；单操作沿既有Outbox租约及状态CAS。租约过期仅允许接管查询，UNKNOWN/PENDING不得推断取消；无需新增通用租约表。JB-051落实任务与多节点验证。


### 7.6 core.member：mbr_identity_ticket（JB-010，V426）

统一身份凭证，非余额表。ticket_hash为唯一查询摘要（tenant_no,ticket_hash），原文永不落库；purpose区分MEMBER_CODE/CUSTOMER_REFERENCE，user_no为可信顾客。MEMBER_CODE的entity_no/store_no/actor_no/source_ticket_hash必须全部为空；CUSTOMER_REFERENCE必须全部非空，分别绑定商家/门店/B操作人及来源会员码摘要。issued_at/expires_at为UTC DATETIME(3)，CHECK expires_at>issued_at，半开有效期60/120秒。公共审计字段齐全，version/deleted沿第一节。

索引tenant/purpose/expires_at供未来JB-051批量清理过期记录；tenant/user/purpose供账号审计和撤销。来源摘要不设跨行FK，引用具有独立截止时间；清理父码不改变引用。禁止把此表当作消费授权，源意图和本人授权仍归ord_ent_authorization。V426在统一Flyway下归核心数据源，实体MbrIdentityTicket和MemberIdentityTicketMapper；DataScopeRegistration登记SELF:user_no和MERCHANT:entity_no，服务所有读写显式tenant/purpose且在可信owner内绕过环境scope。


## 第一阶段正式迁移与事实边界（2026-10-19）

V430创建统一预置目录并插入40AP+6EP（全部默认禁用）；V431扩展pmt_activity并创建版本、范围、受众、储值档位、预算、参与者、参与凭证、效果投影与core命令幂等表；V432补投影store_no及门店时间索引。归core数据源，沿主应用统一Flyway，不修改已应用脚本。目标pay表不因此视为已交付。

订单优惠的预算/个人计数/pmt_apply/效果投影必须在同一core事务；取消仅返还已使用且尚未撤销的原事实。统计按币种分组，关联原pmt_apply排除已撤销；投影不充当资金账。活动预算按活动号累计而非版本重置。参与超时只触发可信来源核查，UNKNOWN保持占用并延期重查。


## 第二阶段B1持久化补充

V433创建ent_issue_policy/ent_issue_recipient，字段沿设计DDL。V434创建ent_grant/ent_instance/ent_ledger，并在ent_grant增加prepared_snapshot JSON NOT NULL：保存GrantCommand、不可变EntitlementDefinition、可信paidAt及exposureMinor。已有payload_hash校验同grant命令，source_key防换号重复发行；所有计数、实例、账与事件同pay事务，未接源适配器不伪造付款或资格。


V435补ent_issue_batch，新增audience_snapshot JSON、recipient_results JSON和cursor_offset INT，固定受众/规则/摘要和逐项原grant号，批次恢复不重新筛人。每项grant与结果游标同pay事务，失败先回滚后单独记录明确跳过原因，不留半次预算预占。


### 第二阶段V436使用领域落库

新增ent_period_bucket、ent_use_intent、ent_use_allocation、ent_usage及ent_use_proposal；完整DDL见pay-schema目标文件及V436。意图冻结command_snapshot（原命令、源事实、规则、授权、选择与原服务日），usage_no用于稳定重放；active_source_key以不含修订的来源键互斥未决意图，RELEASED原子置空，成功终态继续保留；不能以新修订绕过旧预占。提案存preview/quote元数据和到期时刻，不写余额，不存明码。

服务日桶与次数分录都在pay锁定实例后变更。到期仅改状态、记0变化分录，不清余额或释放未决预占。新增迁移已通过实际MySQL Writer事务、JSON/CHECK、失败回滚与H2结构/生成器对账；已应用V425–V435保持原样。

# 活动卡券储值模块数据库设计

状态：目标逻辑模型草案，待评审 · 2026-10-18

执行状态与先后顺序见[Java后端独立任务表](./任务表-活动卡券储值-Java后端.md)，已实现目录内核直接复用，其余目标设计不视为已完成。

[总设计](./TDD-活动卡券储值-模块化系统设计.md) · [接口](./TDD-活动卡券储值-API契约.md)。活动类归核心服务数据源，券与卡和储值权益归pay数据源；HTTP入口不改变数据所有者。以下DDL用于完整字段评审，不可直接替代当前线上同名表。

## 一、共同约定与关系

每表id为内部自增主键，tenant_no参与业务唯一性；version条件更新（对外expectedRevision映射此列）；created/updated_at用UTC DATETIME(3)，created/updated_by留操作人。资金/核销事实不可物理删除；模板归档不删除历史。沿现有BaseEntity实际审计列对齐后再生成正式迁移，不借设计重命名所有现存列。

公共字段适用于当前基础47张及22张支撑表（core27/pay42，合计69张；含预览、取消栅栏、订单协调及销售预留增量），DDL逐表展开：

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

当前基础目标逻辑表47张（核心18、支付29），补充支撑表22张（核心9、支付13），合计69张（核心27、支付42）。包含现有表重构和新增表，不表示已创建69张生产表；正式迁移V425为目录三表，V426为会员身份凭证，V427为授权与源决策，V428新增售卡权益快照。快照的order_line_no由core将已落库ord_item主键转换成仅内部使用的稳定行身份，不改动公共订单表；主体与门店按子单/订单行可信事实落快照，不能从整张支付主单推断。支撑表字典见第七节。

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
| status | VARCHAR(16) NOT NULL | COMMITTED/PARTIAL_REVERSED/REVERSED；原金额与项目不改写，回补另记反向账 |
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
| entity_no | VARCHAR(64) NOT NULL | 所属实例的主体 |
| request_key_hash | CHAR(64) NOT NULL | 本人签发幂等键摘要 |
| payload_hash | CHAR(64) NOT NULL | 本人/实例/用途绑定摘要 |
| nonce | CHAR(32) NOT NULL | HMAC重放用随机nonce，不是明码 |
| expires_at | DATETIME(3) NOT NULL | 最长60秒且不晚于实例有效期 |
| status | VARCHAR(16) NOT NULL | ACTIVE/USED/REVOKED |
| consumed_operation_no | VARCHAR(64) NULL | 绑定的原操作 |
| consumed_store_no | VARCHAR(64) NULL | 原解析门店 |
| consumed_actor_no | VARCHAR(64) NULL | 原B操作人 |
| consumed_at | DATETIME(3) NULL | 首次定位UTC时间 |

索引：`UNIQUE KEY uk_token (tenant_no,token_hash)`；`UNIQUE KEY uk_token_request (tenant_no,user_no,request_key_hash)`；`KEY ix_token_instance (tenant_no,instance_no,status)`。USED要求消费操作/门店/人/时间全部非空；ACTIVE/REVOKED要求这些列全部为空。
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
| usage_no | VARCHAR(64) NOT NULL | 原成功明细号 |
| payload_hash | CHAR(64) NOT NULL | 原回补命令摘要，重放异参拒绝 |
| command_snapshot | JSON NOT NULL | 不可变来源、金额与决策引用 |
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

[支撑DDL](./design/活动权益-support-schema.sql)独立于当前基础47表，用于设计校验；各表正式增量迁移另列。公共字段沿第一节。当前core七张、pay十张支撑表分别仅本模块事务写；无跨库外键。授权/源决策由V427交付；售卡快照实体、Mapper、稳定订单行号及V428由JB-012交付；对账表归JB-046。

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


### V437：源取消早到与请求晚到

pay_ent_source_fence以tenant+purpose+source_key及tenant+purpose+operation_no双重唯一，GRANT来源键不含修订、USE预占键包含修订。OPEN记录源/受益人/主体，CANCELLED额外冻结已核实的SourceDecision和毫秒UTC时刻。所有相关Writer按fence→意图/发放→实例/容量顺序锁定；未出现实体时的可信取消也必须落此表，防止超时后原prepare仍在网络/服务端处理中。负向事实不是资金分录，不伪造权益实例，不凭查无记录或TTL猜取消。Core源决策写入在返回前统一毫秒精度，回读与首响一致。


### JB-020订单券协调补充（执行中）

核心服务以`ord_ent_order_use`保存原操作、授权、命令与原订单摘要，先持久化PENDING再向pay预占；pay返回的真实冻结分摊经核心服务核对后一次应用价格。`ord_ent_payment_seal`在调用外部收银前由核心服务锁单封存应付金额；已发起过收款或已封存的订单不能新增优惠。核心服务终态事务同时记录USE_COMMIT/USE_CANCEL及Outbox；应用协调器只提交/释放原operationNo，丢响应按原操作恢复，不以超时推断取消。暂停新增能力不阻止已有承诺完成。

本切片仅接ORDER_COUPON；服务券/次卡的真实服务意图和储值扣款仍在原任务内继续，不能用普通现金订单支付成功冒充已完成扣次。每单首期只接受一张新订单券；预占后不支持替换券，需取消原订单后重新下单。新表均在核心库；不跨库事务，不直接访问pay实体。


#### ord_ent_order_use（核心库，V438）

公共审计列沿第一节；operation_no、order_no、entity_no、user_no为VARCHAR(64)非空；source_key、payload_hash、order_snapshot_hash为CHAR(64)非空；context_snapshot、command_snapshot为JSON非空；status为VARCHAR(24)非空；decision_snapshot为JSON可空。tenant+operation、tenant+order、tenant+source各唯一，status+updated_at用于恢复扫描。状态PENDING→APPLIED→COMMIT_DECIDED→COMMITTED，未成功支付可转CANCEL_DECIDED→RELEASED；决策状态强制包含源决策JSON，其他状态不得伪造决策。

#### ord_ent_payment_seal（核心库，V438）

公共审计列沿第一节；order_no VARCHAR(64)、pay_amount BIGINT、currency VARCHAR(8)均非空；tenant+order唯一，pay_amount非负。核心订单锁串行化封存与优惠应用；封存只记金额与币种，不改变订单摘要。历史已存在的支付尝试经pay事实端口核实后拒绝改价。此表只经已授权订单回捞，不作为独立商家列表。


### JB-022受限回补实现（V439）

pay仅接受ORDER_COUPON原成功使用；CORE/ORDER_REFUND的sourceNo为权威售后号，sourceLineNo必须是原usageNo，决策同时绑定本人、商家、门店、币种、原订单摘要及本次优惠归属金额。UNKNOWN/PENDING只返回PROCESSING。原使用意图锁串行化所有回补，再锁实例和分配；累计优惠归属不超原discount，只有归属全部返回时才恢复原分配的使用次数。既有“整单退完退券”的经营策略由core/旧券适配保持，不能由pay按现金退款额自行算回券。

反向行、原分配计数、实例、带origin_entry_no的次数账及EntitlementUseReversed事件同pay事务；发生异常整笔回滚。旧效期不延长，EXPIRED/FROZEN保持状态，原消费金额和项目保留。不开新储值或新付费卡线上退款；旧URL及售后自动路由仍由JB-049交付。


### JB-023兼容兑换凭证实现（V440）

仅STORE_EXCHANGE类型为可签发模板策略；新代码不扩展所有既有不可变模板定义的字段。`shop.pay.entitlement.code.enabled`默认false，并要求use开关；签发密钥`shop.pay.entitlement.code-key`由`SHOP_PAY_ENTITLEMENT_CODE_KEY`供给，至少32字节，缺失拒绝新签发，不生成随机默认密钥。HMAC-SHA256绑定租户/本人/实例/原幂等键摘要/随机nonce，数据库只存token摘要及nonce，不存明文或可逆密文；重试原幂等键可重建原响应，密钥改变后无法重建旧token时明确拒绝，不另发新码。已持有的原码仍按摘要验证。

本人签发/撤销；解析仅定位一张兑换券并限制主体/适用门店，USED绑定原操作、门店和人；只有完全相同绑定在过期前可重放，换号/换店/换人拒绝。解析/签发不激活、不预占、不扣次数，不可查询该人的其他权益。停止新增签发不使短期已签凭证失效；冻结/到期/耗尽的资产仍不可新解析。原USED凭证不因撤销其他ACTIVE凭证而取消已建立的业务意图，原意图按020协议单独确认/取消。


### 第二阶段活动领取来源：V441 pmt_benefit_intent

核心服务新增真实领取来源表，唯一键为tenant+intentNo及tenant+user+requestKeyHash。
保存请求摘要、原上下文、冻结活动版本/参与命令/许可及全部子发放命令；JSON中无余额、明码。
状态PENDING→COMMIT_DECIDED/ABORT_DECIDED→COMMITTED/ABORTED，终态不可互转。
同一用户幂等键不可更换模板、活动或门店。核心源决定和ActivityBenefitDecided事件同事务落库，
支付prepare/commit/abort在核心资源锁外进行；未知结果不释放预算。
一次活动预留一份营销名额，每个权益项保留稳定sourceLineNo与grantNo；子许可仅替换sourceRef，
其余字段与父许可一致，由核心来源owner逐字段核实并冻结完整GrantCommand。
全部子发放准备成功后才确定COMMIT；部分准备失败先确定ABORT，再逐子撤销（含未准备的取消栅栏），
全部支付终态核实后确认或释放营销占用。COMMIT决定后暂停发行不能反悔已准备的权益承诺。
当前C主动领取只支持NONE触发的ENTITLEMENT_GRANT活动；订单条件触发仍须真实订单资格owner。


### V442：服务核销业务源（已实现）

核心服务新增`ord_ent_service_order`；支付服务继续独占实例、余次、日额度、预占与核销流水。
服务单独立于`ord_order`现金订单，不能产生`stl_payment`或现金收入事件。字段与约束完整DDL见
[core模型](./design/活动权益-core-schema.sql)，运行迁移为`V442__entitlement_service_order.sql`。

| 字段组 | 持久事实与约束 |
|---|---|
| tenant/entity/store/user/operator | 原租户、门店、消费者、经办人；本人查询由C会话绑定，B写入必须原经办人及有核销权限的门店 |
| service_order_no/operation_no/request_key_hash | 创建即固定业务源与使用操作；请求按租户/主体/经办人幂等；取消先到亦可建立原操作负栅栏 |
| payload_hash/source_snapshot/source_hash | 原创建请求仅存摘要；实际在售SERVICE/STORE_VERIFY商品、数量、类目、门店及服务时间不可变；无明文会员引用或兑换码 |
| preview_snapshot/preview_expires_at | 原实例、预览摘要、版本及资产时区服务日；只存元数据，不预占余次 |
| command_snapshot/context_snapshot | C确认后冻结原UseCommand与可信上下文；PENDING必须有命令；恢复不重新识别会员、不更换操作 |
| decision_snapshot/cancellation_reason | USE_COMMIT或USE_CANCEL与UseDecided outbox同一核心事务；终态决定字段必填 |
| deadline/status/version | 新提案15分钟；技术行版本不改变业务源修订0；已有预占承诺不因超时释放 |

状态依次为CREATED→PREVIEWED→AUTHORIZED→PENDING→COMMIT_DECIDED→COMMITTED；未提交完成可转
CANCEL_DECIDED→RELEASED。客户端展示WAIT_AUTH、WAIT_SUBMIT、PROCESSING、EXPIRED是业务投影，
不扩充支付预占状态。授权复用`ord_ent_authorization`和C09，服务源采用专用验证委托，不新建授权表。
共享UseSourceFact增加可空`stagedOperationNo`；仅核心已持久命令后返回，支付新prepare必须匹配。
它是源提交就绪事实，不冒充授权状态，也不参与不可变服务快照摘要；旧订单源保持null兼容。


### V443：订单券报价选择快照（已实现）

`ord_ent_authorization`增加可空`order_use_snapshot JSON`，冻结订单源事实、QuoteView及选定实例。
无字段的旧意图和SERVICE_USE授权保持兼容；字段不表示余额，不允许页面改写来源金额。授权确认时生成
稳定原操作，同一core事务保存ord_ent_order_use和UsePrepared outbox。原cash seal与历史支付事实继续校验。


### V444：CARD商品绑定与命令原回执（已实现）

将原目标`prd_entitlement_binding`建为核心商品表，额外冻结实际SKU市场；只绑定商家主市场的CARD SKU，
币种沿真实MarketPort，售价沿SKU，不能从请求覆盖。`units_per_item`是每件发行实例数，30次余量属于模板。
另增商品域`prd_ent_binding_command`保存租户/原请求键/actor+entity+参数摘要与原BindingView，
避免商品域直接写营销的pmt_command_dedup；绑定后再改版本时，旧请求仍回原结果。
远程pay版本校验先于核心事务；核心事务内认领命令、锁商品及核对expectedProductRevision、绑定、递增商品
版本并保存原回执。新增表后目标为基础45+支撑6，共51（core21/pay30）。

绑定同时保存已验证的pay不可变规则JSON及原摘要，供真实下单时本地冻结售卡快照；不在订单持锁事务里
跨服务读取pay目录，不把模板草稿当作已售权益。版本引用和副本均来自同一次真实validateReference结果。


### V445：实收到账账务内核（实施中）

先建设已实收原充值单到唯一到账的pay内核，尚不开放B实收入口；后续JB034须接实际活动/会员来源再验收。
建立既定sv_account/recharge/lot/voucher/entry/user_store_index，并增加sv_posting_line管理备查分录，
每本金/赠金池分记消费者储值请求权借方和商家履行义务贷方，双方相等；不表示平台持有银行现金或托管。
充值表补原来源及实收决定JSON；credit只核对本pay已实收事实，不以活动当前状态或网络可用性撤销实收承诺。
按账户→原充值单固定顺序锁定，账户/批次/凭证/分录/备查借贷/目录投影/到账事件同一pay事务，失败全回滚。
金额用addExact防溢出；冻结账户仍可记录已欠到账，消费另受冻结约束。原档位未规定资金失效时间，不擅加到期。
此增量落地后目标逻辑52（core21/pay31，含6支撑），运行表247；完整来源/实收链路仍属后续034。


V446补账户总额上限：不仅每池余额各自安全，principal+bonus合计也须能由公开long金额表示。
新增独立迁移CHECK `principal_minor <= Long.MAX_VALUE - bonus_minor`；V445保持原校验和不变。
应用在写入批次/凭证之前核对合计，超限整笔回滚，原单保持RECEIVED。该增量不新增表，仍52目标/247运行表。

### 储值真实来源与人工实收增量（JB-034，V447/V448）

V447仅由core拥有`pmt_recharge_intent`。唯一键为tenant+intent、tenant+recharge及tenant+entity+operator+request摘要；PENDING/CREDITED/CANCELLED与恢复索引独立列。JSON冻结原可信上下文、CORE/RECHARGE来源、确定的RC号、实际会员、门店名称、活动版本档位、参与许可及处理截止；不保存原始customerRef。先插真实来源，再在同一个core事务中reserve及写Prepared Outbox，失败一并回滚。处理截止不是资金有效期。

V448仅修改pay的`sv_recharge`：received_receipt_key为NULL直到有权确认人实收时，写入收款方式和原收款凭据号的摘要；tenant+entity+received_receipt_key唯一，阻止一份人工收款凭据支持两笔到账。原凭据号/摘要和核验备注保持私有。cancelled_at/cancellation_reason记录未实收取消，CHECK禁止无事实的取消状态；已实收/到账不可取消。先取消后创建使用完整真实来源写CANCELLED栅栏，迟到create返回实际取消状态，不能重新提示收款。

实收与取消竞争同一pay行锁及revision；实收决定与Received Outbox同事务，不持有充值行锁调用到账。到账继续遵循account→recharge锁序。已有RECEIVED承诺不因活动停用、会员引用过期、储值开关关闭或新增receipt claim列而失效。独立部署由反向core事实API读取许可，应用通过pay路由门面调用，不跨库JOIN。

付费卡取消决策复用说明：ord_ent_source_decision以purpose=GRANT、原PAID_CARD_ORDER的grantKey逐单件/件内权益记录GRANT_ABORT，paid_fact_ref为空；成功/取消不能互相覆盖，重复取消沿原decisionNo与UTC occurredAt。决策与真实主订单取消/关闭在同一core事务中写入，没有新增表或迁移；pay容量释放仍由后续预留协议消费该可信事实。

### V450：付费卡付款前容量预留（JB-040）

`ent_sale_reservation`归pay，是第7张支撑表。正式迁移为V450，支撑设计DDL同步；V449的`sv_hold_allocation`已在目标基础表中，不重复计数。目标逻辑54表（core22/pay32），迁移生成的H2运行表250张；目标逻辑表与运行表不是同一口径。

| 字段 | 类型 | 约束与用途 |
|---|---|---|
| reservation_no/source_key | VARCHAR(64)/CHAR(64) | 各自与tenant组成唯一键；SR+原grantKey前60位；包含单件及件内权益索引 |
| source_snapshot/command_snapshot/payload_hash | JSON/JSON/CHAR(64) | 冻结来源、预留命令及摘要，拒绝同号异参 |
| template_no/definition_snapshot | VARCHAR(64)/JSON，可空 | RESERVED/TRANSFERRED必须非空；取消先到的墓碑无需读取今日模板 |
| user_no/entity_no/currency | VARCHAR(64)/VARCHAR(64)/VARCHAR(8) | 原受益人、发行主体及币种，不接受客户端覆盖 |
| status | VARCHAR(24) | RESERVING→RESERVED→TRANSFERRED，或RESERVING/RESERVED→RELEASED；中间态不独立提交 |
| grant_no/source_decision_no | VARCHAR(64)，可空 | TRANSFERRED必有接管grant；RELEASED必有可信取消决定 |
| 公共审计字段 | 沿第一节 | version/deleted/UTC毫秒/actor齐全，财务承诺禁止删除 |

索引：tenant+reservation、tenant+source唯一；tenant+status+id用于后续恢复扫描。CHECK覆盖状态、必填快照、接管号及取消号。预留事务先claim/锁预留，再调用既有发行策略锁模板/总量/个人量；策略失败连同预留行一起回滚。只支持PAID_CARD/SERVICE_UNITS，单件quantity=1、敞口0，不形成储值资金分录。

付款后发行在同一pay事务内锁grant栅栏→grant→预留，将RESERVED移交TRANSFERRED并保存原grantNo；不再次reserve、不在移交时settle。后续仅grant的commit/abort结算该份容量。取消先到持久RELEASED墓碑，迟到reserve沿原号返回RELEASED；取消与移交锁同一预留，TRANSFERRED不会被销售释放再次减量。不可因超时或付款查不到而释放，必须读取core真实GRANT_ABORT。当前只实现领域内核，本地/远程销售协调与自动恢复仍待JB-040/041。

### V451售卡恢复确认

1档：在core既有ord_card_sale_snapshot追加delivery_status VARCHAR(16) NOT NULL DEFAULT PENDING，CHECK限定PENDING/ACKNOWLEDGED，按tenant+delivery_status+order_no建恢复索引。不新增表，不更改来源键及付款事实；仅由应用协调确认整单已全部发卡或取消释放后写ACKNOWLEDGED。扫描只读真实主单终态，不根据截止时间推断取消。支付与core分库，恢复调用在core事务外，确认失败则原号重放。计划：迁移/源SPI与Mapper→恢复批次与调度→重复发卡/取消/失败继续扫描测试。

### V450/V451原生增量与独立装配验证

新增scripts/tests/paid-card-migrations.test.mjs，仅在无端口、无网络的临时MySQL 9.7.2执行V450及V428→V451，finally清理。验证源键唯一、RESERVED规则快照必填、TRANSFERRED接管grant必填、RELEASED取消决定必填、JSON合法性；旧快照升级后默认PENDING、非法状态拒绝及恢复索引存在。/tmp/paid-card-migrations.log为1项通过，0失败/跳过，约6.8秒。该证据仅覆盖迁移与数据库约束，不冒充真实Writer并发或生产版本升级演练。

PayApplicationBootTest新增saleCapacityAndReverseSourceHaveExactlyOneStandaloneOwner，检查真实独立pay上下文的预留SPI、反向SaleSource及HTTP端点唯一，来源确为core远程适配。结合运行时HTTP与主应用embedded/standalone互斥路由测试验收本批装配；不据此宣称两个真实进程的业务链路已全部验收。

### V452支付异常记录内核

1档，沿既定pay_ent_exception目标表落正式迁移；字段/唯一键与目标DDL一致，补OPEN/PROCESSING/RESOLVED状态CHECK。不新增目标逻辑表，运行表将251。首批只从真实PREPARED发行单捕获GRANT_DELIVERY异常，证据白名单为原grantNo/发行状态/来源类型/固定原因码，不保存paidFactRef、命令JSON或凭证。tenant+kind+bizNo持久幂等，同业务主体不一致拒绝。平台查询同时按tenant+entity收窄，非平台scope拒绝。原号恢复与动作审计/权限/RPC随后接线，本批不开放运营HTTP。

### V453异常恢复动作审计

1档：新增pay_ent_exception_action支撑表，tenant+request_key唯一，冻结主体/操作者/异常号/原因/expectedRevision摘要，PENDING/DONE及最终安全响应JSON。动作认领与异常PROCESSING同pay事务；远程查源/原grant恢复在锁外；完成动作与异常终态同pay事务。中断保留PENDING，同键原参续办，同键异参冲突，其他请求不能抢PROCESSING。动作历史保留，不用last_action覆盖审计。首批仅GRANT_DELIVERY，不开放任意调账/改受益人；新增目标支撑表使逻辑55、运行252。

### V454异常动作类型

1档：pay_ent_exception_action新增operation_kind VARCHAR(32) NOT NULL DEFAULT RETRY_GRANT，限定RETRY_GRANT/QUERY_SOURCE，旧恢复动作升级保持原含义。查源动作复用认领→锁外查事实→审计完成，仅更新异常证据中的sourceStatus，不调用grant commit/abort，不结算容量；动作类型纳入新查源请求摘要，防止查源键被当作恢复键。无新增表。

V455只登记运营异常read/recover/verify三个sys_function_point及SUPER_ADMIN授权，不新增业务表，不给普通FINANCE/CS岗位自动赋权；权限码与Perms、ops-web映射及真实路由一致。后端已落地，界面尚无对应页，ui_ready=0；不添加空菜单。

### V456权益分录对账内核

1档：将既定pay_ent_recon_run/diff转正式V456，不新增目标逻辑表，运行表254。首批RIGHTS轴仅比较ent_instance余次/冻结次数/assetSeq与ent_ledger汇总/max序号，按主体及instanceNo键集分页（每批1–200），每个异常实例计1个差异。pay同一REPEATABLE_READ事务完成来源读取、批次与差异写入；不锁外查源，不调整余额或补发。COMPLETED只表示该页完成，scan_cursor非空须继续下一页，不能当全主体已核完。幂等摘要含范围/操作者/日期时区/游标/数量，同键异参冲突。原金额轴、发行轴与逐服务日额度仍后续独立实现。

对账日期说明：bizDate/marketZone是批次归属日与时区，当前RIGHTS核对的是运行时快照，不回放历史日末状态。scan_cursor关联当前页完成结果，后续页另起原请求号；跨页不宣称同一个全库快照。


### N01对账轴实现补充（2026-10-19）

继续复用V456的pay_ent_recon_run/diff，不改已存在迁移。运行行写入真实axis及currency；MONEY必须分币种，其他轴currency为NULL。RIGHTS/HOLD资源为instanceNo，ISSUANCE为grantNo，MONEY为accountNo；同资源多字段差异合并为一条内部快照，保证diff_count<=checked_count。只读事实Mapper归pay数据源，运行/差异与REPEATABLE_READ快照同事务提交；每页独立快照，不能作为跨页全时点一致快照。


### N03冻结审计复用（2026-10-19）

不新增权益表。复用ent_instance状态/版本、ent_ledger零delta生命周期流水以及pay_ent_command_dedup持久幂等。operation_kind=LIFECYCLE_FREEZE，request_key沿原请求；payload hash覆盖实例号、完整命令和可信scope。response_snapshot内部保存action/reason/beforeStatus/expectedRevision及公开FreezeView，记录与状态更新、流水在同一pay事务提交。解冻由有效期/激活/余量推导目标状态，无需增加previous_status列，不以过去状态覆盖期间已经发生的使用或到期。


### N08效果投影实现细化（2026-10-19）

复用pmt_effect_projection现有amount_minor/currency/units及source_event_no+activity_no+effect_kind唯一键，不新增表或改旧迁移。新增CREDITED/RIGHTS_USED/COUPON_DISCOUNT/COUPON_RESTORED效果码为应用白名单（现有列无枚举CHECK）。sv消费通过现有sv_voucher/entry/lot/recharge的同pay查询精确归因到活动；多活动汇总COUNT(DISTINCT source_event_no)，金额按贡献SUM，数量单列。ORDER_DISCOUNT继续依据pmt_apply未回滚事实查询，不让新投影绕过旧回滚过滤。


### N10 owner实际迁移（2026-10-19）

V458落实已有目标表ent_owner_registry，目标逻辑表总数仍55，实际主迁移物理表变为255。source_no用utf8mb4_bin精确匹配，联合唯一键仍tenant/source_system/source_kind/source_no。新增CHECK保证系统/类型/owner/迁移状态合法；PAY必须CUTOVER/ARCHIVED且target_no、水位齐全，CORE_LEGACY仅PREPARING/VERIFIED，VERIFIED也必须目标号和水位齐全。登记使用INSERT…ON DUPLICATE KEY UPDATE取得排他claim，再锁原行比对批次，不覆盖已有映射。

### 到期任务查询索引（V459，2026-10-19）

追加`ent_instance`普通索引`ix_instance_expiry(tenant_no,status,effective_until,id)`，供到期状态任务及24小时提醒窗口查询；不改变已有事实、余次或开关。沿用UTC半开截止语义，H2测试普通索引不承担方言证明，原生迁移测试核对真实索引列顺序。无新增表，目标仍为core22/pay33，实际主库255表。

### 旧券源根写栅栏（V460，2026-10-19）

仅扩展原mkt_coupon/pmt_coupon，各追加migration_state VARCHAR(24) NOT NULL DEFAULT OPEN、migration_batch_no VARCHAR(64) utf8mb4_bin、migration_watermark CHAR(64) utf8mb4_bin、migration_frozen_revision BIGINT。两个根表增加(tenant_no,migration_state,migration_batch_no,id)索引。状态限定OPEN/DRAINING/FROZEN/COMMIT_DECIDED/ABORT_DECIDED；非OPEN需批号，冻结/提交需水位和非负冻结revision。原业务状态、券码、余次和金额没有回填改值；实际主库255表/2374种子、目标逻辑55表不变。

源命令的FenceAudit复用pmt_command_dedup，与源状态同core事务提交，记录原因/操作者。同键异参拒绝；旧成功响应可重放，真实状态须另查，不能把重放的FREEZE结果当作当前提交决定。新BEGIN显式set null清空旧水位/冻结revision，ABORT保留批次墓碑。

LegacyCouponFenceMapper锁读包括已逻辑删除的根，原释放不能绕过已有栅栏。孤儿原释放保留旧行为，但TEMPLATE_MISSING阻止导入；此分支不允许在owner缺失/PAY调用失败时回退。归档时间仅为独立展示元数据，不进入财务水位，不解除根栅栏。

原V1使用MariaDB UCA1400排序规则，缓存MySQL不支持。V460原生测试仅抽取原根建表结构作夹具并明确归一化该旧排序规则，再执行未修改的V460；不冒充V1全库新建/升级。旧迁移未改，完整目标版本演练仍归JB054。

## V461消息恢复动作（2026-10-19）

V461仅将pay_ent_exception_action具名CHECK扩展为RETRY_GRANT/QUERY_SOURCE/RETRY_EVENT/QUERY_EVENT；不修改已验证V454，不新增表。pay_ent_exception沿tenant+kind+bizNo唯一键认领OUTBOX_DELIVERY，bizNo=原eventNo，OPEN→PROCESSING→RESOLVED。动作DONE仅表示重排/核查已完成，异常的PROCESSING仍需原事件真正SENT后关闭；稳定动作响应供原键重放，后续状态读异常详情。

重排原pay_ent_outbox保留不可变事实，DEAD→PENDING、attempts=0、nextAttemptAt=当前UTC，带version CAS；失败defer/markDead同样带读取版本，防止旧投递失败覆盖新一轮重排。事务按原事件→异常→动作锁定，审计与重排同一个pay事务。桥接网络调用在财务/恢复事务之外；捕获死信不再发ExceptionOpened，避免通知失败递归。重启扫描以id有界轮转，补登记DEAD及SENT未关闭异常，单行损坏不阻塞后续行。

H2生成器显式重放DROP CHECK并严格拒绝不存在约束，随后ADD CHECK保留新枚举；运行结构仍255表、2374种子，目标逻辑55表不变。隔离MySQL执行V461并验证新旧动作允许、ADJUST_BALANCE拒绝；该证据不等于全库升级或生产数据库验证。

## 冻结快照读取与事务隔离（2026-10-19）

本切片无新增表或迁移：复用V460根上的migration_state/batch_no/watermark/frozen_revision。现场重算原HMAC，不将已缓存FenceView或幂等命令的历史响应当成当前导入许可。冻结与exportFrozen使用独立RR事务，原root锁保持至证据生成完成；不能继承外层事务已有的一致性快照。requireWritable仍MANDATORY加入原业务事务，旧业务OPEN/ABORT路径不需要迁移密钥。

当前证据只在内存及受认证内部响应中流转，不落明文快照文件或目标数据库。8MiB上限以实际UTF-8字节计数，超限拒绝；未来持久暂存需要独立加密/校验及提交协议，不能把本次读取视为资产导入。运行255表/2374种子、逻辑55表不变。已验证旧码保留但日志脱敏、SHA-256字节一致、批次/事实/终止校验及外层旧快照拒绝。

## S03删除/归档与来源计数（2026-10-19）

复用原mkt/pmt源表，无新增表/迁移。LegacyCouponFenceMapper增加只读库存投影，以显式SQL越过MyBatis的逻辑删除默认过滤；在core迁移服务MAIN检查和executeWithoutScope边界内执行，所有关联仍逐项匹配tenant。LegacyCouponInventoryRow为内部SQL投影，不是第二份源实体或目标账本。

计数包括原模板、实例、发行、pmt_apply及删除子集，保留原软删/归档标记。孤立历史需NOT EXISTS(实例 JOIN 原模板)，不能只判断实例存在。已删除原模板仍是可归属的历史根，孤立指原关联实际缺失。MKT没有对应历史表，返回null而非构造0。模板/来源重叠或孤立计数不是纠错授权；后续导入要求明确规范化与原事实保留。

隔离MySQL以生成的源列形状运行实际Mapper SQL，验证墓碑计数、NULL语义及缺原模板的孤立历史；这只是查询兼容证据，不冒充全库新建/升级。物理255表/2374种子、目标55表不变。

## V462加密迁移证据暂存（2026-10-19）

ent_migration_stage归pay.migration，是第9张支撑表；当前逻辑56表（core22/pay34）、主迁移物理256表、2374种子。该表保存证据，不保存目标资产余额，不允许改成READY或CUTOVER。

| 字段组 | 列与类型 | 含义/约束 |
|---|---|---|
| 业务身份 | stage_no VARCHAR64 binary、tenant_no | 稳定暂存业务号；联合唯一 |
| 精确来源 | source_system VARCHAR24 binary、source_template_no VARCHAR64 binary、migration_batch_no VARCHAR64 binary | 仅MKT_LEGACY/PMT；tenant+系统+模板+批次唯一 |
| 主体与幂等 | entity_no VARCHAR64、request_key VARCHAR64 binary、command_hash CHAR64 | 同tenant原键唯一；摘要含主体、操作人、原query和原因 |
| 操作原因 | reason VARCHAR500、公共created_by/created_at | 原操作人及UTC时间；原因非空 |
| 冻结证据 | source_schema_version INT、frozen_revision BIGINT、watermark CHAR64、content_hash CHAR64 | 首版schema=1、版本非负；SHA/HMAC长度64 |
| 加密载荷 | crypto_key_id VARCHAR32 binary、payload_cipher MEDIUMTEXT | v1.keyId.nonce.cipher，AES-256-GCM，密文不直传元数据响应 |
| 大小与数量 | payload_bytes INT、instance_count/history_count BIGINT | 原文1–8MiB；次数均非负，密文最多12000000字符 |
| 状态 | status VARCHAR16 | CHECK仅STAGED；不表示可导入 |
| 公共审计 | version/deleted/created/updated字段 | 继承公共字段；本服务不覆盖或删除既有证据 |

AES使用随机12字节nonce和128位认证标签。AAD同时绑定租户、业务号、主体、精确来源/批次、冻结版本/水位、原文摘要、命令摘要、操作人、原键、原因、keyId及计数。审计字段或密文被改，verify拒绝；不重新获取/覆盖原证据来掩盖错误。配置密钥为Base64的32字节，keyId为1–32位字母数字/下划线/连字符，默认空关闭新暂存；不同keyId不盲试旧密文。

编排入口拒绝已有事务，避免挂起后仍持有金融锁再远程读源；新源读取和加密都在pay写事务外，只有唯一认领在短pay事务。stage原键完成响应可在源ABORT后重放，但verify需重新验证当前真实源；UNKNOWN/不可达不当成可导入。首版V1是可见实例证据，importReady恒false，升级完整清单需新协议/批次，不能覆盖旧证据变成导入许可。

V462原生MySQL约束测试1通过；设计56表DDL整体检查1通过。两者都不是生产升级演练。已验证默认密钥关闭前不读源、跨主体/非平台/租户拒绝、同键异参/同批换键冲突、审计篡改拒绝及无财务/owner写入。

## V463清单版本元数据与V1兼容（2026-10-19）

V463在ent_migration_stage追加manifest_version INT NOT NULL DEFAULT 1及history_available TINYINT NULL。新版本CHECK仅允许1/2；V1允许NULL供滚动旧写者省略该列，V2必须明确历史可用性，PMT=1、MKT_LEGACY=0。存量回填只补历史可用性，不改旧密文、hash、原键、版本或审计时间；V462保持原校验和。

source_schema_version=1表示传输信封；manifest_version=1是原可见数组证据，=2是全部已知源家族记录。新V2保留所有删除实例、发行、历史和范围；MKT没有独立历史表时明确不可用，history_count=0只指捕获了0行，不能声称0次消费。V1摘要和AAD保持旧字段字节顺序；V2另用LEGACY_MANIFEST_STAGE_V2域，并追加清单版本/历史可用性。两者原键不可互换，同源批次不可覆盖升级。

原VISIBLE_V1根水位并不签名隐藏/删除记录。完整清单在同一个独立core RR事务及FROZEN根锁内重验水位，再读取完整记录；完整contentHash由认证源读取提供。后续源持久提交必须重新比较完整hash，不得以已有SOURCE_MATCHED或旧水位代替。未排空的删除实例关联订单仍拒绝导出，异常源不推断已取消。

正式/目标DDL保持逻辑56、物理256；生成器现报告2375条种子/DML语句（新增元数据回填），不是2375条新增业务记录。原生V462→V463约束/旧列保留1项及设计56表整体SQL1项通过。实际Java额外独立构造旧AAD/密文并模拟新元数据列NULL，验证旧V1仍能读取及现场核对。

### S03规范化的可信事实（2026-10-20）

本批不新增表/列或修改V460–V463。assessmentFacts属于已有AES-GCM加密payload，字段version/entityActive/market/peerTemplateCount/peerInstanceCount连同完整清单hash校验。编号比对固定读取两套原模板及实例（包括逻辑删除），按tenant限制且不返回另一源消费者资料；不依赖BaseMapper的逻辑删除默认过滤。market从core本地商家主数据取得，再由pay本地sys_market确认币种、精度、时区及启用状态，无默认币种回填。

评估不改变ent_migration_stage.status=STAGED，不写ent_instance、ent_ledger、ent_owner_registry或outbox。源事实变化应使已有暂存验证失败；不能为使评估通过直接更新旧源计数、日期或密文。逻辑56表、运行物理256表口径不变。

S03发行政策不新增列：原total_count、received_count、per_user_limit、budget_minor及包含墓碑的实例是评估输入。拟迁入的ent_issue_policy.issued_count为终身发行数，used_exposure_minor为终身发行承诺敞口，不能拿pmt_apply已消费金额代替；删除/到期不减少历史计数。当前剩余权益最大敞口单独评估，不写回原预算。原MKT total_count=0不限，PMT total_count IS NULL不限、=0为零；预算0/已知NULL不限。上述只是只读评估，尚未写入目标政策或按人发行计数。

## V464同号双源认领记录实施设计（2026-10-20）

新增pay.migration.ent_migration_alias（第10张支撑表，目标逻辑57=core22/pay35；正式迁移V464）。保存alias_no、entity_no、migration_batch_no、pmt_stage_no、mkt_stage_no、canonical_stage_no（严格等于pmt_stage_no）、两方content_hash、comparison_hash、matched_instance_count、compared_at、request_key、reason和公共审计列。状态仅RECOGNIZED；不允许READY/CUTOVER，不存原始用户资料、券码或资产余额。

tenant+alias_no、tenant+pmt_stage_no、tenant+mkt_stage_no及tenant+request_key分别唯一，PMT/MKT角色由服务器根据真实stage核对，防止对调来源绕过成员唯一性。claim在同pay短事务按stage_no稳定顺序锁两行，再校验原版本、内容及密文绑定并写认领/命令幂等结果。来源完整读取和差异比较在事务外，无锁内远程调用。compared_at固定比较口径，之后重验同一摘要不因时间自然流逝失效；实际余额/过期资格仍按本次真实时刻判断。

认领只是操作人声明与一致性证据，不是源提交决定或目标接管。后续操作必须现场重验两份原完整清单；源ABORT、内容变化、额外碰撞均拒绝继续。S04必须两根同事务提交、pay原子导入两源映射一份资产，不能将影子再次导入。

## V465旧ORDER券支付操作实施设计（2026-10-20）

新增pay.migration.ent_legacy_coupon_operation（第11张支撑表，目标逻辑58=core22/pay36）。tenant+operation_no及tenant+checkout_no分别唯一；保存原来源身份、精确实例/持有人/主体/币种、原owner批次/计划摘要、经济摘要、原core事实/上下文、准备命令指纹、原规则/门店分摊、优惠金额及源决定快照。只记录ORDER券余次，不建储值或现金余额。

内部PREPARING仅允许同事务认领后完成，不应单独提交；外部状态HELD/COMMITTED/RELEASED/REVERSED。ever_held区分已真实预占与取消先到：未预占RELEASED的优惠金额/规则/分摊及账序均为NULL，没有伪造0元消费或余额分录。HELD及后续真实财务状态要求正优惠和守恒分摊。hold/commit/release/reverse各保存实际asset_seq，ledger使用LEGACY_HOLD/COMMIT/RELEASE/REVERSE类型和原operation稳定键；反向必须关联原COMMIT分录。

源建单真实COMMIT/ABORT、完整取消/退款回补决定分别保存原号和快照，回补只允许已真实消费的一份单位且原单位最多返回一次。记录source_order_no及源业务发生时刻供原消费记录查询，不把优惠金额叫作实际付款。core远程查源、owner查询及报价准备均在pay锁外；本地短事务按操作→实例顺序锁定，同事务更新原EntInstance/不可变流水/操作状态，不依赖HTTP幂等缓存。新增配置shop.pay.entitlement.legacy-coupon.enabled默认false，且新预占仍需use.enabled；关闭不影响已承诺确认、释放或回补。此为待实施金融存储设计，旧checkout源/入口、通知、对账及恢复后续一起接入。

### N10旧ORDER券core结算日记（V466）

追加`ord_legacy_coupon_checkout`，支撑表增至12（core5/pay7）；当前目标59表=core23/pay36，运行schema259表。`checkout_no`、`operation_no`及成功`order_no`各自租户内唯一；保存不可变fact_snapshot、command/stage/economic摘要及在实际建单事务内写入的held_snapshot。OPEN无终态事实，ABORTED无订单，COMMITTED必须有真实订单引用、原hold和决定。行禁止删除。JSON字段及终态NULL约束已在MySQL执行，V466自此冻结。

`LegacyCouponCheckoutJournal`提供core内部准备、绑定、提交和取消；来源read与pay远程调用分离。`bind/commit`强制加入调用者事务；stage/abort拒绝外层事务并使用短独立事务。prepare重放先读原日记，保持原时刻/金额/窗口；不因重新读当前源生成新意图。暂无客户端准备/取消API。旧下单及售后入口对接另行验收，不能根据表存在认定已接管。

### V467旧券投递确认与恢复索引

仅为既有`ord_legacy_coupon_checkout`追加pay_state、pay_decision_no、pay_acked_at、recovery_due_at及`tenant/pay_state/recovery_due_at/id`索引。PENDING为待查原操作，NONE为非金融报价；COMMITTED/RELEASED/REVERSED必须对应原core终态并有实际确认引用与时间，确认后排期为空。新退回决定必须清除旧确认并重置PENDING。升级不编造成功状态/零日期，旧行保持PENDING及NULL排期，由恢复重读原意图建立。

V466→V467带数据升级、NULL/终态约束及完整59表目标DDL已在MySQL验证，V467冻结；当前仍59逻辑表（core23/pay36）、259运行表、12支撑表。源日记状态更新使用`LegacyCouponCheckoutMapper.StateChange`条件SQL（tenant/checkout/version），显式写NULL、UTC毫秒及真实调用组件身份，避免通用审计填充覆盖；不可变fact/经济摘要不在更新字段中。

### V468：旧券核销统一消费事实

沿用 ent_usage，新增 protocol（STANDARD 默认）及 legacy_instance_no。operation_no 为真实核销操作号：STANDARD 对应原 use intent，LEGACY 对应 ent_legacy_coupon_operation，不产生替代意图。STANDARD 继续强制门店及门店名非空；LEGACY 仅 ORDER_COUPON/CNY、1 次、正优惠额、真实订单，允许未知或跨门店为空。原生 MySQL 已验证升级兼容与约束，V468 冻结。表总数仍 259、活动权益逻辑表仍 59。

### V469：固定加密目标计划（支付服务）

ent_migration_plan 属 pay.migration 支撑表，保存 plan_no、主 stage_no、可选 alias_no、原系统/模板/批次、主体、请求键/命令摘要/原因、完整目标 plan_hash 与 source_bundle_hash、密钥版本/密文/字节数、有效实例和归档实例数量、目标规则版本数、评估时间及审计字段。tenant+plan、stage、request、source+template+batch 各有唯一约束。状态仅 PLANNED、deleted=0，不能写成 COMMIT_DECIDED 或 IMPORTED。

敏感目标清单使用既有 AES-GCM 证据加密机制及独立 LEGACY_TARGET_PLAN_V1 AAD，绑定上述身份、摘要、计数和原操作人；不得明文保存或输出消费者身份。源 bundle 绑定完整 V2 source contentHash、原冻结版本/水位和所有认领来源，不能只信可见 V1 水位。目标清单保存固定模板、规则版本、原实例→目标实例映射及原剩余次数；归档实例只保留证据，无目标金融实例或发放号。规划不补造收款、历史核销或金融流水。

V469 已在隔离原生 MySQL 执行并冻结，60 张目标逻辑表 DDL 一并通过；运行 schema 260 张表、2375 条种子。最终源决定与目标原子导入仍为后续步骤，不能把计划存在当作已导入。

### V470：核心源永久决定及唯一来源绑定

核心服务 pmt_migration_decision 保存 decision/plan/entity/batch、目标及源bundle摘要、非敏感计划头快照、命令摘要/原请求键/原因/操作人、UTC决定时间与审计字段。tenant+decision、plan、request 唯一。仅 COMMITTED、version=0、deleted=0，没有源决定反向撤销写入。

pmt_migration_source 用 tenant+source_system+source_template_no 全生命周期唯一绑定决定；同号 PMT/MKT 来源分别绑定同一决定。与全部原券 FROZEN→COMMIT_DECIDED、决定及命令去重同一核心事务；不关联支付数据库外键，不写金融资产。读证明按来源绑定与决定 JOIN，未知来源返回空证明，网络不可用不得当作未决定。

V470 原生 MySQL 状态/不可删除/绑定唯一约束通过并冻结；完整设计 DDL 62 逻辑表，基础47+支撑15（core25/pay37），运行262表、2375种子。永久源决定仅授权后续按原目标计划滚动完成导入，不代表导入已经完成；提交之后不能用 ABORT 恢复旧写。

### V471：支付迁入回执与原实例基线

ent_migration_import（pay）以 tenant+import、plan、sourceDecision 各自唯一保存实际迁入结果：主体、原核心决定、plan/bundle摘要、完整源证明快照、实际资产/归档数、UTC迁入时间及审计。状态仅 IMPORTED，version=0、deleted=0。

ent_migration_asset（pay）保存 import、主来源系统/原实例号、目标实例号、baseline_no、是否仅归档、原总次数与迁入时真实剩余次数。原来源、目标实例和基线各自唯一；归档行的目标实例和基线必须 NULL，不能为归档记录伪造金融资产。该表存初始基线，不跟写当前可用余额。

目标目录/历史规则版本、发行与逐人历史计数、实际剩余次数、每实例 MIGRATION_BASELINE、全部原别名 owner CUTOVER 与迁入回执同一支付事务；晚期唯一冲突全部回滚。实例 grant_no 此处是该 baseline_no 的技术引用，明确不是重放原grant，普通 ent_grant 不新增。source_paid_at 保持未知 NULL，不发到账/发券通知。初始迁入时发行保持暂停，回执 issuanceEnabledAtImport=false 表示迁入时状态，不代表以后发行策略状态。

V471已在隔离原生MySQL执行冻结，完整64逻辑表DDL通过；生产支付内核的原生MySQL20并发重放也验证只有一套记录。基础47+支撑17（core25/pay39），运行264表、2375种子。当前能力门同时约束源提交准入和目标导入：仅无待迁历史应用流水、净余次未消耗的来源；MKT历史不可用保留未知，有历史的来源等待历史投影完整实现，不能部分导入。

### V472–V473 历史核销与原订单权益退回

原 `pmt_apply` 核销事实投影为 `ent_usage.protocol=MIGRATED`，原核销号作为 operation_no；唯一键按 tenant/protocol/operation 分命名空间。未知门店、操作人及逐次余额保留未知；已知 0 元保留 0，不补造支付或原核销账本。

- pay `ent_migration_usage`：唯一原核销号→展示记录映射，保存来源行哈希、迁入批次、原实例和原退回时间；归档来源允许没有新实例。
- core `pmt_migration_return`：迁入前核销在真实取消/整单退款后产生的不可变决定，同事务 outbox；唯一原核销号。PENDING→RESTORED 只更新支付回执与确认时间。
- pay `ent_migration_return`：原核销映射、迁入回执、来源决定与源哈希一致才恢复原实例一次；同 pay 事务写 MIGRATION_RETURN 权益账、历史记录 REVERSED 与回执。没有现金退款能力，也不修改原冻结核销事实。

锁顺序：core 原应用行→退回决定；pay 原核销映射→权益实例→展示记录。跨服务读取和确认均在对方事务之外。消费历史查询始终按登录本人限制，可返回没有新权益实例的归档历史。运行测试 schema 当前 267 表、2375 条种子/DML。

### V474：旧入口接续原生发行与core展示映射

`ent_legacy_coupon_issue`归pay：按唯一兼容操作号和唯一原生batch固定关联来源模板、原生版本、真实core受众快照、调用人及TARGETED/CENTER模式；PREPARED只续原批次，COMPLETED保存实际grant/instance及完成回执。先锁模板与策略，真实账号的逐人计数先ensure再当前读，避免MySQL缺行gap锁；预算整批拒绝时全部预占回滚。

`pmt_pay_coupon_projection`归core：唯一(tenant,instance)保存真实支付完成的grant、实际持有人、原模板来源、原生版本、迁移批次/planHash及真实发放时刻；不是权益账户，不保存余次或写冻结旧券表。核对永久core来源决定后claim唯一键再当前读，同实例异事实拒绝。旧身份解析和券包合并该投影，实余额/效期继续读pay。

正常发放沿用原生grant到账事件。LEGACY_COUPON_ISSUE_COMPLETED只驱动core展示映射，不重复发到账通知。恢复扫描PREPARED原号，不重新解析受众或调用旧发行器；新恢复任务受总job开关及独立开关控制，默认关闭。

V474完整69表DDL已在隔离MySQL执行通过（`/tmp/ent-legacy-issue-schema-native.log`），该版本冻结；后续追加V475+。当前运行269表、2375条种子/DML，基础47及支撑22=core27/pay42。

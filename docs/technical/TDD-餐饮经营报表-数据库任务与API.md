# 餐饮经营报表：数据库、任务Service与查询API

状态：首批后端链路已编码、编译及专项验证；缺源指标未业务验收，未生产启用 · 2026-10-25。对应[报表主方案§14](./design/TDD-B端报表库与日结.md#14-经营核心报表-review-与补充2026-10-25待实施)。本文件§0～§2保留完整目标及开发计划，§3记录实际实现、验证与缺源边界；目标清单不代表全部指标已有真实业务数据。

## L1 阅读入口与结论

- 数据库：独立report库；首批六表、导出阶段第七表。完整日汇总而非每日TopN；周/月及榜单在读侧聚合。
- 生成：只新增FoodReportDailyJob一个每日任务，计划、构建、补缺与校验在同一流程完成；补算/导出按需，监控/初始化复用原设施。
- 查询：餐饮公开Controller仍归food-web；共享统计读取/发布在shop-report，跨owner编排在shop-app/reportbridge，行业事实由food-core提供。独立PAY/库存只走只读owner合同。
- 经营主线：订单/成交额、优惠退款与渠道、历史销售成本及经营费用、毛利和经营净利；今日实时和历史采用同一指标目录与计算定义。
- 部署：S1-A代码已随9b2b52798上线，其余仍是计划。先完成隔离/源合同、迁移与必要补偿质量，再开放真实统计；报表方案不执行支付或退款。

## §0 需求对账

| 用户诉求 | 存储/执行落点 | 查询落点 |
|---|---|---|
| 经营日报、订单与金额 | ORDER/CORE来源＋日事实OPERATING_ORDER_DAY | daily、period、trend |
| 支付渠道、优惠、退款 | PAY/权益原分配＋PAYMENT_CHANNEL/DISCOUNT_TYPE/REFUND_CHANNEL_DAY | payments、discounts、refunds |
| 毛利、纯利 | 历史COGS、费用归属与PROFIT_COST_DAY | profit、daily |
| 菜品日/周/月销售榜 | 完整SKU/角色/单位/币种日分组 | rankings，先期间聚合再LIMIT |
| 库存 | 精确日收发及边界结存 | inventory |
| 今日实时及组合 | 同一指标目录＋实时owner快照适配 | realtime |
| 数据可追溯 | build/attempt、manifest、publication及coverage | health、响应元数据 |

## L2 数据库方案

### 1. 库、连接与迁移

目标独立库候选名`ai_shop_report`，前缀`rpt_`，使用`reportDataSource/reportJdbcClient/reportTransactionManager`及`rpt_flyway_history`。不能只与主库共库换前缀。继承shop-report的record＋JdbcClient＋DAO，既有三张零售表及V1/V2冻结。

新迁移候选V3，在编码前重新检查最大版本。所有新增DDL之前验证实际实例/catalog、批准目标及有效账号权限；API只读、worker统计DML、独立迁移DDL。STRICT执行新增DDL也不得绕过校验。报表故障不回退业务数据源，ISOLATED不阻断点餐宿主。

### 2. 公共字段和关系

除多店导出任务外，六表均有：`id BIGINT`、`tenant_no VARCHAR(32)`、`entity_no/store_no VARCHAR(64)`、`created_at/updated_at DATETIME(6)`，UTC存储。partition包含`business_date DATE`、`date_basis VARCHAR(24)`、`metric_version INT`；首批date_basis固定BUSINESS_DATE。依表职责显式冗余partition，写入时与build证据一致。

业务标识`build_no/export_no`沿平台现有编号能力，候选VARCHAR(32)；请求键VARCHAR(128)，SHA256为CHAR(64)。数据库时间使用UTC_TIMESTAMP(6)判断lease。row版本、generation、attempt、fence分别定义，不混用。值列不能默认0代替未知。

关系：scope_cursor规划partition；partition的publication指向build/attempt；build/attempt拥有manifest和metric；dirty触发新build。build记录job runId，任务平台记录runId关联；跨库逻辑引用，不建指向业务/库存/PAY库的外键。所有DAO读写显式scope；仅有buildNo不构成授权。

### 3. 六张首批表字段清单

#### 3.1 rpt_scope_cursor：门店统计启算及规划

| 字段 | 类型 | 约束/用途 |
|---|---|---|
| date_basis/metric_version | VARCHAR(24)/INT | 与scope构成唯一范围 |
| first_stat_date | DATE | 启用日期，历史补算另申请 |
| last_planned_date | DATE NULL | 只代表请求已持久规划，不代表成功发布 |
| enabled | BOOLEAN | 原受控启用状态 |
| default_for_reads | BOOLEAN | 当前门店默认读取口径标记，初始false |
| read_slot | TINYINT生成列 | default_for_reads=true时为1，否则NULL，用于唯一约束 |
| version | BIGINT | 乐观锁，初始0 |

唯一键：tenant/entity/store/date_basis/metric_version；额外唯一键tenant/entity/store/date_basis/read_slot，保证一个门店同日期基准最多一个默认版本。read_slot为MySQL生成列，NULL允许多个非默认版本；原生迁移测试必须验证。默认版本切换先锁对应scope全部版本行，在同一report事务内验证重建覆盖后撤旧立新。跨店默认版本不一致时不混合查询，要求选择已完整生成的共同版本。游标推进与该范围请求持久化在同一report事务中完成；不允许先推进后补请求。

#### 3.2 rpt_build_batch：逻辑构建与当前执行尝试

| 字段 | 类型 | 约束/用途 |
|---|---|---|
| build_no | VARCHAR(32) | 唯一逻辑构建号 |
| request_key/request_hash | VARCHAR(128)/CHAR(64) | scope内key唯一；同key异hash冲突 |
| business_date/date_basis/metric_version | DATE/VARCHAR(24)/INT | 目标分区 |
| reason | VARCHAR(24) | INITIAL/DIRTY/LOOKBACK/BACKFILL/VERSION_REBUILD |
| job_run_id | VARCHAR(64) NULL | 原任务平台调用关联 |
| attempt_no/fence/expected_generation | BIGINT | 重领次数、分区令牌、发布CAS基准 |
| lease_owner/lease_until | VARCHAR(64)/DATETIME(6) NULL | 当前执行者及租约 |
| status | VARCHAR(24) | QUEUED/RUNNING/VALIDATING/PUBLISHED/FAILED/CANCELLED |
| spec_hash/required_sources | CHAR(64)/JSON | 固定目录hash，必需(owner,family)清单 |
| day_evidence | JSON | dayNo/policyNo/version/timezone/cutover/UTC起止，来源可信 |
| captured_dirty_generation | BIGINT | 本逻辑请求捕获的代次；恢复不吞掉新代次 |
| validation_hash | CHAR(64) NULL | 内部校验结果绑定本attempt内容 |
| failure_kind/error_code/next_retry_at | VARCHAR(32)/VARCHAR(64)/DATETIME(6) NULL | 脱敏错误、可重试分类/退避 |
| started_at/finished_at | DATETIME(6) NULL | 原执行时间 |

索引：scope/request_key唯一；partition＋status；status＋next_retry_at/lease_until；job_run_id。已PUBLISHED请求只查回，不改变attempt。新一轮重算使用新请求键；失败恢复保持buildNo并递增attempt，不能覆盖旧发布事实。

#### 3.3 rpt_source_manifest：来源与完整性

| 字段 | 类型 | 约束/用途 |
|---|---|---|
| build_no/attempt_no | VARCHAR(32)/BIGINT | 固定执行尝试 |
| owner_code/family_code | VARCHAR(24)/VARCHAR(48) | CORE/FOOD/PAY/INVENTORY/费用owner及事实族 |
| source_snapshot_ref/source_version | VARCHAR(128)/VARCHAR(128) NULL | owner给出的快照/版本，不用请求时间伪造 |
| as_of_at | DATETIME(6) | 原来源截至 |
| row_count/checksum | BIGINT/CHAR(64) | 原行控制数及内容摘要 |
| completeness/unknown_reason | VARCHAR(24)/VARCHAR(64) NULL | COMPLETE/EMPTY_VERIFIED/PARTIAL/UNKNOWN |
| source_evidence | JSON | 白名单非敏感来源元数据 |

唯一键：scope＋build/attempt/owner/family。必需来源缺失不能发布；EMPTY_VERIFIED必须有真实扫描0行证据，来源失败不是空日。

#### 3.4 rpt_day_publication：分区锁与在读版本

| 字段 | 类型 | 约束/用途 |
|---|---|---|
| business_date/date_basis/metric_version | DATE/VARCHAR(24)/INT | 与scope构成唯一分区 |
| active_build_no/active_attempt_no | VARCHAR(32)/BIGINT NULL | 在读指针；均空表示尚未发布 |
| generation | BIGINT | 每次成功发布递增 |
| current_fence | BIGINT | 每次领取执行租约递增 |
| lease_owner/lease_until | VARCHAR(64)/DATETIME(6) NULL | 分区当前执行权 |
| published_at/coverage_summary | DATETIME(6)/JSON NULL | 发布与分族覆盖摘要 |

领取先锁publication再锁build；每次写暂存/发布都验证attempt/fence/lease。发布时manifest、指针、build终态及dirty确认在同一短report事务提交。不能先DELETE旧事实。

#### 3.5 rpt_dirty_day：变更重算

| 字段 | 类型 | 约束/用途 |
|---|---|---|
| business_date/date_basis/metric_version | DATE/VARCHAR(24)/INT | 与scope构成唯一分区 |
| dirty_generation/acked_generation | BIGINT | dirty>acked即待处理 |
| first_event_ref/last_event_ref | VARCHAR(128) NULL | 原事件关联，不承诺仅靠两列实现全部事件去重 |
| reason/ready_after | VARCHAR(48)/DATETIME(6) | 原单/退款/成本/费用等，营业日结束＋缓冲后才可执行 |
| version | BIGINT | 并发合并控制 |

重复投递最多造成多一次整日重算，不重复累加金额。发布只确认captured代次，新事件保持待处理。更正必须关联真实受影响日期，不能全部记消费当天。

#### 3.6 rpt_food_metric_daily：完整日指标事实

| 字段 | 类型 | 约束/用途 |
|---|---|---|
| business_date/date_basis/metric_version | DATE/VARCHAR(24)/INT | 统计分区冗余，须匹配build |
| build_no/attempt_no | VARCHAR(32)/BIGINT | 不可变执行版本 |
| aggregation_set/metric_family/metric_code | VARCHAR(48)/VARCHAR(48)/VARCHAR(64) | 固定目录白名单 |
| attribution_basis | VARCHAR(32) | 原单/发生/计划服务归属 |
| dimension_key/dimensions/display_snapshot | CHAR(64)/JSON/JSON NULL | 规范维度hash、typed维度和历史名称快照 |
| value_type | VARCHAR(16) | COUNT/QUANTITY/MONEY/DURATION；比率和均值查询计算 |
| count_value | BIGINT NULL | COUNT；DURATION以整数秒及SECOND计量 |
| quantity_value | DECIMAL(24,6) NULL | 精确数量，禁止隐式截断 |
| amount_minor | BIGINT NULL | 币种最小单位，检查溢出 |
| currency/unit | VARCHAR(8)/VARCHAR(32) NULL | MONEY币种必填；数量单位必填；不适用与未知区分 |
| completeness/unknown_reason | VARCHAR(24)/VARCHAR(64) NULL | 完整值或未知/部分覆盖 |
| known_count_value/known_quantity_value/known_amount_minor | BIGINT/DECIMAL(24,6)/BIGINT NULL | 可证明的部分合计；不是完整指标值 |
| sku_no/menu_no/channel_code/station_no/material_sku_no/actor_no | 白名单VARCHAR NULL | 热点过滤类型化列，与dimensions一致；最终长度沿原owner |

唯一键：scope＋build/attempt/metric_code/dimension_key；dimension_key包含aggregation_set、归属基准及全部计量/分组维度。已知完整值恰好一个主数值列有效；UNKNOWN主数值列均空；PARTIAL不能把knownSubtotal塞入完整值列。负值规则按指标定义，不能一律钳成0。

索引至少覆盖build/attempt＋set/metric读取、scope/date/version查询及实际SKU/渠道/工位/物料路径。联合索引长度、JSON和CHECK约束、锁行为在原生MySQL验证；H2只验证快速DAO语义。物理typed列在实施前以实际筛选组合冻结，不允许客户端任意JSON字段分组。

### 4. rpt_export_task：后续第七表

字段：id、tenant_no/entity_no、export_no、actor_no、authorized_store_nos JSON、scope_hash、request_key/hash、query_snapshot JSON、query_hash、build_refs JSON、format、status、object_ref、checksum、row_count、expires_at、job_run_id、error_code、created_at/updated_at。

跨店导出不强塞一个虚假store_no，存固定获准门店列表；actor/scope/key唯一。创建时固定筛选及build引用，再通过原异步任务平台按需执行，避免跨库投递丢失造成永远不导出；不依赖新分布式事务。状态QUEUED/RUNNING/SUCCEEDED/FAILED/EXPIRED。下载每次重验actor、门店权限、状态和到期，走授权代理，不给永久公开URL。过期清理只能删除已确认临时对象。

### 5. 经营数据集与来源

| 数据集 | 完整日分组 | 主指标/来源 |
|---|---|---|
| OPERATING_ORDER_DAY | 门店/日，允许渠道/履约切片；金额分币种 | CORE订单/成交/应收/商品净额；COUNT无币种不复制到多币种行 |
| PAYMENT_CHANNEL_DAY | 门店/发生日/工具/通道/币种 | PAY成功收款/结算、外部流入、手续费 |
| DISCOUNT_TYPE_DAY | 门店/原单日/类型/承担方/币种 | 原CORE/权益最终优惠分配 |
| REFUND_CHANNEL_DAY | 门店/退款发生日/原支付工具/实际退款渠道/币种 | PAY成功退款；原单归属累计另视图 |
| PROFIT_COST_DAY | 门店/归属日/费用或收入分类/币种 | 原商品净额、历史COGS、费用分摊、其他收益；来源证据不足为UNKNOWN |
| DISH_COMPONENT_DAY/MENU_LINE_DAY | SKU/组件角色/单位或币种及已核实餐牌 | FOOD原组件＋CORE唯一金额，套餐父叶不重记 |
| INVENTORY_MOVEMENT_DAY | owner/location/SKU/单位/动作/发生日 | 原库存精确入出/损耗/调整/冲销 |
| INVENTORY_BALANCE_DAY | owner/location/SKU/单位/日边界 | 原时点期初/期末及估值，不SUM余额 |

每个查询只选一个合法aggregationSet；总计与分组不能相加。其他运营数据集沿主TDD§13。费用凭证/COGS缺来源时须先补owner合同，统计六表不能自行创造财务账。

## L3 定时任务与执行Service

### 6. 模块、包与调用边界（实际Service数量见§7.2）

- 共享存储与读写：`backend/shop-report`，包`ai.neargo.shop.report.dao/dto/service/service.impl`。每张新表对应Row/DAO；Service接口与Impl分离，事务显式reportTransactionManager。
- 跨域编排：`backend/shop-app`，包`ai.neargo.shop.reportbridge.job/service/service.impl`；现有ReportStorageInitJob保留原位置和bean，不为归类移动已上线类。新Handler薄封装，不写SQL/金额公式。
- 通用只读合同：`shop-base`既有`ai.neargo.shop.spi`下新增report能力包，先检索唯一归属；原PAY/库存合同可复用则不复制。CORE/PAY/FOOD/库存/费用各由owner提供源事实，不给统计服务直接跨库JOIN。
- 行业接口与Controller：food-api的`ai.neargo.shop.food.service.DiningReportService`、`ai.neargo.shop.food.dto.DiningReportContracts`与food-web的`ai.neargo.shop.food.api.biz`；行业ServiceImpl在food-core，只做行业准入、目录选择及typed共享Port适配，不依赖report实体/DAO。
- 查询独立部署：沿RemoteDiningReportService/内部typed接口接线；共享ReportQueryPort由宿主本地adapter或food-channel远程adapter提供，统计库不复制到FOOD库。事实提取走单独facts-only接口，禁止把经营查询Service递归注册为来源。跨RPC不持有FOOD写事务。

### 7. 精简后的实际任务与Service（最新约定）

**只新增一个常规定时任务FoodReportDailyJob。** 原J01～J12保留为历史能力编号，不再逐项创建JobHandler、cron或Service。已有ReportStorageInitJob属于共享基础设施，本轮不新增、不改成第二个餐饮业务任务。

| 实际入口 | 触发 | 执行方法 | 处理范围 |
|---|---|---|---|
| FoodReportDailyJob | 每门店每营业日一次，日切后配置时间 | ReportDailyService.runDaily(DailyRunCommand) | 计划、漏跑/失败查回、必要dirty补算、来源汇总、校验、发布；末尾按需处理派生数据维护 |
| 原任务平台人工补算入口 | 按需，无独立cron | ReportDailyService.rebuild(RebuildCommand) | 指定授权门店/日期/口径/原因，复用同一buildOne；不新建FoodReportBackfillJob或版本重建Job |
| 原异步任务平台导出入口（后续） | 用户申请，按需执行 | ReportExportService.export(ExportCommand) | 固定exportNo/build集合生成文件；失败沿原平台重试，不新增每分钟导出扫描任务 |

DailyRunCommand携带原runId/bizDate及经原任务定义/调用权限核验的scope；不能信任params自报actor/entity。bizDate指定目标营业日，缺省选择上一已结束营业日。04:00切点门店可配置当地04:30执行，具体时区调度能力实施前核验。同日正常请求幂等，不能重复加总。

### 7.1 合并原任务能力

| 原能力 | 精简后归属 |
|---|---|
| 计划、构建、质量检查 | runDaily/buildOne内部顺序完成，不设置独立调度 |
| 失败重试 | 原任务平台有界重试；先查原结果，超限告警；下次每日主批补缺口 |
| 标脏 | 原业务事件调用ReportStorageService.markDirty，不注册定时任务；下一每日主批或人工rebuild处理 |
| 回看/历史补算 | 每日主批仅处理确有缺口/dirty的日期；人工调用rebuild，不默认全量重跑最近7天 |
| 版本重建 | 同一rebuild方法指定新metricVersion；完整性及对比通过后由受控操作切默认读版本，不自动混版 |
| 健康检查 | 查询API给coverage及健康信息，复用现有任务日志/监控，不新增FoodReportHealthJob |
| 导出过期、旧版本清理 | 到期下载立即拒绝；实际文件/无引用旧统计的清理由每日任务末尾执行或原通用清理设施处理；维护失败不回滚已发布日报 |
| 报表库初始化 | 复用现有ReportStorageInitializer/J13，不新增行业初始化任务 |

导出任务通过原异步平台持久化调度，report表保存原runId/幂等关联；跨库提交失败须可查回/重试。未能确认入队的记录不能标成功，每日维护可补查QUEUED孤儿并告警。无需另建一个分钟扫描器；到期权限检查不能等到每日清理才生效。

### 7.2 精简后的Service归属

| 模块 | 接口→实现 | 职责 |
|---|---|---|
| shop-app/reportbridge.service及service.impl | ReportDailyService→ReportDailyServiceImpl | runDaily/rebuild；内部resolvePartitions/recover/buildOne/validate/maintenance，不再拆Plan/Rollup/Recovery/Quality/Health/Retention六套执行Service |
| 同上 | ReportSourceCollectionService→ReportSourceCollectionServiceImpl | collect，只读各owner，避免日报Service直接调用各域Mapper |
| shop-report/service及service.impl | ReportStorageService→ReportStorageServiceImpl | 统计范围/版本、beginBuild/stage/publish/fail/findResult/markDirty、引用安全清理；统一report短事务 |
| 同上 | ReportQueryService→ReportQueryServiceImpl | metrics/daily/period/trend/payments/discounts/refunds/profit/ranking/inventory/health；同一只读一致事务选版本与事实 |
| shop-app/reportbridge.service及service.impl | ReportRealtimeService→ReportRealtimeServiceImpl | summary/inventory；与日报共用固定目录与纯计算对象 |
| 同上，导出阶段 | ReportExportService→ReportExportServiceImpl | create/get/download/export/expire；按需执行，固定版本及私有对象 |

固定指标目录收敛为ReportMetricCatalog纯配置对象，计算与校验用ReportMetricCalculator，不再为每个步骤创建独立Service接口。food-api/core的DiningReportService及DiningReportFactsService保留必要行业接口/实现和typed传输边界，不是额外定时任务。

通用共享查询Port统一命名ReportQueryPort；宿主adapter只依赖通用合同，默认宿主不强依赖food-core。

### 8. 每日内部执行顺序

```text
FoodReportDailyJob
  → ReportDailyService.runDaily
    → 确定授权范围、目标日和实际缺口/dirty
    → ReportStorageService.beginBuild / 原请求查回
    → ReportSourceCollectionService.collect
    → ReportMetricCalculator.aggregate / validate
    → ReportStorageService.stage / publish
    → 返回分区结果；独立处理必要维护
```

同一buildOne供正常日报、补算及重建复用。简化调度和类数量，不简化scope、精确数量、必需来源、未知状态、attempt/fence、租约或发布原子性。stage每次验证执行权，publish内部再次验证实际内容及manifest；外部不能自报校验成功。所有来源RPC不持有业务写事务或report写事务。

来源合同候选：ReportOrderFactsPort、ReportPaymentFactsPort、ReportInventoryFactsPort、ReportExpenseFactsPort；FOOD使用DiningReportFactsService提供行业快照。统一`readSnapshot(SourceRequest)->SourceSnapshot`语义，包含scope、UTC边界、原归属/发生口径、sourceRef/version、rowCount/hash、coverage。接口名字和owner归属实施前核对原合同，单笔支付查询不能冒充完整支付批量快照。

费用owner未找到完整现成能力，应独立确认系统来源/导入协议；不提供假的零费用实现。库存旧int月报不足以证明精确数量COGS或边界结存，补只读合同再使用。调用PAY仅查询成功收退款和分配，不调用发起/扣款/退款执行API。

## L3 查询API方案

### 9. 公共输入输出及授权

B端前缀候选`/biz/x/food/operating-reports`，Controller类`BizDiningReportController`归food-web。沿平台ApiResult(code,msg,data)封装；内部typed接口保持原内部传输封装规则，不能再包一层ApiResult导致DTO全空。实际已实现路径与参数以§3及源码typed合同为准，以下表格保留完整目标。

公共PeriodQuery：

| 参数 | 类型 | 规则 |
|---|---|---|
| from/to | LocalDate | from含/to不含；必填历史期间，建议最大366日，超限走受控异步流程 |
| storeNos | List<String> | 可省略=当前门店；显式空集合拒绝；每店由原StoreDirectory/主体授权校验，不省略成全主体 |
| metricVersion | Integer可选 | 缺省选服务端已发布默认版本；整次请求固定，不跨版本拼接 |
| currency/unit | String可选 | 金额/数量榜按同币种/单位；未指定多币种总览分组，不换算成假合计 |
| fulfillment/sourceChannel/menuNo/skuNo/componentRole | 白名单可选 | 只有指标和aggregationSet真实支持才允许；不接受任意SQL/JSON表达式 |
| paymentMethod/paymentChannel/discountType | 白名单可选 | 资金/优惠专属组合；没有原分配的交叉维度拒绝 |

tenant/entity/actor来自原账号和门店上下文，不接受公网JSON指定。日期为门店营业日标签；真实UTC边界按原冻结证据。金额long最小单位沿平台序列化，数量使用精确十进制文本，前端不拿浮点自行重算。

公共ReportResult：dataMode(LIVE/DAILY/MIXED)、range、scope、dateBasis/attributionBasis、metricVersion、rows/totals、currency/unit、asOfAt/sourceWatermarks、buildRefs、coverage、missingPartitions、unknownMetrics、isFinal。每指标值为Known(value)或Unknown(reason)，可带knownSubtotal；不确定金额必须null及原因，不能通过COALESCE补0。

正式经营金额/利润读取要求既有BizPerms.FINANCE及原门店范围；目录的非敏感部分可沿ORDER_VIEW。导出另需登记动作权限，未登记前不开放。库存整合结果还需原inventory owner/location授权，不能以FINANCE绕过库存主体隔离。现有库存独立API仍保持原STOCK/CUSTOMER权限语义。

### 10. B端查询端点与调用Service

所有入口先走DiningReportService（food-api接口及food-core Impl），后经通用ReportQueryPort到宿主共享查询Service；独立形态使用typed传输适配，不向客户端暴露内部scope。

| HTTP/路径 | 具体输入 | 输出data主体 | 最终执行Service |
|---|---|---|---|
| GET /metrics | 可选reportCode | MetricDefinitionVO列表：code、口径/公式、维度、计量、阶段、可用性 | ReportQueryService.metrics(CatalogQuery) |
| GET /realtime | 当前store，可选currency及允许切片；不接受任意历史日期 | 当前营业日订单/金额/优惠/退款/渠道及可核实利润，分族水位，isFinal=false | ReportRealtimeService.summary(RealtimeQuery) |
| GET /daily | businessDate必填；storeNos/currency可选 | DailyOperatingReportVO，订单/成交/收付/优惠/利润与完整性 | ReportQueryService.daily(DailyQuery) |
| GET /period | PeriodQuery | PeriodOperatingReportVO，期间核心合计，不生成新汇总表 | ReportQueryService.period(PeriodQuery) |
| GET /trend | PeriodQuery＋granularity=DAY/WEEK/MONTH；metricCodes白名单 | TrendPointVO列表＋完整性；均值用分子分母重算 | ReportQueryService.trend(TrendQuery) |
| GET /payments | PeriodQuery＋groupBy=METHOD/CHANNEL | PaymentBreakdownVO：成功结算笔数/金额、涉及订单数、外部收款、手续费；计数不混用 | ReportQueryService.payments(PaymentQuery) |
| GET /discounts | PeriodQuery＋discountType/承担方 | DiscountBreakdownVO：类型、平台/商家承担、生效分配金额 | ReportQueryService.discounts(DiscountQuery) |
| GET /refunds | PeriodQuery＋原支付工具/实际退款渠道 | RefundBreakdownVO：成功金额/笔数/涉及订单，申请/未决单列，不充成功 | ReportQueryService.refunds(RefundQuery) |
| GET /profit | PeriodQuery＋groupBy=DAY/STORE/COST_CATEGORY | ProfitReportVO：税前商品净额、COGS、毛利、其他收益、费用、经营净利、未知覆盖 | ReportQueryService.profit(ProfitQuery) |
| GET /rankings | PeriodQuery＋target＋metricCode＋limit(默认10/最多100) | RankingReportVO：固定N行、稳定序号、候选数、分母、排行完整性 | ReportQueryService.ranking(RankingQuery) |
| GET /inventory | PeriodQuery＋locationNo/skuNo/unit | InventoryReportVO：期初/收发损调/期末、平衡、估值 | ReportQueryService.inventory(InventoryPeriodQuery) |
| GET /inventory/realtime | 当前门店＋locationNo/skuNo/unit，不接受历史日期 | InventoryRealtimeVO：当前账面/可用/预占及库存水位 | ReportRealtimeService.inventory(InventoryRealtimeQuery) |
| GET /health | PeriodQuery | ReportHealthVO：应生成/已发布/缺失、延迟、来源/利润覆盖、失败，不泄露敏感错误 | ReportQueryService.health(HealthQuery) |

rankings.target固定DISH/BUNDLE/ADDON/MATERIAL/STORE/STATION/STAFF；metricCode须匹配目标/角色/计量，不传任意sort SQL。日榜from=date/to=date+1；周榜周一至下周一；月榜月初至下月初，使用同一接口。第一版历史榜不混今日；今日榜需要显式LIVE及有界真实事实，缺来源不降级伪排行。

库存库存历史与实时采用两个明确端点及InventoryPeriodQuery/InventoryRealtimeQuery，实时端点禁止携带历史日期，不把两种口径混成一个数。日期/粒度/过滤器在最终OpenAPI中用明确DTO约束，不以宽松Map接收。

### 11. 导出及内部API（后续）

| HTTP/路径 | 输入 | 输出/边界 |
|---|---|---|
| POST /exports | Idempotency-Key＋reportCode、query、format；权限/actor由上下文取 | ExportTaskVO(exportNo,status,expiresAt)，创建时固定合法build集合，不同步生成大文件 |
| GET /exports/{exportNo} | 原导出号 | 状态、行数、期限；对象引用不直接公开 |
| GET /exports/{exportNo}/download | 原导出号 | 鉴权下载代理；每次检查当前权限、scope、成功状态及过期 |

人工补算/口径重建先沿原任务平台受控操作，需明确发起人/门店范围和原因；本方案不顺便增加匿名执行端点。报表清理/迁移不暴露公网API。

内部候选：`POST /internal/report/operating/query`对应ReportQueryInternalApi.query(ReportQueryCommand)，由宿主提供共享历史/实时查询；`POST /internal/food/report-facts/snapshot`对应DiningReportFactsInternalApi.readSnapshot，FOOD owner只读事实。其他CORE/PAY/库存/费用来源HTTP只在现有typed Port确实不能覆盖时在对应owner内补充，路径待owner合同review，不声称已有。

内部服务令牌不代替业务授权：必须复核调用方、actor/scope及查询权限，facts与query使用不同操作能力。公共Controller、内部Controller、HTTP客户端排除wrapper/鉴权及序列化都需专项验证。C端不开放商家经营数据；现有C端订单/账单查询保留原接口。平台端仅复用现有OpsFoodSupportController支持入口，若增加统计健康需SYSTEM_INDUSTRY_READ和原范围校验，不自动取得商家利润明细。

### 12. 示例与公式边界

日报请求：`GET /biz/x/food/operating-reports/daily?businessDate=2026-10-24&currency=AED`，主体/当前门店由登录态取。周菜品榜请求：`GET /biz/x/food/operating-reports/rankings?from=2026-10-19&to=2026-10-26&target=DISH&metricCode=DISH_ORDERED_QTY&unit=EA&limit=10`。此示例只表示请求结构，不表示该期间已结束或该单位/SKU已存在；正式历史请求必须通过所有日到期/覆盖校验。

典型data结构（字段候选，示意中的UNKNOWN不是0）：

```json
{
  "dataMode": "DAILY",
  "businessDate": "2026-10-24",
  "currency": "AED",
  "metricVersion": 1,
  "isFinal": false,
  "metrics": {
    "ORDER_CREATED_COUNT": {"value": 120, "completeness": "COMPLETE"},
    "ORDER_TRANSACTED_AMOUNT": {"value": 250000, "completeness": "COMPLETE"},
    "OPERATING_NET_PROFIT_AMOUNT": {"value": null, "completeness": "UNKNOWN", "reason": "EXPENSE_SOURCE_NOT_READY"}
  },
  "coverage": {"orders": "COMPLETE", "payment": "COMPLETE", "cost": "PARTIAL", "expense": "UNKNOWN"},
  "missingPartitions": [],
  "unknownMetrics": ["OPERATING_NET_PROFIT_AMOUNT"],
  "buildRefs": [{"storeNo": "STORE_EXAMPLE", "businessDate": "2026-10-24", "buildNo": "BUILD_EXAMPLE", "attemptNo": 1, "generation": 2}]
}
```

isFinal此处指本次报表完整性/最终状态，不表示永远不可因迟到事实重建；需要分别保留publication发布状态。实际完整响应还须包含公共scope/range/sourceWatermarks；示意省略不构成授权输入。错误沿平台ErrorCode/三语及ApiResult注册，不直接把SQLException或连接消息返回前端。

## §2 开发计划（2026-10-25，已启动）

执行方式：使用writing-plans细化本节，后续执行沿executing-plans。原需求为《餐饮系统-完整需求文档》§7，设计依据为本文件及原报表TDD§12～17。当前只制定计划，不启动编码或生产操作。

### 2.1 固定范围与交付节奏

- 只新增FoodReportDailyJob一个每日任务；补算走runDaily同源的rebuild方法，导出沿原任务平台按需执行。下列工作包不是新增定时任务。
- 共享能力归原shop-report/shop-base；跨owner编排归shop-app/reportbridge；Controller和行业事实归餐饮五模块。默认宿主不得强依赖未启用的food-core。
- 独立report库，六张统计表加后续导出表；旧CORE/FOOD/库存/支付迁移冻结。报表只读资金事实，不新增付款、退款、资产扣减。
- 先完成整批生产代码和测试源码，再统一串行编译，最后一次集中专项测试。编码阶段补测试但不执行；测试阶段修复只重跑直接相关范围，不反复全仓测试。
- 订单/成交额、优惠退款、渠道、库存、毛利及经营净利是主线。费用或历史COGS无真实来源时不能把UNKNOWN功能标成“纯利已验收”。

### 2.2 工作包与依赖

| 工作包 | 开发内容 | 交付件/完成出口 | 前置 |
|---|---|---|---|
| DEV-01 核心合同及来源盘点 | 固定首批指标、金额/时间/分组、权限和API；逐项核对CORE/PAY/库存/费用owner | 指标→原事实→Port→日报字段对账；明确已可读、需扩展、缺来源。保留费用/成本真实依赖，不造0 | 已确认方案 |
| DEV-02 存储与隔离 | 实际库/账号验证、初始化失败分类、六表Row/DAO、ReportStorageService；默认读版本指针 | 新迁移及字段/索引；完整scope、幂等、attempt/fence、lease、原子发布、未知值及小数数量。生产不自动启用 | 01 |
| DEV-03 只读事实与纯计算 | ReportSourceCollectionService、原owner typed适配、ReportMetricCatalog/Calculator | 订单成交、原优惠/税/费用、成功收退款及混合支付分配、精确库存/历史成本、费用归属快照；共享聚合与公式 | 01，存储合同已固定 |
| DEV-04 每日报表与补算 | FoodReportDailyJob、ReportDailyService.runDaily/rebuild；源事件标脏接线 | 每店每日一次；有界补漏/失败查回/必要更正，主批与补算同一buildOne，校验后发布；无额外cron | 02/03 |
| DEV-05 历史与实时查询 | ReportQueryService、ReportRealtimeService、ReportQueryPort及DiningReportService/Controller/remote | 日报、周月/趋势、渠道/优惠/退款、利润、库存、榜单、实时、健康；按本文件API参数输出 | 02/03；04发布数据可用于后续联验 |
| DEV-06 导出与每日维护 | 第七表、ReportExportService及原异步平台接线；到期拒绝/维护 | 按需固定build导出、私有文件与撤权校验；未确认入队可查回；必要清理并入每日末尾，失败不回滚日报 | 04/05 |
| DEV-07 统一编译与契约对齐 | 默认宿主、餐饮五模块、集成/独立形态及受影响来源模块串行编译 | 生产/测试源码全部编译成功；OpenAPI、schema/类清单、权限/i18n及生成物一致，不把编译当验收 | 01～06全部编码完成 |
| DEV-08 集中专项验收与交付 | 隔离MySQL、并发/恢复/权限/金融口径测试；整批一次风险review | 用例真实通过/跳过/外部依赖分列，重要问题修复并定向复测；提交、后续按授权发布 | 07 |

费用来源的处理决策必须在DEV-01给出：能复用则接原owner；外部财务则定义受控只读/导入协议；若无原始业务台账，另列最小费用事实建设方案及验收依赖，不能悄悄在report里建第二套费用录入审批。DEV-03可以完成缺源拒绝和UNKNOWN处理，但这不等于经营净利业务验收完成。

### 2.3 代码文件与方法交付清单

| 范围 | 新增/扩展对象 | 核心方法/约束 |
|---|---|---|
| shop-base/spi/report | 固定查询/来源DTO、ReportQueryPort及确需新增的owner Port | typed范围/时点/版本/来源覆盖；先检索原合同，不复制现成接口 |
| shop-report/config | 扩展ReportStorageInitializer/Properties及真实库/权限校验对象 | 所有新增DDL前验证；API不能持有迁移凭据；暂不可达与迁移失败区别处理 |
| shop-report/dto、dao | 六表Row/DAO；DEV-06加ExportRow/Dao | 每次读写完整scope，所有连接显式Qualifier；SQL留DAO |
| shop-report/service、service.impl | ReportStorageService/Impl | beginBuild、stage、publish、fail、findResult、markDirty、默认版本切换、引用安全维护 |
| 同上 | ReportQueryService/Impl | metrics、daily、period、trend、payments、discounts、refunds、profit、ranking、inventory、health |
| shop-report/support | ReportMetricCatalog、ReportMetricCalculator | 固定合法维度/口径，aggregate/validate；无任意SQL/脚本，无资金写入 |
| shop-app/reportbridge | ReportSourceCollectionService/Impl | collect；适配真实CORE/PAY/库存/费用/FOOD，来源水位独立，不能以单笔查询冒充日完整来源 |
| 同上 | ReportDailyService/Impl、FoodReportDailyJob | runDaily、rebuild、内部buildOne；一个任务声明，只用原任务平台权威触发路径 |
| 同上 | ReportRealtimeService/Impl | summary、inventory；与日报共用定义/公式，不周期落今日快照 |
| 同上，DEV-06 | ReportExportService/Impl及原平台按需适配 | create/get/download/export/expire，无新增分钟导出Job |
| food-api/core/web/channel/server | DiningReportContracts、DiningReportService/Impl、DiningReportFactsService/Impl；BizDiningReportController和必要typed内部/remote适配 | 公开端点归food-web；独立/集成同语义；内部wrapper/鉴权/调用链有界，不递归调用查询当事实 |

代码中不再新增早期候选的ReportPlan/Rollup/Recovery/Quality/Health/Retention独立执行Service，以及对应Job类。原S1-A已交付类保留兼容，不为本轮重命名搬家。

### 2.4 编码阶段要补的测试源码与场景

以下是计划新增测试名，不声称当前已存在或已通过。测试先描述行为，执行按用户要求统一放DEV-08，关键保护通过消融证明。

| 测试组候选 | 必须覆盖 |
|---|---|
| ReportDatabaseIsolationNativeTest、ReportSchemaUpgradeNativeTest | 实际同库/别名误配、有效权限过大、报表不可达不拖主业务、V1/V2升级、索引/约束、旧零售不变 |
| ReportStorageScopeTest、ReportPublicationNativeTest | 越主体/门店、同key异hash、过期lease/旧attempt、发布中断/响应丢失、读者只见完整版本、默认读版本切换 |
| ReportSourceCollectionContractTest、ReportMetricCalculatorTest | 混合支付100=60+40而订单仍1；多券/退款不笛卡尔倍增；套餐父叶不重记；税前/含税、币种、数量精度、UNKNOWN与溢出 |
| FoodReportDailyJobTest、ReportDailyServiceNativeTest | 每营业日一次正常主批、重复触发幂等、夜宵/多时区、停机多日补缺、失败退避、dirty期间新事件不丢、重建不覆盖签核 |
| ReportQueryServiceTest、ReportRealtimeConsistencyTest | 同定义的实时/日报核对、周月均值重算、余额期初期末、完整期间TopN优于每日TopN合并、缺分区不得正式排名 |
| DiningReportApiContractTest、DiningReportTransportTest | 公开身份不可自报、财务/库存范围、默认/集成/独立装配、内部响应不双包装、查询不可形成RPC递归 |
| ReportExportAuthorizationTest、ReportMaintenanceNativeTest | 固定版本、原异步入队查回、撤权/过期拒绝、CSV注入防护、无引用才清理、维护失败不回滚日报 |
| ReportOperatingProfitContractTest、ReportInventoryBoundaryTest | 真实COGS匹配/费用分摊守恒、缺费用净利UNKNOWN；原单位小数、边界结存重建、收发存不自证平衡 |

### 2.5 统一串行编译出口

实施时先确认Java21及当前可用Maven本地仓库；以下为执行顺序模板，不在制定计划时运行。生产与测试源码都要编译，不使用maven.test.skip绕开测试源码。

1. 从明确的本批源码状态编译/安装受影响共享模块及默认宿主：`mvn -o -f backend/pom.xml -pl shop-app -am clean install -DskipTests`。
2. 在共享合同安装后编译餐饮五模块：`mvn -o -f backend-plugins/shop-industry-food/pom.xml clean install -DskipTests`。
3. 独立接入宿主：`mvn -o -f backend/pom.xml -Pfood-standalone -pl shop-app -am clean package -DskipTests`。
4. 集成宿主：`mvn -o -f backend/pom.xml -Pfood-embedded -pl shop-app -am clean package -DskipTests`。
5. 若新增PAY/库存只读provider，按其真实发布入口单独编译；不因此重启或部署PAY/库存。

全部串行，不并行Maven。分别保存形态/源码SHA/命令/退出码，不能用最后一个target包冒充所有部署形态。失败先定位修复再继续；此出口只报告“编码/编译通过”，测试尚未通过前不生产启用。

### 2.6 集中测试、评审与发布出口

- 在一次集中验证阶段按相关模块执行既有回归与本批新用例；测试选择器以DEV-04～06实际生成类名登记，禁止用failIfNoTests关闭后把零测试当通过。
- 原生迁移、精度、锁、fence/发布及隔离要使用隔离的目标数据库环境；H2/Mock不能替代这些证据。测试不在生产写订单/费用/退款。
- 若失败，按根因修复并仅重测直接受影响组；没有新变更/失败风险时不重跑全仓。跨源核对使用能证明的事实和边界，不强迫临时跨库状态构成不存在的全局事务。
- 统一review关注：纯利真实依赖、支付/优惠/退款去重、历史COGS、权限/隔离、唯一每日调度、独立/集成接线，以及复杂代码是否违背精简Service清单。
- 交付记录分“已编码、编译、自动验证、来源依赖、生产启用”五种状态。费用/成本缺源不可以把完整利润功能标完成；资金写入、Keeta、离线及前端仍不因报表交付自动开放。
- 完成后形成明确提交与迁移/开关清单。按后续授权执行备份、真实迁移、发布与健康/运行SHA核验；本计划本身不执行上线操作。

### 2.7 可审阅里程碑

M1=DEV-01～03合同、存储和源适配编码完成；M2=DEV-04～06日报、查询及导出编码完成；M3=DEV-07各形态编译成功；M4=DEV-08集中专项验证和整批review通过。M1/M2是代码里程碑，不安排每个模块独立上线或反复全量测试。

以上为已批准的执行计划；执行结果和依赖清单统一见§3。

## §3 实施记录与交付边界（2026-10-25）

本节为当前工作树实际对账。新增 V3/V4 迁移及 Java/测试源码；迁移只在本地一次性 MySQL 测试库执行。没有对生产建库、迁移、启用任务、推送或部署。功能开关默认关闭；不能将本次验证等同于线上已启用。

### 3.1 工作包状态

| 工作包 | 已编码内容 | 验证/业务边界 |
|---|---|---|
| DEV-01 合同与来源 | typed scope/day/source/query/realtime/export/变更日期合同，指标目录标记实际可用性 | 费用 owner、销售历史 COGS、最终商品级优惠/税分配尚无已核实来源；保持 UNKNOWN |
| DEV-02 分库存储 | V3 六张日报表＋V4 导出表；原生实例/catalog/最小权限验证；scope、attempt/fence、lease、不可变构建与原子发布、默认读版本、dirty 代次 | 旧 V1/V2 不改；业务主库不可回退；SELECT-only reader 不得承担 writer |
| DEV-03 只读来源/计算 | FOOD 原批次/接受行/精确数量与原 CORE 绑定；CORE 原单/有效应收/真实已支付成交；独立 PAY 成功流水及核验退款；库存发生日精确出入库、实时余额；共享聚合及利润公式 | PAY 因遗留无门店归属流水只能 PARTIAL；不执行支付/退款/refresh；领耗不冒充销售 COGS，posting balanceAfter 不冒充历史日末 |
| DEV-04 唯一每日任务 | FoodReportDailyJob：失败查回、连续补漏、dirty 重算和导出维护；同一 buildOne；Outbox 原订单/履约/售后事件标脏 | FOOD 根据原绑定定位原营业日，并按原日切定位事件发生营业日；重复投递可多重算但不累加金额。库存追溯更正等未核实事件不宣称已接入 |
| DEV-05 查询 | 13 个 B 端查询路由；历史只读已发布统计库，今日实时有界读 owner；ISO 周/月趋势、期间榜先聚合后 TopN、利润派生、健康失败状态 | 不同币种/单位分列；缺日期降为 PARTIAL；完整空菜品日可形成完整空榜；门店维度保留，不伪造跨店合计 |
| DEV-06 私有导出 | 3 个 B 端路由；固定查询结果/build 集合、幂等任务、租约/尝试隔离、私有 CSV 下载、撤权复核、到期及遗留对象清理 | 首发仅 writer-host/integrated，默认关闭；独立 reader API 与 writer worker 分离需要后续专用网关。任务 execution_ref 是本地执行号，job_run_id 不伪造 |
| DEV-07 编译 | 默认后端、餐饮五模块、独立宿主、集成宿主、PAY 服务串行编译（含测试源码） | Java21，离线依赖；最终日志及专项验证见§3.4 |
| DEV-08 验证/交付 | 隔离原生 MySQL、并发/恢复/权限/计算/导出及 HTTP 边界专项；整批一次新上下文审查 | 完成证据继续追加§3.4；业务源依赖与生产启用属于独立验收条件 |

### 3.2 实际表、执行器及 API

新增表：`rpt_scope_cursor`、`rpt_build_batch`、`rpt_source_manifest`、`rpt_day_publication`、`rpt_dirty_day`、`rpt_food_metric_daily`、`rpt_export_task`。全部使用独立 reportJdbcClient/reportTransactionManager。只读历史请求不跨回业务库补值。

新增常规定时任务只有 `FoodReportDailyJob`。日报、失败查回、dirty 重算共用 `ReportDailyService.rebuild`；导出按需运行，使用有界 executor（2线程/32队列）和持久化任务，恢复/清理并入每日末尾，无分钟级导出任务。源事件消费者只标脏，不在消费路径计算报表。

B 端统一前缀 `/biz/x/food/operating-reports`：

| 方法/后缀 | 实际参数 | 输出/执行入口 |
|---|---|---|
| GET metrics | 无报表筛选参数 | MetricDefinition 列表（含 availability），DiningReportService.metrics |
| GET realtime / inventory/realtime | 当前门店、metricVersion；不接收历史日期/任意过滤器 | ReportRealtimePort.Result（isFinal=false），realtime |
| GET daily | businessDate、storeNos、metricVersion、metricCodes、limit、currency、unit | ReportQueryPort.Result，query |
| GET period / trend / payments / discounts / refunds / profit / rankings / inventory / health | from含/to不含、storeNos、metricVersion、metricCodes、limit、currency、unit；trend 专属 granularity=DAY/WEEK/MONTH；inventory 专属 inventoryView=BALANCE/MOVEMENT | ReportQueryPort.Result，query；未实现的业务切片或目标参数拒绝，不能静默忽略 |
| POST exports | Idempotency-Key；DiningReportExportReq（view、storeNos、businessDate/from/to、metricVersion、metricCodes、limit、measure） | Task（exportNo/status/expiresAt），export；entity/actor 取登录上下文 |
| GET exports/{exportNo} | 导出号 | Task，getExport；当前 actor 独占及全部原门店权限复核 |
| GET exports/{exportNo}/download | 导出号 | 私有 CSV 字节，downloadExport；不返回公开对象 URL |

精确合同以 `ReportQueryPort`、`ReportRealtimePort`、`ReportExportPort`、`DiningReportExportReq` 为准。Query.Result 包含 rows、buildRefs、missingDays、complete、scope、range、dataMode、dateBasis、sourceWatermarks、buildProblems；逐指标保留 coverage/unknownReason/knownSubtotal。不存在预先造出的全功能 VO。金额最小单位、数量精确小数，客户端不合并异币种/异单位。

currency 只筛选 MONEY，unit 只筛选 QUANTITY。默认“计数＋金额”可以带 currency：金额按币种筛选，尚无币种分配证据的计数显式 UNKNOWN（CURRENCY_COUNT_ALLOCATION_UNAVAILABLE），不返回全币种计数冒充该币种笔数；单位不适用的混合计数同理。若请求中没有任何对应 MONEY/QUANTITY 指标，拒绝无意义筛选。派生利润拒绝这些过滤器并按币种分列。尚无真实渠道/优惠/成本事实时，端点可以返回 UNKNOWN，不表示已具有完整渠道或利润账。

内部路径：宿主 `/internal/reports/food/{query,realtime,metrics}`，导出 `/internal/reports/exports/{create,get,download}`；餐饮 `/internal/food/operating-report/{query,realtime,metrics,facts,day,current-day,affected-days,export,export-status,export-download}`；PAY 采用 ReportPaymentFactsInternalApi.SNAPSHOT。采用现有内部令牌，查询另按 actor/每店 FINANCE（库存另 STOCK）复核；内部错误返回真实 HTTP 状态及空 body，不返回“200＋空 DTO”。公网文件 Resource 透传，普通业务响应继续统一 ApiResult。

C 端不开放经营数据，原订单/账单接口不变；平台端不新增跨商家财务接口。

### 3.3 开关与后续启用清单

1. 独立库先核实 expected-catalog、expected-instance（MySQL server_uuid）以及 reader/writer/migrator 最小权限；迁移账号与运行账号分离，V3/V4 只由受控迁移执行。不得连接主库后静默降级。
2. `shop.report.enabled=true` 与 `shop.report.food.enabled=true` 才装配本批能力；writer 开启原任务平台 `FoodReportDailyJob`，按门店注册 firstDate/metricVersion，追齐后切换默认读版本。单一全局 cron 不保证每个时区恰好在日切后30分钟运行。
3. `shop.report.access-role=READER` 不注册导出写执行器、事件写消费者和每日 writer 任务。导出另需 writer 和 `shop.report.export.enabled=true`。
4. PAY 事实服务在 pay-svc 由 `shop.pay.report-facts.enabled` 控制，宿主不重复注册本地 PAY owner。库存必须显式配置核实后的 `shop.inventory.report-source-zone`；缺配置返回 UNKNOWN。
5. B 端经营需 FINANCE，库存另 STOCK，导出另 `biz:report:export`；不自动给所有员工加导出权限。私有文件每次下载复核 actor、全部原门店、期限、任务成功与校验和。
6. 待真实来源补齐：销售 COGS/完整商品税前净额、优惠/退餐行分配、费用台账及互斥归属、遗留 PAY 门店归属、历史库存期初/期末证据、套餐/小料/工位/员工扩展指标。目录声明与 UNKNOWN 链路已实现，真实业务指标尚未验收。
7. 已接 CORE 原单/履约/售后事件；库存回溯、外部 PAY 独有更正等无已核实来源事件的情况，需受控 markDirty/rebuild，并另补 owner 事件合同；不承诺全部历史更正自动捕获。
8. 生产备份、目标库迁移、配置/权限投放、灰度启算、监测及回滚演练不在本批本地测试中冒充完成，按后续授权执行。

### 3.4 验证证据（继续登记）

已完成一次编码后的集中专项验证，发现问题只回归受影响组。隔离 MySQL 9.7.2 测试库使用独立 migrator/writer/reader，凭据不入库。并发发布、租约过期/旧尝试、完整性校验、默认版本、dirty 重建、失败恢复、私有导出及撤权都用真实持久化测试；不以 H2 代替原生发布语义。

最终编译：默认宿主 install、餐饮五模块 install、独立宿主 package、集成宿主 package、PAY pay-svc package 均成功，包含测试源码；使用 Java21、离线依赖仓库及 -DskipTests，随后执行专项测试。重命名事实适配器后重新编译餐饮和两种宿主，不以旧包代替当前源码。

专项执行记录（不含架构基线检查）：shop-report 54；宿主及受影响 owner/HTTP/Outbox 共64（1条仅独立形态适用的条件跳过）；餐饮事实与实时参数5；独立形态装配7。合计130次执行记录、129通过、1条件跳过，对应独立分支已在独立形态补跑通过；这些是执行次数，跨部署形态有重复用例，不冒称130个独立业务场景。无全仓测试或生产数据试跑。

架构检查另列：ArchitectureTest/ControllerProfileTest 共20条。本批 Port/命名偏差已修复；仍有4个既有失败规则：旧 FoodAmendmentSourcePortImpl/PricingFeeRuleServiceImpl 的包位置、旧 StoredValueFinanceAppServiceImpl 全限定引用、旧 KDS 对 Web runtime 的依赖、4个旧调度方法缺锁。这些源文件未被本批修改；不改守卫或用跳过冒称全仓架构通过。生产发布不能将此记录视为全仓闸门通过。

### 3.5 整批审查与修复对账

执行一次独立只读整批审查，无二次审查或并行实现。8项重要问题及本批规范偏差统一修复，关键行为先复现失败再回归；实际源码及测试一并提交。

| 问题 | 修复 | 失败→通过的验证 |
|---|---|---|
| 真实统一 Outbox 转换丢事件时间 | TransactionalOutboxHandler 将原 envelope.occurredAt 转 UTC LocalDateTime；不造当前时间 | ReportCorrectionConsumerTest.actualUnifiedAdapterPreservesOriginalTimeAndRetriesReadOnlyCorrectionFailure，OUTCOME_UNKNOWN→SENT |
| 标脏暂时失败不会自动重试 | 只读定位＋派生 dirty 写入可安全重放，返回 RETRYABLE；不改变其他外部资金工作流的 unknown 策略 | 同上，OUTCOME_UNKNOWN→RETRYABLE；支持部分写入后的整次重放 |
| 独立 worker 无 FOOD 来源 | FoodReportFactsAutoConfiguration 独立于 api/ops 公网装配，worker 也注册事实/日历/更正日期端口 | ReportStandaloneWorkerWiringTest，端口缺失→唯一有效 bean，无本地food-core/公网Controller |
| 先缺口、后日期已发布导致游标卡住 | 已发布 INITIAL 幂等重放也按连续规则补推进游标，历史结果不重写 | ReportPublicationNativeTest.publishedInitialReplayReconcilesCursorAfterAnEarlierGapWasFilled，原生MySQL失败→通过 |
| 历史失败占满预算饿死当前日期 | 恢复最多用每店预算约1/3，规划保留 dirty 容量；maxPartitions 最小3、默认30，仍只有一个每日任务 | ReportDailyPipelineNativeTest.poisonRetryQueueCannotConsumeCapacityNeededForCurrentDates，失败→通过 |
| 默认计数＋金额加币种被整体拒绝 | 金额筛选；无对应分配的计数明确未知；不混算或偷换口径 | ReportQueryServiceTest.mixedDefaultMetricsFilterMoneyButMarkUnallocatedCountsUnknown，拒绝→合法混合结果 |
| 跨店读取得到互不共存的发布代次 | 查询Service在 reportTransactionManager 的只读 REPEATABLE_READ 中解析版本及读全部门店；无 owner RPC | ReportPublicationNativeTest.multiStoreQueryPinsOneReadSnapshotWhileAnotherStorePublishes，混合代次→同一快照 |
| 实时接口静默忽略参数 | 两个实时接口共用白名单，仅接受 metricVersion，当前门店取原可信上下文 | DiningReportRealtimeParametersTest，不抛错→BAD_REQUEST |
| 本批 Port/包/类命名偏差 | 原事实读取提供方放各域 .port 的 *PortAdapter；查询/实时/导出业务在 service/impl，由薄 PortAdapter 转接；无放松守卫 | 架构检查中的本批10项包位置违规消除；远程事实类名按PortAdapter修正 |

真实原生数据库测试还覆盖并发发布、旧租约/尝试拒绝、脏代次、完整性摘要、最小权限、分数数量/负金额/未知小计；导出覆盖固定快照、撤权、旧上传尝试与到期清理；文件包装错误已先复现 ClassCastException 后修复，普通 JSON 包装回归不变。

### 3.6 实施裁定及剩余依赖

这些裁定不把目标删掉；代价是需要后续真实 owner 数据及发布验收才能达到完整经营报表。

| 裁定 | 原因、约束及误判代价 |
|---|---|
| 测试节奏按用户要求集中执行 | 测试源码随代码补齐、编译后统一专项；失败只回归相关组。未逐模块运行的风险由集中边界测试承担 |
| 首批目录仅对已核实指标宣称可用 | 缺源指标 UNKNOWN/PARTIAL，不能把表/端点齐全当经营净利完成；代价是完整指标仍待真实来源 |
| 原营业日缺行不创建业务日 | 初期“缺行即阻塞”的裁定已被零订单日证据修正：按原策略/DST生成只读 POLICY_INTERVAL，真实冻结日只能确认同一边界。原策略无数值修订号时 policyVersion=0，保留 policyNo/UTC证据；若策略真源被改写，需显式重建而非静默改历史 |
| 阶段编译不是交付验收 | 早期先编译已完成批次再补导出/来源，最终已全部重新编译并集中验证；不把中间成功扩称全功能完成 |
| 按需导出采用派生任务＋有界 executor | 未核实到可直接复用的通用持久化按需分发器；延续原有 executor 方式，持久化7表任务并合入每日恢复。首发writer-host，分离reader/writer导出还需专用网关；不新增cron或伪造job_run_id |
| 完整业务指标与费用分类仍有依赖 | COGS、费用、最终优惠税/退款行分配和历史余额需原owner提供，费用分类互斥需业务真源证明；算术测试不能证明未来分类正确。误判会高估毛利/净利，因此不启用伪0 |
| 自动更正覆盖不冒称全部完成 | 已接原CORE订单/履约/售后Outbox；库存/PAY独有回溯事件等仍需owner合同，现阶段受控markDirty/rebuild兜底。代价是未接事件须人工受控补算 |
| 生产启用及旧架构失败独立保留 | 本批无生产迁移/远端推送/部署；旧架构规则失败不扩大修复。代价是后续发布仍需处理对应闸门、配置、授权与真实环境演练 |

审查未延后本批 minor；包/命名偏差因用户明确要求架构一致，提升为本批修复项。

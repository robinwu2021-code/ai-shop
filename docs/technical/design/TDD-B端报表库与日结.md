# TDD-B端报表库与日结

> **当前进度（2026-10-25）：** BIZ-01～12非支付后端已交付。餐饮经营报表首批后端（独立统计库七表迁移、唯一每日任务、历史/实时查询与私有导出）已集成发布云端，运行99d3fa516、health UP。统计库/跑批/导出未启用，成本及费用等指标仍有真实来源依赖；详见[上线实证](../TDD-餐饮经营报表-数据库任务与API.md#38-集成上线实证2026-10-25)。既有零售报表和J00保留原用途。

> **2026-10-24 餐饮补充：** [§9 实时查询与独立统计库方案](#9-餐饮实时查询与独立统计库方案2026-10-24设计待实现)为分阶段设计；除§10明确列出的初始化能力外，统计表/汇总与查询导出仍未实现，未在生产启用。§0～§8保留原零售实现及历史验收。餐饮历史报表每日汇总至独立报表库，当前营业日和现场操作读取实时业务事实。

> 状态：**已实现** · 创建 2026-09-30 · P1–P4 全部落地 2026-10-01
> （毛利与逐商品退货未做，上线前还需在生产库副本上跑迁移 —— 都写在 §7）
> 档位：**2**（新表族 + **第四个独立库** + 不可逆的存储决策）
> 依据：[B端报表清单-由浅入深](../../requirements/B端报表清单-由浅入深.md) ·
> 原型 [b-reports.html](../../../prototypes/b-reports.html) s01–s05

---

## §0 对账一 · 需求 → 设计

| # | AC（来自原型与清单） | 由谁满足 |
|---|---|---|
| AC1 | 近 7/14/30 天逐日的成交额、单量、退款 | `rpt_daily_store` + `GET /biz/report/daily` |
| AC2 | 顶部给本期合计与**环比**（上一个等长区间） | 同上，服务端算两段再相减 |
| AC3 | 按月逐月的营收与**订单数** | 由 `rpt_daily_store` 按月聚合，补上今天 `Statement` 缺的单量 |
| AC4 | 商品 TopN，可按件数 / 销售额排 | `rpt_daily_goods` + `GET /biz/report/goods` |
| AC5 | 每行同时给件数与销售额 | 两列都落表，不靠排序字段单给 |
| AC6 | 自带客流占比能按时间段看 | `rpt_daily_store.owned_*` 两列 |
| AC7 | 多门店能分店看、也能合并看 | 三张表都带 `store_no`，查询层决定合并与否 |
| AC8 | 昨天的数据今天一定在 | `rpt_job_watermark` + 水位告警 |

---

## §1 现状与影响面

### 今天的报表全是「现算」

| 报表 | 怎么来的 | 代价 |
|---|---|---|
| `mStats` / `statsByStore` | 扫 `ord_sub_order`，`TRANSACTED` + 本月起 | 每次请求一次全表扫 |
| 动销排行 | 扫 `inv_ledger` 近 N 天再内存聚合 | 同上 |
| 对账单 | 扫结算表，单账期 | 跨月要发 N 次 |

`MerchantOrderServiceImpl.statsByStore` 的注释里已经写着这件事：
「**一次扫完按店分组，不是逐店调 stats()**：后者是 N 次全表扫，而门店数正是这个功能的自变量」。
**商品数、天数是同一类自变量** —— 逐日 × 逐商品的报表用现算，这条路走不远。

### 三个供不上的需求

① 没有逐日序列（`mStats` 只有今日/本月两个定点数）；
② 商品 × 金额无处可答（`inv_ledger` 只有成本没有售价）；
③ 跨月并排要发 N 次请求。

### 这个仓库已经有三个独立库

`InventoryDataSourceConfig`（`inv-pool`）· `JobStoreConfig`（`job-pool`）· `PayDataSourceConfig`。
**装配模式成熟，而且背后有一次线上事故** —— 本方案照抄它们，§4 逐条列出。

---

## §2 方案

### 2.1 库与模块

```
ai_shop_report        第四个独立库，表前缀 rpt_
backend/shop-report/  新模块（Maven），只依赖 shop-base
```

**读写方向是单向的**：日结作业从平台库/进销存库**读**，往报表库**写**；
报表查询只**读**报表库。报表库不被任何业务写入，**丢了可以重跑重建**。

### 2.2 表（3 张）

#### ① `rpt_daily_store` 门店日汇总 —— R1 / R2 / 自带客流的底座

| 列 | 说明 |
|---|---|
| `stat_date` DATE | 统计日（自然日，按**下单时间**归属，见 2.5 ②） |
| `entity_no` / `store_no` | 商户主体 / 门店。**都不为空**，合并看时在查询层 SUM |
| `orders` / `gmv_minor` | 成交单量 / 成交额 |
| `refund_orders` / `refund_minor` | 退款单量 / 退款额（**按退款发生日归属**，不回冲原单那天） |
| `buyers` / `new_buyers` | 下单人数 / 其中首单人数 |
| `owned_orders` / `owned_gmv_minor` | 自带客流的那部分（`TrafficSource.MERCHANT_OWNED`） |
| `commission_minor` / `service_fee_minor` | 佣金 / 履约服务费 |
| `freight_income_minor` / `freight_cost_minor` | 运费收 / 支 |
| `net_minor` | 应结 = 毛 − 佣金 − 服务费 ± 运费 |
| `currency` | 币种，跟着金额走 |

> **客流来源不单独建表**：`TrafficSource` 只有 `MERCHANT_OWNED` / `PLATFORM` 两个值
> （库列注释「下单时固化」），两个值建一张表是又一张「建了表没人读」的表。
> 落成两列 `owned_*`，平台那部分 = 总数减去它。

#### ② `rpt_daily_goods` 商品日汇总 —— R3 的底座

| 列 | 说明 |
|---|---|
| `stat_date` · `entity_no` · `store_no` · `goods_no` | 联合主键 |
| `sku_no` | 可空：同一 `goods_no` 多 SKU 时按商品汇总，SKU 维度二期 |
| `title` / `spec` / `category_no` | **写入时快照** —— 报表库不 JOIN 商品表（2.5 ①） |
| `qty` / `amount_minor` | 件数 / 销售额，取自 `ord_item.qty` 与 `ord_item.amount` |
| `refund_qty` / `refund_amount_minor` | 退货件数 / 退款额 |

> **成本与毛利不在本期**：销售额在交易域（`ord_item.amount`），成本在进销存域
> （`inv_ledger.unit_cost_minor`）。跨两个域，且成本口径要先定
> （出库那一刻的成本 vs 当前进价，两者不同）。原型 s04 的「毛利」那一档本期置灰。

#### ③ `rpt_job_watermark` 作业水位

| 列 | 说明 |
|---|---|
| `job_key` | 主键，如 `daily-store` / `daily-goods` |
| `last_stat_date` | 已经算到哪一天 |
| `last_run_at` / `rows_written` / `duration_ms` | 上次跑的时间、写了多少行、耗时 |

> **这张表是 AC8 的全部依据**。没有它，「昨天的数据没生成」的表现是报表里少一行 ——
> 而少一行和「那天真的没单」长得一模一样。

### 2.3 日结作业

```java
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class ReportDailyRollupJob implements JobHandler {
    @Scheduled(cron = "${shop.job.report-daily-rollup.cron:0 30 1 * * *}")
    @SchedulerLock(name = "report-daily-rollup", lockAtLeastFor = "PT1M", lockAtMostFor = "PT2H")
```

照 `MemberLevelRecomputeJob` 的形状：`JobHandler` + `@Scheduled` + `@SchedulerLock`
+ `JobSupport.run` + 一个 `JobDeclaration` Bean。
**ShedLock 的锁表在平台库**（`V91__shedlock.sql`），报表库不用重复建。

#### 关键决策：**重算一个窗口，不做增量累加**

每次跑 `[T-N, T-1]`（N 默认 3，可配），对这几天**先删后写**。

> **为什么不增量**：退款、售后、改价都会改动**历史某一天**的数字。
> 纯增量累加的话，一笔前天的退款永远补不回去，而差额会一直留在账上 ——
> 且不会有任何东西报错。重算窗口让「最近几天」始终是对的，
> 代价只是每天多算 N 天的量（一个门店一天几十单，可以忽略）。
>
> **幂等由此而来**：同一天跑几次结果一样，重跑是安全的。补历史就是把 N 调大跑一次。

### 2.4 读侧：今天与历史的分界

| 时间 | 数据来源 | 理由 |
|---|---|---|
| **今天** | 现算（`mStats` 那条路不动） | 今天的数还在变，落表就会比实际少 |
| **T-1 及以前** | 读 `rpt_*` | 已经封账，且要能按日/按月任意切 |

> **这条必须写进接口注释**：不写清的后果是「近 7 天」里今天那一格恒为 0
> —— 因为日结还没跑到它。服务端把今天那一格用现算补上。

### 2.5 跨库的三条硬约束

**① 不 JOIN，名字在写入时快照。**
报表库里没有商品表、没有门店表。`title` / `spec` 落的是**当时**的值 ——
商品改名之后，历史报表显示的仍是当时的名字，这是对的：那张报表描述的是那一天。

**② 归属日按「事件发生日」，不回冲。**
成交归下单日，退款归**退款发生日**。回冲原单那天的后果是：
昨天截图发群里的数字，今天再看会变。原型 s02 的底部提示写的就是这条。

**③ 报表库不装拦截器，隔离靠查询层显式带条件。**
同 `JobStoreConfig` 的第三条：本库没有租户、没有数据域、没有软删。
**所以每一个查询方法都必须显式带 `entityNo`（多门店时再带 `storeNos`）** ——
平台库那套行级隔离在这里不存在，漏一个条件就是越权，而且**不会报错**。
§5 为此单列一条测试。

### 2.6 P4 · 订单列表加时间区间（契约变更）

**这是三张报表共同的下钻出口** —— 没有它，报表上的每个数字都点不进去
（原型 s05 画的就是这一屏）。

| 项 | 变更 |
|---|---|
| 端点 | `/biz/order` **不新增**，加两个可选查询参数 `from` / `to`（`yyyy-MM-dd`，含两端） |
| 权限码 | 不变（`order:view`），它只是把已有列表筛窄 |
| 库表 | **不动**。`ord_sub_order.created_at` 已有索引覆盖的前缀即可 |
| 端上契约 | `mOrderList` 的 `q` 加两个可选字段 |

**四条实现约束**：

1. **按下单时间筛，与报表同一条时间轴。** 报表的「那天」是下单日，
   列表若按支付时间或更新时间筛，点进去的单数就对不上 ——
   而两边各自都说得通，这正是口径分岔最难查的形状。
2. **左闭右闭，在服务端翻成左闭右开。** 入参 `to=2026-09-30` 的含义是
   「含 9 月 30 日整天」，实现上取 `< 10-01 00:00`。
   要防的错是**忘了 +1 天**（写成 `< 09-30 00:00`）—— 那会把整个 `to` 当天排除，
   从报表点「今天」进来得到空列表。
   > ⚠️ 起初这里写的理由是「`<= 23:59:59` 会漏掉那一秒里的单」。
   > **那条对本表不成立**：`ord_sub_order.created_at` 是 `datetime(0)`，存不住小数秒，
   > 两种写法对它能存的每一个值都等价。选 `< to+1` 是为了将来加精度时仍然对。
3. **两个都可空，且可以只给一个。** 只给 `from` = 从那天起到现在；
   只给 `to` = 截至那天。报表下钻只会两个都给，但手工调接口不该因此报错。
4. **非法日期拒绝，不要静默忽略。** 传 `2026-13-01` 时必须报错 ——
   静默忽略会让调用方拿到一个**没筛过的全量列表**，而他以为那就是那一天的单。
   这比点不动更糟（§六之二 已经记过同一条）。

---

## §3 选型（2 档必填）

| 方案 | 优点 | 为什么没选 |
|---|---|---|
| A. 在平台库加汇总表 | 最省事，能 JOIN、能一个事务 | **不隔离扫描压力** —— 报表查询与交易读写抢同一个库；而报表恰恰是「偶尔来一次、一次扫很多」的负载 |
| B. 读库 / 从库 | 不改代码 | 只解决读压力，不解决「逐日 × 逐商品要预聚合」这件事；且多一套主从运维 |
| **C. 独立报表库（选）** | 压力隔离、可重建、与交易库的一致性要求解耦 | 代价：跨库不能 JOIN、无跨库事务、T+1 延迟 —— 三条都能接受（见 2.5 与 §4） |

**决定性的两条**：
① 报表数据是**可重建的派生数据**，丢了重跑即可 —— 它不需要交易库那种一致性保障；
② 这个仓库已经有**三个**独立库的成熟装配，第四个是走熟路，不是开新路。

---

## §4 风险

| # | 风险 | 处置 |
|---|---|---|
| R1 | **第二数据源接走平台的自动配置** —— Boot 的 DataSource / SqlSessionFactory / Flyway 三处自动配置都是 `@ConditionalOnMissingBean`，一出现第二个就整体退让，**行级越权防线静默丢失** | 平台侧已由 `PlatformDataSourceConfig` 显式接管；本模块一个 bean 都不标 `@Primary` |
| R2 | **注入拿到平台数据源** —— 2026-08-27 线上真实发生：19 张 `inv_*` 连同迁移历史建进平台库，`inv-pool` 从不启动，零 ERROR | 注入一律写 `@Qualifier`（参数名兜底会输给 `@Primary`）+ 照抄 `mustBeOwnDataSource` 那道护栏，pool 名不对就**拒绝启动** |
| R3 | **声明 `Flyway` 类型的 bean** 会让平台自己的迁移一次都不跑，库停在那天 | 迁移凭证 bean 刻意不是 `Flyway` 类型，返回自定义 record（照 `JobMigrated`）；自己的历史表 `rpt_flyway_history`，迁移号从 V1 重来 |
| R4 | **口径分岔** —— 汇总与现算两套实现，「总览说 3 单，点进去只有 2 单」 | 两边共用同一个 `OrdSubOrder.TRANSACTED` 与同一条时间轴；§5 有一条逐日对账测试 |
| R5 | **越权** —— 报表库无数据域拦截器 | 查询方法签名强制带 `entityNo`；§5 有一条只查得到自己的测试 |
| R6 | 日结没跑，报表少一天 | `rpt_job_watermark` + 水位落后告警；读侧发现缺口时明确显示「统计中」而不是 0 |
| R7 | 生产库是 **MySQL 9.7** | 新迁移**禁用 `uca1400` 排序规则**；建表照平台库现行写法 |

---

## §5 对账三 · 实现 → 需求（测试）

**18 条，全部跑过；十次消融全部红过。**

| AC / 风险 | 测试方法 | 判据 |
|---|---|---|
| AC1 · AC2 | 随 `/biz/report/daily` 的读侧一起验 | 逐日与环比的组装无 DB 分支 |
| AC3 | `ReportDailyStoreDaoTest#monthlyGroupsByCalendarMonth` | 月界在月初那一天：9/30 与 10/1 不合并 |
| AC4 | `ReportDailyGoodsDaoTest#orderByChangesTheWinner` · `#mergesAcrossDays` | 两种排法给出**不同**的第一名；跨天合并 |
| AC5 | `ReportDailyGoodsDaoTest#giftsStayOutOfQty`（DAO 契约）<br>`GoodsAggregateGiftTest#giftLinesDoNotCountAsSales`（**聚合口径**） | 两层各一条 —— DAO 那条管「分开存取」，聚合那条管「哪些行算哪个数」 |
| AC6 | 由 `owned_* / attributed_orders` 两列承载 | 占比的分母是**有归因的单**，见 V1 的列注释 |
| AC7 | `ReportDailyStoreDaoTest#scopeIsExplicit` | 空门店集合返回空而**不是查全部** |
| AC8 | `ReportWatermarkDao` + 读侧的 `complete` 标记 | 缺口不画成 0 |
| **幂等** | `ReportDailyStoreDaoTest#rerunIsIdempotent` | 同窗口跑三次结果不变 |
| **重算窗口** | `ReportDailyStoreDaoTest#lateRefundFixesHistory` | **前天的退款今天跑批能改正前天那一行** —— 「不做增量」的唯一判据 |
| R1 · R2 | `ReportDataSourceGuardTest`（3 条，纯单元）<br>`ReportWiringSmokeTest#secondDataSourceDoesNotTakeOverPlatform` | 后一条在**真实容器**里验「按类型注入拿到的还是平台那套」 |
| R3 | `ReportWiringSmokeTest#reportContributesNoFlywayBean` | 判据是「**有没有** Flyway bean」，不是「某一个 bean 的类型」 |
| R4 | `ReportVsLiveConsistencyTest#aggregatesMatchLiveStats` | 日结逐日合计 == 现算的本月 |
| R5 | `ReportDailyStoreDaoTest#scopeIsExplicit` | 漏 `entityNo` 抛错 |
| P4 | `OrderListDateRangeTest#toIncludesTheWholeDay` · `#filtersOutsideTheRange` | `to` 含那一整天（防「忘了 +1 天」） |

**空转防护**：凡是从库里取数的断言都配了对照量 ——
`ReportVsLiveConsistencyTest` 自己造两单（不造的话本地库当月无单，「0 == 0」会让它永远绿）；
`GoodsAggregateGiftTest` 先断言探针被聚合到；`OrderListDateRangeTest` 末尾断言不限时间时能查到 2 单。

---

## §6 对账二 · 设计 → 实现

**新模块 `backend/shop-report/`（18 个文件）**：pom · 2 份迁移 · 配置两件 ·
3 个 DAO · 3 个 record · 4 个测试类。
**装配层 `shop-app`**：`reportbridge/`（日结作业 + 读侧服务）· `portal/biz/BizReportController` ·
4 个测试类 · `application-rptwiring.yml`。
**交易域 `shop-core`**：`MerchantOrderService` 加三个方法（两个聚合 + `list` 的时间区间）。

### 偏差说明

| # | 与 §2 的设计不符之处 | 为什么 |
|---|---|---|
| 1 | `rpt_daily_store` **没有** `freight_cost_minor` | 起草时写了，查下来运费成本只在结算域按日算，子单上没有 —— 那是一个没有来源的列 |
| 2 | `rpt_daily_store` **多了** `attributed_orders` | 自带客流占比的分母是「有归因的单」不是全部单（`StatsSummary` 的 javadoc）；少这一列，历史单越多商家的费率档越吃亏 |
| 3 | `rpt_daily_goods` **没有** `refund_qty / refund_amount_minor` | 同 1：`ord_after_sale` 只到子单级，没有行级信息 |
| 4 | `rpt_daily_goods` **多了** `gift_qty` | 赠品行价格为 0，混进 `qty` 的话「送出去 100 件」会被读成「卖了 100 件」 |
| 5 | §2.6 约束 2 的理由改写过 | 原写「`<= 23:59:59` 会漏掉那一秒」，而本表是 `datetime(0)` 存不住小数秒，两种写法等价。真正要防的是「忘了 +1 天」 |
| 6 | 月报**没有建月表** | 从 `rpt_daily_store` 聚合即可；月表要自己维护一套「哪个月重算过」的状态 |

**1 与 3 是同一型**：两次写出没有数据来源的列，都在落地前删掉了。
留两个恒为 0 的列比没有更坏 —— 它们看起来是「没有」，真相是「不知道」。
下次设计表时先把每一列的来源列出来再动手。


## §7 计划与完成情况

| 步 | 内容 | 状态 |
|---|---|---|
| **P1** | 建库与模块骨架 + `rpt_daily_store` + 日结作业 + 水位 | ✅ |
| **P2** | `rpt_daily_goods` + 商品榜查询 | ✅ |
| **P3** | 按月聚合（从日汇总，不建月表）+ 补订单数 | ✅ |
| **P4** | `/biz/order` 加 `from`/`to` | ✅ |

三个端点：`/biz/report/daily` · `/biz/report/goods` · `/biz/report/monthly`，
端上契约四处与十一份产物均已跟上。启用方式见
[报表库-生产启用手册](报表库-生产启用手册.md)。

### 还没做的两件

| 事 | 为什么还没做 |
|---|---|
| **毛利**（原型 s04 的第三档） | 销售额在交易域、成本在进销存域，**跨两个域**；且成本口径要先定（出库那一刻的成本 vs 当前进价，两者不同）。原型里那一档置灰 |
| **逐商品的退货** | `ord_after_sale` 只到子单级，没有行级信息。要做得先让售后单带上退的是哪几行，那是交易域的改动 |

### 上线前必须有人做的一件

**在生产库副本上跑一遍 V1/V2。** 已在本机 **MariaDB 12.2** 上验过
（建表、索引逐条核对、10 条 DAO SQL 原文解析、两条关键查询真执行），
但生产是 **MySQL 9.7** —— 那一轮排掉的是语法与结构的粗错，
**排不掉两个引擎之间的差异**。需要有 DB 凭据的人执行。


## §8 收工清单

- [x] §0 的 8 条 AC 都有落点，没有挂不上 AC 的设计条目
- [x] §5 每条有测试方法，跑过；**十次消融全部红过**
- [x] §6 填了实现清单与**六条偏差**（两条是「我发明了没有来源的列」）
- [x] 新迁移在**真库**上跑过（MariaDB；MySQL 9.7 的残留差异见 §7）
- [x] 端点按 `/biz` 的七处清单登记齐（判权表 · 契约四处 · RESPONSE_TYPES · 产物）
- [x] 生成物重新生成（openapi-b 249 路径 / 281 操作）
- [x] 本文状态改成「已实现」


## §9 餐饮实时查询与独立统计库方案（2026-10-24，设计待实现）

### L1 定位与结论

本节执行用户最新要求：报表分实时数据与统计数据；已结束营业日每天跑定时任务，将每日汇总写入独立数据库，列明报表、任务与补算规则。本轮只提交方案，不继续之前的实时期间报表编码。依据：[餐饮PRD§六/七](../../requirements/餐饮系统-完整需求文档.md)、[餐饮主计划](../TDD-餐饮订单结账-完整开发计划.md)、[任务管理PRD](../../requirements/PRD-定时任务管理.md)、[ADR-040](../ADR/ADR-040-餐饮销售配置与耗用权威边界.md)。

**默认方案：当前营业日及现场待办实时查询；已结束营业日每天汇总落表；周/月/自定义历史期间只汇总统计库。** 报表库沿现有`ai_shop_report`、`shop-report`模块和原任务平台扩展，不把rpt表建到订单/商品/fnb业务库。生产建议独立实例，至少必须是独立database、连接池、账号和迁移历史；仅换表前缀不满足本次要求。

源码核验（不等于生产已启用）：

| 已有能力 | 当前事实 | 本方案处理 |
|---|---|---|
| ReportDataSourceConfig | 已有reportDataSource、reportTransactionManager、reportJdbcClient、rpt_flyway_history；缺URL拒绝启动，不回退主库 | 复用并增加真实数据库身份隔离验收；池名校验不能证明URL没指向业务库 |
| 报表迁移V1/V2 | rpt_daily_store、rpt_daily_goods、rpt_job_watermark，零售自然日口径 | 原字节、字段和口径保留；餐饮营业日不直接写进旧表 |
| ReportDailyRollupJob | report-daily-rollup默认01:30、回看默认3天；源码使用服务器LocalDate和CNY | 保留旧零售任务，餐饮另声明handler，使用原门店时区/日切及原币种 |
| DailyReportService | 今日现算、历史读汇总，按全局watermark识别缺口 | 复用分层思想；餐饮增加门店/日期级覆盖，不能仅凭全局最大日期说所有门店齐全 |
| BusinessCloseServiceImpl | 餐饮原营业日报表仍实时读取fnb事实，签核快照在业务库 | 用于受控今日/诊断及原营业日签核；历史分析改为查询报表库，签核不搬成可删除统计投影 |
| JobDeclaration/JobInvocation | 已有handlerName、runId、bizDate、params、超时/锁约束及任务管理 | 复用调度、日志、人工触发；不引入新的调度平台，不让本地Scheduled和远程worker重复触发 |

### L2 数据分层与查询路径

![实时查询、日汇总与独立报表库](../diagrams/food-report-realtime-statistics.svg)

图中所有汇总写入均由任务执行器完成。报表查询不跨库JOIN，不在读接口触发补算；报表库故障不阻断下单、制作或打印；这是新增目标，必须先完成下述初始化隔离，现有启动期同步Flyway不能直接满足。跨库没有全局事务，任务来源读取与统计发布分别提交，通过原buildNo查回恢复。

| 类别 | 业务例子 | 数据来源 | 时效与标签 |
|---|---|---|---|
| REALTIME实时 | 桌台、排队、厨房、待出品、未结客单、当前营业日经营概览 | 原业务owner只读接口/Port | asOfAt；当前仍在变化，不标成正式日报 |
| DAILY日统计 | 已结束营业日的订单/菜品/渠道/厨房/退餐等指标 | 独立库已发布日统计 | businessDate、generatedAt、sourceWatermarks、metricVersion、完整性 |
| PERIOD期间统计 | 日报、周报、月报、年度趋势、门店对比 | 对日统计按相同口径聚合 | 列出缺失日期/门店，不把缺口填成0 |
| MIXED含今日 | 近7天且包含当前营业日 | 历史日统计＋单独今日实时段 | 两段分别给来源、水位，整体isFinal=false；不覆盖历史行 |
| SIGNED签核 | 已签核日结与后续更正 | 原fnb日结快照与审计权威 | closeNo/revision/snapshotHash，和当前重算差异并列展示 |

首版不周期落“今日5分钟快照”，避免和每日统计混淆。现场接口采用门店、范围和分页约束；不开放无界实时多店全历史查询。统计库未启用/不可用时，历史查询返回明确NOT_READY/UNAVAILABLE，不静默改扫业务库。

### L3.1 统计日期、状态与指标口径

1. **营业日复用原配置。** 使用可信门店IANA时区、营业日切点和生效版本，冻结businessDate、policyNo、startsAtUtc/endsAtUtc。区间左闭右开。切点04:00时，10月21日02:00属于10月20日。支持23/25小时夏令时；不用服务器零点或固定24小时计算。
2. 已冻结在原订单/台账的营业日不得用当前新配置重归日。付款、退款、厨房事件按各自发生营业日统计，同时保留原单营业日关联。旧`ORIGINAL_ORDER_BUSINESS_DATE`厨房汇总只代表原单队列视图，不直接冒充“当天实际制作”。
3. **原单归属视图与发生日视图分开。** dateBasis=BUSINESS_DATE是日期边界；attributionBasis=ORIGINAL_ORDER/OCCURRENCE是事实归属。退款当天计发生额，原单退款分析另选ORIGINAL_ORDER，两者不能相加。
4. 订单数=原核心子单去重；接单批次数=原FOOD batch去重；就餐客单数=原check去重。多轮加菜不能同时当新增客单，AA付款次数不能当订单数。无check场景单列，不能虚构桌台客单。
5. 先付/后付以冻结payMode和真实生命周期区分。ACCEPTED不是付款；退款申请不是退款完成；库存出库不是实际物理用量。
6. 套餐收入沿原行唯一金额分配；父/叶角色明确。父项用于套餐销量，组成项用于制作/耗用，默认不把父子数量和收入重复相加。配料另列选配量/加价，不从当前菜单价格倒算历史。
7. 数量按原单位分别汇总，BigDecimal受控精度；金额long最小币种单位、币种分组并检查溢出。AED与CNY不直接相加；没有真实汇率来源，不给伪“统一总金额”。
8. 来源缺失/未接通，指标值为null并给unknownReason。真实完成扫描、确实无事实才是0。尚未跑批、来源失败与无业务三者必须区分。

指标目录使用固定白名单和版本，不做任意SQL/脚本规则引擎。每个metricCode定义：名称、valueType、来源owner、有效状态、dateBasis/attributionBasis、去重键、允许维度、单位/币种、聚合方式及前置能力。口径变更增加metricVersion，先补算再切默认读取，不将新旧口径混加。

| 指标/公式 | 规则 |
|---|---|
| 期间订单量/数量/金额 | 只对相同口径、单位、币种的可加指标SUM |
| 净收款 | 同币种、同发生日范围的真实成功收款－真实成功退款；不含报价/未收应收 |
| 平均出品时长 | SUM(有效耗时)/SUM(对应任务数)，不平均每日平均值；暂停/重做是否计入由版本规定 |
| 人均/客单价 | 同一归属范围的可核实净销售额/有效就餐人数或完成客单数；缺分母为null |
| 翻台率 | 有效完成桌次/有效可营业桌数；转台/拼桌去重、可营业资源历史不足时为null |
| 毛利/毛利率 | 可核实净销售额－同口径历史销售成本；真实配方领料不自动等于销售成本，当前缺来源保持未知 |
| 跨日去重顾客数/复购率 | 不SUM每日去重人数；首批不提供精确期间去重，后续补独立去重事实模型后开放，不用近似值冒充精确值 |

### L3.2 报表清单

A=非支付优先；B=补齐行业事实后；C=依赖资金/成本真实owner，本轮仅设计。所有报表支持获准门店、期间、口径、水位及钻取；不是所有维度任意做笛卡尔积。

| ID / reportCode | 报表 | 主要指标与维度 | 路径/阶段 |
|---|---|---|---|
| F01 LIVE_OVERVIEW | 当前营业概览 | 接单/待制作/待交付/未结客单、来源完整性 | 实时/A |
| F02 LIVE_TABLE | 桌台与排队 | 空闲/用餐/待清台、等位、叫号、等待时长 | 实时/A |
| F03 LIVE_KITCHEN | 厨房工位看板 | 待制/制作中/已出品、等叫、超时、工位负载 | 实时/A |
| F04 DAILY_OVERVIEW | 营业日报 | 原子单数、批次数、客单数、有效菜品量、取消/退餐申请量 | 日统计/A |
| F05 PERIOD_TREND | 周/月/自定义趋势 | 日/周/月可加指标；环比按同样完整范围计算 | 日统计聚合/A |
| F06 STORE_COMPARE | 多店对比 | 同口径接单/菜品量/厨房效率；授权店分别展示 | 日统计聚合/A |
| F07 DISH_SALES | 菜品/规格分析 | 主项销量、赠送量、单位、类目/SKU；金额按owner能力开放 | 日统计/A，金额C |
| F08 MENU_CHANNEL | 餐牌/渠道/餐段 | 餐牌及版本、早餐/午餐/晚餐/特殊餐牌、H5/点餐机/员工/渠道及堂食/自提/外送 | 日统计/A，缺冻结维度标UNKNOWN |
| F09 KITCHEN_EFFICIENCY | 厨房出品 | 开始/出品/上菜/撤菜/损耗/重做量、有效耗时分子分母 | 发生日日统计/A |
| F10 RETURN_OPERATIONS | 退餐处理 | 原行申请量、CANCEL/LOSS/KEEP及处理状态；关联原单 | 日统计/A；不当成退款成功 |
| F11 OPTION_BUNDLE | 套餐与选配 | 套餐销量、组成品需求、小料选择/加价、替换/赠送 | 日统计/B；以实际成交事实为前提 |
| F12 TABLE_SERVICE | 桌台运营 | 桌次、就餐人数、用餐/等位时长、翻台/座位利用率 | 日统计/B；资源有效历史不足指标关闭 |
| F13 STAFF_OPERATIONS | 员工操作 | 实际接单、催菜、传菜、撤菜等动作与数量 | 日统计/B；SYSTEM/买家操作不计员工业绩 |
| F14 MATERIAL_USAGE | 物料耗用与损耗 | 销售理论/制作理论/真实出库/损耗分别列，物料/原单位/配方版 | 日统计/B；现有BLOCKED量仍为理论 |
| F15 PAYMENT_REFUND | 收款退款 | 真实成功金额/笔数、渠道、未收/未知及退款在途 | 日统计/C |
| F16 DISCOUNT_TAX | 优惠与税费 | 活动/券/人工优惠及原税分配，税前额/税额/含税额分列 | 日统计/C；不按当前税率重算历史 |
| F17 SHIFT_CLOSE | 班次与日结差异 | 班次收付/盘差、原日结版本、签核状态及重算差异 | 签核权威＋统计/C；只读差异不代签 |
| F18 COST_MARGIN | 历史成本与毛利 | 原销售成本、损耗成本、净销售、毛利及未知原因 | 日统计/C |
| F19 CUSTOMER_REPEAT | 顾客/复购 | 日去重顾客、期间去重与新老客，真实身份及归属 | B/C；精确期间去重模型未有前不开放 |
| F20 REPORT_HEALTH | 数据完整性与任务 | 缺失门店日期、发布版本、延迟、来源缺口、失败及补算进度 | 控制表/A |

首版查询日报/周报/月报复用同一组日统计，不另外运行周汇总/月汇总任务，不保存第二套期间金额。

同一来源没有实际支持的业态，不因目录出现就开放。Keeta只保留未来渠道维度，不接SDK/webhook，也不产生假渠道营业额。

### L3.3 独立统计库与候选表

沿`backend/shop-report`的record + JdbcClient + DAO方案，所有注入显式Qualifier。统计库迁移仅在`db/report`及`rpt_flyway_history`执行，实施时从最新编号追加，不修改V1/V2。新的rpt表不进入food-core的业务迁移，不使用业务库默认transactionManager。

**推荐先扩充原候选`rpt_food_metric_daily`，配固定指标目录和受控维度，不按20张报表建20张表。** 相比每报表宽表，能够复用原方案、降低首批表数量；热点指标后续根据实际查询性能增加专用投影，保留统一口径与发布机制。当前机器设计的原候选字段不足以支撑发布版本/来源覆盖，以下为待评审扩展，不表示已修改89张表目标、已生成DDL或已建表。

| 候选表 | 粒度/职责 | 关键字段与约束 |
|---|---|---|
| rpt_scope_cursor | 每主体/门店/口径版/日期基准 | firstStatDate、lastPlannedDate、timezone/policyRef、enabled；漏跑恢复从游标续排，不能只盯昨天 |
| rpt_build_batch | 一次门店营业日构建 | buildNo、原请求键/hash、scope/date、metricVersion、status、leaseToken/fence、来源水位向量、开始结束/错误；重试查原build |
| rpt_source_manifest | 每build＋owner＋事实族 | sourceSnapshotRef/游标、asOfAt、rowCount、checksum、完整性/未知原因；原始来源不塞任意SQL |
| rpt_day_publication | 每主体/门店/日期/基准/口径版一行 | activeBuildNo、generation、publishedAt、requiredFamilies及覆盖状态；指针CAS，旧worker不能覆盖新发布 |
| rpt_dirty_day | 每scope/date/基准/口径一条重算请求 | first/lastEventRef、dirtyGeneration、原因、状态、请求人；重复事件合并，期间新事件不得被本轮完成抹掉 |
| rpt_food_metric_daily | 每build＋指标＋规范维度一行 | 指标版、metricFamily/code、dateBasis/attributionBasis、dimensionKey/白名单快照、countValue/quantityValue/amountMinor、unit/currency、completeness/unknownReason |
| rpt_export_task | 每次获准导出一行，导出阶段再建 | exportNo、scope/actor、筛选/hash、固定build清单、格式、状态、私有objectRef/校验和、过期时间；不落长期公开URL |

**共7张候选表：首批6张（不含导出），导出阶段第7张。** 既有3张零售表继续复用原用途，不计为餐饮新增。全局rpt_job_watermark用于任务概览；正确覆盖以门店/日期publication与manifest为准。

共同规则：

- 新表显式保存tenantNo（当前MAIN）、entityNo；分区元数据有storeNo，事实行也保留scope便于强约束。此库没有MyBatis数据域拦截器，DAO每条读写必须完整带scope，拒绝空主体/未授权门店。不能照搬旧DAO的全日期DELETE用于单门店餐饮构建。
- count/quantity/amount由固定valueType决定；已知值恰好一个数值列有效，未知时数值null并写原因。指标并非任意key-value，禁止客户端新增指标、SQL表达式或任意JSON过滤。
- 规范维度包含角色、菜品/SKU、套餐/配料、渠道、餐牌/版本、餐段、工位/任务角色、员工、物料及原单位等白名单子集；不同reportCode只支持声明的组合。常用筛选可以有类型化索引列，与维度快照一致性由写入校验保证。
- dimensionKey使用规范化typed维度哈希，明确NONE（不适用）与UNKNOWN（应有但缺来源），避免NULL唯一索引重复。事实唯一键为(buildNo,metricCode,dimensionKey)，维度必须包含该指标的币种/单位/归属基准等全部区分项。
- 报表库不保存支付私参、卡号、顾客手机号/地址；名称、类目、餐牌和角色只取原冻结快照。当前商品改名不重写原单显示；缺快照不能把当前主数据冒充历史。
- 数据源隔离需比较连接到的实际catalog/实例身份并检查用户权限：报表账号只写report库，源账号只读相应owner；报表迁移不能以主库账号跨库建表。启动时同一database即拒绝启用；统计表误落业务库的集成测试必须为红。

### L3.4 每日生成、校验与发布

**先补齐初始化隔离。** 当前ReportDataSourceConfig在宿主启动同步连接报表库并执行Flyway，数据库临时不可达会使整个宿主启动失败。因此不得将“现有多数据源”直接声称为“报表故障已隔离”。本方案选用同一构建产物内的报表子系统隔离初始化，暂不新建报表服务或Maven应用：

- 计划新增显式初始化模式`shop.report.initialization-mode=ISOLATED`。旧环境默认STRICT保持现有启用/启动合同；餐饮采用ISOLATED前必须完成兼容改造和双模式测试，不能仅新增配置名就宣称支持。
- ISOLATED模式只装配惰性report连接与门面；原同步Flyway不参与主业务启动必经依赖。独立worker中的J13是指定迁移执行者；API实例只校验真实schema版本，不并发跑迁移。迁移成功凭据只有实际成功后才产生，不用一个空的ReportMigrated对象冒充已就绪。
- 状态分为DISABLED/INITIALIZING/READY/UNAVAILABLE/MISCONFIGURED/MIGRATION_FAILED。未READY时所有统计DAO/查询/构建先拒绝访问，历史接口返回报表NOT_READY或503，主业务readiness保持独立；不回落到默认数据源、不返回伪空报表。
- 网络暂不可达采用有上限退避探测，恢复后校验实际database身份及所需schema再READY。同库误配或缺配置为MISCONFIGURED，拒绝启用报表、保留业务启动；迁移失败为MIGRATION_FAILED并停止自动推进，不自动repair/baseline掩盖历史问题。
- 调度/运行记录使用原独立任务设施；即使report库不可用也能记录失败。worker完全未启动的检测由独立任务管理/外部健康巡检比较最后执行时间，不能只依靠停掉的worker自己发告警。


每日调度以“门店上一营业日结束＋建议30分钟缓冲”到期为准，不以全球统一01:30为准。按门店或相同时区/切点分组配置每日任务；每个已结束营业日正常生成一次，任务失败/迟到修订可以额外重算。03:00/04:00夜宵店不在尚未结束时提前生成最终日报。

1. **计划。** 从真实获准门店及原营业日策略确定应生成范围，持久化分区请求；firstStatDate建议从报表启用日开始，历史数据显式回填。停机多天后按scopeCursor分批补齐所有欠缺日期，不因7天回看窗口而漏掉更早未生成日。
2. **领取。** 锁定原scope/day/metricVersion，生成buildNo、fence和冻结统计定义；已存在成功的同请求直接查回。任务重试不新建第二个未知构建。
3. **读取来源。** FOOD、CORE、PAY、INVENTORY分别按权威提供只读快照/聚合合同；业务只读快照与统计写事务分开。第一版以门店/日单次有界读取为主，大数据需要稳定快照分页合同；不能把多次变化中的普通分页称为同一快照。
4. **构建。** 报表库按buildNo写不可见版本。多个来源记录各自水位向量，asOfAt参数不等于跨库全局ACID。来源无法满足必需的一致性/完整性合同，则相应事实族不可标COMPLETE。
5. **校验。** 检查原量/金额守恒、父叶不重记、同币种、单位精度、去重、scope、来源行数/hash、必需事实族与零业务证明。必需业务族失败不发布；可选尚未接通的资金/成本族可明确UNKNOWN，不能补0。
6. **发布。** 在一个短reportTransactionManager事务内校验fence，完成manifest及activeBuildNo CAS切换；读端旧版→完整新版。仅增加新日/新版本，不先删除读者正在读取的旧行。
7. **完成与查回。** 统计发布后再确认原任务结果；若发布响应丢失，按原buildNo查publication后确认，不换键重算。任务平台runId与buildNo双向关联。

**首版只发布完整日版本。** 每次主批、回看、标脏重算、人工补算都重新构建该metricVersion已启用的全部事实族，所有报表共享一个activeBuildNo。不实现按指标族替换；补算请求只接受scope/date/version，不接受局部family筛选。未接通的规划族在目录/manifest中明确UNKNOWN；已经启用且列为必需的族临时失联则本次构建失败，继续读上个完整版本。后续若需要局部补算，必须另设计不可变bundle及族水位，不能直接把局部build挂到整日指针。

处理状态分开：构建QUEUED/RUNNING/VALIDATING/PUBLISHED/FAILED/CANCELLED；数据覆盖COMPLETE/PARTIAL/UNKNOWN/EMPTY_VERIFIED。PUBLISHED不代表所有业务能力齐全，不代表支付成功或日结已签核。必需事实族列表随metricVersion冻结；query明确报告可选未知字段与缺失分区。

读端在同一个只读report事务中读取所有分区指针与事实，跨日查询可以固定本次选中的build集合；不同HTTP分页请求如需同一版本，携带服务端签发querySnapshotRef，绑定授权范围及build集合、设置期限。没有此token仅承诺单次请求一致，不承诺跨页同水位。

### L3.5 定时任务与事件清单（历史功能拆分，执行收敛见§17）

以下为餐饮新增/复用计划，不表示当前已注册。初始频率是建议配置；JobDeclaration默认只首次入库生效，后续运营调整不得被发版覆盖。统一任务平台负责触发、停启、人工重跑和日志，行业handler负责编排；event consumer不是额外cron。

| ID / handler | 类型/建议频率 | 具体工作 | 幂等/失败边界 |
|---|---|---|---|
| J00 report-daily-rollup | 已有零售任务01:30 | 原自然日门店/商品统计与原回看3天 | 保留原行为，不拿来直接生成餐饮日报 |
| J01 food-report-daily | 每店每日一次主批 | 已过日切＋缓冲后，计划并调用J02构建，按游标恢复漏跑 | scope/date/version唯一；全局扫描分批，失败不推进未计划部分游标 |
| J02 food-report-build | 主批/补算内部执行，无独立cron | 仅领取营业日结束＋缓冲已到期的请求，按原build全日读取、聚合、校验及发布 | 分区锁＋fence；有界批数、租约超时、原号查回 |
| J03 food-report-dirty | 事件驱动 | 接单、改单/取消、厨房动作、退餐、更正等只标记受影响日期 | 原outbox事件去重；仅置脏，不增量加金额/数量 |
| J04 food-report-lookback | 每店每日主批后 | 检查最近7个已结束营业日，按缺口/dirty必要补算，不默认全量重算 | 不负责代替全历史补算；每个分区仍走J02同一路径 |
| J05 food-report-catchup | 失败退避/下一每日主批/人工补算 | 检查逾期、异常构建及租约；查询是否已发布，重试/告警 | UNKNOWN先查原build，重试退避；不得把尚在执行的构建当已失败 |
| J06 food-report-quality | 每日发布后＋人工 | 同scope/date/version的行数、来源hash、量额守恒、缺失日期与控制总数校验 | 只生成差异/修复请求，不改业务原单或签核 |
| J07 food-report-backfill | 人工异步 | 指定门店、日期、口径版本，整日补历史或修复 | 原请求key/hash，分日分批；大范围暂停/续跑，不能一次长事务 |
| J08 food-report-version-rebuild | 发布新口径时人工启动 | 新metricVersion完整重建、对比，验证后切默认读版本 | 旧版保留；切换前不混用两版指标 |
| J09 food-report-export | 异步请求＋每分钟补扫 | 查询固定build集合，生成CSV/XLSX，写私有对象存储 | 同原导出号可查回；失败不暴露半文件，下载时重新鉴权 |
| J10 food-report-export-expire | 每日/建议低峰 | 清理已到期临时导出文件及授权；保留必要审计 | 仅删除已确认过期对象，不删业务凭证 |
| J11 food-report-version-cleanup | 每日/建议低峰 | 清理确认过期且无签核/导出/查询快照引用的派生旧版本 | 当前activeBuild不可删；保留周期按配置和记录类别执行 |
| J12 food-report-health | 每5分钟 | 统计未生成日/延迟、连续失败、来源不可用、队列积压、worker未执行 | 只通知可行动异常，避免持续无变化刷告警 |
| J13 report-storage-init | worker启动＋每分钟失败补探 | 指定执行者迁移独立report库，校验身份/schema并发布真实READY；API只校验不迁移 | 暂不可达退避；同库/配置错误拒绝启用；迁移失败暂停，不伪造就绪或自动repair |

J03收到当前营业日事件时可以合并dirty记录，但readyAfter必须为该日结束＋缓冲；J02在到期前不能生成这一天的日统计。未来预点餐日期也不能被提前发布。已结束日的修订按重算队列处理；首版因此没有隐含的“当前日每分钟统计落表”。

J03事件覆盖规则：交易变更标记原订单归属日；退款/付款标记真实发生日与必要的原单归属分析日；厨房动作按事件发生日，若影响原单队列视图再标记原单日；日结更正关联原日与当前日。消费者先持久化dirtyGeneration再ACK；J02只确认自己领取的generation，期间新事件仍保持待处理。

现有FOOD事件中一部分是archive-only。实施J03时必须同步真实producer路由与宿主handler声明，移除对应archive-only冲突并兼容报表开关；不能仅增加消费者名称就假定会收到历史事件。历史已归档事件由回填/回看补齐，不假定会自动重投。

**任务粒度：** 总任务可有多个日期分片，但每个分片固定主体/门店/日期/指标版，跨店失败独立。单分片超时/锁时长按原JobDeclaration约束设置，lock>=timeout且比例不超过4，另有报告分区fence防旧worker发布；超时是结果未知，不是确定失败。重试次数/退避、每轮最大分片、并发和来源限流均配置化，未成功不得推进成功覆盖。

### L3.6 API、模块与代码归属（候选，尚未注册）

| 能力 | API/合同建议 | 数据侧 |
|---|---|---|
| 实时概览 | 复用现有桌台/厨房/批次读取；候选GET /biz/x/food/reports/live | 业务owner，强scope、分页/限流 |
| 历史报表 | 保留原目标FOOD-API-077 GET /biz/x/food/reports/{reportCode} | 只读report库，受控reportCode/维度/metricVersion |
| 导出/查结果 | 原目标078/079，POST /biz/x/food/report-exports及GET /{exportNo} | 固定build集合、私有导出、短时下载 |
| 补算/升级 | 原目标080 POST /ops/x/food/report-rebuilds；商家可申请本范围补算，平台支持需明确授权 | 原任务系统异步执行，不在请求内扫全库 |
| 日结业务读取 | 原business-days/closes及exceptions读取 | 原业务库，只读签核权威，不当历史分析表 |

查询参数至少包含：reportCode、from/to（左闭右开）、storeNos（获准子集）、timeBasis、attributionBasis、currency（金额类必需）、metricVersion；可选门店允许的SKU/工位/餐牌/渠道/餐段、page/size或签发的querySnapshotRef。服务端不能信任客户端entityNo、tenantNo、可查询门店声明。

返回统一增加dataMode、from/to、rows/totals、currency/unit、metricVersion、generatedAt/asOfAt、sourceWatermarks、buildRefs、coverage、missingPartitions、unknownMetrics、isFinal。实时/统计值同名也必须展示来源和时间；汇总不隐藏PARTIAL。必需分区/指标有未知时，完整total为null；如提供已知部分，只能另列knownSubtotal并标注覆盖，不能用SUM忽略NULL或COALESCE把未知补成0。

- 餐饮公开Controller仍归food-web；行业事实提取在food-core，经food-api行业typed契约支持本地或food-server内部访问。
- 共享报表存储、DAO、指标定义/读取与发布在shop-report；只依赖原基础合同，不依赖food-core实体或Mapper。需要共享query/source接口时放原shop-base.spi.report，名称和归属实施前核对现有能力，不复制第二份接口。
- 跨域构建编排归app现有reportbridge，或原统一任务业务handler；嵌入/独立FOOD均调用同一typed来源语义。独立PAY/库存只能通过各自真实只读合同取数，不给job直接写其账本。
- 任务声明沿原job-api/core/store，日期使用JobInvocation.bizDate，参数白名单校验；任务不是新的一套调度器。
- 权限至少复用ORDER_VIEW及真实门店目录；资金明细叠加FINANCE，导出另登记明确动作权限及三语词条后开放。平台权限不自动取得商家明细访问权，下载时检查权限是否已撤销。

### L3.7 跨库、补算、导出及例子

**跨库读取与隔离。** 源owner给出自己的一致性快照/游标和原引用。初版不建设CDC、Kafka、Flink或跨库分布式事务。独立支付/库存未提供可核验历史事实时，仅发布已验证的非支付指标，对资金/成本标UNKNOWN。汇总任务不得持有业务写事务等待报表写入；报表库下线时业务照常运行，恢复后按请求及游标补算。

**迟到例子。** 门店日切04:00、缓冲30分钟：10月21日04:30后生成10月20日的日报。10月22日发生对10月20日原单的退款：退款发生日记10月22日，原单退款分析更新10月20日的统计新版本。10月20日已签核的日结不被覆盖；需要时展示与新统计的差异。若事件发生在10月22日02:00，发生营业日则是10月21日，按已冻结规则归属。

**跨日查询例子。** 查10月1～8日（to不含）时，共7个日分区；若其中一店10月3日未生成，返回missingPartitions与complete=false；它不能贡献一个假0。查看“近7天含今日”，历史段读库、今日段实时，标isFinal=false，清楚显示两个asOf。

**导出。** 沿原查询授权固定版本集合；CSV对以=、+、-、@等开头的外部文本做公式注入防护，XLSX将文本明确写为字符串。文件在私有存储，任务成功后才返回可下载状态；下载必须走需要登录的授权代理（可带短时ticket），每次发起下载都复验actor、scope、任务状态和过期时间，撤权后拒绝新下载。不直接发放可绕过实时撤权检查的对象存储签名地址。导出数据含查询条件、时区/日期基准、币种、指标版、生成时间、水位与部分数据声明。

### L4 验收矩阵、实施顺序与边界

| AC | 必须验收的结果 |
|---|---|
| A01 数据库分离 | report URL误配为业务库时拒绝报表子系统启用；实际建表只出现在report库，业务库无新增rpt表；ISOLATED模式不牵连业务启动 |
| A02 每日完整覆盖 | 多时区/夜宵/夏令时按营业日结束触发；跨多天停机后所有缺口能分片补齐 |
| A03 真实查询路由 | 当前营业日只读业务事实；历史查询只连统计库；库挂掉/日期缺失不回扫主库、不填0 |
| A04 发布原子性 | 构建半途失败不改变activeBuild；发布后响应丢失按原号查回；同分区并发只能有合法新代次生效 |
| A05 来源与口径 | 子单/批次/check不混数，套餐父叶不重记，赠送/退餐/损耗分开，单位币种不混，未知不变0 |
| A06 回看/dirty/重跑 | 重复/乱序事件不重复累计，构建期间新dirty不丢；超过7日变化按原日期补算；已签核版本不覆盖 |
| A07 跨库真实合同 | 源失联/无历史成本/无资金回执产生可见UNKNOWN或失败；不以Mock成功证明资金或原料完整性 |
| A08 多店权限 | 同主体不同门店、跨主体/tenant及撤权下载均正确隔离；DAO无拦截器也不能漏scope |
| A09 聚合数学 | 周/月金额数量求和、平均值用分子分母、零分母null、去重不求和；不以不完整两段生成假同比环比 |
| A10 兼容性 | 零售自然日/CNY旧切片与原rpt表不被静默改语义；STRICT/ISOLATED分别测试，独立/嵌入FOOD同合同，原任务不双触发 |
| A11 导出/保留 | 固定查询build、私有文件、文本防注入、过期/撤权拒绝；active及仍被引用版本不清理 |

实施顺序：

1. **S0 合同冻结**：指标目录、报表A/B/C门禁、日期/币种/单位、真实来源、source snapshot及授权合同。
2. **S1 独立库与发布控制**：先实现ISOLATED初始化/J13及双模式兼容，再完成首批6表、数据源硬校验、原生数据库迁移/空日证明/版本指针及读取一致性。
3. **S2 每日主流程**：J01/J02/J05/J12，先开放F04/F05/F06/F07/F08/F09/F10/F20中实际有源字段；没有支持的维度明确未知。
4. **S3 补偿与质量**：J03/J04/J06/J07/J08，接真实路由、dirty generation、回看及历史重建。首批生产开放前S3必做，不把补偿作为可以永久不做的后续。
5. **S4 查询及导出**：077历史查询、今日/历史混合标识、J09/J10/J11与第7表、原单钻取；当前原单发生变化时明确CURRENT_DETAILS，只有来源能还原build快照才称AS_OF_BUILD。
6. **S5 行业与资金深度**：真实套餐/小数/桌台/员工归属、材料成本及F15～F19按owner可核验能力逐项开放；当前支付开发仍摘除，不自动启动资金扣减或退款。

方案不承诺：统计跑批就是商家日结签核；一次来源查询参数asOf就形成全系统原子快照；所有日指标都能直接SUM；所有派生/审计数据都能随意删除。统计旧版本可按策略清理，原交易、签核和审计由各自owner保留，不能因“可重算”随意删除来源证据。

默认建议参数（尚非上线配置）：日报每门店每日一次，日切后缓冲30分钟，计划与构建同一主批执行；回看7个已结束营业日仅检查缺口/变更并必要补算，健康巡检5分钟。历史启算日期、并发/限流、源查询上限、导出和版本保留周期在实施配置中显式设置；不硬编码全球统一时区、币种或税率。

设计可实施性审阅（2026-10-24，只读）：发现“现有同步Flyway启动耦合”与“局部族补算/整日单指针冲突”两项重要问题，现已明确选择ISOLATED惰性初始化＋指定迁移执行者、首版整日全量重算。两项均是待实施合同，不声称源码已支持。具体连接池参数、任务并发和保留时间由实施配置决定，不能改变以上数据与发布语义。


## §10 S1-A 隔离初始化实施记录（2026-10-25，代码验证完成）

### §0 需求与设计

承接§9 L3.4及J13：统计库故障不得阻断餐饮宿主；默认STRICT保留零售启动合同；ISOLATED由任务重试初始化，API角色首次报表访问也可按需验证，指定MIGRATOR角色才迁移。未核验成功不得读取或写入派生数据。此切片不启用餐饮统计，不创建候选表，不改变现有零售指标或资金状态。

### §1 实现边界

`shop.report.initialization-mode=STRICT|ISOLATED`（默认STRICT）；`shop.report.initialization-role=VALIDATOR|MIGRATOR`（默认VALIDATOR，仅影响ISOLATED）。STRICT仍在启动迁移；历史flyway-enabled=false保留原禁用行为。ISOLATED禁用Flyway时仍须validate及确认没有pending迁移，不能凭配置假定READY。状态NOT_READY/INITIALIZING/READY/FAILED；失败仅保存异常类型，避免凭据出现在任务结果。API按需校验不依赖job开关或任务路由，首次报表请求承担校验耗时；未就绪时只返回脱敏连接错误，不宣称API已后台就绪。

### §2 编码清单与顺序

1. ReportStoreProperties及ReportStorageInitializer：串行初始化、严格/隔离、迁移/验证角色、真实成功后READY，失败可重试。
2. ReportDataSourceConfig：保持原bean名及无Flyway类型bean；JdbcClient与报表事务管理器使用就绪保护的数据源；全部JdbcClient注入显式Qualifier。
3. ReportStorageInitJob：沿原JobHandler/JobDeclaration/JobSupport/ShedLock注册J13，每分钟探测，遵循report/job开关，STRICT仅报告现有状态。
4. 测试源码同时补齐；统一test-compile，再集中运行report初始化/DAO和装配相关用例。按用户指示不逐任务运行；集中阶段通过消融确认关键保护。

后续S1-B：实际数据库身份及账号权限隔离、六表与发布fence；S2/S3：营业日汇总、事件补偿和质量门禁；S4：查询导出。S1-A不得替代全部S1验收，不配置生产ISOLATED。

### §5 验收

默认STRICT兼容原护栏；隔离启动不访问数据库；未就绪获取连接失败；验证者不得迁移空库；迁移者真实迁移后可读；失败重试可恢复；真实校验失败不产生READY。集中验证结果待补，不声明已上线。


### §6 实施与验证证据

- 默认宿主及餐饮food-embedded宿主：生产和测试源码test-compile均成功，日志`/tmp/food-report-s1a-compile.log`及`/tmp/food-report-s1a-embedded-compile.log`。
- ReportStorageInitializerTest 5、原ReportDataSourceGuardTest 3、原DailyGoods/Store DAO 7，共15项通过，无跳过。覆盖真实Spring隔离装配不连接、未就绪两种连接路径、验证者不迁移、迁移后API自行恢复、报表事务回滚。
- ReportStorageInitJobTest 2、原ReportWiringSmokeTest 3，共5项通过，无跳过；仅相关模块验证，未跑全仓回归。
- 一次独立审阅发现API多实例NOT_READY缺口，已改为VALIDATOR实例首次访问只读校验，不依赖J13被路由到该实例。移除按需校验时对应测试以REPORT_STORAGE_NOT_READY失败，恢复源码后15项重新通过；消融日志`/tmp/food-report-s1a-ablation.log`。
- 默认STRICT、原bean名和非Flyway类型句柄保留；句柄存在不再解释为已迁移，必须检查initializer状态。所有报表DAO使用显式JdbcClient限定，事务与JdbcClient共用同一保护数据源。
- 与原L3.4差异：API采用首次访问按需验证，首次报表请求承担连接校验耗时；J13由分钟任务或任务平台触发，尚未实现worker启动即触发。S1-B须补实际catalog/权限隔离及生产级迁移演练，因此本次不在生产开启ISOLATED，不宣称全部S1或餐饮报表完成。未修改V1/V2迁移、业务库或支付服务。


### §7 云端发布（2026-10-25）

用户授权推送并部署后，统一分支正常推送；补齐三个新增枚举的原字典生成物，未跳过生成物/契约守卫。沿用SKIP_TEST_GATE避免重复全仓后端测试，干净提交编译通过，既有专项20项证据保持不变。

- 运行提交：`9b2b527984bcdf58270e13934f0c956ecd6b74cd`。其后`7fe5f34cc`仅更新字典生成物，与运行提交无backend/backend-plugins差异。
- 包：`/data/app/ai-shop/shop-app/shop-app-20261025-0944-9b2b52798.jar`，food-api/core/web集成，包含ReportStorageInitializer与ReportStorageInitJob。
- 健康UP，实际运行SHA一致；CORE581、FOOD25、inventory14；FOOD111业务表加历史表共112，无失败迁移。餐饮模式validate。
- 发布前业务库、独立库存库及配置已备份：`/data/app/ai-shop/backups/food-report-20261025T014118Z-9b2b52798`。配置文件SHA256与发布前一致；未启用ISOLATED或新统计任务。
- PAY仍为PID1129197，进程启动指纹相同，受保护接口401；未部署或重启支付。旧主应用cd1b6d4da包保留可回退。
- 发布日志`/tmp/food-report-deploy.log`，最终核验`/tmp/food-report-cloud-final-verify.log`。当前仅初始化能力随集成包交付，餐饮历史统计、六表和日报汇总仍待后续阶段，不宣称报表业务已可用。


## §11 S1-B 任务清单与方案 Review（2026-10-25，待实施）

### L1 结论与边界

**Review结论：原方向可沿用，但须先补齐下列控制合同；当前不能直接启用餐饮统计。** 下一阶段是S1-B“独立统计库及整日版本发布”，不是立即开放20张报表。S1-A运行基线为`9b2b52798`，发布记录位于`c843decd3`；本节仅整理方案和待办，没有新增Java、DDL、公开API或生产配置。

沿用：独立report数据库、shop-report的record/JdbcClient/DAO、原统一任务平台、整日重算与单次原子发布。采用固定指标白名单，不引入通用SQL/JSON表达式引擎、CDC、新应用或新调度器。资金、Keeta、离线和前端实机仍按原边界后置。

**本批交付目标：** 得到可证明隔离、可重试、不会覆盖在读版本的统计持久层。真实营业事实提取及J01～J12编排属于后续S2/S3；S1-B的合成测试数据不代表真实日报已产出。生产统计开放须同时满足S2/S3及对应查询入口的验收。

### L2.1 本次 Review 发现与处理

| 编号/级别 | 现状与风险 | 本次收口决定/落点 |
|---|---|---|
| R01 重要 | ReportDataSourceConfig.mustBeOwnDataSource仅检查rpt-pool名称；相同名称仍可连接业务库。initializer在真实身份校验前可能migrate | 在任何迁移DDL前校验实际实例和catalog、期望库名及禁入的业务库；别名/代理不能靠URL字符串判定。不能证明隔离则拒绝餐饮统计，不能退回主库；S1B-02 |
| R02 重要 | ISOLATED的VALIDATOR/MIGRATOR目前是代码角色，同一组连接配置不能证明账号最小权限 | API只读、worker统计DML、迁移DDL三类数据库权限分离；ISOLATED迁移使用独立凭据，不向API注入DDL账号。平台/库存/PAY原凭据不复制给report；S1B-02 |
| R03 重要 | initializer将异常统一记FAILED，J13下一分钟可再次migrate；API失败后每次访问都可同步重试；READY后也无失效策略 | 区分临时不可达、配置/身份错误、schema未就绪及迁移失败。仅临时失败退避重试；迁移失败暂停，禁止自动repair或在已有未知schema上baseline。每实例只允许一个探测，其余请求快速返回未就绪；S1B-03 |
| R04 重要 | 原事实唯一键仅buildNo/metric/dimension，未描述同请求失败重领后的半成品与旧worker写入 | 保持逻辑buildNo，增加attemptNo；事实、manifest、发布指针均绑定attemptNo。分区fence递增，所有写入/发布同时核验attempt和fence；S1B-04～06 |
| R05 重要 | 仅有成功指针CAS不足以证明读取一致；默认事务隔离配置可能改变 | 发布在单个report短事务完成；读端显式只读REPEATABLE_READ事务一次选定指针及事实。跨HTTP分页快照留S4，不先承诺；S1B-06 |
| R06 重要 | rpt_job_watermark是全局任务概览；有日期水位不代表每店每日真实完整 | 门店/日publication＋required manifest才证明覆盖。没有扫描证明不能发布空日；缺分区/未知不得补0；S1B-01/06 |
| R07 重要 | 原机器设计只规划1张rpt投影；FOOD V1已经有fnb_metric_definition/fnb_report_run物理表，但无现成报表Service/Mapper实现 | 新版六表须先对齐原设计真源与生成清单。历史fnb表保留，不作为第二套build/发布水位，也不双写；指标定义用固定版本目录，暂不开放历史定义表为可执行规则；S1B-01/04 |
| R08 重要 | 如果以当前门店策略重算旧日报，修改日切会改变历史归属；UTC边界也不能按固定24小时推算 | 复用原营业日证据及原策略版本，冻结UTC起止。BusinessCloseServiceImpl.boundaries已按当地日期分别换算，保留23/25小时日；历史边界不可证明时阻塞该分区；S1B-01 |
| R09 中等 | 所有新表/新配置若直接挂report.enabled，会随旧零售启用而自动开放餐饮跑批 | 餐饮构建/调度另设默认关闭的子开关；旧STRICT和零售DAO语义保留。Flyway仍由真实迁移角色执行，新统计构建权限与schema就绪分开；S1B-03/08 |
| R10 重要 | V3与旧V1/V2共用迁移目录；新API包先于迁移上线会因pending拒绝读取；STRICT也不能绕过新DDL身份校验 | 先预检并准备迁移凭据/配置，再由指定角色升级，最后发布API。新增DDL在STRICT/ISOLATED均须身份校验；保留旧指标语义不代表旧配置零改动升级。原生验证旧读SQL与升级时序；S1B-02/04/08 |

以上是源码及方案审阅发现，不宣称已通过并发/原生数据库实验，也不把“重要问题”描述为当前生产业务已故障。

### L2.2 前置合同：先完成最小S0，再写迁移

1. **范围键PartitionKey**：`tenantNo/entityNo/storeNo/businessDate/dateBasis/metricVersion`，来自原主体/门店授权及真实营业日。当前tenant为MAIN也必须显式携带。dateBasis首批固定BUSINESS_DATE，不在请求里接受任意时区或日切覆盖。
2. **营业日证据**：优先读取原FnbBusinessDay的dayNo、policyNo、startsAt/endsAt；当前实现边界是UTC LocalDateTime，转换时明确UTC，禁止二次套门店时区。无原日记录时，由FOOD owner基于可证明的历史策略提供边界，不由report库读取“当前策略”猜旧日。冻结timezone、cutoverMinute、policyNo/version和UTC边界，存于build证据快照。
3. **固定指标目录**：每个metricCode声明owner/family、valueType、单位/币种要求、允许维度、归属基准、聚合方式、去重规则与前置能力；目录版本＋内容hash不可变。S1-B冻结字段及校验机制，S2接通实际来源后才能发布相应指标，不能把requiredFamilies设为空绕过来源检查。
4. **来源合同**：返回scope、固定查询边界、sourceSnapshotRef、sourceVersion或水位、rowCount/checksum、completeness及unknownReason。单次有界完整读取优先；不能形成稳定快照的普通分页拒绝标COMPLETE。asOfAt只表示来源自己的截至，不代表跨库原子快照。
5. **空日证明**：已启用必需事实族全部完成扫描，manifest记录真实0行，才允许EMPTY_VERIFIED；COUNT类指标可为0。没有单位/币种依据时不凭空生成EA/CNY的数量或金额0。可选资金/成本族保持UNKNOWN。
6. **授权**：公开入口未来从既有账号/主体/门店范围构造scope；内部worker用已验证的门店任务上下文。DAO仍必须逐条带scope；不能只验证buildNo就跨店查询。S1-B不新增C/B/Ops公开端点或权限码。

### L3.1 六表字段与约束细化（候选，未执行）

既有零售三表继续原用途；首批新增6表，导出阶段再新增rpt_export_task。迁移位于`backend/shop-report/src/main/resources/db/report/`，当前最新V2，候选V3编号在编码前重新检查；不改V1/V2，不向经营库fnb迁移目录追加统计表。

下面“scope”均为完整tenant/entity/store；“partition”另含营业日、dateBasis、metricVersion。标识符沿原owner长度及校验，不另造商家身份。

| 表 | 必需字段/唯一约束 | 职责与禁止事项 |
|---|---|---|
| rpt_scope_cursor | scope/dateBasis/metricVersion唯一；firstStatDate、lastPlannedDate、enabled、version | lastPlannedDate只代表任务已持久规划，不代表已成功生成；全范围完整性从publication逐日判断 |
| rpt_build_batch | buildNo唯一；scope/requestKey唯一、requestHash；partition、attemptNo、fence、leaseOwner、leaseUntil、status、specHash、requiredFamilies快照、营业日证据、dirtyGeneration、错误分类/时间 | 同key不同hash冲突；已PUBLISHED重放查原结果；未发布失败重领attempt递增。不能因重试覆盖已发布数据 |
| rpt_source_manifest | scope/buildNo/attemptNo/owner/family唯一；snapshotRef/sourceVersion、asOfUtc、rowCount、checksum、completeness/unknownReason | 每次attempt证据独立，来源缺失不得冒充EMPTY_VERIFIED；必需族缺一即拒绝发布 |
| rpt_day_publication | partition唯一；activeBuildNo/activeAttemptNo、generation、publishedAt；currentFence、leaseOwner/Until | 可先存在无active指针的分区锁行，此时仍是MISSING。领取时fence递增，发布时切换active指针，generation只在成功发布递增 |
| rpt_dirty_day | partition唯一；dirtyGeneration、ackedGeneration、first/lastEventRef、reason、readyAfter、version | ack仅推进到本次捕获的generation，新事件仍保留；待处理=dirtyGeneration>ackedGeneration，不依赖易被覆盖的单一DONE状态 |
| rpt_food_metric_daily | scope/buildNo/attemptNo/metricCode/dimensionKey唯一；metricVersion、family、attributionBasis、typed维度快照、valueType、countValue/quantityValue/amountMinor、unit/currency、completeness/unknownReason | dimensionKey=规范typed维度SHA256；包含单位/币种/归属等区分项。事实不可覆盖已发布attempt，不把套餐父叶合并重复计量 |

已知值按valueType恰好一个数值列非空；未知值三列均空且有原因。COUNT/MONEY使用BIGINT并检验溢出，QUANTITY沿已交付精确数量使用DECIMAL(24,6)，拒绝截断/隐式四舍五入；允许负值与否由固定指标定义决定，不给所有指标统一套非负约束。币种与单位按指标要求验证，不做跨币种/单位SUM。

schema索引需覆盖partition查询、待领取/lease到期、scope/requestKey重放、build/attempt事实读取；联合索引长度在目标MySQL验证，不用H2成功替代。写入时校验完整scope及partition与build一致，禁止只凭外来buildNo定位。历史未发布attempt可延后清理，不在本批删除已发布事实。

**对原§9的明确修订：** 原唯一键`(buildNo,metricCode,dimensionKey)`扩充attemptNo；activeBuildNo对应增加activeAttemptNo。这样仍只有六张表，不另建一套重试引擎。文档中旧键是早期候选，实施以本节为准，迁移前同步机器真源。

### L3.2 数据库身份与账号隔离

- 先确认真实引擎/版本，再读取其可验证的服务器实例身份与`DATABASE()`/catalog；MySQL可采用server_uuid。host/port、连接池名、URL路径和单独的server_id都不能独立证明物理身份。别名或代理无法可靠解析时拒绝餐饮统计启用，不用字符串比较兜底。
- 以批准的期望report catalog及实例身份做正向匹配，并与业务/库存/PAY已知数据库身份比对；report实例不必独立于业务实例，但catalog必须隔离。发现平台/FNB/库存/PAY迁移历史等禁入业务结构也拒绝迁移。拒绝时无任何report建表/写入。
- 账号分为API只读、统计worker读写report、独立migration账号DDL。有效权限审计包括全局/通配符/角色继承，不能只看直接schema grants。业务来源仍经原typed owner查询，不把业务库账号授予report worker。
- 账号创建/授权由既有部署流程显式执行，应用不自动GRANT。身份与权限校验不能证明时不产生READY；迁移凭据与口令不写日志/文档，不让API拥有migration连接池。
- 建库及账号资源先准备，应用只迁移已批准的空report库或已证明属于report的V1/V2基线。新ISOLATED路径禁用自动baseline；已有零售STRICT的历史合同保留，不借本批静默变更。

具体新配置键在S1B-02设计对账时登记到原ReportStoreProperties：期望catalog/实例、独立migration datasource、探测退避/连接超时、餐饮构建子开关。均为候选，当前源码及生产配置尚未增加。

### L3.3 构建、重试与发布算法

1. **请求冻结**：校验授权scope、原营业日边界和specHash；相同scope/requestKey先比hash，已发布直接查回。新的整日重算使用新的请求key/dirty代次；恢复同一请求不更换buildNo。
2. **领取**：按partition锁行，再锁build行，统一锁顺序。使用数据库UTC时间判断lease；领取递增partition fence及本次attemptNo，冻结本轮dirtyGeneration。旧worker不能延续原fence写入。同一逻辑请求保留最初捕获的dirtyGeneration，新事件留待下一请求。失败后的新attempt可重读来源但证据隔离；已PUBLISHED请求只查回，不刷新。
3. **提取与暂存**：不持有业务事务等待report写入；先从各owner获取有界一致证据，再按当前attempt写暂存事实和manifest。每次暂存写事务必须验证partition/build/attempt/fence和lease，不能只在领取时检查一次。首版超过单日上限明确失败，不静默截断或用普通分页拼快照。
4. **校验**：完整scope、目录hash、必需manifest、空日证明、维度唯一、单位/币种、量额守恒、未来/未结束营业日门禁。可选UNKNOWN不补0；必需族失败则保持旧active。
5. **发布**：一个短`reportTransactionManager`事务内重新锁partition→build，验证最新attempt/fence、lease、校验结果与预期generation；切activeBuild/attempt并generation+1，标build PUBLISHED，仅确认捕获的dirtyGeneration。任一步异常全部回滚，不先删除旧事实。
6. **查回**：响应丢失使用scope/requestKey或buildNo读取已发布结果；禁止换key重复计算后猜哪次生效。lease过期的旧响应不能把成功版本改成失败。
7. **读取**：同一只读REPEATABLE_READ report事务选定partition指针集合，再按其build/attempt读取事实。MISSING不等于0；单次请求一致。跨HTTP分页固定快照及授权token在S4实现。

重复事件可能造成一次额外的幂等整日重算；不宣称exactly-once事件消费。结果不会增量叠加，同分区事件以dirtyGeneration保留，先持久化dirty再ACK；跨库ACK响应丢失可安全重放。S3再接真实producer及现有outbox投递合同。

### L3.4 代码归属及内部方法清单（均待新增）

| 位置 | 类/方法建议 | 输出与约束 |
|---|---|---|
| shop-report/config | ReportDatabaseIdentityGuard、ReportDatabaseAccessVerifier；扩展原ReportStorageInitializer/Properties | 校验真实库/权限；错误分类、退避及单探测；不依赖food实体 |
| shop-report/dto | ReportPartitionKey、ReportBuildRequest、ReportBuildLease、ReportPublicationRef、ReportSourceManifestRow及其余五表Row | record不可变，完整scope；build lease含attempt/fence/expiry；名字实施前再核对全仓 |
| shop-report/dao | ReportScopeCursorDao、ReportBuildBatchDao、ReportSourceManifestDao、ReportDayPublicationDao、ReportDirtyDayDao、ReportFoodMetricDailyDao | 显式reportJdbcClient，每个方法带scope；锁顺序partition→build，SQL留DAO |
| shop-report/service及service/impl | ReportBuildService/Impl：beginBuild(request)→lease；stage(lease,snapshot)→receipt；publish(lease)→publication；fail(lease,error)；findResult(scope,key) | 只管理派生数据及发布；publish内部读取当前attempt事实/manifest并校验，不接受调用方自报成功；不在Service里直接拼SQL、不读取food Mapper |
| 同上 | ReportPublicationService/Impl：readDay(partition)→publishedDay；findRange(scope,range,version)→固定本次build集合及coverage | 只读一致事务，缺分区显式MISSING；不回扫业务库 |
| shop-app/reportbridge | 原ReportStorageInitJob适配classified retry；后续构建编排仍归reportbridge | 原job-api/JobSupport；不新增调度器；来源owner调用在后续S2 |
| food-api/core/server/web | S1-B无新增公开Controller；S2按原typed合同增加行业快照能力 | 独立/集成语义相同，Controller仍归food，来源适配不偷渡到report DAO |

若需要跨域ReportSourceSnapshotPort，归既有shop-base.spi下的report能力包；当前尚未发现现成report SPI，编码前做唯一归属检索与依赖对账，不预先制造空接口。

### §2 下一批执行清单

以下全部未开始。顺序有依赖，不按Controller/Service/Mapper分层空转。执行时先整批编码及测试源码，再串行编译，最后集中专项验证；本轮只review方案，不执行这些步骤。

| ID | 工作及依赖 | 完成标志/交付件 |
|---|---|---|
| S1B-01 | 冻结最小S0合同与原设计真源；前置无 | partition/营业日证据/指标目录/required族/来源快照/权限及历史fnb两表归属明确；六表/attempt新键回填原schema/API/类清单，不宣称已迁移 |
| S1B-02 | 实际库及权限隔离；依赖01 | 身份校验在DDL前；API/worker/migration权限验证；候选配置登记；源账号不复制到report；含别名同库拒绝用例 |
| S1B-03 | 初始化恢复收口；依赖02 | 分类错误、退避与单探测、API校验only、迁移失败停止、schema失效后保护；STRICT/ISOLATED开关矩阵与每实例行为明确 |
| S1B-04 | 六表迁移与Row/DAO；依赖01/02 | 新空库及真实V1/V2基线升级均成功；表/列/索引/约束真实核验；旧SQL冻结；完整scope和attempt贯穿 |
| S1B-05 | 领取/重试/暂存；依赖03/04 | 同key/hash查回、冲突拒绝；lease/attempt/fence约束每次写入；旧worker延迟写入被拒绝，无半成品读出 |
| S1B-06 | 校验/原子发布/读取/dirty确认；依赖05 | 整日必需族及空日证明、事务切换、响应丢失查回、代次不倒退、只读一致性、dirty不丢；不以全局watermark冒充完整 |
| S1B-07 | 测试源码与证据准备；随01～06编码补齐 | 覆盖下表验收；原生引擎隔离夹具、故障注入、并发时序与旧零售回归，不在编码阶段执行 |
| S1B-08 | 串行编译→一次集中专项验证→交付Review；依赖01～07 | 默认宿主及food-embedded/standalone受影响形态编译；专项结果和跳过项分开；问题仅定向复测；无未处理重要项后形成可审阅提交 |

**S1-B出口：** 持久层与控制流程可验证，不启用真实营业跑批。S2接真实非支付来源与J01/J02/J05/J12；S3接事件、回看、质量与补算；S4接商家历史查询和私有导出。完成对应上线门槛后再授权执行生产建库/迁移及开关启用，本节本身不执行部署。

### §5 集中验收矩阵（计划，不是已执行测试）

| AC/计划测试 | 必须验证的失败与成功场景 |
|---|---|
| A01 ReportDatabaseIsolationNativeTest | 池名正确但指向业务库、DNS别名同库、全局写权限或角色继承越权均拒绝；拒绝后业务库零新增rpt表；真正隔离report成功 |
| A02 ReportInitializationRecoveryTest | report断网不阻断业务启动；临时失败退避及并发探测；迁移错误不自动重复DDL/repair；API无job也能恢复；READY失效不漏写 |
| A03 ReportSchemaUpgradeNativeTest | 新空库、已安装V1/V2、重复启动、两个迁移实例并发；六表索引/约束、Flyway历史、旧三表与数据均核对 |
| A04 ReportBuildScopeTest | 空scope、同主体越店、跨tenant/entity、仅伪造buildNo均拒绝；worker范围不扩大；原零售DAO语义不改 |
| A05 ReportBuildLeaseNativeTest | 相同key同hash重放/异hash冲突；lease过期后旧attempt写入/发布/失败响应均不能影响新attempt；数据库时钟而非JVM时钟判断 |
| A06 ReportPublicationNativeTest | 失败不换active；publish中途异常全回滚；成功响应丢失查回；并发读只见旧整日或新整日；同日不同口径隔离 |
| A07 ReportCoverageTest | 无扫描、缺必需族、未知成本、确证空日、金额溢出、小数精度、不同币种/单位；UNKNOWN不变0，无完整total时不拼假合计 |
| A08 ReportDirtyGenerationNativeTest | 构建中加入新事件、重复/乱序、ACK丢失、lease恢复；仅确认已捕获代次，后续仍待处理 |
| A09 ReportBusinessDayEvidenceTest | 04:00夜宵日切、23/25小时日、策略修改后的历史日、UTC边界、未来/未结束日阻塞；不按当前策略重写过去 |
| A10 ReportCompatibilityWiringTest | report关闭、STRICT旧零售、ISOLATED API/worker/migrator、job关闭；默认/餐饮集成/独立宿主的实际适用组合分别记录 |

H2用于快速DAO语义，原生MySQL用于迁移/约束/锁/CAS/事务读取，不用Mock/H2结果替代原生证据。不对生产造测试订单或误配测试。验证指令在编码前基于最终真实类名写入本节，不能把以上计划类名描述为已存在。

### §6 本轮交付记录

本轮只读核对已部署代码、原需求与设计，输出R01～R10及S1B-01～08。未修改运行代码、迁移、账号、权限或云端；未运行Maven测试。后续编码以本节与原§9共同为合同；优先处理R01/R02，不能先建表再补隔离。


## §12 统计维度与指标目录（2026-10-25，方案待实施）

### L1 先明确看什么

维度回答“按什么看”，指标回答“看多少”。先固定指标口径，再限定可搭配维度；不允许把全部维度任意组合，避免同一订单被菜品/配料/员工关联重复放大。

本目录沿餐饮需求第七节和原F01～F20报表整理，不增加交易或资金功能。**A=首批非支付候选；B=需补齐历史事实/分摊或专项能力；C=资金或销售成本专项后置；L=实时状态；S=统计系统自身。** A也只表示优先接入，其真实快照提取与统计生成尚未实现，不能称为已上线指标。

首批允许读取CORE冻结订单金额及应收更正；不因支付摘除就禁用全部金额。应收不叫实收，订单金额不自动叫销售收入。原§9 F07“金额C”细分为：原单/有效应收A、完成履约商品净额B、资金收退款与毛利C。本节细化原目录，以此口径为准。

### L2.1 统计维度清单

| 维度组 | 字段/例子 | 来源与使用规则 |
|---|---|---|
| 组织与范围 Organization | tenantNo、entityNo、storeNo；品牌/区域/门店类型 | 前三项为每次查询强制隔离条件，不是可绕开的普通筛选；主体/品牌/区域沿原主数据。跨店按获准门店集合，历史品牌归属缺证据时不拿当前归属冒充 |
| 时间 Time | businessDate、周/月、weekday、hourBucket | 门店冻结IANA时区和营业日切点；日区间左闭右开。周一为报表周起点、月按营业日标签归月；ISO周用week-based-year。小时维度只在对应事实时间可核实后启用 |
| 事实归属 Attribution | ORIGINAL_ORDER、OCCURRENCE、SCHEDULED_SERVICE；实时SNAPSHOT | 原单日、动作发生日、预约服务日分开；同一指标只声明一个默认口径，另一视图另给版本/明确基准，不把几种视图加总 |
| 餐段与餐牌 Menu | 早餐/午餐/晚餐/特殊餐段、menuNo/version | 来自原单冻结餐牌/餐段；不能按当前菜单重归类。餐段是业务餐牌分类，小时是时间桶，两者不互相替代 |
| 渠道与终端 Channel | 下单渠道、外送平台、收银/服务员/H5/点餐机、线上/线下 | 分开保存业务渠道与终端类型；source/actorRealm等真实值通过固定映射形成候选枚举，未证明时UNKNOWN。Keeta仅规划，不造实际渠道记录 |
| 履约 Fulfillment | 堂食、自提、自营外送、平台外送 | 沿原订单冻结履约方式；“平台外送”只有真实接入后启用，终端H5不等于外送 |
| 付款模式 Pay mode | PREPAY先付、POSTPAY后付 | 原单冻结模式；不能由是否已付款反推模式，未接通的复杂预付场景不假装有事实 |
| 商品 Dish | goodsNo、skuNo、原分类、商品名称/规格快照 | 使用成交时商品归属及名称快照；缺历史分类标UNKNOWN，不拿当前分类改历史 |
| 成交角色 Component | 普通主项、套餐容器、套餐组成项、小料/配料、赠送/收费 | 沿冻结componentRef/父子关系；套餐容器销量、组成品制作量、小料选择量各有口径。赠送是价格/操作属性，不作为第二份同一数量重复累计 |
| 桌台与服务 Table | 桌区、桌台、就餐单check、用餐session、AA份额 | 桌台等是受控钻取维度；check/session/份额不都放入日汇总分组。移桌/合拆单保留原责任链；当前责任与历史发生责任分开 |
| 厨房 Kitchen | stationNo、任务类型、course、制作轮次 | 原任务/事件工位和阶段；补做/重做单独标识。不能用当前菜品默认工位替换历史任务工位 |
| 员工与原因 Staff | 接单员、服务员、操作员、审批员；原因分类 | 按具体动作对应角色统计，不给一笔单无规则分摊多人业绩；SYSTEM、顾客、设备不算员工绩效，身份不明标UNKNOWN |
| 预约与业态 Service | 预约来源、服务类型、宴会/自助餐/团餐、时段资源 | 使用已启用专项和原预约/方案版本；预订人数不等于实际用餐人数 |
| 物料与库存 Material | 物料/SKU、配方版本、工位、用途、批次、库存owner | 理论需求与实际领耗分列；批次为钻取维度，默认不展开全部库存批次；单位严格沿原事实 |
| 金额与数量计量 Measure | currency、quantityUnit | 必须分组或固定筛选，不跨币种/单位相加；COUNT一般无单位币种，金额用最小币种单位，数量DECIMAL(24,6) |
| 状态与完整性 Evidence | 原业务状态、原因；metricVersion、buildRef、coverage、unknownReason | 业务状态与数据质量分开。订单/退款申请状态不是资金成功。build/水位为结果元数据，通常不是商业分析维度 |
| 会员与优惠 Customer | 会员/匿名、新老客、券/活动/人工优惠类型、支付工具 | 真实身份及原权益/资金事实有证据才开；手机号、地址、卡号不入统计维度。匿名party不是唯一自然人；权益及资金维度按专项后置 |

所有名字/类目只保存必要的非敏感历史快照。订单号、行号、batchNo、checkNo、任务号、库存批次号主要用于钻取及去重，不按这些高基数字段建立全量日汇总组合。

### L2.2 时间锚点和汇总粒度

| 事实族 | 默认归日/时间锚点 | 原始去重粒度 |
|---|---|---|
| 核心订单、点餐批次、原成交数量/应收 | ORIGINAL_ORDER，原订单/批次冻结businessDate | 核心subOrderNo、FOOD batchNo、原行业source/行/componentRef分别使用，不混号 |
| 接单动作量 | OCCURRENCE，首次有效acceptedAt | batchNo＋唯一首次受理动作；与“归属于服务日的批次数”是不同指标 |
| 厨房/交付/退餐处理/领耗/员工动作 | OCCURRENCE，动作真实成功时间 | 原事件/操作业务号＋原行/任务；重复投递不再次累计 |
| 用餐完成与耗时 | OCCURRENCE，原用餐session最终完成时间 | 原session责任链去重；仅按原owner确认的完成，不用资金结清代替 |
| 预约与排队 | 预约按SCHEDULED_SERVICE；排队处理按OCCURRENCE | reservationNo、queueTicketNo及其终态动作；预约建单日另列，不混入服务日转化 |
| 收款/退款/充值 | OCCURRENCE，资金owner确认成功时间 | 原资金流水/分配业务号；原单归属分析另列 |
| 当前桌台/队列/厨房待办 | SNAPSHOT，asOf时刻 | 当前实体去重；不SUM一天中的多次快照 |

归属于原营业日的预点单不等于当日发生的接单动作。计划服务日、实际受理日、履约发生日可能不同；跨日不可强行塞入同一时间桶。

### L3.1 订单、应收与业务调整指标

本组COUNT为整数，AMOUNT为对应币种最小单位；A的来源是已有FOOD/CORE业务事实，但统计快照仍待接入。

| metricCode | 中文指标 | 定义/公式及边界 | 默认归属/阶段 |
|---|---|---|---|
| ORDER_CREATED_COUNT | 餐饮原订单数 | 有真实FOOD来源的核心门店子单去重；不含报价/购物车/失败建单，不以AA付款次数代替 | 原单/A |
| ORDER_TRANSACTED_COUNT | 原成交订单数 | 原CORE已核验成交口径的子单去重；沿原成交时间/状态，受理与完成履约均不能擅自替代成交 | 原单/B，须原成交owner证明 |
| ORDER_COMPLETED_COUNT | 已完成履约订单数 | 原CORE owner确认履约完成的餐饮子单去重；不从ACCEPTED或应收余额猜完成 | 履约完成发生日/A，须核验原完成时间 |
| ORDER_BATCH_COUNT | 点餐批次数 | 真实已受理batch去重，归原冻结营业日；同一就餐单多轮加菜是多个batch | 原单/A |
| BATCH_ACCEPTED_EVENT_COUNT | 当日接单动作数 | 首次有效acceptedAt归发生营业日的batch数；不重复计算恢复/重试 | 发生日/A |
| CHECK_OPENED_COUNT | 新开就餐单数 | 真实首次开单check去重；拆分派生台账单独识别，不作为新到店客单 | 开单发生日/B，需原始/派生关系 |
| CHECK_OPEN_COUNT | 当前未关闭就餐单数 | asOf时刻原owner确认未关闭的有效check；不能解释为未收金额 | 实时/L |
| ORDER_ORIGINAL_DUE_AMOUNT | 原订单应收 | 按核心原单唯一分配汇总原始应收，原值冻结；非充值/押金工具款 | 原单/A |
| ORDER_EFFECTIVE_DUE_AMOUNT | 当前有效应收 | 原单应收＋CORE已经生效的应收更正；只统计当前原金额的累计视图，不另与更正重复相加 | 原单/A |
| RECEIVABLE_ADJUSTMENT_AMOUNT | 当日应收更正额 | 原CORE已应用更正事件有符号金额SUM；建议/申请/审批中不计入 | 更正发生日/A |
| RETURN_REQUEST_COUNT | 当日退餐申请数 | 原退餐申请业务号去重，包含未决状态并分列；不是退款笔数 | 申请发生日/A |
| RETURN_EXECUTED_QTY | 当日已执行退餐量 | 原行已生效处理的数量，按CANCEL/LOSS/KEEP与单位分别展示；KEEP不自动减少交付量或应收 | 处理发生日/A |
| ORIGINAL_ORDER_RETURN_RATE | 原单退餐涉及率 | 原单队列中至少一次有效退餐处理的子单数÷相同队列有效子单数；先原单去重再比值 | 原单/B；分子分母不同发生日不能硬相除 |

ORDER_EFFECTIVE_DUE_AMOUNT是“截至某次build的原日订单累计有效应收”；RECEIVABLE_ADJUSTMENT_AMOUNT是当日变化流量，二者时间语义不同。后付未收金额需原收款分配/未决事实，不能直接把全部有效应收称为欠款。

### L3.2 菜品、套餐与配料指标

| metricCode | 中文指标 | 定义/公式及边界 | 默认归属/阶段 |
|---|---|---|---|
| DISH_ORDERED_QTY | 原下单菜品量 | 原有效受理行的精确物理数量SUM，按SKU/单位/角色；报价billing qty=1不能替代实际称重量 | 原单/A |
| DISH_CANCELLED_QTY | 已执行取消菜品量 | 原业务取消/改单中确已执行的取消量；不从所有退餐申请推算 | 取消发生日/A |
| DISH_DELIVERED_QTY | 实际交付菜品量 | 原行部分交付成功事件SUM，补充交付去重；补做/赠送角色保留 | 交付发生日/A |
| DISH_GIFT_QTY | 赠送菜品量 | 原已生效赠送事实数量；是下单/交付数量的可筛选子集，不能再加一次 | 原单/B，需明确赠送事实与作用行 |
| BUNDLE_ORDERED_QTY | 套餐销售份数 | 套餐容器原行份数；不将子项数量再合计为套餐份数 | 原单/A |
| BUNDLE_COMPONENT_QTY | 套餐组成品需求量 | 原冻结套餐组成叶子的实际需求数量，父层容器不重复计入 | 原单/A |
| ADDON_SELECTED_QTY | 小料/配料选择量 | 原选配叶子的精确数量，可按收费/包含/赠送分列；跨单位不合并 | 原单/A |
| ADDON_CHARGE_AMOUNT | 小料原加价金额 | 原CORE唯一行/组件金额分配中的小料加价；包含项0元需真实证据，不能用现价乘历史数量 | 原单/A，依赖原金额归属可核验 |
| DISH_ORIGINAL_AMOUNT | 菜品原单分配金额 | 按原CORE行分配金额，套餐父/叶选择同一金额归属层，不重复累计 | 原单/A |
| GOODS_TRANSACTED_NET_AMOUNT | 成交商品税前净额 | 原CORE同一成交队列的商品优惠后税前金额，排除税及非商品费用，不以收款工具抵用再减一次 | 原单/B，须最终行分配 |
| GOODS_FULFILLED_NET_AMOUNT | 完成履约商品净额 | 原CORE确认的完成履约商品税前净额分配，排除非商品款，实际退餐/更正按原分配处理；不是实收或财务收入确认 | 原单/B，未核实最终商品/费用分配不开放 |
| DISH_ORDER_SHARE | 菜品订单覆盖率 | 含该菜品的原子单去重数÷同队列子单去重数；一单多菜可令各菜占比之和超过100% | 原单/B；不能用菜品件数当订单数 |

菜品分类用于排行时必须先固定数量或金额口径。套餐销售与组成品制作是两张视图；只在固定角色层级内求和。销量默认展示“原下单量、取消量、实际交付量”三列，不用简单相减猜业务净销量，后续由原行有效数量合同支持再增加净量指标。

### L3.3 厨房、出品与服务时效指标

| metricCode | 中文指标 | 定义/公式及边界 | 默认归属/阶段 |
|---|---|---|---|
| KITCHEN_PENDING_COUNT | 当前待制作任务数 | 当前有效待制任务去重；与菜品份数分开 | 实时/L |
| KITCHEN_STARTED_QTY | 当日开做量 | 原制作开始事件精确数量，按单位/工位；原单昨日下单也可计入今日实际开做 | 发生日/A |
| KITCHEN_READY_QTY | 当日出品量 | 原出品成功事件数量；部分出品分次累计，重复事件去重 | 发生日/A |
| KITCHEN_HANDED_OVER_QTY | 当日交接量 | 原厨房交接/上菜成功量；厨房交接与顾客签收分别命名，不互相代替 | 发生日/A |
| KITCHEN_REWORK_QTY | 当日重做量 | 原有效重做操作数量，区分重做/补做原因；不记为新增销售量 | 发生日/A |
| KITCHEN_LOSS_QTY | 当日报损量 | 原制作/成品实际报损事件数量，按阶段/单位；不与退餐申请数量直接相加 | 发生日/A |
| KITCHEN_WAIT_DURATION_SUM / KITCHEN_WAIT_DURATION_COUNT | 排队耗时总和/样本数 | 原任务有效受理→首次开做，保存秒数SUM及可核实样本数；hold暂停另列 | 开做完成该区间的发生日/B |
| KITCHEN_PREPARE_DURATION_SUM / KITCHEN_PREPARE_DURATION_COUNT | 制作耗时总和/样本数 | 原任务有效开做→对应有效出品；部分制作须有原执行片段配对，不猜摊分 | 出品发生日/B |
| KITCHEN_PASS_DURATION_SUM / KITCHEN_PASS_DURATION_COUNT | 出品待交接耗时/样本数 | 对应出品→对应交接；缺事件配对不计样本并标覆盖不足 | 交接发生日/B |
| KITCHEN_OVERTIME_COUNT / KITCHEN_ELIGIBLE_COUNT | 超时任务数/可判任务数 | 使用任务执行时冻结SLA和暂停规则，超时数÷可核实SLA任务数 | 任务完成发生日/B |
| KITCHEN_DURATION_P50 / KITCHEN_DURATION_P90 | 耗时分位数 | 需要原样本或可合并分布证据；不能SUM/平均每日P90，首版无分布模型不开放 | 期间查询/B，明确后续模型 |

平均耗时=SUM(秒数)/SUM(样本数)。部分出品可以形成多个执行片段，因此须区分“片段平均”和“整任务平均”，首个版本固定一种、禁止混算；暂停扣除须同时保留原始耗时和扣除量。

### L3.4 桌台、预约、排队及员工指标

| metricCode | 中文指标 | 定义/公式及边界 | 默认归属/阶段 |
|---|---|---|---|
| TABLE_AVAILABLE_COUNT / TABLE_OCCUPIED_COUNT | 当前可用/占用桌数 | asOf有效桌资源状态去重；合桌资源与占用份额不直接SUM成桌数 | 实时/L |
| DINING_SESSION_COMPLETED_COUNT | 完成接待次数 | 原owner确认终态的有效用餐session责任链去重；移桌/拼拆桌不凭空多记一次接待 | 完成发生日/B，需责任链合同 |
| DINING_CONFIRMED_GUEST_COUNT | 实际用餐人数 | 每次有效接待确认人数一次，排除取消/仅预订人数；多轮加菜不重复加人 | 完成接待归属/B |
| DINING_DURATION_SUM / DINING_DURATION_COUNT | 用餐耗时/样本数 | 原有效入座至实际用餐完成，保存秒数与样本数；不是开单至付款时长 | 完成发生日/B |
| TABLE_TURNOVER_RATE | 门店翻台率 | 完成接待次数÷当日有效营业桌数；全天开放资源集合须有历史证据 | 营业日/B；分母不可证明则null |
| SEAT_UTILIZATION_RATE | 座位利用率 | 真实占用座位分钟÷实际开放座位分钟，按可核实区间求交 | 营业日/B；不以当前座位数×24小时推算 |
| RESERVATION_DUE_COUNT | 应履约预约数 | 归指定服务日、在规定观察截止时有效应履约的预约去重；与预约创建数分开 | 计划服务日/B |
| RESERVATION_ARRIVED_COUNT / RESERVATION_NO_SHOW_COUNT | 到店/爽约预约数 | 同一应履约队列中的确认到店/按冻结宽限规则确认爽约数；取消单分列 | 计划服务日/B；未到观察截止不算最终爽约 |
| RESERVATION_ARRIVAL_RATE | 预约到店率 | 同队列确认到店数÷有效应履约数；未决预约显示未决，不伪装最终比率 | 计划服务日/B |
| QUEUE_CREATED_COUNT / QUEUE_SEATED_COUNT / QUEUE_LEFT_COUNT | 取号/入座/流失次数 | 原ticket及状态动作去重；日发生量分列，不把取号数当到店人数 | 发生日/A，流失需真实终态 |
| QUEUE_WAIT_DURATION_SUM / QUEUE_WAIT_DURATION_COUNT | 入座等位耗时/样本数 | 对已入座ticket：入座时刻−有效取号时刻；未入座等待时长另作实时值 | 入座发生日/B |
| STAFF_ACTION_COUNT | 员工有效操作数 | 按原操作员和动作类型计唯一成功业务操作；接单/催菜/传菜/退餐分列 | 发生日/A |
| STAFF_APPROVAL_COUNT | 员工审核处理数 | 原审批决策去重，按通过/拒绝分列，不把审批意见当执行结果 | 决策发生日/A |

门店完成接待次数是session去重指标。桌台层“到访次数”如涉及转台，同一session可能到访多桌，不能把桌台到访次数SUM回门店接待次数。完整历史资源/责任链尚未接通时，翻台与利用率不优先开放。

### L3.5 原料耗用、损耗与成本指标

| metricCode | 中文指标 | 定义/公式及边界 | 默认归属/阶段 |
|---|---|---|---|
| MATERIAL_SALES_THEORETICAL_QTY | 销售理论耗用 | 原单冻结排他需求树/配方的理论物料叶子量，保留配方版本；不重复展开父SKU | 原单/B |
| MATERIAL_PRODUCTION_THEORETICAL_QTY | 制作理论耗用 | 按真实已开始制作的执行量和冻结原料计划计算，含重做/补料的各自计划 | 开做发生日/B |
| MATERIAL_ACTUAL_ISSUE_QTY | 实际领耗量 | 原独立库存确认成功的生产领耗出库量，按SKU/原单位/用途；HELD/理论需求不计 | 库存成功发生日/A |
| MATERIAL_ACTUAL_REVERSAL_QTY | 实际冲销量 | 原领耗关联的已成功冲销量，独立列示，不能将冲销请求视为完成 | 冲销成功发生日/A |
| MATERIAL_NET_ISSUE_QTY | 净领耗量 | 同单位实际领耗−实际冲销；用途维度保留，已含报损的领耗不再额外加一份报损 | 库存发生日/A |
| MATERIAL_WASTE_QTY | 原料实际报损量 | 原库存用途/报损事实中的真实损耗量；制作成品报损与原料报损分别看 | 库存发生日/A |
| MATERIAL_MATCHED_VARIANCE_QTY | 同批制作耗用差异 | 已匹配同一生产执行范围的实际净领耗−制作理论量；不能把不同时间队列的两个日总量直接相减 | 对应生产队列/B |
| MATERIAL_ISSUE_COST_AMOUNT | 实际领耗历史成本 | 原库存成功领耗及冲销回执上的原历史估值；任一相关成本未知则完整合计null，另列knownSubtotal和覆盖 | 库存发生日/B，已有回执仍须逐项核验 |
| GOODS_SALES_COST_AMOUNT | 商品历史销售成本 | 库存owner可关联到实际销售履约的COGS，不用当前进价或全部领耗成本代替 | 原销售队列/C |
| GOODS_GROSS_PROFIT_AMOUNT / GOODS_GROSS_PROFIT_RATE | 商品毛利额/率 | 同队列商品净额−历史销售成本；毛利率=毛利额÷商品净额；零分母null | 原销售队列/C |

本目录把“菜品消耗”拆成成交数量、制作数量、理论原料需求和实际物料出库，四者分别可追溯；库存预占不等于消耗。材料领耗成本不自动等于销售成本，损耗和在制品不能直接混入商品毛利。

### L3.6 资金、税费、顾客与专项指标（条件开放）

| metricCode | 中文指标 | 定义/公式及边界 | 默认归属/阶段 |
|---|---|---|---|
| GOODS_DISCOUNT_AMOUNT | 商品优惠额 | 原CORE冻结并最终确认的商品优惠分配，活动/券/人工分列；赠送如已包含优惠分配不再减一次 | 原单/B，只读原金额不执行权益扣减 |
| ORDER_TAXABLE_AMOUNT / ORDER_TAX_AMOUNT | 税基/税额 | 原订单最终确认的税快照分配，按税分类/币种；不按当前税率重算，不拿预估税冒充最终税 | 原单/B，最终税证据缺失则未知 |
| SERVICE_FEE_AMOUNT | 服务及专项费用 | 原CORE确认的费用分配，服务费/茶位/包装/最低消费补差等分列，和商品金额互斥归属 | 原单/A，依赖已确认费用事实 |
| PAYMENT_SUCCESS_COUNT / PAYMENT_SUCCESS_AMOUNT | 成功结算笔数/金额 | 原PAY确认且关联订单的成功结算分配，按工具分别列；储值/积分抵用不伪装外部现金流 | 成功发生日/C |
| REFUND_SUCCESS_COUNT / REFUND_SUCCESS_AMOUNT | 成功退款笔数/金额 | 原PAY确认成功的退款/退回分配；退款申请不计为成功 | 成功发生日/C |
| EXTERNAL_NET_CASHFLOW_AMOUNT | 外部净资金流入 | 同币种确认的外部收款流入−外部退款流出；储值消费等内部抵用不记外部流入 | 资金发生日/C |
| STORED_VALUE_REDEEM_AMOUNT | 储值消费核销额 | 原储值owner成功消费核销金额，本金/赠金分列；充值、消费、撤销各自独立，不相加当收入 | 核销发生日/C |
| RECEIVABLE_OUTSTANDING_AMOUNT | 真实未收应收余额 | 期初＋新增应收＋更正−真实收款核销−批准减免/核销；需原付款分配及UNKNOWN门禁 | 截至时点/C；不能SUM每日余额 |
| SHIFT_CASH_DIFFERENCE_AMOUNT | 现金班次盘差 | 原现金owner确认实盘−原应有现金，保留班次/币种/签核版本 | 班次签核/C |
| CUSTOMER_DISTINCT_COUNT | 已识别顾客去重数 | 真实已识别buyer/member按获准范围去重；匿名另列，不生成虚拟顾客 | 原单/B；期间去重需独立事实模型 |
| CUSTOMER_NEW_COUNT / CUSTOMER_REPEAT_RATE | 新客数/复购率 | 新客数=owner可证明首次有效成交落在本期的识别顾客数；期间复购率=本期至少两笔有效成交的识别顾客数÷本期至少一笔有效成交的识别顾客数；固定经营范围/观察期 | 原单/B；不由本店本日首次出现推断新会员 |
| SPECIAL_SERVICE_COUNT / SPECIAL_SERVICE_QTY | 业态专项服务量 | 按自助餐入场、宴会确认履约、团餐交付等分别定义原有效凭证和单位，不做一个混合总量 | 计划/实际完成日/B，按专项逐项启用 |

实收、结算工具抵用、资金到账、充值、订单应收必须分别命名。订单优惠/税/费用的只读统计并不授予执行支付、扣券、扣储值或退款权限。正式日结的签核及差异读取原日结owner，统计build不得覆盖其签核版本。

### L3.7 统计健康与数据质量指标

| metricCode | 中文指标 | 定义/公式及边界 | 阶段 |
|---|---|---|---|
| REPORT_EXPECTED_PARTITION_COUNT | 应生成分区数 | 已到期且已启用的获准门店/营业日/口径分区数 | S |
| REPORT_PUBLISHED_PARTITION_COUNT | 已发布分区数 | 对应范围存在合法activeBuild/attempt且必需族覆盖通过的分区数 | S |
| REPORT_MISSING_PARTITION_COUNT | 缺失分区数 | 应生成−合法已发布；不拿全局watermark推测 | S |
| REPORT_DELAY_SECONDS | 统计延迟 | 对尚未发布分区：now−readyAfter；成功分区另列首次发布时间−readyAfter，不混成同一指标 | S，按分区可取MAX，不SUM |
| REPORT_FAILED_BUILD_COUNT | 失败构建次数 | 原build/attempt真实失败数，临时/配置/迁移/来源/校验分列 | S |
| REPORT_UNKNOWN_METRIC_COUNT | 未知指标数 | 固定指标目录与必需/可选覆盖中当前未知项数量，原因可见；不能当业务0 | S，必须声明目录及维度范围 |

### L3.8 汇总规则、限制与默认查询组合

| 指标类型 | 正确汇总方式 | 必须避免 |
|---|---|---|
| 互斥事实COUNT、同单位数量、同币种流量金额 | 在相同metricVersion/归属基准且分区互斥时SUM | 订单按多菜品展开后再加总；把原单视图和发生日视图相加 |
| 平均时长/客单/人均 | 保存并SUM原分子/分母，再相除 | 平均每天平均值；平均门店客单价 |
| 比率 | 同观察队列的分子/分母分别汇总再计算 | 直接平均每日比率；把未成熟预约当最终失败 |
| 去重人数、含菜订单数、接待责任链 | 在所请求范围重新去重或使用经验证可合并模型 | SUM每日去重人数；将各菜品含单数相加当订单总数 |
| 当前状态、库存/应收余额 | 查询截至快照，期间取最后一个可核实点 | SUM每日余额或多个实时快照 |
| P50/P90 | 可核实样本/可合并分布上计算 | 对每日P90取平均或最大冒充期间P90 |

商品客单价沿原需求默认成交队列：同一已成交核心门店订单的可核实商品税前净额÷成交订单数；不能把完成履约订单数替换为成交订单数。桌均=同一有效接待队列商品税前净额÷接待次数；人均=同一队列商品税前净额÷确认人数。三个指标单独命名，分子分母范围不一致时不计算。首批可以展示原单“平均应收”，必须明确是原单应收÷相同队列原单数，不叫实收客单价。

默认只支持如下组合，之后按查询性能和真实需求扩展，不预生成全部笛卡尔积：

- 营业总览：门店×营业日×履约方式/渠道，COUNT与金额分别选择允许维度；跨币种分行。
- 菜品/套餐/小料：门店×营业日×SKU×成交角色×单位；金额另按唯一归属层及币种，分类/餐牌作为已冻结的允许过滤。
- 餐牌餐段：门店×营业日×餐牌版本×来源渠道，不把同一原单的多餐牌关联金额重复累计。
- 厨房：门店×发生营业日×工位×动作×单位；时效按原任务/片段角色分别给分子分母。
- 桌台：门店×营业日为默认接待队列；桌区/桌台明细须能证明原责任/资源归属，首版不任意跨层SUM。
- 物料：门店×发生营业日×物料SKU×单位×用途；理论与实际按相同生产队列比较，不跨队列直接扣减。
- 员工：门店×发生营业日×操作员×动作，不和菜品/审批员任意JOIN分摊“销售业绩”。

每个指标目录还必须登记valueType、owner、sourceStatus、timeAnchor、dateBasis/attributionBasis、dedupKey、allowedDimensions、aggregation、formula、unknownReason、drillRef、metricVersion；计划编码只实现固定目录校验，禁止客户端提交自定义SQL或任意计算脚本。

### §2 首批候选与实施顺序

1. **最小必需族候选FOOD_ORDER**：ORDER_CREATED_COUNT、ORDER_BATCH_COUNT、DISH_ORDERED_QTY，以及可证明的原单/有效应收。冻结数量按SKU/角色/单位；无单日COUNT确证0，有数量但单位缺失时不得伪造0。必需清单实际按(owner,family)冻结：FOOD批次证据不能替代CORE建单/金额证据。最终required集合须以S1B-01的真实快照合同核验结果冻结，不能靠本目录直接开启。
2. **同批可选事实族**：厨房开做/出品/交接量、有效退餐处理、实际库存领耗及冲销、员工操作；各族独立来源覆盖。已声明为必需的族失联则本次构建失败，不发布半日；未接通的可选族明确UNKNOWN。
金额口径补充：ORDER_*_DUE_AMOUNT沿CORE原含税含费应收，不自行重算；本目录商品净额/客单/人均/毛利使用税前商品净额，界面必须标注。若需含税商品额应增加独立明确指标，不能在同一metricCode下切换含税口径。税基不等于全部税前商品额，不能用TAXABLE_AMOUNT代替商品净额。

3. **随后补B**：套餐选配更细归因、耗时片段、桌台/预约队列、历史资源/成本、顾客去重模型；商品净额、税费按原确认事实逐项核验，不先承诺财务口径。
4. **C继续后置**：真实收退款/储值积分、现金班次、账龄/坏账、销售成本与毛利。统计方案不启动资金执行开发。

### §5 本轮核对与剩余问题

已对照原需求第七节、§9指标原则、FnbOrderBatch.acceptedAt/businessDate/frozenRequest、FnbCheck.partySize/openedAt/closedAt、原BusinessClose应收与数量回执核对。此为指标合同整理，不是每项来源字段全部审计完成；特别是partySize不自动成为确认到店人数，closedAt不自动成为真实就餐完成时间。

仍需在S1B-01逐指标确认：acceptedAt等时间的owner语义、所有数量的排他角色、原单/调整金额是否具备唯一组件归属、历史类别/工位/员工快照、预约观察截止、桌台资源历史和跨日去重证据。缺证据的指标降为UNKNOWN或不开放，不用默认值补齐。

本轮只更新原设计与需求入口，未写业务代码、DDL、任务或API，未执行统计、Maven测试及生产变更。


## §13 日报任务、存储与TopN清单（2026-10-25，方案待实施）

### L1 整体方案

采用三层：**原业务事实→独立报表库完整日汇总→日报/趋势/期间TopN查询**。这里“完整”指所启用指标和维度粒度的全部汇总行，不是复制所有订单明细，也不是宣称全部94个规划指标均已接通。

- 日报生成：按门店已结束营业日构建，复用J01/J02及原source/manifest/fence/发布协议，不为每张报表建一个定时任务。
- 数据落库：复用原六张候选表，事实统一写rpt_food_metric_daily；指标按明确aggregationSet组织。周/月不建第二套汇总表，TopN不先建榜单表。
- 查询：先固定获准范围和active build/attempt，再聚合排序。当前营业日走实时；第一版历史TopN仅查询已结束营业日，不把今日未完成事实混成最终排行。
- 性能优化：后续确有查询瓶颈时再增加可重建的专用投影/缓存；必须固定build集合和权限范围，不能先丢弃长尾数据来“优化”。

例：昨日A销量100、B60；今日C100、B60。每天Top1只有A/C，两天销量最高却是B120。故期间TopN必须从期间完整SKU日汇总生成，不能合并每日TopN。

### L2.1 日报处理能力清单（沿用历史编号，实际任务注册见§17）

| ID / 任务 | 触发方式/建议频率 | 输入→输出 | 当前状态/边界 |
|---|---|---|---|
| J00 原零售日结 | 已有01:30 | 原零售自然日事实→原rpt_daily_store/goods/watermark | 已有，保持原语义，不替代餐饮营业日任务 |
| J13 统计库初始化 | 现有分钟任务/任务平台 | 批准的report连接与schema→实际READY或失败 | S1-A已随包交付；身份/权限、分类退避等仍待S1-B，未启用生产统计 |
| J01 日报每日主批 | 每门店每日一次 | 获准门店/启算日/原营业日边界/游标→计划并执行到期分区 | 待实现；门店日切后建议缓冲30分钟，跨天停机补齐所有未规划日期 |
| J02 日报构建发布 | 由每日主批/补算直接调用，无独立cron | 分区请求→真实来源快照→指标日事实/manifest→active publication | 待实现；唯一写入主流程，整日构建，失败保留旧版 |
| J03 变更标脏 | 原业务事件驱动 | 接单/改退餐/厨房/领耗等原事件→受影响日dirtyGeneration | 待实现；只置脏，不增量加总。重复投递可多触发一次重算，不能重复累计业务数值 |
| J04 近期回看 | 每店每日主批后 | 检查最近7个结束日缺口/dirty→必要补算请求 | 待实现；与J03互补，不替代超过窗口的历史修复 |
| J05 漏跑与恢复 | 失败退避/下一每日主批/人工补算 | 未发布请求/过期lease/未知结果→原号查回、重领或告警 | 待实现；确认已发布再判重试，不把超时直接当确定失败 |
| J06 日报质量检查 | 发布前强校验＋发布后巡检 | 来源控制数、维度/量额/覆盖→校验结果及修复请求 | 待实现；发布前门禁由J02同步执行，发布后handler审计复核，不事后补救替代发布门禁 |
| J07 指定日期补算 | 人工异步 | 授权门店/日期/版本/原因→按日分片请求 | 待实现；实际仍走J02，不写第二套计算逻辑 |
| J08 口径版本重建 | 人工发布新口径时 | 新metricVersion→全范围日重建与对比→读版本切换 | 待实现；旧版保留，未补齐前不混读 |
| J12 日报健康巡检 | 每5分钟 | 应生成/已发布分区、任务记录、dirty积压→缺口/延迟/故障 | 待实现；不能依赖停掉的worker自己报告“没运行” |
| J09 报表导出 | 请求异步＋每分钟补扫 | 固定build集合及查询参数→私有CSV/XLSX | 后续S4；日报/趋势/TopN共用导出，不为每种报表另建导出任务体系 |
| J10 导出过期清理 | 每日低峰 | 已到期任务/对象引用→清理临时文件及下载授权 | 后续S4；保留必要审计 |
| J11 统计旧版本清理 | 每日低峰 | 过期且无有效引用的旧build/attempt→派生数据清理 | 后续S4；active及签核/查询/导出引用版本不能删除 |

J02构建顺序：确认到期→claim lease/attempt/fence→冻结本次目录与营业日→读取各owner→按aggregationSet聚合全部有效分组→校验→原子发布→确认捕获的dirty代次。临时来源失联不得发布不完整的必需事实族。

幂等请求键必须区分初次日报、回看轮次、dirty代次、人工补算和版本重建；同一次调用恢复仍使用原key/hash/buildNo。不能永久以“门店＋日期”作唯一重算键，否则原日发布后所有合法更正都会被当旧结果返回。分区键保持不变，新的重算请求生成新build，失败恢复用原build的新attempt。

### L2.2 落库数据集清单：数据集不等于物理表

以下是同一事实表中的受控aggregationSet。只保存真实出现的完整分组，以及可证明的总计/零业务证据，不生成全维度笛卡尔积。不同set代表不同统计粒度，查询固定选择一个set，禁止将总计行与分组行再次相加。

| setCode | 日汇总粒度 | 存储指标与内容 | 接入顺序 |
|---|---|---|---|
| STORE_ORDER_DAY | 门店×原营业日×币种（金额项） | 原订单/批次数、原应收/有效应收等权威总计；COUNT无币种不复制到各币种 | 首批A |
| ORDER_CHANNEL_DAY | 门店×原营业日×原订单渠道×履约×付款模式×币种（按指标） | 原订单去重数及可唯一归属的原应收；订单渠道由原owner确定，不由其多条菜品推断 | 首批A，先核实原渠道 |
| DISH_COMPONENT_DAY | 门店×原营业日×SKU×组件角色×单位/币种（按指标） | 原下单量、套餐份数、组成叶子量、小料量、原唯一分配金额；数量与金额为不同metric行 | 首批A |
| MENU_LINE_DAY | 门店×原营业日×餐牌/版本×餐段×行来源渠道×SKU×组件角色×计量 | 可唯一归属的行数量/金额；菜单总额不含无法归属该菜单的整单费用。不能据此SUM出订单数 | A，缺原冻结维度时UNKNOWN |
| ORDER_ACTION_DAY | 门店×动作发生营业日×动作/结果/原因×计量 | 接单动作、更正额、退餐申请/处理、取消/赠送；次数和数量独立 | 首批A/赠送细归因B |
| DISH_FULFILLMENT_DAY | 门店×履约动作发生营业日×SKU×组件角色×动作/结果×单位 | 实际交付/取消/有效退餐数量等原行事件；用于交付量榜，不能由原单日数量替代 | 首批A，须原行关联 |
| KITCHEN_STATION_DAY | 门店×发生营业日×工位×动作×任务/片段角色×单位 | 开做/出品/交接/重做/报损量，耗时SUM及COUNT，超时分子分母 | 数量A、时效B |
| MATERIAL_USAGE_DAY | 门店×发生营业日×物料SKU×单位×用途 | 实际领耗、冲销、净领耗、物料报损；已核实历史成本独立币种行 | 数量A、成本B |
| PRODUCTION_COHORT_DAY | 门店×生产队列归属日×物料SKU×单位×配方版本 | 同一生产执行队列的理论/实际匹配差异；不能将任意两天总量直接相减 | B |
| STAFF_ACTION_DAY | 门店×发生营业日×员工×动作/结果/原因 | 唯一成功操作/审批次数；不将流水金额任意分给操作员工 | 首批A |
| DINING_SESSION_DAY | 门店×完成接待营业日；可核实桌区等明细另定 | 完成接待次数、确认人数、用餐秒数/样本、有效桌/座位时间分母 | B，需历史责任/资源 |
| RESERVATION_QUEUE_DAY | 门店×计划服务日/处理发生日×事实类型×状态 | 预约有效队列/到店/爽约、排队入座/流失、等待秒数/样本；两种归属不得合成一个分母 | 队列动作A、转化B |
| FINANCE_DAY | 门店×真实资金发生日×工具/渠道×币种 | 成功收退款、储值核销等，仅在原owner能力接通后；原单分析另set | C后置 |

菜品分类、名称、规格、餐牌等展示字段沿原事实快照。若同一SKU在同日同set存在不同历史分类，应把categoryNo加入实际分组或明确不提供分类过滤，不能随便选一个名称/分类再把金额全部归过去。展示名称可选择确定的历史版本并标明规则，不能通过当前商品改名重写历史。

**派生值存储：** 日事实保存可合并的原分子/分母；平均、占比、差异在查询时按固定公式计算。净领耗等若作为校验冗余指标落表，仍须与原领耗/冲销守恒，并禁止同原两列相加。P50/P90和精确跨日顾客去重模型尚未落地，不为其伪造可SUM数值。

### L3.1 物理存储清单

所有新增统计表只进入独立`ai_shop_report`候选库，表前缀rpt_；实际库身份/账号按S1-B先核验。数据库不是业务主库的另一组前缀。

| 表 | 保存什么 | 与日报/TopN的关系 |
|---|---|---|
| rpt_scope_cursor | 每店口径启算日、已规划游标 | 驱动每天计划及长期停机补齐 |
| rpt_build_batch | scope/requestKey/hash、分区、目录/边界、attempt、fence、状态 | 同日整份日报的构建记录，不按每张报表各建一次 |
| rpt_source_manifest | 每build/attempt/owner/family来源快照及完整性 | 证明数据来自哪里、为什么为0或未知 |
| rpt_day_publication | 每分区activeBuild/attempt、generation | 日报/趋势/TopN都只读同一组合法已发布指针 |
| rpt_dirty_day | 分区dirty与ackedGeneration、readyAfter | 控制原日修改后重算，不直接加减日金额 |
| rpt_food_metric_daily | 全量已启用日粒度指标分组，含aggregationSet/metricCode/维度与值 | 日报、周/月趋势和期间TopN的共同底座 |
| rpt_export_task | 后续导出参数、授权、固定build清单、私有对象引用 | S4新增第七表，日报/TopN复用，不保存公开永久URL |

沿§11追加字段：aggregationSet为固定目录枚举，纳入dimensionKey/hash及事实唯一性；完整scope、businessDate、metricVersion、buildNo/attemptNo、attributionBasis、currency/unit均须可校验。NONE（不适用）和UNKNOWN（缺证据）不能当成同一种分组值。

SQL常用过滤/分组字段使用白名单类型化列或明确可索引投影，例如sku_no、channel、menu_no、station_no、material_sku_no、actor_no及aggregation_set；其值须与规范维度一致。不要仅有dimensionKey哈希/任意JSON后再无界加载到Java筛选排序。具体列/索引在S1B-04结合真实查询计划冻结，当前未生成DDL。一个metricCode可以声明多个合法aggregationSet，但每次查询只能选择覆盖其筛选条件的一个set；例如按餐牌过滤菜品榜必须读取MENU_LINE_DAY，不能在DISH_COMPONENT_DAY粗粒度数据上猜分配。

候选索引优先覆盖：①scope＋营业日/metricVersion读取publication；②build/attempt＋aggregationSet/metricCode读取事实；③按实际TopN路径的SKU/工位/物料聚合；④待构建/lease与dirty领取。主体多店查询仍逐店授权，不允许空门店范围退化为全库。

### L3.2 日报与趋势查询清单

| 查询 | 日期/来源 | 默认内容与计算 |
|---|---|---|
| 门店营业日报 | 一个已结束营业日，读日事实 | 订单/批次、菜品量、原/有效应收、厨房/退餐/实际耗用及覆盖；不同单位/币种分行 |
| 多店日报对比 | 同一门店当地营业日标签集合 | 同metricVersion、指标、计量与观察范围逐店对比；跨时区分别给UTC边界和完成情况 |
| 菜品/套餐/小料日报 | 日事实相应SKU/角色set | 数量、原分配金额、餐牌/渠道切片；父子金额按唯一层级 |
| 餐牌餐段/渠道日报 | 日事实MENU_LINE/ORDER_CHANNEL分别查询 | 行数量/金额和原订单统计分开；不跨set拼接后再重复求和 |
| 厨房工位日报 | 发生日日事实 | 制作/交接/报损及有效耗时；不同阶段、任务/片段角色分列 |
| 原料耗用日报 | 库存发生日日事实 | 实际领耗/冲销/净领耗/损耗；理论差异仅用匹配生产队列 |
| 员工操作日报 | 发生日日事实 | 接单/催菜/传菜/退餐/审核等有效动作量，保留角色和原因 |
| 桌台/预约专项日报 | B来源接通后 | 接待/确认人数、资源分母、预约成熟队列、等位耗时 |
| 周报/月报/自定义趋势 | 同口径日事实聚合 | 可加值SUM；均值/比率重算；不另建周/月定时任务或金额表 |
| 同比/环比 | 两段完整且可比日事实 | 两段口径/范围/币种一致才比较；缺分区不算正式增幅，上期为0时增幅null并展示绝对差 |
| 今日概览 | 原实时业务查询 | asOf及非最终状态；不伪装已发布日报 |
| 含今日的期间总览 | 历史统计＋今日实时分别展示 | 标MIXED/isFinal=false；跨段指标口径/去重不可合并时分别展示，不强行求期间总计 |

### L3.3 TopN榜单清单

| 榜单 | 排序指标/候选集合 | 允许维度与阶段 |
|---|---|---|
| 菜品下单量TopN | DISH_ORDERED_QTY；已出现有效菜品分组 | 固定商品角色和数量单位，按SKU或已证明可合并的商品，首批A |
| 菜品原单金额TopN | DISH_ORIGINAL_AMOUNT；有原唯一分配金额的菜品 | 同币种、同金额归属层；明确为原单金额，不叫实收排行，A |
| 菜品交付量TopN | DISH_DELIVERED_QTY；实际交付发生日菜品 | 需完整交付事件与SKU归属；不能直接从原单日销量替代，A来源补齐后 |
| 套餐份数TopN | BUNDLE_ORDERED_QTY；套餐容器 | 不把组成叶子计为套餐份数，A |
| 小料选配TopN | ADDON_SELECTED_QTY或ADDON_CHARGE_AMOUNT | 数量按单位；加价按币种；包含小料零价仍可参与选择量榜，A |
| 餐牌/渠道TopN | 餐牌行分配金额/数量；或原订单渠道数 | 指定对应set，不把多餐牌原订单重复记成多单；A |
| 工位产出TopN | KITCHEN_READY_QTY | 同单位、可比任务角色；动作量不是菜品销售量，A |
| 工位耗时/超时榜 | 秒数SUM/样本数或超时/可判任务数 | B；设置最低有效样本门槛，防一条样本排名失真；不平均每日均值 |
| 物料消耗TopN | MATERIAL_NET_ISSUE_QTY | 同物理单位/用途；KG与L不排在同一数量榜，A |
| 物料损耗TopN | MATERIAL_WASTE_QTY或核实历史损耗成本 | 数量榜A；成本榜需原估值且同币种B；不能按当前采购价补成本 |
| 员工操作TopN | STAFF_ACTION_COUNT | 同动作类型/角色；展示操作量，不自动宣称销售绩效，A |
| 门店业务TopN | 同口径原订单数、原应收等 | 获准门店、完整日范围、同币种；不同业态可按门店类型固定筛选，A |
| 净销售/毛利/顾客复购榜 | 最终商品净额、销售成本、期间去重模型 | B/C专项接通后，不在首批凭估值/日去重求和上线 |

建议默认N=10、上限100，均为待实现参数约束。排序方向由榜单目录固定；时效榜明确“最快”或“最慢”，不能让同一榜单名称有相反含义。

**统一TopN查询规则：**

1. 固定授权主体/门店、期间、metricVersion、aggregationSet、指标和币种/单位，再在同一只读一致事务中选定各日build/attempt。
2. 从完整日分组数据按目标对象聚合，再按指标排序，最后LIMIT N；不能先每天LIMIT N再合并，也不能先全局取TopN再过滤用户门店。
3. 数值并列时按稳定业务标识排序，默认返回固定N条的展示序号；切点并列可附说明，不以不稳定数据库默认顺序翻动榜单。
4. 占比的分母是该获准范围、同计量及同角色的完整候选总值，不是TopN这几行之和。非可加去重指标须使用其专属分母，不能套SUM。分母为0或不能核实时占比为null；已证明的负数流量按指标原定义保留，不钳成0。
5. 任一必需分区/候选关键指标缺失，默认返回排行不可确认及missingPartitions/unknownMetrics；若提供已知候选预览，明确INCOMPLETE且不宣称全局TopN，不能静默剔除未知对象后排名。
6. 历史分类/菜名使用原快照。不同菜单/渠道筛选只有存储粒度包含该维度且指标允许时才支持；不能在粗粒度日总量上事后猜分配。
7. 默认候选是期间有可核实事实的对象。“零销量/滞销商品榜”需期间可售商品及营业时段历史清单，属于额外B合同；缺席日事实不等于可售但零销量。
8. 第一版不开放无法准确合并的期间顾客去重、P90、跨单位消耗总榜；不将实现缺口补成默认值。

查询响应候选字段：range/dateBasis/attributionBasis、scope、metricVersion、aggregationSet、metricCode、currency/unit、rows、rowCount/totalCandidates、denominator（适用时）、buildRefs、sourceWatermarks、coverage、missingPartitions、unknownMetrics、isFinal。均为合同规划，不是已新增API字段。

### §2 下一步清单与依赖

| 顺序 | 清单项 | 输出/前置 |
|---|---|---|
| 1 | 冻结首批指标及aggregationSet | 逐项列owner、去重、归日、单位/币种、可搭配维度；对应S1B-01 |
| 2 | 六表与完整日投影设计 | 把aggregationSet/attempt及查询索引补入原机器真源；实际隔离先于DDL，S1B-02～04 |
| 3 | 日报主流程任务 | J01/J02/J05/J12及统一构建发布；不新增每报表一个cron，S2 |
| 4 | 变更补偿和质量闭环 | J03/J04/J06/J07/J08；首批真实日报生产开放前必须完成，S3 |
| 5 | 日报/趋势/首批TopN查询 | 复用已发布日事实与同一授权/覆盖规则；优先菜品、套餐、小料、物料及门店榜，S4 |
| 6 | 导出和保留 | J09/J10/J11＋第七表；导出同查询固定build集合，S4 |
| 7 | 条件型指标扩展 | 时效/资源/成本/资金/去重事实逐项接通，B/C依原计划后置 |

本轮仅清单和存储方案，未生成DDL、创建数据库/表、注册新任务或API、运行统计/测试/部署。原S1-A部署状态保持不变。


## §14 经营核心报表 Review 与补充（2026-10-25，待实施）

### L1 最新业务主线

用户明确经营报表以“每日订单量、交易金额、毛利、纯利”为核心，拆分支付渠道、优惠与退款；在同一日报底座上形成周/月报、菜品日/周/月销售榜，补齐库存及今日实时组合查询。“余额宝”按上下文暂理解为“月报”，若原意不同须另补需求，当前不扩展任何理财产品。

**Review结论：保留独立统计库、完整日事实、统一构建发布及期间聚合；调整指标优先级并补齐资金只读、利润费用、库存结存和实时金额合同。** §12/§13原A/B/C是当时的实施分层，资金/成本“只读统计事实”按本节提升为核心前置；支付发起、扣券/储值积分、退款执行等写操作仍摘除，不因报表需求自动开放。

### L2.1 现有方式是否满足

| 诉求 | 当前方案 | 必须补充 |
|---|---|---|
| 每日订单量/交易金额 | 有原单/批次和应收，但核心顺序偏运营，成交确认口径尚未闭合 | 首屏分别给下单量、成交量、成交额、优惠、销售退回及净额；原应收不自动叫成交额 |
| 支付渠道及退款优惠 | 目录已有但原列后置，通用FINANCE_DAY太粗 | PAY真实成功只读来源、混合支付分配、退款原支付关联、优惠承担方及统计去重 |
| 毛利 | 已列公式，缺逐单销售成本完整证据 | 优先核验库存历史COGS与菜品/套餐/原料销售履约的关联；理论配方成本不能冒充实际毛利 |
| 纯利 | 原目录没有完整经营费用/期间分摊模型 | 明确经营净利口径，补房租/工资/水电/佣金/配送/支付手续费等成本来源与分摊；不能将毛利直接改名纯利 |
| 日/周/月报与菜品榜 | 主方案成立 | 固定核心字段；周/月按日事实聚合，金额/数量与均值/余额分别处理；跨期去重不能简单SUM |
| 库存 | 现有方案主要覆盖耗用/损耗，原库存模块已有月报/动销，但旧MonthlyVO数量为int | 复用库存owner，补精确数量的日结存与收发平衡；不直接照搬整数月报到餐饮小数库存 |
| 当天实时订单/金额/退款/优惠组合 | 实时/历史分层已有，但LIVE指标偏待办状态 | 使用与日报相同定义的实时事实投影，资金/优惠/成本分族给水位；不能把缺源当0，也不能用原未付应收当实收 |

本轮检索已有InventoryReportService.monthly/ranking与库存历史销货成本输出；这只能证明存在可复用底座，不证明已覆盖餐饮配方叶子COGS。经营费用/房租工资等Java/迁移关键词检索未发现完整费用owner，须专门核验外部或现有财务来源，不能承诺已经存在。

### L2.2 经营日报核心卡片和公式

以下为经营展示合同建议，不替代正式财务核算。所有金额分币种，并沿原订单最终税额/金额分配区分含税与税前。

| 卡片 | 建议口径 | 来源/门禁 |
|---|---|---|
| 下单量 | 本期真实创建的餐饮核心子单去重；另列取消量 | CORE/FOOD，按原事实归日 |
| 成交量 | 本期原CORE明确成交确认的子单去重；不能以接单、付款次数或加菜次数替代 | CORE真实成交状态及时间 |
| 成交额（退款前） | 成交确认时冻结的订单含税应收，商品/费用/税可拆；后续价款更正独立显示 | CORE成交快照，当前未付原应收另列 |
| 商品优惠额 | 原最终商品优惠分配，活动/券/人工、平台/商家承担分别列 | CORE/权益owner最终事实，不从付款差额猜优惠 |
| 成功退款额 | 本期PAY确认成功的退款/退回分配金额，关联原订单及原支付腿 | PAY，退款申请/退餐不等于退款成功 |
| 商品净销售额（税前） | 商品原税前价款−已生效商品优惠−已生效销售退回±其他商品价款更正 | CORE原商品/税分配；同一更正只作用一次 |
| 成功结算/外部净流入 | 按原PAY结算工具分列；外部净流入=确认外部收款−外部退款 | 储值消费、积分抵用不是新外部现金；充值/押金与销售款分开 |
| 商品毛利 | 同销售队列商品税前净额−可核实历史销售成本 | 缺任一相关成本则完整毛利null，另给已知部分及覆盖 |
| 门店经营净利（纯利建议口径） | 商品毛利＋其他已确认税前经营收益−未计入销售成本的直接费用−期间经营费用−其他未计入的损失 | 每项均需来源、成本分类、期间分摊及完整性；不默认等于财务税后净利润 |

**避免重复扣减：** 若直接读取CORE当前净商品额，不得再次减已包含的优惠/退餐更正；成功资金退款也不全部等于销售退回，例如押金退回。退货能否冲回成本取决于原库存真实回入/冲销，已经食用报损的退餐不能自动恢复成本。税额、代收款、充值、押金、小费按原owner明确属性列示，不随意计入商品销售或利润。

新增固定指标候选：ORDER_TRANSACTED_AMOUNT、GOODS_SALES_RETURN_AMOUNT、OPERATING_OTHER_INCOME_AMOUNT、PAYMENT_FEE_AMOUNT、DELIVERY_FEE_COST_AMOUNT、PLATFORM_COMMISSION_AMOUNT、OPERATING_EXPENSE_AMOUNT、OPERATING_UNALLOCATED_LOSS_AMOUNT、OPERATING_NET_PROFIT_AMOUNT/RATE；已有指标继续复用。净利率分母使用已确认的同队列税前经营净收入，分母0或未知为null。全部尚未编码或新增DDL。

### L3.1 支付、优惠与退款拆分

- 支付工具与通道分开：现金/银行卡/电子钱包/储值等工具，以及真实支付网关/收单渠道；仅展示实际已开通工具。储值核销进一步按本金/赠金分列，不能当银行到账。
- 单笔100元，现金60＋银行卡40：全店订单1笔，现金支付60、银行卡支付40；两个渠道各涉及1单，但渠道涉及订单数相加为2，不能替代全店订单数。支付笔数、涉及订单数、成交订单数分别命名。
- 退款保留原订单、原支付腿和实际退款工具/渠道；按退款成功发生日记流量，原单日另做累计分析。部分/多次退款去重到真实退款分配，不重复扣整单。
- 优惠按活动、折扣券、现金券、人工调价/折扣/抹零、赠送等原事实分类；储值/积分若原owner定义为结算工具，不能再列作一次商品折扣。平台承担、商家承担、补贴应收/到账分开，不默认相等。
- 优惠金额按支付渠道拆分，必须有原owner确认分摊规则和冻结分配；没有就仅按订单/优惠类型展示，不在报表临时按支付比例猜分配。多券×混合支付×多次退款不能直接多表JOIN后SUM。

### L3.2 毛利/纯利来源与费用归属

| 项目 | 必须补齐的事实 | 处理原则 |
|---|---|---|
| 销售成本 | SKU/套餐/配料实际销售履约→历史库存成本的映射及冲销 | 原料领耗、在制品、报损和实际COGS分开；出库估值未知不作0 |
| 支付/平台/配送费用 | 原手续费、佣金、配送成本及原归属订单/账期、成功状态 | 营收已净扣的费用不能再扣；费用实际扣款日与成本归属期分开 |
| 房租/人工/水电等 | 已确认费用凭证、门店/期间、税前金额、币种及分类 | 来自原财务/费用owner或受控外部导入，report不是费用录入和审批真源 |
| 周期费用分摊 | 原期间、分摊方式/版本、日分配明细及余数规则 | 按固定规则分配，分配合计=原凭证；迟到/更正生成日报新版本，不覆盖旧签核 |
| 其他经营收益 | 商家服务收入、已确认补贴等原权威归属 | 和商品收入互斥；补贴确认与资金到账独立，不凭促销面额推算收益 |
| 损失 | 未在COGS/经营费用中计入的损耗等原来源 | 分类互斥，不能把已含报损的成本再减一次 |

今日工资/房租等尚未确认时，若需要预估净利，应独立给ESTIMATED及估计来源/版本；实际净利仍UNKNOWN，不能把预估值标ACTUAL。目前默认只承诺核实后实际口径，预估属于后续明确启用的功能。

### L3.3 库存补齐：流量和余额分开

复用独立库存系统与原库存报表能力，追加餐饮精确数量只读合同，而非复制一套库存账。

| 库存视图 | 日报字段/公式 | 周/月处理 |
|---|---|---|
| 收发存 | 期初、采购入库、调入、销售/生产出库、调出、报损、盘点调整、冲销、期末 | 各流量SUM，期初取首日边界、期末取末日边界；不能SUM每日余额 |
| 平衡校验 | 期末=期初＋全部确认入库−全部确认出库＋有符号调整 | 库存owner定义互斥类型，未知reason导致校验缺口，不用倒推期初自证平衡 |
| 当前库存 | 当前账面量、可用量、预占量，按原owner/location/SKU/单位 | 实时查询原库存；不把今日余额称为昨日结存 |
| 历史库存成本 | 期初/期末估值、出库/损耗历史成本及未知覆盖 | 估值来自历史时点，不能用当前成本乘历史量 |

日结存需库存owner提供营业日UTC边界时点快照或可验证历史台账重建，不能在日切后30分钟读取当前余额当日末余额。新增加INVENTORY_MOVEMENT_DAY和INVENTORY_BALANCE_DAY两个受控日数据集，事实仍存独立report库；数量沿DECIMAL(24,6)，禁止套用旧int报表转换截断。

### L3.4 今日实时与历史日报使用同一指标定义

实时经营页补齐：下单/成交/取消、成交额/应收、优惠、更正、成功收退款、按工具/渠道分布、商品数量排行、库存及可核实利润。首批实时查询按单门店/当前营业日和允许组合有界执行，复杂全历史多店查询仍读统计库。

- LIVE与DAILY共用固定指标定义、去重和金额归属；分别用原事实适配与已发布日事实适配，不能各写一套业务公式。
- CORE、FOOD、PAY、INVENTORY、费用owner分别提供asOf/sourceVersion/coverage；单个asOf参数不证明跨库全局一致。分别展示差异/未决，不能强行对齐成假平账。
- 同页并列显示订单/优惠/退款/渠道允许；“按支付渠道×优惠类型×菜品”的交叉汇总须有原行分配及合法统计粒度，否则拒绝该组合而非多表JOIN重复累计。
- 实时值isFinal=false；历史日报仅在营业日结束＋缓冲后发布。近7天含今日时标MIXED，使用同一范围/币种/口径；不可加指标单独处理，不为今日再造每分钟持久日报。
- 实时成本/费用不完整时仅显示可核实的订单及资金，利润明确UNKNOWN/覆盖不足。今日实时与晚些生成日报的正常差异须能由源版本、迟到事实、更正记录解释。

### L3.5 任务与存储的增补清单

六表控制结构与后续导出表继续沿用，暂不因业务主线调整就增加每报表一张表。新增/细化aggregationSet：OPERATING_ORDER_DAY、PAYMENT_CHANNEL_DAY、DISCOUNT_TYPE_DAY、REFUND_CHANNEL_DAY、PROFIT_COST_DAY、INVENTORY_MOVEMENT_DAY、INVENTORY_BALANCE_DAY。它们细化原STORE_ORDER/FINANCE等粒度，S1B-01应统一成唯一目录并明确替代/复用关系，禁止同时查询新旧重复set。

- J02需增加CORE/PAY只读、库存精确历史成本/结存、费用归属的来源提取/校验；不是调用支付/退款写API。
- J03需处理原订单更正、真实收退款、费用补录/更正、成本回执与库存冲销。分别标记原单日/发生日/费用归属日，不能全部丢到事件消费当天。
- J06新增渠道分配守恒、优惠/退款不重扣、税前/含税勾稽、COGS关联、费用分摊及库存收发平衡。没有可靠依据的等式标不可核验，不伪造差额为0。
- 周/月报继续从完整日数据求和与重算比率；菜品日/周/月榜仍先聚合再TopN。利润未知不能参与“完整毛利榜”，库存余额不SUM。
- 费用凭证来源、成本映射与必要历史库存边界若现有owner无法提供，必须单独补业务事实合同；统计表不能替代其业务账。是否需要扩展来源域表须在该合同review后决定，不能承诺六张报表表就能补出纯利。

发布完整性分层：订单/资金核心来源为正式经营日报的前置条件；成本/费用未接通时利润字段显式UNKNOWN，页面/导出标经营日报不完整，不能只展示订单数就宣称经营报表全部完成。已纳入必需来源的族临时失败仍不切换active整日版本，读取旧版并标水位；未接通来源不会默认补0。

### §2 调整后的优先清单（方案，未启动）

| 优先级 | 任务 | 完成标志 |
|---|---|---|
| P0-1 | 核心指标合同 | 订单/成交、税前含税、优惠/销售退回/资金退款、毛利与经营净利定义不混用 |
| P0-2 | 来源review及只读合同 | CORE成交/优惠、PAY渠道/退款、库存COGS/边界、费用凭证/分摊能力逐一核实，缺口显式登记 |
| P0-3 | S1-B独立库与发布底座 | 原隔离、权限、六表、attempt/fence、coverage及原子发布条件不变 |
| P1-1 | 经营日报主流程＋今日实时 | 原J01/J02/J05/J12；核心订单金额、优惠退款与渠道优先，利润按真实源展示完整性 |
| P1-2 | 补偿/质量＋周月报＋菜品榜 | J03/J04/J06/J07/J08；正式开放前补偿/质量必做；期间TopN及比率正确 |
| P1-3 | 库存收发存与成本费用接线 | 精确数量、历史边界、COGS与费用分摊到原日，毛利/纯利按源逐项转为可核实 |
| P2 | 其余运营指标、导出及深度分析 | 桌台/员工/厨房深度排名、顾客模型等沿旧目录保留 |

本轮仅review现有方式并更新优先级与方案；未新增业务代码、DDL、公开API、统计任务、财务来源或支付能力，未执行测试或云端变更。


## §15 数据库、定时任务执行Service与查询API细化入口（2026-10-25）

完整字段/索引/约束、J01～J13的Handler→Service方法、构建执行链、B端日报/实时/周月/渠道/退款/利润/榜单/库存API输入输出及部署边界，见[餐饮经营报表数据库任务与API](../TDD-餐饮经营报表-数据库任务与API.md)。该文件为本方案的实施合同细化，所有新表/类/端点仍为候选；不新增平行业务真源，也不代表已创建数据库或接通资金来源。


## §16 日报按天执行（2026-10-25，用户确认）

正常日报每门店每营业日执行一次，门店日切后配置时间触发，建议缓冲30分钟。FoodReportDailyJob调用ReportDailyService.runDaily完成计划与构建；J02只作为统一内部执行单元，不设置分钟领取cron。旧“十分钟计划/分钟执行”建议取消，失败、漏跑、dirty更正及人工补算仍可独立恢复，不能重复累加。今日经营数据请求时实时读取，不因日报每日运行而失去实时性。具体Service和频率已同步[执行设计§7.1](../TDD-餐饮经营报表-数据库任务与API.md#71-日报执行频率最新约定)，本次无代码/任务配置变更。


## §17 精简执行任务（2026-10-25，用户确认）

只新增FoodReportDailyJob一个每日常规任务，调用ReportDailyService.runDaily，内部完成计划/漏跑恢复/必要dirty补算/取数/计算校验/发布。原J01～J12是能力编号，不各建Handler或cron；日报重算统一由runDaily/rebuild执行，人工补算与导出按需调用原任务平台。业务事件只标脏，初始化与监控复用原设施；过期/无引用派生数据维护并入每日末尾或通用清理，维护失败不得回滚已发布数据。

执行Service由十余个步骤类收敛为Daily/SourceCollection/Storage/Query/Realtime，以及后续Export。scope、幂等、attempt/fence、来源完整性、发布原子性及财务未知规则不变。以[精简执行设计§7](../TDD-餐饮经营报表-数据库任务与API.md#7-精简后的实际任务与service最新约定)为最新清单；旧表格是能力拆分，不再是新增任务数量。此次只改方案，未新增或停用线上任务。


## §18 精简报表开发计划入口（2026-10-25，待执行）

后续依[经营报表TDD§2](../TDD-餐饮经营报表-数据库任务与API.md#2-开发计划2026-10-25已启动)的DEV-01～08执行：先核来源与合同，完成独立存储/只读事实、一个每日任务、历史/实时查询及按需导出代码，再统一编译和集中专项验证。只有一个新增常规定时任务；工作包数量不等于调度任务数量。费用和COGS真实依赖保持可见，不把UNKNOWN当纯利已验收。当前已执行本地编码、隔离库迁移及专项验证，实际对账见经营报表TDD§3；未生产迁移或部署。

# TDD-B端报表库与日结

> 状态：**设计中** · 创建 2026-09-30
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

| AC / 风险 | 测试方法 | 判据 |
|---|---|---|
| AC1 | `ReportDailyRollupTest#dailyRowsPerStore` | 两家店两天的单 → 落 4 行，数字各自对 |
| AC2 | `ReportQueryTest#periodOverPeriod` | 环比取的是**上一个等长区间**，不是上个自然周 |
| AC3 | `ReportQueryTest#monthlyFromDaily` | 按月聚合 = 该月逐日之和 |
| AC4/AC5 | `ReportGoodsTest#rankByQtyAndAmount` | 刻意构造成两种排法**第一名不同**；两列都非空 |
| AC6 | `ReportDailyRollupTest#ownedTrafficSplit` | 自带 + 平台 = 总数 |
| AC7 | `ReportQueryTest#storeScope` | 只看一家店时别店的数不进来 |
| AC8 | `ReportWatermarkTest#watermarkAdvances` | 跑完水位前进；没跑时读侧能识别缺口 |
| **幂等** | `ReportDailyRollupTest#rerunIsIdempotent` | 同一天跑三次，结果与跑一次相同 |
| **重算窗口** | `ReportDailyRollupTest#lateRefundFixesHistory` | 前天的退款在今天跑批后**改正了前天那一行** —— 这条是「不做增量」的理由，去掉重算窗口它必须变红 |
| R4 | `ReportVsLiveConsistencyTest#sameCaliber` | 同一天：汇总表的数 == 现算的数 |
| R5 | `ReportQueryTest#cannotSeeOthers` | 带别家 `entityNo` 查不到数据 |
| R2 | `ReportDataSourceTest#refusesPlatformDataSource` | 注入平台数据源时**启动失败**（护栏生效） |

**两条消融**：① 去掉重算窗口（只算 T-1）→ `lateRefundFixesHistory` 必须红；
② 把护栏 `mustBeOwnDataSource` 注掉 → `refusesPlatformDataSource` 必须红。

---

## §6 计划（四步，每步可独立上线）

| 步 | 内容 | 产出 | 依赖 |
|---|---|---|---|
| **P1** | 建库与模块骨架 + `rpt_daily_store` + 日结作业 + 水位 | R1 近几日能用 | 无 |
| **P2** | `rpt_daily_goods` + 商品榜查询 | R3 商品 TopN（件数 / 销售额两档） | P1 |
| **P3** | 按月聚合 + 与对账单合并口径 | R2 按月营收（补上订单数） | P1 |
| **P4** | 下钻：`/biz/order` 加 `from`/`to` | 原型 s05 走得通 | 独立，可并行 |

**毛利（原型 s04 第三档）不在这四步里** —— 它要先定成本口径与跨域取数方式，单独一笔。

---

## §7 确认与完成

- [ ] §0 的 8 条 AC 都有落点，没有挂不上 AC 的设计条目
- [ ] §5 每条有测试方法，跑过并贴真实输出；两条消融都红过
- [ ] 第二数据源三道（`@Qualifier` / 非 `Flyway` 类型 / `mustBeOwnDataSource`）逐条对照 §4 检查
- [ ] 新迁移在**真库副本**上跑过（H2 会合并重名索引，冒烟关着 Flyway）
- [ ] 新端点按登记清单五处登记齐
- [ ] 生成物重新生成；本文状态改「已实现」

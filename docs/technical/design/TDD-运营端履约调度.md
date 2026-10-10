# TDD-运营端履约调度与物流（P-5.1 / P-5.2）

状态：**已确认（2026-08-13 用户授权批量推进）**
关联需求：[需求矩阵-三端 §六](../../requirements/需求矩阵-三端.md) 的 **P-5.1 履约调度** 与 **P-5.2 物流**
上游设计：[TDD-ops-履约与获客](./ops/TDD-ops-履约与获客.md)（前端已实现）· [TDD-ops-快递与运费](./ops/TDD-ops-快递与运费.md)（前端已实现）
决策依据：[ADR-005 履约方式与自提点模型](../ADR/ADR-005-履约方式与自提点模型.md) §4 §5
创建日期：2026-08-13
覆盖：15 条端点 · 4 个权限码 · 1 张表扩列 + 5 张新表

---

## 一、一句话

**B 端只看自己的货，平台看一个点上所有商家的货** —— 这一批把平台侧的
「调度（批次/配车）、汇总（跨商家分拣）、监控（核销与逾期）、规则（逾期处置）」
四件事接到**真实订单**上，并把快递与运费从 mock 落成可配置的库表。

---

## 二、为什么是这个方案

### 2.1 三个被否掉的整体思路

| 方案 | 优点 | 否掉的理由 | 结论 |
|---|---|---|---|
| **A. 平台侧新建一套「平台批次/平台核销」表** | 与 B 端完全解耦，写起来最快 | 平台与 B 端会各自维护一份「这个点今天有多少单」。**两份计数迟早分岔**，而分岔的症状是「总览说 3 单、点进去只有 2 单」（B-6.0 的原话），没有任何报错 | ❌ |
| **B. 平台侧只做只读投影，一切数字**现算**自 `ord_sub_order`；只有「调度属性」（车次、状态、计划到货时间）落库 | 计数只有一份真源；调度属性本来就只有平台有 | 需要给 fulfillment 域开两个新 Port（订单聚合、自提点名录） | ✅ 采用 |
| C. 让 ops 控制器直接查 `ord_sub_order` / `cmt_pickup_point` | 少两个 Port | 违反域间边界（ArchitectureTest 当场红），且状态机会有第二个入口 | ❌ |

### 2.2 为什么扩 `ful_batch` 而不是新建表

`ful_batch`（V1 基线）已经是「一个自提点某一天到的一堆货 + 签收状态」，
**恰好就是 P-5.1.1 要的对象**，只是缺三个调度属性（车次、计划到货时间、目的社区）。

它今天**只有实体、没有任何读写代码**（`FulBatch` 类在，Mapper 与调用方都不存在）。
新建 `ful_arrival_batch` 的后果是：库里两张语义相同的表，B 端将来做「站长按堆签收」时
不知道该写哪一张 —— 而这正是 ADR 反复要防的那种「同一件事两处真源」。

**状态取值从 `PENDING/RECEIVED` 换成 `PLANNED/DISPATCHED/ARRIVED/SIGNED`**：
两个旧值是四个新值的子集（`PENDING`≈`PLANNED`、`RECEIVED`≈`SIGNED`），
迁移里带 UPDATE 把存量映射过去。**没有存量行**（无写入方），但 UPDATE 仍然写 ——
「今天没有行」不是不写迁移的理由，下一个人不会去数。

### 2.3 为什么逾期规则落 `sys_setting` 而不是新表

它是一行三个字段的单例配置。`sys_setting`（V8）建表注释已经把理由写完了：
「各建一表的结果是十几张只有一行的表，外加十几份几乎一样的读写代码」。
**校验留在本域 Service**，这张表只负责存住 + 留痕。

---

## 三、结构

### 3.1 与 B 端履约的边界（本节是这份方案的核心）

```
                    ┌───────────────────────────────────────────┐
   真源             │        ord_sub_order（状态机在 trade）      │
                    └───────────────────────────────────────────┘
                        ▲ 写                         ▲ 读（聚合）
                        │                            │
        ┌───────────────┴──────────┐    ┌────────────┴──────────────┐
        │  B 端 · 自提点履约台      │    │  平台端 · 履约调度         │
        │  /biz/pickup/**          │    │  /ops/fulfillment/**      │
        │  作用域 = 我承接的点      │    │  作用域 = 全平台           │
        ├──────────────────────────┤    ├───────────────────────────┤
        │ 到货登记 markArrived     │    │ 批次推进 setBatchStatus    │
        │ 扫码核销 verify          │    │ 跨商家分拣汇总 listSorting  │
        │ 分拣单   picking(本点)   │    │ 核销监控 listRedeemStats   │
        │ 短少上报 reportShortage  │    │ 逾期规则 overdue-rule      │
        └──────────────────────────┘    └───────────────────────────┘
```

| 问题 | 答案 |
|---|---|
| **谁写订单状态** | 只有 B 端（经 `FulfillmentQueryPort`→trade 状态机）。**平台侧 15 条端点一条都不写订单状态** |
| **平台侧的数字从哪来** | 每次请求现算 `ord_sub_order`。**不存计数器** |
| **复用了哪些既有领域服务** | `FulfillmentQueryPort`（trade→fulfillment 的订单出口，原样复用）· `PickupService.reportShortage`（在它已有的留痕上补一行结构化记录）· `SettingPort`（平台可调参数）· `ArchiveService` 的软删除口径（运费模板照抄，不复用它的 Kind 枚举——见 §五 T4） |
| **为什么没重建核销** | 核销要扫码、要在自提点现场、要按 `pickup_no` 收敛作用域。平台侧没有这三个条件中的任何一个；**能核销的平台账号 = 一个可以替任何人确认收货的后门** |

**平台侧唯一改变了业务事实的写操作是批次状态推进**，而它的后果只有一个：
`SIGNED` 之后这批货才进入分拣汇总视图。防住的是「没签收就分拣」——
责任判定的依据（货到底交没交到点上）会被跳过去。

### 3.2 数据结构

| 表 | 新建/扩列 | 迁移 | 存什么 | 防住什么 |
|---|---|---|---|---|
| `ful_batch` | **扩列** | V130 | 调度属性：`community_no` / `plan_arrive_at` / `vehicle` / 四态 `status` | 件数与商家数**故意不落列**：落了就有第二份计数 |
| `ful_shortage_report` | 新建 | V131 | 自提点上报的缺件：`sub_order_no` / `pickup_no` / `sku_no` / `kind` / `qty` | 今天 `reportShortage` 收了 `skuNo` 却**原地丢掉**，平台侧「哪个 SKU 缺了几件」无从算起 |
| `ful_shipment` | 新建 | V132 | 运单记录：承运商、运单号、状态、收件人/地区快照 | 快递单的平台侧主键；换单号要留痕，而 `ord_sub_order.express_no` 只有一个字符串，改了就没了 |
| `ful_shipment_trace` | 新建 | V132 | 轨迹节点（append-only） | 轨迹是承运商的事实，**只追加不修改** |
| `ful_freight_template` | 新建 | V133 | 运费模板 + 超区规则（JSON） | 硬删会把历史订单的运费依据一起抹掉 —— 归档不是删除 |
| `ful_carrier` | 新建 | V134 | 运力档案：优先级、截单时间、时效、启停、**密钥是否已配（布尔）** | 密钥本身不入这张表也不入契约，哪怕脱敏 |
| `sys_setting` | 复用 | — | `fulfillment.overdue-rule` 一行 JSON | 见 §2.3 |

`V135` 只改权限配置（功能点 `perm_code` + 角色授权），不动结构。

---

## 四、详细设计

### 4.1 端点与权限（15 条）

| # | 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|---|
| 1 | GET | `/ops/fulfillment/batches` | `fulfillment:batch:read` | 分页壳 `{records,total}` |
| 2 | POST | `/ops/fulfillment/batches/{batchNo}/status` | `fulfillment:batch:read` | 状态机，跳步拒 |
| 3 | GET | `/ops/fulfillment/sorting` | `fulfillment:batch:read` | 只看**已签收**批次覆盖的点 |
| 4 | GET | `/ops/fulfillment/redeem` | `fulfillment:redeem:read` | 逾期数由逾期规则算 |
| 5 | GET | `/ops/fulfillment/overdue-rule` | `fulfillment:redeem:read` | |
| 6 | POST | `/ops/fulfillment/overdue-rule` | `fulfillment:rule:update` | 宽限 <1 小时拒 |
| 7 | GET | `/ops/shipments` | `fulfillment:logistics:read` | 分页壳 |
| 8 | POST | `/ops/shipments/{shipmentNo}/waybill` | `fulfillment:rule:update` | `reason` 必填 |
| 9 | GET | `/ops/freight-templates` | `fulfillment:logistics:read` | `showArchived` 才带归档 |
| 10 | POST | `/ops/freight-templates` | `fulfillment:rule:update` | 新建/保存同一条 |
| 11 | POST | `/ops/freight-templates/{no}/archive` | `fulfillment:rule:update` | 默认模板拒 |
| 12 | POST | `/ops/freight-templates/{no}/unarchive` | `fulfillment:rule:update` | |
| 13 | GET | `/ops/fulfillment/carriers` | `fulfillment:logistics:read` | 按优先级升序 |
| 14 | PUT | `/ops/fulfillment/carriers/{carrier}` | `fulfillment:rule:update` | |
| 15 | POST | `/ops/fulfillment/carriers/{carrier}/enabled` | `fulfillment:rule:update` | 三条启停闸 |

**#2 故意不拆读写**（全表第二处例外，第一处是店招审核 `store:page:audit`）：
「看批次」与「发车/签收」是同一个人同一次动作的两半，拆出来会得到一个
**只有社区运营用、且他必然同时持有**的码 —— 那种码只增加配置负担。
更实际的理由是 **ops-web 的按钮就是用 `fulfillment:batch:read` 门控的**（`page.tsx` 的 `canDispatch`），
后端另判一个码等于造一个「看得见、点下去 403」的按钮。

### 4.2 角色映射

矩阵 §2.3 原话：**社区运营** = 「社区网格、自提点建档与启停、**履约调度**」。
所以四个码全部给 `COMMUNITY_OPS`，其余角色一个都不给。

> **履约相关的运营角色后端此前未实现** —— `Perms.java` 的类注释里写着
> 「风控的拦截/黑名单、**履约调度**、增长归因在后端还没有任何端点，
> 所以那几个角色拿到的清单很短」。这批把履约那一段补上，注释同步改掉。
>
> 刻意**不给客服**（`SUPPORT`）快递只读：矩阵给客服的数据边界是「按工单授权」，
> 而 `/ops/shipments` 是全平台运单。真要让客服查一单的物流，该做的是
> 工单里的一个订单维度入口，不是把全量运单表发出去。

### 4.3 到货批次：读时补齐（materialize-on-read）

批次行由 `listArrivalBatches` **幂等补齐**：扫出「有未完成自提单」的 `(pickup_no, arrive_date)`
组合，缺行就建（`status=PLANNED` / `vehicle=待派`）。

| 决策 | 防住什么 |
|---|---|
| 不做定时任务 | 定时任务的 cron 归 `定时任务清单与调度方案` 统一管，这批不动它；且**一个只在半夜跑的补齐任务，上午开城的新点当天看不到批次** |
| `arrive_date` 一期取**下单日** | 「次日达/预售」各有各的到货口径，编一个统一公式就是编数字。一期按下单日汇总，口径写在这里 |
| `item_count` / `merchant_count` 不落列 | 见 §2.1 方案 A 的否掉理由 |

### 4.4 核销监控与逾期规则的**真实链路**

```
graceHours（sys_setting）
      │
      ▼
overdueBefore = now − graceHours
      │
      ▼
ord_status_log 里状态推进到 FULFILLING（= 站长登记到货）那一条的 at
      │
      ├─ at ≥ overdueBefore  →  pending（还在宽限期内）
      └─ at <  overdueBefore  →  overdue（逾期未取）
COMPLETED（已核销）           →  redeemed
WAIT_FULFILL（货还没到点）     →  pending
rate = redeemed / (redeemed + pending + overdue)
```

**这就是「规则真的改到了消费它的地方」的那个点**：把 `graceHours` 从 24 改成 1，
同一批订单里逾期数立刻变多。测试用例正是这么断言的（撤掉这条链路必须变红）。

> **`pickupGraceDays`（矩阵 §七之二，1 天）没有被这条规则接管**，原因写清楚：
> 那个常量在 `packages/shared/src/utils/constants` 里，标的消费方是
> 「C 取货提醒 / B 自提点清点」，而**这两个消费方今天一行代码都没有**
> （全仓 grep 只有常量定义与那张生成的规则表两处命中）。
> 让平台配置去改一个没人读的常量，只会造出第二个「存了没人读」的东西。
> 本方案的做法是：**先把规则接到今天真的有消费方的地方（逾期看板）**，
> 默认值取 `pickupGraceDays` 的等价小时数（24），
> 等 C/B 两侧的取货提醒与清点做出来时，它们读同一份 `sys_setting`。

### 4.5 分拣汇总

- 只列 `status=SIGNED` 批次覆盖到的自提点 —— 没签收就分拣，责任判定的依据被跳过去
- 按 **SKU × 供货商家** 聚合（与 B 端 `picking()` 的 SKU 聚合键对齐；平台侧多一维商家，
  因为「一个批次混装多家的货」正是平台视角存在的理由）
- `shortQty` 取自 `ful_shortage_report`。**没有这张表时它只能恒为 0**，
  而页面上那个红色徽标会永远不亮 —— 一个永远不亮的告警等于没有告警

### 4.6 快递：运单记录同样是**从真实订单补齐**

`ful_shipment` 由 `listShipments` 幂等补齐：`fulfillment=EXPRESS` 且 `express_no` 非空的子单
各对应一行。承运商取**当时优先级最高的启用运力**并落库快照。

| 决策 | 防住什么 |
|---|---|
| 状态由订单状态推导（`WAIT_FULFILL→CREATED` / `FULFILLING→IN_TRANSIT` / `COMPLETED→DELIVERED`） | 一期**不接快递鸟/菜鸟**（见 §五 T1）。编一个假的轨迹推进比没有更糟 |
| `EXCEPTION` 只能由换单号之外的外部回传产生，一期**没有产生它的路径** | 契约里保留这个取值（疑难件不是终态），但后端不会凭空造 |
| 换单号写一条轨迹 | 「之后对不上时这是唯一线索」 |

### 4.7 校验规则逐条（与 `ops-web/lib/api/mocks/fulfillment.ts` 逐条对齐）

| 端点 | 规则 | 错误码 | 不这么做会怎样 |
|---|---|---|---|
| 批次推进 | 只能沿 `PLANNED→DISPATCHED→ARRIVED→SIGNED` 走一步 | `30004` | 跳步等于「没到货就签收」，责任判定失去依据 |
| 逾期规则 | `graceHours ≥ 1` | `30005` | 到点即作废必产生客诉 |
| 逾期规则 | `action=POSTPONE` 时 `maxPostpone ≥ 1` | `10400` | 顺延 0 次 = 名为顺延实为作废 |
| 换运单号 | 运单号非空、`reason` 非空 | `10400` | 换单号是唯一能追溯的动作 |
| 换运单号 | 已签收（`DELIVERED`）不许改 | `30006` | 把一条已完成的轨迹指向别处 |
| 换运单号 | 同承运商下运单号唯一 | `30007` | 两单轨迹搅在一起，之后谁也说不清 |
| 运费模板 | 首重 ≥ 100 克 · 续重单位 > 0 · 费用非负 · 免邮门槛非负 | `10400` | 首重 0 克 = 拿起来就收首重费 |
| 运费模板 | 超区区域不重复 | `10400` | 命中哪条取决于顺序 —— 隐性行为 |
| 运费模板 | `REJECT` 不能带加价额；`SURCHARGE` 加价额 > 0 | `10400` | 传了就是调用方理解错了，**拒绝而不是静默清零** |
| 归档模板 | 默认模板不能归档 | `30008` | 归档之后新商家没有模板可用 |
| 保存运力 | 优先级正整数且不重复 | `30009` | 同优先级时选哪家取决于顺序 |
| 保存运力 | 截单时间 `HH:mm`、时效为正整数 | `10400` | |
| 启用运力 | 没配密钥不能启用 | `30010` | 启用后下单当场失败，比不启用更糟 |
| 停用运力 | 还有在途单不能停 | `30011` | 那些单的轨迹拉不回来 |
| 停用运力 | 不能停掉最后一家启用的 | `30012` | 全停之后快递单无处可下 |

---

## 五、取舍记录

| # | 冲突 | 让了谁 | 为什么 |
|---|---|---|---|
| **T1** | 5.2.1 快递轨迹「对接」 vs 一期范围 | 只做**存储与展示** | ADR-005 §5：一期只做快递 + 商家自送，第三方即时配送全外接。真接快递鸟/菜鸟要密钥托管、回调鉴权、重试与对账，是一个完整子系统。**先把运单号回填与轨迹留痕做真**，接口位留着 |
| **T2** | 5.2.4 第三方运力「对接配置」 vs 二期 | 只做**配置存储 + 启停** | 同上。`api_key_configured` 是布尔而不是密钥：密钥该进配置中心/KMS，不该进业务表，更不该进前端契约 |
| **T3** | 运费模板配了**没有消费方** | 先存住 | 与逾期规则不同：运费模板是 P-5.2.3 明确要的**平台侧主数据**，商家侧 `store_delivery_rule`（V7）今天各配各的。一期把平台模板存住，二期让下单算价读它。**这一条是已知的「存了暂时没人读」，登记在这里而不是藏着** |
| **T4** | 运费模板归档 vs 复用 `ArchiveService` | 自己写 `archived_at` | `ArchiveService.Kind` 是个枚举 + 一张集中表，加一种要动它的公共代码（并行会话正在改归档相关的域）。运费模板的归档就是一列时间戳 + 一个默认模板闸，自己写 8 行比动公共枚举安全 |
| **T5** | 批次件数「实时算」vs「查询变慢」 | 实时算 | 一个自提点一天几十到几百单，实时聚合完全够。**等它慢了再加缓存，也比现在就存一份会分岔的计数安全** |
| **T6** | 平台能不能代替站长「标记到货」 | 不能 | 平台代签的后果是「平台说到货了、站长没见到货」，而买家已经收到「可以来取了」的通知。签收动作必须发生在货真的在的那一端 |

---

## 六、待确认

1. `arrive_date` 一期取下单日（§4.3）。次日达/预售/生鲜截单各自的到货口径要不要分开，
   等 C 端到货通知（C-5.1）做出来时一起定 —— 那时才有一个真正需要它的消费方。
2. 运费模板何时接进下单算价（T3）。**在接进去之前，页面上那个模板对订单没有任何影响**，
   ops-web 侧是否要加一句说明，留给运营端文案的那一批决定。
3. `EXCEPTION`（疑难件）今天没有产生路径（§4.6）。接快递轨迹回传时一起补。

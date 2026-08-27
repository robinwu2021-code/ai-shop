# TDD 行业包 —— 机制与装配

状态：**草稿 · 待确认** · 创建 2026-08-27
关联：[ADR-019 行业工作流做成独立行业包](../ADR/ADR-019-行业工作流做成独立行业包.md) · [核心能力清单](../reference/核心能力清单.md) · [行业包功能清单](../reference/行业包功能清单.md) · [TDD-行业化扩展](../TDD-行业化扩展-餐饮与服务业.md)
先例：`shop-job-api`（零依赖契约模块）· `shop-inventory`（独立数据源与独立迁移历史）· ADR-016（一份 jar 三个 profile）

---

## 0. 本册回答什么

「插件」是个说法，不是设计。本册把它落成可施工的三件事：

1. **行业包是什么**：一个可独立构建、独立发布、可被基座引用或加载的 jar，**业务逻辑写在里面**；
2. **它和基座之间那条线画在哪**：核心能力 vs 行业能力的判据，以及基座暴露什么、行业包只能拿到什么；
3. **它怎么被装上去**：编译期引用与运行时加载两条路的**具体机制**，而不是「用 Spring 装配一下」。

---

## 1. 先纠正一处：`sys_industry` 已经存在，且**不能拿来当行业包的钥匙**

盘代码时发现的，很关键：

| 已有的东西 | 位置 | 现有语义 |
|---|---|---|
| `sys_industry` 表 + 7 条种子（CATERING/RETAIL/LIFE_SERVICE/ENTERTAINMENT/TRANSPORT/ONLINE/OTHER） | `V1__baseline.sql`, `V2__seed_master_data.sql` | **支付通道准入**（能否小微进件）+ 强制积分 + 入驻展示 |
| `mch_entity.industry` | `MchEntity` | 主体的行业属性，进件时判白名单用 |
| `system:industry:read/update` | `Perms` | 运营维护该表 |

**它是「这家主体在支付通道眼里属于哪一行」，是法律与通道事实，不是「这家店按什么流程做生意」。**
两件事混用会立刻出问题：

- 粒度不对：美业和家政同属 `LIFE_SERVICE`，但工作流完全不同（一个要指定技师和耗卡，一个要派单和上门）；
- 层级不对：`industry` 在**主体**上，而业态是**门店**的事（同一主体开餐厅又开美容店并不罕见）；
- 语义会被污染：为了给美业单独一个工作流而往 `sys_industry` 加一行 `BEAUTY`，
  会**顺带改变这家主体的进件白名单判定** —— 一个前端功能开关，把商家的收款通道判掉了。

**结论**：`sys_industry` 原样不动。新增门店级绑定：

```sql
ALTER TABLE mch_store ADD COLUMN industry_pkg VARCHAR(32) NOT NULL DEFAULT 'RETAIL';
```

命名上刻意用 `industry_pkg` 而不是 `industry`，就是为了让下一个人一眼看出这是两件事。

---

## 2. 行业包的定义

> **行业包 = 一个行业「怎么做生意」的完整实现，打成一个 jar。**
> 它包含这个行业的表、业务逻辑、端点、权限码、菜单、任务、打印模板与事件订阅；
> 它**不包含**商品、订单、支付、会员、营销、结算 —— 那些是基座的，全行业共用一份。

一个行业包必须能回答清楚这三个问题，否则它不该存在：

1. 这个行业有哪些**基座没有的对象**？（餐饮：桌台、台账；美业：服务工单、排班）
2. 这个行业在基座的哪些**节点上要插一脚**？（下单后、支付后、结账前、打印时）
3. 拆掉它，基座还完整吗？（**必须是「是」**）

### 2.1 划线判据（与核心能力清单同一套）

| 归属 | 判据 |
|---|---|
| **基座核心能力** | 零售也要用 **且** 换行业只换数据不换代码 **且** 被两个以上的域读 |
| **行业包** | 上面任一条不成立 |

举三个容易划错的例子：

- **打印** → 基座。零售也出小票，行业差异全在路由规则的数据行里。行业包只提供**打印内容**（`PrintPayloadProvider`）。
- **储值/次卡** → 基座。三个行业都要，而且它是预收款，牵动结算与财务口径，不能有三份实现。
- **先吃后付** → **拆开**：支付链路（`WAIT_OFFLINE_PAY` → `markPaid`）在基座、已存在；
  「一桌多单、吃完合并结账」的台账编排在餐饮包。**同一个业务词，两半归属不同**，这是最容易含混的一处。

---

## 3. 契约：`shop-industry-spi`

新增模块，**零依赖**（连 Spring 都不引），照 `shop-job-api` 的写法。
它是基座与行业包之间**唯一**的共同语言。

> 为什么必须有它：没有它，行业包为了拿订单就得引 `shop-core`，
> 而 `shop-core` 会把 MyBatis、事务、实体列名一起带过去。
> 那一刻起「改基座不动行业包」就不再成立，jar 分开也没用 —— **物理分开不等于逻辑解耦**。

### 3.1 包描述（manifest）

行业包的身份不写在配置文件里，写在代码里，启动时被基座读走：

```java
public interface IndustryPackage {
    String code();                        // FOOD / BEAUTY / …（与 mch_store.industry_pkg 对齐）
    String version();                     // 包自身版本
    String spiVersion();                  // 编译时的契约版本，启动时校验（§5.4）
    Set<String> capabilities();           // 本包提供的能力码
    Set<String> requiredCapabilities();   // 本包依赖的基座能力，缺一个就拒绝启动
    String migrationLocation();           // 自带迁移目录，如 db/industry/food
    String migrationHistoryTable();       // 自带迁移历史表，如 fnb_flyway_history
}
```

`requiredCapabilities()` 是一条**廉价但救命**的检查：
餐饮包依赖 `PRINT_ROUTE_SPLIT`，如果这个基座版本还没做分单路由，
**启动时就报错说清楚**，而不是等到第一桌客人点完菜、后厨没出票才发现。

### 3.2 扩展点（基座调用行业包）

```java
/** 订单生命周期。事件驱动，at-least-once，实现者自己保证幂等（与 OutboxConsumer 同一约定）。 */
public interface OrderLifecycleListener {
    default void onCreated(OrderView o) {}
    default void onPaid(OrderView o) {}
    default void onCancelled(OrderView o) {}
    default void onRefunded(OrderView o) {}
    default void onFulfilled(OrderView o) {}
}

/** 打印内容。通道、设备、路由、重试都在基座，行业包只回答「这一单该打什么」。 */
public interface PrintPayloadProvider {
    String scene();                        // ORDER_PAID / CHECKOUT / KITCHEN / SERVICE_START …
    List<PrintDoc> render(PrintContext ctx);
}

/** 可占资源的语义。桌台/技师/工位共用基座的 mch_resource，行为差异在这里。 */
public interface ResourceTypeProvider {
    String resourceType();
    void onOccupied(String resourceNo, String bizNo);
    void onReleased(String resourceNo, String bizNo);
}

/** 结账前的建议。**只能建议，不能改价** —— 见 §3.4。 */
public interface CheckoutContributor {
    CheckoutAdvice advise(CheckoutContext ctx);
}

/** 行业包自己的定时任务。复用既有 JobHandler 契约，不另造一套。 */
// 直接实现 ai.neargo.job.api.JobHandler
```

### 3.3 回调（行业包调用基座）

行业包**不许**碰基座的表，只能通过这些窄口子：

```java
public interface CoreOrderApi {          // 建单、查单、确认收款
    OrderView find(String orderNo);
    void confirmOfflinePaid(String orderNo, String payChannel, String tradeNo, String operator);
}
public interface CoreResourceApi { ... }  // 资源占用/释放
public interface CorePrintApi { ... }     // 提交打印任务
public interface CoreMemberApi { ... }    // 会员资产读、扣（走基座流水，不许自己 UPDATE 余额）
public interface CoreCapabilityApi { ... }// 这家店开没开某能力
```

**注意 `CoreMemberApi`**：储值扣减必须回基座走流水表。
让行业包自己扣余额，等于允许两份代码写同一个金额 —— 那是对不上账的起点。

### 3.4 故意不存在的扩展点

**改价、改库存、改订单状态。**

这三样一旦开放，「交易主干只有一条」就作废了：定价与状态必须留在基座，
行业包只能通过 `CheckoutAdvice` 提建议，由基座决定采不采纳。
理由不是洁癖 —— 是退款、结算、对账、报表都按基座的价与状态算，
行业包在旁边改一手，四个下游全部对不上，而且**没有任何一处会报错**。

---

## 4. 行业包里装什么（六类交付物）

| 类别 | 形式 | 基座怎么收 |
|---|---|---|
| **表与迁移** | `db/industry/<code>/V*.sql` + 独立历史表 | 启动时按 manifest 的 location 各跑各的（`shop-inventory` 同款做法）。**顺带解决行业迁移与主干抢版本号** |
| **业务逻辑** | 普通 Spring Bean | AutoConfiguration 装配 |
| **端点** | `@RestController`，前缀固定 `/biz/x/<code>/**`、`/mp/x/<code>/**`、`/ops/x/<code>/**` | 前缀是硬约定：网关/权限/审计要能一眼分出行业面 |
| **权限码** | 常量 + 注册到 `Perms` 的行业段 | 权限码归属登记进 `scripts/perm-endpoint-map.mjs`，**生成物必须重跑**，否则矩阵测试红 |
| **菜单** | 迁移里 INSERT（运营端菜单在库里，不在 `nav.ts`） | 按能力开关对商家可见 |
| **打印模板与路由** | 迁移里 INSERT `prn_template` / `prn_route` 默认行 | 商家可改，不改就用默认 |

**注意两条已经吃过亏的**：
- 运营端菜单在库里，只改前端 `nav.ts` 接真后端看不到；
- 权限码与端点矩阵是**生成物**，改了源不重跑生成器等于没改（pre-push 会红）。

---

## 5. 装配机制

用户要的是「基础 jar 引用或者加载行业 jar」。这两条路都设计出来，**行业包代码在两条路上完全相同**。

### 5.1 L1 · 编译期引用（一期采用）

```
backend/shop-app/pom.xml
  <profile>food</profile>    → 引入 ai.neargo.shop.industry:shop-industry-food:jar
  <profile>beauty</profile>  → 引入 shop-industry-beauty
  <profile>all</profile>     → 全引（**线上默认**）
  <profile>core-only</profile> → 一个都不引（闸门用）
```

装配靠 Spring Boot 的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，
行业包自带一个 `FoodAutoConfiguration`，条件是 `@ConditionalOnProperty(shop.industry.food.enabled)`（默认 true）。
**基座 pom 里没有一行 `if`，它只是引不引这个 jar。**

### 5.2 L2 · 运行时加载（预留，机制现在就定死）

```
/opt/ai-shop/
  ├── app-<ver>.jar           基座
  ├── industries/
  │     ├── food-1.2.0.jar
  │     └── beauty-0.9.1.jar
  └── conf/industries.yml     启用哪些、各自版本
```

启动时（**不是运行中**）扫描 `industries/`，每个包一个子 `ClassLoader`，
父加载器只暴露 `shop-industry-spi` 与 JDK，**基座实现类一律不可见**（parent-last 之外再加白名单）。

为什么**只在启动时加载、不做热插拔**：

| 代价 | 说明 |
|---|---|
| Flyway | 行业包带表。热加载 = 运行时执行 DDL，失败时进程已经起着，回滚无处安放 |
| 事务/MyBatis | mapper 扫描与 `@Transactional` 代理跨 ClassLoader 要专门处理，出错现象是「事务静默不生效」 |
| 类型泄漏 | 同一契约类在两个加载器里是两个类，`ClassCastException` 报在与原因毫不相干的位置 |
| 发布路径 | 现在是「版本化 jar + 切软链 + 重启」。多一条插件目录路径，而**覆盖在跑的 jar 会挂住 JVM** 这坑踩过 |
| AOT/native | 已在规划 GraalVM，与动态加载直接冲突 |

**换来的只是少一次重启，而发版本来就要重启。**
启动时加载已经拿到「行业包独立发版、独立回滚、按客户装不同包」的全部收益。

### 5.3 两条路的选择依据

| 场景 | 用哪条 |
|---|---|
| 平台线上（同时接零售/餐饮/美业商家） | **L1 全装**，谁生效由门店能力开关决定 |
| 私有化交付（一个客户一个行业） | L1 按 profile 出包，或 L2 只放该行业的 jar |
| 行业包由第三方开发 | L2（基座不重新构建） |

### 5.4 版本兼容

- 契约 `shop-industry-spi` 用**语义化版本**，只增不改：加方法一律 `default`；
- 行业包编译时把 `spiVersion` 烧进 manifest，启动时基座比对：**major 不同直接拒绝启动**，不做「尽力兼容」；
- 拒绝启动的信息里要写清「哪个包、要的什么版本、当前什么版本」—— 这条信息是运维唯一能看到的东西。

---

## 6. 前端怎么办（后端不分叉 ≠ 前端不分叉）

| 端 | 做法 |
|---|---|
| b-app（商家） | 工作台按**能力**条件渲染：收银台 / 桌台图 / 排班表是三套界面。行业相关页面在 `pages.json` 里始终存在，**入口按能力显隐** —— uni 的分包不支持按商家动态装 |
| c-app（买家） | 商品详情页按履约方式渲染（选时段/选技师/选桌），不认行业 |
| ops-web | 行业包配置页 + 能力开关页；菜单在库里，由行业包迁移插入 |

**改完必须重跑 `python3 scripts/gen-ui-catalog.py`**，界面清单与代码对不上就推不上去。

---

## 7. 闸门（没有这些，行业包就是个包名）

1. **可剔除闸**：`-Pcore-only` 构建出的 jar 必须能起、零售全量场景测试全绿。
2. **依赖方向闸**（ArchUnit）：行业包不许 import 基座实体包；基座不许 import 任何行业包。
3. **行业码闸**：除开店/改店那一处，任何类引用 `industry_pkg` 常量即失败 —— 代码只问能力。
4. **能力两态闸**：每条能力开/关各有用例；撤掉判定后对应用例必须变红且点名正确。
5. **迁移隔离闸**：行业包迁移不得写基座的表；基座迁移不得写 `fnb_`/`svc_` 前缀的表。
6. **契约版本闸**：`spiVersion` major 不匹配必须拒绝启动，且有一条测试证明它真的拒绝。

---

## 8. 风险

1. **两个行业包互相依赖** —— 插件化最常见的死法。共用的东西（叫号、排队、资源）一律下沉基座，**不许横向引用**，由闸门 2 拦。
2. **行业包偷偷读基座的表** —— 同一个库，物理上拦不住。靠闸门 5 + code review；发现一次就当架构缺陷处理。
3. **契约膨胀** —— 每加一个扩展点都在削弱基座的确定性。加扩展点要走 ADR，不许随手加。
4. **迁移与实体不同步** —— 行业包加列必须同步实体，否则那列永远读出 null。
5. **私有 Maven 仓库缺位** —— 现在没有内网仓库，行业包只能同仓 `backend-plugins/` + `mvn install` 到 `~/.m2`。
   L2 与第三方开发都要等仓库到位。**这是唯一一个「现在做不了」的硬约束，写在这里免得被反复提起。**

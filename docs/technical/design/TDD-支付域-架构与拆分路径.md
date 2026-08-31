# TDD-支付域 · 架构与拆分路径

> 状态：**方案待评审** · 创建 2026-08-30
> 决策：[ADR-021 支付域独立为服务与独立库](../ADR/ADR-021-支付域独立为服务与独立库.md)
> 需求：[PRD-支付域](../../requirements/PRD-支付域.md) · [支付域-功能矩阵](../../requirements/支付域-功能矩阵.md)
> 上游架构：[TDD-三端服务拆分](./TDD-三端服务拆分-架构与依赖规划.md)（三端接入层、依赖规则、令牌分池继续有效）
> **参照实现**：[定时任务独立模块 · 实现方案](./定时任务独立模块-实现方案与交付计划.md) ——
> 本仓库第一个真独立出去的模块，形状照搬；**三处不能照抄**见 [ADR-021 §4.5](../ADR/ADR-021-支付域独立为服务与独立库.md)
> 配套：[数据库设计](./TDD-支付域-数据库设计.md) · [API 设计](./TDD-支付域-API设计.md) ·
> [定时任务](./TDD-支付域-定时任务.md) · [核心逻辑](./TDD-支付域-核心逻辑.md) · [资金风控](./TDD-资金风控方案.md)
> · [多区域通道](./TDD-支付域-多区域通道.md)（市场 × 通道 × 支付方式 × 币种，内置渠道清单）
> · [双形态部署与装配](./TDD-支付域-双形态部署与装配.md)（内嵌 / 独立两种形态的逐项装配）

---

## L1 · 定位

支付域是**一个可以单独部署的资金子系统**。
它对外只暴露两样东西：一组 HTTP 端点（给三端）和一组 Port 接口（给同进程内的其他域）。
**别的域永远不直接读它的表** —— 这条从阶段 1 起就由 ArchUnit 强制。

---

## L2 · 一、模块划分

**模块划分照 `shop-job` 的四层形状**（`-api` / `-core` / `-store` + 可跑的服务），
名字按支付域的实际分工命名：

```
backend/
├── pay-api        契约、Port 接口、DTO、错误码 · 零实现 · **零依赖**
├── pay-store      持久层：Spring Data JDBC repository + 数据源 + Flyway(db/pay)
├── pay-core       业务逻辑：结算 · 分账 · 对账 · 进件 · 费率 · 积分
├── pay-channel    通道适配：微信 / 支付宝 / 后续（带第三方 SDK）
├── pay-risk       资金风控：指标计算与拦截判定
└── pay-svc        阶段 3 的可跑产物：web 层 + 装配（对应 shop-job）
```

| 模块 | 允许依赖 | 为什么单独 |
|---|---|---|
| `pay-api` | **只有 JDK** | 别的域只 import 这个。零依赖，所以谁引都不会背上支付域的实现，更不会背上它的持久层 |
| `pay-store` | `pay-api` · `spring-boot-starter-data-jdbc` · 驱动 · flyway | 持久层单独一层，才拦得住「业务逻辑里随手写一条 SQL」 |
| `pay-core` | `pay-api` `pay-store` · `spring-context` | 域的主体 |
| `pay-channel` | `pay-api` · HTTP 客户端 | 换通道不该动业务，改业务不该重打 SDK |
| `pay-risk` | `pay-api` `pay-store` | 它读支付库、问主库名单，两边都不属于它自己 |
| `pay-svc` | 以上全部 + web | 阶段 3 才有 |

### ★ 一条硬约束：`pay-*` **不依赖 `shop-base`**

与 `shop-job` 同一条理由（[ADR-021 §3.5](../ADR/ADR-021-支付域独立为服务与独立库.md)）：
`shop-base` 把 `mybatis-plus-spring-boot4-starter` 作为**编译依赖**引入，
依赖它就等于把 MyBatis 拖进 classpath，而 **Spring Data AOT 是 Spring Data 的特性**，
MyBatis 不在这条路上 —— **一旦用它，这个模块永远进不了 AOT / native**，
且没有任何报错。

代价：`BizException` / `ErrorCode` / `DataScopeContext` 都用不了，支付域自己定义。
这对一个**独立库、独立进程**的模块是划算的 ——
支付库本来就不该有 DataScope 拦截器（数据域按 `entity_no` 显式过滤，见 §跨库）。

### 依赖规则，五个方向都要拦

```
其他域 ──▶ pay-api          ✅  唯一合法入口
其他域 ──▶ pay-core/store   ❌  ArchUnit 拦
pay-*  ──▶ shop-base        ❌  ArchUnit 拦 ★ 这条是 AOT 的前提
pay-*  ──▶ 任何业务域        ❌  ArchUnit 拦
pay-core ──▶ 直接写 SQL      ❌  只能经 pay-store
```

> 第三条是阶段 3 能不能拿到 AOT 的**前提**，而它**被违反时一声不响**：
> 编译过、跑得动、功能正常，只是 AOT 什么都没生成。
> CI 里要有一条依赖断言：`pay-*` 的 classpath 不含 `mybatis`。

---

## L2 · 二、四阶段与它们的闸门

### 阶段 1 · 收拢（同库 · 同 jar）

**做什么**：把 `shop-settle` 全部、`shop-channel` 的支付部分、`shop-merchant` 的进件部分
搬进 `pay-*`，把跨域调用统一到 `pay-api`。

⚠️ **这一阶段同时要换持久层**（MyBatis-Plus → Spring Data JDBC），
这与「只搬不改」有冲突。取舍：**先纯搬（保留 MyBatis），再单独一步换持久层**，
两步各自可回退。合成一步的话，一旦出问题分不清是搬错了还是映射写错了 ——
而支付域的映射写错是金额错。

**闸门**（新增 ArchUnit 断言，进 `shop-app/src/test/.../arch/`）：

| # | 断言 | 拦住什么 |
|---|---|---|
| 1 | `pay-*` 不依赖任何业务包（`shop-core` / `shop-merchant` …） | 切库那天的隐形跨库查询 |
| 2 | `pay-api` 的依赖集合为空（除 JDK） | 别的域 import 它时背上实现 |
| 3 | 非 `pay-*` 的类不得 import `pay-core` / `pay-store` | 绕过 Port 直连 |
| 4 | `pay-*` 里不得出现其他域的 Mapper | 直连别人的表 |
| 5 | **`pay-*` 的 classpath 不含 `mybatis`**（换完持久层后生效） | AOT 静默失效 |

**这一阶段只搬不改。** 行为一行不变 —— 把逻辑顺手改掉的话，
行为变化与结构变化就分不开了，出问题时不知道该回滚哪一个。
（这条沿用 `PaymentReconAxis` 那次的做法，那次是对的。）

**可以停在这里**：拿到的是依赖收敛，今天就能防住「随手 import 一个 core 的类」。

---

### 阶段 2 · 切库（独立库 · 同 jar）

**做什么**：15 张表迁到独立库，装配照 `shop-inventory` 的双数据源样板。

```java
PayDataSourceConfig
    ├── HikariDataSource("payDataSource")          ⚠️ 不标 @Primary
    └── DataSourceTransactionManager("payTxMgr")
@EnableJdbcRepositories(
    basePackages = "…pay.store",
    jdbcOperationsRef = "payJdbcOperations",       ⚠️ 显式指定，**不吃 @Primary**
    dataAccessStrategyRef = "payDataAccessStrategy")
PayFlyway
    └── locations = classpath:db/pay               ⚠️ 平台主库的 Flyway 必须同时显式声明
spring.aot.repositories.enabled = true             ⚠️ 显式写出来，不靠默认值
shop.pay.enabled = false（业务侧）/ true（pay-svc）
```

**四条 ⚠️ 都不是推测，是 `shop-inventory` 与 `shop-job` 已经踩过并记录的**：

1. 第二个数据源标了 `@Primary` 会把平台主数据源抢走 —— 全站查询静默走错库，
   而症状是 **DataScope 行级越权防线静默丢失**；
2. `@EnableJdbcRepositories` 不显式指定 `JdbcOperations` / `DataAccessStrategy` 的话，
   **repository 会静默打到平台库**，只在跑到那一行时才炸；
3. 声明任何 Flyway bean 都会关掉 Spring Boot 的自动装配，**平台主库的全部迁移随之失效** ——
   不报错，只是没跑；
4. **以为开了 AOT 其实没开**：启动照常、功能照常，只是什么都没生成 ——
   直到上 native 才发现。所以验收里要断言构建产物中存在 AOT 生成的 repository 实现类。

**默认关闭**也照 inventory：默认关着的那一半才是别人的生产常态，
装配要能在关着的时候正常起来。

**闸门**：

| # | 断言 | 怎么验 |
|---|---|---|
| 5 | 主库迁移里不再出现 `stl_*` / `sys_pay_*` / `pts_*` / `mch_payment_merchant` 建表 | 扫 `db/migration/*.sql` |
| 6 | `pay-store` 的 repository 只绑 `payDataSource` | 装配测试 |
| 7 | `shop.pay.enabled=false` 时全站可正常启动 | 上下文测试（inventory 已有同款：`InventoryDisabledContextTest`） |
| 8 | 关掉支付库之后，主库迁移**照常执行** | 迁移测试（⚠️ 3 的对照） |
| 9 | repository 打到的是**支付库**不是平台库 | 装配测试：查一张只存在于支付库的表（⚠️ 2 的对照） |

**可以停在这里 —— 而且这是合理终点。**
「账不能被别的域改」这条边界在阶段 2 就已经建立；
绝大多数「支付要独立」的诉求到此为止。

---

### 阶段 3 · 切产物（独立服务）

**做什么**：`backend/pay-svc/` 独立 jar，跨服务走 HTTP + Outbox。

**闸门**：

| # | 断言 | 怎么验 |
|---|---|---|
| 10 | **断网演练**：停掉 pay-svc，下单**明确报错**而不是挂起 | 手工演练 + 契约测试 |
| 11 | 积分预扣的超时释放在 pay-svc 挂掉时仍然生效 | 混沌测试 |
| 12 | 幂等键是子单号（不是事件 id） | 重投测试：同一事件投两次只生成一张结算单 |
| 13 | **构建产物里存在 AOT 生成的 repository 实现类** | 构建断言（⚠️ 4 的对照） |
| 14 | 业务系统的配置里**没有支付库连接串** | 配置扫描：每张表只有一个写者 |

**第 10 条最要紧**：挂起是最难排查的失败形态，而且用户会重复提交。
降级还是拒绝是产品决定（ADR-021 §7），但**不能是「转圈」**。

---

## L3 · 三、跨库与跨服务的三种调用

| 方式 | 用在 | 一致性 | 例子 |
|---|---|---|---|
| **快照列** | 单据生成时固化 | 强（写死在单据上） | 商家名、主体形态、收款号、费率 |
| **Port 回查** | 展示与判断 | 最终一致 | 「这家现在能不能收钱」 |
| **Outbox 事件** | 跨域写 | 最终一致 + 幂等 | 支付成功 → 生成结算单 |

### 快照的边界：账目相关的一律快照

```
账目字段（进结算单、决定金额）     →  快照。历史账不能跟着主库的修改变
展示字段（列表上给人看）           →  回查 + 标注「（成交时）」
判断字段（能不能放款）             →  回查。判断要用最新的事实
```

> 混用是必然的，所以**逐字段写清楚**，见[数据库设计](./TDD-支付域-数据库设计.md) §跨库引用。
> 不写清楚的后果不是报错，是某一天有人「顺手改成实时查」，
> 而历史账从那天起开始跟着主库变 —— 没有任何测试会红。

---

## L3 · 四、部署形态

> **2026-08-31 补充：支付域有两种形态，不是一种。**
>
> | | 方案一 · 内嵌（**默认**） | 方案二 · 独立 |
> |---|---|---|
> | 进程 | 与 `ai-shop.service` 同一个 | `ai-shop-pay.service`（:8083） |
> | 库 | 主库 `ai_shop` | 独立库 `ai_shop_pay` |
> | 持久层 | `shop-store-mybatis` | `shop-store-data-aot` |
> | 域间调用 | `PayPort` → `LocalPayAdapter` | `PayPort` → `RemotePayClient`（HTTP） |
> | 开关 | `shop.pay.mode=embedded` | `shop.pay.mode=remote` |
>
> **调用方一行不改** —— 换的只是装哪个 `PayPort` 实现。
> 如果调用方需要知道支付在哪跑，那就不是「两种形态」而是「两套代码」，
> 而两套代码里一定有一套没人测。
>
> 内嵌是**默认值，不是过渡态**：它就是生产今天跑的形态。
> 反过来说，独立形态是「默认关闭的那一半」，**必须有自己的集成测试** ——
> 只测默认值的话，上线那天才发现装不起来。
>
> 详见 [TDD-基础包分层与支付双形态 §三](./TDD-基础包分层与支付双形态.md)
> 与 [方案二架构图](../diagrams/pay-standalone-architecture.svg)。

```
同一台机器
├── ai-shop.service       SPRING_PROFILES_ACTIVE=api,ops    :8081   今天就有
├── ai-shop-job.service   SPRING_PROFILES_ACTIVE=worker     :8082   已上线
├── ai-shop-pay.service   （新）                             :8083   ← 阶段 3
└── MariaDB
    ├── ai_shop        平台库（含 shedlock）
    ├── ai_shop_inv    进销存库
    ├── ai_shop_job    job 库
    └── ai_shop_pay    **新** 支付库，独立账号只授权这一个 schema
```

| 阶段 | 进程 | 库 |
|---|---|---|
| 0–1 | 业务 + job | 三个 |
| 2 | 同上（业务进程多一个数据源） | **四个** |
| 3 | 业务 + job + **pay** | 四个 |

- **发布互不影响**：发业务不重启支付，反之亦然 —— 这是拆分的**首要驱动力**（与 job 同）
- **独立账号只授权一个 schema**：这是「业务系统一个字都不写支付库」的**技术保证**，
  不靠自觉。业务进程的账号连支付库都连不上
- **同机的诚实说明**：这解决「发布不打断」，不解决「机器挂了」

**阶段 3 的 worker 归属要想清楚**：对账扫描、账期截批、冻结兜底、积分转正
这四个任务**读写的全是支付库**，应随 pay-svc 走，不留在主 worker。
留在主 worker 的话，阶段 3 之后它们要跨服务写 —— 那是把已经拆干净的东西又缝回去。

---

## L4 · 五、这个方案会打破什么

三条与 ADR-021 §5 相同，此处只补落地细节：

| 打破什么 | 落地做法 | 在哪一阶段 |
|---|---|---|
| 积分抵扣与建订单不同事务 | 预扣 → 确认 → 超时释放 + Outbox | **阶段 3 准入条件** |
| 行级数据域跨不了进程 | `pay-api` 上显式传作用域参数 | 阶段 3 |
| 跨域事务 | Outbox + 子单号幂等 | 阶段 3 |

---

## L4 · 六、待确认

1. 积分预扣超时定多久（太短误释放，太长占住用户的分）
2. pay-svc 不可用时下单降级还是拒绝 —— **不能是「转圈」**
3. 支付库的备份、保留期限与写权限（审计要求）
4. **阶段 3 的同步调用量要重算**：job 那句「同步毫无压力」建立在一天几千次上，
   而支付是跟单量线性增长的（见 [ADR-021 §4.5](../ADR/ADR-021-支付域独立为服务与独立库.md) 第 3 条）
5. `pay-svc` 与 `shop-job` 的关系：支付的定时任务是**自己带 `@Scheduled`**，
   还是注册成 job 的 `JobHandler` 由调度器来调？后者要依赖 `shop-job-api`（零依赖契约模块，
   与 `pay-api` 同性质，不破坏 §一那条硬约束），但也把支付的定时能力挂到了 worker 的可用性上

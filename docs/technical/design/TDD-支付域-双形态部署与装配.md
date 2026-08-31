# TDD-支付域 · 双形态部署与装配

> 状态：**方案待评审** · 创建 2026-08-31
> 上游：[TDD-基础包分层与支付双形态](./TDD-基础包分层与支付双形态.md)（§三给了骨架，本文展开到可实施）
> 决策：[ADR-021](../ADR/ADR-021-支付域独立为服务与独立库.md)
> 参照实现：[进销存独立数据源](../../../backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/config/InventoryDataSourceConfig.java)（三个坑已经踩过并写在注释里）·
> [定时任务独立模块](./定时任务独立模块-实现方案与交付计划.md)
> 图：[两种形态的装配差异](../diagrams/pay-two-shapes-wiring.svg)

---

## L1 · 定位

支付域要**同时**支持两种部署：

- **形态 A · 内嵌**：与业务同一个进程、同一个 jar。这是**今天生产跑的样子，也是默认值**。
- **形态 B · 独立**：`ai-shop-pay.service` 自己的进程、自己的库、自己的产物。

一份代码两种形态，成败只取决于一件事：**形态差异有没有全部收敛到装配层**。
只要有一处业务代码知道「我现在是哪种形态」，那就不是两种形态，而是两套代码 ——
而两套代码里一定有一套没人测。

---

## L2 · 一、三条不可破的规矩

这三条不是风格偏好。**每一条被破坏时，形态 A 都照常工作，只有切到形态 B 才炸** ——
这正是它们要写成规矩、而不是留给判断的原因。

### 规矩一：内嵌形态也必须按「没有共享事务」写

形态 A 下 pay 和业务在同一个库，`@Transactional` 能把两边的写包在一个事务里。
形态 B 下不能 —— 那是跨进程，没有分布式事务。

如果形态 A 的代码依赖了共享事务，切到 B 的表现是：
**下单成功、支付单没落**，或者反过来。而且它不是每次都发生，
只在失败回滚的那条路径上发生 —— 也就是最难复现的那条。

**做法不是「大家注意一下」，是让它物理上做不到**：

> 形态 A 下 pay 也用**独立的 `DataSource` 与 `TransactionManager`**，
> 即使 JDBC URL 指向同一个库。

于是 `@Transactional`（业务的那个事务管理器）根本管不到 pay 的连接，
跨域事务在形态 A 下就写不出来 —— 想写也编译得过、运行会分成两个事务，
而这正是形态 B 的语义。**两种形态语义相同，才谈得上「切换」。**

这条直接照抄 `InventoryDataSourceConfig` 的形状，它已经这么做了。

### 规矩二：pay 自己的持久层**只有一套** —— Spring Data JDBC

上一份文档里我写过「形态 A 用 `shop-store-mybatis`、形态 B 用 `shop-store-data-aot`」。
**那条现在改掉**：pay 自己的表**两种形态都走 Spring Data JDBC**。

理由是维护成本的算术：两套 repository 实现意味着每加一张表、每改一个查询都要写两遍，
而**其中一遍（形态 B 的）在切换之前没有生产流量**。没有流量的那一遍会先腐烂，
等到真要切的那天，它已经不是「另一个实现」，而是「一份没人跑过的代码」。

代价很小：形态 A 的进程 classpath 里同时有 MyBatis（业务域用）与 Spring Data JDBC（pay 用）。
两者共存没有问题 —— 它们只是两个 starter。

> **AOT 的收益一点没丢**：AOT 的判据是「`pay-svc` 这个**产物**里有没有 MyBatis」，
> 不是「整个仓库有没有」。形态 A 的 jar 里有 MyBatis 是应该的，
> 那个 jar 本来就不追求 native。

那 `shop-store-mybatis` / `shop-store-data-aot` 的分包还要不要？**要**，
但它管的是**横切件**（幂等、Outbox），不是 pay 的业务表 —— 见 §三 5。

### 规矩三：形态差异只允许出现在两个地方

1. **Maven 打包**：`pay-svc` 这个产物包含什么。
2. **一个开关**：`shop.pay.mode = embedded | remote`（配在**主应用**侧）。

除此之外，任何 `if (mode == ...)` 出现在业务代码里都算破坏。
ArchUnit 加一条：`pay/**` 里不许出现对 `shop.pay.mode` 的读取。

---

## L2 · 二、模块与打包矩阵

```
backend/pay/
├── pay-api          契约：Port 接口 · DTO · 事件。别的域只依赖这一个
├── pay-domain       结算 · 分账 · 对账 · 提现 · 进件 · 费率 · 积分
│                      + repository **接口**
├── pay-store        Spring Data JDBC repository 实现 · 数据源 · Flyway(db/pay)
├── pay-channel      通道适配（微信 / 支付宝 / Xendit / HyperPay ...）
├── pay-risk         资金风控
├── pay-web          三端控制器 + 回调控制器 + 内部口
└── pay-job          四个定时任务的 JobHandler
```

哪个产物含哪些模块：

| 模块 | 形态 A · `ai-shop.jar` | 形态 B · `pay-svc.jar` | 形态 B · `ai-shop.jar` |
|---|:---:|:---:|:---:|
| `pay-api` | ✅ | ✅ | ✅ **只有它** |
| `pay-domain` `pay-store` `pay-channel` `pay-risk` | ✅ | ✅ | ❌ |
| `pay-web` | ✅ | ✅ | ❌ |
| `pay-job` | ✅ | ✅ | ❌ |
| `shop-base`（内核） | ✅ | ✅ | ✅ |
| `shop-base-web` `shop-base-auth` | ✅ | ✅ | ✅ |
| `shop-store-mybatis` | ✅（业务域用） | ❌ | ✅ |
| `shop-store-data-aot` | ✅（pay 用） | ✅ | ❌ |
| 业务域六个模块 | ✅ | ❌ | ✅ |

> **形态 B 下主应用只留 `pay-api`。** 这一条不能省：
> 如果主应用还带着 `pay-web`，那么 `/biz/settle/**` 在两个进程里都有控制器，
> **nginx 少配一条规则就会静默走到主应用那份** —— 而它连不上 pay 库，
> 表现是「查结算单返回空列表」，不是 404。空列表是个合法响应，没人会去查 nginx。

---

## L2 · 三、装配逐项

### 1. 数据源与事务

```java
@Configuration
@EnableConfigurationProperties(PayProperties.class)
@EnableJdbcRepositories(basePackages = "ai.neargo.pay.store",
                        jdbcOperationsRef = "payJdbcOperations",
                        transactionManagerRef = "payTxManager")
public class PayDataSourceConfig {

    @Bean DataSource payDataSource(PayProperties p) { ... }          // 不是 @Primary

    @Bean PlatformTransactionManager payTxManager(@Qualifier("payDataSource") DataSource ds) { ... }

    @Bean PayMigrated payMigrated(@Qualifier("payDataSource") DataSource ds) { ... }
}
```

**三处照抄 `InventoryDataSourceConfig`，每一处都有血的注释：**

| 照抄的 | 不照抄会怎样 |
|---|---|
| **不加 `@Primary`** | pay 的数据源会接走全平台的注入点 |
| **Flyway 凭证 bean 不是 `Flyway` 类型**（用 `record PayMigrated`） | Boot 的 `FlywayAutoConfiguration` 挂着 `@ConditionalOnMissingBean(Flyway.class)`，容器里出现任何一个 `Flyway` bean，**平台自己的全部迁移就整体退让** —— 库停在那一天的版本，之后每次发版都以为迁移跑过了。2026-08-27 在进销存上 A/B 验过：开进销存则平台 Flyway 一次都没跑，库停在 V230 |
| **自己的历史表** `pay_flyway_history` | 与平台迁移号互相撞 |

两种形态的**唯一差别是 URL**：

```yaml
# 形态 A —— 指向主库，表在 ai_shop 里，但事务是独立的
shop.pay.datasource.url: jdbc:mariadb://.../ai_shop
# 形态 B —— 指向 pay 库，独立账号只授权这一个 schema
shop.pay.datasource.url: jdbc:mariadb://.../ai_shop_pay
```

> **`db/pay/V1..Vn` 从第一天就是独立目录**，形态 A 下也是。
> 这样切库那天不用搬迁移，只改一行 URL。

### 2. `PayPort` 的两个实现

```java
// pay-api —— 别的域只看得到这个
public interface PayPort {
    PrepayResult prepay(PrepayCmd cmd);          // 幂等键在 cmd 里
    SettleView settleOf(String subOrderNo);
    ...
}
```

```java
@Configuration
public class PayPortConfig {

    @Bean
    @ConditionalOnProperty(name = "shop.pay.mode", havingValue = "embedded", matchIfMissing = true)
    PayPort localPayAdapter(PaySettleService svc) { return new LocalPayAdapter(svc); }

    @Bean
    @ConditionalOnProperty(name = "shop.pay.mode", havingValue = "remote")
    PayPort remotePayClient(PayClientProperties p) { return new RemotePayClient(p); }
}
```

`matchIfMissing = true` 是有意的：**不配就是内嵌**，也就是生产今天的样子。

`shop-core` 的下单代码一行不改 —— 它注入的一直是 `PayPort`。

### 3. web 层装不装

```java
@Configuration
@ConditionalOnProperty(name = "shop.pay.mode", havingValue = "embedded", matchIfMissing = true)
@ComponentScan("ai.neargo.pay.web")
class EmbeddedPayWebConfig { }
```

形态 B 的主应用里 `pay-web` 根本不在 classpath 上（见 §二的打包矩阵），
这个开关只是**第二道保险**：万一有人把依赖留下了，开关也会拦住。

`pay-svc` 那边不需要开关 —— 它就是为了跑 `pay-web` 存在的。

**控制器本身两种形态完全相同**，因为它只依赖 `shop-base-auth` 的
`SecurityUtils` / `PermChecker`，而那两个在两种形态里是同一个类。

### 4. 鉴权链

形态 B 的 `pay-svc` 要自己起四条 `SecurityFilterChain`（`/biz` `/mp` `/ops` `/callback`）。
**这套装配从 `shop-app` 的 `SecurityConfig` 抽到 `shop-base-auth` 里成为一个可复用的
`@AutoConfiguration`**，两边共用 —— 抄一份过去的话，
两边的 `permitAll` 列表迟早不一致，而不一致的方向可能是「支付服务上多放行了一条」。

前置条件（**必须先做完**）：`token-store` 从 `memory` 切到 `db`。
默认值是进程内内存，`pay-svc` 独立后一个令牌也验不了。
详见 [TDD-基础包分层与支付双形态 §3.3](./TDD-基础包分层与支付双形态.md)。

### 5. 横切件（幂等 / Outbox）

`pay-domain` 只依赖 `shop-base` 里的 SPI 接口（`IdempotencyService` / `OutboxEventBus`），
实现由部署形态给：

| | 形态 A | 形态 B |
|---|---|---|
| 实现 | `shop-store-data-aot` 的那份（走 `payDataSource`） | 同左 |
| 幂等表 | `pay_idempotent`（在 pay 的迁移里） | 同左 |

**注意这里两种形态是一样的**：pay 不复用业务域那份 MyBatis 实现，
因为那份走的是平台数据源 —— 一旦复用，形态 A 下 pay 的幂等记录会落在主库、
而支付单落在 pay 的数据源上，切库时幂等表被留下，**重放保护静默失效**。

### 6. 定时任务

pay 的四个任务（对账扫描、账期截批、冻结兜底、积分转正）读写的全是 pay 的表。

调度器 `shop-job` **已经支持多目标**：

```
shop-job-core/.../JobWorkerProperties.java:36
    private Map<String, String> targets = new LinkedHashMap<>();   // job_definition.target → base URL
```

所以两种形态的差别只是配置：

```yaml
# 形态 A
shop.job.targets: { main: "http://127.0.0.1:8081" }
# 形态 B
shop.job.targets: { main: "http://127.0.0.1:8081", pay: "http://127.0.0.1:8083" }
```

外加把那四行 `job_definition.target` 从 `main` 改成 `pay`。
**任务体一行不改** —— worker 只知道名字，这正是 job 模块当初设计的形状。

> `JobHandlerRegistry` 对重名是**当场启动失败**：
> 两个同名 handler 时其中一个会静默地永远不执行，而它在运营页面上照常显示下次执行时间。
> 形态 B 下 pay 的 handler 从主应用挪走，要确认主应用那边确实不再注册它们 ——
> 否则同一个任务在两个进程里各跑一遍，而两边连的是不同的库。

### 7. 通道回调

路径**不变**：`/callback/pay/channel/{channel}`（permitAll + 验签）。
通道侧配的回调 URL 改一次要重新报备，所以不动它，由 nginx 决定进哪个进程。

控制器随 `pay-web` 走：形态 A 在主应用里，形态 B 在 `pay-svc` 里。

---

## L3 · 四、配置项全表

| key | 形态 A | 形态 B（主应用） | 形态 B（pay-svc） |
|---|---|---|---|
| `shop.pay.mode` | `embedded`（或不配） | `remote` | — |
| `shop.pay.datasource.url` | 主库 | — | pay 库 |
| `shop.pay.client.base-url` | — | `http://127.0.0.1:8083` | — |
| `shop.pay.client.token` | — | 共享密钥 | 同左（校验用） |
| `shop.pay.client.timeout` | — | `3s` / 读 `10s` | — |
| `shop.auth.token-store` | `db`（前置改造后） | `db` | `db` |
| `shop.job.targets.pay` | — | — | 由 worker 侧配 |

**`shop.pay.client.*` 在形态 A 下必须不配**：配了也不会被读（bean 都没装），
但留在配置文件里会让下一个人以为它生效了。

---

## L3 · 五、两种形态必须完全一致的五件事

差异一旦落在这五件上，**切换那天才会发现，而且症状不指向切换**：

1. **事务边界** —— 规矩一。
2. **错误码** —— 两边都用 `shop-base` 的 `ErrorCode`（这是上一份文档改掉
   「pay 自己定义一套」的直接理由）。
3. **幂等键的生成规则** —— 形态 B 下 HTTP 超时是常态，
   「超时但对方已执行」只能靠幂等收敛。形态 A 下几乎不会超时，
   所以**形态 A 的测试必须显式造重放**，否则这条永远测不到。
4. **权限判定** —— `@perm.can` / `@perm.canBiz` 两边同一套；
   形态 B 下 `LivePermResolver` 换成远程实现，但**契约不变**
   （解析不到返回 `null` → 回落会话快照）。
5. **金额与币种口径** —— 见 [多区域通道](./TDD-支付域-多区域通道.md)。

---

## L3 · 六、测试：一组用例跑两遍

```java
@ParameterizedTest
@ValueSource(strings = {"embedded", "remote"})
void 同一组资金链路在两种形态下行为一致(String mode) { ... }
```

三层：

1. **Port 契约测试** —— `LocalPayAdapter` 与 `RemotePayClient` 跑**同一组**断言。
   这是唯一能保证「换实现不换行为」的东西。
2. **装配测试** —— 两种形态各起一次 Spring 上下文，断言：
   - `embedded`：容器里有 `LocalPayAdapter`、有 pay 的控制器；
   - `remote`（主应用）：有 `RemotePayClient`、**没有**任何 `ai.neargo.pay.web` 的 bean。
     这条是 §二那个「空列表不是 404」的防线。
3. **负面对照** —— 断言形态 A 下**跨域事务写不出来**：
   在一个 `@Transactional` 里先写业务表再写 pay 表，然后抛异常，
   断言**业务表回滚了而 pay 表没有**。

   > 第 3 条最重要，也最反直觉：**它断言的是「我们做不到某件事」**。
   > 没有它，规矩一就只是一句话 —— 而某天有人给 pay 数据源加上 `@Primary`
   > 或共用事务管理器，测试全绿，形态 A 一切正常，
   > 直到切 B 的那天才发现代码里到处是跨域事务。

---

## L3 · 七、切换步骤与回滚

| 步 | 动作 | 验证 |
|---:|---|---|
| 0 | **会话外置**：`token-store` 切 `db`，按 operator → merchant → consumer 分三批 | 每批观察一天 |
| 1 | pay 建独立数据源（URL 仍指主库）+ 独立事务管理器 | §六 第 3 条负面对照转绿 |
| 2 | 迁移目录切到 `db/pay/`，历史表 `pay_flyway_history` | 平台自己的迁移仍在跑（对着进销存那个 A/B 验一次） |
| 3 | 建 `pay-svc` 产物，本地起起来，**不接流量** | AOT 产物断言 + jar 里无 `mybatis-*.jar` |
| 4 | nginx 把**一条只读路径**（`GET /biz/settle/batch`）切到 :8083 | 对比两个进程的返回是否逐字节一致 |
| 5 | 逐步切其余只读路径 | 同上 |
| 6 | 切写路径（放款、进件、回调） | 幂等重放测试 |
| 7 | 主应用去掉 pay-* 依赖，只留 `pay-api`，`shop.pay.mode=remote` | 装配测试第 2 条 |
| 8 | 切库：URL 指向 `ai_shop_pay`，数据迁移 | 独立账号连不上主库 |

**回滚**：第 3–6 步任何一步失败，nginx 把路径切回 :8081 即可 ——
主应用此时**还带着完整的 pay**，是个能立即接管的热备。
这正是「先切流量、后去依赖」这个顺序的理由：第 7 步之前，回滚是改一行 nginx。

第 8 步之后回滚要迁数据，**那是第一个不可轻易回退的步骤** —— 单独评审。

---

## L4 · 八、代价，如实说

1. **测试成本翻倍**：每条资金链路要跑两遍。这是双形态的**主要成本**，
   没有办法绕开 —— 绕开的方式就是只测一种，而那等于只有一种形态。
2. **Port 粒度必须按远程调用设计**。形态 A 下 `PayPort` 调 10 次几乎没有代价，
   切 B 就是 10 次 HTTP。**这会诱使人写 N+1**，而形态 A 下看不出来。
   规矩：Port 方法**批量入参**（`settleOf(Collection<String>)` 而不是单个），
   并加一条守卫：`PayPort` 的方法不许在循环体里被调用。
3. **形态 B 的部分失败**：HTTP 超时但对方已执行。只能靠幂等，
   所以 §五 第 3 条要在形态 A 的测试里显式造重放。
4. **两个进程的时钟**：同机部署时可忽略，跨机后账期截批的「今天」要以
   **pay 侧的时钟**为准，不能各算各的。

---

## L4 · 九、被否掉的备选

| 备选 | 为什么否 |
|---|---|
| pay 的持久层做两套（MyBatis + Data JDBC） | 没有生产流量的那一套会先腐烂。切换那天它不是「另一个实现」，是「一份没人跑过的代码」。见规矩二 |
| 形态 A 下 pay 复用平台数据源 | 跨域事务当场可写，规矩一失效，且切库时幂等表被留下、重放保护静默失效 |
| 用 `@Profile` 而不是 `@ConditionalOnProperty` | profile 是**部署形态**的表达，而这里要表达的是**一个功能的装配方式**。混用会让 `api,ops,pay` 这样的组合越来越难推理 —— [生产 profile 组合没测过](./TDD-支付域-架构与拆分路径.md) 那条教训就在这条线上 |
| 先拆库再拆进程 | 拆库不可轻易回滚（§七 第 8 步），而拆进程可以（改一行 nginx）。**先做可回滚的那件** |
| 主应用保留 pay-web 作为「热备」 | 听起来稳，实际是 §二 那个陷阱：nginx 少配一条就静默走到连不上 pay 库的那份，返回空列表而不是报错。热备靠的是**回滚流程**，不是留两份都活着的控制器 |

---

## L4 · 十、待确认

1. **`pay-svc` 与主应用同机还是分机**。同机解决「发布不打断」，不解决「机器挂了」。
   本文按同机写（与 job 一致）；分机的话 §八 第 4 条的时钟要单独处理。
2. **只读路径灰度的粒度**：按路径切（本文）还是按商家灰度？
   按商家更细，但要在 nginx 之上加一层路由，复杂度不小。
3. **`PayPort` 的方法清单** —— 它是形态 B 的性能上限，值得单独过一遍。

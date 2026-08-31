# TDD-基础包分层与支付双形态

> 状态：**方案待评审** · 创建 2026-08-31
> 修订：[ADR-021 §3.5](../ADR/ADR-021-支付域独立为服务与独立库.md)（「pay-* 不依赖 shop-base」的结论下重了，此处改）
> 上游：[TDD-支付域 · 架构与拆分路径](./TDD-支付域-架构与拆分路径.md) ·
> [TDD-统一鉴权与会话外置](../TDD-统一鉴权与会话外置.md)
> 图：[基础包分层与依赖](../diagrams/base-module-layering.svg) ·
> [方案二 · 支付独立服务架构](../diagrams/pay-standalone-architecture.svg)

---

## L1 · 定位

这份文档回答两个问题，它们其实是同一个：

1. **`shop-base` 凭什么把 MyBatis 强加给每一个依赖它的模块？** —— 不凭什么，是历史。
2. **支付域能不能既跑在主进程里、又能单独起一个进程？** —— 能，前提是第 1 条先解决。

结论先写：**`shop-base` 去掉持久层，持久层按方案分包**（`shop-store-mybatis` /
`shop-store-data-aot`）。这样 `job` 和 `pay` 都能依赖 `shop-base`，
而 `pay` 可以在**不改一行业务代码**的前提下有两种部署形态。

---

## L2 · 一、现状：14% 的代码绑架了 100% 的依赖

`shop-base` 共 159 个 Java 文件。按 import 分：

| 类别 | 文件数 | 占比 | 典型 |
|---|---:|---:|---|
| **零框架**（连 `org.springframework` 都没有） | 113 | 71% | `ErrorCode` `BizException` `ApiResult` `Perms` `BizPerms` · **`spi/` 全部 70 个 Port** |
| 绑 MyBatis | 22 | 14% | `BaseEntity` `AuditMetaObjectHandler` `*Mapper` · `event`/`idem`/`media`/`archive` 的实现 |
| 绑 web（servlet / spring-web） | 9 | 6% | 三端 `*TokenAuthFilter` · `ApiResponseWrapper` · `GlobalExceptionHandler` |
| 绑 security | 8 | 5% | `SecurityUtils` `PasswordHasher` `ApiAuthEntryPoint` |

> 数出来的，不是估的：
> ```bash
> find backend/shop-base/src/main/java -name '*.java' | xargs grep -L \
>   "org.springframework\|com.baomidou\|org.apache.ibatis\|jakarta\." | wc -l
> ```

而 `shop-base/pom.xml` 把 `mybatis-plus-spring-boot4-starter` 列为**编译依赖**。
于是「想用 `ErrorCode`」和「必须把 MyBatis 装进 classpath」被绑成了一件事。

[ADR-021 §3.5](../ADR/ADR-021-支付域独立为服务与独立库.md) 观察到了这个事实，
但从它推出的结论是 **「所以 `pay-*` 不依赖 `shop-base`」** ——
为了躲开那 14%，把 71% 的零框架代码也一起躲掉了。代价写在同一节里：

> 代价与 job 相同：`BizException` / `ErrorCode` 用不了，支付域自己定义一套。

**这个代价比它看上去大。** 错误码不是内部实现，它是**三端契约的一部分**：
`ops-web` 的 `lib/api` 按 `ErrorCode` 分段判「这是权限问题还是业务问题」，
b-app 的 `ApiError` 同理。支付域返回另一套码，前端就要写第二套解析 ——
而两套码迟早会在同一个数字上表示不同的意思，那时排查的人看到的是
「同一个 40301，一个说没权限、一个说通道拒绝」。

`shop-job` 已经付过这笔账：它的 `-api` 模块零依赖，代价是它**没有 web 层**，
所以还没撞上错误码这条。支付域有 web 层，一定会撞。

### 一个具体的例子：`PageData`

```java
public record PageData<T>(List<T> records, long total, long page, long size) {
    public static <T> PageData<T> of(IPage<T> p) { ... }   // ← 唯一的 MyBatis 依赖
    public static <T> PageData<T> of(List<T> records, long total, long page, long size) { ... }
}
```

一个纯粹的分页契约，**被一个静态工厂方法钉在了 MyBatis 上**。
全仓 32 处 `PageData.of(`，其中传 `IPage` 的只有个位数。

---

## L2 · 二、方案：内核瘦身 + 持久层按方案分包

### 2.1 模块划分

```
backend/
├── shop-base/              【瘦身】内核。零 MyBatis · 零 web · 零 security
│                             common 的纯净部分 · spi/ 全部 Port · 常量与工具
│                             + 横切件的 **SPI 接口**（幂等 / Outbox / 媒体 / 归档）
│                             依赖：仅 spring-context（为了 @Component 等注解）
│
├── shop-base-web/          【新】ApiResponseWrapper · GlobalExceptionHandler
│                             依赖：shop-base + spring-boot-starter-web
│
├── shop-base-auth/         【新】三端 filter · SecurityUtils · TokenStore · PermChecker
│                             依赖：shop-base-web + security + shop-auth-store
│
├── shop-store-mybatis/     【新】BaseEntity · AuditMetaObjectHandler · *Mapper
│                             + 幂等 / Outbox / 媒体 / 归档的 **MyBatis 实现**
│                             依赖：shop-base + mybatis-plus
│
├── shop-store-data-aot/    【新】同一批 SPI 的 **Spring Data JDBC + AOT 实现**
│                             依赖：shop-base + spring-boot-starter-data-jdbc
│
└── shop-app-base/          【新·聚合 pom，零代码】
                              = shop-base + -web + -auth + shop-store-mybatis
                              现有 6 个业务模块把 `shop-base` 换成它，**代码零改动**
```

**包名一个都不改。** `ai.neargo.shop.common.ApiResult` 还在原来的包里，
只是换了一个 jar。现有 6 个业务模块（core / merchant / settle / channel /
inventory / notify）与 `shop-app` 只改 pom 里的一行 `<artifactId>`。

### 2.2 为什么持久层要按方案分包，而不是一个包里两套实现

因为**「进没进 classpath」才是 AOT 的判据，不是「用没用到」**。
同一个 jar 里放两套实现，MyBatis 就还在依赖树上，AOT 收益照样打折，
而且——照 ADR-021 §3.5 的原话——**没有任何报错**。

分包之后这件事变成可断言的：`pay-svc` 的 fat jar 里**不存在 `mybatis-*.jar`**。

### 2.3 横切件怎么分

`shop-base` 里那 22 个 MyBatis 文件不是一堆散件，是**四个横切能力**的实现：

| 能力 | 接口（留 `shop-base`） | MyBatis 实现 | Data-AOT 实现 |
|---|---|---|---|
| 幂等 | `IdempotencyService` | `shop-store-mybatis` | `shop-store-data-aot` |
| 事件外发 | `OutboxEventBus` `OutboxDispatcher` | 同上 | 同上 |
| 媒体引用 | `MediaUsageService` `MediaStore` | 同上 | **暂不做** —— 支付域不传图 |
| 归档 | `ArchiveService` | 同上 | **暂不做** —— 支付域的归档另有口径 |

**先只做支付真正要用的两个**（幂等、Outbox）。媒体与归档在 `shop-store-data-aot`
里不提供实现，装配时缺 bean 就报错 —— 这比提供一个空实现好：
空实现的表现是「归档跑了、什么都没归」，而报错的表现是「装不起来」。

> `BaseEntity` 是 MyBatis 注解（`@TableId` / `@TableField` / `@TableLogic`）的载体，
> 全仓 144 个实体继承它，**它就该在 `shop-store-mybatis` 里**。
> `shop-store-data-aot` 那边不需要一个对应物：Spring Data JDBC 的审计走
> `@CreatedDate` / `@LastModifiedDate`，形状本来就不同，硬造一个共同父类
> 只会让两边都别扭。

---

## L2 · 三、支付双形态：同一份代码，两种装配

用户要求：**方案一（与核心业务同一个服务）功能保持完整的前提下，支持方案二（独立服务）。**
这正是 `shop-job` 没做到的 —— 它只有独立一种形态，所以本地起一个进程就要连两个库。

### 3.1 两种形态

| | 方案一 · 内嵌 | 方案二 · 独立 |
|---|---|---|
| 进程 | 与 `ai-shop.service` 同一个 | `ai-shop-pay.service`（:8083） |
| 库 | 主库 `ai_shop` | 独立库 `ai_shop_pay`（独立账号，只授权这一个 schema） |
| 持久层 | **两种形态相同：Spring Data JDBC**（见下方修订） | 同左 |
| 三端怎么到达 | 主应用直接装配 pay 的控制器 | nginx 按路径分流 / 主应用反代 |
| 域间调用 | `PayPort` → **本地实现** | `PayPort` → **HTTP 客户端** |
| 开关 | `shop.pay.mode=embedded`（默认） | `shop.pay.mode=remote` |

**默认必须是 `embedded`。** 记忆里那条「默认关闭的那一半没人测」在这里要反过来用：
默认值就是生产今天跑的形态，独立形态是显式打开的那一半，
所以**独立形态的装配必须有自己的集成测试**，不能只靠「上线那天试试」。

> **展开到可实施的版本在 [TDD-支付域 · 双形态部署与装配](./TDD-支付域-双形态部署与装配.md)** ——
> 那份文档修订了本节的一条：**pay 自己的持久层只有一套（Spring Data JDBC），
> 两种形态都用它**。两套 repository 实现里没有生产流量的那一套会先腐烂，
> 切换那天它不是「另一个实现」，是「一份没人跑过的代码」。
> `shop-store-mybatis` / `shop-store-data-aot` 的分包改为只管**横切件**（幂等 / Outbox）。

### 3.2 别的域怎么调支付 —— 只认 Port，不知道钱在哪跑

```
shop-core（下单）
   └─ 依赖 pay-api 的 PayPort（接口，零实现）
        ├─ embedded → LocalPayAdapter    直接调 pay-domain
        └─ remote   → RemotePayClient    HTTP → pay-svc /internal/**
```

`shop-core` 一行都不用改。切形态改的是**装哪个 bean**，不是改调用方。

> 这条是整个方案能成立的关键：**如果调用方需要知道支付在哪跑，
> 那就不是「两种形态」，而是「两套代码」** —— 而两套代码里一定有一套没人测。

### 3.3 方案二的三个必须先解决的问题

**① 会话在进程内存里 —— 阶段 3 的硬前置**

```
shop-app/src/main/resources/application.yml:125
    token-store: ${SHOP_TOKEN_STORE:memory}
```

默认 `memory`（进程内），线上没有覆盖它的地方。
`pay-svc` 一旦独立成进程，**三端的令牌它一个也验不了** ——
表现是支付相关接口全 401，其它接口正常。

出路已经建好：`shop-auth-store` 的 `DbTokenStore` + `shop.auth.token-store-by-realm.<realm>`
支持**分端切换**。`TokenStoreSelection` 的注释里写明了分批计划：
先运营端（十几个人，影响可控），观察一天，再 B 端，最后 C 端全量。

**这件事必须先做完并观察过，才谈得上拆进程。** 它顺带解决另一件事：
今天部署覆盖 jar 会把全部会话清空（全员掉线）。

**② 判权数据在主库，而权限不能快照**

`LivePermResolver` 存在的全部理由就是「不能用登录时的快照」，
否则会出现「菜单里有、点进去 403」。所以 ADR-021 §3.3 的
「只存业务键 + 快照列」这条规矩，**权限是它的例外**。

方案：`pay-svc` 装 `LivePermResolver` 的**远程实现** ——
`/internal/perm/resolve` 回查主应用，带整表快照缓存。

这个接口的契约**已经**为此留好了口子：

> 解析不到时返回 `null`，由 `PermChecker` 回落到会话快照 ——
> 宁可用旧权限，也不要因为解析器没装上而全员失权。

所以主应用挂掉时，pay-svc 不是「全员失权」而是「用旧权限」。
**这不是巧合，是当初写这条回落时就想到的形状。**

**③ 通道回调归谁**

`/callback/pay/channel/{channel}` 现在在 `shop-core`，`@Profile("api")`，
permitAll + 验签（`ChannelCallbackVerifier`）。它是**改账的入口**，
按 ADR-021 该跟着账走进 pay-svc。

但通道侧配的回调 URL 改一次要重新报备。**建议：路径不动，由 nginx 转发到 pay-svc。**
对通道来说 URL 没变，对我们来说入口已经在支付服务里。

---

## L3 · 四、三端接入：四条链，支付跨全部四条

| 链 | 路径 | 认证 | 判权 |
|---|---|---|---|
| 1 | `/biz/**` | `MerchantTokenAuthFilter` | `@perm.canBiz(BizPerms.X)` —— 按**当前门店**解析的角色 |
| 2 | `/mp/**` | `ConsumerTokenAuthFilter` | 无 RBAC，只有属主鉴权 |
| 3 | `/ops/**` | `OperatorTokenAuthFilter` | `@perm.can(Perms.X)` —— `LivePermResolver` **每次现算** |
| 4 | `/callback/**` `/common/**` | permitAll | 通道回调走**验签**，不是 token |

支付是**唯一四条链都要走**的域。今天的端点分布：

- **运营端** 13 组：`/ops/settlements` `/ops/split-records` `/ops/payables`
  `/ops/settle-batches` `/ops/settle/fee-rules` `/ops/settle/pay-channels`
  `/ops/payments/recon-*` `/ops/points/*` `/ops/debts/*` `/ops/finance/withdrawals` …
- **B 端** 9 个：`/biz/settle/{bills,batch,income,rate-card}` `/biz/merchant/debt`
  `/biz/merchant/payment`（进件）`/biz/store/{storeNo}/payment`
- **C 端** 1 个：`POST /mp/order/:orderNo/pay` —— **还是桩**

> C 端只有一个桩，是本方案里最需要说清楚的一件事：
> 方案二把支付拆出去之后，**C 端下单支付这条链会第一次真正跨服务**。
> 在它还是桩的时候拆，比在它上线之后拆便宜得多。

---

## L3 · 五、迁移步骤（每步单独可验、可停）

| 步 | 动作 | 闸门 |
|---:|---|---|
| 1 | 建 `shop-base-web` / `shop-base-auth`，把 17 个 web/security 文件搬过去 | 全量编译 + 现有测试全绿 |
| 2 | 建 `shop-store-mybatis`，把 22 个文件搬过去；`PageData.of(IPage)` → `MybatisPages.of` | 同上 |
| 3 | 建聚合 pom `shop-app-base`，6 个业务模块改一行依赖 | 同上；**ArchUnit：`shop-base` 不出现 `com.baomidou`** |
| 4 | `shop-job` 改依赖 `shop-base`（拿到统一错误码） | job 的 worker 测试全绿；**jar 里不含 mybatis** |
| 5 | 建 `shop-store-data-aot`（先只做幂等 + Outbox） | 新模块自己的测试 + **AOT 产物断言** |
| 6 | `pay-*` 依赖 `shop-base` + `shop-store-data-aot` | ArchUnit：`pay/**` 不依赖 `shop-app-base` |
| 7 | `shop.pay.mode` 双形态装配 | **两种形态各跑一遍同一组集成测试** |

**第 1–3 步是纯搬家**：包名不变、逻辑不变、6 个模块各改一行 pom。
可以独立提交、独立回滚，不与支付域的任何决定绑在一起。

---

## L4 · 六、守卫（防止悄悄漂回去）

1. **ArchUnit** —— `shop-base` 里不许出现 `com.baomidou` / `org.apache.ibatis` /
   `jakarta.servlet` / `org.springframework.security`。
2. **ArchUnit** —— `pay/**` 不许依赖 `shop-app-base` / `shop-store-mybatis`
   （放行 `shop-base` / `shop-base-web` / `shop-base-auth` / `shop-store-data-aot`）。
3. **jar 级断言** —— `pay-svc` 与 `shop-job` 的构建产物里**不存在 `mybatis-*.jar`**。

   > 第 3 条不是第 1 条的重复。**ArchUnit 看不见传递依赖** ——
   > 而 ADR-021 §3.5 担心的正是「进了 classpath 且没有报错」这件事，
   > 只有第 3 条真正测到它。

4. **双形态各跑一遍** —— `shop.pay.mode` 的两个值各有一组集成测试。
   只测默认值的话，独立形态就是「默认关闭的那一半」，上线才炸。

---

## L4 · 七、这个方案改了 ADR-021 的什么

| ADR-021 原文 | 改成 |
|---|---|
| §3.5「由此推出：`pay-*` 不依赖 `shop-base`」 | `pay-*` 不依赖 **`shop-app-base`（MyBatis 那一层）**，但**依赖 `shop-base` 内核** |
| §3.5「代价：`BizException` / `ErrorCode` 用不了，支付域自己定义一套」 | **这个代价消失了** —— 三端错误码保持一套 |
| §3.5「这也是 `pay-api` 必须零依赖单开一个模块的理由」 | `pay-api` 单开的理由回到原来那个：**它是对外契约**，别的域只依赖它 |
| 阶段表「ArchUnit：`pay/**` 不依赖 `shop-base`」 | 改成守卫第 2 条 |
| 阶段 3 部署形态 | **补方案一**：内嵌形态是默认值，不是过渡态 |

---

## L4 · 八、待确认

1. **会话外置切 db 的时机** —— 建议排在拆分之前，按 operator → merchant → consumer 分三批。
2. **`shop-store-data-aot` 的命名** —— 也可以叫 `shop-store-jdbc`。
   前者说的是「为什么用它」（AOT），后者说的是「它是什么」（JDBC）。
   本文用前者，因为选它的**唯一**理由就是 AOT。
3. **媒体与归档要不要 data-aot 实现** —— 本文的判断是不做（支付域用不到）。
4. **通道回调**：确认走 nginx 转发（路径不动，不重新报备）。

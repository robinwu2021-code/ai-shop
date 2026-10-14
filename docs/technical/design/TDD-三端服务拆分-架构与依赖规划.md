# TDD-三端服务拆分 · 架构与依赖规划

状态：**待确认** · ⚠️ **§4.2 中 `shop-settle` 的归属已被改写**

> **2026-08-30**：[ADR-021 支付域独立为服务与独立库](../ADR/ADR-021-支付域独立为服务与独立库.md)
> 决定支付域持有**自己的库与自己的产物**，因此 §4.2 里「`shop-settle` 属 kernel」
> 与「三端为不持数据的接入层」这一条对支付域不再成立。
> **本文其余部分继续有效**（三端接入层、§3.2 四条依赖规则、§5 令牌分池、§7 三件被打破的事），
> ADR-021 正是建立在它们之上 —— 尤其 §7.2 / §7.3 是那份 ADR 的 §5.2 / §5.3。
> 这条注记写在这里而不只写在 ADR 里：**两份文档打架时，谁都不会自己报错。**

关联决策：[ADR-016](../ADR/ADR-016-后端暂不拆构建产物-边界在数据不在jar.md)（其「暂不拆」结论已被业务决策推翻，
但 §3 实测数据与「第一刀切在数据」继续有效）· [ADR-001](../ADR/ADR-001-商家端形态与拆分时机.md)（其「C/B 共用令牌池」被本方案 §5 推翻）
关联清单：[迁移后-项目模块与数据归属清单](../reference/迁移后-项目模块与数据归属清单.md)（88 张表逐张归属）
关联架构：[TDD-backend](TDD-backend.md) §4（模块数 = 未来可能的部署单元数）
创建日期：2026-08-13 · 本版为收敛定稿，推导过程见 git 历史

---

## 1. 目标与边界

按 **C 端 / B 端 / 平台端三个独立服务** 规划架构，并给出从今天的模块化单体走过去的路线。

**「独立服务」的定义**（三条都要满足）：独立构建产物 · 独立发布 · 独立故障域。

**分期**：

| | 部署形态 | 目的 |
|---|---|---|
| **一期** | C+B+P 合一（`app-all`）＋ worker 独立 | 模块边界到位，worker 先独立 |
| **二期** | c / b / p / worker 四个服务 | 端各自发布 |

**三条定稿原则**：
① **二期兼容一期**——一期铺的每一块，二期都不用撬；
② 一期可为二期目标做优化，但**只做不返工的那些**；
③ **简洁优先，允许适度耦合**。

---

## 2. 实测依据

以下数字全部从当前主代码扫描得出（456 个 `.java`、88 张表、351 个端点），
不是估计。它们决定了后面每一个设计选择。

### 2.1 三端的重叠面

| | Controller | 端点 |
|---|---|---|
| C（`/mp/**`） | 10 | 93 |
| B（`/biz/**`） | 15 | 90 |
| 平台（`/ops/**`） | 27 | 166 |
| 公共（`/common` `/callback`） | 2 | 2 |

50 个领域服务里，**29 个（58%）被两端以上使用**；
其中三端共用 5 个，B+P 共用 8 个（商家治理天然双向：商家操作 ＋ 平台审核同一批数据）。

### 2.2 ★ 决定形态的一组：表被几端碰

被端链路覆盖到的 64 张表中：

| 归属 | 张数 |
|---|---|
| **三端共用** | **29（45%）** |
| 两端共用 | 18 |
| 单端独有 | 17 |

三端共用的 29 张包含全部核心表：`ord_order` `ord_sub_order` `prd_goods` `prd_sku`
`mch_entity` `mch_store` `mkt_coupon` `mkt_group_buy` `rvw_review` `stl_bill` `cmt_community`…

**结论：三端不能是持有数据的那一层。** 让 C 端服务拥有 `ord_order`，
就意味着平台端查跨商家订单要调 C 端服务——一个面向消费者的公网服务成了平台报表的上游，
故障域反而更差。

### 2.3 三个对拆分有利的事实

1. **域间零直接引用。** `shop-core` 九个域之间的直接 `import` 是 **0**，
   全部跨域调用走 `spi` 的 Port（AST 扫描与逐对 `grep` 两法互证）；`shop-core` 的 pom 只依赖 `shop-base`。
   → **一拆四不会撞循环依赖**，它今天已是干净的 DAG，只是装在一个 jar 里。
2. **Controller 已按端分子包**（各域 `api/{mp,biz,ops}`）→ 迁移以 `git mv` 为主。
3. **三进程部署已是现状**（`api`/`ops`/`worker` profile），拓扑不用发明。

### 2.4 三个必须先有答案的现状缺陷

1. **Outbox 投递没有在跑**：`OutboxDispatcher.dispatchPending()` 只有测试调，
   真实部署里 `sys_outbox` 永远 `PENDING`，**全站站内信一条发不出去**
   （详见[定时任务清单与调度方案](定时任务清单与调度方案.md) §1.2）。
2. **82 个测试文件全在 `shop-app`**（`scenario` 61 · `arch` 7 · `e2e` 6 · 其余 8），
   而 `shop-app` 要被拆散。708 个测试是本仓最值钱的资产。
3. **Flyway 迁移住在 `shop-app/resources/db/migration`**，同样随之拆散。

---

## 3. 目标架构

### 3.1 端接入层不持数据，域层持数据

```
  接入层 · 无业务表        c (/mp)      b (/biz)      p (/ops)
                             │            │             │
                             └────────────┼─────────────┘
                                          ▼
  领域层 · 持数据      trade  product  mkt  platform  merchant  settle
                                          │
                                          ▼
                              ai_shop 单库（S4 前不拆）
```

端接入层做**路由、认证、端专属编排与字段裁剪**；它天然满足「独立发布」——
改 C 端展示逻辑不碰任何域模块。

这个形态**没有背离「三个独立服务」**：三个端服务确实独立构建、独立发布、独立故障域，
只是数据的边界在域上，不在端上（依据见 §2.2）。

### 3.2 四条依赖规则

1. **端服务之间绝不互相调用**——C 端要商家信息就调 merchant 域，不调 b 服务。
   破了这条，三个端会重新耦合成一个环。
2. **数据只有 owner 写**——每张表有且只有一个域模块能写，其他读走接口。
3. **跨域调用走 Port 接口**——单体内本地调用，拆服务后换 RPC，**调用方一行不改**（今天已 100% 满足）。
4. **跨域一致性走 Outbox 事件，不做分布式事务**——写入侧已兑现，投递侧换 MQ 即可。

---

## 4. 一期落地方案：1 个项目 · 13 个模块 · 2 个产物

### 4.1 目录

```
backend/                         ← 仍是唯一的 Java 项目（不切两个项目）
├── pom.xml
├── kernel/                      ← 目录先分好，二期切项目就是加一个 pom
│   ├── shop-base                # ＋ spi（Port 接口不独立成模块）
│   ├── shop-channel             # 外部通道适配（带第三方 SDK，不并入 base）
│   ├── shop-domain-trade
│   ├── shop-domain-product
│   ├── shop-domain-mkt
│   ├── shop-domain-platform
│   ├── shop-merchant            # ＋ community ＋ fulfillment
│   └── shop-settle
└── service/
    ├── shop-portal-c            # /mp ＋ 支付回调 ＋ consumerChain
    ├── shop-portal-b            # /biz ＋ merchantChain ＋ BizContextFilter
    ├── shop-portal-p            # /ops ＋ operatorChain
    ├── shop-app-all             # 一期主服务：三 portal ＋ publicChain
    │                            #   ＋ Flyway 迁移 ＋ 全部 82 个测试
    └── shop-app-worker          # 独立部署：jobs（含新增 Outbox 投递）＋ 装配
```

### 4.2 kernel 模块清单（8 个，二期零改动）

| 模块 | 文件 | 职责 | 表 |
|---|---|---|---|
| `shop-base` | 78 | 横切地基：`Realm`/`TokenStore`/`LoginUser`/`Perms`/`PasswordHasher`、幂等、Outbox 写入侧、**跨域 Port 接口**。零业务依赖 | 2 |
| `shop-channel` | 10 | 外部通道适配器（支付/短信/存储）。只装适配器，不装业务判断 | 0 |
| `shop-domain-trade` | 35 | 购物车、下单拆单、订单、售后、状态机 | 6 |
| `shop-domain-product` | 38 | 商品、SKU、类目、库存与锁定、门店商品、**评价** | 11 |
| `shop-domain-mkt` | 39 | 券、拼团、活动、报价/求团、归因 | 12 |
| `shop-domain-platform` | 86 | 权限配置、平台参数、行业与地区主数据、内容治理、站内信、用户账号与地址 | 26 |
| `shop-merchant` | 79 | 商家主体、门店、员工与角色、资质与准入、保证金、社区与自提点、履约核销 | 22 |
| `shop-settle` | 31 | 结算单、分账、支付流水、对账、发票、积分 | 9 |

**`shop-core` 消失**——它今天 250 个文件、9 个域包，是「一依赖就全背上」的根源。

`shop-domain-platform`（86 文件）是最大的一块，装着 platform＋user＋content＋message 四个
彼此无关的包。**一期不拆**（避免模块数膨胀），`user`（将来 `user-svc` 的种子）
与 `message`（S2 接 MQ 后会长大）是两个天然裂点，标注待观察。

> 两处归属与直觉相反但符合代码现状：`rvw_*`（评价）在 **product** 不在 mkt（评价挂商品上）；
> `mch_entity_apply`（入驻申请）在 **platform** 不在 merchant（申请发生在成为商家之前）。
> 逐张归属见[清单 §5](../reference/迁移后-项目模块与数据归属清单.md#5-数据归属88-张表--9-个模块)。

### 4.3 service 模块清单（5 个）

| 模块 | 文件 | API 前缀 | 端点 | 认证 |
|---|---|---|---|---|
| `shop-portal-c` | 11 | `/mp/**` `/callback/**` | 94 | `consumerChain` · `ctk_` |
| `shop-portal-b` | 15 | `/biz/**` | 90 | `merchantChain` · `btk_`（新） |
| `shop-portal-p` | 27 | `/ops/**` | 166 | `operatorChain` · `otk_` |
| `shop-app-all` | 8＋ | `/common` `/actuator` `/uploads` | 1 | `publicChain` |
| `shop-app-worker` | 5 | — | 0 | — |

### 4.4 相比「完全铺开」砍掉的五处

| 砍掉 | 一期怎么办 | 二期为什么不用撬 |
|---|---|---|
| `shop-spi` 独立模块 | Port 留在 `shop-base` | 要独立随时抽，不影响服务边界 |
| `shop-jobs` 独立模块 | 并入 `shop-app-worker` | worker 一期二期都是同一个服务，**永远不会拆** |
| `shop-portal-common` | `publicChain` 装在 `app-all` | 二期抽出时 `app-all` 正好退役 |
| `shop-migration` | 迁移留 `app-all`（今天就这样） | 同上 |
| `shop-test-e2e` | 82 个测试留 `app-all` | 同上 |
| 切 2 个 Maven 项目 | 一个聚合 pom，**目录已按项目分好** | 拿走目录 ＋ 加 pom |

后三项是同一个道理：**它们都挂在 `app-all` 上，而 `app-all` 二期本来就要退役。**
挂在一个注定要拆掉的东西上，不构成技术债——这正是原则③该用的地方。

**一期唯一为二期多做的是 `portal-c/b/p`**：对一期部署零影响（都装在 `app-all` 里），
但它是二期拆服务的前提，且做了不返工（原则②）。

### 4.5 一期的一个回退，及缓解

今天 `api` 与 `ops` 是**两个进程**（平台面不上公网）；合一部署后 `/ops` 与公网流量同进程。

**两道缓解都要**：① `@Profile` 机制保留——同一个 `app-all` 产物仍可按 profile 只开某一面，
部署自由度不丢；② 反代层 `/ops/**` 只对内网放行。

---

## 5. 三端认证各自独立（推翻 ADR-001 的共用令牌池）

**现状**：`Realm` 只有两个（`CONSUMER`/`OPERATOR`），`ctk_` 被 C 与 B 共用，
`consumerChain` 一条链同时匹配 `/mp/**` 与 `/biz/**`，B 端身份靠 `BizContextFilter`
在 C 端令牌之上再解析一层。

ADR-001 共用的理由是「一期商家专区**内嵌 C 端小程序**」——**该前提已随 ADR-014 作废**
（商家端已是独立的 `b-app`）。代码里也写下了同向判断
（`/biz/auth/otp/send` 注释：「这两个端的鉴权链、限流策略**将来会分开**」）。

**改为三个 realm**：

| Realm | 前缀 | 链 | 登录入口 |
|---|---|---|---|
| `CONSUMER` | `ctk_` | `/mp/**` | `/mp/user/login` |
| `MERCHANT` | `btk_` | `/biz/**` | `/biz/auth/login`（老板）· `/biz/auth/staff-login`（店员） |
| `OPERATOR` | `otk_` | `/ops/**` | `/ops/auth/login` |

**四条后果**：

1. 一个人两个身份 = 两个令牌。**`b-app` 不能再复用 `c-app` 的登录态。**
2. `/biz/auth/login` 仍可用 C 端账号**校验身份**（老板从 C 端发起入驻），
   但**签发 `btk_`**——校验源与令牌池是两件事，分开后才能各自演进。
3. `BizContextFilter` 从「在 C 端令牌上再解析一层」变成 **B 端链的组成部分**；
   `BizIdentityResolver.resolve(userNo)` 签名不变。
4. **换 Redis 的理由随之改变**：不再是「C/B 共用池要跨进程」，而是
   「**同一端多实例**要共享会话」（`api` 今天就多实例）。
   → 它与端拆分**解耦**，按各端实例数定优先级，不必等拆分。

> 副作用是好的：三端的**会话时长、限流、踢人、密码策略**从此可以不同。
> 商家（长在线、多设备）与消费者的诉求本就不一样。

**模块表达**：认证随端下沉到 portal——每个 portal 自带自己那条链的 `@Configuration`。
于是「二期 `app-c` 只依赖 `portal-c`」天然意味着「它只有 `consumerChain`」，
**不需要任何开关或 profile 判断。装配即隔离。**

---

## 6. 二期与更远

### 6.1 二期：只动 `service/`，`kernel/` 一行不改

| 动作 | 内容 |
|---|---|
| 新增 3 个启动模块 | `shop-app-c` / `-b` / `-p`，各挑一个 portal ＋ common |
| 从 `app-all` 抽出 3 块 | `shop-portal-common` · `shop-migration` · `shop-test-e2e` |
| `app-all` 退役 | — |
| 切成 2 个项目 | `kernel/` `service/` 各加一个 pom |
| **此时才做** | `TokenStore` 换 Redis · 网关上线 |

**「二期兼容一期」的验收标准：二期不动 `kernel/` 的任何一个模块。**

### 6.2 Spring Gateway：二期上，只做四件事

| 用它做什么 | 结论 |
|---|---|
| 路由（`/mp`→c、`/biz`→b、`/ops`→p 仍限内网） | ✅ 本职，与今天三条链同口径 |
| **认证 authN**（令牌有效性 ＋ 是谁） | ✅ **可以统一上收**。前提三条：令牌先进 Redis；身份用**签名头**下传 `userNo/realm/dataScope`，服务只认签名头、**拒绝裸流量**；它与 §7.2 的调用上下文透传是**同一件工程** |
| **鉴权 authZ**（能不能干这件事） | ❌ **永远下沉**。权限码 ＋ DataScope 绑着业务语义（68 个码、角色矩阵、商家数据域），搬进网关等于抄半个权限系统，`LivePermResolver` 的「改权限即时生效」也断了 |
| 响应聚合（BFF） | ❌ 编排是端服务的职责。塞进网关，它会长成一个没有测试的隐形端服务 |
| 限流 / 灰度 / 统一域名 | ✅ 真价值，但要等拆出多个公网服务才有对象 |

**一期不上**：公网只有 `app-all` 一个 HTTP 服务，nginx 一条 location 就是路由；
唯一的网关类需求（`/ops` 限内网）是反代一条 ACL 的事。

### 6.3 再往后

| 阶段 | 内容 | 前置 |
|---|---|---|
| S2 | 调用上下文透传 ＋ Outbox 投递侧接 MQ | Outbox 投递任务真的在跑（S1a） |
| S3 | 按变更节奏切域服务：`settle` → `merchant` → `trade` | S2 |
| S4 | 数据库按域拆 | S3 |

一个库多个服务是**有意的过渡态**，比「先拆库再拆服务」安全得多。

---

## 7. 拆分会打破的三件事

这三件今天靠「同一个进程」成立，二期拆开就不成立了。**缝都已留好**。

### 7.1 令牌存储：进程内 → 共享

`EhcacheTokenStore` 是**进程内**存储；`api` 今天就多实例，两个实例各存各的，
用户被 LB 换一个实例就掉登录。

**缝**：`TokenStore` 是接口，`RedisTokenStore` **已经存在**——换实现是配置不是重写。
优先级按各端实例数定（见 §5 后果 4），不必等端拆分。

### 7.2 行级数据域：线程上下文 → 显式传递

DataScope 是 MyBatis 拦截器（商家只能看自己的数据），主体今天靠
`SecurityUtils.currentUserNo()` 从 ThreadLocal 取——**跨进程就断了**。

需要一个显式的调用上下文（主体 ＋ 数据域）随 RPC 传递。
**这是拆分里最容易漏、且漏了会越权的一处。**

### 7.3 跨域事务：一个事务 → Outbox ＋ 幂等

下单今天在一个事务里跨 trade / product（锁库存）/ settle，拆开后要换 Outbox 事件
＋ 消费者幂等（`msg_message.dedup_key` 的做法已在用）。

**前置条件**：Outbox 投递任务今天**根本没在跑**（§2.4）——
拆分之前必须先把这条链路真的跑起来，否则第一个跨服务写就丢事件。

---

## 8. 执行计划

三段，**各自可发布、可暂停**。

### S1a — 域模块化 ＋ worker 独立（一期真收益）

- [ ] T1 Controller 从各域 `api/{mp,biz,ops}` 迁出（**先于**域拆分，否则同一批文件动两次）
- [ ] T2 `shop-core` 一拆四；`community`、`fulfillment` 归位 `shop-merchant`
- [ ] T3 `shop-app-worker`：迁 2 个既有 Job ＋ **补上 Outbox 投递任务**
- [x] T4 清理仓库根的孤儿目录 `shop-svc-fulfillment/` —— **已完成**（2026-08-14）。
      订正本条的描述：不是「6 个文件与 core 重复」，**4 个与 core 不同**，
      每处都是 core 更靠前；而 `PickupOrderVO` 里有两行 core 已丢失的隐私取舍注释，
      删前捞回了。加了 `OrphanModuleTest` 防它再长出来

**兑现**：worker 独立部署 · 打断「一依赖全背上」· **站内信终于发得出去**。

### S1b — 三端认证独立

- [ ] T5 `Realm.MERCHANT` ＋ `btk_` ＋ `MerchantTokenAuthFilter` ＋ `merchantChain`
      · 动手前先评估 `neargo-auth-core` 的 `TokenIssuer`/`RevokeService`，别另造一套
- [ ] T6 `BizContextFilter` 挂到 B 端链；`b-app` 登录态改造

**兑现**：B 端会话/限流策略可独立于 C 端。

### S1c — portal 拆出 ＋ 目录归位（为二期铺路，可延后）

- [ ] T7 `shop-portal-c/b/p` 拆出，各带自己的 `SecurityFilterChain`
- [ ] T8 目录归位 `kernel/` `service/`，聚合 pom 收敛
- [ ] T9 六条依赖规则落进**已有的** `ArchitectureTest`（它已有 13 条断言，不新建类）

**硬约束**：每段结束 **708 个测试全绿**。

---

## 9. 守卫

拆分最大的风险是**「拆了但没真拆」**：产物分开了、依赖还缠在一起，而没有任何东西报错。

| 规则 | 落点 |
|---|---|
| 1. `portal-*` 之间互不依赖 | ArchUnit ＋ 依赖树 |
| 2. 域 ↔ 域只经 Port（**今天已 100% 满足**） | ArchUnit |
| 3. 任务代码不依赖任何 portal | ArchUnit |
| 4. 域模块反向不得依赖 portal / 启动模块 | ArchUnit；二期切项目后升级为**编译期强制** |
| 5. `@RestController` 只在 `portal-*` 与 `app-all` | ArchUnit |
| 6. `SecurityFilterChain` 只在 `portal-*` 与 `app-all`，**一个 portal 一条链** | ArchUnit（三端认证独立的守卫） |
| 二期 | `app-c` 依赖树不出现 `portal-p`；`app-worker` 无 HTTP |
| 运行期 | `sys_outbox` 里 `PENDING` 超 N 分钟的行数为 0（指标，不是测试） |

规则 5、6 的「与 `app-all`」是一期例外，二期随 `portal-common` 抽出后自动消失。

> [TDD-backend](TDD-backend.md) §4 规划过 `module-graph.py`（原文：判断「能不能拆」的
> **唯一客观依据**）与 `split-readiness.py`，**至今未建**。本方案所有数字都是临时写脚本量的——
> 拆分期间这种度量应该常驻，否则每次判断口径都可能不一致。建议随 S1a 一起补。

---

## 10. 风险与不做的

| 风险 | 处置 |
|---|---|
| 一期 `/ops` 与公网同进程 | `@Profile` 保留 ＋ 反代 ACL（§4.5） |
| 82 个测试随 `shop-app` 拆散 | 一期全部留 `app-all`；二期随其退役抽出 `shop-test-e2e` |
| 二期四服务共库，谁跑迁移 | 二期建 `shop-migration`，服务侧 `flyway.enabled=false`，流水线单独跑一次 |
| `domain-platform` 是杂物袋（86 文件） | 一期不拆，标注 `user`/`message` 为待观察裂点 |
| L0 公共库重复造轮子 | `neargo-common-mq`（`DomainEventPublisher`）与 `neargo-auth-core`（`TokenIssuer`）已存在，S2/S1b 动手前先评估。**引或不引都行，但要写下理由** |

**一期明确不做**：网关 · `TokenStore` 换 Redis（与拆分解耦，按实例数定）· 拆库 · 域服务化 · MQ。

---

## 11. 现况对照（免得把目标态当成现况）

| | 现况 | 一期后 |
|---|---|---|
| 后端项目数 | 1（`backend/` = `shop-parent`） | 1（目录已按 kernel/service 分） |
| 后端模块数 | **6**：`base` `core`(250 文件/9 域) `merchant` `settle` `channel` `app` | **13** |
| 部署产物 | 1 个 jar / 3 种 profile | **2 个 jar** |
| 认证 realm | **2**（`ctk_` 被 C/B 共用） | **3** |
| Outbox 投递 | ❌ 无人调用 | ✅ `app-worker` 里的定时任务 |
| Controller 位置 | 各域 `api/{mp,biz,ops}` 子包 | `shop-portal-*` |
| 数据库 | 一个 `ai_shop` | 一个（S4 前不拆） |

**代码零改动**——本方案至今只有文档。

---

确认记录：待确认

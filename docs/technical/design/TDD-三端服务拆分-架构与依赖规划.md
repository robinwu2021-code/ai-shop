# TDD-三端服务拆分 · 架构与服务依赖规划

状态：**待确认**
关联决策：[ADR-016](../ADR/ADR-016-后端暂不拆构建产物-边界在数据不在jar.md)（本方案是它的执行路线：
ADR-016 的结论是「暂不拆，且第一刀切在数据」，本方案给出**怎么切**）
关联架构：[TDD-backend](TDD-backend.md) §4 · ADR-017 双形态（拆分 = 改依赖列表）
创建日期：2026-08-13

---

## 1. 目标

按 **C 端 / B 端 / 平台端三个独立服务** 规划架构与服务依赖，
并给出从今天的模块化单体走过去的路线。

「独立服务」在本方案里的定义（三条都要满足，缺一条就不算）：

1. **独立构建产物**：各自的 jar 与流水线
2. **独立发布**：改 C 端不需要 B 端和平台端跟着发版
3. **独立故障域**：一个挂了，另外两个照常服务

---

## 2. 实测：今天的耦合在哪

三组数字，都是从代码量出来的（不是估计）：

### 2.1 Controller 分布

| 端 | Controller 数 |
|---|---|
| C（`/mp/**`） | 10 |
| B（`/biz/**`） | 15 |
| 平台（`/ops/**`） | 27 |

### 2.2 领域服务被几端使用（共 50 个）

| 归属 | 数量 | 说明 |
|---|---|---|
| 只有 C | 8 | 购物车、地址、收藏、归因… |
| 只有 B | 6 | 门店管理、核销、商家角色… |
| 只有平台 | 15 | 权限配置、对账、内容治理、看板… |
| **C+B** | 6 | 认证、用户、社区、商家、积分、门店码 |
| **C+平台** | 2 | 券、消息 |
| **B+平台** | 8 | 准入、活动、商品、订单、结算、员工… |
| **三端共用** | 5 | 售后、类目、拼团、评价、OpsService |

**29 个服务（58%）被两端以上使用。**

### 2.3 ★ 决定性的一组：表被几端碰（共 64 张）

| 归属 | 数量 |
|---|---|
| **三端共用** | **29（45%）** |
| C+B | 5 |
| C+平台 | 5 |
| B+平台 | 8 |
| 只有 C | 5 |
| 只有 B | 1 |
| 只有平台 | 11 |

三端共用的 29 张里包含**全部核心表**：

```
ord_order  ord_sub_order  ord_item  ord_after_sale  ord_status_log
prd_goods  prd_sku  prd_category   mch_entity  mch_store
mkt_coupon  mkt_group_buy  rvw_review  stl_bill  cmt_community …
```

**这一组数字决定了架构形态**：`ord_order` 同时被 C 端（我的订单）、
B 端（商家接单）、平台端（跨商家订单管理）读写。
三个端服务不可能各自拥有它——**所以「三端」不能是持有数据的那一层**。

---

## 3. 目标架构

### 3.1 形态：端服务是接入层，域服务持数据

```mermaid
flowchart TB
  subgraph 接入层["接入层 · 三个独立服务（无业务表）"]
    CS["c-svc · /mp/**<br/>公网 · 多实例"]
    BS["b-svc · /biz/**<br/>公网 · 多实例"]
    PS["p-svc · /ops/**<br/>内网 · 1–2 实例"]
  end
  subgraph 域层["领域层 · 持有数据，按域拆"]
    TRADE["trade-svc<br/>订单/购物车/售后"]
    PROD["product-svc<br/>商品/类目/库存"]
    MCH["merchant-svc<br/>商家/门店/准入"]
    STL["settle-svc<br/>结算/支付/对账"]
    MKT["mkt-svc<br/>券/拼团/活动/归因"]
    PLAT["platform-svc<br/>权限/配置/内容/看板"]
  end
  subgraph 公共["横切（库或独立服务）"]
    AUTH["认证与令牌<br/>ctk_ / otk_ 两个 realm"]
    OUTBOX["事件总线<br/>Outbox → MQ"]
  end
  CS --> TRADE & PROD & MKT & MCH
  BS --> TRADE & PROD & MCH & STL
  PS --> TRADE & PROD & MCH & STL & MKT & PLAT
  CS & BS & PS --> AUTH
  TRADE & STL & MKT -.事件.-> OUTBOX
```

**为什么端服务不持数据**：见 §2.3。45% 的表被三端同时碰，
让 C 端服务拥有 `ord_order` 就意味着平台端查跨商家订单要调 C 端服务——
一个面向消费者的公网服务成了平台报表的上游，故障域反而更差了。

**端服务做什么**：路由、鉴权、端专属编排与裁剪（VO 按端裁字段）、端专属缓存。
它们**天然满足「独立发布」**——改 C 端的展示逻辑不碰任何域服务。

**这个形态没有背离「三个独立服务」**：三个端服务确实是独立构建、独立发布、
独立故障域的。只是数据的边界不在端上，而在域上。

### 3.2 依赖规则（四条，是这套架构能立住的原因）

1. **端服务之间绝不互相调用。** C 端要商家信息就调 `merchant-svc`，
   不调 `b-svc`。破了这条，三个端就重新耦合成一个环。
2. **数据只有 owner 写。** 每张表有且只有一个域服务能写；
   其他服务读走接口，不直连别人的表。
3. **跨域调用走 `shop-spi` 的 Port 接口。** 单体内是本地调用，
   拆开后换 RPC 实现，**调用方一行不改**——这条今天已经在执行。
4. **跨域一致性走 Outbox 事件，不做分布式事务。**
   写入侧「业务与事件同事务落库」今天已经兑现，投递侧换 MQ 即可。

### 3.3 数据归属（64 张表 → 6 个域服务）

| 域服务 | 拥有的表（前缀） | 张数 |
|---|---|---|
| `trade-svc` | `ord_*` `trd_*` | 6 |
| `product-svc` | `prd_*` | 5 |
| `merchant-svc` | `mch_*` `cmt_*` `ful_*` | 12 |
| `settle-svc` | `stl_*` `pts_*` | 8 |
| `mkt-svc` | `mkt_*` `rvw_*` | 14 |
| `platform-svc` | `sys_*` `cnt_*` `msg_*` `usr_*` | 19 |

`usr_*`（账号/身份/地址/收藏）暂归 `platform-svc` 是**权宜**：
它其实该有自己的 `user-svc`，但一期用户域改动少，先不多拆一个部署单元。
包边界保留，将来随时可拆（沿用 TDD-backend §4 「模块数 = 未来可能的部署单元数」）。

---

### 3.4 一期形态：C+B+P 合一部署，worker 独立（2026-08-13 确认）

一期**不按端拆部署**：C+B+P 是一个大服务，worker 独立部署——两个构建产物。
但**模块与项目现在就按目标形态拆开**，让二期的 c/b/p 拆分收缩成
「新增一个启动模块、挑一份依赖列表」（ADR-017：拆分 = 改依赖列表）。

```mermaid
flowchart TB
  ALL["shop-app-all · 一期主服务<br/>/mp + /biz + /ops 一个进程"]
  WK["shop-app-worker · 独立部署<br/>无 HTTP"]
  subgraph 接入模块["端接入模块（Maven jar）"]
    PC["portal-c<br/>/mp"] ~~~ PB["portal-b<br/>/biz"] ~~~ PP["portal-p<br/>/ops"] ~~~ JB["shop-jobs<br/>@Scheduled+Outbox"]
  end
  subgraph 域模块["域模块（shop-core 一拆四 + 已独立的两个）"]
    direction LR
    M1["trade"] ~~~ M2["product"] ~~~ M3["mkt"] ~~~ M4["platform"] ~~~ M5["merchant"] ~~~ M6["settle"]
  end
  DB[("ai_shop 单库")]
  ALL --> PC & PB & PP
  WK --> JB
  接入模块 --> 域模块 --> DB
```

⚠️ **一个要点名的回退**：今天 api 与 ops 是**两个进程**（平台面不上公网）；
合一部署后 `/ops` 与公网流量同进程。缓解（两道都要）：
① `@Profile` 机制**保留**——同一个 all 产物仍可按 profile 只开某一面，部署自由度不丢；
② 反代层 `/ops/**` 只对内网放行。

#### 3.4.1 模块与项目拆分方案

现状对拆分友好：Controller 已按 `api/{mp,biz,ops}` 子包组织在各域内
（core 7/6/16 · merchant 0/1/6 · settle 1/2/4，另有 4 个特例），迁移是机械的。

**目标模块树**（模块数 = 未来可能的部署单元数，原则不变）：

```
backend/
├── shop-base                      # 地基，不动
├── shop-channel                   # 通道适配，不动（BizUploadController 迁出）
│
├── 域模块 —— shop-core 一拆四 + 归位
│   ├── shop-domain-trade          # trade 包
│   ├── shop-domain-product        # product 包
│   ├── shop-domain-mkt            # marketing 包
│   ├── shop-domain-platform       # platform + user + content + message 包
│   ├── shop-merchant              # 已独立；community、fulfillment 从 core 迁入（§3.3 归属）
│   └── shop-settle                # 已独立
│
├── 端接入模块 —— Controller 从域内 api/{mp,biz,ops} 迁出
│   ├── shop-portal-c              # 全部 api/mp + portal/mp + PayCallbackController（公网回调）
│   ├── shop-portal-b              # 全部 api/biz + portal/biz + BizUploadController
│   └── shop-portal-p              # 全部 api/ops + OpsPermConfigController
│   （CommonMetaController 三端共用 → 留 shop-domain-platform，是唯一例外）
│
├── shop-jobs                      # 全部 @Scheduled + Outbox 投递任务（补上缺的那个）
│
└── 启动模块 —— 只有 pom + 装配，零业务代码
    ├── shop-app-all               # 一期：portal-c/b/p + config
    └── shop-app-worker            # 一期：jobs
    （二期：shop-app-c / -b / -p —— 各自一个新 pom，代码零改动）
```

**依赖规则（五条，ArchUnit 落地）**：

1. `portal-*` → 域模块，只经 Service 接口；**portal 之间互不依赖**
2. 域 ↔ 域只经 `shop-spi` 的 Port（现状已如此，升格为守卫）
3. `shop-jobs` → 域模块；**不依赖任何 portal**
4. 启动模块只有 pom 与装配；**域模块反向不得依赖 portal / jobs / 启动**
5. `@RestController` 只出现在 `shop-portal-*`；`@Scheduled` 只出现在 `shop-jobs`

### 3.5 Spring Gateway 聚合的评估

| 用它做什么 | 结论 |
|---|---|
| 统一入口 / 按前缀路由 | ✅ 本职。`/mp`→c-svc、`/biz`→b-svc、`/ops`→p-svc（仍限内网），与今天三条 SecurityFilterChain 同口径。**但一期不需要**：HTTP 服务只有一个，nginx 一条 location 就是路由 |
| **认证 authN**（令牌有效性 + 是谁） | ✅ **可以统一到网关**（2026-08-13 确认）。前提三条：① 令牌先进 Redis（S1-next 已排，网关才查得到）；② 身份用**签名头**下传 `userNo/realm/dataScope`，服务只认带签名的身份头、拒绝裸流量——否则内网被穿一层全体裸奔；③ 它与 §4.2 的「调用上下文透传」是**同一件工程**，网关→服务与服务→服务用同一个上下文格式 |
| **鉴权 authZ**（能不能干这件事） | ❌ 留在各服务。`@PreAuthorize` 权限码 + DataScope 绑着业务语义（68 个码、角色矩阵、商家数据域），搬进网关等于把半个权限系统抄过去，`LivePermResolver` 的「改权限即时生效」也断了 |
| 响应聚合（BFF） | ❌ **编排是端服务自己的职责**（§3.1）。把聚合塞进网关，网关会长成一个没有测试的隐形端服务 |
| 限流 / 灰度 / 统一域名 | ✅ 真价值——但要等拆出多个公网服务之后才有对象 |

**结论：网关是 S1-next（按端拆开）那一步的配套，终态职责四样：路由、认证、限流、灰度。**
认证与鉴权在这里是生死线：**认证可以上收，鉴权永远下沉**。
一期不上——给一个服务配网关，多一个必须活着的进程，换不回任何东西。

## 4. 拆分会打破的三件事（必须先有答案）

这三件今天靠「同一个进程」成立，拆开就不成立了。

### 4.1 令牌存储：进程内 → 共享

C 端与 B 端**共用 `ctk_` 令牌池**（[ADR-001](../ADR/ADR-001-商家端形态与拆分时机.md)：
共用令牌池、分开前缀，拆端时后端零改动）。
今天令牌存在 `EhcacheTokenStore`（**进程内**，磁盘持久化）。

`c-svc` 与 `b-svc` 一拆成两个进程，B 端签发的令牌 C 端就不认识了。

**好消息：缝已经留好了。** `TokenStore` 是接口，`RedisTokenStore` 已经存在。
换实现是配置，不是重写。

### 4.2 行级数据域：MyBatis 拦截器 → 每服务各自装配

DataScope 是 MyBatis 拦截器（商家只能看自己的数据）。
拆开后每个域服务各自装配它，而**主体身份要随调用传过去**——
今天靠 `SecurityUtils.currentUserNo()` 从线程上下文取，跨进程就断了。

需要一个显式的调用上下文（主体 + 数据域）随 RPC 传递。
这是拆分里最容易漏、且漏了会**越权**的一处。

### 4.3 跨域事务：一个事务 → Outbox + 幂等

下单今天在一个事务里跨 trade / product（锁库存）/ settle。
拆开后要改成 Outbox 事件 + 消费者幂等（`msg_message.dedup_key` 的做法已经在用）。

**前置条件**：Outbox 投递任务今天**根本没有在跑**
（见 [定时任务清单与调度方案](定时任务清单与调度方案.md) §1.2，
`dispatchPending()` 只有测试在调）。
拆分之前必须先把这条链路真的跑起来——否则第一个跨服务写就丢事件。

---

## 5. 分阶段路线

每一阶段都可独立上线，且上线后系统是完整可用的。

### S1 — 模块拆分 + 两产物部署（一期形态，§3.4）

四步，每步结束时全量测试绿：

1. `shop-core` 一拆四（`git mv` + pom，纯移动）；community、fulfillment 迁入 `shop-merchant`
2. Controller 从域内 `api/{mp,biz,ops}` 迁出到 `shop-portal-c/b/p`
3. 新建 `shop-jobs`：迁两个既有 Job，**并补上 Outbox 投递任务**
   （正好是[定时任务方案](定时任务清单与调度方案.md)的 J1——同一次动手）
4. 启动模块收敛：`shop-app-all`（一期主服务）+ `shop-app-worker`；
   `worker` profile 退役，`api`/`ops` profile **保留**作 all 产物的运行时第二道锁

**拿到**：worker 独立发布与故障域；所有模块边界从「包约定」升级为**构建期事实**；
令牌存储不用动（C/B/P 仍同进程，Ehcache 成立）。
**没拿到**：端与端仍绑在一起发版——那是二期。

### S1-next — 二期按端拆开（触发条件到了再做）

- 新增 `shop-app-c` / `-b` / `-p` 三个启动 pom，**代码零改动**；
  **此时才做**：令牌换 `RedisTokenStore`（§4.1）、网关上线（§3.5）
- 触发条件：B 端独立小程序上线（ADR-001 二期）、或各端发版节奏互相拖累

### S2 — 把事件链路跑起来（拆域的前置）

- 补 Outbox 投递定时任务（今天缺）
- 投递侧从进程内分发换成 MQ
- 建立调用上下文透传（§4.2）

**这一步不产生新服务**，但没有它，S3 的第一个跨服务写就会丢数据。

### S3 — 按价值切域服务

顺序按「变更节奏 + 外部依赖」，不按代码量：

1. `settle-svc`（已独立成工程；外部依赖最重：支付通道、分账）
2. `merchant-svc`（已独立成工程；变更节奏与核心域不同）
3. `trade-svc`（最核心，最后动）

`product-svc` / `mkt-svc` / `platform-svc` 视届时诉求再定，**包边界先保留**。

### S4 — 数据库按域拆

最后一步，也是最贵的一步。在此之前是**一个库、多个服务**——
这是有意的过渡态，不是终点，但它比「先拆库再拆服务」安全得多。

---

## 6. 每阶段的验收与守卫

拆分最大的风险是**「拆了但没真拆」**：产物分开了，依赖还缠在一起，
而没有任何东西报错。所以每阶段配一条机器可查的守卫：

| 阶段 | 守卫 |
|---|---|
| S1 | `@RestController` 只在 `shop-portal-*`，`@Scheduled` 只在 `shop-jobs`（ArchUnit） |
| S1 | 域模块依赖树里不出现 portal / jobs / 启动模块；portal 之间互不依赖 |
| S1 | `shop-app-worker` 起来无 HTTP；二期起 `shop-app-c` 依赖树不出现 `portal-p` |
| S2 | `sys_outbox` 里 `PENDING` 超过 N 分钟的行数为 0（可观测指标，不是测试） |
| S3 | 域服务之间**没有直接的表访问**：`grep` 别的域的 Mapper = 红 |
| S3 | 端服务之间零调用（依赖树里互不出现） |
| 全程 | `module-graph.py`（TDD-backend §4 规划、**至今未建**）——判断「能不能拆」的唯一客观依据 |

最后一条是前置：本方案的所有数字都是我临时写脚本量的，
**这种度量应该常驻**，否则每次讨论都要重新量一遍，而量的口径还可能不一致。

---

## 7. 实现任务

- [ ] T0 建 `module-graph.py` + `split-readiness.py`（度量常驻，是后续所有判断的依据）
- [ ] T1 S1：`shop-core` 一拆四 + community/fulfillment 归位 `shop-merchant`（纯移动）
- [ ] T2 S1：Controller 迁出到 `shop-portal-c/b/p`（含 4 个特例的归属，见 §3.4.1）
- [ ] T3 S1：新建 `shop-jobs`（迁 2 个 Job + 补 Outbox 投递任务）
- [ ] T4 S1：启动模块收敛为 `shop-app-all` + `shop-app-worker`；profile 保留作运行时锁
- [ ] T5 S1：五条依赖规则落 ArchUnit（§3.4.1）
- [ ] （S1-next 的令牌换 Redis 与网关，等触发条件，见 §5）
- [ ] T6 S2：调用上下文透传（主体 + 数据域）
- [ ] T7 S2：Outbox 投递侧接 MQ
- [ ] T8 S3：按 §5 顺序切域服务，每切一个补一条守卫

---

## 8. 需要确认的三件

1. **端服务不持数据**这个形态是否接受（§3.1）——
   它是 §2.3 那 45% 共用表推出来的，但它意味着服务总数最终会多于 3 个
2. **S1 先行**是否接受：先拿「独立构建/发布/故障域」，数据库拆分留到最后
3. `usr_*` 暂归 `platform-svc`（§3.3）是否可接受，还是一期就单开 `user-svc`

---

确认记录：待确认

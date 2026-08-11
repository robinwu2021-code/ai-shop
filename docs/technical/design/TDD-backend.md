# 服务端架构（ai-shop backend）

> 状态：草稿（**待确认**）· 创建 2026-08-05
> 上游：[需求矩阵-三端](../../requirements/需求矩阵-三端.md) → [API 清单](../reference/API清单.md)（437 条端点）→ **本文**（这些端点由什么承载）
> 参考工程：`powerbank`（ShareHub）—— 其 `backend/` 的模块划分、三层契约、双池认证、DataScope 横切、Outbox 已在生产路径上验证，**直接继承，不重新论证**。
> 定位：本文回答「后端怎么分模块、怎么分层、越权怎么防、几条关键链路怎么走、按什么顺序落地」。
> 不含：字段级 DDL（另出 `db-design.md`）、每个 Service 的方法签名（另出各域 TDD）。

---

## 一、约束与设计前提

| # | 约束 | 来源 | 对架构的影响 |
|---|------|------|-------------|
| 1 | 一份后端同时服务 C 端小程序/App、B 端商家专区、平台端 ops-web | architecture.md §2 | **三组 portal，一套领域层**；不做 BFF 拆分（一期） |
| 2 | B 端一期内嵌 C 端小程序，二期拆独立小程序 | [ADR-001](../ADR/ADR-001-商家端形态与拆分时机.md) | `/biz/**` 与 `/mp/**` **共用令牌池、分开前缀**，拆端时后端零改动 |
| 3 | 按商家拆子订单，钱走微信支付分账 | [ADR-002](../ADR/ADR-002-结算走微信支付分账.md) | **订单必须主单/子订单两级**，分账以子订单为单位；退款先回退分账 |
| 4 | 无团长角色，履约由自提点商家 + 团发起人承担 | [ADR-004](../ADR/ADR-004-增长模型从孵化团长转向商家自带客流.md) [ADR-005](../ADR/ADR-005-履约方式与自提点模型.md) | **三个正交数据域键** `merchant_no` / `pickup_no` / `group_no`，不能合并成一个「身份」 |
| 5 | 自提点会看到别家商家的货 | 矩阵 §2.2 | 越权防线必须做到**响应字段级裁剪**，不止行级过滤 |
| 6 | 报价不事前审核，靠锁价 + 公示 | [ADR-003](../ADR/ADR-003-报价不审核而用锁价公示信用防加价.md) | 报价与改价**全量留痕**，审计表是业务表不是日志 |
| 7 | 多市场 / 多货币 / 三语 | 矩阵 C-17 | 金额带 `currency`，文案与价格分别按 market 维度存 |
| 8 | 一期规模小（单城市、几百商家） | 业务现状 | **模块化单体起步**，不上微服务；但模块边界按可裂解画 |

> ⚠️ 第 8 条是本架构最重要的取舍：**现在不拆微服务，但边界现在就要画对**。
> powerbank 的做法（[ADR-017 单体与微服务双形态](../../../../powerbank/docs/technical/ADR/ADR-017-单体与微服务双形态部署.md)）是同一份 `svc-*` jar，
> 单体启动模块依赖全部、微服务启动模块只依赖一个 —— 拆分是**改依赖列表**，不是重构代码。ai-shop 沿用。

---

## 二、技术栈（逐项对齐 powerbank/backend，**不另做选型**）

powerbank 的后端已经在真实项目里趟过一遍，ai-shop 的默认答案是「一样」。下表逐项标出**照抄 / 调整 / 新增**：

| 关注点 | ai-shop 选型 | 与 powerbank 的关系 |
|--------|-------------|-------------------|
| 父 POM | `ai.neargo:neargo-parent:1.0.0-SNAPSHOT`（`relativePath` 留空，走本机 `~/.m2`） | **照抄** |
| 语言 / 框架 | Java 21 + Spring Boot **4.0.x**（由 parent 管；`maven-enforcer` 锁 JDK 21） | **照抄** |
| 形态 | 模块化单体，`common` / `api` / `svc-*` / `app` 四类模块 + 可选微服务启动模块 | **照抄**（ADR-017 双形态） |
| ORM | MyBatis-Plus（`mybatis-plus-spring-boot4-starter`，版本由 parent 的 BOM 管） | **照抄** |
| 数据库 | MySQL / MariaDB，`mysql-connector-j` | **照抄** |
| 迁移 | **Flyway**（`spring-boot-flyway` + `flyway-core` + `flyway-mysql`），`baseline-on-migrate`、`validate-on-migrate` 开 | **照抄** |
| 缓存 / 会话 | `spring-boot-starter-data-redis`；`TokenStore` 可切 memory/redis | **照抄** |
| 认证 | 自持双链 `SecurityConfig` + `ConsumerTokenAuthFilter` / `StaffTokenAuthFilter` + `TokenStore` | **照抄**（B 端 `BizContext` 为新增，见 §5） |
| 鉴权 | `neargo-common-security` 的 RBAC（`@perm.can`），仅启 `RbacAutoConfiguration` | **照抄** |
| 数据权限 | `neargo-common-data` 的 scope 引擎，仅启 `DataScopeAutoConfiguration` | **照抄** + 新增三键（§5.3） |
| 基础库 | `neargo-common-core`（`Result`/`ErrorCode`/`IdGenerator`/`PageResult` 纯库） | **照抄** |
| 响应契约 | 项目自有 `ApiResult` / `PageData`（`{code,msg,data}` / `{records,total,page,size}`），`ApiResponseWrapper` 自动包裹 | **照抄**（与 c-app 现有口径一致，见 S4 待确认） |
| 校验 / 监控 / Web | `starter-validation` · `starter-actuator` · `starter-web` | **照抄** |
| 其它横切 | `BaseEntity`（逻辑删除 `deleted` + 审计字段自动填充）· `GlobalExceptionHandler` · `Messages`(i18n) · `Pages` · `BizKey`(单号生成) | **照抄** |
| MQ | 延时消息 + 事件投递（powerbank 只有 Outbox，无 MQ） | **新增**（关单、库存释放、截单、到货提醒必须要） |
| 搜索 | DB + 前缀索引起步，`SearchPort` 预留 ES | **新增** |
| 对象存储 | 直传凭证 + 服务端中转兜底 | **新增**（商品图/凭证/晒单，powerbank 无此量级） |

### 2.1 直接搬过来的四个「踩过的坑」

这几条在 powerbank 的 `pom.xml` / `application.yml` 里都留了注释，属于**不知道就会栽**的类型：

1. **Boot 4 把自动配置按技术拆成独立模块** —— Flyway 的 AutoConfiguration 不在 `spring-boot-autoconfigure` 里。
   只加 `flyway-core` 迁移**不执行且不报错**（表数不变、无 `flyway_schema_history`），极易误判为「跑过了」。必须显式加 `spring-boot-flyway`。
2. **MyBatis-Plus Boot4 starter 不传递 `mybatis-plus-extension`** —— 分页与乐观锁拦截器在 extension 里，要显式引，
   还需要 `mybatis-plus-jsqlparser`（DataScope 拼 where 依赖它）。
3. **引 neargo commons 必须精确关闭多余 autoconfig** —— `spring.autoconfigure.exclude` 排掉
   `SecurityAutoConfiguration` / `SessionStoreAutoConfiguration` / `DataAutoConfiguration`，否则会和项目自持的会话链冲突。
4. **Boot 4 的 `ObjectMapper` 是 Jackson 3**（`tools.jackson.databind.ObjectMapper`，不是 `com.fasterxml.jackson.databind`）
   —— 2.x 仍在 classpath 上（被别的库传递进来），所以注入 2.x 的 `ObjectMapper` 能编译通过，
   只在启动时炸 `No qualifying bean`。**这条是 ai-shop S0 实际踩到的**，powerbank 尚未遇到（它没在 common 里注 ObjectMapper）。
5. **不用 `spring.sql.init` 建表** —— 它每次启动重跑、ALTER 不幂等、无版本记录，且 `continue-on-error` 会把真错误吞掉。
   powerbank 那边「表不存在」反复出现就是这个原因。ai-shop 从第一天就 `sql.init.mode: never`，建表只走 Flyway。

### 2.2 一并搬过来的工程脚本（`backend/scripts/`）

powerbank 的 `scripts/` 是这套架构能长期不腐化的原因，ai-shop 同样需要，建议 S0 就带上：

| 脚本 | 作用 | 为什么需要 |
|------|------|-----------|
| `arch-guard.py` | 分层与依赖体检 | 与 ArchUnit 互补，CI 拦截 |
| `module-graph.py` | 模块依赖图 + 基础设施表标记 | 判断「能不能拆」的唯一客观依据 |
| `api-extract.py` / `api-align.py` | 从代码抽端点、与 API 清单比对 | **[API 清单](../reference/API清单.md) 437 条不能靠人肉维护**，必须能机器对账 |
| `entity-column-diff.py` | 实体与 DDL 字段差异 | 改表漏改实体是高频事故 |
| `gen-api-doc.py` / `gen-db-doc.py` | 文档生成 | 文档跟着代码走，不反过来 |
| `split-readiness.py` | 微服务拆分就绪度 | 二期拆端前的体检 |

> ⚠️ 其中 `api-align.py` 对 ai-shop 尤其关键：C 端已有 `endpoints.ts` 作为前端唯一真源，
> 后端有 API 清单，**两者必须能自动比对**，否则 437 条端点里漏实现或路径写错，只能等联调时一条条撞出来。

### 2.3 不照搬的部分

| powerbank 有 | ai-shop 不要 | 原因 |
|---|---|---|
| `svc-gateway` / driver 插件化（硬件接入） | ❌ | 没有硬件 |
| 代理商多层级模型、租户隔离键全链路启用 | 🟡 只留 `tenant_no=MAIN` 字段 | 一期单运营方，启用即负担 |
| `SeedData` 内存种子直出 | ❌ | 那边遗留的 🟡 状态（Controller 直连内存）是最难拆的技术债，ai-shop 从一开始就三层齐全落库 |

---

## 三、总体分层

```
   微信小程序 / App / H5              B 端商家专区(内嵌)            ops-web (PC)
        │  Bearer realm=CONSUMER          │  同一 Bearer          │  Bearer realm=OPERATOR
        │  /mp/**                          │  /biz/**              │  /ops/**
        └──────────────┬───────────────────┴───────────────────────┘
                       ▼
   ┌──────────────────────────────────────────────────────────────────┐
   │ shop-app · portal 层（薄 Controller，按端分包 mp / biz / ops）      │
   │ 横切：认证双链 · BizContext 解析 · RBAC(@PreAuthorize) · DataScope │
   │       · 幂等 · 审计 · i18n · ApiResponseWrapper                    │
   └──────────────────────────────────────────────────────────────────┘
                       ▼   （Controller 只调 Service，禁写业务）
   ┌──────────────────────────────────────────────────────────────────┐
   │ shop-svc-*  领域服务（业务规则 · 状态机 · 事件发布 · 策略扩展点）    │
   │  user  product  trade  fulfillment  marketing  settle  message    │
   │  platform(含 ops/config/risk/report)                              │
   └──────────────────────────────────────────────────────────────────┘
        │ 跨模块只经 shop-spi 的 Port 接口 + 事件，禁直接依赖对方 svc
                       ▼
   ┌──────────────────────────────────────────────────────────────────┐
   │ Mapper (MyBatis-Plus) · DataScopeHandler 横切拼数据域条件           │
   └──────────────────────────────────────────────────────────────────┘
        MySQL/MariaDB · Redis · MQ · 对象存储 · (ES 后置)
```

### 3.1 三层职责契约（不可越界，沿用 powerbank 已验证口径）

| 层 | 职责 | **禁止** |
|----|------|---------|
| **Controller**（薄） | 路由；`@PreAuthorize("@perm.can('码')")`（仅 `/ops`）；`@Valid`；调 Service；返 `ApiResult/PageData` | 不写业务；不 `stream`/`selectPage`/`new Entity`；**不判数据范围** |
| **Service**（`interface + impl`） | 全部业务规则；状态机；经 `ConsumerContext / BizContext / StaffContext` 取上下文；发领域事件；实体↔DTO | 不碰 `HttpServletRequest`；不判**功能**权限；不写 `SecurityContextHolder` |
| **Mapper** | 单表 CRUD + `LambdaQueryWrapper`；数据域由拦截器横切注入 | 不写业务分支；跨表聚合放 Service |

> powerbank 的教训直接抄过来：Service **统一 `interface + impl`**（那边 `LocService`/`ConsumerAuthService` 写成具体类，后来要回填规整）；
> Controller **不做跨域聚合直连内存**（那边 `OpsController` 直连 `SeedData`，成了最难拆的一块）。ai-shop 从第一天就不留这两个口子。

---

### 3.4 接口与实现的命名规范（2026-08-07 定，勿再逐案争论）

两套权威规范在这件事上相反：阿里手册【强制】Service 走接口 + `Impl` 后缀；
Fowler/Adam Bien 认为单实现的接口对是反模式（本仓库 23 个 Service 接口实现数全是 1）。
没有共识可抄，只能**选一致并锁死**：

| 角色 | 接口位置 | 实现位置 | 命名 |
|---|---|---|---|
| `XxxService` | `<域>/service/` | **`<域>/service/impl/`** | `XxxServiceImpl` |
| `XxxPort` | **`shop-spi/<域>/`**（另一个 Maven 模块） | **`<域>/port/`** | `XxxPortImpl` |
| `I` 前缀 | — | — | **禁止** |

三条都由 `ArchitectureTest` 锁住，违反即构建失败。

**为什么实现一定要与接口分包**：并排放时，IDE 的文件树里 `XxxService` 与 `XxxServiceImpl`
永远挨着，看目录分不清哪些是对外契约、哪些是内部细节。分包之后，
「这个域对外承诺了什么」= 看 `service/` 与 `shop-spi`，一眼可数。

**为什么 `.impl` 与 `.port` 两个落点不是不一致**：

- `.impl` —— Service 的实现，接口就在父包，靠子包分开
- `.port` —— Port 的实现，**接口在 `shop-spi` 另一个 Maven 模块里**，分离度本就高于 `.impl`；
  这个包名标的是「本域对外提供了什么」，不是「某个本地接口的实现」

混在一起（Port 实现塞进 `service/impl`）会让人以为它是本域 Service 的一部分，
而它恰恰要**绕过 Service 直连 Mapper** —— 10 个 Port 实现里 9 个如此（唯一例外是
`AttributionPortImpl`，它复用了 `AttributionService`）。原因在下一节。

**为什么 Port 不能和 Service 合并**：三个维度都不同 ——
Service 由本域定义、给本域 Controller 用、形状随本域业务；
Port 由**调用方**声明需求、给**别的域**用、形状随调用方需要。
更关键的是 **Port 是本仓库唯一真正需要接口的地方**：单体内是本地调用，
微服务形态换成 RPC 实现（见 §三），调用方一行不改。
而 23 个 Service 接口的实现数全是 1，它们的接口是规范要求，不是架构要求。

**关于 `Impl` 后缀 + `impl` 包是否重复标记**：是有一点，但两个标记各有用途 ——
包名给的是**位置**（去哪找实现），后缀给的是**角色**（在 import 列表和构造器参数里
一眼分辨接口与实现）。若只留包名（`impl/XxxService`），会造出接口与实现同名，
import 立刻歧义、部分位置被迫写全限定名，那是更差的结果。

## 四、Maven 模块划分

```
backend/
├── shop-common          # 横切基础设施，零业务依赖
├── shop-spi             # 只有接口：Port + DTO + Event。谁都能依赖
├── shop-svc-user        # 用户 · 认证 · 归属 · 地址 · 社区/自提点主数据 · 商家主体
├── shop-svc-product     # 类目 · 商品 · SKU · 库存 · 门店主页 · 评价
├── shop-svc-trade       # 购物车 · 下单拆单 · 支付 · 订单 · 售后
├── shop-svc-fulfillment # 履约策略 · 核销 · 分拣 · 配送 · 服务排期
├── shop-svc-marketing   # 券 · 活动 · 团购 · 求团报价 · 归因 · 素材
├── shop-svc-settle      # 分账 · 结算单 · 费率 · 提现 · 履约服务费
├── shop-svc-message     # 订阅消息 · 推送 · 站内信 · 客服工单
├── shop-svc-platform    # ops 账号/RBAC/审计 · 系统配置/开关/字典 · 风控 · 报表
├── shop-app             # ★ 启动模块：portal/{mp,biz,ops} + config + 装配
└── shop-app-*           # （二期）微服务形态启动模块，只换依赖列表
```

父 POM 继承 `ai.neargo:neargo-parent` → Java 21 + Spring Boot 4.0.x + MyBatis-Plus BOM + neargo commons（与 powerbank 同一地基）。

### 4.1 与 API 清单里 `svc-*` 标注的映射

[API 清单](../reference/API清单.md) 按**业务域**标注模块，本文按**构建单元**划分，两者不是一一对应：

| API 清单里的标注 | 落在哪个 Maven 模块 | 为什么合并 |
|---|---|---|
| `svc-community` | `shop-svc-user`（包 `community`） | 社区/自提点主数据与用户归属强耦合，拆开后每次绑定都要跨模块调用 |
| `svc-config` `svc-ops` `svc-risk` `svc-report` | `shop-svc-platform`（四个包） | 都是平台侧支撑域，无独立扩缩容诉求；**包边界保留**，将来任一域要拆随时可拆 |
| 其余 | 同名 `shop-svc-*` | — |

> 原则：**模块数 = 未来可能的部署单元数**，域数 = 包数。模块开太细，一期只会换来跨模块调用的样板代码。

### 4.2 模块依赖规则（用 ArchUnit 测试强制，违反即构建失败）

1. `shop-svc-a` **不得**依赖 `shop-svc-b`；跨域协作只有两条路：
   - **同步**：调 `shop-spi` 里的 Port 接口（实现方 `@Component` 注册，单体内是本地调用，微服务形态换成 RPC 实现）
   - **异步**：发 `shop-spi` 里的 Event，经 Outbox 投递
2. `shop-common` 不依赖任何 `svc`，也不含业务概念
3. Controller 只出现在 `shop-app/portal/**`（例外：`/internal/**` 内部端点可留在 svc，需在 ArchUnit 白名单登记）
4. 实体（`entity`）不跨模块传递，跨模块一律 DTO

**典型 Port 举例**：`MerchantQueryPort`（trade → user 查商家是否可收款）、`StockPort`（trade → product 锁库存）、
`SettlePort`（trade → settle 触发结算单）、`AttributionPort`（trade → marketing 读下单时的 `trafficSource`）。

---

## 五、认证、数据域与越权防线（**本项目最容易出事的地方**）

### 5.1 两个令牌池，三种上下文

| 池 | realm | 使用端 | 上下文对象 | 鉴权模型 |
|----|-------|--------|-----------|---------|
| C 池 | `CONSUMER` | `/mp/**` | `ConsumerContext`（`userId`） | 无 RBAC，**仅属主鉴权** |
| C 池（同一 token） | `CONSUMER` | `/biz/**` | `ConsumerContext` + **`BizContext`** | 数据域裁剪 |
| O 池 | `OPERATOR` | `/ops/**` | `StaffContext` | RBAC + 数据域授权 |

`BizContext` 由 `/biz/**` 专属拦截器解析：拿 `userId` 查「我是哪个 `merchantNo` 的管理员/店员、我管着哪个 `pickupNo`、我发起了哪些 `groupNo`」，
结果放进 `ThreadLocal`。**Service 永远不自己去查身份**，只读上下文。

### 5.2 四道防线（缺任何一道都拦不住越权）

| 防线 | 位置 | 拦什么 |
|:--:|------|-------|
| ① 前缀 + 过滤器链 | `SecurityConfig` 三条链 | 拿 C 池 token 打 `/ops/**` 直接 401 |
| ② 功能权限 | `@PreAuthorize("@perm.can('码')")`，**仅 `/ops`** | 客服调打款接口 → 403 |
| ③ 行级数据域 | `DataScopeHandler`（MyBatis 拦截器）按上下文自动拼 `where` | 商家 A 查订单列表，SQL 自动带 `merchant_no='A'`，**Service 里一行 where 都不写** |
| ④ **字段级裁剪** | 各域 `*ViewAssembler`，按调用场景出不同 VO | 自提点看别家订单：只出取货码/品名/数量/昵称，**金额与完整手机号在 VO 里根本不存在** |

> ④ 是 ai-shop 相对 powerbank **新增的一道**，因为业务上必然出现「看得到别家的货」。
> 做法上不靠 `@JsonIgnore` 条件序列化（易漏、难测），而是**不同场景不同 VO 类** —— 字段不存在，就不可能泄漏。
> 三个作用域（`merchant_no` / `pickup_no` / `group_no`）各自一套 VO，由 `DataScopeVoTest` 逐字段断言。

### 5.3 数据域键登记

`DataScopeTableRegistry` 声明「哪张表按哪个键裁剪」，未登记的表**默认拒绝**在 `/biz/**` 下被查询（fail-closed）：

| 键 | 典型表 | 备注 |
|----|--------|------|
| `merchant_no` | `prd_goods` `ord_sub_order` `stl_bill` `mkt_quote` | 商家自己的货与钱 |
| `pickup_no` | `ful_pickup_task` `ful_batch` | 含他家商品，配合 ④ 裁字段 |
| `group_no` | `ful_group_pickup` | 发起人轻核销，**零报酬、单团作用域**；⚠️ 端点在 **`/mp/groups/**`** 而非 `/biz` —— 发起人是普通用户，`/biz` 的 fail-closed 会把他挡在 403 外（2026-08-06 修正） |
| `community_no` | `prd_community_pool`（视图） | C 端商品池筛选 |
| `tenant_no` | 全表预留 `MAIN` | 多租户口子，一期不启用 |

---

## 六、核心领域模型（关键决策，非完整 ER）

### 6.1 订单两级结构（E3，一期必须一次做对）

```
OrdOrder（主单）        ── 用户视角：一次支付、一个支付单号
 └─ OrdSubOrder（子单）  ── 商家视角：一个 merchant_no，一次分账，一条售后链
     └─ OrdItem          ── SKU 行
```

- 支付在**主单**，分账、结算、售后、发货、核销全部在**子单**
- `trafficSource`（`MERCHANT_OWNED` / `PLATFORM`）写在**子单**上 —— 同一次下单可能一半来自店铺码、一半来自平台逛（E11/R16）
- 履约方式挂在子单：`STORE_PICKUP` / `NEIGHBOR_PICKUP` / `MERCHANT_DELIVERY` / `EXPRESS`

### 6.2 自提点实体（E14 / ADR-005）

`PickupPoint` **取代** `Merchant.isPickupPoint`：

| 字段 | 取值 | 含义 |
|------|------|------|
| `type` | `STORE` / `NEIGHBOR` | 商家门店 / 邻居家 |
| `scope` | `PERMANENT` / `GROUP_INSTANCE` | 常驻 / 仅某个团 |
| `ownerRef` | `merchant_no` / `user_id` | 承接方 |
| `serviceFeeRate` | 仅 `STORE` 有值 | **`NEIGHBOR` 必须为 0**，DB 约束 + 单测双重拦截 |

> 「零报酬」写进约束而不只是写进文档：一旦临时点能收钱，就是团长招募换名字，ADR-004 消掉的合规问题全部回来。

### 6.3 价格模型（A3/R17，**未拍板但架构必须先站队**）

建议：**价格只挂 `merchant_no + sku_no`，社区商品池降级为筛选视图**。
即 `prd_sku_price` 的唯一键是 `(merchant_no, sku_no, market)`，社区池表只存「哪些 SKU 在这个社区可见」，不存价。
这样「逛平台」与「进店」拿到的是同一行价格，物理上不可能出现「店里 8 块平台 7 块」。
反之若两处各存一份价，同价就得靠同步任务保证 —— 同步任务必然有窗口期，客诉必然发生。

### 6.4 其它关键表族

| 域 | 表族 | 要点 |
|----|------|------|
| 求团 | `mkt_request` `mkt_quote` `mkt_quote_revision` | 改价/毁约**留痕表是业务表**，C 端要读来公示（ADR-003） |
| 归因 | `mkt_attribution`（关系链）+ `mkt_attribution_log`（每次判定） | 优先级 店铺码 > 邀请人 > 渠道；**判定过程可回放**，否则争议无法裁决 |
| 结算 | `stl_bill` `stl_split_order` `stl_split_log` | 分账指令与回执分开存，重试幂等靠 `split_no` |
| 履约 | `ful_batch` `ful_pickup_task` `ful_verify_log` | 核销日志 append-only，代核销强制记操作人 |
| 基础设施 | `sys_outbox` `sys_idempotent` `sys_audit_log` | 不属于任何业务域，各模块共写 |

---

## 七、关键链路

### 7.1 下单（`POST /mp/order`）

```
① 幂等：Idempotency-Key 命中 sys_idempotent → 直接返回既有 orderNo
② 校验：登录态 · 社区归属 · 风控黑名单(Port→platform)
③ 试算：PricingStrategy 按品类分发 → 券/满减/买赠 → 得到应付
④ 拆单：按 merchant_no 分组 → N 个子单，各自算运费与履约方式
⑤ 锁库存：Redis 原子扣减 + DB 预占行，写 stock_lock（超时释放由 job 兜底）
⑥ 落库：主单 + 子单 + item，状态 WAIT_PAY
⑦ 发事件：OrderCreated → outbox（消费方：marketing 核销券、message 发提醒）
⑧ 起延时消息：15 分钟未支付关单
```
⑤⑥ 同一事务；③④ 纯计算无副作用（**必须可单测**，powerbank 的 `PriceEngineTest` 模式）。

### 7.2 支付与分账（ADR-002）

```
/mp/order/:no/pay → 调支付网关抽象 → 返回 JSAPI 参数（端侧不自判成功）
        ↓
/callback/wechat/pay（验签 + 幂等）→ 主单 PAID → 子单 WAIT_FULFILL
        ↓ 事件 OrderPaid
settle 生成 stl_bill（按子单，含 trafficSource 分档费率 + 自提点履约服务费）
        ↓ 确认收货 / 自动确认 T+N
分账指令 → /callback/wechat/profit-share → stl_split_order 终态
        ↓ 超时未分账
超期解冻回平台（P-12.1.4 兜底 job）
```

**退款必须先回退分账再退款**（E4）：`stl_split_order` 已分账 → 调回退 → 收到回执 → 才发起退款。
顺序反了钱退给用户但分账收不回来，是**真金白银的损失**，因此该顺序由状态机强制，不靠调用方自觉。

### 7.3 核销（`POST /biz/pickup/verify`）

```
扫码 → 解析取货码 → 校验：属于本 pickup_no？未核销？未退款？
  ↓ 任一失败返回明确错误码（30001/30002/30003），不是笼统失败
CAS 更新 ful_pickup_task（乐观锁）→ 子单 COMPLETED
  ↓ 事件 OrderCompleted
评价可写（C-13.2）· 履约服务费入账（B-10.5）· 结算解冻计时开始
```
> 核销是**订单能否走到 COMPLETED 的唯一出口**（自提线）。它缺位，评价、结算、复购全部无数据 —— 这是 M1-1 与 M1-3 不能拆开验收的技术原因。

### 7.4 进店归因（`POST /mp/store/:no/enter`）

```
扫码/分享进入 → 取 storeCode → 解析 merchantNo
  ↓ 查 mkt_attribution 是否已有归属
  ├─ 无 → 建关系链，trafficSource=MERCHANT_OWNED，窗口 30 天
  └─ 有 → 按优先级(店铺码>邀请人>渠道)裁决，两次结果都写 attribution_log
下单时 trade 经 AttributionPort 读当时归属 → 固化到子单（**不是下单后再查**）
```
> 归因**必须在下单那一刻固化到子单**。若结算时再回查，用户中途扫了别家码，费率就变了 —— 这类问题事后无法举证。

---

## 八、一致性、幂等与并发

| 问题 | 方案 |
|------|------|
| 跨模块最终一致 | **事务性发件箱** `sys_outbox`（powerbank 已验证）：业务与事件同事务落库，投递器轮询发 MQ，消费端按 `eventNo` 去重 |
| 接口幂等 | `sys_idempotent`（key + 端点 + 结果快照，24h）；下单/支付/退款/分账/核销**必接** |
| 库存超卖 | Redis Lua 原子扣减为主 + DB `stock >= n` 条件更新兜底；未支付锁定由延时消息释放 |
| 订单状态机 | 每个聚合一个独立状态机类（`OrdStateMachine` / `AfterSaleStateMachine` / `SettleStateMachine`），**非法迁移抛异常**，不允许 Service 里散写 `if (status == ...)` |
| 分账重试 | 平台幂等号 + 支付服务商幂等号双保险；重试有上限，超限进人工队列（`/ops/settle/split/:no/retry`） |
| 回调乱序 | 回调只做「状态推进」，不做「状态回退」；先到的终态胜出 |

---

## 九、支撑设施

| 关注点 | 一期方案 | 升级路径 |
|--------|---------|---------|
| 缓存 | Redis：会话、商品详情、社区池、配置开关、限流 | 热点商品本地缓存 + 失效广播 |
| 搜索 | DB + 前缀索引（商品量 < 10 万） | 接 ES，`svc-product` 内加 `SearchPort` 换实现 |
| MQ | 延时消息（关单、库存释放、截单、到货提醒）+ 事件投递 | — |
| 对象存储 | 直传凭证 `POST /common/upload/token`，小程序端兜底服务端中转 | CDN + 图片处理样式 |
| 定时任务 | 单体内调度（关单/结算/分账兜底/归因过期/报表聚合） | 独立调度中心 |
| i18n | `Messages`（错误文案）+ 业务多语言表（商品/类目）；`Accept-Language` 回落链 zh → en → ar | — |
| 多市场 | `market` 维度贯穿价格/库存/文案；金额带 `currency`，**分别定价不做汇率换算**（B6） | — |
| 开关 | `sys_feature_flag`（含 `points`，ADR-006 一期关闭），`/ops/config/feature-flag` 维护 | 灰度按人群 |
| 可观测 | 结构化日志（traceId 贯穿三端）· 审计表 · 关键链路埋点（下单/支付/核销/分账成功率） | APM |

---

## 十、测试策略（DoD 的一部分）

沿用 powerbank 的 `scenario` 模式 —— **按业务链路写集成测试，而不是按类写单测**：

| 测试 | 断言什么 |
|------|---------|
| `ConsumerOrderFlowTest` | 加购 → 结算 → 支付回调 → 核销 → 评价，全链路状态正确 |
| `MerchantSplitOrderTest` | 跨商家购物车拆出 N 个子单，各自分账口径正确 |
| `DataScopeFlowTest` | 商家 A 的 token 查不到商家 B 的任何数据（逐端点） |
| `PickupFieldMaskTest` | ④ 字段裁剪：自提点看他家订单的 VO **不含**金额与完整手机号 |
| `NeighborPickupZeroFeeTest` | `type=NEIGHBOR` 的服务费恒为 0（DB 约束 + 服务层双拦） |
| `RefundReverseSplitTest` | 已分账订单退款，必须先回退再退款，顺序颠倒直接失败 |
| `IdempotencyTest` | 下单/支付/核销重复请求返回同一结果，不产生第二条数据 |
| `ArchUnitTest` | 模块依赖规则、Controller 位置、Service 接口化 |

---

## 十一、落地顺序（对齐 [API 清单](../reference/API清单.md) §6 的 M1-1~M1-6）

| 阶段 | 内容 | 完成标志 |
|:---:|------|---------|
| **S0 地基** | 父 POM + 10 模块骨架 + common（认证双池/DataScope/Outbox/幂等/异常/i18n）+ ArchUnit | 空跑通，规则测试全绿 |
| **S1** | `svc-user` 认证与归属 + `svc-product` 商品读 → 打通 C 端「逛」 | c-app `VITE_USE_MOCK=0` 能逛（E1） |
| **S2**（M1-1） | `svc-trade` 购物车/拆单/支付/订单/售后 + 支付回调 | 真付跑通（E2/E3） |
| **S3**（M1-3） | `svc-fulfillment` 核销/分拣/批次 + `/biz/pickup/**` | 订单能到 COMPLETED |
| **S4**（M1-2） | 入驻审核 + 店铺主页 + 店铺码 + 归因 | 一个真实店主能自助开店并带客 |
| **S5**（M1-4/5） | `svc-marketing` 券/活动/团购/求团 + `svc-message` | 营销与团购上线 |
| **S6**（M1-6） | `svc-settle` 分账/结算/费率 + 退款回退分账 | 钱能正确分到商家（E4） |
| **S7** | `svc-platform` 报表与风控补齐 | ops-web 全功能 |

> S2 与 S3 **合并验收**（理由见 §7.3）。S1 之所以在 S2 前单独立一阶段，是因为它同时是「c-app 从 mock 翻真后端」的第一次实弹演练 ——
> 在最简单的读接口上把契约包、分页、错误码、i18n 全部对齐，比在下单接口上对齐便宜得多。

---

## 十二、待确认（影响架构而非字段）

| # | 事项 | 不定会怎样 | 需谁定 |
|---|------|-----------|--------|
| S1 | **是否复用 neargo-parent 与 auth-core**（architecture.md §12.2） | 决定 S0 是两天还是两周 | 技术 |
| S2 | **ops-web 后端新建还是复用现有**（矩阵 M2） | 决定 `shop-svc-platform` 起点与 `/ops/**` 172 条端点的归属 | 技术 |
| ~~S3~~ | ~~价格模型是否按 §6.3 站队~~ | **已定（S1 落地）**：`prd_sku` 唯一键 `(merchant_no, sku_no, market)`，社区池 `prd_community_pool` 只决定可见性、不存价。商品表上没有 price 列可读，双入口不同价在结构上不可能发生。由 `ConsumerBrowseFlowTest.storeAndPlatformSharePrice` 守卫。**若业务侧要推翻，越早越好** —— S2 下单会把这个模型固化进订单快照 | 业务确认 |
| ~~S4~~ | ~~契约包 `{code,msg,data}` vs `{code,message,data}`~~ | **已定（S0 落地）**：ai-shop 自持 `ApiResult`/`PageData` = `{code,msg,data}` + `{records,total,page,size}`，不用 neargo 的 `Result`（那是 `{code,message,data}` + `{list,total}`）。理由：c-app 45 个端点与全部 mock 已按此写死，改前端的成本远高于后端一个 20 行包装类。由 `ApiContractTest` 守卫 | — |
| S5 | 自提点可见字段清单（M11/B12） | 决定 §5.2 ④ 的 VO 字段集，测试写不出来 | 业务 + 法务 |
| S6 | 售后责任归属与出资方（M4） | 决定 `stl_bill` 是否需要三方扣款维度 —— **后加这一维要重算历史账** | 业务 + 财务 |
| S7 | 分账参数书面口径（B7） | 接入前必须拿到，否则 S6 无法联调 | 财务 + 支付服务商 |
| S8 | 是否需要独立 BFF | 一期建议不要；若 B 端二期拆独立小程序且字段差异变大，再评估 | 技术 |

---

## 十三、关联文档

- 端点全集：[API 清单](../reference/API清单.md) · 需求来源：[需求矩阵-三端](../../requirements/需求矩阵-三端.md)
- 总体架构与 C 端：[architecture.md](../reference/architecture.md)（§9 后端部分以本文为准，其中 `svc-leader` 已按 ADR-004 废止）· [TDD-c-app](./TDD-c-app.md)
- 决策：[ADR-001](../ADR/ADR-001-商家端形态与拆分时机.md) · [ADR-002](../ADR/ADR-002-结算走微信支付分账.md) · [ADR-003](../ADR/ADR-003-报价不审核而用锁价公示信用防加价.md) · [ADR-004](../ADR/ADR-004-增长模型从孵化团长转向商家自带客流.md) · [ADR-005](../ADR/ADR-005-履约方式与自提点模型.md) · [ADR-006](../ADR/ADR-006-积分方案.md)
- **参考工程 `powerbank/backend`**（技术栈与架构的直接来源，实施时对照抄）：
  - 模块骨架与依赖：`backend/pom.xml` · `backend/sharehub-app/pom.xml`（依赖清单与四个坑的注释）
  - 运行配置：`backend/sharehub-app/src/main/resources/application.yml`（autoconfigure exclude / Flyway / MP 配置）
  - 分层口径：[后端三层架构梳理](../../../../powerbank/docs/technical/后端三层架构梳理.md) · [TDD-backend-layered-design](../../../../powerbank/docs/technical/TDD-backend-layered-design.md) · [代码结构](../../../../powerbank/docs/technical/代码结构.md)
  - 决策：[ADR-017 单体与微服务双形态](../../../../powerbank/docs/technical/ADR/ADR-017-单体与微服务双形态部署.md) · [ADR-006 复用 neargo 基础框架](../../../../powerbank/docs/technical/ADR/ADR-006-复用neargo基础框架与依赖方式.md) · [ADR-015 权限模块化](../../../../powerbank/docs/technical/ADR/ADR-015-权限模块化与打包方式.md)
  - 横切实现：`sharehub-common/auth/**`（双池 filter/context/TokenStore）· `common/event/**`（Outbox）· `common/crud/**`

---
确认记录：待用户确认

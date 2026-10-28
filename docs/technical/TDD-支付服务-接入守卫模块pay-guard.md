# TDD-支付服务-接入守卫模块 pay-guard

状态：**草稿 v4.1**（2026-10-27 用户已定内部调用、运营端、部署拓扑、业务终端归属四条，见版本表；[ADR-045](./ADR/ADR-045-支付接入守卫与网关集成在支付服务.md) 已接受；其余待确认 §6 S3 与 Q2、Q4）
档位：**2**（新 Maven 模块、跨 `pay-portal` / `pay-svc` / `pay-domain` 搬家、对外鉴权规则、跨域调用契约加字段）→ 本 TDD + [ADR-045](./ADR/ADR-045-支付接入守卫与网关集成在支付服务.md)
依据：[支付服务-代码清单-开放API刷卡POS内部调用](./支付服务-代码清单-开放API刷卡POS内部调用.md) §1–§5（三条路径的类）·
[TDD-支付服务-开放API加解密与密钥分发](./TDD-支付服务-开放API加解密与密钥分发.md) v2.2（签名 / 加密参数 P1–P8）·
[开放API加解密-类清单](./TDD-支付服务-开放API加解密-类清单.md) §9（K1-DB 已实现：`KeyVault`、`PartnerKeyService`，分支 `claude/key-store-db` `ed7d5aed6`）·
`支付服务-安全规范.md` §2（A1–A8 各端鉴权规范）· ADR-044（`pay-portal`）
创建：2026-10-27 · 最后更新：2026-10-28（§7 实施记录）
基线代码：`codex/ai-shop-codex`（W5 密钥模块、I5 接入层、K1-DB 都已合入）
**代码结构、逐类代码清单、数据库设计**：[接入守卫模块pay-guard-代码与数据库](./TDD-支付服务-接入守卫模块pay-guard-代码与数据库.md)（与本篇 §3 冲突时以那份为准）

| 版本 | 改了什么 |
|---|---|
| v1 | 一个模块、每个入口一个过滤器、统一管线；内部调用改签名、运营端加 IP 白名单与 step-up |
| v4.1 | 补**支付运营管理前端方案**（§2.8）：支付运营页面是 ops-web 的一个模块，统一到现有「ops-web → `shop-app` `portal.ops.pay` → `payclient` → 支付」接法，取消直连支付的 `/pay-ops/v1`；新增「支付运营」菜单与前端代码结构；商家端同理 |
| v4 | **用户定（2026-10-27）**：⑤ **按实际部署的两个服务呈现调用链**：直接调支付服务的只有外部系统、POS、支付渠道、收银台 H5、业务系统五类，各一个过滤器（新增 `CallbackGuard`）；⑥ **业务系统的终端（小程序、商家端、运营端）只调业务服务** `shop-app`，商家 / 运营的支付功能也由 `shop-app` 认人判权后经内部接口调支付 —— 取消 v3 的 `OpsGuard` / `BizGuard` 与令牌内省；⑦ **内部调用规划过滤器**：支付侧 `InternalGuard`、业务侧 `InternalCallFilter`，规则相同（识别来源、全量留痕、不拒绝）。见 §2.7 |
| v3 | **用户定（2026-10-27）**：③ **所有支付业务在独立的支付服务里**，`shop-app` 及其他业务一律经 API 调用（`shop.pay.deployment=standalone`）；④ **网关集成在支付服务里**（`pay-guard` + `pay-portal` 装进 `pay-svc` 进程），以后再考虑把网关独立成服务、由它调支付服务。部署与调用链路见 §2.7，§2.6 装配表按此改写 |
| v2 | **用户定（2026-10-27）**：① **内部调用暂不做权限校验**，只记录来源服务与详细信息（含 IP）—— 这是留痕不是安全；来源信息**统一封装在调用参数里**（`CallSource`），存进调用日志 `pay_api_request_log`。② **运营端统一用 ops-web 的认证方案**：餐饮、零售、支付、美业的运营端是同一套权限方案，统一由平台账号体系管理 —— 支付不再加自己的 IP 白名单与 step-up。相应删去 v1 的 §2.3 A 签名方案、§2.3 D 的 IP / step-up、`InternalCredentialService`、`StepUp*`、`pay_ops_step_up`，切片 G5 / G6 改写 |

> **一句话**：把今天散在 `pay-portal`、`pay-domain`、`pay-svc` 三处的接入处理收进一个独立模块 **`pay-guard`**，
> 每个入口一个过滤器（**内部系统、外部 API、POS 终端、运营端**；商家端、收银台照同一形状迁入），走同一条管线、最后产出同一个 `PayCaller`。
> 四个入口各管各的事：**外部 API 与 POS 验签**（不受信）；**运营端认平台会话、权限归平台**（受信的人）；**内部系统只认来源、全量留痕**（受信的系统，暂不校验）。

---

## §0 需求与验收

| AC | 需求（一句话） | 落点 |
|---|---|---|
| G1 | 接入处理整体是**一个独立模块**，代码在支付业务里（`backend/pay/` 下），不在 `shop-app` | 新模块 `backend/pay/pay-guard`（§2.1） |
| G2 | **每个入口一个过滤器**，各自只认自己那一种凭证；外部 API / POS / 运营端拿错凭证打别的入口一律 401 | `OpenApiGuard` · `TerminalGuard` · `OpsGuard` · `InternalGuard`（§2.3） |
| G3 | 所有入口走**同一条管线**，入口只开关其中几步，不各写一遍 | `GuardFilter` + `EntryGuard`（§2.2） |
| G4 | 请求数据在过滤器里校验：请求体大小、必填头、时间窗、nonce、签名、字段是否按要求加密；业务字段校验仍在 controller（`@Valid`） | §2.4 |
| G5 | 外部 API 与 POS 的密钥都存在平台密钥模块、经 `KeyVault` 运算；两者密钥不共用 | §2.5 |
| G6 | 内嵌与独立两种部署形态共用同一套过滤器，只换装配 | §2.6 |
| G7 | `PayCaller` **只能**在 `pay-guard` 里构造，架构测试守住 | §5 `PayCallerConstructionTest` |
| G8 | 每次请求不论成败都写一行 `pay_api_request_log`（失败原因只进记录、不回给调用方） | 管线 ⑫ |
| **G9** | **内部系统调用不做权限校验**；每次调用记录来源服务、实例、服务器 IP、追踪号、触发它的上游接口与终端用户 IP、操作人；**来源信息封装在调用参数里**，内嵌（进程内）与独立（HTTP）两种形态记录同样的字段 | `spi.pay.CallSource` · `InternalGuard`（只识别、不拒绝）· `InternalCallRecorder`（§2.3 A） |
| **G10** | **运营端统一走平台账号体系**：认人用平台运营会话（`OperatorTokenAuthFilter`），权限码登记在平台 `Perms`、由 ops-web 角色管理分配；支付不另设运营端的认证、权限、IP 或二次验证规则 | `OpsGuard`（只确认运营会话并转成 `PayCaller`）· `@perm.can(Perms.PAY_*)`（§2.3 D） |

**孤立项**：无。商家端与收银台不在点名的四个之内，但与运营端、外部 API 共用同一个过滤器框架，照同一形状迁入，规则不改。

---

## §1 现状（2026-10-27，逐条核对过代码）

### 1.1 四个入口今天各在哪

| 入口 | 路径 | 今天的处理类 | 在哪个模块 | 凭证 | 留痕 |
|---|---|---|---|---|---|
| **内部系统**（内嵌） | 无 HTTP，进程内调 `spi.pay.PaymentPort` | `acquiring.port.PaymentPortImpl` → `InternalCallers`（直接构造 `PayCaller`） | `pay-domain` | 无 | **无**：`pay_api_request_log` 的 `entry` 值域里有 `INTERNAL_PORT`，但没有任何代码写它 |
| **内部系统**（独立） | `/internal/pay/v1/**` | `svc.InternalCallerFilter` → `ConfigInternalCallerRegistry` | `pay-svc` | `X-Internal-Caller` + `X-Internal-Credential`（服务端存 SHA-256） | **无**：只打一行 warn 日志 |
| **外部 API** | `/open/pay/v1/**` | `api.auth.OpenSignatureAuthenticator` → `openapp.service.AppAuthService` | `pay-portal` + `pay-domain` | AppId + HMAC 签名 + 时间窗 + nonce | ✓ |
| **POS 终端** | `/terminal/pay/v1/**` | **没有**：`PortalFace.TERMINAL` 只有枚举值 | — | — | — |
| **运营端** | `/pay-ops/v1/**` | 平台 `OperatorTokenAuthFilter` → `api.auth.OpsSessionAuthenticator` | `shop-base-auth` + `pay-portal` | 平台运营会话；权限码已是平台 `Perms.PAY_*`（`pay:order:view` 等 7 个），端点用 `@perm.can` | ✓ |
| 商家端 | `/biz/pay/**` | `api.auth.BizSessionAuthenticator` | `pay-portal` | 商家会话 + 门店范围 | ✓ |
| 收银台 | `/checkout/v1/**` | `api.auth.CheckoutVisitorAuthenticator` | `pay-portal` | 一次性收银令牌 / 码号 | ✓ |

内部调用参数今天**不带任何来源信息**：`PaymentPort.InternalCreatePaymentCmd` 只有业务字段，`InternalRefundCmd` 只有 `operatorNo`；`queryPayment` / `closePayment` 只有 `bizType` 与单号。

### 1.2 问题

| # | 问题 | 处置 |
|---|---|---|
| P1 | 接入处理分在三个模块 | 收进 `pay-guard`（G1） |
| P2 | `PayCaller` 在 5 处被直接 `new`（`InternalCallers` 三处、`CheckoutServiceImpl` 两处、`AppAuthServiceImpl`）；代码清单 §2.1 说「有架构测试守」，**这条测试不存在** | 构造点收进 `pay-guard`，补 `PayCallerConstructionTest`（G7） |
| P3 | 内部调用**完全不留痕**：出了问题答不上「是哪个服务、哪台机器、哪个用户请求触发的」 | `CallSource` + `InternalCallRecorder`（G9） |
| P4 | POS 入口空白 | `TerminalGuard`（G4 切片） |
| P5 | 开放 API 缺响应签名、字段解密、`X-Key-Serial`、细分错误码 | G3 切片 |

---

## §2 方案

### 2.1 模块位置

```
backend/pay/
├── pay-channel     渠道协议（不动）
├── pay-domain      支付业务：支付单、退款、流水、PayCaller、能力矩阵、接入方 / 终端的业务数据、请求记录表
├── pay-guard   🆕  接入守卫：每个入口的过滤器、验签、解密、防重放、来源识别、请求记录写入；外部 API / POS 凭证的发放与吊销
├── pay-portal      对外 controller（open / terminal / ops / biz / checkout），不再含任何鉴权类
└── pay-svc         独立形态的内部端点（/internal/pay/v1），不再含鉴权类
```

```
pay-portal ──┐
             ├──▶ pay-guard ──▶ pay-domain ──▶ pay-channel
pay-svc ─────┘        │                ▲
                      ├──▶ secret-api   │
                      └──▶ shop-base-auth（运营 / 商家会话、Perms —— 平台账号体系）
shop-app（内嵌形态）──▶ pay-guard（SecurityConfig 按入口挂过滤器）
shop-base（spi.pay.CallSource）◀── 内部调用方（商城、餐饮…）与 pay-guard 都依赖
```

| 放进 `pay-guard` | 留在 `pay-domain` |
|---|---|
| 过滤器、管线、每个入口的识别与校验、签名规则、nonce、来源识别、请求记录写入、响应签名、字段解密、错误映射 | `PayCaller`、`PayAction`、`CallerPolicy` / `CapabilityMatrix`（业务规则：谁能做什么） |
| 外部 API（AppSecret / EncryptKey）与 POS（设备密钥）凭证的**发放、轮换、吊销** —— 调 `KeyRegistry` | 接入方 / 终端 / 授权的**业务数据**：`pay_app`、`pay_app_grant`、`pay_terminal`、`pay_manager_code` 的实体、Mapper、管理服务 |
| `PayEntry`（入口枚举，取代 `PortalFace`，多一个 `INTERNAL`） | `pay_api_request_log` 的实体与 Mapper（守卫只调 `ApiRequestRecorder` 写） |

包根 `ai.neargo.shop.pay.api.guard`（ADR-045：守卫依赖 Servlet，而 `ArchitectureTest` 只豁免 `pay.api` 这一棵子树，放在其下不放宽任何规则；`ai.neargo.shop.pay.access` 已是 `pay-domain` 里 `PayCaller` 所在的包，不复用）。

**运营端与商家端的认人不在 `pay-guard`**：它们用平台账号体系（`shop-base-auth`），守卫只把平台已认出的人转成 `PayCaller`（G10）。

### 2.2 统一管线

```
请求 ─▶ GuardFilter(entry)
        │ ① 只管本入口前缀（PayEntry.owns(path)）
        │ ② 缓存请求体（上限 1MB）                          超限 → 413
        │ ③ 基础校验：必填头、Content-Type、时间戳格式         → 400 / 401
        │ ④ 识别调用方：读本入口的凭证 / 来源                  → 401（内部系统不拒绝）
        │ ⑤ 验证：签名（KeyVault.mac / 公钥）或平台会话        → 401
        │ ⑥ 时间窗 ±300s · nonce 抢占（验证通过后才抢）         → 401
        │ ⑦ 来源 IP 白名单                                     → 403
        │ ⑧ 授权范围：scope / 门店授权 / 平台权限码             → 403
        │ ⑨ 敏感字段解密（x-sensitive，明文只活在本次请求）     → 400
        │ ⑩ 构造 PayCaller → 放进请求属性                       （唯一构造点）
        ▼
     controller（只认 PayCaller）
        ▲
        │ ⑪ 响应签名（P2）
        │ ⑫ 写 pay_api_request_log（成败都写；失败原因只进记录）
```

| 步骤 | 内部系统 | 外部 API | POS 终端 | 运营端 | 商家端 | 收银台 |
|---|---|---|---|---|---|---|
| ③ 基础校验 | 只校验格式，不拒绝来源 | ✓ | ✓ | ✓ | ✓ | ✓ |
| ④ 识别 | `CallSource`（调用方自报） | AppId | 终端号 | 平台运营会话 | 平台商家会话 | 收银令牌 |
| ⑤ 验证 | **不验证**（G9） | HMAC / RSA 签名 | HMAC 签名（设备密钥） | 平台已验（`OperatorTokenAuthFilter`） | 平台已验 | 令牌哈希 |
| ⑥ 时间窗 + nonce | — | ✓ | ✓ | — | — | — |
| ⑦ IP 白名单 | — | `pay_app.ip_whitelist` | — | —（平台 / nginx 统一，G10） | — | — |
| ⑧ 授权范围 | —（G9） | scope × `pay_app_grant` | 终端绑定门店 | 平台 `@perm.can(Perms.PAY_*)` | 门店范围 + `mch:pay:*` | 只本单 / 本码 |
| ⑨ 字段解密 | — | ✓ | — | — | — | — |
| ⑪ 响应签名 | — | ✓ | ✓ | — | — | — |
| ⑫ 请求记录 | ✓ **含完整来源**（今天没有） | ✓ | ✓ | ✓ | ✓ | ✓（令牌只留哈希前 8 位） |

```java
/** 一个入口的差异；管线本身不分入口 */
public interface EntryGuard {
    PayEntry entry();
    /** ④–⑧：识别并（按入口）验证调用方；失败给出原因（只进记录） */
    GuardResult authenticate(GuardedRequest request);
    /** ⑨：默认不解密 */
    default void decryptSensitiveFields(GuardedRequest request, GuardedCaller caller) { }
    /** ⑪：默认不签 */
    default void signResponse(GuardedResponse response, GuardedCaller caller) { }
}
```

**能力矩阵不因入口而跳过**：内部系统不做「权限校验」指的是接入层不认凭证、不判授权；进了应用服务，`callerPolicy.check(caller, action, storeId)` 照旧（防代码 bug，例如商城的调用带了别的门店 id）。退款必须带商家操作员也照旧 —— 那是业务规则，不是接入校验。

### 2.3 四个入口

#### A · 内部系统 `InternalGuard` —— 不校验，只留痕（G9）

**1）来源信息封装在调用参数里**：新增跨域契约 `shop-base` `spi.pay.CallSource`，内部调用方不手填，由工厂从当前上下文取：

```java
/**
 * 一次内部调用的来源（G9）：谁、从哪台机器、因为哪个上游请求调了支付。
 * 只用于留痕与排障，不是凭证 —— 全部字段都是调用方自报，支付不据此放行或拒绝。
 */
public record CallSource(
        String service,       // 调用方服务名：spring.application.name（shop-app / food-svc / job-worker …）
        String module,        // 调用方模块 / 业务线：MALL / FOOD / RETAIL / BEAUTY / STORED_VALUE …
        String instance,      // 实例：主机名或 pod 名
        String serverIp,      // 调用方服务所在机器的 IP（自报）
        String traceId,       // 上游追踪号：沿用调用方请求的 X-Request-Id，支付不另起
        String originApi,     // 触发这次调用的上游接口，如 POST /mp/orders/{orderNo}/pay；任务触发填任务名
        String originClient,  // 上游请求的来源端：沿用认证链端码 APP_C / APP_BIZ / WEB_OPS / H5 / PARTNER；JOB / OUTBOX / UNKNOWN
        String originIp,      // 上游请求的终端用户 IP（ClientMeta.clientIp，经可信代理头）
        String originUaDigest, // 上游请求 User-Agent 的 SHA-256（原文不出调用方进程）
        String operatorNo,    // 操作人：商家员工号 / 运营员工号 / 顾客账号；任务为 SYSTEM
        String invokedAt) {   // 调用方发起时间（ISO-8601）
}

/** 统一封装：调用方一行 CallSources.current() 即得；进程内与 HTTP 两种形态拿到的是同一份 */
public final class CallSources {
    public static CallSource current() { … }          // 读 RequestContextHolder / MDC 追踪号 / 任务上下文 + 本进程静态信息
    public static <T> T asJob(String jobName, Supplier<T> body) { … }       // 定时任务（进程内执行器绑定）
    public static <T> T asOutbox(String eventType, Supplier<T> body) { … }  // Outbox 消费（分发器绑定）
}
```

**2）`PaymentPort` 怎么带上它（不破坏现有调用方）**：

| 方法 | 变化 |
|---|---|
| `InternalCreatePaymentCmd` · `InternalRefundCmd` | 末尾加 `CallSource source`；保留原参数个数的构造器，自动填 `CallSources.current()` —— 现有调用方一行不改也有来源 |
| `queryPayment(bizType, payOrderNo)` · `closePayment(bizType, outOrderNo, reason)` | 加带 `CallSource` 的重载；原方法改为 `default`，转调重载并填 `CallSources.current()` |

**3）两种形态记录同样的字段**：

| 形态 | 来源怎么到支付 | 谁写记录 | `entry` |
|---|---|---|---|
| 内嵌（今天的生产形态） | `CallSource` 随参数对象进程内传入 | `InternalCallRecorder`：`PaymentPortImpl` 的每个方法外包一层（开始计时 → 调用 → 成败都写一行） | `INTERNAL_PORT` |
| 独立（`pay-svc`） | 远程实现 `RemotePaymentPort` 把 `CallSource` 序列化进请求头 `X-Call-Source`（Base64URL 的 JSON）；`X-Internal-Caller` 继续带调用方码 | `InternalGuard` 解析头 → 放进请求属性 → 管线 ⑫ 写记录；另记服务端**实际看到的**对端 IP | `INTERNAL_HTTP` |

**4）不做的事**：不验凭证、不判路径授权、不抢 nonce、不拒绝任何来源（解析不出 `CallSource` 也放行，记录里标 `source_service = UNKNOWN`）。
**现有 `InternalCallerFilter` 的凭据校验**：搬进 `InternalGuard` 后保留为开关 `shop.pay.guard.internal.verify-credential`，**默认 `false`**（G9）；以后要恢复校验只改配置。

> **边界**（写下来，免得被误读成「内部接口是安全的」）：内部入口此后的唯一防线是网络隔离 —— nginx 对所有 server `deny /internal/`（安全规范 §4.2）、`pay-svc` 只监听内网。
> 内嵌形态没有这个问题（不经 HTTP）；独立形态上线前要再评估是否打开 `verify-credential`（Q2）。

#### B · 外部 API `OpenApiGuard`

| 项 | 规则 |
|---|---|
| 凭证 | `X-App-Id` · `X-Timestamp` · `X-Nonce` · `X-Key-Serial` · `X-Signature` ·（有加密字段时）`X-Encrypt-Serial`（加解密 TDD §2.4 P1） |
| 校验顺序 | AppId 启用且环境一致 → 时间窗 → 验签（按 `X-Key-Serial` 取版本，`KeyVault.mac` + 常量时间比较；RSA 方案用接入方公钥）→ nonce → IP 白名单 → scope → `pay_app_grant` 门店范围 |
| 数据 | ⑨ 按 `x-sensitive` 清单解密（P3），明文字段传了即 400 `FIELD_ENCRYPT_REQUIRED` |
| 响应 | ⑪ 每个响应按 P2 签名（含 401 / 403；AppId 不存在时不签） |
| 密钥 | AppSecret `INBOUND_HMAC`、EncryptKey `PARTNER_DATA`（K1-DB 已实现发放，`PartnerKeyService`） |
| 推送 | 不经过滤器（出站），签名与加密用同一组类（`ResponseSigner`、`ResourceCipher`），放在 `pay-guard.open` |

#### C · POS 终端 `TerminalGuard`

| 项 | 规则 |
|---|---|
| 凭证 | `X-Terminal-Sn` · `X-Timestamp` · `X-Nonce` · `X-Signature` · `X-Operator`（收银员登录后）—— 待签串同 `SignatureCanonicalizer`，第 4 行的 AppId 换成终端号 |
| 激活接口 | `POST /terminal/pay/v1/activation` **不验签**（终端还没有密钥），改验一次性激活码（10 分钟、单次、库里只存哈希）+ 设备指纹 + 按 IP 限频；通过后经 `TerminalKeyService` 生成设备密钥，只此一次下发 |
| 校验顺序 | 终端存在且 `ACTIVE` → 时间窗 → 验签（设备密钥）→ nonce → `storeScope = {终端绑定门店}` |
| 加签操作 | 撤销 / 退款的店长授权码在业务层校验（`ManagerCodeService`）—— 授权码是业务输入，不是调用方身份 |
| 响应 | ⑪ 按 P2 用设备密钥签名：终端据此判断「收款成功」不是被中间人伪造的 |
| 卡数据 | 读卡套件产出的加密卡数据原样透传，守卫不解析、请求记录对 `encryptedCardData` 整段脱敏（PCI 边界） |
| 密钥 | `sec_key(PAY, TERMINAL, pay_terminal.id, INBOUND, HMAC)`，`BASE64URL_32B`；挂失 = `KeyRegistry.revoke`，下一次请求即失败 |

#### D · 运营端 `OpsGuard` —— 平台账号体系，支付不另设规则（G10）

> **v4 取代本节的部署部分**：运营端与商家端不再直连支付服务，`ops-web` / `b-app` 只打 `shop-app`，
> 由 `OperatorTokenAuthFilter` / `MerchantTokenAuthFilter` 认人、`@perm.can` 判权限，再经内部接口调支付（§2.7 B2）。
> 下表的规则（平台账号体系、`Perms`、不设支付专属规则）照旧成立，只是执行位置在 `shop-app`；`OpsGuard` / `BizGuard` 只在过渡期（内嵌形态）存在。

| 项 | 规则 |
|---|---|
| 认人 | **平台**：ops-web 登录 → 平台运营会话；`OperatorTokenAuthFilter` 只认 `realm=OPERATOR`，角色、数据域、权限码每请求由 `LiveIdentityResolver` / `LivePermResolver` 现算（改角色下一个请求生效）。独立形态经平台的令牌内省接口，结果相同 |
| 权限 | **平台**：权限码登记在 `shop-base-auth` 的 `Perms`（支付已有 `pay:order:view` · `pay:order:manage` · `pay:refund:view` · `pay:refund:review` · `pay:log:view` · `pay:notify:resend` · `pay:qr:manage`），在 ops-web 的角色管理里分配；每个端点 `@PreAuthorize("@perm.can(Perms.PAY_…)")`，`OpsEndpointPermTest` 守覆盖 |
| 与其他行业 | 餐饮、零售、支付、美业的运营端**同一个会话、同一套角色、同一张权限码表**；支付新增运营功能只做两件事：在 `Perms` 加码、在端点上标 `@perm.can`。**不得**在 `pay-guard` 里写任何运营端专属的认人或判权逻辑 |
| 守卫做什么 | 只三件：确认平台已认出运营（`realm=OPERATOR`），把员工号与已解析的权限码转成 `PayCaller(OPS)`，写请求记录 |
| 不做什么 | 不做支付专属的 IP 白名单、二次验证、会话策略 —— 这些若需要，在平台层为所有行业的运营端一起做（nginx 白名单今天已对 `/ops`、`/pay-ops` 生效，安全规范 G1） |
| 两人复核 | 业务流程（`pay_approval`，发起人 ≠ 复核人），不在守卫 |

### 2.4 过滤器校验什么、controller 校验什么

| 校验 | 在哪 | 理由 |
|---|---|---|
| 请求体大小、必填头、时间戳格式、nonce 长度（8–64）、幂等键格式（≤ 128） | 守卫 ③ | 与业务无关的格式错误 |
| 签名、时间窗、nonce、IP、scope / 门店授权 | 守卫 ⑤–⑧（外部 API、POS） | 不受信入口的身份与范围 |
| 运营 / 商家会话与权限码 | 平台过滤器 + `@perm.can` | 平台账号体系（G10） |
| 内部调用的来源 | 守卫只**识别与记录**，不判 | G9 |
| 敏感字段是否加密、能否解开 | 守卫 ⑨ | 解密要密钥，密钥只在守卫与密钥模块之间流动 |
| 金额 > 0、币种、字段长度、枚举值 | controller `@Valid` | 业务字段，规则跟着 DTO 走 |
| 能力矩阵、日退款限额、复核规则、店长授权码、退款必须带操作员 | 应用服务 | 业务规则，所有入口一样 |

### 2.5 密钥

| 入口 | `sec_key.owner_type` | `owner_id` | 用途（`KeyPurpose`） | 形态 | 谁发放 | 默认档 |
|---|---|---|---|---|---|---|
| 外部 API | `PARTNER_APP` | `pay_app.id` | `INBOUND_HMAC`（AppSecret）· `PARTNER_DATA`（EncryptKey） | `ALNUM_32` | 接入方自己（开发者控制台，`PartnerKeyService`） | L3 |
| 外部 API（增强） | `PARTNER_APP` | `pay_app.id` | 接入方 RSA 公钥（公开材料） | PEM | 接入方上传 | — |
| 外部 API（平台） | `PLATFORM_OPEN` | `0` | `OUTBOUND_SIGN` · `PARTNER_DATA`（平台加密私钥） | RSA-2048 | 运营，两人启用 | L3 / L5 |
| POS 终端 | `TERMINAL` | `pay_terminal.id` | `INBOUND_HMAC`（设备密钥） | `BASE64URL_32B` | 激活时自动生成（`TerminalKeyService`） | L3 |
| 内部系统 | — | — | 不用密钥（G9）；`verify-credential` 打开时沿用现有部署配置里的哈希 | — | — | — |
| 运营端 | — | — | 不用密钥（平台会话） | — | — | — |

### 2.6 装配

| 形态 | 怎么挂 |
|---|---|
| **目标（v4）** | **`pay-svc`** 装 `pay-guard` + `pay-portal`，挂五个入口：外部 API、POS、渠道回调、收银台、内部（`shop.pay.guard.entries=OPEN,TERMINAL,CALLBACK,CHECKOUT,INTERNAL`）。**`shop-app`** 不装 `pay-guard`；它的三条终端链（顾客 / 商家 / 运营）不变，另加一条 `/internal/shop/v1` 链挂 `InternalCallFilter` |
| **将来：网关独立成服务** | 新进程 `pay-gateway` = `pay-guard` + `pay-portal`，挂外部 API、POS、渠道回调、收银台四个入口，经 `/internal/pay/v1` 调 `pay-svc`；`pay-svc` 只剩 `InternalGuard` + 支付核心。同一个 jar、换装配 |
| **过渡：内嵌**（今天，切换前保持） | 支付整体在 `shop-app` 里（`shop.pay.deployment` 默认内嵌） |
| 配置 | `shop.pay.guard.entries` · `clock-skew-seconds`（300）· `nonce-ttl-seconds`（300）· `internal.verify-credential`（默认 `false`）· `internal.record-port-calls`（默认 `true`） |

### 2.7 部署与调用链路（v4，按实际部署的两个服务）

> **v4 相对 v3 的变化**（用户 2026-10-27）：**业务系统的终端只调业务系统的服务**。小程序、商家端 b-app、运营端 ops-web 都只打 `shop-app`，
> 由 `shop-app` 的业务服务经内部接口调支付 —— 包括商家端、运营端里的支付功能（退款、收款码、支付单查询…）。
> 于是 `pay-svc` 不再直接面对平台会话，v3 的 `OpsGuard` / `BizGuard` 与「平台令牌内省」（v3 D2）**一起取消**。
> 直接调支付服务的只有五类：**外部系统、POS 刷卡机、支付渠道、收银台 H5、业务系统**。

#### 2.7.1 两个服务、九类终端、九个过滤器

| 服务 | 终端 | 入口前缀 | 过滤器 | 认什么 | 已有 / 新 |
|---|---|---|---|---|---|
| **`pay-svc`** | 外部系统 | `/open/pay/v1` | `OpenApiGuard` | AppId + 签名 + 时间窗 + nonce + scope | 已有（`OpenSignatureAuthenticator`），迁入 |
| | POS 刷卡机 | `/terminal/pay/v1` | `TerminalGuard` | 终端号 + 设备密钥签名 + nonce | 新 |
| | 支付渠道 | `/pay/callback` | **`CallbackGuard`** | 先落原文 → 按渠道验签（§2.7.3） | **新**：今天 `channelCallbackChain` 全放行，验签在 controller 里 |
| | 收银台 H5 | `/checkout/v1` | `CheckoutGuard` | 一次性收银令牌 / 码号 | 已有（`CheckoutVisitorAuthenticator`），迁入 |
| | 业务系统 | `/internal/pay/v1` | **`InternalGuard`** | 调用方码 + `CallSource`，**不拒绝、全量留痕**（§2.7.4） | 已有（`InternalCallerFilter`），改造 |
| **`shop-app`** | 小程序 / H5 | `/mp` | `ConsumerTokenAuthFilter` | 顾客会话 | 已有，不动 |
| | 商家端 b-app | `/biz` | `MerchantTokenAuthFilter` + `BizContextFilter` | 商家会话 + 门店范围 + `mch:*` 权限码 | 已有，不动 |
| | 运营端 ops-web | `/ops` | `OperatorTokenAuthFilter` | 运营会话 + `Perms`（餐饮、零售、支付、美业同一套） | 已有，不动 |
| | 支付服务（回调与反查） | `/internal/shop/v1` | **`InternalCallFilter`** | 同 `InternalGuard` 的镜像（§2.7.4） | **新** |

**一个入口只认一种凭证**：拿 AppId 签名打 `/terminal`、拿运营令牌打 `/open`、拿收银令牌打 `/internal`，都在各自的过滤器上失败（内部入口除外：它不拒绝，但会把来源记成 `UNKNOWN`）。

#### 2.7.2 九条调用链

**A. 直接调用支付服务（`pay-svc`）**

| # | 链路 |
|---|---|
| P1 外部系统 | 外部系统 → nginx `api.{域名}` → `pay-svc` **`OpenApiGuard`** → `api.open.*` → `PaymentService` / `RefundService` → `pay-channel` → 渠道；结果：`pay-svc` webhook（签名 + 加密）→ 外部系统 |
| P2 POS 刷卡机 | POS → nginx `terminal.{域名}` → `pay-svc` **`TerminalGuard`** → `api.terminal.*` → `InStorePaymentService` → 渠道（刷卡 / 被扫）；POS 轮询结果 |
| P3 支付渠道 | 渠道 → nginx `api.{域名}` `/pay/callback` → `pay-svc` **`CallbackGuard`** → 回调端点（主动查单 → 匹配收款商户 → 入账）→ `PaymentOutcomeService` → 外部来源的单推 webhook；业务来源的单走 B3 回调业务 |
| P4 收银台 H5 | 顾客浏览器 → nginx `pay.{域名}`（`checkout-h5` 静态页）→ `/checkout/v1` → `pay-svc` **`CheckoutGuard`** → `CheckoutService` → 渠道收银台。用于外部系统的单与静态码；**小程序里自家订单的付款不走这里**（走 B1） |
| P5 业务系统 | 见 B1–B2 的第二段：`shop-app` → `/internal/pay/v1` → `pay-svc` **`InternalGuard`** → 应用服务 |

**B. 业务系统的终端调业务服务（`shop-app`），业务服务再内部调支付**

| # | 链路 |
|---|---|
| B1 顾客付款 | 小程序 → nginx `www` `/mp` → `shop-app` **`ConsumerTokenAuthFilter`** → 订单服务 → `RemotePaymentPort`（参数带 `CallSource`：服务、实例、IP、追踪号、上游接口 `/mp/...`、顾客 IP、顾客账号）→ `/internal/pay/v1` → `pay-svc` **`InternalGuard`**（记录）→ `PaymentService` → 返回支付参数 → 小程序拉起支付 |
| B2 商家 / 运营的支付功能（运营前端方案见 §2.8） | b-app → `/biz/pay/...` → `shop-app` **`MerchantTokenAuthFilter` + `BizContextFilter`**（`@perm.canBiz('mch:pay:*')`）；ops-web → `/ops/pay/...` → `shop-app` **`OperatorTokenAuthFilter`**（`@perm.can(Perms.PAY_*)`）→ 薄 controller → 远程客户端（`CallSource.operatorNo` = 员工号）→ `/internal/pay/v1/biz/**` · `/internal/pay/v1/ops/**` → `pay-svc` **`InternalGuard`** → 支付应用服务。**认人、判权限都在 `shop-app` 用平台账号体系完成**，支付只记是谁 |
| B3 支付回调业务 | `pay-svc` `PaymentOutcomeService` → `pay_biz_notify(INTERNAL_EVENT)`（Outbox、退避重试、按 `event_key` 幂等）→ `/internal/shop/v1/pay-events` → `shop-app` **`InternalCallFilter`**（记录）→ 订单置已付 / 餐饮账单结清 / 售后推进 |
| B4 支付反查核心 | `pay-svc` 里由核心域实现的 Port（`MerchantQueryPort`、`SettleSourcePort`、积分、权益…）的远程实现 → `/internal/shop/v1/**` → `shop-app` **`InternalCallFilter`** → 核心服务 |

#### 2.7.3 `CallbackGuard`（新）

今天：`SecurityConfig#channelCallbackChain` 对 `/pay/callback/**` 全部放行，原文落库与验签在 `ChannelPayCallbackController` → `CallbackTemplate` 里。改为过滤器后，controller 只做业务（查单、入账）：

| 步 | 做什么 | 失败 |
|---|---|---|
| 1 | 解析路径 `/pay/callback/{channel}/{notifyType}/{ref}`，`ref` = 收款商户号或进件单号 | 路径不合法 → 按渠道要求的失败格式回，记录 |
| 2 | 缓存请求体与头，**先落原文**：加密全量写 `pay_raw_payload`、摘要写 `pay_callback_log` —— 永远先于验签（安全规范 §2.4） | — |
| 3 | 按 `{channel}` 取验签器（`WechatCallbackVerifier` · `AlipayCallbackVerifier` · 蓝新 / LINE Pay / PayBy 各自一个，SPI `ChannelCallbackVerifier`），用**该收款商户自己的**平台证书 / 密钥验签（`ref` 定位收款商户 → 冻结的密钥版本） | 验签失败 → 回渠道约定的失败响应，`pay_callback_log.verify_result=FAILED`，不进 controller |
| 4 | 微信：时间窗 ±5 分钟、按 `Wechatpay-Serial` 选证书（修安全规范 G4） | 同上 |
| 5 | 报文里的商户号与路径 `ref` 交叉校验（防 A 商户的回调打到 B 的地址） | 同上 |
| 6 | 放行到回调 controller：主动查单 → 金额币种比对 → 按渠道交易号幂等入账 | 业务失败按渠道要求回「重试」 |

不做：来源 IP 白名单（渠道不承诺固定 IP，安全规范 §2.4）；时间窗与 nonce 以外的通用签名规则 —— 每家渠道的规则不同，全在各自的验签器里。

#### 2.7.4 内部调用过滤器：`InternalGuard`（支付侧）与 `InternalCallFilter`（业务侧）

两个方向一套规则（用户 2026-10-27 定：暂不做权限校验，只识别与留痕）：

| 步 | 做什么 |
|---|---|
| 1 | 只管 `/internal/pay/v1/**`（支付侧）或 `/internal/shop/v1/**`（业务侧） |
| 2 | 读 `X-Internal-Caller`（调用方服务码：`SHOP_APP` / `PAY_SVC` / `JOB_WORKER` / 以后的 `FOOD_SVC` …） |
| 3 | 解 `X-Call-Source` → `CallSource`（服务、业务线、实例、自报 IP、追踪号、上游接口、来源端、终端用户 IP、UA 摘要、操作人）；缺失或损坏**照样放行**，记 `UNKNOWN` |
| 4 | 追踪号接力：`trace_id` 沿用 `CallSource.traceId`，并写进本进程的 MDC，日志能按一个号串起两边 |
| 5 | 放行 |
| 6 | 结束时写记录：支付侧写 `pay_api_request_log`（`entry=INTERNAL_HTTP`，V16 的来源列）；业务侧写 `shop-app` 现有的接口访问日志（`ApiAccessLogFilter`）并带上同样的来源字段 |
| 开关 | `verify-credential`（默认 `false`）：打开后恢复今天 `InternalCallerFilter` 的「调用方码 + 凭据哈希」校验，不改代码 |
| 网络 | 唯一防线是网络隔离：nginx 所有 server `deny /internal/`，两个服务的内部端口只对内网 |

**发起方不手填来源**：`shop-base` 提供 `CallSourceClientInterceptor`（给内部 HTTP 客户端 `InternalHttp` 统一挂上），每次远程调用自动带 `X-Internal-Caller` 与 `X-Call-Source`（取 `CallSources.current()`）—— 这就是「统一在调用参数里封装」：`PaymentPort` 等接口的命令对象带 `CallSource` 字段，远程实现由拦截器把它写进头，进程内实现（过渡期内嵌）直接传对象。

两边共用的类都放在 `shop-base` `spi.pay`（以后可挪到更中性的 `spi.call`）：`CallSource` · `CallSources` · `CallSourceCodec` · `InternalHeaders`；两个过滤器各自留在自己的服务里，只差「写哪张日志表」。

#### 2.7.5 切到这个形态要补的

| # | 缺口 | 现状（2026-10-27 核对） |
|---|---|---|
| D1 | `pay-svc` 装 `pay-portal`、`pay-guard`、`secret-core`；`PayHasNoControllerTest` 改为「controller 只在 `pay-portal`」 | `pay-svc` 只依赖 `pay-domain`、`pay-channel` |
| D2 | **商家 / 运营的支付功能改走业务服务**：`pay-portal` 的 `api.biz.*`（5 个 controller）、`api.ops.*`（5 个）拆成两半 —— `shop-app` 里的薄 controller（认人、判权限）+ `pay-svc` 的 `/internal/pay/v1/biz/**`、`/ops/**` 内部端点 | 今天这 10 个 controller 在 `pay-portal`，直接挂 `/biz/pay`、`/pay-ops/v1` |
| D3 | 反查核心的 Port 全部有远程实现（B4）+ `shop-app` 的 `/internal/shop/v1/**` 端点 | `MerchantQueryPort`、`SettleSourcePort`、积分、权益等只有进程内实现 |
| D4 | 支付结果回传（B3）：`pay_biz_notify` 的 `INTERNAL_EVENT` 投递 + `shop-app` `/internal/shop/v1/pay-events` | 只实现了 `WEBHOOK` 投递；内部结果今天进程内直调 |
| D5 | `CallbackGuard`；回调 controller 从 `shop-core` `ChannelPayCallbackController` 迁到 `pay-portal` | 验签在 controller 里；回调端点在 `shop-core` |
| D6 | `InternalCallFilter` + `CallSourceClientInterceptor` + `shop-app` 的 `/internal/shop/v1` 安全链 | 无 |
| D7 | `shop-app` 切 `shop.pay.deployment=standalone` | `RemotePaymentPort` 已有；少数服务的远程实现未做；默认内嵌 |
| D8 | nginx：`api.{域名}`（`/open/pay/v1`、`/pay/callback`）、`terminal.{域名}`、`pay.{域名}` → `pay-svc`；`www.{域名}` 全部 → `shop-app`（`/biz/pay`、`/pay-ops` 不再单独分流）。渠道后台登记的回调地址若在 `www` 域名下，nginx 保留 `/pay/callback` → `pay-svc` 一条，直到渠道后台改登记 | `/pay/callback/` 转 8081（`shop-app`）；`/open/pay/v1` 无规则 |
| D9 | `pay-svc` 库账号、密钥、主密钥注入配齐；部署 README「`pay-svc` 空跑应停用」改为「已承接流量」 | 空跑（`deploy/tencent/README.md` 第 9 条） |

数据库：两个服务今天连同一个库实例；支付表已是 `pay_` / `stl_` / `sec_` 前缀、独立迁移目录，切库（ADR-021 D2）可在服务拆开之后再做。

**将来把网关独立出来**：`pay-gateway`（外部 API、POS、渠道回调、收银台四个守卫 + 对应 controller）→ `/internal/pay/v1` → `pay-svc`（`InternalGuard` + 支付核心）；`shop-app` 照旧直调 `pay-svc` 的 `/internal/pay/v1`。网关把已认出的调用方随请求带给 `pay-svc`，`InternalGuard` 记来源 = `pay-gateway`。

### 2.8 支付运营管理前端

**结论**：支付不单独做运营前端。支付的运营页面是 **ops-web 里的一个模块**，与餐饮、零售、美业的运营页面同一个登录、同一套角色、同一张 `Perms`（G10）。
页面只调 `shop-app` 的 `/ops/**`；`shop-app` 认人、判权限、算数据范围，再经内部接口调支付。**`pay-svc` 从不接触运营令牌**。

#### 2.8.1 现状：两种接法并存（2026-10-27 核对）

| 接法 | 页面（ops-web） | 调哪 | 后端 | 规模 |
|---|---|---|---|---|
| **A 直连支付接入层** | `app/pay-ops/{orders,refunds,exceptions,notifies}` | `/pay-ops/v1/**`（`lib/api/https/payops.ts`） | `pay-portal` `api.ops.*`（5 个 controller）· `OpsSessionAuthenticator` | 4 页 |
| **B 业务服务转调** | `app/finance/*` 的支付相关 tab（通道与费率、对账、放款、应付、银行流水…） | `/ops/payments/**` · `/ops/settle/**` · `/ops/payables/**` | `shop-app` `portal.ops.pay.*`（24 个 controller，含积分、权益）→ `payclient.*AppService`（内嵌 / `Remote*` 两种实现，按 `shop.pay.deployment` 装配） | 十余个 tab |

另有订单菜单下的「支付」组（`/orders?tab=pay|repair|close`，权限 `order:pay:*`）。

**接法 B 就是 v4 要的形状**：它已经做到「ops-web → `shop-app` 认人判权 → 远程服务 → 支付」，独立部署时只需切换 `Remote*` 实现。接法 A 是唯一绕开业务服务、直连支付的运营入口 —— **统一到 B，取消 A**。

#### 2.8.2 目标调用链

```
ops-web（静态站点 /ops-web/，支付运营模块）
  │ 同源 /ops/payments/**，带运营令牌
  ▼
nginx www.{域名} /ops/**（办公网 / VPN 白名单）
  ▼
shop-app
  ├─ OperatorTokenAuthFilter        平台运营会话；角色、数据域、权限码每请求现算
  ├─ @perm.can(Perms.PAY_*)         权限码（平台 Perms，ops-web 角色管理分配）
  ├─ portal.ops.pay.Ops*Controller  薄 controller：算数据范围（员工授权的商家 / 门店）作为显式参数
  └─ payclient.Ops*AppService       Remote* 实现：CallSource（operatorNo = 员工号、origin_client = OPS_WEB、运营 IP）
  │ /internal/pay/v1/ops/**
  ▼
pay-svc
  ├─ InternalGuard                  识别来源、全量留痕（不认令牌、不判权限）
  └─ 支付运营应用服务                按传入的数据范围过滤；两人复核按 operatorNo 判「发起人 ≠ 复核人」
```

| 事 | 在哪做 | 说明 |
|---|---|---|
| 认人 | `shop-app` `OperatorTokenAuthFilter` | 平台账号体系 |
| 判权限 | `shop-app` 端点 `@perm.can` | `Perms.PAY_*` 等 |
| 数据范围 | `shop-app` 算出、作为请求参数传给支付 | 支付不读平台员工授权表 |
| 两人复核 | `pay-svc`（`pay_approval`） | 发起人、复核人取自 `CallSource.operatorNo`；**支付信任 `shop-app` 给的员工号**（内部调用不校验，G9） |
| 审计 | 两边各一行 | `shop-app` 访问日志 + `pay_api_request_log`（同一个 `trace_id`） |
| 明文密钥 | 运营看不到 | 运营页面只显示指纹（加解密 TDD §2.3） |

#### 2.8.3 菜单方案（`ops-web/lib/nav.ts`）

新增顶层模块 **「支付运营」**（`key: "pay"`，`modules: ["pay"]`），把散在「订单 · 支付」组、「结算与资金」和 `/pay-ops/*` 的纯支付页面收到一处；**结算、放款、应付、发票留在「结算与资金」**（读者是财务，不是支付运营）。

| 组 | 页面 | 路由 | 权限码 | 来源 |
|---|---|---|---|---|
| 交易 | 支付单 | `/pay/orders` | `pay:order:view` · `pay:order:manage` | 迁自 `/pay-ops/orders` |
| | 退款与复核 | `/pay/refunds` | `pay:refund:view` · `pay:refund:review` | 迁自 `/pay-ops/refunds` |
| | 异常单 | `/pay/exceptions` | `pay:order:manage` | 迁自 `/pay-ops/exceptions` |
| | 推送记录 | `/pay/notifies` | `pay:log:view` · `pay:notify:resend` | 迁自 `/pay-ops/notifies` |
| 对账 | 支付流水核对 | `/pay/recon?tab=flow` | `order:pay:read` | 迁自 `/orders?tab=pay` |
| | 掉单补偿 · 关单策略 | `/pay/recon?tab=repair` · `?tab=close` | `order:pay:repair` | 迁自 `/orders?tab=repair|close` |
| | 对账差异 · 覆盖率 | `/pay/recon?tab=diffs` | `finance:settle:read` | 迁自「结算与资金」 |
| 渠道与商户 | 支付通道与费率 | `/pay/channels` | `finance:rate:update` | 迁自 `/finance?tab=pay-channels` |
| | 收款商户与进件 | `/pay/merchants` | `pay:merchant:*`（新） | 新（I4 进件已有后端） |
| | 刷卡终端 | `/pay/terminals` | `pay:terminal:manage`（新） | 新（POS 入口，G5） |
| | 静态收款码 | `/pay/qr-codes` | `pay:qr:manage` | 后端已有（`PayOpsQrCodeController`），补页面 |
| 接入方 | 接入方与授权 | `/pay/apps` | `pay:app:manage`（新） | 新 |
| | 接入方密钥（只看指纹 / 吊销） | `/pay/apps/{appNo}/credentials` | `pay:app-credential:revoke`（新） | 新 |
| | 请求记录 | `/pay/request-logs` | `pay:log:view` | 新（读 `pay_api_request_log`，含内部调用来源） |
| 密钥 | 平台证书与密钥 | `/pay/platform-keys` | `sec:key:view` · `sec:key:write` · `sec:key:enable`（新） | 新 |

新权限码一律登记在 `shop-base-auth` 的 `Perms`、进 ops-web 角色管理，**不在支付里另立**。迁移的旧路由保留跳转一个版本（`/pay-ops/*` → `/pay/*`、`/orders?tab=pay` → `/pay/recon?tab=flow`）。

#### 2.8.4 前端代码结构（ops-web）

```
ops-web/
├── app/pay/                         支付运营模块（取代 app/pay-ops/）
│   ├── orders/page.tsx              ♻️ 自 app/pay-ops/orders
│   ├── refunds/page.tsx             ♻️
│   ├── exceptions/page.tsx          ♻️
│   ├── notifies/page.tsx            ♻️
│   ├── recon/page.tsx               ♻️ 汇总订单「支付」组与对账 tab
│   ├── channels/page.tsx            ♻️ 自 app/finance/pay-channel-tab.tsx
│   ├── merchants/page.tsx           🆕
│   ├── terminals/page.tsx           🆕
│   ├── qr-codes/page.tsx            🆕
│   ├── apps/page.tsx · apps/[appNo]/credentials/page.tsx   🆕
│   ├── request-logs/page.tsx        🆕
│   ├── platform-keys/page.tsx       🆕
│   └── shared.tsx                   ♻️ 自 app/pay-ops/shared.tsx
├── lib/api/contracts/pay*.ts        ♻️ payops.ts 改为 /ops/payments/** 路径；新增 payApps / payTerminals / payMerchants / payKeys / payLogs
├── lib/api/https/pay*.ts · lib/api/mocks/pay*.ts      同上（https 与 mocks 成对）
├── lib/nav.ts                       新增「支付运营」模块；移出三处旧入口
└── lib/i18n/messages/{zh,en}.ts     菜单与页面文案
```

**按仓库规矩要一起做的**：改了 `nav.ts`、加了页面 → 重跑 `python3 scripts/gen-ui-catalog.py` 并提交 `docs/technical/design/ui-catalog.json`（CLAUDE.md「改了界面就要更新界面清单」）；新页面先画原型（`prototypes/<slug>.html` + `registry.json`）。

#### 2.8.5 后端配套（接到 D2）

| # | 改什么 | 说明 |
|---|---|---|
| E1 | `pay-portal` `api.ops.*` 5 个 controller → `shop-app` `portal.ops.pay.*`（`OpsPayOrderController` · `OpsPayRefundController` · `OpsPayExceptionController` · `OpsPayNotifyController` · `OpsPayQrCodeController`），路径 `/ops/payments/{orders,refunds,exceptions,notifies,qr-codes}` | 照接法 B：controller 判权，调 `payclient` 新服务 |
| E2 | `payclient` 新增 `OpsPayOrderAppService` 等 5 个（内嵌实现直调支付应用服务；`Remote*` 实现经 `/internal/pay/v1/ops/**`） | 与现有 `OpsFeeRuleAppService` / `RemoteOpsFeeRuleAppService` 同一形状 |
| E3 | `pay-svc` 新增 `/internal/pay/v1/ops/**` 内部端点，入参带数据范围与操作人 | `InternalGuard` 记录；`PayHasNoControllerTest` 的白名单随 D1 调整 |
| E4 | 新功能（接入方、终端、收款商户、请求记录、平台密钥）一律按 E1–E3 的形状新建，不再往 `/pay-ops/v1` 加 | — |
| E5 | 删除 `/pay-ops/v1` 入口：`PayEntry.OPS`、`SecurityConfig#payOpsChain`、nginx `/pay-ops/` 段、`OpsSessionAuthenticator` / `OpsGuard` | 在 E1 上线、旧路由跳转期过后 |
| E6 | 商家端同理：`pay-portal` `api.biz.*` 5 个 controller → `shop-app` `portal.biz.pay.*` + `payclient` + `/internal/pay/v1/biz/**`；b-app 的支付页面调 `/biz/payments/**` | D2 的另一半 |

---

## §3 类清单

### 3.1 `pay-guard`（新模块，包 `ai.neargo.shop.pay.api.guard`）

| 子包 | 类 | 状态 | 说明 |
|---|---|---|---|
| `guard` | `PayEntry` | ♻️ 自 `pay-portal` `api.PortalFace` | 六个入口 + 前缀；加 `INTERNAL("/internal/pay/v1")`；`PortalRouteRules` 改引用它 |
| `guard.core` | `GuardFilter` | ♻️ 自 `api.auth.PortalAuthFilter` | 管线 ①–⑫；请求记录写法原样保留，`entry` 加 `INTERNAL_HTTP` |
| `guard.core` | `GuardFilters` | ♻️ 自 `api.auth.PortalAuthFilters` | 每个入口恰好一个 `EntryGuard` |
| `guard.core` | `EntryGuard` · `GuardResult` · `GuardedRequest` · `GuardedCaller` | ♻️ 自 `PortalAuthenticator` / `PortalPrincipal` | §2.2 接口 |
| `guard.core` | `PortalActions` | ♻️ 自 `api.auth.PortalActions` | 路径 → 动作，供请求记录 |
| `guard.core` | `CachedBodyRequest` · `GuardMasking` | ♻️ 自 `api.common.CachedBodyRequest` / `api.auth.PortalMasking` | 脱敏加 `X-Signature`、`encryptedCardData`、`x-sensitive`、`sk_(live|test)_…` |
| `guard.core` | `GuardErrors` | 🆕 | 失败原因 → 对外错误码（生产统一 / 沙箱细分） |
| `guard.sign` | `SignatureCanonicalizer` | ♻️ 自 `pay-domain` `openapp.SignatureCanonicalizer` | 请求待签串（v1 冻结）+ 响应待签串 P2；外部 API 与 POS 共用 |
| `guard.sign` | `HmacVerifier` · `ResponseSigner` | 🆕 | `KeyVault.mac` + 常量时间比较 / P2 |
| `guard.sign` | `NonceStore` · `DbNonceStore` · `RedisNonceStore` | ♻️ 自 `pay-domain` `openapp.*` | Mapper 留在 `pay-domain` |
| `guard.crypto` | `SensitiveFields` · `FieldDecryptor` · `ResourceCipher` | 🆕 | P3 / P4 / P5 |
| `guard.internal` | `InternalGuard` | ♻️ 自 `pay-svc` `svc.InternalCallerFilter` | 只识别 `X-Internal-Caller` + `X-Call-Source`，不拒绝；`verify-credential` 开关保留原校验 |
| `guard.internal` | `CallSourceCodec` | 🆕 | `CallSource` ↔ `X-Call-Source` 头；超长截断、解析失败记 `UNKNOWN` |
| `guard.internal` | `InternalCallRecorder` | 🆕 | 内嵌形态：包住 `PaymentPortImpl` 每个方法，写 `entry=INTERNAL_PORT` 的记录（含 `CallSource` 全部字段、业务单号、金额、结果、耗时） |
| `guard.internal` | `InternalCallerRegistry` · `ConfigInternalCallerRegistry` | ♻️ 自 `pay-svc` | 仅 `verify-credential=true` 时用 |
| `guard.internal` | `InternalCallers` | ♻️ 自 `pay-domain` `acquiring.port.InternalCallers` | 内嵌形态的 `PayCaller` 工厂 |
| `guard.open` | `OpenApiGuard` | ♻️ 自 `OpenSignatureAuthenticator` + `AppAuthServiceImpl` 的验证部分 | §2.3 B |
| `guard.open` | `AppSignatureVerifier` · `SecretAppSignatureVerifier` · `ConfigAppSignatureVerifier` | 🆕（取代 `AppKeyResolver` 三件） | 加解密类清单 §4.1 |
| `guard.open` | `PartnerKeyService` · impl | ♻️ 自 `pay-domain` `openapp.service.PartnerKeyService`（K1-DB） | |
| `guard.open` | `AppScopes` · `IpAllowlist` | ♻️ 自 `AppAuthServiceImpl#requireScope` / `callerFor` / `ipAllowed` | IP 白名单只有外部 API 用 |
| `guard.terminal` | `TerminalGuard` · `TerminalActivationService` · `TerminalKeyService`（+ impl） | 🆕 | §2.3 C |
| `guard.ops` | `OpsGuard` | ♻️ 自 `api.auth.OpsSessionAuthenticator` | §2.3 D：只确认平台运营会话 → `PayCaller(OPS)` |
| `guard.biz` | `BizGuard` | ♻️ 自 `api.auth.BizSessionAuthenticator` | 规则不变 |
| `guard.checkout` | `CheckoutGuard` | ♻️ 自 `api.auth.CheckoutVisitorAuthenticator` | 规则不变；`CheckoutServiceImpl` 两处 `new PayCaller` 改调这里的工厂 |
| `guard.web` | `CallerArgumentResolver` | 🆕 | controller 参数写 `PayCaller caller` 即注入 |

### 3.2 其他模块的变化

| 模块 | 变化 |
|---|---|
| `shop-base` | 新增 `spi.pay.CallSource` · `spi.pay.CallSources`；`PaymentPort` 两个命令加 `source`（保留旧构造器）、`queryPayment` / `closePayment` 加重载（旧方法改 `default`） |
| `shop-app` | `SecurityConfig` 引 `GuardFilters`、新增 terminal 链；独立形态的 `RemotePaymentPort` 写 `X-Call-Source` 头；`CallSources` 的上下文来源（`RequestMetaFilter` 已有的可信 IP 与请求号） |
| `pay-portal` | 删 `api.auth.*`（10 个类）与 `api.common.CachedBodyRequest`；`PortalFace` → `PayEntry`；新增 `api.terminal.*` controller；依赖加 `pay-guard` |
| `pay-svc` | 删 `InternalCallerFilter` / `InternalCallerRegistry` / `ConfigInternalCallerRegistry`；依赖加 `pay-guard`；`PayApplicationBootTest` 增「只装 INTERNAL 一个守卫」 |
| `pay-domain` | 移出 `openapp.SignatureCanonicalizer` / `NonceStore` / `DbNonceStore` / `AppKeyResolver` / `ConfigAppKeyResolver` / `secret.SecretAppKeyResolver` / `AppAuthService(Impl)` 的验证部分 / `PartnerKeyService(Impl)` / `acquiring.port.InternalCallers`；`AppAuthServiceImpl#checkDailyRefund` 留下改名 `AppRefundLimitService`；`PaymentPortImpl` 由 `InternalCallRecorder` 包一层；新增 `terminal.entity.*` 与 `mapper.terminal.*` |
| `backend/pom.xml` · `pay/pom.xml` | 加模块 `pay-guard` |

### 3.3 数据库

**`pay_api_request_log` 加列**（`db/pay` **V16**；V13 / V14 已排给 W6 对账、V15 给终端，开放 API 加解密另走 V17 —— 开发计划 §0.2）：

| 列 | 类型 | 说明 |
|---|---|---|
| `source_service` | `VARCHAR(64)` NULL | 调用方服务名（内部调用必填，取不到为 `UNKNOWN`） |
| `source_module` | `VARCHAR(24)` NULL | 调用方业务线：MALL / FOOD / RETAIL / BEAUTY … |
| `source_instance` | `VARCHAR(128)` NULL | 调用方实例（主机名 / pod） |
| `source_ip` | `VARCHAR(64)` NULL | 调用方**自报**的服务器 IP；`client_ip` 仍是支付**实际看到**的对端 IP（内嵌形态为空） |
| `origin_api` | `VARCHAR(128)` NULL | 触发这次调用的上游接口或任务名 |
| `origin_client` | `VARCHAR(24)` NULL | 上游请求的来源端 |
| `origin_ip` | `VARCHAR(64)` NULL | 上游请求的终端用户 IP |
| `origin_ua_digest` | `CHAR(64)` NULL | 上游 User-Agent 摘要 |
| 索引 | `idx_pay_api_request_log_6 (source_service, received_at)` | 按服务查调用量与失败 |

沿用已有列：`trace_id` = `CallSource.traceId`（有就沿用、没有才生成）、`entry` = `INTERNAL_PORT` / `INTERNAL_HTTP`、`source_type` = `INTERNAL`、`app_no` = 调用方码（`MALL` / `FOOD`）、`operator_no`、`biz_type` / `biz_no`、`request_amount_minor` / `request_currency`、`outcome`、`elapsed_ms`。

**新表**（随 G4）：`pay_terminal` · `pay_manager_code` · `pay_terminal_batch` · `pay_terminal_activation`（激活码，只存哈希）。v1 的 `pay_ops_step_up` 取消（G10）。

---

## §4 实施切片

| 片 | 内容 | 行为变化 | 依赖 |
|---|---|---|---|
| **G1 搬家** | 新建 `pay-guard`；§3.1 标 ♻️ 的类原样搬（改包名，不改逻辑）；`pay-portal` / `pay-svc` 改依赖；`PortalFace` → `PayEntry` | **无**（`OpenApiFlowTest`、`PayApplicationBootTest`、`OpsEndpointPermTest` 原样通过即验收） | K1-DB 合入 `codex` |
| **G2 管线** | `GuardFilter` 拆出 ①–⑫ 与 `EntryGuard`；`PayCallerConstructionTest` 生效；`OpsGuard` 收窄为「确认平台会话 → `PayCaller`」 | 无对外变化 | G1 |
| **G3 内部调用留痕** | `CallSource` / `CallSources` / `CallSourceCodec`；`PaymentPort` 加字段与重载；`InternalCallRecorder`；`InternalGuard` 改为只识别（`verify-credential` 默认关）；`pay_api_request_log` 加列 | 内部调用开始写记录；独立形态 `pay-svc` **不再拒绝**未带凭据的内部请求 | G2 |
| **G4 外部 API 补全** | `AppSignatureVerifier` 取代 `AppKeyResolver`；`X-Key-Serial`；响应签名；字段解密；细分错误码 | 对外新增响应签名头、两个错误码；签名 v1 待签串不变 | G2 |
| **G5 POS 终端** | 终端表 + `TerminalGuard` + 激活 + 设备密钥 + `api.terminal.*` | 新入口 | G2；POS App 签名拦截器同一组向量 |

v1 的「运营端 step-up」「内部调用签名」两片取消（G9 / G10）。

---

## §5 测试与守卫

| 测试 | 管什么 |
|---|---|
| `GuardPipelineTest` | 管线顺序：验证失败时不抢 nonce、不解密、不进 controller；失败都写请求记录 |
| `EntryIsolationTest`（参数化） | 外部 API / POS / 运营端 / 商家端 / 收银台的凭证互打：只有对应入口放行，其余 401（G2；内部入口不在矩阵内） |
| `InternalCallRecorderTest` | 内嵌调用：成功、业务异常、未预期异常三种都写一行 `INTERNAL_PORT` 记录，`CallSource` 每个字段落到对应列；旧构造器自动带上 `CallSources.current()` |
| `InternalGuardTest` | 独立形态：`X-Call-Source` 解析进记录；头缺失或损坏照样放行并记 `UNKNOWN`；`verify-credential=true` 时恢复原 401 行为 |
| `CallSourcesTest` | HTTP 请求上下文、定时任务、Outbox 消费者三种场景取到的来源；终端用户 IP 只取可信代理头（`ClientMeta`） |
| `OpenApiGuardTest` | 加解密 TDD 的 P1–P3 向量；`X-Key-Serial`；重叠期两版本；响应签名可验 |
| `TerminalGuardTest` | 激活码单次、过期；挂失后下一次请求 401；响应签名；加密卡数据不进记录 |
| `OpsGuardTest` | 非运营令牌 401；平台改角色后下一个请求的 `PayCaller` 权限随之变；守卫不含任何权限码字面量 |
| `PayCallerConstructionTest`（ArchUnit，新） | `new PayCaller(...)` 只允许出现在 `ai.neargo.shop.pay.api.guard..`（G7） |
| `GuardDependencyTest`（ArchUnit，新） | `pay-portal` / `pay-svc` 的 controller 不读 `X-App-Id` / `X-Terminal-Sn` / `X-Internal-*` / `X-Call-Source` / `X-Signature`；`pay-guard` 不依赖 `pay-portal` / `pay-svc`；`guard.ops` 不引用 `Perms`（权限判定只在端点注解，G10） |
| 现有 `OpenApiFlowTest` · `PortalRouteRulesTest` · `OpsEndpointPermTest` · `PayApplicationBootTest` · `SchemaDriftTest` | 每片后原样通过 |

---

## §6 选型

| 编号 | 问题 | 方案 | 结论 |
|---|---|---|---|
| S1 | 守卫放哪 | A 新模块 `pay-guard` · B 留在 `pay-portal` · C 放 `pay-domain` | **A**：B 让 `pay-svc` 为一个内部过滤器依赖全部对外 controller；C 让支付领域依赖 servlet 与会话 |
| S2 | 内部调用 | — | **已定（用户，2026-10-27）**：不校验，只留痕；来源封装在调用参数（`CallSource`） |
| S3 | 管线形态 | A 每个入口一个独立过滤器、各写一遍 · B 一个 `GuardFilter` + 每入口一个 `EntryGuard` | **B**：今天的 `PortalAuthFilter` 已是这个形状 |
| S4 | 运营端 | — | **已定（用户，2026-10-27）**：平台账号体系，支付不另设规则 |
| S5 | 内部调用来源怎么传 | A 调用方手填参数 · B 参数里带 `CallSource`，由 `CallSources.current()` 统一生成 · C 支付侧从线程上下文自己读 | **B**：按用户「统一在调用参数封装」；A 会漏填、各填各的；C 在独立形态（跨进程）拿不到 |

---

## §7 实施记录与偏差

代码在分支 `claude/pay-guard-m1`，按[开发计划](./开发计划-支付接入守卫与独立部署.md)的任务号一条一个提交。

| 任务 | 与本篇的偏差 | 原因 |
|---|---|---|
| M1.5 | 商家端 controller 不经参数拿 `PayCaller`，仍由 `BizPayContext.caller(storeId)` 换 | 参数解析在 `@PreAuthorize` 之前；商家调用方要按请求里的门店圈，门店须先过门店校验。M5a 商家端改走业务服务时一并去掉 |
| M2.1 | `CallSource.originUserAgent` → `originUaDigest` | 字段只装摘要，名字照实说；与 V16 列 `origin_ua_digest` 同名 |
| M2.1 | `CallSources.job(name)` → `asJob(name, body)` + `asOutbox(eventType, body)`，另有 `bind(Origin)` | 来源要在一段执行期间有效（任务体里任意深处调支付都取得到），返回一个值做不到；Outbox 单列来源端 `OUTBOX`，M2.7 的抽样只针对 `JOB` |
| M2.1 | `originClient` 沿用认证链已有端码（`APP_C` / `APP_BIZ` / `WEB_OPS` / `H5` / `PARTNER`），不另起 `MINI_PROGRAM` 等 | 端码全仓一套，访问日志、登录日志已在用；另起一套就要维护映射 |
| M2.1 | `CallSources.current()` 不填 `module` | 调用方进程不知道这次是哪条业务线；由支付侧按业务类型补（`CallSource#withModuleIfAbsent`，M2.4） |
| M2.1 | HTTP 来源由 MVC 拦截器 `CallSourceInterceptor`（`shop-base-auth`）绑定，不在 `CallSources` 里读 `RequestContextHolder` | `shop-base` 没有 Servlet（模块注释的约束）；拦截器处认证链已跑完、路由模板已匹配，IP 只经 `ClientMeta` |
| M2.1 | 只挂了进程内任务执行器（`InProcessJobConfig`）与 Outbox 分发器；直接 `@Scheduled` 的方法未绑定，来源端记 `UNKNOWN` | 63 个 `@Scheduled` 类不逐个改；补偿查单若走 `@Scheduled`，M2.7 抽样前再在调度器上加 `TaskDecorator` |
| M2.2 | 两个命令的 `source` 标 `@JsonIgnore`：<b>不进</b> `/internal/pay/v1` 的请求体 | 请求体的契约不变（新旧版本混部时 `pay-svc` 不会遇到认不出的字段）；来源只走 `X-Call-Source` 头（M2.3），接收方以头为准、不会两处对不上 |

---

## 待确认

| # | 问题 | 倾向 |
|---|---|---|
| ~~Q1~~ | ~~模块名与包根~~ | **已定（ADR-045）**：模块 `pay-guard`，包根 `ai.neargo.shop.pay.api.guard` |
| Q2 | 独立形态 `pay-svc` 真正上线前，`internal.verify-credential` 是否打开 | 上线评审时再定；在此之前内部入口的边界只有网络隔离（§2.3 A 边界说明） |
| Q3 | `CallSource` 是否也用于储值、权益等其他内部 Port（不止 `PaymentPort`） | 是：同一个类放在 `spi.pay`，其他 Port 迁移时照加；本篇只改 `PaymentPort` |
| Q4 | POS 激活码由运营生成还是商家端也能生成 | 两者都能（代码清单 §4.4） |
| ~~Q5~~ | ~~ADR 编号~~ | **已定**：[ADR-045](./ADR/ADR-045-支付接入守卫与网关集成在支付服务.md) |

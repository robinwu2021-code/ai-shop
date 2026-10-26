# TDD-支付服务-I5接入层

状态：实现中（S1–S4 已实现；S5 起未动）
档位：2 · 依据：[开发计划 · 支付服务](./开发计划-支付服务.md) §3.3–§3.6、§5 I5 · [完整设计方案](./TDD-支付服务-完整设计方案.md) §9 · [台湾阿联酋通道与开放 API](./TDD-支付服务-台湾阿联酋通道与开放API.md) §2.8 · [代码清单](./支付服务-代码清单-开放API刷卡POS内部调用.md) §3 · [ADR-044](./ADR/ADR-044-支付接入层模块pay-portal.md) · 配套：[接口清单](./支付服务-接口清单.md) · [安全规范](./支付服务-安全规范.md)
创建：2026-10-26 · 最后更新：2026-10-26

## L1 · 一句话

把支付从「只有内嵌调用」变成「谁都能按自己的身份接进来」：第三方经签名开放 API 建单、退款、收 webhook；顾客在统一收银台付款；
商家在 b-app 退款、代客收款、授权第三方；运营在 ops-web 的支付分区查每一笔的四向时间线、复核退款、管接入方。
**每个入口只认一种凭证，解析成同一个 `PayCaller`，过同一张能力矩阵**；I3 / I4 留下的转调与收口一并做完。

## 切片

| 片 | 内容 | 依赖 |
|---|---|---|
| S1 | 迁移 11（`pay_app_grant` · `pay_api_nonce` · `pay_qr_code`，与开发计划 §2 一致）+ 实体；`pay:*` / `mch:pay:*` 权限码与角色种子。接入方密钥**不建表**：归密钥模块 `sec_key`（表结构说明 §8.4），过渡期走部署注入 | — |
| S2 | `pay-portal` 公共底座：`PayCaller` 注入、统一响应与异常、`Idempotency-Key`、请求留痕 `pay_api_request_log`、限流、按入口的 CORS 与安全头；**回调入口自 `shop-core` 迁入，作为第六个面 `CALLBACK`**（复核 D-6） | S1 |
| S3 | 开放 API 鉴权：`SignatureCanonicalizer` + 示例向量、`AppAuthService`（启用 → IP → 时间窗 → nonce → 验签 → scope → 授权）、`NonceStore`、`ConfigAppKeyResolver` ⏸ | S2 |
| S4 | `PaymentPort#startPayment` + `RouteSelectionPolicy`（I3 偏差 10 / I4 偏差 18）：**资金路径改取收款商户**、**交易尝试行写冻结路由列**（复核 D-10 / D-12）；开放 API 建单 / 查单 / 选方式 / 关单（**补渠道关单**）/ 支付方式 | S3 |
| S5 | 退款入口收口：开放 API 退款、商家端退款、线下退款凭证；复核规则与两人复核（`pay_approval`）接到运营端 | S4 |
| S6 | 结果事件与通知：`PaymentSucceededEvent` / `PaymentClosedEvent` / `Refund*Event` 经 Outbox；`BizNotifier` → webhook（签名、退避 ≥ 24h、放弃、手动重推）；内部消费者改走事件（I3 偏差） | S4 |
| S7 | 收银台后端 `/checkout/v1`（动态码 / 静态码 / 托管收银台 / 结果）+ `checkout-h5` 最小版（`zh-TW` / `en` / `ar` + RTL） | S4 |
| S8 | 商家端 `/biz/pay`：退款、代客收款、流水与对账单、第三方授权、静态码、进件自助（**旧进件入口转调，I4 偏差 14**） | S5 · S7 |
| S9 | 支付运营端 `/pay-ops/v1` 第一、二批 + ops-web `/pay-ops/*` 分区；旧 `portal/ops/pay/**` 24 个 controller 迁入、旧路径转发一个版本周期 | S2 |
| S10 | 独立形态：`Remote*Service`、`pay-gateway` 进程装配、`/internal/pay/v1` 每调用方凭据（R12）；停双写、退役比对（I4 偏差 14 的前提） | S8 · S9 |

S3–S7 是开放 API 的主链（试点接入方的完成标准），S8 / S9 可与 S5–S7 并行，S10 收尾。

## §0 对账一 · 需求 → 设计：功能清单

编号 `F` = 功能，`AC` = 验收。「入口」列的前缀见[接口清单](./支付服务-接口清单.md)，鉴权细节见[安全规范](./支付服务-安全规范.md)。

### 0.1 公共底座（所有面向人与第三方的入口）

| F | 功能 | 验收 | 片 |
|---|---|---|---|
| F01 | 入口只认一种凭证：`PortalFace` 五个前缀各挂一个鉴权器，统一解析成 `PayCaller`（类型、调用方、商户 / 门店范围、动作、代谁操作） | 带错凭证类型（如商家令牌调 `/open`）一律 401，不进 controller | S2 |
| F02 | controller 不接受「我是谁」的入参：`PayCaller` 只经参数解析器注入，商户 / 门店范围由鉴权算出 | 请求体里塞别家 `merchantNo` / `storeId` 不生效（越权用例） | S2 |
| F03 | 能力矩阵逐格执行：每个应用服务方法第一行 `CallerPolicy.check`（I3 已有 `CapabilityMatrix`），矩阵与[台湾阿联酋 TDD](./TDD-支付服务-台湾阿联酋通道与开放API.md) §2.8.2 一致 | 矩阵每一格一条用例（允许 / 拒绝） | S2 |
| F04 | 幂等：所有写接口接受 `Idempotency-Key`（与 `outOrderNo` / `outRefundNo` 二选一）；同键同内容返回首次结果，同键不同内容 409 | 并发同键只落一单；请求摘要不同返回冲突 | S2 |
| F05 | 请求留痕 `pay_api_request_log`：每次请求一行（入口、调用方、路径、结果码、耗时、请求摘要；**体脱敏**）；鉴权失败记 `REJECTED_AUTH` | 签名错、时间窗外、nonce 重放各有一行且 controller 未执行 | S2 |
| F06 | 统一响应 `{code, message, data, requestId}` 与三语错误文案；未知异常不回栈、不回 SQL | 开放 API 与收银台不出现全局包装的双层结构 | S2 |
| F07 | 限流：开放 API 按 `pay_app.rate_limit_qps`；收银台按 IP + 令牌 / 码；终端按设备（I7）；超限 429 带 `Retry-After` | 压到阈值以上返回 429，且不写业务表 | S2 |
| F08 | 按入口的浏览器安全：开放 API / 终端 / 内部不开 CORS；收银台只许本域；运营端与商家端沿用各自域；安全头见安全规范 §4 | CORS 预检对非白名单源失败 | S2 |

### 0.2 开放 API（第三方）

| F | 功能 | 验收 | 片 |
|---|---|---|---|
| F10 | 签名 v1：`X-App-Id` · `X-Timestamp`（毫秒 ±300s）· `X-Nonce`（300s 不可重复）· `X-Signature`；HMAC-SHA256 或 RSA-SHA256；`stringToSign` 七行规则**发布即冻结** | 对外文档里的示例向量逐字通过；改任一字节验签失败 | S3 |
| F11 | 校验顺序：AppId 启用 → IP 白名单 → 时间窗 → nonce → 逐把有效密钥验签（轮换重叠期两把）→ scope → `pay_app_grant`，**全部在落库之前** | 每一步失败各一条用例，均 401 / 403 且不进业务 | S3 |
| F12 | 接入方与授权：`pay_app`（INTERNAL / PARTNER，已有）、`pay_app_grant`（门店授权 app 的动作 `COLLECT` / `REFUND` / `READ`）；接入方密钥在密钥模块（HMAC 信封加密、RSA 只存对方公钥），过渡期 `ConfigAppKeyResolver` 从部署注入读 | 未授权门店建单 403；撤销授权后立即生效 | S1 · S3 |
| F13 | 建单 `POST /orders`：接入方只给 `payMethod` / 场景，**不指定渠道与资金路径**；返回 `payOrderNo` + `checkoutUrl` 或 `PayInstruction` | 同 `outOrderNo` 同内容幂等、不同内容 409（I3 已有） | S4 |
| F14 | 选方式发起 `POST /orders/{no}/pay`（第三方自建收银页）· 查单（按号 / 按 `outOrderNo`）· 关单（关前查单，I3 已有） | 关单时已付 → 按结果入口入账、返回已付 | S4 |
| F15 | 门店可用支付方式 `GET /stores/{storeRef}/pay-methods`：收款路由（`PayMerchantQueryPort#collectableRoutes`）× 支付产品 × 市场 | 没有门店绑定的门店返回空，不兜底主体级号（R01） | S4 |
| F16 | 退款 `POST /orders/{no}/refunds`：`pay:refund` scope + 商家授权含退款 + 日限额 + 复核规则；命中复核返回 `PENDING_REVIEW` | 超日限额拒绝；命中规则不发往渠道 | S5 |
| F17 | 流水与日对账单：本 app 来源的流水分页、`GET /statements/{billDate}`（CSV） | 只看到自己来源的流水（来源可拆，AC25） | S6 |
| F18 | webhook：支付成功 / 关闭 / 退款成功 / 失败；接入方密钥签名（头与请求签名同构）；指数退避 ≥ 24h → `GIVEN_UP`；运营手动重推；`event_key` 幂等 | 接收方 5xx 重试、2xx 停止、超 24h 放弃；签名示例向量 | S6 |
| F19 | 沙箱：同一套接口，接入方 `env=SANDBOX` 时路由到 TEST 渠道；沙箱与生产密钥、域名分开 | 沙箱密钥调生产域名 401 | S3 |
| F20 | 对外文档 `docs/api/openapi-pay-open.yaml` + 接入指南（签名、示例向量、错误码、webhook、沙箱） | 文档里的示例请求能在沙箱跑通 | S3–S6 |

### 0.3 收银台（顾客，完全不受信）

| F | 功能 | 验收 | 片 |
|---|---|---|---|
| F30 | 动态码：`/c/{token}` → 会话（商户名、门店名、金额、可用方式）→ 选方式 → 跳渠道 → 结果；令牌随机 32 字节、库里只存哈希、15 分钟、可立即作废 | 关单 / 退款后令牌立即失效；过期 410 | S7 |
| F31 | 静态码：`/q/{code}` → 门店信息与限额 → 顾客输金额 → 服务端以码的来源建单并签发令牌；单笔 / 单码日累计 / IP 频率上限；一键停用 | 超单笔上限拒绝；停用后 404 | S7 |
| F32 | 托管收银台：开放 API 建单返回的 `checkoutUrl`（`/checkout/{payOrderNo}?t=`）同动态码 | 第三方只建单 + 跳转即可完成支付 | S7 |
| F33 | 结果页只展示「成功 / 处理中 / 失败」，**不入账**；`return_url` 只跳白名单域名 | 篡改 `return_url` 到非白名单域名不跳转 | S7 |
| F34 | 防换贴码：页头与金额确认页显示「收款方：商户名 · 门店名」；只返回最小字段，不出现任何个人信息 | 字段白名单用例 | S7 |
| F35 | `checkout-h5` 工程：独立域名、首屏 < 100KB、`zh-TW` / `en` / `ar`（RTL 闸门）、登记 `ui-catalog.json`（生成器加 `checkout` 端） | `gen-ui-catalog.py --check` 通过；RTL 闸门通过 | S7 |

### 0.4 商家端（b-app，`/biz/pay`）

| F | 功能 | 权限码 | 片 |
|---|---|---|---|
| F40 | 发起退款（原路）、查退款、命中复核时显示「待平台复核」 | `mch:pay:refund` | S8 |
| F41 | 线下退款：上传凭证、标记已退（命中复核时须平台通过后才能标记） | `mch:pay:refund-offline` | S8 |
| F42 | 代客收款：生成动态码（金额、备注、有效期） | `mch:pay:refund` 同组 | S8 |
| F43 | 本店流水、日对账单、结算单、平台费应收（I6 前只读已有数据） | `mch:pay:statement` | S8 |
| F44 | 授权第三方代本店操作：选 app、选门店、选动作（收款 / 退款）、日限额；撤销 | `mch:pay:app-grant` | S8 |
| F45 | 静态码：生成 / 停用本店码（打印用）；店长授权码与终端随 I7 | `mch:pay:qr` | S8 |
| F46 | 进件自助：建单、填资料、提交、看驳回原因、重提（接 I4 `OnboardingService`）；**旧 `/biz/merchant/payment*` 转调**，同时停双写、退役比对 | `mch:pay:onboarding` | S8 · S10 |

### 0.5 支付运营端（ops-web `/pay-ops/*`，`/pay-ops/v1`）

| F | 功能 | 权限码 | 复核 | 片 |
|---|---|---|---|---|
| F50 | 支付单列表与**四向时间线**（交易尝试、退款、流水、渠道调用、回调、通知）；主动查单、异常关单 | `pay:order:view` / `manage` | — | S9 |
| F51 | 退款列表与复核（**只复核不发起**）；两名不同人员先后通过，规则最近修改人不能复核 | `pay:refund:view` / `review` | 两人 | S5 · S9 |
| F52 | 复核规则：新版本 → 第二人确认后 `ACTIVE`；无 ACTIVE 规则的市场一律复核 | `pay:refund-rule:manage` | 两人 | S5 · S9 |
| F53 | 四向记录：API 请求、渠道调用（脱敏 / 原文分权）、回调（重放）、业务通知（重推） | `pay:log:view` / `raw` · `pay:callback:replay` · `pay:notify:resend` | — | S9 |
| F54 | 接入方：登记、启停、scope、IP 白名单、限流、发放 / 吊销密钥（只回指纹，明文只显示一次） | `pay:app:manage` / `pay:app-credential:issue` | 发放与启用分人 | S9 |
| F55 | 进件与收款商户：进件单查看 / 代建 / 提交 / 回查 / 撤销、资料原文（单独授予、全程审计）；收款商户启停、门店绑定 | `pay:onboarding:*` · `pay:merchant:*` | 启用需复核 | S9 |
| F56 | 地区、渠道、支付产品开关 | `pay:market:*` · `pay:channel:*` · `pay:product:manage` | — | S9 |
| F57 | 静态码风控停用；渠道运行（跟进项、生命周期、暂停 / 恢复商户） | `pay:qr:manage` · `pay:channel:manage` | — | S9 |
| F58 | 旧运营接口迁移：`portal/ops/pay/**` 24 个 controller 迁入 `pay-portal`（结算、出款、对账、积分、权益…），旧路径转发一个版本周期；积分、权益拆出各自权限码（R35） | 原权限码 → `pay:*` 映射 | — | S9 |
| F59 | 审计：所有资金动作与密钥动作写审计（操作人、IP、前后值、原因），只增不改 | — | — | S2 · S9 |

### 0.6 内部系统与收口

| F | 功能 | 验收 | 片 |
|---|---|---|---|
| F60 | `PaymentPort#startPayment`：选方式 → 选路由 → 冻结快照（收款商户 id + 密钥版本）；商城、餐饮下单改走它 | 读切后路由来自门店绑定；退款按冻结值 | S4 |
| F61 | 结果事件经 Outbox：商城 `markPaid`、餐饮结账改由消费者处理；`OrderPaidReconciler` 重放 | 事件丢失时重放补齐；重复事件幂等 | S6 |
| F62 | 内部调用方注册成 `pay_app(INTERNAL)`：流水、对账能按来源拆 | 商城与餐饮流水分开统计 | S1 |
| F63 | 独立形态：`/internal/pay/v1` 每调用方一把凭据 + 路径授权（R12）、常量时间比较；`Remote*Service` | 用 A 的凭据调 B 的路径 403 | S10 |
| F64 | 停双写、退役比对（I4 偏差 14）：读切稳定一个结算周期后，进件与绑定只写新表，`mch_payment_merchant` 只读 | 停双写后旧入口全部转调；比对任务下线 | S10 |

### 0.7 前端与生成物

| F | 内容 | 片 |
|---|---|---|
| F70 | ops-web `/pay-ops/*` 分区、独立菜单与契约文件；改 `nav.ts` 后重跑 `gen-ui-catalog.py` | S9 |
| F71 | b-app 退款、代客收款、报表、授权、进件页面；改 `pages.json` 后重跑；`vue-tsc` 通过 | S8 |
| F72 | `checkout-h5` 新工程；`ui-catalog` 生成器加 `checkout` 端 | S7 |
| F73 | 原型：新界面先画 `prototypes/<slug>.html`、登记 `registry.json`、每屏挂锚点 | S7–S9 |

### 0.8 完成标准（开发计划 I5）

- 试点接入方在**沙箱**完成：建单 → 顾客在收银台付款 → 收到 webhook → 退款（命中复核时经运营两人复核）。
- 运营端能查任意一笔的四向时间线。
- 全量回归不新增失败；pre-push 闸门全绿（含 `vue-tsc` 两端）。

## §1 现状与影响面

- **已就位（I0–I4）**：`pay-portal` 骨架（`PortalFace` 五个前缀、`PortalRouteRules` 守卫、`PortalAuthenticator` 接口）；`access.PayCaller` / `PayAction` / `CapabilityMatrix` / `CallerPolicy`；
  支付单、退款、复核规则、`pay_approval`、`pay_checkout_session`（I3 迁移 8）；`pay_app` 与内部 app 种子 `MALL` / `FOOD`（迁移 7）；四向记录表（迁移 5）；
  收款路由 `PayMerchantQueryPort` 与读切（I4）；支付侧进件服务（I4 S7）。
- **还没有**：任何面向人或第三方的支付 controller（`PayHasNoControllerTest` 守着 `pay-domain`）；开放 API 鉴权；webhook；收银台；`pay:*` 权限码。
- **要迁的**：`shop-app` `portal/ops/pay/**` 24 个 controller（结算、出款、对账、积分、权益…）、`portal/biz/pay/**` 3 个、旧进件入口 `/biz/merchant/payment*` 与 `/ops/onboarding*`。
- **文档里的前缀不一致，以本篇为准**：台湾阿联酋 TDD 写的是 `/merchant/pay/**`、`/ops/pay/**`，完整设计方案与开发计划（后定）是 `/biz/pay`、`/pay-ops/v1` —— 采用后者，与 `PortalFace` 一致。

## §2 方案（逐片细化在开工时补）

- 接口逐条见[接口清单](./支付服务-接口清单.md)；鉴权、密钥、nginx、网关与各端规范见[安全规范](./支付服务-安全规范.md)。
- 本篇是剩余计划的 I5 部分，整体排期见[完成度复核与剩余开发计划](./支付服务-完成度复核与剩余开发计划.md) §4（I5 拆为 I5a 开放 API 主链 / I5b 商家端与运营端 / I5c 独立形态与迁移收尾）。
- 计划写在本篇各片下，不另起 `docs/plans/`；每片照 I1–I4：先写会变红的测试 → 实现 → 消融 → 更新本篇 §5 / §6 → 提交。

## §5 对账三 · 实现 → 需求（测试）

| F | 测试 | 跑过 | 消融（改回去必须红） |
|---|---|---|---|
| F01 F05 F11 | `OpenApiFlowTest#authFailuresNeverReachBusiness`（无头、签名错、时间窗外、nonce 重放、未知 app、错密钥 → 401；≥ 6 行 `REJECTED_AUTH`；无 `pay_order`） | ✅ | ① 不占 nonce → 「重放」那一断言红 ✅ |
| F02 F12 | `#ungrantedStoreForbidden`（403）· `#queryIsScopedToTheApp`（同门店另一 app 查 404） | ✅ | ② 去掉 `PaymentServiceImpl#requireOwner` → `queryIsScoped…` 红 ✅ |
| F04 F13 | `#signedCreateIsIdempotent`（同体同单、异体 409、`checkoutUrl` 带单号） | ✅ | — |
| F06 | 全部用例断言 `{code, message, data, requestId}` 单层结构 | ✅ | ③ `ApiResponseWrapper` 不跳 `/open/pay/` → 6 条红（被包成双层）✅ |
| F10 | `SignatureCanonicalizerTest`（2 条；期望值由 Python 独立算出） | ✅ | ④ 星号不编码 → `fixedVector` 红 ✅ |
| F14 | `#startPaymentFreezesRoute`（渠道 TEST、`prepayId`、支付单与尝试行冻结 `pay_merchant_id` / `funds_path`）· `#closeUnpaid` | ✅ | — |
| F15 | `#payMethodsOfStore` | ✅ | — |
| F16（部分，开放 API 一侧） | `#refundRules`（退 1000 成功；超日限额 422；无 `pay:refund` 的 app 403/404） | ✅ | — |
| 收口 | `#partnerPaymentNeverReachesTrade`：第三方单付成功后，回调 / 对账把支付单号交给交易补单 → 跳过不抛 | ✅（修前红：`NOT_FOUND`，通道会无限重推） | 修前即红 ✅ |
| 守卫 | `PortalRouteRulesTest`（类级前缀）· `PayReverseDependencyBudgetTest` · `TableHasProducerTest` · `ArchitectureTest` · `CallbackTemplateTest` · `OpsEndpointPermTest` 等 88 条 | ✅ | — |

## §6 对账二 · 设计 → 实现

| 片 | 文件 |
|---|---|
| S1 | `V11__pay_open_access.sql` · `PayAppGrant` / `PayApiNonce` / `PayQrCode` · `OpenAppMappers` / `CheckoutMappers` · `PayOpenAccessSchemaTest` |
| S2 | `pay-portal` `api.auth.{PortalAuthFilter, PortalAuthFilters, PortalActions, PortalMasking}` · `api.common.{PortalResponse, PortalRequests, CachedBodyRequest, PortalExceptionHandler, PortalWebConfig}` · `pay-domain` `access.{entity.PayApiRequestLog, service.ApiRequestRecorder(+Impl)}` · `mapper.access.AccessMappers` · `shop-base-auth` `ApiResponseWrapper`（跳过 `/open/pay/`、`/checkout/`）· `shop-app` `SecurityConfig#openPayChain` |
| S3 | `openapp.{SignatureCanonicalizer, NonceStore, DbNonceStore, AppKeyResolver, ConfigAppKeyResolver}` · `openapp.service.AppAuthService(+Impl)` · `api.auth.OpenSignatureAuthenticator` · `ErrorCode.PAY_APP_REFUND_LIMIT` + 三语 · `SignatureCanonicalizerTest` |
| S4 | `acquiring.route.RouteSelectionPolicy` · `PaymentService#start`（+ `requireOwner`、`attachAttempt` 先按支付单号挂并复制冻结列）· `RefundService#query` + 第三方只退自己的单 · `PayMerchantBindingService#storeByNo` · `api.open.{OpenPaymentController, OpenRefundController, OpenStoreController, OpenViews}` · `shop-base` `spi.pay.PayOrderNumbers` · `OrderServiceImpl#markPaid` 跳过支付单号 · `OpenApiFlowTest` · `application-testcfg.yml` |

### 偏差说明

1. **F07 限流、F19 沙箱路由未做**：S3 只按 `shop.pay.open.environment` 拒绝环境不符的应用（沙箱应用打生产 401）；按 `rate_limit_qps` 限流与沙箱固定走 TEST 渠道留到 S6 前补。
2. **F08**：开放 API 链不开 CORS（服务端对服务端），收银台的同源规则随 S7。
3. **回调 / 对账的支付单号**：S6 改为支付事件通知业务方之前，交易侧 `markPaid` 用 `PayOrderNumbers` 跳过；`OrderPaidReconciler` 会把这类单当作「订单未付」每轮跳过一次（只打 info 日志），随 S6 退场。
4. **反向依赖预算** `SettlePort` 59 → 62（`PaymentServiceImpl#start` 构造发起流程的嵌套记录），已在 `known-pay-reverse-deps.txt` 写明；门店解析经 `PayMerchantBindingService#storeByNo` 转一手，`MerchantQueryPort` 不增。
5. **请求记录里没有回填 `pay_order_id` / `store_id`**：过滤器只看得到路径与结果码；关联靠 `trace_id` 与支付单号，回填留到 S6 事件里做。

## §7 待确认

| # | 问题 | 推荐 |
|---|---|---|
| Q1 | 支付运营端前端先做 ops-web 分区，还是一开始就独立工程 | 先分区（复用运营登录），接口不变，需要时再拆 |
| Q2 | 试点接入方用 HMAC 还是 RSA | 两者都支持；试点先 HMAC（实现与排障成本低），RSA 给有合规要求的接入方 |
| Q3 | 密钥模块（`sec_*`）是否在 I5 内落地 | 不阻塞 I5：接入方密钥先走 `ConfigAppKeyResolver`（部署注入、只读）；见安全规范 §3 的过渡规则 |
| Q4 | `checkout-h5` 的生产域名 | 独立子域 `pay.{品牌域名}`（Apple Pay 验证文件、渠道回跳地址都登记在它上面） |
| Q5 | 旧运营路径转发周期 | 一个版本周期（约 2 周），期间旧路径打弃用日志，归零后删除 |

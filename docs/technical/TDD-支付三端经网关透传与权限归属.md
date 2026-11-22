# TDD · 支付三端经网关透传，权限由提供接口的服务判

状态：方案定稿（2026-11-22，用户：「形成方案以及交接文档」）；§7 按推荐项写入正文，用户可随时改选；未实现。
交接：[docs/handoff/pay-gateway-proxy-2026-11-22](../handoff/pay-gateway-proxy-2026-11-22/README.md)。
附录：[入口盘点](TDD-支付三端经网关透传与权限归属-附录-入口盘点.md)（`scripts/inventory-pay-endpoints.py` 生成）。
档位：2（权限归属与入口变更：ai-shop 网关路由、统一认证接口、near-pay 判权来源；前端基址）。
关联：
- [TDD-支付运营端经网关转发与权限下沉](TDD-支付运营端经网关转发与权限下沉.md)：运营端 `/pay-ops/v2` 已按此模式上线，本方案把它推广到三端。
- [ADR-060](ADR/ADR-060-网关层独立模块与鉴权不进路由网关.md)：本方案修订其第 3、5、6 条在支付上的适用。
- [TDD-App支付权限由ai-shop决定](TDD-App支付权限由ai-shop决定.md)：店主来源委托并入 B 端统一认证。

## 一句话

支付相关的请求，ai-shop 网关**只认证、只转发**，不判权限。权限由**提供这个接口的服务**来判：接口在 near-pay，就由 near-pay 判；接口在 ai-shop 自己的进程里，就由 ai-shop 判。near-pay 不建自己的角色和权限表，权限码统一向 ai-shop 的统一认证接口取。

## §0 用户的决定（2026-11-22，原话）

> 统一网关针对支付部分也调整为仅仅做代理，权限统一交给支付，或者说谁提供 controller，谁提供权限，微服务服务没有权限，先整理方案

采用的理解（§7 第 1 条）：

| 原话 | 理解为 |
|---|---|
| 网关针对支付仅仅做代理 | 网关对支付请求只做认证（确认令牌有效、属于哪一端），然后原样转发。不按接口判权，也不进 `sys_api_resource` 的提前判权 |
| 谁提供 controller，谁提供权限 | 哪个服务的 controller 真正处理这个请求，就由哪个服务判这个请求能不能做 |
| 微服务没有权限 | 微服务里**没有权限数据**：不建角色表，不维护角色到权限码的映射。权限码仍在 ai-shop 授予，微服务通过统一认证接口取到再判。这与 ADR-060 第 3 条后半句「不建角色表」一致 |

这样，「授予」和「判定」分开：**授予**统一在 ai-shop，运营在一个地方配；**判定**跟着接口走，谁的接口谁判。

## §1 现状（ai-shop `origin/main` `1cc4fef64`；near-pay `develop/s1` `b19b2e14d`；v2 跑的是 `main-1cc4fef64`，`SHOP_PAY_DEPLOYMENT=standalone`）

v2 上支付由独立的 near-pay（166:18093）提供。ai-shop 进程里的支付相关入口有三种形态：

| 形态 | 例子 | 接口数（约） | 谁判权限 | 是否符合新原则 |
|---|---|---|---|---|
| **A. 网关透传** | 运营端 `/ops/pay-ops/v2/**` → near-pay `/pay-ops/v2/**`（`PayOpsPassThrough`） | 1 条前缀，下游 near-pay 运营端约 329 个 | near-pay：`AiShopOpsSessionFilter` 调 `/internal/auth/v1/operator` 取码，`@payPerm.can` 判 | 符合（11-21 已上线） |
| **B. 网关转发，但 ai-shop 也判** | B 端 `/biz/pay/v2/**`：`BizPayOnboardingController`、`BizPayMerchantController`、`BizChannelAccountClaimController` 等经 `BizOnboardingForwarder` 转 near-pay，有的加了 `@perm.canBiz('biz:finance')`，并对店主改写商户号、加来源委托凭据 | 22 | 两边都判：ai-shop 判一部分，near-pay 再按**自己的成员表**判 | 不符合：判两次，且 near-pay 有自己的权限数据（成员表） |
| **C. ai-shop 适配层** | 运营端 `/ops/payments/**`、`/ops/pay/...`（结算、提现、对账、费率、渠道……）；B 端 `/biz/payment/**`、`/biz/settle/**`、储值、权益；C 端 `/mp/` 储值、权益、积分。controller 在 ai-shop 网关模块里，经 `payclient.*AppService` 调 near-pay 的 `/internal/pay/v1|v2/**` | 运营端 141、B 端 147、C 端 20 | ai-shop：`@PreAuthorize("@perm.can…")`；near-pay 内部入口只识别调用方，不认人（ADR-045） | 要分开看，见下 |

形态 C 里又分两类。[附录](TDD-支付三端经网关透传与权限归属-附录-入口盘点.md)已按依赖做了初判：运营端 C1 110、C2 31；B 端 C1 58、C2 89；C 端 C1 2、C2 18。初判要在阶段 0 逐个人工确认：

- **C1 薄转调**：controller 只是把请求换个形状调 near-pay，业务在 near-pay。按新原则，接口其实是 near-pay 提供的，应改成透传，由 near-pay 判。
- **C2 ai-shop 有业务**：controller 组合了 ai-shop 自己的数据（门店、主体、订单、积分规则等），或业务逻辑本来就在 ai-shop 进程里（如 `pay.points.admin.*`、`pay.entitlement.*` 直接注入的服务）。按新原则，接口是 ai-shop 提供的，**仍由 ai-shop 判，维持现状**。

near-pay 这边已有的认证件：

| 端 | 过滤器 | 现在调 ai-shop 的 | 判权依据 |
|---|---|---|---|
| 运营 | `AiShopOpsSessionFilter` | `/internal/auth/v1/operator`（服务间契约） | ai-shop 下发的 `pay:` 权限码 |
| B 端 | `AiShopBizSessionFilter` | `/biz/auth/session`（页面接口）+ 主体查询 | near-pay **自己的成员表** + ai-shop 的店主来源委托 |
| C 端 | 无（`/checkout/v1` 走支付单号与签名） | — | — |

## §2 原则

1. **网关对支付只做两件事**：认证（令牌有效、端对得上，不对就 401），转发（方法、路径、查询串、请求体、`Authorization`、状态码、响应体原样）。
2. **判权在接口所在的服务里**。near-pay 的接口由 near-pay 判；ai-shop 进程里的接口由 ai-shop 判。同一个请求只判一次。
3. **权限数据只在 ai-shop**。角色、权限码、数据范围在 ai-shop 授予；near-pay 通过统一认证接口取，按令牌短缓存，不落库、不建角色表。
4. **业务归属不变**。商户归属、单据归属、状态机这类「这单是不是你的」「现在能不能退」的校验，本来就在业务里，不算权限，不动。

## §3 方案

### 3.1 网关：一个透传件，按端挂前缀

复用运营端已上线的 `PassThroughInvoker`，抽出一个按端配置的透传注册：

| 端 | 网关前缀 | 转到 near-pay | 开关 |
|---|---|---|---|
| 运营 | `/ops/pay-ops/v2/**` | `/pay-ops/v2/**` | `shop.gateway.ops.pay-ops-proxy.enabled`（已有，已开） |
| B 端 | `/biz/pay/v2/**` | `/biz/pay/v2/**` | `shop.gateway.biz.pay-proxy.enabled`（新，默认关） |
| B 端（旧版） | 不新增 | — | near-pay 的旧版 `/biz/pay/{app-grant,collection,qr-code,refund,statement}` ai-shop 现在没有转发；本仓库 b-app 用的是 ai-shop 的 `/biz/payment/**`（形态 C），按 3.4 处理（§7 第 4 条） |
| C 端 | 暂不新增 | — | C 端支付走 `/checkout/v1` 与支付回调，本来就不经网关判权 |

- 透传的前缀不进 `sys_api_resource`，不做提前判权；网关的「接口资源」页面上显示为一行「整前缀透传 → 支付服务」，便于看清楚。
- 认证沿用各端已有的会话链：运营端认运营令牌、B 端认商户令牌。端不对就 401，不转发。
- 网关**去掉**客户端带来的 `X-Internal-*`、`X-Merchant-No` 之外的内部头（防冒充）。
- 打开 B 端透传开关后，§1 形态 B 的 forwarder 类与它们的 `@PreAuthorize` 一并下线；店主来源委托改由统一认证接口承担，见 3.2。

### 3.2 统一认证接口：补 B 端

运营端已有 `/internal/auth/v1/operator`。B 端新增一条，同样只在内部可达、带进程凭据：

```
GET /internal/auth/v1/merchant-staff
Authorization: Bearer <商户端令牌>
X-Auth-Namespace: pay:
X-Pay-Merchant-No: <可选，App 当前选中的支付商户号>

200 {"code":0,"data":{
  "userNo":"U…","status":"ACTIVE",
  "memberships":[                                   // 本人在哪些支付商户下、各有哪些 pay: 码
    {"payMerchantNo":"PM…","entityNo":"M…","role":"OWNER","perms":["*"]},
    {"payMerchantNo":"PM…","entityNo":"M…","role":"STAFF","perms":["pay:collection:read", …]}
  ],
  "permVersion":"…","expiresAt":…
}}
```

- **店主**：对其名下主体关联的支付商户，`perms` 为 `["*"]`。这就是现在「店主来源委托」的效果（TDD-App支付权限由ai-shop决定 AC1），改成由认证接口直接给出，不再靠转发时改写请求头。
- **店员**：码来自 ai-shop B 端的员工角色（`Perms` 里 B 端那一套）；按 `pay:` 前缀筛。
- 商户号由 near-pay 按请求自己取，再到 `memberships` 里找对应一行判；不在 `memberships` 里就 403。AC2（非本人商户不委托）由此自然成立。

C 端暂不需要：买家只有「本人」，没有角色。如果以后 near-pay 有直接面向买家的接口要经网关，再加 `/internal/auth/v1/buyer`，只回身份。

### 3.3 near-pay：判权只认统一认证接口

| 项 | 运营端 | B 端 |
|---|---|---|
| 认证 | 已改（调 `/internal/auth/v1/operator`，30 秒缓存） | `AiShopBizIdentityClient` 改调 `/internal/auth/v1/merchant-staff`，按令牌 SHA-256 缓存 30 秒 |
| 判权 | `@payPerm.can(code)` 只认 ai-shop 的码；SY03 映射观察期满后退役 | 新增 `@payPerm.canBiz(merchantNo, code)`：在 `memberships` 里找该商户，有码即放行。替换现在按成员表判的守卫 |
| 自有权限数据 | SY03 `pay_permission_mapping` 退役；SY04 数据范围按 §10 已改为按 ai-shop 角色授予 | 成员表 `pay_merchant_membership`、`pay_access_membership` 不再参与判权，先只读保留一个发布周期，再删 |
| 灰度 | 已完成 | 开关 `near-pay.biz.permission-source=host\|host+membership`，先 `host+membership`，日志记「只靠成员表通过」的请求，一周没有再切 `host` |

near-pay 的 `/internal/pay/v1|v2/**`（形态 C 调的那些）维持 ADR-045：只识别调用方，不认人。因为这些请求的权限已在 ai-shop 判过（C2），或将改成透传（C1）。

### 3.4 形态 C 的去留

| 类别 | 做法 |
|---|---|
| C1 薄转调，near-pay 已有同功能的公开接口（`/pay-ops/v2`、`/biz/pay/v2`） | 前端改调透传前缀，ai-shop 适配 controller 下线 |
| C1 薄转调，near-pay 只有内部接口 | 由 near-pay 补公开接口（带 `@payPerm`），再按上一行处理；补之前维持现状 |
| C2 ai-shop 有业务 | 不动：ai-shop 提供 controller，ai-shop 判 |

运营端的支付中心已经全部走透传；运营端剩下的形态 C（结算、提现、对账、费率、积分、权益）多半是 C2 或 near-pay 尚无公开接口，第一期不动。

## §4 对既有决定的影响

| 决定 | 影响 |
|---|---|
| ADR-060 第 3 条「认证与鉴权在网关层做一次；微服务不认终端令牌，不建角色表」 | 对支付三端改为：网关只认证；**谁提供接口谁判权**；near-pay 认终端令牌，经统一认证接口取权限；「不建角色表」保留。需在 ADR-060 加修订说明（运营端那次只写在 TDD 里，借这次一并补进 ADR） |
| ADR-060 第 5、6 条（链接权限入库、微服务链接白名单） | 支付透传前缀整段放行，不逐条入库；白名单粒度从「每条链接」变为「每个前缀」 |
| TDD-App支付权限由ai-shop决定 | 结论不变（店主默认全权、权限由 ai-shop 定）；实现从「转发时改写头 + 来源委托凭据」改为「统一认证接口给出 memberships」 |
| ADR-045（支付内部入口只识别不拒绝） | 不受影响 |
| 记忆「网关与业务脱耦」 | 一致：网关不为支付加规则，也不改业务注解 |
| 其他非支付微服务（库存等） | 本方案不推广。用户原话限定「支付部分」；是否推广另议 |

## §5 分期与上线

| 阶段 | 内容 | 谁 | 回退 |
|---|---|---|---|
| 0 · 盘点 | 形态 B、C 逐个接口列表：路径、当前判权码、调的 near-pay 接口、C1/C2 归类、near-pay 有无公开对应。产出一张对照表附在本 TDD | 本仓库 | — |
| 1 · ai-shop | 透传注册抽成按端配置；B 端 `/biz/pay/v2/**` 透传（开关关）；`/internal/auth/v1/merchant-staff` | 本仓库 | 开关关 |
| 2 · near-pay | `AiShopBizIdentityClient` 改调新接口 + 缓存；`canBiz` 判权；`permission-source=host+membership`，加日志 | near-pay（与 RobinWu 对齐排期） | 配置切回 |
| 3 · 切换 | 开 B 端透传开关；App 无需改（路径不变）；下线形态 B 的 forwarder | 本仓库 | 关开关，forwarder 仍在包里 |
| 4 · 观察 | 一周没有「只靠成员表通过」后切 `host`；SY03 同期退役 | near-pay + 用户确认 | 切回 |
| 5 · 形态 C1 | 按阶段 0 的表逐批迁到透传 | 两边 | 前端改回旧路径 |

阶段 1 不依赖 near-pay 改动：near-pay 现在已经认 ai-shop 的商户令牌，透传过去照样按成员表判。

## §6 测试对账

| 验收 | 测试 |
|---|---|
| 无令牌或令牌属于别的端：网关 401，下游无请求 | 网关集成测试，桩下游计数为 0 |
| 有令牌：方法、路径、查询串、`Authorization`、请求体、上传、文件流、错误码原样往返 | `PassThroughInvokerTest` 按端各一组 |
| 客户端伪造 `X-Internal-*`：被去掉 | 同上 |
| 开关关：404 | 网关测试 |
| `merchant-staff`：店主对名下商户回 `*`；店员回角色码；非成员商户不出现；过期令牌 401；无进程凭据 403 | ai-shop 接口测试 |
| near-pay：缓存命中不回调；`host` 模式下只靠成员表的请求被拒；店主无需成员记录即可操作 | near-pay 单测 |
| 线上：Oscar 在 App 打开渠道审核、进件、补件；店员按角色可见；非本人商户 403 | 模拟器走查 + 白名单测试账号脚本 |

## §7 选项与采用

用户没有逐条答复，按推荐项定稿；改选时相应调整 §3、§5。

| # | 问题 | 采用 | 可改为 |
|---|---|---|---|
| 1 | 「微服务没有权限」的含义 | 不建角色与权限表，在服务内判；权限码由 ai-shop 授予 | 微服务连判定也不做（那就回到网关判，与「仅仅做代理」冲突） |
| 2 | B 端店员的支付权限码从哪来 | 沿用 ai-shop B 端员工角色，按 `pay:` 前缀下发 | 另设一组支付专用角色 |
| 3 | near-pay 成员表 | 观察期后不参与判权，只读保留一个发布周期再删 | 长期保留作店员授权来源（与第 1 条冲突） |
| 4 | near-pay 旧版 `/biz/pay/{app-grant,collection,qr-code,refund,statement}` | 不经网关开放（ai-shop 现在也没转发）；b-app 走的 `/biz/payment/**` 按形态 C 归类迁移 | 透传开放，b-app 直接改调 |
| 5 | 推广到库存等其他微服务 | 不推广 | 另立方案 |
| 6 | near-pay 改动排期 | 与 RobinWu 对齐；ai-shop 阶段 1 不等它 | — |

## §8 实施清单（阶段 1、3 在本仓库）

| 改动 | 位置 | 测试 |
|---|---|---|
| 透传注册按端配置：前缀、目标服务、开关；运营端 `PayOpsPassThrough` 改为其一个实例 | `shop-gateway` `gateway.core.proxy`、`gateway.ops`、新增 `gateway.biz.PayBizPassThrough` | `PassThroughInvokerTest`、新增 B 端透传集成测试 |
| 去掉客户端带来的 `X-Internal-*` | 同上（复用 `PayBizSourceDelegation#withoutInternal` 的逻辑） | 伪造头用例 |
| `/internal/auth/v1/merchant-staff` | `shop-gateway` `portal.internal`，与 `OperatorIdentityEndpoint` 并列；店主商户取自 `MerchantEntityService#myEntities` + `PaySourceLinkApi#status` | `MerchantStaffIdentityEndpointTest` |
| 开关 `shop.gateway.biz.pay-proxy.enabled` | `application.yml`，默认 false | 开关关 404 |
| 阶段 3 下线：`BizPayOnboardingController`、`BizPayMerchantController`、`BizPayMerchantChannelController`、`BizChannelAccountClaimController`、`PayChannelAccountClaimForwarder`、`PayBizOnboardingRelay` 的委托改写 | `shop-gateway` `portal.biz`、`payclient` | 端点快照 `EndpointSnapshotTest` 更新 |
| ADR-060 修订说明 | `docs/technical/ADR/ADR-060-网关层独立模块与鉴权不进路由网关.md` | — |

档位 2 的三处对账：本 TDD（§3 设计 ↔ §6 测试 ↔ §8 清单）；`Perms` 与 near-pay `@payPerm` 的码在迁 C1 时逐批登记；生成文档闸门 `node scripts/check-generated-docs.mjs`。

## §9 风险

- **两次判权的过渡期**：阶段 3 之前 B 端 v2 仍是 ai-shop 与 near-pay 各判一次，阶段 2 之前 near-pay 仍按成员表判。顺序是「先透传、再换判权来源」，任何一步回退都不会放宽权限。
- **统一认证接口不可用**：near-pay 回 503（已有 `IDENTITY_UNAVAILABLE` 处理），不会放行；缓存 30 秒能挡住短暂抖动。
- **撤权生效延迟**：最长 30 秒（缓存期），与运营端一致。
- **C1 初判错**：把有 ai-shop 业务的接口当薄转调迁走，会丢数据。阶段 0 必须逐个确认后才迁。

# TDD · 支付运营端经网关转发、权限在支付服务内判

状态：方案（2026-11-20 整理，待用户确认）；未实现。
关联：[TDD-支付中心运营页面](TDD-支付中心运营页面.md)、[ADR-060](ADR/ADR-060-网关层独立模块与鉴权不进路由网关.md)（本方案修订其第 3、5 条在支付服务上的适用）、`TDD-网关层与统一权限-五层架构对齐.md` §2.12（在网关分支 `feat/gateway-layer-20261118` 与发布线上）。

## 一句话

运营端的支付接口改经 ai-shop 网关转发：**网关只管登录**（没有有效运营会话就拦在门外），把令牌原样转给支付服务；**支付服务在过滤器里调 ai-shop 的统一认证接口**，拿到操作人、角色、权限码与数据范围，权限在支付服务内部判。

## §0 用户的决定（2026-11-20，原话整理）

1. 支付部分运营端的权限在支付服务内部进行。
2. 运营的网关只把 token 传给微服务。
3. 支付微服务拿到 token 后，在过滤器中调用统一的认证方式来进行权限配给。

讨论过程中比较过的另两种做法与取舍：网关逐接口判权（ADR-060 定稿的做法，规则要和 near-pay 的 322 个接口同步）、Spring Cloud Gateway 做入口聚合（要先改父 pom 并验证与 Boot 4.0.7 兼容）。本方案不引入新框架，复用现有网关模块与 near-pay 现有的过滤器。

## §1 现状（near-pay `develop/s1` `fa708146c`；ai-shop 发布线 `release/v2-paycenter-20261120`）

```
运营端前端（支付中心）──/oms-api/pay-ops/v2/**──▶ nginx(166) ──▶ near-pay :18093 /pay-ops/v2/**
                                                             │
                     AiShopOpsSessionFilter（/pay-ops/*，order -100）
                       └ AiShopOperatorClient：GET {near-pay.ops.ai-shop.base-url}/ops/auth/me（每个请求一次，无缓存）
                       └ LoginUser.operator(staffNo, realName, roles, perms, DataScopeSpec)
                     OpsSessionAuthenticator（PortalFace.OPS）→ PortalPrincipal
                     @payPerm.can(code) = 宿主 perms 匹配 ∪ pay_permission_mapping（SY03，按角色映射）
                     数据范围 = pay_data_scope_grant（SY04，DataScopeService.effective）
```

| 项 | 现状 | 问题 |
|---|---|---|
| 入口 | nginx 把 `/oms-api/pay-ops/v2/` 直连 near-pay，对公网可达 | 不经 ai-shop；安全全靠 near-pay 自己的过滤器（11-19 免认证密钥直录事故即出在这条路径） |
| 认证 | 每请求回调 ai-shop `/ops/auth/me`，2 s 连接 / 3 s 读超时，无缓存 | `/ops/auth/me` 是给运营端页面用的接口，不是服务间契约；每请求多一跳 |
| 权限码来源 | ai-shop 下发的 perms **并集** near-pay 自己的 SY03 映射 | 同一个人在两边可能得出不同结论；授权要在两处配 |
| 数据范围 | near-pay SY04 授权表；ai-shop 下发的商户 / 社区 / 网点字段另成一套 | 两套范围，口径不一 |
| ai-shop 网关 | 运营端认 `/ops`、`/pay-ops` 两个前缀；`/pay-ops/v2` 在 ai-shop 进程里没有入口 | 支付中心的请求根本不进 ai-shop |

## §2 方案

### 2.1 目标链路

```
运营端前端 ──/ops-shop-v2/ops/pay-ops/v2/**──▶ nginx ──▶ ai-shop v2（网关，运营端链）
   ① 认证：令牌必须是有效的运营会话，否则 401（不判任何权限码）
   ② 透传：/ops/pay-ops/v2/{rest} → {支付服务地址}/pay-ops/v2/{rest}
           原样带 Authorization、Content-Type、Accept、Accept-Language、X-Request-Id；请求体与响应原样（含上传、文件流、状态码与 near-pay 包体）
                                   │
                                   ▼
near-pay /pay-ops/v2/**
   ③ AiShopOpsSessionFilter：取 Bearer → 调「统一认证接口」→ LoginUser（短缓存）
   ④ @payPerm.can(code)：只认统一认证接口给的权限码（去掉 SY03 并集）
   ⑤ 数据范围：见 2.5
```

### 2.2 统一认证接口（ai-shop 提供，服务间契约）

现在 near-pay 调的是页面接口 `/ops/auth/me`。改为一个专门给服务用、字段稳定的接口：

```
GET /internal/auth/v1/operator          （仅内部：nginx deny /internal/、安全组；带进程凭据 X-Internal-Token）
Authorization: Bearer <运营端令牌>
X-Auth-Namespace: pay:                  （只回这个前缀的权限码，控制体积）

200 {"code":0,"data":{
  "staffNo":"S001","realName":"…","status":"ACTIVE","roles":["FINANCE"],
  "perms":["pay:merchant:view","pay:refund:review", …],   // 已按角色展开、按前缀筛过；超管为 ["*"]
  "dataScope":{"all":false,"merchantNos":[…],"communityNos":[…],"pickupNos":[…]},
  "permVersion":"r42-1732080000",                          // 角色或授权变化时变
  "expiresAt":1732083600000                                // 会话到期
}}
401 令牌无效或已过期；403 进程凭据不对
```

- 实现在 ai-shop 网关模块的运营端认证包里，复用现有会话存储与 `perm` 解析，不改业务入口（网关与业务脱耦的约定）。
- **过渡**：接口上线前 near-pay 继续调 `/ops/auth/me`，返回字段是新接口的子集，near-pay 的解析两边都认。
- 体积：只回 `pay:` 前缀，现有约 76 个码，约 2 KB；不受请求头上限影响（这是响应体）。

### 2.3 ai-shop 网关：登录校验 + 透传

| 项 | 做法 |
|---|---|
| 入口 | 运营端链新增 `/ops/pay-ops/v2/**`；落在 v2 现有的 nginx 规则 `/ops-shop-v2/ops/` 下，**nginx 不改** |
| 认证 | 走运营端现有 Spring Security 链，只要求 `authenticated()`；不做逐接口判权，该前缀不进资源索引与提前判权（`shop.gateway.ops.precheck` 对它不生效） |
| 转发 | 新增 `PassThroughInvoker`（`gateway.core.proxy`，继承 `AbstractServiceInvoker`）：请求体流式转发（上传上限与 near-pay 一致，附件 5 MiB + 余量，设 8 MiB）；响应状态码、`Content-Type`、`Content-Disposition`、包体原样回写，不包统一包体、不改写错误。现有 `HttpProxyInvoker` 只转 JSON、会包一层、会吞错误码、不传 `Authorization`，不适用 |
| 下游地址 | `shop.services.targets.PAY`；.80 上为 `http://127.0.0.1:18093`（`nearpay-fwd` 转 166:18093） |
| 头 | 只带白名单头；去掉 `Cookie`、`Host`、逐跳头；补 `X-Forwarded-For`、`X-Request-Id`；签名上下文（S 期）照常附带，near-pay 维持 observe |
| 超时 | 连接 3 s、读 30 s（进件提交、渠道调用较慢） |
| 开关 | `shop.gateway.ops.pay-ops-proxy.enabled`（默认 false）；关时该前缀 404 |
| 日志 | 方法、路径模板、操作人、下游状态码、耗时；不记请求体与 `Authorization` |

### 2.4 near-pay：过滤器调统一认证、权限只认 ai-shop

| 项 | 做法 |
|---|---|
| 认证接口 | `AiShopOperatorClient` 改调 `/internal/auth/v1/operator`（带 `X-Internal-Token`、`X-Auth-Namespace: pay:`），新配置 `near-pay.ops.ai-shop.identity-path`，缺省仍 `/ops/auth/me` 以便过渡 |
| 缓存 | 按令牌的 SHA-256 缓存 `LoginUser`，有效期取 `min(30 s, expiresAt)`；下游判权失败、ai-shop 回 401 时立即失效。回答「每次都调影响性能」：同一会话 30 秒内只调一次 |
| 权限码 | `PayPermissionEvaluator.can` 改为只认宿主码（`Permissions.matches(hostPerms, code)`），**去掉与 `pay_permission_mapping`（SY03）的并集**；开关 `near-pay.ops.permission-source=host|host+mapping`，先 `host+mapping` 上线、观察日志确认没有只靠映射通过的请求后切 `host` |
| `/me/access` | 权限部分改为宿主码按前缀筛；仍只用于按钮显隐 |
| SY03 页面 | 切到 `host` 后在 near-pay 运营端隐藏；表保留一个发布周期 |
| 入口收口 | 支付中心切到网关并稳定后，166 nginx 的 `/oms-api/pay-ops/v2/` 只留给 near-pay 自己的 `/oms` 页面；`/oms` 停用后删除这条转发 |

### 2.5 数据范围（待确认，见 §5 第 2 条）

两个选项：

- **A（推荐，先不动）**：数据范围仍由 near-pay 的 SY04 授权表决定，属于业务层的「这个人能看哪些商户」，与「能不能调这个接口」分开。ai-shop 下发的 `dataScope` 只做留痕。
- **B**：统一由 ai-shop 下发，near-pay 的 SY04 退役。需要 ai-shop 先有「运营人员 × 支付商户」的范围模型，目前没有。

### 2.6 前端

- 支付中心基址：`NEXT_PUBLIC_PAY_OPS_API_BASE=/ops-shop-v2/ops/pay-ops/v2`（`resolvePayOpsBase` 只收同源绝对路径，符合）。令牌仍是 ai-shop 运营端令牌，不变。
- 本地开发：ops-web 指向本机 ai-shop，ai-shop 的 `shop.services.targets.PAY` 指向本机 near-pay 或 mock。

## §3 上线步骤与回退

| 步 | 动作 | 谁 | 回退 |
|---|---|---|---|
| 1 | ai-shop：`PassThroughInvoker`、`/ops/pay-ops/v2/**` 路由（开关关）、统一认证接口；发 v2 | 本仓库 | 开关关即 404，前端仍走旧地址 |
| 2 | 开开关；支付中心前端换基址发版；31 个页签与写操作在测试商户上走一遍 | 本仓库 | 前端基址改回 `/oms-api/pay-ops/v2` |
| 3 | near-pay：认证改调统一接口 + 缓存；`permission-source=host+mapping`，加「只靠映射通过」的日志 | RobinWu | 配置改回 `/ops/auth/me` |
| 4 | 观察一周无「只靠映射通过」后切 `host`；隐藏 SY03 页面 | RobinWu + 用户 | 切回 `host+mapping` |
| 5 | `/oms` 停用后删 166 nginx 的 `/oms-api/pay-ops/v2/` 直连 | 运维 | 恢复 nginx 配置 |

各步独立可回退；步 1–2 不依赖 near-pay 改动（near-pay 现在就认 ai-shop 令牌）。

## §4 对既有决定的影响

- **ADR-060 第 3 条**（认证与鉴权在网关层做一次、微服务不认终端令牌）：对支付运营端改为「网关只认证、支付服务认终端令牌并经统一认证接口取权限」。第 5 条（链接权限入库）不适用于 `/ops/pay-ops/v2/**`。需在 ADR-060 增补修订说明。
- **TDD-网关层 §2.12「直连现在不动」**：本方案即其「重新评估」。
- **ADR-045**（支付内部入口只识别不拒绝）：不受影响，本方案走运营端链，不走 `/internal/pay/v1`。
- **已做的网关逐接口判权**（运营端提前判权、`sys_api_resource`、B 端同构）：对本前缀不启用；其余前缀维持原状，是否保留另议。
- **支付中心 P5 新登记的 6 个权限码**：照旧在 ai-shop 角色里授予，near-pay 通过统一认证接口拿到。

## §5 待确认

1. 统一认证接口用新的内部接口 `/internal/auth/v1/operator`，还是先继续用 `/ops/auth/me` 只加缓存（少一处 ai-shop 改动，但页面接口变成服务契约）。
2. 数据范围走 2.5 的 A 还是 B。
3. SY03 退役的时间点与谁来确认「没有只靠映射通过的请求」。
4. near-pay 改动排期（与 RobinWu 对齐；他手上还有 V132 与修复二待发）。

## §6 测试对账

| 验收 | 测试 |
|---|---|
| 无会话请求 `/ops/pay-ops/v2/**` 在网关回 401，下游无请求 | 网关集成测试（桩下游计数为 0） |
| 有会话请求原样到达下游：方法、路径、查询串、`Authorization`、请求体字节一致 | `PassThroughInvokerTest`（桩 HttpServer 记录） |
| 下游 409 `VERSION_CONFLICT`、403、文件流、multipart 上传原样回到终端 | 同上，逐类一条 |
| 开关关时 404 | 网关测试 |
| 统一认证接口：有效令牌回本人 `pay:` 码；超管回 `*`；过期令牌 401；无进程凭据 403 | ai-shop 接口测试 |
| near-pay：缓存命中不再回调；401 失效；`host` 模式下只靠映射的码被拒 | near-pay 仓库单测（RobinWu） |
| 前端：基址改为网关路径后接口测试不变 | `client.test.ts` 加一条基址用例 |
| 线上：支付中心 31 个页签可用、测试商户写操作成功、无权角色在 near-pay 回 403 | v2 走查记录 |

## §7 执行步骤（按 §5 推荐项排；改选项时相应调整）

### 阶段 0 · 准备（本仓库）

| # | 动作 | 完成标志 |
|---|---|---|
| 0.1 | 从 `release/v2-paycenter-20261120` 开分支 `feat/pay-ops-gateway-passthrough`，新工作树 `~/work/ai-shop-payops`；合入 `feat/ops-pay-center-menu`（含 P5）与最新 `origin/main` | 分支建好、无冲突，前端测试与 `tsc` 通过 |
| 0.2 | 根目录 `npm ci`；本机 MySQL 起好，`COMMERCE_MYSQL_*` 就绪 | pre-push 能跑到后端测试 |

### 阶段 1 · ai-shop 后端

| # | 动作 | 测试 |
|---|---|---|
| 1.1 | `gateway.core.proxy.PassThroughInvoker`：流式请求体（上限 8 MiB）、白名单请求头（含 `Authorization`）、响应状态码与包体原样、连接 3 s / 读 30 s | `PassThroughInvokerTest`（桩 HttpServer）：方法、路径、查询串、`Authorization`、请求体字节一致；multipart；文件流与 `Content-Disposition`；下游 409 / 403 原样；超时回 504 |
| 1.2 | 运营端链挂 `/ops/pay-ops/v2/**`：只要求已登录；不进资源索引与提前判权；开关 `shop.gateway.ops.pay-ops-proxy.enabled`（默认 false）；目标 `shop.services.targets.PAY` | 网关集成测试：无会话 401 且下游计数 0；开关关 404；开关开后转发 |
| 1.3 | 统一认证接口 `GET /internal/auth/v1/operator`：校验 `X-Internal-Token`，按 `X-Auth-Namespace` 筛码，回 §2.2 的字段 | 接口测试：本人 `pay:` 码；超管 `*`；过期令牌 401；缺进程凭据 403 |
| 1.4 | 重跑 `EndpointSnapshotTest`、`SecurityChainSnapshotTest`，更新基线；权限对齐测试 | 快照差异只有新增的两条入口 |

### 阶段 2 · 前端

| # | 动作 | 测试 |
|---|---|---|
| 2.1 | `client.test.ts` 加网关基址用例；`.env.local.example` 写明 v2 取值 `/ops-shop-v2/ops/pay-ops/v2` | ops-web 全量测试通过 |
| 2.2 | 构建 v2 前端包时带 `NEXT_PUBLIC_PAY_OPS_API_BASE=/ops-shop-v2/ops/pay-ops/v2` | 产物里的基址正确 |

### 阶段 3 · 本机联调

| # | 动作 | 完成标志 |
|---|---|---|
| 3.1 | 本机起 ai-shop（ops）+ near-pay（或桩），`shop.services.targets.PAY` 指本机 | 浏览器里支付中心经网关打开商户、进件、订单三个页签；附件上传与预览可用 |
| 3.2 | 拿无支付权限的运营账号访问 | near-pay 回 403、页面给出无权提示；无会话时网关回 401 |

### 阶段 4 · 提交与评审

| # | 动作 |
|---|---|
| 4.1 | 提交、推送、开 PR（目标 main）；PR 写明开关默认关、near-pay 无需同步改 |

### 阶段 5 · 上线 v2（.80，先拉取合并再发布）

| # | 动作 | 回退 |
|---|---|---|
| 5.1 | 备份 `run.sh` 与环境文件；配置 `shop.services.targets.PAY=http://127.0.0.1:18093`、开关 false；换后端包、重启 | 换回旧包 |
| 5.2 | 开关改 true、重启；`curl` 无令牌应 401 | 开关改回 false |
| 5.3 | 换前端包（新基址）；31 个页签逐个打开；在测试商户上做一次写操作（收款策略改后复原） | 前端 `current` 指回上一版 |
| 5.4 | 观察 3 天：网关日志里 4xx/5xx、耗时；near-pay 日志无异常 401 | 同 5.3 |

### 阶段 6 · near-pay（RobinWu，与他对齐排期后执行）

| # | 动作 | 回退 |
|---|---|---|
| 6.1 | `AiShopOperatorClient` 支持 `near-pay.ops.ai-shop.identity-path` 与 `X-Internal-Token`；按令牌哈希缓存 30 s，401 立即失效 | 配置改回 `/ops/auth/me` |
| 6.2 | `PayPermissionEvaluator` 加 `near-pay.ops.permission-source=host\|host+mapping`，先 `host+mapping`，记录「只靠映射通过」的请求 | — |
| 6.3 | 配置指向 ai-shop 的 `/internal/auth/v1/operator`，发布 | 同 6.1 |
| 6.4 | 观察一周，无「只靠映射通过」后切 `host`，隐藏 SY03 页面；表保留一个发布周期 | 切回 `host+mapping` |

### 阶段 7 · 收口

| # | 动作 |
|---|---|
| 7.1 | ADR-060 增补修订说明（支付运营端：网关只认证、权限在支付服务内判） |
| 7.2 | near-pay `/oms` 停用后，删 166 nginx 的 `/oms-api/pay-ops/v2/` 直连（运维，先备份） |
| 7.3 | 更新交接文档与本 TDD §6 实现记录 |

## §8 实现记录

### 阶段 0–2（2026-11-20，分支 `feat/pay-ops-gateway-passthrough`，工作树 `~/work/ai-shop-payops`）

- **新增**：
  - `gateway.core.proxy.PassThroughInvoker`：透传型转发，继承 `AbstractServiceInvoker`。`route()` 造路由，`supports()` 只认它造的路由，因此不会被提前判权的调用列表误选。
  - `gateway.ops.PayOpsPassThrough`：前缀 `/ops/pay-ops/v2`，开关配置写在内部类里。
  - `portal.internal.OperatorIdentityEndpoint`：统一认证接口。
- **改动**：
  - `OpsGatewayEnd.operatorChain`：开关开时，把透传过滤器加到 `AuthorizationFilter` 之后。
  - 前端 `client.test.ts` 加了网关基址用例；`.env.local.example` 写明 v2 的取值。
- **偏差**：
  1. 统一认证接口放在 `ai.neargo.shop.portal.internal`（网关模块内），没有放 `gateway.ops`。原因是 `CommonPackageIsolationTest` 规定 `/internal` 端点只能在这个包，否则会被统一包体包住而不报错。
  2. 进程凭据用单独的配置 `shop.gateway.ops.identity.caller-token`，没有复用 `shop.services.internal-token`。后者是 ai-shop 调别人时出示的凭据，前者是别人调 ai-shop 时要出示的，方向相反，不该共用一个值。
  3. 接口内部复用 `OpsService.me()`：按与运营端认证链相同的口径取实时角色，临时放进安全上下文后调用。这样它与 `/ops/auth/me` 对同一个人逐字段一致，有测试钉住。
  4. 本机没有同源反代：`next.config` 没有配 rewrites，所以本机联调用桩服务的集成测试代替浏览器走查。真实 near-pay 的端到端要到阶段 5 在 v2 上验证。
- **测试**：
  - `PassThroughInvokerTest` 5 条：原样转发、409 原样回、multipart 与文件流、连不上回 502、只接手透传路由。
  - `PayOpsPassThroughTest` 3 条：未登录 401 且下游收不到；已登录、无任何支付码也照样转发并带原令牌；前缀之外的地址不受影响。
  - `PayOpsPassThroughOffTest` 1 条：开关关时 404。
  - `OperatorIdentityEndpointTest` 4 条：凭据 403；四种无效会话 401；与 `/ops/auth/me` 一致且按前缀筛码；筛码规则。
  - 架构包与网关包全量回归。

### 阶段 5–6 上线（2026-11-21 00:42–01:00 CST）

| 部件 | 现在 | 回退 |
|---|---|---|
| ai-shop v2 后端 | .80 `releases/shop-app-398f4a855.jar`（sha256 前缀 `72ee34016b9e9305`），标签 `gateway-payops-398f4a855`。包含 main 上 RobinWu 的 `7bb9bf944` 与 `6f72a56f1`：线上两次补丁换的 4 个收款类与本包 javap 逐条一致 | 复原 `run.sh.before-payops-398f4a855`、`ai-shop-all-v2.env.before-payops-398f4a855`，`./run.sh all restart`；旧包 `shop-app-b4335120a-biz-6f72a56f1.jar` 仍在 |
| v2 环境文件 | 加 `SHOP_GATEWAY_OPS_IDENTITY_CALLER_TOKEN`（随机值，未显示）、`SHOP_GATEWAY_OPS_PAY_OPS_PROXY_ENABLED=true` | 同上 |
| 运营端页面 | 166 `current → releases/gateway-398f4a855`（构建参数同上一版，另加 `NEXT_PUBLIC_PAY_OPS_API_BASE=/ops-shop-v2/ops/pay-ops/v2`） | `current` 指回 `releases/gateway-e86b5ddc0` |
| near-pay | 166 `pay-svc.jar` 换为补丁包（sha256 前缀 `cf59cb8913a44168`）：线上包（`2f1d7927fa009d96`，= near-pay `origin/main` `8805ce759`，4 个相关类 javap 一致）只换 `AiShopOperatorClient`（新增 `$Cached`）、`AiShopOpsIdentityConfiguration`、`pay-guard` 内 `PayPermissionEvaluator`，其余条目逐字节保留 | 复原 `pay-svc.jar.before-opsid-20261121`、`cloud.properties.before-opsid-20261121`、`secret.properties.before-opsid-20261121`，`docker restart nearpay-prod` |
| near-pay 配置 | `cloud.properties` 加 `identity-path=/internal/auth/v1/operator`、`cache-ttl=PT30S`、`permission-source=host+mapping`；凭据进 `secret.properties` | 同上 |

中断：v2 重启两次（00:49:52–00:51:25、00:52:29–00:53:57），near-pay 重启一次（00:54:34–00:55:28）；重启窗口内 nginx 记到的 5xx 全部在窗口内。

**线上测试（运营账号已登录的浏览器，经网关）**

- 不带令牌：网关回 401（ai-shop 包体），near-pay 收不到；`/internal/auth/v1/operator` 公网 404，166 本机不带凭据 403。
- 支付中心 33 个列表 / 字典接口经网关全部 200、业务码 0（含 P5 的转人工、渠道账户、适配器、支付方式）。
- 商户列表经网关 12.7 s、直连 13.4 s，响应字节相同：慢在 near-pay 查询本身，网关无可见开销（门店、渠道账户列表同样约 17 s）。
- 写：测试商户 `PM0E0F6CC8F7454D75` 收款策略，过期版本原样拿到 409 `VERSION_CONFLICT`；按原值保存成功（版本 1→2，内容仍为 `UNRESTRICTED`）。
- near-pay 回调统一认证接口 5 次（全 200），同期经网关的支付请求四十余次：30 秒缓存生效；不再调 `/ops/auth/me`；`[ops-identity]` 拒绝 0 次。

**未验证**：无支付权限账号在 near-pay 回 403（没有现成的低权限测试账号可登录）；进件附件预览（单测覆盖了文件流）；P5 新页面的写操作。

**上线中发现、与本次无关的问题**

1. near-pay → ai-shop `/internal/payment-results/merchant-collections`（收款结果推送）自 11-20 16:39（v1 切 v2）起几乎全部 403（823 次 403、6 次 200），near-pay 每分钟重试。待查：v2 上该内部入口的调用方凭据或来源地址校验（请求现在从 166 经 18081 转到 .80）。
2. near-pay 日志持续 `Payment display rebuild deferred for projection 830/834`（上线前 40 分钟 3000 次）。
3. 商户、门店、渠道账户列表在 near-pay 侧 13–17 秒。

**下一步**：观察一周 `[ops-perm] mapping-only` 日志，无记录后 `permission-source=host`、隐藏 SY03（§7 阶段 6.4）。

# TDD-商户 App 问题回报与版本

状态：草稿（未实现，未拍板）
档位：1（新增端点 4 个 B/公共 + 3 个运营；`notify_ticket` 加列、新表 `sys_app_version`；新错误码 1 个；运营端一个新 tab）
关联需求：
- 交接文档 `docs/交接-商户App功能矩阵与API.md` 第 6 模块「系统状态」6-08、6-09，附录 B 第 8 行（`/biz/feedback`、`/app/version` 被正式包拦下）
- 商户 App PRD（ai-shop-bapp 仓库 `docs/requirements/PRD-NearGo商户B端需求说明书.md`）FR-040（AC-040a 日志去敏）、FR-042（AC-042b 最低支持版本）
- 本仓库工单设计 `docs/technical/design/卡券与工单-需求与技术方案.md` §5.1 G1.5「B 端商家同样可提单」
创建：2026-11-08 · 最后更新：2026-11-08

> **一句话**：商户 App 的「回报问题」落进平台已有的客服工单（`notify_ticket`），按**账号**提交、客服在运营端原有的工单页回复；
> 「版本检查」是一个**匿名**读接口，版本策略存一张小表、运营端可改，并给出「旧版本写入被拒」的统一错误码。

---

## §0 对账一 · 需求 → 设计

### 问题回报（6-08，原型 v22 Y07–Y09）

| AC | 需求（一句话，Given/When/Then 压成一句） | 落点 |
|---|---|---|
| F1 | 已登录的商户账号（店主或店员，**不选门店也行**）提交分类 + 描述，得到回报号 | `POST /biz/feedback` → 新 `BizFeedbackController#submit` → `MessageService#createMerchantFeedback` → `notify_ticket`（`source=B_MERCHANT`） |
| F2 | 描述为空或超过 1000 字、分类不认得、诊断条数超过 200、单字段超长 → 10400，**不落库** | 请求记录上的 Bean Validation + `createMerchantFeedback` 入口校验 |
| F3 | 请求带 `storeNo` 时，必须是这个账号进得去的门店，否则 10403；不带也能提交 | `BizFeedbackController` 用 `MerchantEntityService#myStores` 核对 |
| F4 | 诊断资料只存 `at/method/path/outcome` 四个字段；`path` 去掉查询串、8 位以上数字只留后 4 位（与 App 端 `redactPath` 同规则）；其它字段丢弃 | 新 `FeedbackDiagnostics#sanitize`，结果存 `notify_ticket.diagnostics` |
| F5 | 「我的回报」只列本账号、来源为商户的回报，最近 50 条，新的在前，带客服回复 | `GET /biz/feedback/mine` → `MessageService#myMerchantFeedback` |
| F6 | C 端 `/mp/ticket` 看不到商户回报，商户也看不到 C 端工单（两套账号号段只是「恰好不撞」） | `MessageServiceImpl#myTickets` / `#ticket` 加 `source=C_USER` 条件；商户侧固定 `source=B_MERCHANT` |
| F7 | 新回报进有权处理工单的客服收件箱；运营端工单页能看出来源、分类、门店、版本、平台，并能展开看诊断 | 复用 `createTicket` 的推送；`TicketVO` 加字段；新 `GET /ops/tickets/{ticketNo}/diagnostics`；`ops-web/app/messages/page.tsx` 工单 tab |
| F8 | 客服回复商户回报后，商户「我的回报」显示已回复与回复内容，并收到一条站内信 | `MessageServiceImpl#replyTicket` 对 `B_MERCHANT` 单调 `pushTo(RECEIVER_STAFF, userNo, …)` |
| F9 | 同一账号短时间连发被拒 10429（阈值待决，建议 10 分钟 5 条），被拒那次不计数 | `RateLimiter#tryAcquire("feedback:submit:" + userNo, …)` |
| F10 | 新模型门店的账号调用这两个接口不被 70006 拒 | `CommerceRoutePolicy.ACCOUNT` 加两行 |

### 版本检查（6-09，原型 v22 Y10–Y11）

| AC | 需求 | 落点 |
|---|---|---|
| V1 | 不带令牌、或带过期令牌，都能读到版本信息（HTTP 200、`code=0`）——令牌过期的旧包也要能被劝升级 | `GET /common/app/version`，走 `SecurityConfig#publicChain`（已放行 `/common/**`） |
| V2 | 按 `platform`（`android` / `ios`，不分大小写）返回 `latest`、`minSupported`、`notes[]`、`storeUrl`；`platform` 不认得 → 10400 | 新 `AppVersionController`（`@Profile("api")`）→ `AppVersionService#current` → `sys_app_version` |
| V3 | 该平台还没配置 → `code=0`，`latest`/`minSupported` 为空串、`notes` 为空数组、`storeUrl` 为 null（App 据此不提示） | `AppVersionService#current` 查无行时的返回 |
| V4 | 带 `build` 时多回一个 `upgradeRequired`（`build < minSupported`），比较规则与 App `compareVersions` 一致 | 新 `AppVersions#compare` |
| V5 | `notes` 按 `Accept-Language` 取对应语言；该语言没填 → 回落 `zh-Hant` → 再回落空数组 | `AppVersionService#current` |
| V6 | 运营端能看、能改每个平台的版本策略；`minSupported ≤ latest`；`storeUrl` 只能是 `https://apps.apple.com/…` 或 `https://play.google.com/…`；每次改动留审计 | `GET/PUT /ops/app-versions…` → 新 `OpsAppVersionController`；`ops-web` 新 tab `/system?tab=appVersion` |
| V7 | 旧版本写入被拒：`/biz/**` 的写请求带了 `X-App-Platform` + `X-App-Version` 且低于 `minSupported` → `code=10426`、`msg=CLIENT_UPGRADE_REQUIRED`；**不带这两个头的请求放行**；登出不拦 | 新 `ClientVersionGateFilter` 挂在 `SecurityConfig#merchantChain`；`ErrorCode.CLIENT_UPGRADE_REQUIRED(10426)` |

**孤立项**

- 没落点的 AC：
  - **FR-040 AC-040b 远程诊断授权**（范围 / TTL / 撤销）—— 交接文档写明「首发只做日志和问题反馈」，本 TDD 不做。
  - **回报附截图** —— 交接文档 6 模块「回报暂不支持截图」，本 TDD 不做；要做时复用 `/biz/upload/image`，另开。
  - **6-10 本机资料迁移失败（Y12）** —— 纯 App 本机逻辑，没有服务端部分。
- 挂不上 AC 的设计：
  - `GET /ops/tickets/{ticketNo}/diagnostics`、`TicketVO` 加字段 —— 挂 F7，不是超范围；但它们改动的是 C 端也在用的 `TicketVO`（见 §1「会被改到的」）。
  - V7 的过滤器**依赖 App 先在每个请求上带版本头**，而 App 现在不带（ai-shop-bapp `apps/merchant_app/lib` 里只发 `X-Store-No`、`X-Entity-No`、`X-Merchant-No`、`X-Market-Code`）。服务端先上、App 下一版加头才生效。

---

## §1 现状与影响面

### 后端已有（逐条带出处）

| 能力 | 出处 | 用法 |
|---|---|---|
| 客服工单表 `notify_ticket`（单号、提单人、标题、正文、关联订单、状态、客服回复、指派） | 实体 `backend/shop-core/src/main/java/ai/neargo/shop/message/entity/MsgTicket.java`（`@TableName("notify_ticket")`）；改名迁移 `backend/shop-app/src/main/resources/db/migration/V162__rename_msg_to_notify.sql`；指派列 `V248__ops_wiring.sql`；测试库结构 `backend/shop-app/src/test/resources/schema-test.sql` 第 489 行起 | **直接复用**为回报的存储。状态 `OPEN / REPLIED / CLOSED`（`MsgTicket.OPEN/REPLIED/CLOSED`）恰好覆盖 App 认的 `OPEN\|REPLIED` |
| 提单并推送给有权处理的客服 | `MessageServiceImpl#createTicket`（`backend/shop-core/src/main/java/ai/neargo/shop/message/impl/MessageServiceImpl.java` 第 309 行起，按 `Perms.MESSAGE_TICKET_HANDLE` 找人推站内信） | 商户回报复用同一推送 |
| 客服回复 / 关闭 / 指派 / 代客留痕 | `backend/shop-core/src/main/java/ai/neargo/shop/message/api/ops/OpsTicketController.java`（`/ops/tickets`、`/reply`、`/close`、`/assign`、`/proxy-actions`）；服务 `MessageServiceImpl#replyTicket`、`#closeTicket` | 不改接口，只扩 `TicketVO` 字段 |
| 运营端工单页 | `ops-web/lib/nav.ts` 第 510 行「客服工单与代客留痕」`/messages?tab=tickets`（`message:ticket:read`）；页面 `ops-web/app/messages/page.tsx` | **不加菜单**，只在工单 tab 加列和诊断抽屉 |
| C 端提单与查单 | `backend/shop-core/src/main/java/ai/neargo/shop/message/api/mp/MpMessageController.java`（`/mp/ticket`） | 不复用路径，但要给它的查询加 `source` 条件（F6） |
| 站内信推给商户账号 | `MessageServiceImpl#pushTo`（第 85 行）；商户消息列表按 `MsgMessage.RECEIVER_STAFF` 读（`BizMessageController#messages`） | F8 回复通知 |
| 工单号生成 | `BizKey.TICKET = "TK"`（`backend/shop-base/src/main/java/ai/neargo/shop/common/BizKey.java` 第 93 行） | 回报号 = 工单号 |
| 频率闸 | `backend/shop-base/src/main/java/ai/neargo/shop/common/ratelimit/RateLimiter.java`（返回 `retryAfter`） | F9 |
| 「我能进哪几家店」 | `MerchantEntityService#myStores(LoginUser)`（`backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/MerchantEntityService.java` 第 48 行；`/biz/stores/mine` 用的就是它） | F3 核对 `storeNo` |
| 商户账号级判权 | `PermChecker#merchantAccount()`（`backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/PermChecker.java` 第 76 行） | 回报接口的 `@PreAuthorize` |
| 公共链放行 `/common/**` | `SecurityConfig#publicChain`（`backend/shop-app/src/main/java/ai/neargo/shop/config/SecurityConfig.java` 第 404 行 `securityMatcher("/common/**", …)`，`anyRequest().permitAll()`） | 版本接口放这条链，**不用改 SecurityConfig** |
| 生产 nginx 放行前缀 | `deploy/tencent/nginx/www.hxmall.top.conf` 第 262 行 `location ~ ^/(mp\|biz\|common\|actuator\|uploads\|media\|s)(/\|$)` | `/common` 已在内；`/app` **不在** |
| 账号级路由（不要求选店） | `CommerceRoutePolicy.ACCOUNT`（`backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java` 第 73–87 行） | F10 |
| 运营端系统参数权限码 | `Perms.SYSTEM_PARAM_READ / SYSTEM_PARAM_UPDATE`（`backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/Perms.java` 第 554、560 行；`TECH_OPS` 岗位持有，第 816 行） | V6 |
| 后端错误码 10400 / 10403 / 10429 | `ErrorCode.BAD_REQUEST / FORBIDDEN / TOO_MANY_REQUESTS`（`backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java` 第 19、33、36 行）；**10426 未被占用** | V7 新码用 10426 |

### 后端没有

- `/biz/feedback*`、`/biz/ticket*`：后端没有（`backend` 下搜不到这些映射）。工单设计 §6.2 写过「B 端同构的 `/biz/ticket/**`」，没有实现。
- `/app/version` 或任何「App 版本 / 最低版本」接口与表：后端没有。仓库里唯一的 `app_version` 是**收款终端**的心跳字段（`backend/pay/pay-domain/src/main/resources/db/pay/V16__pay_terminal.sql`、`TerminalActivationController`），属于支付服务、语义是「这台终端装的是哪版」，不能复用为发版策略。
- 请求上的客户端版本头：后端没有读（`ClientMeta` 只取 IP 和 `User-Agent`，`backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/ClientMeta.java`）。

### 路由放行的现状（为什么这两组接口要放在不同的地方）

`/biz/**` 走 `SecurityConfig#merchantChain`（第 171 行），认证后由 `BizContextFilter`（`backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizContextFilter.java` 第 102 行）调 `ShopCommerceEndContextAdapter#select`，按 `CommerceRoutePolicy#match` 分道：

- 在 `ACCOUNT` 集合里 → 不要求选门店，直接放行（`allowsAccountRecovery`）。
- 在 `ShopStoreMemberRoutes` 里 → 要求带 `X-Store-No` 且是该店成员；**不带店号就回 70006 + `SELECT_STORE`**。
- 都不在 → `DENY`；只要这个账号名下有新模型门店，就回 70006 + `CONTACT_ADMIN`（`AccessDecision` 把 FORBIDDEN 映射成 `CONTACT_ADMIN`）。

所以：

- **`/biz/feedback` 必须进 `ACCOUNT`，不能进 `ShopStoreMemberRoutes`**——后者会逼用户先选店才能回报，而「选不了店」本身就是要回报的问题之一。
- **版本接口不应该放在 `/biz/**` 下**——那条链 `anyRequest().authenticated()`（第 189 行），令牌过期的旧包读不到「你该升级了」。

### 会被改到的（已在跑的功能）

- C 端 `/mp/ticket` 三个接口：`myTickets` / `ticket` 加 `source` 条件；存量行迁移时补成 `C_USER`，行为不变。
- 运营端 `/ops/tickets`：`TicketVO` 尾部加可空字段，现有字段与顺序不动；`ops-web` 工单 tab 多几列。
- `SecurityConfig#merchantChain`：多挂一个过滤器（V7），不带版本头的请求行为不变。

### 明确不受影响的

- 工单状态机、指派、代客留痕（`OpsTicketController#assign/proxyAction`）：不改。
- 收款终端的 `pay_terminal.app_version` 与终端心跳：不碰。
- C 端帮助中心 `/mp/faq`：不碰。

### 顺带发现（不在本 TDD 范围，记下来）

- `/biz/push-token` 与 `/biz/push-token/unregister` 既不在 `CommerceRoutePolicy.ACCOUNT`，也不在 `ShopStoreMemberRoutes`，按上面的分道会落到 `DENY`，**名下有新模型门店的账号调用应会 70006**（代码推断，未在 166 验证）。App 接推播时会撞上；建议与 F10 一起把它们放进 `ACCOUNT`。
- `ops-web/lib/types/message.ts` 的 `Ticket` 用的状态是 `PENDING/ASSIGNED/RESOLVED/CLOSED`、标题字段叫 `title`，后端 `TicketVO` 是 `OPEN/REPLIED/CLOSED`、`subject`——两边早已不一致（该文件第 55–90 行自己也注明了 `proxyActions` 只有 mock 有）。本 TDD 只加字段，不顺手修这处；改工单 tab 时要按后端字段读。

---

## §2 方案

### 契约变更总览

| 项 | 内容 |
|---|---|
| 端点（新增） | `POST /biz/feedback`、`GET /biz/feedback/mine`、`GET /common/app/version`、`GET /ops/app-versions`、`PUT /ops/app-versions/{appCode}/{platform}`、`GET /ops/tickets/{ticketNo}/diagnostics` |
| 端点（改响应） | `GET /ops/tickets` 等返回的 `TicketVO` 尾部加可空字段 |
| 库表 | `notify_ticket` 加 8 列 + 1 个索引；新表 `sys_app_version`。迁移号 **V6xx 待定**（当前最新 `V635__ops_staff_second_factor_exempt.sql`） |
| 权限码 | **不新增**（回报用 `@perm.merchantAccount()`；版本策略用 `system:param:read/update`。是否另立一个码见「待决」D6） |
| i18n | 新错误码 `err.client.upgrade_required` 三语（`backend/shop-app/src/main/resources/i18n/messages*.properties`）；`BackendI18nParityTest` 会盯 |
| 配置项 | 无（频率阈值写常量；如要可调见 D4） |
| 请求头（新约定） | `X-App-Platform`、`X-App-Version`（App 端新增发送，服务端只读） |

### 接口一 · `POST /biz/feedback` 提交问题回报

| 项 | 契约 |
|---|---|
| 范围 | **账号范围**。不要求 `X-Store-No`；带了也不作为门店上下文使用 |
| 鉴权 | `Authorization: Bearer btk_…`（`merchantChain`）；`@PreAuthorize("@perm.merchantAccount()")`，不要求任何 `BizPerms` 码 |
| 路由登记 | `CommerceRoutePolicy.ACCOUNT` 加 `"POST /biz/feedback"`；**不登记** `ShopStoreMemberRoutes`；`BizEndpointPermTest` 登记为账号级判权（与 `FOUNDATION_READS` 里 `@perm.merchantAccount()` 同类） |
| 幂等 | 不要求 `Idempotency-Key`（见 D5）；重复提交靠 F9 频率闸兜底 |

请求体：

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `category` | string | 是 | `BUG \| PAYMENT \| IDEA \| OTHER` |
| `description` | string | 是 | 去首尾空白后 1–1000 字（`notify_ticket.content` 是 `VARCHAR(1024)`） |
| `errorCode` | string | 否 | ≤ 32；App 从错误提示进来时带，如 `business_70006` |
| `page` | string | 否 | ≤ 128；App 页面标识 |
| `storeNo` | string | 否 | 带了就必须在 `myStores` 里（F3） |
| `appVersion` | string | 是 | ≤ 32，如 `0.1.0` |
| `platform` | string | 是 | `android \| ios`，不分大小写（App 发的是 Dart `TargetPlatform.name`，即 `android` / `iOS`），服务端存大写 |
| `diagnostics` | array | 否 | ≤ 200 条；每条 `{at: ISO-8601, method, path, outcome}`，其余字段丢弃（F4） |

响应 `data`：

```json
{ "feedbackNo": "TK2611081234567" }
```

错误：10400（F2）· 10403（`storeNo` 不是本账号的店）· 10429（F9，`data` 带 `retryAfterSeconds`）· 401 / 10401（未登录、过期）。

**与 App 建议的差异**

| 差异 | App 建议 | 本 TDD | 理由 |
|---|---|---|---|
| 回报号格式 | mock 用 `FB-20261108-0412` | 用工单号 `TK…`（`BizKey.TICKET`） | 回报就是一张工单，客服在工单页按同一个号搜；另起号段就要维护两套号的映射 |
| `description` 上限 | 未定 | 1000 字 | 存储列 `VARCHAR(1024)`；超出直接 10400，不截断（截断会让客服看到半句话还以为是原文） |
| 诊断条数 | 未定（App 记近 30 分钟） | ≤ 200 条 | 防一次上传几万条把单行撑爆；App 30 分钟内正常用不到 200 条请求 |
| `storeNo` | 随手带 | 带了就核对 | 不核对的话，任何账号都能把别人的门店号挂到工单上，客服顺着它去别人的店里查问题 |

### 接口二 · `GET /biz/feedback/mine` 我的回报

| 项 | 契约 |
|---|---|
| 范围 / 鉴权 / 路由 | 同接口一；`CommerceRoutePolicy.ACCOUNT` 加 `"GET /biz/feedback/mine"` |
| 请求 | 无参数 |
| 响应 `data` | 数组，最多 50 条，按提交时间倒序 |

```json
[{
  "feedbackNo": "TK2611081234567",
  "category": "BUG",
  "description": "列印的字太小",
  "status": "REPLIED",
  "reply": "列印時選「A5」即可放大",
  "storeNo": "ST261106T585CY14HG",
  "createdAt": "2026-11-08T09:20:00Z",
  "repliedAt": "2026-11-08T10:02:00Z"
}]
```

- `status`：`OPEN | REPLIED | CLOSED`。**App 现在只认 `OPEN | REPLIED`**（`feedback_pages.dart` 的 `FeedbackItem.replied`），`CLOSED` 要 App 补一个显示（见 D2）。
- `createdAt` / `repliedAt` 是 ISO-8601 字符串（App 用 `DateTime.tryParse` 读）。这一点和 `TicketVO` 不同（它是毫秒数），所以**单独一个 `FeedbackVO`**，不复用 `TicketVO`。
- 不分页（与 App 建议一致）；50 条以外的旧回报不在 App 里展示。

### 接口三 · `GET /common/app/version` 版本检查（匿名）

| 项 | 契约 |
|---|---|
| 路径 | **`/common/app/version`**（App 建议 `/app/version`，差异见下） |
| 鉴权 | 匿名。带不带 `Authorization` 都行，服务端不读 |
| 放行 | `SecurityConfig#publicChain` 已有 `/common/**` → **不用改 SecurityConfig**；不经过 `merchantChain`，所以 **`CommerceRoutePolicy` 与 `ShopStoreMemberRoutes` 都不涉及**；控制器标 `@Profile("api")`（ops 部署里不存在这个路由） |
| 请求 | query `platform`（必填，`android \| ios`，不分大小写）、`build`（可选，当前版本号如 `0.1.0`）；头 `Accept-Language` |

响应 `data`：

```json
{
  "platform": "ANDROID",
  "latest": "0.2.0",
  "minSupported": "0.1.0",
  "notes": ["收款通知可以語音播報", "修正退款偶爾轉圈的問題"],
  "storeUrl": "https://play.google.com/store/apps/details?id=ai.neargo.merchant",
  "upgradeRequired": false
}
```

- `upgradeRequired` 只在带 `build` 时有值，否则为 null。App 不读它也不影响（它自己比较），给的是服务端口径，免得两边比较规则漂移。
- 没配置（V3）：`latest=""`、`minSupported=""`、`notes=[]`、`storeUrl=null`、`upgradeRequired=null`。
- 错误：10400（`platform` 缺失或不认得）。
- 响应头 `Cache-Control: no-store`：强更是止血手段，不能被中间缓存拖住。

**与 App 建议的差异**

| 差异 | App 建议 | 本 TDD | 理由 |
|---|---|---|---|
| 路径 | `/app/version` | `/common/app/version` | `/app/**` 不在任何一条安全链里，要新增 `publicChain` 匹配；生产 nginx 的放行正则（`www.hxmall.top.conf` 第 262 行）也没有 `app`，还要改部署配置。`/common/**` 两处都已放行，零改动。App 只需改一个字符串 |
| 新增字段 | 无 | `platform`、`upgradeRequired` | 都是附加字段，App 现有解析（`VersionInfo.fromJson`）忽略多余字段 |
| 版本号规则 | App `compareVersions`：按 `.` 拆前三段比数字，`+` 之后丢掉 | 服务端 `AppVersions#compare` **照抄同一规则** | 两边口径必须一致，否则服务端判「该强更」而 App 判「不用」，V7 拒写时 App 弹不出升级页 |

> 若坚持用 `/app/version`：要 ① `SecurityConfig#publicChain` 的 `securityMatcher` 加 `"/app/**"`（按该处注释「显式写出来」的约定）；② `deploy/tencent/nginx/www.hxmall.top.conf` 第 262 行正则加 `app`，166 的 nginx（不在本仓库）同改；③ 控制器仍 `@Profile("api")`。`CommerceRoutePolicy` 依旧不涉及。

### 接口四 · 旧版本写入被拒（V7，不是新端点，是新错误码）

| 项 | 契约 |
|---|---|
| 作用面 | `/biz/**` 的 `POST / PUT / DELETE / PATCH`；排除 `POST /biz/auth/logout`、`POST /biz/push-token/unregister`（让旧包至少能退出、解绑） |
| 判定 | 请求同时带 `X-App-Platform` 与 `X-App-Version`，且 `X-App-Version < minSupported(platform)` → 拒 |
| 不带头 | **放行**。uni-app 版 b-app 与现有 Flutter 包都不带，拦了就是全量误伤 |
| 拒绝的响应 | HTTP 200，`{"code":10426,"msg":"CLIENT_UPGRADE_REQUIRED","data":{"minSupported":"0.2.0","storeUrl":"…"}}`；`msg` 是全大写机器码，App 按 §3 约定会读 |
| App 行为（建议） | 收到 10426 直接进 Y11 强制升级页 |
| 实现位置 | 新 `ClientVersionGateFilter`，挂在 `merchantChain` 的 `MerchantTokenAuthFilter` 之后；策略读 `AppVersionService`（本机缓存 60 秒） |

防住什么：只靠 App 弹窗的话，被拦下的只有「会弹窗的新包」；真正有问题的老包（比如退款金额算错的那一版）恰恰不认识这个弹窗。服务端拒写才是止血。

### 运营端配套

| 页面 | 要不要做 | 说明 |
|---|---|---|
| 客服工单（已有 `/messages?tab=tickets`） | **要改，不加菜单** | 列表加「来源」（C 端 / 商户）、「分类」、「门店」、「版本 · 平台」四列与来源筛选；详情抽屉加「诊断资料」折叠区，点开才调 `GET /ops/tickets/{ticketNo}/diagnostics` |
| App 版本（新 tab `/system?tab=appVersion`） | **要做** | 每个平台一行：最新版、最低支持版、更新说明（按语言）、商店地址；改「最低支持版」时二次确认并显示「低于此版本的商户将无法写入」 |

新增的运营接口：

| 方法 | 路径 | 权限 | 请求 / 响应 |
|---|---|---|---|
| GET | `/ops/app-versions` | `system:param:read` | `[{appCode, platform, latest, minSupported, notes{zh-Hant[],zh-Hans[],en[],ar[]}, storeUrl, updatedBy, updatedAt, revision}]` |
| PUT | `/ops/app-versions/{appCode}/{platform}` | `system:param:update` | `{latest, minSupported, notes, storeUrl, expectedRevision}` → 同上单条；`minSupported > latest` → 10400；`storeUrl` 不是两家商店的 https 地址 → 10400；`expectedRevision` 对不上 → 10409；写审计 `AuditLogPort#record("APP_VERSION_UPDATE", …)` |
| GET | `/ops/tickets/{ticketNo}/diagnostics` | `message:ticket:read` | `[{at, method, path, outcome}]`；C 端工单返回空数组 |

`appCode` 首期只有 `MERCHANT`；留这一维是因为 POS 安卓 App（`docs/technical/design/POS安卓App-技术栈与架构.md`）以后也要强更，届时加一行数据，不加表。

### 库表与迁移（V6xx 待定）

**`notify_ticket` 加列**（存量行 `source` 落默认值 `C_USER`，行为不变）：

| 列 | 类型 | 说明 |
|---|---|---|
| `source` | `VARCHAR(16) NOT NULL DEFAULT 'C_USER'` | `C_USER \| B_MERCHANT`；F6 的隔离靠它 |
| `category` | `VARCHAR(16) NULL` | 商户回报的分类 |
| `store_no` | `VARCHAR(64) NULL` | 提交时所在门店（已核对） |
| `client_version` | `VARCHAR(32) NULL` | App 版本 |
| `client_platform` | `VARCHAR(16) NULL` | `ANDROID \| IOS` |
| `error_code` | `VARCHAR(32) NULL` | 从哪个错误进来 |
| `page` | `VARCHAR(128) NULL` | 从哪一页进来 |
| `diagnostics` | `JSON NULL` | 清洗后的诊断数组 |
| 索引 `idx_ticket_source_user (source, user_no)` | | 「我的回报」与 C 端「我的工单」都按它查 |

`subject`（`NOT NULL VARCHAR(128)`）由服务端生成：`[分类] + 描述前 40 字`，客服列表一眼能扫。

**新表 `sys_app_version`**：

| 列 | 类型 | 说明 |
|---|---|---|
| `app_code` | `VARCHAR(32) NOT NULL` | `MERCHANT` |
| `platform` | `VARCHAR(16) NOT NULL` | `ANDROID \| IOS` |
| `latest_version` | `VARCHAR(32) NULL` | |
| `min_supported_version` | `VARCHAR(32) NULL` | |
| `notes` | `JSON NULL` | `{"zh-Hant":[…],"zh-Hans":[…],"en":[…],"ar":[…]}` |
| `store_url` | `VARCHAR(255) NULL` | |
| 公共列 | | 照 `BaseEntity`：`tenant_no`、`created_*`、`updated_*`、`version`、`deleted` |
| 唯一键 `uk_app_version (tenant_no, app_code, platform)` | | 一个平台只有一行策略 |

不预置数据行（V3 的「没配置不提示」就是首发状态）。

> 交接注意：166 应用库账号没有建表权限，带 DDL 的这次迁移要由 root 先执行（见项目记忆「166 DDL 迁移需手工」）。迁移同时要改 `backend/shop-app/src/test/resources/schema-test.sql`。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizFeedbackController.java` | 两个 `/biz/feedback*` 端点；放 `portal/biz` 是因为它同时要用 `MerchantEntityService`（shop-merchant）和 `MessageService`（shop-core），与 `BizEntityController` 同处 |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/message/FeedbackDiagnostics.java` | F4 清洗；纯函数 |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/message/MessageService.java` / `impl/MessageServiceImpl.java` | 新 `createMerchantFeedback`、`myMerchantFeedback`、`ticketDiagnostics`；`myTickets`/`ticket` 加 `source=C_USER`；`replyTicket` 对商户单推站内信 |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/message/entity/MsgTicket.java` | 加 8 个字段与 `SOURCE_*` 常量 |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/message/dto/MessageVOs.java` | `TicketVO` 尾部加 `source, category, storeNo, clientVersion, clientPlatform`；新 `FeedbackVO`、`DiagnosticVO` |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/message/api/ops/OpsTicketController.java` | 加 `GET /ops/tickets/{ticketNo}/diagnostics` |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/platform/AppVersionService.java` + `impl/AppVersionServiceImpl.java` + `entity/SysAppVersion.java` + mapper | 读写策略、60 秒缓存 |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/platform/AppVersions.java` | 版本比较（与 App `compareVersions` 同规则） |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/platform/api/common/AppVersionController.java` | `GET /common/app/version`，`@Profile("api")` |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/platform/api/ops/OpsAppVersionController.java` | `/ops/app-versions` 两个端点 |
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/config/ClientVersionGateFilter.java` | V7 |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/config/SecurityConfig.java` | `merchantChain` 挂 V7 过滤器（**`publicChain` 不改**） |
| 修改 | `backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java` | `ACCOUNT` 加 `POST /biz/feedback`、`GET /biz/feedback/mine` |
| 修改 | `backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java` | `CLIENT_UPGRADE_REQUIRED(10426, "err.client.upgrade_required")` |
| 修改 | `backend/shop-app/src/main/resources/i18n/messages.properties`、`messages_en.properties`、`messages_ar.properties` | 新码文案 |
| 新增 | `backend/shop-app/src/main/resources/db/migration/V6xx__merchant_feedback_and_app_version.sql` | 上面两张表的 DDL |
| 修改 | `backend/shop-app/src/test/resources/schema-test.sql` | 同步 DDL |
| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java` | 登记两个 `/biz/feedback*` 的判权 |
| 修改 | `ops-web/lib/types/message.ts`、`ops-web/app/messages/page.tsx`、`ops-web/app/messages/copy.ts` | 工单 tab 加列与诊断抽屉 |
| 新增 | `ops-web/app/system/` 下 App 版本 tab（具体文件照现有 tab 的拆法） | V6 |
| 修改 | `ops-web/lib/nav.ts` | 「运行配置」组加 `/system?tab=appVersion`（`system:param:read`）；改完重跑 `python3 scripts/gen-ui-catalog.py` 并提交 `docs/technical/design/ui-catalog.json` |
| App 侧（ai-shop-bapp，供对照） | `features/system/app_version.dart`、`feedback_pages.dart`、`features/v5/contracts.dart`、请求层 | 改版本路径；显示 `CLOSED`；每个请求加 `X-App-Platform`/`X-App-Version`；冻结后删 `pendingContracts` 两行 |

---

## §5 对账三 · 实现 → 需求（测试，计划，未跑）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| F1 | `BizFeedbackFlowTest#merchant_submits_feedback_without_selecting_store` | 未跑 | 计划：去掉 `ACCOUNT` 登记 → 70006 变红 |
| F2 | `BizFeedbackFlowTest#blank_or_overlong_description_is_rejected_and_not_stored`、`#unknown_category_is_rejected`、`#more_than_200_diagnostics_is_rejected` | 未跑 | 计划：注掉长度校验 → 落库变红 |
| F3 | `BizFeedbackFlowTest#store_no_outside_my_stores_is_forbidden` | 未跑 | 计划：注掉 `myStores` 核对 → 变红 |
| F4 | `FeedbackDiagnosticsTest#keeps_only_four_fields_and_redacts_long_numbers`、`#strips_query_string` | 未跑 | 计划：去掉脱敏 → 变红 |
| F5 | `BizFeedbackFlowTest#mine_lists_only_my_merchant_feedback_newest_first_capped_at_50` | 未跑 | |
| F6 | `MessageServiceTicketSourceTest#consumer_tickets_exclude_merchant_feedback_with_same_user_no`、`#merchant_feedback_excludes_consumer_tickets` | 未跑 | 计划：去掉 `source` 条件 → 变红（用同一个 `user_no` 造两边数据） |
| F7 | `OpsTicketFlowTest#merchant_feedback_shows_source_category_store_and_version`、`#diagnostics_endpoint_returns_sanitized_entries` | 未跑 | |
| F8 | `OpsTicketFlowTest#reply_to_merchant_feedback_pushes_staff_message` | 未跑 | |
| F9 | `BizFeedbackFlowTest#sixth_submission_within_window_is_rate_limited` | 未跑 | |
| F10 | `CommerceAccessSecurityFlowTest#feedback_routes_pass_for_new_model_account_without_store` | 未跑 | 计划：删 `ACCOUNT` 两行 → 70006 变红 |
| V1 | `AppVersionHttpTest#anonymous_and_expired_token_both_read_version` | 未跑 | |
| V2 | `AppVersionHttpTest#platform_is_case_insensitive`、`#unknown_platform_is_bad_request` | 未跑 | |
| V3 | `AppVersionHttpTest#unconfigured_platform_returns_empty_fields` | 未跑 | |
| V4 | `AppVersionsTest#compares_like_app_compare_versions`（逐条照抄 App `compareVersions` 的用例）、`AppVersionHttpTest#upgrade_required_when_build_below_min` | 未跑 | |
| V5 | `AppVersionHttpTest#notes_follow_accept_language_then_fall_back_to_zh_hant` | 未跑 | |
| V6 | `OpsAppVersionFlowTest#min_above_latest_is_rejected`、`#store_url_must_be_app_store_or_play`、`#stale_revision_conflicts`、`#update_writes_audit_log` | 未跑 | |
| V7 | `ClientVersionGateFilterTest#old_build_write_gets_10426`、`#missing_headers_pass`、`#reads_are_never_blocked`、`#logout_is_never_blocked` | 未跑 | 计划：过滤器不挂 → 第一条变红 |
| 闸门 | `BizEndpointPermTest`、`BackendI18nParityTest`、`ArchitectureTest`、`gen-ui-catalog.py --check` | 未跑 | |

---

## 待决（要产品 / 用户拍板）

| # | 问题 | 建议 |
|---|---|---|
| D1 | 版本接口路径用 `/common/app/version`（零部署改动）还是保持 App 建议的 `/app/version`（要改安全链与 nginx） | **用 `/common/app/version`**，App 改一个字符串 |
| D2 | 客服「关闭」后，商户看到什么？App 现在只认 `OPEN/REPLIED` | App 把 `CLOSED` 显示为「已结案」；服务端照实返回 `CLOSED`，不伪装成 `REPLIED` |
| D3 | 回报是否允许商户追问（多轮）？现表只有单轮 `reply` | 首期**单轮**，与 C 端一致；多轮等工单设计 §6.1 的 `msg_ticket_message` 落地后两端一起上 |
| D4 | 回报频率阈值 | 同一账号 10 分钟 5 条；先写常量，要运营可调再挪到配置 |
| D5 | 提交要不要幂等键 | 首期不要：回报不是钱，重复一条客服可合并；网络重送造成的重复靠 D4 兜住 |
| D6 | 改「最低支持版本」用现有 `system:param:update`（TECH_OPS 就有）还是单独一个码 | **单独一个码 `system:app-version:update`**，只给超管与发版负责人：抬高最低版本等于让全部旧包停写，影响面比一般参数大。若接受，契约里补权限码与种子迁移 |
| D7 | V7 拒写首期就上，还是等 App 先发一版带版本头的包 | 服务端先上（不带头一律放行，零风险）；App **首个上架版本就带头**，否则那一版永远拦不住 |
| D8 | 更新说明要几种语言 | 首期 `zh-Hant` 必填，其它可空（回落 `zh-Hant`） |
| D9 | 回报诊断资料保留多久 | 90 天后清空 `diagnostics` 列、保留工单本身；需要的话另起清理任务 |
| D10 | 166 预发布的 nginx 是否把 `/shop-api/common/**` 转到后端（配置不在本仓库） | 部署侧确认一次；生产 `www.hxmall.top.conf` 已放行 `/common` |

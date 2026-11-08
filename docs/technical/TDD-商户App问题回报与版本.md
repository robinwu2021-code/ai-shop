# TDD-商户 App 问题回报与版本

状态：已实现（2026-11-08：用户按「已拍板」一节各条的建议全部接受；实现提交 936885ea1，§5 测试已跑，偏差见 §6）
档位：1（新增端点 3 个 B/公共 + 3 个运营；`notify_ticket` 加列、新表 `sys_app_version`；新错误码 1 个；新权限码 1 个（P5）；运营端一个新 tab）
关联需求：
- 交接文档（ai-shop-bapp 仓库 `docs/implementation/功能矩阵与API交接文档.md`）第 6 模块「系统状态」6-08、6-09，附录 B 第 8 行（`/biz/feedback`、`/app/version` 被正式包拦下）
- 商户 App PRD（ai-shop-bapp 仓库 `docs/requirements/PRD-NearGo商户B端需求说明书.md`）FR-040（AC-040a 日志去敏）、FR-042（AC-042b 最低支持版本）
- 本仓库工单设计 `docs/technical/design/卡券与工单-需求与技术方案.md` §5.1 G1.5「B 端商家同样可提单」
- 评审：ai-shop-bapp 仓库 `docs/implementation/TDD评审-商户App.md`「最需要先定的」第 8、9 条与「第一组 · 三」（本轮修订逐条见文末「评审修订说明」）
创建：2026-11-08 · 最后更新：2026-11-08

> **一句话**：商户 App 的「回报问题」落进平台已有的客服工单（`notify_ticket`），按**账号**提交、客服在运营端原有的工单页回复；
> 「版本检查」是一个**匿名**读接口，版本策略存一张小表、运营端可改，并给出「旧版本写入被拒」的统一错误码。

---

## §0 对账一 · 需求 → 设计

### 问题回报（6-08，原型 v22 Y07–Y09）

| AC | 需求（一句话，Given/When/Then 压成一句） | 落点 |
|---|---|---|
| F1 | 已登录的商户账号（店主或店员，**不选门店也行**）提交分类 + 描述，得到回报号；**未登录 → 401**（首期不做匿名回报，见已拍板 P1） | `POST /biz/feedback` → 新 `BizFeedbackController#submit` → `MessageService#createMerchantFeedback` → `notify_ticket`（`source=B_MERCHANT`） |
| F2 | **正文严、附带资料宽**：描述为空或超过 1000 字、分类不认得、缺 `appVersion` → 10400，**不落库**；附带资料（`errorCode`/`page`/`appVersion` 超长、`platform` 不认得、诊断超过 300 条、诊断单条字段超长或 `at` 不带时区）**一律不拒单**，按 §2 接口一的规则截断或归一后照常落库 | 请求记录上的 Bean Validation（只管正文）+ `createMerchantFeedback` 入口的归一（`FeedbackDiagnostics#sanitize` 管诊断） |
| F3 | 请求带 `storeNo` 时，核对是这个账号进得去的门店：是 → 存进 `store_no`；**不是 → 丢弃门店号、照常落库**（`store_no` 为空，`store_dropped=1`），不回 10403；不带也能提交 | `BizFeedbackController` 用 `MerchantEntityService#myStores` 核对 |
| F4 | 诊断资料只存 `at/method/path/outcome` 四个字段（`at` 的时区处理见 §2 接口一）；`path` 去掉查询串、8 位以上数字只留后 4 位（与 App 端 `redactPath` 同规则）；其它字段丢弃 | 新 `FeedbackDiagnostics#sanitize`，结果存 `notify_ticket.diagnostics` |
| F5 | 「我的回报」只列本账号、来源为商户的回报，最近 50 条，新的在前，带客服回复 | `GET /biz/feedback/mine` → `MessageService#myMerchantFeedback` |
| F6 | C 端 `/mp/ticket` 看不到商户回报，商户也看不到 C 端工单（两套账号号段只是「恰好不撞」） | `MessageServiceImpl#myTickets` / `#ticket` 加 `source=C_USER` 条件；商户侧固定 `source=B_MERCHANT` |
| F7 | 新回报进有权处理工单的客服收件箱；运营端工单页能看出来源、分类、门店、版本、平台，并能展开看诊断 | 复用 `createTicket` 的推送；`TicketVO` 加字段；新 `GET /ops/tickets/{ticketNo}/diagnostics`；`ops-web/app/messages/page.tsx` 工单 tab |
| F8 | 客服回复商户回报后，商户「我的回报」显示已回复与回复内容，并收到一条站内信；**无论 App 当前选的是哪家门店，消息列表里都看得到** | `MessageServiceImpl#replyTicket` 对 `B_MERCHANT` 单调 `pushTo(RECEIVER_STAFF, userNo, …)`；`GET /biz/message` 只按收件人查、不按门店过滤（§1） |
| F9 | 同一账号短时间连发被拒 10429（阈值见已拍板 P3，建议 10 分钟 5 条），被拒那次不计数；**响应不带等待秒数**（§2 接口一说明） | `RateLimiter#tryAcquire("feedback:submit:" + userNo, …)` |
| F10 | 新模型门店的账号调用这两个接口不被 70006 拒 | `CommerceRoutePolicy.ACCOUNT` 加两行 |

### 版本检查（6-09，原型 v22 Y10–Y11）

| AC | 需求 | 落点 |
|---|---|---|
| V1 | 不带令牌、或带过期令牌，都能读到版本信息（HTTP 200、`code=0`）——令牌过期的旧包也要能被劝升级 | `GET /common/app/version`，走 `SecurityConfig#publicChain`（已放行 `/common/**`） |
| V2 | 按 `platform`（`android` / `ios`，不分大小写）返回 `latest`、`minSupported`、`notes[]`、`storeUrl`；`platform` 不认得 → 10400 | 新 `AppVersionController`（`@Profile("api")`）→ `AppVersionService#current` → `sys_app_version` |
| V3 | 该平台还没配置 → `code=0`，`latest`/`minSupported` 为空串、`notes` 为空数组、`storeUrl` 为 null（App 据此不提示） | `AppVersionService#current` 查无行时的返回 |
| V4 | 带 `build` 时多回一个 `upgradeRequired`（`build < minSupported`）；版本号只取开头的 `数字(.数字){0,2}`，**`1.4.2 (20261108.1)` 按 `1.4.2` 比**（App 现有 `compareVersions` 会把它当成 `1.4.0`，见 §2 接口三） | 新 `AppVersions#compare` |
| V5 | `notes` 按 `Accept-Language` 取对应语言（BCP 47 归并规则见 §2 接口三：`zh-Hant`、`zh-Hant-TW`、`zh-TW`、`zh-HK` 都归到 `zh-Hant`）；该语言没填 → 回落 `zh-Hant` → 再回落空数组 | `AppVersionService#current` |
| V6 | 运营端能看、能改每个平台的版本策略；`minSupported ≤ latest`；`storeUrl` 只能是 `https://apps.apple.com/…` 或 `https://play.google.com/…`；每次改动留审计 | `GET/PUT /ops/app-versions…` → 新 `OpsAppVersionController`；`ops-web` 新 tab `/system?tab=appVersion` |
| V7 | 旧版本写入被拒：`/biz/**` 的写请求带了 `X-App-Platform` + `X-App-Version` 且低于 `minSupported` → `code=10426`、`msg=CLIENT_UPGRADE_REQUIRED`；**不带这两个头的请求放行**；登出、解绑推播、**问题回报、四个登录类写请求**不拦（清单与理由见 §2 接口四） | 新 `ClientVersionGateFilter` 挂在 `SecurityConfig#merchantChain`；`ErrorCode.CLIENT_UPGRADE_REQUIRED(10426)` |

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
| 站内信推给商户账号 | `MessageServiceImpl#pushTo`（第 85 行）；商户消息列表按 `MsgMessage.RECEIVER_STAFF` 读（`BizMessageController#messages`，`backend/shop-core/src/main/java/ai/neargo/shop/message/api/biz/BizMessageController.java` 第 42–44 行） | F8 回复通知 |
| 商户消息列表**只按收件人查，不按门店过滤** | `MessageServiceImpl#rows`（第 424–429 行：条件只有 `receiver_type` 与 `receiver_no = 当前账号`）；`X-Store-No` 在 `BizMessageController#messages` 里只用来算售后待办标记（第 55–57 行） | F8：账号级的回复通知在任一门店的消息列表里都看得到 |
| 工单号生成 | `BizKey.TICKET = "TK"`（`backend/shop-base/src/main/java/ai/neargo/shop/common/BizKey.java` 第 93 行） | 回报号 = 工单号 |
| 频率闸 | `backend/shop-base/src/main/java/ai/neargo/shop/common/ratelimit/RateLimiter.java`（返回 `retryAfter`） | F9 |
| 「我能进哪几家店」 | `MerchantEntityService#myStores(LoginUser)`（`backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/MerchantEntityService.java` 第 48 行；`/biz/stores/mine` 用的就是它） | F3 核对 `storeNo` |
| 商户账号级判权 | `PermChecker#merchantAccount()`（`backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/PermChecker.java` 第 76 行） | 回报接口的 `@PreAuthorize` |
| 公共链放行 `/common/**` | `SecurityConfig#publicChain`（`backend/shop-app/src/main/java/ai/neargo/shop/config/SecurityConfig.java` 第 404 行 `securityMatcher("/common/**", …)`，`anyRequest().permitAll()`） | 版本接口放这条链，**不用改 SecurityConfig** |
| 生产 nginx 放行前缀 | `deploy/tencent/nginx/www.hxmall.top.conf` 第 262 行 `location ~ ^/(mp\|biz\|common\|actuator\|uploads\|media\|s)(/\|$)` | `/common` 已在内；`/app` **不在** |
| 账号级路由（不要求选店） | `CommerceRoutePolicy.ACCOUNT`（`backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java` 第 73–87 行） | F10 |
| 运营端系统参数权限码 | `Perms.SYSTEM_PARAM_READ / SYSTEM_PARAM_UPDATE`（`backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/Perms.java` 第 554、560 行；`TECH_OPS` 岗位持有，第 816 行） | V6 |
| 后端错误码 10400 / 10403 / 10429 | `ErrorCode.BAD_REQUEST / FORBIDDEN / TOO_MANY_REQUESTS`（`backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java` 第 19、33、36 行）；**10426 未被占用** | V7 新码用 10426 |
| 业务异常的响应**不带 `data`** | `BizException` 只有错误码与消息参数（`backend/shop-base/src/main/java/ai/neargo/shop/common/BizException.java`）；`GlobalExceptionHandler#onBiz`（`backend/shop-base-auth/src/main/java/ai/neargo/shop/common/GlobalExceptionHandler.java` 第 48–60 行）返回 `ApiResult<Void>`；10429 的文案 `err.too_many_requests` 没有秒数占位符（`messages.properties` 第 7 行），而且 `PickupServiceImpl`、`AuthServiceImpl` 等多处共用 | F9 不承诺 `data.retryAfterSeconds`（§2 接口一） |
| 登录类写请求也在 `merchantChain` 上 | `SecurityConfig#merchantChain` 第 185–186 行把 `/biz/auth/login`、`/biz/auth/staff-login`、`/biz/auth/otp/send`、`/biz/auth/email-otp/send` 设为 `permitAll`——它们**经过**这条链上的过滤器，只是不要求登录 | V7 必须显式决定拦不拦（§2 接口四） |

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
| 库表 | `notify_ticket` 加 9 列 + 1 个索引；新表 `sys_app_version`。迁移 **`V638__merchant_feedback_and_app_version.sql`**（DDL）与 **`V639__app_version_perm_and_menu.sql`**（菜单点与权限码种子） |
| 权限码 | 新增 **`system:app-version:update`**（已拍板 P5 = B，只授超管，V639 种子）；回报用 `@perm.merchantAccount()`；看版本策略用 `system:param:read` |
| i18n | 新错误码 `err.client.upgrade_required` 三语（`backend/shop-app/src/main/resources/i18n/messages*.properties`）；`BackendI18nParityTest` 会盯 |
| 配置项 | 无（频率阈值写常量；如要可调见「已拍板」P3） |
| 请求头（新约定） | `X-App-Platform`、`X-App-Version`（App 端新增发送，服务端只读） |

### 接口一 · `POST /biz/feedback` 提交问题回报

| 项 | 契约 |
|---|---|
| 范围 | **账号范围**。不要求 `X-Store-No`；带了也不作为门店上下文使用 |
| 鉴权 | `Authorization: Bearer btk_…`（`merchantChain`）；`@PreAuthorize("@perm.merchantAccount()")`，不要求任何 `BizPerms` 码。**未登录 → 401**：App 挂在通用错误条上的「回报这个错误」（ai-shop-bapp `onboarding_widgets.dart` 第 222 行）在登录、注册页也会出现，未登录时 App 应隐藏这个入口；要不要开匿名回报见已拍板 P1 |
| 路由登记 | `CommerceRoutePolicy.ACCOUNT` 加 `"POST /biz/feedback"`；**不登记** `ShopStoreMemberRoutes`；`BizEndpointPermTest` 登记为账号级判权（与 `FOUNDATION_READS` 里 `@perm.merchantAccount()` 同类） |
| 幂等 | 不要求 `Idempotency-Key`（见「评审修订说明」已定·原 D5）；重复提交靠 F9 频率闸兜底 |

请求体：

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `category` | string | 是 | `BUG \| PAYMENT \| IDEA \| OTHER`；不认得 → 10400（以后加分类见已拍板 P4） |
| `description` | string | 是 | 去首尾空白后 **1**–1000 字（`notify_ticket.content` 是 `VARCHAR(1024)`）。服务端下限是 1；App 输入框的「至少 4 字、最多 500 字」是端上更严的约束，OpenAPI 里两个数都写明，免得以后对不上 |
| `errorCode` | string | 否 | **≤ 64，超长截断不拒单**；App 从错误提示进来时带 `AppFailure.code`，形如 `business_70006`，最长的已知值 `business_CONTEXT_REFRESH_REQUIRED` 是 33 字（ai-shop-bapp `packages/foundation/lib/src/network.dart` 第 175、268 行） |
| `page` | string | 否 | ≤ 128，超长截断；App 页面标识 |
| `storeNo` | string | 否 | 核对通过才存；不属于本账号 → 丢弃、置 `store_dropped=1`，**照常落库**（F3） |
| `appVersion` | string | 是 | ≤ 32，超长截断；缺失 → 10400 |
| `platform` | string | 是 | 不分大小写：`android` → `ANDROID`、`ios` → `IOS`，**其它值（App 在桌面或调试环境发的 `macOS`、`windows`、`linux`）→ `OTHER`，不拒单**（App 发的是 Dart `TargetPlatform.name`） |
| `diagnostics` | array | 否 | **最多保留 300 条**：超过 300 条时按数组顺序保留**最后** 300 条（App 是旧的在前），不拒单。每条 `{at, method, path, outcome}`，其余字段丢弃（F4）；单条字段超长截断：`method` ≤ 8、`path` ≤ 256、`outcome` ≤ 96 |

诊断的 `at`（评审修订）：App 现在用 `DateTime.now().toIso8601String()`，得到的是**不带时区的设备本地时间**（ai-shop-bapp `apps/merchant_app/lib/features/system/system_status.dart` 第 17 行）。服务端按下面三种情况处理，**都不拒单**：

| 收到的 `at` | 处理 | 运营端显示 |
|---|---|---|
| 带 `Z` 或 `±hh:mm` | 解析后统一存成 UTC（`…Z`） | 按运营端时区显示 |
| 不带时区（`2026-11-08T17:20:00.123`） | **原样存**，不猜时区 | 原文 + 「设备本地时间」标记 |
| 解析不了 | `at` 存 null，这条诊断保留 | 「时间缺失」 |

防住什么：按 UTC 硬解析不带时区的值，台北的回报会整体偏 8 小时，客服拿去对服务端日志永远对不上；而按 ISO-8601 严格解析直接拒单，回报就落不了库。建议 App 改为 `toUtc().toIso8601String()`，改了以后走第一行。

响应 `data`：

```json
{ "feedbackNo": "TK2611081234567" }
```

错误：10400（F2 的正文类错误）· 10429（F9）· 401 / 10401（未登录、过期）。**不再有 10403**（F3 改为丢弃门店号）。

10429 **不带等待秒数**（评审修订）：草稿原写「`data` 带 `retryAfterSeconds`」，但后端的业务异常响应没有 `data`（`GlobalExceptionHandler#onBiz` 返回 `ApiResult<Void>`），而 `err.too_many_requests` 的文案没有秒数占位符、又被多处共用，改它会波及别的接口。App 的 `network.dart` 已把 10429 归为 `rateLimited`（第 266 行），显示通用的「稍后再试」即可；10 分钟 5 条的闸，正常使用碰不到。

**与 App 建议的差异**

| 差异 | App 建议 | 本 TDD | 理由 |
|---|---|---|---|
| 回报号格式 | mock 用 `FB-20261108-0412` | 用工单号 `TK…`（`BizKey.TICKET`） | 回报就是一张工单，客服在工单页按同一个号搜；另起号段就要维护两套号的映射 |
| `description` 上限 | 未定 | 1000 字 | 存储列 `VARCHAR(1024)`；超出直接 10400，不截断（截断会让客服看到半句话还以为是原文） |
| 诊断条数 | 未定（App 记近 30 分钟、内存里最多 300 条：`system_status.dart` 第 66 行） | 最多保留 300 条，多了留最后 300 条 | 上限不能低于 App 的 300：草稿原定 200 条且超了 10400，App 一次正常的回报就会整单被拒。300 条 × 单条上限约 400 字节 ≈ 120 KB，单行 JSON 放得下 |
| `storeNo` | 随手带 | 带了就核对；核对不过**丢弃门店号、照常落库** | 不核对的话，任何账号都能把别人的门店号挂到工单上，客服顺着它去别人的店里查问题；但核对不过就整单拒，最常见的那种回报——「权限刚被收回、门店进不去」（App 的 `controller.selected` 还是旧门店）——反而落不了库。所以只丢门店号、记 `store_dropped=1`，客服看到这个标记就知道「提交时带的店他已经进不去了」 |
| 附带资料超长 | — | 截断，不拒单 | 回报是出问题时的求助通道，附带资料（错误码、页面、版本、平台、诊断）不合规不该让求助本身失败；正文（分类、描述）才严格校验 |

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

- `status`：`OPEN | REPLIED | CLOSED`。**App 现在只认 `OPEN | REPLIED`**（`feedback_pages.dart` 的 `FeedbackItem.replied`），`CLOSED` 要 App 补一个显示「已结案」（已定·原 D2）。
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

- `upgradeRequired` 只在带 `build` 时有值，否则为 null。**（评审修订）建议 App 以它为准**，本地 `compareVersions` 只在它为 null 时兜底：V7 拒写用的是服务端比较，两边规则只要差一点，就会出现「服务端拦了写、App 却判断不用升级」。草稿原写「App 不读它也不影响」，收回。
- 没配置（V3）：`latest=""`、`minSupported=""`、`notes=[]`、`storeUrl=null`、`upgradeRequired=null`。
- 错误：10400（`platform` 缺失或不认得）。
- `Accept-Language` 归并（V5，评审修订补写）：取头里第一个能归并的标签，不分大小写——`zh-Hant*`、`zh-TW`、`zh-HK`、`zh-MO` → `zh-Hant`；`zh-Hans*`、`zh-CN`、`zh-SG` → `zh-Hans`；`en*` → `en`；`ar*` → `ar`；其它、裸 `zh` 或没带 → `zh-Hant`。App 送的是 `zh-Hant`、`en` 这类 BCP 47 标签，也可能带地区（`zh-Hant-TW`），都要命中。
- 响应头 `Cache-Control: no-store`：强更是止血手段，不能被中间缓存拖住。

**与 App 建议的差异**

| 差异 | App 建议 | 本 TDD | 理由 |
|---|---|---|---|
| 路径 | `/app/version` | `/common/app/version` | `/app/**` 不在任何一条安全链里，要新增 `publicChain` 匹配；生产 nginx 的放行正则（`www.hxmall.top.conf` 第 262 行）也没有 `app`，还要改部署配置。`/common/**` 两处都已放行，零改动。App 只需改一个字符串 |
| 新增字段 | 无 | `platform`、`upgradeRequired` | 都是附加字段，App 现有解析（`VersionInfo.fromJson`）忽略多余字段 |
| 版本号规则 | App `compareVersions`：按 `.` 拆前三段比数字，`+` 之后丢掉 | 服务端 `AppVersions#compare`：**只取开头的 `数字(.数字){0,2}`**，缺的段按 0，之后的内容（`+…`、空格、括号里的构建号）一律丢掉 | 草稿原写「照抄 App 规则」，评审时核对发现照抄会连 bug 一起抄：App 的版本号来自编译参数 `APP_VERSION`，注释里的示例是 `1.4.0 (20261108.1)`（ai-shop-bapp `features/me/me_pages.dart` 第 22–23 行）；`compareVersions` 对 `1.4.2 (20261108.1)` 拆出的第三段是 `2 (20261108`，`int.tryParse` 失败按 0，于是 **1.4.2 被当成 1.4.0**。服务端按前缀取就不会错；App 要么改成同样的前缀规则，要么请求头 `X-App-Version` 与 `build` 只送 `1.4.2` 这一段。打包脚本也必须保证 `APP_VERSION` 与商店版本号一致 |

> 若坚持用 `/app/version`：要 ① `SecurityConfig#publicChain` 的 `securityMatcher` 加 `"/app/**"`（按该处注释「显式写出来」的约定）；② `deploy/tencent/nginx/www.hxmall.top.conf` 第 262 行正则加 `app`，166 的 nginx（不在本仓库）同改；③ 控制器仍 `@Profile("api")`。`CommerceRoutePolicy` 依旧不涉及。

### 接口四 · 旧版本写入被拒（V7，不是新端点，是新错误码）

| 项 | 契约 |
|---|---|
| 作用面 | `/biz/**` 的 `POST / PUT / DELETE / PATCH`，**排除下面这张表里的七个**（评审修订：原稿只排除了前两个） |
| 判定 | 请求同时带 `X-App-Platform` 与 `X-App-Version`，且 `X-App-Version < minSupported(platform)` → 拒 |
| 不带头 | **放行**。uni-app 版 b-app 与现有 Flutter 包都不带，拦了就是全量误伤 |
| 拒绝的响应 | HTTP 200，`{"code":10426,"msg":"CLIENT_UPGRADE_REQUIRED","data":{"minSupported":"0.2.0","storeUrl":"…"}}`；`msg` 是全大写机器码，App 按 §3 约定会读 |
| App 行为（建议） | 收到 10426 直接进 Y11 强制升级页 |
| 实现位置 | 新 `ClientVersionGateFilter`，挂在 `merchantChain` 的 `MerchantTokenAuthFilter` 之后；策略读 `AppVersionService`（本机缓存 60 秒） |

防住什么：只靠 App 弹窗的话，被拦下的只有「会弹窗的新包」；真正有问题的老包（比如退款金额算错的那一版）恰恰不认识这个弹窗。服务端拒写才是止血。

**不拦的写请求**（过滤器里一张常量表，`ClientVersionGateFilterTest` 逐条断言）：

| 请求 | 为什么不拦 |
|---|---|
| `POST /biz/auth/logout` | 让旧包至少能退出 |
| `POST /biz/push-token/unregister` | 让旧包能解绑推播，不再收到它处理不了的通知 |
| `POST /biz/feedback` | 强制升级页 Y11 上有「回报问题」（ai-shop-bapp `features/system/app_version.dart` 第 171 行）。拦了它，「升不了级 / 商店打不开」这种最需要回报的问题就报不上来；回报本身不改任何业务数据 |
| `POST /biz/auth/login`、`POST /biz/auth/staff-login`、`POST /biz/auth/otp/send`、`POST /biz/auth/email-otp/send` | **登录类不拦，是有意的**。它们在 `merchantChain` 上（`SecurityConfig` 第 185–186 行 `permitAll`），不排除就会被拦。不拦的理由：① 登录不写业务数据，旧包登录进来后第一笔业务写入照样 10426；② 回报接口要登录态——令牌过期的旧包如果连登录都被拦，就再也报不了问题；③ 少一处「登录页也要认 10426」的端上要求。拦的话 App 登录页必须认 10426 并显示 Y11，否则用户看到的是一条通用错误 |

`POST /biz/auth/password`（设密码）**照拦**：它是账号写入，旧包拿到 10426 进 Y11 即可。

### 运营端配套

| 页面 | 要不要做 | 说明 |
|---|---|---|
| 客服工单（已有 `/messages?tab=tickets`） | **要改，不加菜单** | 列表加「来源」（C 端 / 商户）、「分类」、「门店」、「版本 · 平台」四列与来源筛选；详情抽屉加「诊断资料」折叠区，点开才调 `GET /ops/tickets/{ticketNo}/diagnostics` |
| App 版本（新 tab `/system?tab=appVersion`） | **要做** | 每个平台一行：最新版、最低支持版、更新说明（按语言）、商店地址；改「最低支持版」时二次确认并显示「低于此版本的商户将无法写入」 |

新增的运营接口：

| 方法 | 路径 | 权限 | 请求 / 响应 |
|---|---|---|---|
| GET | `/ops/app-versions` | `system:param:read` | `[{appCode, platform, latest, minSupported, notes{zh-Hant[],zh-Hans[],en[],ar[]}, storeUrl, updatedBy, updatedAt, revision}]` |
| PUT | `/ops/app-versions/{appCode}/{platform}` | `system:app-version:update`（P5） | `{latest, minSupported, notes, storeUrl, expectedRevision}` → 同上单条；`minSupported > latest` → 10400；`storeUrl` 不是两家商店的 https 地址 → 10400；`expectedRevision` 对不上 → 10409；写审计 `AuditLogPort#record("APP_VERSION_UPDATE", …)` |
| GET | `/ops/tickets/{ticketNo}/diagnostics` | `message:ticket:read` | `[{at, method, path, outcome}]`；C 端工单返回空数组 |

`appCode` 首期只有 `MERCHANT`；留这一维是因为 POS 安卓 App（`docs/technical/design/POS安卓App-技术栈与架构.md`）以后也要强更，届时加一行数据，不加表。

### 库表与迁移（V638 / V639）

**`notify_ticket` 加列**（存量行 `source` 落默认值 `C_USER`，行为不变）：

| 列 | 类型 | 说明 |
|---|---|---|
| `source` | `VARCHAR(16) NOT NULL DEFAULT 'C_USER'` | `C_USER \| B_MERCHANT`；F6 的隔离靠它 |
| `category` | `VARCHAR(16) NULL` | 商户回报的分类 |
| `store_no` | `VARCHAR(64) NULL` | 提交时所在门店（已核对；核对不过不存） |
| `store_dropped` | `TINYINT(1) NOT NULL DEFAULT 0` | 提交时带了门店号、但不属于该账号而被丢弃（F3）；运营端显示「门店号未核实已丢弃」 |
| `client_version` | `VARCHAR(32) NULL` | App 版本 |
| `client_platform` | `VARCHAR(16) NULL` | `ANDROID \| IOS \| OTHER` |
| `error_code` | `VARCHAR(64) NULL` | 从哪个错误进来（草稿原为 32，App 已知值有 33 字） |
| `page` | `VARCHAR(128) NULL` | 从哪一页进来 |
| `diagnostics` | `JSON NULL` | 清洗后的诊断数组（≤ 300 条） |
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
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/message/FeedbackDiagnostics.java` | F4 清洗：留四个字段、截到 300 条、单字段截断、`at` 归一；纯函数 |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/message/MessageService.java` / `impl/MessageServiceImpl.java` | 新 `createMerchantFeedback`、`myMerchantFeedback`、`ticketDiagnostics`；`myTickets`/`ticket` 加 `source=C_USER`；`replyTicket` 对商户单推站内信 |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/message/entity/MsgTicket.java` | 加 9 个字段与 `SOURCE_*` 常量 |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/message/dto/MessageVOs.java` | `TicketVO` 尾部加 `source, category, storeNo, storeDropped, clientVersion, clientPlatform`；新 `FeedbackVO`、`DiagnosticVO` |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/message/api/ops/OpsTicketController.java` | 加 `GET /ops/tickets/{ticketNo}/diagnostics` |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/platform/AppVersionService.java` + `impl/AppVersionServiceImpl.java` + `entity/SysAppVersion.java` + mapper | 读写策略、60 秒缓存 |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/platform/AppVersions.java` | 版本比较（与 App `compareVersions` 同规则） |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/platform/api/common/AppVersionController.java` | `GET /common/app/version`，`@Profile("api")` |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/platform/api/ops/OpsAppVersionController.java` | `/ops/app-versions` 两个端点 |
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/config/ClientVersionGateFilter.java` | V7；七个不拦的写请求写成常量表 |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/config/SecurityConfig.java` | `merchantChain` 挂 V7 过滤器（**`publicChain` 不改**） |
| 修改 | `backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java` | `ACCOUNT` 加 `POST /biz/feedback`、`GET /biz/feedback/mine` |
| 修改 | `backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java` | `CLIENT_UPGRADE_REQUIRED(10426, "err.client.upgrade_required")` |
| 修改 | `backend/shop-app/src/main/resources/i18n/messages.properties`、`messages_en.properties`、`messages_ar.properties` | 新码文案 |
| 新增 | `backend/shop-app/src/main/resources/db/migration/V638__merchant_feedback_and_app_version.sql`、`V639__app_version_perm_and_menu.sql` | 上面两张表的 DDL；「App 版本」菜单点与 `system:app-version:update` 种子 |
| 修改 | `backend/shop-app/src/test/resources/schema-test.sql` | 同步 DDL |
| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java` | 登记两个 `/biz/feedback*` 的判权 |
| 修改 | `ops-web/lib/types/message.ts`、`ops-web/app/messages/page.tsx`、`ops-web/app/messages/copy.ts` | 工单 tab 加列与诊断抽屉 |
| 新增 | `ops-web/app/system/` 下 App 版本 tab（具体文件照现有 tab 的拆法） | V6 |
| 修改 | `ops-web/lib/nav.ts` | 「运行配置」组加 `/system?tab=appVersion`（`system:param:read`）；改完重跑 `python3 scripts/gen-ui-catalog.py` 并提交 `docs/technical/design/ui-catalog.json` |
| App 侧（ai-shop-bapp，供对照） | `features/system/app_version.dart`、`feedback_pages.dart`、`system_status.dart`、`features/v5/contracts.dart`、`packages/foundation/lib/src/network.dart` | 改版本路径并改成不带令牌的公开调用；以 `upgradeRequired` 为准；显示 `CLOSED`；在 `network.dart` 这一层给每个请求加 `X-App-Platform`/`X-App-Version`（只送 `数字.数字.数字` 段）；全局认 10426；诊断 `at` 改送 UTC；未登录时隐藏「回报这个错误」；冻结后删 `pendingContracts` 两行。**服务端已放宽的（条数、`errorCode` 长度、`platform`、`at`）App 不改也不会被拒** |

---

## §5 对账三 · 实现 → 需求（测试，已跑）

先写测试跑出红：实现前跑 7 个流程类 49 条，**8 失败 / 22 错误**（端点、表、错误码都不存在；未登录 401 那条本来就绿）。
实现后全部转绿。本仓库约定不强制回退实现再造红（`references/java.md` §8.4），「消融」列记的是实现前的那次红与断言能识别的缺陷。

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| F1 | `BizFeedbackFlowTest#merchant_submits_feedback_without_selecting_store`、`#anonymous_submission_is_unauthorized` | ✅ | 实现前红（404/无表） |
| F2 | `BizFeedbackFlowTest#blank_or_overlong_description_is_rejected_and_not_stored`、`#unknown_category_is_rejected`、`#app_shaped_report_is_accepted`（301 条诊断、33 字 `errorCode`、`platform=macOS`、不带时区的 `at`）；`FeedbackDiagnosticsTest#keeps_last_300_entries`、`#truncates_overlong_fields`、`#offset_at_is_normalized_to_utc_and_local_at_is_kept_verbatim`、`#unparseable_at_becomes_null` | ✅ | 实现前红 |
| F3 | `BizFeedbackFlowTest#store_no_outside_my_stores_is_dropped_and_feedback_still_stored`、`#store_no_of_my_store_is_kept` | ✅ | 断言 `store_no` 为空且 `store_dropped=1`：不核对或改回 10403 都会红 |
| F4 | `FeedbackDiagnosticsTest#keeps_only_four_fields_and_redacts_long_numbers`、`#strips_query_string`；`BizFeedbackFlowTest#app_shaped_report_is_accepted`（落库后无 `body` 字段、路径 `P…2001`） | ✅ | 断言与 App `redactPath` 测试同一用例 |
| F5 | `BizFeedbackFlowTest#mine_lists_only_my_merchant_feedback_newest_first_capped_at_50` | ✅ | 实现前红 |
| F6 | `MessageServiceTicketSourceTest#consumer_tickets_exclude_merchant_feedback_with_same_user_no`、`#merchant_feedback_excludes_consumer_tickets`（同一 `user_no` 造两边数据） | ✅ | 实现前红；去掉 `source` 条件即串单 |
| F7 | `OpsTicketFlowTest#merchant_feedback_shows_source_category_store_and_version`、`#diagnostics_endpoint_returns_sanitized_entries` | ✅ | 实现前红 |
| F8 | `OpsTicketFlowTest#reply_to_merchant_feedback_pushes_staff_message`、`#reply_message_is_listed_whichever_store_is_selected` | ✅ | 实现前红 |
| F9 | `BizFeedbackFlowTest#sixth_submission_within_window_is_rate_limited`（断言 10429 且只落 5 条） | ✅ | 实现前红 |
| F10 | `StoreScopedDailyRoutesFlowTest#feedbackRoutesWorkWithoutStoreSelection`（新模型门店账号，带与不带 `X-Store-No`） | ✅ | 实现前红（`ACCOUNT` 未登记 → 70006） |
| V1 | `AppVersionHttpTest#anonymous_and_expired_token_both_read_version`（含 `Cache-Control: no-store`） | ✅ | 实现前红 |
| V2 | `AppVersionHttpTest#platform_is_case_insensitive`、`#unknown_platform_is_bad_request` | ✅ | 实现前红 |
| V3 | `AppVersionHttpTest#unconfigured_platform_returns_empty_fields` | ✅ | 实现前红 |
| V4 | `AppVersionsTest#compares_like_app_compare_versions`（照抄 App `v22_units_test.dart` 四条）、`#build_suffix_in_parentheses_is_ignored`、`#non_versions_are_rejected`；`AppVersionHttpTest#upgrade_required_when_build_below_min` | ✅ | 括号用例：按 `.` 硬拆会把 `1.4.2 (…)` 当 1.4.0 → 红 |
| V5 | `AppVersionHttpTest#notes_follow_accept_language_then_fall_back_to_zh_hant`、`#zh_hant_tw_and_zh_tw_match_zh_hant` | ✅ | 实现前红 |
| V6 | `OpsAppVersionFlowTest#list_shows_both_platforms`、`#min_above_latest_is_rejected`、`#store_url_must_be_app_store_or_play`、`#zh_hant_notes_are_required`、`#stale_revision_conflicts`、`#update_writes_audit_log`、`#tech_ops_reads_but_cannot_update`（P5） | ✅ | 实现前红 |
| V7 | `ClientVersionGateFilterTest#old_build_write_gets_10426`、`#missing_headers_pass`、`#reads_are_never_blocked`、`#exempt_writes_are_never_blocked`（参数化七条）、`#set_password_is_blocked`；`ClientVersionGateFlowTest#old_build_write_is_rejected_end_to_end`（挂在链上、回报不拦） | ✅ | 流程用例实现前红（过滤器不挂即红） |
| P7 | `BizFeedbackFlowTest#diagnostics_older_than_90_days_are_purged_ticket_kept` | ✅ | — |
| 闸门 | `BizEndpointPermTest`、`BackendI18nParityTest`、`ArchitectureTest` 及 `arch` 包全部守卫；`gen-ui-catalog.py --check`、`check-generated-docs.mjs`、`vue-tsc`（b-app / c-app）、ops-web `tsc` 与 vitest | ✅ 见下 | |

跑法与输出（`cd backend && mvn -q -o -pl shop-app -am test -Dtest=…`）：

```
BizFeedbackFlowTest              Tests run: 10, Failures: 0, Errors: 0
MessageServiceTicketSourceTest   Tests run: 2,  Failures: 0, Errors: 0
OpsTicketFlowTest                Tests run: 9,  Failures: 0, Errors: 0   （原 5 条 + 新 4 条）
AppVersionHttpTest               Tests run: 7,  Failures: 0, Errors: 0
OpsAppVersionFlowTest            Tests run: 7,  Failures: 0, Errors: 0
ClientVersionGateFilterTest      Tests run: 11, Failures: 0, Errors: 0
ClientVersionGateFlowTest        Tests run: 1,  Failures: 0, Errors: 0
StoreScopedDailyRoutesFlowTest   Tests run: 14, Failures: 0, Errors: 0   （原 13 条 + 新 1 条）
FeedbackDiagnosticsTest          Tests run: 6,  Failures: 0, Errors: 0   （shop-core）
AppVersionsTest                  Tests run: 3,  Failures: 0, Errors: 0   （shop-core）
BackendI18nParityTest            Tests run: 6,  Failures: 0, Errors: 0
```

**基线对比**：同一组命令（`ai.neargo.shop.arch.*Test` + 消息相关场景测试）在基线 `54eb737ab` 的导出副本上跑：231 跑 / 19 失败 / 1 错误；
本分支同组 292 跑 / 19 失败 / 1 错误。失败的**方法清单逐条相同**，失败内容逐字相同（`SchemaParityTest` 两侧集合各多一张 `sys_app_version`，属同一条存量失败）：
`ArchitectureTest` 5 条、`BizEndpointPermTest.everyBizEndpointHasADecision`（未决清单与基线逐条相同，不含 `/biz/feedback*`）、
`BizKeyConventionTest`、`BizRoleSeedTest`、`InventoryDisabledContextTest`、`MapperSmokeTest`、`MpEndpointAuthTest`（`/mp/checkout/**`）、
`OrphanModuleTest`、`PayAuditTrailTest`、`PayReverseDependencyBudgetTest` 3 条、`ScheduledJobConventionTest` 2 条、`SchemaParityTest`、`TableHasProducerTest` —— 均为基线即有。
实现过程中新增过一条 `ArchitectureTest.domainsMustNotTouchWebRuntime`（版本接口用了 `HttpServletResponse` 设响应头），已改为 `ResponseEntity` + `CacheControl.noStore()` 消除。

## §6 对账二 · 设计 → 实现

`git show --stat 936885ea1`：92 个文件，+4256 / −112。按 §2 模块设计逐行核对：

| §2 列的文件 | 实际 |
|---|---|
| `portal/biz/BizFeedbackController.java` | ✅ 新增 |
| `message/FeedbackDiagnostics.java` | ✅ 新增 |
| `message/MessageService.java`、`impl/MessageServiceImpl.java` | ✅ 改 |
| `message/entity/MsgTicket.java` | ✅ 改 |
| `message/dto/MessageVOs.java` | ✅ 改 |
| `message/api/ops/OpsTicketController.java` | ✅ 改 |
| `platform/AppVersionService.java` + `impl/AppVersionServiceImpl.java` + `entity/SysAppVersion.java` + mapper | ✅ 新增；mapper 进现有 `PlatformMappers.AppVersionMapper` |
| `platform/AppVersions.java` | ✅ 新增 |
| `platform/api/common/AppVersionController.java` | ✅ 新增 |
| `platform/api/ops/OpsAppVersionController.java` | ✅ 新增 |
| `config/ClientVersionGateFilter.java` | ✅ 新增 |
| `config/SecurityConfig.java` | ✅ 改（只动 `merchantChain`，`publicChain` 未改） |
| `CommerceRoutePolicy.java` | ✅ 改 |
| `ErrorCode.java` | ✅ 改 |
| `messages*.properties` 三语 | ✅ 改 |
| `V6xx__…sql` | ✅ 为 `V638__merchant_feedback_and_app_version.sql`，另加 `V639__app_version_perm_and_menu.sql`（见偏差 1） |
| `schema-test.sql` | ✅ 由 `backend/scripts/gen-test-schema.py` 重放迁移生成 |
| `BizEndpointPermTest.java` | ✅ 改（`FOUNDATION_READS` 两行） |
| `ops-web/lib/types/message.ts`、`app/messages/page.tsx`、`app/messages/copy.ts` | ✅ 改 |
| `ops-web/app/system/` 下 App 版本 tab | ✅ `app-version-tab.tsx` 新增、`page.tsx`/`copy.ts` 改 |
| `ops-web/lib/nav.ts` + `ui-catalog.json` | ✅ 改、重新生成 |

| 差异 | 说明 |
|---|---|
| TDD 里没有、实际改了的文件 | `platform/dto/AppVersionVOs.java`（VO 与请求体）；`message/job/TicketDiagnosticsPurgeJob.java`（已拍板 P7 的 90 天清理）；`Perms.java`、`V639`、`ops-web/lib/{perm-map,permissions,point-codes}.ts`、`lib/i18n/nav-labels.ts`、`nav.test.ts`、`scripts/perm-endpoint-map.mjs`（P5 新权限码与新菜单的登记面）；`ops-web/app/messages/ticket-diagnostics.tsx`（诊断抽屉）；`ops-web/lib/api/{contracts,https,mocks}/…`、`lib/api/query.ts`、`lib/mock/db/…`（运营端接口与替身）；`packages/shared/src/types/message.ts`、`b-app/src/api/{endpoints,contract,http,requests,mocks/message}.ts`、`b-app/scripts/gen-openapi.mjs`（契约类型与端点登记）；`docs/api/响应格式规范.md`（10426 进错误码分段表）；`check-generated-docs` 列出的生成物（openapi 三份、API 清单与详情、权限 / 菜单 / 词条 / 表清单等）；测试类 `MessageServiceTicketSourceTest`、`AppVersionHttpTest`、`OpsAppVersionFlowTest`、`ClientVersionGateFilterTest`、`ClientVersionGateFlowTest`、`BizFeedbackFlowTest`、`FeedbackDiagnosticsTest`、`AppVersionsTest`，以及 `OpsTicketFlowTest`、`StoreScopedDailyRoutesFlowTest` 的追加用例 |
| TDD 列了、实际没动的文件 | 无 |

### 偏差说明

1. **两条迁移**：DDL 放 V638；P5 选 B 之后多了「新权限码 + 菜单点」的种子，单列 V639（照 V355 的可重入写法）。166 上 V638 要 root 先执行（DDL），V639 只是 INSERT。
2. **权限码**：§2 原写「不新增」，按已拍板 P5 = B 新增 `system:app-version:update`，只授超管（`Perms.ROLE_PERMS` 不配任何角色，超管靠通配）；列为高危码（`CRITICAL_PERMS`）。看策略与菜单仍是 `system:param:read`（超管、技术运维）。
3. **`platform` 缺失**：接口一表格写「必填」，但 F2 只把描述、分类、`appVersion` 列为拒单条件、并要求附带资料一律不拒单 —— 实现按 F2：缺失与不认得一样归 `OTHER`，不回 10400。
4. **版本策略的校验细则**（§2 只写了 `min ≤ latest` 与商店地址）：版本号必须能取出 `数字(.数字){0,2}` 前缀；配了 `minSupported` 就必须配 `latest`；`zh-Hant` 说明在 `latest` 非空时必填（P6 = A）；`notes` 里出现四种语言以外的键 → 10400；商店地址按 URI 的 scheme=https、host 整串等于 `apps.apple.com` / `play.google.com` 判（`apps.apple.com.evil…` 过不去）。
5. **`/ops/tickets` 多一个可选 `source` 查询参数**：运营端配套写了「来源筛选」，接口表没列；只加可选参数，旧调用不变。
6. **过滤器位置**：挂在 `BizContextFilter` 之前（认证之后、选店之前），旧包的写请求不会先撞 70006；通过 `ObjectProvider` 取 `AppVersionService`，只装了安全链的切片测试里没有它就不挂（与同一方法里另两个依赖的处理一致）。闸门缓存 60 秒，运营端保存时清本机缓存；多实例部署时其它实例最多晚 60 秒。
7. **版本接口的 `Cache-Control`**：用 `ResponseEntity.cacheControl(noStore())` 而不是 `HttpServletResponse`（`ArchitectureTest.domainsMustNotTouchWebRuntime` 禁止领域包依赖 Servlet）；信封照包。
8. **F10 的锁定用例**放在 `StoreScopedDailyRoutesFlowTest#feedbackRoutesWorkWithoutStoreSelection`，不在 §5 原写的 `CommerceAccessSecurityFlowTest`（后者是 MySQL 测试，本地默认跑不了）。
9. **H2 测试库不建 `idx_ticket_source_user`**：生成器对普通索引一律丢弃；MySQL 迁移里有。
10. **频率闸是本机内存实现**（现有 `InMemoryRateLimiter`）：多实例时「10 分钟 5 条」按实例计。正常使用碰不到，接受。
11. **§1「顺带发现」里的 `/biz/push-token*`**：基线 `54eb737ab` 里两条已在 `CommerceRoutePolicy.ACCOUNT`，无需再改。
12. **跟进提交（文档与守卫）**：`packages/shared` 守卫要求新具名枚举登记、取值域列登记、不在 interface 里内联字面量联合 —— 于是
    `enum-registry.ts` 登记了 `FeedbackCategory`/`FeedbackStatus`/`AppPlatform`/`AppVersionPlatform`/`AppVersionLang`/`TicketSource`，
    `scripts/check-enum-fields.mjs` 登记了 `notify_ticket.category/source/client_platform` 与 `sys_app_version.platform`，
    平台字段抽成具名类型；分类码在 `MsgTicket` 里落成常量（`CATEGORY_*`、`CATEGORY_PATTERN`，请求体 `@Pattern` 引用它），
    后端取值表才认得 BUG / IDEA。`packages/shared` 守卫与基线相比没有新增失败（`ops-data-scope` G1/G3 在基线 `54eb737ab` 上已红，
    来自门店配送的 `StoreFulfillmentServiceImpl#inheritedChannelOf`，与本 TDD 无关）。


## 评审修订说明（2026-11-08，按 ai-shop-bapp `docs/implementation/TDD评审-商户App.md`）

技术问题核对属实的，已直接改进 §0 / §1 / §2 / §5；与后端事实不符的没有照改，理由写在这里。

| 评审条目 | 改了什么 | 为什么 |
|---|---|---|
| 「最需要先定的」8 · 诊断上限 200 | F2、接口一：最多保留 300 条，多了留最后 300 条，不拒单 | App 内存里最多 300 条（`system_status.dart` 第 66 行），200 上限会让正常回报整单 10400 |
| 8 · `errorCode` ≤ 32 | 上限改 64、超长截断；`error_code` 列改 `VARCHAR(64)` | App 的 `business_CONTEXT_REFRESH_REQUIRED` 是 33 字 |
| 8 · `at` 不带时区 | 接口一新增三行处理表：带时区归一 UTC、不带时区原样存并标「设备本地时间」、解析不了置 null；都不拒单 | App 用 `DateTime.now().toIso8601String()`，不带时区；按 UTC 硬解析会偏 8 小时 |
| 8 · 门店号不属于本账号整单拒收 | F3：丢弃门店号、照常落库，新列 `store_dropped` 记一笔；错误码表删掉 10403 | 最常见的回报正是「权限刚被收回」，整单拒收等于这类问题永远报不上来 |
| 9 · 旧版拦截没排除回报与登录 | 接口四：不拦清单扩到七个（加 `POST /biz/feedback` 与四个登录类写请求），并写明登录类**有意不拦**与理由；`POST /biz/auth/password` 照拦 | Y11 上的「回报问题」会被拦；登录类请求在 `merchantChain` 上，原稿没说拦不拦 |
| 三 · `platform` 桌面值会 10400 | `macOS` / `windows` 等归为 `OTHER`，不拒单；列取值加 `OTHER` | 回报不该因附带资料失败 |
| 三 · 10429 读不到 `data.retryAfterSeconds` | 删掉「`data` 带 `retryAfterSeconds`」，10429 不带秒数 | **评审建议的两条路都没照走**：后端业务异常响应没有 `data`（`GlobalExceptionHandler#onBiz` 返回 `ApiResult<Void>`）；10429 的文案无占位符且多处共用，往 `msg` 里塞秒数会改到别的接口。App 已把 10429 当 `rateLimited`，显示通用提示即可 |
| 三 · `upgradeRequired` 不读也不影响 | 接口三改为「建议 App 以 `upgradeRequired` 为准」；版本比较改为取 `数字(.数字){0,2}` 前缀，不再照抄 App | 核对时发现 App `compareVersions` 把 `1.4.2 (20261108.1)` 当成 `1.4.0`（`APP_VERSION` 注释示例就是这种格式），照抄会连 bug 一起抄 |
| 三 · F8 账号级站内信在门店消息列表里不一定看得到 | **没有照改**，F8 补写依据与一条测试 | 与后端事实不符：`GET /biz/message` 只按收件人查（`MessageServiceImpl#rows` 第 424–429 行），`X-Store-No` 只用来算售后待办标记，选哪家店都看得到 |
| 三 · F1 未登录怎么办 | F1、接口一写明未登录 401、App 隐藏入口；是否开匿名回报列入已拍板 P1 | 匿名回报牵涉产品取舍（防刷、谁来收回复） |
| 三 · 最少字数 | 接口一写明服务端下限 1、App 下限 4 是端上约束 | 免得以后 OpenAPI 与 App 对不上 |
| 三 · `Accept-Language` 匹配 | 接口三补写 BCP 47 归并规则 | 原稿只写「按 `Accept-Language`」，`zh-Hant-TW`、`zh-TW` 会不会命中没说 |
| 关联需求里的交接文档路径 | 改为 ai-shop-bapp `docs/implementation/功能矩阵与API交接文档.md` | 原路径在本仓库不存在 |
| 原 D1（路径）、D2（`CLOSED`）、D5（幂等键） | 移出待决，见下方「已定」 | 评审已接受 `/common/app/version` 与「已结案」显示；幂等键是技术取舍 |

**已定（不再待决）**

| # | 结论 | 依据 |
|---|---|---|
| 原 D1 | 版本接口用 `/common/app/version` | 零部署改动（§2 接口三）；评审已把「路径改为 `/common/app/version`」列入 App 要改的 |
| 原 D2 | 服务端照实返回 `CLOSED`，App 显示「已结案」（中性色） | 评审已列入 App 要改的 |
| 原 D5 | 提交不要幂等键 | 回报不是钱，重复一条客服可合并；网络重送的重复靠 F9 兜住 |

---

## 已拍板

> 2026-11-08 用户按建议全部接受：下列各条一律按「建议」执行；「选项」与「后果」保留作决策记录。

| # | 问题 | 选项与各自后果 | 建议 |
|---|---|---|---|
| P1 | 未登录时（登录、注册页报错）能不能回报问题 | **A 不开匿名**：App 未登录时隐藏「回报这个错误」；登录页的问题只能登录后或走客服渠道报。**B 开匿名**：要另一个 `/common/feedback` 接口、按 IP / 设备限流防刷，回报没有账号，客服回复无处可送，「我的回报」也查不到 | **A**。首期登录问题量小，匿名回报的防刷与回复送达都是新问题；真要做再单开 |
| P2 | 商户看到回复后想追问怎么办（原 D3） | **A 单轮**：与 C 端一致，App 在回复下方引导「再提交一条新回报」，文案产品定。**B 多轮**：要等工单设计 §6.1 的 `msg_ticket_message` 落地，两端一起改 | **A**，引导文案建议「还有问题？再回报一次」 |
| P3 | 回报频率阈值（原 D4） | **A 10 分钟 5 条**，写常量：正常使用碰不到，刷单会被挡。**B 更松或可配**：要挪到配置项（契约多一项配置） | **A**，要运营可调再挪 |
| P4 | 分类码 `BUG / PAYMENT / IDEA / OTHER` 是否就此冻结；以后要加「列印」「收款机」怎么办 | **A 冻结四个**：以后加分类要服务端与 App 同发；旧版 App 读到不认识的码按「其他」显示（App 要先按这个兜底写）。**B 现在就加**：要产品现在给全清单 | **A**，并请 App 现在就把未知分类显示为「其他」，以后加码不用强更 |
| P5 | 改「最低支持版本」用现有 `system:param:update`（TECH_OPS 就有）还是单独一个码（原 D6） | **A 共用**：零改动，但所有技术运维都能一键让全部旧包停写。**B 单独 `system:app-version:update`**：只给超管与发版负责人；契约多一个权限码与种子迁移 | **B**：抬高最低版本影响面比一般参数大 |
| P6 | 更新说明要几种语言（原 D8） | **A `zh-Hant` 必填、其它可空**（回落 `zh-Hant`）。**B 四语必填**：发版前要等翻译 | **A** |
| P7 | 回报诊断资料保留多久（原 D9） | **A 90 天后清空 `diagnostics`、保留工单**：要另起清理任务。**B 永久保留**：零开发，但请求路径（已脱敏）长期留存 | **A** |

## 待确认（部署 / 发版侧，不需要拍板）

- **V7 的上线顺序**（原 D7）：服务端先上（不带头一律放行，零风险）；App **首个上架版本就带版本头并全局认 10426**，否则那一版永远拦不住。请 App 给出该版发版时间。
- **166 预发布的 nginx** 是否把 `/shop-api/common/**` 转到后端（原 D10，配置不在本仓库）：这条不通，版本检查在预发布环境就测不了。生产 `www.hxmall.top.conf` 已放行 `/common`。

---

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-08 | 起草（草稿，未拍板） |
| 2026-11-08 | 按商户 App 评审（TDD评审-商户App.md）修订：见上方「评审修订说明」；状态仍为草稿 |
| 2026-11-08 | 用户按「已拍板」各条建议全部接受，状态改为已确认 |
| 2026-11-08 | 实现（分支 `codex/p0-feedback-version`，提交 936885ea1）：§5 全部跑绿、基线对比无新增失败，§6 偏差 11 条；状态改为已实现 |

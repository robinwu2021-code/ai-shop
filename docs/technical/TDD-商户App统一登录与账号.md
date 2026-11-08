# TDD-商户App统一登录与账号

状态：**草稿**（待产品与 App 确认「待决」各条后改「已确认」）
档位：**2**（跨端：后端 + Flutter 商户 App；新增账号范围端点与两张表；部分推翻 2026-08-15「登录页不让人先选身份」的拍板；注销不可逆）。**需要另开 ADR**（见 §3 末尾，本文不写）
关联需求：本仓库 `docs/交接-商户App功能矩阵与API.md`（复制自 ai-shop-bapp `docs/implementation/功能矩阵与API交接文档.md`，2026-11-08）§3 通用约定、第 1 模块「账号与登录」、第 5 模块 5-05 / 5-06 / 5-14、附录 A 末行、附录 B `/biz/auth/identities` 行。本仓库 `docs/requirements/` 没有对应 PRD（见待决 D1）。
关联设计：[TDD-账户登录与选店校验](./TDD-账户登录与选店校验.md)（第二步「登录语义收口」）· [TDD-B端邮箱验证码注册登录](./TDD-B端邮箱验证码注册登录.md) · [ADR-051](./ADR/ADR-051-B端账号允许邮箱作为独立登录身份.md) · [TDD-测试身份白名单登录](./TDD-测试身份白名单登录.md) · [注册登录-方案梳理](./reference/注册登录-方案梳理.md)
创建：2026-11-08 · 最后更新：2026-11-08

---

## L1 定位

商户 App 已按 mock 做完「统一登录」（一个人可能既是自己店的店主、又是别家店的员工，登录后列出身份让他选）、
「更换登录手机」「绑定电子信箱」「注销账号」四块界面，正式包里都被拦着，因为后端没有接口。
本文给出这四块的**后端最终建议契约**和实现计划：能复用的全部复用（发码、验码、白名单、会话签发、C 端注销），
新增的只有身份列举与进入、账号凭证变更、商户注销前置检查三件事。

## L2 结构

![商户 App 统一登录主链路](./diagrams/商户App统一登录-主链路.svg)

图上最要紧的是中间那张票据表：登录前**先验证、后进入**拆成两步，两步之间要记住「这个人已经证明过哪个手机号 / 邮箱」，
否则第二步只能再要一次验证码（验证码是一次性的，`OtpStore.verifyAndConsume` 验完即删）。
右上角的 `/biz/auth/login` 原样保留：旧版 App 和 uni-app B 端还在用它，它的「店主优先、不让选」语义不变。

| 部分 | 现状（均可在代码里找到） | 本 TDD |
|---|---|---|
| 登录 | `POST /biz/auth/login`：店主优先；名下无主体且是手机验证码时改签员工会话（`BizAuthController#login`） | 保留不改 |
| 身份列举 / 进入 / 切换 | 后端没有 | 新增 `/biz/auth/identities`、`/identities/enter`、`/identities/mine` |
| 账号凭证读 | `GET /biz/merchant/profile` 的 `phone`、`account`（有手机给手机，否则给邮箱）；**没有单独的邮箱字段** | 新增 `GET /biz/auth/account` |
| 换手机 | 后端没有 B 端接口；C 端有 `POST /mp/user/phone/bind`（只绑，不换） | 新增 `POST /biz/auth/phone/change` |
| 绑邮箱 | 后端没有 | 新增 `POST /biz/auth/email/bind` |
| 注销 | 后端没有 B 端接口；C 端有 `POST /mp/user/deregister`（只查买家在途单） | 新增 `GET /biz/auth/deregister/precheck`、`POST /biz/auth/deregister` |

---

## §0 对账一 · 需求 → 设计

需求原文来自交接文档（以下简称「交接」）。「落点」里的类名，带「新增」的是计划，其余是现有代码。

| AC | 需求（Given / When / Then） | 出处 | 落点 |
|---|---|---|---|
| AC1 | Given 合法手机号 / 邮箱验证码或密码 When `POST /biz/auth/identities` Then 返回 `ticket` 与 `identities[]`，**不建账号、不签会话**；验证码被消费 | 交接 1-05 | 新增 `BizIdentityController#list` → 新增 `LoginIdentityService#verifyAndList`（验码复用 `AuthServiceImpl#verifyOtp`、`EmailOtpService#verify`、`loginByPassword` 的校验部分） |
| AC2 | Given 这个人名下有主体 Then 列出一个 `OWNER` 身份（主体名、门店数） | 交接 1-05 | `mch_account` 按 `user_no` 查在职成员行（同 `MerchantMemberships.active(userNo, USR)` 口径） |
| AC3 | Given 已验证的是手机号，且该号是若干主体的在职员工 Then 每个主体列一个 `STAFF-{mchAccountNo}` 身份 | 交接 1-05、1-08 | `mch_account` 按 `login_phone` 查 ACTIVE 行（同 `MerchantStaffServiceImpl#findActiveStaff` 口径，但取全部而非 `limit 1`） |
| AC4 | Given 已验证的是邮箱 Then **不列员工身份** | ADR-051 决策 3 | 同上，邮箱分支跳过 |
| AC5 | Given 零个身份 Then `identities=[]`，App 进 E09c；`identityNo=NEW_OWNER` 进入时，有账号用旧账号、没有才建（首次建号须 `agreed=true`） | 交接 1-05、1-06 | 新增 `LoginIdentityService#enter`；建号复用 `AuthServiceImpl#findOrCreate`（经新增的「凭已验证凭证登录」入口） |
| AC6 | Given 有效票据 + 票据里的 `identityNo` When `POST /biz/auth/identities/enter` Then 返回与 `/biz/auth/login` 同形的 `{token, merchant}`；店主签 USR 会话、员工签该行的 MCH 会话；票据作废 | 交接 1-06 | `TokenStore.issue` + `LoginUser.merchantByUser` / `LoginUser.merchant`；`BizMerchantController#profileOf`；新表 `usr_login_ticket` |
| AC7 | Given 票据过期 / 已用 / 不存在 Then 拒绝，App 回登录页 | 交接 1-06 | 新错误码 `LOGIN_TICKET_INVALID` |
| AC8 | Given 已登录 When `GET /biz/auth/identities/mine` Then 返回当前这个人的全部身份 + 换身份票据；用它进入新身份成功后，**来源会话作废** | 交接 1-07 | 新增 `BizIdentityController#mine`；票据记来源会话哈希，`enter` 成功后 `TokenStore.revoke` |
| AC9 | 登录限制照旧：10450 / 10451 / 10429 / 10454 / 10455 / 10456 / 10457 / 60001 的语义与 `/biz/auth/login` 一致 | 交接 1-10 – 1-12 | 复用 `OtpSendGuard`、`OtpStore` 失败闸、`PWD_TRY_RULE`、`RISK_BLOCKED` 判定 |
| AC10 | 测试身份白名单在新入口同样生效（统一码 / 统一密码） | 交接 §2、TDD-测试身份白名单登录 | 复用 `TestIdentityService#acceptsCode`（已接在 `verifyOtp` / `EmailOtpServiceImpl#verify` / `loginByPassword` 里） |
| AC11 | Given 已登录 When `GET /biz/auth/account` Then 返回本人的 `phone`、`email`、`hasPassword` 与三项能力开关 | 交接 5-02、5-05、5-06（「我的 › 个人资料」要分别显示手机与邮箱） | 新增 `BizAccountController#account`；`usr_identity` 读 PHONE / EMAIL；`AuthService#hasPassword` |
| AC12 | Given 账号有手机号 When 换手机，提交旧手机验证码 + 新手机 + 新手机验证码 Then 新号成为登录号，旧号不能再登录；门店「店主登录手机」随之改为新号 | 交接 5-05、App 文案 `mePhoneWarn` | 新增 `AccountCredentialService#changePhone`：`usr_identity`、`usr_account.phone`、店主 `mch_account.login_phone`（`MerchantStaffPortImpl#ownerPhone` 读的就是它） |
| AC13 | 新手机已属于别的账号 → 10409，不合并；新手机就是当前号 → 10458 | 同上；`UserServiceImpl#attachPhone` 既有口径 | 同上 |
| AC14 | 换手机 / 绑邮箱成功后，这个人在 MERCHANT、CONSUMER 两个池的其它会话全部作废，响应带新 `token` | 安全基线（凭证变更后旧会话不可继续用） | `TokenStores.of(realm).revokeUser`（`UserServiceImpl#deregister` 已同时踢两池） |
| AC15 | Given 已登录 When 绑邮箱（邮箱 + 该邮箱验证码）Then 账号多一条 EMAIL 凭证，之后可用邮箱登录；已有邮箱时为换绑 | 交接 5-06、App 文案 `meEmailNote` | 新增 `AccountCredentialService#bindEmail` |
| AC16 | 新邮箱已属于别的账号 → 10409，不合并 | ADR-051 决策 2 | 同上 |
| AC17 | Given 店主 When 打开注销页 Then 服务器逐项给出未满足的前置条件（在途订单与售后、未停业门店、待拨款、未到期付费方案） | 交接 5-14、App 文案 `delOrders` / `delStores` / `delFunds` / `delPlan` | 新增 `GET /biz/auth/deregister/precheck` → 新增 `MerchantDeregisterPrecheck` |
| AC18 | Given 前置条件全部满足 + 当前登录标识的验证码 When 注销 Then 账号匿名化、凭证全部解绑、两池会话作废；门店、主体与交易记录保留 | 交接 5-14、`delWarn` | 新增 `POST /biz/auth/deregister`：复用 `UserServiceImpl#deregister` 的匿名化与解绑主体；另把本人的 `mch_account` 行置 DISABLED |
| AC19 | Given 前置条件未满足 When 直接调注销 Then 拒绝并带同一份 blockers | 交接 5-14 | 新错误码 `DEREGISTER_BLOCKED` |
| AC20 | 员工会话（MCH）不能调换手机、绑邮箱、注销 | 交接 `delStaffNote`；`BizAuthController#personalUser` 既有口径 | `personalUser()` → 10403 |
| AC21 | 以上账号范围接口不带门店头也能用；在新模型门店账号上不被 70006 `CONTACT_ADMIN` 拦 | 交接 §3「账号范围的接口不带」、TDD-账户登录与选店校验 | `CommerceRoutePolicy` ACCOUNT 通道登记（见 §2「路由登记」） |
| AC22 | 换手机、绑邮箱、注销逐笔留痕（谁、何时、改了什么，号码只存脱敏） | 支持与纠纷需要；仓库既有「写操作要审计」惯例（`StaffAuditLogger`） | 新表 `usr_account_change_log` |

**孤立项**

- 没落点的 AC：无。
- 挂不上 AC 的设计：
  - `GET /biz/auth/account`（AC11）是本文**新提出**的，App 的建议契约里没有。理由：`/biz/merchant/profile` 只有 `phone` 与合一的 `account`，App 无法分开显示手机与邮箱，也无从判断「换手机」在台湾能不能用（见待决 D4）。如果 App 不想多一条接口，退路是把 `email` 加进 `/biz/merchant/profile`。
  - `usr_account_change_log`（AC22）不是交接里的需求，是设计自加的留痕；如果产品认为 `LoginAuditor` 日志够用可删。
- 交接里有、本文**不做**的（不算孤立，列出免得被当成漏了）：
  - 1-09 冷启动、1-13 会话结束、1-14 权限收回、1-15 登出：后端已有，契约不变。
  - 交接 1 模块「10450 / 10454 的等待时间从 `msg` 里抽数字，建议另给 `retryAfterSeconds`」：属于错误响应结构变更，影响所有发码与登录接口，单独开 0/1 档处理，建议见 D9。
  - 5-03 改用户名的路由登记问题见 §1「顺带发现」，不在本 TDD 的接口范围内。
  - 交接 5 模块提到的「头像」：交接说没有接口，但功能矩阵 5-01 – 5-14 没有头像行，本文不处理。

---

## §1 现状与影响面

### 相关现有模块（每条都能在代码里找到）

| 模块 | 路径 | 与本文的关系 |
|---|---|---|
| B 端认证端点 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizAuthController.java` | `login`、`otp/send`、`email-otp/send`、`password`（GET/POST）、`logout`、`staff-login`（`@Deprecated`）。`personalUser()` 只放行 `realm=MERCHANT && subjectKind=USR` |
| 登录建户 | `backend/shop-core/src/main/java/ai/neargo/shop/user/service/impl/AuthServiceImpl.java` | `login` → `resolveCredentials` / `findOrCreate` / `loginByPassword`；`verifyOtp`（白名单统一码先认）；`hasPassword`；`accountMarket`；`loginIdentifier`；`setMockSmsPrefixes`（`shop.sms.mock-prefixes`） |
| 邮箱验证码 | `backend/shop-core/src/main/java/ai/neargo/shop/user/service/EmailOtpService.java`、`impl/EmailOtpServiceImpl.java` | `send` / `verify` / `normalize` / `key`；`Delivery(expiresInSeconds, resendAfterSeconds)` |
| 验证码存储 | `backend/shop-base/src/main/java/ai/neargo/shop/common/OtpStore.java` | 内存；手机 5 分钟、邮箱 10 分钟；失败闸 15 分钟 5 次；`verifyAndConsume` 验完即删 |
| 发码限流 | `backend/shop-base-auth/src/main/java/ai/neargo/shop/common/ratelimit/OtpSendGuard.java` | 间隔 / 日上限 / 发起人 |
| 测试身份白名单 | `backend/shop-core/src/main/java/ai/neargo/shop/user/service/TestIdentityService.java`、`impl/TestIdentityServiceImpl.java` | 已接入手机验码、邮箱验码、统一密码三处 |
| 员工会话 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/MerchantStaffService.java`、`impl/MerchantStaffServiceImpl.java` | `issueStaffSession(phone)` 只取 `is_primary` 优先的**一条**；`findActiveStaff` 是私有方法 |
| 成员关系 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/entity/MchAccount.java`、`service/impl/MerchantMemberships.java`、`service/impl/BizIdentityResolverImpl.java` | 一行 = 一个人在一个主体里的身份：`user_no`（可空）、`login_phone`、`is_owner`、`is_primary`、`status` |
| 店主登录手机 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/port/MerchantStaffPortImpl.java#ownerPhone` | 读店主那行 `mch_account.login_phone`，订单短信与 `/biz/notify/setting` 的 `ownerPhone` 都靠它 |
| C 端绑手机 | `backend/shop-core/src/main/java/ai/neargo/shop/user/service/impl/UserServiceImpl.java#bindPhone` / `attachPhone` | 冲突报 10409、同号幂等；**只加不换** |
| C 端注销 | 同上 `#deregister`；`MpUserController` `POST /mp/user/deregister` | 只查买家在途单（`OpenOrderPort#hasOpenOrders(userNo)` → 70028）；物理删 `usr_identity`；`PersonService#deregister`；匿名化 `usr_account`；两池 `revokeUser` |
| 人档 | `backend/shop-core/src/main/java/ai/neargo/shop/user/service/PersonService.java` | `bindOnLogin` / `bindPhone` / `deregister` / `merge`；**没有「换号」方法** |
| 路由准入 | `backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java` | `ACCOUNT` 集合（写死在 starter 里，已含全部 `/biz/auth/*`）；`isSessionEntry` 集合；未登记 → `DENY` |
| 路由准入（主机侧） | `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopCommerceEndContextAdapter.java`、`ShopStoreMemberRoutes.java` | `ShopStoreMemberRoutes` 只产 BOOTSTRAP（门店成员）通道，**不适合账号范围接口**；适配器对 `/biz/auth/password` 额外做 `checkFoundationSession` |
| 匿名放行 | `backend/shop-app/src/main/java/ai/neargo/shop/config/SecurityConfig.java`（`permitAll` 四条）、`backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizContextFilter.java`（跳过作用域解析的五条） | 新匿名端点要在这两处和 `CommerceRoutePolicy` 两处同时登记 |

### 可直接复用

- 发码：`/biz/auth/otp/send`、`/biz/auth/email-otp/send` 原样复用。换手机、绑邮箱、注销要的验证码都用它们发，**不新增发码端点**（理由见 §3 选型 C）。
- 验码与白名单：`AuthServiceImpl#verifyOtp`（私有，需提出一个包内可调的入口）、`EmailOtpService#verify`、`TestIdentityService#acceptsCode`。
- 密码校验：`AuthServiceImpl#loginByPassword` 的限流与比对（同上，需拆出「只校验不签会话」的部分）。
- 会话：`TokenStore.issue`、`TokenStores.of(Realm).revokeUser`、`LoginUser.merchantByUser` / `LoginUser.merchant`、`LoginAuditor`。
- 登录响应：`BizAuthController.MerchantLoginResp(token, merchant)` 与 `BizMerchantController#profileOf`。
- 注销主体：`UserServiceImpl#deregister` 的「删凭证 + 人档解绑 + 匿名化 + 两池踢人」整段。
- 冲突口径：`UserServiceImpl#attachPhone`（属于别人 10409，同号幂等）。
- 在 `docs/technical/reference/` 搜过「身份 / 注销 / 绑定」：[注册登录-方案梳理](./reference/注册登录-方案梳理.md) 是现状投影，其「待办 5 注销账号」已由 C 端 `/mp/user/deregister` 落地；没有另一份 B 端身份切换的实现。

### 会被改到的已在跑的功能

| 功能 | 怎么受影响 |
|---|---|
| `AuthServiceImpl` 登录 | 把「验码 / 验密码」与「找人建户签会话」拆开成两个可单独调用的步骤；`login` 本身行为不变（回归测试兜） |
| `MerchantStaffService` | 新增「列出某手机号的全部在职员工行」「按 `mchAccountNo` 签会话」两个方法；`issueStaffSession(phone)` 不改 |
| `UserServiceImpl#deregister` | 拆出与入口无关的核心（删凭证、人档、匿名化、踢人），C 端入口行为不变 |
| C 端注销（小程序） | **建议**同时加上「是店主则拒绝」的前置检查（见待决 D7）——今天一个店主从小程序注销，`usr_identity` 被删、`mch_account` 店主行原样留着，门店成了无人能登录的店 |
| 店主订单短信、`/biz/notify/setting` 的 `ownerPhone` | 换手机后跟着变（AC12），这是 App 文案已经承诺的行为 |
| C 端（小程序 / App）登录 | B 端店主的 `usr_account` 就是 C 端账号（`BizAuthController#login` 走的是同一个 `AuthService#login`）：在商户 App 换手机，**C 端登录号也一起换**；在商户 App 注销，C 端账号也一起注销 |
| `CommerceRoutePolicy` | `ACCOUNT` 与 `isSessionEntry` 两个集合加行；兜底 DENY 不变 |

### 明确不受影响

- `/biz/auth/login`、`/biz/auth/staff-login` 的请求与响应：一字不改。uni-app B 端（`b-app/`）不用跟着改。
- 运营端登录与第二因子、`/ops/test-identities/**`。
- `/mp/user/phone/bind`、`/mp/user/login` 等 C 端登录接口（注销入口见待决 D7）。
- 门店范围接口与 `ShopStoreMemberRoutes`：本文新接口全部是账号范围，不登记那里。

### 顺带发现（不在本文范围，记一笔）

- `POST /biz/merchant/display-name`（交接 5-03，标「已有」）**不在** `CommerceRoutePolicy.ACCOUNT`，也不在 `ShopStoreMemberRoutes`。按 `ShopCommerceEndContextAdapter#select` 的代码，账号名下有新模型门店时它会落到 DENY → 70006。**这是读代码推断，没有在 166 上验证。** 若属实，修法是把它加进 ACCOUNT（0/1 档，可与本文实施一起做）。
- 员工会话调 `/biz/auth/password` 会被 `personalUser()` 拒成 10403（只认 USR），而交接 5-04 写的角色是「全部」。员工能不能设密码要另定，不在本文范围。

---

## §2 方案

### 契约变更总表

- **端点**：新增 8 条（下文逐条）；`/biz/auth/login` 等既有端点不改。
- **库表 / 迁移**：新增 `usr_login_ticket`、`usr_account_change_log`，一个迁移 **V6xx 待定**（当前分支最高是 V635；共享工作区里多会话并行，实施当天再取号）。
- **权限码**：无新增。账号范围接口按会话主体判（USR / MCH），不挂 `biz:*`；进 `BizEndpointPermTest` 的免码清单。
- **i18n**：后端新错误码 3 个（三语 `messages*.properties` 各加一行，`BackendI18nParityTest` 盯着）；App 侧新增文案由 App 自行处理。
- **配置项**：新增 `shop.auth.login-ticket.ttl`（缺省 `PT5M`）。复用 `shop.sms.mock-prefixes` 判「换手机能不能真发短信」。

### 端点契约（最终建议）

返回包、鉴权头、语言头照交接 §3。下表「会话」一列：匿名 = 不带 token；USR = 店主 / 未开店的个人账号会话；MCH = 员工会话。

#### 1. `POST /biz/auth/identities` —— 验证并列出身份

| 项 | 内容 |
|---|---|
| 会话 | 匿名 |
| 请求 | 与 `/biz/auth/login` 相同：`{grantType: PHONE_OTP\|EMAIL_OTP\|PASSWORD, principal, credential, agreed?, marketCode?}` |
| 响应 | `{ticket, expiresInSeconds, identities: [{identityNo, type: OWNER\|STAFF, merchantNo, merchantName, role, roleCodes[], storeCount}], canCreateOwner}` |
| 规则 | ① 只验证：验证码被消费，**不建 `usr_account`、不写 `usr_identity`、不签会话**。② `OWNER`：按已验证凭证找到的账号，其 `user_no` 在 `mch_account` 有在职行时出一条，`identityNo` 固定为 `OWNER`；`merchantName` 取默认主体（`is_primary`）的名称，`storeCount` 为名下全部主体的门店数。③ `STAFF`：只在 `PHONE_OTP` 或 principal 为手机号的 `PASSWORD` 下列，按 `login_phone` 取全部 ACTIVE 且 `is_owner=false` 的行，排除 `user_no` 等于本人的行（那些已含在 OWNER 会话里），`identityNo = "STAFF-" + mchAccountNo`。④ `role` 为按 `Accept-Language` 本地化的展示名（店主 / 店长 / 收银员…），`roleCodes` 给机器读。⑤ `canCreateOwner`：没有 OWNER 身份时为 true。⑥ 排序：OWNER 在前，STAFF 按 `is_primary` 倒序、`id` 正序（与 `findActiveStaff` 同一确定顺序）。 |
| 错误 | 10400（参数缺失、格式错）、10455 码错或过期、10454 锁定、10456 / 10457 密码错 / 未设、10429 密码试太多、60001 账号被拦截（账号存在且 BANNED 时在这一步就拒） |
| 与 App 建议的差异 | 多了 `expiresInSeconds`、`merchantNo`、`roleCodes`、`canCreateOwner`；`role` 改为服务端本地化（App mock 里是写死的中文）。全部是**加字段**，App 现有解析不受影响。理由：`identityNo` 必须稳定（App 本机记「上次进入的身份」排第一，交接 1-06），所以用 `OWNER` / `STAFF-{mchAccountNo}` 而不是每次随机号——这与 App mock 的 `OWNER`、`STAFF-MOCK-S1` 恰好同形 |

#### 2. `POST /biz/auth/identities/enter` —— 进入身份

| 项 | 内容 |
|---|---|
| 会话 | 匿名（凭 `ticket`） |
| 请求 | `{ticket, identityNo}`；`identityNo` 取 `identities[].identityNo`，或 `NEW_OWNER`（仅当票据的 `canCreateOwner=true`） |
| 响应 | 与 `/biz/auth/login` 相同：`{token, merchant{…}}` |
| 规则 | ① 票据一次性：成功进入即标记已用（条件更新 `consumed_at IS NULL`，并发两次只成功一次）。② 进入时**重新校验**身份仍有效（员工可能刚被停用）：失效回 10404，App 重新列身份。③ `OWNER` / `NEW_OWNER` → USR 会话（`LoginUser.merchantByUser`）；`NEW_OWNER` 且账号不存在时此刻才建号（走 `findOrCreate`，首次建号要 `agreed=true`，`marketCode` 只在建号时生效，与 `/biz/auth/login` 同口径）。④ `STAFF-*` → 该 `mch_account_no` 的 MCH 会话。⑤ 票据来源是 `/identities/mine` 时，成功后吊销来源会话。⑥ 成功 / 失败都记 `LoginAuditor`（MERCHANT 池）。 |
| 错误 | `LOGIN_TICKET_INVALID`（新，票据不存在 / 过期 / 已用）、10404（身份不在票里或已失效）、10400（新建号未勾协议）、60001 |
| 与 App 建议的差异 | 无 |

#### 3. `GET /biz/auth/identities/mine` —— 已登录者的身份与换身份票据

| 项 | 内容 |
|---|---|
| 会话 | USR 或 MCH |
| 请求 | 无；不带门店头 |
| 响应 | 同 1 的响应，另加 `currentIdentityNo` |
| 规则 | 从当前会话推出「这个人」：USR → `user_no`，及其 `usr_identity` 里的 PHONE（用于列员工身份）；MCH → `mch_account.login_phone`，再按 `usr_identity(PHONE)` 找个人账号（**只找不建**）。邮箱账号没有手机号就只有 OWNER（ADR-051 决策 3）。票据记下当前会话的哈希。 |
| 错误 | 10401 / 10402 |
| 与 App 建议的差异 | 多 `currentIdentityNo`（App 现在靠本机记当前身份，服务器给更可靠） |

#### 4. `GET /biz/auth/account` —— 本人登录凭证（本文新增）

| 项 | 内容 |
|---|---|
| 会话 | USR（MCH 回 10403） |
| 响应 | `{phone, email, hasPassword, phoneChangeAvailable, emailBindAvailable, deregisterAvailable}` |
| 规则 | `phone` / `email` 读 `usr_identity` 的 PHONE / EMAIL（E.164 / 归一化地址，本人看自己的完整值）；`hasPassword` 同 `AuthService#hasPassword`；`phoneChangeAvailable`：账号经营地区（`AuthService#accountMarket`）的国际区号被 `shop.sms.mock-prefixes` 覆盖时为 false（短信只存不发，验证码送不到）；`emailBindAvailable`：邮件通道可用（`EmailOtpServiceImpl` 发码前的同一判定）；`deregisterAvailable`：恒 true（注销页自己读 precheck）。 |
| 与 App 的差异 | App 没有这条；它现在读 `/biz/merchant/profile.phone`。退路见 §0 孤立项 |

#### 5. `POST /biz/auth/phone/change` —— 更换 / 绑定登录手机

| 项 | 内容 |
|---|---|
| 会话 | USR（MCH 回 10403） |
| 请求 | `{currentCode?, newPhone, newCode}`。`currentCode`：账号有手机号时为发到**当前手机号**的验证码（App P05 第一步「驗證舊手機」）；账号没有手机号（邮箱注册）时为发到**当前邮箱**的验证码。App 先用 `/biz/auth/otp/send` 或 `/biz/auth/email-otp/send` 给这两个地址发码 |
| 响应 | `{token, account{…同 4}}` —— 新会话；旧会话已全部作废 |
| 规则 | ① 先验 `currentCode`（目标由服务器按账号决定，不信请求里的号），再验 `newCode`。② 新号 = 当前号 → 10458；新号在 `usr_identity` 属于别人 → 10409，不合并（与 `attachPhone` 同口径；此时新码已验过，不构成「这个号注册过没有」的探测口子）。③ 同一事务内：改 `usr_identity` PHONE 行的值（无则插入，`verified_at=now`）、改 `usr_account.phone`（过渡期双写，同 `attachPhone`）、改本人店主行 `mch_account.login_phone`（`user_no=本人 且 is_owner=true`）。④ 员工行与人档的处理见待决 D5、D6。⑤ 事务提交后：两池 `revokeUser(userNo)`，再签一个新 USR 会话返回。⑥ 写 `usr_account_change_log(PHONE_CHANGE, 旧号脱敏, 新号脱敏)`。⑦ `phoneChangeAvailable=false` 时直接 10505（不让用户走完两步才发现收不到码）。 |
| 错误 | 10400、10455 / 10454（任一验证码）、10458、10409、10505、10403 |
| 与 App 建议的差异 | App 没给契约（交接说「连草案都没有」）。本契约与 App P05 页面的两步表单（旧号验证码 → 新号 + 新号验证码）一一对应；一次提交而不是两次，理由：两次提交就要在服务器上保存「旧号已验过」的中间态，又是一张票据 |

#### 6. `POST /biz/auth/email/bind` —— 绑定 / 换绑电子信箱

| 项 | 内容 |
|---|---|
| 会话 | USR（MCH 回 10403） |
| 请求 | `{email, code, currentCode?}`。`code` 为发到新邮箱的验证码。`currentCode` 只在**账号已有邮箱（换绑）**时必填，为发到当前邮箱的验证码（见待决 D3） |
| 响应 | `{token, account{…}}`，同 5 |
| 规则 | ① 归一化同 `EmailOtpService.normalize`。② 新邮箱 = 当前邮箱 → `EMAIL_ALREADY_BOUND`（新）；属于别人 → 10409，不合并（ADR-051 决策 2）。③ 插入或改写 `usr_identity(EMAIL)`。④ 两池踢人、签新会话、写变更记录，同 5。 |
| 错误 | 10400、10455 / 10454、`EMAIL_ALREADY_BOUND`、10409、10505、10403 |
| 与 App 建议的差异 | App P06 只有「邮箱 + 验证码」两栏，**首次绑定**完全一致；换绑多一个 `currentCode`，App 要加一步（D3） |

#### 7. `GET /biz/auth/deregister/precheck` —— 注销前置检查

| 项 | 内容 |
|---|---|
| 会话 | USR（MCH 回 10403） |
| 响应 | `{allowed, verifyBy: PHONE\|EMAIL, verifyTarget（脱敏）, blockers: [{code, count, storeNos[]}], staffMemberships}` |
| blockers.code | `OPEN_ORDERS`（名下门店有进行中的订单或售后）· `ACTIVE_STORES`（名下有未停业门店）· `PENDING_PAYOUT`（收款账户有待拨款项）· `PAID_PLAN_ACTIVE`（门店方案有未到期的付费期间）· `BUYER_OPEN_ORDERS`（本人作为买家有在途单，即 C 端已有的 70028 那一条）——与 App 的四条文案一一对应，多出的最后一条来自 C 端既有闸 |
| 规则 | `staffMemberships`：本人在别家主体的在职员工行数，注销时会一并停用（待决 D8）。`verifyBy`：有手机号且短信可发 → PHONE，否则 EMAIL。 |
| 后端现状 | `OpenOrderPort#hasOpenOrders(userNo)` 只按**买家**判；按商户 / 门店判「在途单、待拨款、未到期方案」的只读口子**后端没有**，要在订单、资金、方案三个域各补一个只读查询，经 `spi` 端口给 `MerchantDeregisterPrecheck` 用（端口名实施时定）。门店「转让」**后端没有**（没找到任何转让接口），App 文案里的「或轉讓給其他店主」一期只能是「停业」，见 D7 |

#### 8. `POST /biz/auth/deregister` —— 注销

| 项 | 内容 |
|---|---|
| 会话 | USR（MCH 回 10403） |
| 请求 | `{code, confirm: true}`；`code` 为发到 `verifyTarget` 的验证码 |
| 响应 | 无（`data: null`）；成功后当前会话已失效，App 清本机回登录页 |
| 规则 | ① 先验码，再在同一事务里**重跑一次** precheck（防「检查完到提交之间又来了一单」）。② 通过后：本人全部 `mch_account` 行（`user_no=本人`，以及待决 D8 决定的按 `login_phone` 匹配的行）置 DISABLED；调用从 `UserServiceImpl#deregister` 拆出的核心（删 `usr_identity`、`PersonService#deregister`、匿名化、状态 `DEREGISTERED`、两池 `revokeUser`）。③ 主体、门店、订单、结算原样保留（`delWarn`：「門店、主體與交易紀錄依法保留」）。④ 写变更记录（DEREGISTER）。 |
| 错误 | 10400（`confirm` 不是 true）、10455 / 10454、`DEREGISTER_BLOCKED`（新，`data.blockers` 同 precheck）、10403 |

### 新错误码（号段实施时取 `ErrorCode` 当天的空号）

| 名称 | 建议号段 | 含义 | App 表现 |
|---|---|---|---|
| `LOGIN_TICKET_INVALID` | 104xx（认证段） | 票据不存在、过期或已用 | 回登录页重新验证 |
| `EMAIL_ALREADY_BOUND` | 104xx（紧挨 10458） | 新邮箱就是当前邮箱 | 「已經綁定這個信箱」 |
| `DEREGISTER_BLOCKED` | 700xx（紧挨 70028） | 注销前置条件未满足，`data.blockers` 列出 | 刷新注销页清单 |

其余全部复用：10400 / 10403 / 10404 / 10409 / 10429 / 10450 / 10451 / 10454 / 10455 / 10456 / 10457 / 10458 / 10505 / 60001。

### 路由登记（每个新端点要动哪几处）

`ShopStoreMemberRoutes` 只产生 BOOTSTRAP 通道（「令牌 + 该门店成员」），要求带 `X-Store-No`，**账号范围接口不能登记在那里**。全部登记到 `CommerceRoutePolicy` 的 ACCOUNT 通道。

| 端点 | `SecurityConfig` permitAll | `BizContextFilter` 跳过作用域 | `CommerceRoutePolicy.ACCOUNT` | `isSessionEntry` | `ShopCommerceEndContextAdapter` 走 `checkFoundationSession` |
|---|---|---|---|---|---|
| `POST /biz/auth/identities` | ✅ | ✅ | ✅ | ✅ | — |
| `POST /biz/auth/identities/enter` | ✅ | ✅ | ✅ | ✅ | — |
| `GET /biz/auth/identities/mine` | — | — | ✅ | — | ✅ |
| `GET /biz/auth/account` | — | — | ✅ | — | ✅ |
| `POST /biz/auth/phone/change` | — | — | ✅ | — | ✅ |
| `POST /biz/auth/email/bind` | — | — | ✅ | — | ✅ |
| `GET /biz/auth/deregister/precheck` | — | — | ✅ | — | ✅ |
| `POST /biz/auth/deregister` | — | — | ✅ | — | ✅ |

最后一列：与现有 `/biz/auth/password` 同一处理——对账号级写操作先用 `AccountSessionVerifier#withFresh` 核一次会话主体，防「会话行已被换掉但内存里还是旧身份」。

`ACCOUNT` 写死在可移植的 starter 里，而 [TDD-账户登录与选店校验](./TDD-账户登录与选店校验.md) §6 说过 starter 不应知道主机有哪些接口。本文**沿用现状**（`/biz/auth/*` 本来就全在那个集合里），不借机重构；如果评审要求移到主机侧，做法是仿 `HostRoute` 给 ACCOUNT 也开一个主机登记表，那是独立的 0 档重构。

### 库表（计划，迁移号 V6xx 待定）

**`usr_login_ticket`** —— 先验证后进入之间的一次性票据

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT PK | |
| `ticket_hash` | CHAR(64) UNIQUE | 票据明文的 SHA-256；**明文不落库**（库被读走也换不出会话） |
| `purpose` | VARCHAR(16) | `LOGIN` / `SWITCH` |
| `user_no` | VARCHAR(64) NULL | 已有账号时填；新号（还没建户）为空 |
| `verified_type` | VARCHAR(16) | `PHONE` / `EMAIL` / `ACCOUNT`（SWITCH 时） |
| `verified_value` | VARCHAR(191) NULL | 已验证的手机号 / 归一化邮箱。NEW_OWNER 建号要用它；与 `usr_identity.identity_value` 同等级别的数据 |
| `agreed` | TINYINT | 请求时是否勾选协议 |
| `market_code` | VARCHAR(16) NULL | 请求带来的注册地区 |
| `origin_token_hash` | CHAR(64) NULL | SWITCH 的来源会话，进入成功后吊销 |
| `identity_nos` | VARCHAR(1024) | 签发时列出的身份号，`enter` 只认这里面的（外加 `NEW_OWNER`） |
| `expires_at` / `consumed_at` | DATETIME | 5 分钟；进入成功写 `consumed_at` |
| 公共列 | | `tenant_no`、`created_at`、`updated_at`、`version`、`deleted`，同 `usr_identity` |

清理：过期行由现有定时任务框架每日删除，或懒删（读到过期即删）——实施时看哪种不新增定时任务。

**`usr_account_change_log`** —— 账号凭证变更与注销留痕

| 列 | 说明 |
|---|---|
| `user_no` | 谁 |
| `action` | `PHONE_CHANGE` / `EMAIL_BIND` / `EMAIL_CHANGE` / `DEREGISTER` |
| `before_masked` / `after_masked` | 只存脱敏值（`Masks#phone`、邮箱打码），**不存明文** |
| `actor_kind` | `SELF`（一期只有本人自助；留着给以后客服代办） |
| `client_ip` | 请求来源 IP，取法沿用 [TDD-安全基线P0-运营登录与真实IP](./TDD-安全基线P0-运营登录与真实IP.md)（`LoginAuditor` 现在不记 IP） |
| `created_at` 等公共列 | |

迁移只建表、不带种子。H2 测试库 `backend/shop-app/src/test/resources/schema-test.sql` 要同步加两张表——`SchemaDriftTest` 比对两边的表与列，只改一边会红。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/shop-app/src/main/resources/db/migration/V6xx__login_ticket_and_account_change_log.sql` | 两张表 |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/user/entity/UsrLoginTicket.java`、`UsrAccountChangeLog.java` | 实体；Mapper 加进 `UserMappers` |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/user/service/LoginTicketService.java`、`impl/LoginTicketServiceImpl.java` | 签发（随机 32 字节 base64url）、按哈希取、条件更新核销 |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/user/service/AuthService.java`、`impl/AuthServiceImpl.java` | 新增 `VerifiedPrincipal verify(LoginCommand)`（只验不建，返回已验证的类型、值、已有 userNo）与 `UsrAccount enterVerified(VerifiedPrincipal, agreed, marketCode)`（复用 `findOrCreate`）；`login` 改为两者串联，行为不变 |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/user/service/AccountCredentialService.java`、`impl/AccountCredentialServiceImpl.java` | `changePhone`、`bindEmail`；冲突口径与 `attachPhone` 共用一个私有方法（不各写一份） |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/user/service/UserService.java`、`impl/UserServiceImpl.java` | 把 `deregister()` 的核心拆成 `deregisterCore(userNo)`；C 端入口照旧调它 |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/user/service/PersonService.java` 及实现 | 新增「换号」方法（待决 D6 定口径后实现） |
| 修改 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/MerchantStaffService.java`、`impl/MerchantStaffServiceImpl.java` | 新增 `List<StaffIdentity> activeStaffIdentities(phone)`、`Optional<String> issueStaffSessionFor(mchAccountNo)`、`void updateOwnerLoginPhone(userNo, newPhone)`、`int disableAllOf(userNo, phone)`；`findActiveStaff` 与新列表方法共用同一查询条件 |
| 新增 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/LoginIdentityService.java`、`impl/LoginIdentityServiceImpl.java` | 列身份、进入身份、换身份（组合 AuthService、LoginTicketService、MerchantStaffService、BizIdentityResolver）。放 shop-merchant 而不是 shop-core：要读 `mch_account`，而 shop-core 的 `AuthServiceImpl` 读店员走的是 `StaffSessionPort`，不直接依赖商户模块（实施时以 `ArchitectureTest` 判定为准） |
| 新增 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/MerchantDeregisterPrecheck.java` 及实现 | 汇总 blockers；订单 / 资金 / 方案三个只读口子经 `spi` 端口取 |
| 新增 | `backend/shop-base/src/main/java/ai/neargo/shop/spi/...` 下三个只读端口（名称实施时定）及各域实现 | 「商户有无进行中订单与售后」「有无待拨款」「门店方案有无未到期付费期」 |
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizIdentityController.java` | 端点 1–3 |
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizAccountController.java` | 端点 4–8。不塞进 `BizAuthController`：那个类管「登录与会话」，这几条管「已登录的人改自己的凭证与注销」，分开后各自的测试与注入都更窄（路径前缀同为 `/biz/auth`，不影响 `known-fat-controllers.txt` 的判据） |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/config/SecurityConfig.java` | permitAll 加 2 条 |
| 修改 | `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizContextFilter.java` | 跳过集合加 2 条 |
| 修改 | `backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java` | ACCOUNT 加 8 条，`isSessionEntry` 加 2 条 |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopCommerceEndContextAdapter.java` | `checkFoundationSession` 的路径判断加 6 条（与 `/biz/auth/password` 同列） |
| 修改 | `backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java`、`backend/shop-app/src/main/resources/messages.properties`、`messages_en.properties`、`messages_ar.properties` | 3 个新码 |
| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java` | 免码清单加 8 条（理由同 `/biz/auth/password`：作用对象是调用者本人） |
| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/contract/ApiContractTest.java` | 游客端点清单加 2 条 |
| 修改 | `backend/shop-app/src/test/resources/schema-test.sql` | H2 侧同步两张表（`SchemaDriftTest` 本身不改） |
| 新增 | 测试类见 §5：`BizIdentityFlowTest`、`BizAccountFlowTest`、`BizDeregisterFlowTest`、`LoginTicketServiceTest`、`MerchantDeregisterPrecheckTest`、`CommerceRoutePolicyTest`（后者 starter 里目前没有同名测试，新建） | |
| 修改（App 仓库，不在本仓库提交） | ai-shop-bapp `features/v5/contracts.dart` 删 `^/biz/auth/identities`；`features/me` 接 P05 / P06 / P14；错误码映射加 3 个新码 | 冻结后由 App 做 |

### 关键接口

```java
// AuthService（新增两个，login 改为二者串联）
record VerifiedPrincipal(String type /* PHONE|EMAIL */, String value, @Nullable String userNo) {}
VerifiedPrincipal verify(LoginCommand cmd);                       // 验码 / 验密码，不建户；BANNED → 60001
UsrAccount enterVerified(VerifiedPrincipal p, Boolean agreed, String marketCode); // 复用 findOrCreate

// LoginIdentityService
record Identity(String identityNo, String type, String merchantNo, String merchantName,
                String role, List<String> roleCodes, int storeCount) {}
record IdentityChoice(String ticket, long expiresInSeconds, List<Identity> identities,
                      boolean canCreateOwner, @Nullable String currentIdentityNo) {}
IdentityChoice verifyAndList(LoginCommand cmd, Locale locale);
IdentityChoice mine(LoginUser current, String currentToken, Locale locale);
String enter(String ticket, String identityNo);                   // 返回 token；响应体由控制器拼 profileOf

// AccountCredentialService
String changePhone(String userNo, @Nullable String currentCode, String newPhone, String newCode); // 返回新 token
String bindEmail(String userNo, String email, String code, @Nullable String currentCode);          // 返回新 token

// MerchantDeregisterPrecheck
record Blocker(String code, int count, List<String> storeNos) {}
List<Blocker> check(String userNo);
```

---

## §3 选型（2 档）

### A. 先验证后进入，还是一步登录后再换身份

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A1 两步：`identities` 只验证 + 票据，`enter` 才签会话 | 与 App 已做的 E09 / E09b / E09c 完全一致；零身份时不预先建账号 | 要一张票据表；多一次往返 | ✅ 采用 |
| A2 沿用 `/biz/auth/login` 签店主会话，响应里附 `identities`，换身份再调 `/mine` + `enter` | 不加匿名端点 | 员工-only 的人会先被签一个空的个人会话、还会被「登录即注册」建一个 C 端账号——员工本来**不需要** C 端账号（`MerchantStaffService` 类注释） | ❌ |
| A3 登录页先选「老板 / 店员」 | 实现最简单 | 2026-08-15 已否：「他可能选错，选错的表现是进去什么都看不到」 | ❌ |

A1 只在「多个身份」时让人选，零个和一个都不选，所以没有回到 A3 的问题；但它确实改变了「后端按身份判、不让人选」这条拍板的字面，**要用 ADR 记下来**。

### B. 票据存哪

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| B1 新表 `usr_login_ticket` | 多实例安全；一次性靠条件更新；可审计 | 一张新表 | ✅ 采用 |
| B2 放 `OtpStore` 那种内存缓存 | 不加表 | `OtpStore` 是单实例内存（TDD-B端邮箱验证码 §4 已记风险）；会话已在外置（`DbSessionConfig`），票据退回内存就成了新的单点 | ❌ |
| B3 无状态签名票据（HMAC） | 不加表 | 做不到一次性；要吊销只能等过期 | ❌ |

### C. 换手机 / 绑邮箱 / 注销的验证码怎么发

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| C1 复用两个匿名发码端点，App 自己给目标地址发码 | 零新端点；限流、白名单、模拟通道、邮件正文语言全部沿用 | 服务器在提交时才知道码是发给谁的——所以「验哪个地址」必须由服务器按账号决定，不能信请求 | ✅ 采用 |
| C2 新增 `POST /biz/auth/account/otp/send {purpose}`，服务器自己选目标 | 目标不可伪造 | 要复制一套限流与通道判断，或者改 `OtpSendGuard`；收益只是少一个前端判断 | ❌ 一期不做 |

### D. 注销是匿名化还是删除

沿用 C 端已落地的口径（`UserServiceImpl#deregister`）：匿名化 + 物理删凭证，不删交易数据。B 端多做的只是「前置检查更严」与「停用成员行」。不另立一套。

### 需要的 ADR（本文不写）

- **ADR-0xx 商户 App 统一登录：多身份时让用户选**——记录对 2026-08-15「不让人先选」拍板的修订（只在多身份时选）、`/biz/auth/login` 保留为兼容入口、票据模型。
- **ADR-0xx 商户账号注销口径**——不可逆：注销后同一手机号 / 邮箱再来是全新账号；主体与门店不随人注销而注销；店主注销的前置条件。若 D7 选择「C 端入口也拦店主」，一并记在这里。

## §4 风险（2 档）

| 风险 | 影响 | 缓解 |
|---|---|---|
| 台湾没有短信通道（`shop.sms.mock-prefixes` 覆盖 `+886` 时短信只存不发） | 手机号账号无法完成换手机；员工身份（只按手机号认）在台湾只能靠白名单登录 | `phoneChangeAvailable` 让 App 提前隐藏入口；员工问题见 D4，不在本文解决 |
| 换手机后旧号的员工行、人档没跟上 | 换完号「别家店的员工身份没了」，或会员关系挂在旧号上 | D5、D6 先定口径；测试 `phoneChangeMovesOwnerLoginPhone` 钉住店主行 |
| 票据被截获 | 5 分钟内可进入对应身份 | 只存哈希、一次性、5 分钟；与验证码本身同等强度（截获验证码同样能登录） |
| 注销前置检查漏一个域 | 有待拨款的店主注销后无人能收款 | 提交时在事务里重跑 precheck；三个只读端口各有一条「有数据就拦」的测试 |
| 店主从小程序注销绕过 B 端检查（**现状就存在**） | 门店无人能登录 | D7 建议 C 端入口共用 precheck |
| 换身份时来源会话没吊销 | 一人同时持有两个身份的有效令牌 | AC8 测试拿旧令牌再打一次，必须 401 |
| `ACCOUNT` 集合漏登记一条 | 新模型门店账号上 70006（交接已踩过一次） | `CommerceAccessSecurityFlowTest` 加新模型账号逐条打 8 个端点 |

---

## §5 对账三 · 实现 → 需求（测试）

计划的测试方法，**均未编写、未运行**。「跑过」「消融验证」两列实现后填。

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `BizIdentityFlowTest#listingConsumesCodeButCreatesNoAccountOrSession` | | |
| AC2 | `BizIdentityFlowTest#ownerWithStoresListsOneOwnerIdentity` | | |
| AC3 | `BizIdentityFlowTest#phoneThatIsStaffInTwoMerchantsListsTwoStaffIdentities` | | |
| AC4 | `BizIdentityFlowTest#emailLoginNeverListsStaffIdentities` | | |
| AC5 | `BizIdentityFlowTest#newPhoneHasNoIdentityAndNewOwnerCreatesAccountOnlyOnEnter` · `#newOwnerWithoutAgreementIsRejected` | | |
| AC6 | `BizIdentityFlowTest#enterOwnerIssuesUsrSessionShapedLikeLogin` · `#enterStaffIssuesMchSessionForThatRow` | | |
| AC7 | `BizIdentityFlowTest#ticketIsSingleUse` · `#expiredTicketIsRejected` · `#identityNotInTicketIsRejected` · `LoginTicketServiceTest#concurrentConsumeSucceedsOnce` | | |
| AC8 | `BizIdentityFlowTest#switchingIdentityRevokesOriginToken` · `#staffSessionSeesOwnIdentityViaPhone` | | |
| AC9 | `BizIdentityFlowTest#wrongCodeLocksAfterFiveAttemptsLikeLogin` · `#bannedAccountIsBlockedAtListing` | | |
| AC10 | `TestIdentityFlowTest#whitelistedIdentityListsWithUnifiedCode` | | |
| AC11 | `BizAccountFlowTest#accountShowsPhoneEmailAndPassword` · `#phoneChangeUnavailableWhenMarketSmsIsMocked` | | |
| AC12 | `BizAccountFlowTest#phoneChangeRequiresOldAndNewCodes` · `#phoneChangeMovesOwnerLoginPhone` · `#oldPhoneCannotLoginAfterChange` | | |
| AC13 | `BizAccountFlowTest#phoneOfAnotherAccountConflictsWithoutMerge` · `#samePhoneReportsAlreadyBound` | | |
| AC14 | `BizAccountFlowTest#credentialChangeRevokesSessionsInBothPools` | | |
| AC15 | `BizAccountFlowTest#bindEmailThenLoginWithEmailReachesSameAccount` · `#rebindEmailRequiresCurrentEmailCode` | | |
| AC16 | `BizAccountFlowTest#emailOfAnotherAccountConflictsWithoutMerge` | | |
| AC17 | `MerchantDeregisterPrecheckTest#eachBlockerIsReportedWithStores`（四个域各一组数据） | | |
| AC18 | `BizDeregisterFlowTest#ownerWithNoBlockersIsAnonymizedAndStoresAreKept` · `#deregisteredOwnerCannotLoginAndSameEmailGetsFreshAccount` | | |
| AC19 | `BizDeregisterFlowTest#directCallWithBlockersReturnsSameBlockers` | | |
| AC20 | `BizAccountFlowTest#staffSessionCannotChangeCredentialsOrDeregister` | | |
| AC21 | `CommerceAccessSecurityFlowTest#accountRoutesOpenWithoutStoreOnNewModelAccount` · `CommerceRoutePolicyTest#accountScopeRoutesAreAccountLane` | | |
| AC22 | `BizAccountFlowTest#changesAreLoggedMasked` | | |
| 回归 | `BizAuthEmailLoginTest`、`EmailOtpAuthTest`、`TestIdentityFlowTest`、`DeregisterFlowTest`、`PhoneBindFlowTest`、`IdentityUnificationFlowTest`、`StoreScopedDailyRoutesFlowTest`、`ApiContractTest`、`BizEndpointPermTest`、`BackendI18nParityTest`、`SchemaDriftTest`、`ArchitectureTest` | | |

---

## 待决

每条给出建议；带 ★ 的会卡住实现，要在开工前定。

| # | 问题 | 谁定 | 建议 |
|---|---|---|---|
| D1 ★ | 本文按 2 档写，2 档要 PRD。本仓库没有商户 App 的账号 PRD，需求源是 bapp 的交接文档与原型 v10 / v21 / v23 | 产品 | 不另写 PRD：把交接文档第 1、5 模块与本文 §0 的 AC 作为需求基线，产品在本文上签字即可；两份 ADR 照写 |
| D2 ★ | 一个店主名下有多张执照（多个主体）时，列一个 OWNER 还是每个主体一个 | 产品 + App | **列一个**。USR 会话本来就覆盖名下全部主体（`BizIdentityResolverImpl` 按门店反查主体），按主体拆成几条会得到「点哪条进去都一样」的假选择 |
| D3 | 换绑邮箱要不要验证旧邮箱 | 产品 + App | **要**（`currentCode`）。邮箱是登录凭证，只凭会话就能换的话，借来的手机上一分钟就能把账号永久夺走；首次绑定不需要 |
| D4 ★ | 台湾没有短信：换手机实际只能对白名单号生效；员工身份只按手机号认（ADR-051 决策 3），台湾员工同样只能靠白名单登录 | 产品 | 一期 App 按 `phoneChangeAvailable` 隐藏 P05；**员工能用邮箱登录**另开一份 TDD（要给 `mch_account` 加登录邮箱并修订 ADR-051 决策 3），不塞进本文 |
| D5 | 换手机时，别家主体把他按**旧号**加成员工的那些行（`user_no` 为空、`login_phone`=旧号）要不要跟着改 | 产品 | **跟着改**，并在每家主体的员工变更记录里各写一条「员工换了登录手机」，店主看得到。不改的话换完号员工身份全部消失，而他做的只是换号 |
| D6 | 换手机时人档（`usr_person`）怎么跟：新号已有一份**未绑账号**的人档（商家早先录过这个号）时并不并 | 产品 | 人档跟着换号；新号已有未绑账号的人档时走 `PersonService#merge` 并入（留 `usr_person_merge_log`）；新号的人档已绑别的账号时整单 10409 |
| D7 ★ | 店主从小程序 `/mp/user/deregister` 注销今天**不会被拦**，门店会变成无人能登录的店；要不要 C 端入口也走商户前置检查 | 产品 | **要**。两个入口共用 `MerchantDeregisterPrecheck`；C 端对店主回同一个 `DEREGISTER_BLOCKED`。另：App 文案「或轉讓給其他店主」后端没有转让功能，一期把文案改成只说「已停業」 |
| D8 | 注销时，本人在**别家主体**的员工行怎么办 | 产品 | 不拦注销，一并停用并写员工变更记录；precheck 用 `staffMemberships` 告诉他「注销后将退出 n 家店的员工身份」 |
| D9 | 10450 / 10454 的等待时间要不要给结构化字段（交接建议 `retryAfterSeconds`） | 后端 + App | 要，但单独做：在 `data` 里加 `retryAfterSeconds` / `lockedMinutes`，`msg` 不动，App 优先读字段、读不到再从 `msg` 抽数字。它影响全部发码与登录接口，不跟本文绑在一起 |
| D10 | 注销要不要冷静期（`需求矩阵-三端.md` 1.3 写过「注销与冷静期」，C 端落地时没做） | 产品 + 法务 | 一期不设，与 C 端一致：前置条件已经保证没有在途的钱和货；设冷静期要一张待注销表和一个定时任务，等法务明确要求再加 |
| D11 | `/biz/auth/login` 什么时候下线；`staff-login` 是否随统一登录上线删除 | 后端 + App | `login` 至少保留到 uni-app B 端停用；`staff-login` 在 App 正式包切到统一登录后、看访问日志一个版本周期无调用再删 |
| D12 | `/biz/merchant/display-name` 疑似在新模型账号上 70006（§1 顺带发现，读代码推断） | 后端 | 先在 166 上用新模型门店账号实测一次；属实就在本文实施时一并加进 ACCOUNT |

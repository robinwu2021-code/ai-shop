# TDD-B端邮箱验证码注册登录

状态：**已实现**（2026-11-05；本地提交，未推送。实现与设计的偏差见 §6）
关联需求：`ai-shop-bapp` · `docs/requirements/PRD-台湾一期注册开店Plan套餐商城与支付开通-V3.md` §3.1；
本仓 `docs/requirements/B端全链路-流程节点与开发状态-2026-11-04.md`（「邮箱验证码登录为商城后独立追加工作」）
关联决策：[ADR-051 B端账号允许邮箱作为独立登录身份](./ADR/ADR-051-B端账号允许邮箱作为独立登录身份.md)
档位：**2**（跨端：后端 + Flutter；新增端点与登录方式取值；改变「手机号是唯一权威标识」这一既定模型）
创建：2026-11-05 · 最后更新：2026-11-05

---

## L1 定位

台湾一期没有可用的国际短信通道，PRD 规定此时**以邮箱验证码注册／登录为实际入口**。
今天 App 的「邮箱」页签是占位（按钮全灰、写着「待接入」），后端没有任何邮箱认证端点 ——
商户在台湾**只能靠开发桩登录**，上线即断。本设计补上这条入口：
商户输入邮箱 → 收到 6 位码 → 首次即注册、之后即登录，后续进入与手机号完全相同的「确认经营地区」路径。

**这不是从零开始。** `codex/ai-shop-codex` 上的 `stash@{0}`（2026-11-03 切分支前保存，未提交）
已有一版实现与 11 条通过的测试（`EmailOtpService`、`EmailOtpAuthTest`、`SmtpMailPrivacyTest`）。
但那份 stash 把邮箱与「会话外置 / 账号市场 / 登出」三件事混在一起，后三件在统一线上已经用别的方式落地。
本方案**只移植其中的邮箱部分**，并补齐它缺的三块：本地可测、邮件正文语言、Flutter 端。

## L2 结构

![主链路](./diagrams/B端邮箱验证码-主链路.svg)

图上最重要的是右侧灰框：**发码限流、邮件通道、验证码存储、身份表、会话签发全部是现有组件**，
本期只新增一个服务类和一个端点，再给登录加一个取值。红色虚线是本地验证路径 ——
它和生产路径在「桩」与「SMTP」处分叉，生产 profile 下桩直接拒绝，不会出现「假装发出去了」。

| 部分 | 现状 | 本期 |
|---|---|---|
| 发码端点 | 只有 `/biz/auth/otp/send {phone}` | 新增 `/biz/auth/email-otp/send {email, locale}` |
| 登录方式 | `PHONE_OTP` / `PASSWORD`（principal=手机号） | 新增 `EMAIL_OTP`；`PASSWORD` 的 principal 可为邮箱 |
| 身份模型 | `IdentityType` 无 EMAIL；注释写「手机号唯一权威标识」 | 加 `EMAIL`，进 `RESOLVE_ORDER`；注释改为 ADR-051 口径 |
| 账号表 | `usr_account.phone` 可空；`usr_identity uk(type,value)` | **不改表、不加迁移** |
| 验证码存储 | `OtpStore` 内存 5 分钟；核销先 get 再 remove（非原子） | 核销改为 `remove(key, entry)` 原子，同码并发只成功一次 |
| 邮件通道 | `MailPort`：SMTP / 桩，桩**不打印正文** | 桩对 `bizType=OTP` 以 debug 打印验证码（与短信桩同口径） |
| App | 邮箱页签占位 | 接通发码、倒计时、登录；错误按码分别提示 |

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话，可断言） | 落点 |
|---|---|---|
| AC1 | Given 合法邮箱 When 发码 Then 该邮箱收到 6 位码，接口返回 `expiresInSeconds=300`、`resendAfterSeconds=60` | `BizAuthController#sendEmailOtp` → `EmailOtpService#send` → `MailPort.send(..., "OTP", null)` |
| AC2 | Given 60 秒内再次发码 Then 拒绝（10450）且**旧码仍有效**；超日上限 10451 | 复用 `OtpSendGuard.check(key)`，限流在生成新码之前 |
| AC3 | Given 首次邮箱 + 已勾协议 When `EMAIL_OTP` 登录 Then 新建 `usr_account`（phone 为空）与 `usr_identity(EMAIL, 归一化地址)`，返回 B 端会话；未勾协议拒绝 | `AuthServiceImpl#login` 新 `case GRANT_EMAIL_OTP`；凭证解析 `resolveCredentials` 加分支，认人 / 建号沿用 `resolveUserNo` |
| AC4 | Given 同一邮箱大小写/首尾空格不同 When 再登录 Then 认到同一账号，不新建 | `EmailOtpService.normalize`（trim + 全小写，不合并供应商别名） |
| AC5 | 邮箱码不能核销手机码，反之亦然；邮箱登录**不**按手机号匹配员工；**不**自动与同人手机号账号合并 | `OtpStore` 键前缀 `email:`；`BizAuthController#login` 员工分支仍只认 `PHONE_OTP`；ADR-051 |
| AC6 | 输错 → 10455；连错 5 次 → 10454 并作废当前码；过期 → 10455；同码并发两次只成功一次 | 复用 `OtpStore` 失败闸；核销改原子 |
| AC7 | Given 已设登录密码 When `PASSWORD` 登录且 principal 为邮箱 Then 按 EMAIL 身份找人；限流键与手机号分开 | `AuthServiceImpl#loginByPassword` 识别 `@` |
| AC8 | 日志、审计、异常消息中不出现完整邮箱与验证码（生产口径） | `LoginAuditor.maskPrincipal` 遮邮箱；SMTP 失败映射 10505 且不带供应商原文 |
| AC9 | Given `shop.mail.stub=true` 且未显式放行（`shop.mail.otp-stub-allowed` 缺省 false）When 发码 Then 10505，不返回成功 | `EmailOtpServiceImpl#send`（§6 偏差 1：生产与本地 profile 同名，改为显式开关） |
| AC10 | Given 走桩且已放行 When 发码 Then debug 日志可见 `[mail-stub] otp to t***r@example.test = 123456`（地址打码） | `EmailOtpServiceImpl#send`（§6 偏差 2：日志打在服务里而非 `StubMailGateway`） |
| AC11 | 邮件正文语言跟随 App 当前界面语言（繁 / 简），缺省繁体 | 请求体 `locale`；`EmailOtpService` 两套正文模板（见 L4-D3） |
| AC12 | App：邮箱页签可发码、显示倒计时、输入后才可提交；成功后进入「确认经营地区」；10455/10454/10505/10450 各自提示 | Flutter `OnboardingApi#sendEmailOtp`、`login(grant: EMAIL_OTP)`、`StandardLoginPage` 邮箱卡片 |

**孤立项**
- 没落点的 AC：无。
- 挂不上 AC 的设计：`OtpStore` 核销改原子 —— 手机号同样受益，挂在 AC6；不单列。

**不做（Out of Scope）**
- 已有手机号账号**绑定 / 换绑邮箱**、邮箱找回密码、邮箱链接验证、HTML 邮件模板。
- C 端（小程序）邮箱登录。
- 账号合并（同一人先后用手机号与邮箱各注册一次 → 两个账号；见 ADR-051 后果）。
- 选择 / 采购邮件服务商：沿用现有 SMTP 配置（`shop.mail.*`，缺省 smtp.office365.com:587）。

---

## §1 现状与影响面

- **现有模块**
  - `shop-core/.../user/service/impl/AuthServiceImpl.java`：登录总入口，按 `IdentityType.RESOLVE_ORDER` 认人 / 建号。
  - `shop-app/.../portal/biz/BizAuthController.java`：B 端认证端点；登录后吊销 C 端凭证、签 B 端会话；员工分支只在 `PHONE_OTP`。
  - `shop-base/.../common/OtpStore.java`：内存验证码，5 分钟，失败闸 15 分钟 5 次。
  - `shop-base-auth/.../ratelimit/OtpSendGuard.java`：发码三道闸（间隔 / 日上限 / 发起人）。
  - `shop-notify/.../port/SmtpMailGateway.java`、`StubMailGateway.java`：`shop.mail.stub` 切换，缺省走桩。
- **可直接复用**：上面全部；`stash@{0}` 中的 `EmailOtpService`、`EmailOtpAuthTest`、`SmtpMailPrivacyTest`
  （`git show 'stash@{0}^3':<path>` 取未跟踪文件，`git diff 'stash@{0}^1' 'stash@{0}'` 取已跟踪改动）。
- **会被改到的已上线功能**
  - 手机号验证码登录：`OtpStore` 核销逻辑改原子（行为不变，只消除竞态）。
  - 密码登录：principal 增加邮箱分支；手机号分支行为不变。
  - 登录审计：`maskPrincipal` 增加邮箱遮盖。
- **明确不受影响**：C 端 `/mp/user/otp/send`、员工登录 `staff-login`、运营端第二因子（也走 MailPort，但不经 `EmailOtpService`）、
  账号经营市场（统一线由 `shop.merchant.default-market` + 「确认经营地区」页承担，**不移植** stash 里的 `/biz/auth/market` 与 `usr_account.market_code`）。

---

## §2 方案

### 契约变更

- **端点**
  - 新增 `POST /biz/auth/email-otp/send`，游客可调（`SecurityConfig` 与 `CommerceRoutePolicy` 两处放行）。
    请求 `{ "email": string(@Email, ≤191), "locale": "zh-Hant" | "zh-Hans" | null }`；
    响应 `{ "expiresInSeconds": 300, "resendAfterSeconds": 60 }`。
  - `POST /biz/auth/login`：`grantType` 新增取值 `EMAIL_OTP`（principal=邮箱，credential=6 位码）；
    `PASSWORD` 的 principal 允许为邮箱。请求体加 `@Valid`：`grantType`、`principal` 非空，邮箱形态时 `@Email ≤191`。
- **库表 / 迁移**：无。`usr_identity` 新增取值 `identity_type='EMAIL'`（列宽 32、值宽 191 均够）。
- **权限码**：无。
- **i18n**：后端无新增错误码（复用 10450/10451/10454/10455/10505）。邮件正文不进 `messages*.properties`（理由见 L4-D3）。
  Flutter：删除 `emailUnavailable`；新增 `emailOtpPrompt`、`errMailUnavailable`（繁 / 简两份 arb）。
- **配置项**：新增 `shop.mail.otp-stub-allowed`（缺省 false）：邮件走桩时是否允许发登录验证码。只在测试 profile（`application-test.yml`）与本机启动脚本里打开；`shop.mail.stub`、`shop.mail.*` 沿用。（§6 偏差 1）

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/user/service/EmailOtpService.java` | 移植 stash；改：桩判定按 profile（AC9/AC10）、正文按 locale（AC11） |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/user/IdentityType.java` | 加 `EMAIL`，进 `RESOLVE_ORDER`（排在 PHONE 之后）；类注释改为 ADR-051 口径 |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/user/service/AuthService.java` | `GRANT_EMAIL_OTP`、`sendEmailOtp(email, locale)` |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/user/service/impl/AuthServiceImpl.java` | `case GRANT_EMAIL_OTP`；密码登录邮箱分支与独立限流键；日志不带完整地址 |
| 修改 | `backend/shop-base/src/main/java/ai/neargo/shop/common/OtpStore.java` | `TTL` 公开；核销 `cache.remove(key, e)` 原子 |
| 修改 | `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/LoginAuditor.java` | `maskPrincipal` 遮邮箱 |
| 修改 | `backend/shop-notify/src/main/java/ai/neargo/shop/notify/port/StubMailGateway.java` | 覆写 5 参 `send`：`bizType=OTP` 时 debug 打印码（地址打码） |
| 修改 | `backend/shop-notify/src/main/java/ai/neargo/shop/notify/port/SmtpMailGateway.java` | 失败消息不含供应商原文（cause 保留），移植 stash |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizAuthController.java` | 新端点；`LoginReq` 校验 |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/config/SecurityConfig.java` | 放行新端点 |
| 修改 | `backend/commerce/commerce-spring-boot-starter/.../security/CommerceRoutePolicy.java` | 放行新端点（与 `/biz/auth/otp/send` 同组） |
| 新增 | `backend/shop-core/src/test/java/ai/neargo/shop/user/service/EmailOtpAuthTest.java` | 移植 stash 的 11 条中的 9 条 + 本期 AC9/AC11；**不移植** `taiwanRegistrationPersistsMarket…` 与 `businessRegistrationRequiresExplicitMarket…`（依赖不移植的账号市场字段）；拆分 `stubAndDeliveryFailures…`（见 §5 AC9） |
| 新增 | `backend/shop-app/src/test/java/ai/neargo/shop/portal/biz/BizAuthEmailLoginTest.java` | 控制器层：邮箱账号无手机号也能取到 profile；不走员工匹配 |
| 新增 | `backend/shop-notify/src/test/java/ai/neargo/shop/notify/port/StubMailGatewayTest.java` | AC10 |
| 新增 | `backend/shop-notify/src/test/java/ai/neargo/shop/notify/port/SmtpMailPrivacyTest.java` | 移植 stash |
| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/contract/ApiContractTest.java` | 新端点进游客端点清单 |
| 修改 | `backend/shop-core/src/test/java/ai/neargo/shop/user/ProfileAndPasswordTest.java` | 构造参数 |
| 修改 | `docs/api/`（B 端认证 OpenAPI，如统一线已有则改，无则从 stash 的 `merchant-app-auth.openapi.json` 取邮箱两段） | 契约真源 |
| 修改 | Flutter `apps/merchant_app/lib/features/standard_onboarding/onboarding_api.dart` | `sendEmailOtp(email, locale)`；`login` 支持 `EMAIL_OTP` |
| 修改 | Flutter `.../onboarding_controller.dart` | 登录方法带身份类型 |
| 修改 | Flutter `.../onboarding_forms.dart` | 邮箱卡片接通：发码、倒计时、提交启停、错误显示（与手机卡片共用状态机） |
| 修改 | Flutter `.../onboarding_widgets.dart` | 邮箱上下文下 10505 → `errMailUnavailable` |
| 修改 | Flutter `lib/l10n/merchant_zh.arb`、`merchant_zh_Hans.arb` + 生成物 | 见 i18n |
| 新增 | Flutter `test/onboarding_email_login_test.dart` | AC12 |

### 关键接口

```java
// EmailOtpService
public Delivery send(String email, @Nullable String locale);   // 10450/10451/10505
public String verify(String email, String code);               // 返回归一化地址；10455/10454
public static String normalize(String email);                  // trim + toLowerCase(ROOT)
public record Delivery(long expiresInSeconds, int resendAfterSeconds) {}

// AuthService
String GRANT_EMAIL_OTP = "EMAIL_OTP";
EmailOtpService.Delivery sendEmailOtp(String email, @Nullable String locale);
```

```dart
// OnboardingApi
Future<AppResult<EmailOtpDelivery>> sendEmailOtp(String email, {required String locale});
Future<AppResult<OnboardingLogin>> login(String principal, String credential,
    {required bool agreed, required LoginGrant grant}); // phoneOtp | emailOtp | password
```

---

## §3 选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A 移植 stash 的邮箱部分，补本地可测 / 语言 / Flutter | 已有 11 条测试与隐私修正；不改表 | 需要从混合 stash 里挑文件，挑漏会编译失败 | ✅ 采用 |
| B 整体应用 stash | 一步到位 | 会把 `/biz/auth/market`、`usr_account.market_code`、`/biz/auth/session` 带进来，与统一线已落地的「默认市场 + 确认经营地区」冲突 | ❌ |
| C 从零重写 | 不受 stash 约束 | 白扔已验证的并发 / 隐私测试 | ❌ |
| 邮箱进 `usr_account.email` 列 | 查询直观 | 违反本模型「凭证一人多条，不平铺成列」（`IdentityType.PASSWORD` 注释）；要加迁移 | ❌ 用 `usr_identity` |
| 验证码按邮箱哈希做键 | 堆转储不直接暴露地址 | 多一次 SHA-256 | ✅ 沿用 stash |

不可逆部分（邮箱成为与手机号并列的独立身份、不自动合并）见 ADR-051。

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 生产误开桩，发码「成功」但无人收到 | 台湾商户全部无法注册 | AC9：生产 profile + 桩 → 10505；`PlatformChannelCredentials` 健康页已显示 MAIL 是否走桩 |
| `profileOf(personal, null, phone=null)` 等下游假设手机号非空 | 邮箱账号登录后 500 | `EmailOtpAuthTest` 加一条端到端：邮箱首登 → 取 profile → 建店入口可达；`grep result.user().phone()` 逐处过 |
| 同一人手机号、邮箱各注册一次成两个账号 | 门店分散在两个账号 | ADR-051 明确接受；后续「我的 → 账号」做绑定（不在本期） |
| SMTP 被判垃圾邮件 / 送达慢 | 首登失败率高 | 纯文本、短正文；`resendAfterSeconds` 60；上线前做一次真实投递（Gmail / Outlook / Yahoo TW 各一）并记录 |
| 邮箱地址进日志 | 违反日志方案 §7 | AC8 测试断言日志无完整地址；桩打印也打码地址 |
| `OtpStore` 内存存储，多实例部署时发码与核码落到不同实例 | 验证码「总是不对」 | 现状同样适用于手机号；本期不改，记入 L4 |

---

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `EmailOtpAuthTest#sendsThroughExistingMailAndReturnsRealExpiryAndResendMetadata` · `BizAuthEmailLoginTest#emailOnlyAccountReachesProfileWithoutPhone`（HTTP 层断言 300/60） | ✅ | — |
| AC2 | `EmailOtpAuthTest#resendIsRateLimitedBeforeCodeReplacement` | ✅ | — |
| AC3 | `EmailOtpAuthTest#firstRegistrationRequiresAgreementAndExistingLoginDoesNot` · `BizAuthEmailLoginTest#emailOnlyAccountReachesProfileWithoutPhone` | ✅ | 去掉邮箱首登协议校验 → `firstRegistration…` 变红 ✅ |
| AC4 | `EmailOtpAuthTest#repeatedEmailLoginUsesSameRealAccountWithoutPhoneMerge` · `BizAuthEmailLoginTest`（大写地址再登录仍 1 条身份） | ✅ | — |
| AC5 | `EmailOtpAuthTest#emailCodeIsSingleUseAndCannotConsumePhoneCode` · `#repeatedEmailLoginUsesSameRealAccountWithoutPhoneMerge` | ✅ | — |
| AC6 | `EmailOtpAuthTest#expiredEmailCodeCannotAuthenticate` · `#repeatedWrongAttemptsInvalidateEmailCode` · `#sameCodeCanOnlyBeConsumedByOneConcurrentRequest` | ✅ | 核销改回「先 get 再 remove」→ `sameCode…Concurrent…` 变红 ✅ |
| AC7 | `EmailOtpAuthTest#passwordLoginResolvesEmailIdentity` | ✅ | — |
| AC8 | `EmailOtpAuthTest#emailLoginAuditNeverContainsRawAddress` · `SmtpMailPrivacyTest#failureLogMessageDoesNotContainRecipientOrCode` | ✅ | — |
| AC9 | `EmailOtpAuthTest#stubIsRefusedUnlessExplicitlyAllowed` · `#deliveryFailureNeverStoresValidOtp` | ✅ | 去掉放行判断 → `stubIsRefused…` 变红 ✅ |
| AC10 | `EmailOtpAuthTest#allowedStubLogsCodeAtDebugWithMaskedAddress` | ✅ | — |
| AC11 | `EmailOtpAuthTest#bodyFollowsRequestedScriptAndDefaultsToTraditional` | ✅ | 正文不跟随语言 → 该测试变红 ✅ |
| AC12 | Flutter `onboarding_email_login_test.dart`（3 条）· `dev/standard_onboarding_live_policy_test.dart#live dev entry lets the email tab request a code` | ✅ | 邮箱登录发成 `PHONE_OTP` → 主流程用例变红 ✅；去掉邮箱页 10505 文案 → 断网用例变红 ✅；白名单用例先红后绿 ✅ |

**跑过的输出（2026-11-05）**

- 后端定向：`EmailOtpAuthTest` 14/0、`ProfileAndPasswordTest` 9/0、`SmtpMailPrivacyTest` 1/0、`BizAuthEmailLoginTest` 2/0、`ApiContractTest` 5/0、`BizFoundationJourneyFlowTest` 7/0、`OtpTestPhoneFlowTest` 8/0（Tests run / Failures）。
- 后端 shop-core + shop-notify 全量（`-Dmaven.test.failure.ignore`）：945 条，1 失败 2 错误 + 另 3 处失败，共 6 条，**全部在 trade / order / spi.trade**；在不含本改动的 HEAD 上用独立 worktree 跑同 5 个类，**同样 6 条红** —— 既有欠账，与本期无关。
- `ArchitectureTest` 22 条 5 红：第一次跑时其中 1 处是本期的 `EmailOtpService` 具体类（`serviceMustBeInterface`），已拆成接口 + `EmailOtpServiceImpl`；余下违规全部指向 trade / food 既有类，报告中不再出现 EmailOtp。`BackendI18nParityTest` 6/0。
- Flutter：`flutter analyze` 无问题；全量 419 通过（+5 跳过）。
- 本机部署（TW 实例，邮件走桩已放行）HTTP 冒烟：发码 → `{"expiresInSeconds":300,"resendAfterSeconds":60}`；60 秒内重发 → 10450；非邮箱 → 10400；日志取码登录 → `btk_…`；同码重放 → 10455；`usr_account.phone` 为 NULL；`sys_notify_log` 记 `MAIL/OTP/s***r@example.test/SENT`。
- 模拟器 `pos_tablet@5580`（简体界面）：邮箱页签输入 `TW.Owner@Example.test` → 获取验证码（倒计时、提示「请输入发送到此邮箱的验证码」、空码时「验证并继续」置灰）→ 从后端日志取码 → 进入「确认经营地区」；库里新增 `EMAIL / tw.owner@example.test`、phone 为空；退出后用新码再登录 → 同一账号，身份仍 1 条。

## §6 对账二 · 设计 → 实现

后端（`ai-shop-integ`，`codex/commerce-into-ai-shop-codex`）与 Flutter（`ai-shop-bapp-mall`，`codex/mall-offline-checkout`）各一个提交，文件清单见提交本身（`git show --stat`）。与 §2 逐行比：

| 差异 | 说明 |
|---|---|
| 新增 `user/service/impl/EmailOtpServiceImpl.java`；`EmailOtpService` 变为接口 | `ArchitectureTest#serviceMustBeInterface`。归一化与键规则作为接口静态方法，登录、密码登录、测试共用一份 |
| 修改 `shop-base-auth/.../BizContextFilter.java`；`CommerceRoutePolicy` 改了两处（`ACCOUNT` 通道 + 会话入口） | §2 只列了 `SecurityConfig` 与 `CommerceRoutePolicy` 一处。登录入口清单在四个地方各有一份，`BizFoundationJourneyFlowTest` 逐个抓出来的 |
| 修改 `shop-app/src/test/resources/application-test.yml` | 测试世界放行走桩发码（偏差 1） |
| 修改 `BizFoundationJourneyFlowTest.java` | 新端点进「会话入口不查商家目录」断言 |
| §2 列了 `StubMailGateway.java` / `StubMailGatewayTest.java`，实际未动 | 偏差 2 |
| §2 列了 `LoginReq` 加 `@Valid`，实际未加 | 邮箱形态在发码端点已校验；登录时地址不对只会核码失败（10455），不另加一层校验以免改动手机号登录的既有入参行为 |
| Flutter 新增改动 `lib/dev/standard_onboarding_live.dart` + `test/dev/standard_onboarding_live_policy_test.dart` | 开发入口有写请求白名单；不加时页面只显示「当前服务尚未开放」、请求根本没发出（模拟器上实测到） |
| Flutter `onboarding_phone.dart`、`onboarding_widgets.dart` | 邮箱归一化放在身份归一化文件里；`failureText` 钩子让 10505 在邮箱页显示邮件通道文案 |
| Flutter i18n 多了 `useEmailOtpLogin` | 邮箱页「使用密码登录」切回时的文案，原来写的是「使用短信验证码登录」 |

### 偏差说明

1. **AC9 的判据从 profile 改为显式开关 `shop.mail.otp-stub-allowed`。** `FixedOtpGuard` 类注释写明本项目生产与本地都跑 `api,ops`，靠 profile 名分不开。缺省拒绝，只在测试 profile 与本机启动脚本里打开；生产误开桩的症状是发码报 10505，当天可见。
2. **桩日志打在 `EmailOtpServiceImpl`，不改 `StubMailGateway`。** 发送记录装饰器 `NotifyLoggingMailPort` 调桩时走 3 参 `send`，桩拿不到 `bizType`，无法只对验证码打印；而桩对所有正文打印会泄露运营初始密码。
3. **「首登须勾协议」只加在 `EMAIL_OTP`。** stash 同时加在 `PHONE_OTP`，那会改变手机号注册的既有行为（其它端依赖），不在本期范围。
4. **审计遮盖的已知缺口（未改）：** `LoginAuditor.maskPrincipal` 对非 11 位数字（如 `+886912345611`）原样返回。台湾手机号在审计里是明文，属既有问题，建议另行处理。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-05 | 草稿 |
| 2026-11-05 | 方案确认：D1 本期不互绑；D2 邮箱账号可设密码；D3 请求带 locale、繁 / 简两段正文、缺省繁体 |
| 2026-11-05 | 已实现；后端定向测试与 Flutter 全量绿，消融 7 处均变红；`ArchitectureTest` / shop-core 全量的既有红已在不含本改动的 HEAD 上复现；本机 HTTP 与模拟器全链路通过。未推送 |

---

## L4 边界：待决与取舍

**已决（2026-11-05 按建议确认）**

- **D1 邮箱与手机号是否可以互绑？** 已决：本期不做 ——首登用什么就是什么，两个入口各成一号（ADR-051）。
  若要做，至少需要「已登录状态下验证第二身份」与「冲突时不合并、提示改用原账号」两条规则，单开 TDD。
- **D2 邮箱账号能否设登录密码？** 已决：能 ——stash 已实现且有测试（AC7），改动只在密码登录的 principal 识别。
- **D3 邮件正文语言。** 后端 `messages*.properties` 只有 zh-CN / en / ar，没有繁体；App 也不发 `Accept-Language`。
  已决：请求体带 `locale`（App 当前界面语言），`EmailOtpService` 内置繁 / 简两段纯文本正文，缺省繁体；
  不为两句正文新增整套 `messages_zh_TW.properties`（会被 `BackendI18nParityTest` 要求补齐全部 319 条）。

**取舍记录**

- 不移植 stash 的账号市场字段与 `/biz/auth/market`：统一线已由默认市场 + 「确认经营地区」页承担（TDD-B端建店默认经营市场）。
- 不把 `OtpStore` 换成 Redis / 数据库：多实例问题对手机号同样存在，应单独决策；本期只修核销竞态。
- 验证码只存内存、不落库：本地验证从日志取码（AC10），不为测试方便把明文验证码写进数据库。

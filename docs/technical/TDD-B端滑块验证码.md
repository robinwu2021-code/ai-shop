# TDD-B端滑块验证码

> 2026-11-13 公共发码调整：短信/邮件发送改为账户与平台频率触发滑块，密码登录仍按原策略。当前策略、模块迁移及兼容边界见 [公共验证码发送服务](TDD-公共验证码发送服务.md)，下述首版强制发送校验描述为历史实现。


状态：已实现并部署166（统一开发分支，API已启用）
关联需求：用户 2026-11-13 确认的开源引擎自托管 + Flutter 原生交互；`docs/requirements/B端全链路-流程节点与开发状态-2026-11-04.md` N01；实现计划在 bapp checkout `docs/superpowers/plans/2026-11-13-slider-captcha.md`。
创建：2026-11-13 · 最后更新：2026-11-13

## §0 对账一 · 需求 → 设计

| AC | 需求 | 落点 |
|---|---|---|
| AC1 | 服务端发行真实拼图，不返回答案/原图 | Tianai 1.5.6 core；按次生成私有背景；challenge 端点 |
| AC2 | 真实轨迹匹配后才发短期凭据 | verify + BasicCaptchaTrackValidator；精确原图尺寸 |
| AC3 | 挑战/凭据绑定 action+规范化 principal，过期且原子消费一次 | sys_auth_captcha_state；独立 JDBC 事务 |
| AC4 | 开启后密码 login/identities、短信/邮件发码缺头也必须挑战 | 两个现有控制器调用同一服务；10452 data |
| AC5 | OTP 登录/身份进入/已认证业务不受影响，挑战端点匿名可达 | 显式安全公开路径；不改 grant |
| AC6 | 多实例共享状态和发行/校验限流 | JDBC 状态/窗口计数；源 IP + principal 维度 |

孤立项：无。

## §1 现状与影响面

已有 AuthCache 明确只允许数据库权威副本的本地缓存，不能用于验证码；旧 CaptchaService 是运营端文字验证码，保持其功能。复用 ApiResult、10452/i18n、主数据源事务管理器。改动商户密码认证和发码前的检查；默认 rollout 关闭，OTP 凭证认证、identity enter 与登录后接口照旧。

## §2 方案

端点：POST /biz/auth/captcha/challenge `{action,principal}` → `{challengeId,backgroundImage,templateImage,backgroundImageWidth,backgroundImageHeight,templateImageWidth,templateImageHeight,expiresInSeconds}`；POST /biz/auth/captcha/verify `{challengeId,action,principal,track}` → `{captchaToken,expiresInSeconds}`。action 为 LOGIN/OTP_SEND/EMAIL_OTP_SEND。保护请求头 X-Captcha-Token；10452 带 `{captchaRequired:true,action}`。

轨迹使用 bgImageWidth/bgImageHeight、templateImageWidth/templateImageHeight、left/top、startTime/stopTime（毫秒）及 trackList `{x,y,t,type}`；type 为 DOWN/MOVE/UP，x/y 为从按下起的原图像素位移，t 为相对毫秒。模板保留透明竖向位置，top=0；Basic 校验要求至少 10 个采样且耗时至少 300ms。HTTP DTO 用 Jakarta 校验；业务匹配校验尺寸与轨迹最终位移的一致性。

配置：`shop.auth.captcha.enabled` / `SHOP_AUTH_CAPTCHA_ENABLED` 默认 false，部署协调客户端后显式开启；客户端不能决定是否需要验证。挑战 120 秒、凭据 60 秒。发行每 principal 每分钟 10 次、每 IP 每分钟 30 次；校验每 principal 每分钟 20 次、每 IP 每分钟 60 次。只有可信 ClientMeta 的 IP 参与，不读取客户端自报 forwarded 头。

库表：V663 创建 sys_auth_captcha_state（随机键、scope 哈希、服务端校验 JSON、有效期）及 sys_auth_captcha_rate（哈希键、窗口结束、计数）；不存明文 principal 或 proof。锁行读取后删除，在 REQUIRES_NEW 事务提交，再执行匹配/保护操作。错误匹配也消费挑战；错误 scope 消费该凭据。过期行在发行/校验的限流事务清理，避免无限增长。状态保存不缓存到进程。

幂等设计卡：动作身份为随机 challenge/proof，数据库主键和锁行删除是唯一消费点；保护业务失败也不归还凭据；挑战发行本身可重复请求。

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | backend/shop-app/src/main/java/ai/neargo/shop/auth/captcha/ | 引擎、DTO、服务、JDBC 状态 |
| 新增 | backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizCaptchaController.java | 匿名 HTTP |
| 修改 | BizAuthController / BizIdentityController | 密码/发码保护 |
| 修改 | SecurityConfig / BizContextFilter | 公开路径 |
| 修改 | backend/pom.xml / shop-app/pom.xml | 统一管理 core 1.5.6 |
| 新增 | V663 / captcha 测试 | 共享持久状态/限流/并发/HTTP 证据 |
| 修改 | GlobalExceptionHandler + 新 CAPTCHA 异常 | 10452 结构化 data |
| 修改 | application.yml | rollout 配置 |
| 新增 | docs/third-party/tianai-captcha-1.5.6/ | 原样保留上游通知与元数据，不推断法律结论 |

## §5 对账三 · 实现 → 需求

| AC | 测试方法 | 实际证据 |
|---|---|---|
| AC1/2 | BizCaptchaServiceTest#realEngineMatchesAndProofIsCanonicalScopedAndSingleUse、#invalidOffsetDimensionAndChallengeReplayCannotMintProof | H2 实际引擎生成 PNG、正确轨迹成功/错误位移与伪造尺寸拒绝；服务 7/7 |
| AC3 | #missingWrongExpiredAndMismatchedProofsAreRejected、#expiredOrPrincipalMismatchedChallengesAreConsumed、#sharedStoreAtomicallyConsumesProofAcrossInstances | H2 与 MySQL9，正确/错误 scope、超时、重放与独立 store 并发 |
| AC3 | BizCaptchaNativeMysqlTest#protectedBusinessRollbackDoesNotRestoreConsumedProof | 外层回滚后 proof 仍失效 |
| AC4 | BizCaptchaHttpTest#missingAndUntrustedHeadersCannotBypassPasswordLoginOrIdentityDiscovery、#smsAndEmailSendsRequireProofWithoutDeliveringAnyMessage | enabled=true，缺头/伪头 10452 + action；OTPStore 无新增 |
| AC4 | #anonymousChallengeAndVerifyUseRealEngineAndFailedPasswordConsumesProof、#missingPasswordPrincipalIsRejectedByFrameworkValidation | 真引擎 HTTP 发行/校验，错误密码消费 proof；非法输入 10400 |
| AC5 | #otpCredentialLoginAndIdentityEntryRemainOutsideCaptchaPolicy；BizAuthEmailLoginTest 4 条 | OTP/身份进入保持正常错误；既有邮箱完整登录回归 4/4 |
| AC6 | BizCaptchaServiceTest#principalAndIpIssueAndVerifyLimitsAreShared；BizCaptchaNativeMysqlTest#rateLimitHasExactlyOneSharedCounterUnderConcurrency | 10 次发行/20 次校验窗口及过期复位；20 并发严格接受 10 次 |

以下初始验证来自功能源 checkout `codex/food-next01`，不是统一分支发布证明。真实运行（JDK 21.0.12.1；Maven reactor `-f backend/pom.xml -pl shop-app -am test`，带 `-Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false`）：

- `/tmp/captcha-maven-affected.log`：`BizCaptchaServiceTest` 7、`BizCaptchaNativeMysqlTest` 9、`BizCaptchaHttpTest` 4、`ArchitectureTest#topLevelPackagesMustBeRegistered` 1；`Tests run: 21, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
- `/tmp/captcha-http-final.log`：输入框架校验补充后只重跑受影响 HTTP 与既有邮箱登录；HTTP 5、邮箱 4，`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
- `/tmp/captcha-proof-final.log`：强化错误 principal 的凭据消费与两个独立 JDBC store 的并发用例后，只重跑 H2/MySQL 对应 2 方法；`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
- `/tmp/captcha-maven-final.log` 中 `BackendI18nParityTest` 6/6 通过。完整 ArchitectureTest 首次 22 项有 7 失败、1 跳过；其中本次 authcaptcha 顶层包归属已移至既有 auth.captcha 并单独通过。其余 6 个失败方法为 `implsMustLiveInDedicatedPackage`、`svcModulesMustNotDependOnEachOther`、`noFullyQualifiedReferences`、`everyScheduledMethodMustBeLocked`、`serviceMustBeInterface`、`controllersMustNotTouchMappers`，输出均指向已有其他领域的未提交代码，未改动。

MySQL 实测使用隔离临时实例 `/opt/homebrew/opt/mysql@9.7/bin/mysqld` 9.7.4，loopback 3319（测试隔离例外；3308 已有实例密码未知，未停机/修改）。每方法新建并删除随机 `captcha_test_*` 库，直接执行 V663 原始 DDL。环境 `CAPTCHA_MYSQL_URL=jdbc:mysql://127.0.0.1:3319/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`。首次并发复现 InnoDB deadlock 后，将限流改为主键 upsert，过期清理不与计数锁共用事务；受影响原用例通过，没有回退制造失败。

H2 全仓生成器在已有品牌营销 `JSON_LENGTH(recipients_json)` CHECK 处失败（与本次 V663 无关）；已通过同一生成器仅投影 V663，追加两张生成表到现有 dirty schema，保留其他内容。没有全仓 schema/全量回归通过的声明。按 MEMORY 只执行受影响增量验证。

## §6 对账二 · 设计 → 实现

本任务不提交共享工作区，因此以自身文件清单及执行前的 BizAuthController/schema 快照对比；不把其他会话 diff 归入本任务。

- 新增 `backend/shop-app/src/main/java/ai/neargo/shop/auth/captcha/{BizCaptchaService,JdbcCaptchaStore,TianaiSliderEngine}.java` 与 `impl/BizCaptchaServiceImpl.java`；装配位于 `backend/shop-app/src/main/java/ai/neargo/shop/config/BizCaptchaConfiguration.java`。
- 新增 `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizCaptchaController.java`、`backend/shop-base/src/main/java/ai/neargo/shop/common/captcha/CaptchaRequiredException.java`、`V663__biz_auth_captcha.sql`。
- 修改两个既有 biz auth/identity 控制器、SecurityConfig、BizContextFilter、GlobalExceptionHandler、application.yml；仅密码认证与发码入口增加凭据消费。
- 修改两个 POM（版本仅在父 dependencyManagement）与 H2 schema（仅新增两表投影）；新建三份测试 `BizCaptchaServiceTest`、`BizCaptchaHttpTest`、`BizCaptchaNativeMysqlTest`。
- 本 TDD 与 `docs/third-party/tianai-captcha-1.5.6/` 的 README、原始 LICENSE、artifact/parent POM 保留依赖来源。

偏差：为多实例共享复用已有主数据库，不引入 Redis；已有 AuthCache 是本地派生缓存，不能承担权威挑战。包采用已注册 auth.captcha，不新增顶层域。没有更改已应用迁移，V663 是新增迁移。

部署要求：先应用 V663 并发布支持挑战的客户端，再显式设置 `SHOP_AUTH_CAPTCHA_ENABLED=true`；所有 API 副本使用同一主数据库并保持时钟同步。默认 false 只用于协调 rollout，不能声称已上线防护。挑战限流在开关关闭时仍生效。已部署166并启用；未发送真实 OTP。发布与限制见 [部署记录](部署-B端滑块验证码-166-2026-11-13.md)。


## §7 统一开发分支移植与发布交接（2026-11-13）

用户追加授权合入统一开发分支并部署。干净发布工作区以 `origin/codex/ai-shop-codex` 的 `575747b14` 为基线，功能分支为 `codex/slider-captcha-release-20261113`。

源 checkout 有大量其他未提交工作，移植只使用本任务 25 文件清单。既有文件仅应用已审阅的验证码 hunks；BizAuthController 以执行前 `/tmp/captcha-before/BizAuthController.java` 为基线提取，因此没有带入源 checkout 其他登录令牌改动。测试 schema 仅追加两张验证码表的 17 行投影，没有复制整份 dirty schema。其余新增文件是本任务自建文件。主代理已确认胶囊范围。

统一分支已有冻结迁移 `V661__media_host_payment_evidence.sql`、`V662__operator_initial_store_profile.sql`，本功能迁移定为 `V663__biz_auth_captcha.sql`。源 checkout 仅重命名自身未提交验证码迁移并同步自身测试/TDD 引用，避免旧 V661 被后续误合；没有改动既有发布迁移。

合并树重新验证使用干净工作区源码与隔离 MySQL 9.7.4 / loopback3319，直接执行 V663。

- 首轮 `/tmp/captcha-release-tests.log`：服务 7、原生 MySQL 9、HTTP 5、既有邮箱 3、i18n 6 全部通过。完整 ArchitectureTest 23 项有 1 失败、1 跳过；失败只涉及本功能认证基础设施直接引用用户域邮箱函数。
- 已将既有邮箱归一化函数从 `config/BizCaptchaConfiguration` 注入服务，函数仍为 `EmailOtpService::normalize`，没有复制归一化规则。装配类放在既有 config 包，服务没有用户域依赖。
- `/tmp/captcha-release-affected.log`：注入后服务 7、原生 MySQL 9、HTTP 5 全通过；该轮暴露装配类仍在 auth 包，随后移至 config。只删除自身旧位置生成的 class，未执行全仓 clean。
- 最后只重跑受影响 HTTP 5 与 `ArchitectureTest#commonMustNotDependOnDomains`，`/tmp/captcha-release-final.log` 为 `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`、BUILD SUCCESS。其余已有效结果按 MEMORY 复用，不因文档/提交重复回归。统一分支没有遗留本功能失败；完整架构原有的其余项已在首轮通过，1项原有跳过保持。

部署/推送由主代理执行，本代理仅负责移植、验证和已授权本地提交。

# TDD · 测试身份白名单登录（统一验证码 / 统一密码）

状态：已实现（2026-11-07）
档位：1（新表 `usr_test_identity` V634、新运营端点 `/ops/test-identities/**`、新配置项 `shop.auth.test-identity.*`）
依据：用户需求（2026-11-07，经 b-app 会话转达，原话「增加测试白名单方案，针对注册登录，手机或者邮箱，增加到白名单中，则用统一的密码，验证码」）；方案稿 `ai-shop-bapp/docs/implementation/测试白名单登录方案.md`；用户决定：robin@neargo.ai「加入」，统一码与密码「直接在配置里写，针对所有的白名单用户一致，如1234，123456」。

## §0 需求 → 设计

已有的「测试号固定验证码」（TDD-测试号固定验证码，`usr_otp_test_phone`）只管大陆手机号、每号一个码、拒收已注册的号，用途是苹果审核。这次要的是联调：台湾手机号与邮箱都要能进、已注册的真实账号也能加、统一码统一密码，所以另起一张表，不去放宽那张表的护栏。

| # | AC | 落点 |
|---|---|---|
| A1 | 名单里的手机号请求验证码：不发短信、不存随机码；用统一码登录 / 注册成功 | `AuthServiceImpl.sendOtp`（限流之后跳过）、`verifyOtp`（统一码先认） |
| A2 | 名单里的邮箱同上：不发信；大小写不同也认 | `EmailOtpServiceImpl.send` / `verify` |
| A3 | 密码登录认统一密码，只登录**已存在**的账号（没设过密码也行），不建户；账号自己的真实密码照常可用 | `AuthServiceImpl.loginByPassword` |
| A4 | 不在名单的身份用统一码 / 统一密码一律登不进 | `TestIdentityServiceImpl.isTestIdentity` |
| A5 | 总开关默认关；关着时名单在也不生效；开着却没配码或密码拒绝启动 | `shop.auth.test-identity.enabled/.code/.password` |
| A6 | 运营在「系统设置 · 测试号」页签下维护名单：加、停用、删；停用即时生效；启用上限 10 条；写操作审计 | `OpsTestIdentityController` + `TestIdentityServiceImpl` + ops-web `test-identity-section.tsx` |

## §1 契约

- 表 `usr_test_identity`（V634）：`identity_type` PHONE/EMAIL，`identity_value` 规范化后的值（手机号去空格与连字符，邮箱小写），唯一键 `(identity_type, identity_value)`，不带种子。
- 配置（只在服务器 env，不进 git）：
  - `shop.auth.test-identity.enabled`（默认 false）
  - `shop.auth.test-identity.code`（统一验证码）
  - `shop.auth.test-identity.password`（统一密码）
- 运营端点，权限沿用 `system:testphone:read / update`（只有超管通配能到）：
  - `GET /ops/test-identities` → `{switchOn, items:[{id, identityType, identityValue, enabled, remark}]}`
  - `POST /ops/test-identities` `{identityType, identityValue, remark?}`；格式不对 10400，启用满 10 条 10462
  - `POST /ops/test-identities/{id}/enabled` `{enabled}`
  - `POST /ops/test-identities/{id}/remove`
- App 端无契约变化：仍走 `/biz/auth/otp/send`、`/biz/auth/email-otp/send`、`/biz/auth/login`。

## §2 模块

| 文件 | 内容 |
|---|---|
| `shop-app/.../db/migration/V634__test_identity.sql` | 建表 |
| `shop-core/.../user/entity/UsrTestIdentity.java`、`UserMappers.TestIdentityMapper` | 实体与 Mapper（`purge` 物理删） |
| `shop-core/.../user/service/TestIdentityService.java`、`impl/TestIdentityServiceImpl.java` | 判定、规范化、60 秒缓存（写操作立即清）、上限、审计、常量时间比较 |
| `shop-core/.../user/service/impl/AuthServiceImpl.java` | 手机发码 / 验码 / 密码登录三处接入（setter 注入） |
| `shop-core/.../user/service/impl/EmailOtpServiceImpl.java` | 邮箱发码 / 验码两处接入（setter 注入） |
| `shop-core/.../user/api/ops/OpsTestIdentityController.java` | 运营端点 |
| ops-web：`app/system/test-identity-section.tsx`、`page.tsx`、`copy.ts`、types / contracts / https / mocks / mock db | 界面，挂在测试号页签下面 |

## §5 测试

| AC | 测试 |
|---|---|
| A1、A4 | `TestIdentityFlowTest.whitelistedPhoneUsesUnifiedCode`：`sys_notify_log` 无短信、`OtpStore` 无码、统一码登录得 token；名单外的号用统一码失败 |
| A2 | `TestIdentityFlowTest.whitelistedEmailUsesUnifiedCode`：有效期 600、不存码、大小写混写统一码登录、错码失败 |
| A3、A6（停用即时） | `TestIdentityFlowTest.unifiedPasswordOnlyForExistingWhitelistedAccounts`：未注册不建户；注册后无密码也能用统一密码；名单外账号不行；停用后立即失效 |
| A5 | `TestIdentityServiceTest.switchOffAcceptsNothing`、`enabledWithoutSecretsRefusesToStart` |
| A4、规范化 | `TestIdentityServiceTest.switchOnAcceptsOnlyListedAndExact`、`normalize` |
| A6 | `TestIdentityFlowTest.opsListShowsSwitchAndRejectsMalformed`；`OpsEndpointPermTest`、`OpsPermConfigFlowTest`、`SchemaDriftTest` 通过 |

运行（2026-11-07）：`TestIdentityFlowTest` 4/4、`TestIdentityServiceTest` 4/4；同批 `EmailOtpAuthTest` 15、`OtpTestPhoneFlowTest` 8、`ProfileAndPasswordTest` 9、`SmsMockPrefixTest` 3、`BizAuthEmailLoginTest` 3、`OpsEndpointPermTest` 5、`OpsPermConfigFlowTest` 31、`SchemaDriftTest` 6 全绿；ops-web vitest 926/926。

消融：把手机验码、邮箱验码、统一密码三处接入分别改成 `false`，`whitelistedPhoneUsesUnifiedCode`（:63「统一码没能登录 / 注册」）、`whitelistedEmailUsesUnifiedCode`（:85）、`unifiedPasswordOnlyForExistingWhitelistedAccounts`（:104）各自变红；还原后转绿。

## §6 偏差说明

- 方案稿里的 `TestIdentityGuard`（prod profile 拒绝启动）没有做：本项目生产与本地都跑 `api,ops`（见 `FixedOtpGuard` 类注释），按 profile 判断拦不住任何东西，而用户明确要在 166（生产）上联调。护栏改为：总开关默认关、开着必须配码与密码、启动打 WARN、名单只超管可改且审计、启用上限 10 条。**正式运营前必须清空名单并关掉开关。**
- 不另开运营端页签：挂在「测试号固定验证码」页签下面，权限码相同，免去菜单、点位码与界面清单的改动。
- 同批顺手修了 `BizAuthEmailLoginTest` 的有效期断言（300 → 600）：上一笔「邮箱验证码改成 10 分钟」（414e08012）漏改了它。
- `ArchitectureTest` 有 5 条既有违规（定时任务锁、Port 位置、全限定名等），报告里没有本次改动的文件，不在本次范围。

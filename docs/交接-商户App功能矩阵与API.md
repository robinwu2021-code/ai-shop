# 商户 App 功能矩阵与 API 交接文档

> 本文复制自商户 App 仓库 `ai-shop-bapp` 的 `docs/implementation/功能矩阵与API交接文档.md`（2026-11-08，`main` 提交 `31a56c1`）。以 bapp 仓库为准；文中路径（`apps/merchant_app/...`、`docs/...`）都指 bapp 仓库。后端接口状态对照的是本仓库 `origin/codex/ai-shop-codex` 与 166 预发布。

整理：2026-11-08，仓库 `ai-shop-bapp`，分支 `feat/core-flow-ui` / `main`（`96d8295` 之后）。读者：接手的 Flutter、后端与产品同事。

这份文档按业务模块列出商户 App（NearGo 商户端，台湾一期）的全部功能、对应原型页、调用的接口，以及每个接口的输入、输出与对接状态。后端还没有的接口，给出 App 按 mock 形状实现的**建议契约**，后端可以照着开发，也可以改了再告诉 App。

相关文档：
- 原型清单（ai-shop-bapp `docs/prototypes/原型清单.md`）、原型总览（ai-shop-bapp `docs/prototypes/index.html`）：v4–v27 全部原型页。
- 后端对接清单（ai-shop-bapp `docs/implementation/后端对接清单.md`）：三批对接计划与 166 联调记录。
- 接口对接记录（ai-shop-bapp `docs/implementation/接口对接记录.md`）：逐个功能的对接细节。
- 缺少功能清单（ai-shop-bapp `docs/implementation/缺少功能清单.md`）：按「卡在哪里」整理的缺口。

## 1. 接手结论

- **界面**：原型 v4–v27 的页面 App 都已做出，mock 环境里全部可走通。全量测试 576 个通过，`dart analyze` 无问题。
- **接口**：App 调用约 345 个接口（方法 + 路径）。对照 ai-shop 最新分支 `origin/codex/ai-shop-codex`：
  - 约 240 个后端已有。其中 11/08 在 166 上读通 28 个读接口，字段解析都对。
  - 一大批已有的接口原先在**新模型门店**上被拒（70006 `CONTACT_ADMIN`），原因是没在 `ShopStoreMemberRoutes` 登记。ai-shop 已补登记并在 166 发版（11/08 09:49Z，`codex/biz-host-routes-70006` `56d729544`），重跑后 38 个读接口通过 32 个；列印模块打开、App 改读法后 36 个，剩下 2 个是自提（门店不是自提点，10403，属预期）。
  - 约 88 个后端没有，App 先按 mock 做了界面，正式包拦下（`features/v5/contracts.dart` 的 `pendingContracts`）。这些在本文标「建议」。
- **最优先**：修附录 A 的字段不一致（App 与后端各自改的部分）→ 冻结 P0 契约（统一登录、商户资料提交、资金、外部记账、问题回报与版本、快递公司）→ 按首批店型冻结餐饮或服务业。

## 2. 环境与运行

| 项 | 值 |
|---|---|
| 测试环境（166 预发布） | 商户 API `https://twpay.umeshop.ai/shop-api` |
| 测试登录 | 白名单账号（如 `robin@neargo.ai`）走邮箱验证码，后端跳过发信、用统一测试验证码；白名单在运营端「测试手机号」页（`/ops/test-identities`）维护 |
| mock 环境 | 不带服务地址时默认 mock；mock 手机号任意台湾号，验证码 `246810` |
| App 入口 | `apps/merchant_app/lib/main_onboarding_live.dart` |
| 打包参数 | `MERCHANT_API_BASE_URL`（服务地址）、`MERCHANT_DATA_SOURCE`（`server` / `mock`；有服务地址时默认 server）、`USER_AGREEMENT_URL`、`PRIVACY_POLICY_URL`、`MERCHANT_AGREEMENT_URL`（连真实服务必须给，否则登录页「登录条款暂未提供」）、`MERCHANT_DEFAULT_LOCALE`（开发默认 `zh_Hans`）、`APP_VERSION`、`SUPPORT_LINE` / `SUPPORT_PHONE` / `SUPPORT_EMAIL`、`NEARGO_MARKET` |
| 连 166 打包示例 | `../../tooling/flutterw build apk --debug -t lib/main_onboarding_live.dart --dart-define=MERCHANT_API_BASE_URL=https://twpay.umeshop.ai/shop-api --dart-define=MERCHANT_DATA_SOURCE=server --dart-define=USER_AGREEMENT_URL=… --dart-define=PRIVACY_POLICY_URL=… --dart-define=MERCHANT_AGREEMENT_URL=…` |
| 接口只读检查 | `test/live/batch1_smoke_test.dart`：带 `LIVE_BASE_URL` 与 `LIVE_TOKEN` 跑，逐个调读接口并用 App 自己的模型解析 |
| 测试 | `cd apps/merchant_app && ../../.toolchains/flutter/bin/flutter test`；静态检查 `dart analyze lib test` |

## 3. 接口通用约定

以下约定所有模块通用，模块章节里不再重复。

| 项 | 约定 |
|---|---|
| 返回包 | `{code, msg, data}`。`code=0` 成功；非 0 为业务错误，App 记为 `business_<code>`。`msg` 只有全大写机器码（如 `OFFLINE_UNAVAILABLE`）才会被 App 读取，人读文字不展示 |
| HTTP 状态 | 业务错误多数 HTTP 200；401 为未登录 / 过期；列印接口用真实 HTTP 状态（409、422、503） |
| 鉴权 | `Authorization: Bearer <token>`（登录返回 `token`） |
| 门店上下文 | 门店范围的接口带 `X-Store-No`（门店号）与 `X-Entity-No`（主体号）；账号范围的接口不带 |
| 语言 | `Accept-Language: zh-Hant` / `zh-Hans` / `en` / `ar`；服务端据此本地化行业、方案、渠道等系统名称 |
| 幂等 | 新建类写操作（如新增印表机、商城下单、退款）带 `Idempotency-Key` 头或请求体里的幂等号，重送不重复建 |
| 金额 | 最小单位整数（`xxxMinor`）或 `{currency, minor}`；币种跟门店（台湾 `TWD`，精度按 `currencyScale`） |
| 时间 | 时间戳毫秒或 ISO 8601；显示按门店时区（`timeZone`，台湾 `Asia/Taipei`） |
| 手机号 | E.164（`+886912345678`）；台湾号 09 开头 10 位 |
| 分页 | `page`、`size`；返回 `{records[], total, page, size}` |

### 常用错误码（App 已处理）

| 码 | 含义 | App 表现 |
|---|---|---|
| 401 / 10401 / 10402 | 未登录、登录过期、员工被停用 | 登出，登录页说明（v23 W15） |
| 10403 | 无权限 / 数据不在范围 | 通用「没有权限」 |
| 70006 + `nextAction: SELECT_STORE` | 要先选门店 | 退回门店列表 |
| 70006 + `nextAction: CONTACT_ADMIN` | 新模型门店未登记的路由（硬拒绝） | 「这家门店还没开放这个功能」 |
| 70006（无 nextAction） | 角色没有这个权限 | 说明后重读权限、回首页（v23 W14） |
| 10404 | 不存在或不是本店的 | 「不存在」 |
| 10409 | 冲突 / 状态已变 | 重读后再试 |
| 10450 / 10451 / 10429 / 10454 / 10455 / 60001 | 验证码太频繁 / 当天上限 / 网络太频繁 / 锁定 / 错误或过期 / 账号被拦截 | 按服务器给的秒数、分钟数倒数（v23 W30–W32） |
| 70023 / 40040 | 需要更高方案 / 权益不可用 | 「升级方案才能用」 |
| 70020 / 70022 | 门店 / 员工额度已满 | 「已用 n / n」 |

### 状态标记

| 标记 | 含义 |
|---|---|
| 已联调 | 11/08 在 166 上读通 |
| 已有 | ai-shop 最新分支有，App 已接，未在 166 验证 |
| 待登记 | 路由有，但新模型门店被拒（70006 `CONTACT_ADMIN`）。11/08 发版后本文已没有这类，保留说明供以后新增接口参考：新增经营接口要同步登记 `ShopStoreMemberRoutes` |
| 待确认 | 路径或字段是 App 推断的，要后端确认 |
| 建议 | 后端没有；本文给出 App 按 mock 实现的契约，正式包拦下 |

## 4. 模块总览

| # | 模块 | 原型 | App 代码 | 后端情况 |
|---|---|---|---|---|
| 1 | 账号与登录 | v4、v10 E09–E10、v23 W14–W15、W30–W32 | `features/standard_onboarding`、`authentication` | 登录已有；统一登录 `/biz/auth/identities*` 建议 |
| 2 | 门店与建店 | v4、v8、v23 W01–W08 | `standard_onboarding`、`store_profile` | 门店列表、门店资料已联调 |
| 3 | 方案、额度与商城 | v5 A、v6 商城、v23 W10–W13 | `standard_onboarding`、`mall`、`store_profile` | 商城、`/biz/plan` 已联调；商城用量建议 |
| 4 | 商户与进件 | v5 M/C/N、v8 C、v12 J01–J05 | `merchant`、`standard_onboarding`、`collect` | 多数已有；商户资料提交建议 |
| 5 | 我的、消息与通知 | v21 | `me` | 消息、本店通知已联调；换手机 / 绑信箱 / 注销无接口 |
| 6 | 系统状态 | v22 | `system` | 推播注册已有；问题回报、版本建议 |
| 7 | 收款与收银 | v6 收款 / 付款链接、v8 C04–C05、v12 J06–J07 | `collect` | 收款已有（166 联调过）；外部记账建议 |
| 8 | 资金 | v11 | `funds` | 建议（F06 已有） |
| 9 | 感应收款 | v13 | `softpos` | 建议（对接方式待蓝新） |
| 10 | 列印 | v23 W20–W25 | `store_profile` | 读接口已联调；写接口要按后端改（附录 A），飞鹅需 166 配密钥 |
| 11 | 商品 | v6 G、v14 | `goods`、`standard_onboarding` | 已联调 / 已有 |
| 12 | 订单与履约 | v6 R、S01、v15 | `store_orders` | 订单已联调；自提要门店先成为自提点（否则 10403）；快递公司建议 |
| 13 | 进销存 | v18、v25 | `inventory` | 已登记（11/08），待 166 联调；正式包 `inventoryOpen` 关着 |
| 14 | 餐饮 | v6 F、v16 | `store_orders` | 建议（`/biz/food/*`） |
| 15 | 服务业 | v6 B、v17 | `store_orders` | 建议（`/biz/beauty/*`） |
| 16 | 员工与权限 | v10 | `staff`、`identity_access` | 已联调 / 已有 |
| 17 | 考勤与工资 | v27 E11–E21 | `hr` | 建议（`/biz/hr/*`，后端没有） |
| 18 | 会员与营销 | v20、v24 | `operations`、`members` | 两套：`/biz/ops/*` 建议；`/biz/member-*` 等已登记；待统一 |
| 19 | 连锁 | v19 | `operations` | 建议（`/biz/ops/*`） |
| 20 | 团购 | v26 | `group_buy` | 已登记；建活动字段待确认 |
| 21 | 评价 | v27 V01–V03 | `standard_onboarding` | 已联调（列表） |

---

## 1. 账号与登录

手机 / 邮箱验证码登录、密码登录、统一登录与多重身份、登录限制与会话失效。原型 v4 S01–S02、S10，v10 E09–E10，v23 W14–W15、W30–W32。代码在 `features/standard_onboarding`（`onboarding_api.dart`、`onboarding_controller.dart`、`onboarding_forms.dart`、`onboarding_identity.dart`）。正式包现在走「店主登录」（`/biz/auth/login`）；统一登录（`/biz/auth/identities*`）只在 mock 下可用，正式包被 `pendingContracts` 拦下。166 上白名单邮箱已能登录并进入「台北早餐」。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 1-01 | 手机验证码登录 / 注册 | v4 S01–S02 | 全部 | `POST /biz/auth/otp/send`、`POST /biz/auth/login` | 已有 | 手机号先规范成 E.164；必须勾选协议（`agreed=true`）才发请求 |
| 1-02 | 邮箱验证码登录 / 注册 | v4 S01、v10 E09 | 全部 | `POST /biz/auth/email-otp/send`、`POST /biz/auth/login` | 已有 | 台湾一期的真实入口；`locale` 决定邮件正文字形；166 白名单邮箱已走通 |
| 1-03 | 密码登录 | v4 S10、v21 P04 | 全部 | `POST /biz/auth/login`（`grantType=PASSWORD`） | 已有 | 手机或邮箱都可作 `principal`；设密码见 5-03 |
| 1-04 | 新注册时选地区 | v4 S01（地区卡） | 全部 | `POST /biz/auth/login`（`marketCode`） | 已有 | 只对新注册生效；已有账号以注册时地区为准 |
| 1-05 | 统一登录：列出这个账号的所有身份 | v10 E09、E09b | 全部 | `POST /biz/auth/identities` | 建议 | 只验证、不建任何东西；一个身份直接进入，多个让用户选，零个进 E09c |
| 1-06 | 进入其中一个身份 / 新开商户 | v10 E09b、E09c | 全部 | `POST /biz/auth/identities/enter` | 建议 | `identityNo=NEW_OWNER` 表示没有身份时新开商户；本机记住上次进入的身份排第一 |
| 1-07 | 切换身份 | v21 P02、v10 E10 | 全部 | `GET /biz/auth/identities/mine` | 建议 | 「我的 › 切換身分」，只在统一登录开着时显示 |
| 1-08 | 员工视角 | v10 E10 | 员工 | （身份 `type=STAFF` + `/biz/context` 的 `perms`） | 建议 | 员工会话隐藏店主专属入口（方案、主体、员工管理） |
| 1-09 | 冷启动恢复登录 | — | 全部 | `GET /biz/merchant/profile` → `GET /biz/stores/mine` | 已联调 | 本机有 token 就读资料；失败按 401 处理 |
| 1-10 | 验证码太频繁 / 当日上限 | v23 W30 | 全部 | `POST /biz/auth/otp/send`、`email-otp/send` | 已有 | 10450 用服务器给的秒数倒计时（无则 60 秒）；10451 当日上限；10429 太频繁 |
| 1-11 | 验证码输错与锁定 | v23 W31 | 全部 | `POST /biz/auth/login` | 已有 | 10455 输错可再试；10454 锁定，分钟数取自服务器消息；两码不可合并 |
| 1-12 | 账号被拦截 | v23 W32 | 全部 | `POST /biz/auth/login` | 已有 | 60001：登录页常驻提示「已暂停登入，已收款与订单不受影响」 |
| 1-13 | 会话被服务器结束 | v23 W15 | 全部 | 任意接口回 401 | 已有 | 自动登出，登录页提示一次「上次登录已结束」（员工被停用或过期） |
| 1-14 | 权限被收回 | v23 W14 | 店长、员工 | 任意接口回 70006 | 已有 | 弹窗说明 → 重读门店列表 → 回首页；`nextAction` 为 `SELECT_STORE` / `CONTACT_ADMIN` 时不走这条（见待决） |
| 1-15 | 登出 | v21 P13 | 全部 | `POST /biz/auth/logout` | 已有 | 先清本机，再尽力通知服务器；离线也能登出 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `POST /biz/auth/otp/send` | 发手机验证码 | `phone`（E.164） | `expiresInSeconds?`；开发环境另有 `deliveryMode, region, developmentCode` | 10450（msg 含秒数）、10451、10429、60001；匿名 | 已有 |
| `POST /biz/auth/email-otp/send` | 发邮箱验证码 | `email, locale` | `expiresInSeconds?` | 同上；10505 在邮箱页表示邮件通道不可用；166 曾因库连接被拉黑超时（已查明） | 已有 |
| `POST /biz/auth/login` | 登录 / 注册 | `grantType: PHONE_OTP\|EMAIL_OTP\|PASSWORD, principal, credential, agreed, marketCode?` | `token, merchant{merchantNo, status, currentStoreNo, agreementPending, displayName, phone, userNo?, account?}` | 10455 码错、10454 锁定（msg 含分钟）、10456 / 10457 密码错、60001 拦截；匿名 | 已有 |
| `POST /biz/auth/identities` | 统一登录：列身份 | 同 login 的请求体 | `ticket, identities[{identityNo, type: OWNER\|STAFF, merchantName, role, storeCount}]` | 空数组 = 这个号还没有店（E09c）；匿名 | 建议 |
| `POST /biz/auth/identities/enter` | 进入身份 | `ticket, identityNo`（或 `NEW_OWNER`） | 同 login：`token, merchant{…}` | 匿名，凭 `ticket` | 建议 |
| `GET /biz/auth/identities/mine` | 已登录账号的身份 + 换身份用的 ticket | — | `ticket, identities[…]`（同上） | — | 建议 |
| `POST /biz/auth/staff-login` | 旧员工登录 | `phone, code` | `token, merchant{…}` | App 有方法但没有页面调用；统一登录上线后可废 | 已有 |
| `GET /biz/merchant/profile` | 当前账号 / 商户资料 | 可带头 `X-Store-No`、`X-Entity-No` | `merchantNo, status, currentStoreNo, agreementPending, displayName, phone, subject, fundsMode, name, rejectReason` | 带门店头时 App 校验 `merchantNo == entityNo` | 已联调 |
| `POST /biz/auth/logout` | 登出 | — | — | 失败也照样清本机 | 已有 |

### 待决与注意
- 统一登录 3 个接口是 P0（后端清单 3-1），契约以 `dev/mock/mock_onboarding_server.dart` 的 `_identities` 为准；冻结后从 `features/v5/contracts.dart` 删 `^/biz/auth/identities`，App 侧开关 `unifiedLogin` 打开即可。
- 70006 分三种：`nextAction=SELECT_STORE` → 清当前门店回门店列表；`nextAction=CONTACT_ADMIN` → 「这家门店还没开放这个功能」（新模型门店路由未登记，见 2-0）；其他 70006 → W14 权限被收回。后端不要把这三种混用。
- 10450 / 10454 的等待时间是从 `msg` 里抽第一个数字（秒 / 分钟），后端改文案时数字必须保留；最好另给结构化字段（建议 `retryAfterSeconds`）。
- 正式包打包必须带 `USER_AGREEMENT_URL`、`PRIVACY_POLICY_URL`，否则登录页显示「登录条款暂未提供」、无法勾选协议。
- `features/authentication`（旧 admission 壳）还调 `GET /biz/auth/session`、`POST /biz/auth/market`，后端最新分支没有这两条；正式包 `main_prod.dart` 运行的是 `StandardOnboardingApp`，不经过它们，可以忽略或清理。
- 冷启动连 166 约 15 秒才进首页，未查出是哪个请求慢。

---

## 2. 门店与建店

门店列表与切换、三步建店（地区 → 业态 → 地址 / 地图选点）、门店资料维护（名称地址、营业时间、封面、公告、服务范围与自画范围、资质、暂停营业）。原型 v4 S03–S08、v8 H01/H04/H05、v23 W01–W08、W13。代码在 `features/standard_onboarding`（建店、门店列表、`onboarding_location_picker.dart`、`store_admin_pages.dart`）与 `features/store_profile`（`store_profile_api.dart`、`store_profile_pages.dart`）。门店列表与建店读接口在 166 已通；`/biz/store` 整组在新模型门店上被 70006 拒，等后端登记。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 2-01 | 我的门店列表（按主体分组） | v4 S03–S04、v8 H04 | 全部 | `GET /biz/stores/mine` | 已联调 | 员工只看到自己所在的店；没有店且有进件时读进件进度 |
| 2-02 | 进入 / 切换门店 | v8 H01 | 全部 | `GET /biz/merchant/profile`、`GET /biz/context`、`GET /biz/stores/{storeNo}/context` | 已联调 / 已有 | 依次读：商户资料（校验主体）、权限点、门店经营上下文；再按权限读进件与收款 |
| 2-03 | 建店第 1–2 步：地区与行业 / 细分业态 | v4 S05、S05b | 店主 | `GET /biz/merchant/store-setup` | 已有 | 「其他」行业用 `otherBusinessTypeCode` + 自填文字 |
| 2-04 | 业态与方案目录 | v4 S06 | 店主 | `GET /biz/business-types`、`GET /biz/business-types/{code}/plans` | 已联调 / 已有 | 方案目录见 3-03 |
| 2-05 | 建店第 3 步：地址搜索 | v4 S06b | 店主 | `GET /biz/geo/tips` | 已有 | 后端代理高德 / Google，按 `market` 选；只取带坐标的结果 |
| 2-06 | 地图选点反查地址 | v4 S06b | 店主 | `GET /biz/geo/reverse` | 已有 | 坐标系随地区：高德 gcj02、Google wgs84 |
| 2-07 | 提交建店 | v4 S07–S08 | 店主 | `POST /biz/merchant/quick-start` | 已有 | 台湾必须有地址；成功后重读门店列表，找到新店再进入，找不到提示「已建立但未确认」 |
| 2-08 | 门店资料总览 | v23 W01 | 店主、店长 | `GET /biz/store` | 已联调 | 名称、地址、营业时间、公告、服务范围、封面 |
| 2-09 | 改名称与地址 | v23 W02 | 店主 | `POST /biz/store/{storeNo}/rename` | 已有 | 名称与地址只能走这一条；地址补充（楼层）走 `POST /biz/store` 的 `addressDetail` |
| 2-10 | 营业时间 | v23 W03 | 店主、店长 | `POST /biz/store`（`openHours`） | 已有 | 后端只有一段文字；App 用选择器拼成一句话再存 |
| 2-11 | 门店封面 | v23 W01 | 店主、店长 | `POST /biz/upload/image`（`bizType=STORE`）→ `POST /biz/store`（`bannerUrl`） | 已有 | `bannerUrl` 空串 = 清掉，不传 = 不改 |
| 2-12 | 公告（可同步到其他门店、常用语） | v23 W04 | 店主、店长 | `POST /biz/store/announcement`、`POST /biz/store/announcement/recent/remove` | 已有 | 敏感内容进人工审核：旧公告继续显示，`noticePending` 显示待审原文 |
| 2-13 | 服务范围（履约方式 + 覆盖区域） | v23 W05 | 店主、店长 | `POST /biz/store/scope-preview`、`POST /biz/store`（`fulfillmentReach, serviceAreas`） | 已有 | 保存前预估覆盖社区数与买家数；区、街道级覆盖要运营审核（`status=PENDING`） |
| 2-14 | 地图上画配送范围 | v23 W06 | 店主、店长 | `POST /biz/store`（`serviceAreas[level=POLYGON]`） | 待确认 | `geometry` 格式与坐标系和后端不一致，见待决 |
| 2-15 | 资质 | v23 W07 | 店主 | `GET /biz/qualifications`、`POST /biz/qualifications/save` | 已联调 / 已有 | 接口见第 4 节 |
| 2-16 | 暂停 / 恢复营业 | v23 W08 | 店主 | `POST /biz/store/{storeNo}/status` | 已有 | 默认店不能停用；被平台停权的店不能自己恢复 |
| 2-17 | 门店唯读 / 被停权提示 | v23 W13 | 全部 | `GET /biz/store`（`status`） | 待确认 | `READONLY` 商户自停或方案超额；`SUSPENDED` 平台下架 |
| 2-18 | 门店代码（公开链接 `/s/<slug>`） | v8 H05 | 店主 | `POST /biz/store/{storeNo}/slug` | 已有 | 空串 = 清除 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/stores/mine` | 门店列表 | — | `[{entity{entityNo, name, status, legalForm, taxNo, owner, storeCount, canManage, market}, stores[{storeNo, name, address, market?, currency, currencyScale, timeZone, industryCode, subIndustryCode, industryText, subIndustryText, isDefault, status, payReady, planSuspended, slug}]}]` | 门店行没带 `market` 时取主体的 | 已联调 |
| `GET /biz/context` | 当前门店的权限 | 头 `X-Store-No`、`X-Entity-No` | `currentStoreNo, merchantNo, owner, perms[]` | `perms` 含 `*` 为全部；App 校验门店号与主体号一致才采用 | 已有 |
| `GET /biz/merchant/store-setup` | 建店可选的地区、行业、业态 | — | `markets[{market, displayName, currency, currencyScale, timeZone, mapProvider, mapJsKey, mapJsSecurity, industries[{industryCode, displayName, subIndustries[{subIndustryCode, displayName, businessTypeCode, businessTypeDisplayName, catalogVersion}]}]}], otherBusinessTypeCode` | — | 已有 |
| `GET /biz/business-types` | 业态列表 | 头 `Accept-Language` | `[{businessTypeCode, displayName, catalogVersion}]` | 页面目前以 store-setup 为准 | 已联调 |
| `GET /biz/geo/tips` | 地址联想 | `kw, market` | `[{name, address, latE6, lngE6}]` | 后端另支持 `city`（App 未传） | 已有 |
| `GET /biz/geo/reverse` | 坐标 → 地址 | `lat, lng`（度）, `market, sys: gcj02\|wgs84` | `recommend?, address` | — | 已有 |
| `POST /biz/merchant/quick-start` | 建店 | `storeName, businessTypeCode, address?, market?, industryCode?, subIndustryCode?, industryText?, subIndustryText?, addressDetail?, latE6?, lngE6?, coordSys?` | 商户资料：`merchantNo, status, currentStoreNo` | 70198 业态不可用；手动输地址时坐标三项都不传 | 已有 |
| `GET /biz/store` | 门店资料 | 头 `X-Store-No` | `name, address, addressDetail, openHours, announcement, announcementUntil, announcementRecent[], noticePending{content, submittedAt}, fulfillmentReach, serviceAreas[{areaNo, level: COMMUNITY\|STREET\|DISTRICT\|CITY\|POLYGON\|UNLIMITED, refCode, name, mode: INCLUDE\|EXCLUDE, status: ACTIVE\|PENDING, geometry}], latE6, lngE6, bannerUrl, slug, status: ACTIVE\|READONLY\|SUSPENDED, isDefault` | 后端 VO 没有 `name, slug, status, isDefault` | 已联调 |
| `POST /biz/store` | 改门店资料（只传要改的字段） | 头 `X-Store-No`；`openHours?, addressDetail?, bannerUrl?, fulfillmentReach?: [PICKUP\|SHIPPING\|ONSITE], serviceAreas?[{areaNo?, level, refCode?, name, mode, geometry?{points[[latE6, lngE6]]}}], latE6?, lngE6?` | 同 `GET /biz/store` | 70034 履约方式为空；`fulfillmentReach` 类型与后端不一致 | 已联调 |
| `POST /biz/store/{storeNo}/rename` | 改名称与地址 | `name, address` | `StoreVO` | 10400 名称为空；店主 | 已有 |
| `POST /biz/store/announcement` | 发布公告 | 头 `X-Store-No`；`announcement`（≤60 字）, `announcementUntil?`（epoch 毫秒）, `alsoStoreNos[]` | — | 进审核时回读 `noticePending` | 已有 |
| `POST /biz/store/announcement/recent/remove` | 删一条常用公告 | `text` | — | 按原文匹配 | 已有 |
| `POST /biz/store/scope-preview` | 服务范围预估 | `areas[{level, refCode, mode, …}]` | `currentCommunities, currentBuyers, nextCommunities, nextBuyers` | 后端 `AreaItem` 只收 `level, refCode, mode`，画的范围不计入 | 已有 |
| `POST /biz/store/{storeNo}/status` | 暂停 / 恢复营业 | `active: true\|false` | `StoreVO` | 10400 默认店不能停；70021 已被平台停权；需 `biz:store:admin` | 已有 |
| `POST /biz/store/{storeNo}/slug` | 设门店代码 | `slug`（空串清除） | `StoreVO` | 不在 70006 已测清单里，未验证 | 已有 |
| `GET /common/master-data` | 进件主数据 | — | `industries[{industry, name, microAllowed}], subjects[{subjectType, name, needLicense, industryGated, settleAccountType}], serviceScopes[], channels[], intentIndustries[]` | 匿名；166 曾超时 10 秒；进件表单与渠道进件用 | 已联调 |
| `GET /biz/communities` | 可选社区 | — | `[{communityNo, name, address}]` | 进件 `serviceScope=COMMUNITY` 时用 | 已有 |

### 待决与注意
- **最优先**：新模型门店上 `/biz/store`（GET/POST）、`announcement`、`scope-preview`、`rename`、`status` 全部 70006（`CommerceRoutePolicy` 默认拒绝），需后端在 `ShopStoreMemberRoutes.ALL` 逐条登记并补 `StoreScopedDailyRoutesFlowTest`。登记前 W01–W08、W13 在正式包都显示「这家门店还没开放这个功能」。
- `fulfillmentReach`：后端 `StoreReq` / `StoreProfileVO` 是**单个字符串**（为空按 PICKUP），App 收发的是**数组**。要么后端改成数组，要么 App 改成单选。
- 自画范围 `geometry`：后端是 JSON **字符串** `[[lngE6, latE6], …]`（ADR-034，先经度），App 发的是对象 `{points: [[latE6, lngE6], …]}`（先纬度）。坐标系也要定：后端注释写门店坐标是 gcj02，台湾走 Google 地图是 wgs84。
- `GET /biz/store` 的后端 VO 没有 `name, slug, status, isDefault`：App 拿不到 `status`，W13 唯读 / 停权提示永远不出现（默认 ACTIVE）。要么 VO 补字段，要么 App 改从 `/biz/stores/mine` 的门店行取。
- 建店成功后 App 只信 `/biz/stores/mine` 的回读：`quick-start` 返回的 `currentStoreNo` 在列表里找不到时不自动进入，提示用户重试（防重复建店）。
- `/biz/store` 不带 `X-Store-No` 时后端取默认店；App 每次都带，后端不要回落到默认店以免改错店。

---

## 3. 方案、额度与商城

门店的订阅方案与权益、商家商城（软件方案、硬件、套餐）线下转账采购、主体方案的宽限期与到期。原型 v5 A01–A07、v6 商城 S01–S04 / H01–H03 / K01–K03 / P01–P06 / O01–O05、v23 W10–W12。代码在 `features/standard_onboarding`（`mall_offline_api.dart`、`mall_offline_pages.dart`、`onboarding_entitlements_page.dart`、`onboarding_entitlements_reader.dart`）、`features/mall`、`features/store_profile`（`PlanApi`、`plan_pages.dart`）。商城读接口在 166 已通；`/biz/plan` 被 70006 拒；用量余额与按组件退款后端没有。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 3-01 | 门店当前方案（首页与状态页） | v5 A05、v8 H01 | 全部 | `GET /biz/stores/{storeNo}/subscription` | 已联调 | FREE 显示为无付费方案；`purchaseAvailable=false` 时不给买 |
| 3-02 | 门店经营上下文与权益明细 | v5 A07 | 店主、店长 | `GET /biz/stores/{storeNo}/context` | 已有 | 含 subscription 与 entitlements；地区、币种、时区必须与门店行一致才采用 |
| 3-03 | 方案目录与价格 | v5 A01 | 店主 | `GET /biz/business-types/{code}/plans` | 已有 | 只在 `commerceModel=STORE_V1` 且目录 `PUBLISHED`、版本一致时显示 |
| 3-04 | 商城首页与分类 | v6 S01–S04 | 店主 | `GET /biz/stores/{storeNo}/mall/products` | 已联调 | 分 SOFTWARE / HARDWARE / BUNDLE 三栏；`canProcure=false` 只能看 |
| 3-05 | 硬件与套餐详情 | v6 H01–H03、K01–K03 | 店主 | 同上 | 已联调 | 规格、组件、依赖（如刷卡要先开通收款）、套餐原价合计 |
| 3-06 | 确认订单（报价） | v5 A02、v6 P01 | 店主 | `POST /biz/stores/{storeNo}/mall/offline/quotes` | 已有 | 报价有效期 `expiresAt`；过期提示重新报价 |
| 3-07 | 下单 | v6 P01 | 店主 | `POST /biz/stores/{storeNo}/mall/orders` | 已有 | 一次意图一把幂等键；有硬件时带收货地址 |
| 3-08 | 取得转账资讯 | v5 A03、v6 P02 | 店主 | `POST` / `GET /biz/stores/{storeNo}/mall/offline/orders/{orderNo}/instructions` | 已有 | 下单后立即 POST 产生；之后 GET 回读；有付款期限 |
| 3-09 | 上传转账凭证 | v6 P03 | 店主 | `POST …/evidence`、`POST …/evidence/content` | 已有 | 先预留 `assetKey` 再传原始图片（png / jpg ≤10 MB） |
| 3-10 | 付款申报与审核结果 | v5 A04、v6 P04–P06 | 店主 | `POST` / `GET …/offline/orders/{orderNo}/claims` | 已有 | 金额、付款时间、帐号末五码、凭证；被退回显示原因与实收金额 |
| 3-11 | 采购单列表与详情（含包裹） | v6 O01–O03 | 店主 | `GET /biz/stores/{storeNo}/mall/orders`、`GET …/mall/orders/{orderNo}` | 已联调 / 已有 | 付款、数字权益、硬件包裹各有状态 |
| 3-12 | 按组件申请退款 | v6 O04 | 店主 | `POST …/mall/orders/{orderNo}/component-refunds` | 建议 | 金额由平台算 |
| 3-13 | 用量余额（AI token、短信） | v6 O05 | 店主 | `GET /biz/stores/{storeNo}/usage` | 建议 | 余额与明细 |
| 3-14 | 我的方案（主体额度） | v23 W12 | 店主 | `GET /biz/plan` | 已联调 | 门店数、员工数额度与用量，各档对比；续约「联系平台」 |
| 3-15 | 方案宽限期横幅 | v23 W10 | 店主 | `GET /biz/plan`（`status=GRACE`） | 已联调 | 到期后 7 天宽限，功能照常 |
| 3-16 | 方案已到期 | v23 W11 | 店主 | `GET /biz/plan`（`status=EXPIRED`） | 已联调 | 降回免费；超额门店唯读，列出受影响门店与未完成订单；每次运行只弹一次 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/stores/{storeNo}/subscription` | 门店订阅 | 头 `X-Store-No` | `currentPlan{tier: FREE\|PRO\|…, effectiveUntil}, purchaseAvailable, free, sources[]` | `effectiveUntil` 后端没有，见待决 | 已联调 |
| `GET /biz/stores/{storeNo}/context` | 门店经营上下文 | 头 `X-Store-No` | `storeNo, entityNo, storeName, market, currency, currencyScale, timeZone, serverNow, commerceModel: STORE_V1\|LEGACY, entitlementVersion, businessTypeCode, industryCode, subIndustryCode, subscription{free, currentPlan{sourceNo, sourceType, tier, displayName, planStartsAt, planEndsAt, entitlementCodes[]}, sources[]}, entitlements{items[{entitlementCode, endCode, displayName, commercialStatus, current{startsAt, endsAt}, future[], remainingMs}]}` | 嵌套的 `storeNo / commerceModel / serverNow / entitlementVersion` 必须与外层一致 | 已有 |
| `GET /biz/business-types/{code}/plans` | 方案目录 | 头 `Accept-Language` | `businessTypeCode, catalogVersion, status: PUBLISHED…, plans[{tier, displayName, summary, priceStatus: INCLUDED\|DISPLAY_ONLY…, entitlementCodes[], priceOptions[{title, price{currency, minor}, periodDays, unitCount}]}]` | — | 已有 |
| `GET /biz/stores/{storeNo}/mall/products` | 商城货架 | 头 `X-Store-No` | `currency, canProcure, categories[{code, parentCode, level, names{zh-CN, zh-TW, en}, icon, sortOrder}], products[{product{productNo, name, kind: PLAN\|HARDWARE\|SUPPLY\|CONSUMABLE\|BUNDLE…, description, subCategory, categoryCode, dependency, specs{}}, skus[{sku{skuVersionNo, title, price{currency, minor}, listPrice?, stockNote?, components[{kind: PLAN\|HARDWARE\|USAGE, title, periodDays, unitCount, note}]}, readiness{available, reasons[]}, offlineReadiness{available, reasons[]}}]}]` | `minor` 是十进制整数**字符串**（scale 2）；两种 readiness 都没给时视为可买 | 已联调 |
| `POST /biz/stores/{storeNo}/mall/offline/quotes` | 报价 | `lines[{skuVersionNo, quantity}], currency` | `quoteNo, payable{currency, minor}, expiresAt` | 原因码见待决 | 已有 |
| `POST /biz/stores/{storeNo}/mall/orders` | 下单 | `quoteNo, idempotencyKey, shippingAddress?{recipient, addressLine, …}` | `orderNo` | QUOTE_EXPIRED、QUOTE_USED | 已有 |
| `GET /biz/stores/{storeNo}/mall/orders` | 采购单列表 | 头 `X-Store-No` | `[{order{orderNo, payable, createdAt, title}, title, lineCount, paymentStatus: PAID\|CANCELLED\|CLAIM_REJECTED…, fulfillmentStatus, refundStatus: NONE…, latestClaimStatus}]` | — | 已联调 |
| `GET /biz/stores/{storeNo}/mall/orders/{orderNo}` | 采购单详情 | 头 `X-Store-No` | `summary{…同列表}, lines[{title, quantity}], quote{components[{kind, title, cycleDays, quantity}]}, shipments[{title, status, carrierCode, trackingNo, eta, trace[{title, at}]}], entitlementUntil, shippingAddress{recipient, addressLine}` | — | 已有 |
| `POST /biz/stores/{storeNo}/mall/offline/orders/{orderNo}/instructions` | 产生转账资讯 | `{}` | `orderNo, payable, paymentDueAt, instructions{键: 值}` | OFFLINE_UNAVAILABLE、OFFLINE_NOT_REQUIRED | 已有 |
| `GET /biz/stores/{storeNo}/mall/offline/orders/{orderNo}/instructions` | 回读转账资讯 | — | 同上 | OFFLINE_INSTRUCTIONS_NOT_ISSUED | 已有 |
| `POST /biz/stores/{storeNo}/mall/offline/orders/{orderNo}/evidence` | 预留凭证位置 | `{}` | `assetKey` | — | 已有 |
| `POST /biz/stores/{storeNo}/mall/offline/orders/{orderNo}/evidence/content` | 上传凭证图片 | query `assetKey`；body 为原始图片，头 `Content-Type: image/png\|image/jpeg` | `assetKey` | EVIDENCE_ALREADY_STORED；≤10 MB | 已有 |
| `POST /biz/stores/{storeNo}/mall/offline/orders/{orderNo}/claims` | 付款申报 | `claimNo, declaredAmount{currency, minor}, declaredPaidAt`（ISO-8601 UTC）, `bankReference`（末五码）, `evidenceKeys[]` | `claimNo, status: SUBMITTED\|REVIEWING\|CONFIRMED\|MISMATCHED\|DECLINED, declaredAmount, declaredPaidAt, submittedAt, bankReference, evidenceKeys[], receivedAmount?, reviewNote?` | `claimNo` 由 App 生成，重试复用 | 已有 |
| `GET /biz/stores/{storeNo}/mall/offline/orders/{orderNo}/claims` | 申报记录 | — | `[{…同上}]` | 实收也可能叫 `confirmedAmount`，原因可能叫 `reason` / `note`，App 都认 | 已有 |
| `POST /biz/stores/{storeNo}/mall/orders/{orderNo}/component-refunds` | 按组件退款 | `refundNo, components[], reason` | — | 后端没有 | 建议 |
| `GET /biz/stores/{storeNo}/usage` | 用量余额 | 头 `X-Store-No` | `balances[{kind, balance, expiresAt}], entries[{kind, title, at, delta, until}]` | 后端没有 | 建议 |
| `GET /biz/plan` | 主体方案 | 头 `X-Store-No`（主体级数据，但新模型门店要在门店上下文里读） | `planCode, planName, status: ACTIVE\|GRACE\|EXPIRED, startAt, expireAt, graceUntil?, storeQuota, storeUsed, staffQuota, staffUsed, crossStoreStats, trialUsed, trialTier, trialDays, suspendedStores[{storeNo, name, openOrders}], tiers[{planCode, name, storeQuota, staffQuota, crossStoreStats, trialDays, current}]` | 需 `biz:store:admin`，店长回 403（不显示）；新模型门店 70006；字段差异见待决 | 已联调 |

### 待决与注意
- `/biz/plan`：后端 `MinePlanVO.suspendedStores` 是 `List<String>`（只有门店号），App 要 `[{storeNo, name, openOrders}]` 才能在 W11 列出受影响门店与未完成订单；后端也没有 `graceUntil`，App 按 `expireAt + 7 天` 推算（对应 `PlanExpiryJob`）。
- `subscription.currentPlan.effectiveUntil` 后端没有（上下文里叫 `planEndsAt`），首页「方案到期日」在正式包读不到；建议统一成一个字段名。
- 商城只接了**线下转账**（task8-v1 契约）。后端另有在线付款与退款（`/orders/{o}/payments*`、`/cancel`、`/refund-previews`、`/refunds`、`/upgrade-quotes`），App 未接；O04 的「按组件退款」是 App 自拟契约，要和后端的 `/refunds` 二选一。
- 商城机器原因码（后端 `msg`）App 已映射：OFFLINE_UNAVAILABLE / OFFLINE_NOT_REQUIRED、QUOTE_EXPIRED / QUOTE_USED、ACCOUNT_BUSY / DIGITAL_CHANGE_PENDING、OFFLINE_INSTRUCTIONS_NOT_ISSUED、EVIDENCE_ALREADY_STORED、STORE_UNAVAILABLE、CURRENCY_MISMATCH / MONEY_POLICY_UNAVAILABLE / CURRENCY_UNAVAILABLE、OFFLINE_INTENT_REQUIRED。新增原因码要同步给 App。
- 金额 `minor` 是字符串，App 不做浮点运算，只插小数点显示；后端不要改成数字。
- `POST /biz/plan/trial` 后端有，App 未接（W12 只显示可试用天数，续约与试用都走「联系平台」）。

---

## 4. 商户与进件

商户主体（后端 entity，`merchantNo == entityNo`）、入驻申请与协议、收款渠道进件与状态、门店收款申请开通（单人审核）、资质与证照识别。原型 v5 M01–M04、C01–C09，v8 H02–H03、C01–C03，v12 J01–J05，v23 W07。代码在 `features/merchant`（`merchant_api.dart`、`merchant_pages.dart`、`merchant_form_page.dart`、`store_payment_page.dart`）、`features/standard_onboarding`（`onboarding_payments.dart`、`onboarding_certificates.dart`）、`features/collect/pay_service_apply.dart`。台北早餐的收款开通（pay-service）在 166 联调中；一次提交进件、绑定已有商户号、门店改挂主体三条后端没有。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 4-01 | 商户主体列表 | v5 M01、v8 H02 | 店主 | `GET /biz/entities`、`GET /biz/merchant/payment?entityNo=` | 已有 | 每个主体再读一次渠道 |
| 4-02 | 主体详情（门店、渠道） | v5 M02–M03、v8 H03 | 店主 | `GET /biz/entity/{entityNo}`、`GET /biz/merchant/payment?entityNo=` | 已有 | 渠道固定显示 NEWEBPAY、LINEPAY、JKOPAY（JKOPAY 后端无代码，恒为「即将开放」） |
| 4-03 | 渠道状态总览与补件 / 签约 / 激活中 / 暂停 | v12 J01–J05 | 店主 | `GET /biz/merchant/payment` | 已有 | 由 `applyStatus` + `canReceiveMoney` + `submitted` + `missing` + `signRequired` 推出九种状态 |
| 4-04 | 为本店启用主体已有的商户号 | v5 C03 | 店主 | `POST /biz/store/{storeNo}/payment` | 已有 | — |
| 4-05 | 门店改挂到另一个主体 | v5 C02（N1） | 店主 | `POST /biz/store/{storeNo}/entity` | 建议 | — |
| 4-06 | 绑定已有的渠道商户号 | v5 C09（N2） | 店主 | `POST /biz/merchant/payment/bind` | 建议 | — |
| 4-07 | 新建商户资料（四步一次提交） | v5 C04–C07、M04（N3） | 店主 | `POST /biz/merchant/onboarding` | 建议 | 主体类型 COMPANY / SOLE / PERSONAL 决定必传证照；统编 8 位；帐号 10–16 位 |
| 4-08 | 入驻申请（旧进件表单）与进度 | v4 后、v8 H01b | 店主 | `GET` / `POST /biz/merchant/apply` | 已有 | 按 master-data 校验；被退回（REJECTED）才能改 |
| 4-09 | 同意商户协议 | v8 H01b | 店主 | `POST /biz/merchant/agreement/accept` | 已有 | 返回同意时间 |
| 4-10 | 渠道进件提交与补件 | v5 C04、v12 J02 | 店主（`biz:finance`） | `GET /biz/merchant/pay-channel`、`POST /biz/merchant/payment` | 已有 | 结算帐户、帐户类型、证照图片；`NONE` / `REJECTED` / 未提交的 `APPLYING` 才能提交 |
| 4-11 | 门店独立开通收款 | v5 C08 | 店主（`biz:finance`） | `POST /biz/merchant/payment/store/{storeNo}` | 已有 | 默认行不带 `storeNo`，独立门店行才带 |
| 4-12 | 刷新渠道状态 | v12 J04 | 店主（`biz:finance`） | `POST /biz/merchant/payment/{payChannel}/refresh` | 已有 | — |
| 4-13 | 收款 Tab 申请开通（单人审核） | v8 C01–C03、v12 J05 | 店主（`biz:finance`） | `GET /biz/merchant/pay-service`、`POST /biz/merchant/pay-service/apply` | 已联调 / 已有 | 运营审一次后开渠道；`APPROVED` 仍可能在开通中，以 `collect-status` 为准 |
| 4-14 | 资质列表（含平台要求的类目资质） | v23 W07 | 店主 | `GET /biz/qualifications?entityNo=` | 已联调 | 未提供的必需资质列在下方 |
| 4-15 | 新增 / 更新资质 | v23 W07 | 店主 | `POST /biz/upload/image`（`bizType=QUAL`）→ `POST /biz/qualifications/save` | 已有 | 长期有效时 `expireAt=null`；保存后进平台审核 |
| 4-16 | 拍照识别证照 | v23 W07 | 店主 | `POST /biz/qualifications/recognize` | 已有 | 识别不到提示手填；`validTo=长期` 视为长期 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/entities` | 主体列表 | 头 `X-Store-No`（任一自己的店） | `[{entityNo, name, legalForm\|subject, status: APPROVED\|ACTIVE\|APPLYING\|REVIEWING\|PENDING\|REJECTED, taxNo, owner, rejectReason, storeCount}]` | 需 `biz:store:admin` | 已有 |
| `GET /biz/entity/{entityNo}` | 主体详情 | 同上 | `entity{…同上}, stores[{storeNo, name, industryName}]` | 需 `biz:store:admin` | 已有 |
| `GET /biz/merchant/payment` | 渠道进件状态 | 头 `X-Store-No`、`X-Entity-No`；`entityNo?` | `[{payChannel, channelName, applyStatus: NONE\|APPLYING\|ACTIVE\|REJECTED\|FROZEN\|UNAVAILABLE, canReceiveMoney, submitted, missing[settleAccount\|settleAccountType\|licenses], payMerchantNo, subMchidMasked, settleAccountType, settleAccountMasked, settleCycle, rejectReason, appliedAt, activatedAt, storeNo, enabledStores[{storeNo}], signRequired?, signUrl?}]` | `applyStatus / canReceiveMoney / submitted` 缺一 App 判为格式错误 | 已有 |
| `POST /biz/store/{storeNo}/payment` | 本店用某商户号收款 | `payMerchantNo` | `StoreVO` | — | 已有 |
| `POST /biz/store/{storeNo}/entity` | 门店改挂主体 | `entityNo` | — | 后端没有 | 建议 |
| `POST /biz/merchant/payment/bind` | 绑定已有渠道商户号 | `entityNo, payChannel, channelMerchantNo, credential` | — | 后端没有 | 建议 |
| `POST /biz/merchant/onboarding` | 一次提交商户资料 | `entityNo, storeNo, profile{subject: COMPANY\|SOLE\|PERSONAL, name, taxNo, owner, ownerPhone, address, industry}, licenses[{type: COMPANY_REGISTRATION\|BUSINESS_REGISTRATION\|OWNER_ID_FRONT\|OWNER_ID_BACK, imageUrl}], channels[{payChannel, channelMerchantNo?}], settleAccount{bank, account}, agreed` | — | 后端没有 | 建议 |
| `GET /biz/merchant/apply` | 入驻申请进度 | 可带门店头 | `null` 或 `{merchantNo, applyNo, status: PENDING\|REVIEWING\|REJECTED…, name, subject, contactName, contactPhone, industry, serviceScope, communityNos[], licenses[], qualificationItems[], rejectReason, agreedAt, createdAt, auditedAt}` | — | 已有 |
| `POST /biz/merchant/apply` | 提交入驻申请 | 头 `X-Store-No`、`X-Entity-No`；`name, subject, contactName, contactPhone, category, desc, serviceScope, communityNos[], licenses[], asPickupPoint, industry, qualificationItems[{type, code, imageUrl, expireAt, issuer}], entityNo?, storeNo?` | 商户资料（同 `/biz/merchant/profile`） | 10467 申请已变更；App 只放行白名单字段 | 已有 |
| `POST /biz/merchant/agreement/accept` | 同意商户协议 | `{}` | 同意时间（epoch 毫秒整数） | — | 已有 |
| `GET /biz/merchant/pay-channel` | 可进件的渠道 | 门店头 | 同 `/biz/merchant/payment` 的行 | — | 已有 |
| `POST /biz/merchant/payment` | 渠道进件 / 补件 | 门店头；`payChannel, settleAccountType, settleAccount, licenses[], contactName?, contactPhone?, entityNo?, storeNo?`（独立门店才带） | 一行渠道状态 | 70045 渠道不可用；`storeNo` 与门店头不符 App 直接拒 | 已有 |
| `POST /biz/merchant/payment/store/{storeNo}` | 门店独立开通 | `payChannel?` | 一行渠道状态 | — | 已有 |
| `POST /biz/merchant/payment/{payChannel}/refresh` | 刷新渠道状态 | `storeNo?`（独立门店） | 一行渠道状态 | — | 已有 |
| `GET /biz/merchant/pay-service` | 收款开通申请状态 | 头 `X-Store-No` | `status: NONE\|PENDING\|NEED_MORE\|REJECTED\|APPROVED, reviewReason, merchantRef` | 需 `biz:finance` | 已联调 |
| `POST /biz/merchant/pay-service/apply` | 申请开通收款 | 头 `X-Store-No`；`legalName, registrationNo`（统编 8 位）, `representativeName?, contactPhone?, registeredAddress?` | `merchantRef, applicationRef, status, storeLinks` | 10400 资料不合格；10505 暂不可申请；同样资料重复提交幂等 | 已有 |
| `GET /biz/qualifications` | 资质列表 | 门店头；`entityNo` | `items[{qualNo, qualType, qualName, qualNumber, imageUrl, expireAt, status: PENDING\|VALID\|EXPIRED\|REVOKED}], grantedCodes[], catalog[{code, name, requiredQualification, qualType, qualName}]` | — | 已联调 |
| `POST /biz/qualifications/save` | 保存资质 | 门店头；`qualNo?, qualType, qualName, qualNumber, imageUrl?, expireAt?`（epoch 毫秒，长期为 null）, `entityNo` | — | 同类型覆盖 | 已有 |
| `POST /biz/qualifications/recognize` | 证照识别 | 门店头；multipart `file`（png / jpg ≤12 MB） | `recognized, docType, side, name, code, legalForm, person, address, issuedAt, validTo, confidence` | — | 已有 |
| `POST /biz/upload/image` | 上传图片 | 门店头；multipart `file`（png / jpg ≤5 MB）, `bizType: QUAL\|STORE\|GOODS\|REVIEW` | `url, assetKey` | App 先校验文件头与扩展名 | 已有 |

### 待决与注意
- N1–N3（`/biz/store/{s}/entity`、`/biz/merchant/payment/bind`、`/biz/merchant/onboarding`）是 P0（后端清单 3-2），契约以 `merchant_api.dart` 与 `dev/mock/mock_v5.dart` 为准；冻结后从 `contracts.dart` 删对应三行。
- v12 J03 签约用的 `signRequired`、`signUrl` 是 App 推断字段，后端渠道行里没有；没有时「待签约」状态不会出现。`settleCycle` 同样待确认。
- 证照识别 `validTo` 长期值，App 只认简体「长期」；后端如果回繁体或英文要统一。
- 进件有两套：旧的 `/biz/merchant/apply` + `/biz/merchant/payment`（v4 后 / v8 H01b，正式包在用），新的一次提交 N3（v5 C04–C07，等后端）。上线前要定哪一套为准，避免同一主体两份资料。
- 「台北早餐」开通收款在 166 联调中：pay-service 能读，`apply` 未在 166 验证。

---

## 5. 我的、消息与通知

「我的」只放账号维度：个人资料、用户名、登录密码、设置、本店通知、消息、说明与客服、关于、登出与注销。原型 v21 P01–P14。代码在 `features/me`（`me_api.dart`、`me_pages.dart`）。用户名、密码、消息走 ai-shop 现有接口且已在 166 读通；本店通知被 70006 拒；换手机、绑信箱、头像、注销没有接口，按钮停用。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 5-01 | 我的（店主 / 员工两种） | v21 P01、P01b | 全部 | `GET /biz/merchant/profile` | 已联调 | 员工看不到钱、方案与员工管理 |
| 5-02 | 个人资料 | v21 P02 | 全部 | `GET /biz/merchant/profile` | 已联调 | 用户名、手机、邮箱；切换身份见 1-07 |
| 5-03 | 改用户名 | v21 P03 | 全部 | `POST /biz/merchant/display-name` | 已有 | 1–20 字；显示在员工列表、变更记录、交班单 |
| 5-04 | 设置 / 修改登录密码 | v21 P04、v4 S10 | 全部 | `GET` / `POST /biz/auth/password` | 已联调 / 已有 | 至少 6 位；先读是否已有密码决定标题 |
| 5-05 | 更换登录手机 | v21 P05 | 全部 | 无 | — | 页面已有，按钮停用 |
| 5-06 | 绑定电子信箱 | v21 P06 | 全部 | 无 | — | 同上 |
| 5-07 | 设置、语言与显示、清缓存 | v21 P07–P08 | 全部 | 本机 | — | 语言、主题、字号即时生效 |
| 5-08 | 本店通知开关 | v21 P09 | 店主、店长 | `GET` / `PUT /biz/notify/setting` | 已有 | 场景 × 渠道（PUSH / SMS / MAIL）；微信、企业微信台湾不显示 |
| 5-09 | 本店通知收件人 | v21 P09 | 店主、店长 | `PUT /biz/notify/sms-phones`、`PUT /biz/notify/email` | 已有 | 额外手机最多 2 个；店主登录手机永远收，不在列表里 |
| 5-10 | 消息与未读数 | v21 P10 | 全部 | `GET /biz/message`、`GET /biz/message/unread-count` | 已联调 | 订单、营销、系统三类；有门店时带门店头 |
| 5-11 | 标为已读 / 全部已读 | v21 P10 | 全部 | `POST /biz/message/{messageNo}/read`、`POST /biz/message/read-all` | 已有 | 点开即已读 |
| 5-12 | 说明与客服、关于 | v21 P11–P12 | 全部 | 打包参数 | — | 客服联系方式与版本号来自打包参数，空的不显示；回报问题入口见 6-07 |
| 5-13 | 登出确认 | v21 P13 | 全部 | `POST /biz/auth/logout` | 已有 | 提示本机收款提醒会停；接口见 1-15 |
| 5-14 | 注销账号 | v21 P14 | 店主 | 无 | — | 只列注销前须完成的事项，按钮停用 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `POST /biz/merchant/display-name` | 改用户名 | `displayName`（1–20 字） | — | 10400 | 已有 |
| `GET /biz/auth/password` | 是否已设密码 | — | `hasPassword` | — | 已联调 |
| `POST /biz/auth/password` | 设 / 改密码 | `password`（≥6 位） | — | — | 已有 |
| `GET /biz/message` | 消息列表 | 可带头 `X-Store-No` | `[{messageNo, type: TRADE\|MARKETING\|SYSTEM, title, body, bizType, bizNo, read, at}]` | `at` 为 epoch 毫秒；无分页参数 | 已联调 |
| `GET /biz/message/unread-count` | 未读数 | 可带门店头 | 整数 | — | 已联调 |
| `POST /biz/message/{messageNo}/read` | 标为已读 | — | 消息列表（同上） | — | 已有 |
| `POST /biz/message/read-all` | 全部已读 | — | 消息列表（同上） | — | 已有 |
| `GET /biz/notify/setting` | 本店通知设置 | 头 `X-Store-No` | `scenes[{scene: SUB_ORDER_PAID\|AFTER_SALE_APPLIED\|REVIEW_CREATED…, switches{PUSH, SMS, MAIL, WXSUB}}], extraPhones[], ownerPhone, email, wecomReady` | 新模型门店 70006 | 已联调 |
| `PUT /biz/notify/setting` | 切换一个开关 | 门店头；`scene, channel: PUSH\|SMS\|MAIL, enabled` | 同 `GET` | 10400 场景或渠道不存在 | 已有 |
| `PUT /biz/notify/sms-phones` | 设额外短信手机 | 门店头；`phones[]`（整份，≤2） | 同 `GET` | 10400 超过 2 个或格式错 | 已有 |
| `PUT /biz/notify/email` | 设通知邮箱 | 门店头；`email`（空串清除） | 同 `GET` | 10400 格式错 | 已有 |

### 待决与注意
- `/biz/notify/*` 在新模型门店上 70006，与 `/biz/store` 一起登记（后端清单 2-0）。
- 换手机、绑信箱、头像、注销是 P0（后端清单 3-7），目前**连草案都没有**，需要后端先给契约（含验证码二次确认与注销前置检查）。
- 店主用邮箱登录时 `/biz/staff` 回的 `displayName`、`loginPhone` 都是 null，App 已改用当前账号名；本店通知的 `ownerPhone` 对邮箱账号同样可能为空，后端可考虑回 `loginEmail`。
- 消息列表没有分页，消息多了要补 `cursor` / `size`（建议）。

---

## 6. 系统状态

每个页面都可能遇到的状态：收款提醒与播报、通知权限、离线、离线时拦写、草稿恢复、问题回报、版本检查。原型 v22 Y01–Y12。代码在 `features/system`（`payment_alerts.dart`、`system_status.dart`、`form_draft.dart`、`feedback_pages.dart`、`app_version.dart`）。多数是本机逻辑；问题回报与版本检查走未冻结接口（正式包拦下）；推播、语音播报、通知权限插件尚未接。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 6-01 | 这支手机的收款提醒设定 | v22 Y01 | 全部 | 本机 | — | 全部收款或只收银台 / 感应收款；是否语音 |
| 6-02 | 通知权限未开提示 | v22 Y02 | 全部 | 本机 | — | 系统权限端口未接插件，目前判断为「未知」 |
| 6-03 | 收到一笔收款（卡片 + 播报） | v22 Y03 | 全部 | `GET /biz/payment/orders`（每 15 秒，见收款模块） | 已有 | 门店首页开着时轮询；每个「单号 + 状态」只报一次；首次读取只记不报 |
| 6-04 | 推播注册 | v22 Y01–Y03 | 全部 | `POST /biz/push-token` | 已有 | 后端有，App 未接；推播只当「再查一次」的信号 |
| 6-05 | 离线横幅 | v22 Y04 | 全部 | 本机（记录每次请求是否有回应） | — | 置顶「离线中」，点一下重试；内容标「截至」时间 |
| 6-06 | 离线时要收款 / 退款 | v22 Y05 | 全部 | 本机 | — | 钱、审批、进件、权限变更离线一律拒绝，不排队 |
| 6-07 | 恢复草稿 | v22 Y06 | 全部 | 本机 | — | 先做商品表单；草稿带保存时间，存在本机 |
| 6-08 | 回报问题（可附诊断资料） | v22 Y07–Y09 | 全部 | `POST /biz/feedback`、`GET /biz/feedback/mine` | 建议 | 诊断资料是近 30 分钟去敏请求记录，先列给用户看、可关掉 |
| 6-09 | 有新版本 / 需要升级 | v22 Y10–Y11 | 全部 | `GET /app/version` | 建议 | 每次启动登录后查一次；低于最低版本强制升级；只开 App Store / Google Play |
| 6-10 | 本机资料迁移失败 | v22 Y12 | 全部 | — | — | 未做，等有本机迁移时再做 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `POST /biz/push-token` | 注册推播 token | `platform, provider?, clientId`（后端 `RegisterReq`） | — | App 未调用；另有 `POST /biz/push-token/unregister`（`clientId?`） | 已有 |
| `POST /biz/feedback` | 送出问题回报 | `category: BUG\|PAYMENT\|IDEA\|OTHER, description, errorCode?, page?, storeNo?, appVersion, platform, diagnostics?[{at, method, path, outcome}]` | `feedbackNo` | 10400 描述为空；后端没有 | 建议 |
| `GET /biz/feedback/mine` | 我的回报 | — | `[{feedbackNo, description, status: OPEN\|REPLIED, reply, createdAt}]` | `createdAt` 为 ISO-8601；后端没有 | 建议 |
| `GET /app/version` | 版本检查 | `platform: android\|iOS…, build`（当前版本号） | `latest, minSupported, notes[], storeUrl` | 后端没有；读不到时不提示 | 建议 |

### 待决与注意
- 回报与版本检查是 P0（后端清单 3-5）；同时要定「旧版本写入被拒」的错误码，App 收到后直接进 Y11 强制升级（建议）。服务器仍要自己拒绝不支持版本的写入，不能只靠 App 弹窗。
- 收款事件推播内容由支付域定义（后端清单 3-14），建议只带 `paymentNo` + `eventId`，App 收到后照旧读列表决定显示与播报，不信推播里的金额。
- 诊断资料已在 App 端去掉 token、验证码、证件与卡号，只记方法、路径、结果；后端存储时也不要回填敏感字段。回报暂不支持截图。
- 语音播报（TTS）、系统通知权限、推播 SDK 三个插件未接，目前都是空实现：mock 包只记录「本该播报的内容」。

## 7. 收款与收银

收款 Tab（支付订单列表、汇总、查找、详情、退款）、本店收款码、收银台与一次性付款链接，以及现金 / 外部 POS 记账。原型 v6 Q01–Q13、v6 L01–L06、v8 C04–C05、v12 J06–J07；代码在 `features/collect`（`collect_http.dart` 支付订单、`payment_link.dart` 付款链接、`offline_records.dart` 外部收款、`pay_service_apply.dart` 申请开通）。`/biz/payment/*` 在 ai-shop 最新分支都有，并已登记在 `ShopStoreMemberRoutes`（`/biz/payment(?:/.*)?`）；11/08 在 166 读通了 pay-service 和付款链接列表，其余读接口早前联调过，本批做回归。外部收款 `/biz/payment/offline-records*` 后端没有，是建议契约，正式包拦下。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 7-01 | 判断本店是否已开通收款 | v6 Q04 | 全部 | `GET /biz/payment/collect-status` | 已有 | 未开通时不显示空列表，显示开通入口 |
| 7-02 | 查看收款开通申请进度 | v6 Q04、v8 C03 | 店主、店长 | `GET /biz/merchant/pay-service` | 已联调 | NONE / PENDING / NEED_MORE / REJECTED / APPROVED；APPROVED 后仍以 collect-status 为准 |
| 7-03 | 提交收款开通申请 | v8 C01–C02 | 店主 | `POST /biz/merchant/pay-service/apply` | 已有 | 统一编号 8 位数字；同样资料重复提交是幂等的 |
| 7-04 | 按日期查看收款列表（分页） | v6 Q01 | 全部（收款查看权限） | `GET /biz/payment/orders` | 已有 | 今天 / 昨天 / 近 7 天 / 本月 / 自定义；游标分页，每页 50 |
| 7-05 | 按来源、渠道、状态、金额段筛选 | v6 Q02 | 全部 | `GET /biz/payment/orders` | 已有 | 条件可组合；金额段：<100、100–1000、>1000（元） |
| 7-06 | 搜索订单号 / 交易号 | v6 Q01 | 全部 | `GET /biz/payment/orders?q=` | 已有 | `q` 只允许字母、数字、`-`、`*`，其余在 App 端直接提示 |
| 7-07 | 查看时段汇总（按渠道、手续费、预计入账） | v6 Q03 | 全部（对账权限） | `GET /biz/payment/orders/summary` | 已有 | 手续费未对账时标「估算」 |
| 7-08 | 顾客说「我付了」：按金额 + 时间或交易号后 6 位查找 | v6 Q06–Q07 | 全部 | `GET /biz/payment/orders/lookup` | 已有 | 窗口 10 分钟 / 1 小时 / 今天；渠道未回结果的列为「待确认」 |
| 7-09 | 查看收款详情（收款码、刷卡、订单支付、失败、待确认） | v6 Q08–Q12 | 全部 | `GET /biz/payment/orders/{payOrderNo}` | 已有 | 刷卡多卡号、发卡行、授权码、终端；订单支付带业务订单号可点 |
| 7-10 | 待确认的收款再向渠道查一次 | v6 Q12 | 全部 | `POST /biz/payment/orders/{payOrderNo}/query` | 已有 | 待确认时不显示成功也不显示失败，避免重复收钱 |
| 7-11 | 退款（可部分退） | v6 Q13 | 店主、店长（退款权限） | `POST /biz/payment/refund` | 已有 | 金额不能超过可退金额；原因必选；成功后重读详情 |
| 7-12 | 查看 / 生成本店收款码，保存图片 | v6 Q05、v8 C04 | 店主、店长（收款码权限） | `GET /biz/payment/qr-code`、`POST /biz/payment/qr-code` | 已有 | 一店一个固定码；优先显示立牌链接 `storeCodeUrl`，`url`（`/q/…`）作备用 |
| 7-13 | 收款码暂不可用 | v8 C05 | 店主、店长 | 同上 | 已有 | 两个链接都为空时不画假码、按钮停用、不新建码 |
| 7-14 | 收银台输入金额与说明，产生一次性付款链接 | v6 L01–L02 | 全部（代客收款权限） | `POST /biz/payment/collection` | 已有 | 有效期 15 分钟 / 1 小时 / 24 小时；说明最多 40 字 |
| 7-15 | 分享链接（LINE、Messenger、复制、存图） | v6 L03 | 全部 | — | 已有 | 纯本机操作，不读联络人 |
| 7-16 | 等待付款，成功后切到「已收款」 | v6 L04、L04b | 全部 | `GET /biz/payment/orders/{payOrderNo}` | 已有 | 每 3 秒轮询，过期 2 分钟后停止；过期 / 作废时二维码淡化 |
| 7-17 | 付款链接记录（按状态筛选） | v6 L05 | 全部 | `GET /biz/payment/links` | 已联调 | 记录不含链接本身（后端只存 token 哈希）；过期按当下时间判断 |
| 7-18 | 待付款链接再次分享（换新链接） | v6 L05b | 全部 | `POST /biz/payment/links/{payOrderNo}/reissue` | 已有 | 发新 token，旧链接立即失效；只限待付款 |
| 7-19 | 作废付款链接 | v6 L06 | 全部 | `POST /biz/payment/links/{payOrderNo}/void` | 已有 | 后端先查单：作废时已付款的返回 PAID 而不是 VOIDED |
| 7-20 | 记一笔现金 / 外部 POS / 转账收款 | v12 J06 | 全部 | `POST /biz/payment/offline-records` | 建议 | 从收银台底部进入，金额带入；不进拨款、不能退款 |
| 7-21 | 外部收款记录列表；收款列表里带「外部」标签 | v12 J07 | 全部 | `GET /biz/payment/offline-records` | 建议 | 正式包拦下，收款列表不混入外部记录 |
| 7-22 | 更正外部收款（金额、方式） | v12 J07 | 店主、店长 | `POST /biz/payment/offline-records/{recordNo}/correct` | 建议 | 原因必填，留痕 |
| 7-23 | 作废外部收款 | v12 J07 | 店主、店长 | `POST /biz/payment/offline-records/{recordNo}/void` | 建议 | 原因必填，留痕；已作废的再操作回 10409 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/payment/collect-status` | 是否已开通收款、可收的渠道 | 头 `X-Store-No` | `enabled, channels[{channel}]` | App 也兼容 `name` / `code` 作渠道名 | 已有 |
| `GET /biz/merchant/pay-service` | 收款开通申请状态 | 头 `X-Store-No` | `status(NONE/PENDING/NEED_MORE/REJECTED/APPROVED), reviewReason, merchantRef` | 后端在 shop-app `BizPayServiceController` | 已联调 |
| `POST /biz/merchant/pay-service/apply` | 提交开通申请 | 头 `X-Store-No`；`legalName, registrationNo(8 位), representativeName?, contactPhone?, registeredAddress?` | `merchantRef, applicationRef, status, storeLinks[{storeNo,status}]` | 权限 biz:finance；资料不合法 10400 | 已有 |
| `GET /biz/payment/orders` | 收款列表 | 头 `X-Store-No`；`from, to`（yyyy-MM-dd，`to` 含当天）, `source[]?(QR_CODE/POS/SOFTPOS/ORDER/COLLECT)`, `channel[]?`, `status[]?(SUCCEEDED/PENDING/FAILED/PARTIALLY_REFUNDED/REFUNDED)`, `amountMin?, amountMax?`（最小单位）, `q?`, `cursor?`, `size?`(≤50) | `items[{payOrderNo, channel, source, status, amountMinor, currency, refundedMinor, succeededAt, createdAt, payerMasked, method, cardLast4, terminalLabel, bizRef{no}}], nextCursor` | 未知 source / status 显示「其他」；`SOFTPOS` 来源是否新增待确认 | 已有 |
| `GET /biz/payment/orders/summary` | 时段汇总 | 头 `X-Store-No`；`from, to` | `currency, collected{count,amountMinor}, refunded{count,amountMinor}, netMinor, byChannel[{channel,count,amountMinor}], feeMinor, feeEstimated, expectedSettlement{status,amountMinor,expectedDate,accountMasked}` | | 已有 |
| `GET /biz/payment/orders/lookup` | 查找一笔收款 | 头 `X-Store-No`；`tradeNoSuffix`（6 位）或 `amountMinor + within(10m/1h/today)` | `matches[<列表行>], pendingNearby[<列表行>]` | 后缀不足 6 位回 10400 | 已有 |
| `GET /biz/payment/orders/{payOrderNo}` | 收款详情；付款链接轮询也用它 | 头 `X-Store-No` | `row{<列表行>}, amounts{refundedMinor, feeMinor, feeEstimated, feeRateBps}, settlement{status(SETTLED/…), expectedDate, accountMasked}, payer{payerMasked, method, cardBrand, cardLast4, issuer, authCode}, refs{channelTradeNo, channelMerchantNo}, source{terminalSn, operatorNo, tableRef, bizRef{no}}, payerNote, failure{code,message}, timeline[{at,kind}], refunds[{amountMinor, status, reason, requestedBy, createdAt, succeededAt}]` | `timeline.kind` 是事件码，App 翻成文字 | 已有 |
| `POST /biz/payment/orders/{payOrderNo}/query` | 向渠道再查一次 | 头 `X-Store-No`；空体 | 同详情 | | 已有 |
| `POST /biz/payment/refund` | 退款 | 头 `X-Store-No`；`payOrderNo, amountMinor, reason, outRefundNo`（App 生成 `RF`+UUID，防重） | `payRefundNo, status(PROCESSING…)` | App 成功后重读详情 | 已有 |
| `GET /biz/payment/qr-code` | 本店收款码 | 头 `X-Store-No` | `[{qrCode, kind(STORE/…), tableRef, currency, status(ACTIVE/DISABLED), url, storeCodeUrl}]` | App 取第一个 `kind=STORE` 且未停用的 | 已有 |
| `POST /biz/payment/qr-code` | 首次生成本店码 | 头 `X-Store-No`；`kind: STORE` | `url, storeCodeUrl` | 后端还接受 `tableRef, singleLimitMinor, dailyLimitMinor`，App 未用 | 已有 |
| `POST /biz/payment/collection` | 产生一次性付款链接 | 头 `X-Store-No`；`amountMinor, subject, expireMinutes(1–1440)` | `payOrderNo, token, expireAt, checkoutUrl, amountMinor, currency, subject, storeName` | 旧后端少后四个字段时 App 用输入值补 | 已有 |
| `GET /biz/payment/links` | 付款链接记录 | 头 `X-Store-No`；`status?(PENDING/PAID/EXPIRED/VOIDED)`, `cursor?`, `size` | `items[{payOrderNo, amountMinor, currency, subject, status, createdAt, expireAt, paidAt, operatorNo, voidedBy, channel}], nextCursor` | App 也接受直接回数组 | 已联调 |
| `POST /biz/payment/links/{payOrderNo}/reissue` | 再次分享（新 token） | 头 `X-Store-No`；空体 | 同 collection 的回应 | 非待付款回 10409 `LINK_NOT_PENDING` | 已有 |
| `POST /biz/payment/links/{payOrderNo}/void` | 作废链接 | 头 `X-Store-No`；空体（后端可收 `reason?`） | 记录行（见 links） | 已付款返回 `status=PAID`；非待付款回 10409 | 已有 |
| `GET /biz/payment/offline-records` | 外部收款记录 | 头 `X-Store-No` | `[{recordNo, amount{currency,minor}, method(CASH/POS/TRANSFER/OTHER), orderNo, note, recordedBy, recordedAt, status(RECORDED/VOIDED), history[{at,by,text}]}]` | mock 形状 | 建议 |
| `POST /biz/payment/offline-records` | 记一笔 | 头 `X-Store-No`；`amount{currency,minor}, method(CASH/POS/TRANSFER/OTHER), orderNo?, note?, requestId` | 单条记录 | `requestId` 防重复提交 | 建议 |
| `POST /biz/payment/offline-records/{recordNo}/correct` | 更正 | 头 `X-Store-No`；`amount{currency,minor}, method, reason` | 单条记录（history 追加） | 不存在 10404；已作废 10409 | 建议 |
| `POST /biz/payment/offline-records/{recordNo}/void` | 作废 | 头 `X-Store-No`；`reason` | 单条记录（status=VOIDED） | 同上 | 建议 |

### 待决与注意
- 金额：支付订单接口用 `xxxMinor` 整数，按门店 `currencyScale` 换算；外部收款建议契约用 `{currency, minor}`（minor 为字符串），两套写法不同，后端定稿时统一。
- 后端各接口的权限点分别是 `PAY_ORDER_VIEW`（列表、详情、查找、链接记录）、`PAY_STATEMENT`（汇总）、`PAY_REFUND`（退款）、`PAY_QR`（收款码）、`PAY_COLLECT`（收银、链接再分享 / 作废）；收银员角色是否带 `PAY_COLLECT` 要和员工模块的角色模板一致。
- 新收款提醒（v22 Y03）：外壳收到推送后，收款列表在「今天」且空闲时自动重读；推送内容（`paymentNo` + `eventId`）由支付域定义，见清单 3-14。
- `SOFTPOS` 来源：App 已能解析，后端是否新增此来源值待确认（见模块 9）。
- 外部收款（清单 3-4，P0）后端未做；做完后从 `contracts.dart` 删掉 `^/biz/payment/offline-records`。

## 8. 资金

「我的 › 资金」：台湾模式下渠道直接拨款给商户，平台只记录收款与渠道回报的拨款，不经手资金。原型 v11 F01–F07；代码在 `features/funds/funds_pages.dart`。除收款账户（F06）走已有的 `/biz/merchant/payment`，其余 `/biz/funds/*` 后端都没有，是建议契约（mock 形状），正式包拦下。ai-shop 现有 `/biz/settle/*` 是商城订单的平台抽佣结算，与此不是同一件事，没有套用。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 8-01 | 资金总览：本期收款、手续费、已拨款、待拨款、按渠道 | v11 F01 | 店主、有「资金」权限者 | `GET /biz/funds/overview` | 建议 | 员工身份不显示入口；有对账差异时显示差异数 |
| 8-02 | 每日流水（按月，本店或整个商户） | v11 F02 | 同上 | `GET /biz/funds/daily` | 建议 | 每日收款、退款笔数、估算手续费 |
| 8-03 | 点某一天看当天收款明细 | v11 F02 | 同上 | `GET /biz/payment/orders`（from=to=当天） | 已有 | 明细沿用收款 Tab，点进收款详情 |
| 8-04 | 渠道拨款记录，按渠道或「待拨款」筛选 | v11 F03 | 同上 | `GET /biz/funds/payouts` | 建议 | 状态 EXPECTED / PAID / MISMATCH；渠道没回传的只显示预计 |
| 8-05 | 拨款详情：总额、退款、手续费、实拨、账户、费率、笔数 | v11 F04 | 同上 | 取自 `GET /biz/funds/payouts` 的单行 | 建议 | 没有单独的详情接口；手续费是渠道收的，平台分润不出现 |
| 8-06 | 看拨款期间内的交易 | v11 F04 | 同上 | `GET /biz/payment/orders`（from/to 取拨款期间） | 已有 | |
| 8-07 | 对账：平台笔数与渠道帐单笔数、差异列表 | v11 F05 | 同上 | `GET /biz/funds/reconcile` | 建议 | 差异只展示，不在 App 处理 |
| 8-08 | 收款账户（按渠道，只读）与变更说明 | v11 F06 | 同上 | `GET /biz/merchant/payment?entityNo=` | 已有 | 变更走渠道流程，App 只给步骤说明或跳 v12 J01 渠道页 |
| 8-09 | 渠道费率与拨款周期（只读） | v11 F07 | 同上 | `GET /biz/funds/rates` | 建议 | 用于 F01–F04 估算手续费 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/funds/overview` | 资金总览 | 头 `X-Store-No` | `collected{currency,minor}, count, fee{…}, paidOut{…}, pending{…}, diffCount, channels[{code, name, collected{…}, pending{…}, cycle}]` | mock 形状；`cycle` 如 `T+3` | 建议 |
| `GET /biz/funds/daily` | 每日流水 | 头 `X-Store-No`；`month?`(yyyy-MM), `scope: STORE|MERCHANT` | `[{day, date(yyyy-MM-dd), count, refundCount, amount{currency,minor}, fee{currency,minor}}]` | 可评估改用现有 `GET /biz/payment/statement`，其结构待查 | 建议 |
| `GET /biz/funds/payouts` | 拨款记录 | 头 `X-Store-No` | `[{payoutNo, channelName, channelCode, period, status(EXPECTED/PAID/MISMATCH), amount{…}, gross{…}, refunds{…}, fee{…}, feeRate, count, accountMasked, date, from, to}]` | `date` 是展示文字（如「預計 11/10」）；未分页 | 建议 |
| `GET /biz/funds/reconcile` | 对账 | 头 `X-Store-No` | `platformCount, channelCount, diffs[{title, detail, status}]` | `status` 为展示文字 | 建议 |
| `GET /biz/merchant/payment` | 各渠道收款账户 | 头 `X-Store-No`；`entityNo` | `[{payChannel, channelName, applyStatus(ACTIVE/APPLYING/REJECTED/FROZEN/UNAVAILABLE), canReceiveMoney, settleAccountMasked, settleCycle, payMerchantNo, activatedAt, rejectReason, enabledStores[{storeNo}]}]` | App 固定列 NEWEBPAY、LINEPAY、JKOPAY（JKOPAY 恒为「即将推出」） | 已有 |
| `GET /biz/funds/rates` | 渠道费率 | 头 `X-Store-No` | `[{name, rate, cycle}]` | `rate` 为展示文字（如 `2.30%`） | 建议 |

### 待决与注意
- 最大前提待确认：各渠道是否回传拨款帐单（清单 3-3）。没有回传时 F03–F05 只能显示「预计」，对账也做不了。
- 建议契约里 `date`、`period`、`rate`、对账 `status` 都是展示文字，后端定稿时建议改为结构化字段（日期、bps、枚举），App 再格式化。
- 金额统一用 `{currency, minor}`（minor 字符串）。
- 「本店 / 整个商户」切换（`scope=MERCHANT`）需要跨店汇总权限，后端要按商户主体校验。
- 上线后从 `contracts.dart` 删掉 `^/biz/funds/`。

## 9. 感应收款

蓝新 SoftPOS：手机当感应刷卡机。开通（T01–T03）、设备管理（T12）、收银台感应收款与结果查询（T04、T05、T07）。原型 v13；代码在 `features/softpos`（`softpos_pages.dart` 页面与 API，`softpos_terminal.dart` 读卡接口）。`/biz/softpos/*` 后端没有，全部是建议契约，正式包拦下；读卡本身抽象成 `SoftPosTerminal`，正式包用 `UnavailableSoftPosTerminal`（收银台不显示感应收款），mock 包 2 秒后模拟成功。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 9-01 | 介绍与开通前提（蓝新未开通时去开通） | v13 T01 | 店主、店长 | `GET /biz/merchant/payment?entityNo=`（看 NEWEBPAY 状态） | 已有 | 蓝新未开通时引导到 v12 J01 |
| 9-02 | 本机检测（NFC、系统版本、安全性） | v13 T02 | 全部 | —（SDK） | 建议 | 检查项以蓝新 SDK 为准；不过时给替代方式 |
| 9-03 | 用这支手机申请开通 | v13 T03 | 全部 | `POST /biz/softpos/devices` | 建议 | 是否审核、是否一机一终端号待蓝新确认 |
| 9-04 | 设备列表（店主看全部、员工只看自己） | v13 T12 | 全部 | `GET /biz/softpos/devices` | 建议 | 状态：审核中 / 已开通 / 已退回 / 已解除 |
| 9-05 | 解除遗失手机 | v13 T12 | 店主 | `POST /biz/softpos/devices/{deviceId}/unbind` | 建议 | |
| 9-06 | 收银台输入金额后感应收款 | v13 T04–T05 | 全部（收银） | —（SDK） | 建议 | 只有已开通的手机显示；逾时或取消不扣款 |
| 9-07 | 输入密码、电子签单 | v13 T06、T08 | 全部 | —（SDK） | 建议 | 由蓝新 SDK 画面提供，App 不接触密码；未做 |
| 9-08 | 成功后先回报给支付域，让收款列表先看到 | v13 T07 | 全部 | `POST /biz/softpos/charges` | 建议 | 以 `txnNo` 去重；正式入账仍以蓝新回调为准 |
| 9-09 | 结果不明时按原交易号查单（不重扣） | v13 T07 | 全部 | `GET /biz/softpos/charges/{txnNo}` | 建议 | 成功 / 失败 / 确认中三种 |
| 9-10 | 收款列表中的感应交易 | v13 T09 | 全部 | `GET /biz/payment/orders?source=SOFTPOS` | 待确认 | 来源值 `SOFTPOS` 是否新增待后端确认 |
| 9-11 | 取消 / 退款 | v13 T10 | 店主、店长（退款权限） | `POST /biz/payment/refund` | 已有 | 沿用收款详情退款；当天取消与隔天退款是否不同待蓝新确认 |
| 9-12 | 批次结帐 | v13 T11 | 店主、店长 | — | 建议 | 只有蓝新要求时才做；未做 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/softpos/devices` | 设备列表 | 头 `X-Store-No` | `[{deviceId, deviceName, holder, status(REVIEWING/ACTIVE/REJECTED/UNBOUND), note}]` | 未知状态按审核中显示 | 建议 |
| `POST /biz/softpos/devices` | 申请开通本机 | 头 `X-Store-No`；`deviceName` | 单个设备（同上） | | 建议 |
| `POST /biz/softpos/devices/{deviceId}/unbind` | 解除手机 | 头 `X-Store-No` | 成功即可 | 不存在 10404 | 建议 |
| `GET /biz/softpos/charges/{txnNo}` | 查一笔感应交易结果 | 头 `X-Store-No` | `status(SUCCEEDED/FAILED/NOT_CHARGED/其他=确认中), cardBrand, last4, authCode, reason` | 查询不会再扣款 | 建议 |
| `POST /biz/softpos/charges` | 回报一笔感应成功 | 头 `X-Store-No`；`deviceId, txnNo, amount{currency,minor}, card`（如 `VISA ****1234`）`, authCode` | 成功即可 | 以 `txnNo` 幂等 | 建议 |

### 待决与注意
- 对接方式未定：嵌 SDK、跳转「藍新簡單收」再回来，或只做开通引导（清单 3-10，v13 第四节 8 个问题）。定了以后只需实现新的 `SoftPosTerminal`，页面不改。
- 目前只考虑 Android；iOS 视为不支持。
- 回报接口与蓝新回调可能先后到达，支付域需按 `txnNo` 合并成一笔支付订单。
- 上线后从 `contracts.dart` 删掉 `^/biz/softpos/`。

## 10. 列印

列印设备、新增飞鹅云打印机、测试列印、自动出单（按场景）、单台打印机的排队与改印、结果不明的单。原型 v23 W20–W25；代码在 `features/store_profile/print_pages.dart`，API 在 `store_profile_api.dart` 的 `PrintApi`。路径在 ai-shop 最新分支都有（`BizPrintDeviceController`、`BizPrintJobCommandController`、`BizPrintJobQueryController`、`BizPrintSceneController`、`BizPrintMonitorController`），11/08 已登记，但 166 没开 `shop.print.enabled`，全部 404；另外多处请求 / 回应字段与后端 DTO 不一致（见下），属于清单 2-7。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 10-01 | 打印机列表：在线、离线、缺纸、暂停、待印数 | v23 W20 | 店主、店长 | `GET /biz/print/printers` | 待确认 | |
| 10-02 | 首页 / 列印页的异常提示（离线打印机、结果不明单数） | v23 W20、W24 | 店主、店长 | `GET /biz/print/health` | 待确认 | 回应字段与后端不一致 |
| 10-03 | 新增飞鹅云打印机（名称、SN、user、ukey、58/80mm） | v23 W21 | 店主、店长 | `POST /biz/print/printers` | 待确认 | SN 可扫码；金钥只写不读；带幂等键 |
| 10-04 | 测试列印 | v23 W22 | 店主、店长 | `POST /biz/print/printers/{printerNo}/test-jobs` | 待确认 | 最多等 2 分钟回报；逾时请店家自己看 |
| 10-05 | 改纸宽 | v23 W22 | 店主、店长 | `PUT /biz/print/printers/{printerNo}` | 待确认 | 测试印出版面不对时切换 58 / 80 |
| 10-06 | 设定自动出单：每个场景开关、打印机、份数 | v23 W23 | 店主、店长 | `GET /biz/print/scene-policies`、`PUT /biz/print/scene-policies/{scene}` | 待确认 | 按行业只列相关场景 |
| 10-07 | 单台打印机：看排队、暂停 / 恢复 | v23 W24 | 店主、店长 | `GET /biz/print/jobs`、`PUT /biz/print/printers/{printerNo}` | 待确认 | 暂停走的接口与后端不一致 |
| 10-08 | 把排队中的单改到另一台打印 | v23 W24 | 店主、店长 | `POST /biz/print/jobs/{jobNo}/reprints` | 待确认 | 逐张改印；字段名与后端不一致 |
| 10-09 | 出单记录，结果不明的排最前 | v23 W25 | 店主、店长 | `GET /biz/print/jobs` | 待确认 | 后端回分页结构 |
| 10-10 | 结果不明的单：先向厂商查结果 | v23 W25 | 店长以上（`biz:print:resolve`） | `POST /biz/print/jobs/{jobNo}/result-queries` | 待确认 | 任何补印前都要先查 |
| 10-11 | 结果不明的单：确认已印出 | v23 W25 | 店长以上 | `POST /biz/print/jobs/{jobNo}/manual-confirmations` | 待确认 | |
| 10-12 | 结果不明的单：勾选「可能重复」后补印 | v23 W25 | 店长以上 | `POST /biz/print/jobs/{jobNo}/reprints` | 待确认 | `riskAcknowledged: true`，没勾回 82001 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/print/printers` | 打印机列表 | 头 `X-Store-No`；`storeNo`（后端还可 `status?, healthStatus?, connectionType?, page?, size?`） | App 读数组 `[{printerNo, name, connectionType(CLOUD/LOCAL), paperWidthMm, status(ACTIVE/PAUSED), healthStatus(ONLINE/OFFLINE/PAPER_OUT/…), pendingCount, deviceSn, revision}]` | 后端回 `PageData{records,total,page,size}`，`PrinterVO` 没有 `pendingCount`、`deviceSn`（在 `PrinterDetailVO`）；App 需拆 `records` | 待确认 |
| `POST /biz/print/printers` | 新增云打印机 | 头 `X-Store-No`、`Idempotency-Key`；`storeNo, name, paperWidthMm(58/80), connection{type: CLOUD, adapterCode: FEIE, deviceSn, credentials{user, ukey}}` | App 读 `printerNo` | 后端回 `PrintOperation{operationNo, type, status, targetType, targetNo, result, error}`，`printerNo` 应取 `targetNo`（待确认） | 待确认 |
| `PUT /biz/print/printers/{printerNo}` | 改名称 / 纸宽 | 头 `X-Store-No`；`expectedRevision, name?, paperWidthMm?` | 成功即可 | revision 不符 82002 | 待确认 |
| `PUT /biz/print/printers/{printerNo}`（暂停） | 暂停 / 恢复 | App 送 `expectedRevision, status(ACTIVE/PAUSED)` | 成功即可 | 后端 `UpdatePrinterReq` 没有 `status`；应改走 `POST /biz/print/printers/{printerNo}/status-changes {expectedRevision, status, reason}` | 待确认 |
| `POST /biz/print/printers/{printerNo}/test-jobs` | 测试列印 | 头 `X-Store-No`；`expectedRevision, testPattern: STANDARD` | App 读 `status`（COMPLETED 为成功）、`reason`（如 PAPER_OUT） | 后端回 `PrintOperation`，成功与原因要从 `status` / `error` 取，映射待确认 | 待确认 |
| `GET /biz/print/health` | 异常概况 | 头 `X-Store-No` | App 读 `issues[<打印机>], unknownJobs` | 后端 `GetHealthVO{activePrinters, pendingJobs, unknownJobs, blockedRequests, asOf}`，没有 `issues[]`；可改为用 printers 列表按 `healthStatus` 自算 | 待确认 |
| `GET /biz/print/jobs` | 出单记录 / 排队 | 头 `X-Store-No`（后端可 `storeNo?, scene?, status?, printerNo?, from?, to?, page?, size?`） | App 读数组 `[{jobNo, printerNo, scene, title, status(PENDING/DISPATCHING/RETRY_WAIT/COMPLETED/UNKNOWN/FAILED/CANCELLED), createdAt}]` | 后端回 `PageData<PrintJobVO>`；`PrintJobVO` 无 `title`（有 `orderNo, subOrderNo, revision, allowedActions, blockers`）；App 需拆 `records`、改用订单号作标题 | 待确认 |
| `POST /biz/print/jobs/{jobNo}/result-queries` | 向厂商查结果 | 头 `X-Store-No`；App 送空体 | App 按 `PrintJob` 解析 | 后端要 `{expectedRevision}`，回 `PrintOperation` | 待确认 |
| `POST /biz/print/jobs/{jobNo}/manual-confirmations` | 确认已印出 | 头 `X-Store-No`；App 送空体 | App 按 `PrintJob` 解析 | 后端 `ConfirmManuallyReq{expectedRevision, outcome, reason, evidenceRef, queueTerminatedConfirmed}` | 待确认 |
| `POST /biz/print/jobs/{jobNo}/reprints` | 补印 / 改印 | 头 `X-Store-No`；App 送 `printerNo?, riskAcknowledged?` | App 按 `PrintJob` 解析 | 后端 `ReprintReq{expectedRevision, reason, targetPrinterNo, riskAcknowledged}`：字段名是 `targetPrinterNo`；结果不明未勾确认 82001 | 待确认 |
| `GET /biz/print/scene-policies` | 自动出单设定 | 头 `X-Store-No`（后端 `storeNo`） | App 读 `[{scene, enabled, printerNo, copies(1–10)}]` | 后端 `ScenePolicyVO{policyNo, scene, enabled, policy, revision}`，打印机 / 份数在 `policy` 里（路由规则 `targets[{printerNo, copies}]` 或 `fallbackPrinterNo`） | 待确认 |
| `PUT /biz/print/scene-policies/{scene}` | 改某场景 | 头 `X-Store-No`；App 送 `enabled?, printerNo?, copies?` | 单个场景策略 | 后端 `SaveScenePolicyReq{storeNo, expectedRevision, policy{enabled, critical, triggerTypes, fallbackPrinterNo, …}}` | 待确认 |

场景值：`KITCHEN, KITCHEN_ADD, KITCHEN_VOID, CHECKOUT`（餐饮）、`CASHIER_RECEIPT, REFUND_RECEIPT`（收银）、`TAKEOUT_PACKING, PICKING, DELIVERY_HANDOVER`（零售）、`APPT_CONFIRM, SERVICE_COMPLETE`（服务）。餐饮门店列餐饮 + 收银 + 零售，美业列服务 + 收银，零售列收银 + 零售。

### 待决与注意
- 先决：`/biz/print/*` 在 `ShopStoreMemberRoutes` 登记（清单 2-0），否则 166 上全部 70006。
- 字段对齐（清单 1-11、2-7）：App 是按 mock 写的，和后端 DTO 有多处差异——列表分页 `PageData.records`、暂停走 `status-changes`、改印用 `targetPrinterNo` 且需 `expectedRevision` 与 `reason`、命令接口都回 `PrintOperation` 而非作业本身、`health` 没有 `issues`、场景策略的打印机 / 份数在 `policy` 结构里。登记后要逐条改 App 解析，或请后端出一层面向 App 的视图。
- 错误码：82001 = 结果不明未确认风险（被拦）、82002 = revision 已变（别人改过，需重读）、82003 = 打印机不可用；App 已有对应文案。
- 绝不能误印两次：结果不明的单必须先 `result-queries`，再由店长选「已印出」或勾选风险后补印；权限 `biz:print:resolve`。
- 后端另有 App 未用的能力：本地打印代理 `/biz/print/agents*`、批量改印 `POST /biz/print/reprints`、重试 / 取消 / 加印、`/uncertainty-resolutions`、导出 `/biz/print/exports*`；W24「全部改到另一台」可考虑改用批量改印。

## 11. 商品

零售商品的列表、新建与编辑、草稿与送审、上线后改动的发布与版本、上下架、改库存与本店价，以及门店设定里的货架分类、规格库、SKU 识别码和门店代码。对应原型 v6 G01–G08、v14 G10–G15；代码在 `features/goods`（`goods_api.dart`）与 `features/standard_onboarding/store_admin_pages.dart`（`StoreAdminApi`）。全部走 ai-shop 已有接口，`/biz/goods*`、`/biz/sku-identity*`、规格与类目都已在 `ShopStoreMemberRoutes` 登记；166 上读接口已通，写接口未在 166 验证。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 11-01 | 商品列表，按状态、分类分组，按关键字搜 | v6 G01–G02 | 店主、店长 | `GET /biz/goods` | 已联调 | 分组数靠每个状态各查一次 `size=1` 读 `total`，后端没有计数接口 |
| 11-02 | 列表上看每件商品的库存方式 | v6 G01 | 店主、店长 | `GET /biz/goods/inv-mode` | 已有 | `managed=true` 时库存归进销存，列表上不能直接改 |
| 11-03 | 改本店库存（一次一个 SKU） | v6 G03 | 店主、店长 | `POST /biz/goods/{goodsNo}/store-stock` | 已有 | 进销存管理的商品不走这里，要改得去盘点 |
| 11-04 | 设本店价（一次一个 SKU，清空则跟基础价） | v6 G04 | 店主、店长 | `POST /biz/goods/{goodsNo}/store-price` | 已有 | `price: null` 表示跟基础价 |
| 11-05 | 新增 / 编辑商品并存草稿 | v6 G05、v14 G10 | 店主、店长 | `POST /biz/goods/save` | 已有 | 缺标题、分类或价格时，页面显示「还差：…」 |
| 11-06 | 一段文字解析出规格与价格 | v6 G05 | 店主、店长 | `POST /biz/goods/parse-text` | 已有 | 解析后 `entrySource=QUICK_TEXT`，可以撤回 |
| 11-07 | 拍照识别商品 | v6 G06 | 店主、店长 | `POST /biz/goods/recognize` | 已有 | 回填标题、副标题、分类，`entrySource=IMAGE` |
| 11-08 | AI 写商品详情 | v6 G05 | 店主、店长 | `POST /biz/goods/describe` | 已有 | |
| 11-09 | 设可用付款方式（线上 / 到店付） | v6 G05 | 店主、店长 | `GET/PUT /biz/goods/{goodsNo}/pay-mode` | 已有 | 存草稿后另调一次 |
| 11-10 | 未上线商品送审 | v6 G05 | 店主、店长 | `POST /biz/goods/{goodsNo}/submit` | 已有 | 状态：DRAFT → PENDING（审核中）→ ON_SALE / REJECTED |
| 11-11 | 已上线商品的草稿：读取、放弃 | v6 G07 | 店主、店长 | `GET /biz/goods/{goodsNo}/draft`、`POST …/draft/discard` | 已有 | 上线商品改动先进草稿，线上版本不变 |
| 11-12 | 发布前看改了什么，确认后发布 | v6 G07、v14 G15 | 店主、店长 | `GET /biz/goods/{goodsNo}/publish-preview`、`POST …/publish` | 已有 | 改价时先列出每个 SKU 的「原价 → 新价」让用户确认；`stale=true` 时要重读 |
| 11-13 | 版本记录、版本详情、从旧版本另开草稿 | v6 G08 | 店主、店长 | `GET /biz/goods/{goodsNo}/revisions`、`…/revisions/{revisionNo}`、`POST …/fork` | 已有 | 只有 `canFork=true` 的版本能另开 |
| 11-14 | 上架 / 下架（单件） | v6 G04 | 店主、店长 | `POST /biz/goods/{goodsNo}/toggle` | 已有 | |
| 11-15 | 批量上下架 | v14 G14 | 店主、店长 | `POST /biz/goods/{goodsNo}/toggle`（每件一次） | 已有 | 部分失败时逐件列出商品名和原因 |
| 11-16 | 货架分类：加入、改名、排序、移除 | v14 G11 | 店主、店长 | `GET/POST /biz/store/{storeNo}/categories`、`GET /biz/category/tree` | 已联调（读）/ 已有（写） | 整份替换；还有商品的分类不能移除 |
| 11-17 | 规格库：查看、新增规格、加规格值、改名、停用 / 恢复 | v14 G12 | 店主、店长 | `GET /biz/my-spec-dims`、`POST /biz/spec-dims`、`POST /biz/spec-values`、`POST /biz/my-spec-dims/{dimNo}/rename\|archive` | 已联调（读）/ 已有（写） | 有用量上限 `dimUsed / dimQuota` |
| 11-18 | SKU 识别码：列表、导出 csv、绑定条码 | v14 G13 | 店主、店长 | `GET /biz/sku-identity/export`、`POST /biz/sku-identity/barcode` | 已联调（读）/ 已有（写） | 条码在店内唯一；目前手动输入 |
| 11-19 | 门店代码（公开链接 `/s/<slug>`） | v9 R05 | 店主 | `POST /biz/store/{storeNo}/slug` | 已有 | 传空串清掉，回落系统代码 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/goods` | 商品列表 | 头 `X-Store-No`；query `status?: ON_SALE\|DRAFT\|PENDING\|REJECTED\|OFF_SALE`、`keyword?`、`categoryNo?`、`page`、`size` | `PageData{records[GoodsVO], total}`；GoodsVO 见 `GET /biz/goods/{goodsNo}` | | 已联调 |
| `GET /biz/goods/inv-mode` | 批量读库存方式 | query `goodsNos`（逗号分隔） | `[{goodsNo, mode, managed}]`（`mode=OFF` 表示不记库存） | | 已有 |
| `GET /biz/goods/{goodsNo}` | 商品详情 | 头 `X-Store-No` | `goodsNo, title, subtitle, cover, images[], detail, categoryNo, fulfillments[](STORE_PICKUP\|MERCHANT_DELIVERY\|EXPRESS), limitPerUser, saleMode(NORMAL\|ACTIVITY_ONLY), specGroups[{name, options[]}], skus[{skuNo, optionValues[], price, originPrice, costPrice, stock, storePrice, barcode, merchantSkuCode, saleUnit, nominalGram}], status(DRAFT\|PENDING\|REJECTED\|ON_SALE\|OFF_SALE), onSale, auditReason, hasDraft, freightTemplateNo, entrySource` | 金额为最小单位整数；库里的 AUDITING 对外叫 PENDING | 已有 |
| `GET /biz/goods/{goodsNo}/draft` | 已上线商品的未发布草稿 | — | GoodsVO 或 `null` | | 已有 |
| `POST /biz/goods/save` | 新建 / 保存草稿 | `goodsNo?, title, subtitle, categoryNo, cover, images[], specGroups[{name, options[]}], skus[{skuNo?, optionValues[], price, stock, originPrice?, costPrice?, nominalGram?, barcode, merchantSkuCode, saleUnit}], fulfillments[], limitPerUser?, detail, saleMode, freightTemplateNo, entrySource(MANUAL\|QUICK_TEXT\|IMAGE)` | GoodsVO | `cover` 为空时取第一张图 | 已有 |
| `POST /biz/goods/{goodsNo}/submit` | 送审 | `{}` | GoodsVO | | 已有 |
| `POST /biz/goods/{goodsNo}/toggle` | 上 / 下架 | `onSale: bool` | GoodsVO | 审核中等状态会被拒，批量时逐件记原因 | 已有 |
| `POST /biz/goods/{goodsNo}/store-stock` | 改本店库存 | `skuNo, stock` | GoodsVO | | 已有 |
| `POST /biz/goods/{goodsNo}/store-price` | 设本店价 | `skuNo, price?`（`null` 跟基础价） | GoodsVO | | 已有 |
| `GET /biz/goods/{goodsNo}/pay-mode` | 读付款方式 | — | `payModes[](ONLINE\|OFFLINE)` | | 已有 |
| `PUT /biz/goods/{goodsNo}/pay-mode` | 设付款方式 | `payModes[](ONLINE\|OFFLINE)` | `payModes[]` | App 总是带 `ONLINE` | 已有 |
| `POST /biz/goods/parse-text` | 文字解析 | `text, categoryNo?` | `title, specs[{name, options[]}], pricesMinor[]` | App 只取第一个规格 | 已有 |
| `POST /biz/goods/recognize` | 拍照识别 | `imageUrl` | `title, subtitle, categoryNo` | | 已有 |
| `POST /biz/goods/describe` | 生成详情 | `imageUrl, title, subtitle, categoryNo` | `detail` | | 已有 |
| `GET /biz/goods/{goodsNo}/publish-preview` | 发布预览 | — | `changes[{field, label, before, after}], blocked[], stale, baseVersion` | `blocked` 非空时不能发布 | 已有 |
| `POST /biz/goods/{goodsNo}/publish` | 发布草稿 | `confirmVersion?`（= 预览的 `baseVersion`） | GoodsVO | 版本对不上要重读预览 | 已有 |
| `POST /biz/goods/{goodsNo}/draft/discard` | 放弃草稿 | `{}` | GoodsVO | | 已有 |
| `GET /biz/goods/{goodsNo}/revisions` | 版本列表 | — | `[{revisionNo, status, changeSummary, savedBy, savedAt, publishedAt, rejectReason, entrySource, canFork, changesVsOnline[{field, label, before, after}]}]` | 有的版本给的是 `changesFromPrev`，App 两个都读 | 已有 |
| `GET /biz/goods/{goodsNo}/revisions/{revisionNo}` | 版本详情 | — | 同上单条 | | 已有 |
| `POST /biz/goods/{goodsNo}/revisions/{revisionNo}/fork` | 从旧版本另开草稿 | `{}` | 版本对象 | | 已有 |
| `GET /biz/store/{storeNo}/categories` | 本店货架分类 | 头 `X-Store-No` | `[{categoryNo, name, displayName, goodsCount}]` | | 已联调 |
| `POST /biz/store/{storeNo}/categories` | 整份替换货架分类 | `items[{categoryNo, displayName, sort}]` | 成功即可 | 移除还有商品的分类会被拒（mock 10409） | 已有 |
| `GET /biz/category/tree` | 平台类目（顶层） | — | `[{categoryNo, name}]` | | 已联调 |
| `GET /biz/my-spec-dims` | 我的规格库 | — | `[{dimNo, name, valueCount, usedCount, status(ACTIVE\|ARCHIVED), dimUsed, dimQuota, values[{label}]}]` | | 已联调 |
| `POST /biz/spec-dims` | 新增规格 | `name, labels[], usageType: SPEC` | 成功即可 | 超出 `dimQuota` 会被拒 | 已有 |
| `POST /biz/spec-values` | 加规格值 | `dimNo, label` | 成功即可 | | 已有 |
| `POST /biz/my-spec-dims/{dimNo}/rename` | 规格改名 | `name` | 成功即可 | | 已有 |
| `POST /biz/my-spec-dims/{dimNo}/archive` | 停用 / 恢复 | `archived: bool` | 成功即可 | | 已有 |
| `GET /biz/sku-identity/export` | SKU 识别码 | — | `{csv}`：带 BOM，表头 `skuNo, goods, spec, barcode, code, unit` | 列表和导出用同一个接口，App 自己解析 csv | 已联调 |
| `POST /biz/sku-identity/barcode` | 绑定条码 | `skuNo, barcode` | 成功即可 | 幂等；同店已被别的 SKU 占用会被拒（mock 10409） | 已有 |
| `POST /biz/store/{storeNo}/slug` | 设门店代码 | `slug`（空串清掉） | 成功即可（mock 回 `{storeNo, slug}`） | 权限 STORE_ADMIN | 已有 |

### 待决与注意
- 分组计数每次要发 5 个 `size=1` 的请求，建议后端出一个按状态计数的接口。
- `GoodsVO` 还缺送审时间（G02）；新增商品按条码查标准品要 `/biz/spu-std` 支持条码（G10）。都已列在对接清单 2-9。
- 改价确认（G15）只在 App 端比对加载时的价格；对历史订单的影响说明靠 `publish-preview` 的字段差异，后端没有单独的改价接口。
- 批量上下架没有批量接口，每件一次 `toggle`。
- 运费模板（`GET /biz/store/{storeNo}/freight-template`）在商品编辑的发货设置里也会读，接口见第 12 节。
- ai-shop 还有 App 没接的商品接口：`/biz/goods/zip-plan`、`zip-import`、`{goodsNo}/presale`、`{goodsNo}/stock`、`skus/{skuNo}/pricing-policy`、`/biz/sku-identity/import(/plan)`。

## 12. 订单与履约

零售商品订单的列表、待办、详情，备货、发货（填运单 / 叫快递）、送达、确认线下收款、改履约方式、物流轨迹与改运单；售后审批、收退货、确认退款；发货设置与运费模板；自提点核销、批量核销与分拣。对应原型 v6 R01–R09、S01，v15 R10–R13；代码在 `features/store_orders`（`store_orders_api.dart`、`fulfillment_pages.dart`）。`/biz/order*`、`/biz/after-sale*`、发货设置与运费模板已登记并在 166 读通；`/biz/pickup/*` 在 166 被 70006 拒，等后端登记。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 12-01 | 订单列表（只看零售），按状态、履约方式筛 | v6 R01 | 全部 | `GET /biz/order` | 已联调 | 固定带 `caller=RETAIL`，老订单也会带出 |
| 12-02 | 订单待办（最急的排前） | v15 R13 | 全部 | `GET /biz/order/worklist` | 已联调 | 待办类型：售后、确认收款、履约、核销 |
| 12-03 | 订单详情（顾客、付款、操作记录、发票） | v6 R02 | 全部 | `GET /biz/order/{orderNo}/full` | 已有 | 付款状态和履约进度分开显示，「已付款」不代表「已完成」 |
| 12-04 | 备货 | v6 R02 | 全部 | `POST /biz/order/{orderNo}/prepare` | 已有 | |
| 12-05 | 填运单发货（先检查运单） | v6 R03 | 店主、店长 | `POST /biz/order/{orderNo}/ship/precheck`、`POST …/ship` | 已有 | INVALID 拦下；运单以前出现过时，要确认是同一个包裹才能发 |
| 12-06 | 叫快递上门：报价、下单、看取件单、取消 | v15 R10 | 店主、店长 | `GET /biz/order/{orderNo}/express/quotes`、`POST/GET …/express`、`POST …/express/cancel` | 已有 | 报价里带承运商代码，所以不依赖承运商列表接口；只有待接单 / 已接单时能取消 |
| 12-07 | 自送订单标记送达 | v6 R02 | 全部 | `POST /biz/order/{orderNo}/delivered` | 已有 | |
| 12-08 | 确认线下收款 | v6 R04 | 店主、店长 | `POST /biz/order/{orderNo}/confirm-offline-pay` | 已有 | 用于 WAIT_OFFLINE_PAY 订单 |
| 12-09 | 改履约方式 | v6 R05 | 店主、店长 | `GET /biz/order/{orderNo}/fulfillment-options`、`POST …/fulfillment-change` | 已有 | 只列 `allowed` 不为 false 的方式；要填原因 |
| 12-10 | 物流轨迹、手动刷新、同步记录 | v6 R06 | 全部 | `GET /biz/order/{orderNo}/trace`、`GET …/logistics-updates` | 已有 | `refreshable=true` 时才显示刷新 |
| 12-11 | 运单填错了，改运单 | v6 R06 | 店主、店长 | `POST /biz/order/{orderNo}/waybill` | 已有 | 原因保留在记录里 |
| 12-12 | 售后列表（待处理 / 全部） | v6 R07 | 店主、店长 | `GET /biz/after-sale/worklist` | 已联调 | |
| 12-13 | 售后详情 | v6 R08 | 店主、店长 | `GET /biz/after-sale/{afterSaleNo}` | 已有 | |
| 12-14 | 同意 / 拒绝售后 | v6 R08 | 店主、店长 | `POST /biz/after-sale/{afterSaleNo}/approve\|reject` | 已有 | 同意时退款金额等于申请金额，退款方式固定 CASH |
| 12-15 | 确认收到退货（逐行填数量与品相） | v6 R09 | 店主、店长 | `POST /biz/after-sale/{afterSaleNo}/receive` | 已有 | 可选择是否回库存 |
| 12-16 | 确认退款 | v6 R09 | 店主、店长 | `POST /biz/after-sale/{afterSaleNo}/confirm-refund` | 已有 | |
| 12-17 | 发货设置（寄件人、地址、默认承运商、默认重量） | v6 S01 | 店主、店长 | `GET/PUT /biz/store/{storeNo}/ship-setting` | 已联调（读）/ 已有（写） | |
| 12-18 | 看运费模板 | v6 S01 | 店主、店长 | `GET /biz/store/{storeNo}/freight-template` | 已联调 | App 只读 |
| 12-19 | 自提点总览、自提订单、分拣单 | v15 R12 | 全部 | `GET /biz/pickup/overview`、`/orders`、`/picking` | 已有 | |
| 12-20 | 标记到货 | v15 R12 | 全部 | `POST /biz/pickup/arrived` | 已有 | |
| 12-21 | 报缺货 / 破损 | v15 R12 | 全部 | `POST /biz/pickup/{orderNo}/report` | 已有 | 只记录并通知顾客，不是退款 |
| 12-22 | 核销取货码、代顾客核销、按片段搜单 | v15 R11 | 全部 | `POST /biz/pickup/verify`、`GET /biz/pickup/verify/search` | 已有 | 被拒时仍回 200，带 `success:false` 和原因 |
| 12-23 | 批量核销 | v15 R11 | 全部 | `POST /biz/pickup/verify/batch` | 已有 | 逐条处理，失败的逐条列出 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/order` | 订单列表 | 头 `X-Store-No`；query `caller=RETAIL`、`status?`、`fulfillments?`（可重复）、`page`、`size` | `PageData{records[OrderVO], total, page, size}`；OrderVO：`orderNo, status, fulfillment(EXPRESS\|MERCHANT_DELIVERY\|STORE_PICKUP\|NEIGHBOR_PICKUP\|STORE_VERIFY\|SERVICE_EXECUTION), amount{currency, payableMinor, goodsMinor, freightMinor, discountMinor, paidMinor, pointsDeductMinor}, items[{title, spec, qty, price, amount}], createdAt, paidAt, receiver{name, phone, address}, expressNo, expressCompany, verifyCode, buyerNickname, trafficSource, afterSale{afterSaleNo, status, type, reason}` | App 认得的 `status`：WAIT_PAY、WAIT_OFFLINE_PAY、PAID、FULFILLING、COMPLETED、REFUNDED、CANCELLED | 已联调 |
| `GET /biz/order/worklist` | 订单待办 | query `page, size` | `PageData{records[{order: OrderVO, actionKind(AFTER_SALE\|RECEIVE_PAYMENT\|FULFILL\|VERIFY), afterSaleNo}], total}` | 还缺截止时间（清单 2-9） | 已联调 |
| `GET /biz/order/{orderNo}/full` | 订单详情 | — | `order: OrderVO, currency, customer{nickname, phone, receiverName}, source{trafficSource}, remark, payments[{kind(ONLINE\|…), channel, amountMinor, at, channelTradeNo, confirmedBy}], operations[{at, event, label, operatorNo}], invoice{titleType, title, status, invoiceNo}` | | 已有 |
| `POST /biz/order/{orderNo}/prepare` | 备货 | `{}` | OrderVO | | 已有 |
| `POST /biz/order/{orderNo}/ship/precheck` | 检查运单 | `expressCompany, expressNo` | `verdict(INVALID\|WARNING\|…), reason, confirmationRequired` | `INVALID` 不能发货 | 已有 |
| `POST /biz/order/{orderNo}/ship` | 发货 | `expressCompany, expressNo, confirmExistingTrace` | OrderVO | 运单重复且没确认会被拒 | 已有 |
| `GET /biz/order/{orderNo}/express/quotes` | 快递报价 | query `weightKg` | `[{carrier, carrierName, priceMinor, listPriceMinor}]` | | 已有 |
| `POST /biz/order/{orderNo}/express` | 叫快递 | `carrier, weightKg` | 取件单 `pickupNo, carrier, carrierName, status(CREATED\|ACCEPTED\|…), trackingNo, courierName, courierMobile, failReason` | | 已有 |
| `GET /biz/order/{orderNo}/express` | 读取件单 | — | 取件单或 `null` | | 已有 |
| `POST /biz/order/{orderNo}/express/cancel` | 取消取件 | — | 取件单 | 只在 CREATED / ACCEPTED 时可取消 | 已有 |
| `POST /biz/order/{orderNo}/delivered` | 标记送达 | `{}` | OrderVO | | 已有 |
| `POST /biz/order/{orderNo}/confirm-offline-pay` | 确认线下收款 | `{}` | OrderVO | | 已有 |
| `GET /biz/order/{orderNo}/fulfillment-options` | 可改的履约方式 | — | `[{code \| fulfillment, allowed}]` | App 两个字段名都读 | 已有 |
| `POST /biz/order/{orderNo}/fulfillment-change` | 改履约方式 | `targetFulfillment, reason` | OrderVO | | 已有 |
| `GET /biz/order/{orderNo}/trace` | 物流轨迹 | query `refresh: bool` | `status, carrier, waybillNo, refreshable, nodes[{at, text, location}]` | | 已有 |
| `POST /biz/order/{orderNo}/waybill` | 改运单 | `expressCompany, expressNo, reason` | OrderVO | 未发货的订单会被拒（mock 10409 ORDER_NOT_SHIPPED） | 已有 |
| `GET /biz/order/{orderNo}/logistics-updates` | 物流同步记录 | — | `[{source, outcome, detail, nodesAdded, occurredAt, beforeStatus, afterStatus}]` | | 已有 |
| `GET /biz/after-sale/worklist` | 售后列表 | query `pending: bool, page, size` | `PageData{records[{afterSale: {afterSaleNo, subOrderNo, type, status, reason, refundMinor, images[], createdAt, merchantReply, returnItems[{itemId, title, requestedQty}], timeline[{at, status, label}]}, order: OrderVO}], total}` | `status`：APPLIED、WAIT_RECEIPT / RETURNING / WAIT_RETURN、REFUNDED、REJECTED、CLOSED | 已联调 |
| `GET /biz/after-sale/{afterSaleNo}` | 售后详情 | — | 同上单条 | | 已有 |
| `POST /biz/after-sale/{afterSaleNo}/approve` | 同意售后 | `remark, refundMinor, refundMode: CASH` | 售后对象 | 「需寄回 / 仅退款」的选择后端还没有（清单 2-9） | 已有 |
| `POST /biz/after-sale/{afterSaleNo}/reject` | 拒绝售后 | `remark` | 售后对象 | | 已有 |
| `POST /biz/after-sale/{afterSaleNo}/receive` | 收到退货 | `restockApproved, lines[{itemId, receivedQty, quality: SALEABLE\|UNSALEABLE}]` | 售后对象 | | 已有 |
| `POST /biz/after-sale/{afterSaleNo}/confirm-refund` | 确认退款 | `{}` | 售后对象 | | 已有 |
| `GET /biz/store/{storeNo}/ship-setting` | 读发货设置 | 头 `X-Store-No` | `senderName \| defaultSenderName, senderPhone \| defaultSenderPhone, address \| defaultAddress, carrier, weightG` | 两套字段名 App 都读；`weightG` 为 0 时按 1000 | 已联调 |
| `PUT /biz/store/{storeNo}/ship-setting` | 存发货设置 | `senderName, senderPhone, address, carrier, weightG` | 成功即可 | | 已有 |
| `GET /biz/store/{storeNo}/freight-template` | 运费模板 | — | `firstWeightGram, firstFee, addWeightGram, addFee, freeThreshold?, rules[{region, action(EXCLUDE\|DENY\|…)}]` 或 `null` | 金额为最小单位整数 | 已联调 |
| `GET /biz/pickup/overview` | 自提点总览 | — | `pickupName, pendingVerify, arrivedBatches` | 还缺逾期数（清单 2-9） | 已有 |
| `GET /biz/pickup/orders` | 自提订单 | — | `[OrderVO]` | | 已有 |
| `GET /biz/pickup/picking` | 分拣单 | — | `[{title, spec, totalQty, buyers}]` | | 已有 |
| `POST /biz/pickup/arrived` | 标记到货 | `orderNos[]` | 成功即可 | | 已有 |
| `POST /biz/pickup/{orderNo}/report` | 报缺货 / 破损 | `kind: SHORTAGE\|DAMAGE, qty, note` | 成功即可 | | 已有 |
| `POST /biz/pickup/verify` | 核销 | `verifyCode, onBehalf?` | `success, subOrderNo, reason(ALREADY_VERIFIED\|NOT_THIS_PICKUP\|REFUNDED\|CODE_NOT_FOUND\|NOT_PAID\|NOT_ARRIVED)` | 被拒时仍回 HTTP 200；还缺核销时间与经手人（清单 2-9） | 已有 |
| `POST /biz/pickup/verify/batch` | 批量核销 | `verifyCodes[]` | `successCount, failed[{success, subOrderNo, reason}]` | | 已有 |
| `GET /biz/pickup/verify/search` | 按片段搜单 | query `keyword` | `[OrderVO]` | | 已有 |

### 待决与注意
- `/biz/pickup/*` 要在 `ShopStoreMemberRoutes` 登记（清单 2-0），否则 R11、R12 在新模型门店都打不开。
- 承运商列表 `/biz/express/companies` 后端没有，在 `pendingContracts` 里：服务端包里「填运单发货」的承运商选择和发货设置的默认承运商都关着，靠 v15 叫快递顶上（清单 3-6）。
- `StoreOrdersApi.bookPickup` 和 `FulfillmentApi.book` 调的是同一个 `POST /biz/order/{orderNo}/express`，页面用的是后者。
- 售后同意时 `refundMinor` 直接取申请金额，`refundMode` 写死 CASH；部分退款和退款方式要后端确认。
- ai-shop 还有 App 没接的：`/biz/after-sale/by-order/{subOrderNo}`、`/biz/order/{orderNo}/inventory-trace`、`/biz/order/{orderNo}/points-earning`。

## 13. 进销存

零售门店的库存首页、库存列表与单品明细，进货、出库、盘点、调拨四种单据（草稿 → 过账才动库存并写流水），供应商、库位与仓、月报与动销、记库存的品类；v25 补上线上库存同步（期初对齐、可售规则、同步纪录）、跨店库存、线下卖出与撤销、品类断开确认、月报平衡与成本。对应原型 v18 K01–K12、v25 K13–K23；代码在 `features/inventory`（`inventory_api.dart`、`inventory_sync_api.dart`）。接口都在 ai-shop，但 `/biz/inventory/*`、`stock-alignment`、`stock-reconcile`、`offline-sale` 在 166 被 70006 拒；正式包用 `OnboardingController.inventoryOpen` 整个关着（入口显示「未開放」，不发请求），mock 包开着。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 13-01 | 进销存首页：品项数、缺货、滞销、在途、进行中的盘点 | v18 K01 | 店主、店长 | `GET /biz/inventory/summary` | 已有 | |
| 13-02 | 库存列表（只看需要处理的 / 全部） | v18 K02 | 店主、店长 | `GET /biz/inventory/balances` | 已有 | |
| 13-03 | 扫码找品项 | v18 K02、K04 | 店主、店长 | `GET /biz/inventory/items/by-barcode` | 已有 | 一个码最多对应一个品项；没绑定时为空 |
| 13-04 | 单品库存、各库位数量、变动明细 | v18 K03 | 店主、店长 | `GET /biz/inventory/items/{itemId}`、`GET /biz/inventory/ledger` | 已有 | |
| 13-05 | 设安全库存 | v18 K03 | 店主、店长 | `PUT /biz/inventory/safety-stock` | 已有 | |
| 13-06 | 直接改实际数量（单品盘点） | v18 K03 | 店主、店长 | `POST /biz/inventory/adjust` | 已有 | 服务端记成一行盘点单并写流水 |
| 13-07 | 进货：选品项、建进货单、过账 / 作废 | v18 K04 | 店主、店长 | `GET /biz/inventory/pickable`、`POST /biz/inventory/inbounds`、`POST …/inbounds/{no}/post\|void` | 已有 | 选品项时零库存的也列出 |
| 13-08 | 盘点：开单、填实盘数、过账 / 作废 | v18 K05 | 店主、店长 | `POST /biz/inventory/counts`、`GET/PUT …/counts/{no}(/lines)`、`POST …/counts/{no}/post\|void` | 已有 | 同时只能有一张进行中的盘点单 |
| 13-09 | 调拨：建单、发出、收货、作废 | v18 K06 | 店主、店长 | `POST /biz/inventory/transfers`、`POST …/transfers/{no}/ship\|receive\|void` | 已有 | 单店场景 App 一次做完「发出 + 收货」 |
| 13-10 | 出库（报损、自用、退供应商） | v18 K07 | 店主、店长 | `POST /biz/inventory/outbounds`、`POST …/outbounds/{no}/post\|void` | 已有 | |
| 13-11 | 单据列表、按单号查、按状态继续操作 | v18 K08 | 店主、店长 | `GET /biz/inventory/documents` | 已有 | 草稿进货 / 出库可过账或作废；草稿调拨可发出；已发出可收货；盘点中可作废 |
| 13-12 | 供应商：列表、新增、编辑、停用 / 启用 | v18 K09 | 店主、店长 | `GET/POST /biz/inventory/suppliers`、`PUT …/suppliers/{no}`、`POST …/suppliers/{no}/active` | 已有 | 平台供应商只能改备注 |
| 13-13 | 库位与仓：列表、加仓、设线上发货源 | v18 K10 | 店主、店长 | `GET/POST /biz/inventory/locations`、`PUT …/locations/{id}/source` | 已有 | 每店只有一个发货源 |
| 13-14 | 月报（期初、进、销、损、调整、期末，含成本）与动销排行 | v18 K11、v25 K23 | 店主、店长 | `GET /biz/inventory/report/monthly`、`GET …/report/ranking` | 已有 | 期初 + 进 − 销 − 损 ± 调整 ≠ 期末时标「不平」 |
| 13-15 | 哪些品类记库存；断开时确认 | v18 K12、v25 K22 | 店主、店长 | `GET /biz/inventory/category-setting`、`PUT …/category-setting/{categoryNo}` | 已有 | 有库存要再确认；有未完成单据时拦下 |
| 13-16 | 线上库存同步：状态、开 / 关 | v25 K13 | 店主 | `GET/PUT /biz/store/{storeNo}/stock-sync` | 已有 | 未对齐前不能开（70069） |
| 13-17 | 期初对齐：对比、预览、确认、看结果 | v25 K14–K16 | 店主 | `GET /biz/store/{storeNo}/stock-alignment`、`POST …/preview`、`POST …/batch/{batchNo}/confirm`、`GET …/batch/{batchNo}` | 已有 | 一批 15 分钟有效；部分失败时重新确认同一批，不另开新批 |
| 13-18 | 线上可售规则（门店默认、按品类） | v25 K17 | 店主 | `GET/PUT /biz/store/{storeNo}/sell-rules` | 已有 | |
| 13-19 | 同步纪录与单条重试 | v25 K18 | 店主 | `GET /biz/store/{storeNo}/stock-reconcile/records`、`POST …/stock-reconcile` | 已有 | |
| 13-20 | 跨店库存 | v25 K19 | 店主、店长 | `GET /biz/inventory/cross-store` | 已有 | |
| 13-21 | 线下卖出：按天看、记一笔 | v25 K20 | 店主、店长、员工 | `GET/POST /biz/store/{storeNo}/offline-sale` | 已有 | 只记数量，不记金额 |
| 13-22 | 撤销线下卖出 | v25 K21 | 店主、店长 | `POST /biz/store/{storeNo}/offline-sale/{docNo}/revoke` | 已有 | 撤销另开退回单，原单不删 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/inventory/summary` | 首页汇总 | 头 `X-Store-No` | `itemCount, shortageCount, staleCount, inTransitCount, openCountNo` | | 已有 |
| `GET /biz/inventory/balances` | 库存列表 | query `filter?`（不传 = 只看要处理的，`ALL` = 全部） | `{itemId, name, specText, onHand, available, safetyStock, flags[（ai-shop-bapp `docs/implementation/SHORTAGE\|…`）}]` | | 已有 |
| `GET /biz/inventory/pickable` | 可上单的品项 | — | 同 balances | 含零库存 | 已有 |
| `GET /biz/inventory/items/by-barcode` | 按条码找品项 | query `code` | 单个 balance 对象或空 | | 已有 |
| `GET /biz/inventory/items/{itemId}` | 单品库存 | — | balance 字段 + `barcode, byLocation[{locationName, onHand}]` | 不存在 10404 | 已有 |
| `GET /biz/inventory/ledger` | 变动明细 | query `itemId, size` | `entries[{docKind, docNo, qtyDelta, balanceAfter, occurredAt, reasonCode}]` | | 已有 |
| `PUT /biz/inventory/safety-stock` | 设安全库存 | `itemId, qty` | 成功即可 | | 已有 |
| `POST /biz/inventory/adjust` | 单品改实际数量 | `itemId, countedQty, reasonCode: MANUAL` | 成功即可 | | 已有 |
| `POST /biz/inventory/inbounds` | 建进货单 | `sourceType: PURCHASE, supplierName?, lines[{itemId, qty, unitCostMinor?}]` | `no` | 进货单还缺库位（清单 2-9） | 已有 |
| `POST /biz/inventory/inbounds/{no}/post\|void` | 进货单过账 / 作废 | — | 成功即可 | 状态不对 10409 | 已有 |
| `POST /biz/inventory/outbounds` | 建出库单 | `purpose: LOSS\|USE\|RETURN, remark?, lines[{itemId, qty, unitCostMinor?}]` | `no` | | 已有 |
| `POST /biz/inventory/outbounds/{no}/post\|void` | 出库单过账 / 作废 | — | 成功即可 | | 已有 |
| `POST /biz/inventory/counts` | 开盘点单 | `itemIds[]` | `no` | 已有进行中的盘点单 10409 | 已有 |
| `GET /biz/inventory/counts/{no}` | 盘点单 | — | `countNo, status(COUNTING\|…), lines[{itemId, name, specText, bookQty, countedQty?}]` | | 已有 |
| `PUT /biz/inventory/counts/{no}/lines` | 填实盘数 | 数组 `[{itemId, countedQty}]` | 成功即可 | 请求体是数组，不是对象 | 已有 |
| `POST /biz/inventory/counts/{no}/post\|void` | 盘点过账 / 作废 | — | 成功即可 | | 已有 |
| `POST /biz/inventory/transfers` | 建调拨单 | `fromLocationId, toLocationId, lines[{itemId, qty, unitCostMinor?}]` | `no` | | 已有 |
| `POST /biz/inventory/transfers/{no}/ship\|receive\|void` | 调拨发出 / 收货 / 作废 | — | 成功即可 | | 已有 |
| `GET /biz/inventory/documents` | 单据列表 | query `kind?: IN\|OUT\|COUNT\|TRANSFER`、`no?` | `[{kind(IN\|INBOUND\|OUT\|OUTBOUND\|COUNT\|TRANSFER), docNo, status(DRAFT\|SHIPPED\|COUNTING\|…), label, totalQty, occurredAt, subtitle, operator}]` | | 已有 |
| `GET /biz/inventory/suppliers` | 供应商列表 | — | `[{supplierNo, name, contactName, contactPhone, status(ACTIVE\|INACTIVE), fromPlatform, remark}]` | | 已有 |
| `POST /biz/inventory/suppliers` | 新增供应商 | `name, contactName?` | 成功即可 | | 已有 |
| `PUT /biz/inventory/suppliers/{no}` | 编辑供应商 | `name?, contactName?, contactPhone?, remark` | 成功即可 | 平台供应商只收 `remark`，由服务端把关 | 已有 |
| `POST /biz/inventory/suppliers/{no}/active` | 停用 / 启用 | `active: bool` | 成功即可 | | 已有 |
| `GET /biz/inventory/locations` | 库位列表 | — | `[{locationId, name, kind, isDefault(1\|0)}]` | `isDefault=1` 是线上发货源 | 已有 |
| `POST /biz/inventory/locations` | 加仓 | `name` | 成功即可 | | 已有 |
| `PUT /biz/inventory/locations/{id}/source` | 设线上发货源 | 无请求体 | 成功即可 | | 已有 |
| `GET /biz/inventory/report/monthly` | 月报 | query `month`（YYYY-MM） | `month, opening, purchased, sold, lost, adjusted, closing, soldCostMinor?, lostCostMinor?, balanced` | 成本不是销售额；售价在订单里 | 已有 |
| `GET /biz/inventory/report/ranking` | 动销排行 | query `type: TOP\|STALE` | `[{name, qty, costAmountMinor?}]` | | 已有 |
| `GET /biz/inventory/category-setting` | 记库存的品类 | — | `[{categoryNo, name, managed, goodsCount}]` | | 已有 |
| `PUT /biz/inventory/category-setting/{categoryNo}` | 开 / 关品类（含 K22 断开） | `managed, confirm?` | `status(DONE\|NEEDS_CONFIRM\|BLOCKED), goods[{title, onHand, blockers[{kind, docNo}]}]` | NEEDS_CONFIRM：商品还有库存，带 `confirm:true` 再调；BLOCKED：有未完成单据 | 已有 |
| `GET /biz/store/{storeNo}/stock-sync` | 同步状态 | 头 `X-Store-No` | `state(NOT_ALIGNED\|ALIGNED\|SYNCING), enabled, alignedAt` | | 已有 |
| `PUT /biz/store/{storeNo}/stock-sync` | 开 / 关同步 | `enabled` | 同上 | 未对齐 70069 STOCK_SYNC_NOT_ALIGNED；仅店主 | 已有 |
| `GET /biz/store/{storeNo}/stock-alignment` | 实物与线上库存对比 | — | `[{skuNo, title, spec, onHand, mallStock, diff, note(ENTITY_MULTI_STORE\|NO_ITEM)}]` | `note` 有值的行不调整 | 已有 |
| `POST /biz/store/{storeNo}/stock-alignment/preview` | 生成对齐批次 | `mode: COUNT\|MALL` | 批次 `batchNo, mode, snapshotVersion, expiresAt, status(PREVIEW\|PROCESSING\|COMPLETED\|PARTIAL\|BLOCKED\|STALE\|EXPIRED), completed, blocked, waiting, rows[{skuNo, title, spec, beforeQty, afterQty, status, errorCode}]` | `mode` 不对 10400；冲突 10409 | 已有 |
| `POST /biz/store/{storeNo}/stock-alignment/batch/{batchNo}/confirm` | 确认 / 继续同一批 | `operationNo`（App 用 `ALIGN-<batchNo>`）、`snapshotVersion` | 批次 | STALE / EXPIRED 要重新预览；PARTIAL / BLOCKED 再确认同一批 | 已有 |
| `GET /biz/store/{storeNo}/stock-alignment/batch/{batchNo}` | 读批次结果 | — | 批次 | | 已有 |
| `GET /biz/store/{storeNo}/sell-rules` | 可售规则 | — | `[{scopeType(STORE\|CATEGORY), scopeRef, ruleType(INHERIT\|ALL\|RESERVE\|RATIO\|CAP\|MANUAL), param}]` | INHERIT 只用于门店以下 | 已有 |
| `PUT /biz/store/{storeNo}/sell-rules` | 存可售规则 | `rules[{scopeType, scopeRef, ruleType, param}]` | 成功即可 | RESERVE、RATIO、CAP、MANUAL 要带 `param` | 已有 |
| `GET /biz/store/{storeNo}/stock-reconcile/records` | 同步纪录 | query `page, size` | `records[{operationNo, skuNo, title, reason, status, updatedAt, result{applied, before, after, skipped}}]`（也可能直接回数组） | | 已有 |
| `POST /biz/store/{storeNo}/stock-reconcile` | 单条重试 | `skuNo, reason: RETRY, operationNo`（App 用 `RETRY-<原 operationNo>`） | 成功即可 | 仅店主 | 已有 |
| `GET /biz/inventory/cross-store` | 跨店库存 | query `filter: shortage\|all`、`size` | `[{itemId, name, specText, available, shortageLocations, byLocation[{locationName, onHand, safetyStock}]}]` | | 已有 |
| `GET /biz/store/{storeNo}/offline-sale` | 某天的线下卖出 | query `date`（YYYY-MM-DD） | `[{docNo, occurredAt, totalQty, revoked, items[{title, spec, qty}]}]` | | 已有 |
| `POST /biz/store/{storeNo}/offline-sale` | 记线下卖出 | `lines[{skuNo, qty}], remark` | `docNo` | 空行 10400 | 已有 |
| `POST /biz/store/{storeNo}/offline-sale/{docNo}/revoke` | 撤销 | — | 成功即可 | 已撤销 10400；不存在 10404 | 已有 |

### 待决与注意
- 11/08 已解决：70006 的原因是路由未在 `ShopStoreMemberRoutes` 登记（不是角色权限）。ai-shop 已补登记 `/biz/inventory/**` 与门店下的 `offline-sale`、`stock-overview`、`stock-alignment`、`stock-reconcile`，166 09:49Z 发版。App 打开 `inventoryOpen` 前在 166 联调一遍。
- 商品侧的 `GET /biz/goods/inv-mode` 已登记（见第 11 节）；`managed=true` 的商品要在这里盘点改库存，不能在商品列表改。
- ai-shop 还有 App 没接的：`/biz/inventory/export`、`items/{item}/valuation`、`item-by-sku`、`/biz/store/{storeNo}/stock-overview(/batch)`、`stock-alignment/confirm`（不带批次的旧版）、`stock-reconcile/{operationNo}`。

## 14. 餐饮

餐饮门店的点餐单（堂食、外带、外送）：看单、加菜、退菜、结账（可拆单）、外送单接单 / 拒单；以及菜单与菜品（加料口味、出品站、照片）、沽清与剩余份数、桌台与桌码、出品看板、外送平台开关、首页汇总和报表。对应原型 v6 F01–F07、v16 D01–D08；代码在 `features/store_orders`（`industry_http.dart`、`dining_manage.dart`、`industry_models.dart`），线上形状由 `features/v5/wire_codec.dart` 编码。ai-shop 没有这组接口，整个 `/biz/food/` 前缀在 `pendingContracts` 里，服务端包显示「尚未開放」；下面都是按 mock 形状提的**建议契约**（对接清单 3-8，共 21 个）。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 14-01 | 点餐单列表（按桌、外带、外送） | v6 F01 | 全部 | `GET /biz/food/orders` | 建议 | |
| 14-02 | 单张点餐单详情 | v6 F02–F03 | 全部 | `GET /biz/food/orders/{orderNo}` | 建议 | |
| 14-03 | 加菜 | v6 F04 | 全部 | `POST /biz/food/orders/{orderNo}/dishes` | 建议 | 沽清或下架的菜不能点 |
| 14-04 | 退菜（填原因） | v6 F05 | 店主、店长 | `POST /biz/food/orders/{orderNo}/returns` | 建议 | 退菜只是停做，不是退款；看板上显示「已退，不用做」 |
| 14-05 | 结账（可拆多种付款）、开发票 | v6 F06 | 全部 | `POST /biz/food/orders/{orderNo}/checkout` | 建议 | 付款方式 QR / CARD / CASH |
| 14-06 | 外送单接单（填备餐分钟） / 拒单 | v6 F07 | 全部 | `POST /biz/food/orders/{orderNo}/accept\|reject` | 建议 | |
| 14-07 | 餐饮首页：桌台、出餐排队、外送待接、今日营收 | v16 D01 | 全部 | `GET /biz/food/summary` | 建议 | 每个数字标更新时间 |
| 14-08 | 菜单 | v16 D02 | 全部 | `GET /biz/food/menu` | 建议 | 和点餐共用一份菜单 |
| 14-09 | 新增 / 编辑菜品（加料口味、出品站、照片） | v16 D03 | 店主、店长 | `POST /biz/food/menu/items` | 建议 | 选项组可单选 / 多选、必选，每项可加价 |
| 14-10 | 沽清 / 恢复 / 下架 | v16 D04 | 全部 | `POST /biz/food/menu/items/{id}/status` | 建议 | |
| 14-11 | 设今天剩余份数 | v16 D04 | 全部 | `POST /biz/food/menu/items/{id}/remaining` | 建议 | 卖完最后一份自动沽清到打烊；`null` 取消限量 |
| 14-12 | 桌台：列表、新增、改名 / 改座位 / 换区、删除、看桌码 | v16 D05 | 店主、店长 | `GET/POST /biz/food/tables`、`POST …/tables/{tableId}`、`POST …/tables/{tableId}/delete` | 建议 | 桌码由 `orderUrl` 画成二维码，可复制链接 |
| 14-13 | 出品看板（按出品站筛选）、开始做 / 做好 | v16 D06 | 全部 | `GET /biz/food/kitchen`、`POST …/kitchen/{orderNo}/{dishId}/start\|done` | 建议 | 有催菜标记 |
| 14-14 | 外送平台：看连接状态与今日单量、暂停 / 恢复接单 | v16 D07 | 店主、店长 | `GET /biz/food/platforms`、`POST …/platforms/{code}` | 建议 | 是否在首期范围待确认 |
| 14-15 | 餐饮报表（今天、昨天、近 7 天、本月） | v16 D08 | 店主、店长 | `GET /biz/food/report` | 建议 | |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/food/orders` | 点餐单列表 | 头 `X-Store-No` | `[DiningOrder]`；DiningOrder：`orderNo, kind(dineIn\|takeaway\|delivery), title, stage(toAccept\|cooking\|toPickup\|dining\|toPay\|done\|rejected), openedAt, guests, waiter, platform, platformCollected, paid, dishes[{id, name, quantity, unitPrice{currency, minor}, state(waiting\|cooking\|served\|returned), note, addedAt, returnReason, returnBy}], amounts[{label, amount{currency, minor}, strong}], total{currency, minor}, payments[{method, amount, at, paymentNo, note}], invoice{type, status, number}, source, records[{at, text}]` | 枚举是 App 的 Dart 枚举名（小驼峰），后端定稿时可改成大写 | 建议 |
| `GET /biz/food/orders/{orderNo}` | 点餐单详情 | — | DiningOrder | | 建议 |
| `POST /biz/food/orders/{orderNo}/dishes` | 加菜 | `items: {菜品 id: 数量}` | DiningOrder | | 建议 |
| `POST /biz/food/orders/{orderNo}/returns` | 退菜 | `dishIds[], reason` | DiningOrder | | 建议 |
| `POST /biz/food/orders/{orderNo}/checkout` | 结账 | `payments[{method: QR\|CARD\|CASH, amount{currency, minor}}], invoice` | DiningOrder | 拆单就是多条 `payments` | 建议 |
| `POST /biz/food/orders/{orderNo}/accept` | 外送接单 | `prepMinutes` | DiningOrder | mock 默认 15 分钟 | 建议 |
| `POST /biz/food/orders/{orderNo}/reject` | 外送拒单 | `{}` | DiningOrder | | 建议 |
| `GET /biz/food/summary` | 首页汇总 | — | `tables{total, dining, toPay}, kitchen{dishes, longestMinutes}, delivery{toAccept}, today{revenue{currency, minor}, orders, avgTicket?, vsYesterday}, updatedAt` | | 建议 |
| `GET /biz/food/menu` | 菜单 | — | `[MenuItem]`；MenuItem：`id, category, name, price{currency, minor}, note, status(ON\|SOLD_OUT\|OFF), station, remaining?, imageUrl, options[{name, multi, required, choices[{name, extraMinor}]}]` | | 建议 |
| `POST /biz/food/menu/items` | 新增 / 编辑菜品 | MenuItem（`id` 空 = 新增） | MenuItem | 照片先上传再填 `imageUrl` | 建议 |
| `POST /biz/food/menu/items/{id}/status` | 改菜品状态 | `status: ON\|SOLD_OUT\|OFF` | 成功即可 | | 建议 |
| `POST /biz/food/menu/items/{id}/remaining` | 设剩余份数 | `remaining?`（`null` 取消） | 成功即可 | | 建议 |
| `GET /biz/food/tables` | 桌台列表 | — | `[{tableId, area, name, seats, busy, toPay, orderUrl}]` | | 建议 |
| `POST /biz/food/tables` | 新增桌台 | `area, name, seats` | 成功即可 | | 建议 |
| `POST /biz/food/tables/{tableId}` | 改桌台 | `area, name, seats` | 成功即可 | | 建议 |
| `POST /biz/food/tables/{tableId}/delete` | 删桌台 | — | 成功即可 | | 建议 |
| `GET /biz/food/kitchen` | 出品看板 | — | `[{orderNo, dishId, table, dish, station, qty, state(WAITING\|COOKING\|RETURNED), minutes, note, urged}]` | | 建议 |
| `POST /biz/food/kitchen/{orderNo}/{dishId}/start\|done` | 开始做 / 做好 | — | 成功即可 | WAITING 调 start，COOKING 调 done | 建议 |
| `GET /biz/food/platforms` | 外送平台 | — | `[{code, name, linked, accepting, todayOrders}]` | | 建议 |
| `POST /biz/food/platforms/{code}` | 暂停 / 恢复接单 | `accepting: bool` | 成功即可 | | 建议 |
| `GET /biz/food/report` | 餐饮报表 | query `range: TODAY\|YESTERDAY\|LAST_7_DAYS\|THIS_MONTH` | `hours[{slot, amount{currency, minor}}], top[{name, qty}], turnover, deliveryShare, avgDiningMinutes, updatedAt` | `turnover`、`deliveryShare` 是显示用字符串 | 建议 |

### 待决与注意
- 整组接口要后端出 TDD 冻结（清单 3-8，优先级看首批店型）；冻结一组，App 就从 `pendingContracts` 删掉 `^/biz/food/`。
- 餐饮金额用 `{currency, minor}`（`minor` 为字符串整数）；零售订单用的是 `xxxMinor` 长整数，两套不一样，冻结时要统一。
- 订单、菜品的枚举现在是 Dart 枚举名（`dineIn`、`toAccept`），桌台、看板、菜品状态是大写，冻结时统一。
- 首页的餐饮卡在 166 上显示「暂时读不到」，就是因为 `/biz/food/*` 被拦着。
- ai-shop 的 `/biz/x/food/*`（存酒、宴席权益）和这里无关，App 没接。

## 15. 服务业

服务业（美业等）门店的预约：按天看预约、详情、签到并指派技师、取消、结账（储值 + 其他方式，订金另记）、改约、服务中加项；以及首页汇总、时间线、代客预约（查可约时段、按手机号查会员）、服务项目、技师与资源、停约、排班与改班影响、报表。对应原型 v6 B01–B05、v17 B10–B18；代码在 `features/store_orders`（`industry_http.dart`、`beauty_manage.dart`、`beauty_pages.dart`）。ai-shop 没有 `/biz/beauty/*`，整组在 `pendingContracts` 里，下面是按 mock 形状提的**建议契约**（对接清单 3-9，22 个）；停约用 ai-shop 已有的 `/biz/appointment-slots/{slotNo}/close`，但也跟着拦着（清单 2-5）。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 15-01 | 按天看预约 | v6 B01 | 全部 | `GET /biz/beauty/bookings` | 建议 | |
| 15-02 | 预约详情（服务项、金额、业绩、记录） | v6 B02–B03 | 全部 | `GET /biz/beauty/bookings/{bookingNo}` | 建议 | 有订金时记录里写「订金已收 / 待收」 |
| 15-03 | 签到并指派技师（看谁几点有空） | v6 B04 | 全部 | `GET /biz/beauty/bookings/{bookingNo}/staff`、`POST …/check-in` | 建议 | |
| 15-04 | 取消预约 | v6 B02 | 店主、店长 | `POST /biz/beauty/bookings/{bookingNo}/cancel` | 建议 | |
| 15-05 | 结账（储值扣多少 + 其余用卡 / 现金） | v6 B05 | 全部 | `POST /biz/beauty/bookings/{bookingNo}/checkout` | 建议 | |
| 15-06 | 改约（换时间、换技师） | v6 B04 | 全部 | `POST /biz/beauty/bookings/{bookingNo}/reschedule` | 建议 | 只有已预约（未到店）的能改；时段被占 10409 |
| 15-07 | 服务中加项 | v6 B02 | 全部 | `POST /biz/beauty/bookings/{bookingNo}/items` | 建议 | 只有服务中的能加 |
| 15-08 | 首页：今日预约、已到、待结账、空档、下一位、营收 | v17 B10 | 全部 | `GET /biz/beauty/summary` | 建议 | |
| 15-09 | 预约时间线（按技师 / 资源分栏） | v17 B11 | 全部 | `GET /biz/beauty/timeline` | 建议 | |
| 15-10 | 代客预约：查可约时段、按手机号认会员、下单（含订金） | v17 B12 | 全部 | `GET /biz/beauty/availability`、`GET /biz/beauty/customers`、`POST /biz/beauty/bookings` | 建议 | 不指定技师时由服务端分配 |
| 15-11 | 服务项目：列表、新增 / 编辑（时长、缓冲、订金、可做技师、资源） | v17 B13–B14 | 店主、店长 | `GET/POST /biz/beauty/catalog` | 建议 | 缓冲时间内不能开始新预约 |
| 15-12 | 技师与资源、设技师可否被预约 | v17 B15 | 店主、店长 | `GET /biz/beauty/staff-resources`、`POST …/staff-resources/{name}/bookable` | 建议 | |
| 15-13 | 停约：先看范围内时段和已有预约，再逐个关时段 | v17 B16 | 店主、店长 | `GET /biz/beauty/slots`、`POST /biz/appointment-slots/{slotNo}/close` | 建议 / 已有 | 停约不取消已有预约，由门店联系顾客 |
| 15-14 | 排班：看周班表、改班前看影响、改班 | v17 B17 | 店主、店长 | `GET /biz/beauty/schedule`、`GET …/schedule/impact`、`POST …/schedule` | 建议 | 改班后落在班外的预约先列出来 |
| 15-15 | 服务业报表（今天、近 7 天、本月） | v17 B18 | 店主、店长 | `GET /biz/beauty/report` | 建议 | |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/beauty/bookings` | 某天预约 | 头 `X-Store-No`；query `date`（YYYY-MM-DD） | `[Visit]`；Visit：`bookingNo, customer, at, stage(booked\|inService\|toPay\|done\|cancelled), designer, minutes, channel, member, visits, phone, preference, balance?{currency, minor}, items[{name, staff, price{currency, minor}, minutes, startAt, endAt, addOn, product, quantity}], amounts[{label, amount, strong}], total?, payments[{method, amount, at, paymentNo, note}], performance[{staff, what, amount}], records[{at, text}]` | `stage` 是 Dart 枚举名 | 建议 |
| `GET /biz/beauty/bookings/{bookingNo}` | 预约详情 | — | Visit | | 建议 |
| `GET /biz/beauty/bookings/{bookingNo}/staff` | 可指派的技师 | — | `[{name, freeFrom}]` | | 建议 |
| `POST /biz/beauty/bookings/{bookingNo}/check-in` | 签到 | `designer` | Visit | | 建议 |
| `POST /biz/beauty/bookings/{bookingNo}/cancel` | 取消 | `{}` | Visit | | 建议 |
| `POST /biz/beauty/bookings/{bookingNo}/checkout` | 结账 | `storedValue{currency, minor}, rest: CARD\|CASH` | Visit | | 建议 |
| `POST /biz/beauty/bookings/{bookingNo}/reschedule` | 改约 | `date, start`（如 `14:00`）`, staff` | 成功即可 | 不存在 10404；不是 booked 10409 NOT_BOOKED；时段被占 10409 | 建议 |
| `POST /biz/beauty/bookings/{bookingNo}/items` | 加项 | `serviceNo, staff` | 成功即可 | 不是服务中 10409 NOT_IN_SERVICE | 建议 |
| `GET /biz/beauty/summary` | 首页汇总 | — | `bookings, arrived, toPay, freeSlots, next{start, customer, service, bookingNo}, today{revenue{currency, minor}, payments, avgTicket?}` | | 建议 |
| `GET /biz/beauty/timeline` | 时间线 | query `by`（按技师或资源分栏） | `columns[], slots[{column, start, end, label, kind, ref}]` | | 建议 |
| `GET /biz/beauty/availability` | 可约时段 | query `date, serviceNo, staff`（空 = 不指定） | `[{start, staff, free}]` | | 建议 |
| `GET /biz/beauty/customers` | 按手机号查会员 | query `phone` | `{name, level, visits, preference}` 或 `null` | | 建议 |
| `POST /biz/beauty/bookings` | 代客预约 | `customer, phone, serviceNo, staff, date?, start, depositPaid` | 成功即可 | 服务不存在 10404；时段被占 10409 | 建议 |
| `GET /biz/beauty/catalog` | 服务项目 | — | `[{serviceNo, category, name, minutes, bufferMinutes, price{currency, minor}, deposit?{currency, minor}, staff[], resource, status(ON\|OFF)}]` | | 建议 |
| `POST /biz/beauty/catalog` | 新增 / 编辑服务 | 同上单条（`serviceNo` 空 = 新增） | 成功即可 | | 建议 |
| `GET /biz/beauty/staff-resources` | 技师与资源 | — | `staff[{name, skills[], bookable, note}], resources[{name, count}]` | | 建议 |
| `POST /biz/beauty/staff-resources/{name}/bookable` | 设可否被预约 | `bookable: bool` | 成功即可 | 路径里用的是技师名字，建议改成编号 | 建议 |
| `GET /biz/beauty/slots` | 停约预览 | query `date, from, to, staff`（空 = 全店） | `slots[]（slotNo）, bookings[{customer, start, staff}]` | | 建议 |
| `POST /biz/appointment-slots/{slotNo}/close` | 关一个时段 | App 传 `reason?`（只内部看） | ai-shop 回 `SlotVO`；App 只看成功 | ai-shop 已有此路由（权限 STORE），不收请求体；未在 `ShopStoreMemberRoutes` 登记，App 也拦着 | 已有 |
| `GET /biz/beauty/schedule` | 周班表 | — | `[{staff, shifts[]}]` | | 建议 |
| `GET /biz/beauty/schedule/impact` | 改班影响 | query `staff, day`（星期几的数字）`, shift` | `bookings[{customer, start}]` | | 建议 |
| `POST /biz/beauty/schedule` | 改班 | `staff, day, shift` | 成功即可 | | 建议 |
| `GET /biz/beauty/report` | 服务业报表 | query `range: TODAY\|LAST_7_DAYS\|THIS_MONTH` | `bookings, showRate, noShow, byStaff[{name, amount{currency, minor}}], top[{name, count}], updatedAt`（毫秒时间戳） | | 建议 |

### 待决与注意
- 整组要后端出 TDD 冻结（清单 3-9）；冻结后从 `pendingContracts` 删掉 `^/biz/beauty/` 和 `^/biz/appointment-slots/`。
- 停约要接上 ai-shop 已有的时段模型：`slotNo` 现在来自待定的 `/biz/beauty/slots`；ai-shop 另有 `GET/POST /biz/stores/{storeNo}/appointment-slots`（`{startAt, endAt, capacity}`），时段从哪个接口来要后端定。ai-shop 关时段不收 `reason`，原因要不要存也要定。
- 订金：服务带 `deposit`，代客预约传 `depositPaid`；结账接口没有订金字段，订金怎么抵扣尾款要定。
- 金额用 `{currency, minor}`；`stage` 用 Dart 枚举名；报表 `updatedAt` 是毫秒数，别处是 ISO 时间，冻结时统一。
- 技师用名字当标识（`staff`、`designer`、`/staff-resources/{name}`），建议改成员工编号，和 `features/staff` 的员工对上。

## 16. 员工与权限

店主管理员工、逐店授权（一人一店可多个角色）、自定义角色与权限点、变更记录；员工登录后首页按 `/biz/context` 的权限裁剪（v10 E01–E10）。代码：`features/staff`（`staff_api.dart`、`staff_pages.dart`）、`features/identity_access`（`/biz/context`、`/biz/management/*`）。员工与角色全部走 ai-shop `BizStaffController`，读接口 166 已联调；整块权限是 `biz:store:admin`（只有店主）。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 16-01 | 员工列表（含停用） | v10 E01 | 店主 | `GET /biz/staff` | 已联调 | 店主固定在最上，停用的排最后；手机号后端脱敏 |
| 16-02 | 加员工（手机号 + 备注名） | v10 E02–E03 | 店主 | `POST /biz/staff` | 已有 | 不发密码、不建 C 端账号；已存在（含停用）时重新启用 |
| 16-03 | 给员工在某门店授予 / 撤销一个角色 | v10 E02、E04 | 店主 | `POST /biz/staff/{mchAccountNo}/store` | 已有 | 一次只动一个角色；加员工后对每家选中的门店各调一次；撤到一个不剩 = 移出该店 |
| 16-04 | 员工详情 | v10 E04 | 店主 | `GET /biz/staff`、`GET /biz/staff/logs?mchAccountNo=` | 已联调 | 详情取列表里的这一行，加这个人的变更记录 |
| 16-05 | 停用 / 启用员工 | v10 E05 | 店主 | `POST /biz/staff/{mchAccountNo}/status` | 已有 | 店主不能停用 |
| 16-06 | 角色列表（预置只读 + 自定义，含使用人数） | v10 E06 | 店主 | `GET /biz/roles` | 已联调 | 预置 6 个只读；要改先复制为自定义 |
| 16-07 | 建 / 改自定义角色（勾权限点） | v10 E07 | 店主 | `GET /biz/role-perms`、`POST /biz/roles`、`POST /biz/role/{roleCode}` | 已联调（读）/ 已有（写） | 权限点按前缀在 App 端分组；退款、资金、员工管理归「敏感权限」 |
| 16-08 | 删除自定义角色 | v10 E07 | 店主 | `POST /biz/role/{roleCode}/delete` | 已有 | 有人持有时后端拒，App 按钮停用 |
| 16-09 | 变更记录 | v10 E08 | 店主 | `GET /biz/staff/logs` | 已联调 | 谁、何时、对谁、做了什么 |
| 16-10 | 统一登录、多重身份选择 | v10 E09–E09c | 全部 | `/biz/auth/identities*`（见登录模块） | 建议 | 正式包仍走店主登录 |
| 16-11 | 员工视角首页（按权限隐藏入口） | v10 E10 | 员工 | `GET /biz/context` | 已有 | 按 `perms` 隐藏进销存、营销、报表、门店设定、商城 Tab |
| 16-12 | 管理空间：读上下文、建空间、读有效授权、授权 / 撤销 | — | 店主 | `/biz/management/*` | 已有 | 只在 admission 流程读 context；建空间带幂等键，撤销带 `expectedRevision` |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/staff` | 员工列表 | 头 `X-Store-No` | `[{mchAccountNo, displayName, loginPhone, isOwner, status(ACTIVE/DISABLED), roles[{storeNo, storeName, role}]}]` | 店主邮箱登录时 `displayName`、`loginPhone` 为 null（App 用当前账号名补） | 已联调 |
| `POST /biz/staff` | 加员工 | 头 `X-Store-No`；`loginPhone, displayName?` | 同列表一行 | 缺 `loginPhone` 拒；mock：10400 / 10409 重复 | 已有 |
| `POST /biz/staff/{mchAccountNo}/status` | 停用 / 启用 | 头 `X-Store-No`；`active: bool` | 同列表一行 | 店主拒 | 已有 |
| `POST /biz/staff/{mchAccountNo}/store` | 授予 / 撤销门店角色 | 头 `X-Store-No`；`storeNo, role, granted?`（不传视为授予） | 同列表一行 | `role` 预置为 `MANAGER/CLERK/PICKER/COURIER/CS`，是否接受自定义 `roleCode` 待确认 | 已有 |
| `GET /biz/staff/logs` | 变更记录 | 头 `X-Store-No`；query `mchAccountNo?` | `[{action(STAFF_ADD/STAFF_ENABLE/STAFF_DISABLE/ROLE_GRANT/ROLE_REVOKE…), at(毫秒), detail, actor, targetName}]` | action 枚举取自 mock，以后端为准 | 已联调 |
| `GET /biz/roles` | 角色列表 | 头 `X-Store-No` | `[{roleCode, name, builtin, usedBy, perms[], permLabels[]}]` | `perms` 含 `*` 表示全部 | 已联调 |
| `GET /biz/role-perms` | 可勾的权限点 | 头 `X-Store-No` | `[{code, label}]` | 不含 `biz:store:admin` | 已联调 |
| `POST /biz/roles` | 建自定义角色 | 头 `X-Store-No`；`name, perms[]` | 角色一行 | 带 `biz:store:admin` 拒 | 已有 |
| `POST /biz/role/{roleCode}` | 改自定义角色 | 头 `X-Store-No`；`name, perms[]` | 角色一行 | 预置角色拒（mock 10409） | 已有 |
| `POST /biz/role/{roleCode}/delete` | 删自定义角色 | 头 `X-Store-No` | 无 | 有人持有或预置时拒（mock 10409） | 已有 |
| `GET /biz/context` | 当前门店的有效权限 | 头 `X-Store-No` | `merchantNo, currentStoreNo, owner, storeNos[], pickupNos[], groupNos[], staffRoles[], perms[], categoryCodes[], switches{}` | App 校验 `currentStoreNo` 与请求门店一致，否则当作失败 | 已有 |
| `GET /biz/management/context` | 管理空间上下文 | — | `actorKey, canBootstrap, spaces[{managementNo, name, actions[]}], bootstrapSources[{accountNo, entityNo, entityName, stores[{storeNo, name}], paymentMerchants[{payMerchantNo}]}]` | — | 已有 |
| `POST /biz/management/spaces` | 建管理空间 | 头 `Idempotency-Key`；`name, sourceAccountNo` | `managementNo, name, revision` | 只在 `canBootstrap` 且来源账号匹配时发 | 已有 |
| `GET /biz/management/spaces/{managementNo}/resources` | 读本人有效授权 | query `view=EFFECTIVE, cursor?` | `items[{actorNo, managementNo, resourceType, resourceNo, permissionCode}], nextCursor` | 需 `management.resource.read` | 已有 |
| `POST /biz/management/spaces/{managementNo}/grants` | 授权 | `actorNo, resourceType, resourceNo, permissionCode, validFrom?, validTo?, delegatable, expectedRevision?, reactivate` | `grantNo, managementNo, actorNo, resourceType, resourceNo, permissionCode, status, revision` | 需 `management.grant.manage` | 已有 |
| `DELETE /biz/management/spaces/{managementNo}/grants/{grantNo}` | 撤销授权 | query `expectedRevision, reason` | 同上 | 版本冲突时刷新再试 | 已有 |

### 待决与注意
- 权限是**门店级 + 店主专属**：员工、角色、记录都要 `biz:store:admin`。店长看不到员工管理。
- `POST /biz/staff/{no}/store` 的 `role` 是否接受自定义角色 `roleCode`（OpenAPI 枚举只有 5 个预置）待后端确认；App 目前列出全部非店主角色。
- 第二批 2-9：`StaffReq` 允许没有 `loginPhone`（E02）、权限点加退款上限（E07）。
- 店主用邮箱登录时员工列表店主一行没有名字和手机，建议后端回 `loginEmail`。
- 写操作后 App 会作废本地权限快照并重读 `/biz/context`；切店一律重读，不复用别家店的权限。
- 新门店上 70006 `CONTACT_ADMIN` 是路由未登记，不是权限被收回；App 分开提示。

## 17. 考勤与工资

班次与打卡规则、周排班（编辑、复制上周、发布）、我的班表与打卡、请假、补卡、审批、考勤月报；工资方案（版本化）、本期核算（重算、确认冻结）、个人明细与修订、我的工资条（v27 E11–E21）。代码：`features/hr`（`hr_api.dart`、`hr_pages.dart`），mock `dev/mock/mock_v27.dart`。**ai-shop 完全没有 `/biz/hr/*`**，下表全部是按 mock 写的建议契约，正式包由 `pendingContracts` 拦下（第三批 3-13，P1）。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 17-01 | 班次与打卡规则 | v27 E11 | 店主、店长 | `GET /biz/hr/shifts` | 建议 | 宽限分钟、打卡半径、跨日切点 |
| 17-02 | 新增班次 | v27 E11 | 店主、店长 | `POST /biz/hr/shifts` | 建议 | 结束早于开始 = 跨日 |
| 17-03 | 看一周排班（含冲突、人手提醒） | v27 E12 | 店主、店长 | `GET /biz/hr/roster` | 建议 | 冲突如「该时段有指定他的预约」 |
| 17-04 | 改一格排班 | v27 E12 | 店主、店长 | `PUT /biz/hr/roster/cell` | 建议 | 值为班次号 / `OFF` / `LEAVE` |
| 17-05 | 复制上周 | v27 E12 | 店主、店长 | `POST /biz/hr/roster/copy-last` | 建议 | 复制后回到未发布 |
| 17-06 | 发布排班 | v27 E12 | 店主、店长 | `POST /biz/hr/roster/publish` | 建议 | 排班与到岗分开记 |
| 17-07 | 我的班表、今日班次 | v27 E13 | 全部 | `GET /biz/hr/me/shifts` | 建议 | 本周打卡、迟到 / 缺卡、假期余额、补卡剩余次数 |
| 17-08 | 上班 / 下班打卡 | v27 E13 | 全部 | `POST /biz/hr/clock` | 建议 | 服务器记时间与是否在范围内 |
| 17-09 | 请假 | v27 E14 | 全部 | `POST /biz/hr/leave` | 建议 | 有预约冲突时回 `warning` |
| 17-10 | 补卡 | v27 E15 | 全部 | `POST /biz/hr/clock-fix` | 建议 | 需审批，留痕 |
| 17-11 | 审批列表（待审 / 已处理） | v27 E16 | 店主、店长 | `GET /biz/hr/approvals` | 建议 | 自己的申请不能自己批 |
| 17-12 | 核准 / 驳回 | v27 E16 | 店主、店长 | `POST /biz/hr/approvals/{requestNo}/approve`、`/reject` | 建议 | 驳回必填理由 |
| 17-13 | 考勤月报 | v27 E17 | 店主、店长 | `GET /biz/hr/attendance/monthly` | 建议 | 出勤天数、工时、迟到、请假、补卡 |
| 17-14 | 确认月报 | v27 E17 | 店主 | `POST /biz/hr/attendance/monthly/confirm` | 建议 | 确认后供工资核算用，不可重复确认 |
| 17-15 | 工资方案（含版本历史） | v27 E18 | 店主 | `GET /biz/hr/pay-plans` | 建议 | 底薪或时薪、服务 / 商品提成比例 |
| 17-16 | 调整方案（产生新版本） | v27 E18 | 店主 | `POST /biz/hr/pay-plans/{planNo}/revise` | 建议 | 新版本下一期生效 |
| 17-17 | 本期核算 | v27 E19 | 店主 | `GET /biz/hr/payroll/{period}` | 建议 | 冻结所用方案版本 |
| 17-18 | 重算 / 确认冻结 | v27 E19 | 店主 | `POST /biz/hr/payroll/{period}/recalculate`、`/confirm` | 建议 | 确认后不能重算 |
| 17-19 | 个人明细与修订 | v27 E20 | 店主 | `GET /biz/hr/payroll/{period}/staff/{staffNo}` | 建议 | 冻结后的修订并入下期 |
| 17-20 | 我的工资条 | v27 E21 | 全部 | `GET /biz/hr/payslips/mine` | 建议 | 员工只看本人 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/hr/shifts` | 班次与规则 | 头 `X-Store-No` | `shifts[{shiftNo, name, start(HH:mm), end, breakMin, crossDay}], rules{graceMin, radiusM, dayCutoff(HH:mm)}` | — | 建议 |
| `POST /biz/hr/shifts` | 新增班次 | 头 `X-Store-No`；`name, start, end, breakMin` | 班次一行 | 名称空 10400 | 建议 |
| `GET /biz/hr/roster` | 周排班 | 头 `X-Store-No`；query `week`（周一 yyyy-MM-dd） | `weekStart, published, staff[{staffNo, name}], cells[{staffNo, date, value(shiftNo/OFF/LEAVE)}], conflicts[{staffNo, date, count, text}], warnings[]` | — | 建议 |
| `PUT /biz/hr/roster/cell` | 改一格 | 头 `X-Store-No`；`weekStart, staffNo, date, value` | 整周排班（同上） | 员工或日期不在本周 10400 | 建议 |
| `POST /biz/hr/roster/copy-last` | 复制上周 | 头 `X-Store-No`；`weekStart` | 整周排班 | 复制后 `published=false` | 建议 |
| `POST /biz/hr/roster/publish` | 发布 | 头 `X-Store-No`；`weekStart` | 整周排班 | — | 建议 |
| `GET /biz/hr/me/shifts` | 我的班表 | 头 `X-Store-No` | `today{date, shiftName, start, end, clockIn, clockOut, inRange}, week[{date, shiftName, clockIn, clockOut, late, missing}], leaveBalanceDays, fixesLeft` | 今天无班时 `today` 为空 | 建议 |
| `POST /biz/hr/clock` | 打卡 | 头 `X-Store-No`；`kind: IN\|OUT` | `kind, at(HH:mm)` | 重复上班卡、未上班先下班 10409 | 建议 |
| `POST /biz/hr/leave` | 请假 | 头 `X-Store-No`；`type, date, reason` | 申请一行：`requestNo, kind(LEAVE), staffName, summary, reason, status(PENDING), warning, mine` | 理由空 10400；见注意（`type` 目前送的是文字） | 建议 |
| `POST /biz/hr/clock-fix` | 补卡 | 头 `X-Store-No`；`date, kind, time, reason` | 申请一行（`kind=CLOCK_FIX`） | 理由空 10400 | 建议 |
| `GET /biz/hr/approvals` | 审批列表 | 头 `X-Store-No`；query `status: PENDING\|DONE` | `[{requestNo, kind(LEAVE/CLOCK_FIX), staffName, summary, reason, status(PENDING/APPROVED/REJECTED/WITHDRAWN), warning, mine, rejectReason}]` | — | 建议 |
| `POST /biz/hr/approvals/{requestNo}/approve` | 核准 | 头 `X-Store-No` | 申请一行 | 自己的 10403；已处理 10409；不存在 10404 | 建议 |
| `POST /biz/hr/approvals/{requestNo}/reject` | 驳回 | 头 `X-Store-No`；`reason` | 申请一行 | 理由空 10400；其余同上 | 建议 |
| `GET /biz/hr/attendance/monthly` | 月报 | 头 `X-Store-No`；query `month`（yyyy-MM） | `month, confirmed, rows[{staffNo, name, days, hours, late, leave, fixes}]` | — | 建议 |
| `POST /biz/hr/attendance/monthly/confirm` | 确认月报 | 头 `X-Store-No`；`month` | `true` | 已确认 10409 | 建议 |
| `GET /biz/hr/pay-plans` | 工资方案 | 头 `X-Store-No` | `[{planNo, name, version, effectiveFrom, staffCount, baseMinor?, hourlyMinor?, serviceRate(%), goodsRate(%), history[{version, effectiveFrom, note}]}]` | 底薪与时薪二选一，另一个为 null | 建议 |
| `POST /biz/hr/pay-plans/{planNo}/revise` | 调整方案 | 头 `X-Store-No`；`baseMinor` | 方案一行（`version+1`，新 `effectiveFrom`） | `baseMinor` 非正整数 10400 | 建议 |
| `GET /biz/hr/payroll/{period}` | 本期核算 | 头 `X-Store-No`；`period` yyyy-MM | `period, status(CALCULATING/PENDING_CONFIRM/CONFIRMED/REVISED), planVersion, calculatedAt, totalMinor, rows[{staffNo, name, role, amountMinor}]` | `CONFIRMED`、`REVISED` 视为冻结 | 建议 |
| `POST /biz/hr/payroll/{period}/recalculate` | 重算 | 头 `X-Store-No` | 核算（同上） | 已确认 10409 | 建议 |
| `POST /biz/hr/payroll/{period}/confirm` | 确认冻结 | 头 `X-Store-No` | 核算（同上） | 已确认 10409 | 建议 |
| `GET /biz/hr/payroll/{period}/staff/{staffNo}` | 个人明细 | 头 `X-Store-No` | `staffNo, name, lines[{label, amountMinor}], totalMinor, revisions[{at, text, deltaMinor, into}]` | — | 建议 |
| `GET /biz/hr/payslips/mine` | 我的工资条 | 头 `X-Store-No` | `[{period, status, totalMinor, lines[{label, amountMinor}], attendance{days, late}}]` | 只回本人 | 建议 |

### 待决与注意
- 先定由哪个服务做，以及台湾劳基法（加班、休息日、特休）的计算范围；mock 的明细行 `label` 是服务端拼好的文字，正式契约建议改成结构化（类型 + 基数 + 比例）。
- **坑**：请假的 `type`、补卡的 `kind` 目前 App 送的是本地化文字（如「事假」「下班」），不是代码。冻结契约时应改为 `type: PERSONAL|SICK|ANNUAL|OTHER`、`kind: IN|OUT`（App 内部已用这些代码）。
- 金额：App 把输入的元 ×100 作为 `baseMinor`，与 `gbMoney` 一致按两位小数处理；后端若按币种 scale（TWD 0 位）要对齐。
- 角色只按原型：排班、审批、月报、工资给店主 / 店长；工资方案与核算建议只给店主；员工只看本人班表与工资条。权限点（如 `biz:hr:*`）未定义，需后端定。
- 员工号 `staffNo` 与 `/biz/staff` 的 `mchAccountNo` 是否同一个，需定。
- 排班冲突（指定预约）依赖服务业预约（`/biz/beauty/*`，也在第三批）。

## 18. 会员与营销

两套接口并存：(a) v20 活动、卡券、储值、会员走暂定的 `/biz/ops/*`（`features/operations/marketing_pages.dart`，ai-shop 没有，正式包拦下）；(b) v24 会员设定、新增、标签、人群、定向发券、群发与效果、平台活动报名、门店积分走 ai-shop 现有接口（`features/members`，路由都在，11/08 已为新门店登记）。两套必须统一，属后端决定（第二批 2-2）。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 18-01 | 活动列表（进行中 / 暂停 / 结束 / 草稿） | v20 M1 | 店主、店长 | `GET /biz/ops/activities` | 建议 | 连锁活动带 `chainName` |
| 18-02 | 活动详情 | v20 M2 | 店主、店长 | 同上（整包） | 建议 | 预算、名额用量 |
| 18-03 | 新建活动（折扣、满减、特价、买 N 送 M） | v20 M3 | 店主、店长 | `POST /biz/ops/activities` | 建议 | 可存草稿；可按星期循环 |
| 18-04 | 发布 / 暂停 / 恢复 / 结束活动 | v20 M2 | 店主、店长 | `POST /biz/ops/activities/{no}/publish\|pause\|resume\|end` | 建议 | 结束前确认 |
| 18-05 | 卡券列表、详情 | v20 C1–C2 | 店主、店长 | `GET /biz/ops/coupons` | 建议 | 发出 / 已用 / 总量 |
| 18-06 | 新建卡券（现金、满减、折扣） | v20 C3 | 店主、店长 | `POST /biz/ops/coupons` | 建议 | 相对天数或固定截止日 |
| 18-07 | 暂停 / 恢复卡券 | v20 C2 | 店主、店长 | `POST /biz/ops/coupons/{no}/pause\|resume` | 建议 | — |
| 18-08 | 把券发给会员（全部 / 某等级 / 选人） | v20 C2 | 店主、店长 | `POST /biz/ops/coupons/{no}/send` | 建议 | 统一后改走 18-23 定向发券 |
| 18-09 | 核销券（先查码再核销） | v20 C4 | 全部（收银） | `GET /biz/ops/coupons/lookup`、`POST /biz/ops/coupons/redeem` | 建议 | ai-shop 有 `/biz/coupon-redeem` 可替代 |
| 18-10 | 储值方案、账户、流水 | v20 S1 | 店主、店长 | `GET /biz/ops/stored-value` | 建议 | 台湾首期是否开放储值待定 |
| 18-11 | 新建储值方案 | v20 S2 | 店主 | `POST /biz/ops/stored-value/plans` | 建议 | 实付 + 赠送 |
| 18-12 | 会员列表、等级分布 | v20 U1 | 店主、店长 | `GET /biz/ops/members` | 建议 | 统一后由 v24 `/biz/members` 取代 |
| 18-13 | 会员打标签 | v20 U2 | 店主、店长 | `POST /biz/ops/members/{memberNo}/tags` | 建议 | 最多 10 个 |
| 18-14 | 会员设定（范围、下单自动入会、等级门槛） | v24 U3 | 店主 | `GET/PUT /biz/member-settings` | 已有 | 等级由平台算 |
| 18-15 | 新增会员（手机号） | v24 U4 | 店主、店长、员工 | `POST /biz/members` | 已有 | 只填手机的是线索（LEAD），收不到消息 |
| 18-16 | 标签列表、新增、改名、停用 | v24 U5 | 店主、店长 | `/biz/member-tags`、`PUT /biz/member-tags/{tagNo}` | 已有 | 系统标签只读 |
| 18-17 | 标签用量与合并（先预览） | v24 U6 | 店主、店长 | `GET .../usage`、`POST .../merge` | 已有 | `confirm=false` 只试算 |
| 18-18 | 条件存成人群（先试算人数） | v24 U1 | 店主、店长 | `POST /biz/member-segments/preview`、`POST /biz/member-segments` | 已有 | 条件：等级、标签、多久没来、消费额、某次群发没回来 |
| 18-19 | 人群列表、详情、删除 | v24 U7–U8 | 店主、店长 | `GET /biz/member-segments(/{no})`、`POST .../remove` | 已有 | 详情列被哪些活动、发券引用 |
| 18-20 | 定向发券：选对象并试算 | v24 C5 | 店主、店长 | `POST /biz/members/audience-preview`、`GET /biz/coupons?status=ACTIVE` | 已有 | 命中 / 收得到 / 跳过原因 |
| 18-21 | 定向发券：发出 | v24 C5 | 店主、店长 | `POST /biz/coupons/{couponNo}/issue` | 已有 | — |
| 18-22 | 发放结果与纪录 | v24 C6 | 店主、店长 | `GET /biz/coupon-issues` | 已有 | 发出、跳过、使用、带动金额 |
| 18-23 | 群发：选对象与场景、试算 | v24 N1 | 店主、店长 | `POST /biz/member-reach/plan` | 已有 | 频率上限：公告 3 天、唤回 14 天、发券通知 7 天 |
| 18-24 | 群发：写内容并发出 | v24 N2 | 店主、店长 | `POST /biz/member-reach/send` | 已有 | 标题 ≤ 64 字 |
| 18-25 | 发出去的、效果 | v24 N3–N4 | 店主、店长 | `GET /biz/member-reach/task(/{taskNo})` | 已有 | 打开、下单、带动金额；可把「没回来的」存成人群 |
| 18-26 | 平台活动列表、详情 | v24 X1 | 店主 | `GET /biz/platform-activity(/{no})` | 已有 | 可报名 / 已报名 / 已结束 |
| 18-27 | 平台活动报名、撤回 | v24 X2 | 店主 | `POST .../enrollment`、`POST .../withdraw` | 已有 | 已核准不能改 |
| 18-28 | 门店积分账户、签约 / 解约、确认对账单 | v24 I1 | 店主 | `/biz/points/store-account`、`store-agreement/sign\|terminate`、`store-statement-confirm` | 已有 | 签约送渠道码 `NEWEBPAY` |
| 18-29 | 积分明细 | v24 I2 | 店主、店长 | `GET /biz/points/store-entries` | 已有 | 兑换 / 结算 / 退回 |

### 接口

v20（`/biz/ops/*`，暂定路径，均为建议）：

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/ops/activities` | 活动整包 | 头 `X-Store-No` | `activities[{activityNo, storeNo, name, kind(percentOff/fullReduction/specialPrice/buyNGetM), status(running/paused/ended/draft), ruleLabel, scheduleLabel, quotaLabel?, budget?{minor, currency, scale}, goodsLabel?, budgetUsed?, quotaUsed?, quotaTotal?, chainName?}]` | 金额是 `{minor, currency, scale}` | 建议 |
| `POST /biz/ops/activities` | 建活动 | 头 `X-Store-No`；`name, kind, a, b, quota, recurring, weekdays?[], budgetMinor?, goods(文字), goodsNos?[], draft?` | `true` | `a`、`b` 是随 `kind` 变的规则参数（文字） | 建议 |
| `POST /biz/ops/activities/{activityNo}/publish` | 发布草稿 | 头 `X-Store-No` | `true` | — | 建议 |
| `POST /biz/ops/activities/{activityNo}/pause` | 暂停 | 头 `X-Store-No` | `true` | — | 建议 |
| `POST /biz/ops/activities/{activityNo}/resume` | 恢复 | 头 `X-Store-No` | `true` | — | 建议 |
| `POST /biz/ops/activities/{activityNo}/end` | 结束 | 头 `X-Store-No` | `true` | — | 建议 |
| `GET /biz/ops/coupons` | 卡券整包 | 头 `X-Store-No` | `coupons[{couponNo, storeNo, name, kind(cash/fullCut/percent), status(active/paused/ended), benefitLabel, validityLabel, issued, used, total?, threshold?{minor,currency,scale}, perUserLimit?}]` | — | 建议 |
| `POST /biz/ops/coupons` | 建券 | 头 `X-Store-No`；`name, kind, value, threshold, relative, days, until?(yyyy-MM-dd), total?(null=不限), perUser` | `true` | 数值以文字送 | 建议 |
| `POST /biz/ops/coupons/{couponNo}/pause` | 暂停券 | 头 `X-Store-No` | `true` | — | 建议 |
| `POST /biz/ops/coupons/{couponNo}/resume` | 恢复券 | 头 `X-Store-No` | `true` | — | 建议 |
| `POST /biz/ops/coupons/{couponNo}/send` | 发给会员 | 头 `X-Store-No`；`target: ALL\|LEVEL\|MEMBERS, level?(fresh/regular/loyal/sleeping), memberNos?[]` | `true` | — | 建议 |
| `GET /biz/ops/coupons/lookup` | 查券码 | 头 `X-Store-No`；query `code` | `couponNo, name, benefitLabel, codeTail, holder, expires, status(USABLE…)` | 10404 `COUPON_NOT_FOUND` | 建议 |
| `POST /biz/ops/coupons/redeem` | 核销 | 头 `X-Store-No`；`code, couponNo` | `true` | 10409 `COUPON_USED` | 建议 |
| `GET /biz/ops/stored-value` | 储值整包 | 头 `X-Store-No` | `plans[{planNo, storeNo, name, paid{}, bonus{}, status(active/inactive)}], accounts[{memberName, maskedPhone, available{}, held{}}], records[{recordNo, title, timeLabel, change{}, inbound}]` | — | 建议 |
| `POST /biz/ops/stored-value/plans` | 建储值方案 | 头 `X-Store-No`；`name, paidMinor, bonusMinor` | `true` | — | 建议 |
| `GET /biz/ops/members` | 会员整包 | 头 `X-Store-No` | `members[{memberNo, name, maskedPhone, level(fresh/regular/loyal/sleeping), spent{}, visits, lastVisitLabel, tags[], storedValue?{}}], memberStats{fresh, regular, loyal, sleeping}` | — | 建议 |
| `POST /biz/ops/members/{memberNo}/tags` | 改会员标签 | 头 `X-Store-No`；`tags[]` | `true` | 超过 10 个 10400 `TOO_MANY_TAGS` | 建议 |

v24（ai-shop 现有路由）：

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/member-settings` | 会员设定 | 头 `X-Store-No` | `memberScope(ENTITY/STORE), autoJoinOnOrder, sleepDays, loyalD90Orders, regularD90Orders, recountedAt` | — | 已有 |
| `PUT /biz/member-settings` | 改设定 | 头 `X-Store-No`；`memberScope?, autoJoinOnOrder?` | 同上 | — | 已有 |
| `POST /biz/members` | 新增会员 | 头 `X-Store-No`；`phone, remark?, tagNos?[], storeNo` | `memberNo, phone, remark, status(LEAD/…)` | 已存在时返回原记录并合并备注；后端按 `CN_MOBILE` 校验（mock 按台湾 09 开头，不合 70039） | 已有 |
| `GET /biz/member-tags` | 标签列表 | 头 `X-Store-No` | `[{tagNo, name, tagType(SYS/…), status(ACTIVE/DISABLED), count}]` | — | 已有 |
| `POST /biz/member-tags` | 新增标签 | 头 `X-Store-No`；`name` | 忽略 | 空或 >32 字 10400；重名 10409（mock） | 已有 |
| `PUT /biz/member-tags/{tagNo}` | 改名或启停 | 头 `X-Store-No`；`name?` 或 `enabled?` | 忽略 | 停用被引用的标签 10409（mock） | 已有 |
| `GET /biz/member-tags/{tagNo}/usage` | 标签用量 | 头 `X-Store-No` | `tag{…}, newThisMonth, activities[{refNo, name, status}], segments[{segmentNo, name, lastCount}]` | — | 已有 |
| `POST /biz/member-tags/{tagNo}/merge` | 合并标签 | 头 `X-Store-No`；`intoTagNo, confirm` | `affectedMembers, referencedActivities` | `confirm=false` 只预览 | 已有 |
| `GET /biz/member-segments` | 人群列表 | 头 `X-Store-No` | `[{segmentNo, name, lastCount, countedAt, rule{…}, broken}]` | `broken` = 条件引用的标签已失效 | 已有 |
| `GET /biz/member-segments/{segmentNo}` | 人群详情 | 头 `X-Store-No` | `segment{…}, matched, reachable, activities[{refNo, name, status}], couponIssues[{issueNo, title, issuedAt}]` | — | 已有 |
| `POST /biz/member-segments/preview` | 试算人数 | 头 `X-Store-No`；`scopeStoreNo, rule{level?, tagNos?[], idleDays?, spentMin?, reachTaskNo?, reachOutcome?(NOT_OPENED)}` | `matched` | `idleDays` 对应后端 `lastOrderBefore`，字段名待确认 | 已有 |
| `POST /biz/member-segments` | 存人群（新建或覆盖） | 头 `X-Store-No`；`segmentNo?, name, scopeStoreNo, rule{…}` | 忽略 | — | 已有 |
| `POST /biz/member-segments/{segmentNo}/remove` | 删人群 | 头 `X-Store-No` | 忽略 | — | 已有 |
| `POST /biz/members/audience-preview` | 发券对象试算 | 头 `X-Store-No`；`audiences[{type: ALL\|LEVEL\|TAG\|SEGMENT, ref?}]` | `matched, reachable, pushable, skips[{reason, count}]` | 后端还收 `scene?, forActivity?` | 已有 |
| `GET /biz/coupons` | 可发的券 | 头 `X-Store-No`；query `status=ACTIVE` | `[{couponNo, title, benefitValue, totalCount?, receivedCount, status}]` | 路由在；`status` 过滤与字段待确认 | 已有 |
| `POST /biz/coupons/{couponNo}/issue` | 定向发券 | 头 `X-Store-No`；`audiences[], audienceDesc, segmentNo?` | `issueNo` | 券非 ACTIVE 40014（mock）；后端 `IssueReq` 只有 `segmentNo, audiences`，`audienceDesc` 会被忽略 | 已有 |
| `GET /biz/coupon-issues` | 发放纪录 | 头 `X-Store-No`；query `couponNo?` | `[{issueNo, audienceDesc, issued, skipped, skipReasons[{reason(ALREADY_HAS/UNREACHABLE…), count}], amountMinor, usedCount, usedAmountMinor, issuedAt}]` | — | 已有 |
| `POST /biz/member-reach/plan` | 群发试算 | 头 `X-Store-No`；`audiences[], scene: NOTICE\|WAKEUP\|COUPON` | `matched, reachable, pushable, skips[{reason(TOO_SOON/OPT_OUT/LEAD/NO_ACCOUNT), count}]` | — | 已有 |
| `POST /biz/member-reach/send` | 群发 | 头 `X-Store-No`；`audiences[], scene, title, body, audienceDesc` | 任务一行（见下） | 标题空或 >64 字 10400 | 已有 |
| `GET /biz/member-reach/task` | 群发任务列表 | 头 `X-Store-No`；query `page=1, size=50` | `records[{taskNo, title, scene, audienceDesc, sent, pushed, skipped, opened, ordered, orderedAmountMinor, settled, sentAt}]` | 分页包 `records` | 已有 |
| `GET /biz/member-reach/task/{taskNo}` | 群发效果 | 头 `X-Store-No` | 任务一行 + `orderedMembers[{name, phone, amountMinor}]` | — | 已有 |
| `GET /biz/platform-activity` | 平台活动列表 | 头 `X-Store-No`；query `tab: ENROLLABLE\|ENROLLED\|ENDED` | `[{activityNo, name, triggerType(AMOUNT/QTY/其他=直降), threshold, benefitMinor, startAt, endAt, enrollDeadline, platformShareBp, perOrderPlatformMinor, perOrderMerchantMinor, status(RUNNING…), mine{status(SUBMITTED/APPROVED/REJECTED/WITHDRAWN), goodsNos[], quota, rejectReason}}]` | — | 已有 |
| `GET /biz/platform-activity/{activityNo}` | 平台活动详情 | 头 `X-Store-No` | 同列表一行 | — | 已有 |
| `POST /biz/platform-activity/{activityNo}/enrollment` | 报名 / 改报名 | 头 `X-Store-No`；`goodsNos[], quota` | 忽略 | 已核准 10409；商品空或名额 ≤0 10400（mock） | 已有 |
| `POST /biz/platform-activity/{activityNo}/withdraw` | 撤回报名 | 头 `X-Store-No` | 忽略 | 只能撤 SUBMITTED（mock 10409） | 已有 |
| `GET /biz/points/store-account` | 门店积分账户 | 头 `X-Store-No` | `typeName, rateText, pending, settling, settled, agreement?{termsVersion, settleMode, channel}, statements[{statementNo, month, points, amountMinor, status}]` | 未签约时 `agreement` 为空 | 已有 |
| `POST /biz/points/store-agreement/sign` | 签约 | 头 `X-Store-No`；`typeCode(NEARGO), termsVersion(v2026.10), settleMode: PER_ORDER\|PERIODIC, channel(NEWEBPAY)` | 忽略 | 后端 `SignRequest` 还有 `termsHash`，App 没送 | 已有 |
| `POST /biz/points/store-agreement/terminate` | 解约 | 头 `X-Store-No` | 忽略 | — | 已有 |
| `POST /biz/points/store-statement-confirm` | 确认对账单 | 头 `X-Store-No`；`statementNo` | 忽略 | 后端另有 `store-statement-dispute`，App 未接 | 已有 |
| `GET /biz/points/store-entries` | 积分明细 | 头 `X-Store-No`；query `typeCode=NEARGO, size=20` | `records[{bucket(REDEEM/SETTLE/REFUND), deltaPoints, voucherNo, at, afterPoints}]` | — | 已有 |

### 待决与注意
- **两套要统一（后端决定，第二批 2-2）**：`/biz/ops/*` 若作废，v20 活动改走 `/biz/activities`、卡券改走 `/biz/coupons*`（含 `/biz/coupons/{no}/status`、`/biz/coupon-redeem`）、会员改走 `/biz/members*`；v20 卡券详情的「发给会员」并到 v24 定向发券（18-21）。储值 ai-shop 另有大量接口，台湾首期开不开待定。
- v24 全部路由在 166 新门店上 70006（`/biz/member-*`、`/biz/members*`、`/biz/coupons*`、`/biz/coupon-issues`、`/biz/points/*`、`/biz/platform-activity*`），要后端在 `ShopStoreMemberRoutes` 登记（第二批 2-0）。
- 新增会员手机号：后端 `CN_MOBILE`，须按门店市场校验（台湾 09 开头，第二批 2-3）。
- `GET /biz/coupons?status=ACTIVE` 的过滤参数与返回字段是按 uniapp 推断的，联调时核对。
- 平台活动与门店积分台湾首期是否开放、积分可用渠道清单待定（第二批 2-8）；`termsVersion` 写死 `v2026.10`，建议改为从 `/biz/points/store-terms` 读并带 `termsHash`。
- v20 金额是 `{minor, currency, scale}`，v24 是 `xxxMinor` 整数，统一时一起定。
- 跳过原因 `skips[].reason` 的枚举取自 mock，以后端为准。

## 19. 连锁

建立连锁、把其他门店关联进来（先检查再提交）、总部审核关联、门店退出连锁（v19 H1–H5）。代码：`features/operations/chain_page.dart`（经 `operations_http.dart` 走 `/biz/ops/chain*`）。ai-shop 没有这组接口（真实能力在 management space / brand，未冻结），正式包由 `pendingContracts` 拦下，页面显示「尚未開放」（第三批 3-11，与 2-2 一起决定）。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 19-01 | 连锁状态（未加入 / 已加入：门店、待审、总部账号） | v19 H1、H1b | 店主 | `GET /biz/ops/chain` | 建议 | 一次读整包 |
| 19-02 | 建立连锁 | v19 H2 | 店主 | `POST /biz/ops/chain` | 建议 | 当前门店成为第一家 |
| 19-03 | 关联门店：检查 | v19 H3 | 店主 | `GET /biz/ops/chain/links/check` | 建议 | 有储值余额等未通过项时不能提交 |
| 19-04 | 关联门店：提交 | v19 H3 | 店主 | `POST /biz/ops/chain/links` | 建议 | 进入待审 |
| 19-05 | 审核关联（核准 / 驳回） | v19 H4 | 店主（总部） | `POST /biz/ops/chain/links/{changeNo}/approve\|reject` | 建议 | 驳回理由回给门店；结果未确认前不重发 |
| 19-06 | 门店退出连锁 | v19 H5 | 店主 | `POST /biz/ops/chain/stores/{storeNo}/leave` | 建议 | 仅 mock；后端无此能力 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/ops/chain` | 连锁快照 | 头 `X-Store-No` | `chain?{chainNo, name, status(active/disabled), role, stores[{storeNo, name, current}], pending[{changeNo, storeName, state(pendingApproval/approved/rejected), submittedLabel, reason?}], grants[{account, role}], linkableStores[{storeNo, name}]}` | 未加入时 `chain` 为 null | 建议 |
| `POST /biz/ops/chain` | 建立连锁 | 头 `X-Store-No`；`name` | `true` | — | 建议 |
| `GET /biz/ops/chain/links/check` | 关联前检查 | 头 `X-Store-No`；query `storeNo` | `storeNo, passed, checks[{code(STORE_ACTIVE/STORED_VALUE/ACTIVITY_CONFLICT), passed, detail}]` | — | 建议 |
| `POST /biz/ops/chain/links` | 提交关联 | 头 `X-Store-No`；`storeNo` | `true` | 不可关联 10404；检查未过 10409 `LINK_CHECK_FAILED` | 建议 |
| `POST /biz/ops/chain/links/{changeNo}/approve` | 核准 | 头 `X-Store-No` | `true` | 已处理 10409 `ALREADY_DECIDED` | 建议 |
| `POST /biz/ops/chain/links/{changeNo}/reject` | 驳回 | 头 `X-Store-No`；`reason` | `true` | 理由空 10400 `REASON_REQUIRED`；已处理 10409 | 建议 |
| `POST /biz/ops/chain/stores/{storeNo}/leave` | 退出连锁 | 头 `X-Store-No` | `true` | 当前门店或不在连锁 10404 | 建议 |

### 待决与注意
- 真实流程预计在 ai-shop 的 management space（`/biz/management/*`，见 16）与 brand 上，带 `expectedRevision` 与幂等键；冻结时 App 要把 `HttpOperationsReader` 的路径整体换掉。
- 退出连锁时储值余额、进行中的连锁活动怎么处理待确认；连锁活动在单店能否暂停待确认。
- `submittedLabel`、`role` 等是服务端拼好的文字，正式契约建议回结构化时间与角色码。

## 20. 团购

拼团（活动设定、开团、列表、详情、散团、成团 / 未成团）、社区集单（活动设定、期列表、截单、未达起订的决定、备货单）、邻里求团报价池（报价、改价）（v26 Z01–Z13）。代码：`features/group_buy`（`group_buy_api.dart`、`group_buy_pages.dart`），mock `dev/mock/mock_v26.dart`。拼团、集单、报价走 ai-shop 现有路由（11/08 已为新门店登记）；建活动用 `/biz/activities?triggerType=` 是假设，正式包拦下。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 20-01 | 拼团 / 集单活动列表 | v26 Z01、Z07 | 店主、店长 | `GET /biz/activities?triggerType=GROUP\|CUTOFF` | 待确认 | 价格、人数、截单规则来自活动 |
| 20-02 | 建拼团活动 | v26 Z01 | 店主、店长 | `POST /biz/activities` | 待确认 | 成团人数 ≥2、团价、成团时限（小时） |
| 20-03 | 建集单活动 | v26 Z07 | 店主、店长 | `POST /biz/activities` | 待确认 | 每日截单时间、取货偏移天数与时间、起订量、每期上限 |
| 20-04 | 拼团列表（进行中 / 成团 / 未成团） | v26 Z02、Z06 | 店主、店长 | `GET /biz/groups` | 已有 | 倒计时、还差几人 |
| 20-05 | 开团（选商品、活动、自提点） | v26 Z03 | 店主、店长 | `GET /biz/group/pickups`、`POST /biz/groups` | 已有 | — |
| 20-06 | 拼团详情 | v26 Z04、Z06 | 店主、店长 | `GET /biz/group/{groupNo}` | 已有 | 未成团显示原因与退款额 |
| 20-07 | 散团 | v26 Z05 | 店主、店长 | `POST /biz/group/{groupNo}/dissolve` | 已有 | 没成团全额退；成团后不能散 |
| 20-08 | 集单期列表（收单中 / 待处理 / 已确认 / 已取消） | v26 Z08 | 店主、店长 | `GET /biz/period` | 已有 | 有待处理时先打开待处理 |
| 20-09 | 期详情（按商品、按自提点） | v26 Z09 | 店主、店长 | `GET /biz/period/{periodNo}` | 已有 | — |
| 20-10 | 提前截单 | v26 Z09 | 店主、店长 | `POST /biz/period/{periodNo}/cutoff` | 已有 | 只限收单中 |
| 20-11 | 未达起订：照做或取消 | v26 Z10 | 店主、店长 | `POST /biz/period/{periodNo}/decision` | 已有 | 截单后 14 小时内决定，逾时自动取消退款 |
| 20-12 | 备货单 | v26 Z11 | 店主、店长、员工 | `GET /biz/period/{periodNo}/purchase-lines` | 已有 | 按 SKU 汇总 |
| 20-13 | 求团报价池 | v26 Z12 | 店主、店长 | `GET /biz/group-request/pool` | 已有 | 收集中 / 已有报价 |
| 20-14 | 报价 | v26 Z13 | 店主、店长 | `POST /biz/group-request/{requestNo}/quote` | 已有 | 已报过价时视为改价 |
| 20-15 | 改价 | v26 Z13 | 店主、店长 | `POST /biz/quote/{quoteNo}/revise` | 已有 | 锁定后不能改；涨价公示旧价 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/activities` | 拼团 / 集单活动 | 头 `X-Store-No`；query `triggerType: GROUP\|CUTOFF` | `[{activityNo, name, triggerType, status(RUNNING…), goods[{goodsNo, title, priceMinor}], triggerQty, benefitAmountMinor, groupHours, cutoffTime, pickupOffset, pickupFrom, minQty?, periodQuota?}]` | 路由在（`model!=unified`），是否支持 GROUP / CUTOFF 与这些字段待确认；`pendingContracts` 拦 `^/biz/activities$` | 待确认 |
| `POST /biz/activities` | 建活动 | 头 `X-Store-No`；`name, triggerType, goodsNos[], goods[{goodsNo, title}]`；GROUP：`triggerQty, benefitAmountMinor, groupHours`；CUTOFF：`cutoffTime(HH:mm), pickupOffset, pickupFrom(HH:mm), minQty?, periodQuota?` | 活动一行 | 名称或商品空 10400（mock） | 待确认 |
| `GET /biz/groups` | 拼团列表 | 头 `X-Store-No`；query `status: OPEN\|FORMED\|FAILED` | `[{groupNo, status(PENDING/OPEN/FORMED/FAILED), title, initiatorNickname, pickupName, basePrice, groupPrice, minCount, joinedCount, expireAt, members[{nickname}], activityName, failReason, refundedMinor}]` | `basePrice`、`groupPrice` 为最小单位 | 已有 |
| `GET /biz/group/pickups` | 自提点 | 头 `X-Store-No` | `[{pickupNo, name}]` | — | 已有 |
| `POST /biz/groups` | 开团 | 头 `X-Store-No`；`goodsNo, activityNo, pickupNo?` | 拼团一行 | 商品不存在 10404（mock） | 已有 |
| `GET /biz/group/{groupNo}` | 拼团详情 | 头 `X-Store-No` | 拼团一行 | — | 已有 |
| `POST /biz/group/{groupNo}/dissolve` | 散团 | 头 `X-Store-No`；`reason?` | 拼团一行 | 已成团 10409（mock） | 已有 |
| `GET /biz/period` | 集单期列表 | 头 `X-Store-No`；query `status: OPEN\|SHORT\|CONFIRMED\|CANCELLED` | `[{periodNo, activityName, periodDate, cutoffAt, pickupDate, pickupFrom, status, qty, customers, amountMinor, minQty?, periodQuota?, decideDeadline?}]` | — | 已有 |
| `GET /biz/period/{periodNo}` | 期详情 | 头 `X-Store-No` | `period{…}, byGoods[{title, qty}], byPickup[{pickupName, qty}]` | — | 已有 |
| `POST /biz/period/{periodNo}/cutoff` | 提前截单 | 头 `X-Store-No` | 忽略 | 非 OPEN 40025（mock） | 已有 |
| `POST /biz/period/{periodNo}/decision` | 未达起订的决定 | 头 `X-Store-No`；`action: PROCEED\|CANCEL` | 忽略 | 非 SHORT 40025（mock） | 已有 |
| `GET /biz/period/{periodNo}/purchase-lines` | 备货单 | 头 `X-Store-No` | `[{skuNo, goodsNo, title, spec, qty}]` | — | 已有 |
| `GET /biz/group-request/pool` | 求团报价池 | 头 `X-Store-No` | `[{requestNo, title, desc, wantCount, expectQty, budgetMinor?, pickupName, status(COLLECTING/QUOTED/LOCKED/CONFIRMED/CLOSED), quotes[{quoteNo, merchantName, unitPriceMinor, minQty, note, mine, locked, breachCount, previousPriceMinor?}]}]` | 报价状态 ACTIVE / WITHDRAWN / BREACH | 已有 |
| `POST /biz/group-request/{requestNo}/quote` | 报价 | 头 `X-Store-No`；`unitPriceMinor, minQty, note` | 忽略（后端回 `QuoteVO`） | 价或量 ≤0 10400；需求已关 20004（mock）；后端还收 `validDays?` | 已有 |
| `POST /biz/quote/{quoteNo}/revise` | 改价 | 头 `X-Store-No`；`unitPriceMinor, minQty, note` | 忽略（后端回 `QuoteVO`） | 已锁定 20004（mock） | 已有 |

### 待决与注意
- 台湾首期开不开团购、`/biz/activities` 是否支持 `GROUP` / `CUTOFF` 及上表字段，待后端确认（第二批 2-4）；确认后从 `pendingContracts` 移除 `^/biz/activities$`。
- 166 新门店上 `/biz/groups`、`/biz/group/*`、`/biz/period*`、`/biz/group-request/*`、`/biz/quote/*` 都 70006，要登记（第二批 2-0）。
- 金额：App 建拼团时把输入的元 ×100 作为 `benefitAmountMinor`，显示也按两位小数（`gbMoney`）；TWD 实际 scale 要和后端对齐。
- 集单「未达起订 14 小时内决定，逾时自动取消退款」由后端定时执行，App 只显示 `decideDeadline`。

## 21. 评价

商户的评价列表（App 端筛选：待回、全部、低分、有图）、一次性回复、对 1–2 星评价一次性申诉、申诉进度（v27 V01–V03，含 v9 R01「待回评」）。代码：`features/standard_onboarding/reviews_page.dart`，走 ai-shop `BizReviewController`（权限 `biz:review`）。评价属于商户（商品与订单），不是单店。11/08 登记后 166 上 `GET /biz/review` 已读通。

### 功能矩阵

| # | 功能 | 原型 | 角色 | 接口 | 状态 | 说明 |
|---|---|---|---|---|---|---|
| 21-01 | 评价列表与筛选、统计 | v27 V01 | 店主、店长（有 `biz:review` 的角色） | `GET /biz/review` | 已联调 | 筛选与统计在 App 端算 |
| 21-02 | 回复（一次） | v27 V02 | 同上 | `POST /biz/review/{reviewNo}/reply` | 已有 | ≤100 字，送出前提示只能回一次 |
| 21-03 | 申诉（一次，只限 3 星以下） | v27 V03 | 同上 | `POST /biz/review/{reviewNo}/appeal` | 已有 | 理由 ≤120 字，可附图 |
| 21-04 | 申诉进度 | v27 V03 | 同上 | `GET /biz/review`（`appeal` 字段） | 已联调 | 审核中 / 成立（评价隐藏）/ 驳回 |

### 接口

| 接口 | 功能 | 输入 | 输出（关键字段） | 错误 / 备注 | 状态 |
|---|---|---|---|---|---|
| `GET /biz/review` | 评价列表 | 头 `X-Store-No` | `[{reviewNo, nickname, rating, content, spec, reply, createdAt, subScores{goods, fulfillment, service}, images[], appeal?{status(PENDING/UPHELD/REJECTED), reason, verdict, at}}]` | 全商户，不分页、无筛选 | 已联调 |
| `POST /biz/review/{reviewNo}/reply` | 回复 | 头 `X-Store-No`；`reply` | 评价一行 | 空 10400 `EMPTY_REPLY`；已回过 10409 `ALREADY_REPLIED`（mock） | 已有 |
| `POST /biz/review/{reviewNo}/appeal` | 申诉 | 头 `X-Store-No`；`reason, images[]` | 评价一行 | 理由空或 ≥3 星 10400 `BAD_APPEAL`；已申诉 10409 `ALREADY_APPEALED`（mock） | 已有 |

### 待决与注意
- 第二批 2-0：`/biz/review*` 要在 `ShopStoreMemberRoutes` 登记，166 才能读。
- 可选（第二批 2-10）：`/biz/review` 加筛选与汇总，评价多时 App 端算不划算。
- 申诉附图要先走 `/biz/upload/image` 拿 URL；`appeal.verdict` 是平台审核说明。
- 回复、申诉上限（100 / 120 字、3 星以下）App 照 ai-shop `REVIEW_RULES` 写死，后端改规则时要同步。

---

## 附录 A　App 与后端不一致的地方（11/08 对照 `origin/codex/ai-shop-codex`）

对照代码时发现的，按谁来改分。改之前这些功能在真实服务器上会出错或显示不全。

| 模块 | 不一致 | 建议谁改 |
|---|---|---|
| 2 门店 | `/biz/store` 的 `fulfillmentReach` 后端是一个字符串，App 收发数组 | 定一种；App 可改成按后端 |
| 2 门店 | 自画范围 `geometry` 后端要 JSON 字符串 `[[lngE6,latE6]]`（经度在前），App 送 `{points:[[latE6,lngE6]]}`；坐标系（gcj02 / WGS84）未定；`scope-preview` 不看 `geometry` | 后端定格式与坐标系，App 跟着改 |
| 2 门店 | `GET /biz/store` 不回 `name`、`slug`、`status`、`isDefault`，App 的门店唯读 / 停权提示（v23 W13）出不来 | 后端补字段，或 App 改读门店列表的状态 |
| 3 方案 | `/biz/plan` 的 `suspendedStores` 后端是门店号列表，App 要 `{storeNo, name, openOrders}`；没有 `graceUntil`（App 用到期日 + 7 天） | 后端补对象或 App 用门店列表补名称 |
| 3 方案 | 门店订阅的到期日 App 读 `currentPlan.effectiveUntil`，后端没回（上下文接口里叫 `planEndsAt`） | App 改读 `planEndsAt`，或后端补 |
| 4 进件 | 签约（v12 J03）App 依赖 `signRequired` / `signUrl`，后端没回 | 后端补 |
| 10 列印 | 列表后端回分页 `PageData.records`，App 当数组；暂停应调 `/status-changes` 而不是 `PUT`；补印用 `targetPrinterNo`，要 `expectedRevision` 与 `reason`；命令类接口回 `PrintOperation` 不是作业；`health` 没有 `issues`；场景策略的印表机与份数在 `policy` 里 | App 按后端改 `PrintApi`（另：166 未部署列印，404） |
| 13 进销存 | 原先只登记了 `stock-sync`、`sell-rules` | 已解决（11/08 发版） |
| 10 列印 | 166 原先全部 404（`shop.print.enabled` 没开）；11/08 10:28Z 已打开。读接口 `health`、`jobs`、`scene-policies` 必须带 `?storeNo=` | App 读接口已改并在 166 读通；写接口（暂停、补印、新增印表机）仍按上一行要改；新增飞鹅 / Agent 需 166 配列印密钥 |
| 12 自提 | `/biz/pickup/*` 回 10403：`BizContext.pickupNos()` 为空（门店不是自提点）就拒，与权限码无关 | App 用 `GET /biz/context` 的 `pickupNos` 判断，为空显示「本店还没有自提点」并引导 `GET /biz/pickup-points/candidates?storeNo=` / `POST /biz/pickup-points`（`storeNo, name, address, latE6, lngE6, openHours, communityNo`），建好后重读 `/biz/context` |
| 15 服务业 | 停约 `/biz/appointment-slots/{slotNo}/close` 后端不收请求体，App 送 `reason`；后端另有 `/biz/stores/{storeNo}/appointment-slots` 时段模型，时段来源要定 | 冻结服务业契约时一起定 |
| 14、15 餐饮 / 服务业 | 建议契约里金额用 `{currency, minor}`（零售用整数 `xxxMinor`）；阶段值是 Dart 枚举名（如 `dineIn`）；服务业按技师姓名而不是员工号 | 冻结契约前统一：金额、枚举用大写码、人员用员工号 |
| 17 考勤 | 请假、补卡请求送的是界面文字，应送代码（`PERSONAL\|SICK\|ANNUAL\|OTHER`、`IN\|OUT`） | App 改（契约是建议的，改起来最便宜） |
| 18 营销 | 发券 `IssueReq` 后端没有 `audienceDesc`（会被忽略）；积分签约少了后端要的 `termsHash`，`termsVersion` 写死 | App 改 |
| 18 营销 | 两套接口并存：v20 走 `/biz/ops/*`（后端没有），v24 走 `/biz/member-*`、`/biz/coupons` 等（后端有） | 决定用哪套；建议统一到后端已有的那套 |
| 16 员工 | 授权门店时能否用自定义角色码，未确认 | 后端确认 |
| 1、6 | 后端有 `/biz/push-token`、`/biz/auth/staff-login`、`/biz/plan/trial`，App 没调；旧壳 `features/authentication` 调的 `/biz/auth/session`、`/biz/auth/market` 后端没有（正式入口不用这个壳） | 按需接入；旧壳可删 |

## 附录 B　正式包拦下的接口（`features/v5/contracts.dart`）

后端冻结一组，App 就删掉对应一行，再联调。

| 路径前缀 | 模块 | 说明 |
|---|---|---|
| `/biz/food/` | 14 餐饮 | 建议契约 |
| `/biz/beauty/`、`/biz/appointment-slots/` | 15 服务业 | 停约路由后端已有，跟时间线一起放开 |
| `/biz/ops/` | 18 营销（v20）、19 连锁 | 与 v24 那套二选一 |
| `/biz/funds/` | 8 资金 | |
| `/biz/softpos/` | 9 感应收款 | 对接方式待蓝新 |
| `/biz/payment/offline-records` | 7 外部记账 | |
| `/biz/auth/identities` | 1 统一登录 | 正式包现在走店主登录 |
| `/biz/feedback`、`/app/version` | 6 系统状态 | |
| `/biz/merchant/onboarding`、`/biz/merchant/payment/bind`、`/biz/store/{s}/entity` | 4 进件 | |
| `/biz/express/companies` | 12 订单 | 没有时可先用叫快递 |
| `/biz/stores/{s}/usage`、`…/component-refunds` | 3 商城 | |
| `/biz/hr/` | 17 考勤与工资 | ai-shop 完全没有 |
| `/biz/activities` | 20 团购 | 166 已登记；字段与 `triggerType=GROUP\|CUTOFF` 确认后可放开 |

## 附录 C　接手顺序建议

1. **App**：按附录 A 改「App 改」的几项（列印请求与 404 说明、自提点判断、考勤代码、发券与积分字段、方案读法），各自补测试。
2. **后端**：回附录 A 里「后端补」的字段；166 打开 `shop.print.enabled`。
3. **联调**：重跑 `test/live/batch1_smoke_test.dart`（目标 38/38），再在模拟器上逐页走主链路；进销存在 166 通过后打开 `inventoryOpen`。
4. **冻结 P0 契约**：统一登录、商户资料提交、资金、外部记账、问题回报与版本、快递公司；每冻结一组，删附录 B 的一行。
5. **按首批店型**冻结餐饮或服务业契约（先统一金额与枚举写法）。
6. **P1**：会员营销两套统一、连锁、团购开放与否、考勤与工资后端立项。

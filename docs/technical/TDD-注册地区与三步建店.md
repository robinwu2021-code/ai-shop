# TDD-注册地区与三步建店

状态：**已实现**（2026-11-05；后端 062a45855 + App 端见 §6；均为本地提交，未推送）
关联需求：用户 2026-11-05 两轮要求（见下方 AC）；原型 `ai-shop-bapp` · `docs/prototypes/core-pages/v4-store-onboarding.html`（S01–S10，提交 48b7559）；
前置：`TDD-B端建店默认经营市场.md`（部署级默认市场、store-setup、quick-start）。
档位：**2**（账号新增市场字段、门店新增自填行业字段；login / store-setup / quick-start 契约变化；新增两个配置项；跨端）
创建：2026-11-05

---

## L1 定位

经营地区是**账号**的事实：注册时定一次，之后建店、方案、支付都按账号走，App 里不再展示、不再切换。
测试阶段在注册页底部选（一行小字），正式运营按地区分别打包（包里写死地区，不显示选择）。
同时把「我的门店」与个人设置拆开，建店改为「行业 → 资料 → 确认」三步；行业两级都可选「其他」自填；地址可手动输入，有地图能力的地区可地图选点。

## L2 结构

| 部分 | 现状 | 本期 |
|---|---|---|
| 账号地区 | 无；部署级 `shop.merchant.default-market`（本实例 TW），App 本地记忆所选地区 | `usr_account.market` 注册时写入；读不到按部署默认 |
| 可注册地区 | 只有部署默认 | 新配置 `shop.merchant.registration-markets`（逗号分隔，缺省 = 部署默认）；本机 `TW,CN,AE` |
| login | 无地区入参 | 可选 `marketCode`，**只在新建账号时生效**，已有账号忽略 |
| store-setup | 返回部署默认市场 | 只返回**账号**市场；每个市场带 `mapProvider`；顶层带 `otherBusinessTypeCode` |
| quick-start | `market` 可传、须等于部署默认 | 市场取账号（传了且不同则拒）；新增 `industryText`、`subIndustryText`、`addressDetail`；行业可为 `OTHER` |
| 门店 | `business_type_code`、`address`、`address_detail`、`lat_e6/lng_e6` 已有 | 新增 `industry_text`、`sub_industry_text`；`/biz/stores/mine` 带回这两项 |
| App | 地区页、地区横幅与切换按钮、我的门店混放账号设置、单页建店 | 见 §2 Flutter |

## §0 对账一 · 需求 → 设计

| AC | 需求（可断言） | 落点 |
|---|---|---|
| AC1 | Given 可注册地区含 CN When 新号码带 `marketCode=CN` 首次登录 Then `usr_account.market=CN` | `AuthServiceImpl#findOrCreate` 建号分支 |
| AC2 | Given 已有账号（market=TW）When 带 `marketCode=CN` 登录 Then market 仍为 TW | 同上，只在建号时写 |
| AC3 | Given `marketCode` 不在可注册地区或为空 When 建号 Then 写部署默认 | `RegistrationMarkets#resolve` |
| AC4 | Given 账号 market 为空（存量账号）When 读 store-setup / quick-start Then 按部署默认 | `AuthService#accountMarket` 回落 |
| AC5 | When 读 store-setup Then `markets` 只有账号市场一项（未启用 / 无已发布经营类型则为空），带 `mapProvider`（CN 且地图可用 = `AMAP`，否则 null），顶层带 `otherBusinessTypeCode`（配置的兜底经营类型且已发布，否则 null） | `BizFoundationController#storeSetup` |
| AC6 | When quick-start 传 `market` 且不等于账号市场 Then 拒绝（10400），不建壳；不传则用账号市场 | `BizMerchantController#quickStart` |
| AC7 | Given `industryCode=OTHER` When quick-start Then 必须带非空 `industryText`（≤20）、`subIndustryCode=OTHER`、`subIndustryText`（≤20），且 `businessTypeCode` 等于 `otherBusinessTypeCode`；否则拒绝 | 同上校验 |
| AC8 | Given 已有行业 + `subIndustryCode=OTHER` When quick-start Then 必须带 `subIndustryText`，`businessTypeCode` 等于 `industryCode`（沿用默认映射） | 同上 |
| AC9 | 建店后 `mch_store.industry_text / sub_industry_text / address_detail` 落库；`GET /biz/stores/mine` 每家店带 `industryText`、`subIndustryText` | `MerchantPortImpl#quickStart`、`StoreVO` |
| AC10 | App 测试包：注册页主按钮下一行小字选地区（TW/CN/AE），登录时带 `marketCode`；正式包（`NEARGO_MARKET` 已设）不显示，固定该地区 | Flutter `StandardLoginPage` |
| AC11 | App 登录后不出现地区页、地区横幅、地区切换 | Flutter `StandardMarketGate`（单一市场自动通过）、`_StandardHome` |
| AC12 | 「我的门店」只放门店；右上角进入「个人中心」（账号、登录密码、界面语言、退出登录）；门店工作台「我的」不再有地区按钮 | Flutter `StandardProfilePage`、`_StandardHome` |
| AC13 | 建店三步：行业（两级「其他」可输入）→ 资料（名称、地址必填，楼层门牌选填，`mapProvider` 非空时有「地图选点」）→ 确认（FREE 一句话 + 方案对比入口）；顶部扁平三段进度条 | Flutter `StandardStoreCreationPage` |

孤立项：无。

**不做（Out of Scope）**
- 地图选点的地图组件本身（Flutter 端的高德 / Google SDK、Key 申请）：本期只做能力开关与手动地址；`mapProvider` 为空时不显示按钮。本机无地图 Key，CN 也会是 null。
- 台湾 / 阿联酋接 Google 地图。
- 第二家及以后的门店创建入口（quick-start 只建首店）：「我的门店」有店时不显示创建按钮，与原型 S04 的偏差见 §6。
- 已有账号改地区。

## §1 现状与影响面

- 后端：`AuthServiceImpl#findOrCreate`（建号）、`BizAuthController#login`（LoginReq）、`BizFoundationController#storeSetup`、`BizMerchantController#quickStart` → `MerchantPortImpl#quickStart`、`MerchantEntityServiceImpl`（StoreVO 组装）。
- 会被改到的已上线行为：store-setup 的市场来源（部署默认 → 账号，存量账号回落到部署默认，**结果不变**）；quick-start 的市场校验（部署默认 → 账号，存量同上）。
- 明确不受影响：C 端登录（不传 `marketCode`，建号写部署默认，与今天等价）、员工登录、支付域、商城。

## §2 方案

### 契约变更

- **库表**（下一个可用迁移号，当前最大 V628）
  - `usr_account.market VARCHAR(8) NULL` —— 不回填（生产部署默认是 CN，按部署默认回落即可，迁移里写死任何值都会错）。
  - `mch_store.industry_text VARCHAR(40) NULL`、`mch_store.sub_industry_text VARCHAR(40) NULL`。
- **端点**
  - `POST /biz/auth/login`：`LoginReq` 增可选 `marketCode`。
  - `GET /biz/merchant/store-setup`：`markets[]` 只含账号市场；每项新增 `mapProvider`（`"AMAP"` | null）；顶层新增 `otherBusinessTypeCode`（string | null）。
  - `POST /biz/merchant/quick-start`：新增 `industryText`、`subIndustryText`、`addressDetail`（≤64）；`industryCode` / `subIndustryCode` 可为 `OTHER`。
  - `GET /biz/stores/mine`：`StoreVO` 末尾新增可空 `industryText`、`subIndustryText`（保留旧构造）。
  - `GET /biz/merchant/profile` 与登录响应的 `merchant`（`MerchantProfileVO`）：末尾新增可空 `account`
    —— 个人账号已验证的登录标识，有手机号给手机号（与 `phone` 同格式），否则给归一化邮箱；员工会话或取不到为 null。
    **AC12 的后端支撑**（2026-11-05 追加）：邮箱账号登录后 `phone` 为空，App 重启后个人中心无从显示账号。
- **配置项**
  - `shop.merchant.registration-markets`（缺省 = `default-market`）。本机 `TW,CN,AE`。
  - `shop.merchant.other-business-type`（缺省空 = 不提供顶层「其他」）。本机设为已发布的零售经营类型。
- **i18n**：后端无新增错误码（复用 10400）。Flutter 新增词条若干（繁 / 简）。

### 模块设计

后端（ai-shop-integ）

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/shop-app/src/main/resources/db/migration/V629__account_market_and_store_industry_text.sql` | 三列；MySQL 方言（见 known-sql-dialect 守卫） |
| 修改 | `UsrAccount`、`AuthService(Impl)`、`BizAuthController` | `market` 字段；`LoginCommand` 带 `marketCode`；建号时写入；`accountMarket(userNo)` |
| 新增 | `RegistrationMarkets`（shop-core 或 shop-app 配置组件） | 可注册地区解析 |
| 修改 | `BizFoundationController`、`BizMerchantController`、`MerchantAdminPort.QuickStartCommand`、`MerchantPortImpl`、`MchStore`、`StoreVO`、`MerchantEntityServiceImpl` | 见契约 |
| 修改 | `BizMerchantController.MerchantProfileVO`、`AuthService#loginIdentifier` | AC12 后端支撑：profile / 登录响应带 `account` |
| 修改 | `docs/api/openapi-b-onboarding.json`、`docs/api/examples/b-foundation.json` | 契约真源；示例随 `MerchantProfileVO` 形状补 `account`（`FoundationHttpExamples` 按示例比对字段集） |
| 新增 | 对应测试（见 §5） | |

Flutter（ai-shop-bapp-mall）

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `onboarding_forms.dart` | 注册页地区小字选择（测试包），`marketCode` 随登录发送 |
| 修改 | `onboarding_api.dart`、`onboarding_controller.dart` | login 带 `marketCode`；quickStart 新字段；store-setup 新字段 |
| 修改 | `onboarding_store_setup.dart` | 单一市场自动通过，不显示地区页 |
| 修改 | `onboarding_app.dart` | 去掉地区横幅 / 按钮；我的门店只放门店 + 头像入口；工作台「我的」去地区按钮、改个人中心入口 |
| 新增 | `onboarding_profile.dart` | 个人中心 |
| 修改 | `onboarding_store_creation.dart` | 三步建店 |
| 修改 | `lib/l10n/*.arb` + 生成物 | 新文案 |
| 修改 / 新增 | 相关 widget 测试 | |

## §3 选型

| 方案 | 结论 |
|---|---|
| 地区存账号（`usr_account.market`） | ✅ 用户要求「登录后根据注册账号判断」 |
| 继续用 App 本地记忆 | ❌ 换设备就丢，且与「注册时设定」矛盾 |
| 迁移里把存量账号回填 TW | ❌ 生产部署默认是 CN；运行时回落到部署默认，结果与今天一致 |
| 顶层「其他」新建专门经营类型 | 暂缓：需运营发布目录。先用配置的兜底经营类型（`other-business-type`），自填文字单独存；以后运营发布了「其他」类型只改配置 |

## §4 风险

| 风险 | 缓解 |
|---|---|
| 测试选了 CN / AE，但该市场未启用或无已发布经营类型 → 建店第一步为空 | store-setup 如实返回空；App 显示「该地区暂未开放建店」，不造数据 |
| 正式包误用测试入口 → 用户自选地区 | 正式包靠 `NEARGO_MARKET` 编译常量，未设时才显示选择；`main_prod` 发版检查清单写明必须设 |
| 「我的门店」不再显示刷新按钮 | 改为下拉刷新 |

## §5 对账三 · 实现 → 需求（测试）

先写测试再实现：`RegistrationMarketsTest` 首跑为编译红（类不存在）；其余用例与实现同轮落地，
因此「红」以消融为准 —— 每条关键规则都撤掉对应实现行、确认对应用例变红、再还原并 `touch`。

| AC | 测试方法 | 结果 | 消融（撤掉 → 变红的用例） |
|---|---|---|---|
| AC1 | `RegistrationMarketFlowTest#marketIsFixedAtRegistrationOnly`（HTTP 登录 + H2 `usr_account`） | 绿 | 删 `createAccount` 里 `user.setMarket(...)` → 本用例与 AC3 用例红 |
| AC2 | 同上（同号带 CN 再登录仍为 TW） | 绿 | 在已有账号分支也写 market → 红（`已有账号的地区不随登录参数改`） |
| AC3 | `RegistrationMarketsTest` ×2；`RegistrationMarketFlowTest#unknownOrMissingMarketFallsBackToTheDeploymentDefault` | 绿 | `resolve` 改成不查清单 → 3 条全红 |
| AC4 | `RegistrationMarketFlowTest#legacyAccountWithoutMarketReadsTheDeploymentDefault`；`BizStoreSetupTest#ac4ac5StoreSetupMarketIsTheAccountsAndStaffFallBackToTheDeploymentDefault` | 绿 | `accountMarket` 去掉空值回落 → 红 |
| AC5 | `BizStoreSetupTest#ac5StoreSetupCarriesMapProviderAndThePublishedOtherBusinessType`、`#ac4ac5…` | 绿 | `mapProvider` 去掉 `"CN".equals` → 红 |
| AC6 | `RegistrationMarketFlowTest#quickStartRejectsAMarketOtherThanTheAccounts`（账号 TW、请求 CN → 10400 且无 `mch_account`）；`BizStoreSetupTest#ac6QuickStartMarketIsTheAccountMarket` | 绿 | 删「必须等于账号市场」→ 场景用例拿到 code 0、单测红。初版场景用「账号 CN 请求 TW」，消融后仍绿（TW 在测试库未启用，先被另一道拦下），已改写 |
| AC7 | `BizStoreSetupTest#ac7TopLevelOtherNeedsBothTextsAndTheConfiguredBusinessType` | 绿 | 删 `otherBusinessType.equals(type)` → 红 |
| AC8 | `BizStoreSetupTest#ac8SubIndustryOtherNeedsTextAndKeepsTheDefaultMapping` | 绿 | 删 `subIndustryText` 必填 → 红 |
| AC9 | `RegistrationMarketFlowTest#selfDescribedIndustryIsStoredAndReadBack`、`#quickStartRejects…`（`address_detail`）；`BizStoreSetupTest#ac9AddressDetailIsAtMost64AndTextsReachTheCommandOnlyForOther`；`MerchantEntityStoreMarketTest#selfDescribedIndustryComesBackAsOther` | 绿 | 删 `extras.accept(store)` → 两个场景红；`StoreAdminServiceImpl#toVO` 不带文字 → `/biz/stores/mine` 断言红；`withMarket` 不报 OTHER → `MerchantEntityStoreMarketTest` 红 |
| AC12（后端） | `BizAuthEmailLoginTest#emailAccountProfileCarriesTheEmailAsAccount`；`RegistrationMarketFlowTest#phoneAccountProfileCarriesThePhoneAsAccount` | 先红后绿 | `loginIdentifier` 去掉邮箱回落 → 邮箱用例红、手机号用例仍绿 |

回归（2026-11-05 最终一轮，`mvn-neargo -o -q -pl shop-app -am test`）：
RegistrationMarketsTest 2/2、SmsMockPrefixTest 3/3、EmailOtpAuthTest 14/14、ProfileAndPasswordTest 9/9、
MerchantEntityStoreMarketTest 3/3、BizStoreSetupTest 9/9、RegistrationMarketFlowTest 6/6、HostStoreContextTest 2/2、
BizAuthEmailLoginTest 3/3、OtpTestPhoneFlowTest 8/8、ApiContractTest 5/5、BizFoundationJourneyFlowTest 7/7、
QuickStartFlowTest 10/10、CommerceStoreCreationFlowTest 4（1 skip，原生 MySQL）、SchemaDriftTest 6/6、
MultiEntityIdentityFlowTest 11/11、StoreBannerFlowTest 5/5、CommerceAccessSecurityFlowTest 5（2 skip）、MerchantStatusMappingTest 7/7 —— 全绿。
`ArchitectureTest` 5 红均为既有（food/report/trade 类，无本期类）；`BizEndpointPermTest#everyBizEndpointHasADecision`
1 红为既有（`/biz/auth/email-otp/send`、`/biz/inventory/food-stock-projections` 等，本期未新增端点）。

### App 端（ai-shop-bapp-mall，AC10–AC13）

| AC | 测试 | 结果 | 消融 |
|---|---|---|---|
| AC10 | `onboarding_v4_flow_test#test build picks the registration market under the submit button`（选阿联酋 → 登录请求 `marketCode: AE`）；`onboarding_email_login_test`（缺省带 `TW`） | 绿 | 登录不带 `marketCode` → 红 |
| AC11 | `standard_onboarding_store_setup_test#explicit server market …`（×4：单一地区不显示地区页、不读写本地地区记忆）；`#server setup empty`（地区未开放只给一句说明）；`standard_onboarding_widget_test#standard login goes straight …`（无地区字样、无地区按钮） | 绿 | 单一地区不再直接通过 → ×3 红 |
| AC12 | `onboarding_v4_flow_test#profile page holds account settings apart from stores`；`standard_onboarding_widget_test#standard login …`（我的门店无密码入口、有个人中心入口） | 绿 | — |
| AC13 | `onboarding_v4_flow_test#top-level Other …`、`#Other under a real industry …`、`#an Other store still confirms FREE …`；`standard_onboarding_store_setup_test` 主流程（三步 + 方案对比 + 提交中锁定）；`standard_onboarding_widget_test` 建店恢复两条；`standard_onboarding_journey_test` | 绿 | 结果页对 OTHER 仍严格比对行业码 → 「FREE 已確認」用例红 |

Flutter 全量：428 通过（5 跳过），`flutter analyze` 无问题。

本机联调（手机模拟器 `pos_phone`，TW 实例，`SHOP_REGISTRATION_MARKETS=TW,CN,AE`、`SHOP_OTHER_BUSINESS_TYPE=RETAIL`，V629 已执行）：
- 台湾号 +886 0912 345 655、注册页选「中国大陆」→ `usr_account.market=CN`（与手机区号无关）→ 直接进「我的门店」，无地区页。
- 个人中心显示账号 `+886912345655`（来自 `account`）。
- 顶层「其他」建店（pet · grooming，地址 + 2F）→ `mch_store.industry_text/sub_industry_text/address_detail` 落库、主体 market=CN、经营类型 RETAIL；结果页「门店已建立」，列表副标题与工作台行业显示自填文字，工作台不再展示经营地区。
- +971 号、选「阿联酋」→ `market=AE`；本地 `sys_market` 无 AE → 「我的门店」显示「你注册的经营地区暂未开放创建门店」，无地区选择。

## §6 对账二 · 设计 → 实现

已知偏差：原型 S04「有门店时 + 创建门店」本期不做（quick-start 只建首店）。

后端实现提交 `062a45855`（`git show --stat 062a45855`）：

```
 .../neargo/shop/portal/biz/BizAuthController.java  |   9 +-
 .../shop/portal/biz/BizFoundationController.java   |  75 ++++++++-
 .../shop/portal/biz/BizMerchantController.java     | 162 +++++++++++++++---
 .../shop-app/src/main/resources/application.yml    |   5 +
 ...629__account_market_and_store_industry_text.sql |  14 ++
 .../shop/portal/biz/BizAuthEmailLoginTest.java     |  17 ++
 .../neargo/shop/portal/biz/BizStoreSetupTest.java  | 113 +++++++++++++
 .../shop/scenario/RegistrationMarketFlowTest.java  | 186 +++++++++++++++++++++
 .../src/test/resources/application-test.yml        |   4 +
 .../shop-app/src/test/resources/schema-test.sql    |   3 +
 .../ai/neargo/shop/spi/user/MerchantAdminPort.java |  16 +-
 .../ai/neargo/shop/user/entity/UsrAccount.java     |   6 +
 .../shop/user/policy/RegistrationMarkets.java      |  48 ++++++
 .../ai/neargo/shop/user/service/AuthService.java   |  27 ++-
 .../shop/user/service/impl/AuthServiceImpl.java    |  46 +++++
 .../shop/user/policy/RegistrationMarketsTest.java  |  31 ++++
 .../java/ai/neargo/shop/merchant/dto/StoreVO.java  |  36 +++-
 .../ai/neargo/shop/merchant/entity/MchStore.java   |   9 +
 .../shop/merchant/port/MerchantPortImpl.java       |  16 +-
 .../service/impl/StoreAdminServiceImpl.java        |   3 +-
 .../impl/MerchantEntityStoreMarketTest.java        |  24 +++
 docs/api/examples/b-foundation.json                |   9 +-
 docs/api/openapi-b-onboarding.json                 | 120 +++++++++++--
 ...270\211\346\255\245\345\273\272\345\272\227.md" | 176 +++++++++++++++++++
 24 files changed, 1097 insertions(+), 58 deletions(-)
```

与设计的出入：

1. **`StoreVO.industryCode / subIndustryCode` 对自填的那一级报 `OTHER`**（设计只说新增两段文字）。理由：与 quick-start 提交的码一致，
   App 回显不用再拿文字猜；经营类型仍在 `businessTypeCode`。只有子业态自填时 `industryCode` 仍等于经营类型。
2. **自填文字只在对应那一级是 `OTHER` 时落库**，其余情况忽略（不拒）；`addressDetail` 空白按未填，超 64 字 10400。
   长度按码点计（与 utf8mb4 VARCHAR 同口径）。
3. 行业 `OTHER` 要求 `businessTypeCode` 非空且等于 `other-business-type`；该配置为空时顶层「其他」一律拒绝（store-setup 也返回 null）。
4. 员工（`MCH`）会话读 store-setup 时按部署默认（背后不一定有个人账号）。
5. `RegistrationMarkets` 放在 `shop-core` 的 `ai.neargo.shop.user.policy`（`..service..` 包只放接口）；配置用 setter 注入，未改 `AuthServiceImpl` 构造。
6. 测试世界 `application-test.yml` 增 `shop.merchant.registration-markets: CN,TW`（部署默认仍 CN，其余用例不受影响）。
7. AC9 落库场景走 `MerchantAdminPort#quickStart`：测试 profile 未开 commerce，HTTP quick-start 不能带 `businessTypeCode`，
   带行业码的校验因此由 `BizStoreSetupTest` 的静态用例覆盖，控制器到命令的组装由 `quickStartCommand` 单测覆盖。
8. 本机 `TW,CN,AE` 与 `other-business-type` 只给了配置入口（`SHOP_REGISTRATION_MARKETS`、`SHOP_OTHER_BUSINESS_TYPE`），
   未改本机运行中的实例与 env 文件。
9. 追加（协调方 2026-11-05）：`MerchantProfileVO.account`，见 §2；`docs/api/examples/b-foundation.json` 三处商家档案示例同步补 `account`。

### App 端与设计的出入

1. **结果页（原型 S08）一并简化**：设计 §2 未列 `StandardStoreResultPage`。联调发现它仍是旧版（展示地区、店号、经营类型、开发用按钮），且对 OTHER 门店永远「FREE 待确认」（列表报 OTHER、门店上下文报经营类型，严格比对必然不等）。已改为：自填那一级跳过行业码比对（店号 / 主体 / 地区 / 币种比对照旧），未确认时自动刷新最多 5 次，底部「进入门店 / 返回我的门店」。
2. **门店工作台首页也去掉了「经营地区」一行**，行业显示自填文字（设计只提到「我的」页签的地区按钮）。
3. **地区未开放的空态**：服务端回空地区列表时，标题改为「我的门店」并给一句说明（设计 §4 风险里写的是「显示该地区暂未开放建店」）。
4. **多地区兜底保留**：服务端若回多个地区（旧版后端），仍走原地区页 + 本地记忆；对应测试改用两地区数据继续覆盖。
5. **地图选点按钮未渲染**：本期只做 `mapProvider` 解析，按钮等地图组件接入时再加（本机 CN 也是 null）。
6. **有门店时不提供「＋ 创建门店」**（同 §0 不做项）。
7. App 修改清单：`onboarding_api.dart`、`onboarding_controller.dart`、`onboarding_forms.dart`、`onboarding_store_setup.dart`、`onboarding_app.dart`、`onboarding_store_creation.dart`、`onboarding_models.dart`、`onboarding_widgets.dart`（`page()` 支持下拉刷新）、新增 `onboarding_profile.dart`；arb + 生成物；测试 5 个文件修改、新增 `onboarding_v4_flow_test.dart`。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-05 | 用户确认按建议实施（含扁平步骤条） |
| 2026-11-05 | 已实现：后端 062a45855（子代理）、App 端本地提交；后端定向测试、Flutter 全量 428 绿；手机模拟器全链路通过。未推送 |

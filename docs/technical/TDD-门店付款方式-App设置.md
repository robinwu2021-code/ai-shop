# TDD-门店付款方式-App设置

状态：已确认（2026-11-17，用户「按建议确认」Q1–Q5）；§2 已按 §3.1 修正路线改写（2026-11-17，待用户确认后按 §2 计划实施）
档位：2（跨端：near-pay 内部业务接口 + ai-shop `/biz/payment` 端点与业务权限码 + Flutter 商户 App 页面；不改库表）
关联需求：用户 2026-11-17 口述「运营端可以控制商户的渠道，App 可以控制门店的渠道」；`docs/requirements/运营端-账户商户门店渠道-功能矩阵.md`、`支付域-功能矩阵.md` 中无门店付款方式开关条款，本 TDD 的 AC 即需求，确认后回写功能矩阵
关联设计：near-pay `CollectionPolicyService`（BM02 F02/F03 收款产品策略）；near-pay `TDD-沙箱渠道并行与取消环境分区.md` §8（同一方式正式与测试分开展示）
创建：2026-11-17 · 最后更新：2026-11-17（§2 改写、新增 §8 并行事项）

## 验收标准（需求）

- **AC1** Given 商户管理员打开某门店的「付款方式」页，When 页面加载，Then 列出该门店在收银台上可能出现的全部付款方式（名称、所属渠道、测试渠道带「測試」标签），每项显示开/关；被平台或商户层关掉的项显示为不可开并说明原因。
- **AC2** Given 某方式当前开启，When 管理员关闭它并保存，Then 该门店的收银台（扫码页、付款页）不再出现该方式（展示缓存 ≤5 分钟）；其他门店不受影响。
- **AC3** Given 门店已关闭部分方式，When 管理员把所有可开的方式都打开并保存，Then 门店回到「跟随商户」，此后运营端放开新方式时该门店自动获得。
- **AC4** Given 某方式被运营端在商户层关闭（商户收款策略未包含 / 账户产品关闭 / 账户暂停），When 管理员试图开启，Then 页面不允许，接口拒绝。
- **AC5** Given 门店至少要保留一种可用方式，When 管理员要关掉最后一种，Then 页面阻止并提示。
- **AC6** Given 非商户管理员或非本商户门店，When 读写该门店付款方式，Then 拒绝（读写均要求商户管理员）。
- **AC7** Given 运营端「门店收款配置」与 App 同时修改同一门店，When 后保存的一方提交，Then 以版本冲突拒绝并提示刷新；两端看到的是同一份门店策略。

不做：运营端商户层控制（已有：账户状态、账户产品开关、商户收款策略）；门店渠道账户的绑定/解绑（已有绑定，解绑仍走运营端）；收款入口（`pay_collection_entry`）的商户自助编辑。

## §0 对账一 · 需求 → 设计

| AC | 需求原文（一句话） | 落点 |
|---|---|---|
| AC1 | 列出门店全部付款方式及开关、不可开原因 | ai-shop `GET /biz/payment/store-method` → near-pay 内部 `GET /internal/pay/v1/biz/stores/{storeNo}/collection-methods` → `StoreCollectionMethodService#view`（门店 ACTIVE 绑定 → 账户 ACTIVE → 产品 OPEN，叠加 `CollectionPolicyService#preview/#storePolicy` 与 H5 收款入口）；Flutter「门店管理 › 付款方式」 |
| AC2 | 关闭后收银台不再出现 | ai-shop `PUT /biz/payment/store-method` → near-pay 内部 `PUT …/collection-methods` → `StoreCollectionMethodService#save` → `CollectionPolicyService#saveStore(OVERRIDE, 开启集合)`；收银台既有 `StoreCapabilityLayer` 生效 |
| AC3 | 全开即回到跟随商户 | 同上，开启集合 = 门店可开全集时保存为 `INHERIT` |
| AC4 | 商户层关掉的不可开 | `preview` 的 `excludedBy` ≠ STORE 的项只读；`saveStore` 既有「只能在商户能力内收窄」校验 |
| AC5 | 至少保留一种 | `saveStore` 既有「OVERRIDE 至少选择一个产品」；页面前置提示 |
| AC6 | 管理员 + 本商户门店 | ai-shop `@PreAuthorize canBiz(BizPerms.PAY_STORE_METHOD)`（`mch:pay:store-method` 只随属主 `*`）+ 门店取 `BizContext.requireStoreNo()`；near-pay `PayBizFacade#ownerOf`（本地投影解析门店当前归属，必须带操作员） |
| AC7 | 与运营端同一份数据、版本冲突 | 同一张 `pay_collection_policy`，`expectedVersion` 乐观锁（既有） |

**孤立项**：
- 没落点的 AC：无。
- 挂不上 AC 的设计：无。

## §1 现状与影响面（2026-11-17 只读盘点）

- **收银台显示规则**：`RouteSelectionPolicy.available` 对以下各层取交集，任一层不放行即不显示：门店绑定 → 账户 ACTIVE → 渠道目录/账户产品 OPEN/商户收款策略/门店收款策略（`EffectiveCapabilityQuery` → `StoreCapabilityLayer` → `CollectionPolicyService#decide`）→ 收款入口产品（`EntryRoutePolicy`）。
- **运营端已有**：商户层 `/channel-accounts/{no}/activate|suspend|…`、`/products/{code}/open|close`、`PUT /merchants/{no}/collection-policy`；门店层 `/stores/{no}/bindings`、`PUT /stores/{no}/collection-policy`（INHERIT / OVERRIDE，只能收窄）、`/collection-entries`。
- **App 缺口**：near-pay 商户端只有 `GET /channel-accounts`、`GET|POST /stores/{storeNo}/bindings`；ai-shop `BizOnboardingForwarder` 白名单不含门店策略；b-app 无对应页面。`pages/payment/index`（收款设置）是旧微信/支付宝进件，`pages/store-scope/index` 只管线下付款与货到付款，均与本功能无关。
- **可直接复用**：`CollectionPolicyService#preview/#storePolicy/#saveStore`（校验、版本、审计全在里面）。注意 `preview` 遍历**平台目录全部产品**，层级只有 CATALOG / MERCHANT / STORE / STORE_STATE，不含门店绑定、账户产品与收款入口，不能直接作为 App 列表；`BizMemberships.current` + `store.bind`；`BizOnboardingForwarder` 白名单；收银台无需改动。
- **会被改到的**：near-pay `pay-portal` 新增一个商户端控制器；ai-shop `BizOnboardingForwarder#allowed`、b-app `pages.json`、i18n、`docs/technical/design/ui-catalog.json`（重新生成）。
- **明确不受影响**：库表（沿用 `pay_collection_policy(_product)`）、收银台与选路逻辑、运营端页面与接口、商户层策略。

## §2 方案

> 2026-11-17 按 §3.1 修正路线改写：App 不直连 near-pay 商户端 `/biz/pay/v2`（生产包里该前缀要编译开关 `NEARPAY_BIZ_ONBOARDING_ENABLED` 才放行），改走已上线的业务接口 `/biz/payment/...` → 支付服务内部接口。页面落在 Flutter 商户 App，不是 uni-app b-app。

### 调用链

```
Flutter「门店管理 › 付款方式」
  └─ GET|PUT /biz/payment/store-method        （ai-shop，门店 = 请求头当前门店）
       @PreAuthorize canBiz(mch:pay:store-method)
       └─ BizPaymentAppService
            ├─ standalone（UME166）：RemoteBizPaymentAppService → PayBizInternalApi（HTTP 127.0.0.1:18093，调用方 AI_SHOP_UME）
            │    └─ near-pay GET|PUT /internal/pay/v1/biz/stores/{storeNo}/collection-methods
            │         → PayBizFacade#storeMethods / #saveStoreMethods（ownerOf：本地投影解析门店归属 + 操作员）
            │         → StoreCollectionMethodService#view / #save → CollectionPolicyService#preview/#storePolicy/#saveStore
            └─ embedded：抛 PAY_CHANNEL_UNAVAILABLE（内嵌形态没有门店收款策略服务）
```

### 契约变更

**ai-shop 端点（App 调用）**

```
GET  /biz/payment/store-method
PUT  /biz/payment/store-method   { requestNo, expectedVersion, enabledProductCodes: [..] }
→ { storeNo, mode: INHERIT|OVERRIDE, version,
    items: [ { productCode, channelCode, method, test, enabled, editable, excludedBy } ] }
```

- 门店取 `BizContext.requireStoreNo()`，不收路径或参数里的门店号（与收款码、代客收款同口径）。
- 列表 = 门店 ACTIVE 绑定 → 账户 ACTIVE → 产品 OPEN；目录里门店用不上的产品不出现。
- `excludedBy`：`null` 开启；`STORE` 门店关（可重开）；`MERCHANT` / `CATALOG` / `STORE_STATE` 商户层或目录关；`ENTRY` 门店有 ACTIVE 的 H5 收款入口但未包含该产品（Q3）。后四种 `editable=false`。
- 保存：开启集合 = 可开全集 → `INHERIT`（`productCodes=[]`）；否则 `OVERRIDE`；至少保留一种；越权开启 → 400；版本不符 → 409（提示刷新）；成功记 ai-shop 审计 `BIZ_PAY_STORE_METHOD`，near-pay 策略审计 actor `BIZ:{operatorNo}`、原因「商户 App 设置门店付款方式」。
- 生效：收银台展示缓存最长 5 分钟。

**near-pay 内部接口**（`PayBizInternalApi`，ai-shop 与 near-pay 两份必须同形）

```
GET  /internal/pay/v1/biz/stores/{storeNo}/collection-methods?operatorNo=
PUT  /internal/pay/v1/biz/stores/{storeNo}/collection-methods   StoreMethodsReq{operatorNo, requestNo, expectedVersion, enabledProductCodes}
→ PortResult<StoreMethods{storeNo, mode, version, items: StoreMethodItem[]}>
```

**权限码**：ai-shop `BizPerms.PAY_STORE_METHOD = "mch:pay:store-method"`，只随属主（OWNER 的 `*`）生效，不进店长 / 店员模板，**不需要迁移**（Q2）。

**App 契约登记**：b-app `src/api/endpoints.ts` + `scripts/gen-openapi.mjs` 类型映射 → 重新生成 `docs/api/openapi-b.yaml`、API 清单、`glossary.json`（推送前「契约」「生成文档」闸门比对）。Flutter `features/v5/contracts.dart` 的 `pendingContracts` 不加新条目（契约随本次冻结）。

**i18n**：Flutter `merchant_zh_Hans.arb` / `merchant_zh.arb`（繁体）/ `merchant_en.arb` / `merchant_ar.arb` 新增词条（前缀 `v31Pm`）；near-pay 校验错误沿用既有中文提示，App 按 HTTP 状态与 `excludedBy` 自行出文案。

**库表 / 配置**：无。

### 模块设计

1. **near-pay**（分支 `feat/biz-store-collection-methods-20261117`，已实现）：`StoreCollectionMethodService`（视图组装、INHERIT/OVERRIDE 换算、ENTRY 排除、至少一种）；`PayBizFacade#storeMethods/#saveStoreMethods`；`InternalPayBizEndpoint` 两条路由；`PayBizInternalApi` 三个 record + 两个方法。
2. **ai-shop**（分支 `docs/store-payment-methods-tdd-20261117`，已实现，测试已补跑通过）：`BizPerms.PAY_STORE_METHOD`；`BizPaymentController` `GET|PUT /biz/payment/store-method`（`StoreMethodReq{requestNo, expectedVersion, enabledProductCodes}`）；`BizPaymentAppService` 两方法，`RemoteBizPaymentAppService` 经 `PayBizInternalApi`、保存成功记审计；内嵌实现抛 `PAY_CHANNEL_UNAVAILABLE`；`PayBizInternalApi` 同步；`BizEndpointPermTest` 登记。
3. **Flutter 商户 App**（`ai-shop-bapp/apps/merchant_app`，待做）：
   - 数据层 `features/payment_operations/data/store_method_api.dart`：`load()`、`save(requestNo, expectedVersion, codes)`；`requestNo` 每次保存一个 UUID，重试沿用同一个。
   - 页面 `features/payment_operations/presentation/store_payment_methods_page.dart`：「正式」「测试」两组（`test=true` 归测试组，行尾「測試」小标签）；每行方式名（按 `method` 出名称）+ 渠道 + 开关；`editable=false` 置灰并按 `excludedBy` 写原因（MERCHANT「商户未开通」、CATALOG「平台未开放」、STORE_STATE「门店暂停收款」、ENTRY「平台未开通到收银台」）；只剩一项开启时禁用其开关；只剩测试方式时顶部警示「顾客无法用正式方式付款」；`mode=INHERIT` 时顶部说明「跟随商户设置」；保存成功提示「收银台约 5 分钟内更新」；409 提示「设置已被修改，请刷新后重试」并重新载入。交互参照已有的门店取货方式页（`v23Ch*`）。
   - 入口：门店管理（`store_creation_entry.dart` 的管理卡片）在「收款渠道」下新增「付款方式」单元格，`controller.can('mch:pay:store-method')` 为真且当前选中门店即本门店时显示；路由 `/stores/:storeId/payment-methods`。
   - 台湾版隐藏旧「收款设置」入口（Q1）：Flutter 端若仍有指向旧微信 / 支付宝进件的入口，按市场 `TW` 隐藏；当前「收款渠道 → 支付能力」不是旧入口，保留。
   - 开发分支：从 `origin/main` 另开 `feat/store-payment-methods`，独立工作区，不碰 `feat/core-flow-ui` 会话的工作区。
4. **文档**：回写 `docs/requirements/运营端-账户商户门店渠道-功能矩阵.md`（App 门店付款方式）；本 TDD §5/§6 对账；状态改「已实现」。

### 实施计划（writing-plans，全部在本会话进行）

| # | 步骤 | 仓库 / 分支 | 验收 |
|---|---|---|---|
| 1 | ai-shop 契约登记：endpoints.ts、gen-openapi 类型映射，重新生成 openapi-b、API 清单、glossary；TDD 本节定稿 | ai-shop `docs/store-payment-methods-tdd-20261117` | `bash .githooks/pre-push </dev/null` 全过 |
| 2 | 两份 `PayBizInternalApi` 逐字段比对；near-pay 分支跑 `StoreCollectionMethodServiceTest`、`PayBizInternalApiRoundTripTest`、`PortalRouteRulesTest` | near-pay `feat/biz-store-collection-methods-20261117` | 字段一致；三组测试通过 |
| 3 | 本机联调：本机 near-pay（18093）+ ai-shop，按 AC1–AC7 逐条跑接口（含属主 / 店长 / 他店门店、INHERIT↔OVERRIDE、越权开启、最后一项、版本冲突、运营端同时修改） | 本机 | §5 逐条填测试与结果 |
| 4 | Flutter 页面 + 数据层 + 入口 + 四语词条；widget 测试（分组、置灰原因、最后一项禁用、只剩测试警示、409 刷新） | `ai-shop-bapp` `feat/store-payment-methods`（新工作区） | `flutter analyze`、`flutter test` 通过；Android 模拟器 pos_tablet@5580 走真实界面（简繁英） |
| 5 | 合并进 166 发布线：near-pay 分支合入 `release/166-live-20261117`；ai-shop 分支合入 ai-shop `release/166-live-20261117`（本功能不改库表，先于 V679 那批单独发） | 两仓库 release 分支 | 冲突解决、相关测试通过；**合并前确认** |
| 6 | 发布 UME166：near-pay 覆盖包（`PayBizFacade`、`PayBizInternalApi`、`InternalPayBizEndpoint`、`StoreCollectionMethodService`，基线核对线上 jar）；ai-shop 包（shop-app、shop-base-auth、pay 客户端）；先拉最新、合并、提交 | 166 | 基线一致；健康检查；**发布前确认** |
| 7 | 线上验证：测试门店 `ST261117JC0WT5KSDV` 在 App 关 / 开一种方式，5 分钟内收银台增减；运营端「门店收款配置」看到同一份策略 | 166 + 真机 / 模拟器 | 截图与订单页记录写入 §5 |
| 8 | Flutter 发版（内测渠道） | `ai-shop-bapp` | **发版前确认** |
| 9 | TDD §5/§6 对账、状态「已实现」、回写功能矩阵 | ai-shop | 文档闸门通过 |

推送、合并、发布、发版每一步单独征得用户同意。

## §3 决定（2026-11-17，用户「按建议确认」）

- **Q1** 入口放在「店铺设置」新增「付款方式」；台湾版隐藏旧「收款设置」（`pages/payment/index`，微信/支付宝进件）入口。
- **Q2** 只有商户管理员（`store.bind`）可读写；店长看不到入口。
- **Q3** App 只改门店收款策略；运营端建 H5 收款入口默认包含商户允许的全部产品；门店已有 ACTIVE 的 H5 收款入口但未包含某产品时，该项在 App 显示为不可开，原因「平台未开通到收银台」（`excludedBy=ENTRY`）。
- **Q4** App 同样可开关测试渠道（带「測試」标签）；计入「至少保留一种」，但只剩测试方式时页面给警示。
- **Q5** 未同步门店跳过门店策略的问题不在本 TDD 范围，另行确认何时开启 `require-projection`。

原待决记录：

- **Q1 页面入口**：建议放在「店铺设置」里新增「付款方式」；旧的「收款设置」（`pages/payment/index`，微信/支付宝进件）台湾不用，是否同时从台湾版入口隐藏？
- **Q2 谁能改**：建议只有商户管理员（`store.bind`）可读写；门店店长只读还是不可见？
- **Q3 收款入口产品与门店策略的重叠**：两者都是门店层过滤、都要放行才显示。建议 App 只改门店策略；运营端建 H5 收款入口时默认包含商户允许的全部产品，避免「App 打开了却仍不显示」。App 页面对「入口未包含」的项是否也显示为不可开（原因「平台未开通到收银台」）？
- **Q4 测试渠道**：建议 App 同样可开关测试渠道（带「測試」标签）；还是测试渠道只由运营端控制、App 不显示？
- **Q5 未同步到 near-pay 的门店**：现在这类门店会跳过门店策略、直接放行（`require-projection=false`）。本功能上线后，是否对已完成同步的环境打开 `require-projection`？（不在本 TDD 实施范围，建议另行确认。）

## §3.1 实现中发现的偏差（2026-11-17；已据此改写 §2，待用户确认）

1. **商户 App 是 Flutter 工程 `ai-shop-bapp/apps/merchant_app`**，不是 uni-app `b-app`。原 §2「b-app `pages/store-payment-methods/index`」与 Q1 的入口描述作废，页面应落在 Flutter App（该仓库现有 `feat/core-flow-ui`、`feat/entity-ocr` 两个会话在改）。
2. **App 不直连 near-pay 商户端接口。** 现有支付功能（收款码、代客收款、第三方授权）走 ai-shop 业务接口 `/biz/payment/...`：`@PreAuthorize("@perm.canBiz(BizPerms.X)")` 校验 App 用户 → `BizPaymentAppService` → `PayBizFacade` → 支付服务内部端点（`InternalPayBizEndpoint`）。`b-app/src/api/endpoints.ts` 注释写明「经业务服务；原直连支付的 /biz/pay 过渡后删」。原 §2 的「near-pay `/biz/pay/v2/stores/{storeNo}/collection-methods` + ai-shop 转发白名单」不符合这条路线。
3. **修正方案（待确认）：**
   - ai-shop：`GET|PUT /biz/payment/store-method?storeNo=`（`/biz` 单数约定），新增业务权限码 `BizPerms.PAY_STORE_METHOD`（只授予商户管理员角色，Q2），经 `PayBizFacade` 调支付服务；
   - 支付服务：在内部端点上新增门店付款方式读写，复用已写好的 `StoreCollectionMethodService`（视图与 INHERIT/OVERRIDE 换算、ENTRY 排除，单测已过）；
   - Flutter App：「店铺设置」新增「付款方式」页；
   - 撤回已写的 near-pay 商户端控制器与 ai-shop 转发白名单两处提交（尚未推送）。
4. 需先查清：`PayBizFacade` 在 UME166 上是进程内调用还是经 HTTP 调 near-pay（决定改 ai-shop 内置 pay 模块还是 near-pay 的内部端点）。

## §4 风险

| 风险 | 缓解 |
|---|---|
| 商户关掉全部真实方式只留测试方式，顾客付不了真钱 | AC5 只保证至少一种；Q4 决定测试渠道是否计入；页面在只剩测试方式时给警示 |
| 收银台展示缓存 5 分钟，商户以为没生效 | 保存后明确提示生效时间 |
| 运营端与 App 同时改 | 版本乐观锁（AC7） |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 结果 |
|---|---|---|
| AC1 | near-pay `StoreCollectionMethodServiceTest#viewListsOnlyBoundActiveOpenProductsWithTheirExclusionLayer`（只列 ACTIVE 绑定 / 账户 / OPEN 产品，STORE / MERCHANT / CATALOG / ENTRY 各层）；`PayBizInternalApiRoundTripTest`（真实 HTTP 往返）；ai-shop `RemoteBizPaymentAppServiceTest#storeMethodsCarryOperatorAndStore` | 通过（2026-11-17） |
| AC2 | near-pay `#enablingASubsetOverridesWithExactlyThatSet` | 通过 |
| AC3 | near-pay `#enablingEverythingSwitchableGoesBackToInherit` | 通过 |
| AC4 | near-pay `#rejectsEnablingANonSwitchableMethodOrDisablingEverything`（越权开启） | 通过 |
| AC5 | 同上（全部关闭被拒） | 通过 |
| AC6 | ai-shop `BizEndpointPermTest`（`/biz/payment/store-method` → `mch:pay:store-method`，注解实际生效）；`MerchantRoleFlowTest#assignable…`（该码不在可授予清单，只有属主 `*` 拥有）；门店取 `BizContext.requireStoreNo()` | 通过 |
| AC7 | near-pay `#aStaleVersionFromTheAppIsRejectedNotOverwritten`（版本冲突原样抛出）；冲突判定在 `CollectionPolicyService#saveStore` 既有乐观锁 | 通过 |

端到端（App → ai-shop → near-pay → 收银台）在 UME166 线上验证时补记（§2 第 7 步）：2026-11-17 已发布，待用连 166 的 App 测试包在测试门店上登录后开关一种方式确认（登录需商户属主本人操作）。

## §6 对账二 · 设计 → 实现

（实现后贴 `git show --stat` 与偏差说明）

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-17 | 草稿 |
| 2026-11-17 | 用户「按建议确认」Q1–Q5，开始实现 |
| 2026-11-17 | 实现中发现 App 为 Flutter、支付功能经业务服务，记录偏差 §3.1，暂停待确认 |
| 2026-11-17 | 交接后补跑 ai-shop 测试（RemoteBizPaymentAppServiceTest 3、BizEndpointPermTest 12、ArchitectureTest 23）全部通过；按修正路线改写 §2 并加实施计划、§8 并行事项，待用户确认 |
| 2026-11-17 | 发布 UME166（用户确认「合并并发布」）：ai-shop 合进 `release/166-live-20261117`（`a122d1e99`），near-pay 快进到 `bd8ed03da`。两边线上 jar 都是覆盖包，所以只把本功能改动的类覆盖进线上 jar：near-pay 9 个类（`PayBizFacade`、`PayBizInternalApi` 及三个新 record、`StoreCollectionMethodService` 及两个内部类、`InternalPayBizEndpoint`），jar `61a6fb2e` → `055f6dcd`；ai-shop 10 个类（`BizPaymentController` 及 `StoreMethodReq`、`BizPaymentAppService` 及两个实现、`BizPerms`、`PayBizInternalApi` 及三个 record），jar `d66b300b` → `402e64c8`。无库表变更（迁移仍 V678）。备份 `pay-svc.jar.before-np-store-methods-20261117-bd8ed03da`、`shop-app.jar.before-as-store-methods-20261117-a122d1e99`；两个 ai-shop 容器与 near-pay 均健康，新路由未带凭据返回 401（非 404），启动日志只有既有的 SHIP_TRACK_KEY 一条 ERROR |

## §8 本会话并行事项（交接 store-payment-methods-2026-11-17 §五，不计入本 TDD 验收）

| # | 事项 | 方案 | 所在 | 时机 |
|---|---|---|---|---|
| P1 | ume早餐店对顾客显示「LINE Pay（測試）」「信用卡（測試）」 | 运营端停用收款入口 `CE3A941F09E7E94974` → 移除 `LINEPAY_TEST_ONLINE`、`NEWEBPAY_TEST_CARD_ONETIME` → 重新启用；回读收银台只剩正式方式 | 166 运营端（页面操作） | 用户确认测试已结束后立即做 |
| P2 | 沙箱试运行资格到期（LINE 11-18 05:00 UTC、蓝新 06:07 UTC） | 本 TDD 线上验证（§2 第 7 步）若用测试方式，须在到期前完成或按既有流程续期 | 166 PAY 运营端 | 第 7 步前检查 |
| P3 | 正式收款推送依赖 ai-shop `payment_collection_source` 的门店 PRODUCTION 映射（现仅 2070、2074） | 迁移前用 `PaymentCollectionSourceBootstrap` 按已核验门店补映射；或改 near-pay 推送按签名 storeNo 解析（ADR-059 方向）。先出只读差异清单，再定 | ai-shop paybridge / near-pay notify | 批量迁移启动前 |
| P4 | 迁入的旧沙箱资格材料坐标仍为 SANDBOX，资格列表报「资格材料归属冲突」 | `QualificationService#read` 对环境不符的材料：只读列表降级为「历史资格（环境已迁移）」，不再整表报错；或一次性把迁入材料坐标改为 PRODUCTION（需迁移脚本）。先写失败测试复现 | near-pay `pay-domain/qualification` | 本 TDD 第 5 步前 |
| P5 | 结果页把「明确授权失败」显示为「无法确认，请联系商家」 | 结果视图区分 `FAILED`（渠道明确拒绝）与超时未知：前者显示「付款失败，可重新付款」并给重新付款按钮，后者保持现文案；简繁英同步 | near-pay `checkout-h5`（结果页 + `i18n.ts`） | 可与第 6 步同包发布 |
| P6 | 其余约 380 家商户批量迁移 | 等用户检查首户后启动；P3 是前置条件 | — | 用户指示 |

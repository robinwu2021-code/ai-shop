# TDD-门店付款方式-App设置

状态：已确认（2026-11-17，用户「按建议确认」Q1–Q5）
档位：2（跨端：near-pay 商户端接口 + ai-shop 转发 + 商户 App 页面；不改库表）
关联需求：用户 2026-11-17 口述「运营端可以控制商户的渠道，App 可以控制门店的渠道」；`docs/requirements/运营端-账户商户门店渠道-功能矩阵.md`、`支付域-功能矩阵.md` 中无门店付款方式开关条款，本 TDD 的 AC 即需求，确认后回写功能矩阵
关联设计：near-pay `CollectionPolicyService`（BM02 F02/F03 收款产品策略）；near-pay `TDD-沙箱渠道并行与取消环境分区.md` §8（同一方式正式与测试分开展示）
创建：2026-11-17 · 最后更新：2026-11-17

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
| AC1 | 列出门店全部付款方式及开关、不可开原因 | near-pay `GET /biz/pay/v2/stores/{storeNo}/collection-methods` ← 门店绑定（`StoreBindingService#ofStore`）+ 账户产品 OPEN + `CollectionPolicyService#preview` + `#storePolicy`；ai-shop 转发白名单；b-app `pages/store-payment-methods/index` |
| AC2 | 关闭后收银台不再出现 | near-pay `PUT …/collection-methods` → `CollectionPolicyService#saveStore(OVERRIDE, 开启集合)`；收银台既有 `StoreCapabilityLayer` 生效 |
| AC3 | 全开即回到跟随商户 | 同上，开启集合 = 门店可开全集时保存为 `INHERIT` |
| AC4 | 商户层关掉的不可开 | `preview` 的 `excludedBy` ≠ STORE 的项只读；`saveStore` 既有「只能在商户能力内收窄」校验 |
| AC5 | 至少保留一种 | `saveStore` 既有「OVERRIDE 至少选择一个产品」；页面前置提示 |
| AC6 | 管理员 + 本商户门店 | 照 `BizChannelAccountController#admin/#own`（角色 `store.bind`，门店当前归属本商户） |
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

### 契约变更

**端点（near-pay 商户端，`@Profile("api")`，经 ai-shop `/biz/pay/v2/**` 转发）**

```
GET  /biz/pay/v2/stores/{storeNo}/collection-methods
→ { storeNo, mode: INHERIT|OVERRIDE, version,
    items: [ { productCode, channelCode, method, test, enabled, editable, excludedBy, reasons[] } ] }

PUT  /biz/pay/v2/stores/{storeNo}/collection-methods
   { requestNo, expectedVersion, enabledProductCodes: [..] }
→ 同 GET 的视图
```

- `method`：付款方式码（LINE_PAY / CARD / APPLE_PAY …），App 按它显示名称；`test` = 渠道 `test_channel=1`。
- 列表范围 = 门店当前 ACTIVE 绑定的渠道账户（账户 ACTIVE）下状态为 OPEN 的产品；其余目录产品不出现（门店用不上）。
- `editable` = 该项只被门店层排除或当前开启（即商户层允许）；被商户层或目录排除的项 `editable=false`，`excludedBy` 取 `preview` 的层级（CATALOG / MERCHANT / STORE_STATE）；门店 ACTIVE 的 H5 收款入口未包含该产品时 `excludedBy=ENTRY`、`editable=false`（Q3）。
- 「商户层允许的全集」= 列表中 `excludedBy` 不是 CATALOG / MERCHANT / STORE_STATE / ENTRY 的项。
- PUT 语义：`enabledProductCodes` = 商户层允许的全集 → 存 `INHERIT`（`productCodes=[]`）；否则存 `OVERRIDE`（集合即开启项）；`reason` 固定「商户 App 设置门店付款方式」；actor `BIZ:{subjectRef}`。
- 鉴权：读写都要 `store.bind`（商户管理员）且门店当前归属本商户（同 `BizChannelAccountController`）；否则 403 / 404。

**ai-shop**：`BizOnboardingForwarder#allowed` 增加 `GET|PUT /biz/pay/v2/stores/{storeNo}/collection-methods`（`storeNo` 限 `[A-Za-z0-9_-]{1,64}`）。

**App 页面**：新增 `pages/store-payment-methods/index`「付款方式」，入口在「店铺设置」（`pages/store/index`），按当前门店进入。

**i18n**：b-app 新页面词条（zh-CN / zh-TW / en）；near-pay 校验错误沿用既有中文提示。

**库表 / 配置 / 权限码**：无新增（角色能力沿用 `store.bind`）。

### 模块设计

1. near-pay `pay-portal/.../api/biz/v2/BizStoreCollectionMethodController`：`admin` + `own` → 取门店 ACTIVE 绑定的账户及其 OPEN 产品，与 `policies.preview(p)`、`policies.storePolicy(p)` 合成视图（只保留绑定范围内的产品）；PUT 把开启集合换成 `SaveCommand` 调 `saveStore`。产品 → 方式码、`test` 用渠道目录与 `ChannelSandboxPolicy`。
2. ai-shop `BizOnboardingForwarder`：白名单两条 + 单测。
3. b-app 页面：列表按「正式 / 测试」分组，每行方式名 + 渠道 + 开关；不可开的行置灰并显示原因；只剩一项开启时禁用其开关；保存成功后提示「收银台约 5 分钟内更新」；版本冲突提示刷新。
4. 文档：回写功能矩阵（App 门店付款方式）；`gen-ui-catalog.py` 重新生成。

### 实施计划（writing-plans）

1. near-pay：控制器测试（视图组装、INHERIT/OVERRIDE 换算、鉴权、版本冲突）→ 实现。
2. ai-shop：转发白名单测试 → 实现。
3. b-app：页面 + i18n + pages.json → `vue-tsc` → 生成界面清单。
4. 本机联调（ai-shop + near-pay 本机实例）→ UME166 发布（near-pay 覆盖包、ai-shop 覆盖包、b-app 发版）。

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

## §3.1 实现中发现的偏差（2026-11-17，待确认后改 §2）

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
| AC1 | （实现后填） | |
| AC2 | | |
| AC3 | | |
| AC4 | | |
| AC5 | | |
| AC6 | | |
| AC7 | | |

## §6 对账二 · 设计 → 实现

（实现后贴 `git show --stat` 与偏差说明）

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-17 | 草稿 |
| 2026-11-17 | 用户「按建议确认」Q1–Q5，开始实现 |
| 2026-11-17 | 实现中发现 App 为 Flutter、支付功能经业务服务，记录偏差 §3.1，暂停待确认 |

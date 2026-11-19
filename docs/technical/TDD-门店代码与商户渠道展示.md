# TDD-门店代码与商户渠道展示

状态：已实现、已上线 UME166（2026-11-19；App 部分待 App 会话）
档位：2（跨端：near-pay 渠道目录状态与内部业务接口 + ai-shop `/biz/merchant/payment` 数据来源 + Flutter 商户 App 主体页；改 near-pay 渠道状态取值，不加权限码）
关联需求：用户 2026-11-19 口述——
1. 「门店数据要增加门店名称代码，给到支付链接用」；「代码用英文」。
2. 「支付系统整理全局渠道列表…是否沙箱，是否已开通」；「状态由运营人员维护，目前根据实际情况设置数据库状态」。
3. 「商户端关联渠道基础数据进行展示，商户端的渠道包含渠道商户号」（显示全号）；「目前 App 上已经有运营主体，主体下可以看到渠道列表」。
4. 「先完成 roger 和 oscar 账户的导入」（已完成，见 §7）。
5. 此前：「暂时不支持的渠道在系统中标注为待开通，但是要同步到数据库中」。

功能矩阵（`docs/requirements/支付域-功能矩阵.md`、`运营端-账户商户门店渠道-功能矩阵.md`）无这几条；本 TDD 的 AC 即需求，确认后回写功能矩阵。
关联设计：门店代码现有实现 V357 `mch_store.slug`、`StoreSlugs`、`NearPayStoreProfile#storeCode`、near-pay `StoreCodeService`；App 主体页渠道列表 `merchant_api.dart#channels` → `GET /biz/merchant/payment`；交接 [oscar-ume-align-2026-11-19](../handoff/oscar-ume-align-2026-11-19.md)。
创建：2026-11-19 · 最后更新：2026-11-19（第三版）

## 验收标准（需求）

### A 门店代码（英文，支付链接用）

- **A1** Given 门店设了英文代码（小写字母、数字、连字符，3–32 位，`StoreSlugs` 规则），When 门店同步到 near-pay 且门店有启用的整店收款码，Then `tpay.neargo.ai/s/<代码>` 打开该门店收银台（现有链路，不改）。
- **A2** Given 代码由运营或店主设置，When 设置，Then 沿用现有接口：运营 `PUT /ops/stores/{storeNo}/basics`，店主 `POST /biz/store/{storeNo}/slug`；占用时提示「代码已被使用」。
- **A3** Given 一批 UME 门店没有代码，When 运营批量导入「门店号 → 英文代码」清单，Then 逐家调用 A2 的运营接口写入，撞码或不合规的行列为失败并给原因，其余照常写入。不做自动拼音生成（用户 2026-11-19：代码用英文）。

### B 全局渠道清单（支付系统，运营维护状态）

- **B1** Given 渠道目录中的任一渠道，When 运营查看渠道清单，Then 每个渠道显示「是否沙箱」（`test_channel`）与「开通状态」：`ENABLED` 已开通、`PENDING` 待开通、`DISABLED` 已停用。
- **B2** Given 运营把渠道设为 `PENDING`（待开通），When 保存，Then 状态写入 `pay_channel.status`，不校验适配器；该渠道不进任何收银台。
- **B3** Given 渠道为 `PENDING`，When 运营要启用，Then 走现有启用校验（适配器须已注册），不通过则保持 `PENDING`。
- **B4** Given 渠道为 `PENDING` 或 `DISABLED`，When 顾客打开任一门店收款页，Then 付款方式里不出现它（保持现状，作回归）。

### C 商户端：App 主体页的渠道列表

- **C1** Given 主体在 near-pay 有支付商户与渠道账户，When 店主在 App 打开主体页，Then 渠道列表以 near-pay 的渠道账户为准，每个账户一行：渠道名称、是否沙箱、渠道开通状态（B1）、渠道商户号**全号**、账户状态、已启用的门店。
- **C2** Given 渠道为 `PENDING`，Then 该行状态显示「待開通」；Given 渠道 `ENABLED` 但账户未核验，Then 显示「待開通」并说明「待補密鑰並完成核驗」。
- **C3** Given 账户已关闭（CLOSED），Then 不显示；沙箱账户显示并带「沙箱」标签。
- **C4** Given 主体在 near-pay 没有支付商户，When 打开主体页，Then 渠道列表照旧（读 ai-shop 本地进件表 `mch_payment_merchant`，保留现有「去开通」入口）。
- **C5** Given 非本主体或无 `biz:finance` 权限，When 读取，Then 拒绝（现有 `GET /biz/merchant/payment` 的权限与归属校验不变）。
- **C6** Given 旧版 App，When 新后端上线，Then 列表仍能显示（只增字段；旧版不认识的状态显示为「暫不可用」）。

**不做**：在 App 里开通渠道或提交密钥（仍走运营端核验）；新建独立的「收款渠道」页；门店代码的自动生成；UME 店员账号同步（用户 2026-11-19 决定暂不做）；其余 UME 商户的导入（见 §8）。

## §0 对账一 · 需求 → 设计

| AC | 需求原文（一句话） | 落点 |
|---|---|---|
| A1 | 代码进支付链接 | 现有 `setSlug` → `StoreSyncPublisher` → near-pay `StoreCodeService#assignForProjection`；不改 |
| A2 | 运营、店主设代码 | 现有两个接口；不改 |
| A3 | 运营批量导入英文代码 | `deploy/ume/store-codes/import.py`（新，运维脚本，调 A2 运营接口）+ 结果报告 |
| B1 | 沙箱与开通状态 | near-pay `CatalogModels` 加 `PENDING`；运营渠道清单返回已有的 `status`、`testChannel` |
| B2 | 运营设待开通 | near-pay 新 `POST /pay-ops/v2/channels/{code}/pending`（与启用 / 停用同一命令格式与审计） |
| B3 | 待开通不能直接启用 | 现有 `CatalogValidationPolicy#validateChannelEnable`；`changeStatus` 允许 `PENDING → ENABLED` |
| B4 | 收银台不出现 | 现有收银台只取 `ENABLED`；回归 |
| C1 | 主体页渠道列表含全号 | near-pay 新内部接口 `GET /internal/pay/v1/biz/entities/{entityNo}/channel-accounts`；ai-shop `MerchantPaymentServiceImpl#list` 改为优先读它；`PaymentApplymentVO` 加字段 |
| C2 | 两种「待開通」 | near-pay `MerchantChannelQuery` 的 `pendingReason`；ai-shop 映射为 `applyStatus=PENDING_ACTIVATION` |
| C3 | 关闭不显示、沙箱标签 | `MerchantChannelQuery` 过滤与 `sandbox` |
| C4 | 无支付商户时照旧 | ai-shop 回退到 `mch_payment_merchant` |
| C5 | 权限不变 | 现有 `paymentAccess.require` + near-pay 内部守卫 |
| C6 | 兼容 | 只增字段；App 未知状态走 `ChannelState.unavailable` |

**孤立项**：无。前两版的「拼音自动生成」「新端点 `/biz/payment/channels` 与新页」「状态由系统算出」均按用户回复取消。

## §1 现状与影响面

- **门店代码**：`mch_store.slug`（唯一键、`StoreSlugs` 规则）已能进支付链接；全库只有少数门店有代码（今天给 oscar、roger 的门店补了 6 家）。`mch_store.store_code` 是小程序店铺码，不相关。
- **渠道目录**：`pay_channel.status` 只有 `ENABLED` / `DISABLED` 两个值，`test_channel` 标沙箱。今天新增的 7 个 UME 渠道暂以 `DISABLED` 表示待开通。现状（2026-11-19）：已开通 LINE Pay、藍新（TW）、PayBy（AE）；沙箱 LINE Pay 測試、NEWEBPAY Sandbox、街口支付測試（启用）、全支付測試（停用）；停用 Mockpay；待开通 7 个。
- **App 主体页渠道列表**：`merchant_api.dart#channels` → ai-shop `GET /biz/merchant/payment?entityNo=` → `MerchantPaymentServiceImpl#list` → **ai-shop 本地 `mch_payment_merchant`**（旧进件表，全库只有 9 条测试用微信进件）。App 固定列藍新、LINE Pay、街口，再附加后端多给的渠道；字段读 `payChannel`、`channelName`、状态、`payMerchantNo`、`enabledStores` 等。所以 UME 商户在 App 主体页看不到 near-pay 里的渠道账户。
- **near-pay 渠道账户**：`pay_merchant`，按支付商户（`platform_merchant_id`）挂；支付商户经 `pay_merchant_source_link.external_entity_id` 对应 ai-shop 主体号。
- **会被改到的**：`GET /biz/merchant/payment` 的数据来源与返回字段（只增）；near-pay 渠道状态取值。
- **明确不受影响**：`POST /biz/merchant/payment`（进件提交）、`/biz/merchant/pay-channel`、收银台、门店付款方式偏好、收款码。

## §2 方案

### 契约变更

- **端点**：
  - near-pay 新增运营 `POST /pay-ops/v2/channels/{channelCode}/pending`（请求体同 enable / disable：`requestNo`、`expectedVersion`、`reason`）。
  - near-pay 新增内部 `GET /internal/pay/v1/biz/entities/{entityNo}/channel-accounts`（内部令牌守卫；按来源关联找支付商户），返回：
    ```
    { entityNo, merchantNo,
      accounts: [ { payMerchantNo, channelCode, channelName, sandbox, channelStatus: ENABLED|PENDING|DISABLED,
                    channelMerchantId /*全号*/, accountStatus, verificationStatus,
                    pendingReason: CHANNEL_NOT_LIVE|ACCOUNT_UNVERIFIED|null, boundStoreNos: [] } ] }
    ```
  - ai-shop `GET /biz/merchant/payment` 返回的每行（`PaymentApplymentVO`）加：`channelMerchantId`、`sandbox`、`channelStatus`、`pendingReason`。主体在 near-pay 有支付商户时，行来自上面的内部接口；状态映射：账户 ACTIVE → `ACTIVE`，暂停 → `FROZEN`，其余未关闭 → `PENDING_ACTIVATION`。
- **库表**：`pay_channel.status` 取值加 `PENDING`（varchar，无 CHECK 约束，不需迁移；`CatalogModels` 加常量）。
- **权限码**：无新增（near-pay 运营用 `PERM_CHANNEL_MANAGE`；ai-shop 沿用 `biz:finance`）。
- **i18n**：后端无。App：「待開通」「沙箱」「渠道商戶號」「待補密鑰並完成核驗」及 7 个新渠道名（zh-TW / zh-CN / en）。
- **配置项**：无。

### 模块设计

| 动作 | 仓库 · 路径 | 说明 |
|---|---|---|
| 修改 | near-pay `pay-domain/.../catalog/CatalogModels.java` | 加 `PENDING` |
| 修改 | near-pay `ChannelCatalogService`、`OpsChannelController` | `markPending`（同 `changeStatus` 审计）；`changeStatus` 允许 `PENDING → ENABLED`（仍过适配器校验） |
| 新增 | near-pay `pay-domain/.../platmch/store/policy/MerchantChannelQuery.java` | 主体号 → 支付商户 → 渠道账户 × 渠道目录 × 门店绑定 |
| 修改 | near-pay `PayBizInternalApi`、`InternalPayBizEndpoint`（或新内部控制器） | 新 record 与内部端点 |
| 新增 | near-pay 测试 `ChannelPendingStatusTest`、`MerchantChannelQueryTest`、内部端点守卫测试 | B1–B3、C1–C3 |
| 修改 | ai-shop `backend/pay/pay-domain/.../client/PayBizInternalApi.java`（内嵌副本） | 同步新 record 与 `@GetExchange` |
| 修改 | ai-shop `backend/shop-merchant/.../service/impl/MerchantPaymentServiceImpl.java#list` | 有 near-pay 支付商户时读内部接口，否则照旧（C4）；经 `spi` 端口注入，内嵌形态不可用时照旧 |
| 修改 | ai-shop `PaymentApplymentVO` | 加四个字段 |
| 修改 | ai-shop 测试 `MerchantPaymentFlowTest`、新 `MerchantPaymentNearPaySourceTest` | C1、C4、C5 |
| 新增 | ai-shop `deploy/ume/store-codes/import.py` + `README.md` | A3：读 CSV（`storeNo,code`），调运营接口，输出结果 |
| 修改 | App `features/merchant/merchant_api.dart`、`merchant_pages.dart`、`entity_channel_rows.dart`、l10n | 解析新字段，显示渠道商户号、沙箱标签、「待開通」与新渠道名（交 App 会话） |

### 关键接口

```java
// near-pay & ai-shop：PayBizInternalApi
record EntityChannelAccount(String payMerchantNo, String channelCode, String channelName, boolean sandbox,
                            String channelStatus, String channelMerchantId, String accountStatus,
                            String verificationStatus, String pendingReason, List<String> boundStoreNos) {}
record EntityChannelAccounts(String entityNo, String merchantNo, List<EntityChannelAccount> accounts) {}
@GetExchange("/internal/pay/v1/biz/entities/{entityNo}/channel-accounts")
EntityChannelAccounts entityChannelAccounts(@PathVariable String entityNo);
```

### 实施计划（writing-plans）

1. near-pay：`PENDING` 状态与 `markPending` + 测试 → `MerchantChannelQuery` + 测试 → 内部端点与守卫测试。
2. ai-shop：内嵌 `PayBizInternalApi` 同步 → `MerchantPaymentServiceImpl#list` 改数据来源 + 测试 → `PaymentApplymentVO` 字段 → 生成物（端点清单）重跑。
3. 运维脚本 `store-codes/import.py`（A3）。
4. App（交 App 会话）：主体页渠道行显示新字段与状态。
5. 发布：near-pay 先、ai-shop 后（覆盖部署）；上线后用运营接口把 7 个 UME 渠道从 `DISABLED` 改为 `PENDING`。
6. 166 验证：oscar、roger 的 App 主体页渠道列表与 near-pay 一致（oscar 6 个、roger 7 个，含全号与「待開通」）；收款页付款方式不变（B4）。

## §3 决定

| # | 问题 | 决定 | 状态 |
|---|---|---|---|
| Q1 | 门店代码 | 英文；运营设置或批量导入，店主可改；不自动生成 | ✅ 用户 2026-11-19 |
| Q2 | 渠道状态 | 运营维护，存 `pay_channel.status`；新增 `PENDING` 表示待开通 | ✅ 用户 2026-11-19 |
| Q3 | 渠道商户号 | 全号，只给有 `biz:finance` 的商户管理员 | ✅ 用户 2026-11-19 |
| Q4 | 商户端入口 | App 现有主体页的渠道列表；后端 `GET /biz/merchant/payment` 改为以 near-pay 渠道账户为准 | ✅ 用户 2026-11-19 |
| Q5 | 主体在 near-pay 没有支付商户时 | 列表照旧读 ai-shop 本地进件表（C4） | ✅ 用户 2026-11-19 |

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| `GET /biz/merchant/payment` 数据来源切换 | 已有进件流程的商户看到的列表变了 | 只对 near-pay 已有支付商户的主体切换；其余照旧（C4）；端上字段只增 |
| 新状态值 `PENDING` 被别处当成未知值 | 运营界面显示异常 | 运营端渠道清单同步加该值；收银台只认 `ENABLED`，不受影响 |
| 渠道商户号全号外露 | 被截图泄露 | 只给 `biz:finance`；渠道商户号不是密钥 |
| 两端 `PayBizInternalApi` 副本不同步 | 远程调用失败 | 新接口独立；同一批次改两端并加转发测试 |

## §5 对账三 · 实现 → 需求（测试）

2026-11-19 在两个实现分支上运行（near-pay `feat/channel-pending-entity-accounts-20261119` `24a2ab6de`；ai-shop `codex/ops-fiat-local-closure` `1c476d3ef` 起）。

| AC | 测试方法 | 结果 |
|---|---|---|
| A1 | 166 验证（现有链路，未改代码）：`/s/ume-breakfast`、`/s/ume-cafe` 打开对应门店收银台 | ✅ |
| A2 | 现有接口未改；166 上用 `PUT /ops/stores/{storeNo}/basics` 写入 7 家门店代码 | ✅ |
| A3 | `deploy/ume/store-codes/test_import.py`：仅 `--apply` 才写、不合规 / 保留词 / 清单内重复 / 接口拒绝逐行报告且不影响其他行、同值重写幂等 | ✅ 3/3 |
| B1–B3 | near-pay `ChannelPendingStatusTest`：设为待开通写 `PENDING` 且不校验适配器；待开通启用仍被适配器校验拒绝 | ✅ 2/2 |
| B1 | near-pay 运营前端：`presentation.ts` 状态表加「待开通」，`channels-page.tsx` 行操作「标为待开通」；`tsc --noEmit` 通过，`channel-catalog`、`channel-test-attribute`、`channel-account-products-ui` 测试 | ✅ 29/29 |
| B4 | 收银台只取 `ENABLED` 渠道（未改代码）；回归 `CatalogServiceCommandTest` | ✅ 18/18 |
| C1–C3 | near-pay `MerchantChannelQueryTest`：全号、按目录排序、关闭不列、两种待开通原因、沙箱、启用门店；无已核验来源关联时返回空商户 | ✅ 2/2 |
| C1、C2、C4 | ai-shop `MerchantChannelListingTest`：有支付商户改读支付侧并映射 `PENDING_ACTIVATION` / 全号 / 启用门店；没有时原样返回本地进件表 | ✅ 2/2 |
| C1 | ai-shop `RemoteBizPaymentAppServiceTest#entityChannelAccountsCarryEntityAndSourceScope` | ✅ 5/5 |
| C5 | ai-shop `BizEndpointPermTest`（`/biz/merchant/payment` 权限未变）、`MerchantPaymentFlowTest` 回归 | ✅ 12/12、10/10 |
| C6 | App 侧，交 App 会话（旧版 App 遇未知 `applyStatus` 走 `ChannelState.unavailable`） | 待 App |

另跑：near-pay `StoreCollectionMethodServiceTest` 9/9、`PayBizFacadeCollectStatusTest` 3/3；ai-shop `StoreMethodPreferenceContractTest` 5/5、`ArchitectureTest` 23（1 跳过）。

## §6 对账二 · 设计 → 实现

near-pay `24a2ab6de`（`git show --stat`）：

```
 backend/pay/pay-domain/.../biz/PayBizFacade.java                       | +13
 backend/pay/pay-domain/.../catalog/CatalogModels.java                  |  +2
 backend/pay/pay-domain/.../catalog/ChannelCatalogService.java          |  +8 -1
 backend/pay/pay-domain/.../client/PayBizInternalApi.java               | +15
 backend/pay/pay-domain/.../merchant/account/ChannelAccountService.java |  +6
 backend/pay/pay-domain/.../platmch/store/policy/MerchantChannelQuery.java      | 新增
 backend/pay/pay-domain/src/test/.../catalog/ChannelPendingStatusTest.java      | 新增
 backend/pay/pay-domain/src/test/.../policy/MerchantChannelQueryTest.java       | 新增
 backend/pay/pay-portal/.../ops/v2/OpsChannelController.java            |  +8
 backend/pay/pay-svc/.../svc/InternalPayBizEndpoint.java                |  +5
 ops-web/features/catalog/channels-page.tsx、commands.tsx               | 待开通操作与确认框
 ops-web/lib/channel-catalog/mock.ts、presentation.ts、types.ts         | PENDING 状态
```

ai-shop `1c476d3ef`：`pay/pay-domain/.../PayBizInternalApi.java`（副本同步）、`payclient/BizPaymentAppService.java`、`impl/BizPaymentAppServiceImpl.java`、`impl/RemoteBizPaymentAppService.java`、`payclient/MerchantChannelListing.java`（新）、`portal/biz/BizMerchantController.java`、`merchant/dto/PaymentApplymentVO.java` 与两份测试；以及本次的 `deploy/ume/store-codes/`（脚本、测试、说明）。

| 差异 | 说明 |
|---|---|
| TDD 列了 `MerchantPaymentServiceImpl#list`，实际没动 | `shop-merchant` 不依赖支付模块；改在 `shop-app` 新增 `MerchantChannelListing`，由 `BizMerchantController#payments` 先问支付侧、没有支付商户再回退到 `paymentService.list` |
| TDD 没写、实际新增 `PaymentApplymentVO.enabledStores` | App 主体页本来就读 `enabledStores[].storeNo`；支付侧行用它带出已绑定门店。保留旧 14 参构造，原调用不变 |
| TDD 没写、实际改了 near-pay `ops-web` | 运营前端要能显示与设置「待开通」（B1 的「运营查看」） |
| `MerchantPaymentNearPaySourceTest` 未新建 | 由 `MerchantChannelListingTest` 覆盖同一行为 |
| 导入脚本不预读现有代码 | 运营门店详情 `GET /ops/stores/{storeNo}` 不回显代码；改为写后以返回值回读，同值重写幂等 |

### 偏差说明

上表即全部偏差，均已同步到本节；§2 的设计意图不变（数据来源以支付侧为准、无支付商户回退本地）。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-19 | 数据先行（运营接口，UME166）：7 个 UME 渠道入目录（暂为 `DISABLED`）；oscar、roger 的 11 个渠道账户；藍新 `MS354053380` 按 UME 数据登记给 roger |
| 2026-11-19 | roger、oscar 导入：门店英文代码 `ume-breakfast`、`ume-cafe`、`chuangke-pay`、`jeff-first-home`、`ume-plan-pay`、`chuangke-sam`、`chuangke-oscar`（运营接口，店主可改）；roger 的 5 家店尚无收款码，代码待开码后进链接 |
| 2026-11-19 | 第一版草稿（付款方式接口加 `pendingChannels`）→ 第二版（拼音代码、系统计算状态、新页）→ 第三版（按用户四点回复） |
| 2026-11-19 | 用户确认第三版（「确认，开始实现」）；near-pay `24a2ab6de`、ai-shop `1c476d3ef` 与导入脚本实现完成，相关测试全绿（§5）。未推送、未部署；上线后用运营接口把 7 个 UME 渠道改为 `PENDING` |
| 2026-11-19 | 推送：near-pay `develop/s1` `525207556`（含 V112 排序规则修复）；ai-shop 推送过程中补了契约示例与 OpenAPI（`720496ec1`）、状态词申报（`a6dc6926d`）。上线 UME166（覆盖部署，校验清单见 `deploy/ume/entity-channels/`）：near-pay `dbc3a6b2da0a → 75b5a2272e78`，备份 `pay-svc.jar.before-entity-channels-20261119-525207556`，V112 已由 root 执行并登记 `flyway_schema_history`（checksum −1256008217）；ai-shop `d0ade3d80fc0 → a43238a6ecc1`，备份 `shop-app.jar.before-entity-channels-20261119-1c476d3ef`。7 个 UME 渠道已用 `POST /pay-ops/v2/channels/{code}/pending` 改为 `PENDING`；收银台付款方式不变（B4）。按接口规则用 SQL 复核：oscar 9 个账户、roger 7 个，原因与状态符合 C1–C3；`/biz/merchant/payment` 需店主登录，端到端验收待 App |

## §8 后续（不计入本 TDD 验收）

- 其余 UME 商户的导入：门店英文代码清单（A3 脚本）、渠道账户批量登记（按原始导出的真实渠道商户号）、支付商户（多数主体仍为 PENDING_LICENSE、无运营主体）。
- UME 内多户共用的渠道商户号（如藍新 `MS354053380`）在批量时按 UME 数据逐户判定。

# TDD-门店代码与商户渠道展示

状态：草稿（2026-11-19 第二版，按用户两条新需求改写；待确认 §3 后实施）
档位：2（跨端：ai-shop 门店数据与定时任务 + near-pay 渠道目录与内部业务接口 + ai-shop 新 `/biz/payment` 端点 + Flutter 商户 App；不改库表、不加权限码，新增一个 Java 依赖）
关联需求：用户 2026-11-19 口述——
1. 「门店数据要增加门店名称代码，给到支付链接用」；未设代码的门店「用门店拼音或者英文作为代码」。
2. 「支付系统整理全局渠道列表，根据系统状况设置渠道状态，是否沙箱，是否已开通。商户端关联渠道基础数据进行展示，商户端的渠道包含渠道商户号」（显示全号）。
3. 此前：「暂时不支持的渠道在系统中标注为待开通，但是要同步到数据库中」。

功能矩阵（`docs/requirements/支付域-功能矩阵.md`、`运营端-账户商户门店渠道-功能矩阵.md`）无这几条；本 TDD 的 AC 即需求，确认后回写功能矩阵。
关联设计：[TDD-门店付款方式-App设置](TDD-门店付款方式-App设置.md) §9；门店代码现有实现 V357 `mch_store.slug`、`StoreSlugs`、`NearPayStoreProfile#storeCode`、near-pay `StoreCodeService`；交接 [oscar-ume-align-2026-11-19](../handoff/oscar-ume-align-2026-11-19.md)。
创建：2026-11-19 · 最后更新：2026-11-19（第二版）

## 验收标准（需求）

### A 门店名称代码（支付链接用）

- **A1** Given 门店没有门店代码，When 自动分配任务运行，Then 按门店名称生成代码：中文转拼音、英文保留，小写、单词间连字符，3–32 位，符合 `StoreSlugs` 规则。例：「ume早餐店」→ `ume-zao-can-dian`，「QA166 Retail Test」→ `qa166-retail-test`。
- **A2** Given 生成的代码已被别的门店占用或是保留词，When 分配，Then 追加 `-2`、`-3`… 直到唯一（超长时先截断再追加）；名称转不出任何字母数字时，回退为 `s` + 门店号末 8 位。
- **A3** Given 门店已有代码（店主或运营设过），When 任务运行，Then 不改动。
- **A4** Given 门店有代码且有启用的整店收款码，When 门店同步到 near-pay，Then `tpay.neargo.ai/s/<代码>` 打开该门店收银台（沿用现有同步与 `StoreCodeService`）。没有整店收款码的门店只分配代码，链接在开码后自动生效。
- **A5** Given 店主想换成好记的代码，When 在 App 修改，Then 沿用现有 `POST /biz/store/{storeNo}/slug`，占用时提示「代码已被使用」。

### B 全局渠道清单（支付系统）

- **B1** Given 渠道目录中的任一渠道，When 运营查看渠道清单，Then 每个渠道显示「是否沙箱」（`test_channel`）与「开通状态」，开通状态由系统状况算出、不手填：
  - `OPEN` 已开通：目录启用，且适配器已在代码中注册；
  - `PENDING` 待开通：适配器尚未注册（UME 迁来的橘子支付、支付寶等）；
  - `DISABLED` 已停用：适配器已注册，但目录停用。
- **B2** Given 一个 `PENDING` 渠道，When 运营尝试启用，Then 仍被现有校验拒绝（`validateChannelEnable`），状态不变。
- **B3** Given 一个 `PENDING` 或 `DISABLED` 渠道，When 顾客打开任一门店收款页，Then 付款方式里不出现它（保持现状，作回归）。

### C 商户端渠道列表

- **C1** Given 商户名下有渠道账户，When 店主在 App 打开「收款渠道」，Then 每个账户一行：渠道名称、是否沙箱、渠道开通状态（B1）、渠道商户号**全号**、账户状态（已启用 / 待核验 / 已暂停等）、是否已绑定当前门店。
- **C2** Given 渠道 `PENDING`，Then 该行显示「待開通」，说明「平台尚未支援此渠道」；Given 渠道 `OPEN` 但账户未核验，Then 显示「待開通」，说明「待補密鑰並完成核驗」。
- **C3** Given 账户已关闭（CLOSED），Then 不显示；沙箱账户显示并带「沙箱」标签。
- **C4** Given 非店主（商户管理员）或非本商户门店，When 读取，Then 拒绝（与 `store-method-preference` 同一权限 `mch:pay:store-method`）。
- **C5** Given 旧版 App，When 服务端已上线新端点，Then 旧功能不受影响（新端点只读、独立）。

**不做**：在 App 里开通渠道或提交密钥（仍走运营端「渠道账户」核验）；运营端界面大改（渠道清单只加两列）；UME 店员账号同步（用户 2026-11-19 决定暂不做）；自动为门店开整店收款码；其余 UME 商户的渠道批量登记（数据操作，见 §8）。

## §0 对账一 · 需求 → 设计

| AC | 需求原文（一句话） | 落点 |
|---|---|---|
| A1 | 无代码的门店按名称拼音 / 英文生成 | ai-shop 新 `StoreSlugGenerator`（拼音转换）+ 新定时任务 `StoreSlugBackfillJob` |
| A2 | 撞码与转不出时的规则 | `StoreSlugGenerator#candidates` + `StoreAdminService#setSlug` 的唯一约束兜底 |
| A3 | 已有代码不动 | 任务只扫 `slug IS NULL` |
| A4 | 代码进支付链接 | 现有 `setSlug` 推 `updated_at` → `StoreSyncPublisher` → near-pay `StoreCodeService#assignForProjection`；不改 |
| A5 | 店主可改 | 现有 `POST /biz/store/{storeNo}/slug`；不改 |
| B1 | 渠道沙箱与开通状态由系统算出 | near-pay `ChannelAvailability`（新，纯函数）；`ChannelCatalogService#get/list` 的视图加 `availability` |
| B2 | 待开通不能启用 | 现有 `CatalogValidationPolicy#validateChannelEnable`；回归 |
| B3 | 收银台不出现 | 现有 `CheckoutServiceImpl#codeMethods`；回归 |
| C1 | 商户渠道列表含全号 | near-pay 新 `MerchantChannelQuery` + 内部端点 `GET /internal/pay/v1/biz/stores/{storeNo}/channels`；ai-shop 新 `GET /biz/payment/channels`；App 新「收款渠道」页 |
| C2 | 两种「待開通」原因 | `MerchantChannelQuery` 的 `pendingReason` |
| C3 | 关闭不显示、沙箱带标签 | `MerchantChannelQuery` 过滤与 `sandbox` 字段 |
| C4 | 权限 | ai-shop `@perm.canBiz(mch:pay:store-method)` + near-pay `PayBizFacade#ownerOf` |
| C5 | 兼容 | 新端点，旧接口不改 |

**孤立项**：无。第一版里的「在 `store-method-preference` 加 `pendingChannels`」被 C 取代，不再实施。

## §1 现状与影响面（2026-11-19 只读盘点 + 已执行的数据同步）

- **门店代码已有一半**：`mch_store.slug`（V357，唯一键 `uk_mch_store_slug`，规则 `StoreSlugs`：`^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$` 加保留词）。
  - 店主 `POST /biz/store/{storeNo}/slug`、运营 `PUT /ops/stores/{storeNo}/basics` 都能设。
  - 门店同步把它作为 `storeCode` 推给 near-pay，挂到该门店启用中的整店收款码上，形成 `/s/<代码>`（今天 `ume-breakfast` 就是这样切到新门店的）。
  - **缺口**：832 家门店只有 4 家有代码，UME 原系统的「门店号」只是商户内序号（1、2、3…），不能沿用。
  - 注意：`mch_store.store_code` 是小程序店铺码（如 `UME1002708307355500544`），不是这里的代码。
- **渠道目录**：`pay_channel` 有 `status`（ENABLED / DISABLED）与 `test_channel`。适配器是否注册只在启用时校验，目录与运营接口不展示。今天已新增 7 个待开通渠道（适配器未注册、DISABLED）。
- **渠道账户**：`pay_merchant`，商户端已有 `/biz/pay/v2/channel-accounts`（掩码、无渠道名、ai-shop 未转发）。今天为 oscar、roger 登记了 11 个 UNVERIFIED 账户。
- **商户端读取链（照抄）**：App → ai-shop `BizPaymentController` → `RemoteBizPaymentAppService` → near-pay `InternalPayBizEndpoint` → `PayBizFacade`。两端各有一份 `PayBizInternalApi`（ai-shop 在 `backend/pay/pay-domain` 内嵌一份）。
- **会被改到的**：near-pay 运营渠道清单的返回（加字段）；ai-shop 新增一个定时任务与一个只读端点。
- **明确不受影响**：收银台、门店付款方式偏好、收款码、店主改代码的接口、运营改门店基础信息。

## §2 方案

### 契约变更

- **端点**：
  - 新增 ai-shop `GET /biz/payment/channels`（门店取 `BizContext.requireStoreNo()`），返回：
    ```
    { storeNo, merchantNo,
      channels: [ { channelCode, channelName, sandbox, availability: OPEN|PENDING|DISABLED,
                    payMerchantNo, channelMerchantId /*全号*/, accountStatus, verificationStatus,
                    pendingReason: CHANNEL_NOT_LIVE|ACCOUNT_UNVERIFIED|null, boundToStore } ] }
    ```
  - 新增 near-pay 内部 `GET /internal/pay/v1/biz/stores/{storeNo}/channels?operatorNo=`（与 method-preferences 同一守卫与归属校验）。
  - near-pay 运营 `GET /pay-ops/v2/channels`、`/channels/{code}` 返回加 `availability`（只增字段）。
- **库表 / 迁移**：无。
- **权限码**：无新增；`/biz/payment/channels` 用 `mch:pay:store-method`。
- **i18n**：后端无。App 新增约 10 个词条（zh-TW / zh-CN / en）。
- **配置项**：ai-shop `shop.store.slug-backfill.enabled`（默认 `true`）、`shop.store.slug-backfill.batch-size`（默认 200）。
- **依赖**：ai-shop `shop-merchant` 新增拼音库 `com.belerweb:pinyin4j:2.5.1`（支持繁体字；见 §3 Q2）。

### 模块设计

| 动作 | 仓库 · 路径 | 说明 |
|---|---|---|
| 新增 | ai-shop `backend/shop-merchant/.../merchant/StoreSlugGenerator.java` | 名称 → 候选代码：汉字逐字转无声调拼音、英文与数字保留，非字母数字作分隔，合并连字符，截断到 32；`candidates(name, storeNo)` 依次给出 base、base-2…、`s`+门店号末 8 位 |
| 新增 | ai-shop `backend/shop-merchant/.../merchant/job/StoreSlugBackfillJob.java` | `@Scheduled` + `@SchedulerLock`；每批取 `slug IS NULL AND deleted=0` 的门店，逐个候选调 `StoreAdminService#setSlug`，遇 `STORE_SLUG_TAKEN` 或保留词换下一个 |
| 修改 | ai-shop `backend/shop-merchant/pom.xml` | 加 pinyin4j |
| 新增 | ai-shop 测试 `StoreSlugGeneratorTest`、`StoreSlugBackfillJobTest` | A1–A3 |
| 新增 | near-pay `pay-domain/.../catalog/ChannelAvailability.java` | `of(PayChannel, AdapterRegistry)` → OPEN / PENDING / DISABLED |
| 修改 | near-pay `ChannelCatalogService` 视图与 `OpsChannelController` DTO | 加 `availability` |
| 新增 | near-pay `pay-domain/.../platmch/store/policy/MerchantChannelQuery.java` | 商户名下 `owner_type=MERCHANT` 且未关闭的账户 × 渠道目录 × 当前门店绑定 → 列表（全号） |
| 修改 | near-pay `PayBizInternalApi`、`PayBizFacade`、`InternalPayBizEndpoint` | 新 record `MerchantChannels` / `MerchantChannel` 与端点 |
| 新增 | near-pay 测试 `ChannelAvailabilityTest`、`MerchantChannelQueryTest`、端点守卫测试 | B1、C1–C3 |
| 修改 | ai-shop `backend/pay/pay-domain/.../client/PayBizInternalApi.java`（内嵌副本） | 同步新 record 与 `@GetExchange` |
| 修改 | ai-shop `BizPaymentAppService` / `RemoteBizPaymentAppService` / `BizPaymentAppServiceImpl` / `BizPaymentController` | 新方法与端点；内嵌形态抛 `PAY_CHANNEL_UNAVAILABLE`（同 method-preferences） |
| 修改 | ai-shop 测试 `BizEndpointPermTest`、新 `MerchantChannelsRelayTest` | C1、C4 |
| 修改 | ai-shop 生成物：业务权限矩阵 / 端点清单 | 新端点登记（`scripts/check-generated-docs.mjs`） |
| 新增 | App `features/payment_operations/data/merchant_channel_api.dart`、`presentation/merchant_channels_page.dart` | 交 App 会话实施 |
| 修改 | App 门店管理入口、l10n、mock 路由 | 同上；改了界面要更新 `ui-catalog.json` |

### 关键接口

```java
// near-pay & ai-shop：PayBizInternalApi
record MerchantChannel(String channelCode, String channelName, boolean sandbox, String availability,
                       String payMerchantNo, String channelMerchantId, String accountStatus,
                       String verificationStatus, String pendingReason, boolean boundToStore) {}
record MerchantChannels(String storeNo, String merchantNo, List<MerchantChannel> channels) {}
@GetExchange(PREFIX + "/channels") MerchantChannels channels(@PathVariable String storeNo, @RequestParam String operatorNo);

// near-pay
enum Availability { OPEN, PENDING, DISABLED }
Availability ChannelAvailability.of(PayChannel channel, AdapterRegistry adapters)

// ai-shop
List<String> StoreSlugGenerator.candidates(String storeName, String storeNo)
```

### 实施计划（writing-plans）

1. near-pay：`ChannelAvailability` + 测试 → 运营渠道清单加字段 → `MerchantChannelQuery` + 测试 → 内部端点与 facade。
2. ai-shop：`StoreSlugGenerator` + 测试（繁简中文、中英混合、全符号、超长、保留词、撞码）→ `StoreSlugBackfillJob` + 测试 → 内嵌 `PayBizInternalApi` 同步 → `/biz/payment/channels` 与权限测试 → 重跑生成物。
3. App（交 App 会话）：渠道列表页、入口、l10n、mock、`flutter test`、模拟器截图、`ui-catalog.json`。
4. 发布：near-pay 先、ai-shop 后（覆盖部署）；166 上任务首次运行会给约 828 家门店分配代码，先在 UME166 只读演算一遍，把结果清单给用户过目再开任务（见 §4）。
5. 166 验证：oscar 门店页 6 个渠道（含 4 个待开通）、roger 7 个；抽查若干门店 `/s/<代码>`；两家收款页付款方式不变（B3）。

## §3 决定

| # | 问题 | 决定 / 建议 | 状态 |
|---|---|---|---|
| Q1 | 未设代码的门店怎么处理 | 按门店名称拼音或英文生成，店主可改 | ✅ 用户 2026-11-19 |
| Q2 | 拼音库 | `pinyin4j 2.5.1`：老牌、纯 Java、无传递依赖、覆盖繁体字。多音字取第一读音（如「重」→ `zhong`），店主可改 | 待确认 |
| Q3 | 渠道商户号显示 | 全号，只给店主（商户管理员） | ✅ 用户 2026-11-19 |
| Q4 | 渠道开通状态怎么算 | 由「目录状态 + 适配器是否注册」算出，不新增手填字段（B1）；以后要加凭据、端点就绪度时扩展这个函数 | 待确认 |
| Q5 | 商户端入口 | App 门店管理下新增「收款渠道」页，列商户名下全部渠道账户，标出是否绑定本门店；不放进「付款方式」开关页 | 待确认 |
| Q6 | 批量分配代码的上线方式 | 先只读演算并给清单，确认后再启用任务（`slug-backfill.enabled`） | 待确认 |

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 拼音生成的代码不好看或有歧义 | 商户不喜欢 | 店主可改；上线前出清单过目（Q6） |
| 一次给 800+ 家门店写 `slug` 会触发同样数量的门店同步 | near-pay 同步压力 | 分批（默认 200）；同步本来是异步、幂等的 |
| 名称相同的门店多（如多家「早餐店」） | 代码带 `-2`、`-3` | 规则可预期，店主可改 |
| 渠道商户号全号外露 | 被截图泄露 | 只给店主；渠道商户号不是密钥，不能用来收款或退款 |
| 两端 `PayBizInternalApi` 副本不同步 | 远程调用失败 | 新端点独立，不影响旧端点；同一提交里改两端，并加转发测试 |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 |
|---|---|---|
| A1 | ai-shop `StoreSlugGeneratorTest#chineseBecomesPinyinAndEnglishIsKept` | 待实现 |
| A2 | ai-shop `StoreSlugGeneratorTest#collisionsReservedWordsAndUnconvertibleNamesFallBack`、`StoreSlugBackfillJobTest#takenCodeMovesToNextCandidate` | 待实现 |
| A3 | ai-shop `StoreSlugBackfillJobTest#storesWithACodeAreLeftAlone` | 待实现 |
| A4 | 166 验证（现有同步链路，不改代码） | 待实现 |
| A5 | 现有 `setSlug` 测试回归 | 待实现 |
| B1 | near-pay `ChannelAvailabilityTest#openPendingDisabled` | 待实现 |
| B2 | near-pay 现有 `validateChannelEnable` 测试回归 | 待实现 |
| B3 | near-pay `CheckoutServiceImpl` 付款方式测试补一例（停用渠道账户不进付款方式） | 待实现 |
| C1–C3 | near-pay `MerchantChannelQueryTest`（全号、两种原因、关闭不列、沙箱标记、绑定本门店） | 待实现 |
| C4 | ai-shop `BizEndpointPermTest`（`/biz/payment/channels` → `mch:pay:store-method`） | 待实现 |
| C5 | ai-shop `MerchantChannelsRelayTest`；现有 `StoreMethodPreferenceContractTest` 回归 | 待实现 |

## §6 对账二 · 设计 → 实现（实现完再填）

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-19 | 数据先行：7 个待开通渠道与 oscar、roger 的 11 个渠道账户已写入 UME166（运营接口，见交接） |
| 2026-11-19 | 第一版草稿（仅在付款方式接口加 `pendingChannels`） |
| 2026-11-19 | 第二版：按用户两条新需求改为「门店代码 + 全局渠道清单 + 商户端渠道列表」；Q1、Q3 已由用户决定 |

## §8 后续（不计入本 TDD 验收）

- 其余 UME 迁移商户的渠道账户批量登记：按原始导出（真实渠道商户号，不用计划表里的 UME 记录号），复用 oscar、roger 的做法；前提是各商户已有支付商户。
- UME 内多户共用的渠道商户号（如藍新 `MS354053380`）在批量时按 UME 数据逐户判定。

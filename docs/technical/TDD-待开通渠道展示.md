# TDD-待开通渠道展示

状态：草稿（2026-11-19，待用户确认 §3 决定后实施）
档位：2（跨端：near-pay 内部业务接口返回结构 + ai-shop `/biz/payment/store-method-preference` 透传 + Flutter 商户 App 页面；不改库表、不加权限码）
关联需求：用户 2026-11-19 口述「暂时不支持的渠道在系统中标注为待开通，但是要同步到数据库中」「在商户端要展示对应的信息」。功能矩阵（`docs/requirements/支付域-功能矩阵.md`、`运营端-账户商户门店渠道-功能矩阵.md`）无此条，本 TDD 的 AC 即需求，确认后回写功能矩阵。
关联设计：[TDD-门店付款方式-App设置](TDD-门店付款方式-App设置.md) §9（`store-method-preference` 契约，本 TDD 在其返回里加一个字段）；交接 [oscar-ume-align-2026-11-19](../handoff/oscar-ume-align-2026-11-19.md)（数据已同步的部分）
创建：2026-11-19 · 最后更新：2026-11-19

## 验收标准（需求）

- **AC1** Given 商户名下有 UME 迁来的渠道账户、该渠道在 near-pay 渠道目录中为停用（尚未对接，如橘子支付、支付寶），When 商户管理员在 App 打开门店「付款方式」页，Then 页面「待開通渠道」区列出：渠道名称、渠道商户号（只显示末 4 位）、状态「待開通」、原因「平台尚未支援此渠道」；不能开关。
- **AC2** Given 渠道已对接（LINE Pay、藍新）但商户的渠道账户还未核验（未补密钥），When 打开同一页，Then 该账户也列在「待開通渠道」区，原因「待補密鑰並完成核驗」。
- **AC3** Given 渠道账户已启用（ACTIVE），When 打开同一页，Then 它不出现在「待開通渠道」区（已启用的照旧以付款方式出现在上方开关区）。
- **AC4** Given 账户属于测试渠道（`test_channel=1`）或已关闭（CLOSED），When 打开同一页，Then 不出现在「待開通渠道」区。
- **AC5** Given 商户有待开通渠道，When 顾客打开该商户任一门店的收款页，Then 付款方式里不出现待开通渠道（保持现状，作回归）。
- **AC6** Given 服务端是旧版本（返回里没有该字段），When App 打开同一页，Then 不显示「待開通渠道」区、不报错；旧版 App 收到新字段时忽略，原有开关不受影响。
- **AC7** Given 非商户管理员或非本商户门店，When 读取，Then 与现有 `store-method-preference` 一样拒绝（不新增权限码）。

**不做**：在 App 里开通渠道或提交密钥（仍走运营端「渠道账户」核验）；运营端界面改动（运营端渠道账户列表已能看到 UNVERIFIED）；UME 店员账号同步（用户 2026-11-19 决定暂不做）；其余 UME 商户的渠道批量登记（数据操作，另按批量脚本执行，见 §8）。

## §0 对账一 · 需求 → 设计

| AC | 需求原文（一句话） | 落点 |
|---|---|---|
| AC1 | 停用渠道的账户列为待开通、显示掩码商户号 | near-pay `PendingChannelQuery#ofMerchant`（新）→ `PayBizInternalApi.MethodPreferences.pendingChannels`；App `StorePaymentMethodsPage` 新区块 |
| AC2 | 已对接但未核验的账户也列为待开通 | 同上，`reason=ACCOUNT_UNVERIFIED` |
| AC3 | 已启用的不列 | `PendingChannelQuery` 过滤 `status=ACTIVE` |
| AC4 | 测试渠道、已关闭不列 | `PendingChannelQuery` 过滤 `test_channel=1`、`status=CLOSED` |
| AC5 | 收银台不出现 | 不改收银台；停用渠道无产品，`CheckoutServiceImpl#codeMethods` 本来取不到。回归测试 |
| AC6 | 新旧版本互相兼容 | 字段只增不改；App `StoreMethodApi` 缺字段按空列表 |
| AC7 | 权限同现状 | 复用 `PayBizFacade#ownerOf` 与 ai-shop `mch:pay:store-method` |

**孤立项**：无。

## §1 现状与影响面（2026-11-19 只读盘点 + 已执行的数据同步）

- **数据已在库**（2026-11-19，经运营接口写入 UME166 `nearpay_prod`，见交接文档）：
  - `pay_channel` 新增 7 个停用渠道：`GAMAPAY` 橘子支付、`ALIPAY` 支付寶、`EZPAY` ezPay簡單付、`EZPAY_CROSSBORDER` ezPay境外、`FUBON_WECHAT` 富邦微信、`PIPAY` Pi拍錢包、`ATOME` Atome（市场 TW，适配器未注册，状态 DISABLED；渠道目录本就允许「先登记、保持停用」，启用时才校验适配器）。
  - `pay_merchant`（渠道账户）为 oscar、roger 两户登记 11 个 UNVERIFIED 账户，凭证指向 UME 原记录。
- **商户端读取链**：App `GET /biz/payment/store-method-preference` → ai-shop `BizPaymentController#methodPreferences` → `RemoteBizPaymentAppService` → near-pay `GET /internal/pay/v1/biz/stores/{storeNo}/method-preferences`（`InternalPayBizEndpoint`）→ `PayBizFacade#methodPreferences` → `StoreCollectionMethodService#methodView`。返回 `PayBizInternalApi.MethodPreferences`，两端各有一份同名 record（ai-shop 在 `backend/pay/pay-domain` 内嵌一份，供内嵌形态与反序列化）。
- **现有返回只按「付款方式」列**：由门店绑定的账户 × 账户已开产品算出。待开通账户没有产品、也没有门店绑定，所以现在 App 看不到。
- **可复用**：
  - `ChannelAccountService#list(tenant, Query)`（商户名下账户，已有按 `merchantNo` 过滤）与 `ChannelAccountService.mask`（末 4 位，`BizChannelAccountDto` 已在用）。
  - 渠道名称：`CatalogSnapshot.channels().get(code).getName()`。
  - near-pay 商户端 `/biz/pay/v2/channel-accounts` 已返回掩码账户，但 ai-shop 未转发、无渠道名，且要走 `PayBizSourceDelegation`；不选它（见 §3 Q1）。
- **会被改到的**：`store-method-preference` 的 GET 与 PUT 返回（PUT 也返回 `MethodPreferences`）。
- **明确不受影响**：收银台付款方式、门店偏好保存逻辑与 `eligibilityRevision`（待开通列表不参与摘要，不会让 App 保存时误报冲突）、运营端门店收款配置、旧接口 `/biz/payment/store-method`。

## §2 方案

### 契约变更

- **端点**：无新增。`GET|PUT /biz/payment/store-method-preference` 返回增加一个字段（只增不改）：

```
{ storeNo, mode, version, eligibilityRevision, items: [...],          // 不变
  pendingChannels: [                                                  // 新增，可为空数组
    { channelCode: "GAMAPAY",              // 渠道目录编码
      channelName: "橘子支付",              // 渠道目录名称（目录原文，不翻译）
      channelMerchantIdMasked: "**0421",   // ChannelAccountService.mask，末 4 位
      reason: "CHANNEL_NOT_LIVE" }          // CHANNEL_NOT_LIVE | ACCOUNT_UNVERIFIED
  ] }
```

  - `reason`：渠道目录状态不是 ENABLED → `CHANNEL_NOT_LIVE`；否则账户不是 ACTIVE → `ACCOUNT_UNVERIFIED`。端上未知值按「暫不可用」显示。
  - 排序：渠道目录 `sort_no`，再按渠道商户号。
  - near-pay 内部接口 `GET|PUT /internal/pay/v1/biz/stores/{storeNo}/method-preferences` 同步加该字段。
- **库表 / 迁移**：无（数据已按现有表写入）。
- **权限码**：无（沿用 `mch:pay:store-method`）。
- **i18n**：后端无。App 新增 4 个词条（zh-TW / zh-CN / en）：区块标题「待開通渠道」、状态「待開通」、两种原因。
- **配置项**：无。

### 模块设计

| 动作 | 仓库 · 路径 | 说明 |
|---|---|---|
| 新增 | near-pay `pay-domain/.../platmch/store/policy/PendingChannelQuery.java` | `List<PendingChannel> ofMerchant(tenant, merchantId)`：取商户名下 `owner_type=MERCHANT` 账户，排除 CLOSED、ACTIVE、测试渠道；按目录状态定 `reason` |
| 修改 | near-pay `pay-domain/.../client/PayBizInternalApi.java` | `MethodPreferences` 加 `List<PendingChannel> pendingChannels`；新 record `PendingChannel(channelCode, channelName, channelMerchantIdMasked, reason)` |
| 修改 | near-pay `pay-domain/.../biz/PayBizFacade.java` | `methodPreferences` 与 `saveMethodPreferences` 都附上 `PendingChannelQuery#ofMerchant(owner.platformMerchantId())` |
| 新增 | near-pay `pay-domain/src/test/.../PendingChannelQueryTest.java` | AC1–AC4 |
| 修改 | near-pay `PayBizFacade` 相关测试 | 返回含 `pendingChannels`，保存后仍含 |
| 修改 | ai-shop `backend/pay/pay-domain/.../client/PayBizInternalApi.java` | 同上两个 record 改动（远程形态靠它反序列化，缺字段时 Jackson 置 null → 统一转空数组） |
| 修改 | ai-shop `backend/shop-app/.../payclient/impl/BizPaymentAppServiceImpl.java` | 内嵌形态现读写均抛 `PAY_CHANNEL_UNAVAILABLE`，不变；只补构造参数 |
| 修改 | ai-shop `backend/shop-app/src/test/.../StoreMethodPreferenceContractTest.java` | 透传 `pendingChannels`；远程缺字段时为空数组（AC6） |
| 修改 | App `apps/merchant_app/lib/features/payment_operations/data/store_method_api.dart` | 解析 `pendingChannels`，缺省空列表 |
| 修改 | App `.../presentation/store_payment_methods_page.dart` | 开关区下方新增「待開通渠道」只读区；为空不显示 |
| 修改 | App l10n（zh-TW / zh-CN / en） | 4 个词条 |
| 修改 | App `lib/dev/mock/mock_store_routes.dart` 与对应测试 | mock 返回带两条待开通 |

### 关键接口

```java
// near-pay & ai-shop：PayBizInternalApi
record PendingChannel(String channelCode, String channelName, String channelMerchantIdMasked, String reason) {}
record MethodPreferences(String storeNo, String mode, long version, String eligibilityRevision,
                         List<MethodPreference> items, List<PendingChannel> pendingChannels) {}

// near-pay
public List<PayBizInternalApi.PendingChannel> PendingChannelQuery#ofMerchant(String tenantNo, long platformMerchantId)
```

### 实施计划（writing-plans）

1. near-pay：`PendingChannelQuery` + 单测（AC1–AC4，先红后绿）→ record 加字段 → `PayBizFacade` 接入 → 现有 `StoreCollectionMethodServiceTest` / facade 测试回归。
2. ai-shop：record 加字段 → `StoreMethodPreferenceContractTest` 补透传与缺字段两例（AC6）→ `BizEndpointPermTest` 回归（AC7）。
3. App（交给 App 会话）：解析、页面区块、l10n、mock；`flutter test` 与模拟器截图。
4. 发布：near-pay 与 ai-shop 按覆盖部署（先 near-pay 后 ai-shop，旧 ai-shop 忽略新字段，顺序反了也只是暂时不显示）；App 随下个联调包。
5. 166 验证：oscar 门店页显示 4 个待开通（橘子支付、富邦微信 ×2、ezPay），roger 门店页显示 7 个（含 LINE Pay、藍新「待補密鑰並完成核驗」）；两家收款页付款方式不变（AC5）。

## §3 决定（待用户确认）

| # | 问题 | 建议 | 其他选项 |
|---|---|---|---|
| Q1 | 走哪条接口 | ✅ 在 `store-method-preference` 返回里加字段：App 已在用、权限与归属已校验、只增字段 | 转发 near-pay `/biz/pay/v2/channel-accounts`：要走源映射与授权代理，且无渠道名 |
| Q2 | 已对接但未核验的账户算不算待开通 | ✅ 算（AC2），原因与「平台尚未支援」分开 | 只列停用渠道 |
| Q3 | 渠道商户号显示多少 | ✅ 末 4 位（与现有商户端口径一致） | 全号；不显示 |
| Q4 | 列表范围 | ✅ 商户名下全部待开通账户（与门店无关，待开通账户本来没有门店绑定） | 只列与本门店有关的 |

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 两端 record 不同步 | 远程反序列化失败 | 只增字段；Jackson 忽略未知字段；ai-shop 缺字段转空数组并有测试（AC6） |
| 每次读偏好多查一次账户表 | 读延迟 | 单商户账户数为个位到十几；走已有 `merchant_no` 条件 |
| 富邦微信用 UME 记录号代替渠道商户号 | 商户看到的不是真实商户号 | 只显示末 4 位；交接文档已记，补真实号码时运营端更新账户 |
| App 版本参差 | 旧 App 看不到 | AC6：旧 App 忽略字段，功能不退化 |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 |
|---|---|---|
| AC1 | near-pay `PendingChannelQueryTest#disabledCatalogChannelIsPendingWithMaskedMerchantId` | 待实现 |
| AC2 | near-pay `PendingChannelQueryTest#unverifiedAccountOnLiveChannelIsPendingVerification` | 待实现 |
| AC3 | near-pay `PendingChannelQueryTest#activeAccountIsNotPending` | 待实现 |
| AC4 | near-pay `PendingChannelQueryTest#testChannelAndClosedAccountsAreHidden` | 待实现 |
| AC5 | near-pay `CheckoutCodeMethodsTest`（停用渠道账户不进付款方式；已有则补一例） | 待实现 |
| AC6 | ai-shop `StoreMethodPreferenceContractTest#pendingChannelsRelayAndDefaultToEmpty`；App `store_method_api_test.dart` 缺字段 | 待实现 |
| AC7 | ai-shop `BizEndpointPermTest`（回归） | 待实现 |

## §6 对账二 · 设计 → 实现（实现完再填）

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-19 | 数据先行：7 个待开通渠道与 oscar、roger 的 11 个渠道账户已写入 UME166（运营接口，见交接） |
| 2026-11-19 | 草稿 |

## §8 后续（不计入本 TDD 验收）

- 其余 UME 迁移商户的渠道账户批量登记：按 `mig_ume_channel_20261117` 与原始导出（真实渠道商户号，而非计划表里的 UME 记录号），复用 oscar、roger 的做法；前提是各商户已有支付商户（多数主体仍为 PENDING_LICENSE、无运营主体，见 2026-11-19 试跑）。
- UME 内多户共用的渠道商户号（如藍新 `MS354053380`）在批量时按 UME 数据逐户判定。

# TDD-支付服务-W2渠道层补完

状态：已实现（全量回归见 §5 末）
档位：1 · 依据：[完成度复核与剩余开发计划](./支付服务-完成度复核与剩余开发计划.md) §3.2 W2（D-11、D-15、B03–B08 剩余、R30、R31、G4、G5）· [I2 TDD](./TDD-支付服务-I2渠道统一封装.md) 偏差 0–9 · [渠道接入统一封装设计](./支付服务-渠道接入统一封装设计.md) §1、§2.3、§3、§5.2、§6、§11 · [安全规范](./支付服务-安全规范.md) §2.4 G4 / G5
创建：2026-10-26 · 最后更新：2026-10-26 · 分支 `claude/amazing-curie-72e4fb`（自 `codex/pay-i5-access` 切出）

I2 交付的是「旧网关外挂一条管线」：渠道报文仍在 `WechatDirectPayGateway` 等旧类里拼，管线经 `LegacyGatewayHandler` 套在外面，
路由仍是 `PayGatewayRouter` 按渠道名一张表。W2 把这一层补完：按支付类型拆的能力接口、按接入模式拆的处理器、只做协议的
`WechatV3Protocol` / `AlipayProtocol`、唯一的上下文工厂、所有出站 HTTP 必经的记录型客户端、递归脱敏、回调时间窗与证书序列号，
以及管线缺的两环（指标、退款间隔限速）。**CN 现有支付与退款行为不变**（场景测试一行不改且全绿）。I8（TW / AE 新渠道）以它为前置。

## 切片

| 片 | 内容 |
|---|---|
| S1 | `PayInstruction`（shop-base `spi.pay`，sealed）· `ChannelResult` 演进（`Pending` 带指令、`Failed` 可带渠道凭据）· 8 个能力接口 · `CapabilityHandler` · `ChannelCapabilityRegistry` |
| S2 | `PayloadMasker` 递归（嵌套 Map / List / JSON 字符串）+ `TradeSha` / `CheckCode` / `CheckValue` / `ChannelSecret` / `timestamp`（R30） |
| S3 | `channel.http`：`ChannelTransport`（唯一碰 HTTP 客户端）· `RecordingChannelClient`（真实 `http_method` / `url_path`、脱敏副本、`pay_raw_payload`）· 守卫 `RecordingClientOnlyTest` |
| S4 | `channel.context.ChannelContextFactory`（`ChannelAccountContext` 唯一构造者）+ 守卫 |
| S5 | `WechatV3Protocol` · `channel.wechat.direct` · `channel.wechat.ecommerce` · `AlipayProtocol` · `channel.alipay` · `channel.platform`（TEST / STUB / FREE）；退役 `LegacyGatewayHandler` / `LegacyAccessModes` / `PayGatewayRouter` / `AbstractPayGateway` / 三个真渠道网关；守卫 `GatewayStatelessTest` |
| S6 | 微信回调 ±5 分钟时间窗 + 按 `Wechatpay-Serial` 选平台公钥（新旧并存，R31 / G4）；支付宝公钥配置项名（G5） |
| S7 | 管线两环：指标 60（Micrometer）、限速 80（按产品 `refund_interval_seconds` 给退款排队）（I2 偏差 0） |
| S8 | `db/pay` V12 `pay_channel_market_explicit`：`sys_pay_channel_market`「无行 = 不可用」、TEST 显式行、NEWEBPAY / LINEPAY / PAYBY 渠道行（I2 偏差 1、2） |

## §0 对账一 · 需求 → 设计

| AC | 验收（复核 §3.2 / I2 偏差 / 安全规范） | 落点 | 片 |
|---|---|---|---|
| AC1 | 能力接口按支付类型拆；一家渠道 × 接入模式只实现它支持的那几个；注册表由能力派生，不再按渠道名一张表 | `channel.capability.*` · `CapabilityHandler` · `ChannelCapabilityRegistry` | S1 |
| AC2 | 给端上的下单结果是 sealed `PayInstruction`（JSAPI / 跳转 / 表单 / 二维码 / App SDK / 无动作），端上仍拿到与今天逐字相同的参数 | `spi.pay.PayInstruction` · `ChannelResult.Pending` | S1 |
| AC3 | 脱敏递归到嵌套 Map / List / 字符串里的 JSON；蓝新 `TradeSha` / `CheckCode`、LINE Pay `ChannelSecret`、时间戳都遮 | `PayloadMasker` | S2 |
| AC4 | 所有出站渠道 HTTP 经 `RecordingChannelClient`：调用记录写真实 `http_method` / `url_path`、请求 / 响应脱敏副本；原文落 `pay_raw_payload`（密钥模块前存脱敏全文）；别处不得直接用 HTTP 客户端 | `channel.http.*` · `RecordingClientOnlyTest` | S3 |
| AC5 | `ChannelAccountContext` 只由 `ChannelContextFactory` 造；接入模式、精度、收款商户、冻结密钥版本在一处定 | `channel.context.ChannelContextFactory` · `ContextFactoryOnlyTest` | S4 |
| AC6 | 微信（直连 / 收付通）与支付宝按接入模式拆处理器，协议类只做签名验签；`LegacyGatewayHandler` 与 `PayGatewayRouter` 删除；CN 支付与退款场景测试不改且全绿 | `channel.wechat.direct` · `channel.wechat.ecommerce` · `channel.alipay` · `channel.platform` · `LegacyChannelBridge` | S5 |
| AC7 | 处理器与协议类无请求级可变状态（同一实例并发服务多个商户） | `GatewayStatelessTest` | S5 |
| AC8 | 微信回调：时间戳超出 ±5 分钟拒绝；按 `Wechatpay-Serial` 选平台公钥，新旧两把并存；serial 不认识即拒 | `WechatCallbackVerifier` · `WechatPlatformKeys` · `shop.pay.callback.timestamp-window` | S6 |
| AC9 | 支付宝验签读得到 yml 里配的公钥（`public-key-path`），内容 / 路径二选一 | `AlipayCallbackVerifier` · `AlipayChannelConfig` | S6 |
| AC10 | 管线有指标环：按渠道 × 操作 × 结果计数与计时 | `MetricsInterceptor`（60） | S7 |
| AC11 | 同一原单的两次退款间隔不少于产品的 `refund_interval_seconds`：不够就排队等，等不起明确失败且不发渠道 | `RefundIntervalInterceptor`（80） | S7 |
| AC12 | `sys_pay_channel_market` 改为「无行 = 不可用」；TEST 有显式行仍在各市场可用；NEWEBPAY / LINEPAY / PAYBY 渠道行存在且默认停用 | `db/pay/V12` · `PayChannelMasterServiceImpl#enabled` | S8 |

**孤立项**：
- 没落点的 AC：无。
- 挂不上 AC 的设计：`channel.platform.InProcessChannel`（TEST / STUB / FREE 的能力实现）—— 不在复核 §3.2 的清单里，是删除 `LegacyGatewayHandler` 的必要代价（见偏差 1）。

## §1 现状与影响面

- 现有通道层：`PayGateway`（三态）· `AbstractPayGateway` 模板 · `WechatDirectPayGateway` / `WechatPayGateway` / `AlipayPayGateway` · `PayGatewayRouter` · `channel.legacy`（`LegacyGatewayHandler` 把网关挂进管线、`LegacyAccessModes`、`LegacyChannelBridge` 给调用方译回旧类型）· `WechatChannelClient`（JDK HttpClient + 签名 + 主备域名）。
- 直接用 `PayGatewayRouter` 的调用方 6 个：`OrdinaryPaymentInitiationServiceImpl`、`RefundExecutionServiceImpl`、`SettleServiceImpl`（线 B 的文件，只换依赖类型，判断不动）、`PayPointsFundsAdapter`、`PayQueryPortImpl`、`PayChannelMasterPortImpl`；测试里 `OrderPayMethodTest`（场景，判「装配了没有」）与 4 个 pay-domain 单测。
- 场景测试对 TEST 渠道的耦合：40 余个场景类以 `TestPayGateway` 为「测试银行」（`markPaid` / `placeOrder`、`@MockitoSpyBean` 验 `prepay` / `refund(TxContext…)` / `queryRefund` 次数、`doReturn(PayGateway.Result…)`）。**这层形状不能动**，否则「CN 场景测试一行不改」做不到。
- 可复用：`WechatApiV3Signer`（签名 / 应答验签 / paySign）原样留用，`WechatV3Protocol` 包它；回调模板 `CallbackTemplate`、`ChannelCallbackVerifier` SPI 不变。
- 明确不受影响：端点、权限码、i18n、回调控制器位置（I5 正在把 `ChannelPayCallbackController` 迁入 `pay-portal`，本包只改验签类）、`stl_channel_message` 写入（开关 `shop.pay.log.recording` 照旧，写点挪到记录型客户端）。
- 迁移号：`db/pay` 现有 V1–V11，V11 = I5 `pay_open_access`（已在 `codex/pay-i5-access`），V12 空，本包占用。

## §2 方案

### 契约变更
- 库表：`db/pay/V12__pay_channel_market_explicit.sql` —— 语义切换（无数据结构变化之外：只改注释与数据）；TEST 补每个市场一行；`sys_pay_channel` 加 NEWEBPAY / LINEPAY / PAYBY 三行（`enabled=0`）。
- 新写入：`pay_raw_payload`（V5 已建、此前无人写）；`pay_channel_call_log.http_method / url_path / request_masked / response_masked / request_raw_id / response_raw_id / http_status`（此前恒为占位 `-` 或空）。
- 配置项（全部无密钥默认值，`NoSecretDefaultInConfigTest` 绿）：
  - `shop.pay.callback.timestamp-window`（默认 `PT5M`，G4；已写进 `application.yml`）
  - `shop.pay.wechat.platform-public-key-serial`（当前平台公钥 / 证书序列号；空 = 不按 serial 选、只有一把）、`shop.pay.wechat.platform-public-key-next` / `-next-path` / `-next-serial`（轮换期的新一把；已写进 yml）
  - `shop.pay.alipay.public-key`（内容）与已有 `shop.pay.alipay.public-key-path`（路径）二选一，内容优先（G5）；`shop.pay.alipay.app-id` / `private-key` / `private-key-path` / `read-timeout-seconds` 由 `AlipayChannelConfig` 读
  - `shop.pay.channel.refund-queue-max-wait`（默认 `65s`：等得起微信 60 秒间隔）
  - `shop.pay.channel.connect-timeout`（默认 `5s`，传输层统一；原 `shop.pay.wechat.connect-timeout-seconds` 不再读，见偏差 12）
  - `shop.pay.raw-payload.retention-days`（默认 `1825`：`pay_raw_payload.retain_until`，合规期限待 Q4）
- 端点、权限码、i18n：无。

### 模块设计

| 动作 | 路径（`backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel` 下，另注明除外） | 说明 |
|---|---|---|
| 新增 | `shop-base/.../spi/pay/PayInstruction.java` | sealed：`JsapiParams` · `AppSdkParams` · `RedirectUrl` · `FormPost` · `QrContent` · `NoAction`；`asParams()` 给今天的端上透传形状 |
| 修改 | `api/ChannelResult.java` | `Pending(PayInstruction, channelTradeNo, channelStatus)`；`Failed` 加可选 `channelTradeNo` / `channelAmount`（退款关闭的凭据）；旧参数个数的构造保留 |
| 修改 | `api/ChannelOperation.java` · `api/PayErrorCode.java` · `fault/FaultPolicyRegistry.java` | 加 `SPLIT`；加 `REFUND_CLOSED` / `REFUND_ABNORMAL` / `CHANNEL_THROTTLED` 已有则复用 |
| 新增 | `api/command/{VoidCommand,SplitCommand,OnboardingSubmitCommand,OnboardingQueryCommand,StatementFetchCommand,PayoutCommand}.java` | 能力接口要的命令；后四个今天无实现（I6 / I8 / W4） |
| 新增 | `capability/{ChannelCapability,OnlinePayCapability,OfflinePayCapability,CardPayCapability,TransactionCapability,OnboardingCapability,StatementCapability,PayoutCapability,SplitCapability}.java` | 方法名互不重叠（`payOnline` / `payOffline` / `payByCard` …），一个类可实现多个 |
| 新增 | `capability/CapabilityHandler.java` · `capability/ChannelCapabilityRegistry.java` | 能力 → `ChannelHandler`（`supports()` 由实现了哪些接口派生）；注册表回答「这个渠道装了没有、装的是哪种接入模式、要某个能力」，替代 `PayGatewayRouter` |
| 修改 | `PayloadMasker.java` | 递归；JSON 字符串先解析再遮；键表加蓝新 / LINE Pay / 时间戳 |
| 新增 | `http/{ChannelHttpRequest,ChannelHttpResponse,ChannelTransport,JdkChannelTransport,RecordingChannelClient,RawPayloadStore,MybatisRawPayloadStore,RawPayloadSealer,MaskedPlaintextSealer}.java` | 传输与原文记录；`RawPayloadSealer` 是给 W5 换成加密实现的缝 |
| 新增 | `entity/PayRawPayload.java` · `mapper/ChannelMappers` 加 `PayRawPayloadMapper` | V5 已有表 |
| 修改 | `invoke/Invocation.java` | `connectFailed()`：连接没建成时撤回「已发出」 |
| 新增 | `context/{ChannelContextFactory,ChannelRoute}.java` | `from(ChannelRoute)`；接入模式缺省取本部署装的那一种；精度先取产品地区行再取币种默认 |
| 修改 | `handler/ChannelProductCatalog.java` · `catalog/DbChannelProductCatalog.java` | 加 `amountScale(...)`、`refundIntervalSeconds(...)` |
| 新增 | `wechat/{WechatV3Protocol,WechatV3Client}.java` · `wechat/direct/WechatDirectChannel.java` · `wechat/ecommerce/WechatEcommerceChannel.java` | 报文与结果映射自原网关逐字搬来；主备域名切换在 `WechatV3Client` |
| 新增 | `alipay/{AlipayProtocol,AlipayClient,AlipayChannel}.java` | RSA2 签名 / 应答验签；查单、退款、查退款、分账、补差（自 `AlipayPayGateway`）；App 支付下单串（本地签名，不发 HTTP） |
| 新增 | `platform/InProcessChannel.java` | TEST / STUB / FREE：进程内模拟银行（`TestPayGateway` 等），无协议无 HTTP |
| 修改 | `legacy/LegacyChannelBridge.java` | 不再依赖路由表；`prepay` / `refund` / `query` 走管线，`queryRefund` / `queryRefundOutcome` / 零金额直达处理器（与今天同口径）；结果译回旧类型 |
| 修改 | `WechatChannelClient.java` · `WechatPayChannelConfig.java` · `AlipayChannelConfig.java` | 客户端改为 `WechatV3Client` 之上的旧 `ChannelClient` 外形（进件仍用）；装配新处理器 |
| 删除 | `PayGatewayRouter.java` · `base/AbstractPayGateway.java` · `WechatDirectPayGateway.java` · `WechatPayGateway.java` · `AlipayPayGateway.java` · `legacy/LegacyGatewayHandler.java` · `legacy/LegacyAccessModes.java` | |
| 修改 | `port/PayQueryPortImpl.java` · `port/PayChannelMasterPortImpl.java` · `config/ChannelPipelineConfiguration.java` | 改用注册表 / 桥 |
| 修改 | `pay-domain`：`OrdinaryPaymentInitiationServiceImpl` · `RefundExecutionServiceImpl` · `impl/SettleServiceImpl` · `points/port/PayPointsFundsAdapter` | 只把 `PayGatewayRouter` 换成 `ChannelOperations`；分支判断一行不动 |
| 修改 | `verify/WechatCallbackVerifier.java` · 新增 `verify/WechatPlatformKeys.java` · `verify/AlipayCallbackVerifier.java` | 时间窗、serial、新旧并存；G5 |
| 新增 | `invoke/interceptor/{MetricsInterceptor,RefundIntervalInterceptor}.java` | 60 / 80 |
| 新增 | `pay-domain/src/main/resources/db/pay/V12__pay_channel_market_explicit.sql` | |
| 修改 | `master/impl/PayChannelMasterServiceImpl.java` | 「无行 = 不可用」 |
| 新增 | `shop-app` test `arch/RecordingClientOnlyTest` · `arch/GatewayStatelessTest` · `arch/ContextFactoryOnlyTest` | 守卫 |

### 关键接口

```java
// shop-base spi.pay
public sealed interface PayInstruction {
    record JsapiParams(Map<String, String> params) …   record AppSdkParams(Map<String, String> params) …
    record RedirectUrl(String url) …                   record FormPost(String action, Map<String, String> fields) …
    record QrContent(String content) …                 record NoAction() …
    Map<String, String> asParams();   // 端上今天拿到的扁平参数，逐字不变
}

// channel.capability（一家渠道 × 接入模式实现它支持的那几个）
interface ChannelCapability { String payChannel(); AccessMode accessMode(); }
interface OnlinePayCapability  extends ChannelCapability { Set<PayScene> onlineScenes(); ChannelResult payOnline(Invocation, PayCommand); }
interface TransactionCapability extends ChannelCapability { query / close / refund / queryRefund; Set<ChannelOperation> transactionOperations(); }
interface SplitCapability       extends ChannelCapability { ChannelResult split(Invocation, SplitCommand); }
// Offline / Card / Onboarding / Statement / Payout 同形，今天无实现

// channel.http
public final class RecordingChannelClient {
    ChannelHttpResponse send(Invocation inv /* 管线外为 null */, String payChannel, String api, ChannelHttpRequest request);
}

// channel.context
public final class ChannelContextFactory { ChannelAccountContext from(ChannelRoute route); }
```

## §5 对账三 · 实现 → 需求（测试）

先写测试（新类型不存在时以编译失败为红），再实现，再消融：每片把实现改回去 / 注掉，对应测试必须变红，还原后复跑全绿。

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `CapabilityHandlerTest`（5 条：`patternsDeriveFromCapabilities` · `dispatchByCommandType` · `registryAnswersWiringQuestions` · `capabilityWithoutAnyOperationFails` · `instructionFlattensToTodaysParams`） | ✅ 5/5 | `derive()` 去掉交易操作 + 注册表装了两种模式也任挑一个 + `FormPost` 不带 `formAction` → 3 红（`patternsDeriveFromCapabilities:135` · `registryAnswersWiringQuestions:179` · `instructionFlattensToTodaysParams:218`）✅ |
| AC2 | 同上 `instructionFlattensToTodaysParams`；`WechatDirectChannelTest#端上六件套`（六件套键、`prepay_id=` 前缀、交易号为空）；`InProcessChannelTest#prepayIsVerbatim`（测试银行给的参数逐字到端上） | ✅ | 见 AC1 / AC6 |
| AC3 | `PayloadMaskerTest`（新增 5 条：`nestedMapIsMasked` · `listOfMapsIsMasked` · `jsonStringValueIsMasked` · `newChannelKeysAreSensitive` · `textBodies`；原 5 条不变） | ✅ 10/10 | 不递归 + 去掉新键 → 5 红（`:95` `:109` `:120` `:127` `:144`）✅ |
| AC4 | `RecordingChannelClientTest`（4 条：`recordsRealCoordinatesAndMaskedPayloads` · `queryStringIsMaskedInPath` · `connectFailureIsNotSentButTimeoutIs` · `recordingFailureDoesNotBlockAndWorksOutsidePipeline`）；`WechatDirectChannelTest#callLogCarriesRealHttpCoordinates`；守卫 `RecordingClientOnlyTest`（2 条，含判据自检） | ✅ | 方法写占位 + 不遮路径 + 不撤回「已发出」+ 记录失败上抛 → 4/4 红；`WechatV3Client` 加一个 JDK `HttpClient` 字段 → 守卫红（`…calls method <java.net.http.HttpClient.newHttpClient()> in (WechatV3Client.java:21)`）✅ |
| AC5 | `ChannelContextFactoryTest`（3 条：`accessModeResolution` · `amountScale` · `merchantIdentityPassesThrough`）；守卫 `ContextFactoryOnlyTest`（2 条，含判据自检） | ✅ | 两种模式也任挑 + 不读产品精度 → 2 红（`:85` `:95`）；补查改回自己 `new ChannelAccountContext` → 守卫红（`ChannelFollowUpRunner.context … calls constructor … (ChannelFollowUpRunner.java:107)`）✅ |
| AC6 | `WechatDirectChannelTest`（21 条：`WechatDirectPayGatewayTest` 与 `WechatRefundReceiptFlowTest` 的用例逐条搬来、断言不变，后者并进一条五态用例；另加调用记录、主备切换、应答验签 3 条；入口换成「桥 → 管线 → 处理器 → 协议 → 记录型客户端」）· `WechatEcommerceChannelTest`（7 条，含原 `WechatPayGatewayQueryTest` 3 条）· `AlipayChannelTest`（4 条）· `InProcessChannelTest`（6 条）；CN 支付 / 退款场景测试<b>一行未改</b>（见末尾全量回归） | ✅ 38/38 | 一次 7 处 → 7 红各中其的：不认 `ORDER_NOT_EXIST` → `订单不存在可关单:221`；去掉 openid 前置 → `没有openid不发通道:93`；进程内退款非明确失败当拒绝 → `InProcessChannelTest.refund:107`；桥把所有不明当 UNPROVEN → `acceptedResponseWithoutProvider…:293`；不切备域名 → `failsOverToBackupHost:164`；支付宝不验应答签名 → `queryOutcomes:108`；收付通去掉能力位 → `subsidyDeniedByCapabilityBit:155` ✅ |
| AC7 | 守卫 `GatewayStatelessTest`（2 条，含判据自检；扫描面断言列出 8 个必须扫到的类） | ✅ | `WechatDirectChannel` 加一个非 final 字段 → 红（`WechatDirectChannel.lastOutTradeNo : java.lang.String`）✅ |
| AC8 | `CallbackFreshnessAndRotationTest`（`timestampWindow` · `serialSelectsKeyAndOldNewCoexist` · `singleUnnamedKeyKeepsWorking` · `unnamedPrimaryIsFallbackDuringRotation`）；`CallbackVerifierTest` 原 11 条（三条有效回调的固定 2019 年时间戳改为当前时间，其余不变） | ✅ | 去掉时间窗 + 选钥不看 serial → 3 红（`timestampWindow:54`「三个月前的真回调重放进来也该拒」· `:65` · `:87`）✅ |
| AC9 | `CallbackFreshnessAndRotationTest#alipayReadsPublicKeyPath` | ✅ | 支付宝不读 `-path` → 红（`:104`「只配了路径（yml 的真实形状）」）✅ |
| AC10 | `PipelineRingsTest#standardOrder`（生产管线 13 环 10…130）· `#metricsRing` | ✅ | 生产管线去掉指标环 → 2 红（`standardOrder:56` · `metricsRing:69 MeterNotFound`）✅ |
| AC11 | `PipelineRingsTest#refundIntervalQueues` · `#noIntervalNoQueue` | ✅ | 间隔环恒放行 → 红（`refundIntervalQueues:88`）✅ |
| AC12 | `PayChannelDefaultResolveTest#noMarketRowsMeansUnavailable` · `PayChannelMarketRelationTest`（`noRowsMeansUnavailableAndTestIsExplicit` · `newChannelsSeededDisabled` · `emptyMarketUpdateIsRejected` · `opsContractShapeUnchanged` · `testSeedCarriesBackfillResult` 加 V12 种子检查）；V12 在本机 MySQL 9.7.4 的独立库上执行两遍（第二遍幂等），另一遍带一个无行的通道验证第 2 段搬运 | ✅ | 读法改回「无行 = 不限」+ 不拒空市场 + 无行派生回 null → 4 红（`:188` · `:161` · `:183` · `:223`）✅ |

**MySQL 实跑 V12**（克隆 `ai_shop` 的 `sys_market` / `sys_pay_channel` / `sys_pay_channel_market` 到临时库，跑完即删）：
TEST → `AE,CN,HK,SA,SG,TW`；WECHAT / ALIPAY → `CN` 不变；NEWEBPAY / LINEPAY → `TW`、PAYBY → `AE`，三条渠道行 `enabled=0`；
再跑一遍无报错、无新增行；预置一个没有市场行的通道 `ZZ` → 搬成全部六个市场。

**pay-channel 全部**：134 / 134（I2 交付时 84；删掉 4 个旧网关测试类、搬入 7 个新类）。**pay-svc**：80 / 80。**pay-domain 四个改了依赖的单测**：25 / 25。

**全量回归**（`mvn -o -pl shop-app -am test`）：基线（`796e1e3b0`，本包之前）全绿、0 失败（shop-app 段 36 分钟）；本包的结果在下一行回填。

- 回归记录：（待回填）

## §6 对账二 · 设计 → 实现

八个提交（`git log 796e1e3b0..HEAD`）：`6b7e34636` S2 + 本 TDD 计划 · `9245d1861` S1 · `6e88f28de` S3 · `e5db201fd` S4 · `096983f3f` S5 · `bb174bb4b` S6 · `c96973d4b` S7 · `9773f496b` S8。
`git diff --stat 796e1e3b0..HEAD`（不含生成的 `schema-test.sql`）：**118 个文件，+6694 / −2719**。按 §2 逐行：

| 改动位置（文件数） | §2 | 对得上 |
|---|---|---|
| `shop-base` `spi/pay/PayInstruction` · `api/ChannelResult` · `api/ChannelOperation` · `api/PayErrorCode` · `api/command`（6 新 + `ChannelCommand`）· `fault/FaultPolicyRegistry` · `capability`（11） | S1 | ✅ |
| `PayloadMasker` | S2 | ✅ |
| `http`（9）· `entity/PayRawPayload` · `mapper/ChannelMappers` · `invoke/Invocation`（`connectFailed`）· `config/ChannelPipelineConfiguration` | S3 | ✅ |
| `context`（2）· `api/ChannelAccountContext`（`keyVersion`）· `handler/ChannelProductCatalog` · `catalog/DbChannelProductCatalog` · `followup/ChannelFollowUpRunner` · `shop-app` `paybridge/ChannelFollowUpConfiguration` | S4 | ✅ |
| `wechat`（4）· `wechat/direct` · `wechat/ecommerce` · `alipay`（3）· `platform/InProcessChannel` · `legacy/LegacyChannelBridge` · `WechatChannelClient` · `WechatPayChannelConfig` · `AlipayChannelConfig` · `port`（2）· `log/JsonPayLogger`；删 `PayGatewayRouter` · `base/AbstractPayGateway` · `WechatDirectPayGateway` · `WechatPayGateway` · `AlipayPayGateway` · `WechatDirectApis` · `legacy/LegacyGatewayHandler` · `legacy/LegacyAccessModes`；`pay-domain` 四个调用方 | S5 | ✅（`WechatDirectApis` 未列在 §2 删除表里：它只给被删的直连网关与旧客户端用，路径常量已搬进 `WechatDirectChannel` / `WechatV3Protocol`） |
| `verify`（2 改 + `WechatPlatformKeys`）· `shop-app` `application.yml` | S6 | ✅ |
| `invoke/interceptor`（2）· `invoke/StandardPipeline` · `pay-channel/pom.xml`（micrometer-core，版本归 BOM） | S7 | ✅（`StandardPipeline` 是 §2 之外新加的一个类，理由见偏差 10） |
| `db/pay/V12` · `schema-test.sql`（生成）· `entity/SysPayChannelMarket`（`status`）· `master`（2）· `shop-app` `OpsPayChannelAppServiceImpl` | S8 | ✅（`OpsPayChannelAppServiceImpl` 未列在 §2：语义翻转后运营端派生值要跟着改，见偏差 11） |
| 测试：pay-channel 新 9 类（`capability` · `context` · `http` · `wechat`×3 · `alipay` · `platform` · `verify/CallbackFreshnessAndRotationTest` · `invoke/PipelineRingsTest`）、改 7 类、删 4 类；pay-domain 改 4 类；shop-app 新守卫 3 类、改场景 3 类（`OrderPayMethodTest` 2 行 · `PayChannelDefaultResolveTest` · `PayChannelMarketRelationTest`） | §5 | ✅ |

§2 列了而没动的：无。

### 偏差说明

0. **TEST / STUB / FREE 仍是 `PayGateway` 形状的进程内模拟银行**，由 `channel.platform.InProcessChannel` 接到能力接口上。40 余个 CN 场景测试以 `TestPayGateway` 为「测试银行」，`@MockitoSpyBean` 后按 `prepay(PrepayCommand)` / `refund(TxContext…)` / `queryRefund` 的次数与参数断言 —— 换形状就做不到「场景测试一行不改」。真渠道（微信两种、支付宝）一律走处理器 + 协议 + 记录型客户端；`PayGateway` 接口的语义收窄为「进程内模拟银行」，再没有真渠道实现它。
1. **`LegacyChannelBridge` 保留**（仍在 `channel.legacy`）：它是「四态 → 旧结果类型」的译码层，调用方的判断留给 I3 改。不再依赖路由表；I2 时就绕开管线的两类调用（查退款三态 / 五态、零金额）仍直达处理器（I2 偏差 3 继续）。**新进管线的一处**：`SettleServiceImpl#sendRefundToChannel` 此前直调网关，现经桥进管线 —— 多了调用记录、校验、退款间隔排队；渠道答了说不清时，落 `err_msg` 的从网关原话变成 `REFUND_SEND_UNPROVEN: <原话>`。判断一行没动（线 B 的文件，只换了依赖类型）。
2. **下单「不明」不再抛 500**：I2 的桥遇到管线级不明（如 15 秒超时）抛 `IllegalStateException`，调用方没接住；现在译成非明确的失败结果（`PrepayResult.unknown`），调用方照旧回「下单失败」、原收款意图留待查单，补查已由管线登记。微信下单的传输问题此前被旧适配器一律折成「渠道拒绝」，现在按设计 §8.2 分类：连不上 = 没发出去（重发一次）、发出后超时 = 不明（补查）—— 用户看到的仍是「下单失败」，多出的是一条补查。
3. **收付通下单仍未实现**：与旧网关同样明确失败、不发渠道（合单 JSAPI 待接）。声明了线上收款能力，是因为产品 `WECHAT_JSAPI_SP` 在库里开着，不声明的话启动校验会拦下收付通部署。
4. **支付宝此前从未接通**（`alipayChannelClient` 全仓没实现），没有存量行为可保：现在凭据齐才装处理器、不齐照旧 `AlipayNotReady`（`AlipaySwitchBootTest` 不改且绿）。查单遇到 `TRADE_NOT_EXIST` 以外的业务错 → 不明（旧代码若接通会判成「未付」）。App 支付下单串、查退款、进件（`AlipayApplymentGateway`）未接；R31 的另一半（支付宝回调校验 `app_id` / `seller_id`）未做 —— 均列为后续。
5. **凭据仍在 yml**：`ChannelContextFactory` 只把冻结的密钥版本带进上下文，「经 `secret-api` 开凭据」等 W5；按渠道名造上下文时，同一渠道装了两种接入模式即拒绝（存量调用方还不带收款商户路由，每个部署仍只装一种）。
6. **原文存脱敏全文**：`pay_raw_payload.cipher_text` 在 W5 前是脱敏全文，`data_key_cipher = NONE:MASKED_PLAINTEXT`、`sec_master_key_id = 0`，`sha256` 仍按明文算。加密实现由 W5 替换 `RawPayloadSealer` bean 接入。`stl_channel_message` 的「发送」行改由传输层写：成败按 HTTP 2xx 判（旧网关按业务回执判），查单也会记一行；该表按 O6 计划在 W2 后切 `shop.pay.log.recording=new`。
7. **时间窗只管回调**：先验签、后看时间（伪造请求刷不了「过期」计数）；微信<b>出站应答</b>验签仍是单把平台公钥（`WechatApiV3Signer`），轮换期新旧并存只覆盖了回调 —— 后续。
8. **退款间隔排队在进程内**：多实例各排各的；等不起（前面已排一笔）→ `CHANNEL_THROTTLED`，桥按 I2 偏差 5 译为明确拒绝（没发出去，售后重新发起）。
9. **独立 pay-svc 没有 actuator**：指标落进程内 `SimpleMeterRegistry`，不挡启动。
10. **加了 `invoke/StandardPipeline`**（§2 未列）：I2 时生产管线在装配类里手写一串、测试另写一串，生产顺序没有任何测试看着；收成一处后 `PipelineRingsTest#standardOrder` 钉的是生产那一串。
11. **V12 的连带改动**：① `OpsPayChannelAppServiceImpl` 无行派生 `[]` 不再是 `null`（`null` 在运营页显示成「全部市场」，语义翻转后会说反）；② `updateSettings` 收到空市场直接拒（`BAD_REQUEST`）—— 运营页（`ops-web/app/finance/pay-channel-tab.tsx` 与 `copy.ts` 的 `pcMarketsPrompt` / `pcAllMarkets`）仍写着「留空 = 所有市场」，前端改文案与提示是后续（不在本线）；③ `SysPayChannelMarket` 补映射 V4 的 `status` 列，显式 `DISABLED` 的行不算可用。两个专测市场语义的场景类（`PayChannelDefaultResolveTest` / `PayChannelMarketRelationTest`）按新语义改了断言与还原方式（快照还原，不再「一律启用」—— 那会把三条默认停用的新渠道在测试库里开出来）；`OrderPayMethodTest` 只把 `PayGatewayRouter` 换成 `ChannelCapabilityRegistry`（2 行）。CN 支付与退款场景测试未改。
12. **连接超时统一到传输层**：`shop.pay.channel.connect-timeout`（默认 5s）对所有渠道生效；`shop.pay.wechat.connect-timeout-seconds` 不再读（默认值相同）。读超时仍按渠道（`shop.pay.wechat.read-timeout-seconds`）。
13. **`ChannelResult` 比设计 §2.3 多两个可选字段**：`Failed` 可带渠道单号与金额（退款被渠道关闭是终态，凭据要给额度释放核对）、`Pending` 可带渠道原始状态（五态回执的 `rawStatus` 与微信 paySign 自验结果；处理器不打日志，由管线日志带出）。旧参数个数的构造保留。
14. **命名与类清单的差异**：类清单 §5 的 `ChannelRouter` 即本篇 `ChannelCapabilityRegistry`（`<T> find(payChannel, Class<T>)` 同义）；`ChannelContextFactory.from(PaymentRoute)` 落成 `from(ChannelRoute)` —— `PaymentRoute` 是商户侧 SPI 记录，接入模式与密钥版本不在里面。
15. **删掉的旧测试类**：`WechatDirectPayGatewayTest`、`WechatPayGatewayQueryTest`、`WechatRefundReceiptFlowTest` 的用例逐条搬进 `WechatDirectChannelTest` / `WechatEcommerceChannelTest`（断言不变，入口换成桥）；`GatewayRecordsMessagesTest`（旧模板方法的报文留痕）由 `RecordingChannelClientTest` 取代 —— 留痕从网关下沉到了传输层。

### 后续（不在本包）

- 运营页市场设置的提示语与「全部市场」显示（偏差 11 ②）。
- 微信出站应答验签的证书轮换（偏差 7）；支付宝回调校验 `app_id` / `seller_id`（R31 后半）。
- 支付宝 App 下单串、查退款、进件迁到能力接口（偏差 4）；收付通合单下单（偏差 3）。
- W5 就绪后：`RawPayloadSealer` 换加密实现、`ChannelContextFactory` 经 `secret-api` 开凭据（偏差 5、6）。

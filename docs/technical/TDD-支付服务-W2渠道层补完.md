# TDD-支付服务-W2渠道层补完

状态：草稿
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
- 配置项：
  - `shop.pay.callback.timestamp-window`（默认 `PT5M`，G4）
  - `shop.pay.wechat.platform-public-key-serial`（当前平台公钥 / 证书序列号；空 = 不按 serial 选、只有一把）、`shop.pay.wechat.platform-public-key-next` / `-next-path` / `-next-serial`（轮换期的新一把）
  - `shop.pay.alipay.public-key`（内容）与已有 `shop.pay.alipay.public-key-path`（路径）二选一，内容优先（G5）；`shop.pay.alipay.app-id` / `private-key` / `private-key-path` 由 `AlipayChannelConfig` 读
  - `shop.pay.channel.refund-queue-max-wait`（默认 `65s`：等得起微信 60 秒间隔）
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

（实现后回填）

## §6 对账二 · 设计 → 实现

（实现后回填）

### 偏差说明

（实现后回填）

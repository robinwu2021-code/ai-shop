# 台湾线下购买阶段 API 交接

2026-11-04 工作区快照。用户要求跳过验证直接提交；本文件说明当前源码契约，不代表 HTTP 接口已开放、已冻结或可完整对接。已有可对接的商城基础接口见 [商城 HTTP](commerce-mall-http.md) 和 [OpenAPI](commerce-mall-openapi.json)；注册到进件见 [标准 API](B端注册到进件-标准API文档.md) 与 [OpenAPI](openapi-b-onboarding.json)。

## 当前公开 Java 契约

源码：`backend/commerce/commerce-api/src/main/java/ai/neargo/commerce/api/offline/OfflinePurchaseFacade.java`。

| 方法 | 入参 | 用途及边界 |
| --- | --- | --- |
| quote | actor、QuoteCommand | 线下报价。收款条款只从可信 pay 域档案（`commerce.mall.offline.profile-no`）读取，不接受调用方传入；ACCOUNT 为本人购买，OPERATOR 为代客 STORE 购买 |
| reserveEvidence | actor、OrderQuery、purpose | 预留订单私有凭证；预留不等于上传完成 |
| submit | actor、ClaimCommand | 商家申报付款；不是实际到账，不发权益 |
| decline | actor、DeclineCommand | 运营拒绝申报，不代表无款或关单 |
| confirm | actor、ReviewCommand | 以运营实际到账证明创建核款处理意图 |
| voidOrder | actor、VoidCommand | 运营对账作废，保留已知异常款处理依据 |
| recover | FulfillmentQuery | 可信内部恢复已保存授权意图；不得暴露成无鉴权 HTTP |
| evidence | actor、OrderQuery、assetKey | 授权读取私有凭证；实际媒体接入未完成 |
| retainedEvidenceKeys | tenantNo | 可信内部媒体引用读取；GC接入与有界性待完成 |

`ClaimCommand`：order、claimNo、declaredAmount、declaredPaidAt、bankReference、evidenceKeys。声明金额与时间仅为商家申报。

`ReviewCommand`：order、actionNo、claimNo、actualAmount、actualPaidAt、actualReceiver、bankReference、evidenceKey、historical、reason。必须经过真实运营身份、能力及可信收款账户校验；historical仅供明确人工补录，不放宽线上证明。

`VoidCommand`：order、actionNo、reconciliationReference、reason、evidenceKey、unmatchedDispositions。拒绝申报不能替代此操作。

返回：ClaimView 包含申报状态/金额/时间；ActionView 包含 actionNo/status/paymentNo。不能从动作已接受推导资金到账或权益已交付，应回读订单的独立支付、交付、退款状态。

## 宿主资金端口

`OfflineCollectionPort` 定义 terms、confirm、reconcile；默认缺失能力不可用。ManualPayment保留原支付意图、真实运营、实际金额/时间/收款主体/流水/证据及补录标记。PlatformCollectionService与现有stl_payment承接实际资金，不新增商业现金账本。

异常实收使用 `MALL_UNMATCHED`，原应付意图不改写；不触发正常权益交付。正常款和异常款共享可信银行账户与流水唯一性。人工退款实际出款确认尚未完成。

## 金额与兼容

金额使用 currency + 整数minor；HTTP基础合同minor为字符串。TWD69900表示699元，新金额策略现金/分摊量子100；估值与余额仍保留minor1，余数留余额。历史策略缺失按旧量子1回放。报价15分钟与订单线下paymentDueAt独立。

新增源码涉及 MoneyPolicy、MoneyPolicyPort、OfflineCollectionTerms、门店STORE归属和付款期限。它们尚未全部接入starter/HTTP/配置/恢复任务；不要根据Java方法名猜URL或请求JSON。完成接线后须同步正式OpenAPI、错误码、真实鉴权及实际MVC响应校验，再交B端联调。

## 2026-11-04 续接进展

已接线（源码与测试，仍无 HTTP）：

- starter `CommerceOfflineAutoConfiguration`：`commerce.mall.offline.enabled=true` 且支付协调已开启时装配 `OfflinePurchaseFacade`。缺 `OfflineCollectionPort`、`PrivateEvidencePort` 或 `commerce.mall.offline.profile-no` 时启动失败，不装假 facade。
- 宿主 `ShopMallOfflineCollectionAdapter`：把 terms/confirm/reconcile 映射到 `PlatformCollectionService`，MATCHED/LATE/UNMATCHED 只取账本结果。
- 平台付款适配器支持 `OFFLINE:BANK_TRANSFER`：不需要 payer 绑定，冻结条款的收款主体与币种必须与意图一致，否则返回 `OFFLINE_INTENT_INVALID`、不触达账本。
- `evaluateOffline` 可用性：需要显式路由 `OFFLINE:BANK_TRANSFER`、条款收款主体/币种匹配、正金额（零元为 `OFFLINE_NOT_REQUIRED`）。

审查修复：

- 已付款意图上再确认一笔真实到账，记为 `MALL_UNMATCHED`，不再抛错丢失资金事实。
- 账本明确拒绝（IllegalArgumentException）的核款/作废动作落 `REJECTED` 终态并返回原因码；未知/传输失败仍保持 `ACCEPTED` 待恢复。`recover` 逐条处理，一条失败不再阻塞同单其余动作。

审查遗留（未修）：非 ACTIVE 门店的运营核款/作废被 `locked()` 拒绝；空 evidenceKey/dispositions 抛 NPE 而非 INVALID_REQUEST；申报与核款结果未关联；STORE 订单幂等键按门店共享；对账把 MALL_UNMATCHED 计为失败；下单后改档案会使未发出的指引无法发出；宿主 actor 适配仍拒绝 OPERATOR，`commerce:mall:offline:*` 权限尚无宿主支撑。

## 接手必须完成

- 私有媒体适配、实际ACTIVE校验、预览/清理隔离和GC。
- 人工退款出款证明及异常实收退回。
- facade/starter/宿主端口/HTTP/OpenAPI/配置和恢复任务统一接线。
- 当前快照构建、迁移与schema一致性、单测/原生MySQL、权限/幂等/并发/恢复与完整端到端验证。
- 运营页面、B端仓库/页面和稳定API联调。三个台湾线上通道尚未真实接通。

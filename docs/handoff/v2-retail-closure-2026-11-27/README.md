# v2 零售闭环：注册登录 → 开店 → 商品 → 下单/直接收款 → 支付 → 订单查看

目标（用户 2026-11-27）：把 ai-shop 部署到线上，完成基本的下单与支付流程，从注册/登录到开店、下单/直接支付、订单查看整个闭环修复并验证；包含零售的商品、规格、订单等功能。支付服务（near-pay）已部署。

## 0 线上现状（2026-11-27 核对）

| 项 | 现状 |
|---|---|
| ai-shop v2 | 192.168.3.80:18180，单进程三端（profile `api,ops`），包 `shop-app-pos-page-actions-e1001ac9e.jar`（v2 线上的提交，早于零售域对齐 `bb7962138`） |
| near-pay | 同机 18093；v2 配置 `SHOP_PAY_DEPLOYMENT=standalone`，经 Nacos 服务名 `near-pay` 调用 |
| 公网入口 | 运营端 `https://tw.neargo.ai/ops-shop-v2/`（200）；B 端 `https://nearshop.umeshop.ai/shop-api-v2/biz/**`（未登录 401）；C 端 `https://nearshop.umeshop.ai/shop-api-v2/mp/**` |
| 商户 App | 正式入口 `main_prod.dart` → `StandardOnboardingApp`（`~/work/ai-shop-bapp`）；`lib/app.dart` 新壳只在开发与 mock 入口 |
| C 端 | `c-app`（uni-app），接口表 `c-app/src/api/endpoints.ts` |

## 1 B 端（商户 App）闭环节点

| # | 节点 | App 页面 | 接口 | 后端 | 依赖服务 | 已知缺口 | 检查 |
|---|---|---|---|---|---|---|---|
| B1 | 手机号注册/登录 | `standard_onboarding/onboarding_forms.dart` | POST `/biz/auth/otp/send`、`/biz/auth/login`、`/biz/auth/captcha/*` | `BizAuthController` → AuthService、OtpSendService | 短信（阿里，`shop.sms.stub`） | 短信生产未验收；`/biz/auth/context` 无处理器 | 待查 |
| B2 | 创建经营主体与证照 | `merchant/entity_creation_page.dart`、`onboarding_certificates.dart` | POST `/biz/entities`、`/biz/merchant/apply`、`/biz/qualifications/recognize\|save`、`/biz/upload/image` | BizEntityController、BizMerchantController、BizCertController、BizUploadController | 对象存储、OCR | FREE 生效不代表资质获批 | 待查 |
| B3 | 开店与门店审核 | `onboarding_store_creation.dart`、`onboarding_review_progress.dart` | POST `/biz/merchant/quick-start`、`/biz/merchant/store-openings`、GET `/biz/stores/mine` | BizMerchantController → StoreOpeningService | 运营审核、`commerce.store-creation.enabled` | 未签协议也能通过（待定） | 待查 |
| B4 | 支付进件、开通收款 | `payment_onboarding/.../pay_intake_page.dart` 等 | `/biz/pay/v2/{onboardings,memberships,merchant-creations,capability}` | BizPayOnboardingController → BizOnboardingForwarder | near-pay | 蓝新只到平台审核；挂商户号需 `shop.pay.merchant.dual-write` | 待查 |
| B5 | 门店付款方式 | `payment_operations/.../store_payment_methods_page.dart` | GET/PUT `/biz/payment/store-method(-preference)` | BizPaymentController → PayBizInternalApi | near-pay | tw 环境 preference 曾 404 | 待查 |
| B6 | 类目 | StoreCategoriesPage | GET `/biz/category/tree`、`/biz/store/{no}/categories` | BizGoodsController、StoreCategoryService | — | 平台类目无繁体名 | 待查 |
| B7 | 规格 | SpecLibraryPage、`goods/category_spec_page.dart` | `/biz/spec-dims`、`/spec-values`、`/my-spec-dims/*`、`/biz/store-spec-dims` | BizGoodsController → MerchantGoodsService | — | **App 正式环境禁用了类目规格**（`goods/category_spec_api.dart:17`） | 待查 |
| B8 | 商品与 SKU | `goods/goods_pages.dart`、`goods_publication_pages.dart` | `/biz/goods/save`、`/submit`、`/publish`、`/revisions*`、`/biz/sku-identity/*` | BizGoodsController、BizSkuIdentityController | AI 识别、对象存储 | 素材包仅 mock | 待查 |
| B9 | 门店上架、定价、库存 | 商品页、`inventory/inventory_pages.dart` | POST `/biz/goods/{no}/toggle`、`/store-price`、`/store-stock`、`/biz/inventory/**` | BizGoodsController、BizStock*Controller、invbridge | 库存模块 | 首次 store-stock 把 SKU 切成按店管理 | 待查 |
| B10 | 直接收款（付款链接、店内码） | `collect/payment_link_pages.dart`、`collect_pages.dart` | POST `/biz/payment/collection`、`/biz/payment/qr-code`、`/biz/payment/offline-records` | BizPaymentController → BizPaymentAppService | near-pay 收银台 `/checkout/v1` | LINE Pay、蓝新、街口未接；只有店内静态码 | 待查 |
| B11 | 支付结果回写 | `system/payment_event_stream.dart` | SSE `/biz/payment/events`、GET `/biz/payment/orders/{no}` | BizPaymentEventsController、InternalPaymentCollectionController | near-pay 推送 | 推送需 `payment_collection_source` 有 PRODUCTION 映射（当时 2 家） | 待查 |
| B12 | 订单列表与详情 | `store_orders/store_orders_pages.dart` | GET `/biz/order?caller=RETAIL`、`/{no}/full`、发货、自提、`confirm-offline-pay` | BizOrderController → MerchantOrderService | 物流 | 不能按单号/顾客搜；台湾门店发货被拒 | 待查 |
| B13 | 退款与售后 | AfterSaleListPage、AfterSalePage | `/biz/after-sale/*`、POST `/biz/payment/refund` | BizAfterSaleController、RemoteOrdinaryRefundPort | near-pay 退款 | — | 待查 |

商户 App 里**没有按商品下单的收银入口**（CashierPage 只按金额收款；`/biz/store-orders` 只有手持 POS 在规划）。B 端「下单」在本闭环里指 C 端顾客下单、商户看单与收款。

## 2 C 端（顾客）闭环节点

| # | 节点 | 页面 | 接口 | 后端 | 依赖服务 | 已知缺口 | 检查 |
|---|---|---|---|---|---|---|---|
| C1 | 登录/注册 | `pages/login/index.vue` | POST `/mp/user/login`、`/login/phone`、`/otp/send`、`/phone/bind` | MpUserController → AuthService | 微信 code2session、短信 | 发码要求已登录 | 待查 |
| C2 | 选店 | `pages/store` | GET `/mp/store/{no}`、`/by-code`、`/nearby` | MpStoreController | 地图 | — | 待查 |
| C3 | 类目与商品列表 | `pages/category`、`pages/home` | GET `/mp/goods`、`/mp/store/{no}/goods` | MpCatalogController → GoodsService | — | 类目页没调 `/mp/category/tree` | 待查 |
| C4 | 商品详情与规格 | `pages/goods/index.vue` | GET `/mp/goods/{goodsNo}` | MpCatalogController | 库存（available） | 没调 `/sku-price` | 待查 |
| C5 | 购物车 | `pages/cart/index.vue` | `/mp/cart`、`/add`、`/update`、`/remove` | MpTradeController → CartServiceImpl | 库存 | 切 INVENTORY 后加购不拦缺货 | 待查 |
| C6 | 确认订单与计价 | `pages/order-confirm/index.vue` | POST `/mp/order/preview`、`/capability` | OrderServiceImpl.preview/capability | 营销、计价 | — | 待查 |
| C7 | 创建订单 | 同上 | POST `/mp/order`（`Idempotency-Key`） | OrderServiceImpl.create | 库存锁量 | 新结账 `/mp/checkout/*` 端上未用 | 待查 |
| C8 | 发起支付 | `pages/pay/index.vue` | GET `/mp/order/{no}/pay-method`、POST `/mp/order/{no}/pay` | OrderServiceImpl.pay → SettlePort（standalone 走 RemotePaymentPort → near-pay） | near-pay、微信支付 | **H5 端不唤起支付、靠 stub，生产 stub 关** | 待查 |
| C9 | 支付回调与订单状态 | pay 页轮询 | GET `/mp/order/{no}/pay-result` | near-pay 回调 → 订单投影；兜底 OrderPaidReconciler | near-pay、微信 notify | **独立部署时回调投影订单是占位实现**（`pay-svc/.../PendingRemotePortsConfig.java:53-60`），要核实线上怎么把订单改成已付 | 待查 |
| C10 | 订单列表与详情 | `pages/orders`、`pages/order` | GET `/mp/order`、`/{no}`、`/trace` | MpTradeController | 物流 | — | 待查 |
| C11 | 售后与退款 | `pages/after-sale/index.vue` | POST `/mp/order/{subNo}/after-sale`、`/mp/after-sale/*` | AfterSaleServiceImpl → SettlePort.refund | near-pay 退款、对象存储 | 只能整单退 | 待查 |

## 3 依赖服务清单

| 服务 | 用在 | 检查 |
|---|---|---|
| near-pay（.80:18093，Nacos `near-pay`） | B4、B5、B10、B11、B13、C8、C9、C11 | 待查 |
| Nacos（.80:8848） | 服务发现 | 待查 |
| 短信 | B1、C1 | 待查 |
| 对象存储 / 本地上传目录 | B2、B8、C11 | 待查 |
| 库存模块（进销存库） | B9、C5、C7 | 待查 |
| 微信（小程序登录、支付、通知） | C1、C8、C9 | 待查 |
| 运营审核（运营端） | B3、B4 | 待查 |
| 正式库 `ai_shop_ume`（.61） | 全部 | 待查 |

## 4 检查记录

（逐项检查的结论写在这里，按节点编号。）

### 2026-11-27 第一轮（只读）

- **near-pay**：.80:18093 在跑（`/internal/pay/v1/*` 回 401、`/checkout/v1/*` 回 403，鉴权正常）。同机有容器 `nearpay-subject-next` 刚启动，像是别的会话在发 near-pay。
- **Nacos 发现**：v2 日志里约每 20 分钟出现一次「从 Nacos 取服务 PAY（near-pay）失败，回落到配置的地址」，一分钟后恢复。调用靠回落地址没有断，但 near-pay 在 Nacos 的登记在抖。
- **C9 支付回写（最关键）**：正式库近 14 天已付订单 23 笔，其中 21 笔线下付款、2 笔无付款渠道；**在线支付一笔都没有**，最近一笔已付是 11-24。近 14 天有 8 笔一直停在待付款（11-23 有 7 笔、11-26 有 1 笔）。在线下单 → near-pay 收款 → 订单变已付这一段，线上还没有真实跑通的证据。代码上，独立部署时订单状态靠支付回调投影或兜底对账任务 `OrderPaidReconciler`；要用一笔真实在线支付来验。
- **C8 发起支付**：C 端 H5 不唤起支付、靠 stub 推进，生产 stub 关着，所以 **H5 上付不了**；在线付款要走小程序（微信支付）或 near-pay 收银台页面。
- **B 端下单入口**：商户 App 没有按商品下单的收银入口，只有按金额收款（付款链接、店内静态码）。

### 2026-11-27 代码核对：C 端在线支付在独立部署下没接上 near-pay

读代码确认线上「在线支付一笔都没有」的原因（v2 配 `SHOP_PAY_DEPLOYMENT=standalone`）：

| 段 | 原来 | 问题 |
|---|---|---|
| C8 发起 | `SettlePortImpl.initPayment` 走本进程的支付域（legacy dispatch） | 钱不进 near-pay，独立部署下本进程没有可用的渠道配置 |
| C9 确认 | `successPayment` / `paymentByOutTradeNo` 读本地支付账 | 独立部署下本地支付账是空的，永远查不到 |
| C9 推送 | near-pay 的 INTERNAL_EVENT 推送 ai-shop 没有接收端（B 端收款有 `/internal/payment-results/merchant-collections`，C 端订单没有） | 付完不会自动转已付 |
| C9 兜底 | `OrderPaidReconciler` 读本地支付账 | 独立部署下永远「没有差异」 |
| C8 端上 | `packages/shared/src/ports/payment.ts` 的 H5 分支直接返回「已唤起」，不付钱 | H5 上付不了 |

本轮改动（未提交、未部署）：

1. `SettlePortImpl`：独立部署时，发起走 `OrdinaryPaymentInitiationPort`（near-pay），确认走 `OrdinaryPaymentPort.success(订单号, 下单人)`；`PayClientConfig` 只在独立部署时提供这组 bean，内嵌形态不变。
2. `OrderServiceImpl.payResult`：付款页轮询时，订单还是待支付就先向支付服务确认一次，确认成功就 `markPaid`（幂等）。免付（FREE）分支改用 `successPayment(订单号)` 并核对 outTradeNo。
3. 新任务 `order-paid-remote-recon`（每 2 分钟）：独立部署下扫 24 小时内的待支付订单，逐笔向 near-pay 取成功记录、补 `markPaid`。兜住付完就关页面的顾客。
4. C 端 H5：从 near-pay 回的支付参数里找跳转地址（`paymentUrl`/`payUrl`/`redirectUrl`…，支持 `paymentUrl.web` 一层嵌套）就跳过去；只有表单（`paymentForm`）就提交表单；都没有就报「不支持在网页上付款」。回到付款页后轮询 pay-result 由第 2 条确认。

仍待定：

- near-pay 推送给 C 端订单的接收端（要 near-pay 配 `internal-results.targets`，和 near-pay 那边协调）；目前靠第 2、3 条，最长延迟 2 分钟。
- Oscar 测试商户 `PM0E0F6CC8F7454D75` 在 near-pay 开了哪些渠道、`/mp/order/{no}/pay-method` 实际回什么；要在线上下一单看支付参数的键名是否落在第 4 条的范围里。
- B7：商户 App 正式环境禁用了类目规格。

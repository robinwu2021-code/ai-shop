# B 端经营 · 收款、订单、商城采购、付款链接 · API 对接文档

文档版本：2026-11-05。后端基线：`92bb3a701`，分支 `codex/commerce-into-ai-shop-codex`（未推送）。

本文汇总 Flutter 商户端在原型 v5 / v6 下要用的服务端接口，包括本期新增和改动的接口，以及直接复用的既有接口。注册、登录、建店、证照、进件前半段见 [B端注册到进件-标准API文档](B端注册到进件-标准API文档.md)。设计与测试对账见：
- `docs/technical/TDD-B端收款订单与商城接口对齐.md`（P1 收款、P2 零售订单、P3 第一批商城）
- `docs/technical/TDD-门店收银台与付款链接.md`
- `docs/technical/TDD-账户登录与选店校验.md`

商城接口的形状以 [commerce-mall-openapi.json](commerce-mall-openapi.json)（版本 `task8-v1+p3`）为准。

---

## 1. 接入约定

| 项目 | 约定 |
|---|---|
| 认证 | `Authorization: Bearer <BTK>`。令牌只代表账户，不含门店 |
| 选店 | **登录后必须先选门店，所有经营操作都基于门店**。每个经营请求都带 `X-Store-No: <storeNo>`；路径里带门店号的，必须与请求头一致 |
| 响应 | `{code, msg, data}`。`code=0` 为成功，msg 不能作为程序分支的依据 |
| 金额 | `amountMinor` 为最小货币单位（long）。商城接口的 `Money` 是 `{currency, minor}`，其中 `minor` 是**字符串** |
| 时间 | 都是 UTC epoch 毫秒；日期参数用门店所在市场的 `yyyy-MM-dd` |
| 分页 | 本期新接口用**游标**：传 `cursor`、`size`（≤50），返回 `nextCursor`，为 null 表示没有下一页。旧接口 `/biz/order` 仍用 `page`、`size` |
| 枚举 | 端上遇到不认识的枚举值，按「其他 / 未知」显示，不要崩溃 |

### 1.1 门店准入与 70006

新流程建的门店属于新模型（STORE_V1）。一个请求要先通过下面几道检查，才会进到具体接口：

| 情况 | 返回 |
|---|---|
| 登记过的日常经营接口，选了门店、是该门店成员、有接口要求的权限码 | 正常处理 |
| 没带 `X-Store-No` | `70006` + `data.nextAction="SELECT_STORE"`，端上应回到选店页 |
| 不是该门店成员、没有权限码、接口未登记 | `70006` + `data.nextAction="CONTACT_ADMIN"` |

已登记的日常经营接口（`ShopStoreMemberRoutes`）：
- `/biz/goods/**`、`/biz/sku-identity/**`、规格相关接口、`/biz/category/tree`
- `/biz/order/**`、`/biz/after-sale/**`
- `/biz/payment/**`
- `/biz/entities/**`、`GET /biz/entity/{entityNo}`
- `/biz/store/{storeNo}/` 下的 `categories`、`ship-setting`、`freight-template`、`sell-rules`、`stock-sync`、`payment`

新增经营接口必须同步登记。

---

## 2. 「收款」Tab（支付订单）

只列经过支付渠道的钱，来源有四种：收款码、付款链接、POS、业务订单的线上支付。现金和储值不在这里。开放平台第三方的单不进列表；只开了单、一次付款都没发起过的，也不进。

| 方法 · 路径 | 权限 | 说明 |
|---|---|---|
| GET `/biz/payment/collect-status` | `mch:pay:order:view` | 门店是否已开通收款 → `{enabled, channels[]}` |
| GET `/biz/payment/orders` | `mch:pay:order:view` | 列表，参数见下 |
| GET `/biz/payment/orders/summary?from&to` | `mch:pay:statement` | 时段汇总（店员没有这个权限） |
| GET `/biz/payment/orders/lookup` | `mch:pay:order:view` | 两种查法：`amountMinor` 加 `within=10m\|1h\|today`；或 `tradeNoSuffix`（≥6 位，只查今天和昨天） |
| GET `/biz/payment/orders/{payOrderNo}` | `mch:pay:order:view` | 详情 |
| POST `/biz/payment/orders/{payOrderNo}/query` | `mch:pay:order:view` | 待确认的单立即去渠道查单，返回查后的详情 |
| POST `/biz/payment/refund` | `mch:pay:refund` | 既有接口：`{payOrderNo, amountMinor, reason, outRefundNo?}` |
| GET / POST `/biz/payment/qr-code` | `mch:pay:qr` | 既有接口：本店静态收款码，返回 url，二维码由端上画 |

**列表参数**：
- `from`、`to`：门店市场日期，默认今天，跨度不超过 93 天
- `source[]`：`QR_CODE`、`COLLECT`（付款链接）、`POS`、`ORDER`
- `channel[]`
- `status[]`：`SUCCEEDED`、`PARTIALLY_REFUNDED`、`REFUNDED`、`FAILED`、`PENDING`
- `amountMin`、`amountMax`
- `q`：单号、交易号片段或付款人，只允许字母、数字和 `-*`
- `cursor`、`size`

```json
// 列表行 PaymentRow
{"payOrderNo":"P2611051432001","source":"QR_CODE","channel":"LINEPAY","method":"LINE_PAY","currency":"TWD",
 "amountMinor":42000,"paidMinor":42000,"refundedMinor":0,"status":"SUCCEEDED","payerMasked":"LINE 用户 ***88",
 "cardLast4":null,"terminalLabel":null,"bizRef":null,"succeededAt":1793867528000,"createdAt":1793867521000}

// 汇总
{"currency":"TWD","from":"2026-11-05","to":"2026-11-05","collected":{"count":83,"amountMinor":1231000},
 "refunded":{"count":1,"amountMinor":28000},"netMinor":1203000,
 "byChannel":[{"channel":"LINEPAY","count":32,"amountMinor":421000}],
 "feeMinor":0,"feeEstimated":true,
 "expectedSettlement":{"status":"PENDING","amountMinor":1203000,"expectedDate":"2026-11-06","accountMasked":"****8901"}}

// 详情
{"row":{…PaymentRow…},
 "amounts":{"paidMinor":42000,"refundedMinor":0,"refundableMinor":42000,"feeMinor":0,"feeEstimated":true,
            "feeRateBps":0,"expectedNetMinor":42000},
 "settlement":{"status":"PENDING","amountMinor":42000,"expectedDate":"2026-11-06","accountMasked":"****8901"},
 "payer":{"channel":"LINEPAY","method":"LINE_PAY","payerMasked":"…","cardBrand":null,"cardLast4":null,"issuer":null,"authCode":null},
 "refs":{"payOrderNo":"…","outTradeNo":"…","channelTradeNo":"…192811","channelMerchantNo":"SUB-…"},
 "source":{"kind":"QR_CODE","qrKind":"STORE","tableRef":null,"terminalSn":null,"operatorNo":null,"bizRef":null},
 "failure":null,
 "refunds":[{"payRefundNo":"…","amountMinor":28000,"reason":"…","requestedBy":"…","status":"SUCCEEDED","createdAt":…,"succeededAt":…}],
 "timeline":[{"at":…,"kind":"CREATED","amountMinor":42000,"ref":null},{"at":…,"kind":"PAID","amountMinor":42000,"ref":"…"}]}
```

- `timeline[].kind` 取值：`CREATED`、`PAID`、`FAILED`、`CLOSED`、`REFUND_REQUESTED`、`REFUND_SUCCEEDED`。文案由端上按码显示。
- 卡号、发卡行、授权码这一期固定为 null，要等真实渠道接入。
- 收银员只给 `operatorNo`，姓名由端上换。
- 手续费在对账前按配置费率 `shop.pay.fee.estimate-rate-bps` 估算，并标 `feeEstimated=true`。

---

## 3. 收银台 · 付款链接（动态码）

一次性、有有效期的付款链接。顾客打开链接，在收银台 H5（`/c/{token}`）付款。

| 方法 · 路径 | 权限 | 说明 |
|---|---|---|
| POST `/biz/payment/collection` | `mch:pay:collect` | 生成链接：`{amountMinor, subject?, expireMinutes}`，有效期 1–1440 分钟，subject 为空时用店名 |
| GET `/biz/payment/links?status&cursor&size` | `mch:pay:order:view` | 链接记录：`status=PENDING\|PAID\|EXPIRED\|VOIDED` |
| POST `/biz/payment/links/{payOrderNo}/reissue` | `mch:pay:collect` | 再次分享：给同一张支付单换发新令牌，旧链接随即失效。只能对 PENDING 调用 |
| POST `/biz/payment/links/{payOrderNo}/void` | `mch:pay:collect` | 作废：`{reason?}`。作废前会先查单，已付的返回 `status=PAID`，不报错 |

`mch:pay:collect` 默认给店主和店长；店员默认没有，需要店主在角色里授权。

```json
// 生成 / 再次分享
{"payOrderNo":"PYO261105…","token":"<43 位>","expireAt":1793891116408,
 "checkoutUrl":"http://10.0.2.2:5177/c/<43 位 token>","amountMinor":12800,"currency":"TWD","subject":"订金","storeName":"Joint Test Store"}

// 链接记录行
{"payOrderNo":"…","amountMinor":12800,"currency":"TWD","subject":"联调测试","status":"VOIDED","createdAt":…,"expireAt":…,
 "paidAt":null,"channel":null,"operatorNo":"U…","voidedBy":"U…"}
```

- 链接记录里**没有** `checkoutUrl`：令牌只存哈希，生成后拿不回原链接。要重新分享就调 reissue。
- 等待付款时，轮询 GET `/biz/payment/orders/{payOrderNo}`，直到 status 变为 `SUCCEEDED`。
- `checkoutUrl` 是完整地址，由 `shop.pay.checkout.base-url` 拼出。端上直接用于二维码和分享，不要自己拼地址。

**顾客端（收银台 H5 所用接口）**

| 方法 · 路径 | 说明 |
|---|---|
| GET `/checkout/v1/sessions/{token}` | `{payOrderNo, storeName, amountMinor, currency, status, payMethods, expireAt, subject}` |
| POST `/checkout/v1/sessions/{token}/pay` | `{payMethod}` |
| GET `/checkout/v1/sessions/{token}/result` | `{result, tradeNoSuffix}` |

令牌过期、被作废、被换下时返回 HTTP 410（70093）；令牌不存在返回 404。

---

## 4. 零售订单

| 方法 · 路径 | 权限 | 说明 |
|---|---|---|
| GET `/biz/order?caller=RETAIL&status&fulfillments&from&to&page&size` | `biz:order:view` | 零售列表。`caller=RETAIL` 时，caller 为空的存量单也算在内 |
| GET `/biz/order/worklist` | `biz:order:view` | 待办 |
| GET `/biz/order/{subOrderNo}` | `biz:order:view` | 既有详情（OrderVO）。`amount.currency` 现在取主单币种 |
| GET `/biz/order/{subOrderNo}/full` | `biz:order:view` | **完整详情**（新增）；配送员只拿到裁剪后的详情 |
| POST `/biz/order/{no}/ship/precheck`、`/ship` | `biz:ship` | 发货：`{expressNo, expressCompany, confirmExistingTrace}` |
| POST `/biz/order/{no}/prepare`、`/delivered`、`/fulfillment-change`、`/waybill` | `biz:ship` | 备货、送达、改履约、改运单 |
| POST `/biz/order/{no}/confirm-offline-pay` | `biz:receive` | 确认线下收款 |
| GET `/biz/order/{no}/trace`、`/logistics-updates` | `biz:order:view` | 物流轨迹 |
| `/biz/after-sale/worklist`、`/{no}`、`/{no}/approve\|reject\|receive\|confirm-refund` | `biz:aftersale` | 售后 |

```json
// GET /biz/order/{no}/full
{"order":{…OrderVO…},"currency":"TWD","remark":"放门口，不用按铃",
 "customer":{"nickname":"张先生","receiverName":"张先生","phone":"0912…678","salutation":null},
 "source":{"caller":"RETAIL","trafficSource":"MERCHANT_OWNED","admissionKind":null,"payScene":"ONLINE"},
 "payments":[{"kind":"ONLINE","channel":"LINEPAY","amountMinor":38100,"at":…,"channelTradeNo":"LP…5521","payDocumentNo":"…","confirmedBy":null}],
 "operations":[{"at":…,"event":"…","fromStatus":"WAIT_FULFILL","status":"FULFILLING","label":"已发货","operatorType":"MERCHANT","operatorNo":"…"}],
 "invoice":{"requestNo":"…","status":"PENDING","titleType":"PERSONAL","title":"…","invoiceNo":null,"issuedAt":null,"amountMinor":38100}}
```

- 手机号的脱敏规则和原详情一致：商家自送、快递给全号，其余只给后四位。
- 想从付款跳到「收款」里对应的那一笔：用 `GET /biz/payment/orders?q=<channelTradeNo>` 搜。
- 会员等级这一期不提供。

---

## 5. 商城采购（软件、硬件、套餐，线下银行转账）

基础路径：`/biz/stores/{storeNo}/mall`。

| 方法 · 路径 | 说明 |
|---|---|
| GET `…/products` | 目录。每个 SKU 带 `readiness`（在线支付）和 `offlineReadiness`（线下转账，本期新增；线下没开时为 null） |
| POST `…/offline/quotes` | 线下报价：`{lines:[{skuVersionNo, quantity}], currency}` → `quoteNo, payable…` |
| POST `…/orders` | 下单：`{quoteNo, idempotencyKey, shippingAddress?}`。含硬件时必须带收货地址 |
| GET `…/orders`、`…/orders/{orderNo}` | 采购订单列表与详情 |
| POST / GET `…/offline/orders/{orderNo}/instructions` | 转账说明；`instructions.transferRemark`（本期新增）是要求商家填在转账备注里的内容 |
| POST `…/offline/orders/{orderNo}/evidence` → POST `…/evidence/content` | 先预留凭证位，再上传原始字节（jpeg / png，≤10MB） |
| POST / GET `…/offline/orders/{orderNo}/claims` | 付款申报：`{claimNo, declaredAmount{currency,minor}, declaredPaidAt, bankReference, evidenceKeys[1..10]}` |

- 能不能买：`readiness.available || offlineReadiness.available`。
- **转账凭证必填**：`evidenceKeys` 为空时返回 `INVALID_REQUEST`。
- 订单 `summary`（列表行与详情共用）本期新增字段：`title`（第一行的 SKU 标题）、`lineCount`、`latestClaimStatus`。
- 详情本期新增 `lines:[{productNo, skuNo, skuVersionNo, title, quantity}]`，数据取自下单时冻结的快照。
- `ClaimView` 本期新增两个字段：
  - `receivedAmount`：只在 `CONFIRMED`、`MISMATCHED` 时有；
  - `reviewNote`：只在 `DECLINED`、`MISMATCHED` 时有。
- 申报状态：`SUBMITTED`、`REVIEWING` → `CONFIRMED`、`MISMATCHED`、`DECLINED`。
- 订单 `paymentStatus` 要等运营确认到账后才变；页头的「核款中」看 `latestClaimStatus`。

---

## 6. 门店设置与开通收款

| 方法 · 路径 | 权限 | 说明 |
|---|---|---|
| GET / POST `/biz/store/{storeNo}/categories` | `biz:store` / `biz:store:admin` | 货架类目。POST 整份替换：`{items:[{categoryNo, displayName?, sort}]}`；删除仍有商品的类目会被拒 |
| GET / PUT `/biz/store/{storeNo}/ship-setting` | `biz:store` / `biz:store:admin` | 发货设置 |
| GET `/biz/store/{storeNo}/freight-template` | `biz:store` | 运费模板（只读） |
| GET `/biz/merchant/payment?storeNo=`、`/biz/merchant/pay-channel` | `biz:finance` | 收款进件状态 |
| POST `/biz/merchant/payment` | `biz:finance` | 提交进件 |
| POST `/biz/store/{storeNo}/payment` | `biz:store:admin` | 门店挂收款商户号：`{payMerchantNo}`，取自 GET merchant/payment 返回行的 `payMerchantNo` |
| GET `/biz/entities`、`/biz/entity/{entityNo}` | — | 商户主体 |

门店挂上收款商户号之后，后端会把它同步成支付域的门店收款绑定（需要开 `shop.pay.merchant.dual-write`），之后 `collect-status` 才会返回 `enabled=true`。

---

## 7. 错误码速查

| code | 含义 | 端上处理 |
|---|---|---|
| 10400 | 参数不合法（日期跨度超限、交易号不足 6 位、缺凭证等） | 提示修改输入 |
| 10404 | 不存在，或不是本店的单 | 统一提示「不存在」 |
| 70006 | 门店准入被拒，看 `data.nextAction` | `SELECT_STORE` 回选店；`CONTACT_ADMIN` 提示无权限 |
| 70045 | 该门店市场没有可用收款渠道，或渠道不能进件 | 提示暂不可开通 |
| 70093（HTTP 410） | 付款链接已过期、已作废或已被换下 | 顾客端显示失效页 |

---

## 8. 已知缺口（未实现）

| 范围 | 缺口 |
|---|---|
| 开通收款 | 门店改挂已有主体（N1）、绑定已有渠道商户号（N2）、一次提交开通（N3） |
| 商城 | 用量组件与「我的 › 用量」余额；硬件规格；逐组件的交付状态；硬件退款 |
| 零售 | 订单按单号 / 顾客搜索；商品分组计数一次返回；新门店默认类目方案（待定） |
| 发货 | 台湾物流公司代码与物流公司列表（本期不补）；台湾门店发货会被拒 |
| 付款链接 | 链接记录里给 `operatorName` |
| 支付渠道 | 台湾 LINE Pay、蓝新、街口尚未接入；本机用 TEST 渠道，配合 `shop.pay.stub=true` 和 `/pay/callback/stub` 模拟回调 |
| 登录语义 | 登录不预选门店、无主体时不转店员会话（见 TDD-账户登录与选店校验 第二步，待定） |
| 餐饮、美业 | 订单视图（P4）整块未做 |

## 9. 本机联调环境

| 项 | 值 |
|---|---|
| API | `http://localhost:8091`；模拟器访问 `http://10.0.2.2:8091` |
| 顾客端收银台 H5 | `http://localhost:5177`，`/checkout/v1` 代理到 8091；`shop.pay.checkout.base-url=http://10.0.2.2:5177` |
| 模拟付款成功 | `POST /pay/callback/stub {"outTradeNo":"<payOrderNo>","transactionId":"TX-…","sign":"stub-secret"}`（仅本机 `shop.pay.stub=true`） |
| 测试门店 | Joint Test Store `ST261105EZ7KKE8WAS`，TW；已开通 TEST 渠道 |

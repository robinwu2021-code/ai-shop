# 主体额度包线下购买 API

本接口购买的是主体门店/员工额度，不是门店商城权益。金额为 `{currency,minor}`，TWD仍是分（19900=199元，必须整元），时间为ISO秒精度。禁止客户端决定成交价格或收款主体。

|商家端（API）|说明|
|---|---|
|GET /biz/plan/purchases/offers|可购买报价；过滤不支持币种、未配置收款档案、有效付费跨档/无到期协议包|
|GET /biz/plan/purchases|当前主体及本人最近100条购买记录|
|POST /biz/plan/purchases|`{requestNo,offerNo,offerVersion}`；原键原参数重放。下单及付款指引准备状态持久化；没有成功发出指引时instructions=null|
|GET /biz/plan/purchases/{orderNo}|原单、原requestNo、冻结offer、dueAt、instructions、claims、actions|
|POST .../{orderNo}/claims|`{claimNo,amount,paidAt,bankReference,evidenceKey}`。申报仅为声明，超出付款窗口仍可纠正/补报真实到账凭证；AWAITING_PAYMENT/CANCELLATION_REQUESTED/CANCELLED均可申报，CLOSING暂等待对账完成|
|POST .../{orderNo}/cancel|仅申请取消→CANCELLATION_REQUESTED，非立即关闭|
|POST .../{orderNo}/evidence|预留本人的私有凭证，返回assetKey|
|POST .../{orderNo}/evidence/content?assetKey=...|JPEG/PNG原始字节，10MiB以内|
|GET .../{orderNo}/evidence/url?assetKey=...|短期签名读取，禁止把私有字节转公共URL|

商家要求登录老板、`biz:store:admin`、真实选店上下文；沿用 `X-Store-No` 和主体身份头。存储锚点门店不改变“额度归主体”的规则。

运营前缀 `/ops/merchant-plan-purchases/{entityNo}`，受原商家数据域限制：

|接口|权限及输入|
|---|---|
|GET /offers，GET根，GET /{orderNo}|merchant:merchant:read|
|PUT /offers|system:param:update；`{offerNo,expectedVersion,planCode,name,days,price,enabled}`。保存当前档位容量快照，版本递增；仅PRO/CHAIN付费报价；不覆盖旧单|
|POST /{orderNo}/confirm|merchant:merchant:ban + commerce:mall:offline:confirm；`{actionNo,claimNo,amount,paidAt,bankReference,evidenceKey,reason}`。接收者由原单冻结，不接收客户端自填。NORMAL核款不支持早于订单准备时间的历史转账|
|POST /{orderNo}/decline|merchant:merchant:ban + commerce:mall:offline:review；`{claimNo,reason}`|
|POST /{orderNo}/recover|商家处置 + 核款/作废相应权限；恢复原动作，不能换原命令|
|POST /{orderNo}/cancel|merchant:merchant:ban + commerce:mall:offline:void；`{actionNo,reference,evidenceKey,reason}`。只有核对银行后才关闭；不可把收到款的单伪装成无款作废|
|POST /{orderNo}/evidence，POST /{orderNo}/evidence/content|商家处置 + 核款或作废权限；上传自己的平台凭证|
|GET /{orderNo}/evidence/url|商家读权限，可查看该可见订单的付款和核款凭证|

`Offer`：offerNo/version/planCode/name/days/price/storeQuota/staffQuota/crossStoreStats/enabled。
`OrderView`：orderNo/requestNo/entityNo/storeNo/offer/status/dueAt/instructions/claims/actions。
`instructions.instructions` 是bankCode/bankAccount/accountName的冻结指引；显示给商家须非null。
`ClaimView`：claimNo/status/declaration/reviewNote。
`ActionView`：actionNo/status/paymentNo/reason（明确拒绝时为资金底座原因码）。

正常状态：PREPARED→AWAITING_PAYMENT→SUBMITTED→CONFIRMING→APPLIED。
拒绝申报回AWAITING_PAYMENT；明确资金拒绝动作REJECTED、订单回SUBMITTED，须新actionNo纠正。未知失败保持原动作ACCEPTED并恢复。UNMATCHED/REVIEW_REQUIRED不是成功开通，保留未关闭订单的原占用、需后续退款/协商处置功能；已关闭单迟到收款为REVIEW_REQUIRED，不重新占用主体或覆盖其后续新单。取消：CANCELLATION_REQUESTED→CLOSING→CANCELLED；对账明确拒绝保留REJECTED审计记录并回待核查。

恢复原购买：服务器历史PREPARED记录中取requestNo和offer.offerNo/version，重放原Buy；不能生成新键或套用新价格。核款未知：GET原单查actions中原actionNo，ACCEPTED只恢复；缺失时重放原始命令；已终结按原结果刷新，禁止把任意HTTP成功视为权益成功。

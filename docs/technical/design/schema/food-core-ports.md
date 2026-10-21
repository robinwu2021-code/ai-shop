
## 七、餐饮与基础域的输入输出合同

> 状态：待评审 · 2026-10-19 · 本文件是API生成输入片段；正式阅读入口为技术设计目录中的《餐饮系统API设计》。

Catalog.goodsMetadata已实现为GoodsCatalogQueryPort及typed内部查询；其余名称仍为目标端口，不是已存在Java类。集成部署实现为宿主桥接，独立部署实现为服务调用；不将跨库实现细节传入餐饮领域。字段中的ref均须受权查询，不允许客户端自造已付或已退款凭据。

| 端口 | 输入（必填，?为可选） | 输出 | 当前边界 |
|---|---|---|---|
| Identity.authorize | actorToken:string, entityNo:string, storeNo:string, action:string, objectRef?:string | actorNo:string, buyerScope:string[], allowed:boolean, limits:Money[] | 复用身份；匿名就餐主体与代办需正式合同 |
| Catalog.goodsMetadata | entityNo:string, storeNo:string, goodsNo:string | GoodsFact:{goodsNo,entityNo,storeNo,title,cover?,baseOnSale,auditStatus?,version,skus:{skuNo,spec?,version}[]} | CAT-01A基础管理事实；明确商品真实归属及全部CN规格，无价格库存，不表示最终可售；独立经GoodsCatalogInternalApi读取PLATFORM |
| Catalog.snapshot | entityNo:string, storeNo:string, skuNos:string[], channel:string, at:datetime | items:{goodsNo,skuNo,title,unit,currency,priceMinor,onSale,availableQty,version}[] | 读取商品服务，不直接跨库JOIN |
| Catalog.validateSelections | skuNo:string, selections:Selection[], configVersion:int64 | valid:boolean, normalized:Selection[], recipeRef?:string | 强销售约束提交前校验及收费组合为缺口 |
| Order.quote | buyerNo:string, storeNo:string, fulfillment:string, channel:string, lines:OrderLineInput[] | Quote及sourceLineRef到报价行映射 | 当前preview可适配基础SKU；选配小数不直接传Item |
| Order.place | requestKey:string, quoteNo:string, buyerNo:string, storeNo:string, lines:OrderLineInput[], payMode:string | paymentRootNos:string[], storeOrderNos:string[], stableLines:{sourceRef,orderItemId,qty,amount,kind}[], phase:string | 当前create/createFor适配；源行和后付需补能力 |
| Order.findPlacement | entityNo:string, requestKey:string | phase:string, originalResult?:Batch | 超时恢复必须查同键，不任意重建 |
| Inventory.acceptDeferred | storeOrderNos:string[], acceptanceKey:string, expiresPolicy:string | commitmentRef:string, status:string, expiresAt?:datetime | 后付承诺与取消守卫缺口，不能餐饮自行二次锁库存 |
| Fulfillment.deliver | storeOrderNo:string, lineQty:{orderItemId,qty}[], operationKey:string, kind:string | deliveryRef:string, status:string | 自取配送适配现有；堂食及未付交付需补合同 |
| Cashier.freeze | scopes:ScopeItem[], currency:string, scopeHash:string | cashierRef:string, due:Money, version:int64, expiresAt:datetime | 通用应收范围预占和跨单收银缺口 |
| Payment.open | cashierRef或合法paymentRootNo:string, tool:string, amount:Money, actorNo:string, requestKey:string | paymentRef:string, status:string, clientAction?:object | 现有单根pay可适配；混合/跨根分配不可伪造 |
| Payment.query | paymentRef:string, entityNo:string | status:string, confirmedAmount:Money, allocations:{orderRef,amount}[], occurredAt?:datetime | 唯一可信支付结果，退款保持原分配追踪 |
| Payment.confirmCash | storeOrderNos:string[], requestKey:string, actorNo:string, cashEvidence:string | corePaymentRef:string, status:string | 当前confirmOfflinePay完整单店影响集限制保留 |
| Order.adjustReceivable | scope:ScopeItem[], kind:string, amount:Money, reason:string, approvalRef:string, requestKey:string | adjustmentRef:string, newQuote:Quote, status:string | 订单金额不可餐饮直接update；通用调整缺口 |
| AfterSale.request | storeOrderNo:string, lines?:{orderItemId,qty}[], reason:string, requestKey:string | caseNo:string, refundable:Money, status:string | 当前仅门店整单全额；lines部分退受能力门控 |
| Resource.holdOrOccupy | storeNo:string, resourceNos:string[], startAt?:datetime, endAt?:datetime, seats:int32, requestKey:string | claimNos:string[], status:string, expiresAt?:datetime | 时段预约与开放占用分开，统一仲裁者 |
| Resource.transfer | oldClaims:string[], newResources:string[], expectedVersion:int64, requestKey:string | newClaims:string[], status:string, recoveryRef?:string | 先占后放，失败保留可恢复占用 |
| Approval.requestOrVerify | actorNo:string, action:string, amount?:Money, objectRef:string, policyVersion:int64, approvalRef?:string | decision:string, approvalRef:string, approverNo?:string | 复用审批；未存在时建共享能力，不把审批塞进任意JSON |
| Reporting.queryOrBuild | storeNos:string[], range:{from,to}, timeBasis:string, currency:string, metricVersion:int64 | Report及runRef:string | 复用shop-report，新增营业日投影不改旧统计 |
| Reconcile.evidence | storeNo:string, dateRange:{from,to}, channel:string, currency:string, watermark?:string | totals:MetricCell[], differences:{ref,type,amount,status}[], asOf:datetime | 支付对账非餐饮重建资金账 |
| Assets.applyOrReverse | settlementRef:string, assetRef:string, amount:Money, requestKey:string, originalRef?:string | operationRef:string, allocations:{principal,gift}[], status:string | 会员资产唯一扣减，充值不计消费收入 |
| AR.transferOrApply | customerRef:string, scope:ScopeItem[], amount:Money, approvalRef:string, requestKey:string | arRef:string, balance:Money, status:string | 企业挂账/PMS房账待通用应收能力 |
| Print.submit | sourceEventNo:string, scene:string, sourceRef:string, contentVersion:int64 | jobNos:string[], status:string | 通用打印业务与渠道分层；内容服务端冻结 |

核心命令需要最终校验报价、权限、库存及资金版本；餐饮预校验不能替代核心提交校验。发票、排队、PMS、税费与第三方专项协议以其权威系统合同为准，实施前补充协议版本及失败恢复，不在此臆造现成接口。

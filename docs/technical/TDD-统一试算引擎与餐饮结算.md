# 统一试算引擎与餐饮结算

> 状态：设计提案，未实现 · 2026-10-22 · 档位2。用户已确认：前端统一调用Java后端API。本篇是可评审的接口、对象与执行方案，不代表接口、表或SDK已经存在。不改冻结V1–V7迁移。
>
> 需求：[餐饮完整需求](../requirements/餐饮系统-完整需求文档.md)「报价、结账、资金与报表」；[后端计划](./TDD-餐饮系统-后端开发计划.md) ORD-02；边界：[ADR-040](./ADR/ADR-040-餐饮销售配置与耗用权威边界.md)；前置：[ORD-01](./TDD-餐饮系统-ORD01共享购物车实施.md)。

## L1 定位与结论

封装一套Java试算引擎，前端选菜、选券、选择储值支付、现金实收变化时都调用后端。订单正式确认、后付结账和收款确认使用同一套计算内核，但按阶段选择“当前权威事实”或“已冻结订单事实”。支付成功后只核对原资金证据，不按今天的菜单和优惠重新定价。

三个账清楚表达：**商品应收、优惠与调整、支付资金分配**。储值是支付工具，券是优惠/权益，找零是现金收付差额，抹零是明确的应收调整。它们在同一个报价结果中展示，各自仍由原订单、营销/权益、PAY权威执行。

引擎遵循：相同完整输入和规则版本产生相同金额及分摊；金额不使用double/float；试算不核销券、不预占余额、不扣库存、不代表支付成功；正式确认必须重验并使用原指令幂等及恢复。

### 方案比较

| 方案 | 优点 | 主要代价 | 结论 |
|---|---|---|---|
| 共享纯计算内核＋核心报价服务＋餐饮适配 | 同一实现覆盖前后端调用与正式确认，保留原资金权威，可逐步接入 | 需新增明确的报价/冻结合同，不能假设现有储值支持餐饮 | 推荐 |
| 餐饮自建全部优惠与收款计算 | 初期改动集中在food | 复制券/储值规则、订单与退款分摊形成两套权威，违背ADR-040 | 不采用 |
| 一次替换全部零售下单计算 | 最终入口统一 | 影响范围过大，既有团购、配送、积分、旧券迁移一起变化 | 不作为首期 |

## L2 结构与职责

![统一试算引擎与权威边界](./diagrams/unified-pricing-engine.svg)

| 层 | 责任 | 部署与归属 |
|---|---|---|
| food-web / food-api | B/C接口、范围鉴权、简洁DTO；把真实购物车或获准账单范围转换为源引用 | 继续属于餐饮项目；嵌入或独立部署保持相同合同 |
| food-core | 餐牌/套餐/配料冻结定义、草稿版本、桌台/AA范围及报价引用 | 不自行写核心订单金额、券余额或储值余额 |
| shop-base.common.pricing | 无IO、无Spring、无Mapper的定点值对象、计算内核与规则组合 | 先放现有shop-base包，不急于新建独立Maven模块 |
| shop-core.trade | 加载权威事实、组织优惠决策、调用纯内核、报价快照、重算确认、订单/结算桥接 | 金额与应收确认的核心权威；不依赖food实体类 |
| 原营销/权益及PAY | 活动与券适用判断、已验证分摊、储值批次/本金赠金、授权/预占/提交/释放、资金证据 | 使用既有Port；跨进程通过真实typed InternalApi适配 |
| 库存权威 | 可售/预留及原引用结果 | 试算读可售事实；库存预留发生在正式提交协议中 |

### 当前源码核对，不把目标当实现

| 已有源码 | 当前实际能力 | 本设计如何处理 |
|---|---|---|
| `OrderService.preview` / `OrderServiceImpl.discountsOf` | 预览和下单已复用活动/券计算；活动先于券，券门槛依据活动后金额 | 保留顺序与原规则；逐步通过新报价门面组织，首期不整体重写零售 |
| `CouponPort.allocate`、`CouponCalculation` | 原券分摊及定点算法；PERCENT的value代表“应付比例基点” | 复用，不再实现一份券公式；9000代表付90%，不是优惠90% |
| `EntitlementUsePort` | `quote/prepare/commit/release/query`；报价不预占；UseItem数量int | 餐饮来源、源行身份及计量适配需验证，不伪造已存在的订单/服务源 |
| `StoredValueUseQuotePort` / `StoredValueRedemptionServiceImpl` | 当前只接收CORE/SERVICE_USE、CNY、无freight；quote需适用余额覆盖整个来源才redeemable | 餐饮购物车、普通餐饮账单、部分储值混合支付均是合同缺口；不能将applicableMinor直接当可扣金额 |
| `CouponCalculation.allocate` | 各项四舍五入，差额按最大基数补齐，包含稳定顺序 | 保留原算法/分摊，不替换成另一种算法导致旧券历史漂移 |
| 独立支付/库存核验TDD | 普通现金/多根混合、后付、原指令查回及独立库存仍有专项门禁 | 本篇给出合同需求；真实适配验收前返回BLOCKED，不假装端到端已完成 |
| API041/131、056～061、134/135/140 | 已有餐饮目标接口规划，尚非完整报价/混合收银实现 | 优先扩展这些接口语义，确认后再同步canonical设计及代码清单 |

参考源码均位于当前仓库：`backend/shop-core/.../trade/service`、`backend/shop-base/.../spi`及`backend/pay/pay-domain/.../storedvalue`。实现前应复核当时版本。

## §0 需求与验收映射

| AC | Given / When / Then | 落点 |
|---|---|---|
| Q01 | 同一事实快照/顺序规范/规则版本，试算或确认反复执行，金额、分摊和economicsHash相同 | PricingEngine / 金额守恒与确定性测试 |
| Q02 | 餐牌、渠道价格、小料组合、套餐补差变化，只有权威冻结定义决定价格；缺原SKU/配置合同则阻止确认 | FoodPricingSourceResolver / BasePricePolicy |
| Q03 | 有券/活动，原所属服务判适用、门槛、叠加及分摊；试算不核销，失效不能静默换券 | BenefitQuoteResolver / BenefitApplicationPolicy |
| Q04 | 选择储值，展示授权范围内可用额和本金赠金分配；无部分支付合同不得假设可混用 | StoredValueFundingAdapter / FundingPlanPolicy |
| Q05 | 现金实收大于现金应收，只算现金找零；电子/储值超额不转现金；现金不足阻止最终确认 | CashTenderPolicy |
| Q06 | 选择抹零/调价，验证B端权限、有效审批、调整基数/方向/上限；C端不能构造员工调整 | PriceAdjustmentPolicy / RoundingPolicy |
| Q07 | 正式下单前重取当前权威快照；发生金额或商业含义变化，返回差异并要求重新确认 | PricingConfirmationService / quote comparison |
| Q08 | 已下单后，基于冻结订单及已成功收款继续算剩余，不读取今天的菜单重算旧菜，不重复应用优惠 | FrozenOrderPricingSourceResolver / RemainingDuePolicy |
| Q09 | AA、并单或重复支付竞争，冻结获准范围，已付/未知份额不能再收；优惠与支付分摊按源行可追溯 | ScopeAllocationPolicy / 原核心结账冻结协议 |
| Q10 | 前端请求乱序/报价失效/远端UNKNOWN，不能用旧报价付款或换键重试；幂等查询返回原结果 | SDK交互约定 / 原意图恢复 |
| Q11 | 退款时按原价格、原优惠和原资金分摊反向处理，不按现规则重算、不给储值现金找零 | 原退款编排 / 冻结quote allocation |

## L3 详细设计

## §1 计算规则与输入输出

### 1.1 金额、数量与固定精度

- 对外兼容现有int64最小币种单位：`amountMinor: long`、`currency`。金额值对象不自动跨币种相加；每次加减用`Math.addExact/subtractExact`，溢出明确失败。
- 货币小数位来自受控币种定义/版本，不写死“所有币种两位”。首批经营币种和支付/储值适用币种由原能力明确返回；储值当前CNY限制必须保留到合同升级。
- 数量使用BigDecimal，支持能力内的COUNT整数；称重最多6位小数仅在商品、核心订单与库存合同都支持后启用，否则BLOCKED。率用整数基点10000或受控有理数，区分`PayableRateBp`与`DiscountRateBp`。
- `unitPriceMinor × quantity`使用BigInteger分子/固定分母，或BigDecimal精确乘法；在“计价叶子行”按快照中的规则一次舍入到最小单位，随后父套餐/菜项只求和，不再逐层舍入。
- 不引入“万能自动四舍五入”。普通计量舍入、优惠分摊尾差、税额舍入、经营抹零、现金币值进位分别建模、记录各自policyVersion。
- 既有券分摊保留所属服务原结果；新增自有调整分摊采用有版本的`ProportionalAllocationPolicy`，受益行、封顶、稳定sourceLineRef排序、尾差规则一并冻结。不能用输入数组当前顺序决定谁多1分。
- 不让Java long溢出变负数，也不让前端JS丢精度。沿当前JSON number合同，新增API金额设业务上限且不超过JS安全整数；新增数量合同以严格十进制规则进入BigDecimal，不经过double；既有购物车qty的JSON形状保持兼容，不因此强改为字符串。若未来支持完整long范围，另设版本化十进制字符串合同，不能偷偷修改全平台序列化。

### 1.2 三类结果，不能混为一个total

| 结果 | 核心字段 | 含义 |
|---|---|---|
| ReceivableBreakdown | originalGoodsMinor、priceAdjustmentMinor、activityDiscountMinor、couponDiscountMinor、feeMinor、taxMinor、waiverMinor、receivableMinor | 商品经济结果；优惠不含储值付款，税含/不含口径显式标识 |
| FundingPlan | confirmedNetPaidMinor、pendingFundingMinor、plannedStoredValueMinor、plannedOnlineMinor、cashDueMinor、remainingAfterPlanMinor | 已确认资金与本次计划分列；计划支付不能填进已付 |
| CashTenderQuote | cashDueMinor、cashTenderedMinor、changeMinor、shortageMinor | 现金实收与找零；非现金无change |

基础守恒：`receivable = 原价 + 有符号调价 - 活动 - 券 + 新增费用 + 价外税 - 抹零`。

资金守恒：`confirmedNetPaid + outstanding = receivable`；无争议未付场景下`已确认资金 + 本次计划净支付 + remainingAfterPlan = receivable`。UNKNOWN份额另列且占用可付范围，不能当已付，也不能重新分配给另一工具。

现金守恒：`现金净支付 = cashTendered - change`；`change=max(0,tendered-cashDue)`、`shortage=max(0,cashDue-tendered)`。不足时可展示草稿计划，但不可确认“已结清”。

退款形成独立有符号反向凭据和原可退计数，不能把退款直接作为“可再次消费余额”触发重复收费。退款/撤销是否重开应收由原核心状态决定。

### 1.3 固定执行顺序

1. **范围与身份**：原购物车版本或冻结账单范围、主体/商户/门店、实际买家、渠道、币种、AA份额；从服务端解析，不接受客户端代填身份或价格。
2. **基础计价**：原SKU价、渠道价、已发布规格/配料组合、套餐父价和补差。套餐若使用父价，明细只作分摊/耗用，不再按零售价重复加总。
3. **获准调价**：具体计价叶子行改价/单级优惠，作用范围与是否含小料明确；默认人工商品优惠先于活动与券，门槛基数随之改变。
4. **活动**：仍问原活动权威；按当前规则处理渠道/门店/支付模式和出资方，不扩大线下可用范围。
5. **优惠券**：对活动后、券可作用的商品额，由原券/权益权威判门槛与叠加；保留quoteRef、适用源行、资方与完整分摊。原合同只支持一券时，先限制一券，不能在引擎循环扣多张实现伪叠加。
6. **费用和税**：按受控策略/原权威结果算服务费、包装、配送等，说明收费基数是否包含券后价；含税价格不重复加税。未实现的税率、含税反算、小费或支付附加费不默认为0或擅自启用。
7. **经营抹零**：明确订单级减免，限定只减不加、最大值及审批；逐行分摊。只有明确的获准进位政策允许增加应收，展示为另一调整种类。
8. **资金计划**：扣除原已确认净收款及锁定中的意图范围，按顾客明确选择分配储值、线上、现金；尊重账户/批次适用范围，不读一个余额后自己取min。
9. **可选现金币值舍入**：只作用于最终现金剩余，单列调整并反映回receivable。其他工具金额先冻结，不再反复自动“最大储值→现金舍入→再最大储值”。修正应收后保持非现金计划不变，刷新现金应付、找零和remaining，并再次校验守恒。当前无已验证现金币值政策时不启用，不能把它当通用抹零。
10. **找零与全量校验**：现金实收比较现金应收；校验所有分摊和金额守恒、能力门禁、范围版本、来源指纹和有效期。

顺序写成不可变`PricingProfile`，例如`FOOD_STANDARD_V1`；策略可替换，依赖次序不能由前端传。试算响应返回具体profileVersion与ruleSetHash。

### 1.4 储值卡与优惠券

**储值卡**：区分业务卡号/会员码与真实PAY账户；通过原身份和授权流程解析账户，端上不拿任意accountNo代扣。本金与赠金按PAY原批次规则、门店/项目限制及先后顺序分配；引擎消费权威`FundingOffer`，不在food重写lot选择算法。储值赠金不再作为一笔营销优惠重复扣减销售额。

建议扩展原储值报价合同的语义：`quoteFunding(verifiedSource, requestedMinor|maxApplicable, expectedSourceHash)`，返回`offerRef/accountRef/assetRevision/eligibleLineCaps/principalMinor/bonusMinor/amountMinor/sourceHash/expiresAt`及可否部分支付。`offerRef`只是报价，不是冻结凭据。实际prepare须同金额、同源hash、同批次/资产版本重新核验，可能竞争失败。该签名为提案，不是现有StoredValueUseQuotePort方法。

餐饮未下单报价需要可信草稿报价源；正式下单后必须绑定真实核心订单/结算范围，保留sourceLineRef→核心行的映射。不能虚构SERVICE_USE订单来绕过PAY限制。新来源需在原SourceRef协议、来源解析器和能力矩阵中显式增加，并做拒绝任意客户端金额测试。

**优惠券**：旧券CouponPort/LegacyCouponUsePort与新权益EntitlementUsePort按券来源路由到唯一所属实现，不能同一券走两套后端。只允许所属服务认定的券/活动/储值叠加；储值付款不应自行降低券门槛，但券明确限定支付方式时要带入计划渠道，改支付方式会使报价失效。匿名扫码只能使用原权威明确允许的公开优惠，不能获得同桌其他买家的券或储值。

#### 来源事实与正式扣款的稳定身份

拟在shop-base.spi.trade定义`PricingSourceFactsPort.resolve(context, sourceRef, scopeHash)`，返回有schema/hash/version的只读计价事实。餐饮实现通过已发布餐牌、配料与套餐定义提供组件及价格依据；核心验证原商品身份、发布修订、配置授权和收费白名单后交给纯引擎计算。不能将前端传来的“可信价格”原样转为该内部事实，也不能由food算完总额再要求core照单创建。

未下单时若PAY需要读报价基数，新增受控“只读草稿报价源”合同；正式prepare/commit必须转换为真实核心订单或排他结算份额/付款意图。稳定消费来源不能使用每次变化的quoteNo，否则多次报价会变成多次可扣来源。每个资金意图绑定同一冻结范围、quoteFingerprint和可扣上限，同源不同修订须先解决原意图；AA/分次付款用核心签发的独立范围引用，不由客户端生成新引用绕过已扣额度。

### 1.5 调价、抹零与找零对象

| 类型 | 输入意图 | 规则 |
|---|---|---|
| UNIT_PRICE_OVERRIDE | lineRef/componentRef、targetUnitMinor、reason、approvalRef? | B端原权限；作用于特定基础SKU或特定小料，默认不把“小料免费”隐含到基价调价中；负价拒绝 |
| ORDER_DISCOUNT | requestedDiscountMinor、reason、approvalRef? | 在商品优惠阶段分摊，不能超过可优惠基数；是否叠加由profile及原权威决定 |
| ORDER_WAIVER | amountMinor或获准策略编号、reason、approvalRef? | 抹分/抹角/抹元/限定定额，仅对获准未结范围；总量、班次/员工累计限额由审批权威确认 |
| CASH_DENOMINATION_ROUNDING | policyRef；无任意signedDelta | 仅最终现金剩余；不调整电子支付、不无声增加应收；账单多次分付只能对最终片段执行一次 |
| CASH_TENDER | tenderedMinor | 仅B收银端且有真实现金权限/当班；找零由服务端计算，确认需原现金证据 |

“目标整单实付”不得作为任意客户端finalAmount入口。若需要一键调到目标价，前端提交获准操作意图，核心从原范围计算差额并校验审批，仍记录原价/差额/原因；已收款后不得直接覆盖历史金额，走核心应收调整或退款协议。

### 1.6 统一引擎的Java面向对象设计

尽量沿现有单层DTO、Service接口/Impl、policy与spi规范，不机械增加Command→DomainDTO→ResultVO多层映射。以下是**待实现**签名草案：

```java
// shop-base: ai.neargo.shop.common.pricing
public interface PricingEngine {
    PricingResult calculate(PricingInput input);
}

public record Money(String currency, long amountMinor) { }
public record PricingInput(
        PricingScope scope,
        PricingFacts facts,
        PricingProfile profile,
        List<BenefitDecision> benefits,
        List<ApprovedAdjustment> adjustments,
        FundingFacts funding) { }

public record PricingResult(
        ReceivableBreakdown receivable,
        List<PricedLine> lines,
        List<PriceAllocation> allocations,
        FundingPlan funding,
        CashTenderQuote cash,
        String economicsHash,
        List<PricingIssue> issues) { }

// shop-core: ai.neargo.shop.trade.service
public interface OrderPricingService {
    PriceQuoteResultVO quote(VerifiedPricingContext context, PriceQuoteReq req);
    PriceComparisonVO revalidate(VerifiedPricingContext context, RevalidatePriceReq req);
    ConfirmedPrice confirm(VerifiedPricingContext context, ConfirmPriceReq req);
}
```

- `PricingInput`是已解析、不可变、版本化且有来源的事实；不等于浏览器请求。集合防御复制，构造时校验币种、引用和上限；值对象不持有数据库Entity。
- `DefaultPricingEngine`组合少量具名策略，固定调用顺序；使用工厂根据profileVersion选择策略，禁止全局可变配置和反射脚本执行。无需为每个加法建一个Spring Bean或使用可任意重排的责任链。
- `PricingResult.economicsHash`只覆盖规范化经济字段/分摊和规则指纹，不包含新生成quoteNo、traceId、时间戳等；来源事实版本/授权则在quoteFingerprint中独立绑定。金额一样而买家、资方、券身份或范围不同，仍必须确认差异。
- 当前时间、用户权限、币种精度、价格事实均在输入中；内核不调用Clock.now、SecurityUtils、Mapper或远端。依赖原规则的优惠决定由外部解析并经baseFingerprint匹配，防止拿A账单的券报价贴到B账单。

#### 关键类与方法清单

| 包/类（待实现） | 方法 | 职责 |
|---|---|---|
| common.pricing.DefaultPricingEngine | calculate(PricingInput) | 纯计算与守恒，输出行/整单/资金计划 |
| common.pricing.FixedPointArithmetic | multiplyAndRound、applyRate、addExact | 定点运算，不复制券权威算法 |
| common.pricing.BasePricePolicy | price(ResolvedPriceFacts) | 已验证原SKU/小料/套餐定义的计价叶子行 |
| common.pricing.PriceAdjustmentPolicy | apply(base, approved) | 调价意图/受控差额，不自行批准员工操作 |
| common.pricing.BenefitApplicationPolicy | apply(frame, decisions) | 验证权威决定与基数hash、分摊总和；不查询券数据库 |
| common.pricing.FeeTaxPolicy | apply(frame, facts) | 显式费用/税口径，不内置地域税法 |
| common.pricing.RoundingPolicy | applyOrderWaiver、applyFinalCashRounding | 区分抹零与现金币值规则 |
| common.pricing.FundingPlanPolicy | plan(frame, fundingFacts) | 已付/UNKNOWN/本次计划分离，按权威上限分配 |
| common.pricing.CashTenderPolicy | quoteCash(due, tendered) | 找零、不足额与现金守恒 |
| common.pricing.ProportionalAllocationPolicy | allocate(amount, eligibleLines, version) | 新增自有调整的有版本分摊，保留所属权威券分摊 |
| trade.service.impl.OrderPricingServiceImpl | quote、revalidate、confirm | 编排权威事实、纯引擎、快照、差异及确认协议 |
| trade.pricing.PricingFactsResolver | resolveLive、resolveFrozenOrder | 明确事实模式，后付不重读当前菜单 |
| trade.pricing.BenefitQuoteResolver | resolve(baseFrame, selection) | 原活动/券/权益报价，保留来源和分摊 |
| trade.pricing.StoredValueFundingAdapter | quoteFunding、verifyOffer | 原PAY合同桥接；不直接依赖PAY实体 |
| trade.pricing.PricingSnapshotPolicy | normalize、fingerprint、compare | 稳定排序、差异、快照schema及版本 |
| trade.pricing.PricingConfirmationService | prepare、resume、query | 借用原订单/资金意图及Outbox协议，不在纯引擎扣款 |
| trade.mapper.TradeMappers新增嵌套Mapper | 报价insert/select、状态CAS、冻结行/分摊append | 沿现有嵌套Mapper风格，不暴露Entity给Controller |
| spi.trade.PricingSourceFactsPort | resolve(context, sourceRef, scopeHash) | 通用权威计价事实入口；餐饮本地/远端实现，核心不依赖food实体 |
| spi.trade.OrderPricingPort及typed内部合同 | quote、revalidate、confirm/query | food→核心；embedded本地适配、standalone远端适配 |
| food.service.DiningQuoteService/impl | quoteCart、quoteBill、revalidate | 餐饮授权/版本与源引用；不持有核心Service实现 |
| food.api.biz.BizQuoteController / mp.MpQuoteController | quoteCart、quoteBill | 放food-web，复用原身份、范围、权限和返回壳 |

**权威报价的编排**：先解析商品/人工调整并用相同内核得到优惠基数，再在事务外问原活动/券及PAY，取得绑定该基数的决定；把完整事实作为PricingInput再次调用同一内核并核对baseFingerprint。决策依赖支付方式时先冻结“支付工具选择”，再获取决策；出现循环依赖的规则组合直接不支持，不能反复扣到结果收敛。

### 1.7 API与前端调用合同

优先复用原目标API041/131；以下均为待评审的扩展，不声称已上线：

| 方法与候选路径 | 用途 | 关键输入 → 输出 |
|---|---|---|
| POST /biz/x/food/stores/{storeNo}/quotes | B端购物车试算（041） | cartNo/cartVersion、优惠选择、调价意图、付款计划 → quote |
| POST /mp/x/food/stores/{storeNo}/quotes | C端购物车试算（131） | cartNo/cartVersion、本人权益选择、获准线上付款计划 → 脱敏quote；禁止员工调价/现金 |
| POST /biz/x/food/settlements/{settlementNo}/quotes | 已下单后B端预结账 | settlementVersion、scopeHash、benefit/funding选择 → frozen-order quote |
| POST /mp/x/food/settlements/{settlementNo}/quotes | 顾客获准范围预结账 | scope/version、本人权益/线上工具 → 脱敏quote |
| POST /biz或mp/x/food/quotes/{quoteNo}:revalidate | 付款前/重新进入页面校验 | quoteFingerprint、源版本 → UNCHANGED或REQUOTE_REQUIRED及diff |
| 既有正式下单/结账pay入口 | 最终确认，不另加可随意调用的markConfirmed | quoteNo/quoteFingerprint、源CAS、幂等键 → 原操作意图及状态 |
| 内部 /internal/.../pricing/... | food调用核心报价权威 | typed command +可信源引用 +服务鉴权 → raw typed result；路径随核心服务归属审定 |

B/C分别定义Req；不能用同一个包含approvalRef/cash字段的“大DTO”仅靠页面隐藏。C端服务端派生买家与来源；B端代客使用原真实身份授权。quoteNo或HMAC不能代替范围校验，扫码仍按ORD-01当前台账/桌台成员规则。

**B端请求示例（非现有已实现API）**：

```json
{
  "cartNo": "FCRT01", "cartVersion": 8,
  "benefits": {"couponRefs": ["UC01"], "mode": "EXPLICIT"},
  "adjustments": [
    {"kind": "UNIT_PRICE_OVERRIDE", "lineRef": "L1", "componentRef": "BASE", "targetUnitMinor": 12120, "reason": "店长调价", "approvalRef": "AP01"},
    {"kind": "ORDER_WAIVER", "amountMinor": 30, "reason": "抹分", "approvalRef": "AP02"}
  ],
  "funding": {"storedValue": {"mode": "EXACT", "requestedMinor": 10000}, "cash": {"tenderedMinor": 6000}}
}
```

上述可编辑金额仅是受限意图，必须经原权限/审批/账户适用能力重新认定；浏览器不上传商品最终单价、券优惠额、已付金额或最终应收。购物车完整行由cartNo+CAS加载，废止原目标041中“cart与任意lines并列且无主从”的歧义。若保留即时行试算，单独定义quoteLines模式及源hash，不能混合覆盖购物车。

**报价响应关键形状**：

```json
{
  "quote": {
    "quoteNo": "PQ01", "sourceMode": "LIVE_CART", "cartVersion": 8,
    "state": "READY", "confirmable": true, "currency": "CNY", "minorUnit": 2,
    "receivable": {
      "originalGoodsMinor": 18680, "priceAdjustmentMinor": -680,
      "activityDiscountMinor": 1000, "couponDiscountMinor": 2000,
      "feeMinor": 230, "taxMinor": 0, "waiverMinor": 30, "receivableMinor": 15200
    },
    "funding": {
      "confirmedNetPaidMinor": 0, "pendingFundingMinor": 0,
      "plannedStoredValueMinor": 10000, "plannedOnlineMinor": 0,
      "cashDueMinor": 5200, "remainingAfterPlanMinor": 0
    },
    "cash": {"cashTenderedMinor": 6000, "changeMinor": 800, "shortageMinor": 0},
    "profileVersion": "FOOD_STANDARD_V1", "quoteFingerprint": "<sha256>",
    "economicsHash": "<sha256>", "expiresAt": "2026-10-22T12:02:00Z",
    "issues": [], "lines": [], "allocations": []
  }
}
```

示例金额完整闭合，lines/allocations为节省篇幅省略；真实READY响应不可省略金额行和完整分摊。税额0在本例表示可信含税/无新增税事实，不表示未知税统一按0。

**前端TypeScript调用样例**（基于平台现有http封装的合同示意，不是已交付SDK）：

```ts
async function refreshQuote(input: FoodQuoteRequest) {
  const requestVersion = ++latestRequest;
  const result = await http.post<FoodQuoteResponse>(
    `/biz/x/food/stores/${storeNo}/quotes`, input,
    { headers: { 'X-Store-No': storeNo } }
  );
  if (requestVersion !== latestRequest || input.cartVersion !== cart.version) return;
  quote.value = result.quote;
}

async function submitOrder() {
  const q = quote.value;
  if (!q || !q.confirmable || q.cartVersion !== cart.version || isExpired(q)) return;
  return orderApi.submit({
    cartNo: cart.cartNo, cartVersion: cart.version,
    quoteNo: q.quoteNo, quoteFingerprint: q.quoteFingerprint
  }, currentSubmissionKey);
}
```

以上http返回的是现有封装解包后的data；前端不得再减券、减储值或算另一份应收。选菜/选券/调价/付款工具改变时清除可确认标记，防抖150～300ms只是体验参数；用请求序号或AbortController丢弃乱序响应。时间仅用于展示，过期由服务端判定。提交期间同一命令保留原幂等键；重新确认新的经济版本生成新命令，不能复用旧键异参。源版本冲突先刷新购物车，不偷偷用旧行重新试算。

### 1.8 下单后仍复用同一逻辑，如何最终确认

| 阶段 | 数据源 | 计算与确认 |
|---|---|---|
| CART_PREVIEW | 当前获准购物车、当前有效菜单/SKU/优惠、余额与政策快照 | 同内核试算；quote有效期取所有依赖最早过期时刻，建议上限120秒 |
| ORDER_CONFIRM | 当前权威事实再取一次，重验购物车与审批 | 同内核重算，与用户确认quote比较；变化返回差异，不直接改金额下单 |
| BILL_QUOTE（先吃后付） | 已下单各批次冻结行价格、原分摊、退餐/结账事实；新增获准账单优惠单列 | 原已应用优惠不重放；账单级新增优惠按核心结算调整协议实施。餐前整账优惠仅为估计，不承诺数小时后仍有效 |
| PAYMENT_CONFIRM | 已接受且未过期/已按合同锁定的报价、当前账单剩余、当前资产与资金意图 | 同内核验证剩余与本次工具/现金找零；prepare可能竞争失败，不能用旧余额承诺扣款 |
| RECEIPT_VERIFY | 原实际付款/储值/券使用回执、金额币种、订单/源hash和状态 | 只核验并入账、核对守恒；不再用今天的活动重算历史订单 |

**最终确认协议**：

1. 权威事实/报价在本地写事务外加载；源版本与价格快照绑定。外部事实无法分布式原子读取时，依靠权威报价/预占令牌、源hash和prepare的版本条件，不声称“读完就跨服务锁住”。
2. 以源范围CAS和原幂等框架，在短事务内保存确认意图、冻结快照、Outbox；相同key同参返回原操作，同key异参拒绝。购物车进入SUBMITTING，不在Controller长事务里调用PAY。
3. 按能力协议准备券与储值等权益、库存及核心订单。只保存明确的原指令/预占号，不把“调用未抛异常”视为资金成功；准备失败释放已准备项，存在UNKNOWN时查询原号，禁止直接重建另一笔。
4. 冻结当前实际采用的couponQuoteRef、fundingOffer、profile与line分摊。若权威prepare返回变化，先中止/恢复原意图，再要求重新确认；不在用户不知情时改用另一张券/多扣现金。
5. 用户付款与现金确认沿原支付权威/班次凭证。commit与订单确认采用原准备/决策/恢复协议，所有步骤有幂等查询与重扫；混合支付部分成功不标全单成功，UNKNOWN不允许关单或第二次收取该范围。
6. 最终结清需：每条应收份额支付覆盖、各币种守恒、无未知资金/权益状态、所有原决策完成且核心确认。不能只比较“计划支付之和”等于应收。

同一逻辑不是冻结后不停重定价：已成功的原操作重放返回原结果，不拿当前价格作比较；未执行的新操作才按本阶段允许的快照重新确认。已付金额被改小、退菜或费项变化，产生新调整/退款版本，不把超收余额误当现金找零。

### 1.9 报价与冻结存储（候选，不执行DDL）

所有表沿BaseEntity、主体/门店/tenant语义和现有库，仅新建报价核心数据，不新建资金账本。建议三张核心ord_表；是否直接复用现有冻结/授权载体须在实现计划再次核对。

| 候选表 | 字段及约束重点 | 作用 |
|---|---|---|
| ord_price_quote | quote_no唯一、entity_no/store_no、buyer_ref、source_kind/source_no/source_revision、scope_hash、currency、profile_version、rule_set_hash、quote_fingerprint、economics_hash、receivable_minor、status、expires_at、snapshot_schema、facts_snapshot JSON、result_snapshot JSON | 不可变报价事实；状态以原意图关联/有限CAS推进，不能原地改报价金额 |
| ord_price_quote_line | quote_line_no、quote_no、source_line_ref、parent_line_ref、component_ref、goods/sku refs、qty DECIMAL、original_minor、adjusted_minor、net_minor、pricing_snapshot JSON；quote+source/component唯一 | 支持父菜/小料/套餐叶子行，保存冻结身份与计价，不用物理id作外部引用 |
| ord_price_quote_allocation | allocation_no、quote_no、source_line_ref、component_ref、kind、benefit_ref、funder_ref、amount_minor、policy_version、source_ref/hash；确定性业务唯一键 | 优惠、抹零、费用及计划资金分配的可追溯依据；计划资金不是储值已扣流水 |

`fnb_settlement.quote_ref`指向核心报价；`fnb_charge_intent`沿原请求快照/核心引用；已下单的核心行、退款分摊与原支付/储值流水各守原职责。若确认绑定没有合适原字段，新增关联字段/表必须通过后续新迁移评审，不能改旧迁移或给任意JSON暗塞无法查询的结清关系。

QUOTE的存储不会冻结远端余额；保留quote并不代替prepare。快照中只保存必要身份引用/脱敏展示，不保存会员码、明文token、支付密码。分离`economicsHash`、`sourceHash`及包含权限/范围/有效期的quoteFingerprint，不能把日志时间放进确定性金额hash。

## §2 详细实施顺序（供确认后执行）

| 顺序 | 范围 | 文件/合同落点 | 验收重点 |
|---|---|---|---|
| QP-01 | 先冻结金额口径与算法 | 本TDD、Money/Quantity/Rate、PricingProfile、PricingInput/Result | 示例及各币种精度、溢出、每步分摊守恒，纯Java测试 |
| QP-02 | 纯引擎基础与餐饮事实 | common.pricing策略、food source resolver、核心source SPI | 套餐/小料不重复计价、菜单修订和源行对应、确定性排序 |
| QP-03 | 原活动/券接线 | 原CouponPort/EntitlementUsePort适配与baseFingerprint | 保持活动后门槛和原分摊，拒过期/异源/不支持叠券；不核销 |
| QP-04 | 储值合同扩展 | 原PAY来源、部分支付offer、资产版本、prepare/query/release | 真实餐饮来源＋CNY/费用/批次范围，竞争与UNKNOWN恢复；缺能力BLOCKED |
| QP-05 | B/C报价与前端SDK | API041/131扩展、Bill quotes、revalidate、严格DTO及typed内部合同 | B/C权限分型，乱序丢弃、失效与差异确认；沿原返回壳和JSON金额合同 |
| QP-06 | 正式下单确认 | 核心报价快照三表候选、新迁移、ORD-03准备/确认意图 | 同一内核复算，源CAS、原键恢复、Outbox及故障补偿；不改零售历史金额 |
| QP-07 | 后付、混合收款、现金与AA | 原结账冻结、应收调整、钱包/现金原支付权威、班次 | 已付不重算、优惠只一次、现金找零、最后份额抹零、禁止双收 |
| QP-08 | 退餐与对账 | 原退款分摊、储值本金赠金反向、报表引用 | 历史冻结规则、原路退、退款与再收费分离、净收入/资金分列 |

先完成QP-01～03的可核验试算，不因此宣称储值、混合资金或独立部署交易已贯通。QP-04～07的合同门禁是明确交付任务，不以模拟成功隐藏。

## §5 验证计划与当前证据

当前只做源码核对、设计和文档自检；本篇Java/TS接口、候选表和计算引擎**没有实现或执行测试通过的声明**。以下为实施时必须落地的测试，而非当前已有测试名：

| 测试组 | 必须覆盖 |
|---|---|
| 定点/性质测试 | 同输入确定性；顺序规范；0金额；0/2/3位币种；BigInteger乘法；long及JS上限；数量精度；分摊总和/非负/上限；不重复优惠 |
| 餐饮定价 | 早餐/晚餐/渠道价；相同SKU不同做法；小料组合；父套餐价与补差；收费项被调价时作用域清楚；当前COUNT不伪支持称重 |
| 优惠权益 | 活动后券门槛；原券PERCENT语义；资方/原分摊；叠券能力；换支付方式；买家/同桌数据隔离；过期券不静默替换 |
| 储值 | 仅CORE/SERVICE_USE现合同拒餐饮；新来源合法/伪造拒绝；本金/赠金/持有/门店限制；部分余额/跨币种；并发消费；报价后冻结/撤销 |
| 现金/抹零 | 15200应收、储值10000、现金收6000，找800；现金不足；无现金但电子超付不找零；支付后降价走调整退款；分付最终片段抹零一次 |
| 前后确认 | 价格变、金额相同但券/资方变、源版本/审批过期、已有支付/UNKNOWN变化；不同hash需显式差异确认 |
| 正式事务与恢复 | 双端提交同源一个赢家；同key异参拒绝；多服务prepare部分成功、UNKNOWN、乱序回调；失败后原指令查回；已成功重放不重算当前价 |
| 账单/AA/退款 | 冻结批次不按现价；源行部分结账限额；优惠与分摊只一次；全退/部分退/赠金退回；不得通过小份额连续抹零套利 |
| 部署与消融 | embedded与standalone同向量结果；纯引擎无IO；移除金额/来源hash校验、当前资金范围或现金守卫时对应测试必须变红 |

## §6 设计取舍与对账

- 本轮新增本TDD及架构SVG，不修改商品、订单、PAY代码，不创建表，不改canonical API既有实现标记。后续确认范围后再同步机器真源和实施代码清单。
- 在现有shop-base新增common.pricing包，而非首期单独部署引擎服务；金额结果仍由原核心报价权威确认，food部署方式不改变这一点。代价是核心增加一个清晰门面及共享纯计算对象。
- 不在本轮抽空4000行OrderServiceImpl；先为餐饮提供经批准的新合同、向量和旁路验证，再单独评估零售迁移，避免隐性改变活动/积分/旧券行为。
- 固定默认顺序和版本化策略，保留原活动/券分摊；不采用任意规则脚本/DAG重排，也不把“优雅”变成大量空接口和链节点。
- 前端仅调用Java API已由用户确认。断网可继续编辑本地草稿，但本篇不提供前端权威定价、余额承诺或离线收款确认。
- 税费具体规则、经营抹零权限/上限及可用储值/券叠加能力由原商户政策及权威配置输入，不臆定一个全平台统一金额。缺配置/缺合同时显式不可确认。

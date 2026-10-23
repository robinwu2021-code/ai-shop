# 订单结账CKO-07：地区税与最终定税

> 状态：纯税内核已实现 · 2026-10-22 · 档位2 · 基线d3f3099b5。依据[完整计划CKO-07](./TDD-餐饮订单结账-完整开发计划.md)、[税费设计](./TDD-统一试算引擎-初始规则与结账税费.md)及[ADR-040](./ADR/ADR-040-餐饮销售配置与耗用权威边界.md)。需求沿用餐饮结账、地区税、积分、抹零及AA原税证据，不建立新商品、订单或税申报权威。

## L1 定位

本轮实现无IO税内核，输入由可信owner适配层接纳的地区/登记/税点、源价格、税类和逐行调整分类。行业规则与审批仍归CKO-04；税分类不能替代优惠资格或审批。种子税档仍是待平台审阅的示例，不自动发布。

## L2 边界

分别输出预估和最终结果，税票总额invoiceGross与客户待安排金额customerDue分离。储值及其他付款计划不减少税基、不列对价抵付；现金实收找零在CKO-17。本轮不开放API、建表或切换零售链路。CKO-05继续明确输出税前候选；税后最优比较需后续报价编排接入最终税评估，不能直接使用税前最优充当税后最优。

## L3 设计与实施

## §0 需求 → 设计

| AC | 要求 | 落点 |
|---|---|---|
| T01 | 地区/供给地/登记/上下文、税点和政策有效区间匹配，未知阻断 | TaxPolicy、TaxEngine.Context |
| T02 | 含税/未税、标准/零税/免税/范围外明确；未知税类阻断 | TaxClass、PriceTaxMode、TaxEngine |
| T03 | 同一原发票行汇总源份额后仅舍入一次，不按券或AA创造新行 | TaxLineResult、TaxEngine |
| T04 | 每笔owner/资方/地区分类证据，价格减免与对价抵付分开 | TaxTreatment、TaxAdjustmentResolver |
| T05 | 积分/抹零精确调整，不能表达的gross减免阻断 | 有界整数逆算、调整轨迹 |
| T06 | 最终定税不重复减积分，保护已固定储值/付款计划 | TaxResult、finalizeTax |
| T07 | 8个JSON向量、定点安全和税证据不可变 | 2个测试类及TaxTestData |

## §1 合同与公式

金额沿MoneyMinor；所有价格/减免非负、同币种且JSON安全，中间乘法BigInteger。税率基点0～10000；STANDARD必须正率，ZERO_RATED必须0，EXEMPT/OUT_OF_SCOPE率为null。政策显式提供版本、国家/区域、币种、有效起止Instant、允许价格模式/业务上下文及算法，不默认AE或免税。税点必须提供；预估可ESTIMATED，正式结果必须CONFIRMED，有效区间为[from,to)。Instant由来源适配转换，不在引擎猜当地日期或营业日。

发票行：EXCLUSIVE tax=HALF_UP(net×bp/10000)，gross=net+tax；INCLUSIVE tax=HALF_UP(gross×bp/(10000+bp))，net=gross-tax。同invoiceLineRef的不同源份额先合计，税类/价税模式及owner提供的完整invoiceBase必须一致，源价合计必须精确等于完整invoiceBase；部分AA范围不能再次计原行税。重叠源份额拒绝。invoiceBase必须取原发票行owner，不能把请求份额合计自报为原行总额。

价格减免在原价格模式下变更基数；对价抵付保持税基，仅减少customerDue。输入调整必须显式声明金额量纲、owner/资方/分类证据、税档版本和原发票行。储值只能在FundingPlan出现，不能伪装优惠或对价券。

EXCLUSIVE的PAYABLE_GROSS减免使用单调整数二分逆算目标gross，至多54次，精确相等才接纳。INCLUSIVE的TAX_EXCLUSIVE减免暂拒绝：舍入可能存在多个gross对应同net，现有合同没有选择依据，要求owner换成获准gross报价；不猜隐含税差。保留调用方批准顺序，不重新计算折扣率或改变实际经济金额。百分比券的实际金额也可在owner/地区明确分类后作为对价抵付，不能因券名强制减税基。

invoiceNet+invoiceTax=invoiceGross；customerDue=invoiceGross−considerationOffsets；customerDue=storedPlan+otherPaymentPlans+unassignedDue。最终gross−对价抵付不足已固定付款计划时整笔拒绝，不缩减储值或反复择优。纯函数可用相同冻结输入复算；每次调用只对固定完整计划产出一次最终税结果。

AA沿最终TaxResult.allocateInvoiceLine(invoiceLineRef, List<Share>)处理：完整名单gross合计须等于原冻结行gross，shareRef唯一；用原CouponCalculation.allocate按gross权重分配原tax，net=gross−分到的税，保留原政策/税点/发票行。此为税额分配，不是重新产生税票，也不授权AA收款或改原优惠。名单应由后续范围owner冻结，不在每次新增付款人时重新分配已付税额。

## §2 代码与执行计划

新增生产类全部在`backend/shop-base/src/main/java/ai/neargo/shop/common/pricing/`：

| 类 | 公开职责 |
|---|---|
| TaxClass、PriceTaxMode、TaxTreatment | 有限枚举，UNKNOWN显式阻断 |
| TaxPolicy | 不可变地区/版本/有效区间/税类政策 |
| TaxLineResult | 原发票行身份、源份额、税前/税/含税金额证据 |
| TaxEngine | estimate(lines,policy,context)→ProvisionalTaxResult；finalizeTax(adjusted,policy)→TaxResult；嵌套Context/Line/TaxFailure |
| TaxAdjustmentResolver | resolve(provisional,adjustments,funding,policy)→AdjustedLines；嵌套证据/调整/资金计划/轨迹 |
| TaxResult | 冻结政策、税点、行/分类汇总、抵付、固定付款与未安排金额；allocateInvoiceLine分配原税至完整AA名单 |

新增测试在`backend/shop-base/src/test/java/ai/neargo/shop/common/pricing/`：TaxEngineTest、TaxAdjustmentResolverTest、TaxTestData。更新本收据、原计划、存储Java实施方案及税费设计的状态/对账；不改配置JSON种子。

- [x] 先写含税/未税/混合税类/原行舍入及政策拒绝测试，运行确认缺失/RED。
- [x] 最小类型和税引擎实现，验证GREEN。
- [x] 调整/精确逆算/固定储值/对价测试RED→GREEN，运行8个原JSON标准向量。
- [x] 消融、完整回归、架构检查、评审与范围明确的提交。

## L4 验证与交付

## §5 验证记录

首轮算术空实现：8项中7项断言失败、0错误（不可变集合检查已由值对象满足）；实现估税后，18项中11项断言失败、0错误，确认调整/最终税缺失。最小实现后18项通过。补强真实种子税档、税后对价分类、整数逆算穷举、报表分类后继续验证。

评审反例：部分AA单独估税、AA原税分配及百分比券对价抵付分别确认RED（各1项断言失败、0错误）后修复；完整基数/冻结税分配与券分类复查无新增重要问题。8向量读取原JSON的calculationVectors及regionalTaxProfiles，不复制另一份税率/价税模式预期。

| AC | 对应测试方法 |
|---|---|
| T01 | supplierPlaceCurrencyAndLegalContextMustMatchPolicy；policyValidityIsHalfOpenAtTrustedTaxPointAndFinalRequiresConfirmedPoint；regionSpecificPolicyCannotLeakToOtherSupplierOrSupplyRegion |
| T02 | inclusiveExclusiveMixedClassesAndLineRounding；noPolicyOrUnsupportedModeAlgorithmOrRateDefaultsToZeroTax；invalidRateSemanticsCannotPretendToBeZeroOrNonTaxable |
| T03 | promotionalAndAaPortionsDoNotCreateNewTaxRoundingLines；partialAaScopeCannotRecalculateOriginalInvoiceTax；aaAllocatesFrozenOriginalTaxRatherThanRoundingEachPersonsNetAgain |
| T04 | unknownClassificationWrongProfileAndStoredAsDiscountAreBlocked；classificationKeepsOriginalFunderSharesAndFrozenEvidence；percentCouponCanSettleConsiderationOnlyWithExplicitOwnerAndRegionalClassification |
| T05 | exactExclusiveGrossInverseRejectsAnUnrepresentableTarget；exactGrossInverseMatchesAllRepresentableSmallIntegerTargetsAndRejectsEveryGap |
| T06 | pointsPriceReductionThenWaiverAreNotDeductedTwice；pointsConsiderationAndStoredPaymentDoNotReduceTaxOrDoubleCountStored；exclusiveNetDiscountCanIncreaseGrossReductionAndCannotInvadeFixedStoredPlan；laterPriceReductionCannotConsumePreviouslyOffsetConsideration |
| T07 | allEightOriginalSeedVectorsExecuteAgainstTheSameTaxImplementation；largeIntermediateMultiplicationIsExactAndUnsafeTotalsFailWithoutPartialOutput；policyAndEvidenceCollectionsAreDeeplyImmutable；frozenPolicyCannotBeReplacedBetweenEstimateResolveAndFinalize |

消融：分别关闭完整发票行基数保护、把税舍入改成直接截断、将对价重复扣一次，针对各自测试均得到1项断言失败、0错误；全部原代码已恢复。最终全量离线回归BUILD SUCCESS：auth-store34、job-api11、svc-client10、shop-base228、base-auth28、store-mybatis38、shop-core414，均0失败/错误/跳过。其中新增TaxEngineTest 11项、TaxAdjustmentResolverTest 15项，共26项。

ArchitectureTest为16项中15通过、1项既有失败：noFullyQualifiedReferences指出OpsPointsInspectionController的全限定PathVariable。该文件相对基线HEAD无改动；不修改既有守卫或无关Controller消除历史问题。

复现（Java21，本机测试HTTP端口及Mockito附加代理需允许）：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -f backend/pom.xml -pl shop-core -am test
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
```

第二条会复现上述历史失败。实际日志在本机临时目录`/private/tmp/cko07-full-core-suite.log`、`/private/tmp/cko07-architecture.log`；三个消融日志为`cko07-ablation-invoice.log`、`cko07-ablation-rounding.log`、`cko07-ablation-offset.log`，不作为仓库永久附件。

## §6 实施裁决及对账

CKO-06 BUSINESS_PRE_TAX按业务分类扣过的余额不能直接作为税基，否则会把对价券和储值误当价格减免。本轮税引擎从原价格事实＋完整owner逐行税分类计划计算；调用方必须将同一CKO-06步骤/资方/金额映射到本合同并核验一致，正式跨阶段适配仍随报价编排交付，不能拿前端金额替代原证据。

核对官方资料：阿联酋财政部[VAT](https://mof.gov.ae/en/public-finance/tax/value-added-tax-vat/)及FTA[VATP006](https://tax.gov.ae/DataFolder/Files/Pdf/06-Tax-Invoices.pdf)支持标准5%与行级最近fils舍入；不代表种子税档已取得平台发布资格。实际法律版本有效性与商品分类由后续受控税档owner提供。


### 6.1 实施裁决

原算法种子名AE_LINE_FILS_V1作为显式受限别名，仅接受AE/AED；通用有限算法名VAT_INVOICE_LINE_HALF_UP_V1依受控输入政策运算，不声明已覆盖所有国家。种子日期→UTC仅用于测试夹具，不是生产法律日期转换规则。

TaxSummary落为TaxResult.Summary、ProvisionalTaxResult落为TaxEngine嵌套输出，保留原规划职责，避免再拆等义顶级DTO；接口增加必填Context以承载原规划未在两参数签名中表达的税点/地区事实。

完整invoiceBase是owner提供的原行总价证据，不是本次范围合计；它与价格源身份一起由未来适配层验证。纯构造器不证明来源真实性。AA分配方法新增在TaxResult，避免后续应用只能通过重新估税拆出AA金额。


### 6.2 参数及产物对账

| 输入/输出 | 字段与约束 |
|---|---|
| TaxPolicy | policyNo/version、countryCode/regionCode、currency、effectiveFrom/effectiveTo、priceMode、allowedContexts、rates、algorithm、evidenceRef；不可变、显式提供 |
| Context | supplierCountry/supplierRegion、placeCountry/placeRegion、supplierTaxRegistrationRef、contextCode、taxPoint/taxPointStatus、sourceSnapshotHash/sourceVersion |
| Line | source:PricingUnit、taxClass、priceMode、invoiceBase:原完整发票行价格；最多1024个源份额 |
| ProvisionalTaxResult | policy/context/lines、invoiceNet/invoiceTax/invoiceGross；仅engine可构造，ESTIMATED不允许正式定税 |
| Evidence | asset:AssetRef、kind:BenefitKind、treatment、amountBasis、funderRef、evidenceRef、policyNo/policyVersion |
| Adjustment | evidence、invoiceLineRef、amount:MoneyMinor；最多256项，原owner顺序，重复asset/kind/line/funder拒绝 |
| FundingPlan | storedPlan、otherPaymentPlans；同币种固定金额，不含找零实收 |
| AdjustedLines | provisional、lines、traces、funding、considerationOffsets；仅resolver可构造，不接受再次调整一个最终结果 |
| Trace | adjustment、before/after行、netEffect/taxEffect/grossEffect；有符号变化，对价抵付税影响为0 |
| TaxResult | policy/context、lines/traces、funding、invoiceNet/Tax/Gross、considerationOffsets、customerDue、unassignedDue、summaries（税类/税率/net/tax/gross） |
| Share / InvoiceShare | 输入shareRef/gross，完整名单最多64项；输出原policy/context/invoiceLine及各份net/tax/gross，不产生新税点 |
| TaxFailure | 内部code、无金额payload；结构错误也可抛IllegalArgumentException，整次调用不返回部分价格；正式API错误码/i18n在对应应用任务登记 |

实际共15个文件：8个新增生产类、3个新增测试/夹具、本文及3份既有设计/计划更新；未改现有生产类/POM/种子JSON/API/数据库。下一步CKO-08：4张配置表与发布事务，并以真实目标数据库验证约束和原子性。

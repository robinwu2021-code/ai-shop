# 统一试算引擎：初始规则、结账阶段与地区税费

> 状态：设计提案，未实现 · 2026-10-22 · 档位2。补充[存储与Java实施方案](./TDD-统一试算引擎-存储与Java实施方案.md)，本篇覆盖规则初始数据、订单结账逐阶段执行及地区税费；与早期简化公式冲突时，以本篇税基分类和最终定税口径为准。沿用[ADR-040](./ADR/ADR-040-餐饮销售配置与耗用权威边界.md)，不新增税务申报或资金账本。
>
> 数据真源：[pricing-initial-data.example.json](./design/schema/pricing-initial-data.example.json)；总规范：[pricing-rule-design.json](./design/schema/pricing-rule-design.json)，升级为PRICING_RULE_SPEC_V4。都是设计示例，不能直接执行为数据库初始化脚本。

## L1 结论

初始数据由**12条共同优惠规则＋8条税务规则＋3个行业模板＋地区税档案＋门店覆盖示例**组成。规则全部组合校验，通过后才计算；金额采用同一纯Java内核试算和重验。税费按“地区、供应方资格、交易税点、税类、价格含税方式”确定，不能只有一个门店taxRate字段。

阿联酋标准VAT为5%；税前100 AED形成105 AED含税应收。面向消费者的公示价格原则上含税，已标价105 AED时拆出5 AED VAT，不再次加5%。这两种计算均支持，地区模板控制展示及适用条件。[阿联酋财政部税率说明](https://mof.gov.ae/en/public-finance/tax/value-added-tax-vat/)、[FTA实施条例第27条](https://tax.gov.ae/DataFolder/Files/Pdf/Executive%20Regulation%20of%20Federal%20Decree%20Law%20No%208%20of%202017%20-%20Publish-new-2.pdf)

## L2 全流程

| 阶段 | 做什么 | 结果 |
|---|---|---|
| 事实 | 身份、购物车/原账单、未付份额、价格及税点 | PricingFacts |
| 规则 | 结构编译、资格、数量、互斥、份额、资金能力、税分类 | 合法CalculationPlan或明确拒绝 |
| 价格 | 逐行价格纠错、合法优惠、费用、预估税额 | 税前/含税阶段金额 |
| 结账 | 储值计划→积分→人工抹零→最终定税 | 固定应收、税分摊、资金计划 |
| 报价 | 校验守恒并保存头/行/分摊 | 不核销的quote及版本指纹 |
| 确认 | 冻结范围、重验并复算、比较差异、原资产/PAY协议执行 | 原意图结果及资金证据 |
| 凭证 | 打印、报表及原分摊售后 | 经济与税务事实可追溯 |

规则校验、金额计算、资金执行是三件事。计算通过不代表资产已核销，支付成功也不触发按新税率重算旧凭证。最终税额会受有合法依据的价格减免影响，所以“积分/抹零最后”是优惠决策顺序，后面仍需完成税额确认与守恒校验。

## L3 详细设计

## §0 需求 → 设计

| AC | 要求 | 落点 |
|---|---|---|
| N01 | 有详细、可审查的初始规则数据 | JSON的systemRules、industryTemplates、storeDraftExamples |
| N02 | 逐阶段给出校验、试算、计算和确认 | §2.3阶段表、§2.4调用及状态协议 |
| N03 | 地区税率可调整，阿联酋标准VAT 5% | regionalTaxProfiles、TaxProfileResolver、平台受控发布 |
| N04 | 不重复加税，优惠、积分、抹零税基正确 | TaxTreatment、TaxEngine预估/最终定税、精度及算例 |
| N05 | 打印、报表、退款及AA不丢失原税证据 | 报价税字段/分摊、原打印与退款接口 |

## §1 基础实现及影响

继续使用原7表，税档案嵌入行业不可变发布定义、合并到门店有效配置及报价快照。暂不为税率、税类、每条规则各建表。若以后独立税务配置域成为原权威，再以唯一Port取版本化快照，不让订单和税务域并行维护两套税率。

本次在`shop-base/common.pricing`与`shop-core/trade`拟新增税计算职责；B/C端报价补充税前、税额、含税及抵付显示。商品原SKU不直接改造为地区税率主表，使用原商品/类目身份和受控税类映射；缺真实商品税类合同必须阻止对应交易确认。源码限定搜索未发现可直接复用的销售VAT策略实现，不能把结算域的同名tax字段直接当销售VAT。

## §2 方案

### 2.1 初始数据的结构与落库

JSON包含：

| 区块 | 内容 | 落库方式 |
|---|---|---|
| importPolicy | 默认DRY_RUN、不自动发布、不升级存量门店 | 初始化命令约束，不保存成门店规则 |
| systemRules | B01～B12、T01～T08，ruleNo/version/evaluatorType/parameters | 代码有限目录＋发布definition中的规则引用；不能用种子JSON创建任意Evaluator |
| industryTemplates | FOOD、RETAIL、BEAUTY_SERVICE各一份完整初始定义 | `ord_pricing_template`草稿，评审后按原发布协议写revision |
| regionalTaxProfiles | AE消费者含税、AE内部未税基价/合法例外两份档案 | 定义引用＋编译时完整嵌入definition_snapshot/effective_snapshot，不依赖可变外部JSON |
| storeDraftExamples | 阿联酋门店税档绑定、多折扣券分项覆盖 | 仅示例；真实门店显式选择后写config草稿及门店revision |
| ownerCapabilityFixtureExamples | 测试提供方支持/不支持能力 | 仅测试夹具，不导入为真实支付/券能力 |
| calculationVectors | 8个金额和税费预期向量 | 内核与未来前端一致性测试，不生成真实订单 |

导入拟新增`PricingSeedService#importDrafts(seed, expectedRegistryVersion, mode)`，实现放`trade.service.impl`。流程为：校验seed/schema/规则目录→规范化hash→预览差异→只创建或CAS更新目标草稿→平台单独发布。不自动启用余额、核销卡或开通税登记。重复导入同版本同hash不变更；同版本不同hash拒绝，需新seed版本。真实发布版本No及审计由现有BizKey/登录态产生，不能将示例文本当作生产No或伪造操作人。

#### 共同默认规则

| 编号 | 初始化含义 | 门店调整 |
|---|---|---|
| B01 | 实际使用活动不能再用券 | 不可放宽 |
| B02 | 任意折扣券不能与储值同时使用 | 不可放宽 |
| B03 | 合法组合还须每个原提供方允许，基数顺序一致 | 只能收紧 |
| B04 | 折扣券默认最多1张，受全单最多2张限制 | 可在真实能力内修改 |
| B05 | 金额券默认最多1张；现金/金额抵扣券受分类及合计上限 | 可在真实能力内修改 |
| B06 | 多折扣券默认DISJOINT，同计价份额最多1张 | 可选BEST_ONE；STACK专项验收后启用 |
| B07 | 活动、券、储值或积分存在时不再人工打折/扣减 | 不可放宽；纠错不能伪装成促销 |
| B08 | 用任何券后禁人工抹零/现金舍入调整 | 不可放宽；不禁止实际现金找零 |
| B09 | 活动/金额券加储值需真实来源及混合能力 | 能力不足拒绝 |
| B10 | 储值计划后积分、最后抹零，不得侵入已付/已安排范围 | 固定阶段 |
| B11 | 人工操作须原因、权限、额度及必要审批 | 原因与受控额度可配 |
| B12 | 金额/币种/份额守恒，原根范围券数不因AA拼桌重置 | 不可关闭 |

优惠参与资格和税资格是两个维度：不参加活动的菜品仍收费，也可能照常适用VAT；不能拿活动排除列表当免税列表。

三行业新模板都默认`total=2, percent=1, amountTotal=1`，可用1张折扣券＋1张金额券，同项不叠折。餐饮及零售默认MULTI_KIND；美业默认SINGLE_KIND，但COUPON种类内部仍允许上述组合。积分保留`pointsEnabled=true, pointsAutoApply=false`政策意图，**实际可用性还要通过来源/owner/地区税分类能力门禁**；未接线不展示可用积分，用户明确请求则BLOCKED。储值默认关闭；美业已有全额储值另沿原协议，不因新种子改成部分混合。

#### 完整门店覆盖例子

```json
{
  "templateNo": "PRICING_FOOD_DEFAULT",
  "templateRevision": 1,
  "override": {
    "couponLimits": {"total": 3, "percent": 2, "amountTotal": 1, "cash": 1, "deduction": 1},
    "discountCouponPolicy": {
      "maxUsedPerOrder": 2, "overlapMode": "DISJOINT",
      "maxUsedPerItem": 1, "stackingEnabled": false
    },
    "taxBinding": {
      "taxProfileNo": "AE_VAT_CONSUMER_V1",
      "resolveFrom": "VERIFIED_STORE_REGION_AND_TRANSACTION_CONTEXT",
      "missingPolicy": "BLOCK", "storeMayEditRate": false
    }
  }
}
```

解释：最多2张折扣券分菜项＋1张金额券；不是同项连打两折。门店在已核验地区和资格下绑定税档，不提交任意`vatRate=0`。数组整体替换、缺省继承、null拒绝、恢复继承删除覆盖键，仍沿原配置协议。

### 2.2 地区税规则

#### 税档字段

| 字段 | 例子 | 来源与约束 |
|---|---|---|
| taxProfileNo / profileVersion | AE_VAT_CONSUMER_V1 / 1 | 平台验证后发布的唯一版本 |
| countryCode / regionCode? | AE / 受控区域 | 供应方/供给地事实，不取设备GPS或请求自报 |
| supplierTaxRegistrationRef | 原主体税登记引用 | 原经营主体档案，不能由门店开关伪造 |
| taxType / taxClass | VAT / STANDARD | STANDARD、ZERO_RATED、EXEMPT、OUT_OF_SCOPE分别记录 |
| rateBp | 500 | 5%；ZERO_RATED为0；EXEMPT/OUT_OF_SCOPE为null，保留不同原因 |
| priceTaxMode | INCLUSIVE / EXCLUSIVE | 该源价格是否含税，不由UI临时切换改变价格事实 |
| consumerDisplayMode | INCLUSIVE | 阿联酋消费者默认含税展示 |
| effectiveFrom / effectiveTo / taxPoint | 有效期间/可信税点 | 按合法税点匹配版本，不简单等于支付时刻或营业日 |
| roundingScope / roundingMode | INVOICE_LINE / HALF_UP | 当前AE档按发票行数学舍入到fils，并保存算法版本 |
| adjustmentTreatment | REDUCES_TAX_BASE / SETTLES_CONSIDERATION | 由原优惠/资金性质和地区策略核验，不按券名称猜测 |
| allowedContexts / evidenceRef | 内部未税基价、获准例外等 | 决定EXCLUSIVE输入能否使用及对外如何展示 |

阿联酋标准税率5%不是“所有商品统一5%”：原税类可能为零税率、免税或范围外；未知类别不能默认为0税。[FTA税类说明](https://tax.gov.ae/en/faq.aspx?keyword=Does+VAT+apply+to+all+goods+and+services)

地区可调整的是平台验证的税档、税率、有效期及适用条件；门店只在所属地区、真实资格范围选择/申请税档。不能为了地区配置灵活性允许收银员修改法定税率。新地区未配置则`TAX_POLICY_MISSING`，不默认为阿联酋5%或无税。

模板锁版不能用来延长已失效的法定税率：平台法律版本有效性门禁按实际taxPoint拦截过期配置并要求升级。已冻结税点及税证据不随今日配置改变；尚无确定税点的报价标记ESTIMATED，正式确认按原业务提供的真实税点重验。先付、后付、预收/押金由来源和原税务合同提供税点，不能凭“先吃后付”统一推迟税务责任。

#### 税前与含税公式

对同一发票行、同一税类，用整数中间计算并明确数学舍入：

```text
价外税 EXCLUSIVE：tax = round(net × rateBp / 10000)
                 gross = net + tax
价内税 INCLUSIVE：tax = round(gross × rateBp / (10000 + rateBp))
                 net = gross - tax
订单税额 = Σ最终发票行tax；含税总额 = Σ(net + tax)
```

5%价内税相当于含税金额×5/105，不是含税金额×5%。税率采用基点的版本明确限定可表达精度；未来地区需要更高精度时升级有理数合同，不截断税率。[FTA加税/拆税公式](https://tax.gov.ae/DataFolder/Files/Pdf/Taxable-Person-Guide-June-2018.pdf)

舍入采用原地区及发票行口径，不为凑整单5%修改已确定的行税。例如两行未税各0.10 AED，各行税0.005→0.01，税合计0.02，而不是把0.20×5%得到0.01替换原行结果。AE档在本方案中固定行级数学舍入；未来不同发票类型必须明确独立政策后启用。[FTA发票舍入说明](https://tax.gov.ae/DataFolder/Files/Pdf/06-Tax-Invoices.pdf)

#### 优惠、积分及抹零如何影响税

| 输入 | 应收/资金性质 | 税处理 |
|---|---|---|
| 商家承担、符合条件的真实价格优惠 | 降低销售对价 | 分摊到原发票行后减少税基并重算税 |
| 平台/第三方补贴、已付对价券等 | 是否减少税基不能看名称判断 | 读取原资方/对价事实；合法保税基时记独立对价抵付，不再重复记降税基优惠 |
| 储值/现金/电子支付 | 支付方式 | 不因选择支付方式再次减税基；预付发行/兑换的税点沿原合同 |
| 积分 | 可能是降价，也可能构成对价抵付 | 必须有owner＋地区分类；UNKNOWN阻止本次使用 |
| 人工抹零/扣减 | 若获准形成价格减免 | 有税务分类、逐行分摊和审批后确认最终税；现金少收不能自动改成合法税前折扣 |
| 实际现金找零 | 退还多收现金 | 不改变应收和税额 |

阿联酋折扣和voucher存在具体条件，包括优惠受益与供应方承担等；因此“所有券/积分先扣了再对余额×5%”不能通用。[财政部实施条例第28条](https://mof.gov.ae/wp-content/uploads/2024/10/Executive-Regulation-of-Federal-Decree-Law-No-8-of-2017.pdf)

本方案将优惠的冲突类型和税务类型分开：同样名为现金券，仍参与券数及互斥校验，但只有经核验的降价部分进入`coupon_discount_minor`；构成对价的部分记`voucher_consideration_minor`和独立分摊。没有原清算/抵付合同，不能仅以“保留税基”之名宣布第三方已经付款。

### 2.3 订单结账逐阶段实现

| 步骤 | 输入/来源 | 规则校验 | 计算及输出 | Java职责/副作用 |
|---|---|---|---|---|
| S00 请求及身份 | 登录态、cart/check/scope引用、意图 | B/C权限、门店、本人资产、幂等body | 规范化请求hash | PricingAccessPolicy；无扣款 |
| S01 来源与范围 | 原购物车/冻结批次、根账单及版本 | 未付范围，排除已付/UNKNOWN；拼桌AA累计身份 | PricingFacts及可计算份额 | PricingSourceResolver/来源Port；报价不锁长期资源 |
| S02 配置编译 | 门店revision、模板版本、税档及能力证据 | schema、强制规则、上限、行业来源、税档有效性 | EffectivePricingConfig，完整锁版引用 | PricingConfigCompiler；失败不进入金额计算 |
| S03 基价及纠错 | 原SKU、渠道/时段价、小料、套餐、纠错意图 | 原价格版本、父叶不重复、纠错权限与税类 | 每个计价份额B及优惠/储值/积分独立资格 | BasePricePolicy、PricingAdjustmentPolicy |
| S04 优惠计划 | 原活动/券报价、用户显式或候选模式 | B01～B09、原资格/门槛、数量/重叠/顺序、资方、税分类 | 唯一合法Plan及used/unused；EXPLICIT冲突不静默择券 | PricingRuleEngine、BenefitPlanSelector |
| S05 商品优惠金额 | 基价、Plan、owner分摊算法 | 不超基数、不重复分摊、计量和封顶 | 按既定顺序先金额券后折扣券；或单独活动/人工促销路径 | PricingFormula；保存逐步basis/amount/舍入 |
| S06 费用及预估税 | 配送/服务费等原权威、发票行税类 | 费用是否含税/是否适用优惠、未知税类阻断 | 逐发票行provisional net/tax/gross；R0供资金计划 | FeePolicy、TaxEngine#estimate；不是最终税票 |
| S07 储值计划 | 用户明确意图、真实资金适用范围/余额 | 无折扣券；来源/币种/部分支付真实支持 | S及本金/赠金owner引用，不记销售优惠 | FundingPlanPolicy；不扣款，无能力BLOCKED |
| S08 积分 | owner资格、换算、税分类、R0与S | 用户选用、只能剩余、与其他优惠组合获准 | I_price或I_consideration及行份额 | PointsPlanPolicy；不扣积分 |
| S09 人工抹零 | 经审批原因/额度、积分后未安排范围 | 有券禁止；不触及已付/UNKNOWN/S；税务处理可表达 | W获准减免及份额；不自动把现金不足变抹零 | PricingAdjustmentPolicy；自动现金舍入默认关闭 |
| S10 最终定税 | S05～S09完整已接纳计划 | T01～T08、税基与对价分类、定点及资金上限 | 最终行net/tax/gross、税汇总；固定计划单次最终计算 | TaxEngine#finalizeTax；不重选券、不改变S |
| S11 分摊及报价 | 最终行及资金计划 | 行/总、税类、资方、券数、资产、应付资金守恒 | quote头/行/分摊及economicsHash；含税/待付/找零显示 | PricingInvariantChecker；7表中的报价3表同事务 |
| S12 正式确认 | quote、用户确认hash、原durableIntent | 冻结原scope、重验来源/税点/规则/资产/审批 | 同内核复算；任何商业差异回CHANGED待确认 | OrderPricingService#confirm；新配置不追改合法历史事实 |
| S13 执行与恢复 | 原意图固定源键、owner prepare/commit/query | 重复确认、远端失败/UNKNOWN、支付结果一致 | 原订单/PAY/资产确认结果 | 原结账编排＋outbox；无长事务跨远端扣款 |
| S14 凭证与售后 | 已确认原经济/税/资金证据 | 打印权限、退款份额、原凭证关系 | 客单、税票数据、报表事件、原税额退款分配 | 原打印/报表/退款Port；不复制账本 |

**S04不只检查请求结构**：先校验用户候选的所有静态限制，逐候选执行owner认可的计算与税务计划，检查实际金额/门槛/封顶及付款能力，再在全部合法计划中择优。预筛合格不代表金额计算后一定合格；每个最终Plan仍须通过S10～S11。AUTO_BEST比较使用明确的同一用户资金意图与目标，不把多花储值伪装成商品价格更便宜；不支持的第三方抵付/资金计划不能参与“最优”排名。

**税务行与促销份额分开**：source pricing unit用于多券重叠和AA份额；taxInvoiceLineRef用于税舍入。不能因为将同一菜品拆成两张券分摊行，就新增两条税发票行并改变税额。发票行映射在S03确定并冻结，费用也是明确行/份额。

### 2.4 试算与金额计算的具体调用

```java
// 目标代码流程；本轮不创建运行时代码，所有远端事实在进入纯内核前取得。
PricingResult calculate(PricingFacts facts, EffectivePricingConfig config,
        BenefitSelection selection) {
    var units = basePricePolicy.resolve(facts, config);
    var candidates = ruleEngine.validateAndBuildCandidates(facts, units, config, selection);
    return planSelector.selectValid(candidates, candidate -> {
        var priced = formulaRegistry.resolve(candidate.formulaRef()).calculate(units, candidate);
        var provisional = taxEngine.estimate(feePolicy.apply(priced, facts), config.taxPolicy());
        var funding = fundingPlanPolicy.plan(provisional, facts.fundingIntent());
        var points = pointsPlanPolicy.plan(provisional, funding, facts.pointsFacts());
        var waiver = manualAdjustmentPolicy.planWaiver(provisional, funding, points, facts.approvalFacts());
        var adjusted = taxAdjustmentResolver.resolve(priced, provisional, points, waiver);
        var tax = taxEngine.finalizeTax(adjusted, config.taxPolicy());
        var result = resultAssembler.assemble(tax, funding, points, waiver, candidate);
        invariantChecker.check(result);
        return result;
    });
}
```

`facts`已经带原owner资格/税分类/可执行报价及有效期，lambda不能临时联网取余额。提供方若只能针对单个基数远程试算，应用编排在有界候选数内分阶段取证后再执行纯内核，不能以代码示意宣称现有一次quote已经支持所有候选。字段/方法示意将在E0冻结为精确签名。

原券计算和原分摊算法仍用owner批准版本；不是在上述Formula里重新实现券权威。优惠来源所认可的basisHash必须等于实际应用基数；不一致的候选作BLOCKED，不能两套金额各算各的。

#### 为什么税不造成循环试算

1. S06税额只服务资金和合资格剩余测算，不产生税票。
2. S07固定S，S08/S09确定积分和抹零的实际经济数额及税基/对价性质；涉及金额必须声明`TAX_EXCLUSIVE`或`PAYABLE_GROSS`。
3. `PAYABLE_GROSS`减免先按冻结发票行分配目标含税减额，再由TaxAdjustmentResolver精确拆出相应税前/税额变化；`TAX_EXCLUSIVE`按未税减额计算实际含税减少，须重新检查未安排金额上限。
4. 价外税输入若目标gross减额无法按该行整数税前金额及地区舍入精确表达，返回`TAX_ADJUSTMENT_UNREPRESENTABLE`或新报价待确认；不静默多减/少减，不靠无限循环趋近。
5. S10最终定税只执行一次，校验`finalGross - considerationOffsets ≥ S`及全部已安排金额；失败则整候选拒绝，不回头扩大积分、修改S或循环重选优惠。

TaxAdjustmentResolver可采用受控有理数逆算及有界整数候选检查，并验证实际重算值精确等于目标；不承诺任意目标都可表达。地区未验收积分/抹零税合同前，禁用本次相应操作，普通商品含税报价仍可运行。

#### 最终金额字段，避免把税票和客户现金混为一笔

```text
invoiceNet   = Σ最终发票行net（含非征税行的相应销售对价，按税类分组）
invoiceTax   = Σ最终发票行tax
invoiceGross = invoiceNet + invoiceTax
considerationOffsets = pointsConsideration + voucherConsideration + 其他已验证对价抵付
customerDue  = invoiceGross - considerationOffsets
customerDue  = storedPlan + otherNewPaymentPlans + cashDue + unassignedDue
cashChange   = cashTender - cashDue （仅现金足额时）
```

- REDUCES_TAX_BASE的积分/抹零已经进入最终行金额，不再在公式末尾减一次。
- SETTLES_CONSIDERATION的积分/券不减invoiceNet/tax，只减少客户仍需安排的资金；必须保存原对价来源及履约证据。
- 储值不列considerationOffsets再次扣除，也不记为优惠；它在右侧支付计划中只出现一次。
- 当前算法处理本次获准未付范围，历史已付/UNKNOWN在S01排除；不得在右侧再次减同一历史支付。
- 税票金额必须与原对价清算/支付分配守恒；原订单若只有单一receivable字段而无法表达此区别，E0需扩展明确合同或暂时拒绝该抵付能力，不能改名掩盖差额。

### 2.5 Java及数据库/API补充

沿用原包，不建立新税务Maven模块。以下包名＋类名为完整代码规划：

| 模块/包 | 类及方法 | 职责 |
|---|---|---|
| shop-base / `ai.neargo.shop.common.pricing` | `TaxEngine#estimate(), finalizeTax()` | 无IO定点税运算 |
| 同上 | `TaxAdjustmentResolver#resolve()` | 积分/抹零的净额/税/含税减免与精确表达检查 |
| 同上 | `TaxPolicy`、`TaxClass`、`PriceTaxMode`、`TaxTreatment`、`TaxLineResult`、`TaxSummary` | 有类型record/枚举；政策与结果分离 |
| 同上 | `BasePricePolicy`、`FeePolicy`、`FundingPlanPolicy`、`PointsPlanPolicy`、`ManualAdjustmentPolicy`、`PricingResultAssembler` | 纯事实到金额；ManualAdjustmentPolicy只使用已获准审批事实，不依赖core权限Service |
| shop-core / `ai.neargo.shop.trade.policy` | `TaxProfileResolver#resolve()`、`PricingAccessPolicy#authorize()` | 可信地区/税点/税登记映射及访问权限 |
| shop-core / `ai.neargo.shop.trade.service`及`.impl` | `PricingSeedService#importDrafts()`、`PricingSeedServiceImpl` | 初始草稿导入、预检和幂等；不自动发布 |
| 原商品/主体/优惠来源Port | 扩充typed事实：税类、含税方式、地区/登记、税点、资方/对价证据 | 先核验已有字段，按原能力扩展唯一接口，不造重复TaxPort假装已有 |

7表数量不变，补以下候选字段；配置表仍是类型化JSON，列约束沿原设计：

| 表 | 新增/明确字段 | 说明 |
|---|---|---|
| ord_price_quote | tax_policy_no/version、tax_point、tax_point_status、tax_snapshot JSON | 税点为ESTIMATED/CONFIRMED；多税率完整明细在快照 |
| 同上 | invoice_net_minor、invoice_gross_minor、customer_due_minor | tax_minor仍为税合计；receivable_minor明确定义为invoice_gross_minor，不等同customerDue |
| 同上 | points_consideration_minor、voucher_consideration_minor | 与原points_discount_minor/coupon_discount_minor互斥分列，禁止双记 |
| ord_price_quote_line | tax_invoice_line_ref、tax_class、tax_profile_version、tax_rate_bp(nullable)、price_tax_mode | 税发票行身份及事实；促销份额拆分不改变发票行身份 |
| 同上 | net_minor、gross_minor、tax_rounding_allocation_minor | 同一发票行先算税，再按冻结算法分配到计价份额，Σ份额税=原行税 |
| ord_price_quote_allocation | tax_treatment、amount_basis、net_effect_minor、tax_effect_minor、gross_effect_minor、tax_evidence_ref | 税影响有符号；与非负优惠amount_minor的字段语义分别定义 |

对外PricingQuoteVO补充`tax:{policyNo,policyVersion,taxPointStatus,priceTaxMode,netMinor,taxMinor,grossMinor,groups:[{taxClass,rateBp,netMinor,taxMinor,grossMinor}]}`、`invoiceGrossMinor/customerDueMinor`、`pointsConsiderationMinor/voucherConsiderationMinor`；行返回税类/税额。税snapshot和原owner令牌不直接透传C端。

门店配置API仅提交`taxBinding.taxProfileNo`，服务端校验地区与资格。平台模板API可编辑受控税档并发布新版本，需税务配置权限及审计。报价API不接受客户端税率或最终税额；消费者不能用请求覆盖地区。

### 2.6 可核对算例

金额单位为AED；除明确说明外均假设标准税类、登记供应方、政策及对应owner能力均已核验。JSON向量金额使用fils整数。

| 场景 | 计算 | 结果 |
|---|---|---|
| 未税基价加5% | 100＋100×5% | 未税100，VAT5，含税105；消费者展示105 |
| 已含税拆税 | 105×5/105 | VAT5，未税100，应收仍105 |
| 先金额券后折扣券，未税口径 | (200－20)×90%=162；VAT8.10 | 含税170.10；两券均需原owner支持该口径，不能储值/人工抹零 |
| 多税类 | 标准税100＋零税50＋免税20 | 未税/非征税对价170，VAT5，含税175；三类分别打印统计 |
| 行舍入 | 两行未税各0.10，各税0.005→0.01 | 含税总额0.22，不按整单重算成0.21 |
| 积分降价后人工抹零 | 含税105－积分降价10－获准抹零0.50=94.50 | 未税90，税4.50；无券、无人工打折；依实际地区和owner资格开放 |
| 储值是付款 | 含税105，未来储值付60，现金付45，实收50 | 税仍5；找零5；餐饮部分储值当前无合同则BLOCKED |
| 积分构成对价 | 含税105，积分对价抵付10，客户剩余95 | 税仍5；invoiceGross105，customerDue95；需真实积分清算合同 |

税前/含税金额券不能混用量纲：同样“20 AED券”在某个真实券合同中究竟抵含税应付20、还是抵未税20，必须由owner给出；上表第三例明确是未税20，不代表所有现金券默认未税抵扣。

### 2.7 先付、后付、AA与凭证

- **先付后吃**：提交前重验完整源/税点/计划，按原付款意图确认；成功后出品，打印经济结果使用确认快照。
- **先吃后付**：下单冻结菜价及批次身份；结账读取原冻结事实、合法税点及未付份额。加菜独立来源，不能拿新税率/优惠重定价原已确认税务范围。
- **AA/拼桌**：沿原根账单已确定的发票行税额分配，AA尾差按既定份额算法承接；不能每人分别对拆零金额再算5%创造/消失税额。若确需拆成不同税发票，走原开票更正/拆票能力，不在点餐端隐式改税证据。
- **现金找零**：实收与找零单列，不改变税票销售额；未足额现金不能自动当人工抹零。
- **打印**：顾客结账单展示优惠、未税、VAT、含税、各抵付、实收找零；税发票字段取原供应方税登记及发票能力；厨房单只执行出品，不承担税票权威。
- **报表/日结**：分别统计按税类的net/tax/gross、降价优惠、对价抵付、储值/现金/电子收款、找零、原退款税额；营业日切点用于经营汇总，不能替代法定税点和税务申报期间。
- **退餐/退款**：已确认份额按原价格、税类、税率、舍入和资金证据处理，累计退税不超原税；税发票更正沿原税票/credit note能力，不直接UPDATE原quote税额。

## §3 选型及执行顺序

采用有限规则种子＋类型化地区税档＋预估/最终定税两阶段，继续复用7表和现有纯内核。单个全店vatRate不能表达含税/未税、混合税类、税点及对价差异，因此不采用。完整税务申报/开票平台不属于本轮范围。

原E0补充税字段、税点与owner分类合同；E1先交付标准VAT两模式和上述向量，再做积分/抹零精确调整；E2发布配置种子草稿与地区档案；E3报价展示与税快照；E4结账确认/打印/AA原税分配；未来复杂多税/复合税或地区特殊规则另加显式算法版本，不假定本VAT公式覆盖全球。

## L4 边界与验证

## §4 未具备能力的处理

没有实际地区/供应方税登记/商品税类或税点事实则BLOCKED；没有积分、voucher对价、人工减免税务分类则阻止该项使用，不静默按0税或“全部折扣”处理。阿联酋5%种子只是标准税类示例，发布前需平台验证目标地区当前法律版本及供应方上下文；不把示例文件视为已经发布的税务政策。

## §5 测试计划及本轮检查

| AC | 拟新增测试 | 断言 |
|---|---|---|
| N01 | PricingSeedServiceTest#importIsIdempotentAndDoesNotPublish | 20条规则引用完整、3行业默认一致、示例门店/能力不落生产 |
| N02 | PricingCheckoutStagesTest#rulesBeforeAmountsAndNoSideEffectsInQuote | 错误阶段短路、不核销；确认同内核、商业差异需确认 |
| N03 | TaxProfileResolverTest#usesTrustedRegionAndTaxPoint | 门店不能改法定率，未知地区拒绝，过期配置按税点拦截 |
| N04 | TaxEngineTest#inclusiveExclusiveAndMixedClasses | 含税不二次加税；标准/零税/免税保持不同分类 |
| N04 | TaxEngineTest#roundsByFrozenInvoiceLine | 两行各0.10的税合计0.02；券拆份额不改变税发票行 |
| N04 | TaxAdjustmentResolverTest#doesNotDoubleSubtractPoints | 降价/对价路径分开；不可表达减免拒绝，不改S或循环逼近 |
| N05 | PricingTaxHistoryTest#aaRefundAndReprintUseOriginalTax | AA税分摊守恒，累计退款税不超原税，重打不读取新税率 |

本轮设计检查结果：20个规则编号唯一、3个模板引用完整、2个AE税档、S00～S14及TAX_FINALIZE阶段顺序检查通过；8个金额向量经独立Decimal参考计算全部通过，文档本地链接与代码围栏检查通过。此检查不执行提供方、权限、数据库或Java实现；表中Java测试为拟定清单，不宣称已实现税务计算或资金合同。

## §6 本轮对账

新增本补充方案和规则初始数据示例；同步主实施方案、总规则JSON及餐饮需求的地区税要求。未执行数据库迁移、未导入或发布配置、未修改订单/支付运行时代码。

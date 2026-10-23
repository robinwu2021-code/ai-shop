# 订单结账CKO-04：业务规则校验与证据

> 状态：B01～B12业务阶段已实现，后续按计划推进 · 2026-10-22 · 档位2。任务基线44471e76b；依据[完整计划CKO-04](./TDD-餐饮订单结账-完整开发计划.md)、[CKO-03](./TDD-订单结账-CKO03规则配置编译.md)及[ADR-040](./ADR/ADR-040-餐饮销售配置与耗用权威边界.md)。

## L1 定位

实现共享B01～B12业务规则。行业适配器提供可信价格、实际活动、商品份额资格、根账单历史券及原owner/审批证据；内核校验显式候选，返回所有适用冲突。禁止规则共同生效，任何允许组合不得覆盖禁止。规则校验无IO、不消费资产。

## L2 调用与边界

PricingRuleEngine#validate(PricingRuleFacts facts, EffectivePricingConfig config, PricingRuleCandidate candidate)返回不可变List<RuleViolation>。每项携带ruleNo、ruleVersion、stage、code、源行及优惠资产引用。税规则T01～T08、候选搜索、优惠公式、税后最终确认和真实权威接线属于后续任务；空列表只表示本阶段未发现业务冲突。

## L3 设计与实施

## §0 需求 → 设计

| AC | 需求 | 设计 |
|---|---|---|
| R01 | 活动禁券，折扣券禁储值，券后禁人工优惠 | B01/B02/B07/B08独立禁止，汇总所有冲突 |
| R02 | 多券及原owner组合/基数/顺序 | B03/B04/B05/B09，按owner+asset去重，累计根范围 |
| R03 | 份额重叠与套餐父叶 | B06/B12，半开数量范围及原owner父叶身份 |
| R04 | 储值后积分、最后人工抹零 | B10，显式顺序及owner合格未资助余额 |
| R05 | 人工操作原因、权限与审批匹配 | B11，actor/store/scope/kind/amount/expiry、门店下限 |
| R06 | 排除商品仍收费，已付/UNKNOWN禁用，同币种守恒 | B12，份额资格及预税候选金额一致性 |

## §1 影响范围

只新增shop-base/common/pricing类及测试，不改变PricingFacts既有构造器、不改零售算法、支付/库存、数据库及API。沿用户指定餐饮分支和精确路径提交，保留共享目录其他改动。能力和审批对象只能由后端权威适配构造，尚不作为前端请求DTO。

## §2 代码及方法计划

所有生产类完整包名均为ai.neargo.shop.common.pricing，位于backend/shop-base/src/main/java/ai/neargo/shop/common/pricing/。

| 类 | 职责/方法 |
|---|---|
| PricingRuleEngine | validate：固定目录及业务Evaluator调度，汇总不可变冲突 |
| RuleEvaluator | type/evaluate：有限类型接口，注册表由代码构建 |
| BusinessRuleEvaluators | 互斥、数量、能力、重叠、阶段、审批、守恒校验 |
| PricingRuleContext | 本次校验的facts/config/candidate聚合，不存可变状态 |
| PricingRuleFacts | PricingFacts包装、可信上下文、逐份额资格/状态、实际活动、根历史券 |
| PricingRuleCandidate | 显式优惠列表、owner证据、审批证据及预税剩余应付；嵌套record防御复制 |
| PricingPortion | 源行/组件/半开数量范围；匹配/重叠/包含 |
| RuleViolation | 规则版本、阶段、码及关联源行/资产 |

测试PricingRuleEngineTest在同模块同包。实施先观察RED，再最小实现、完整模块回归和B02消融，独立评审后提交。

预税候选金额一致性只核对输入金额是否守恒，不在此推导折扣金额。活动只能来自facts.appliedActivities，不从门店EXCLUSIVE开关推断实际参加活动。历史券与本次券按owner+asset联合计数；同资产多份额仍只计一张，不允许同一次候选重复资产行。

## §3 输入、输出及规则细节

### 3.1 事实和显式候选

| 对象 | 输入字段及来源 |
|---|---|
| PricingRuleFacts | prices:PricingFacts；context:AccessContext；unitPolicies:List<UnitPolicy>；appliedActivities:List<Benefit>；previousCoupons:List<CouponUse> |
| AccessContext | actorNo/storeNo/scopeNo：可信鉴权/根结账范围；calculationBasis：原owner确认的金额基数；evaluatedAt：本次可信判断时间，不在内核读取时钟 |
| UnitPolicy | portion:PricingPortion；promotionEligible:boolean；status:OPEN/PAID/UNKNOWN；bundleGroup及role:NONE/PARENT/LEAF |
| PricingRuleCandidate | benefits:List<Benefit>；owners:List<OwnerEvidence>；approvals:List<ApprovalEvidence>；remainingPayable:MoneyMinor |
| Benefit | asset:AssetRef(owner,assetNo)；kind:BenefitKind；amount:MoneyMinor；allocations:List<PortionAmount>；payableRateBp:Integer（人工折扣用） |
| PortionAmount | portion:PricingPortion及amount:MoneyMinor；每个优惠的分配金额之和必须等于该优惠金额，同币种 |
| OwnerEvidence | asset、combinableKinds、formulaId/version、calculationBasis、eligibleRemainder；来自原owner本次可信报价，不是前端声明 |
| ApprovalEvidence | asset、kind、actorNo/storeNo/scopeNo、amount、reasonCode、expiresAt、evidenceRef；原权限/审批能力核验后准入 |
| CouponUse | asset及kind；原根结账范围已用券历史，不能只传本次购物车券 |

PricingPortion以sourceLineNo/componentRef/start/end标识半开区间，数量最多6位小数，规范化后匹配。UnitPolicy必须恰好覆盖每个源定价份额；实际活动列表只允许ACTIVITY。本次candidate不得自报ACTIVITY代替源权威。

所有集合防御复制。边界：源份额/策略最多512，实际活动64、历史券64、候选优惠64、owner证据128、审批64、单优惠分配64；超界或非法结构构造即拒绝。业务编号及引用沿32/64上限，拒绝空白及控制字符。结构异常不冒充业务冲突，调用层在后续API适配时负责统一转换。

### 3.2 共同生效的B01～B12

| 规则 | 具体校验 |
|---|---|
| B01 | 实际生效活动与任意券互斥；门店活动开关不是实际活动事实 |
| B02 | 折扣券与储值互斥，其他组合允许也不能覆盖 |
| B03 | 门店组合及资产开关；每个非人工owner确认其他资产的券型/优惠种类，包括同类多券，并核对formula版本及基数；公式ID同时锁定阶段顺序 |
| B04 | 根范围联合去重计折扣券/总券数，含discountCouponPolicy.maxUsedPerOrder |
| B05 | 现金券/抵扣券分别及金额券合计/总券上限；同owner+asset多份额计一张 |
| B06 | DISJOINT/BEST_ONE候选不允许折扣券重叠；STACK按同一数量点并发资产数约束，不能只做成对比较 |
| B07 | 人工打折/扣减与活动、券、储值及积分互斥 |
| B08 | 券与人工抹零/现金舍入互斥 |
| B09 | 储值与活动/金额券组合需要双方owner许可，不可覆盖B02 |
| B10 | 普通优惠→储值计划→积分→人工抹零→现金舍入；储值、积分及抹零不得超过各owner确认的合格未资助余额；份额累计余额另由B12共同约束 |
| B11 | 人工功能开关、原因目录、审批主体/范围/种类/金额/有效期逐项匹配；金额基数匹配；人工应付比例及金额下限；现金舍入默认不开放 |
| B12 | 真实源份额完整匹配、金额/币种守恒、重复资产/证据拒绝、历史资产种类一致、排除项仍计价、PAID/UNKNOWN禁止、套餐父叶不得双计 |

规则按固定目录稳定顺序执行，汇总全部适用冲突，不因为前一条允许而短路后续禁止；同一规则可返回多个关联问题。重新编译核对配置结构/强制目录及hash一致性，防止调用代码手造EffectivePricingConfig删规则；该检查不是签名或发布权限。运行期从每个owner逐项确认能力，不因编译时存在宽泛支持声明授权真实资产。

### 3.3 逐份额余额与金额证据

每个PortionAmount必须严格匹配一个完整源定价PricingUnit的范围；内核不对未拆分的源金额自行按数量比例舍入。CKO-05/06应准备带有原源价/修订证据的定价份额，并计算优惠分配；本模块只验证这些显式金额。

简洁校验式：`份额余额 = 原份额baseAmount − 实际活动分配 − 本次优惠/资金分配`，每步余额≥0。全单还须满足`Σ原金额 = Σ实际活动/本次优惠金额 + 剩余应付`。储值和积分是资金抵扣，不能据此误删排除商品的原计价；非资金减价不得侵入排除项原金额。每笔优惠内部也须满足`优惠amount = Σ该优惠allocations.amount`。

人工折扣率不是由这里执行的金额公式；实际amount与rate是否一致在CKO-06重算。人工比例下限同时检查申请rate和本次人工总扣减对全单原金额的下限，不替代更严格的owner审批。税后毛额、税类及对价处理在CKO-07统一完成。

输出RuleViolation字段：ruleNo、ruleVersion、stage（CONFIGURATION/SELECTION/ELIGIBILITY/APPROVAL/AMOUNT）、code、sourceLineNos、assets。代码是内核诊断标识，尚未对外发布为ErrorCode或i18n词条，不直接作为用户可见文案。来源状态失败即使没有优惠也会返回受影响源行。

## L4 验证与交付

## §5 验证记录

初始目标类缺失RED；模型和空校验器具备后运行13项，11项断言失败、0错误，确认未执行规则会被测试捕获。首批13项实现后通过。

独立只读评审指出同类owner组合、逐份额金额及人工抹零余额三个重要问题。分别补反例确认失败后修复，22项通过；随后补基数及无优惠源行追溯反例，两项断言失败后修复，27项通过。

| AC | 对应测试方法（节选，真实运行类PricingRuleEngineTest） |
|---|---|
| R01 | denyCannotBeOverriddenByAllow；couponRejectsManualWaiverAndManualPercentRejectsPoints；configuredActivityPolicyIsNotEvidenceThatAnActivityWasApplied |
| R02 | countsUniqueAssetsAcrossRootHistoryAndChecksEachLimit；multipleAssetsOfSameKindRequireEachOwnerToAllowThatKind；ownerMustConfirmCombinationBasisAndOrderEvenWhenStoreAllowsIt |
| R03 | disjointAllowsAdjacentRangesButRejectsOverlappingPercentCoupons；stackedCouponsRespectMaximumConcurrentAssets；parentAndLeafCannotBothContributeToSourcePrice |
| R04 | pointsMustFollowStoredPlanAndCannotExceedEligibleRemainder；waiverCannotReduceAnAlreadyFundedPortion |
| R05 | manualOperationRequiresMatchingUnexpiredApprovalAndConfiguredReason；staleApprovalAmountStoreScopeKindAndReasonAllFail；manualBasisMustMatchTheVerifiedCalculationContext |
| R06 | repeatedDiscountsCannotSpendAnotherLinesUnrelatedValue；discountsCannotConsumeExcludedItemsAmountThroughAnEligibleScope；fundingCanSettleExcludedGoodsWithoutDiscountingTheirInvoiceCharge；paidSourceEvidenceIdentifiesTheAffectedLineEvenWithoutAnyBenefits |

两次消融：跳过B02，denyCannotBeOverriddenByAllow断言失败；关闭逐份额负余额检查，repeatedDiscountsCannotSpendAnotherLinesUnrelatedValue断言失败。两次均为1项失败、0错误，源码已恢复。独立评审复核三个重要修复通过。最终全量及架构结果见§6。

## §6 实施对账

后续模块：CKO-05候选选择，06金额公式，07税务，08～11配置/报价及真实来源适配。所有来源、审批和owner证据的真实性/归属/时效重验仍须在接线层完成；内核不接收客户端自报已授权布尔值来放行资金操作。

实施裁决：候选优惠增加显式PortionAmount分配，原计划只写candidate未限定承载字段；采用分配证据以满足B12份额守恒，不在校验器引入按比例金额算法。部分数量必须先形成带源金额的定价份额，无法匹配则SCOPE_REQUIRES_PRICED_PORTION，不能假装已准确算价。

最终回归（Java21，离线）：shop-base 143项、job-api 11项、svc-client 10项均0失败/0错误/0跳过。ArchitectureTest 16项中15通过、1项失败，仍是基线已有OpsPointsInspectionController全限定PathVariable注解；该文件与本任务HEAD无差异，不修改无关Controller或放宽守卫。

命令：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f backend/pom.xml -pl shop-base -am test
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f backend/pom.xml -pl shop-app -am test \
  -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
```

全量回归上游测试获准使用本机临时HTTP监听，未访问真实业务服务。新增9个Java文件≤120列、无通配符import、无行尾空格；文档本地链接/围栏及任务范围diff检查通过。

实现清单与§2逐项一致：8个生产类、1个测试类、3份文档，共12个限定路径；无POM、Controller、Service、Mapper、表或API变动。下一任务CKO-05多券候选与份额计划；不将本阶段空冲突当作完整金额/税务校验或正式交易授权。

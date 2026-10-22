# 订单结账CKO-05：多券候选与份额计划

> 状态：有界候选与税前计划编排已实现 · 2026-10-22 · 档位2 · 起点1dd087b09。依据[完整开发计划CKO-05](./TDD-餐饮订单结账-完整开发计划.md)、[CKO-04业务规则](./TDD-订单结账-CKO04业务规则校验.md)及[ADR-040](./ADR/ADR-040-餐饮销售配置与耗用权威边界.md)。

## L1 定位

在shop-base提供有限搜索与不可变份额计划；不复制优惠金额算法。EXPLICIT全部采用或明确拒绝；CANDIDATE_POOL/AUTO_BEST可从提供的券池选择子集。所有候选须重新评估完整组合，并通过B01～B12；未选优惠没有核销行为。

## L2 选择边界

本任务结果明确处于BUSINESS_PRE_TAX阶段。具体公式/原owner封顶与舍入接CKO-06，含税最终报价及完整最终目标比较接CKO-07；当前不声称含税全局最优。固定人工、储值和积分意图不可在选优时擅自删除、换金额或换份额，以免把多消费资产伪装成更优惠。

## L3 实施设计

## §0 需求 → 设计

| AC | 需求 | 落点 |
|---|---|---|
| P01 | 显式选择不能丢券 | 精确资产/种类及执行顺序匹配；EXPLICIT只评估全集 |
| P02 | 同券多份额计一张，数量重叠/根范围限制 | 复用CKO-04，source-priced allocations冻结到CalculationPlan |
| P03 | 同项择优及受控叠折 | 合法子集枚举，逐组合完整评估，筛掉非法重叠 |
| P04 | 有界搜索，不伪称最优 | 硬限制、请求及方案评估预算；不足返回PLAN_SEARCH_LIMIT无部分赢家 |
| P05 | 同价确定性、保留owner顺序 | 同价少耗券，业务身份及分配打破并列；排序不改实际计算顺序 |
| P06 | 固定资金/人工意图与无核销 | fixedBenefits完全匹配；纯回调和规则计算，不接消费Port |
| P07 | 评估缺失不能当无资格 | 区分Rejected与Unavailable；不可用/合同不完整即EVALUATION_INCOMPLETE |

## §1 影响范围

新增共享类，不改CKO-04校验器/原零售算法、API、表或支付库存。沿用户指定分支和精确文件归属提交。计划服务的具体价格/税评估器后续接线；本阶段无默认返回成功的生产报价实现。

## §2 类及方法计划

完整包名ai.neargo.shop.common.pricing，生产路径backend/shop-base/src/main/java/ai/neargo/shop/common/pricing/。

| 类 | 职责 |
|---|---|
| BenefitSelection | mode、券池CouponOption(asset,kind)、固定非券优惠意图 |
| BenefitPlanSelector | select(Context, BenefitSelection)；嵌套Context/SearchLimits/PlanCalculator/PlanRequest及封闭Evaluation结果 |
| CalculationPlan | 不可变源事实、配置、本次实际采用金额/份额、used/unused及阶段 |
| PlanSelectionResult | SELECTED/NO_LEGAL_PLAN/INVALID_SELECTION/PLAN_SEARCH_LIMIT/EVALUATION_INCOMPLETE，方案、拒绝原因及评估计数 |

新增测试BenefitPlanSelectorTest位于同模块同包。先RED，再最小实现、全量shop-base回归及丢券/预算保护消融，独立评审后提交。

回调PlanCalculator只评估请求中的精确券子集与固定意图，重新执行实际公式，返回完整分配备选集合Evaluated、已证实不适用Rejected或尚不能完成Unavailable。每个返回方案仍经原规则引擎校验。未能枚举完整分配的评估器必须返回Unavailable，不能把一个错误分配当成该券子集必定无解。

源份额金额必须预先有真实源价及修订证据；本模块不按数量猜测源金额或自动拆分舍入。后端准备的券池顺序是冻结执行顺序，回调保留其子序列；同价身份排序仅用于选择结果，不改变叠折顺序。候选模式搜索只覆盖传入券池，非平台全部可用资产。

## §3 输入输出与算法

### 3.1 合同

| 输入/输出 | 字段 |
|---|---|
| Context | facts:PricingRuleFacts，config:EffectivePricingConfig，calculator:PlanCalculator，limits:SearchLimits |
| BenefitSelection | mode:EXPLICIT/CANDIDATE_POOL/AUTO_BEST，coupons:List<CouponOption>，fixedBenefits:List<Benefit> |
| CouponOption | asset:AssetRef(owner,assetNo)，kind:PERCENT_COUPON/CASH_COUPON/DEDUCTION_COUPON；顺序由可信后端预先确认 |
| PlanRequest | 原facts/config、本次精确券子序列、原fixedBenefits；不可变 |
| Evaluated | candidates:List<PricingRuleCandidate>，完整的分配备选集合，1～64项 |
| Rejected | code、violations；原owner/公式已证实本子集不成立 |
| Unavailable | code；尚未取得完整金额/适用/分配证据，不等同无资格 |
| PlanSelectionResult | status、plan（失败为null）、rejections、evaluatedPlans、evaluatedSubsets |
| CalculationPlan | mode、原facts/config、已校验candidate、usedCoupons/unusedCoupons；stage固定BUSINESS_PRE_TAX |
| Rejection | code、关联coupons、原规则violations（保留ruleNo/version/stage/源项） |

状态：SELECTED唯一合法赢家；NO_LEGAL_PLAN表示所有已完成方案均不成立；INVALID_SELECTION表示重复/非法固定意图或超容量显式意图；PLAN_SEARCH_LIMIT表示搜索预算不足；EVALUATION_INCOMPLETE表示未完成评估或评估器没有忠实保留请求。后两种结果即使之前找到合法候选也不返回部分赢家。

SELECTED可同时带有其他方案的拒绝证据。used/unused按稳定业务身份排序展示，candidate中保留原执行顺序。返回集合及嵌套事实/金额对象均不可变；不产生核销、占用、退款或库存调用。

### 3.2 搜索和比较

1. 检查券池及固定意图资产唯一性，fixedBenefits禁止夹带券或ACTIVITY；实际活动只来自源事实。
2. EXPLICIT只评估全部券；可选模式枚举传入池所有子集，含不用券，子序列保持原顺序。
3. 每个子集调用一次完整PlanCalculator，不能复用单券金额相加。取得全部相关分配备选；若无法证明完整，评估器必须返回Unavailable。
4. 检查实际返回券的资产、种类及顺序完全匹配请求；固定意图包括金额/份额/顺序必须完全相同。遗漏、注入、换种或改资金量返回EVALUATION_INCOMPLETE，不偷偷采用修改后的候选。
5. 对每份金额/份额方案运行原B01～B12；不合格记录完整原规则冲突。优惠为零的券不作为成功使用：EXPLICIT明确失败，自动模式可选不用券。
6. 合法方案比较剩余应付（同一固定资金意图、同币种），同价优先少耗券，再按owner/assetNo和规范化份额分配稳定打破并列。该排序不改变原owner叠折执行顺序。
7. 全部搜索完成才构造CalculationPlan；回调不可用、返回null、不守合同或超预算均不返回已有赢家。意外程序异常向上传递，不伪装成无资格后继续择优。

CANDIDATE_POOL和AUTO_BEST当前共享这套受控选择行为，均只在指定池择优；未来自动发现资产归原owner接线，不能据模式名称直接查库或核销。即使规则上允许叠加，也须每个子集按原owner认可的顺序重新计算与分配，不能按名义折扣率简单相乘。

### 3.3 公开复杂度上限

默认maxOptionalCoupons=8、maxEvaluations=1024。硬上限12和4096，构造时拒绝越界；每个子集回调及每个分配方案校验各消耗1单位。两类实际次数分别由evaluatedSubsets/evaluatedPlans返回。池过大或最低所需回调数已超预算，评估前返回PLAN_SEARCH_LIMIT；中途耗尽也无赢家，不截断后声称最优。

EXPLICIT最多64张输入券，不进行幂集搜索，独立于可选池大小限制。显式券加固定意图超过64直接INVALID_SELECTION；可选模式仅排除总意图超过64的子集，仍评估可容纳的子集。原CKO-04的候选64、owner128、份额512和单优惠分配64等限制继续生效。

## §4 剩余门禁及实施裁决

本任务是可执行的搜索/验证编排，未提供假报价默认实现。测试中使用手工核对的完整报价及故意失败/不完整报价，验证选择器行为，不把测试报价当作已开发公式。具体商品/权益来源真实性、候选池完整性、报价分配完整性和原owner阶段顺序由后端适配器核验；配置/事实对象不是前端请求DTO。

金额公式在CKO-06接入PlanCalculator，地区税及完整最终应付比较在CKO-07接入后统一验收。当前CalculationPlan显式标记税前阶段，不可以此正式收款或消费储值/积分。源金额拆份额仍须沿原权威舍入证据，不能在选择器自行按数量平均分；已付/UNKNOWN、套餐父叶双计及根累计券限制复用CKO-04。

实施裁决：按原计划select(context, selection)落实有限PlanCalculator合同，以解决05先于06/07、组合金额不能简单相加的依赖；先实现编排，公式/税务接线不虚构完成。默认可选池8项控制后端复杂度；需要更多候选可在硬边界内调整可信SearchLimits，超限明确失败。

## L4 验证与交付

## §5 验证记录

初始测试先于目标类，确认类缺失；模型及空选择器就绪后，19项均断言失败、0错误。最小实现后19项通过，扩展边界后27项通过；随后两个容量边界各先确认1项断言失败、0错误，再实现保护。

| AC | 对应BenefitPlanSelectorTest测试 |
|---|---|
| P01 | explicitCannotDropCoupon；silentlyDroppedExplicitCouponIsAnIncompleteEvaluation；cannotReorderOrChangeKindsInAFrozenExplicitExecution |
| P02 | countsAssetsAndChecksQuantityOverlap；disjointCouponsCanUseDifferentPricedPortionsWithinRootLimits；rootHistoryCanMakeAnOtherwiseLegalSubsetInvalid |
| P03 | bestOneSearchesOptionalSubsetsInsteadOfDroppingExplicitIntent；considersEveryReturnedAllocationAlternativeBeforeRejectingASubset；revaluesEachFullCombinationInsteadOfAddingIndependentCouponSavings；stackUsesFrozenOwnerOrder |
| P04 | excessivePoolFailsBeforeAnyQuoteAndNeverReturnsAPartialWinner；allocationBudgetExhaustionDiscardsAnyEarlierWinner；preflightBudgetCannotHideUnvisitedCombinations；totalIntentSizeCannotExceedTheRuleCandidateCapacity；oversizedOptionalSubsetDoesNotInvalidateTheFeasibleFixedIntent |
| P05 | stableEqualPriceChoiceUsesAssetIdentityAndDoesNotSpendZeroValueCoupons；equalPriceAllocationWinnerDoesNotDependOnAlternativeIterationOrder |
| P06 | fixedFundingIntentCannotBeDroppedOrIncreasedToWin；quoteMustNotChangeFixedManualIntentToCreateAnAlternative；unselectedAssetsAndInputListsRemainUntouched |
| P07 | incompleteQuoteBlocksBestClaimEvenAfterFindingALegalWinner；provenIneligibleSubsetCanBeSkippedWithoutPretendingAnUnavailableQuoteIsIneligible；nullEvaluationCannotBecomeAnImplicitRejection；calculatorFailureIsNotSilentlyConvertedIntoAnOptimalPlan |

独立只读评审未发现重要问题；新增显式/可选容量保护也已复核。最终消融、全套测试及架构结果见§6。

## §6 实施对账

§2四个生产类与实际实现一致，另新增1个测试类及本收据，更新完整计划/Java实施方案，共8个归属路径。无已有生产类、POM、API、Controller/Service/Mapper、数据库或资金调用修改。正式金额计算、税务、配置发布和报价/支付接线仍按后续计划执行。

消融结果：关闭精确意图匹配，silentlyDroppedExplicitCouponIsAnIncompleteEvaluation断言失败；关闭运行期预算，allocationBudgetExhaustionDiscardsAnyEarlierWinner断言失败。两次各1失败、0错误，源码已恢复。恢复后完整回归：shop-base 173项、job-api 11项、svc-client 10项均0失败/0错误/0跳过。

ArchitectureTest共16项，15通过、1失败；仍为基线已有OpsPointsInspectionController全限定PathVariable注解，该文件与本任务HEAD无差异。本任务未修改该Controller或放宽守卫。独立只读评审及容量修复复核均无重要发现。

实际命令（Java21离线）：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f backend/pom.xml -pl shop-base -am test
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f backend/pom.xml -pl shop-app -am test \
  -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
```

上游HTTP传输测试获准使用本机临时监听，不连接真实业务服务。新增5个Java文件≤120列，无行尾空格/通配符import；任务文档链接、围栏和范围diff验证通过。仅提交本任务8个路径，保留共享工作区其他改动。下一任务为CKO-06公式、分摊与金额守恒及零售兼容回归。

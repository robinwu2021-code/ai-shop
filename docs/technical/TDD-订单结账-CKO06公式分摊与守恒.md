# 订单结账CKO-06：公式、分摊与金额守恒

> 状态：税前公式、分摊及守恒已实现 · 2026-10-22 · 档位2 · 基线51865a1ef。依据[完整计划CKO-06](./TDD-餐饮订单结账-完整开发计划.md)、[CKO-05](./TDD-订单结账-CKO05多券候选与份额计划.md)及[ADR-040](./ADR/ADR-040-餐饮销售配置与耗用权威边界.md)。

## L1 定位

共享纯Java金额公式，复用原CouponCalculation优惠向下取整、比例四舍五入及最大基数尾差算法。新计算必须与原owner确认的基数、公式/算法版本、实际金额/份额完全一致，出资分配原样保存并检查守恒。旧零售调用链不自动切换。

## L2 边界

输出BUSINESS_PRE_TAX，不是地区税票或最终收款授权。先金额券后折扣券，储值计划后积分再获准抹零；每一步都基于剩余份额重新计算，叠折逐张舍入。LEGACY_OWNER_COMPAT只接受原owner冻结分配，不猜测原商家分组如何拆成菜品行。

## L3 设计与实施

## §0 需求 → 设计

| AC | 要求 | 实现落点 |
|---|---|---|
| F01 | 20000减2000后九折=16200；分项和逐券叠折 | 固定公式、基点与检查过的定点算法 |
| F02 | 原owner基数/顺序/算法/金额一致，不一致阻断 | Quote绑定、执行序列及逐步分配核对 |
| F03 | 每行/总/券/出资/尾差守恒 | PricingInvariantChecker及不可变StepResult/AllocationTrace |
| F04 | 对接05逐组合完整评估 | RecipeSource与PlanCalculator适配，不拼接独立券金额 |
| F05 | 原零售金额/舍入/商家门店/出资不变 | core RetailPricingCompatibilityTest调用真实既有分配Service |
| F06 | 非法阶段/范围/数量/币种/人工/资产约束仍有效 | 同一个CKO-04规则引擎最终重验 |

## §1 影响范围

common.pricing新增纯运算类；原CouponCalculation、CouponPort及零售Service不修改。core仅新增兼容测试，Mock只替代数据库行读取，不替代被测金额算法或出资判断。无Controller、表、配置发布、资产核销或库存调用。

## §2 类及方法计划

生产类完整包名ai.neargo.shop.common.pricing，位于既有shop-base：

| 类 | 职责 |
|---|---|
| PricingEngine | calculate(PricingFormula.Input)；calculator(RecipeSource)桥接05，失败不泄露可用金额 |
| PricingFormula | 固定公式合同及Input/Step/Operation/Quote/FundedAllocation/资方等不可变参数 |
| FormulaRegistry | resolve(formulaId,version)，仅支持已登记的三个固定公式 |
| FixedPricingFormula | 固定阶段、原定点优惠算法、份额余额、owner分配核对与轨迹 |
| PricingResult | candidate、逐行/逐步/尾差/出资及状态，明确税前阶段 |
| PricingInvariantChecker | check(facts,result)，独立核对计算证据及守恒 |

新增base PricingEngineTest及测试辅助PricingEngineTestData；core trade/RetailPricingCompatibilityTest。复用既有公式ID/version，不开放门店自定义公式。金额参数及资方来自可信后端owner适配，不作为前端请求DTO。

实施先RED，最小实现，再目标/全量回归、保护消融、独立只读评审与精确文件提交。

## §3 输入、公式及证据

### 3.1 输入及输出参数

| 类型 | 字段/用途 |
|---|---|
| PricingFormula.Input | facts:PricingRuleFacts，config:EffectivePricingConfig，steps:List<Step>（≤128），approvals:List<ApprovalEvidence>（≤64） |
| Step | kind:BenefitKind，operation:Operation，quote:Quote；asset由quote.owner.asset确定 |
| Amount | amountMinor:long，minBaseMinor:long；非负、JSON安全；金额券可按基数封顶，人工/资金精确金额不足时明确拒绝 |
| Percent | payableRateBp:int（0～10000），capMinor:Long（null/0沿原无封顶约定），minBaseMinor:long |
| OwnerAllocated | 使用原owner冻结分配，不能作为新公式未知算法的替代品 |
| Quote | owner:OwnerEvidence，quoteRef:String，basisHash:String（64位SHA-256），algorithm:Algorithm，bases:List<PortionAmount>，allocations:List<FundedAllocation> |
| FundedAllocation | portion:PricingPortion，amount:MoneyMinor，funders:List<FundingShare>（≤16）；金额/币种合计必须等于该份额，资方引用不重复 |
| FundingShare | funderRef:String（原owner命名空间下引用，≤64），amount:MoneyMinor；不默认推断商家或平台 |
| PricingResult | status:CALCULATED/REJECTED/UNAVAILABLE，candidate，lines，steps，violations，code；失败无candidate/金额行/计算步骤 |
| LineResult | source:原PricingUnit，priceReduction:MoneyMinor，assetOffset:MoneyMinor，remaining:MoneyMinor |
| StepResult | step:完整原输入/报价证据，basisTotal，appliedTotal，allocations:List<AllocationTrace> |
| AllocationTrace | portion，basis，roundedShare，correction，allocation:完整FundedAllocation |

可直接调用`new PricingEngine().calculate(input)`；报价与确认复算使用同一实现。失败时只读取status/code/violations，金额getter拒绝读取。输出stage固定BUSINESS_PRE_TAX；价格减免与资产抵扣是本阶段业务分类，不提前裁定积分、券和抹零的法定税务性质。

Quote不是客户端授权凭据。报价编号、来源权限、用户/门店归属、真实性、有效期及资方身份仍需原owner适配核验；basisHash只用于防止错误上下文、执行顺序及基数复用，不是签名。后续快照应冻结原quoteRef、basisHash、algorithm及完整分摊/资方，不能只保存合计。

### 3.2 固定阶段与公式

执行顺序：实际活动→金额券（现金/抵扣，保持原顺序）→折扣券（保持owner叠折顺序）→人工促销→储值计划→积分→人工抹零→获准现金舍入。顺序错误明确拒绝，不自行重排用户/owner意图。现金舍入仍受既有规则关闭，现金实收找零不属于本公式。

对金额券：`减免=min(面额,本次适用份额剩余基数)`。对折扣券：`减免=floor(剩余基数×(10000−应付基点)/10000)`，再依原owner封顶；`新余额=原余额−减免`。逐张计算而非乘总折扣后一次舍入。门槛使用本步骤原owner确认的剩余基数，其他门槛基数/算法必须提供对应获准合同，不能假定等价。

例：20000−2000=18000，九折再减1800，剩余16200；分项10000八折、5000九折剩余12500；同项10000八折后九折剩余7200。101连续两次85折：先减15，基数86再减12，剩余74，体现逐券舍入。

STORED_VALUE/POINTS/人工精确金额不足时不能悄悄截断以改变用户意图。所有金额最终转换检查溢出及JSON安全上限，中间乘法BigInteger，无double/float。原Currency/数量及来源修订继续沿原值对象。

源事实保留所有应收费商品。参与优惠的报价基数必须由原owner针对合格份额形成；不合格范围明确拒绝，不擅自从原报价删行、改价或猜测按数量拆分金额。排除商品、已付/UNKNOWN、根累计用券及套餐父叶规则由CKO-04统一重验。

### 3.3 算法、尾差和资方

SHARED_COUPON_V1直接调用原common.promotion.CouponCalculation.discount/allocate。分摊先按每行比例HALF_UP，再把正/负尾差优先调到最大基数；同基数按原顺序，不能把owner确认的分摊顺序换成界面排序。每份轨迹保存`原始比例舍入值＋尾差修正=最终分配`，独立checker重新计算比例舍入值及原共享分摊结果，防止两字段一起篡改仍相加正确。

例如金额1、基数[1,1]，原比例舍入[1,1]，首项修正−1，最终[0,1]。基金/资方不按这里重新划分：原owner给出的平台400、主体600原样保留，逐份额/逐券重新合计验证。

LEGACY_OWNER_COMPAT仅支持原冻结活动/券分摊；不支持新增人工率、储值或积分规则，相关kind明确拒绝。OWNER_FROZEN_V1保留原分配，roundedShare/correction为空，不能编造新舍入解释。它仍受新业务规则约束，不意味着旧零售所有历史组合已自动迁移；旧零售继续原Service及原合同。

DEDUCT_THEN_STORED_VALUE_V1可金额券后储值，折扣券明确拒绝；DEDUCT_THEN_DISCOUNT_V1依统一规则使用，折扣券＋储值仍被B02禁止。注册表只有上述三个公式version=1，不支持脚本或门店修改公式。

### 3.4 证据前置与独立复核

计算前核对完整实际活动清单，不能让虚增的活动先改变后续门槛，再把错误方案当无资格丢弃。每步核对完整顺序/上下文摘要、owner公式/算法/基数、完整源定价份额及实际分配；金额不一致返回UNAVAILABLE，不在无法确认的候选中声称最优。

计算后运行B01～B12，并由独立checker核对：源行身份及顺序、逐步基数/金额/资方/尾差、候选优惠与步骤完全对应、每行及全单守恒。`原商品额=价格减免＋资产抵扣＋剩余应付`。失败结果清除全部可用金额/份额输出，只返回状态及诊断。

### 3.5 与CKO-05连接

`PricingEngine#calculator(RecipeSource)`返回PlanCalculator。RecipeSource必须针对本次精确请求返回所有相关分配配方：RecipeResponse.COMPLETE包含1～64份Recipe（steps/approvals），INELIGIBLE是已证实不成立，UNAVAILABLE是无法完成。缺失、错误券/固定意图/活动清单或原owner证据不一致都阻止最优声明；不得先筛掉错误配方再把剩下的称为完整搜索。

每个配方重新调用同一calculate，合法候选交给05继续统一校验/比较。原05默认8张可选券、1024单位搜索预算仍约束回调及输出方案检查；每个回调内部另有最多64份配方的硬上限，不把外部owner/配方生成成本伪称为固定时长。正式API接线还需设置来源数量、请求时限及整体资源保护。

## §4 复用与剩余门禁

原CouponCalculation、PmtCoupon/MktCoupon、CouponAllocServiceImpl/CouponPortImpl及零售订单Service均未修改。新增兼容测试在两条真实分配链路上验证逐分舍入、封顶、主体/门店分组及platform/byMerchant资方；不以新引擎同一个helper的输出充当原零售预期。

原CouponPort只提供商家/门店分配，尚无通用源份额报价合同；不能凭LEGACY_OWNER_COMPAT名字补造这层证据。实际餐饮/美业/新报价接线、持有人/审批/账户真实性、税类/价税模式/资方出资资格及发布权限仍在后续owner合同任务核验。本轮无支付/库存/核销写入，所有算例均不是地区税票。

## L4 验证与交付

## §5 验证记录

最初目标类缺失；模型及空引擎阶段运行18项，15项断言失败、0错误，确认计算缺失可被测试捕获。首版18项通过；评审两项、零效果券及legacy明确范围均补RED后修复，27项通过。活动事实前置补RED后修复，最后新增混合资方及强化真实B12路径，共29项通过。

| AC | PricingEngineTest / 兼容测试方法 |
|---|---|
| F01 | deductThenDiscount；disjointAmountsAndControlledStackHaveExactResults；roundingIsPerCouponNotOneCombinedRate；capsAndThresholdUseTheCurrentApprovedScopeBasis |
| F02 | ownerAmountAllocationAndBasisMismatchBlockCalculation；replayingQuoteUnderAnotherActorOrExecutionOrderFails；rejectedWrongRecipeCannotHideAMissingRequestedCoupon；activityManifestMustMatchBeforeLaterThresholdCanRejectARecipe |
| F03 | proportionalRemainderIsVisibleAndFundersConserveEachLine；independentInvariantCheckFindsLineTamperingEvenWhenGlobalTotalStillMatches；coordinatedRoundingTraceTamperingCannotPreserveAFalseExplanation；mixedFunderSharesArePreservedWithoutRedistribution |
| F04 | formulaEngineCanSupplyTheBoundedPlanSelectorAndBeReusedAtConfirmation；unavailableOwnerRecipePreventsAnyBestPriceClaim |
| F05 | RetailPricingCompatibilityTest 4项：两模型基点/封顶、尾差平台资方、商家限定/门店分组、面额及同基数顺序 |
| F06 | percentCouponAndStoredValueStillConflictAfterArithmetic；manualDiscountAndLastWaiverRequireMatchingComputedAmountApprovals；sourceExcludedAndUnknownPortionsCannotBeAuthorizedByFormula（刷新quote hash后明确到B12） |

零售测试首次被沙箱禁止Mockito/Byte Buddy向本机测试JVM自附加代理，5项报初始化错误（含原LegacyCouponOrderReturnFactsTest）；不是金额算法失败。获准本机测试代理后，4项新增兼容＋3项原活动＋1项原退券事实测试均通过，不调用真实数据库或资金服务。legacy夹具显式提供有效时间范围，沿真实原owner可用性约束。最终全量/消融/架构结果见§6。

## §6 实施裁决与对账

原三参数calculate缺少owner报价/分配证据，落地为Input聚合可信事实、编译配置、完整有序步骤及审批；不把缺失数据用前端金额或虚构owner成功补齐。源数量拆分必须带原金额证据；税务及完整最终报价优化留CKO-07。


### 6.1 最终验证

| 检查 | 实际结果 |
|---|---|
| shop-core及依赖全量离线回归 | BUILD SUCCESS；auth-store 34、job-api 11、svc-client 10、shop-base 202、base-auth 28、store-mybatis 38、shop-core 414；均0失败/错误/跳过 |
| 本轮新增 | PricingEngineTest 29项、RetailPricingCompatibilityTest 4项通过 |
| 基数摘要消融 | 关闭摘要保护，跨actor复用报价测试1项断言失败、0错误；已恢复 |
| 尾差证据消融 | 关闭独立舍入复核，协同篡改轨迹测试1项断言失败、0错误；已恢复 |
| 资方守恒消融 | 关闭份额资方合计保护，测试1项断言失败、0错误；已恢复 |
| ArchitectureTest | 16项中15通过、1项历史基线失败：noFullyQualifiedReferences指出OpsPointsInspectionController中的全限定PathVariable；该生产文件相对HEAD无改动 |
| 独立评审 | 配方意图、独立舍入、活动前置问题均补失败测试后修复；最终源码复查无新增重要问题 |

复现命令（Java 21，仓库根目录）：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -f backend/pom.xml -pl shop-core -am test
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
```

全量回归需要允许测试绑定本机临时HTTP端口及Mockito向本机JVM附加代理。架构命令会复现上述已知失败，不能解释为全架构通过。运行日志位于本机临时目录`/private/tmp/cko06-full-core-suite.log`和`/private/tmp/cko06-architecture.log`，不作为仓库永久附件。

### 6.2 范围对账

本轮共12个文件：6个新增生产类、3个新增测试/夹具、本文及2份既有设计/计划更新。未改原零售生产类、POM、数据库或API；未执行支付、核销或库存写入。原有其他工作区改动不纳入本次提交。

下一步CKO-07：按既有地区税设计补齐价税模式、地区税配置及最终定税，并在税后金额上对齐候选比较；本轮BUSINESS_PRE_TAX结果继续作为明确的中间阶段。

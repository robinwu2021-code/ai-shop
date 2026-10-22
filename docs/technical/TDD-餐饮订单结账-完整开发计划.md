# 餐饮订单结账完整开发计划

> 执行者：使用superpowers:executing-plans按任务顺序实施；每个任务完成失败测试、最小实现、验证、独立评审及范围明确的提交。不得仅因本计划存在就启动编码或声称能力已交付。
>
> 状态：共享能力归属已确认，开发待执行 · 2026-10-22 · 档位2 · 代码基线`217f4182a`，设计包含本工作区尚未提交的规则、存储和税费补充。执行前重新记录HEAD/工作区状态，不覆盖其他会话改动。

**目标：** 基于原商品、订单、支付、库存及账号门店能力，实现从规则配置、报价到先付/后付、收退款、打印、日结及恢复的餐饮结账闭环，并保留零售、美业共享内核的接入路径。

**架构：** shop-base提供纯规则/定点内核和唯一SPI；shop-core.trade承担报价、经济确认及订单权威编排；餐饮五模块提供行业事实、B/C入口和结账流程。独立PAY/库存依真实typed合同接入，不在food复制账本。本文“结账”是顾客订单Checkout，不是另建供应商结算/支付清算平台。

**技术栈：** Java21、原平台Spring/MyBatis-Plus/Flyway、已有幂等/outbox/作业/审计；沿父POM，不增加规则引擎依赖或新Maven模块。

**设计依据：** [存储与Java](./TDD-统一试算引擎-存储与Java实施方案.md)、[初始规则及税费](./TDD-统一试算引擎-初始规则与结账税费.md)、[优惠组合](./TDD-三行业优惠规则-组合与多券.md)、[独立支付库存核验](./TDD-餐饮系统-独立支付库存合同核验.md)、[原后端计划](./TDD-餐饮系统-后端开发计划.md)、[API任务索引](./design/餐饮系统-开发任务对照索引.md)、[ADR-040](./ADR/ADR-040-餐饮销售配置与耗用权威边界.md)。

## L1 交付范围与顺序

后端优先，按31个任务推进。先交付内核与配置，再交付真实报价和单支付根先付闭环，之后扩展后付、现金、人工调整、AA/混合支付、储值积分及售后。打印、恢复和对账属于营业闭环，不作为最后才考虑的装饰功能。

“完整计划”覆盖全部目标，不代表所有能力一次发布。各能力独立开关、独立验收；Keeta只规划渠道合同，暂不开发真实适配；离线结账单列专项，不因未来前端能计算就允许离线扣储值或核销平台券。

## L2 里程碑

![共享内核与权威边界](./diagrams/unified-pricing-engine.svg)

图中的共享金额权威和原资金/库存能力决定任务依赖；food负责结账范围和行业编排，不替代原账本。

| 里程碑 | 任务 | 可验收结果 | 原计划映射 |
|---|---|---|---|
| M0 合同冻结 | CKO-01 | 精确API、状态、字段、权威及能力门禁清单 | CORE-01～04、ORD-02、PAY-01～04 |
| M1 纯内核 | CKO-02～07 | 规则校验、优惠计划、金额及税计算可独立验证 | ORD-02 / E1 |
| M2 配置发布 | CKO-08～09 | 行业模板、门店覆盖、初始数据、预览/发布/回滚 | E2 |
| M3 真实报价 | CKO-10～11 | B/C读取真实菜品/套餐/配料，报价不消费资产 | ORD-02，API041/131 |
| M4 单支付根在线闭环 | CKO-12～15；营业还须24、26及31对应门禁 | 真支付/库存合同、建单恢复、先付确认可追溯 | CORE-01、ORD-03、PAY-01/02 |
| M5 现场经营 | CKO-16～18 | 后付多批次、现金当班找零、人工审批 | CORE-02、ORD-06、SHIFT、PAY-03/04 |
| M6 复杂结账及资产 | CKO-19～22 | AA、跨根混合、订金、储值与积分逐能力验收 | CORE-03、PAY-04 |
| M7 经营闭环 | CKO-23～27 | 售后、打印、日结报表、对账、三端联调 | CORE-04、RET、PRN、CLOSE、RPT |
| M8 行业与专项扩展 | CKO-28～30 | 零售/美业接入，离线能力及渠道预留 | 原M8；独立专项合同 |
| M9 发布验收 | CKO-31 | 双部署、沙箱链路、降级、恢复演练及证据 | 各里程碑按能力可分批发布 |

CKO-24/26不是必须等CKO-23才开始，详见任务依赖。此表是能力分组，不是要求把任务编号机械串行执行。

## L3 任务及实施约束

## §0 需求 → 计划对账

| AC | 要求 | 任务 |
|---|---|---|
| C01 | 行业默认、门店覆盖、强制规则及不可变版本 | 01、03～04、08～09 |
| C02 | 多券、分项、金额后折扣、真实owner资格及确定性 | 02、04～06、11 |
| C03 | 地区VAT含税/价外、税类、税点及最终定税 | 07、10～11、18、22～25 |
| C04 | 先付后吃与先吃后付、多批加菜、线上/线下/堂食/外送 | 11～16、30 |
| C05 | 现金实收找零、人工打折扣减抹零及审批 | 17～18 |
| C06 | AA、拼并桌、跨根、混合与订金不重复收款 | 19～20 |
| C07 | 储值/积分未来兼容，真实接线后才开放 | 21～22 |
| C08 | 退餐与财务退款分离、原行金额税费及资金退款 | 23 |
| C09 | 客单、厨房单、打印恢复、当班日结报表 | 16、24～25 |
| C10 | 幂等、UNKNOWN原键恢复、对账、权限与双部署 | 01、10、12～15、26～27、31 |
| C11 | 共享多行业内核、旧零售兼容、受控离线和渠道扩展 | 28～30 |

## §1 全局约束、状态及代码位置

### 1.1 全局约束

**2026-10-22用户确认：共用部分在现有零售及核心代码中开发，餐饮和美业调用基础能力。** 这确认实现归属，不表示所有外部合同、字段或接口已经验收，CKO-01仍先核验。

| 能力 | 权威实现归属 | 餐饮/美业职责 |
|---|---|---|
| 通用商品、SKU、价格及计价身份 | 现有商品/零售基础域 | 菜品/服务配置和源事实映射，不维护第二套商品权威 |
| 行业模板、门店覆盖、规则/公式/税运算 | shop-base.common.pricing＋shop-core.trade | 提供行业参数、资格及场景事实，不复制计算器 |
| 报价快照、应收版本、重算确认 | shop-core.trade，7张ord表 | 保存原业务引用，展示及提交获准源范围 |
| 通用范围锁、AA金额分摊、混合支付计划、订金抵付 | 核心交易能力及原资金owner | 桌台/座位/服务项目与核心份额的映射及流程 |
| 券、储值、积分、支付、库存及恢复 | 各现有权威域及唯一SPI；共享改造在原提供方 | 调用，不直接改余额、资金账或库存实存 |
| 通用打印、报表、审计与审批能力 | 原共享能力域/核心现有设施 | 行业模板、展示、事件和业务签核编排 |
| 桌台、点餐批次、厨房、配方耗用意图 | food模块 | 行业特有流程；fnb表不升格为通用金额/资金账 |
| 预约、技师、服务任务、疗程履约 | 原美业模块 | 行业特有流程；消费金额及资产确认复用基础能力 |

共享能力在原零售基础设施上做兼容扩展，不等于所有类都放进零售OrderService。纯运算归common，交易服务归trade，支付/库存/打印/报表各归原owner。行业模板差异以配置及typed事实表达；核心不能import food/美业实体或调用其私有Service。

- 新增Java遵守[平台Java规范§七](../../.claude/skills/ai-shop-dev-standards/references/java.md)：接口/实现分包、Entity完整表名驼峰、嵌套Mapper、无Repository转发；跨域接口仅放shop-base.spi，food核心不持有trade实体。
- 金额long最小币种单位、比例基点、数量BigDecimal；中间乘法检查溢出，币种精度/JSON安全整数业务上限受控。不使用double/float计算钱。
- 配置取不可变发布版本；门店不能删除强制规则。模板升级不自动追改旧订单；法定税率有效性按可信税点验证，不能靠锁旧模板延长失效税率。
- 默认最多1张折扣券＋1张金额券；活动禁券、折扣券禁储值、券后禁人工打折/抹零。人工扣减默认同人工折扣互斥，纠错有独立依据；现金找零不属于抹零。
- 试算无资产/库存写副作用；确认前同内核重验。sourceRef、durableIntentNo及原核销键稳定，quoteNo不能作为不断变化的扣款源键。
- UNKNOWN不代表失败；不能换键重扣、先释放再重占。外部调用不包在长本地事务；使用原意图/outbox及查回。
- 已应用V1～V7餐饮迁移和其他历史迁移不修改；新增迁移号执行时读取最新编号后分配。本库7张ord表归核心迁移，fnb增量归餐饮迁移；既有表不得重复CREATE。
- 用户/主体/门店/行业及税登记从可信上下文得出；C端不发布配置、不提交员工调整、不读他人quote；新权限及三语错误码同任务登记。
- food-web Controller在宿主挂载；独立food-server沿原方案只提供内部接口；通道模块不依赖food-core，平台核心不依赖food实现。
- 当前工作区多人共享；实施建立隔离工作区或明确文件归属，按任务精确路径提交，禁止整目录git add或覆盖别人修改。

### 1.2 状态分开处理

| 对象 | 基础状态/处理 | 禁止混淆 |
|---|---|---|
| 报价 | READY/EXPIRED/SUPERSEDED；经济内容不可变 | READY不是已核销或已付款 |
| 结账范围 | DRAFT/FROZEN/COLLECTING/SETTLED/CANCELLED | 只有原权威已收清且无未知份额才SETTLED |
| 收款意图 | 原表PREPARED/SENT/CONFIRMED/UNKNOWN/FAILED | 超时进入UNKNOWN；原键查询，不生成另一成功副本 |
| 部分收款 | 原范围及各支付计划分别保存 | 不给原表随意塞PARTIAL状态替代资金分摊合同 |
| 退餐/退款/厨房 | 分别推进、以原引用关联 | 厨房退菜不等于财务退款成功 |
| 日结 | 沿已有设计签核与修订 | 日结不是关闭支付回调、也不是篡改迟到资金事实 |

CKO-01核验每个提供方真实状态和迁移字段，将本表映射为明确状态机；不得仅按设计状态字符串模拟资金成功。

### 1.3 精确文件路径约定

下列前缀在任务中展开为仓库根下的实际路径；包名由src/main/java后路径唯一确定。单个任务列出的类按实际职责建文件，不生成空壳。

| 前缀 | 路径 |
|---|---|
| K | `backend/shop-base/src/main/java/ai/neargo/shop/common/pricing/` |
| KT | `backend/shop-base/src/test/java/ai/neargo/shop/common/pricing/` |
| SPI | `backend/shop-base/src/main/java/ai/neargo/shop/spi/` |
| C | `backend/shop-core/src/main/java/ai/neargo/shop/trade/` |
| CT | `backend/shop-core/src/test/java/ai/neargo/shop/trade/` |
| A | `backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/` |
| F | `backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/` |
| FT | `backend-plugins/shop-industry-food/food-core/src/test/java/ai/neargo/shop/food/scenario/` |
| W | `backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/` |
| WT | `backend-plugins/shop-industry-food/food-web/src/test/java/ai/neargo/shop/food/api/` |
| H | `backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/` |

共享打印/报表现有供应者路径在CKO-01核对并登记到任务对照索引；未找到真实实现时在原能力域补齐，不在food临时造PrintService/报表账本。任务中的方法为目标合同，实施前与已有同名能力合并，不并存等义Service。

### 1.4 全局评审重点

1. 同价但券身份、税点、覆盖份额、资方不同：CKO-15要求重新确认。
2. 支付成功但绑定/outbox/打印之前进程崩溃：CKO-14/24/26原键恢复。
3. 同SKU不同份额、套餐父叶、AA拆行影响计数/税舍入：CKO-05/07/19固定身份。
4. 现金金额不足、电子超额、余额不足、无原审批：CKO-17～22不得静默转抹零/现金或切换资金源。
5. 日结后迟到回调、退款、重复事件：CKO-25/26追加事实/受控修订，不能修改历史金额以对平。

## §2 逐任务开发清单

### 2.1 每个代码任务的执行周期

每个任务的下列清单按顺序执行：先写指定测试并确认目标分支失败，再实现，然后运行目标测试、相关架构/i18n/数据库检查、必要消融，最后审查并仅提交本任务文件。缺少外部合同只允许推进不依赖它的工作，不能以成功Mock代替真实提供方营业验收。

基础命令（仓库根；JDK路径按已安装Java21核验，禁止联网解析）：

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn -o -f backend/pom.xml -pl shop-base -am test -Dtest=MoneyMinorTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -f backend/pom.xml -pl shop-core -am test -Dtest=PricingConfigServiceTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -f backend-plugins/shop-industry-food/pom.xml -pl food-core -am test -Dtest=DiningQuoteTest -Dsurefire.failIfNoSpecifiedTests=false
```

每个任务将-Dtest替换为其列出的真实测试类；查看目标模块Surefire报告，必须有目标测试实际执行的计数，不能只看BUILD SUCCESS。API、数据库和remote场景需对应模块集成测试，最后执行两个reactor完整相关验证；共享构件变化先install再验证独立food，避免使用旧.m2包。上面命令是计划，未在本轮运行。

### CKO-01 合同与规范冻结

依赖：无。关联：全部能力；先完成本项再扩运行时代码。

文件：修改本计划§6、原API任务索引、原存储/税费TDD；核验`SPI/settle/SettlePort.java`、`SPI/product/StockPort.java`、原券/储值/积分Port、`F/mapper/FoodMappers.java`及两处迁移目录。

产出：逐动作记录输入/输出、来源身份、状态、幂等/查回、owner、真实实现路径、支持部署方式、能力开关、测试证据。API041/131保留原`/stores/{storeNo}/quotes`资源，不额外注册先前候选cart quote同义路由；明确报价Service门面与原DiningOrderService的委托关系。

- [x] 对照真实源码和现有迁移，输出7张ord新表与fnb已有表字段差异、唯一约束及状态映射；不重建已有表。
- [x] 为PAY、库存、后付、AA、储值混合、积分税务、打印分别标SUPPORTED/CONTRACT_GAP/EXTERNAL_VERIFICATION_REQUIRED，附可追溯证据。
- [x] 登记权限/错误码、canonical API及类清单；未来新合同给出精确签名后再进入相应任务。
- [x] 评审字段/方法/任务映射及原零售影响，保存可执行合同基线；只提交本任务文档变化。

完成证据：[CKO-01核验收据](./TDD-订单结账-CKO01合同与字段核验.md)及[机器合同基线](./design/schema/pricing-checkout-contract-baseline.json)。仅源码/迁移核验完成，外部合同补齐与营业验证留在对应任务；02～31尚未执行。

### CKO-02 定点值对象与标准事实

依赖：01。文件：新增`K/MoneyMinor.java`、`K/PricingFacts.java`、`K/PricingUnit.java`及`SPI/trade/PricingSourceRef.java`，公共来源引用仅SPI一份；新增`KT/MoneyMinorTest.java`。

接口：`MoneyMinor#add/subtract`；`PricingUnit`固定sourceLineNo/componentRef/quantityRange/taxInvoiceLineRef，qty不使用double。

- [ ] 写MoneyMinorTest#rejectOverflowAndCurrencyMismatch：MAX_VALUE加1、不同币种相加均拒绝；0.1数量按受控精度计算，不静默截断。
- [ ] 运行目标测试确认实现前失败；实现最小金额/事实record及边界校验。
- [ ] 检查JSON安全金额上限、负数/比例/数量边界，运行测试并仅提交本任务文件。

### CKO-03 JSON类型化编译器

依赖：02。文件：新增`K/PricingConfigCompiler.java`、`EffectivePricingConfig.java`、`RuleDefinition.java`及有限参数record；新增`KT/PricingConfigCompilerTest.java`。

接口：`compile(template, override, systemConstraints, capabilities) → CompilationResult`；输出不可变配置及问题；无IO，不使用任意反射路径或脚本。

- [ ] 写rejectUnknownEvaluatorAndInvalidOverride：未知Evaluator/路径/null、SINGLE却total=3、门店删除强制规则拒绝；false保留、数组整替换、reset恢复继承。
- [ ] 确认失败后实现字段白名单/有限参数解码/覆盖合并/编译；强制规则以代码目录核验，不信客户端mandatory。
- [ ] 验证同输入编译/hash确定性；缓存按不可变版本；运行目标测试并独立提交。

### CKO-04 规则校验器与执行证据

依赖：03。文件：新增`K/PricingRuleEngine.java`、`RuleEvaluator.java`及互斥/数量/能力/审批/阶段/守恒校验实现；`KT/PricingRuleEngineTest.java`。

接口：`validate(facts, config, candidate) → List<RuleViolation>`；每项含ruleNo/version/阶段/code/关联源项；所有适用禁止共同生效。

- [ ] 写denyCannotBeOverriddenByAllow：活动+券、折扣券+储值、券+人工抹零拒绝；“允许折扣券+金额券”不能放行三者加储值。
- [ ] 确认失败后实现B01～B12；实际订单活动参与事实与门店活动开关分开。
- [ ] 验证EXPLICIT错误返回全部可解释冲突；校验不核销资产；消融B02必须失败，恢复后验证并提交。

### CKO-05 多券候选与份额计划

依赖：04。文件：`K/BenefitPlanSelector.java`、`CalculationPlan.java`、`BenefitSelection.java`、`KT/BenefitPlanSelectorTest.java`。

接口：`select(context, selection) → PlanSelectionResult`；EXPLICIT/CANDIDATE_POOL/AUTO_BEST、DISJOINT/BEST_ONE/受控STACK。

- [ ] 写explicitCannotDropCoupon、countsAssetsAndChecksQuantityOverlap：同券跨5行计1张；两张折扣券同一份额DISJOINT拒绝，不同份额可用仍受全单上限。
- [ ] 实现有界候选、稳定同价选择、根账单累计用券范围；超过复杂度限制返回PLAN_SEARCH_LIMIT，不伪称全局最优。
- [ ] 验证未采用候选不核销、套餐父叶不重复；测试通过并提交。

### CKO-06 公式、分摊与金额守恒

依赖：05。文件：`K/PricingEngine.java`、`FormulaRegistry.java`、`PricingFormula.java`、`PricingResult.java`、`PricingInvariantChecker.java`、`KT/PricingEngineTest.java`。

接口：`calculate(PricingFacts, EffectivePricingConfig, BenefitSelection) → PricingResult`；原优惠算法版本通过获准事实/owner定义使用，不复制一套旧券权威。

- [ ] 写deductThenDiscount：未税20000先减2000后9000基点得16200；DISJOINT的10000八折+5000九折得12500；受控STACK10000得7200。
- [ ] 实现固定阶段、逐行基数、资方及尾差证据，原legacy分摊保持；owner不认可相同顺序/基数时阻断。
- [ ] 校验行/总/券/资方守恒及确定性，并增加`CT/RetailPricingCompatibilityTest.java`验证现有零售金额/舍入/资方不变；不等到28才首次做零售回归。无税促销算例不得冒充地区税票；验证并提交。

### CKO-07 地区税与最终定税

依赖：06。文件：新增`K/TaxEngine.java`、`TaxAdjustmentResolver.java`及TaxPolicy/TaxTreatment/TaxLineResult；新增`KT/TaxEngineTest.java`、`TaxAdjustmentResolverTest.java`；向量来自规则种子JSON。

接口：`estimate(lines, policy) → ProvisionalTaxResult`、`finalizeTax(adjustedLines, policy) → TaxResult`；固定税发票行身份，与促销份额分开。

- [ ] 写inclusiveExclusiveMixedClassesAndLineRounding：未税10000加税500；含税10500拆税500；两行未税10各税1；未知税类拒绝。
- [ ] 实现可信地区/税点匹配、税前/含税两模式、价格优惠与对价分类、积分/抹零后单次最终定税；不可表达gross减免拒绝。
- [ ] 运行8个标准向量，验证不双减积分、不因拆券/AA重算原税、不侵入已安排S；测试与消融通过后提交。

### CKO-08 4张配置表及发布事务

依赖：03；01字段冻结。文件：核心增量迁移、`C/entity/OrdPricingTemplate.java`等4配置Entity、现有`C/mapper/TradeMappers.java`、`C/service/PricingTemplateService.java`及impl、`StorePricingConfigService.java`及impl；`CT/PricingConfigServiceTest.java`及真实DB IT。

接口：`saveDraft(context,no,req)`、`preview(context,no)`、`publish(context,no,req,idempotencyKey)`；发布时插入revision与CAS切换指针同事务。

- [ ] 写concurrentPublishHasOneWinner：草稿CAS并发仅一胜，失败无孤立revision/outbox；同幂等键异body拒绝。
- [ ] 追加4表迁移、实体和嵌套Mapper，实现锁版/初始化/发布/回滚；旧revision不可改。
- [ ] 真实数据库验证唯一约束、事务、空首次指针与回滚；不以H2/Mock代替目标数据库证据；验证并提交。

### CKO-09 初始数据与配置API

依赖：07、08。文件：`C/service/PricingSeedService.java`及impl、`C/api/ops/OpsPricingTemplateController.java`、`C/api/biz/BizPricingConfigController.java`、对应dto和权限/i18n目录；`CT/PricingSeedServiceTest.java`。

接口：`importDrafts(seed,registryVersion,DRY_RUN|DRAFT)`；Ops模板、B门店草稿/预览/发布/升级/回滚按原设计合同。

- [ ] 写importIsIdempotentAndDoesNotPublish：20规则、3模板、2AE税档、示例门店/能力夹具不进入真实配置；同版本异hash拒绝。
- [ ] 实现受控种子导入、地区税档及门店覆盖API；平台税率权限和门店可改项分开，C端无配置写入口。
- [ ] 验证越权、三语错误码、模板升级不隐式迁移、法定税档有效性门禁；验证并提交。

### CKO-10 3张报价表与快照

依赖：06～08。文件：核心增量迁移、`C/entity/OrdPriceQuote.java`、`OrdPriceQuoteLine.java`、`OrdPriceQuoteAllocation.java`、`C/assembler/PricingQuoteAssembler.java`及TradeMappers嵌套Mapper；`CT/PricingQuotePersistenceTest.java`。

接口：保存quote头/行/分摊同一短事务；factsHash、economicsHash、requestHash分别规范化；确认引用原不可变quote。

- [ ] 写quoteIsAtomicImmutableAndScoped：写分摊失败整份回滚；别店/别用户quote不可读；金额不可UPDATE；时间/UUID不影响经济hash。
- [ ] 实现3表及税/对价字段；quote过期、被替换与支付状态分离；有订单/意图引用不得过期物理清除。
- [ ] 验证同一发票行的份额税合计、积分降价/对价字段互斥及金额脱敏；真实DB测试通过并提交。

### CKO-11 餐饮真实事实与B/C报价

依赖：09～10。文件：`SPI/trade/OrderPricingPort.java`、`PricingSourceFactsPort.java`及typed DTO；`C/service/OrderPricingService.java`及impl、`C/port/OrderPricingPortImpl.java`、`C/api/internal/InternalPricingController.java`；`A/service/DiningPricingService.java`、`F/service/impl/DiningPricingServiceImpl.java`、`F/port/FoodPricingSourceFactsPortImpl.java`、原API041/131对应W/biz/BizDiningQuoteController.java与W/mp/MpDiningQuoteController.java；`FT/DiningQuoteTest.java`。

接口：`quote(context, QuotePricingReq) → PricingQuoteVO`、`getQuote`、`revalidate`；原quote资源读取cart/check/scope引用，不接收可信单价/税率。

- [ ] 写usesAuthorityPriceAndHasNoAssetSideEffects：时段/渠道餐牌、收费小料、套餐补差来自真实冻结版本；前端传价拒绝，不预占券/余额/库存。
- [ ] 实现事实/能力加载与纯内核、报价持久化、source版本前后检查；独立food经typed客户端调用核心，缺来源/owner能力返回BLOCKED。
- [ ] 验证B店铺与C会话隔离、乱序quote/过期/税点未确定标记、canonical接口契约及双装配；验证并提交。

### CKO-12 独立PAY普通收退款合同

依赖：01；可与内核阶段独立推进。文件：现有`SPI/settle/SettlePort.java`、`backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/client/PayInternalApi.java`、原PAY Controller/Service及`backend/shop-app/src/main/java/ai/neargo/shop/payclient/`typed适配；具体owner文件在01逐方法登记；对应PAY/provider与consumer合同测试。

接口：原`init/openPayment`、原键查询、关单、发起退款及queryRefundExecution；不以权益专用查询冒充普通支付合同。

- [ ] 写originalInstructionSurvivesTimeout：提供方成功但客户端超时，原键查询返回同一结果；错币种/错主体/不同请求hash拒绝。
- [ ] 最小扩充原typed PAY接口、鉴权、错误语义及本地/远程装配；未接事实Port不能返回假成功。
- [ ] 本地与远程相同合同测试、沙箱trace及原退款查回通过后开放独立收款；仅HTTP200/启动成功不算完成。

### CKO-13 独立库存承诺与恢复合同

依赖：01；可与12独立推进。文件：现有`SPI/product/StockPort.java`、`backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/service/ReservationService.java`、`backend/shop-app/src/main/java/ai/neargo/shop/invbridge/port/InventoryStockPort.java`及核验后的内部typed通道；provider/consumer测试。

接口：原预留/提交/释放/履约及按原引用查询；后付承诺和取消墓碑沿原权威状态扩充，不能假装已支付分配。

- [ ] 写queryOriginalReservationAndRejectResurrection：预留成功响应丢失可查回；释放/取消后的迟到请求不复活库存。
- [ ] 在库存owner最小补查询/远程合同与装配，预留计量和餐饮配方耗用各沿原事实，不由quote扣库。
- [ ] 验证并发、重复、短缺、取消与履约竞争及真实隔离库/远程链；验证并提交。

### CKO-14 正式下单、批次绑定与恢复

依赖：11～13。文件：`A/service/DiningOrderService.java`、`F/service/impl/DiningOrderServiceImpl.java`、批次/核心绑定/源行分配Entity及FoodMappers；`W/biz/BizOrderBatchController.java`、`W/mp/MpOrderBatchController.java`；`FT/DiningPlacementRecoveryTest.java`。

接口：原API042/132下单、043等查询及取消；源cart版本、quote/hash、稳定intentNo、原核心订单引用。

- [ ] 写coreCreatedBeforeBindingRecoversOnce：核心创建成功本地断电，重试绑定同一核心单；相同键异请求拒绝。
- [ ] 实现短事务意图→重验报价→核心建单→绑定/源行/outbox；失败/UNKNOWN只按原源查询。
- [ ] 测数量、行金额及税费绑定守恒，取消竞争、零售待付超时保持；核验独立装配后提交。

### CKO-15 单支付根电子收款及先付闭环

依赖：14。文件：`A/service/DiningCheckoutService.java`、`F/service/impl/DiningCheckoutServiceImpl.java`、FnbSettlement/FnbSettlementItem/FnbChargeIntent对应Entity/Mapper、`W/biz/BizDiningCheckoutController.java`、`W/mp/MpDiningCheckoutController.java`；`FT/DiningPrepayFlowTest.java`、`PricingConfirmationTest.java`。

接口：原056～060、134/135/140；create/get/pay/cancel/finalize；`OrderPricingService#confirm`由获准意图调用，非C端无边界扣款API。

- [ ] 写sameAmountDifferentBenefitNeedsAck及payTwiceCreatesOneIntent：总价相同但券身份/税点/范围变更仍需确认；重复点击原意图复用。
- [ ] 实现冻结单根未付范围、同内核复算、原资产确认/PAY流程及查询恢复；支付成功事件才触发先付受理/厨房。
- [ ] 验证超时、回调乱序/重放、关单与支付竞争、0应收获准结清；原权威未收清不得finalize，测试通过提交。

### CKO-16 后付受理与多批次加菜

依赖：13～15。文件：修改核心原订单生命周期策略、`F/service/impl/DiningOrderServiceImpl.java`及原厨房受理事件适配；`FT/DiningPostpayFlowTest.java`、核心RetailOrderCompatibilityTest。

接口：获准POSTPAY接单/库存承诺/履约与追加批次；不把未支付订单调用“已支付分配”。

- [ ] 写acceptedUnpaidMealSurvivesRetailTimeout：后付获准受理且有库存承诺才派工；未付不被零售待付超时误撤，零售原超时仍有效。
- [ ] 实现先付/后付门店策略、冻结批次价及真实税点、加菜独立源、未付范围汇总；多支付根不伪合并成功。
- [ ] 测结账冻结与加菜竞争、部分收清还有新批次、取消/退餐与厨房状态独立；验证并提交。

### CKO-17 现金当班、实收与找零

依赖：15；后付现金再依赖16。文件：`A/service/CashShiftService.java`及F实现、FnbCashShift/Entry及原对应表Mapper、`W/biz/BizCashShiftController.java`、BizCashEntryController.java、`K/CashTenderPolicy.java`；`FT/DiningCashPaymentTest.java`。

接口：原068～071及获准checkout现金方式；amount/currency/shift/source intent、现金权威支付引用。

- [ ] 写cashRequiresOpenShiftAndRecoversOnce：现金应付4500、实收5000找零500；不足拒绝；未开班不能收，C电子支付不写现金entry。
- [ ] 实现当班、交班及资金支付引用幂等绑定；系统记录现金支付事实仍走原PAY合同，不只在food标PAID。
- [ ] 测权威现金已成功本地断电、重复交班/entry、负实收/电子超额不找现金；验证后提交。

### CKO-18 人工调整、审批与税额重验

依赖：07、15；现金使用依赖17。文件：`C/policy/PricingAdjustmentPolicy.java`、`K/ManualAdjustmentPolicy.java`、`W/biz/BizDiningAdjustmentController.java`及原调整/审批实体与Mapper；`FT/DiningAdjustmentTest.java`。

接口：原API061，kind/amountBasis/reasonCode/approvalRef/expectedReceivableVersion；调整发布新应收版本，不改已付事实。

- [ ] 写couponForbidsWaiverAndApprovalIsScoped：有券不能人工抹零/打折；过期/错店/错人/超额度审批拒绝；现金不足不自动抹零。
- [ ] 实现纠错、人工打折扣减、末段抹零及税务分类；明示gross/未税量纲，不可精确表达则拒绝/新quote确认。
- [ ] 测多次小额累计绕限、券后假调价、已付/UNKNOWN不可侵入及税前税后守恒；验证并提交。

### CKO-19 AA、按菜分摊与桌台变更

依赖：15～16、18；核心03子合同通过。文件：核心trade原范围/支付分配能力、`K/ScopeAllocationPolicy.java`、餐饮Checkout/Check服务及原fnb范围表；`FT/DiningSplitPaymentTest.java`。

接口：精确份额scopeRef/version、原rootScopeNo、按菜/按金额/按人获准模式；拼桌/并桌不创建新的优惠资格根。

- [ ] 写concurrentAaCannotPaySameQuantityTwice：同源行同份额竞争仅一胜；已付/UNKNOWN不可重收；两人分摊不重算原行税额。
- [ ] 实现原根范围冻结、尾差承接、券累计次数及转并拆桌保留来源；并桌不能移动已付资金。
- [ ] 测0.01尾差、同SKU不同份额、退餐与AA竞争；范围锁合同未实现模式保持关闭，验证并提交。

### CKO-20 跨支付根、混合支付及订金

依赖：19、12；原CORE-03逐能力扩充。文件：核心原收款分配/订金能力、餐饮Checkout intent/item映射、`FT/DiningMixedPaymentTest.java`。

接口：统一计划下的各root/各支付leg固定源键、quote版本、金额及原订金引用；订金抵付/取消退款合同分别定义。

- [ ] 写oneLegUnknownCannotRestartWholePlan：两leg一成功一UNKNOWN不重收成功leg；跨根已清部分保留，整体不伪标结清。
- [ ] 实现计划金额/资方/税与各根分摊、逐leg幂等恢复、获准订金应用及退款；失败补偿沿原owner，不本地改余额。
- [ ] 验证不支持跨主体/跨币种及超额抵扣拒绝，重复订金应用不发生；各子能力独立开关与沙箱验收后提交。

### CKO-21 餐饮储值接线

依赖：20及真实资金来源/部分支付合同；无合同可先完成只读禁用状态和契约测试。

文件：原`SPI`储值/权益唯一Port、PAY对应实现、核心资金适配及餐饮事实来源；`CT/StoredValueFundingContractTest.java`、`FT/DiningStoredValueTest.java`。

接口：原来源quote/prepare/commit/release/query；支持范围、币种、余额、本金赠金、授权、可部分支付必须由owner明确返回。

- [ ] 写unsupportedFoodOrPartialBalanceIsBlocked：当前CORE/SERVICE_USE全额限制不被绕过，applicableMinor不等于可扣款授权。
- [ ] 真实合同具备后接餐饮稳定source及本金赠金计划；折扣券互斥、用户明确授权、储值不减税基、不转现金找零。
- [ ] 测超时原键查回、余额变动需新确认、成功后重复提交不再扣款；沙箱证据及能力开关验收后提交。

### CKO-22 积分末段抵扣

依赖：07、18、20的资金计划合同；储值关闭且S=0时不依赖21，启用储值＋积分组合才额外依赖21。原积分来源/扣减/恢复能力须完成核验。

文件：原`SPI/points/`合同及owner、`K/PointsPlanPolicy.java`、税调整适配；`CT/PointsFundingContractTest.java`、`FT/DiningPointsTest.java`。

接口：用户opt-in、owner换算/额度、税Treatment、prepare/commit/release/query或原等效可恢复协议；不假设存在PointsDebitPort。

- [ ] 写pointsAfterStoredAndTaxTreatmentRequired：储值全额覆盖时积分0；未知积分税分类拒绝；降价积分和对价积分不双扣。
- [ ] 最小补原积分owner合同后接内核/确认；积分不侵入S及已付/UNKNOWN，不因反复quote重复预占。
- [ ] 验证并发余额、原键恢复、税调整不可表达拒绝、无接线不展示可用积分；验证并提交。

### CKO-23 退餐、原行部分退款与补偿

依赖：15、12～13；复杂分摊已启用时再依赖19～22相应能力。文件：原CORE-04/退款/库存接口、`A/service/DiningReturnService.java`及实现、原FnbReturn及行表Mapper、`W/biz/BizDiningReturnController.java`；`FT/DiningPartialReturnTest.java`。

接口：原062～064、源行数量/原分摊/已退与在途量、原退款executionNo；整单退款先支持，按菜退款另门禁。

- [ ] 写refundCannotExceedOriginalLineTaxOrFunding：分两次退税累计不超原税/优惠/资金；已在途退款份额不可再退。
- [ ] 实现厨房撤菜与财务退款独立流程、原路径原资方回退、库存归还/已耗用处理；美业在线储值冲正不因本任务默认新增。
- [ ] 测退款成功响应丢失、分账回退失败、资产释放失败、跨日及AA后部分退；未知原键恢复、对账追踪，验证提交。

### CKO-24 打印与凭证

依赖：15；现金内容依赖17，退款凭证依赖23；无需等待全部复杂资产功能。

文件：核验后的原共享打印owner及唯一Port、餐饮已确认事件适配、原065～067入口；`FT/DiningPrintContractTest.java`及打印owner测试。

接口：confirmedQuoteRef/intentNo/sourceEventNo/templateVersion/printerRef；税票字段沿原开票权威，出纸任务不能生成第二份资金成功。

- [ ] 写paymentSuccessPrintFailureDoesNotRecollect：打印失败可重试/重打但不重新付款；同事件只建一个逻辑打印任务，物理出纸不承诺网络exactly-once。
- [ ] 实现客单/税明细/实收找零/退款单/厨房单不同模板及脱敏，重打显式标识并记录原因。
- [ ] 先模拟设备协议，再实机断网/缺纸/恢复出纸验收；税票能力未具备不把普通客单标税发票，验证提交。

### CKO-25 日结、交班及经营报表

依赖：17、23～24的已开启能力；电子-only门店按对应真实资金来源验证。

文件：原CLOSE/RPT能力与报表DAO/JdbcClient体系、餐饮CashShift/DailyClose编排、源事件适配；`FT/DiningDailyCloseTest.java`及原报表域测试。

接口：门店营业日切点、冻结统计watermark、现金盘点/差异原因/签核、原资金与税分组事实；不直接查当前商品价重算历史。

- [ ] 写latePaymentAndRefundAppendCorrection：日结后迟到回调/退款不丢失，按原修订/追加事实制度追溯；重复消费不重复计收入。
- [ ] 实现销售/优惠/税净额含税/各资金/对价抵付/退款分列、盘点交班、日结差异与报表钻取；营业日不是法定税点。
- [ ] 测切点跨夜、税类汇总守恒、实收减找零与钱箱入账一致、UNKNOWN差异不静默抹平；验证并提交。

### CKO-26 恢复作业、对账与可观测性

依赖：14～15；每新增现金/混合/资产/退款能力同步补相应恢复，不等全部阶段结束。

文件：原核心/PAY/库存job与outbox执行器、`F/job/`必要的行业绑定恢复、审计/指标；`FT/DiningRecoveryTest.java`及各owner恢复测试。

接口：按稳定intent/source查询原权威、状态CAS、幂等事件、有限重试及人工处置单；不由food job直接更新支付账。

- [ ] 写crashAtEachDurableBoundaryRecovers：意图后/远端成功后/绑定前/outbox后逐点崩溃，同一业务只产生一次有效结果。
- [ ] 实现pending/UNKNOWN扫描、原键查询、差异告警、trace关联及受控补偿；无权限读取不泄露卡/审批数据。
- [ ] 验证回调乱序、重复、长时间未知、作业并发、重放后统计不重复；恢复证据通过后才开放对应资金能力。

### CKO-27 B/C/平台接口与前端联调合同

依赖：09～11；每个收款/调整能力跟随其任务完成后联调。文件：对应W入口、核心Ops/B配置Controller、`b-app/src/api/`、`c-app/src/api/`及ops-web API客户端；页面修改同步原UI清单。

接口：保留原API编号与资源；quoteNo/sourceVersion/scopeVersion/configRevision/economicsHash/expiresAt；受控错误、blockedReasons和差异确认。

- [ ] 写rejectOutOfOrderAndForeignQuote：晚到旧quote不能覆盖新选择；价相同券不同仍确认；C端伪造员工调整拒绝。
- [ ] 先交付API契约/类型和真实后端联调，再做必要结账展示；平台改税、门店改配置和顾客用券各自权限隔离。
- [ ] 验证现金/电子入口差异、未知支付提示不允许换键重付、设备切换与会话失效；端测试及vue-tsc通过后提交。

### CKO-28 零售新策略迁移与美业适配

依赖：成熟的03～11、相关资产合同。本任务做零售新策略显式迁移和美业来源适配；共享实现从02～11起就在原核心开发并进行零售兼容回归，不把公共能力留到本任务才回迁。

文件：原零售OrderService报价/确认适配、美业原服务来源适配、共享CalculationSpec；`CT/RetailPricingCompatibilityTest.java`、原美业合同测试。

接口：LEGACY_OWNER_COMPAT默认留存；新模板显式切换；美业真实SERVICE_USE源、权益次数及全额储值保持。

- [ ] 写legacyAmountsAndFunderAllocationRemainIdentical：旧零售金额/分摊/资方不变；美业余额不足不自动补现金，同服务不能双来源结清。
- [ ] 实现影子对比、差异归因、受控迁移与关闭新单开关；历史冻结quote仍可查询/恢复，不能用回滚重写历史。
- [ ] 三行业同规范向量、来源身份/权限/税及退款回归通过后分行业发布。

### CKO-29 离线估算与离线营业专项

依赖：成熟的11、17、26～27及独立边缘安全/资金/库存合同；不阻塞在线闭环。

文件：未来前端定点runtime及离线边缘模块，具体工程文件必须在本任务启动前形成独立spec并登记；当前不预建空模块。

接口：CalculationSpec版本/签名/有效期、设备身份、离线交易稳定键、同步/查重/冲突恢复；本地估算不等于可核销资产。

- [ ] 独立评审离线可售/库存承诺、现金收据、设备序列和冲突边界；平台券/储值/积分无离线授权能力就禁用。
- [ ] 先实现与Java同版本向量的离线估算，再逐项验收获准离线现金营业及恢复；本地估算明确标待后端确认。
- [ ] 测设备时钟回拨、配置过期、重复上传、双端竞争、重连价格差异及本地数据丢失；专项合同未签不得开放离线资金。

### CKO-30 外送渠道合同及后续适配

依赖：11、14～16、23、26。当前Keeta仅规划，不调用/开发真实平台适配。

文件：原餐牌渠道定义、Food事实适配及内部渠道DTO；真实Keeta客户端及webhook文件须将来获得明确接入范围后再登记。

接口：渠道餐牌/价格/配料版本、externalOrderNo幂等、平台/商家出资、税是否已含、实收及取消退款证据；平台服务费不混入顾客商品税。

- [ ] 规划channel来源事实及合同夹具，覆盖渠道差价/不同配料、重复外部单、外送税费和券互斥。
- [ ] 保证现有自营外送/H5报价与收款使用真实来源；Keeta状态保持PLANNED_DISABLED，不造沙箱成功或订单。
- [ ] 后续正式接入前另验地区/税/出资/回调/退款合同；当前完成条件是预留兼容且真实适配禁用。

### CKO-31 双部署、灰度与最终验收

依赖：所发布能力对应任务完成，且24/26/27必要门禁通过；不要求未开通专项全部完成。

文件：原food-server/shop-app装配、按任务新增迁移/配置开关、部署smoke及验收证据；回填本计划§5～6和原后端计划。

- [ ] 执行shop-app嵌入、food独立＋PAY/库存独立两种形态的真实沙箱链；禁用food时零售仍可运行。
- [ ] 演练配置升级、quote过期、支付/库存/资产UNKNOWN、进程重启、打印失败和迟到回调；新单开关关闭不阻止原意图恢复。
- [ ] 先测试门店再受控灰度；退款/日结所需能力未通过则不能宣传完整营业；数据库仅前向兼容迁移，不通过删表回滚。
- [ ] 更新每项任务状态、实际测试计数/trace、迁移号/API版本/提交及未开能力；独立审核后按已获部署授权范围发布。

### 2.2 依赖与首个可执行切片

主线：`01 → 02 → 03 → 04 → 05 → 06 → 07`；`03 → 08`；`07+08 → 09`；`06+07+08 → 10`；`09+10 → 11`；`11+12+13 → 14 → 15`。

合同支线12/13可在01后推进；打印24、恢复26、接口27随第一条收款能力进入，不等待资产21/22。后付16、现金17、调整18之后进入范围19和混合20。储值21、积分22、退款23按真实能力依赖推进；退款按已开放支付工具提供可用路径，不能先开放某资金却没有相应原路恢复/退款门禁。

下一步执行CKO-01，产出**真实合同与字段差异清单**；随后首个代码切片是CKO-02定点对象，使用`MoneyMinorTest#rejectOverflowAndCurrencyMismatch`确认失败后实现。此计划不授权在没有合同的情况下先写模拟成功的支付适配器。

## §3 任务边界及排期方式

共享内核、配置发布、报价持久化、资金/库存合同、餐饮结账、经营凭证各自为可单独评审的工作包，但依上述合同连成一条主线。不要按Controller→Service→Mapper分批开发一堆不可验收空壳。

每个任务完成独立可验证切片后再领取下一项；不预承诺日历工期，因为PAY/库存/储值真实合同、沙箱及打印硬件验收尚未完成。CKO-01结束后基于真实缺口、人员和环境给出工时范围；外部等待与开发工时分开记，不以“表已建”估算业务已完成。

## L4 验收、风险与执行记录

## §4 风险与能力门禁

| 风险 | 必须满足的门禁 |
|---|---|
| 本地可运行，远程普通支付/库存未通过 | 12/13 provider-consumer合同及真实sandbox trace，不以启动200替代 |
| 多券/税前后顺序原owner不支持 | 05/06/11按精确basis/hash阻断，保留旧零售兼容 |
| 全额储值被误作部分餐饮支付 | 21保留真实来源/币种/全额约束，未验收关闭 |
| 多根/AA在food本地拼成已结清 | 19/20必须有核心真实范围/资金分配及并发锁 |
| 总价相同却换券/税点/资方 | 15/27比较完整商业差异，重新确认 |
| 日结或打印失败导致重收款 | 24～26原键恢复；金融与凭证状态独立 |
| 大计划长期无可用交付 | 分阶段发布可报价、单根先付、后付现金、复杂结账；不同时重写零售 |

## §5 实现 → 需求验证矩阵

| AC | 必须有的验证 | 当前状态 |
|---|---|---|
| C01 | ConfigCompiler/ConfigService/Seed真实发布并发、幂等、越权 | 待实施 |
| C02 | RuleEngine/PlanSelector/Engine逐券、数量份额及确定性 | 待实施 |
| C03 | TaxEngine向量、税点/地区权限、AA原税与退款上限 | 仅设计向量参考检查通过，Java待实施 |
| C04 | DiningPlacement/Prepay/Postpay真实源与双部署链 | 待实施，原合同核验有缺口 |
| C05 | CashPayment/Adjustment权限、盘点、精确找零 | 待实施 |
| C06 | Split/MixedPayment并发、部分成功及UNKNOWN恢复 | 待实施 |
| C07 | StoredValue/Points owner合同、原键恢复、来源/税分类 | 待实施/能力门禁 |
| C08 | PartialReturn原行、原税、原资金及已退在途并发 | 待实施 |
| C09 | Print实机、DailyClose迟到事实及报表钻取 | 待实施 |
| C10 | Recovery故障注入、API授权、Architecture/i18n与双装配 | 待实施 |
| C11 | Retail兼容、美业原来源、Offline专项/渠道禁用 | 待实施；Keeta保持规划 |

每阶段需保存：实际测试类/方法与Tests run计数、必要的目标数据库IT、架构/i18n检查、关键消融结果、沙箱trace、公开API契约版本。计划检查、Mock测试、数据库已存在不能替代资金/库存/打印营业证据。

## §6 执行记录及设计 → 实现对账

本轮计划及共享归属确认已记录；后续已执行CKO-01源码合同核验并勾选其4个步骤，02～31未执行。未建表、导入种子、编码或部署。后续逐任务回填：任务编号、起止基线、实际文件/迁移/API、测试证据、提交、偏差与仍关闭的能力。原后端计划与本清单一对多映射；本清单细化结账主线，不推翻桌台、菜品和厨房的原实现记录。

本轮计划检查：31个任务编号连续且唯一、各任务含依赖/文件/至少3个未勾选步骤、共95个执行步骤；11项需求映射及本地链接、代码围栏检查通过。未运行Java/数据库/支付/库存或打印验收。

执行进度（2026-10-22）：CKO-01完成源码合同/迁移基线核验，下一步CKO-02；外部环境全部仍待验证，不因此开放交易。

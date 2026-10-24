# 餐饮订单结账完整开发计划

> **税配置最新口径（2026-10-23）：** 平台统一默认、商家自主覆盖，支持EXCLUSIVE额外加税/INCLUSIVE含税倒推，无平台审核；普通订单不再以前置税登记核验阻断。原税档审核/登记来源计划由[CKO-09 C2新口径](./TDD-订单结账-CKO09种子与配置API.md#c2新口径平台默认商家自主设置2026-10-23设计待实现)取代。首版同商家门店共用配置，历史订单冻结原税配置；新合同、内部存储、管理API及共享税计算/复核Port已实现；无权益受控报价和原单接线已完成，完整权益及扩展场景仍待开发。

> 执行者：使用superpowers:executing-plans按任务顺序实施；按用户最新确认的节奏先完成当前范围全部编码，再完成统一编译，最后进入一次集中测试阶段、独立评审及范围明确的提交。不得仅因本计划存在就启动编码或声称能力已交付。
>
> **当前执行状态：** §2.7～2.13已新增员工堂食后付、整数加料、预约/转桌、厨房交接、业务AA、异常结转、人工调价、批次查询、非支付退餐及员工处理中撤销源码；本批输入边界已收口。本批后端统一编译及范围明确的集中测试已执行通过，真实收据、跳过项及限制见§2.14；§2.7～2.13内“未编译/测试”保留为编码时点历史。逐接口完整目标及营业验收仍按§2.3待办，不因测试通过而自动完成。

> 历史基线159e00b32状态（不适用于当前未提交修改）：2026-10-24非支付后端批次已完成编码、生产编译、集中测试及双部署打包；本批范围、真实能力门禁与验证见§2.5和§6首条。新增50条实际B端接口、52个递归DTO及9张增量表；不等于§2.3完整需求总账或CKO-11～31全部完成。原先付受控切片（`a09593529`）及历次收据保留原时点。

**目标：** 基于原商品、订单、支付、库存及账号门店能力，实现从规则配置、报价到先付/后付、收退款、打印、日结及恢复的餐饮结账闭环，并保留零售、美业共享内核的接入路径。

**架构：** shop-base提供纯规则/定点内核和唯一SPI；shop-core.trade承担报价、经济确认及订单权威编排；餐饮五模块提供行业事实、B/C入口和结账流程。独立PAY/库存依真实typed合同接入，不在food复制账本。本文“结账”是顾客订单Checkout，不是另建供应商结算/支付清算平台。

**技术栈：** Java21、原平台Spring/MyBatis-Plus/Flyway、已有幂等/outbox/作业/审计；沿父POM，不增加规则引擎依赖或新Maven模块。

**设计依据：** [存储与Java](./TDD-统一试算引擎-存储与Java实施方案.md)、[初始规则及税费](./TDD-统一试算引擎-初始规则与结账税费.md)、[优惠组合](./TDD-三行业优惠规则-组合与多券.md)、[独立支付库存核验](./TDD-餐饮系统-独立支付库存合同核验.md)、[原后端计划](./TDD-餐饮系统-后端开发计划.md)、[API设计与实现对账](./design/餐饮系统-API设计.md)、[ADR-040](./ADR/ADR-040-餐饮销售配置与耗用权威边界.md)。

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
| C03 | 平台默认税率、商家自主覆盖、含税/价外、冻结版本及最终定税，无平台审批 | 07、09 C2、10～11、18、22～25 |
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
| 报价快照、应收版本、重算确认 | shop-core.trade，4张配置表＋3张报价表＋1张来源扩展表 | 保存原业务引用，展示及提交获准源范围 |
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
- 已应用V1～V7餐饮迁移和其他历史迁移不修改；新增迁移号执行时读取最新编号后分配。上述ord表及其增量归核心迁移，fnb增量归餐饮迁移；既有表不得重复CREATE。
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

按用户最近确认的方式执行：按业务切片开发并分别编译，多阶段完成后统一运行测试、相关架构/i18n/数据库检查和必要消融，最后一次独立审查并提交本批范围内文件。各任务列出的测试步骤是必须满足的验收要求，可在批次末归并执行；关键缺陷修复保留失败→通过证据。缺少外部合同只允许推进不依赖它的工作，不能以成功Mock代替真实提供方营业验收。

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

完成证据：[CKO-01核验收据](./TDD-订单结账-CKO01合同与字段核验.md)及[机器合同基线](./design/schema/pricing-checkout-contract-baseline.json)。仅源码/迁移核验完成，外部合同补齐与营业验证留在对应任务；当前进度见§6及CKO-02收据。

### CKO-02 定点值对象与标准事实

依赖：01。文件：新增`K/MoneyMinor.java`、`K/PricingFacts.java`、`K/PricingUnit.java`及`SPI/trade/PricingSourceRef.java`，公共来源引用仅SPI一份；新增`KT/MoneyMinorTest.java`。

接口：`MoneyMinor#add/subtract`；`PricingUnit`固定sourceLineNo/componentRef/quantityRange/taxInvoiceLineRef，qty不使用double。

- [x] 写MoneyMinorTest#rejectOverflowAndCurrencyMismatch：MAX_VALUE加1、不同币种相加均拒绝；0.1数量按受控精度计算，不静默截断。
- [x] 运行目标测试确认实现前失败；实现最小金额/事实record及边界校验。
- [x] 检查JSON安全金额上限、负数/比例/数量边界，运行测试并仅提交本任务文件。

实施证据：[CKO-02定点金额与标准事实对象](./TDD-订单结账-CKO02定点金额与事实对象.md)。新增4个record及18个测试；架构检查保留原有1处失败，详见收据，不声称全量架构通过。

### CKO-03 JSON类型化编译器

依赖：02。文件：新增`K/PricingConfigCompiler.java`、`EffectivePricingConfig.java`、`RuleDefinition.java`及有限参数record；新增`KT/PricingConfigCompilerTest.java`。

接口：`compile(template, override, systemConstraints, capabilities) → CompilationResult`；输出不可变配置及问题；无IO，不使用任意反射路径或脚本。

- [x] 写rejectUnknownEvaluatorAndInvalidOverride：未知Evaluator/路径/null、SINGLE却total=3、payableRateBp超出0～10000、门店删除强制规则拒绝；false保留、数组整替换、reset恢复继承。
- [x] 确认失败后实现字段白名单/有限参数解码/覆盖合并/编译；强制规则以代码目录核验，不信客户端mandatory。
- [x] 验证同输入编译/hash确定性；运行目标测试并独立提交。编译器无可变缓存，按不可变版本缓存由CKO-08应用服务实现。

实施证据：[CKO-03类型化规则配置编译](./TDD-订单结账-CKO03规则配置编译.md)。新增8个生产类、3个测试辅助/测试类，20个新增测试通过；可选manualOperations.minPayableRateBp落实0～10000配置边界，实际优惠计算与审批留在后续任务。

### CKO-04 规则校验器与执行证据

依赖：03。文件：新增`K/PricingRuleEngine.java`、`RuleEvaluator.java`及互斥/数量/能力/审批/阶段/守恒校验实现；`KT/PricingRuleEngineTest.java`。

接口：`validate(facts, config, candidate) → List<RuleViolation>`；每项含ruleNo/version/阶段/code/关联源项；所有适用禁止共同生效。

- [x] 写denyCannotBeOverriddenByAllow：活动+券、折扣券+储值、券+人工抹零拒绝；“允许折扣券+金额券”不能放行三者加储值。
- [x] 确认失败后实现B01～B12；实际订单活动参与事实与门店活动开关分开。
- [x] 验证EXPLICIT错误返回全部可解释冲突；校验不核销资产；消融B02必须失败，恢复后验证并提交。

实施证据：[CKO-04业务规则校验与证据](./TDD-订单结账-CKO04业务规则校验.md)。新增8个生产类及1个测试类，27项新增测试通过，shop-base全套143通过；逐份额金额证据防止跨菜品挪用及重复资助。空冲突只表示本阶段通过，税务/优惠公式/交易授权仍在后续。

### CKO-05 多券候选与份额计划

依赖：04。文件：`K/BenefitPlanSelector.java`、`CalculationPlan.java`、`BenefitSelection.java`、`KT/BenefitPlanSelectorTest.java`。

接口：`select(context, selection) → PlanSelectionResult`；EXPLICIT/CANDIDATE_POOL/AUTO_BEST、DISJOINT/BEST_ONE/受控STACK。

- [x] 写explicitCannotDropCoupon、countsAssetsAndChecksQuantityOverlap：同券跨5行计1张；两张折扣券同一份额DISJOINT拒绝，不同份额可用仍受全单上限。
- [x] 实现有界候选、稳定同价选择、根账单累计用券范围；超过复杂度限制返回PLAN_SEARCH_LIMIT，不伪称全局最优。
- [x] 验证未采用候选不核销、套餐父叶不重复；测试通过并提交。

实施证据：[CKO-05多券候选与份额计划](./TDD-订单结账-CKO05多券候选与份额计划.md)。选择器要求逐组合完整金额/分配评估，保护固定资金/人工意图；明确BUSINESS_PRE_TAX阶段，不将税前计划当作含税最终最优。具体公式/税务评估器分别在06/07接线。

### CKO-06 公式、分摊与金额守恒

依赖：05。文件：`K/PricingEngine.java`、`FormulaRegistry.java`、`PricingFormula.java`、`PricingResult.java`、`PricingInvariantChecker.java`、`KT/PricingEngineTest.java`。

接口：`calculate(PricingFacts, EffectivePricingConfig, BenefitSelection) → PricingResult`；原优惠算法版本通过获准事实/owner定义使用，不复制一套旧券权威。

- [x] 写deductThenDiscount：未税20000先减2000后9000基点得16200；DISJOINT的10000八折+5000九折得12500；受控STACK10000得7200。
- [x] 实现固定阶段、逐行基数、资方及尾差证据，原legacy分摊保持；owner不认可相同顺序/基数时阻断。
- [x] 校验行/总/券/资方守恒及确定性，并增加`CT/RetailPricingCompatibilityTest.java`验证现有零售金额/舍入/资方不变；不等到28才首次做零售回归。无税促销算例不得冒充地区税票；验证并提交。

实施证据：[CKO-06公式、分摊与金额守恒](./TDD-订单结账-CKO06公式分摊与守恒.md)。29项新增引擎测试及4项真实零售分配兼容测试通过；shop-base全套202、shop-core全套414通过。输入增加原owner有序报价/基数/资方证据，不猜旧商家分配到源行的映射；输出仍为BUSINESS_PRE_TAX，税务归07。

### CKO-07 地区税与最终定税

依赖：06。文件：新增`K/TaxEngine.java`、`TaxAdjustmentResolver.java`及TaxPolicy/TaxTreatment/TaxLineResult；新增`KT/TaxEngineTest.java`、`TaxAdjustmentResolverTest.java`；向量来自规则种子JSON。

接口：`estimate(lines, policy) → ProvisionalTaxResult`、`finalizeTax(adjustedLines, policy) → TaxResult`；固定税发票行身份，与促销份额分开。

- [x] 写inclusiveExclusiveMixedClassesAndLineRounding：未税10000加税500；含税10500拆税500；两行未税10各税1；未知税类拒绝。
- [x] 实现可信地区/税点匹配、税前/含税两模式、价格优惠与对价分类、积分/抹零后单次最终定税；不可表达gross减免拒绝。
- [x] 运行8个标准向量，验证不双减积分、不因拆券/AA重算原税、不侵入已安排S；测试与消融通过后提交。

实施证据：[CKO-07地区税与最终定税](./TDD-订单结账-CKO07地区税与最终定税.md)。26项新增测试及8个JSON向量通过；shop-base全套228、shop-core全套414通过。完整原发票行基数及冻结原税AA分配防止重复舍入；税基减免/对价/储值分开。尚未接入正式报价/税后候选择优、税档发布、实际owner或收款接口。

### CKO-08 4张配置表及发布事务

依赖：03；01字段冻结。文件：核心增量迁移、`C/entity/OrdPricingTemplate.java`等4配置Entity、现有`C/mapper/TradeMappers.java`、`C/service/PricingTemplateService.java`及impl、`StorePricingConfigService.java`及impl；`CT/PricingConfigServiceTest.java`及真实DB IT。

接口：`saveDraft(context,no,req)`、`preview(context,no)`、`publish(context,no,req,idempotencyKey)`；发布时插入revision与CAS切换指针同事务。

- [x] 写concurrentPublishHasOneWinner：草稿CAS并发仅一胜，失败无孤立revision/outbox；同幂等键异body拒绝。
- [x] 追加4表迁移、实体和嵌套Mapper，实现锁版/初始化/发布/回滚；旧revision不可改。
- [x] 真实数据库验证唯一约束、事务、空首次指针与回滚；不以H2/Mock代替目标数据库证据；验证并提交。

实施证据：[CKO-08配置发布与事务](./TDD-订单结账-CKO08配置发布与事务.md)。4表、内部Service/Mapper、幂等/发布outbox原子事务、恢复继承/升级/回滚已实现；17项真实MariaDB测试通过。API、种子导入与真实身份/能力/税档来源解析待09；既有两项守卫基线失败及实际测试数字见收据。

### CKO-09 初始数据与配置API

依赖：07、08。文件：`C/service/PricingSeedService.java`及impl、`C/api/ops/OpsPricingTemplateController.java`、`C/api/biz/BizPricingConfigController.java`、对应dto和权限/i18n目录；`CT/PricingSeedServiceTest.java`。

接口：`importDrafts(seed,registryVersion,DRY_RUN|DRAFT)`；Ops模板、B门店草稿/预览/发布/升级/回滚按原设计合同。

- [x] 写importIsIdempotentAndDoesNotPublish：20规则、3模板、2AE税档、示例门店/能力夹具不进入真实配置；同版本异hash拒绝。
- [x] 实现受控种子草稿导入、7个平台/9个门店配置API，C端无配置写入口；保留缺实际owner证据阻断。
- [ ] 按§2.3任务09-C2.1～5接通平台默认与商家自主税配置、移除税审批/登记前置门禁，校验真实授权/版本及三语；不以取消审核放开资金能力。

实施进度：[CKO-09种子与配置API](./TDD-订单结账-CKO09种子与配置API.md)。A/B种子校验与草稿导入已实现；20规则、3模板草稿、2待审核税档候选，不导入示例门店/能力。C1已补7个平台/9个门店接口及真实身份/缺证据阻断；C2按最新要求改为平台默认税率、商家自主覆盖及两种计税模式，不做平台审核；详见§2.3。CKO-09整体未完成。旧审核归属记录已被2026-10-23用户修正取代。

### CKO-10 3张报价表与快照

依赖：06～08。文件：核心增量迁移、`C/entity/OrdPriceQuote.java`、`OrdPriceQuoteLine.java`、`OrdPriceQuoteAllocation.java`、`C/assembler/PricingQuoteAssembler.java`及TradeMappers嵌套Mapper；`CT/PricingQuotePersistenceTest.java`。

接口：保存quote头/行/分摊同一短事务；factsHash、economicsHash、requestHash分别规范化；确认引用原不可变quote。

- [x] 写quoteIsAtomicImmutableAndScoped：写分摊失败整份回滚；别店/别用户quote不可读；金额不可UPDATE；时间/UUID不影响经济hash。
- [x] 实现3表及税/对价字段；quote过期、被替换与支付状态分离；有订单/意图引用不得过期物理清除。
- [x] 验证同一发票行的份额税合计、积分降价/对价字段互斥及金额脱敏；真实DB测试通过并提交。2026-10-23内部存储批次完成，见存储实施TDD收据；消费者VO脱敏和真实owner准入继续在CKO11验收，未提供公开报价API。

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

### 2.2 依赖与首个可执行切片（原时点记录，当前待办见§2.3）

主线：`01 → 02 → 03 → 04 → 05 → 06 → 07`；`03 → 08`；`07+08 → 09`；`06+07+08 → 10`；`09+10 → 11`；`11+12+13 → 14 → 15`。

合同支线12/13可在01后推进；打印24、恢复26、接口27随第一条收款能力进入，不等待资产21/22。后付16、现金17、调整18之后进入范围19和混合20。储值21、积分22、退款23按真实能力依赖推进；退款按已开放支付工具提供可用路径，不能先开放某资金却没有相应原路恢复/退款门禁。

当前已完成共享内核、配置/税管理、CKO10报价存储，以及CKO11的价格来源、当前配置准入、商品策略、PAY精确能力描述、来源日志和优惠基数切片。下一步完成实际权益/活动事实与场景准入、生产发布证据及统一B/C报价；CKO12/13合同核验可同步推进，然后进入14正式下单与15先付闭环。此计划不授权在没有合同的情况下先写模拟成功的支付适配器。


### 2.3 完整需求待办总账（2026-10-24，非支付进展见§2.5）

> **最新优先级：先完成业务功能，报表排在最后。** 代码基线为`159e00b32`，已交付的非支付受控切片见§2.5及§6。此前报表设计保留为后续依据，不启动报表编码、建库、汇总任务或分析接口开发。当前已获准启动非支付业务编码；不部署、不迁移经营库。

本节是**唯一当前剩余待办入口**，沿用CKO、KIT、PRN、SHIFT、CLOSE、RPT、GAP及原菜品增强方案编号；不另建平行任务。原任务完整目标仍有效，部分实现不等于整项完成，原历史收据不改写。以下顺序替代此前“首批先付→日结报表→后付”的排期。

**执行方式（最新指令）：** 后端优先，按业务模块组织代码，但不按模块分别交付测试；采用“全部编码 → 全部编译 → 一次集中测试”的统一批次。当前批次覆盖B节非支付业务；C节明确延期专项及E节报表不提前混入。业务验收、灰度和发布位于测试之后，不因代码/编译完成提前执行。

| 阶段 | 工作内容 | 阶段出口 |
|---|---|---|
| 1 全部编码 | 连续完成B节当前范围的业务实现、API/数据库/Controller/Service/Mapper、权限、恢复、双部署装配及必要测试代码；同步合同与文档 | 各模块编码及跨模块接线齐备；测试代码可先编写，本阶段不运行自动化测试，不逐模块宣称验收完成 |
| 2 全部编译 | 编码完成后，串行编译受影响公共模块、核心、库存及餐饮模块、两种宿主形态；完成测试源码编译及打包，显式跳过测试执行 | 记录各模块实际编译/打包结果；编译错误回到编码修复，全部编译通过后才进入测试阶段，不并发争用本地Maven产物 |
| 3 集中测试 | 一次性组织单元、业务场景、原生数据库、远程合同、双部署、旧零售兼容及权限守卫验证；硬件联验按真实设备条件记录 | 汇总通过/失败/跳过及外部缺口；失败修复和必要复测仍属本次集中测试阶段，未通过不标已验收，不以编译替代测试 |

每个模块仍需具备门店隔离、原键幂等、数量/金额守恒、失败恢复及真实owner合同；这些是编码要求，验证集中在阶段3。API与数据库清单同步不是另起一轮业务测试。历史测试收据只代表原提交，不代表本次新增代码已通过。

**范围边界：** 支付继续摘除，不新增收款、退款资金执行、现金找零、混合支付、订金收退或券/储值/积分扣减。保留现有原单/资金事实及门禁；后付受理、应收计算、退餐申请、AA分摊等业务仍可推进，但不能假结清、假退款。Keeta仍仅规划。已运行的零售报表不因餐饮报表后置而停用。

#### A. 已交付基线：复用，不重复开发

| 领域 | 已有能力 | 尚不能据此宣称完成的目标 |
|---|---|---|
| 工程、桌台、菜品 | food五模块、双部署；桌区桌台/营业时间/会话；时段及渠道餐牌、商品绑定、选配套餐/配方发布及停售 | 带真实订单转拼拆桌、完整成交组件及实际耗用 |
| 规则、税、报价 | 共享定点内核、商家规则/税率、价外税及含税倒推、不可变报价和最终重验 | 实际权益消费、复杂来源和人工调整完整授权 |
| 受控正式单 | 登录H5、自提、整数MAIN的PREPAY原闭环；本批补POSTPAY真实未付受理及原库存生命周期 | 员工代客、匿名/设备、正式堂食/外送、复杂组件、多批次整桌结账 |
| 厨房、退餐、取餐 | 冻结工位路由；部分制作/出品/上菜、道次及催菜、撤菜/报损/重做；原退餐厨房独立处理；原完整单取餐核销 | 所有复杂行/部分履约、打包齐套和骑手交接、完整跨域售后 |
| 打印、排队、日结 | 共享持久打印任务/尝试/桥接回执/补打；受理事件自动派单；排队原子入座；营业日预结/快照/签核门禁 | 真实设备出纸、预约资源预占、财务日结及完整结转 |
| 耗用、恢复 | 制作理论耗用、BLOCKED及原键恢复；独立进程producer-only outbox，宿主只领取可处理任务 | 排他叶子物料计划、真实小数领耗/历史成本；UNKNOWN不能当成功 |

§6保留实际测试、条件跳过及既有失败证据；未迁移经营库、未部署，不以原生数据库测试替代真实门店验收。储值owner已有远端实现，后续应核验实际FOOD合同，不标为系统不存在，也不在本轮接线扣款。

#### B. 当前业务开发顺序（从1开始，报表不穿插）

下列条目均为**剩余目标**；每项先复核§2.5已交付部分，只补缺口。公共库存合同按依赖前置，恢复与诊断随每个模块一起实现。

| 顺序 | 业务模块与原任务 | 本批应交付的剩余功能 | 关键验收边界 |
|---|---|---|---|
| 1 | 员工代客与终端身份 · CKO-11/14/27 | B端收银/服务员代客报价、下单与查询；区分操作员工、顾客、付款人；桌码、匿名顾客、点餐机设备会话及门店范围 | 员工号不能冒充买家；跨店/换码/过期会话拒绝；本批不新增支付写授权 |
| 2 | 菜品真实成交 · CKO-11/13/14、P3/P4前置合同 | 时段/渠道餐牌与不同价格/配料真正进入报价和原订单；固定/自选套餐、奶茶多小料及组合；小数/称重单位、冻结组件树及库存需求 | 父子不重复计价或扣库；小数不截断；无真实库存合同的组合继续禁售，不把配置发布当成交完成 |
| 3 | 多场景点餐与加菜 · CKO-14/16/18 | 正式堂食、自提、线上线下、外送；先付模式保留现有门禁，后付受理扩展至多批次加菜；地址/配送费/餐段/座位来源冻结；人工打折/扣减/抹零的原因、权限、审批、额度及新应收版本 | 先付未获原owner成功不得制作；后付保持未付事实且不被零售超时误撤；重试不重复建单；券后互斥及累计限额生效，调整重验税额，现金找零仍暂缓 |
| 4 | 原单变更与退餐 · CKO-23、RET-01～03、GAP-03～06 | 未付取消、加减菜、已制作/部分履约退餐、套餐配料退量、换赠菜与称重补差；原新行关联及申请/审核/厨房处置 | 原量/预留容量并发守恒；退餐不代表退款；无真实收货凭证不返库，资金差额只记录待处理 |
| 5 | 带单桌台与AA · CKO-19、TBL交易联验 | 真实订单转台、拼桌、拆桌/账、多人同桌；按人/菜/金额形成业务份额，座位/批次归属及范围锁；清台门禁 | AA只做应收分摊及责任范围，不新增资金执行；已付事实不搬移，同份额不能重复占用 |
| 6 | 厨房与交付补齐 · KIT-01～04 | 补复杂组件跨工位、部分数量/道次联动、齐套打包、自取部分核销及骑手交接；复核现有派工/撤菜/重做 | 按原事件幂等，撤菜与资金处理独立；取餐不能把部分退餐后的原完整核销凭证直接套用 |
| 7 | 实际原料耗用 · P3/P4 | 排他叶子物料需求计划、小数单位与换算、制作领耗/补料/重做/报损、真实库存原键查回；保存实际数量与历史估值事实 | 由库存owner执行；不能父子双扣，交付不再次扣制作原料，未知数量/成本不填0；本项建设业务事实，不开发成本报表 |
| 8 | 预约排队与专项业务 · GAP-01/02、GAP-03～09、SPEC-01～04 | 真预约资源占用、预点截止/转单、排队通知；预售定时派工/产能、替代/错单重开；茶位/锅底/包材/服务费/低消等来源及税合同 | 排队入座已有能力不重做；预约配置不等于资源已锁或订金已收；企业挂账/自助/宴会/酒店等逐业态启用，资金动作仍延期 |
| 9 | 打印与KDS设备 · CKO-24、PRN-01～03、GAP-08/11 | 接真实桥接/打印设备；厨房票、加撤菜票、标签、预结票及原回执票适配；工位负载/拥堵暂停恢复、语音呼叫、授权订阅与断线回放 | 逻辑任务幂等；UNKNOWN/断纸不盲重发；补打有原号原因；必须补实机出纸和设备撤权证据 |
| 10 | 营业日及运营收口 · CLOSE-01～02、SHIFT非资金部分、CKO-26 | 营业日切点、预结/异常/签核/结转更正、员工责任与非资金交接；统一原单/库存/厨房/打印恢复及审计处置 | 沿现有营业时间；不依赖新报表库；未收/UNKNOWN不得假对账，迟到事件不改历史签核；财务结清继续等待真实资金合同 |
| 11 | 各端业务接入 · CKO-27 | 收银平板、服务员、H5/点餐机、KDS、商家运营、老板App及平台支持接真实业务；管理菜品/规则/税/权限/设备 | 按模块同步接入；白底黑按钮、保留菜图、点击入车、车内调量；三语/RTL/授权隔离；老板经营报表页面留最后 |
| 12 | 双部署及业务验收 · CKO-31、治理任务 | 嵌入core和独立food-server同合同；迁移单执行者、升级备份恢复、门店开关、健康检查、旧零售回归和现存守卫治理 | 业务测试/故障恢复/真实硬件证据齐全再灰度；不以报表未开发阻塞已支持非支付业务验收，也不宣称含支付的完整营业闭环 |

- [ ] **1 身份与员工代客**：按原API/方法清单核对身份入参、权限及原单创建合同，补实施切片后编码。
- [ ] **2 菜品成交**：对齐渠道/时段价格、组件树、数量单位、核心原行与库存owner需求合同。
- [ ] **3 场景与加菜**：补真实堂食/外送、多批次后付及有依据的人工应收调整，沿共享规则/税引擎、当前原单及恢复机制。
- [ ] **4 原单变更及售后**：逐行原量、制作/交付状态、取消/退量/换赠菜及待资金处置记录。
- [ ] **5 带单桌台及AA**：补真实转拼拆范围和份额守恒，不做新的资金结算。
- [ ] **6 厨房交付**：补复杂组件、齐套、部分核销及骑手交接。
- [ ] **7 真实耗用**：补排他叶子物料计划、精确数量及真实库存回执。
- [ ] **8 预约与专项**：保留所有原专项需求，按已核验合同逐一开放，不将订金标已收。
- [ ] **9 打印/KDS实机**：补模板、设备运行、断线/UNKNOWN及实机验收。
- [ ] **10 营业日与运营**：补业务结转/更正、非资金交接、恢复及审计，不新增报表汇总。
- [ ] **11 终端接入**：逐端真实联调，报表型看板及分析导出留最后。
- [ ] **12 工程验收**：全部业务编码完成后统一串行编译及双部署打包；所有编译通过后，再集中测试及逐合同证据收口。

#### C. 保留但暂不进入当前编码的专项

此处保留完整需求，不是自动启用资金或真实外部渠道的授权，也不作为当前非支付业务交付的前置。

- [ ] **CKO-12/13/15、CKO-17/SHIFT、CKO-20：支付及资金专项。** 原PAY通道关单/晚到成功、现金班次/备用金/盲盘/盘差/找零、混合/跨根支付、订金收退及真实资金退款。必须有原owner回执和精确容量，UNKNOWN只能查原尝试，不能换键重扣；后续恢复资金开发时单独细化。
- [ ] **CKO-11/09-C2.4、CKO-21/22：权益专项。** 实际活动/券资格、张数/叠加、资方冻结和能力证据；储值明确授权及原键消费恢复；积分末段抵扣及累计退回。保留折扣券与储值互斥等既定规则，报价不等于核销，不用模拟资格开放交易。
- [ ] **CKO-28：零售/美业共享接入。** 影子对比及显式切换，服务/疗程等真实来源合同；不复制计算引擎、不默认迁移旧零售。
- [ ] **CKO-29/EDGE：前端估算及离线。** 与Java共享金额测试向量；离线点餐/厨房需唯一写者租约、持久化、配额及同步查重。离线现金及权益消费等待独立真实授权，联网最终确认仍调用后端引擎。
- [ ] **CKO-30/INT：Keeta及第三方。** 仅保留账号绑定、专属餐牌价格/配料、映射版本和幂等异常规划；不开发真实SDK/webhook/同步回流。其他系统明确供应商后适配。

#### D. 每个业务批次的共同门槛（不等报表阶段）

- [ ] **合同与清单同步。** 更新`food-system-design.json`及原API/数据库/代码/方法清单、OpenAPI、权限与三语；区分完整目标、实际受控实现和未开放场景。沿平台包名及类命名，三端Controller继续归food-web，核心能力归权威域。
- [ ] **恢复与验证。** 原键、版本、锁、容量、门店隔离、UNKNOWN和真实owner合同随模块编码落实；全部编码及统一编译完成后，再集中进行相关单元/原生库/远程契约/双部署及旧零售回归。条件跳过、既有失败、硬件等待分列，历史迁移不改写。
- [ ] **既有守卫治理。** 原前端8不可达页面、3缺失权限词条、19未引用词条及架构/schema旧失败按实际报告治理；同时核对已发现CORE V548/V549/V550重复迁移及既有PAY失败。禁止弱化断言、扩大known清单或虚构入口；修复前不声称全仓通过或可发布。

#### E. 最后阶段：报表、统计库与汇总任务（排在业务之后）

> 本阶段当前状态：**已有设计，未实施，暂缓**。进入条件是当前范围业务事实、授权、营业日归属及相应业务验收完成；C节明确延期专项不要求提前开放，其缺失数据不得用0或模拟记录补齐。

- [ ] **RPT基础 · CKO-25/RPT-01～02：** 先核对实际业务源及报表口径，再实现实时经营看板与查询；桌台状态、厨房任务列表等现场操作实时接口属于B节业务，不因报表延期而停做。
- [ ] **独立报表库：** 沿[原报表TDD§9](./design/TDD-B端报表库与日结.md#9-餐饮实时查询与独立统计库方案2026-10-24设计待实现)落地6张基础候选表及后续1张导出表、独立数据源/迁移及故障隔离；不与商品/订单/fnb业务共库，不提前创建空表或接线任务。
- [ ] **日报及任务：** 按门店营业日每日生成日统计；实现§9的J01～J13计划、构建、脏日/回补/重建、质量、导出清理及健康任务；保留既有J00零售任务。业务本身需要的订单outbox、恢复、预售派工任务仍随B节开发，不归入延期报表任务。
- [ ] **销售及经营报表 · CKO-25/RPT扩展：** 日/周/月、门店/餐段/渠道、菜品/套餐/小料、翻台/人均/员工/厨房时效，授权原单钻取；历史期间查统计库，不逐日扫描业务表。
- [ ] **耗用损耗成本 · P6/RPT：** 区分销量、销售理论需求、制作理论耗用、实际领耗、损耗及历史成本；批量领料不冒充逐菜实耗，未知不填0，币种/单位分别汇总。
- [ ] **资金/权益分析及各端报表：** 有真实来源后才开放实收/退款/班次盘差/优惠税额/毛利等指标；实现老板App、商家及平台授权分析与异步导出。当前摘除的资金能力保持未支持，不由报表推断资金终态。
- [ ] **报表统一验收：** 发布原子性、水位/缺口、迟到跨日、重复与并发构建、签核不改写、数据授权及独立库故障隔离通过，再开放报表入口。

**当前开发任务：B节第1项，员工代客与终端身份的非支付下单合同，实施账本见§2.7。** 已启动注册顾客代客切片编码，继续补齐余下真实身份及业务合同；当前未编译/测试，不勾选整项完成。报表§2.6及报表TDD仅保留设计，不再作为下一批开发入口。

### 2.4 结账与售后整批实施（2026-10-23，受控切片已交付）

按最新指令，将结账与售后作为连续开发批次；开发期间串行分阶段编译，整批实现后统一测试。复用本TDD与既有餐饮V1表、核心ord订单、PAY资金账和进销存原单，不另建订单或资金总账。

| 阶段 | 具体实施 | 开发产物与门禁 |
|---|---|---|
| 1 提供方合同 | PAY原支付尝试开立/精确查询/渠道查证与独立部署调用；库存原键需求比对、原预留状态查询、返库与收货累计上限 | 用户/订单/尝试/币种/金额逐项绑定；渠道没有关单合同不能用本地关闭冒充；两条返库路径共用原单锁 |
| 2 统一报价 | 权威来源、商品参与性、当前规则、商家税、原发票行计算与不可变报价；B/C调用同一内核 | 前端只传来源和选择标识，不传价格；不支持的权益明确拒绝，报价不扣库存或资产 |
| 3 餐饮结账接线 | 餐饮模块内报价、确认以及后续批次/收银/退餐服务；嵌入与独立部署同合同 | 原购物车版本、门店/主体/会话授权；服务端确认摘要，内部证据不暴露给顾客 |
| 4 正式交易与售后 | 原核心订单绑定、先付/后付生命周期、实际支付结果、原行退款及恢复 | 小数数量、匿名付款、营业受理、结算责任及退款容量必须有实际owner合同；不得把骨架、计算或账本查询标成营业闭环 |
| 5 统一验证 | 所有生产代码编译后再运行单元、原生数据库、远程契约及旧零售回归；汇总提交测试 | 未验证与缺实际合同项保留未完成状态，不部署、不迁移业务库、不推送远端 |

上表是原批次计划；受控实现已交付，未完成边界及后续任务以§2.3为准。原“不推送远端”限于当时开发授权；本次用户已明确授权将代码与待办全部推送，部署及经营库迁移仍不执行。

本批技术档位2：沿既有架构与表规范扩充实现。PAY和库存提供方独立开发，所有Maven构建严格串行；复用§2.3任务状态，不因纳入本批就提前关闭任务。渠道关单、分数库存、匿名核心付款及后付发货是已识别的实际合同缺口，必须在阶段4补真实实现或维持明确关闭。


#### 2.4.1 实际公开合同与目标差异（已集中验证，证据见§6）

本批实际19条公开路由、Controller、DTO输入输出、授权及剩余能力登记在[原机器设计的checkoutImplementation.implementedApis](./design/schema/food-system-design.json)。原`apis`保留完整目标合同；相同路由的受控子集不等于全部目标完成。本节只记录源码，不声明编译或测试通过，不改变§2.3任务完成状态。

- 报价：B/C原门店quotes使用`DiningQuoteReq(cartNo,version,selection)`，直接返回`PricingCheckoutPort.QuoteVO`；新增双方quote GET及`:confirm`。确认仅重验原车版本/展示摘要，不扣款或核销权益。B端沿`biz:order:view`和认证门店，C端复用原CartAccess逐次鉴权。
- 下单：C端132使用`CreateDiningOrderReq(cartNo,version,quoteNo,confirmationDigest,payMode)`；B/C批次查询已接线。正式提交仅登录本人H5、自营取餐、MAIN整数完整车、PREPAY；DTO接受POSTPAY不代表开放后付。B端042、选行提交与按人预分单仍未实现。
- 收银：新增`POST /mp/x/food/batches/{batchNo}/settlements`仅冻结本人完整原批次/单支付根，输入confirmationDigest。**140的`POST /mp/x/food/checks/{checkNo}/settlements`仍未实现**，不得用batch接口替代台账版本、获准行范围或整桌授权目标。原134与135的`:pay`已接线；pay输入version、confirmationDigest、payChannel，不接受客户端金额。新增C端`:finalize`、`:cancel`不是B端060/059。B端只有057，056、058～061写入口仍未实现。C收银不接匿名session付款、现金、AA、跨根或混合结账。
- 售后：C端136、137及新增`:cancel`，B端064已接线；输入为batchNo、type、原因/图片、sourceLineRef及正整数量，输出`DiningReturnVO`含原核心financialCase与独立厨房轨道，**没有version**。选中原MAIN整数行退量走原核心售后/退款容量，无客户端金额、任意核心itemId或厨房命令；B端062/063及厨房实际处理仍未实现。
- 读取语义：B端settlement/return为授权本地读取；C端settlement GET调用customerQuery、return GET调用refresh，会查询原owner并合并持久状态，不能沿用“全部读取无副作用”的目标描述。UNKNOWN不等于成功，厨房状态不由资金完成推断。

内部传输沿原food-server令牌与typed `DiningPricingInternalApi`、`DiningOrderInternalApi`、`DiningCheckoutInternalApi`、`DiningReturnInternalApi`；嵌入/独立部署使用同一服务合同，令牌不替代资源归属验证。恢复只重放已持久化原意图，不授予新的付款身份或资金命令。POSTPAY、匿名/堂食、小数交易、B端资金写授权、完整权益/活动、厨房及营业验收保持未完成；迁移文件不表示已应用经营库。

部分退款履约边界：本批新增的部分/余量退款须在原子单与商品锁内确认`WAIT_FULFILL`且所有原行`shippedQty=0`；混合履约、已发货部分/余量退款关闭。原完整首次已发货退货仍沿已有真实收货/处置合同，不借退款成功推断库存已归还。该门禁在申请与执行冻结复用同一策略校验，防止资金已退但未发货预留遗留。

### 2.5 非支付业务集中编码（2026-10-24，后端批次已验证，完整目标仍有待办）

用户最新指令：先摘除支付环节，一次性完成未完成的其余功能编码。解释为本批不开发或触发新收款、退款资金执行、券/储值/积分扣减、现金找零/混合支付及订金资金操作；保留既有实现、原单事实和门禁，不删除接口、不伪造已付款。前期后端优先及分阶段编译、批末统一测试的要求继续有效。

沿现有PRD、ADR-040及§2.3执行，设计采用原表族/原单/原事件上的业务状态机。厨房履约、共享打印、日结报表是可独立编码单元，分别实施；主线负责订单路由冻结、事件接线、库存/耗用及双部署集成。默认不改变生产开关、经营库和第三方渠道。

| 工作包 | 具体交付 | 验收及边界 |
|---|---|---|
| N1 制作履约 | 原fnb厨房单/任务/道次/事件实体、Mapper、Service、B端及内部API；真实接受事件派工、部分制作/出品/上菜、等叫/催菜/撤菜/重做 | 只读取冻结路由及原数量，原事件幂等；已收款与已接受分离，不能绕过原准入 |
| N2 打印 | 共享core打印owner与唯一SPI，设备/模板/路由、持久任务/尝试、租约及真实桥接回执；餐饮订单/厨房/预结/退餐单适配 | 未知回执不当失败后自动重打；补打留原号及原因；没有支付权威不生成收款/退款凭证 |
| N3 营业日与经营读模型 | 原business_day/close_run/close_balance/exception/adjustment；营业切点、预结问题、签核冻结、追加更正和业务报表 | 缺资金owner事实显示未知，不伪造实收/利润；阻塞项未解决不签核；现金班次随支付专项保留 |
| N4 订单/售后业务与耗用 | 在原订单/购物车/源行上补行业准入与冻结、原键恢复、厨房撤菜分轨及配方制作耗用 | 未实现的真实库存精度/消费owner必须先实现合同；无实物回执不假返库，无真实支付不假退款 |
| N5 端点及部署集成 | food-web B/C入口、food-server内部、food-client remote、嵌入装配、数据域、schema版本、原计划/JSON/API/代码清单对账 | Controller/Service/Impl/嵌套Mapper按平台命名，核心不能依赖food实现；无空壳或占位成功 |
| N6 集中验证 | 先完成各工作包生产编译，再统一单元/原生数据库/本地远程契约/旧零售回归及独立评审 | 设备、外部供应商、离线营业验收另标实际状态；不把编译或Mock等同完整营业验收 |

N7补充：排队原设计FNB-C03没有正式表/API，现明确增加FOOD V10排队票源、永久取号键、叫号/过号/取消。新就座使用同主库短事务锁票→原CheckService.openQueuedCheck→SEATED，原开台、占位、幂等键、审计和事件全成或全退；正常抢桌冲突可重新选桌。旧SEATING仅保留原冻结命令及OPEN_CHECK回执恢复，UNKNOWN不换键；不伪造预约资源hold。已存在的预约只读保留，真实预约资源仲裁及订金仍受原合同门禁。

N4库存边界核验：原正式单仍预留成品SKU，当前没有唯一RECIPE_LEAVES库存计划及排除成品扣减的权威证明。制作事件先落真实配方理论量并标BLOCKED/THEORETICAL_ONLY，禁止调用原料扣库；实际量与成本为空，不是零。单独真实库存提供方接口不等于餐饮获得原料叶子执行资格。物理自提核销复用原验证码、子单及库存原引用，保持买家确认和未付订单状态。原单owner目前只支持整行全量交付，原任务有VOID或派工前撤菜缩量均阻断整单核销，不能以净厨房量出原整单库存。

N1队列及N2自动打印：默认KDS从真实活跃任务反查本店批次，排除已关闭票及已上菜/撤销任务，保留FIFO、多工位与重做；指定batch查询保留完整历史，供打印/交接/原单数量证明使用。接单事件先幂等落原厨房单，再按已配置ORDER或对应工位KITCHEN路由提交持久打印任务；没有配置路由表示仅KDS，不冒充出纸。首次单据身份不含客户端键；额外纸张须按原任务补打并记录原因。

N4耗用查询补齐原FOOD-API-153：沿共享PageData和同一只读REPEATABLE_READ快照返回COUNT及分页；营业日左闭右开、跨度最多93天、页长1～100。skuNo筛选原订单主行SKU，itemRef筛选实际持久耗用子行；全链MAIN/主体/门店/软删除隔离，跨页请求不承诺同一快照。不在查询时补投影、补扣库存或推算实际成本。

N5装配调整：宿主事件适配复用已有shop-base.OutboxHandler，不让food-web增加持久化依赖；独立进程使用真实producer-only source及准确目标元数据，不创建第二worker。共享runner仅在SQL LIMIT前筛选本宿主真实handler，foreign投递与过期租约不被无处理器宿主领取/改为失败。不能以测试facade或虚构消费者代替。

执行记录：本批仅对达到实现及验证条件的条目勾选；§2.3重排清单仍是完整需求总账，涉及支付或明确后续专项（Keeta仍仅规划）的条目不自动改完成。发现无法绕开的真实合同缺口时记录具体owner与未完成行为，继续不依赖它的工作，禁止改成模拟成功。

### 2.6 经营查询补齐（2026-10-24，历史提案未编码，报表整体后置）

> **优先级更新：本节及报表TDD整体排在业务功能之后，当前暂缓，不是下一批编码入口。** 此前要求先输出报表方案：实时查询＋每日定时汇总到独立统计库。以下为被替代的实时期间汇总提案，R04不再按逐日扫描业务表实施；R01～R03仅作为业务日结只读目标保留。本轮无对应Java/SQL新增，完整方案、7张候选表、报表和任务清单见[报表TDD§9](./design/TDD-B端报表库与日结.md#9-餐饮实时查询与独立统计库方案2026-10-24设计待实现)。

原提案档位1，依据原CKO-25/RPT、原需求C09/C10及已有营业日/日结表。以下仅保留历史实施设想，不授权当前执行；如后续实施，复用当前隔离分支；不另起规划项目、不修改历史迁移。采用原BusinessCloseService/Impl、FoodMappers三类原Mapper及原内部/远程门面。

| AC | 功能与实际合同 | 实现及验证 |
|---|---|---|
| R01 | GET /biz/x/food/business-days；from/to左闭右开，page/size，status；PageData<DayVO> | 本店MAIN实体/软删除SQL；同一只读REPEATABLE_READ内COUNT及分页，稳定倒序 |
| R02 | GET /biz/x/food/closes；from/to、可选currency/status、page/size；PageData<CloseVO> | 原run JOIN原day确认门店，读取冻结快照、不重算历史、不用FOR UPDATE |
| R03 | GET /biz/x/food/closes/{closeNo}/exceptions；可选status、page/size；PageData<ExceptionVO> | 先确认原close本店存在，外店同404；原exception/run/day同scope，不能借只读接口resolve/carry |
| R04 | GET /biz/x/food/reports/business-period；from/to、currency；PeriodReportVO | 营业日口径，最多93天；复用原collect逐日统计，整段同一只读快照，汇总单位分别累加，真实sourceHash。最多10000源批次，超限拒绝而非返回截断报表；不当收入/实收/成本 |
| R05 | 双部署及授权 | 现有B端ORDER_VIEW+FoodBizStoreScope，内部服务token及service门店归属复验；typed LocalDate/PageData/records，远程可调用 |

步骤：1. 本节冻结参数/边界并补真实Mapper和服务测试；2. 一次编码DTO、原Service/Impl/Mapper、Controller/internal/remote；3. 串行生产编译；4. 集中相关/完整FOOD、原生MySQL新增用例、双部署装配和文档检查；5. 最终独立审查、实际对账与范围明确的本地提交。本轮不推送、不部署、不迁移经营库，支付继续摘除。

Ruling：期间报表沿原订单营业日归属统计厨房/退餐，不改为事件发生自然日；同一单次请求保证快照一致，跨次分页不保证相同水位。历史签核与当前统计分开展示。这个口径复用原ReportVO且明确timeBasis；若需要事件发生日，作为另一版本实现，不能静默混合。

### 2.7 初始员工代客下单账本（PICKUP基线；扩展见§2.8～2.12，未编译/测试）

档位1；依据§2.3 B1及原FOOD-API-042。沿原Java包和三端入口，在既有订单/库存来源合同上增加独立员工路径，不移除C端“操作人=顾客”门禁。用户要求全部编码、全部编译后集中测试，本批测试代码先编写，暂不运行RED/GREEN、基线或模块测试；不得记录测试已通过。

| AC | 当前切片设计 | 编码落点/待集中验证 |
|---|---|---|
| S01 身份分离 | 员工从B端认证上下文取值；请求选择真实已注册顾客。仅POS/WAITER、PICKUP、POSTPAY、完整整数MAIN车；同车原买家非空时必须一致 | CreateStaffDiningOrderReq、BizOrderBatchController、DiningOrderServiceImpl；匿名/设备、堂食/外送另按后续模块扩展，禁止员工冒充顾客 |
| S02 权限与报价 | VERIFY写权限、当前主体/门店；使用staff报价与最终重验，不构造顾客Credentials；报价当前无权益，不消费资产 | 核心placeForStore显式路径；原place/cancel保留消费者门禁；拒绝员工路径PREPAY及携带顾客Credentials |
| S03 原键及恢复 | 哈希绑定员工、顾客、门店及完整请求；批次buyerNo与operatorNo分别保存，actorRealm=MERCHANT；恢复只查询原员工、原顾客、原批次键 | 原表原Mapper，无增量迁移；核心queryForStore及餐饮recoverOriginal；不换操作人查原意图，不借读取重开单 |
| S04 双部署 | B端入口→本地服务或typed内部接口→独立food→原core权威Port；内部token不是顾客支付授权 | DiningOrderInternalApi、InternalDiningOrderController、RemoteDiningOrderService、IndustryOrderInternalApi及原adapter |
| S05 真实未付 | 创建原核心订单并获库存生命周期3未付受理，才派厨房事件；原单WAIT_PAY且paidAt为空 | 复用原acceptUnpaid；不触发PAY、退款、储值或积分 |

实施顺序：先编写核心拒绝/真实原行/未付库存场景及餐饮范围/恢复测试代码；再接核心独立员工合同、餐饮持久意图、公开入口与双部署；登记机器设计和原清单。继续其余业务编码后统一编译，最后集中执行包括故障/越权/原单幂等与旧消费者路径的测试及消融。当前状态仅为编码中，不能提前勾选§2.3 B1。

当前编码记录：已写入B端POST /biz/x/food/stores/{storeNo}/batches、CreateStaffDiningOrderReq、staffPlace、核心placeForStore/queryForStore、原身份恢复及两种部署传输。复用原Mapper及表字段，无增量迁移。核心真实原行/税份额/未付库存场景扩展到员工向量；餐饮补原身份持久化/重试、超时恢复、跨店拒绝，web补远程回执及JSON注入用例。测试均仅编写，未执行；整体业务仍编码中，不进入统一编译或集中测试阶段。

设计生成记录：首轮因隔离目录无@viz-js/viz依赖失败，已使用已有本地/tmp/food-er-render依赖重新生成API和原类/方法清单；未安装依赖。git diff --check仅为空白检查，不计入业务测试。无提交、推送、部署或经营库操作。

Ruling：本节保留原PICKUP/MAIN切片设计，当前已由§2.8的真实整数ADDON及§2.11的专用DINE_IN合同扩展；真实注册顾客仍为必要条件，匿名/设备和小数交易保持后续任务，禁止冒充身份、截断小数或将堂食写成自提。

### 2.8 连续业务编码扩展（2026-10-24，编码中，未编译/测试）

沿§2.3，继续档位1，不重复已交付配置，不扩大支付和报表范围。三个独立模块按dispatching-parallel-agents并行编码；共享实体/Mapper/装配及订单由主线整合。以下是编码约束，不是完成声明。

| 模块 | 具体合同及边界 | 数据与验收待办 |
|---|---|---|
| 实际加料成交 | 原MAIN与MODIFIER ADDON按(root,componentRef)分别绑定核心原行、税额、库存整数数量；餐饮父行唯一，组件及原分配独立存储；仅POSTPAY扩展 | 复用fnb_order_component/原allocations；不把套餐父子需求或小数截断为整数；核心/餐饮数量金额守恒场景待集中测试 |
| 加料快照持久化 | 原component.choice_no为套餐专用NOT NULL，MAIN/ADDON不得伪造套餐choice | V15 choice_no改可空，新增component_kind/source_component_ref；原modifier.source_no扩至64以保留原规格标识，写真实选择快照 |
| 预约资源 | 本地FOOD时段桌台分配，复用TableResourceLocks；创建/修改/确认/取消/爽约/到店转台账，原键及CAS | V11新分配表；预约到店只占[当前时刻,原endAt)窗口，不能因更晚不重叠预约被拒；未知离店时间的散客开台保守检查未来预约 |
| 厨房交付 | 包装确认、部分实物交接及骑手身份/引用留痕；保留原行/原组件/原回执及数量，不冒充核心库存出库或订单完成 | V12服务事件custody_snapshot；复杂组件MAKE与父级ASSEMBLE/PACK不得重复派工；设备真实出纸仍需外部硬件 |
| 营业日运营 | 非资金SYNC异常提议/双人审批结转；历史签核不改写，AR/PAY_UNKNOWN/REFUND/CASH_DIFF不可假消除 | V13审计列，资金完整性保持UNKNOWN；具体协议如下 |

N3 NONPAY营业关账补齐（V13，编码中，未编译/测试）
档位1，依据§2.3 B10。继续原BusinessCloseService、原fnb_close_*表。
POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:propose-carry，body version/ownerNo/nextDayNo/reason。
POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:approve-carry，body version。
GET /biz/x/food/closes/{closeNo}/exceptions 返回本候选冻结异常及提议/审批证据。
FINANCE写、ORDER_VIEW读；scope来自认证上下文，双部署原typed内部协议同步扩展。
仅SYNC可提议，OPEN→ASSIGNED→CARRIED。提议人和审批人必须不同，业务责任ownerNo仅为工作交接业务键，非员工权限证明。nextDayNo必须本主体本门店且营业日期严格更晚；源/目标日按dayNo排序加锁。签核后不许修改异常。版本冲突409；不同键同内容命令重放返回原证据，不同内容拒绝。
审批生成LOCAL_NONPAY:<业务键>引用，不接受客户端外部审批号；不能resolve、carry或改写AR/PAY_UNKNOWN/REFUND/CASH_DIFF，也不表示资金已结。
签核继续复核原sourceHash；只允许原SYNC异常全部得到双人CARRIED审批后操作签核。营业日继续CLOSING，金额/收退款完整性仍UNKNOWN。冻结run报告/指纹不改写。
目标日collect读取已签原run的有效CARRIED异常为新SYNC来源（原exceptionNo），继续保留责任与来源，直到后续权威业务事实或明确处置合同可用。不消除原同步不确定性。
历史更正继续只追加；目标日必须比原签核日更晚且营业中，当前day签核快照不能原地修改。现有外部approvalRef不据此假定权威更正已审批，未接权威审批/退款事实仍为缺口。
不创建员工财务交班；现有fnb_cash_shift绑定cash_till/opening_minor，不能冒充非资金班次。
测试先编写但按用户顺序不执行：财务异常拒绝、外店目标/相同或历史日期拒绝、自审拒绝、过期版本拒绝、幂等、SIGNED后写拒绝、结转持续可见、签核历史不变。真实数据库并发及双部署契约待集中验证。


### 2.9 同台账转桌、AA业务分摊与共享人工让价（编码中）

### 同台账实体转桌与加桌（非合单）

- 范围：CheckServiceImpl.transferCheck/combineTables仅改变同一check的桌台占位拓扑；允许PREPAY/POSTPAY及已有原订单批次。checkNo、主体门店、业务日、payMode、人数、座位身份、batch/core order/line绑定、结账/退款/权益事实均不迁移、不重算、不触发资金动作。
- 准入：持有当前check版本并锁定该行；check.status=OPEN且serviceState=OPEN；payMode必须是PREPAY或POSTPAY；existing externalOrPendingForCheck为0。SETTLING、RELEASED、未知状态及外部/待决桌台操作继续关闭。
- 原有防线保留：主体门店条件、区域/桌台稳定次序锁、所有输入桌台版本、外部resourceNo/resourceClaimNo拒绝、目标容量/共享模式/清洁状态、预约pending guard、幂等回执、同事务先占新桌再结束旧占位、审计/outbox。
- 此放行仅替换move中的noTrade。saveSeats与clearCheck继续使用noTrade，带交易的人数/座位改动及清台仍关闭。combineTables表示给同一check增加实体桌台，不表示合并两个check或原订单。
- 预约到店的reservedUntil在获取真实桌台锁后的allocation时再次与当前时间比较，锁等待跨过结束时间即CONFLICT，全事务回滚。
- 验收源码：PhysicalTableMoveTest.sameOrderPostpayTransferAndCombinePreserveRealBindings；emptyPostpayCheckCanMoveWithoutCreatingTradeFacts；pendingOrNonopenCheckNeverChangesOccupancy；reservationConflictNeverReleasesSource。CheckServiceTest原有tradeFacts测试继续覆盖clear/saveSeats拒绝。
- 验证状态：按用户要求仅编码，不运行测试、编译或数据库迁移。真实并发/回滚、设备显示和制作单打印随桌号更新仍待后续验证；跨check合拆单、订单/资金迁移继续不在本切片范围。

源事实边界：当前DiningCheckoutServiceImpl的FROZEN/COLLECTING写在原结账表，不自动回写check.status/serviceState。本切片拒绝check层SETTLING状态，不把它宣称为完整资金结账互斥。由于同check物理移动保持人数、批次、原单和金额全部不变，不新增对这些资金事实的写权限。完整结账状态传播及跨台账合拆仍需独立权威合同。


### NONPAY-AA · 台账业务份额计划（编码中，未编译/未测试）

档位1；依据原§2.3 B5及FOOD-API-140的范围/原行约束。此模块是员工维护的原订单金额责任分配，不是FOOD-API-140核心应收冻结或资金结算；不开放原140路径，不操作付款、退款、会员资产或库存。

新增独立`fnb_business_share_plan`（FOOD V14），保存原来源快照、原核心行金额、分摊矩阵、来源hash、计划hash、版本、原因与员工。一个台账只保留一个本地有效计划；取消保留历史并释放本地计划槽位。DRAFT_BUSINESS_ONLY→CONFIRMED_BUSINESS_ONLY/CANCELLED_BUSINESS_ONLY，不产生SETTLED状态。确认只确认业务责任方案。

输入模式：BY_PERSON按唯一personNo均分；BY_AMOUNT由员工输入各人目标金额，严格要求和=原金额；BY_LINE对每条完整餐饮源行（含其原配料/组件金额）指定一人或多人正整数权重。前端金额只指定分配目标，原金额只取原`fnb_core_line_allocation.core_amount_minor`。先按最小币种单位最大余数法形成按人总额，再按固定原行顺序分配矩阵，保证每原分配行、每人及全量总额精确守恒。余数并列按personNo字典序；原分配顺序按allocationNo，输入重排不改变结果。BigInteger中间计算不溢出，不使用浮点金额。

来源守卫：认证entity/store/check；OPEN check及expectedCheckVersion；当前未结束membership优先，原batch.checkNo只在该批次全局无live membership时回落；多live membership拒绝。check锁后按batchNo排序锁原批次，与原退餐batch锁串行。只纳入ACCEPTED/BOUND原单；每条完整frozen unit必须有唯一原allocation/componentRef/qty/hash/orderItemId，数量/币种/合计与原frozen quote一致。任何非明确REJECTED且资金NONE的退餐引用这些行即拒绝。原行、批次、归属或原分配变化使来源hash变化；查询返回STALE，确认拒绝，不改原历史金额。取消无需来源仍可用。

边界：`localSourcesCurrent`只表示FOOD可见的原来源一致；始终`settlementEligible=false`、`financialAuthorityPending=true`。此模块没有核心实时应收、退款在途/已付抵扣或原端独立取消权威；原核心直接变更且尚未投影到FOOD时不能认证其财务有效性。确认不阻止原来源未来变化，后续读/确认重新校验。personNo是员工声明的业务责任身份，不授予顾客会话结账权限；无原应收排他占用、跨根清偿或资金搬移。

公开B端：POST checks/{checkNo}/business-share-plans；GET checks/{checkNo}/business-share-plans/{planNo}；POST同一计划:confirm、:cancel。写权限复用VERIFY、读ORDER_VIEW；当前认证门店目录校验；所有写操作保留Idempotency-Key及请求hash。typed内部协议`/internal/food/business-shares/{create,get,confirm,cancel}`，同一Service支持embedded/standalone。

测试计划（仅写源码，统一编译后集中执行）：最大余数并列/重排/Long上限/零金额，按人/金额/菜完整守恒；非法金额/缺失或重复源行拒绝；原quote金额/完整unit/归属/撤餐/版本变更拒绝与STALE；同check单有效计划并发/原键重放；跨主体门店与typed HTTP/双部署、MySQL V14唯一槽位及事务回滚。当前不得勾选FOOD-API-140或完整AA资金结算已交付。


N8 NONPAY手工让价权威（CORE V558，编码前建立、随实现修订，未编译/测试）
依据原手工折扣/减免需求与共享PricingEngine manualOperations.limits.source=EXISTING_PERMISSION_AND_APPROVAL_AUTHORITY。复用平台BizIdentityResolver逐次验主体/门店/当前实际角色：员工须CAMPAIGN；店主设置策略/超阈审批，不把客户端actor/approval当权威。
新增ord_manual_price_policy：主体/门店/币种/原因类别/手工类型唯一，enabled、自审让价阈值（当前必须为0）、绝对上限、最低应付折扣率；无记录/禁用默认DENY。店主显式配置，CAS+幂等，变更version立即作废旧审批。
新增ord_manual_price_adjustment：原source/version、原准备hash、配置hash、税hash、策略版本、实际操作员工、原因/说明、操作意图、共享公式计算的实际减免额、审批状态/人/时间/有效期、原键/请求hash。MANUAL_PERCENT、MANUAL_AMOUNT、MANUAL_WAIVER；任何CASH_ROUNDING继续按现共享编译器MANDATORY_POLICY拒绝，不改钱箱/现金找零。
提议按当前源+商品政策+有效共享配置计算，不接受单价/总价/税额/approvalRef；尚无可信餐饮营业日与员工累计额度owner，selfLimit固定0；员工所有非零提议均PENDING，只有不同当前店主可APPROVE。真实当前店主可在显式绝对策略与原因/源/配置/税门禁内发起OWNER_ORIGINATED让价，服务记录其店主权威，不伪造第二审批人；maxLimit/最低应付率始终硬门禁。策略原因必须存在于当前共享配置。准备/配置/税/角色任一变化及过期则物化或重验拒绝，必须新提议。
算术仅复用CouponCalculation、PricingEngine、TaxEngine；实际审批证据由持久记录及新鲜平台权限提供。仅整份活跃FOOD CART的MAIN及已获原单加料合同支持的ADDON价格片段、单一手工意图、不并用优惠券/储值/积分/活动。首版税基限INCLUSIVE+PAYABLE_GROSS或EXCLUSIVE+TAX_EXCLUSIVE，其他基数组合缺精确配置调整合同则拒绝。
实际接口：GET/PUT /biz/pricing-manual-policies/{currency}/{reasonCode}/{kind}（店主），POST /biz/pricing-manual-adjustments（CAMPAIGN），GET /biz/pricing-manual-adjustments/{no}，POST /{no}:approve（不同店主），POST /{no}:quote（原操作员工，真实共享报价保存）。审批仅让价报价，不收款，不退钱，不授予原单创建能力。
实现独立PricingManualAdjustmentService/Impl、实体/Mapper/DTO/Controller、纯共享编译适配策略。测试先写不执行。V558新迁移；不改冻结迁移。不改PricingCheckoutServiceImpl/PricingCheckoutPort/IndustryOrderServiceImpl/config；返回精确集成补丁。父任务已编码共享checkout的handles/verify分支及POSTPAY原单让价守卫/折扣持久化；PREPAY继续拒绝非零折扣。尚未统一编译、执行核心与双部署测试，不据此声明可营业。
待集中验证：缺配置拒绝，外店/撤权拒绝，原因/币种/源版本/税版本/配置版本变更拒绝，阈值及不同店主审批，过期及幂等冲突，整元/分税舍入守恒，无资金计划，原单不接受未经该权威重验的手工报价。

明确剩余：员工/营业日累计限额及连续小单防绕过尚未实现；缺少核心PricingSourceFacts中可信餐饮营业日/切日规则事实和对应累计额度权威。当前员工自审阈值固定0，每笔非零需店主显式审批，是保守替代门禁，不能称累计配额已实现或完整CKO-18完成。

### 2.10 批次业务查询及本轮编码交付边界（编码中）

### FOOD-API-123：原餐饮批次分页摘要

- GET /biz/x/food/stores/{storeNo}/batches，沿FoodBizStoreScope.path与ORDER_VIEW；主体及操作人只取认证上下文。分页page默认1、size默认20且<=100、page<=10000。过滤status、businessDate、orderSource、originalCheckNo、checkNo（当前有效归属）。原check字段与当前membership过滤独立且可合用。
- 真源为MAIN租户、指定entity/store且deleted=0的fnb_order_batch；V1 fnb_check_batch_membership以deleted=0且detached_at IS NULL为有效，不使用不存在的status字段。有任何同tenant/entity/batch的有效membership（全局、非仅匹配check）即取代原batch.check_no；只有完全没有有效membership时才回退原check。列表只暴露同entity/store且未删除check的当前归属，外门店/孤儿membership不会让原归属复活，也不泄露外门店checkNo。
- COUNT与page共用同一SQL过滤片段，按created_at DESC,batch_no DESC稳定排序。服务开启独立REPEATABLE_READ只读主库事务，确保count/page/membership/binding四次读取使用同一快照；不加入调用方未知隔离级别事务。
- 批次页最多100行；membership和core binding两次按本页batch集合批量查询，每次LIMIT 10001，超过10000行明确CONFLICT，不静默截断或N+1访问。无远程订单/资金查询，无报表库或作业。
- 响应仅返回批次原始来源/业务日/履约/支付模式/本地状态与版本、创建/受理/计划时刻、原check/当前check列表及membership权威标志、原quoteNo/hash、原订单绑定列表（引用与本地绑定状态）。不返回原始frozen_request、buyer/operator/payer凭证、金额或推断当前应付/支付成功状态。
- 服务边界：DiningBatchQueryService及独立Mapper；新BizDiningBatchQueryController、typed internal API/controller、RemoteDiningBatchQueryService。复用原表，不迁移，不修改DiningOrderService。
- 验收：DiningBatchQueryServiceTest覆盖当前membership全局优先、detached回退、跨主体/门店/租户/删除隔离、过滤/分页一致、稳定排序、原绑定摘要与敏感字段排除、非法过滤及详情行上限。传输测试覆盖认证门店、ORDER_VIEW、typed参数及原内部token。
- 验证状态：按用户要求仅写代码和测试，测试/编译/迁移全部未执行。


当前整合包含员工代客、整数加料、预约生命周期、同台账实体转桌/加桌、包装/骑手交接凭证、SYNC双人结转、AA业务份额、批次查询及共享手工让价。FOOD V11～V15、CORE V558均为迁移文件，不表示已应用。

原§2.3完整目标仍未全部完成：匿名/设备正式下单、外送下单与堂食/外送核心履约完成、套餐排他库存树、小数成交/物料库存、真实制作原料扣减与历史成本、跨台账合拆单/财务AA、完整多端UI及离线、硬件实机仍有缺口；支付、报表和Keeta真实接入继续按既定范围后置。不能把本轮多个可用切片标成全部业务编码已完成。当前未执行编译或测试；按用户顺序保留测试源码，统一编译及集中验证尚未开始。

### 2.11 员工正式堂食后付与同桌多轮加菜（编码完成，未编译/测试）

### 员工正式堂食后付订单

- 新增IndustryOrderPort/Service.placeForDiningRoom(Command,key)，以及原内部POST /internal/trade/industry-orders/store/dining-room/place与原FOOD通道适配。默认拒绝未实现能力，不改变Command record字段或既有调用协议。
- 原consumer/staff自提入口继续STORE_PICKUP，requestHash严格保持PricingSnapshotHash.of(command)。堂食用PricingSnapshotHash.of(List.of("INDUSTRY_DINE_IN_PLACEMENT_V1",command))区隔，同原key跨履约调用必须冲突，不复用错履约订单。
- 同一private placeOriginal携带独立fulfillment参数，SKU商品权威必须明确支持DINE_IN；原子持久化OrdSubOrder.fulfillment=DINE_IN，仍是POSTPAY、WAIT_PAY、paidAt=null、inventory lifecycle3和原acceptUnpaid流程。原非零折扣/组件分摊逻辑保持。堂食不创建收货人，不伪装自提或配送，不完成履约，不扣原料库存。
- 商家准入收到真实DINE_IN，不映射STORE_PICKUP。AdmissionPortImpl明确归入T1：堂食由商家自己制作和交付，缺独立第三方交付留痕；与商家自送同等级，沿原S×T矩阵。S1/S2按原T1策略允许（未发生自提降级时不新增买家确认）、S3拒绝，未知履约默认放行不适用于已注册DINE_IN。DINE_IN仅加入Fulfillments.ALL，不加入PHYSICAL、SERVICE_LIKE、PICKUP或默认集合。
- FOOD staffPlace只允许原POS/WAITER、真实已注册buyer、POSTPAY和PICKUP或DINE_IN。DINE_IN必须有原checkNo，调用原CheckCartService.requireDiningRoomCart检查同主体门店、OPEN台账/serviceState、POSTPAY、OPEN原购物车。首次批次准备在原主库事务内、持有check锁再次核验，再锁原cart、写原PREPARED批次。既有checkNo与订单身份不更换，批次冻结fulfillment保持DINE_IN。
- 多批次可关联同一check，不合并资金根。每cart仍保持原版本/单次冻结权威。沿用现有bind内已具备的CLOSED+version CAS；DINE_IN绑定前先check→cart锁序重新核验，在本地真实绑定并ACCEPTED后关闭原车，createCart排除CLOSED后可为同check建立新车。无新增通用解冻/归档例外。
- 源码验收：核心堂食订单持久化/WAIT_PAY+无paidAt/lifecycle3、SKU无DINE_IN拒绝、PICKUP原hash兼容及跨履约同key冲突；商家T1三档；FOOD堂食选择专用能力、有效台账与准备锁期重复核验；内部token和typed adapter保留原key。
- 按用户要求仅写代码与测试，全部测试/编译/迁移待后续统一执行。

补充完成合同（编码前记录）：原bind本已关闭成功购物车，本切片不重复新增关闭API；仅将DINE_IN首次绑定纳入原CheckCart活台账锁、核对冻结facts哈希与cart/quote/source版本和原check/fulfillment，完成实际绑定及ACCEPTED后执行原CLOSED CAS。未知/处理中、失败或验证不一致不得关闭；绑定/配额/事件/闭车同原主库事务全成或全退。旧PICKUP闭车与CLOSED原单/售后授权行为保留。

交接元数据：原FOOD-API-042员工提交端点不变，DINE_IN新增可用子能力；原内网POST /internal/trade/industry-orders/store/dining-room/place，IndustryOrderInternalApi.placeForDiningRoom(Place)，复用原Command/Result，不扩展JSON记录字段。新增Fulfillments.DINE_IN只进入ALL；AdmissionPortImpl T1显式注册。Food CheckCart requireDiningRoomCart是同服务本地能力，默认拒绝，故不新增失锁的远程验证端点。旧PICKUP Command哈希及staff/customer持久化请求键不改，无迁移/新配置。

轮次纠正：已确认旧bind()本来就使用UpdateWrapper关闭成功购物车（最初grep setStatus时遗漏）；不存在已宣称的“必须新增闭车API才能下一轮”阻塞。本次保留旧闭车CAS并仅加强DINE_IN首次绑定的check→cart锁及冻结完整性验证，CLOSED后同check的createCart可生成新车和独立批次，不合并支付根。

### 2.12 非支付退餐与旧资金售后隔离（编码完成，未编译/测试）

### NONPAY-RET · 员工退餐/撤菜申请（编码中，未编译未测试）

复用原fnb_return/fnb_return_item，不新增资金或库存台账，不申请原售后/退款，不新增迁移。仅认证当前门店员工可对已受理POSTPAY原批次申请原菜行数量；入口权限AFTERSALE。PREPAY或原订单已付、未知、关闭及缺失原来源拒绝新申请。先在FOOD事务外通过原IndustryOrderPort.getForStore读取WAIT_PAY/POSTPAY/lifecycle3/acceptedUnpaid和原绑定/来源版本，再锁原批次并重验本地来源；保存原读取证据与时间。这不是核心付款冻结，不能保证付款在读取之后不变化；后续变化不赋予本记录任何退款/资金权限。

新申请status=BUSINESS_REQUESTED、financial_status=NOT_REQUESTED、core_case_ref=NULL、kitchen_status=PENDING；request_snapshot使用独立kind=BUSINESS_RETURN_V1和schema=1，保留原batch/cart/binding、员工/原顾客、MAIN原行及所有组件分配、原数量/版本/hash/原权威读取。request_key使用独立业务前缀hash，不与原FOOD_RETURN_KEY_V1金融来源冲突；原键/请求hash在原表持久查回，重试不能重复占量或更换员工、批次、原菜行。

一个退餐item对应完整原foodLine MAIN allocation（component_no=NULL），数量按原菜行单位计；必须验证该原菜行全部冻结MAIN/ADDON单元、原allocation以及真实component记录一致，MAIN数量=原菜量，拒绝漏配料/重复/缺失/伪造原映射。已有KitchenReturnService.handle沿原returnNo独立选择CANCEL/LOSS/KEEP，并按原比例处理组件任务；此申请不自动判厨房已处理、不调用资金、不返库。

原batch FOR UPDATE锁下累计该原菜行全部既有活动退餐数量（含金融来源），合计加本次不得超过原量；组件专属退餐无法映射为整个菜的可用量时保守拒绝，不忽略。只有明确未处理且已取消/拒绝且资金NONE/NOT_REQUESTED的记录可释放本地申请容量；UNKNOWN、HANDLED和资金在途继续保留。源读取/申请/厨房处理/取消复用原批次锁顺序，禁止另起绕过原量守恒的入口。

GET读取业务原申请及真实厨房状态，不刷新资金。CANCEL仅允许本业务marker、无corecase、financial NOT_REQUESTED、厨房PENDING、预期version匹配；原行快照保留，追加原action_log取消证据和原键hash，再把status改CANCELLED，financial/kitchen原轨不改。厨房HANDLED后需未来独立撤销/冲销合同，本接口拒绝。原paid return/refund、核心应收减免/改单、原库存返还及物理退货凭据继续由原owner负责；本地撤菜申请不减原应付、不代表退款完成。

公开B端：POST /biz/x/food/batches/{batchNo}/business-returns；GET /biz/x/food/business-returns/{returnNo}；POST同资源:cancel。typed内部/remote/embedded同Service。父任务必须同时为旧DiningReturnService.customerGet/customerCancel/authorizedReturn/frozen/recoverOriginal和原恢复候选查询加NOT_REQUESTED/marker隔离：不能把新snapshot解析成旧金融command，更不能调用IndustryAfterSalePort。

测试只写源码，最后统一编译/执行：原单已付/未受理/跨店/丢失MAIN/ADDON拒绝；累计申请和取消释放；原键重放/变更内容拒绝；原厨房HANDLED禁止取消；取消版本与审计；原service/HTTP双部署、原生批次锁竞争及事务回滚、旧金融入口marker拒绝，无真实资金或经营库。

父任务整合：本地与独立部署装配已接线。旧资金恢复候选与CAS双重排除NOT_REQUESTED；旧C端get/cancel、staffGet及recoverOriginal拒绝该类型，防止解析为金融命令。旧金融prepare与新业务create共用batch锁，存在活动业务claim的同菜行拒绝新金融申请；明确未处理PENDING且已取消/拒绝可释放本地容量，旧金融原键重放保持。新增隔离场景测试源码，全部待集中执行。

### 2.13 员工处理中批次撤销（编码方案，未编译/测试）

依据§2.3业务4及原取消/恢复需求。沿原Java/SPI与双部署，不新增资金执行、表或迁移。

Ruling: 本次取消只处理MERCHANT、POS/WAITER、POSTPAY且尚无本地原单绑定的PREPARED/PLACING/UNKNOWN；已绑定/已受理不改取消，继续原业务退餐。原因是现有生命周期3已受理单没有完整厨房/应收撤销合同；错误放开会抹去真实债务或已制餐事实。

| AC | 设计与代码落点 | 待集中验证 |
|---|---|---|
| PC1 原身份撤销 | IndustryOrderPort/Service.cancelForStore(Command,key,fulfillment)，复用原来源及原键；PICKUP原hash不变、DINE_IN独立taghash | 核心原键隔离、无顾客Credentials、跨主体/更换hash拒绝 |
| PC2 撤销优先竞态 | 无原intent时验证原CANCEL_PENDING批次冻结来源/原命令/撤销审计后写原RELEASED tombstone；已有PREPARED先RECOVERING再用原库存cancelAll原键释放；BOUND/ACCEPTED明确冲突 | tombstone阻止迟到place，库存UNKNOWN不转成功，无规则/资金调用 |
| PC3 持久本地请求 | POST /biz/x/food/batches/{batchNo}:cancel，VERIFY；CancelDiningOrderReq(version,reason)+Idempotency-Key。原batch锁序store→cart→batch，原fnb_action_log持久actor/reason/requestNo及请求hash；batch=CANCEL_PENDING | 同原键重放、换内容/操作人/版本冲突、跨店与已绑定拒绝 |
| PC4 恢复与终态 | 原recoverOriginal对有真实撤销审计的CANCEL_PENDING重复原core cancel；只有RELEASED才释放原销量额度并CANCELLED；核心已BOUND则保留实际订单、撤销拒绝并恢复绑定 | 超时重启不重开单、不重发不同键；并发place不覆盖取消意图；不能因查询不存在判已取消 |
| PC5 双部署与边界 | 原DiningOrderInternalApi/endpoint/Remote及Core channel typed接线；原Command/Result不改结构；无PAY/退款/原料返库 | token、回执范围、核心/餐饮事务回滚；先写测试源码，全部编码后统一编译及集中执行 |

实施顺序：补核心及FOOD场景测试源码→核心独立staff cancel与typed传输→原DiningOrderService取消审计/恢复→B端和双部署→机器API真源与代码清单生成。按用户已确认节奏不运行逐项测试/编译，不将源码完成记为已验证。

2.13编码账本：PC1～PC5的核心/餐饮/B端/typed双部署源码已写入，复用fnb_action_log，不新增迁移。关键入口为BizOrderBatchController.cancel → DiningOrderService.staffCancel；核心独立cancelForStore保留原Command与hash。初始actor/reason/key/hash审计与CANCEL_PENDING同本地主事务，取消与正式单竞态丢失时追加REJECT_CANCEL_PLACEMENT并恢复原实际绑定。

审查及修正：独立静态审查发现1项Important：原source.staff会重新检查实时额度，FOOD已占满原额度时可能自锁；下架/售罄也可能卡撤销。已新增PricingSourceFactsPort/PricingSourceService.cancellationForStore与DiningOrderService.authorizeCancellation，仅验证原批次冻结价格事实、原完整Command、原fulfillment/key及持久撤销审计。默认缺失能力拒绝，不回退live source.staff；通过原FOOD typed内部接口兼容双部署。此能力不是新的下单或价格授权。新增额度2被原批次占满2、售卖事实不再被调用、来源篡改/原键不符等回归源码。按用户要求未运行RED/GREEN、统一编译、测试或消融，修正仅记CODED_PENDING_VERIFICATION，不记已验证消除。

| AC | 已写入的具名测试（全部待执行） |
|---|---|
| PC1/PC2 | IndustryOrderServiceTest.staffCancellationRejectsConsumerCredentialsAndUnknownFulfillmentBeforeAnyOwnerCall；uncertainCancellationRetainsOriginalIntentAndResumesSameOwnerReference（消费者/员工自提/员工堂食）；cancellationBeforeIntentCreatesOriginalTombstoneAndBlocksLatePlacement |
| PC3/PC4 | DiningOrderServiceTest.pendingStaffCancellationSurvivesRestartAndReplaysTheOriginalOwnerKey；originalCoreCommitWinsCancellationRaceWithoutDroppingDebtOrKitchenAcceptance；acceptedStaffBatchCannotUsePendingCancellationToEraseItsOriginalOrder |
| PC5 | StaffOrderTransportTest.pendingCancellationTransportPreservesActualActorAndOriginalKey；cancellationJsonCannotCoerceVersionsOrInjectAuthorityOrMoney；CoreDiningRoomOrderPortTest.cancellationKeepsOriginalFulfillmentAndDoesNotCallConsumerCancellation；InternalDiningRoomOrderEndpointTest.staffCancellationTokenGateKeepsOriginalKeyAndFulfillment |
| 冻结撤销来源 | PricingSourceServiceTest.pendingCancellationUsesDedicatedOriginalAuthorityWithoutLiveSaleabilityFallback；FoodPricingSourceFactsPortTest.cancellationDelegatesToFrozenOrderAuthorityWithoutReadingLiveCatalogOrQuota |

### 2.14 已编码范围的统一编译与集中测试（2026-10-24，本批范围通过）

用户明确启动编译与测试。范围是§2.7～2.13已经写入的共享CORE/门店准入与FOOD五模块受控后端，不据此勾选§2.3全部业务目标。先用Java21离线clean install -DskipTests统一编译生产和测试源码，再集中执行测试；已有依赖安装为本轮构件，避免独立FOOD使用旧包。

| 验证 | 本轮真实收据 | 日志 |
|---|---|---|
| 核心、商户、宿主及其依赖源码/测试代码编译 | backend shop-app -am clean install -DskipTests成功 | /tmp/food-current-backend-compile.log |
| 共享CORE、商户及依赖完整测试 | 1242项，0失败/错误，91条件跳过，1151实际通过（含job-api 11项）；跳过不计通过 | /tmp/food-current-core-tests.log |
| FOOD五模块最终全量install | 已成功；782项（含迁移打包IT 3项），0失败/错误，54原生/条件跳过，728实际通过 | /tmp/food-current-food-final-all.log |
| 宿主兼容场景及集成形态 | 91项，0失败/错误，1仅standalone条件跳过；履约/物流/库存事件/拼团/储值原场景通过 | /tmp/food-current-embedded-tests-package.log |
| 独立形态宿主package | 8项，0失败/错误/跳过；仅food-api/web，无core/server | /tmp/food-current-standalone-tests-package.log |
| 最终集成形态宿主package | 更新装配修复后8项，0失败/错误，1仅standalone条件跳过 | /tmp/food-current-embedded-final-package.log |
| 独立库存owner原合同 | 5项全部通过；生产/小数及原键释放合同 | /tmp/food-current-inventory-owner-tests.log |
| 隔离MySQL迁移、服务守卫及独立进程 | FOOD主库20项＋服务守卫477项＋独立服务/定价9项，另原商品规格owner 4项，均无失败/错误/跳过；守卫含mock，非全部原生SQL | /tmp/food-current-native-final-prepay-fixture.log |
| 设计、生成器与SQL | Node 65项通过；Python 7项通过；SQL棘轮无新增违规 | /tmp/food-current-design-final-tests.log、/tmp/food-current-schema-generator-tests.log、/tmp/food-current-sql-portability.log |

定位并修正的实际失败：预约Mock updateById重载歧义改为类型化匹配；普通开台将null预约截止放入指纹导致NPE，现保持旧六项指纹，仅有截止时追加独立tag；AA按人分摊的amountMinor可空却误用必填版本reader，现允许null但继续拒绝小数/字符串/负数/溢出；两处Mockito重设触发原answer，改doAnswer；预约/批次查询硬依赖StoreCardQueryPort导致无关上下文启动失败，沿原ObjectProvider延迟取真实owner，缺owner不会返回假门店；设计/原生测试的表数及Schema版本断言同步V15，并检查两张规划同步表仍未创建。独立服务旧夹具此前验证后付门店，随后执行仅限先付空账单的改座/清台，现于旧先付流程前显式切换测试策略。上述均保留原业务断言和门禁，未放宽已冻结V1、SQL棘轮或真实权威校验。

消融记录：临时从renderFoodV1排除集中移去fnb_business_share_plan，目标测试真实失败74≠73；finally原样恢复源码，Node完整65项重新通过。日志/tmp/food-current-v1-ablation-red.log与/tmp/food-current-v1-ablation-green.log；没有修改实际V1迁移。

最终集成jar逐字节核验：当前FoodAutoConfiguration与编译class一致；15条FOOD迁移及CORE V558与源文件一致；food-api/web/core存在，food-server未混入。独立形态打包时仅food-api/web，当前最终构件为集成形态。核验记录/tmp/food-current-embedded-final-artifact.json。

MySQL收据仅验证FOOD V1～V15与所列JDBC场景，CORE仅应用本批V556～V558，不是完整CORE Flyway启动验收；既有V548/V549/V550重复版本及原架构/schema/PAY/前端问题仍见§6，不能声称全仓全绿或可发布。临时容器已随脚本finally清理。不同套件有重叠，不合计为唯一用例数，跳过项不当作通过。

可复跑命令（Java21，离线Maven）：`mvn -o -f backend/pom.xml -pl shop-core,shop-merchant -am test`；`mvn -o -f backend-plugins/shop-industry-food/pom.xml install`；`mvn -o -f backend/pom.xml -pl shop-inventory test`；`python3 scripts/verify-food-primary-mysql.py`。双部署定向package的完整类清单见对应日志；本轮失败日志保留，不覆盖为成功。

当前仍不代表支付执行、完整权益、匿名/外送/套餐排他库存、小数成交、实际耗用成本、财务AA、完整多端/离线或实机验收完成。临时MySQL仅本机回环随机端口、随机测试库；不连接经营库。未部署、未提交、未推送。

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
| C01 | ConfigCompiler/ConfigService/Seed真实发布并发、幂等、越权 | 03/08、09 A/B/C1及C2.1/2/3/5已验证；C2.4生产证据待接线 |
| C02 | RuleEngine/PlanSelector/Engine逐券、数量份额及确定性 | 04～06及11B2E基数已验证；实际券/活动owner及完整报价待11，资金确认待14/15及后续 |
| C03 | TaxEngine两种公式、商家配置授权、版本冻结、AA原税与退款上限 | 07、C2.1/2/3/5、10及11B2A/B/E相应税配置/快照/基数已验证；正式交易、AA及退款继续待实施 |
| C04 | DiningPlacement/Prepay/Postpay真实源与双部署链 | 已验证登录H5/PICKUP整数MAIN原单先付受控切片及真实未付准入；员工代客、堂食/外送、复杂组件和完整后付结账仍待实施 |
| C05 | CashPayment/Adjustment权限、盘点、精确找零 | 待实施 |
| C06 | Split/MixedPayment并发、部分成功及UNKNOWN恢复 | 待实施 |
| C07 | StoredValue/Points owner合同、原键恢复、来源/税分类 | 待实施/能力门禁 |
| C08 | PartialReturn原行、原税、原资金及已退在途并发 | 原受控未发货原行售后及本批厨房CANCEL/LOSS/KEEP已验证；原单部分改单/履约、完整售后及资金专项不据此完成 |
| C09 | Print实机、DailyClose迟到事实及报表钻取 | 共享打印后端、厨房单路由、营业日业务报表及预结/签核门禁已验证；实机、财务日结、导出/异常结转仍待验收或实施 |
| C10 | Recovery故障注入、API授权、Architecture/i18n与双装配 | 原键/租约恢复、相关宿主回归、423原生用例及双部署打包已验证；全仓架构/schema/i18n未全部通过，详见§6首条 |
| C11 | Retail兼容、美业原来源、Offline专项/渠道禁用 | 待实施；Keeta保持规划 |

每阶段需保存：实际测试类/方法与Tests run计数、必要的目标数据库IT、架构/i18n检查、关键消融结果、沙箱trace、公开API契约版本。计划检查、Mock测试、数据库已存在不能替代资金/库存/打印营业证据。

## §6 执行记录及设计 → 实现对账


### 2026-10-24：非支付后端集中编码、统一验证与双部署打包

**本次交付为已编码并验证的非支付后端业务批次，完整餐饮需求仍未全部完成。** 保留原订单、资金及库存权威，不删除既有支付实现；本批不新增资金收付、券/储值/积分消费、现金/混合支付或订金执行，不把后付接单记为已付款。沿原项目、原表族、Controller/Service/Impl/嵌套Mapper规范开发，没有另建完整商品/订单或资金账本。

| 实际落点 | 本批交付与证明边界 |
|---|---|
| 接单与后付 | 原核心源订单lifecycle3及真实reserve/acceptUnpaid；父单仍WAIT_PAY、paidAt为空，子单可履约。FOOD接受、额度消费及接单事件同本域事务；仅已登录H5/PICKUP整数MAIN受控范围，不宣称员工代客/堂食/外送成交完成 |
| 厨房与道次 | 冻结已发布工位路线；部分制作/出品/上菜、等叫/叫起/催菜/撤菜/损耗/重做、座位道次和原事件幂等；默认活跃队列与指定批次完整历史分开 |
| 退餐与交接 | 对已有原退餐申请执行厨房CANCEL/LOSS/KEEP，永久原动作回放；与退款、返库分轨。原单自提核销复用验证码和真实库存回执，保持买家确认策略；原量证明不足或撤菜缩量时不核销整单 |
| 共享打印 | core的prn设备/模板/路由/任务/尝试及唯一PrintPort；餐饮订单/厨房/预结/退餐适配和按路由自动入队；租约、桥接回执、UNKNOWN查证、NOT_PRINTED重试及审计补打。预结单为原始订单金额清单，不能当实时应收；未实现厂家SDK或实机出纸验收 |
| 排队与桌台 | 永久取号键、叫号/过号/取消/原键恢复；新就座锁票并与原开台、占位、SEATED、审计/outbox同主库原子提交。旧SEATING仅原键回执恢复，不伪造预约资源hold |
| 营业日与报表 | 原门店营业切点及冻结日期边界、业务数量/厨房事件指标、日结预检/冻结/双人签核/追加更正；canonical快照签名抗MySQL JSON排序变化。没有真实资金事实时保留未知及阻塞，不把经营数量当实收或利润 |
| 耗用 | 原配方/原START事件形成持久理论量及BLOCKED原因，实际量/成本未知保持null；补齐原API153真实SQL筛选与分页。独立库存提供方有真实整数原生单位生产出库合同，但餐饮未获排除成品扣减的唯一原料叶子计划前不调用原料扣库 |
| 双部署及事件 | food-web公开B端、food-server内部typed接口、原foodclient远程门面；独立进程仅真实producer，宿主仅领取本地可执行handler投递，foreign任务不被误失败；核心不依赖food实现 |
| 原设计对账 | 原机器设计保留165目标API、89目标表，另登记50条实际公开路由及52个递归DTO；路径或字段不一致保留明确差异。逐接口CODED_PENDING_VERIFICATION表示完整目标/营业验收未签收，不能由后端测试自动晋级完整目标完成 |

新增迁移：FOOD `V9__production_consumption.sql`（2表）、`V10__queue_ticket.sql`（1表）；CORE `V556__shared_print_delivery.sql`（5表）、`V557__industry_fulfillment_receipt.sql`（1表）。冻结V1及其他历史迁移不改。只在临时容器应用并核验，没有迁移经营库、部署或推送远端；目标设计中的两张渠道同步表仍是规划表，不误报为已迁移。

**实际执行的验证：** 所有Maven严格串行、Java21、`-o`离线；先编译再集中验证。下列数字按各次运行分别列示，存在交叠，不相加为唯一用例数。

| 验证 | 实际结果 | 本机日志 |
|---|---|---|
| 最终FOOD五模块生产compile | 全部成功 | `/private/tmp/food-nonpay-final-production-compile.log` |
| 最终FOOD完整install，含迁移fat-jar IT | 678项，0失败/错误，54条件跳过，624实际通过；包装IT 3项、producer 4项、打印HTTP 4项、耗用列表真实SQL 3项均实际执行 | `/private/tmp/food-nonpay-food-final-all.log` |
| 共享底座及CORE完整测试 | 1135项，0失败/错误，90原生条件跳过，1045实际通过；不宣称这些CORE条件用例全部在本轮另行执行 | `/private/tmp/food-nonpay-shared-core-full-final.log` |
| 独立库存生产owner及相关定向合同 | InventoryProductionPortTest 4、InventoryProductionFactsTest 6等真实执行通过 | `/private/tmp/food-nonpay-core-unified-tests-fixed.log` |
| 最终原生MySQL | 20主库＋390业务守卫＋9独立服务＋4原商品规格权威＝423项全部通过、无跳过；CORE V556/V557六张真实表应用成功 | `/private/tmp/food-nonpay-native-mysql-final-v3.log` |
| 相关宿主/旧零售回归 | 89项，0失败/错误，1仅standalone条件跳过，88实际通过；包括8项履约库存事件、4项源订单通用入口守卫及原物流/拼团/储值16场景/种子重放 | `/private/tmp/food-nonpay-host-compatibility-final-v3.log` |
| embedded宿主package | 6装配用例，5通过、1仅standalone跳过；实际fat jar含food-api/web/core、不含food-server，CORE新迁移存在 | `/private/tmp/food-nonpay-embedded-final-package.log` |
| standalone宿主package | 6装配用例全部通过；实际fat jar只有food-api/web，不带food-core/server，CORE新迁移存在 | `/private/tmp/food-nonpay-standalone-final-package.log` |
| 文档/生成器 | Node完整餐饮设计测试65项通过；Python schema生成器7项通过；schema生成检查及diff检查通过 | `/private/tmp/food-nonpay-50-doc-tests.log`及最终收口日志 |

最终资源收口：仅归一新增V556末尾空白后，共享打印10项、两形态装配和package再次通过；两个宿主jar中V556/V557字节与最终源码一致。日志：`/private/tmp/food-nonpay-embedded-final-resource-check.log`、`/private/tmp/food-nonpay-standalone-final-resource-check.log`。

统一验证中修复：schema readiness仍回传8而非实际10；native测试用旧facade遮蔽真实producer；默认KDS扫描已闭合历史；取餐撤菜后误用净量证明原整单；原库存事件处理过度要求旧零售paidAt/当前子状态；空闲打印信封与远程最新回执核验。库存旧合同保留原PAID语义，新增lifecycle3仍要求来源/范围/数量真实证明。两项测试夹具修复仅纠正H2关闭session比较器及原退款重试时间未到的错误假设，保留真实资金owner和原业务断言，没有改金融生产逻辑令测试虚假通过。

**全仓与发布边界：** 先前全backend扫描在原PAY两项测试处失败（`PayRightsReadProjectionTest`、`StoredValueMigrationReadinessTest`），已在pristine HEAD复现；继续扫描还发现原架构依赖、schema漂移及其他旧资金fixture问题，没有声称全仓全绿。现有CORE V548/V549/V550重复版本会阻断全库Flyway启动，不能通过本批新增迁移临时库成功掩盖；需另按实际已应用历史治理，未任意改号。原全局前端页面可达/i18n问题未因本批后端代码自动消失。相关宿主定向回归已经完整重跑通过。

**仍未完成的非支付完整目标，继续保留待办：** 员工代客/游客身份及堂食/外送正式原订单写入；复杂套餐配料/小数数量成交与对应库存；原单部分改单/取消及部分履约；预约资源hold/预点餐；唯一原料叶子库存计划、实际耗用与真实成本；异步报表导出、异常结转及完整财务日结；各端完整UI、SSE/推送和受控离线、打印厂家/设备实机验收。Keeta依用户要求仍只规划。资金专项继续摘除；不把本批切片或已建表等同这些完整目标完成。


### 2026-10-23：剩余待办及远端统一分支整合

推送准备补录：首次正常push被`pre-push`的阶段交付清单校验拦截。已移除已接通的C端储值门店目录登记；另将9条实际无B端出口的远端存量接口登记到原前端任务表，未扩大known基线。真实Java合同覆盖测试补录6条现有接口后3项通过，导出89操作/138 DTO；合同生成与阶段交付6项测试通过，51条前端待接入清单与任务表一致，反向接口守卫通过。其余页面可达/i18n问题仍未修复，不能声称默认完整push检查通过；相关处理选择已向用户说明。未使用force、未改守卫断言、未部署经营环境。


- §2.3按受控交付基线`a09593529`更新为36项未完成待办，分首个营业版本、后付/现金/复杂结账、耗用成本、各端、后续专项及技术收口；逐项列依赖或提供方及验收，不重新开发已有受控切片。原后端模块计划已指向此入口。
- 本次用户明确授权“全部提交到远端”；正常合并远端`ddc407682`的28项储值/发行主体、运营与发布等提交，保留本地结账、售后及配置历史，不强推、不覆盖远端。两处PAY内部接口冲突均保留双方新增路径及方法。
- 合并后实际执行：CORE/PAY生产编译成功，更新shop-base本地构件后独立FOOD生产编译成功；`PayInternalApiRoundTripTest`9、`IssuerStorePreparationEndpointTest`1、`StoredValueMigrationInternalApiTest`4，共14项通过、0跳过；Node设计检查63项通过。待办36项计数、CKO11～31覆盖、验收字段、文档链接及diff检查通过。
- 上述是合并兼容和文档验证，不宣称对远端28项增量再次完整全量回归；此前8项架构/schema既有问题仍作为待办治理，不因推送改标通过。此轮不部署、不迁移经营库、不开发Keeta真实对接。


### 2026-10-23：受控先付结账与原单售后集中交付

**本批后端开发、编译与集中验证完成；完整餐饮结账方案仍在进行。** 当前范围是登录顾客H5、自营取餐PICKUP、先付PREPAY、整数MAIN菜品、单一原支付根。B端提供报价/复核与批次、收银、退餐读取；B端资金写入、台账AA/跨根、现金/混合/订金、后付/堂食/匿名付款、复杂组件/小数交易、完整权益、厨房处理、打印、日结报表不据此宣告完成。原FOOD-API-140及其余未实现目标继续保留。

设计→实现预提交统计：`246 files changed, 13923 insertions(+), 319 deletions(-)`。沿用本计划§2.4和原表族，改动如下；所有资金事实继续来自PAY，餐饮未另造资金总账。

| 方案落点 | 实际代码清单与作用 |
|---|---|
| 共用报价/规则 | `shop-base/common/pricing/PricingConfigCompiler`分离模板定义校验与真实能力执行校验；`shop-core/trade/service/impl/PricingCheckoutServiceImpl`统一权威来源、商品策略、商家税与不可变报价；初始化可显式传`initialOverride`，不静默修改默认配置 |
| 核心原单 | `IndustryOrderServiceImpl`复用原ord主/子/商品行与创建意图；`OrdIndustryOrderSource`仅存来源/请求/冻结证据；保留H5会话主体并审计真实买家；CORE编号及事务使用显式限定注入 |
| 原支付/退款 | `IndustryCheckoutServiceImpl`及PAY普通支付/发起/退款typed接口；`RefundFinancialOwnerServiceImpl`按原资金owner查证；`RefundPreparationServiceImpl`事务外取真实原回执、锁内复验；旧FREE通过原outTradeNo取真实tradeNo推进订单 |
| 餐饮本域 | `DiningPricingServiceImpl`、`DiningOrderServiceImpl`、`DiningCheckoutServiceImpl`、`DiningReturnServiceImpl`、`SalesQuotaClaimServiceImpl`；原fnb批次/映射/结算/charge意图/退餐/限售表，Controller均在food-web，内部接口/remote支持两种部署 |
| 原行/原税退款 | `BasicRefundPolicy`、`OriginalRefundEvidenceServiceImpl`与原refund执行器；按原数量前缀分摊，保留原净额/税额/含税额；新部分/余量仅允许原完整未发货事实，首次完整已发货退货保留原收货合同 |
| 库存与恢复 | 原预留重放逐项比对、原预留查询/取消墓碑、退款与收货共享累计上限；未发货全退不因子单已REFUNDED而漏释放；CORE/FOOD原键恢复任务在短事务claim后调用owner，保留UNKNOWN，禁止新扣款 |
| 数据/文档 | 核心`V554`来源元数据与`V555`原售后请求身份；餐饮`V8`补请求身份、MAIN组件引用、恢复时间；V1字节不改。原设计JSON额外登记19条实际公开合同，目标清单未覆盖替换；OpenAPI 1.2登记初始化可选覆盖 |

需求→验证（真实执行，类中其他回归也保留）：

| 验收点 | 对应测试 |
|---|---|
| 来源、税及确认摘要不能由终端伪造 | `PricingCheckoutServiceTest#sharedInvoiceTaxRoundsOnceAndPreservesExactComponentQuantities`、`wrongConfirmationDigestAndExpiredQuoteNeverValidate`；`PricingConfigServiceTest`实际24项原生发布/初始化场景 |
| 原单绑定、原键重试及未知结果不伪造成功 | `DiningOrderServiceTest#stagesDurablyBeforeRpcAndBindsOriginalWholeQuantityTaxInclusiveAmountAndComponent`、`coreDocumentSurvivesFoodBindRollbackAndRestartReusesSameBatchAndOriginalOrder`、`timeoutKeepsUnknownOriginalIntentAndQuotaUntilOriginalDocumentRecovery` |
| 支付/库存/厨房状态分开；接单原子性 | `DiningCheckoutServiceTest#cashSuccessSettlesOnceButStockHeldCannotConsumeQuotaOrAdmitKitchen`、`outboxFailureRollsBackAcceptanceAndCanRetryWithoutAnotherPayment`、`lostPayResponseSurvivesRestartAndOriginalUnknownNeverCreatesAnotherLeg` |
| 原量、原金额与未发货边界 | `BasicPartialRefundPolicyTest#sequentialThirdsConsumeExactOriginalGrossWithoutLosingTail`、`partialRejectsAnyShippedOriginalEvenWhenSelectedLineIsUnshipped`、`frozenPartialVerificationRechecksFulfillmentButFirstFullShippedRemainsSupported`；`DiningReturnServiceTest#selectedMainQuantityUsesOriginalCoreAllocationAndCoreComputedMoney` |
| 超时恢复、取消及授权 | `DiningReturnServiceTest#unknownCancellationWaitsForOriginalStableCaseAndNeverTreatsAbsentLookupAsCancellationProof`；`DiningTransactionRecoveryServiceTest#poisonBackoffAllowsOtherQueuesAndQueriesOwnersAfterClaimCommit`；`SourceRefundMissingPreparationRecoveryFlowTest#merchantDecisionDefersSourcePreparationAndRealScanResumesMissingExecutionOutsideTransaction`明确注入提交后准备超时并由实际扫描恢复同一原单 |
| 零售FREE、原库存/收货兼容 | `BasicZeroRefundFlowTest`两项实际下单/退款，订单tradeNo必须等于原账本`FREE:<outTradeNo>`；`InventoryFlowTest`35项、`InventoryOriginalRestoreFlowTest`6项、`InventoryReservationTransportTest`3项，以及原零售退款/路由回归 |

集中运行证据（不把条件跳过当通过，不把重复执行简单累计为新用例）：

| 运行范围 | 实际结果 |
|---|---|
| 共享基础 | 289项通过，0失败/0跳过 |
| 核心完整回归 | 673项，583实际通过、90条件数据库跳过；另一次性MariaDB实际90项通过、0跳过（发布24/种子8/税18/报价31/商品9） |
| 独立PAY完整回归 | domain231、channel62、svc61，合计354项通过，0失败/0跳过；含真实本机HTTP契约 |
| 餐饮五模块完整回归 | 587项，537实际通过、50数据库条件跳过；原生MySQL另368项实际通过、0跳过，覆盖这些条件项；原规格权威另4项通过 |
| 宿主选定兼容范围 | 155项实际通过，0失败/0跳过；以各真实Surefire类报告最终版本汇总，前轮fail-fast跳过已逐一补跑 |
| 独立部署包 | package通过，`FoodMigrationPackagingIT`3项通过；实际嵌套core仅一份V8与原冻结迁移 |
| 设计与前端合同静态检查 | Node63、OpenAPI6通过；C/B498条中496匹配、2条旧缺失；Ops404中396匹配、8条旧缺失；界面清单292项最新；V1冻结检查通过 |
| 金额消融 | 只在临时验证产物将原前缀floor改为ceil，101/3退款断言按预期RED（33→34）；源码未改、原class精确恢复，再跑原5项策略测试GREEN |

守卫边界：`ArchitectureTest`16项仍7项旧失败，`SchemaDriftTest`2项仍1项旧失败。架构6条失败消息与原基线完全一致；剩余跨域规则仅3处既有`AfterSaleServiceImpl → CoreMarketingDocumentNumbers`调用者方法签名因显式买家/来源参数改变，被调类/方法及调用数量不变，没有新增跨域依赖。Schema差异列名token与原基线一致。未放宽守卫或扩大known清单；**不能声称全项目所有检查通过，也不据此合并/推送。**

验证位置与隔离：最初在原餐饮worktree完成生产/测试编译。全量test升级曾两次自动审批超时，随后在`/private/tmp/checkout-verification`创建无Git的纯验证副本；所有5296个Java/SQL/POM/配置源码逐文件SHA校验一致。回环HTTP与一次性数据库执行另获准，未使用经营库或真实支付凭据；临时MySQL/MariaDB已删除。主要日志为`/private/tmp/checkout-core-delivery-tests.log`、`checkout-unified-core-pay-final-tests.log`、`checkout-unified-food-final-tests.log`、`checkout-unified-food-native.log`、`checkout-unified-pricing-native.log`、`checkout-unified-host-remaining4-tests.log`、`checkout-delivery-guards.log`。真实渠道、厨房/打印硬件及营业验收仍未执行。

旧测试夹具修正说明：物流退款测试原来发布不存在的售后编号，现补真实同身份零额售后；两项结算测试原在业务事务内临时取号，现先通过真实PAY coordinator预留再进入原事务，保留原幂等/回滚断言。新增恢复测试现在明确注入首次提交后准备超时，既验证正常clean边界又验证实际原键扫描，未通过清理交互或禁用消费者制造绿色结果。

经营库、部署与范围：本批只新增迁移文件，**未执行经营库迁移、部署或远端推送**。Keeta保持规划，不注册真实渠道。原§2.3未完成项和C05/C06/C07/C09等完整场景仍未完成；下一批按原任务补真实收银/权益/履约合同，不把普通支付、配置计算或本次受控链路改名为全场景营业闭环。


本轮计划及共享归属确认已记录；后续已执行CKO-01源码合同核验、CKO-02共享值对象、CKO-03类型化配置编译、CKO-04业务规则校验、CKO-05多券候选编排、CKO-06公式与守恒、CKO-07纯地区税内核及CKO-08配置发布事务，09 A/B/C1及C2.1/2/3/5、10、11A/11B1/11B2A～E已执行；09-C2.4、11完整报价及12～31仍待完成。已增加4配置表迁移并在隔离数据库验证；未在既有业务库建表、导入种子、开放报价/支付或部署。后续逐任务回填：任务编号、起止基线、实际文件/迁移/API、测试证据、提交、偏差与仍关闭的能力。原后端计划与本清单一对多映射；本清单细化结账主线，不推翻桌台、菜品和厨房的原实现记录。

本轮计划检查：31个任务编号连续且唯一、各任务含依赖/文件/至少3个未勾选步骤、共95个执行步骤；11项需求映射及本地链接、代码围栏检查通过。未运行Java/数据库/支付/库存或打印验收。

执行进度（2026-10-22）：CKO-01完成源码合同/迁移基线核验，下一步CKO-02；外部环境全部仍待验证，不因此开放交易。

执行进度（2026-10-22，CKO-02）：共享定点金额/计价份额/事实及来源引用已实现，18个新增测试通过，shop-base全套96通过；架构16项中1项基线已存在失败保留。下一步CKO-03。

执行进度（2026-10-22，CKO-03）：行业模板与门店覆盖编译、20条强制规则目录及能力/冲突校验已实现；20个新增测试通过，shop-base全套116通过，独立评审问题已修复并复查。下一步CKO-04；尚无配置发布、运行期规则执行、报价或资金接线。架构检查详见收据，不宣称全部通过。

执行进度（2026-10-22，CKO-04）：B01～B12业务阶段校验及逐份额金额/审批/owner证据已实现；27项新增测试、全套shop-base 143项通过，B02和份额余额消融均确认失败后恢复，独立评审修复并复查。下一步CKO-05；税务、正式计算公式、报价/订单资金接线仍未开放。架构基线问题详见收据。

执行进度（2026-10-22，CKO-05）：多券子集/完整分配方案搜索、固定意图保护及不可变税前计划已实现；30项新增测试通过，独立评审及容量边界复核无重要问题。下一步CKO-06；实际公式、税务、报价和资金接线仍未开放，最终回归/消融/架构证据见收据。

执行进度（2026-10-22，CKO-06）：固定公式/逐份额分摊、原owner有序证据及独立守恒复核已实现，复用原CouponCalculation；新增29项引擎及4项零售兼容测试，全套shop-base 202、shop-core 414通过。三项保护消融后恢复，独立评审修复并复查。下一步CKO-07；税务、报价/订单/资产接线仍未开放。架构原有失败详见收据。

执行进度（2026-10-22，CKO-07）：纯地区税预估/最终定税、逐行分类/精确减免、固定付款上限及原税AA分配已实现；26项新增测试、8个JSON向量及3项消融通过，独立评审修复并复查。全套shop-base228、shop-core414通过。下一步CKO-08；税档发布/报价持久化及正式资金接线仍待实施，架构基线失败详见收据。

执行进度（2026-10-22，CKO-08）：4表及模板/门店内部Service、原子发布/幂等/outbox、锁版初始化、恢复继承、升级草稿和新版本回滚已完成。17项一次性MariaDB实际全绿，事务消融有效，core完整回归通过；架构与schema合计24项有2项已复现历史基线失败。下一步09，正式权限/真实来源与API仍未开放。


### CKO-10执行细化（2026-10-23，批次开发）

顺序裁决：C2.4需要真实结账owner协议，旧CouponPort单次allocate和PAY就绪布尔值不能证明prepare/commit/query/退款及新版组合能力；不新增伪造生产证据provider。CKO10原依赖06～08，可独立实施，不等待未接资金服务。采用executing-plans；遵从用户多阶段开发编译、统一测试要求。

§0 AC：Q01三表同事务、幂等及失败全回滚；Q02按MAIN/主体/门店/消费者范围读取；Q03金额/源/税/分摊不可变，变更创建新报价；Q04逐原发票行税总额/分摊守恒，积分降价和对价互斥；Q05来源事实、意图、经济摘要独立规范化，随机No和时间不进经济摘要；Q06过期/替代与支付分离，被原意图/订单引用的报价保留，无物理删除入口。

§1复用：ord_同库、BaseEntity、TradeMappers嵌套Mapper、IdempotencyService、TransactionTemplate、BizKey及TaxEngine。增量V550三表，不回写旧迁移。无新Controller/权限、无资产/支付/库存写调用。

§2阶段：A先实现三表/实体和闭合typed报价输入及纯快照装配器，编译；B实现保存/读取/替代/引用服务及明确租户/门店/消费者SQL谓词，编译；C添加一次性MariaDB原子性、隔离、不可变、并发幂等及行税守恒测试，统一核心/原生/宿主及生成物验证，最终批次评审。内部合同不得直接作为C端VO，提供方证据留后端；CKO11负责真实source owner、税点、优惠资格、敏感字段裁剪及HTTP鉴权。

代码：trade.dto.PricingQuoteCommands、trade.assembler.PricingQuoteAssembler、trade.entity.OrdPriceQuote/Line/Allocation、trade.service.PricingQuoteService及impl、TradeMappers三个嵌套Mapper、PricingQuotePersistenceTest。快照仅来源typed records，不接受任意JSON；当前冻结商家税模式与原发票行金额，使用TaxEngine校验逐发票行税合计；基础金额有符号调价之外均非负且JSON安全，资金计划不等于扣款。现金找零仅保存意图，不启用现金营业。

生命周期：保存READY，有效期1～300秒、服务器UTC毫秒；过期读取EXPIRED，替代需同主体/门店/消费者及源根，引用仅READY未过期时允许且按原reference幂等。已引用不可替代，无清理/物理删除入口。hash按字段名递归排序、数量去多余零；经济hash排除报价No/创建/过期时间但包含源身份/配置版本/税源/业务金额和计划，不能因同金额不同优惠/资方而相同。


### CKO-11A 餐饮权威价格事实（2026-10-23，执行细化）

沿现有需求及ORD01/CAT餐牌选配套餐方案，采用executing-plans；按用户多阶段开发编译后统一测试。本批独立完成后台价格事实层及本地/远程同合同，不新增公开报价HTTP、不放行未核验券/储值，不提前标CKO11全部完成。

§0 AC：F01从获准购物车读取，不收前端单价；F02当前餐牌/时段/渠道、选配与套餐冻结版本必须匹配；F03主品、小料/组合配料销售SKU、套餐INCLUDED/ADD_SKU/DIFF_BASE分开计价，不重复父子收入；F04源哈希包含版本/真实价格/依赖/选择，不含读取时钟和访问凭证；F05读购物车和目录/价格前后复核变更冲突，来源故障不变0；F06B门店主体与C原会话/买家权限复用CheckCartService；F07嵌入式及独立food提供同typed内部服务，原token过滤先于解析，远端必须校验来源作用域/版本/摘要和金额，暂不作为C端返回值。

§1复用：CheckCartService、CatalogService、CartLinePolicy、DishOfferQueryPort、SalesRestrictionService、StoreCardQueryPort、FoodAutoConfiguration/ServiceCalls/InternalHttp。当前DishOffer提供方仅LOCAL/CNY且没有历史价格revision；冻结真实值内容hash作为价格内容标识，不冒充历史目录版本。外送真实渠道/未知价、预约未来时刻、称重仍阻断；不因报价读取预占或写库存/券/资金。

§2批次：A闭合DiningPriceFacts DTO和可重用SourcePriceMath、DiningPriceFactsPolicy纯校验/组成投影，编译；B DiningPricingFactsService/Impl，复用现有购物车授权，当前目录及SKU提供方前后内容校验；在food-core装配、food-server token保护的两个typed内部路由和food-web Remote适配，编译；C纯政策、服务权限及变更、实际HTTP/token/严格输入、远端损坏响应校验与双装配统一回归、消融和最终评审。

金额：组件数量=父点餐数量×选择数量×每选项单位；组件金额按最小货币单位HALF_UP一次，主品/收费组件分别汇总；共享SourcePriceMath计算乘法和非负补差。DIFF_BASE=max(选项SKU价−同范围基准SKU价,0)，不自动给予负补差；INCLUDED为0且只记录组成。父点餐行及其配料/补差保留同一原发票行身份，供核心统一计税；不在本层拆分税行或计算优惠/税。

输出schema1冻结entity/store/cart/sourceVersion/subject、真实价格来源、pricedUnits与composition/dependencies、sourceHash；不含accessToken/伪造税率/资金资格。staff匿名散客subject可null；C匿名用户必须绑定原session业务No，不能用null跨会话。前后复核是乐观读取稳定性验证，不是跨服务事务或资产确认；CKO11B/14仍须在保存/下单前后重验，过期、已付AA和历史退款不得仅凭本事实执行。


#### 11A 合同与代码落点

前端仍不上传可信价格；本批只提供服务内部两条 POST 合同，沿用 `InternalHttp` 服务令牌过滤，不对公众开放。B/C 公开 quote API 属于 11B。

| 服务方法 / 内部路径 | 输入 | 输出 / 失败语义 |
|---|---|---|
| `staff` / `/internal/food/pricing-sources/cart` | `entityNo, storeNo, cartNo`（各≤32字符）、`expectedVersion`（必填整数，≥0且小于Long最大值） | `DiningPriceFacts`；门店主体及购物车范围复用原B业务校验；staff的subjectNo为null |
| `customer` / `/internal/food/pricing-sources/customer-cart` | `cartNo, expectedVersion, access{sessionNo?, accessToken?, buyerNo?}` | 复用原C购物车认证；subjectNo优先原授权会话，省略sessionNo则使用持久化会话；token不进入输出 |

返回 `schemaVersion=1, payload, sourceHash`。payload含主体、授权subject、已保存cart及version、菜单发布引用/依赖摘要、冻结SKU归属、当前Offer、限售与实际组件用量、无读取时钟的Composition投影、currency和amountMinor。Composition提供当前已选餐牌的菜单/选项/套餐定义，既用于本地组价，也供接收端逐项重建；不得把摘要当签名或资金资格证明。sourceHash使用稳定对象键顺序和规范化小数，数组保留业务顺序，不含asOf或访问凭证。2MB上限。

失败：400参数/原选配非法；403范围无权；404原购物车/会话不存在；409购物车或价格版本变动；503（业务码40190）真实来源不完整、币种/业态不支持或远端回执非法。对跨读取范围变化采取乐观冲突检查；读取结束后的变化仍须由11B/14在报价保存和最终下单时再次确认。

代码：共享 `common.pricing.SourcePriceMath/PricingSnapshotHash`；food-api中的DTO、`DiningPricingFactsService`、`PricingSourceInternalApi`与共享纯政策；food-core的`DiningPricingFactsServiceImpl`显式装配；food-server的`InternalPricingSourceController`；food-web的`RemoteDiningPricingFactsService`。`CartLinePolicy`仅移动到food-api供两端复用，包名与原校验逻辑保持一致；没有新增数据库表或Mapper。数量、金额的乘法和HALF_UP归共享地基，餐饮选配与套餐语义留在餐饮包。

审查处置：增加“原购物车仍含收费选择但回执删除组件”和“DIFF_BASE错误改为0，重算摘要”的校验；接收方必须重建完整组件集合、数量、价格、组件引用，而非仅验证现有金额相加。QR目录查询同步原购物车的session回退规则。新增装配开关/显式实现覆盖、严格JSON和真实HTTP回执测试。

下一步11B：将本价格事实适配到共享PricingFacts和原发票行映射，接入真实规则/税配置与已核验的券/资金owner能力，走统一纯引擎和CKO10不可变报价存储，再提供B/C报价、查询和复核接口。仍未核验的券/储值能力保持BLOCKED；CKO11整体、普通支付与下单闭环尚未完成。


#### 11A 本批验证结果

- 分阶段编译及餐饮全模块clean后统一测试：549项，503项实际通过、46项数据库条件跳过；随后临时MySQL回归将对应数据库场景实际执行，餐饮19项主库＋290项业务守卫＋9项独立服务/内部接口＝318项通过且无跳过，另原商品属性提供方4项通过。临时容器已删除；未连接业务数据库。
- 共享shop-base完整265项通过。宿主food-embedded装配6项（5通过、1仅standalone执行而跳过）、food-standalone装配6项全部通过；新服务的本地/远程类路径隔离与显式实现覆盖已验证。
- 宿主选定守卫40项：31通过、1条件跳过、8失败。Architecture的7条失败消息与既有基线逐字相同；SchemaDrift的1条缺列集合与基线相同，仅输出顺序不同；不将宿主全套宣称为通过。
- Node交付/定价生成9项、Python定价合同4项及交付4项通过；SQL与Ops出口守卫无新增欠账，暂存diff空白检查通过。价格漂移消融测试在移除复核后按预期失败，恢复后通过。独立审查的两项问题均已复现并修复。
- 本批修复原生独立服务测试的过期固定日期：原先写死2026-10-22的“未来策略”在当前日期已生效，改为当前时间＋2日、毫秒精度；保留幂等键内容变更冲突验证，不改业务策略逻辑。

本批只做本地统一分支提交；无新表/业务迁移、公开报价接口、资金能力放行、部署或远端推送。后续任务总览继续保留11B及12～31待办。

### CKO-11B1 核心价格来源接线（2026-10-23）

§0 范围：依据CKO-11及ORD01的权威来源与原发票行约束，将11A价格事实通过共享SPI提供给核心。现有GoodsCatalog事实尚不包含优惠资格/商品税分类；C2.4也没有完整生产资金能力提供方。本批不把这些缺失值解释为可优惠或免税，完整B/C quote接口仍属于后续11B。

验收：S01核心不依赖food包，按sourceSystem唯一注册提供方，缺失/重复提供方阻断；S02餐饮适配复用11A的B/C购物车授权和严格来源校验；S03共享PricingFacts保留原价/数量/版本，INCLUDED组成保留为零、仅不进入收费单位，不重复套餐收入；S04每个原点餐行的全部收费组件属于同一原发票行，完整basis必须守恒，不能按配料重新创造发票行；S05来源与共享投影分别保留摘要，主体/门店/会话/版本及单位集合均校验；S06确认重新鉴权和读源，再对比摘要，失效/变化冲突，不凭旧摘要恢复权限；S07嵌入式及独立调用共用同一适配，令牌不进入摘要/事实；S08不写订单/库存/资金，不生成最终报价或推断税分类/优惠资格。

§1 合同：新增`PricingSourceFactsPort`及typed Scope/Credentials/Snapshot/Component/InvoiceBasis；共享策略验证单币种、DECIMAL(20,6)、金额乘法、组件唯一性和完整原发票行basis。`PricingSourceService`负责唯一owner路由、结果范围验证及read/confirm。餐饮`FoodPricingSourceFactsPortImpl`位于food-web，依赖`DiningPricingFactsService`（embedded本地、standalone远程），由既有FoodWebAutoConfiguration显式装配；纯映射留在food-api，不引入core实现依赖。

§2 执行：A共享SPI与校验、核心Service/Impl，公共模块编译；B餐饮纯映射与适配及双形态装配，餐饮编译；C统一执行金额/发票行/权限/版本/摘要损坏/owner路由与双形态回归、消融、最终独立审查及本地提交。遵从用户先开发编译、多阶段后统一测试的节奏。

§5 待记录真实命令与结果。无新表、Mapper、公开端点或权限码。仅支持MAIN、FOOD/CART、完整OPEN购物车；AA/已付/部分范围/历史退款依然需要后续独立合同。


11B1清单口径：共享来源SPI的实现由food-web行业插件提供。分层清单生成器原来只在backend查找实现，会将它误报为无实现；已让实现查找同时扫描backend-plugins（排除test/target），模块/域的数量仍保持backend原统计面，不增加虚假的默认provider或人工忽略项。


#### 11B1 交付合同与验证

`PricingSourceFactsPort`是backend-only Java SPI，不是HTTP请求或授权对象。`Scope(tenantNo,entityNo,storeNo)`必须来自已解析的服务端B权限上下文；构造Scope不授予权限。C端凭证`Credentials(sessionNo?,accessToken?,buyerNo?)`每次交给原餐饮服务鉴权，toString脱敏，不进入任何快照。当前仅MAIN、FOOD/CART、完整购物车，scopeNo/scopeVersion必须为空。

| 方法 | 输入 | 输出/结果 |
|---|---|---|
| `PricingSourceService.staff` | Scope、PricingSourceRef | 唯一来源owner重新读取并鉴权，返回共享Snapshot；staff的subjectNo为null |
| `customer` | PricingSourceRef、Credentials | 返回原授权会话绑定的Snapshot，未提供session时沿原购物车会话回退 |
| `confirmStaff` / `confirmCustomer` | 原读取参数、expectedSnapshotHash | 重新鉴权、重新读取来源后比较共享摘要；变化409，原权限/会话错误保持原业务失败 |

Snapshot：`schemaVersion=1, scope, subjectNo, source, channel, prices, composition, invoiceBases, snapshotHash`。prices为共享PricingFacts（currency、原sourceSnapshotHash、sourceVersion及完整收费units）；composition保留全部组成，其中BUNDLE_INCLUDED必须为0且不进入收费units。invoiceBases逐原发票行列出完整portions与amount。金额、币种、正数量、无重叠/重复、基准总额、版本与作用域全部复核；共享snapshotHash绑定投影内容，和原餐饮sourceSnapshotHash各自保留。两种摘要都不代替授权、优惠资格、合法税分类或资金能力。

失败：参数非法400；无唯一提供方、来源类型未支持、回执非法40190；陈旧摘要409；来源权限/会话/购物车冲突按原异常传播。无owner不回退另一个提供方，也不生成零价事实。该服务不缓存权限，不保存报价/订单，不触达支付、券、储值或库存写接口。

| AC | 验证落点 |
|---|---|
| S01 | PricingSourceServiceTest.unknownOrDuplicateOwnersNeverFallBack、retailSpringAssemblyBootsWithoutAnyIndustryOwner |
| S02/S06 | routesStaffScopeAndFreshlyReauthorizesOnEveryConfirmation、customerConfirmationPreservesOriginalSessionAndToken、FoodPricingSourceFactsPortTest.originalAuthorizationFailureIsPreservedWithoutProjectionFallback |
| S03 | DiningPriceSourcePolicyTest的配料/组合配料及三类套餐场景；PricingSourceFactsTest.preservesZeroIncludedCompositionAndOnlyChargesMainAndAdditions |
| S04 | PricingSourceFactsTest.originalInvoiceRoundsTaxOnceAcrossMainAndAddon；餐饮原发票行映射消融 |
| S05 | PricingSourceFactsTest的金额、重复组件、basis篡改、范围/会话/版本/摘要测试；PricingSourceServiceTest.malformedScopeAndVersionReceiptsFailClosed |
| S07 | FoodPricingSourceFactsPortTest双部署装配、RemoteDiningPricingFactsTest实际HTTP经共享适配、FoodEmbeddedWiringTest宿主双类路径 |
| S08 | 独立审查确认无新增资金/库存/订单写依赖或默认资格；未新增表、Mapper或公开接口 |

代码落点：shop-base新增`spi.trade.PricingSourceFactsPort/PricingSourceFacts`；shop-core新增`trade.service.PricingSourceService`及impl；food-api新增`policy.DiningPricingSourcePolicy`；food-web新增`port.FoodPricingSourceFactsPortImpl`及显式装配；既有原生11A内部HTTP不改合同。对应测试、宿主类路径守卫及生成清单已同步。本批是计划CKO-11的来源适配部分，`OrderPricingService/OrderPricingPort`与B/C公开quote仍待真实准入合同闭合后实现。

验证：分阶段核心/餐饮编译成功；共享基础272项通过；核心551项中495实际通过、56原数据库条件跳过（完整核心回归后额外核验无行业owner的Spring装配）；餐饮555项中509实际通过、46原数据库条件跳过。本批无数据库逻辑变更，未重复原生数据库批次，也不将条件跳过计为通过。宿主embedded装配6项中5通过/1仅standalone执行而跳过，standalone6项全部通过；新增来源测试在最终宿主reactor再次实际通过。宿主选定40项31通过/1条件跳过/8失败，Architecture7条消息与基线逐字相同，SchemaDrift1条缺列集合相同，无新增失败。

Node交付/定价生成9项、Python配置合同4项及UI交付合同4项通过；SQL/Ops出口守卫无新增欠账；分层/词典/索引/餐饮清单均由生成器生成。消融将餐饮taxInvoiceLineRef改成componentRef，原发票行测试由1行变2行按预期失败，字节恢复后完整餐饮回归成功。最终独立审查无待修或minor项。

下一步：11B2闭合实际优惠资格/活动事实、券与资金owner能力及有效配置读取，再接统一规则/公式/税计算与CKO10报价存储，提供B/C报价/读取/复核。CKO11整体保持未完成；无新金融能力放行或远端推送。

### CKO-11B2A 当前规则与报价上下文准入（2026-10-23）

§0 本批闭合11B2中的有效配置读取/准入及与11B1事实、既有商家税配置的绑定。旧活动/券合同仍不能证明餐饮多券、储值组合的完整执行能力，不新增生产假证据提供方。R01只读取同MAIN/主体/门店/行业唯一ACTIVE头指向的不可变schema3版本；R02从原模板冻结定义＋原覆盖重新编译，校验有效定义、语义hash、引擎与规则目录版本；R03真实owner证据必须唯一、同范围、稳定引用、非空来源、有效且≤5分钟，无证据/能力不足阻断；R04读取期间配置头变化冲突，草稿变化不改现行规则；R05报价准备从原source鉴权读取，绑定规则、能力、商家税配置，前后复核变化冲突；R06确认重新完成鉴权/来源/规则/税读取，旧摘要不续权；R07上下文仅后端使用，不是报价、不计算优惠、不扣资产/库存，无新表/公开接口。

§1 核心新增PricingRuntimeConfigService/Impl与纯PricingRuntimeConfigPolicy；复用ord_store_pricing_config/revision及ord_pricing_template_revision Mapper。PricingPreparationService/Impl组合PricingSourceService、PricingRuntimeConfigService、PricingTaxSettingPort，按来源system对应FOOD/RETAIL/BEAUTY_SERVICE行业，不收客户端行业/税率/能力。返回只读Prepared，保留原Source与投影hash、规则快照、真实owner证据和MerchantTaxSnapshot；不推断商品优惠资格/税分类。core保留跨行业共享能力，food不复制规则。

§2 A实现当前配置准入、编译；B组合source/config/tax与复核、编译；C统一做纯校验、来源重新鉴权、税/规则/owner变化以及真实MariaDB范围/版本读取测试、消融、完整核心和宿主守卫、一次独立评审及本地提交。遵从用户分阶段开发编译后统一测试。

§3 后端 Java 合同（非HTTP，客户端不能直接构造已授权Scope）：

| 类 / 方法 | 输入 | 输出 / 校验 |
|---|---|---|
| PricingRuntimeConfigService.resolve | authorizedScope(tenantNo,entityNo,storeNo)、industry | RuntimeConfig：scope/industry、configNo/revisionNo/revision、templateRevisionNo、engineSpecVersion/ruleCatalogVersion、EffectivePricingConfig、真实Evidence、snapshotHash |
| PricingRuntimeConfigPolicy.verify | 冻结门店revision、原模板revision、真实PricingCapabilities | 重编译EffectivePricingConfig；严格拒绝重复JSON字段，规范化对象键/排版后核验原语义hash，内容变化仍拒绝 |
| PricingPreparationService.staff | 服务端已授权Scope、PricingSourceRef | Prepared：完整授权Source、RuntimeConfig、MerchantTaxSnapshot、expiresAt、preparationHash |
| customer | PricingSourceRef、Credentials | 原来源owner每次执行C端鉴权；Prepared不含凭证 |
| confirmStaff / confirmCustomer | 原读取参数、expectedPreparationHash | 完整重新读取后比较，不使用旧摘要恢复已撤销的权限 |

Evidence必须有稳定reference/expiry与来源版本；同一owner身份出现不同版本也拒绝。源引用集合与旧schema2税档集合排序后入摘要，排列变化不造成假冲突。Prepared摘要绑定source.snapshotHash、规则快照（含能力/证据）、完整商家税快照与到期时间；税快照包含managedVersion，税率不变但继承/覆盖版本变化也冲突。读取时间不参与摘要。上下文没有优惠金额/税计算/最终应收价，也不授予下单权限；后续落单必须再次复核。

失败：输入非法400；来源原授权失败原样传播；无唯一当前配置/唯一真实owner、schema/范围/版本目录不兼容、内容篡改、证据过期或能力下降40190；前后版本或预期摘要不一致409。禁止在已有数据库事务内调用，避免持锁访问跨owner。只读乐观复核不能代替未来落单时的最终校验。

§4 验收映射：R01/R04→PricingConfigServiceTest新增三项真实MariaDB测试（草稿隔离、门店隔离/有效hash篡改、owner读取期间活动指针切换）；R02/R03→PricingRuntimeConfigServiceTest（冻结输入重编译、schema/目录版本、证据缺失/多实现/异范围/过期/冲突版本、能力下降、语义排版稳定）；R05/R06→PricingPreparationServiceTest（来源/规则/税变更、managedVersion、旧摘要不续权、C端凭证透传、拒绝前不读取税配置）；R07→独立只读审查及宿主装配/架构守卫。

§5 验证结果：两阶段开发编译成功；共享基础272项实际通过；核心572项中513实际通过、59原生数据库条件跳过，无失败。临时MariaDB统一59项实际通过、0跳过（配置发布23、种子8、商家税18、报价存储10），运行后确认只删除本次临时容器，不连接业务数据库。18项新增纯/服务测试覆盖上述准入和准备上下文；新增3项真实数据库测试归入配置发布23项。

宿主选定40项：31通过、1条件跳过、8既有失败；Architecture7项失败消息与基线逐字一致，SchemaDrift1项缺列集合与基线一致，无新增失败。Node交付/定价生成9项、Python配置合同4项及UI交付合同4项通过；SQL/Ops守卫无新增欠账，分层/词典/索引生成完成。

消融：临时删除effectiveHash比较，篡改摘要测试按预期失败；字节恢复后完整核心回归通过。JSON等价排版测试先失败、规范化校验后通过。最终一次独立审查发现owner回调继承数据权限绕过：即便尚无生产provider，也作为共享基础设施边界问题处理；新增回归先失败，随后将绕过收窄到三个显式限定范围的Mapper查询，完整核心/原生数据库再次通过。无未解决审查项。

本批不新增表/迁移/公开API，也不实现真实资金能力。C2.4实际生产owner能力、优惠资格/活动事实与完整11B quote仍待后续合同闭合；Prepared仅供后端准备，不能作为资金、下单或税分类授权。下一步先闭合真实owner及活动资格合同，再组合统一规则/公式/税计算、CKO10快照存储和B/C报价接口。


### CKO-11B2B 商品优惠参与与税分类（2026-10-23）

§0 依据[餐饮完整需求§5.1](../requirements/餐饮系统-完整需求文档.md#51-菜单与报价ab组)：11B2需要真实商品资格与税分类，既有prd_goods/prd_sku及GoodsCatalogQueryPort只证明归属，不含这两个事实。依据本文CKO11、11B1-S08及用户“先排除不参与活动商品”“商家自主管理税配置”的要求补齐显式配置。P01同MAIN/主体/门店的实际CN SKU才能配置；P02未配置不默认可优惠/标准税/免税；P03商家指定promotionEligible及明确TaxClass（STANDARD/ZERO_RATED/EXEMPT/OUT_OF_SCOPE），税率/含税方式仍来自原税设置；P04读写按原B权限/门店上下文、版本并发及原键重放，归属变更阻断；P05内部批量查询严格覆盖请求SKU、返回真实版本/稳定摘要；P06报价准备绑定当前商品策略，前后复核及确认重读，变化409；P07不改变商品基础价格/库存/销售，不把配置当法定税务认证或活动命中证明。

§1 存储：V551新增prd_sku_pricing_policy，沿BaseEntity，policy_no稳定业务键；tenant_no/entity_no/store_no/goods_no/sku_no、promotion_eligible、tax_class、version及审计字段。唯一(tenant_no,entity_no,store_no,sku_no)，不复制价格。缺行即未配置；不做批量默认回填，不执行业务库迁移。稀疏扩展表适用于餐饮主菜、小料与套餐收费组件的实际SKU，其他行业复用。

共享商品域新增PrdSkuPricingPolicy、ProductMappers.SkuPricingPolicyMapper、dto.GoodsPricingPolicyCommands、GoodsPricingPolicyService/Impl、GoodsPricingPolicyAccessPolicy、BizGoodsPricingPolicyController；shop-base新增spi.product.GoodsPricingPolicyPort与严格快照校验工具，商品域port实现。复用GoodsMapper/SkuMapper、IdempotencyService及原transactionManager。规则/税运算不复制到商品域。

§2 A完成扩展表、商品服务和严格只读Port后编译；B完成B接口、OpenAPI及后端先行清单、报价准备策略绑定后编译；C统一验证真实数据库创建/更新/幂等/隔离/并发与源归属、严格快照及准备流程版本变化，核心/宿主/合同守卫、消融、一次独立评审后本地提交。沿用户编译优先节奏。

接口GET/PUT /biz/goods/{goodsNo}/skus/{skuNo}/pricing-policy，复用STOCK权限及真实当前门店/主体，写入另要求商家老板身份；请求不能提交身份。PUT输入expectedVersion(0仅首次)、promotionEligible(必填Boolean)、taxClass(明确枚举)、Idempotency-Key；返回policyNo/goodsNo/skuNo/version/配置内容，缺配置GET404。创建version=1，更新CAS+1，旧版本409。每次入口重新验证用户/门店授权，幂等键只重放原作用域/用户/请求。

GoodsPricingPolicyPort.resolve(Scope,List<SkuRef(goodsNo,skuNo)>)只读，限制1..512个不同SKU，缺项/非法/不支持税类阻断40190；返回同scope、每SKU的policyNo/version/promotionEligible/taxClass与snapshotHash，不含价格、权限凭证。配置由真实商品表校验归属；摘要排序后稳定，不是授权。

§5 验收与真实执行结果：

| AC | 测试 / 检查 |
|---|---|
| P01/P04 | GoodsPricingPolicyPersistenceTest.foreignStoreGoodsSkuAndMarketCannotReadOrWrite、currentOwnershipIsRequiredEvenWhenAnOldPolicyExists、idempotentReplayStillChecksCurrentProductOwnership；GoodsPricingPolicyApiTest四项真实method-security/请求测试 |
| P02/P03 | absentPolicyNeverReturnsACompatibleDefault、invalidTaxAndFailedCreateCannotLeaveAReceiptOrImplicitDefault；GoodsPricingPolicyFactsTest四项严格事实测试；严格请求/JSON Schema测试 |
| P04 | createsUpdatesAndReplaysOriginalResponseWithoutChangingPriceTables、changedRequestAndOldVersionCannotReplaceCurrentPolicy、concurrentEditsCommitOnePolicyAndOneSuccessfulReceipt |
| P05 | completeBatchAndStableOrderingAreRequired、exactScopeAndCompleteGoodsSkuSetAreRequiredEvenWithAValidDigest、duplicateSkuConflictingOwnersAndUnsafeIdentitiesAreRejected |
| P06 | PricingPreparationServiceTest.missingOrForeignCatalogPoliciesNeverDefaultToPromotionOrZeroTax、catalogVersionChangeConflictsEvenWithUnchangedEligibilityAndTaxClass；原8项准备测试继续通过 |
| P07 | 一次独立只读审查；宿主结构/权限/装配守卫与既有基线比较；无价格/库存写入依赖 |

两阶段开发编译成功；共享基础276项全部实际通过，完整核心587项中519实际通过、68原生数据库条件跳过，无失败。临时MariaDB统一68项实际通过、0跳过（原配置23/种子8/税设置18/报价存储10＋商品策略9）；运行后确认删除仅本次临时容器。新策略用独立_product临时schema，不改已有套件夹具或连接业务库。

独立审查1项Important：原幂等回执重放跳过商品归属检查。新增真实DB测试先失败（预期异常未抛），修正普通重放和锁冲突恢复路径的归属复核后，68项原生与完整核心回归通过；原策略普通更新后仍重放原响应。无待修或deferred minor。消融去掉商品策略snapshotHash比较，篡改摘要测试按预期失败；字节恢复后完整核心通过。

宿主选定40项31通过/1条件跳过/8既有失败，Architecture7项消息逐字等于原基线，SchemaDrift1项缺列集合一致。Node9项、Python OpenAPI合同5项及交付合同4项通过；正向B/C契约无新增缺口、SQL/Ops守卫无新增欠账。本批14个定价B端后端先行登记均有效，含新增2个商品策略接口。全局B/C孤儿守卫仍因既有权益清单c-app GET /mp/stored-value/stores已接入却仍登记而失败，使用HEAD原权益清单可重现；本批不修改其他业务清单或抑制该失败。

§6 实现对账：

| 模块 / 包 | 类 / 产物 | 需求落点 |
|---|---|---|
| shop-base / ai.neargo.shop.spi.product | GoodsPricingPolicyPort、GoodsPricingPolicyFacts；common.BizKey新增SPP常量 | P02/P03/P05 |
| shop-core / ai.neargo.shop.product.entity、mapper | PrdSkuPricingPolicy、ProductMappers.SkuPricingPolicyMapper | P01/P04 |
| shop-core / ai.neargo.shop.product.dto、service、service.impl | GoodsPricingPolicyCommands、GoodsPricingPolicyService/Impl | P01～P05 |
| shop-core / ai.neargo.shop.product.policy、api.biz、port | GoodsPricingPolicyAccessPolicy、BizGoodsPricingPolicyController、GoodsPricingPolicyPortImpl | P04/P05/P07 |
| shop-core / ai.neargo.shop.trade.dto、service.impl | PricingPreparation与PricingPreparationServiceImpl，摘要V2绑定商品策略 | P06 |
| shop-app | V551迁移文件；生成schema-test.sql，不执行业务库迁移 | P01/P04 |
| 文档 / 守卫 | 既有OpenAPI/B端后端先行清单、完整需求§5.1与本TDD、生成分层/词典/常量清单、原生runner扩充product及全量选择 | 三处对账 |

完整需求§5.1原平台审核口径与已确认的商家管理要求冲突，已同步修正。本表是SKU业务元数据扩展，未放入平台动态参数，也不修改原商品价格。策略只定义商品可参与性，实际活动命中、多券和资金owner合同仍按原计划实现，不以本表推断。V551需按正常迁移部署后使用；本批无部署或远端推送。


### CKO-11B2C PAY定价资金能力合同（2026-10-23）

§0 09-C2.4/CKO11仍缺真实资金能力，现有Status就绪位不能证明来源、币种、数量及组合限制。核验EntitlementUseServiceImpl.quote明确至多1张券，Core EntitlementUseSourcePort支持既有ORDER_USE来源；StoredValueRedemptionServiceImpl明确CORE/SERVICE_USE、CNY、全额适用，无部分储值+其他资金组合。此批将已核验的保守子集做成只读合同，不能凭开关把FOOD/CART转换成CORE/ORDER_USE。

F01复用EntitlementCapabilityPort/StoredValueCapabilityPort新增默认失败的pricing方法，旧Status不变；F02PAY声明显式family/源system-type/币种/协议及精确PricingCapabilities，非SUPPORTED时capabilities必须null，不把能力缺失编码成零值；F03当前仅单券CORE/ORDER_USE/CNY与全额储值CORE/SERVICE_USE/CNY；无跨券叠加或券储值组合，无余额/资格/预占保证；F04运行开关与唯一实际执行Port均须就绪，缺失/多实例/仅Primary均不能放行；F05本地与独立PAY沿原typed客户端/令牌/错误映射，严格验证原scope、family、协议和hash；F06core检查/确认每次重新调用唯一owner，拒绝事务内跨owner读取；旧摘要不续能力；F07不得生成生产PricingConfigEvidencePort，也不据此宣称餐饮资金路径或全CKO11完成。

§1 shop-base新增spi.trade.PricingFundingCapabilities（闭合Scope/Family/Status/Snapshot及规范化严格校验）；scope=(tenantNo,entityNo,storeNo,sourceSystem,sourceType,currency)，family=ORDER_COUPON/STORED_VALUE。schemaVersion=1，协议ORDER_COUPON_SINGLE_V1、SERVICE_STORED_FULL_V1。Snapshot包含family/scope/status/protocol/capabilities/snapshotHash；语义摘要无读取时钟，同请求及运行能力不变时稳定。快照不是授权、余额、税/活动资格或可直接用于发布的限时证据。

PAY原两个Capability实现新增pricing，复用原EntitlementUsePort/StoredValueUseQuotePort实际装配验证；policy.FundingCapabilityPolicy只描述现有边界，不扩充资金执行。既有embedded/standalone组成根显式传ObjectProvider，旧单参数构造只保持原status用途，不能凭它获取SUPPORTED新回执。

新增内部POST /internal/pay/entitlement/capabilities/pricing、/internal/pay/stored-value/capabilities/pricing，原PayInternalApi/Paths及两个Endpoint/Remote适配扩充；请求仅Scope，无客户端金额/能力。原内部令牌校验。core新增trade.service.PricingFundingCapabilityService/Impl.inspect/require/confirm，按family唯一owner路由，缺/歧义/非法回执40190；require只接受SUPPORTED；confirm重新加载后比摘要，变化409；unsupported保留明确状态而非伪造零能力。

§2 A共享合同、PAY纯策略/真实owner装配及core检查服务后编译；B typed HTTP/remote双部署接线后编译；C统一验证开关/实际Port/来源币种/数量组合、严格回执、真实HTTP及令牌、核心重新读取、部署路由、消融和既有守卫，一次独立审查后本地提交。不接业务数据库，不改资金表/金额操作或公开B/C接口。

§3 方法与结果：

| 方法 | 输入 | 输出与约束 |
|---|---|---|
| EntitlementCapabilityPort.pricing | 服务端Scope | ORDER_COUPON Snapshot；不调用余额、用券或预占 |
| StoredValueCapabilityPort.pricing | 同上 | STORED_VALUE Snapshot；full-service协议只声明全额适用，不证明余额充足 |
| 内部两个POST | PayCapabilityRequests.Pricing(scope)、原内部令牌头 | 同一个Snapshot，保持原内部裸响应及HTTP错误映射 |
| PricingFundingCapabilityService.inspect | Family、服务端Scope | 唯一owner的严格Snapshot；SUPPORTED之外仍保留状态且capabilities=null |
| require | 同上 | 只接受SUPPORTED，否则40190 |
| confirm | Family、Scope、expectedSnapshotHash(64位小写hex) | 重新读取owner后检查，变化409；相同但未支持仍40190；不是资金确认 |

状态为SUPPORTED、TENANT_UNSUPPORTED、SOURCE_UNSUPPORTED、CURRENCY_UNSUPPORTED、DISABLED、OWNER_UNAVAILABLE。supported严格绑定schema1、已登记协议/来源/CNY及原精确限制；不能从旧Status合成此回执。snapshotHash仅防错配/变化，不是签名或执行授权，也没有可直接替代原发布证据的有效期。主体/门店在此是能力查询范围，实际身份、来源归属、账户与资产适用性仍须各自owner重新验证。

§5 验收：

| AC | 测试 / 验证 |
|---|---|
| F01/F02 | PricingFundingCapabilitiesTest四项（稳定摘要、拒绝携带零/最大能力的非支持回执、拒绝扩大来源/币种/叠加、拒绝错配/篡改）；PricingFundingCapabilityServiceTest.missingAmbiguousAndOldBooleanOnlyOwnersFailClosed |
| F03/F04 | FundingCapabilityTest五项，真实执行Port存在且唯一才支持，旧构造/旧就绪位不能放行，禁用后不保留能力，未知来源/币种拒绝；部署路由测试真实组成根同时验证两类回执 |
| F05 | PricingFundingCapabilityHttpTest三项真实Tomcat/typed客户端，比较本地策略与HTTP回执、FOOD/CART双端明确拒绝、错误令牌先于owner调用被拒绝；PricingFundingRemoteTest两项错scope/family/hash拒绝 |
| F06 | PricingFundingCapabilityServiceTest其余四项，确认重新读取及撤销冲突、错配回执、拒绝把非支持转成零值、事务内不调用owner |
| F07 | 一次最终只读审查；未新增生产PricingConfigEvidencePort、资金写入/表/迁移或B/C公开接口 |

两阶段编译成功；共享基础280项全部通过，核心592项中524实际通过/68原生数据库条件跳过，无失败；PAY域212项全部通过。最终真实HTTP3、远程回执2、双部署路由5项实际通过。无数据库行为变更，本批不重复原生数据库批次，也不将条件跳过算作通过。

路由旧夹具首次失败在HEAD已有的Autowired依赖：EntitlementUseWriter的PayRightsDocumentNumbers及其payDocumentNumbers查询、StoredValueRechargeAppServiceImpl的StoredValueCustomerBindingPort；一次补齐这些测试mock依赖后5项路由验证通过，没有修改这些服务的业务逻辑。摘要消融移除validate中的hash比较，篡改回执测试按预期失败；字节恢复后完整基础/核心/PAY域通过。

宿主47项（原40＋路由5＋远程2）38通过/1跳过/8原有失败：Architecture7项消息与原基线逐字一致，SchemaDrift1项缺列集合一致。Node9、Python定价合同5及交付合同4、SQL/Ops守卫通过；B/C正向契约无新增缺口。生成分层/词典/索引清单同步。

最终独立审查无Critical/Important；保留1项非阻塞测试建议：显式@Primary多实现及重复StoredValueUseQuotePort专项回归。当前orderedStream().limit(2)正确按数量拒绝，普通重复实现已有覆盖，本批按执行流程登记后续补充，不宣称专项已测。

§6 实现清单与边界：

| 模块 / 包 | 类 / 产物 |
|---|---|
| shop-base / ai.neargo.shop.spi.trade | PricingFundingCapabilities及其Scope/Family/Status/Snapshot |
| shop-base / ai.neargo.shop.spi.entitlement、spi.storedvalue | 原EntitlementCapabilityPort、StoredValueCapabilityPort新增默认失败方法 |
| pay-domain / ai.neargo.shop.pay.entitlement.policy、port / storedvalue.port | FundingCapabilityPolicy及原两个CapabilityPortImpl |
| pay-domain / ai.neargo.shop.pay.client | 原PayCapabilityRequests、PayInternalApi、PayInternalPaths |
| pay-svc / ai.neargo.shop.pay.svc | 原两个CapabilityEndpoint及EntitlementPayBootstrap，新增两个内部POST |
| shop-app / ai.neargo.shop.payclient、port | 原EntitlementRuntimeRouting及两个RemoteCapabilityPort |
| shop-core / ai.neargo.shop.trade.service、service.impl | PricingFundingCapabilityService及Impl |
| 测试与生成物 | 四个模块内新增协议/owner/核心/HTTP/remote测试与旧部署夹具；生成分层/词典/常量清单 |

此批补足“owner能准确说明什么”，没有扩大实际资金执行范围。C2.4、FOOD资金执行、多券、活动命中事实和完整11B报价保持未完成；下一步需补餐饮真实结账来源与决策协议，随后才能按已证明的能力开放报价/执行。不得将餐饮购物车改写为旧CORE来源绕过限制。本批无业务库迁移、部署或远端推送。


### CKO-11B2D 资金报价来源日志与取消真相（2026-10-23）

档位1，沿用既有结算方案；本批不启用FOOD资产执行或生产发布证据。新增共享ord_pricing_funding_source（V552），属于core报价生命周期，不复制餐饮订单或PAY资产表。实际身份为CORE/PRICING_QUOTE_USE，经济源修订固定1；生命周期version另行递增。

内部PricingFundingSourceService.stage输入服务端Context、凭证、quoteNo、expectedPreparationHash；重新执行customer准备，验证真实FOOD/CART、H5、无session且buyer等于报价subject。首版只接受无优惠、无人工调整、无资金计划的完整MAIN整数数量报价；重新构造报价校验商品可参与性、税类、现行规则/税及完整经济摘要。保存原quoteNo/economicsHash/preparationHash、主体/门店/付款人、币种/原报价应收及短有效期，原报价retain同事务；一报价一来源保证幂等，不以调用方金额生成来源。

get按租户/主体/门店/付款人读取；cancel输入来源号及原因，同事务将来源置CANCELLED并经原EntitlementSourceDecisionService写USE_CANCEL，固定源revision1。query读取真实日志：不存在/错误revision为UNKNOWN，PENDING即使超时仍未决；取消返回原持久决策。超时绝不推断取消，也不复活来源。该金额是原报价应收记录，不是可用券基数或扣储值金额。没有EntitlementUseSourcePort适配、资产授权或提交方法，原PAY能力合同继续拒绝该来源。后续正式结账需建立完整权益基数、付款人授权及提交协议。

顺序：来源策略/服务/迁移编译；原决策查询接线编译；统一单元和原生事务回归、一次独立审查及既有守卫。本批不执行任何业务库迁移、部署或远端推送。


本批内部方法（不直接绑定HTTP请求）：

| 方法 | 输入 | 输出 / 错误 |
|---|---|---|
| stage | Context(tenant/entity/store/subject/actor)、Credentials、quoteNo、expectedPreparationHash | View(sourceRef/quoteNo/status/expiresAt/snapshotHash)；相同报价及准备摘要返回同一记录，包括已取消记录，不复活；过期准备不能重放stage，可用get查原记录 |
| get | 同一可信Context、fundingSourceNo | 同一View；过期仍PENDING，需读取expiresAt判断是否可继续；不会自动改变状态 |
| cancel | 同一可信Context、fundingSourceNo、1–128字符非空白原因 | 同一View；同原因重放返回原取消，换原因409；已过期仍可显式取消以供恢复 |
| 原SourceDecisionPort.query | 内部EntitlementContext、完整SourceRef | 精确revision1、无line/unit子身份；存在日志为PENDING，原持久USE_CANCEL为CANCELLED；错租户/未知源/错误修订为UNKNOWN。取消与查询竞争时有界重读一次持久决策，仍无决策则409，不猜测取消 |
| pendingDecision | 内部Context、SourceRef，由原reader在找不到决策时调用 | 仅未决回执；终态日志不独立合成终态，必须回到原reader查实际决策 |

stage参数非法或不支持的组合400，非本人/非门店报价与来源404，状态/摘要变化409；真实准备链路尚无生产证据时保留原40190，不伪造支持。所有来源查询先显式租户和业务范围过滤，再局部忽略DataScope；对外部owner的重新授权在数据库事务外完成。短事务只锁原报价或实际来源行。

表字段：funding_source_no/quote_no/entity_no/store_no/user_no各VARCHAR(32)，currency CHAR(3)，amount_minor BIGINT(原报价应收)，economics_hash/preparation_hash/snapshot_hash各CHAR(64)，status VARCHAR(16)，expires_at DATETIME(3) UTC，cancel_reason VARCHAR(128)可空，复用BaseEntity。funding_source_no全局唯一；(tenant_no,quote_no)唯一；金额非负、状态与取消原因配对CHECK。摘要涵盖固定SourceRef、租户/主体/门店/本人、报价号、原经济摘要、准备摘要、币种金额及有效期，生命周期version/取消原因不改变源事实摘要。没有删除、改金额或提交方法。取消后保留报价引用以保证审计。

实现清单：trade.entity.OrdPricingFundingSource；TradeMappers.PricingFundingSourceMapper；trade.policy.PricingFundingSourcePolicy；trade.service.PricingFundingSourceService及service.impl实现；原entitlement.port.EntitlementSourceDecisionPortImpl只扩充日志未决路由及有界重读；BizKey.PRICING_FUNDING_SOURCE。V552只提交迁移文件及生成H2测试模式。没有餐饮重复资金表，也没有新增Controller、PAY资金SourcePort、生产EvidencePort。

验收映射：冻结/留存/重复/并发stage、取消幂等/固定源修订/并发cancel、过期未决及过期显式取消、租户/门店/本人/子源隔离、篡改摘要、留存+插入回滚、决策+状态回滚、现行商品可参与性/税变化、已有优惠/准备摘要变化、拒绝事务内owner读取，以及取消穿插查询，均在PricingQuotePersistenceTest的新增13项原生InnoDB测试中验证；保留原10项报价回归。审查发现的查询取消竞态先RED再修复；移除日志摘要比较的消融按预期失败后恢复原字节。


验证结果：先编译业务/接线，再统一测试。最终shop-base 280项实际通过；shop-core 605项中524实际通过、81原生数据库条件跳过，无失败。一次性MariaDB全部81项实际通过、零跳过（发布23、种子8、税18、报价及日志23、商品9），数据库容器已确认清理。宿主选择50项：41通过、1条件跳过、8原有失败；7项架构消息逐字一致、1项SchemaDrift缺列token集合一致，无新增失败。真实PAY HTTP3、远程2、双部署接线5均实际通过，包含在宿主选择中。Node9、Python定价合同5/交付4、SQL可移植性、Ops双向与B/C正向契约通过；Ops孤儿10和正向缺8、B/C正向缺2均原基线。

一次最终独立审查0Critical/1Important/0Minor，Important为取消穿插查询的正常竞争；新增原生测试先稳定复现CONFLICT，再经有界持久决策重读修复，最终全套原生及核心绿色。没有第二次审查，也没有将跳过算通过。仍缺正式餐饮权益基数、付款人资金授权/提交、活动命中事实及完整公开试算/确认入口；CKO11与09-C2.4继续未完成，不能因日志已落库扩大PAY来源支持或开放生产发布证据。本批只本地提交，V552尚未对业务库执行。


### CKO-11B2E 优惠基数冻结与重新核验（2026-10-23）

§0 依据CKO11、11B2D及用户“先排除不参与活动商品”“税可外加或倒推”“共用核心能力”的要求，补齐资金来源前的优惠基数。此前日志amount_minor只证明原报价应收，不等于券可计算金额。本批闭合基数的份额、排除、冻结、完整性及重新授权；不据此扩大PAY资金执行。首版来源准入继续沿11B2D无优惠、无资金计划、H5本人、完整MAIN整数基线；共享纯基数类型保留组件和小数份额，供后续行业适配，不将这些份额强转旧UseItem整数或合计组件数量。

§1 规则：E01每个source-priced portion必须有唯一明确OPEN UnitPolicy，未知/缺失/重复拒绝；E02 priceTotal=eligible+excluded，非参与商品原价完整进入excluded，不从原订单应收扣除；E03所有金额使用最小单位及JSON安全整数，保留组件标识、原始数量区间、源版本；不通过单价×数量重新四舍五入原金额；E04纯基数不含税推算，随原报价tax.priceTaxMode存储，EXCLUSIVE基数为原税前标价，INCLUSIVE为原含税标价；实际应收/净额/税沿原报价与原税引擎校验结果；E05纯基数拒绝同套餐正金额父级与叶子同时计价；E06冻结基数与日志、报价retain同事务，摘要V2绑定完整basis_snapshot；E07旧行basis_snapshot=NULL维持V1摘要、查询与取消，新的基数读取40190，禁止按当前策略补写或默认全参与；E08readBasis每次凭原顾客凭证重读准备，与原准备摘要、完整报价及冻结基数比对，并在返回前重读来源状态，过期/取消/变化拒绝；E09confirmBasis在重新授权读取后比原日志摘要，旧摘要不延长授权，不产生资产操作；E10同优惠/券/储值资格、数量门槛、组合能力仍需实际owner，基数不是资格或扣款授权。

共享shop-base.common.pricing新增PricingBenefitBasis（Snapshot/Entry、freeze/validate/eligiblePortions）；输入PricingFacts与显式UnitPolicy，输出原价、参与/排除金额及按源序完整份额；摘要无读取时钟。core.dto新增PricingFundingBasis（schemaVersion、benefits、原MerchantTaxSnapshot、quoteNetMinor/quoteTaxMinor/quoteReceivableMinor、quoteEconomicsHash），校验金额守恒及含税/外加税口径。PricingFundingSourcePolicy.verify重建核验原报价后返回该类型；原Service.stage冻结canonical JSON，实体增加basisSnapshot；V553仅新增可空JSON约束列，不改V552。digest按是否有基数选择V1/V2，V2覆盖canonical基数内容。原get/cancel/决策读取边界不变。

§2 执行计划：
1. 纯基数模型、严格校验与测试；core基数DTO及原报价策略接入，新增V553与字段，先编译。
2. 原PricingFundingSourceService增加readBasis(Context,Credentials,sourceNo)、confirmBasis(Context,Credentials,sourceNo,expectedSnapshotHash)，返回BasisView(View,PricingFundingBasis)。重新读取在事务外，状态/摘要复核在返回前进行；完整性/并发不符409，参数400，错作用域404，旧行无基数40190。同数据库短事务不调用owner，原取消日志V1/V2均可恢复。
3. 统一纯模型和原生InnoDB回归：参与/排除、部分组件与小数份额、JSON安全/溢出、完整策略集合、父叶防重计、摘要篡改；持久冻结与幂等、VAT两口径、非参与应收守恒、过期/取消拒绝、每次fresh准备/变更冲突、旧行不可升级、事务拒绝、freeze回滚、V2取消兼容及读取穿插取消。一次消融和一次独立审查，运行全基础/core/原生与宿主既有守卫，再本地提交。无公开API、部署、业务库迁移或远端推送。

§5 验收映射及实际结果见下文；CKO11、C2.4与资金授权/提交继续未完成。


本批方法与字段：

| 方法 | 输入 | 输出 / 约束 |
|---|---|---|
| PricingBenefitBasis.freeze | 已准入PricingFacts、逐份额显式UnitPolicy | Snapshot(schemaVersion/currency/sourceSnapshotHash/sourceVersion/priceTotalMinor/eligibleMinor/excludedMinor/entries/snapshotHash)；Entry包含原PricingUnit与UnitPolicy，输入策略顺序不影响源序输出 |
| validate / eligiblePortions | 完整Snapshot | 独立重建并核对摘要、金额和条目；eligiblePortions仅返回正金额参与份额，不推断券资格或数量门槛 |
| 原stage | 原后端Context、Credentials、quoteNo、expectedPreparationHash | 原View；新行增加冻结basis_snapshot，V2摘要覆盖该JSON；重复冻结不改写 |
| readBasis | 同一后端Context、本人Credentials、sourceNo | BasisView(source=原View,basis=PricingFundingBasis)；每次重新授权，读取期间取消或到期拒绝；不延长原有效期 |
| confirmBasis | 同上＋expectedSnapshotHash | 调用同一fresh读取，再比日志摘要；不能只靠历史hash直接确认 |

basis_snapshot为V553新增可空LONGTEXT，CHECK为NULL或JSON_VALID，不回填历史行。PricingFundingBasis携带schemaVersion、benefits、原MerchantTaxSnapshot、quoteNetMinor/quoteTaxMinor/quoteReceivableMinor及quoteEconomicsHash，解码还要与日志租户/主体/门店、币种、金额及经济摘要一致。日志原amount_minor依然是应收，不改字段含义。

示例（金额均为最小单位，不代表地区法定税档）：原价10000，参与7000，排除3000，税率500bp。外加税时基数7000、排除3000、净额10000、税500、应收10500；含税时基数7000、排除3000、净额9524、税476、应收10000。排除的3000仍需收取；税后应收不能作为折扣券默认基数。最终优惠后的税与资产适用性仍需原税引擎和实际owner重新确认。

| AC | 对应测试方法 |
|---|---|
| E01/E02 | PricingBenefitBasisTest.completePolicySetIsRequiredWithNoUnknownOrPermissiveDefault / partitionsEligibleAndExcludedPricesWithoutRemovingExcludedGoodsFromOriginalTotal；PricingQuotePersistenceTest.excludedGoodsStayPayableAndDoNotEnterCouponPortions |
| E03/E05 | preservesComponentAndFractionalScopesAndNeverRepricesRoundedSourceAmounts / sourceAmountsRemainJsonSafeAndUnmodifiable / bundlePositiveParentAndLeafCannotBothContributeToTheBasis |
| E04 | PricingQuotePersistenceTest.inclusiveBasisUsesOriginalGrossPricesAndVatIsNotAddedTwice / frozenBenefitBasisIsPersistedAndEveryReadAndConfirmReauthorizes |
| E06/E07 | 原failedJournalInsertRollsBackQuoteRetention；新增malformedJsonAndTamperedV2BasisAreRejectedWithoutChangingDecisions / legacyV1JournalStillCancelsButNeverBackfillsABenefitBasis |
| E08/E09 | preparationChangesCannotRefreshOrSilentlyUpgradeTheFrozenBasis / cancellationDuringPreparationPreventsReturningAnOldPendingBasis / basisReadRejectsExpiredCancelledUnauthorizedAndTransactionalRequests |
| 摘要完整性 | PricingBenefitBasisTest.tamperedTotalsEligibilityAndDigestCannotPassValidation / policyInputOrderDoesNotChangeTheSourceOrderedDigestButSourceVersionDoes；消融结果见下文 |

§6 实现清单：shop-base.common.pricing.PricingBenefitBasis及单测；core.trade.dto.PricingFundingBasis、policy.PricingFundingBasisPolicy；原PricingFundingSourcePolicy/Service/Impl/OrdPricingFundingSource；V553与生成H2模式；原PricingQuotePersistenceTest扩充8项（23→31），原生runner计数同步。既有生成清单重新生成无内容变化。一次独立审查无Critical/Important/Minor问题；验证结果如下。


最终验证：阶段业务编译、共享8项及测试编译通过。全部原生MariaDB 89项实际通过、零跳过（发布23/种子8/税18/报价及来源基数31/商品9），一次性数据库已清理。完整shop-base 288项实际通过；shop-core 613项中524实际通过、89原生条件跳过，无失败，条件跳过未计作通过。宿主选择40项中31通过/1条件跳过/8既有失败，Architecture7项失败消息与原始基线逐字一致，SchemaDrift1项缺列token集合一致，无新增失败。

基数校验消融：只在临时目录编译去掉expected.equals(snapshot)检查的PricingBenefitBasis，用它替换该类及两个嵌套record编译产物；tamperedTotalsEligibilityAndDigestCannotPassValidation按预期发生assertion失败。finally恢复全部三个原始class文件的精确字节及mtime，生产源码未改，再执行完整基础/core回归通过。该方式避免重复编译1093个核心类，未跳过最终正常代码的测试。一次独立审查0Critical/0Important/0Minor，无第二轮审查。

Node9、Python定价合同5/交付4、SQL可移植性、Ops正反向及B/C正向契约通过；Ops欠账8/孤儿10、B/C正向欠账2均原基线。H2模式重生成逐字一致。代码清单与本批实际13个路径一致（V553、生成模式、共享模型/测试、core DTO/两策略/来源实体/Service与Impl/原生测试、runner与本TDD），生成分层/词典/索引无额外变化。

交付边界：新增8项纯模型与8项原生测试；保留原31项报价测试内原有23项。新来源V2可读/复核基数，旧V1查询和取消保持兼容，无法读基数时需重新报价，禁止自动补全。下一步仍须实际权益owner对这些明确份额核验资产适用性及资金授权/提交，才能开放完整报价/结账。没有新增EntitlementUseSourcePort适配、PAY资金能力、生产发布证据、公开Controller或前端资金入口；没有执行业务库V553迁移、部署或远端推送。


任务总览对齐（2026-10-23，实现基线0554edbfd）：本次仅修正顶部状态、§2.1批次执行口径、§2.2顺序、§2.3完整待办及§5映射，不变更API/表/权限或业务实现。CKO01～31编号及历史收据保留；11B2A～E完成不等于完整报价/收付款已交付。下一领取项是§2.3-C中的真实券报价与活动事实；按用户确认继续后端优先、分阶段开发编译、批次统一测试，不在本次清单整理中启动业务代码、部署、业务库迁移或远端推送。

本轮独立静态审查记录：装配、共享人工权威、组件原单映射、V11～V15及旧退餐隔离未发现可证Critical/Important；未覆盖审查时仍变化的DINE_IN与BusinessReturn主体，未运行任何编译、自动测试或经营库迁移。静态审查不替代统一验证。

静态补查：DINE_IN专用hash、SKU/商家准入、准备/绑定锁序与闭车；BusinessReturn未付owner只读证据、MAIN/ADDON映射、原量容量及取消/金融互斥门禁已补查，未发现可证Critical/Important。全部仍待统一编译与集中测试。

人工调价HTTP边界补齐：Request/Save/Approve沿项目JsonCreator(DELEGATING)+JsonNode方式严格检查金额、基点、版本、boolean、text、kind及嵌套source；拒绝小数截断、数字字符串、布尔转数值、溢出和未知字段，不依赖全局Jackson开关。新增PricingManualJsonBoundaryTest仅写源码，未运行。未改变合法JSON合同或引入inventory/product依赖。

2026-10-24继续编码交付：§2.13员工处理中批次撤销，API目标FOOD-API-124仅新增受控未提交子能力，尚不支持已受理正式单撤销。B端请求/Java核心/typed双部署/持久恢复/审计与对应测试源码已对齐；独立静态审查1项Important已以专用冻结撤销来源改写（未执行验证）。无新schema，原计划完整任务不勾选完成。生成器已重新运行，空白检查无输出；编译、测试、消融、经营库迁移、部署、提交和推送均未执行。

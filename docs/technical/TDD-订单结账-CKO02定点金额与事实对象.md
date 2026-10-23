# 订单结账CKO-02：定点金额与标准事实对象

> 状态：共享对象已实现，验证记录见§5 · 2026-10-22 · 档位2。执行[完整计划CKO-02](./TDD-餐饮订单结账-完整开发计划.md)，契约依据[CKO-01](./TDD-订单结账-CKO01合同与字段核验.md)，任务基线`ca4808867`。只交付纯值对象与跨域来源引用，不代表规则引擎、报价API或结账支付已实现。

## L1 定位

新增4个不可变record，全部在既有shop-base模块；餐饮、美业后续适配事实后使用同一基础实现。未改零售下单、营销券算法、支付/库存、Spring装配或JSON全局序列化，未新增数据库迁移。

## L2 对象与边界

| 完整类名 | 职责 | 明确不承担的权威 |
|---|---|---|
| ai.neargo.shop.common.pricing.MoneyMinor | 币种＋有符号long金额，精确加减及边界检查 | 不授权负应收、不查询汇率或余额 |
| ai.neargo.shop.common.pricing.PricingUnit | 原商品/组件计价份额、价格修订与税发票行身份 | 不重新推导原权威已舍入金额，不授权小数库存 |
| ai.neargo.shop.common.pricing.PricingFacts | 单币种、不可变、有源快照引用的份额集合 | hash只是引用，不代表身份/价格可信或已扣款 |
| ai.neargo.shop.spi.trade.PricingSourceRef | 跨域请求的来源/版本及可选范围/版本 | 不替换原权益SourceRef，不作为新的资金核销键 |

纯运算common.pricing不引用SPI、Spring、Mapper或行业Entity。来源协议位于唯一SPI位置，后续由应用服务解析为纯内核事实。

## L3 实现详情

## §0 需求 → 实现

| AC | 验收要求 | 实现/测试 |
|---|---|---|
| M01 | 定点加减、币种隔离、溢出不回绕 | MoneyMinor；rejectOverflowAndCurrencyMismatch |
| M02 | 有符号差额与非负应收分开；JSON整数精度保护 | requireNonNegative/requireJsonSafe；对应MoneyMinorTest |
| M03 | 数量最多6位有效小数且不截断；原份额身份明确 | PricingUnit；precision/range测试 |
| M04 | 不可变事实、金额合计守恒、重复份额不进入内核 | PricingFacts；复制、币种、总额和重叠测试 |
| M05 | 原来源身份及范围版本成对出现，不接受脏ID | PricingSourceRefTest |

## §1 影响范围及实施选择

新增代码只在shop-base.common.pricing和shop-base.spi.trade，测试在同模块。已有CouponCalculation、原权益SourceRef、核心OrderService、支付库存Port均未修改。MoneyMinor按现有契约风格校验显式ISO币种，运营币种与资产支持范围仍由原owner检查。

沿用户已指定的`codex/food-next01`分支执行，保留共享工作区其他改动；本任务仅增加限定路径并更新本计划/收据，不改他人源文件或整目录提交。后续如需调整既有业务共享文件，先核对归属及基线。

## §2 对象合同与代码清单

### 2.1 MoneyMinor

```java
record MoneyMinor(String currency, long minor) {
    MoneyMinor add(MoneyMinor other);
    MoneyMinor subtract(MoneyMinor other);
    MoneyMinor requireJsonSafe();
    MoneyMinor requireNonNegative();
}
```

上面是方法清单，不是可编译record声明。实现使用Math.addExact/subtractExact；不同币种直接拒绝，包括两个零值也不能混币种。构造器允许有符号long中间金额，因此`100-200=-100`合法；负应收、折扣或付款在各自边界调用requireNonNegative拒绝。

JSON安全范围为±9,007,199,254,740,991，使用双边比较而非Math.abs，避免Long.MIN_VALUE绝对值溢出。该边界保护不取代门店/支付/行业更严格的业务限额，后续配置与应用服务负责这些限额；不改全局序列化器。

### 2.2 PricingUnit

字段：sourceLineNo、componentRef（可空但不能空字符串）、goodsNo、skuNo、priceRevision、taxInvoiceLineRef、quantityStart、quantityEnd、unitPrice、baseAmount。

- 数量用BigDecimal，采用半开区间`[quantityStart, quantityEnd)`。必须start≥0且end>start。
- stripTrailingZeros后最多6位小数、14位整数，符合候选DECIMAL(20,6)；多余无意义零可规范化，超精度或超范围拒绝，不四舍五入/截断。
- quantity()只计算区间差值；baseAmount保留源权威已确认的金额，不在值对象用unitPrice×quantity另算一次改变舍入。
- unitPrice/baseAmount必须同币种、非负且JSON安全，免费商品可为0但仍需正数量。
- 税发票行身份与促销计价份额身份分别保存；分券/AA不能仅因拆份额改变税行。本类不计算VAT。
- goods/sku业务No限制32，源行/组件/价格修订/税发票行引用限制64；拒绝空白、填充、控制字符。ID有效不代表实际对象存在，应用适配仍须查原权威。

### 2.3 PricingFacts

字段：currency、sourceSnapshotHash、sourceVersion、units。

- sourceSnapshotHash必须是64位小写十六进制；sourceVersion非负。真实性、归属和当前版本仍由来源适配服务核验。
- List.copyOf防御复制，外部更改原列表不能改变事实，返回列表不可修改；源顺序保留。
- 所有行同币种，baseTotal使用checked金额加法；合计也不能超出JSON安全整数范围，不能只检查单行。
- 按`sourceLineNo＋componentRef`分组，在副本中按quantityStart排序检查重叠，复杂度O(n log n)。重复/重叠份额拒绝，相邻区间允许；不同组件独立，不按SKU名称误判。
- 套餐父叶计价选择、不同事实的跨请求范围锁、已付/UNKNOWN份额资格、券数量及税合法性仍是后续规则/核心范围任务，不由此结构对象冒充完成。

当前只承载基础价格事实；优惠/资金/税务完整决策快照由相应任务按typed合同扩充，不预建一组不可核验的可空业务参数。

### 2.4 PricingSourceRef

字段：sourceSystem、sourceType、sourceNo、sourceVersion、scopeNo、scopeVersion。身份长度沿32业务键，版本非负；scopeNo和scopeVersion必须同时为空或同时出现，范围版本0合法。

构造器只校验规范结构，不自动把任何sourceSystem/type注册为可报价来源，不从来源引用推断扣款资格，也不生成权益grant/use/settled键。服务身份、来源注册及权限将在CKO-11接线。

### 2.5 精确代码清单

| 动作 | 仓库路径 | AC |
|---|---|---|
| 新增 | backend/shop-base/src/main/java/ai/neargo/shop/common/pricing/MoneyMinor.java | M01/M02 |
| 新增 | backend/shop-base/src/main/java/ai/neargo/shop/common/pricing/PricingUnit.java | M03 |
| 新增 | backend/shop-base/src/main/java/ai/neargo/shop/common/pricing/PricingFacts.java | M04 |
| 新增 | backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/PricingSourceRef.java | M05 |
| 新增 | backend/shop-base/src/test/java/ai/neargo/shop/common/pricing/MoneyMinorTest.java | M01/M02 |
| 新增 | backend/shop-base/src/test/java/ai/neargo/shop/common/pricing/PricingUnitTest.java | M03 |
| 新增 | backend/shop-base/src/test/java/ai/neargo/shop/common/pricing/PricingFactsTest.java | M04 |
| 新增 | backend/shop-base/src/test/java/ai/neargo/shop/spi/trade/PricingSourceRefTest.java | M05 |

## §3 实施裁决

- Ruling：本次“继续”执行紧邻下一项CKO-02，未开始03的JSON编译器或后续收款流程。
- Ruling：PricingFacts保持本阶段最小价格事实；未来规则/税/资产模型在对应任务扩展，不能把缺失owner合同用虚构事实填满。
- Ruling：原计划02文字提及比例边界，但本阶段没有比例字段/运算；比例0～10000的参数校验明确归CKO-03，算法与叠折归06。已在03任务登记，不创建无消费者的百分比空壳类。
- Ruling：不可变MoneyMinor支持有符号差额，非负由明确边界约束；JSON安全约束与long算术溢出分开检查。
- Ruling：全量ArchitectureTest有一处任务基线已有全限定注解失败，不修改无关积分Controller，不将15/16通过改写成架构全绿。

## L4 验证与交付

## §4 风险与剩余门禁

没有数据库/API/支付写入副作用；这些对象不是正式报价、资产授权或营业收银能力。结构校验通过仍必须由原owner核验源价、税类、币种、计量、可用资产和范围。原StockPort/UseItem整数计量限制继续有效，小数BigDecimal事实不能直接截断传入原接口。

## §5 测试与真实结果

| 检查 | 结果 | 覆盖边界 |
|---|---|---|
| 原券算法及原权益来源基线 | 6测试通过 | 本任务前基线 |
| 新类实现前RED | 编译失败，目标4类不存在 | 先有测试再写实现 |
| 新增4个测试类 | 18测试通过 | 金额/数量/事实/SPI来源 |
| shop-base全套 | 96测试通过，0失败/错误/跳过 | 含新增18项；上游job-api 11及svc-client 10也通过 |
| 关键保护消融 | 3次均触发目标断言失败，源码恢复 | 溢出、JSON安全边界、数量精度保护 |
| ArchitectureTest全套 | 16测试：15通过、1失败 | 失败见下，未宣称全绿 |
| 新Java静态风格及依赖 | 8文件≤120列/无通配符；jdeps仅JDK及新定价对象依赖 | 限新增8个Java文件，不概括全部历史源码 |

全套shop-base初次被沙箱禁止svc-client测试绑定本机端口，报SocketException: Operation not permitted；获准本机临时HTTP测试监听后原命令通过，没有修改网络测试或业务代码绕过。

架构失败为`ArchitectureTest.noFullyQualifiedReferences`发现`shop-app/.../OpsPointsInspectionController.java`中的全限定PathVariable注解；该文件与任务起点HEAD字节一致、工作区无本任务修改。其余15项（包括common业务依赖及Port位置）通过。本任务新包另检查直接依赖，确保不引入Spring、Mapper、SPI反向依赖或行业实现。

实际命令（仓库根，Java21离线）：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f backend/pom.xml -pl shop-base -am test
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f backend/pom.xml -pl shop-app -am test \
  -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
```

消融后再次运行恢复代码的完整shop-base测试；最终计数及提交在§6记录。未改known-failures棘轮或无关历史代码来消除架构红项。

## §6 执行及设计 → 实现对账

本任务新增4个共享record与4个测试类，更新任务清单及本收据；无POM/依赖/Controller/Service/Mapper/迁移改动。下一任务为CKO-03类型化JSON规则编译，原独立资金/库存/AA/储值混合门禁不变。


最终恢复后回归：shop-base 96测试、job-api 11测试、svc-client 10测试均为0失败/0错误/0跳过。新增4个类对应测试分别为5/5/5/3，共18项。

独立只读评审：未发现阻断问题，未修改工作区或替代测试执行。评审明确留待后续的边界逐项归属：来源真实性/权限、份额间商品/价格/税身份一致性及原owner小数能力由CKO-11适配核验；税舍入归07；跨请求范围锁归19；经济hash归10；全局JSON序列化不在本任务修改。这里的结构校验不能代替这些权威校验。

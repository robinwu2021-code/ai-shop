# 订单结账CKO-09：受控种子与配置API

> 状态：实施中 · 2026-10-22 · 档位2 · 基线9f5140dff。依据[完整计划CKO-09](./TDD-餐饮订单结账-完整开发计划.md)、[存储方案](./TDD-统一试算引擎-存储与Java实施方案.md)、[初始种子与税费](./TDD-统一试算引擎-初始规则与结账税费.md)及[ADR-040](./ADR/ADR-040-餐饮销售配置与耗用权威边界.md)。

## L1 定位

先完成原规则目录对应的种子校验及仅草稿导入，再接平台/门店鉴权API。示例门店、能力夹具、资金计划向量不能变成实际账号/门店/资产能力。2个AE税档只能保存为待审核候选，不能自动获得法律有效性或门店税登记资格。

## L2 边界

系统没有销售VAT税档及门店已核验税登记的现成权威来源；须在平台/原主体能力中补真实来源，不能以new Context构造成功替代核验。模板、门店权限分别登记；C端不开放配置写接口。本轮不接真实Keeta或资金扣款。

## L3 需求与实现

## §0 需求→设计

| AC | 需求 | 落点 |
|---|---|---|
| S01 | 20个mandatory规则与代码目录完全匹配 | PricingSeedValidator复用PricingRuleCatalog |
| S02 | 3行业模板仅生成草稿，2AE税档待审核；无示例门店/能力导入 | typed SeedPlan及白名单持久化 |
| S03 | DRY_RUN无副作用；同版本异hash永久拒绝 | provenance列seed_version/hash/snapshot，原唯一头约束及事务 |
| S04 | 导入原子性及并发幂等 | 原事务管理器/幂等设施，3头同事务；MariaDB |
| S05 | 分端权限/错误码/身份和税档来源门禁 | 后续本任务API子步，未具备真实来源时不开放发布成功路径 |

## §1 存储裁决

幂等收据24小时过期，不能单靠它保证同种子版本永久不变。向原ord_pricing_template增加seed_version VARCHAR(64)、seed_hash CHAR(64)、seed_snapshot LONGTEXT可空来源列，不增加第8张表。seed_snapshot只保留种子版本/规则/税档候选/资料引用；不包含门店示例、能力夹具及资金向量。templateCode由seedVersion及来源templateNo派生稳定哈希，实际templateNo仍用BizKey。

导入不更新已有草稿、不发布、不自动升级门店。重放返回原3个业务No，即使有人编辑过这些草稿也不覆盖。相同seedVersion不同全量规范化摘要拒绝，包含只改示例部分的情况。仅存待审核税档候选，不把它们加入verifiedTaxProfiles。

引擎规范V4与配置JSON结构schemaVersion=2是不同维度。原CKO08写入schemaVersion=4不匹配实际编译器，这轮补断言后改为实际配置结构版本；历史不可变revision不做覆盖更新。

## §2 代码及步骤

A. shop-base/common.pricing/PricingSeedValidator与不可变SeedPlan：严格字段/重复key/大小/代码规则/模板/税档候选验证。校验时使用代码允许的结构能力上界，仅验证模板可表达性，绝不视为实际owner能力。

B. trade.service.PricingSeedService及impl：importDrafts(Context,seed,registryVersion,DRY_RUN|DRAFT)；返回mode/seedVersion/hash、规则/模板/税档候选计数及业务No。OrdPricingTemplate增加来源字段，V533增量迁移；原CKO08服务输出metadata修正schemaVersion。

C. 平台/门店Controller、原权限/i18n及真实来源接线：在本任务后续子步完成，入口不得接受Context或自报主体/能力/税率。税档发布与门店规则发布分权。

### C步税档与主体登记归属（用户已确认，待实现）

| 对象 | 权威维护方 | 门店可做的事 | 发布/使用门禁 |
|---|---|---|---|
| 地区税档及版本 | 平台运营审核，保存来源依据、地区、币种、有效期、审核人及内容摘要 | 申请适用已发布版本 | `pricing:tax:publish`独立授权；待审核种子不能作为已发布版本 |
| 供应方税登记 | 原经营主体档案维护；登记号、所属地区、有效期及核验状态由主体能力提供 | 关联本门店所属主体的有效登记引用 | 服务端验证store→entity归属、登记主体/地区/有效期；不能跨主体选择 |
| 门店税档绑定 | 原门店配置草稿及不可变revision | 选择适用税档版本及本主体登记引用 | 服务端核验平台审核版本、登记及交易适用条件，不能提交法定税率 |
| 报价/订单税证据 | 原报价及最终确认链 | 读取试算结果 | 冻结税档/主体登记版本与摘要；缺失、失效或不适用则阻止相关报价/确认 |

继续沿原方案将经审核税档完整嵌入不可变模板/门店发布与报价快照，不因这次归属确认另建一套门店税务主体。C步须先补齐原主体档案的税登记来源及平台审核证据存储，再对接鉴权API。`verifiedTaxProfiles`是服务端核验结果，不能由前端列表、自报APPROVED字段或seed候选生成。平台模板发布权限不自动等于税档审核权限。审核税档和税登记的改动生成新版本，历史订单仍引用原证据；是否允许新交易按当前有效性重新核验。

- [x] 验证器及导入需求测试RED→GREEN。
- [x] 真实MariaDB验证干跑、重复/冲突、失败回滚、并发及无激活。
- [ ] 平台/门店API与合法税档来源、越权和三语测试。
- [x] A/B回归、评审、消融及生成物对账；不把A/B完成标为整个09完成。

## L4 验证与交付

## §5 验证记录

| 验证项 | 实际结果 |
|---|---|
| PricingSeedValidatorTest | 9项通过；含20规则/3模板/2待审核候选、全量hash、非法字段/日期/税候选拒绝 |
| PricingSeedServiceTest | 7项原生MariaDB11.4通过，0跳过；干跑、幂等、永久版本冲突、编辑后重放、触发器失败回滚、并发、非平台拒绝 |
| PricingConfigServiceTest | 17项原生MariaDB11.4通过，0跳过；结构版本断言由expected2/actual4变绿 |
| 事务消融 | 移除TransactionTemplate后，回滚用例1个断言失败/0错误，发现1份残留模板；实现已恢复 |
| 非法日期回归 | 新测试先1断言失败/0错误（DateTimeParseException未转换），补DateTimeException转换后9项全绿 |
| 核心回归 | 日期修复后完整reactor成功：base237/core438（实际执行414通过，24个仅隔离DB执行用例跳过；另有24项原生DB实跑均通过） |

schema生成器Node测试1项通过；最终原生MariaDB24项重跑均通过、无跳过。

应用守卫30项中28项通过，2项既有基线失败：`ArchitectureTest.noFullyQualifiedReferences`指向未改动的OpsPointsInspectionController全限定注解；`SchemaDriftTest.migrationAndTestSchemaMatch`指向原pay_ent_exception_action列解析差异（CKO08已用原HEAD迁移/schema复现，见CKO08收据）。新增V533的SchemaGeneratorTest/H2全schema载入、SchemaParityTest、业务键、出箱路由及三语6项均通过。未声称全应用守卫通过。

只读独立评审未发现A/B阻塞问题；唯一日期错误契约问题按对未来API的影响提升为本轮修复项，已有RED→GREEN证据。候选日期/格式校验不等于平台审核了税务法律依据。

## §6 对账与剩余工作

已确认：正式税档由平台运营审核，门店关联经营主体的税登记。平台税档审核/发布与门店规则发布分别授权；门店不重复维护主体税登记，不允许以自报税率或示例税档代替核验。此归属用于C步真实来源接线；旧门店/零售未自动迁移。


### A/B代码对账

| §2设计 | 实际代码 |
|---|---|
| 纯种子校验 | shop-base/common.pricing/PricingSeedValidator及9项测试 |
| 草稿原子导入 | trade.service.PricingSeedService及impl；7项真实DB测试 |
| 永久来源约束 | OrdPricingTemplate新增3字段、V533增量迁移及重新生成schema-test.sql |
| metadata版本修正 | PricingPublicationPolicy写结构版本2，revision取实际编译结果；原配置DB测试增加断言 |
| 隔离数据库证据 | verify-pricing-config-mariadb.py支持publication/seed/all，TCP探测等待最终实例 |
| 文档同步 | 本收据、完整开发计划及存储方案；生成表/分层/实体/ER与索引，保留共享工作区其他草稿 |
| C步配置API/真实税务权威 | 未实现，不计为A/B交付；不开放伪造发布成功路径 |


### 统一分支集成（2026-10-22）

统一分支已有V532/V533储值迁移，并推进至V545。餐饮分支原V532配置表与V533来源字段仅在临时测试库验证，未部署业务库，本次分别重编号为V546/V547并同步测试引用；SQL内容不变，统一分支原已部署迁移不变。保留shop-core两边的H2及MySQL测试依赖。旧收据中的V532/V533表示原餐饮分支历史编号，最终集成编号以此为准。


集成时发现统一分支V538/V539动态CHECK替换及MODIFY/ADD省略COLUMN语法未被原测试schema生成器支持。新增精确完整语句白名单重放（仍拒绝未知/被改动动态SQL），保留其他约束；ADD列捕获收紧以防多空格/换行把UNIQUE/INDEX误吞。5项Node回归均通过，新增路径先RED后GREEN；全库生成362表/2417种子，H2完整加载通过。独立评审确认原V532/V533储值内容不变、餐饮迁移只重编号及无重复版本；其空白回溯发现已补测试修复。

合并结果核心回归477项中453项执行通过/24项条件跳过；对应24项发布/导入在临时MariaDB实跑全通过，无跳过。应用守卫31项中23项通过/8项失败；在原统一基线e9ae7bd2d隔离快照重跑ArchitectureTest/SchemaDriftTest，8个失败方法及逐条错误详情与合并结果完全一致，不视为本次引入，也不声称全应用测试通过。餐饮五模块reactor通过：531项/485执行通过/46条件跳过（food-api5、food-core313/38跳过、food-web146、food-channel24、food-server43/8跳过）；尚未将这些跳过项冒充真实数据库或远端验收。最终SchemaGeneratorTest、SchemaParityTest及BackendI18nParityTest共8项重跑全通过。

# 统一试算引擎：存储与Java实施方案

> 状态：部分实施（CKO-02共享值对象及CKO-03配置编译），其余按计划待开发 · 2026-10-22 · 档位2。本文细化已讨论规则的存储、Java职责、接口和开发顺序，不表示表、API或资金适配已交付。沿用[ADR-040权威边界](./ADR/ADR-040-餐饮销售配置与耗用权威边界.md)，不重新建立商品、订单或资金权威。
>
> 规则依据：[三行业优惠组合与多券](./TDD-三行业优惠规则-组合与多券.md)、[规则公式及门店配置](./TDD-统一试算引擎-规则公式与多行业模型.md)、[54条规则目录](./TDD-统一试算引擎-三行业规则与规则引擎.md)。需求沿用[餐饮完整需求](../requirements/餐饮系统-完整需求文档.md)中报价、结账、资金与报表要求，以及上述三行业专项规则。

本轮细化：[详细规则初始数据、结账S00～S14与地区税费](./TDD-统一试算引擎-初始规则与结账税费.md)。税基分类、税前/含税、最终定税及invoiceGross/customerDue以该补充为准；总规范升级为PRICING_RULE_SPEC_V4。

完整执行清单：[餐饮订单结账CKO-01～31](./TDD-餐饮订单结账-完整开发计划.md)，对齐原ORD/CORE/PAY任务，包含每项依赖、目标文件、接口及验证。

CKO-01已完成源码合同核验：[能力、字段差异及纯内核合同基线](./TDD-订单结账-CKO01合同与字段核验.md)。共同时间精度收口为现有DATETIME(3)，经营币种列为CHAR(3)；不代表实际数据库或外部资金合同已验收。

CKO-02实现收据：[定点金额、计价份额、事实集合及SPI来源引用](./TDD-订单结账-CKO02定点金额与事实对象.md)。这些基础对象已实现；[CKO-03配置编译收据](./TDD-订单结账-CKO03规则配置编译.md)记录已实现的类型化编译、强制目录、覆盖/reset及能力校验。运行期规则、金额/税费计算、配置/报价表及交易Service仍待后续任务。

## L1 定位

**共享归属已确认（2026-10-22）：** 通用商品/订单扩展、规则配置、试算、税费、报价与分摊、结账确认均在原零售及核心基础设施实现；资金、库存、打印和报表沿各自原权威域。餐饮和美业只做行业事实/配置适配、入口及各自流程，通过唯一Port调用基础能力，不在行业包复制共享Service、算法、报价表或资产账。零售兼容测试从公共内核开发阶段执行，不等到行业接入末期再补。

实现一个**配置驱动、规则先行、定点计算、报价可追溯**的共享引擎。行业提供默认配置，门店保存明确覆盖；规则校验通过后才生成计算计划。前端现阶段调用Java API，正式下单继续使用同一内核重验。

新增**7张表**：4张保存行业模板、门店覆盖及不可变发布版本；3张保存报价、计价行及逐项分摊。不新增券、储值或积分账本。第一期不增加Maven模块，不引入Drools、脚本执行器或任意公式语言。

## L2 结构与边界

![共享内核与行业适配](./diagrams/unified-pricing-engine.svg)

| 部分 | 放置位置 | 职责 |
|---|---|---|
| 配置及报价存储 | `shop-core`，`trade`域，`ord_`前缀，与商品/订单同库 | 模板发布、门店覆盖、报价及分摊证据 |
| 纯规则与计算内核 | `shop-base`，`ai.neargo.shop.common.pricing` | 配置合并、冲突校验、计划、公式、金额守恒；不访问数据库或网络 |
| 配置/报价服务 | `shop-core`，`ai.neargo.shop.trade.service/impl` | 身份范围、权威事实、能力校验、快照、确认编排 |
| 餐饮入口与适配 | 原`food-web/food-api/food-core` | 菜品/配料/套餐及餐牌版本、桌台/AA范围、B/C/Internal入口 |
| 零售、美业适配 | 各自原模块 | 转换源事实，沿原业务确认协议；存量零售保持原兼容档案 |
| 券、活动、储值、积分、PAY、库存 | 原能力及唯一Port | 资格、余额、原分摊、预占/提交/释放/查回；不是配置引擎的账本 |

最重要的边界：**配置允许 ≠ 提供方支持 ≠ 本次有资格 ≠ 已扣款**。四层分别校验。食品模块独立部署时经typed InternalApi调用核心报价服务，不直连核心Mapper；核心也不依赖food实体或Service。

## L3 详细设计

## §0 需求 → 设计

| AC | 验收要求 | 设计落点 |
|---|---|---|
| P01 | 餐饮、零售、美业默认规则不同；门店只改允许项 | 模板及门店4表；PricingConfigCompiler |
| P02 | 默认1张折扣券＋1张金额券；活动禁券；折扣券禁储值；券后禁人工打折/抹零 | 固定规则目录、RuleEvaluator、发布及运行双校验 |
| P03 | 多折扣券有数量、分项/择优/叠加规则；显式选择不能静默丢弃 | BenefitPlanSelector、真实计价份额及逐券分摊 |
| P04 | 先抵扣后折扣/储值、再积分、最后获准抹零；找零独立 | CalculationPlan、FormulaRegistry、CashTenderPolicy |
| P05 | 试算与确认同一内核；历史经济事实不随配置变动 | 报价3表、PricingRevalidator、冻结确认 |
| P06 | 人工操作有原因、权限、额度、审批；消费者不能伪造 | PricingAdjustmentPolicy、权限及审批事实 |
| P07 | 无券/余额重复消费，UNKNOWN不换键；AA/拼桌不重置次数 | 原结算意图、根账单范围版本、资产恢复协议 |
| P08 | 未来前端可估算，多行业共享且兼容原零售 | CalculationSpec及一致性向量；行业适配/legacy开关 |
| P09 | 详细初始规则、结账逐阶段实现、地区VAT及税证据 | 补充方案N01～N05、初始种子、TaxEngine及报价税字段 |

本篇为实现设计，无孤立新增业务；积分、餐饮储值混合支付等原能力缺口以BLOCKED明确暴露。

## §1 当前实现与复用

- `PlatformConfigService`管理皮肤/开关等`sys_setting`配置，不具备本方案的模板锁版与不可变发布协议。复用平台身份、审计、幂等、outbox基础设施，不把整个方案塞进一个可覆盖JSON键。
- 餐饮`FnbConfigRevision`服务餐牌等发布，不承载跨行业规则；`fnb_settlement.quote_ref`及原`fnb_charge_intent`保留作为行业引用/结账恢复位置。具体字段兼容性在实施迁移前逐列核验。
- 原`OrderService.preview`、活动/券计算、`CouponPort`及其分摊继续由原权威负责。新策略要求“先金额券后折扣券”时，原提供方必须能报价/确认这个准确顺序和基数；只有单券allocate能力不能证明组合可用。
- 当前储值报价只支持特定`CORE/SERVICE_USE`来源、CNY、无运费且要求全额覆盖；`applicableMinor`不是餐饮部分储值扣款授权。餐饮储值及美业混合支付不能靠配置开启。
- 表实体继承现有`BaseEntity`；接口/实现分包；`TradeMappers`内新增嵌套Mapper；不加Repository转发层。规范以[统一Java规范§七](../../.claude/skills/ai-shop-dev-standards/references/java.md)为准。

## §2 实施设计

### 2.1 存储原则

1. **草稿可变、发布不可变、当前指针可变**。修正已发布配置必须新建revision；不UPDATE旧版本内容。
2. 行业模板版本锁定到门店发布版本。行业发布V2不自动修改仍锁V1的门店；升级需预览差异并显式发布。
3. 门店仅存覆盖项；已发布门店版本同时保存最终有效配置。覆盖字段缺省=继承，`false`=明确关闭，`null`=拒绝；数组整体替换；恢复继承是删除覆盖键。
4. 系统强制规则和计算公式版本在代码注册；数据库保存规则编号与有类型参数，不存Java类名、SpEL、SQL或可执行脚本。
5. 当前一期`tenant_no=MAIN`，沿现有底层约定；仍保留租户字段和业务唯一约束。主体、门店与行业从可信档案及鉴权上下文解析，不信任请求自报。
6. JSON不是随意扩展包：有`schema_version`、字段白名单、长度/数量/深度上限和严格反序列化。不能依靠JSON内容维持唯一性和并发约束。

### 2.2 7张表与公共字段

所有表使用现有`BaseEntity`字段：`id BIGINT`物理主键、`tenant_no`、`created_at/created_by`、`updated_at/updated_by`、`version BIGINT`、`deleted`。对外只用业务No，不返回id。发布时间和失效时间使用项目既有时间/时区转换规则，禁止使用收银终端时钟判定有效性。

下列长度是设计上限：业务No `VARCHAR(32)`，行业/状态/枚举 `VARCHAR(32)`，哈希`CHAR(64)`，描述`VARCHAR(256)`，时间`DATETIME(3)`，JSON快照沿项目迁移方言选受校验的JSON/TEXT列。实施前用现有真实BizKey长度及数据库规范复核，不直接复制候选DDL上线。

| 表 | Entity | 用途 |
|---|---|---|
| `ord_pricing_template` | OrdPricingTemplate | 每个行业模板的草稿和发布指针 |
| `ord_pricing_template_revision` | OrdPricingTemplateRevision | 不可变行业发布版本 |
| `ord_store_pricing_config` | OrdStorePricingConfig | 每店配置草稿和发布指针 |
| `ord_store_pricing_revision` | OrdStorePricingRevision | 不可变门店发布版本及有效配置 |
| `ord_price_quote` | OrdPriceQuote | 一次可追溯报价头、事实/规则快照及汇总 |
| `ord_price_quote_line` | OrdPriceQuoteLine | 源计价行、组件及数量份额金额 |
| `ord_price_quote_allocation` | OrdPriceQuoteAllocation | 每项优惠、资金计划和人工调整的行级分摊 |

#### A. `ord_pricing_template` 行业模板头

| 字段 | 类型 | 说明 |
|---|---|---|
| template_no | VARCHAR(32) | 模板业务键 |
| industry_code / template_code | VARCHAR(32) | FOOD / RETAIL / BEAUTY_SERVICE；同一行业可保留新旧档案 |
| name / status | VARCHAR(128) / VARCHAR(32) | ACTIVE / ARCHIVED；不把草稿状态冒充发布状态 |
| active_revision_no | VARCHAR(32), nullable | 首次发布前为空 |
| draft_content / draft_schema_version | JSON / INT | 完整候选配置及结构版本 |
| draft_version | BIGINT | 草稿CAS版本，不与BaseEntity.version混淆 |

唯一：`(tenant_no, template_no)`、`(tenant_no, industry_code, template_code)`。模板业务键不因archive被复用。系统受控映射指定各行业新接入默认template_code，禁止凭“最新创建时间”选模板。

#### B. `ord_pricing_template_revision` 行业发布版本

| 字段 | 类型 | 说明 |
|---|---|---|
| revision_no / template_no | VARCHAR(32) | 版本身份及父模板 |
| revision | BIGINT | 父模板下单调递增序号 |
| industry_code / schema_version | VARCHAR(32) / INT | 历史自描述 |
| definition_snapshot | JSON | 完整发布配置；包含规则、能力要求、公式和分摊算法引用 |
| definition_hash | CHAR(64) | 规范化内容SHA-256 |
| engine_spec_version / rule_catalog_version | VARCHAR(64) | 引擎规范、有限规则目录版本 |
| previous_revision_no | VARCHAR(32), nullable | 上一发布版本 |
| published_at / published_by / publish_reason | 时间 / VARCHAR(32) / VARCHAR(256) | 发布证据 |

唯一：`(tenant_no, revision_no)`、`(tenant_no, template_no, revision)`。发布事务内锁父头/版本CAS取下一个revision，不能无锁`MAX+1`。版本只能插入和查询；不可archive历史版本来绕过引用。

#### C. `ord_store_pricing_config` 门店配置头

| 字段 | 类型 | 说明 |
|---|---|---|
| config_no / entity_no / store_no | VARCHAR(32) | 经营主体及门店归属 |
| industry_code / template_no | VARCHAR(32) | 行业及当前选定模板 |
| active_revision_no | VARCHAR(32), nullable | 为空时尚未激活，不能直接确认交易 |
| draft_template_revision_no | VARCHAR(32) | 草稿明确锁定的模板版本 |
| draft_override / draft_schema_version | JSON / INT | 仅显式覆盖项；初始`{}` |
| draft_version / status | BIGINT / VARCHAR(32) | CAS及ACTIVE/ARCHIVED |

唯一：`(tenant_no, config_no)`、`(tenant_no, store_no, industry_code)`；索引`(tenant_no, entity_no, store_no)`。一个门店行业一个生效头，不按堂食/外送各建一个头；场景差异在发布配置的受控channel规则中表达。首次创建用唯一约束处理并发，不能只用JVM锁。

门店初始化时发布一份空覆盖版本，显式固定当时行业模板；未完成初始化返回`CONFIG_NOT_ACTIVATED`，不隐式取平台最新模板结账。

#### D. `ord_store_pricing_revision` 门店发布版本

| 字段 | 类型 | 说明 |
|---|---|---|
| revision_no / config_no / revision | VARCHAR(32) / VARCHAR(32) / BIGINT | 发布身份及序号 |
| entity_no / store_no / industry_code | VARCHAR(32) | 归属历史证据 |
| template_revision_no | VARCHAR(32) | 固定行业版本 |
| override_snapshot / effective_snapshot | JSON | 当时覆盖项与合并后的完整配置 |
| schema_version / effective_hash | INT / CHAR(64) | 结构版本、最终配置哈希 |
| engine_spec_version / rule_catalog_version | VARCHAR(64) | 对应代码规范版本 |
| previous_revision_no / rollback_from_revision_no | VARCHAR(32), nullable | 正常前序和回滚来源 |
| published_at / published_by / publish_reason | 同上 | 发布证据 |

唯一：`(tenant_no, revision_no)`、`(tenant_no, config_no, revision)`；索引`(tenant_no, store_no, published_at)`。回滚=复制旧内容发布新revision，仍需验证当前强制规则、门店归属与真实能力，不能把指针直接指回旧版绕过校验。

#### E. `ord_price_quote` 报价头

| 字段 | 类型 | 说明 |
|---|---|---|
| quote_no / entity_no / store_no / industry_code | VARCHAR(32) | 报价业务键与归属 |
| source_system / source_type / source_no | VARCHAR(32) | 真实购物车/订单/获准结账范围引用 |
| source_version / root_scope_no / scope_version | BIGINT / VARCHAR(32) / BIGINT | 源CAS；原根账单身份和已结/未知份额版本 |
| subject_no / channel / quote_phase | VARCHAR(32) | 获准消费者身份、渠道；LIVE/FROZEN_UNPAID |
| store_revision_no / template_revision_no | VARCHAR(32) | 使用的已发布配置 |
| currency / engine_spec_version | CHAR(3) / VARCHAR(64) | 币种及规范版本 |
| request_hash / facts_hash / economics_hash | CHAR(64) | 规范化意图、权威事实及经济结果分别取hash |
| facts_snapshot / config_snapshot / plan_snapshot | JSON | 权威事实、有效规则、唯一获准计划及提供方证据引用 |
| result_snapshot / schema_version | JSON / INT | 完整可重放输出及结构版本 |
| goods_minor / price_adjustment_minor | BIGINT | 原商品金额、有符号价格纠错差额 |
| activity_discount_minor / coupon_discount_minor | BIGINT | 活动、券优惠；与其他优惠分列 |
| manual_discount_minor / points_discount_minor / waiver_minor | BIGINT | 人工促销、积分抵扣、人工抹零 |
| fee_minor / tax_minor / receivable_minor | BIGINT | 费用、税额、最终营业应收 |
| stored_planned_minor / other_planned_minor / remaining_due_minor | BIGINT | 新资金计划，不代表已扣款；本范围剩余未安排金额 |
| cash_due_minor / cash_tender_minor / cash_change_minor | BIGINT | 此次现金计划、实收意图、找零；无现金实收时后两者可空 |
| status / expires_at | VARCHAR(32) / 时间 | READY/EXPIRED/SUPERSEDED；这里只管理报价可用性，不是支付状态 |

唯一：`(tenant_no, quote_no)`；索引`(tenant_no, store_no, source_type, source_no, created_at)`及`(tenant_no, expires_at, status)`。查询和引用必须校验主体/门店/消费者范围；quoteNo不是持有人授权凭证。

报价金额、分摊及快照写入后不可改；现金实收或选券变化创建新quote。确认复用原业务意图绑定的quote，不在报价头创建第二套支付状态机。过期清理不能删除已被订单/意图引用的经济证据；归档期限沿订单/资金留存策略。

#### F. `ord_price_quote_line` 计价行

| 字段 | 类型 | 说明 |
|---|---|---|
| quote_line_no / quote_no | VARCHAR(32) | 行业务键、父报价 |
| source_line_no / parent_source_line_no / component_ref | VARCHAR(64), parent可空 | 菜品、小料、套餐叶子等权威身份 |
| pricing_unit_ref / quantity_start / quantity_end | VARCHAR(128) / DECIMAL(20,6) | 真实计价份额；区间避免同一份商品重复用券 |
| goods_no / sku_no / line_kind | VARCHAR(32) | 原商品SKU及GOODS/ADDON/BUNDLE_COMPONENT等类型 |
| quantity / unit_price_minor / gross_minor | DECIMAL(20,6) / BIGINT / BIGINT | 固定精度；计量更高精度由原商品合同决定，不能静默截断 |
| price_adjustment_minor / activity_discount_minor / coupon_discount_minor | BIGINT | 有符号调价、活动/券分摊 |
| manual_discount_minor / points_discount_minor / waiver_minor | BIGINT | 人工/积分/抹零分摊 |
| fee_minor / tax_minor / receivable_minor | BIGINT | 最终行级金额；费用独立行或确定分摊，不能重复计入 |
| eligibility_snapshot / source_snapshot | JSON | 分活动/券/储值/积分资格及原价格版本、套餐计价模式 |
| sort_order | INT | 稳定源序，不能用随机No决定舍入尾差 |

唯一：`(tenant_no, quote_line_no)`及`(tenant_no, quote_no, pricing_unit_ref)`。组件ref不等于SKU；套餐父项和叶项只能一方承担同一金额。套餐展示父行可保留，但不得重复加入可优惠基数。

#### G. `ord_price_quote_allocation` 分摊证据

| 字段 | 类型 | 说明 |
|---|---|---|
| allocation_no / quote_no / quote_line_no | VARCHAR(32) | 父报价及实际承接金额的行 |
| allocation_kind / stage / step_order | VARCHAR(32) / VARCHAR(32) / INT | DISCOUNT/POINTS/WAIVER/FUNDING_PLAN等及固定阶段顺序 |
| benefit_ref / owner / owner_quote_ref | VARCHAR(64) / VARCHAR(32) / VARCHAR(128) | 实际券/活动/资金工具引用与提供方报价证据；不存卡密 |
| application_ref / rule_no / rule_version | VARCHAR(64) / VARCHAR(64) / VARCHAR(64) | 一次应用身份及规则版本 |
| basis_minor / amount_minor / payable_rate_bp | BIGINT / BIGINT / INT, rate可空 | 此步骤前基数、非负实际分摊、应付比例；9000=九折 |
| quantity_start / quantity_end | DECIMAL(20,6) | 实际覆盖份额；与源行区间同口径 |
| funder_ref / algorithm_version / evidence_snapshot | VARCHAR(64) / VARCHAR(64) / JSON | 原资方、分摊算法及逐步舍入证据 |

唯一：`(tenant_no, allocation_no)`及`(tenant_no, quote_no, step_order, application_ref, quote_line_no)`。一个application在同一行多份额先归并为稳定行/步骤，再入库；索引`(tenant_no, owner, benefit_ref)`用于追溯，**不能以该索引充当券核销锁**。

一张券分摊多行仍只算1张；用`owner+benefit_ref`对实际使用资产去重。未使用候选及冲突原因在result_snapshot保存，不产生核销分摊。字段中的FUNDING_PLAN不计营业优惠；现金多付找零不分摊为商品减免。

### 2.3 发布与并发协议

```text
保存草稿(expectedDraftVersion)
→ 严格字段/上限校验、CAS更新草稿
→ 预览：固定模板版本 + 覆盖 → 编译 → 差异/冲突/能力报告
→ 发布：读取草稿并校验 → 事务内重验版本 → 插入不可变revision
→ CAS切换activeRevisionNo → 同事务审计/outbox → 提交
```

- 预览无发布副作用；发布请求提交`expectedDraftVersion + expectedActiveRevisionNo + previewHash`。远端能力查询在短事务外完成；能力证据带版本/有效期，正式交易仍逐单复核。
- 首次发布允许expectedActiveRevisionNo为空；父头唯一性+行锁/CAS处理竞争。revision插入、指针CAS、幂等结果及outbox必须同事务，CAS失败回滚全部本地写入。
- 使用原幂等设施：键绑定操作者、主体、资源、操作及规范化bodyHash；相同key不同body拒绝。重放返回原版本，不再次发布。
- 禁止发布未知字段、循环依赖、不可用公式、不可放宽互斥、非法券上限组合；暂未实现的资金能力若被开启直接阻止发布。
- 缓存按不可变revisionNo/hash键；交易取权威发布指针或经验证的指针版本。缓存故障不能退回未经发布默认值。

### 2.4 门店覆盖示例

```json
{
  "templateRevisionNo": "PTR_FOOD_V1",
  "expectedDraftVersion": 4,
  "override": {
    "couponMode": "MULTI",
    "couponLimits": {"total": 3, "percent": 2, "amountTotal": 1, "cash": 1, "deduction": 1},
    "discountCouponPolicy": {
      "maxUsedPerOrder": 2,
      "overlapMode": "DISJOINT",
      "maxUsedPerItem": 1,
      "stackingEnabled": false
    },
    "storedValueEnabled": false
  }
}
```

结果是“最多2张折扣券分项＋最多1张金额券”，不是同项连续打两次折。`cash=1`与`deduction=1`仍受`amountTotal=1`约束，不能各用1张突破上限。真实提供方未通过此多券能力验收时，发布报告`OWNER_CAPABILITY_UNSUPPORTED`，不能仅保存成功就宣称可交易。

强制互斥由规则目录提供，不允许用覆盖JSON改成`false`。恢复某项继承使用单独的`resetPaths`白名单，不使用`null`暗示删除。

### 2.5 规则执行与公式

**规则不是任选一条执行**：加载所有相关规则→校验全局/来源/数量/范围/组合/能力/人工授权→全部通过→生成计划。ALLOW组合不会覆盖DENY。行业配置不是靠priority覆盖强制规则。

规则目录采用有限枚举，例如`FORBID_COMBINATION`、`COUNT_LIMIT`、`DISJOINT_SCOPE`、`REQUIRE_CAPABILITY`、`REQUIRE_APPROVAL`。每类由一个有类型Evaluator实现；配置只提供合法参数。公式采用注册表中的固定FormulaId，不让门店编辑表达式。

金额用`long`最小币种单位，比例用0～10000基点，数量用BigDecimal；乘法中间值用BigInteger/BigDecimal，明确舍入，最终精确转换并检查溢出。币种精度由原币种定义提供。前端金额须在JSON安全整数及业务金额上限内，禁止先丢精度再转BigInt。

对本次获准**未付范围**，先完成商品价格、优惠及费用，再预估税额供资金计划；储值计划、积分和最后获准抹零确定后，按原税务分类进行最终定税。已付/UNKNOWN份额在范围解析阶段排除并冻结，不能重复扣款。

```text
商品价格路径 = 原基价＋获准纠错 → 合法活动/人工促销，或先金额券后折扣券
预估含税金额R0 = 按地区政策对商品/费用逐行加税或拆税后的合计
S = 用户明确选择且资金权威认可的储值计划（不是销售优惠）
积分、抹零 = 在R0及S约束下按获准顺序和金额量纲确定的实际调整
最终行金额 = 应用获准降价调整后，按税类/发票行最终确认net/tax/gross
invoiceGross = Σ最终行net＋Σ最终行tax
customerDue = invoiceGross－经核验的积分/券等对价抵付（不是再减一次降价）
customerDue = S＋其他新支付计划＋现金应收＋尚未安排金额
现金找零 = 现金实收－现金应收（现金足额时）；不影响应收或税额
```

积分/券若为降价，已进入最终行金额；若为原权威认可的对价抵付，单独保存并减少customerDue，不重复减税基。不能在税额固定后对所有积分/抹零简单减一次了事。`receivable_minor`明确承接invoiceGross，补充customerDue及对价抵付列；历史例子未涉及税或特殊对价时数值可相同。完整阶段、精确公式及8个算例见[税费补充§2.3～2.6](./TDD-统一试算引擎-初始规则与结账税费.md)。

价外税标准5%示例：未税200，获准未税金额券20后九折，净额162、税8.10、含税170.10；有折扣券，不可储值/人工抹零。若原价格已含税，不再加5%，按162×5/105拆税。两种口径必须从真实源价格及券合同确定，不能由前端切换。

S先确定，积分及人工抹零不得侵入已冻结资金计划。人工扣减归入人工促销，默认沿人工打折互斥；真实价格纠错有依据，不能伪装成纠错绕过优惠。未知税类、税点或税务调整性质均阻止相应确认。上述为设计，不代表餐饮储值混合支付已支持。

### 2.6 多券计划与提供方合同

- `EXPLICIT`：列出的每张券都要求真实使用；有券无合法分摊时整份请求失败，不偷偷降级为单券。
- `CANDIDATE_POOL/AUTO_BEST`：允许选择子集。优先满足合法组合和资金约束，再按受控目标择优，稳定业务身份打破同价并列。
- `DISJOINT`：按源计价项/数量份额判断重叠，不按SKU名称判断；根账单累计次数不能因AA、拼桌或多次quote重置。
- `BEST_ONE`：每项择一，同时满足每单券数；未用券不核销。
- `STACK`：默认关闭；需明确次数、顺序、门槛基数、封顶、原分摊算法和提供方确认能力。逐张计算，禁止直接乘比例后只舍入一次。
- 枚举候选设置可公开的数量/复杂度限制；超过限制返回`PLAN_SEARCH_LIMIT`或明确退回手选，不能截断搜索后声称全局最优。
- 原提供方返回的`ownerQuoteRef/basisHash/algorithmVersion/allocations`纳入冻结证据。确认不接受“引擎算162、券权威核160”的组合；不一致返回差异，必须重新报价确认。

### 2.7 Java代码清单与类关系

沿现有包规范；下表中**包名＋类名**构成完整类名。枚举、record按真实复杂度合并，不为每个字段生成Service。下列MoneyMinor、PricingFacts、PricingUnit及SPI的PricingSourceRef已由CKO-02实现；PricingConfigCompiler、EffectivePricingConfig、RuleDefinition及编译辅助对象已由CKO-03实现；其余新类仍是目标，不代表已交付。

| 模块 | 完整包名 | 类名及关键方法 |
|---|---|---|
| shop-base | `ai.neargo.shop.common.pricing` | `PricingEngine#calculate(PricingFacts, EffectivePricingConfig, BenefitSelection)` |
| shop-base | `ai.neargo.shop.common.pricing` | `PricingConfigCompiler#compile(template, override, systemConstraints, capabilities)`；输出配置/问题，不进行IO |
| shop-base | `ai.neargo.shop.common.pricing` | `PricingRuleEngine#validate(facts, config, selection)`；汇总可解释冲突 |
| shop-base | `ai.neargo.shop.common.pricing` | `RuleEvaluator#type(), evaluate(context, definition)`；仅有限注册实现 |
| shop-base | `ai.neargo.shop.common.pricing` | `BenefitPlanSelector#select(context, selection)`；唯一合法候选或明确失败 |
| shop-base | `ai.neargo.shop.common.pricing` | `FormulaRegistry#resolve(formulaId, version)`；`PricingFormula#calculate(context, plan)` |
| shop-base | `ai.neargo.shop.common.pricing` | `PricingInvariantChecker#check(result)`；金额、份额、资产数量守恒 |
| shop-base | `ai.neargo.shop.common.pricing` | `CashTenderPolicy#calculate(cashDue, tender)`；`PricingHash#facts(), economics()` |
| shop-base | `ai.neargo.shop.common.pricing` | records：`MoneyMinor`、`PricingFacts`、`PricingUnit`、`EffectivePricingConfig`、`RuleDefinition`、`RuleViolation`、`BenefitSelection`、`CalculationPlan`、`PricingResult`、`PriceAllocation`；均无Spring/Mapper依赖 |
| shop-core | `ai.neargo.shop.trade.service` | `PricingTemplateService#getDraft(), saveDraft(), preview(), publish(), getRevision()` |
| shop-core | `ai.neargo.shop.trade.service` | `StorePricingConfigService#get(), saveDraft(), resetOverrides(), preview(), publish(), upgradeTemplate(), rollback()` |
| shop-core | `ai.neargo.shop.trade.service` | `OrderPricingService#quote(), getQuote(), revalidate(), confirm()` |
| shop-core | `ai.neargo.shop.trade.service.impl` | 对应`PricingTemplateServiceImpl`、`StorePricingConfigServiceImpl`、`OrderPricingServiceImpl`；直接调用本域Mapper和跨域Port |
| shop-core | `ai.neargo.shop.trade.policy` | `PricingAdjustmentPolicy#validate()`；`PricingRevalidator#compare()`；`PricingSourceResolver#resolve()`；实现来源/权限/历史分支，不在Controller计算 |
| shop-core | `ai.neargo.shop.trade.assembler` | `PricingQuoteAssembler#toEntities(), toVO()`；不重新实现金额算法 |
| shop-core | `ai.neargo.shop.trade.entity` | 前述7个表Entity，继承BaseEntity |
| shop-core | `ai.neargo.shop.trade.mapper` | 在现有`TradeMappers`新增`PricingTemplateMapper`、`PricingTemplateRevisionMapper`、`StorePricingConfigMapper`、`StorePricingRevisionMapper`、`PriceQuoteMapper`、`PriceQuoteLineMapper`、`PriceQuoteAllocationMapper` |
| shop-core | `ai.neargo.shop.trade.dto` | `SavePricingTemplateReq`、`SaveStorePricingConfigReq`、`PublishPricingConfigReq`、`PricingConfigVO`、`PricingConfigPreviewVO`；报价跨域DTO不复制一套等义类型 |
| shop-core | `ai.neargo.shop.trade.api.ops` | `OpsPricingTemplateController#getDraft(), saveDraft(), preview(), publish(), getRevision()` |
| shop-core | `ai.neargo.shop.trade.api.biz` | `BizPricingConfigController#get(), saveDraft(), resetOverrides(), preview(), publish(), upgradeTemplate(), rollback()` |
| shop-core | `ai.neargo.shop.trade.api.internal` | `InternalPricingController#quote(), getQuote(), revalidate()`；资金确认仍走原获准业务意图入口 |
| shop-base | `ai.neargo.shop.spi.trade` | `OrderPricingPort#quote(), getQuote(), revalidate()`；提供方实现`trade.port.OrderPricingPortImpl`；远程实现置原通道adapter/port |
| shop-base | `ai.neargo.shop.spi.trade` | `PricingSourceFactsPort#resolve(), validateVersion()`；源系统实现其自有来源，不允许任意指定Java类 |
| shop-base | `ai.neargo.shop.spi.trade` | 合同records：`PricingSourceRef`、`QuotePricingReq`、`RevalidatePricingReq`、`PricingQuoteVO`、`PricingDiffVO`；无core/food Entity引用 |
| food-api | `ai.neargo.shop.food.service` | `DiningPricingService#quoteCart(), quoteBill(), getQuote(), revalidate()`；行业鉴权与结账范围桥接 |
| food-core | `ai.neargo.shop.food.service.impl` | `DiningPricingServiceImpl`，调用OrderPricingPort，不直接用TradeMappers |
| food-core | `ai.neargo.shop.food.port` | `FoodPricingSourceFactsPortImpl#resolve(), validateVersion()`；解析餐牌、配置、套餐、配料及原根账单身份 |
| food-api | `ai.neargo.shop.food.dto` | `QuoteCartReq`、`QuoteBillReq`、`RevalidateDiningQuoteReq`；复用跨域报价VO，必要时只增加展示包装 |
| food-web | `ai.neargo.shop.food.api.biz/mp` | `BizPricingQuoteController`、`MpPricingQuoteController`；C端只报价，不发布配置/提交员工调整 |

零售适配先包原quote/订单确认逻辑，用`LEGACY_OWNER_COMPAT`明确标记；美业适配在其来源Port实现中提供服务/权益事实，不引用food。独立部署经来源系统注册的typed客户端加载事实；不把事实加载做成客户端可指定URL的通用回调。

**主要调用关系：** Controller → ServiceImpl → 来源/优惠/资金Port取事实 → ConfigCompiler → RuleEngine → PlanSelector → Formula → InvariantChecker → 本域Mapper落快照。正式确认从原意图入口调用`OrderPricingService#confirm`，不是让C端直接调用无边界的扣款接口。

### 2.8 Service / Mapper关键合同

```java
// 设计签名；类型归属以上表为准，非可直接编译的交付代码。
PricingConfigPreviewVO preview(PricingAccessContext context, String configNo);
PricingConfigVO publish(PricingAccessContext context, String configNo,
        PublishPricingConfigReq req, String idempotencyKey);
PricingQuoteVO quote(PricingAccessContext context, QuotePricingReq req);
PricingDiffVO revalidate(PricingAccessContext context, RevalidatePricingReq req);
PricingConfirmation confirm(PricingConfirmationContext context,
        String durableIntentNo, String quoteNo, String expectedEconomicsHash);
```

`PricingAccessContext`/`PricingConfirmationContext`为`trade.service`内部可信record，Controller/获准业务入口构建，不反序列化为客户端输入。`PricingConfirmation`为内部结果，不向消费者泄漏资金owner细节。基础设施跨域记录另走spi，不将这些本域内部类型放进公共Port。

| Mapper | 关键方法 | 约束 |
|---|---|---|
| PricingTemplateMapper | `findByNo()`、`lockByNo()`、`updateDraftCas()`、`switchActiveRevisionCas()` | WHERE包含tenant、业务键、预期版本和状态 |
| PricingTemplateRevisionMapper | `insert()`、`findByNo()`、`listByTemplate()` | 业务层禁止update/archive |
| StorePricingConfigMapper | `findByStore()`、`lockByNo()`、`updateDraftCas()`、`switchActiveRevisionCas()` | 再包含entity/store归属；插入竞争用唯一约束 |
| StorePricingRevisionMapper | `insert()`、`findByNo()`、`listByConfig()` | 原版本不可变，不能覆写effective_snapshot |
| PriceQuoteMapper | `insert()`、`findAuthorizedByNo()`、`markExpiredCas()`、`markSupersededCas()` | 不提供通用更新金额方法 |
| PriceQuoteLineMapper | `insert()`/受控批量插入、`listByQuote()` | 同报价头事务，不允许成功头缺行 |
| PriceQuoteAllocationMapper | `insert()`/受控批量插入、`listByQuote()`、`listByBenefit()` | 同事务；全部查询有范围约束 |

BaseMapper技术上暴露通用写方法不等于业务许可；服务只使用白名单操作，补架构/集成测试禁止发布版本被修改。数据域按既有机制处理，只在验证可信范围后使用必要的局部scope适配，禁止全局关闭数据域防线。

### 2.9 API与输入输出

以下为**候选路由**；实施时与餐饮原API041/131等canonical条目对齐，扩展原资源、不重复注册等义接口。全端沿现有返回封装、业务异常、国际化及鉴权风格。

| 端 | 方法与候选路径 | 输入 | 输出 |
|---|---|---|---|
| 平台运营 | GET/PUT `/ops/x/trade/pricing-templates/{templateNo}/draft` | PUT：expectedDraftVersion、schemaVersion、definition | templateNo、draftVersion、activeRevisionNo、definition |
| 平台运营 | POST `.../{templateNo}/preview`、`.../{templateNo}/publish` | preview：草稿版本；publish：见下述公共发布参数 | 编译差异/问题；新revisionNo/hash |
| B端/云店管理 | GET/PUT `/biz/x/trade/stores/{storeNo}/pricing-config` | PUT：templateRevisionNo、expectedDraftVersion、override | configNo、草稿、锁定模板、当前版本、可改字段 |
| B端/云店管理 | POST `.../pricing-config/reset-overrides` | expectedDraftVersion、resetPaths | 新草稿及继承来源 |
| B端/云店管理 | POST `.../pricing-config/preview`、`.../publish` | 草稿版本；公共发布参数 | 差异、能力门禁；新版本 |
| B端/云店管理 | POST `.../pricing-config/upgrade-template`、`.../rollback` | 目标templateRevisionNo或历史revisionNo、预期头版本、原因 | 生成新草稿或新发布版本；升级不会自动激活 |
| 收银/服务员 | POST `/biz/x/food/carts/{cartNo}/quote` | sourceVersion、benefitSelection、fundingIntent、adjustments、cashTender | PricingQuoteVO |
| 收银/服务员 | POST `/biz/x/food/checks/{checkNo}/quote` | scopeRef、scopeVersion及上述意图；原根账单不能由客户端重置 | PricingQuoteVO |
| C端/H5/扫码 | POST `/mp/x/food/carts/{cartNo}/quote` | sourceVersion、本人候选券/资金意图；不含员工调整 | 脱敏PricingQuoteVO |
| B/C | GET 各自前缀`/food/quotes/{quoteNo}`；POST `.../{quoteNo}/revalidate` | revalidate：sourceVersion、expectedEconomicsHash、scopeVersion | 原报价；UNCHANGED/CHANGED/BLOCKED及受控差异 |
| 内部 | POST `/internal/x/trade/pricing/quotes`及`.../{quoteNo}/revalidate` | 认证调用系统＋typed来源引用＋同合同意图 | 相同内核结果；调用系统不能自报权威单价 |

公共发布参数：`expectedDraftVersion:long, expectedActiveRevisionNo:string|null, previewHash:string, reason:string`，发布带`Idempotency-Key`；创建配置、升级/回滚等写操作同样幂等。具体新增权限码在实施阶段登记到原权限目录及三端矩阵；配置发布、人工打折、人工抹零需分权限校验，不能因已有ORDER_VIEW而授权发布。不预先伪造当前不存在的权限常量。

**QuotePricingReq**：

- `sourceRef:{system,type,no,version,scopeRef?,scopeVersion?}`：系统解析归属与消费者，不接受body里的tenant/entity/行业覆盖真实身份。
- `benefitSelection:{mode:EXPLICIT|CANDIDATE_POOL|AUTO_BEST, couponNos:[], activityRefs:[]}`：有数量上限，原owner核验资产持有人与资格。
- `fundingIntent:{storedValue:{enabled,requestedMinor?,authorizationRef?}, points:{enabled,requestedUnits?}}`：只是意图，余额/换算由owner返回；卡号/密码不在此传输和持久化。
- `adjustments:[{kind,sourceLineNo?,amountMinor?,payableRateBp?,reasonCode,reasonText?,approvalRef?}]`：B端专属；身份从登录态得出，审批需验证操作者、范围、额度、失效时间。
- `cashTender:{amountMinor,currency}?`：仅现金场景；不提供客户端规则JSON、单价、积分换算率或任意券折扣值。

**PricingQuoteVO**：

- `quoteNo, sourceVersion, scopeVersion, expiresAt, currency, engineSpecVersion, configRevisionNo, configHash, economicsHash`。
- `summary:{goodsMinor,priceAdjustmentMinor,activityDiscountMinor,couponDiscountMinor,manualDiscountMinor,pointsDiscountMinor,waiverMinor,feeMinor,taxMinor,receivableMinor}`。
- `funding:{storedPlannedMinor,otherPlannedMinor,remainingDueMinor,cashDueMinor,cashTenderMinor?,cashChangeMinor?}`；计划与支付成功严格分开。
- `lines:[{sourceLineNo,componentRef,quantity,grossMinor,discountMinor,receivableMinor}]`及按授权可见的`allocations`。
- `usedBenefits, unusedCandidates:[{ref,reasonCode}], violations:[{ruleNo,code,field,relatedRefs}], confirmable, blockedReasons`。`confirmable`仍不表示已获最终扣款授权。

**PricingDiffVO**：`status, originalQuoteNo, replacementQuoteNo?, changes:[{field,oldValue,newValue,reasonCode}], requiresAcknowledgement`。金额、券身份、覆盖项、支付路径、授权变化均是商业差异；不能仅比较总价相同就自动确认。指纹不替代权限、资产有效期或真实源版本校验。

C端不提供配置发布API，也不能仅凭quoteNo读取别人报价。平台支持排障只读也需要数据权限；日志/VO不暴露储值本金赠金明细、审批敏感信息或原owner令牌。云店沿当前B域鉴权，不再复制一套核心规则Service。

### 2.10 报价与正式确认时序

```text
报价：鉴权 → 读源/根账单范围 → 读已发布配置 → 取原owner能力/资格与报价
     → 内核校验并计算 → 再验源版本 → 短事务写报价头/行/分摊 → 返回
确认：原结账意图幂等受理 → 冻结获准未付范围
     → 重验源、配置策略、券/资金资格、审批及原owner经济计划
     → 调同一内核 → 比较经济指纹/商业差异
     → 有变化生成新quote并等用户确认；不以旧金额继续扣款
     → 无变化将quote绑定原durableIntent，走原owner prepare/commit/query及原PAY/库存协议
```

- 报价前后源版本变化则重试受限次数或返回SOURCE_CHANGED；不能把两次读取混为同一事实。报价本身不预占券、余额或库存。
- 正式确认锁定/预留源与资产后的校验和执行要绑定同一事实/计划。远端资源按原协议版本校验或预占，禁止校验通过后又无条件使用变化后的资产。
- 当前购物车确认前若门店发布新配置，按策略重新报价；已冻结订单/结账意图按原锁定版本核对，不能新配置追改历史价格。
- `OrderPricingService#confirm`是原业务编排调用的能力，不是新资金账本。durableIntentNo、原SourceRef和原核销键稳定；**quoteNo不能充当每次都不同的扣款源身份**。
- 外部调用不包在长数据库事务里；本地意图/outbox承载重试。任何UNKNOWN保持原键查回，结果未明不能释放后换键再扣。
- AA、转台、并桌先校验原根范围份额、历史券累计使用和已付/UNKNOWN状态；缺少原协议就阻止该范围确认，不能用quote3表自行替代范围锁。
- 退款继续走原退款能力，读取原报价、优惠资方、支付分摊；不按当前菜单或配置重算。美业现阶段不因本设计增加未具备的在线储值冲正入口。

### 2.11 分阶段执行及完成门禁

| 阶段 | 交付 | 完成条件 |
|---|---|---|
| E0 合同冻结 | 对齐原API编号、7表字段、配置schema、权限/错误词条；实测owner组合合同 | 明确LEGACY、新策略、BLOCKED来源；迁移编号实施时从最新仓库分配 |
| E1 纯引擎 | 固定金额、编译器、12条共同约束、有限Evaluator、公式及向量 | 同输入确定性；互斥、溢出、分摊守恒、多券边界单测通过 |
| E2 配置发布 | 4表增量迁移、Ops模板、B店铺草稿/预览/发布/回滚 | 并发CAS、幂等、越权、版本锁定、坏配置拒绝集成测试通过 |
| E3 餐饮报价 | 3表、Food事实适配、原券/活动桥接、B/C报价API | 先普通菜/配料/套餐报价；前端无法伪价，报价无核销副作用 |
| E4 订单确认 | 下单、先付/后付冻结、人工调整、现金找零、原资产/库存意图接线 | 重算差异、双击、并发AA/拼桌、失败恢复及UNKNOWN查回通过 |
| E5 扩展资金 | 仅在typed合同验收后接餐饮储值、积分、多金额券/叠折 | 当前全额储值限制不被绕过；不具备能力维持BLOCKED |
| E6 多行业接入 | 零售影子对比与显式迁移，美业原服务来源适配 | 存量legacy金额/资方不变；美业全额及来源幂等约束通过 |
| E7 前端估算 | 脱敏CalculationSpec、TS定点实现及同版本向量 | 仅本地估算；Java正式确认权威不变 |

先完成E0，再从E1纯内核推进。E1～E4不以储值混合支付已完成作为前提；资金门禁允许基础业务交付，但不得向用户展示未能兑现的支付能力。各阶段完成后同步canonical数据库/API/类清单，不一次替换全零售计算。

## §3 选型记录

| 选择 | 理由 |
|---|---|
| 4个配置表，而非sys_setting一行 | 模板与门店分别有草稿CAS、锁版、不可变发布证据；7表中没有逐规则/逐开关表膨胀 |
| 类型化JSON，而非每条规则一张表 | 字段扩展不改变所有表；发布时编译发现冲突，核心唯一/并发关系仍在列及索引中 |
| 固定规则注册表与公式版本，而非通用脚本平台 | 当前规则是有限业务约束，便于解释、测试及未来两端一致；降低配置越权面 |
| 原common/spi/trade分层，而非新建一套餐饮订单引擎 | 兼容原项目；独立food仍通过原合同调用，不复制核心权威 |

以上是既有共享权威方案的细化；若未来引入独立配置服务或替换原资金权威，应另立ADR，而不是在本设计中暗含部署变更。

## L4 边界、验证及交付状态

## §4 风险与门禁

| 风险 | 处理 |
|---|---|
| 原券只支持旧顺序/单券 | 旧档案保留；新组合没有owner证据即BLOCKED |
| 多种可调上限相互矛盾 | 发布编译检查；按所有上限共同约束，不用某一上限覆盖其他上限 |
| 积分/抹零挤占已计划储值 | 依固定阶段及合资格剩余校验；不自动扩大或减少已授权储值 |
| 配置/金额未变但资产或范围变化 | 事实版本及商业差异同验；总价相同不是放行依据 |
| 快照包含敏感信息或过大 | 有类型schema/大小限制、最小化快照、owner证据引用、分权限读取 |
| 多端离线 | 本轮后端试算需要联网；未来本地估算不自动授权离线核销或支付 |

## §5 实现 → 需求验证计划

以下均是**拟新增测试**，本轮未实现或运行Java业务测试。

| AC | 测试类/方法（目标） | 核心断言 |
|---|---|---|
| P01 | PricingConfigServiceTest#publishPinsTemplateAndKeepsOldRevision | 模板升级不改变旧门店；false/null/reset及数组语义 |
| P01 | PricingConfigServiceTest#concurrentPublishHasOneWinner | CAS失败无孤立revision/outbox；相同幂等键不同body拒绝 |
| P02 | PricingRuleEngineTest#denyCannotBeOverriddenByAllow | 活动+券、折扣券+储值、券+人工抹零均拒绝；默认1+1可用仍验owner |
| P03 | BenefitPlanSelectorTest#explicitCannotDropCoupon | 显式两券不静默变一券，候选未使用不核销 |
| P03 | BenefitPlanSelectorTest#countsAssetsAndChecksQuantityOverlap | 同券多分摊只算1张；同SKU不同份额正确；同份额DISJOINT拒绝 |
| P03 | BenefitPlanSelectorTest#stackUsesFrozenOwnerOrder | 100×0.8×0.9=72，但逐券封顶/舍入保留；无能力拒绝 |
| P04 | PricingEngineTest#deductThenDiscountAndSeparateFunding | (200-20)×0.9=162；储值不减营业应收；积分后抹零；找零独立 |
| P04 | PricingInvariantTest#conservesEveryMinorAndRejectsOverflow | 逐阶段/逐行/逐资产/资方守恒；数量及比例精度、币种/负数/溢出 |
| P05 | PricingConfirmationTest#sameAmountDifferentBenefitNeedsAck | 总额相同但券身份不同仍要求确认；历史冻结不读新菜单 |
| P06 | PricingAuthorizationTest#consumerCannotSubmitStaffAdjustment | 越权门店、伪审批、超额度/过期原因拒绝；quoteNo不能跨身份读取 |
| P07 | PricingRecoveryTest#unknownUsesOriginalIntentAndScope | 重试不换源键；双重确认/AA拼桌不重复占款/优惠 |
| P08 | PricingCompatibilityTest#legacyOwnerAllocationUnchanged | 原零售舍入/资方不变；三行业相同规范输入结果一致 |
| P09 | TaxEngineTest#inclusiveExclusiveAndMixedClasses等，见税费补充§5 | 初始数据、结账阶段、地区含税/价外、积分税分类及原税分摊 |

实施阶段须使用真实数据库事务验证唯一键/CAS/回滚，不能仅内存Mock。资金恢复验证原owner失败及UNKNOWN分支；关键规则做消融验证。Java21离线Maven、ArchitectureTest、Controller资源内聚及后端三语错误码检查按实际修改模块执行，不能以文档检查替代。

## §6 本轮产物与对账

本实施方案复用既有规则目录与SVG；后续新增税费补充、详细种子及PRICING_RULE_SPEC_V4，完善P09和含税/对价口径。后续CKO-01完成合同核验，CKO-02/03实现共享值对象与配置编译，详见前述收据；未创建数据库表、未发布门店配置或开放报价/资金接口。下一步CKO-04运行期规则校验；schema/API/权限在对应应用任务实施时登记并以真实测试证据回填。

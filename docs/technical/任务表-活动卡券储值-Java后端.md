# 活动卡券储值 Java 后端独立任务表

> 执行方式：沿用当前会话内实施，按任务使用 superpowers:executing-plans；每项完成后更新本表。本表是原TDD §2的独立执行台账，不另起一套架构。

状态：执行基线已整理，Java后端优先 · 2026-10-18 · 基准提交64a25a5bd。

目标：完成既定活动、卡券、储值和次卡/月卡的全部Java后端，包括数据库增量迁移、领域服务、内部协议、本地/远程适配、B/C/运营HTTP接口、权限、通知、兼容迁移工具及验收。前端页面和UI原型后置；接口契约、经营规则和权限不能因前端后置而省略。

架构：core拥有活动规则、资格、营销预算、商品、订单和本人授权；pay拥有权益定义、发行、使用和储值资金账。通过共享SPI、分阶段持久决策及Outbox协调，不建立跨库事务。

技术基线：Java 21、既有Spring/MyBatis-Plus/Flyway、离线Maven、既有MySQL兼容约束；不新增模块级依赖版本，不借本轮重构全仓。

## 1. 依据、完成口径与优先级

| 真源 | 本表使用方式 |
|---|---|
| [总设计及原D01–D07计划](./TDD-活动卡券储值-模块化系统设计.md) | 保留原任务归属，本表将其拆成可独立验证的Java任务 |
| [数据库设计](./TDD-活动卡券储值-数据库设计.md)及core/pay设计DDL | 基础38张及支撑6张目标逻辑表逐一指定迁移负责人；目标表不等于已建生产表 |
| [API契约](./TDD-活动卡券储值-API契约.md) | 48条外部、12项内部写操作逐一登记，另补内部恢复query |
| [Java代码结构与类清单](./TDD-活动卡券储值-代码结构与类清单.md) | 类/接口/Controller/实体Mapper落到任务；已实现项以源码及提交核实 |
| [U01–U30功能矩阵](../requirements/活动卡券储值-模块功能矩阵.md) | 后端验收逐项关联；40AP和6EP目录不冒充已具备执行能力 |
| [仓库任务规范](../../.claude/skills/ai-shop-dev-standards/SKILL.md)、[Java规范](../../.claude/skills/ai-shop-dev-standards/references/java.md) | TDD三处对账、RED→GREEN、关键消融、真实数据库与提交证据 |

状态只用TODO/DOING/BLOCKED/DONE。DONE要求本项全部交付、提交号及真实验证证据；父阶段未满足全部条件保持未完成。现有类/旧接口可复用，不能因此将新的目标契约记为DONE。

本表共55项：14项DONE、0项DOING、41项TODO，暂无BLOCKED；这是拆分后的交付单元计数，不等同工作量比例。新增范围预检已运行Java业务测试，历史全量证据与本批定向结果分别列在末尾。

优先顺序：JB-008–JB-014补齐后端契约与可信事实；发行/使用及core活动底座随后实施；再完成储值、付费卡、HTTP和远程适配，最后兼容、恢复、权限及后端整体收口。每个领域的Controller可随服务同步开发，DONE需本地和远程两种模式验收。生产操作另列，不阻塞可独立开展的代码开发。

### 1.1 全局约束

- 商家收款，平台管理账户；不实现平台资金托管或自动结算。
- 新权益Mapper放pay/mapper/entitlement或pay/mapper/storedvalue，绑定paySqlSessionFactory；写服务显式payTxManager，posting不使用REQUIRES_NEW。
- 跨域只经shop-base SPI；core不读pay实体/Mapper，pay不查core业务库；远程调用不放进持锁事务。
- 所有财务/资源写入持久幂等；短期身份凭证按需新签发，不缓存原文或产生财务效果；同键异参冲突、同源不重发/重扣。原生MySQL重复插入加锁问题沿用已验证的排他claim方案。
- 统一会员码；P30兑换券短期码保留用途限制，不创建储值专用码。C DTO/通知不展示本金赠金拆分；无储值或新付费卡线上退款。
- C门店目录默认仅成功储值历史门店，以最后成功充值/核销的最大时间倒序；余额耗尽仍保留，同时间有稳定次级排序。
- 模板/活动版本不可变；COUNT、PERIOD_COUNT、PERIOD_DAILY共用规则，服务日/时区/自然月与滚动天数明确；无无限次、自动续费。
- 每次迁移重新检查版本占用；不修改已应用脚本，沿主应用统一Flyway执行，新表version/deleted/UTC/actor对齐。

### 1.2 重点失败场景

| 风险输入/条件 | 必须验证的行为 | 责任任务 |
|---|---|---|
| 充值实收和取消并发，响应丢失 | 唯一终态、原充值号可查，不再收款/不重复到账 | JB-034/JB-035/JB-041 |
| 首次用卡并发、跨日/月底、预约服务日 | 只激活一次，额度按实际服务日，释放回原bucket | JB-019/JB-020/JB-042 |
| 相同业务号重复/异参、远程超时重投 | 重放原响应或冲突；未知不释放、不换号重扣 | JB-013/JB-016/JB-020/JB-051 |
| 伪造身份/门店/项目、旧凭证重放 | 可信会话和源决策校验，拒绝越权，日志无明码 | JB-009/JB-010/JB-011/JB-053 |
| 旧券同号/乱序事件/迁移中旧写入 | 精确owner唯一写者、排序不倒退，导入不再发放 | JB-037/JB-048/JB-049/JB-050 |

## 2. 独立开发任务表

JB编号在本表固定；原D编号保留用于对账。依赖栏省略JB前缀。任务内容中Service/Impl配对遵循类清单，纯策略/record不制造空接口。

### 2.1 已完成，复用不重写

| ID | 原任务 | 状态 | 依赖 | 模块 | 交付内容/Java类 | 验收条件/测试 |
|---|---|---|---|---|---|---|
| JB-001 | D01.1 | DONE | — | 盘点 | 旧mkt/pmt券读写、订单事件、数据源与部署盘点 | d0b199bb6；生产存量核查未包含 |
| JB-002 | D01.2子项 | DONE | 001 | shop-base | SourceRef来源身份与稳定键 | 97dc22b22；SourceRefTest 4项，已消融 |
| JB-003 | D02规则子项 | DONE | 001 | pay.entitlement | UsageQuota.Count/Daily纯计算 | 97dc22b22；UsageQuotaTest 5项；尚非数据库扣次 |
| JB-004 | D02.1子项 | DONE | 001 | pay/迁移 | EntTemplate、EntTemplateVersion、PayEntCommandDedup及三个Mapper；V425 | 9ab17b5a1；原生DDL、JSON、审计/扫描归属验证 |
| JB-005 | D02.2子项 | DONE | 002,004 | pay.entitlement | EntitlementCatalogService/Impl、EntitlementRuleValidator、CatalogScope/CatalogViews | 9ab17b5a1、815c12c2b；目录12项及真实MySQL并发/回滚通过 |
| JB-006 | D01.2子项 | DONE | 005 | shop-base/pay | EntitlementDefinition、EntitlementCatalogPort/Impl本地实现 | 9ab17b5a1；主体/币种/用途/暂停/历史校验；远程未完成 |
| JB-007 | D01.4子项 | DONE | 005 | 构建/验证 | 编译闸门变量修复、共享履约夹具污染修复、原生MySQL运行器 | 97dc22b22、9a124c7dc、815c12c2b；20模块3526项0失败，原生另跑 |

### 2.2 后端契约、可信事实与公共基础

| ID | 原任务 | 状态 | 依赖 | 模块 | 交付内容/Java类 | 验收条件/测试 |
|---|---|---|---|---|---|---|
| JB-008 | D01.2/1.3/1.4 | DONE | 001,006,007 | shop-base/契约 | 冻结所有SPI/命令/响应/query、错误码三语、权限与能力开关；补缺失DDL设计 | f16cfff0d；Contract10/Preview7；门禁10+目录关闭2；总定向88项、原生MySQL1项及create门禁消融通过；API§11–13/支撑5表已冻结，HTTP/角色接线仍归后续任务 |
| JB-009 | D01.2 | DONE | 006 | core/app | core范围事实预检：门店/商品归属、类目有效性；可信CatalogScope构造 | c29c4fe4a；EntitlementScopeValidationTest 7项：会话身份、门店子集、跨租户门店/商品、赠品归属及201条分批；门店子集消融RED→恢复GREEN；HTTP仍归025 |
| JB-010 | D01.3 | DONE | 008,009 | core.member/app | MemberCodeResolveService/Impl、BizMemberCodeController、MpMemberCodeController；C08/C11 | 51d10402b/6156975bb/ecfda1ebd；身份7+HTTP5，目标门店借权及actor消融RED→GREEN；定向53/standalone12通过；干净3582项、原生DDL通过 |
| JB-011 | D01.3 | DONE | 008,010 | core.trade/app | EntitlementAuthorizationService/Impl、MpEntitlementAuthorizationController、EntitlementSourceDecisionPortImpl；V427持久本人意图与源决策 | EntitlementAuthorizationPersistenceFlowTest：改价失效、他人拒绝、重复确认同意图、支付/取消唯一决策；重建服务后持久查询；78f430311/f116a02ab，干净3590项全绿 |
| JB-012 | D01.3/D06.2 | DONE | 008,011 | core.trade | CardSaleSnapshotPlan、CardSaleSnapshotService/Impl；按订单行/单件/件内权益、模板版本生成不可变售卡快照 | 111856a96 + cb0bf9bf0；CardSaleSnapshotTest 7项；clean test 27模块3598项0失败/错误；V425/V427/V428原生MySQL检查3项0跳过；详见JB-012验收记录 |
| JB-013 | D02.1 | DONE | 005 | pay.entitlement | 提取EntitlementCommandDeduplicator，目录切到唯一共用实现 | EntitlementCommandDeduplicatorTest：同键同参重放、异参冲突、大小写区分、原生并发、业务异常不留PENDING |
| JB-014 | D02.1/D04.2 | DONE | 008,013 | pay事件/core适配 | PayEntitlementEventPublisher、PayEntitlementOutboxRelay、PayEntitlementInboxProcessor；pay事件表提交后桥接既有sys_outbox | `EntitlementEventFlowTest`4项；共享OutboxEventBus稳定eventNo测试；隔离MySQL4项通过：回滚、重放/冲突、恢复、inbox去重/冲突；完整验收记录见本表末 |

### 2.3 支付权益发行、使用、查询及HTTP

| ID | 原任务 | 状态 | 依赖 | 模块 | 交付内容/Java类 | 验收条件/测试 |
|---|---|---|---|---|---|---|
| JB-015 | D02.1/2.2 | TODO | 005,013,014 | pay.entitlement | EntitlementIssuePolicyService/Impl；发行总量/敞口/个人容量条件更新 | EntitlementIssuePolicyFlowTest：100并发不超限；新上限不得低于held+issued；关闭仅挡新增；原生MySQL验证 |
| JB-016 | D02.3 | TODO | 002,014,015,019 | pay.entitlement | EntitlementGrantService/Impl、GrantPortImpl、EntitlementPostingService；prepare/commit/abort/query | EntitlementGrantFlowTest：来源唯一、重复确认同实例、付费卡缺付款事实拒绝、承诺/取消互斥；账与事件原子 |
| JB-017 | D02.3 | TODO | 016 | pay.entitlement | EntitlementIssueBatchService/Impl；P07/P08底层能力，受众快照预检 | EntitlementIssueBatchFlowTest：容量不足不半批超发、逐项跳过原因、重启续发不重发；任意外部用户列表拒绝 |
| JB-018 | D02.3/D04.3 | TODO | 016,032 | pay/app | 本人领取路径、CENTER/ACTIVITY校验、core资格到pay发放协调 | EntitlementClaimFlowTest：未登录/越权拒绝；付费卡不可免费领；未知结果只查原grant |
| JB-019 | D02.4/D06.3 | DONE | 003,005 | pay.policy | EntitlementValidityPolicy、CountUsagePolicy、PeriodCountUsagePolicy；从共享额度规则派生 | EntitlementValidityPolicyTest：支付/首用、自然月/滚动天、时区、月末/闰日、半开区间；GET无激活副作用 |
| JB-020 | D02.4 | TODO | 011,013,014,016,019 | pay.entitlement/app | EntitlementUseService/Impl、UsePortImpl、EntitlementPostingService、EntitlementOrderCoordinator；prepare/commit/release/query | EntitlementUseFlowTest：同源不双扣、确认释放互斥、并发不负数、未知不重扣、本人/订单/门店绑定；原生事务检查 |
| JB-021 | D02.4 | TODO | 008,019,020 | pay.entitlement | CouponQuotePolicy迁入既有唯一门槛/折扣/分摊算法；P15服务 | EntitlementQuoteFlowTest：先活动后券、FREE_SHIP、折扣封顶、分摊守恒；预览不冻结不扣款 |
| JB-022 | D02.4/D03.2 | TODO | 020,021 | pay.entitlement | 原使用受限reverse、EntUseReversal与旧券售后回补；I11 | EntitlementReverseFlowTest：部分售后累计不超原分配、同售后幂等；储值/新付费卡线上退款拒绝 |
| JB-023 | D02.4 | TODO | 010,016,020 | pay.entitlement | EntitlementCodeService/Impl；短期兑换券token签发/解析/失效 | EntitlementCodeFlowTest：仅STORE_EXCHANGE、本人可签、过期/重放拒绝、码不入日志；不生成储值专用码 |
| JB-024 | D02.5 | TODO | 016,020,042 | pay.entitlement | EntitlementQueryService/Impl、QueryPortImpl；本人资产/商家受控详情/总次与日额度/使用明细 | EntitlementQueryFlowTest：跨用户/主体/租户拒绝、过期历史保留、游标稳定、DTO无内部授权/资金拆分 |
| JB-025 | D02.2/2.6 | TODO | 008,009,015,041 | app HTTP | BizEntitlementTemplateController；P01–P06及管理DTO映射 | EntitlementTemplateApiTest：真实HTTP写后读、BeanValidation生效、权限逐条校验；不能直接绑定CatalogScope |
| JB-026 | D02.3/2.4 | TODO | 017,018,020,021,023,041,042 | app HTTP | BizEntitlementIssueController、BizEntitlementUseController、MpEntitlementController使用/领取部分；P07/P08/P11–P16/P30 | EntitlementUseApiTest：先看后核、本人授权、PROCESSING查询原操作；未具备能力拒绝而非假成功 |
| JB-027 | D02.5 | TODO | 024,041 | app HTTP | MpEntitlementController查询部分、B记录/详情；P09/P10/P17/P25/P26/P29 | EntitlementQueryApiTest：本人字段白名单、商家门店过滤、游标/分页、金额币种与次数分开 |

### 2.4 核心活动系统

| ID | 原任务 | 状态 | 依赖 | 模块 | 交付内容/Java类 | 验收条件/测试 |
|---|---|---|---|---|---|---|
| JB-028 | D04.1/D06.1 | TODO | 008 | core.promotion | ActivityPresetService/Impl、BizActivityPresetController；40AP+6EP目录、分类/业态、能力过滤；C01/C02 | ActivityPresetFlowTest：46条唯一稳定编号、默认禁用、缺能力不推荐发布；不创建46个Service |
| JB-029 | D04.1 | TODO | 006,008,009,028 | core.promotion | ActivityService/Impl、ActivityVersionService/Impl、ActivityRuleValidator；草稿/发布/范围/受众/储值档位 | ActivityVersionFlowTest：发布不可变、ORDER/RECHARGE隔离、档位金额服务端固定、暂停不撤销原参与 |
| JB-030 | D04.1/4.3 | TODO | 029 | core.promotion/trade | ActivityPricingService/Impl、PlatformActivityService/Impl及现有团期/赠品适配 | ActivitySceneIsolationTest：ORDER定价不混储值，现有团/期/库存/履约兼容；未实现玩法不可执行 |
| JB-031 | D04.2 | TODO | 008,011,014,016,029 | core.promotion | ActivityParticipationService/Impl、PortImpl、EligibilityPolicy；reserve/confirm/release/query | ActivityParticipationFlowTest：预算/名额/个人限购同事务，跨版本累计；未知pay状态不按TTL释放；原生并发不超卖 |
| JB-032 | D04.3 | TODO | 016,031 | app paybridge | ActivityBenefitCoordinator；core reserve→pay prepare/commit→core confirm | ActivityBenefitCoordinatorTest：失败补偿、事件重复/乱序、远程超时、原业务号恢复；营销占用不提前释放 |
| JB-033 | D04.1/4.4 | TODO | 029,030,031,041 | app HTTP | BizActivityController扩展C03–C06、OpsPromotionController活动治理 | ActivityManagementApiTest：旧URL兼容、新筛选/发布/结束、主体与运营数据域隔离 |

### 2.5 储值、付费卡和跨服务接线

| ID | 原任务 | 状态 | 依赖 | 模块 | 交付内容/Java类 | 验收条件/测试 |
|---|---|---|---|---|---|---|
| JB-034 | D05.1 | TODO | 010,011,013,014,031 | pay.storedvalue/app | StoredValueRechargeService/Impl、StoredValueRechargeCoordinator；创建/提交/实收/取消/query | StoredValueRechargeFlowTest：真实凭据/金额匹配；实收取消竞争唯一终态、重复实收不重复到账；已实收不可取消 |
| JB-035 | D05.2 | TODO | 013,014,034 | pay.storedvalue | StoredValueCreditService/Impl、StoredValuePostingService；I12账户/批次/凭证/分录同事务 | StoredValueCreditFlowTest：原充值号只到账一次、借贷平衡、内部本金赠金守恒、异常整体回滚；原生MySQL验证 |
| JB-036 | D05.3 | TODO | 011,020,035 | pay.storedvalue | StoredValueRedemptionService/Impl、StoredValueAllocationPolicy；统一使用意图的金额预占/确认/释放 | StoredValueConsumptionFlowTest：范围/过期批次校验、条件余额不超扣、释放回原批次、消费重试不重复分录；原生并发 |
| JB-037 | D05.4 | TODO | 014,035,036 | pay.storedvalue | StoredValueStoreProjector、StoredValueQueryService/Impl；记录、门店目录、总余额 | StoredValueConsumerQueryTest：只列成功储值门店、max充值/核销时间倒序、同时间稳定分页、耗尽仍保留、乱序不倒退 |
| JB-038 | D05.1–5.4 | TODO | 009,034,035,036,037,041 | app HTTP | BizStoredValueController、MpStoredValueController；P18–P24/P27/P28 | StoredValueApiTest：办理/到账/核销/本人记录闭环；无本金赠金字段、无线上退款/购买入口、统一会员码 |
| JB-039 | D06.1 | TODO | 006,009,028 | core.product/app | CardProductBindingService/Impl、BizCardProductBindingController；C07 | CardProductBindingFlowTest：只绑已发布PAID_CARD、主体/币种一致；售价沿商品SKU，旧版绑定不随模板草稿改变 |
| JB-040 | D06.2 | TODO | 012,016,039,042 | app/core.trade | CardSaleCoordinator与支付/取消Outbox订阅，按订单行/件/件内权益发卡 | PaidCardSaleFlowTest：2×2权益不漏不重发、付款/取消竞争、迟到付款起算正确；不以储值扣款冒充现金付款 |
| JB-041 | D02.6 | TODO | 008,016,017,020,021,022,023,024,031,034,035,036,037,040 | payclient/pay-svc/core内部适配 | AppService本地/Remote承载底座、PayInternalApi/Paths、内部Endpoint及core反向适配；已实现业务完整query | EntitlementInternalApiRoundTripTest：真实HTTP DTO无wrapper嵌套、签名身份、超时/失联可查、两种部署同语义且Bean互斥 |
| JB-042 | D06.3 | TODO | 019,020 | pay.entitlement | EntitlementLifecycleService/Impl、PeriodDailyUsagePolicy、ent_period_bucket；首用激活/日限/到期 | PaidCardAllowanceFlowTest：并发首用仅激活一次、指定服务日、跨日释放回原bucket、不结转、月末与时区边界 |

### 2.6 通知、运营、兼容及后端收口

| ID | 原任务 | 状态 | 依赖 | 模块 | 交付内容/Java类 | 验收条件/测试 |
|---|---|---|---|---|---|---|
| JB-043 | D05.5 | TODO | 014,020,035,040 | app/message | EntitlementNotificationSubscriber，复用MessageService与O06日志 | EntitlementNotificationFlowTest：提交后发到账/核销/异常通知，去重、失败仅重试消息、收件人校验、无内部双池/明码 |
| JB-044 | D04.4 | TODO | 014,020,029,035,040 | core.promotion/app | MarketingEffectProjector、MarketingSummaryService/Impl、BizMarketingSummaryController；C10 | MarketingSummaryFlowTest：事件幂等乱序、按币种、折扣/发行/实收/消费分项；禁止相加重复统计 |
| JB-045 | D02.5 | TODO | 014,016,020,035,036,041 | pay/app ops | EntitlementExceptionService/Impl、OpsEntitlementController；O01–O04 | EntitlementExceptionFlowTest：证据脱敏、并发恢复原grant/operation、查源不改余额、越权与版本冲突拒绝 |
| JB-046 | D02.5/D05 | TODO | 016,020,035,036 | pay.entitlement | EntitlementReconciliationService/Impl；O05，对账任务/差异结果持久化按008补设计 | EntitlementReconciliationFlowTest：发行/余次/冻结/分录各自核对，重复跑幂等、不跨量纲相加、不自动调平 |
| JB-047 | D02.5/D06.3 | TODO | 024,042 | pay/app ops | EntitlementLifecycleService冻结/解冻、OpsEntitlementController；O07及原因审计 | EntitlementFreezeFlowTest：受限权限、同请求幂等、不清余额、不绕过原预占终态/已承诺交易 |
| JB-048 | D03.1 | TODO | 004,008 | pay.migration | EntOwnerRegistry/Mapper；旧mkt/pmt逐实例owner及精确映射 | EntitlementOwnerRegistryTest：同号不同来源可区分、无owner不猜测回退、唯一写者约束 |
| JB-049 | D03.2 | TODO | 016,020,021,022,041,048 | app/旧core券 | LegacyCouponCompatibilityService、CouponPort路由及旧券Controller/服务适配 | EntitlementLegacyCompatibilityTest：旧DTO/URL/券码/报价/售后结果一致；原markUsed时点核对、已迁实例不双写双扣 |
| JB-050 | D03.1/3.3工具 | TODO | 046,048,049 | pay.migration | EntitlementMigrationService/Impl；导入、对账、断点、冻结/排空/切owner工具与离线演练 | EntitlementMigrationFlowTest：mkt/pmt样本总量/余次/冻结/流水一致、重跑幂等、导入不发新通知；不执行生产切换 |
| JB-051 | D02.5/D04.2/7 | TODO | 014,031,035,041,042,045 | core/pay jobs | ActivityReservationRecoveryJob、EntitlementRecoveryJob、EntitlementExpiryJob；持久租约与恢复 | EntitlementRecoveryFlowTest：重启/重复任务安全、未知不释放、取消确认竞争、到期与提醒去重、死信入异常 |
| JB-052 | D04/D06能力验收 | TODO | 028,030,032,035,040,041 | core/pay集成 | 40AP/6EP逐项能力映射；既有团期/赠品/积分接口复用与限制 | PresetCapabilityAcceptanceTest：逐模板证明可执行或明确缺能力；未验收关闭，付费产品无无限次/自动续费 |
| JB-053 | D01.2/D07后端 | TODO | 010,011,017,025,026,027,033,038,039,043,044,045,046,047 | API/权限/生成物 | 48条外部及内部写/query的OpenAPI、权限、三语、BeanValidation、运营五处登记总对账 | EntitlementApiContractCoverageTest：每条路由真实Handler、权限拒绝用例、DTO一致；生成物无新增失配 |
| JB-054 | D01.4/D02.6/D07.2 | TODO | 041,049,050,051,052,053 | 部署/验证 | 本地pay与独立pay后端装配、真实目标数据库版本适配、升级/回退演练 | EntitlementDeploymentAcceptanceTest：生产profile关开组合、增量升级/新库、JSON/CHECK/锁/时区/字符集；不等于部署生产 |
| JB-055 | D07.2后端收口 | TODO | 054 | 全Java后端 | U01–U30后端映射、场景链路、故障恢复、全量回归与代码审查收口 | 美业充值→到账→核销→本人明细，餐卡/游泳卡购买→发卡→扣次/日限；无未解决严重缺陷 |

### 2.7 每项统一执行清单

以下清单适用于每个TODO，不因拆表/补类而跳过。表中测试类名除已完成项外为计划名称，实施时记录最终真实方法名；不能当作已经通过。

- [ ] 读取对应API、DDL及类清单，列出Create/Modify/Test的精确文件；核对复用路径、迁移版本和上游接口签名。契约有缺口先在JB-008对应文档补齐。
- [ ] 写表中关键失败行为用例，运行并确认RED到达目标行为；数据库/状态机用真实Mapper和事务，不用空实现或仅验证mock被调用。
- [ ] 实现最小闭环：增量迁移+实体Mapper+Service/Impl+SPI/HTTP适配；写入与读回、权限与错误码同项接线。
- [ ] 跑受影响测试和规范守卫。金额/次数/容量并发、JSON/CHECK与事务在目标数据库验证；关键分支做一次消融，证明对应测试变红后恢复。
- [ ] 回填提交号、实际文件、U/API/表映射、命令/退出码/测试方法、部署模式及未通过项，再限定提交；全部条件满足才改DONE。

通用验证命令（替换测试名，JDK21路径按环境调整）：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -o -f backend/pom.xml -pl shop-app -am -Dtest=目标测试名 -Dsurefire.failIfNoSpecifiedTests=false test
node scripts/check-v2-docs.mjs
node scripts/check-sql-portability.mjs
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH node scripts/verify-entitlement-mysql.mjs
bash scripts/check-head-compiles.sh HEAD
git diff --check
```

原生运行器当前仅覆盖目录场景，新发行/储值/核销任务必须扩展独立数据库验收，不能拿这1项代替所有新业务。改shop-core后需按Java规范install再验证运行实例；最终装配验证用新产物，不能依赖旧.m2或正在运行的旧jar。

## 3. 数据库、API与Java类覆盖对账

### 3.1 目标逻辑表到任务

下列每行同时负责对应实体及Mapper；正式迁移号实施时分配，不提前假设V426仍空闲。原表已有时采用增量扩展并保留历史消费者。

| 模块 | 目标表 | 实体 / Mapper | 主要任务 | 当前状态 |
|---|---|---|---|---|
| promotion | pmt_preset_template | PmtPresetTemplate / PmtPresetTemplateMapper | JB-028 | TODO，设计表/已有表待改造 |
| promotion | pmt_activity | PmtActivity / PmtActivityMapper | JB-029 | TODO，设计表/已有表待改造 |
| promotion | pmt_activity_version | PmtActivityVersion / PmtActivityVersionMapper | JB-029 | TODO，设计表/已有表待改造 |
| promotion | pmt_activity_scope | PmtActivityScope / PmtActivityScopeMapper | JB-029 | TODO，设计表/已有表待改造 |
| promotion | pmt_activity_audience | PmtActivityAudience / PmtActivityAudienceMapper | JB-029 | TODO，设计表/已有表待改造 |
| promotion | pmt_recharge_tier | PmtRechargeTier / PmtRechargeTierMapper | JB-029 | TODO，设计表/已有表待改造 |
| promotion | pmt_activity_budget | PmtActivityBudget / PmtActivityBudgetMapper | JB-031 | TODO，设计表/已有表待改造 |
| promotion | pmt_activity_participant | PmtActivityParticipant / PmtActivityParticipantMapper | JB-031 | TODO，设计表/已有表待改造 |
| promotion | pmt_activity_reservation | PmtActivityReservation / PmtActivityReservationMapper | JB-031 | TODO，设计表/已有表待改造 |
| promotion | pmt_effect_projection | PmtEffectProjection / PmtEffectProjectionMapper | JB-044 | TODO，设计表/已有表待改造 |
| promotion | pmt_command_dedup | PmtCommandDedup / PmtCommandDedupMapper | JB-031 | TODO，设计表/已有表待改造 |
| product | prd_entitlement_binding | PrdEntitlementBinding / PrdEntitlementBindingMapper | JB-039 | TODO，设计表/已有表待改造 |
| pay.entitlement | ent_template | EntTemplate / EntTemplateMapper | JB-004 | DONE，V425 |
| pay.entitlement | ent_template_version | EntTemplateVersion / EntTemplateVersionMapper | JB-004 | DONE，V425 |
| pay.entitlement | ent_issue_policy | EntIssuePolicy / EntIssuePolicyMapper | JB-015 | TODO，设计表/已有表待改造 |
| pay.entitlement | ent_issue_recipient | EntIssueRecipient / EntIssueRecipientMapper | JB-015 | TODO，设计表/已有表待改造 |
| pay.entitlement | ent_issue_batch | EntIssueBatch / EntIssueBatchMapper | JB-017 | TODO，设计表/已有表待改造 |
| pay.entitlement | ent_grant | EntGrant / EntGrantMapper | JB-016 | TODO，设计表/已有表待改造 |
| pay.entitlement | ent_instance | EntInstance / EntInstanceMapper | JB-016 | TODO，设计表/已有表待改造 |
| pay.entitlement | ent_period_bucket | EntPeriodBucket / EntPeriodBucketMapper | JB-042 | TODO，设计表/已有表待改造 |
| pay.entitlement | ent_use_intent | EntUseIntent / EntUseIntentMapper | JB-020 | TODO，设计表/已有表待改造 |
| pay.entitlement | ent_use_allocation | EntUseAllocation / EntUseAllocationMapper | JB-020 | TODO，设计表/已有表待改造 |
| pay.entitlement | ent_usage | EntUsage / EntUsageMapper | JB-020 | TODO，设计表/已有表待改造 |
| pay.entitlement | ent_ledger | EntLedger / EntLedgerMapper | JB-016 | TODO，设计表/已有表待改造 |
| pay.entitlement | ent_code_token | EntCodeToken / EntCodeTokenMapper | JB-023 | TODO，设计表/已有表待改造 |
| pay.storedvalue | sv_account | SvAccount / SvAccountMapper | JB-035 | TODO，设计表/已有表待改造 |
| pay.storedvalue | sv_recharge | SvRecharge / SvRechargeMapper | JB-034 | TODO，设计表/已有表待改造 |
| pay.storedvalue | sv_lot | SvLot / SvLotMapper | JB-035 | TODO，设计表/已有表待改造 |
| pay.storedvalue | sv_hold_allocation | SvHoldAllocation / SvHoldAllocationMapper | JB-036 | TODO，设计表/已有表待改造 |
| pay.storedvalue | sv_voucher | SvVoucher / SvVoucherMapper | JB-035 | TODO，设计表/已有表待改造 |
| pay.storedvalue | sv_entry | SvEntry / SvEntryMapper | JB-035 | TODO，设计表/已有表待改造 |
| pay.storedvalue | sv_user_store_index | SvUserStoreIndex / SvUserStoreIndexMapper | JB-037 | TODO，设计表/已有表待改造 |
| pay.entitlement | ent_owner_registry | EntOwnerRegistry / EntOwnerRegistryMapper | JB-048 | TODO，设计表/已有表待改造 |
| pay.entitlement | pay_ent_outbox | PayEntOutbox / PayEntOutboxMapper | JB-014 | DONE；V429，提交后桥接sys_outbox；SENT=统一箱已接管 |
| pay.entitlement | pay_ent_inbox | PayEntInbox / PayEntInboxMapper | JB-014 | DONE；V429，pay端计算摘要，唯一消费键同事务去重 |
| pay.entitlement | pay_ent_exception | PayEntException / PayEntExceptionMapper | JB-045 | TODO，设计表/已有表待改造 |
| pay.entitlement | pay_ent_command_dedup | PayEntCommandDedup / PayEntCommandDedupMapper | JB-004 | DONE，V425 |
| pay.entitlement | ent_use_reversal | EntUseReversal / EntUseReversalMapper | JB-022 | TODO，设计表/已有表待改造 |

基础38张之外，支撑DDL与数据库字典第七节新增以下6张，目标合计44张（core16/pay28）；正式迁移V425为目录三张、V426为会员凭证一张。恢复租约复用既有V91 shedlock，不另建空壳表。

| 模块 | 支撑表 | 计划实体 / Mapper | 责任任务 | 状态 |
|---|---|---|---|---|
| core.member | mbr_identity_ticket | MbrIdentityTicket / MemberIdentityTicketMapper | JB-010 | DONE，V426 |
| core.trade | ord_ent_authorization | OrdEntAuthorization / TradeMappers.EntitlementAuthorizationMapper | JB-011 | DONE，V427 |
| core.trade | ord_ent_source_decision | OrdEntSourceDecision / TradeMappers.EntitlementSourceDecisionMapper | JB-011 | DONE，V427 |
| core.trade | ord_card_sale_snapshot | OrdCardSaleSnapshot / TradeMappers.CardSaleSnapshotMapper | JB-012 | DONE，V428 |
| pay.entitlement | pay_ent_recon_run | PayEntReconRun / PayEntReconRunMapper | JB-046 | 设计，未迁移 |
| pay.entitlement | pay_ent_recon_diff | PayEntReconDiff / PayEntReconDiffMapper | JB-046 | 设计，未迁移 |

### 3.2 API到任务

下表48条外部接口的任务是完整HTTP交付负责人；内部12项是领域协议负责人。JB-041建立新增内部写及query的远程承载；后续批量/运营/售卡任务同时扩展对应门面及往返测试，不等待单个适配任务替代业务接线。JB-053统一核对路由/权限/OpenAPI。已有URL的目标扩展也标TODO，不否认原功能存在。

| ID | 目标接口/操作 | 交付任务 | 状态 |
|---|---|---|---|
| C01 | GET /biz/activity-presets | JB-028 | TODO |
| C02 | GET /biz/card-product-presets | JB-028 | TODO |
| C03 | GET /biz/activities | JB-033 | TODO |
| C04 | POST /biz/activities | JB-033 | TODO |
| C05 | POST /biz/activities/{activityNo}/publish | JB-033 | TODO |
| C06 | PUT /biz/activities/{activityNo}/status | JB-033 | TODO |
| C07 | POST /biz/card-product-bindings | JB-039 | TODO |
| C08 | POST /biz/member-code/resolve | JB-010 | DONE |
| C09 | POST /mp/orders/{orderNo}/entitlement-authorizations | JB-011 | DONE |
| C10 | GET /biz/marketing-summary | JB-044 | TODO |
| C11 | POST /mp/member-code | JB-010 | DONE |
| P01 | GET /biz/entitlement-templates | JB-025 | TODO |
| P02 | POST /biz/entitlement-templates | JB-025 | TODO |
| P03 | PUT /biz/entitlement-templates/{templateNo}/draft | JB-025 | TODO |
| P04 | POST /biz/entitlement-templates/{templateNo}/publish | JB-025 | TODO |
| P05 | PUT /biz/entitlement-templates/{templateNo}/status | JB-025 | TODO |
| P06 | PUT /biz/entitlement-templates/{templateNo}/issue-policy | JB-025 | TODO |
| P07 | POST /biz/entitlement-issue-batches | JB-026 | TODO |
| P08 | GET /biz/entitlement-issue-batches/{batchNo} | JB-026 | TODO |
| P09 | GET /mp/entitlements | JB-027 | TODO |
| P10 | GET /mp/entitlements/{instanceNo} | JB-027 | TODO |
| P11 | POST /mp/entitlement-claims | JB-026 | TODO |
| P12 | POST /biz/entitlement-uses/preview | JB-026 | TODO |
| P13 | POST /biz/entitlement-uses | JB-026 | TODO |
| P14 | GET /biz/entitlement-uses/{operationNo} | JB-026 | TODO |
| P15 | POST /mp/entitlement-quotes | JB-026 | TODO |
| P16 | POST /mp/entitlements/{instanceNo}/activation-quotes | JB-026 | TODO |
| P17 | GET /mp/entitlements/{instanceNo}/allowances | JB-027 | TODO |
| P18 | POST /biz/stored-value/recharges | JB-038 | TODO |
| P19 | POST /biz/stored-value/recharges/{rechargeNo}/submit | JB-038 | TODO |
| P20 | POST /biz/stored-value/recharges/{rechargeNo}/receive | JB-038 | TODO |
| P21 | POST /biz/stored-value/recharges/{rechargeNo}/cancel | JB-038 | TODO |
| P22 | POST /biz/stored-value/consumptions | JB-038 | TODO |
| P23 | GET /mp/stored-value/stores | JB-038 | TODO |
| P24 | GET /mp/stored-value/consumptions | JB-038 | TODO |
| P25 | GET /mp/entitlement-usage/{usageNo} | JB-027 | TODO |
| P26 | GET /biz/entitlement-usage | JB-027 | TODO |
| P27 | GET /biz/stored-value/recharges/{rechargeNo} | JB-038 | TODO |
| P28 | GET /biz/stored-value/accounts | JB-038 | TODO |
| P29 | GET /biz/entitlements/{instanceNo} | JB-027 | TODO |
| P30 | POST /mp/entitlements/{instanceNo}/redeem-codes | JB-026 | TODO |
| O01 | GET /ops/pay/entitlement-exceptions | JB-045 | TODO |
| O02 | GET /ops/pay/entitlement-exceptions/{exceptionNo} | JB-045 | TODO |
| O03 | POST /ops/pay/entitlement-exceptions/{exceptionNo}/retry-grant | JB-045 | TODO |
| O04 | POST /ops/pay/entitlement-exceptions/{exceptionNo}/query-source | JB-045 | TODO |
| O05 | GET /ops/pay/entitlement-reconciliations | JB-046 | TODO |
| O06 | GET /ops/notify-logs | JB-043 | TODO（O06复用既有日志入口） |
| O07 | POST /ops/pay/entitlements/{instanceNo}/freeze | JB-047 | TODO |
| I01 | ActivityParticipationPort.reserve(ReserveActivity) | JB-031 | TODO |
| I02 | ActivityParticipationPort.confirm(ConfirmParticipation) | JB-031 | TODO |
| I03 | ActivityParticipationPort.release(ReleaseParticipation) | JB-031 | TODO |
| I04 | ActivityParticipationPort.query(SourceRef) | JB-031 | TODO |
| I05 | EntitlementGrantPort.prepare(GrantCommand) | JB-016 | TODO |
| I06 | EntitlementGrantPort.commit(GrantDecision) | JB-016 | TODO |
| I07 | EntitlementGrantPort.abort(AbortGrant) | JB-016 | TODO |
| I08 | EntitlementUsePort.prepare(UseCommand) | JB-020 | TODO |
| I09 | EntitlementUsePort.commit(UseDecision) | JB-020 | TODO |
| I10 | EntitlementUsePort.release(CancelUse) | JB-020 | TODO |
| I11 | EntitlementUsePort.reverse(OrderBenefitReverse) | JB-022 | TODO |
| I12 | StoredValuePort.credit(CreditRecharge) | JB-035 | TODO |

内部getPublishedVersion/validateReference属于JB-006本地已实现、JB-041远程待实现；grant/use/recharge/authorization/sourceDecision的query在JB-008固定签名，分别由JB-016/020/034/011实现、JB-041接远程。不能因为I编号只列写操作而漏掉恢复查询。

### 3.3 非实体Java类和文件落点

物理路径以类清单§1为准：P=`backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/`，C=`backend/shop-core/src/main/java/ai/neargo/shop/`，A=`backend/shop-app/src/main/java/ai/neargo/shop/`，S=`backend/shop-base/src/main/java/ai/neargo/shop/spi/`。Service接口与实现分别在同域service和service/impl；HTTP DTO在portal对应端dto，跨服务DTO在对应SPI子包。各任务开始时登记精确文件，不能按简称创建第二套包结构。

| 类组/落点 | 任务与实现约定 |
|---|---|
| S/entitlement：SourceRef、EntitlementDefinition、EntitlementCatalogPort | JB-002/JB-006已完成；其余DecisionRef/ParticipationPermit/GrantCommand/UseCommand等协议由JB-008先固定字段与签名 |
| S：ActivityParticipationPort、EntitlementGrantPort、EntitlementUsePort、EntitlementQueryPort、StoredValuePort | JB-008契约；JB-031/016/020/024及JB-034–037实现；JB-041远程适配 |
| P/entitlement：CatalogScope/CatalogViews、EntitlementCatalogService/Impl、RuleValidator、CatalogPortImpl | JB-005/JB-006已完成；JB-025显式映射公网DTO；JB-013抽公共幂等，不保留两套算法 |
| P/entitlement/service：IssuePolicy/IssueBatch/GrantService及Impl；posting/EntitlementPostingService | JB-015/017/016；posting在JB-020扩展扣次，调用方pay事务内执行 |
| P/entitlement/policy：UsageQuota、CountUsagePolicy、PeriodCountUsagePolicy、PeriodDailyUsagePolicy、EntitlementValidityPolicy、CouponQuotePolicy | JB-003已有纯规则；JB-019/JB-042接持久模型；JB-021迁唯一报价算法 |
| P/entitlement：Use/Lifecycle/Query/CodeService及Impl、UsePortImpl/GrantPortImpl/QueryPortImpl | JB-020/042/024/023；JB-047补冻结；端口不穿透实体 |
| P/entitlement：EntitlementCommandDeduplicator、PayEntitlementEventPublisher、Exception/ReconciliationService及Impl | JB-013/JB-014/JB-045/JB-046；状态/记账与幂等/事件同事务 |
| P/storedvalue：Recharge/Credit/Redemption/QueryService及Impl、PostingService、AllocationPolicy、StoreProjector、StoredValuePortImpl | JB-034/035/036/037；StoredValuePort按方法委派，不将整个逻辑堆到单一Impl |
| C/promotion：ActivityService/VersionService、RuleValidator、ActivityPricingService、PlatformActivityService及Impl | JB-029/JB-030；活动头/发布/定价职责分开，已有类原位扩展 |
| C/promotion：Preset/ParticipationService及Impl、EligibilityPolicy、ParticipationPortImpl | JB-028/JB-031；core预算不能用pay发行容量替代 |
| C/promotion：MarketingSummaryService/Impl、MarketingEffectProjector | JB-044；效果投影为派生数据 |
| C/product、trade、member：CardProductBindingService、CardSaleSnapshotService、EntitlementAuthorizationService、MemberCodeResolveService及Impl | JB-039/JB-012/JB-011/JB-010；会员码先核实复用，不造储值身份体系 |
| A/paybridge：ActivityBenefitCoordinator、StoredValueRechargeCoordinator、CardSaleCoordinator、EntitlementOrderCoordinator | JB-032/JB-034/JB-040/JB-020；无跨库@Transactional，所有未知状态可查 |
| A/payclient：BizEntitlementAppService、MpEntitlementAppService、BizStoredValueAppService、OpsEntitlementAppService及本地/Remote实现 | JB-041建立承载并接已实现业务；JB-045–047补运营方法及远程往返用例；每个门面完成时验收部署选择互斥 |
| 现有PayInternalApi/Paths、InternalPayEndpoint及core反向内部适配 | JB-041；实体不作为HTTP DTO，不被ApiResponseWrapper重复包装 |
| A/portal/biz：Activity/Preset/CardProductBinding/MemberCode/MarketingSummary相关Controller | JB-033/JB-028/JB-039/JB-010/JB-044；沿既有路由与数据域 |
| A/portal/biz：EntitlementTemplate/Issue/Use/StoredValue相关Controller | JB-025/JB-026/JB-038；Issue与Use均归JB-026，权限及身份从可信会话构造 |
| A/portal/mp：EntitlementAuthorization/Entitlement/StoredValue相关Controller | JB-011/JB-026/JB-027/JB-038；仅本人访问，明细字段白名单 |
| A/portal/ops：OpsEntitlementController、OpsPromotionController、既有通知日志Controller | JB-045/046/047、JB-033、JB-043；复用O06，不重复注册路由 |
| A/paybridge：LegacyCouponCompatibilityService、EntitlementNotificationSubscriber | JB-049/JB-043；旧Controller与CouponPort委派唯一业务owner |
| 旧PmtCoupon/Scope/UserCoupon/Issue、CouponService/PmtCouponServiceImpl、CouponAllocService/Impl、CouponRedeemService/Impl、PmtApply、PromotionMappers、旧marketing/mkt实现 | JB-048–050与JB-021/022迁移适配；双读仅校验，不能双写或查不到就回旧表 |
| P/entitlement/migration：EntitlementMigrationService/Impl；core/pay job：ActivityReservationRecoveryJob、EntitlementRecoveryJob、EntitlementExpiryJob | JB-050/JB-051；生产切换另行执行，恢复job持久租约 |

所有HTTP请求/响应schema按API契约§6/§7逐项生成Java record与校验，而非只建Service。JB-008固定这些定义，交付接口的任务同时承担DTO映射和真实读写测试；JB-053核对完整性。

### 3.4 U01–U30后端验收映射

每行最终由JB-055核实对应实际测试方法；现在只是责任映射，不将父功能标为完成。U23/U24优先兼容和门禁，不扩成新的团购/积分系统。

| 功能ID | 后端能力 | 负责任务 |
|---|---|---|
| U01 | 预置活动目录 | JB-028 / JB-052 |
| U02 | 预置付费卡产品 | JB-028 / JB-039 / JB-040 / JB-052 |
| U03 | 活动草稿/发布 | JB-029 / JB-033 |
| U04 | 活动暂停/结束 | JB-029 / JB-033 |
| U05 | 储值档位与范围 | JB-009 / JB-029 / JB-034 |
| U06 | 资格预算预留 | JB-031 / JB-032 |
| U07 | 券/兑换权益模板 | JB-005 / JB-025 |
| U08 | 次卡/月卡模板规则 | JB-005 / JB-019 / JB-025 / JB-042 |
| U09 | 发行控制 | JB-015 / JB-025 |
| U10 | 定向发放与跳过 | JB-016 / JB-017 / JB-026 |
| U11 | 领取与本人券包 | JB-018 / JB-024 / JB-026 / JB-027 |
| U12 | 先看后核 | JB-020 / JB-023 / JB-026 |
| U13 | 订单用券 | JB-020 / JB-021 / JB-022 |
| U14 | 售卡与幂等发卡 | JB-012 / JB-039 / JB-040 |
| U15 | 月卡起算 | JB-019 / JB-042 |
| U16 | 次卡/日额度核销 | JB-020 / JB-026 / JB-042 |
| U17 | 储值与实收确认 | JB-034 / JB-038 |
| U18 | 储值发放账务 | JB-035 |
| U19 | 储值核销 | JB-036 / JB-038 |
| U20 | 储值门店目录 | JB-037 / JB-038 |
| U21 | 核销记录明细 | JB-024 / JB-027 / JB-037 / JB-038 |
| U22 | 本人身份授权 | JB-009 / JB-010 / JB-011 |
| U23 | 团期与赠品既有能力兼容 | JB-030 / JB-052 |
| U24 | 积分既有能力与资金支持门禁 | JB-052 |
| U25 | 通知与受理状态 | JB-014 / JB-043 |
| U26 | 营销效果 | JB-044 |
| U27 | 异常与对账 | JB-045 / JB-046 / JB-051 |
| U28 | 运营治理权限 | JB-025 / JB-033 / JB-047 / JB-053 |
| U29 | 旧券数据/URL兼容 | JB-048 / JB-049 / JB-050 |
| U30 | 线上退款限制与旧券合法补偿 | JB-022 / JB-038 / JB-040 / JB-053 |

## 4. 执行顺序、边界与收口

### 4.1 首批执行队列

1. JB-008：一次性修正契约缺口并固定内部query、权限与能力清单。JB-008完成后立即回填实际类型/方法/DDL，不以“协议待定”继续写占位业务。
2. JB-009/JB-010/JB-011/JB-012：范围、身份、本人授权和售卡快照；JB-013/JB-014可在契约就绪后独立推进。
3. JB-015/JB-019/JB-016：发行授权、有效期、来源幂等发放；接JB-017批量发放。
4. JB-020/JB-021/JB-022/JB-023/JB-042/JB-024：统一使用、报价、旧券回补、码、生命周期及查询。
5. JB-028–JB-033与上述支付底座按依赖推进；接JB-018领取，再推进JB-034–040储值/售卡。
6. JB-041按已完成服务逐批补远程适配，JB-025–027/JB-038按对应能力完成HTTP；JB-043–055收口后端。

这是依赖队列，不是单人日历承诺；现有“2后端+1前端+1QA约7–10周”估算不作为本次Java优先计划工期。每个完成提交更新台账后再领取下一个满足依赖的任务。

### 4.2 必须先补的设计缺口（JB-008）

| 缺口 | 本次处理要求 |
|---|---|
| 授权意图/源决策、售卡快照未列入38表 | 已补core三张支撑表、查询/状态与唯一键；正式实体/Mapper/迁移归JB-011/JB-012 |
| O05对账结果、恢复租约缺少完整存储模型 | 已补pay两张对账支撑表；恢复租约复用V91 shedlock，JB-046/JB-051实施 |
| IssuePolicyCommand.issueMode与表字段对应不完整 | 已删除多余issueMode，模板allowedIssueModes决定合法途径；共享跨版本发行授权由JB-015实现 |
| 目录编辑完整详情与历史版本读取的外部契约未完全明确 | P01完整草稿/currentVersionNo、P04定义/hash、内部历史版本读取已冻结；仍48条外部路由 |
| 独立pay反向核实core本人授权/源事实的内部路由与鉴权 | API§11冻结34条内部路由、上下文、共享密钥及未知/失败语义；Handler/代理由JB-041实现 |
| 新售卡经营条款、冻结/到期剩余处理与真实支付能力 | 有限次/周期、首用/付款有效期、禁止新权益线上退款和自动续费已冻结；冻结/到期保留账与历史，不自动退款；销售须能力和经营条款验收后开放，JB-039/JB-040/JB-042 |

### 4.3 后端完成标准与后置事项

Java后端开发完成须满足：本表所有开发任务均按真实交付改DONE；44张目标逻辑表及复用存储的增量迁移/实体Mapper对齐；48条外部及内部写/query全部具备真实Handler/实现/权限/DTO；本地与远程两种部署均通过；美业储值、旧券、售卡、次卡/月卡端到端及故障恢复通过；生成物、架构、i18n及干净提交全量回归有证据。目录可见、编译成功、H2通过或仅有Service接口都不代表后端完成。

前端页面、UI原型、真人通道授权/经营条款验收、线上存量盘点、生产切owner和灰度部署另列后置操作，不把代码开发与生产上线混为一谈。D03.3生产切换需现场确认水位/写入冻结/对账，当前任务只交付可演练工具。生产目标引擎若不同于已验证的MySQL 9.7.2，发布前需在真实版本补验证，不能外推MariaDB或其他MySQL版本结果。

原计划D07.1“UI随功能同步推进”按本次用户指令调整为Java后端优先；HTTP接口/DTO同步开发，页面和原型后置。总TDD §2保留阶段设计、本表维护执行状态，§6保留证据，避免多处独立维护相冲突的任务进度。


### 4.4 本轮整理验证

2026-10-18：对实际任务文档运行独立静态核对，55个编号唯一、7 DONE/48 TODO，依赖无循环且全部可追溯至JB-055；38张目标表、48条外部/12项内部操作及U01–U30均有责任任务；表格列数及本地链接通过。`node scripts/check-v2-docs.mjs`通过66份受管文档，`git diff --check`通过。本轮没有修改Java、SQL迁移或运行配置，因此没有重跑业务回归；既有全量结果仍只对应代码提交815c12c2b。

执行裁定：JB-013公共幂等实际只依赖JB-005已有CatalogScope和目录命令语义，不消耗JB-008新增SPI；移除对008整体的过度依赖，先提取已验证逻辑。新增MANDATORY要求调用方已有pay事务，不改变目录公开行为。JB-008其余协议/存储/权限部分仍DOING，未借此标记完成。

JB-013验收：公共组件与目录委派已经落地；EntitlementCommandDeduplicatorTest 3项、原目录12项通过，原生MySQL 1项实际运行0跳过；移除MANDATORY后requiresAnExistingPayTransaction失败，恢复后通过。命令摘要仍由owner重算，不信任外部payloadHash。提交号在总TDD后续批次记录，不改变JB-008未完成状态。

JB-019验收：EntitlementValidityPolicy、CountUsagePolicy、PeriodCountUsagePolicy已实现，模板发布复用同一有效期校验。EntitlementValidityPolicyTest 5项通过；将结束时刻改成含边界后目标测试失败，恢复半开区间后通过。首用预览无状态变更；实例CAS激活和日bucket持久化仍属于JB-042，未标完成。


本批提交证据：JB-008协议子项50f1c32c4，JB-013完成855afc35e，JB-019完成685a15805；分页修正244ee7f4c。685a15805干净全量3543项/20模块0失败（原生条件1跳过，独立已实跑通过）；244ee7f4c定向56项/原生1项通过且干净test-compile通过，未将它写成全量通过。当前仍9 DONE、1 DOING、45 TODO。已完成任务的后续消费者尚未实现时保持能力关闭，不将接口声明当作可调用业务。

执行裁定：JB-009只消耗已完成的EntitlementDefinition/CatalogScope，不消耗JB-008未完成的报价、授权持久化或发行协议，依赖收窄为JB-006。通过两个事实SPI在商品和商家owner内显式校验tenant/主体/状态；不把消费目录或SKU展示查询当归属证明。B门面从MERCHANT会话和BizContext构造scope，platformActor固定false；有限门店员工只能配置已授权STORES范围，主体级ALL/GOODS/CATEGORIES需属主语义。READONLY可保留在配置中，真正交易时再校验实时可用性；暂停/删除主体或门店不纳入新配置。新HTTP接线仍需JB-025，运营目标主体授权仍由运营门面另验。


本批验收（2026-10-18）：JB-009完成，新增两个owner事实SPI及本地实现、core范围服务与可信商家scope解析器；7个集成方法覆盖平台类目、缺失/停用/删除、跨主体/租户、商家会话、员工门店、赠品商品和分批末项。临时去除员工门店子集判断时limitedStaffCannotConfigureOtherStoresOrAllMerchantStores实际失败，恢复后通过。该守卫为应用适配器组件；尚无新HTTP路由，不将未来HTTP/远程实现计入完成范围。

JB-008补充quote/preview/activation协议及只读约束，新增EntitlementPreviewContractTest 7项；原契约10项继续通过。IssuePolicy不再单列issueMode，目录完整草稿/版本返回及首配revision=0规则已在API确定。基础38表之外补core3/pay2支撑表，数据库第七节及support-schema.sql完整列字段与唯一键；原生MySQL验证取消后新修订成功、跨修订重复成功拒绝、跨租户独立及非法决策CHECK。合计43张设计表，正式运行迁移仍只有V425三张。JB-008仍DOING：内部鉴权路由、权限/能力门禁及完整错误语义收口尚未完成。

本批命令：Java21离线Maven定向运行Scope7、Preview7、Contract10、Catalog12、Validity5、Dedup3、Source4、Quota5、Architecture16、PayTx1，共70项，0失败/错误/跳过；Node隔离schema/ER/编译闸门测试3项通过；词典重新生成，V2受管文档66份通过，SQL无新增方言债务，git diff --check通过。额外静态核对本任务表55唯一编号、设计43唯一表及更新文档本地链接。这里的定向结果不代替后续干净HEAD全量回归。

部署补充验收：同一范围集成套件用-Dshop.pay.deployment=standalone另跑7项，0失败/错误/跳过；Surefire JVM属性确认standalone实际生效。该验证覆盖core/app范围守卫在独立pay配置下的装配与拒绝行为，没有调用尚未交付的远程权益RPC，不代替JB-041双部署协议验收。API第十一节固定33条内部传输路径，第十二节登记7个默认关闭开关及预置实际35个能力码归属；均明确未接运行配置，JB-008不因此提前改DONE。

最终全量证据：c29c4fe4a8c4edad9de6a07fcf8fbbe83d5056fe，干净test-compile及20模块3558项0失败，617秒、退出0；原生MySQL条件检查默认跳过，本批设计DDL原生验证另跑通过。c29之后仅文档设计/证据变更，未冒称后续文档提交重新全量。代码提交统计23文件、1152新增/22删除，见总TDD最新验收记录。


JB-008收口裁定：本任务交付共享协议、API/权限/能力/错误契约及门禁基础；后续041的真实HTTP代理、053的角色登记、011/012/046的正式持久化不能反向阻塞008，否则形成依赖循环。当前真实目录消费者已接入门禁；未实现消费者仍由其owner任务接线，不以008 DONE冒充整体业务可用。无新公网接口，未为不存在的Handler增权。

本批验收：7项默认关闭配置已真实绑定；ActivityCapability为35码，已独立核对与46项预置要求一致。FeatureGate10、CatalogGate2、目录12、幂等3、原契约10、预览7、范围7、有效期5、Source4、Quota5、i18n6、架构16、PayTx1合计88项0失败/错误/跳过。目录create门禁消融1项实际失败，恢复后通过；原生MySQL1项实际运行、0跳过，验证关闭后原请求回放与新请求claim回滚。默认JDK运行原生脚本曾被Java21构建约束拒绝，指定Java21重跑通过，没有跳过约束。

测试环境偏差：不同开关配置的Spring上下文必须用独立H2库；首次组合回归因重复初始化mch_admission_policy而失败，已按已有隔离夹具惯例修复，未修改schema或失败基线。词典重新生成，V2受管66文档及SQL无新增方言债务通过。当前11 DONE/0 DOING/44 TODO，下一项JB-010会员码/短期customerRef，完整后端仍未完成。最终代码提交及干净全量证据另在总TDD回填。


JB-008最终代码提交f16cfff0d403d8775a0efbfa3a1cdd95ef1f2019：24文件725新增/25删除。干净提交test-compile通过，全量20模块3570项0失败，605秒、退出0；原生MySQL条件用例默认跳过，该项已由独立隔离运行器实际执行1项通过0跳过。此前88项定向、Node2项、i18n6项及文档/SQL检查证据见上。f16之后仅回填文档，未改Java/资源/迁移/运行配置，不重复宣称后续文档提交重新跑过全量。整体仍11 DONE/0 DOING/44 TODO。


### JB-010完成：统一会员身份（2026-10-18）

代码51d10402b，C登录分类6156975bb，目标门店权限修正ecfda1ebd。新增C11签发、实现C08扫码及CustomerIdentityPort本地查询；V426/hash-only凭证表归core.member；码60秒、引用120秒，绑定tenant/entity/store/actor/user。读取引用不受停止新签发开关影响，仍检查有效期及账户/商家/门店状态。扫码不构成消费同意，无余额/次卡扣减。

定向53项、standalone身份12项通过；actor隔离消融及目标门店不同角色借权均先RED后GREEN，日志不含原文。V426在隔离MySQL的唯一键/用途/范围/期限CHECK通过，API/数据库/类清单/U22/ADR及生成物同步。C/B vue-tsc通过；24生成器及66份V2文档通过；共享Vitest仍有api-path-naming的1项既有失败（5条旧路径，本次新增0），模型对齐仍受既有MemberSetting阻断；未增加豁免。

JB-010历史全量证据：ecfda1ebd干净编译及3582项/20模块全绿。JB-011最终干净HEAD f116a02ab1cb68dd0148a8270c3cfbdf918edb5f：test-compile通过，全量3590项/20模块，0红，约9分钟，退出0；另有V427原生MySQL9.7.2两项通过、聚焦34项全绿及24生成器/V2 66份/SQL守卫全绿。第一次全量暴露的UUID编号和媒体注释假阳性已修复，OTP测试独立8/8通过。当前13 DONE /0 DOING /42 TODO。授权开关默认关闭，真实pay报价生成器尚未接入，后续由订单权益报价链路调用`stageTrustedIntent`后启用；整体Java后端仍未完成。未push、部署或连接生产数据库。


### JB-012完成：付费卡售卡权益快照（2026-10-18）

实现`CardSaleSnapshotPlan`、`CardSaleSnapshotService/Impl`、`OrdCardSaleSnapshot`及Mapper、V428迁移和MySQL原生验证；售卡按订单行、购买件序号、件内权益序号展开，每项固化pay模板版本、规则JSON、单件售价、币种、来源键及无歧义摘要。商家主体与门店按子单/订单行保存，避免一张支付主单跨商家或门店时串归属；输入事实必须由服务端从可信订单和商品快照组装。重复调用按来源键插入幂等，已存在来源的快照摘要不同则拒绝。`OrderServiceImpl.markPaid`正常回调和已支付迟到重放都调用支付事实固化；付款凭证号和`paidAt`只从已落库主订单读取，数据库条件更新仅填充空付款事实，重放不能覆盖已写值。下游付费卡绑定/售卡编排仍归JB-039/JB-040，未开放虚假入口。

**裁定：**不在通用`ord_item`表新增必填行号；调用链和多组现有SQL/实体写入会因新列被迫同步改造，超出本任务且造成向后兼容破坏。使用已落库`ord_item.id`派生仅限core内部的稳定行身份，并持久化在快照`order_line_no`列；该值不会返回API。若此裁定不适用于将来分库/订单行ID重建场景，成本是历史来源身份需迁移映射，因此跨库前必须保留稳定业务行号。

实现提交：`111856a96d5238e989a1130ce7d9b7e21aea543c`；跨商家/门店归属修正提交：`cb0bf9bf0`。主体与门店从整单事实下沉到每行可信订单事实，并参与快照摘要；7项`CardSaleSnapshotTest`覆盖2件×2权益、稳定来源键、重试唯一落库、异参冲突、ID派生、真实订单`paidAt`重放、摘要字段边界及跨子单归属。Java 21 `mvn -o -f backend/pom.xml clean test`：27模块3598项、0失败、0错误（Surefire报告0跳过）；V425/V427/V428隔离MySQL原生检查3项通过、0跳过。24个生成器、V2 66份文档、SQL可移植性扫描（0新增方言欠账）、`git diff --check`通过。全Java后端未完成；未推送、部署或操作生产数据库。

### JB-014完成：支付权益事件Outbox/inbox（2026-10-18）

完成pay侧版本化事件SPI、`pay_ent_outbox`/`pay_ent_inbox`和V429；发布器要求处于pay业务事务内，校验事件类型与字段后持久化，按事件号重放须事实一致，否则拒绝。提交后异步桥接到已有`sys_outbox`，桥接写失败采用退避重试，启动与周期扫描恢复崩溃窗口；两套数据源事务管理器不伪装成分布式原子提交。Inbox在pay事务内从可信载荷计算SHA-256，唯一键锁定后再执行业务回调，重复消息不重复应用，同号异参拒绝。`SENT`含义为统一投递箱已持久接管，之后仍由现有投递泵负责最终重试。已验证`EntitlementEventFlowTest`4项、`OutboxEventBusPumpTest`3项及隔离MySQL原生4项（回滚、稳定重放/冲突、桥接恢复、inbox去重/冲突）。业务发行、核销和储值服务的发布器调用仍归后续业务任务，不据此宣称整体业务闭环完成。

实现提交：`d1e260aba`。Java 21 `mvn -o -f backend/pom.xml clean test`：27模块BUILD SUCCESS；Surefire报告合计3,604项、0失败、0错误、4跳过（shop-app模块汇总2,782项）；独立隔离MySQL场景4项通过、0跳过。24个文档生成器、V2 66份文档守卫、SQL可移植性（0新增方言欠账）及`git diff --check`通过。只读代码复核无可执行问题。未推送、部署或访问生产数据库。

当前任务计数：15 DONE / 0 DOING / 40 TODO。下一项JB-015：权益发行容量及敞口原子限制。

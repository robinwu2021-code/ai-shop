# 活动卡券储值 Java 后端独立任务表

> 执行方式：沿用当前会话内实施，按任务使用 superpowers:executing-plans；每项完成后更新本表。本表是原TDD §2的独立执行台账，不另起一套架构。

状态：干净副本验收发现并发连接池耗尽，修复与补验中 · 2026-10-20 · 基准提交64a25a5bd。

目标：完成既定活动、卡券、储值和次卡/月卡的全部Java后端，包括数据库增量迁移、领域服务、内部协议、本地/远程适配、B/C/运营HTTP接口、权限、通知、兼容迁移工具及验收。前端页面和UI原型后置；接口契约、经营规则和权限不能因前端后置而省略。

架构：core拥有活动规则、资格、营销预算、商品、订单和本人授权；pay拥有权益定义、发行、使用和储值资金账。通过共享SPI、分阶段持久决策及Outbox协调，不建立跨库事务。

技术基线：Java 21、既有Spring/MyBatis-Plus/Flyway、离线Maven、既有MySQL兼容约束；不新增模块级依赖版本，不借本轮重构全仓。

## 1. 依据、完成口径与优先级

| 真源 | 本表使用方式 |
|---|---|
| [总设计及原D01–D07计划](./TDD-活动卡券储值-模块化系统设计.md) | 保留原任务归属，本表将其拆成可独立验证的Java任务 |
| [数据库设计](./TDD-活动卡券储值-数据库设计.md)及core/pay设计DDL | 当前基础47张及支撑22张目标逻辑表逐一指定迁移负责人；目标表不等于已建生产表 |
| [API契约](./TDD-活动卡券储值-API契约.md) | 保留原49条编号，当前64条公开路由按真实Handler/Jackson DTO及生成契约对账；内部写/query按运行时承载覆盖对账 |
| [Java代码结构与类清单](./TDD-活动卡券储值-代码结构与类清单.md) | 类/接口/Controller/实体Mapper落到任务；已实现项以源码及提交核实 |
| [U01–U30功能矩阵](../requirements/活动卡券储值-模块功能矩阵.md) | 后端验收逐项关联；40AP和6EP目录不冒充已具备执行能力 |
| [仓库任务规范](../../.claude/skills/ai-shop-dev-standards/SKILL.md)、[Java规范](../../.claude/skills/ai-shop-dev-standards/references/java.md) | TDD三处对账、RED→GREEN、关键消融、真实数据库与提交证据 |

状态只用TODO/DOING/BLOCKED/DONE。DONE要求本项全部交付、提交号及真实验证证据；父阶段未满足全部条件保持未完成。现有类/旧接口可复用，不能因此将新的目标契约记为DONE。

本表共55项：51项DONE、4项DOING、0项TODO，暂无BLOCKED；这是拆分后的交付单元计数，不等同工作量比例。状态快照如下，详细交付和验收条件仍以第2节逐项任务表为准：

| 状态 | 任务编号 | 当前口径 |
|---|---|---|
| DONE（51） | 除JB-016、JB-041、JB-054、JB-055外 | 主体能力和专项验收已完成；这4项因干净副本连接池问题重新打开。 |
| DOING（4） | JB-016、JB-041、JB-054、JB-055 | 连接池缺陷已定向修复验证；最终提交和部署仍待完成。 |
| TODO（0） | 无 | 前端页面另列独立接入任务；生产部署状态以验收记录为准。 |

当前N01–N12首期Java后端验收完成。最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 前端实际页面见[独立接入表](./任务表-活动权益-前端接入.md)，生产开关及生产迁移未执行。

优先顺序（2026-10-19用户调整）：第一阶段完成core活动自身能力，第二阶段完成pay权益、卡券、储值及活动权益联动。具体任务分组、跨阶段子项与验收见[两阶段执行方案](./开发计划-活动优先与权益两阶段.md)。已完成的权益公共基础直接保留；JB-015已在第二阶段完成，不再列为待开发。原任务编号与整体依赖保留，跨阶段任务仅完成活动部分不能标记整项DONE。

测试节奏（2026-10-19最新用户指示）：每轮完成前必须编译并执行受影响的基本单元测试，修复明确失败；可合并少量关键业务/HTTP测试验证接线，不运行全量。全量后端测试留在JB-055最终收口。每项分别记录编码进展和验证结果，未验收不得标DONE。

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

### 1.3 批量执行与验收清单（后端已完成）

以下保留N01–N12的交付拆分、依赖与验收要求，均已按首期后端范围完成。末尾历史逐轮记录保留当时状态，不覆盖本节及顶部当前结论。

| 顺序 | 关联任务 | 模块 | 当前基础与接下来交付 | 本批验收重点 |
|---|---|---|---|---|
| N01 对账闭环 | JB-046、041 | pay / shop-base / payclient / app ops | 已有RIGHTS余额、预占、序号与流水核对及持久结果。补发行、使用预占、日额度bucket、储值金额与分录核对；补差异查询、O05及本地/远程接口、权限。明确各轴口径和分页任务边界。 | 同键重放、逐页完整性、差异可追踪、币种/次数隔离；不自动调账。批量验证V452–V456原生迁移，并按实际新增脚本扩展。 |
| N02 异常治理闭环 | JB-045、051 | pay / app ops / core-pay jobs | 已有异常登记、原grant恢复、查源和O01–O04。补真实失败生产者接入、操作历史查询、死信关联；补需要的跨主体列表及其数据域，保留显式主体操作约束。 | 超时只恢复原操作；查源不变更余额；失败可续跑、操作留痕、跨主体越权拒绝。 |
| N03 冻结与解冻 | JB-047、041 | pay / shop-base / payclient / app ops | 新增冻结/解冻领域命令、状态版本、原因审计、O07及本地/远程接线；逐项定义新预占与既有承诺交易的行为。 | 同键异参冲突、冻结不清余额，原预占仍能按既定决策确认或释放；权限受限。 |
| N04 活动参与与治理收口 | JB-031、033 | core promotion / app biz-ops | 已有活动定义、发布、资格与预算占用。核对充值/发券/售卡参与确认及取消释放；补活动治理和C03–C06兼容缺口。 | 预算/名额/个人限购不超卖；跨版本累计；未知权益结果不按TTL释放；主体范围与旧URL兼容。 |
| N05 储值业务收口 | JB-034–038 | core / pay storedvalue / app biz-mp | 已有实收到账、核销分配、余额与记录查询及HTTP。串联正常发布活动→商家办理实收→一次到账→会员码核销→C端记录，补实际链路暴露的缺口。 | 实收/取消竞争、到账与核销幂等、分录平衡、批次释放正确；门店按最后成功充值/核销倒序，零余额保留；C端字段与权限符合设计。 |
| N06 次卡/月卡与使用收口 | JB-020、040、041 | core trade / pay entitlement / payclient / app | 已有商品绑定、订单快照、支付前容量承诺、支付/取消事件、发卡与恢复。补真实跨进程source反查及售卡/使用链路缺口；核对跨日额度和取消竞争。 | 两件×两项权益不漏不重；实际支付事实可信；首用不重复激活、日限不超扣、释放回原bucket；预占/确认/释放并发终态唯一。 |
| N07 后台恢复与到期 | JB-051 | core-pay jobs / pay lifecycle | 复用已有售卡补偿，补活动占用、待处理grant/use/recharge的原号恢复、到期处理和持久任务租约；与N02异常/死信闭环衔接。 | 重启/重复执行安全；未知状态保留待查；到期与在途操作规则一致；失败可定位和重试。 |
| N08 通知与效果统计 | JB-043、044 | app message / core promotion / app ops-biz | 已有到账/发行/核销站内通知。补商家/运营异常通知、提醒及O06日志治理；核对充值、用卡、售卡事件效果投影和C10统计。外部通知通道沿用既有配置，不擅自开通。 | 消息去重且失败不重做财务动作；收件人正确、字段脱敏；统计按币种与量纲分列，不重复计入发行/实收/消费。 |
| N09 预置能力与契约对账 | JB-052、053 | core / pay / app / 文档及生成物 | 对40AP+6EP逐项核对真实可执行能力和开关；对当前实际路由、SPI、DTO、权限、三语与OpenAPI逐项对账，不沿用历史接口总数作为覆盖证据。 | 不支持的预置保持关闭并说明缺口；每条接口有真实Handler和权限拒绝证据；本地/远程语义一致。 |
| N10 旧券归属与兼容 | JB-048、049 | pay migration / core legacy / app | 实现逐实例精确owner登记及唯一写者；接旧券DTO、URL、券码、报价、核销与售后路由，迁移前后按owner选择实现。 | 同号不同来源不混淆；缺owner不猜测；保持旧契约；已迁移实例不双写、不双扣。 |
| N11 迁移工具与部署演练 | JB-050、054 | pay migration / app / 数据库与部署 | 在N01/N10基础上实现导入、断点、对账、冻结排空和切owner工具；演练embedded/standalone装配、目标数据库新建/升级与回退边界。 | 重跑不重复发行、不发新通知；余额/余次/预占/历史流水一致；实际双进程调用与配置关开组合可验证。仅离线演练，不执行生产切换。 |
| N12 最终后端验收 | JB-055；关联全部未关闭JB | 全Java后端 | 汇总U01–U30证据；完成美业储值与餐饮/游泳次卡月卡场景、故障恢复、最终代码审查及限定范围提交。 | 前序批次通过后执行一次全量后端回归；无未解决严重缺陷；代码、数据库、API、类清单和任务表一致，满足提交与证据条件才将父任务置DONE。 |

执行依赖：N01→N02→N03；随后N04→N05→N06，N07复用N02与业务恢复事实，N08消费已稳定的业务事件；N09汇总前述接口。N10依赖既有权益基础，N11要求N01/N07/N09/N10完成，N12最后执行。按上述顺序推进，不因父任务互相引用产生循环等待。

每个编码批次结束：编译受影响模块，并运行基本单元测试及必要的少量真实事务/HTTP用例；涉及DDL时补相应原生数据库验证。保留实际命令、结果及未验收项，不逐批全量测试。文档整理本身不触发Java编译。单项已有定向测试通过，也不能替代跨进程、原生并发或父任务整体验收。

当前执行状态：N01–N12及55个后端父任务已完成。最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。

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
| JB-015 | D02.1/2.2 | DONE | 005,013,014 | pay.entitlement | EntitlementIssuePolicyService/Impl；发行总量/敞口/个人容量条件更新 | B1真实事务/Mapper及原生MySQL通过；完整证据见第二阶段B1-发行批次验收；源码提交e78d7fc3a；017容量契约修正见B1报告§5 |
| JB-016 | D02.3 | DOING | 002,014,015,019 | pay.entitlement | EntitlementGrantService/Impl、GrantPortImpl、EntitlementPostingService；prepare/commit/abort/query | B1真实事务/Mapper及原生MySQL通过；完整证据见第二阶段B1-发行批次验收；源码提交e78d7fc3a |
| JB-017 | D02.3 | DONE | 016 | pay.entitlement | EntitlementIssueBatchService/Impl；P07/P08底层能力，受众快照预检 | B1真实事务/Mapper及原生MySQL通过；完整证据见第二阶段B1-发行批次验收；源码提交e78d7fc3a |
| JB-018 | D02.3/D04.3 | DONE | 016,032 | pay/app | 本人领取路径、CENTER/ACTIVITY校验、core资格到pay发放协调 | EntitlementClaimFlowTest：未登录/越权拒绝；付费卡不可免费领；未知结果只查原grant |
| JB-019 | D02.4/D06.3 | DONE | 003,005 | pay.policy | EntitlementValidityPolicy、CountUsagePolicy、PeriodCountUsagePolicy；从共享额度规则派生 | EntitlementValidityPolicyTest：支付/首用、自然月/滚动天、时区、月末/闰日、半开区间；GET无激活副作用 |
| JB-020 | D02.4 | DONE | 011,013,014,016,019 | pay.entitlement/app | EntitlementUseService/Impl、UsePortImpl、EntitlementPostingService、EntitlementOrderCoordinator；prepare/commit/release/query | EntitlementUseFlowTest：同源不双扣、确认释放互斥、并发不负数、未知不重扣、本人/订单/门店绑定；原生事务检查；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-021 | D02.4 | DONE | 008,019,020 | pay.entitlement | CouponQuotePolicy迁入既有唯一门槛/折扣/分摊算法；P15服务 | EntitlementQuoteFlowTest：先活动后券、FREE_SHIP、折扣封顶、分摊守恒；预览不冻结不扣款 |
| JB-022 | D02.4/D03.2 | DONE | 020,021 | pay.entitlement | 原使用受限reverse、EntUseReversal与旧券售后回补；I11 | EntitlementReversalFlowTest：部分售后累计不超原分配、同售后幂等；储值/新付费卡线上退款拒绝 |
| JB-023 | D02.4 | DONE | 010,016,020 | pay.entitlement | EntitlementCodeService/Impl；短期兑换券token签发/解析/失效 | EntitlementCodeFlowTest：仅STORE_EXCHANGE、本人可签、过期/重放拒绝、码不入日志；不生成储值专用码 |
| JB-024 | D02.5 | DONE | 016,020,042 | pay.entitlement | EntitlementQueryService/Impl、QueryPortImpl；本人资产/商家受控详情/总次与日额度/使用明细 | EntitlementQueryFlowTest：跨用户/主体/租户拒绝、过期历史保留、游标稳定、DTO无内部授权/资金拆分 |
| JB-025 | D02.2/2.6 | DONE | 008,009,015,041 | app HTTP | BizEntitlementTemplateController；P01–P06及管理DTO映射 | EntitlementTemplateApiTest：真实HTTP写后读、BeanValidation生效、权限逐条校验；不能直接绑定CatalogScope |
| JB-026 | D02.3/2.4 | DONE | 017,018,020,021,023,041,042 | app HTTP | BizEntitlementIssueController、BizEntitlementUseController、MpEntitlementController使用/领取部分；P07/P08/P11–P16/P30 | EntitlementUseApiTest：先看后核、本人授权、PROCESSING查询原操作；未具备能力拒绝而非假成功 |
| JB-027 | D02.5 | DONE | 024,041 | app HTTP | MpEntitlementController查询部分、B记录/详情；P09/P10/P17/P25/P26/P29 | EntitlementQueryApiTest：本人字段白名单、商家门店过滤、游标/分页、金额币种与次数分开 |

### 2.4 核心活动系统

| ID | 原任务 | 状态 | 依赖 | 模块 | 交付内容/Java类 | 验收条件/测试 |
|---|---|---|---|---|---|---|
| JB-028 | D04.1/D06.1 | DONE | 008 | core.promotion | ActivityPresetService/Impl、BizActivityPresetController；40AP+6EP目录、分类/业态、能力过滤；C01/C02 | ActivityPresetServiceImplTest/ActivityPresetExecutionPolicyTest及原生MySQL：46项稳定编号、默认禁用、真实缺失能力；见第一阶段交付报告 |
| JB-029 | D04.1 | DONE | 006,008,009,028 | core.promotion | ActivityService/Impl、ActivityVersionService/Impl、ActivityRuleValidator；草稿/发布/范围/受众/储值档位 | ActivityVersionManagementTest及UnifiedActivityManagementFlowTest：冻结发布、CAS/字段清空、状态/重放与数据域；见第一阶段交付报告 |
| JB-030 | D04.1/4.3 | DONE | 029 | core.promotion/trade | ActivityPricingService/Impl、PlatformActivityService/Impl及现有团期/赠品适配 | UnifiedOrderActivityTransactionTest及旧活动/退券场景：ORDER隔离、真实订单记账与取消返还；未实现玩法拒绝；见第一阶段交付报告 |
| JB-031 | D04.2 | DONE | 008,011,014,016,029 | core.promotion | ActivityParticipationService/Impl、PortImpl、EligibilityPolicy；reserve/confirm/release/query | ActivityParticipationFlowTest：预算/名额/个人限购同事务，跨版本累计；未知pay状态不按TTL释放；原生并发不超卖；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-032 | D04.3 | DONE | 016,031 | app paybridge | ActivityBenefitCoordinator；core reserve→pay prepare/commit→core confirm | ActivityBenefitCoordinatorTest：失败补偿、事件重复/乱序、远程超时、原业务号恢复；营销占用不提前释放 |
| JB-033 | D04.1/4.4 | DONE | 029,030,031,041 | app HTTP | BizActivityController扩展C03–C06、OpsPromotionController活动治理 | ActivityManagementApiTest：旧URL兼容、新筛选/发布/结束、主体与运营数据域隔离；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |

### 2.5 储值、付费卡和跨服务接线

| ID | 原任务 | 状态 | 依赖 | 模块 | 交付内容/Java类 | 验收条件/测试 |
|---|---|---|---|---|---|---|
| JB-034 | D05.1 | DONE | 010,011,013,014,031 | pay.storedvalue/app | StoredValueRechargeService/Impl、StoredValueRechargeCoordinator；创建/提交/实收/取消/query | StoredValueRechargeFlowTest：真实凭据/金额匹配；实收取消竞争唯一终态、重复实收不重复到账；已实收不可取消；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-035 | D05.2 | DONE | 013,014,034 | pay.storedvalue | StoredValueCreditService/Impl、StoredValuePostingService；I12账户/批次/凭证/分录同事务 | StoredValueCreditFlowTest：原充值号只到账一次、借贷平衡、内部本金赠金守恒、异常整体回滚；原生MySQL验证；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-036 | D05.3 | DONE | 011,020,035 | pay.storedvalue | StoredValueRedemptionService/Impl、StoredValueAllocationPolicy；统一使用意图的金额预占/确认/释放 | 主链路和分批分配代码已落地，已验证：范围/过期批次校验、条件余额不超扣、释放回原批次、消费重试不重复分录及原生并发；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-037 | D05.4 | DONE | 014,035,036 | pay.storedvalue | StoredValueStoreProjector、StoredValueQueryService/Impl；记录、门店目录、总余额 | 充值/核销投影、余额/门店/消费记录查询及签名游标已编码，已验证：仅成功储值门店、充值/核销最大活动时间倒序、稳定翻页、耗尽仍保留、乱序不倒退；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-038 | D05.1–5.4 | DONE | 009,034,035,036,037,041 | app HTTP | BizStoredValueController、MpStoredValueController；P18–P24/P27/P28/P31 | StoredValueApiTest：办理/到账/核销/本人记录闭环；无本金赠金字段、无线上退款/购买入口、统一会员码；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-039 | D06.1 | DONE | 006,009,028 | core.product/app | CardProductBindingService/Impl、BizCardProductBindingController；C07 | CardProductBindingFlowTest：只绑已发布PAID_CARD、主体/币种一致；售价沿商品SKU，旧版绑定不随模板草稿改变 |
| JB-040 | D06.2 | DONE | 012,016,039,042 | app/core.trade | CardSaleCoordinator与支付/取消Outbox订阅，按订单行/件/件内权益发卡 | PaidCardSaleFlowTest：2×2权益不漏不重发、付款/取消竞争、迟到付款起算正确；不以储值扣款冒充现金付款；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-041 | D02.6 | DOING | 008,016,017,020,021,022,023,024,031,034,035,036,037,040 | payclient/pay-svc/core内部适配 | AppService本地/Remote承载底座、PayInternalApi/Paths、内部Endpoint及core反向适配；已实现业务完整query | EntitlementInternalApiRoundTripTest：真实HTTP DTO无wrapper嵌套、签名身份、超时/失联可查、两种部署同语义且Bean互斥；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-042 | D06.3 | DONE | 019,020 | pay.entitlement | EntitlementLifecycleService/Impl、PeriodDailyUsagePolicy、ent_period_bucket；首用激活/日限/到期 | PaidCardAllowanceFlowTest：并发首用仅激活一次、指定服务日、跨日释放回原bucket、不结转、月末与时区边界 |

### 2.6 通知、运营、兼容及后端收口

| ID | 原任务 | 状态 | 依赖 | 模块 | 交付内容/Java类 | 验收条件/测试 |
|---|---|---|---|---|---|---|
| JB-043 | D05.5 | DONE | 014,020,035,040 | app/message | EntitlementNotificationSubscriber，复用MessageService与O06日志 | EntitlementNotificationFlowTest：提交后发到账/核销/异常通知，去重、失败仅重试消息、收件人校验、无内部双池/明码；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-044 | D04.4 | DONE | 014,020,029,035,040 | core.promotion/app | MarketingEffectProjector、MarketingSummaryService/Impl、BizMarketingSummaryController；C10 | MarketingSummaryFlowTest：事件幂等乱序、按币种、折扣/发行/实收/消费分项；禁止相加重复统计；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-045 | D02.5 | DONE | 014,016,020,035,036,041 | pay/app ops | EntitlementExceptionService/Impl、OpsEntitlementController；O01–O04 | EntitlementExceptionFlowTest：证据脱敏、并发恢复原grant/operation、查源不改余额、越权与版本冲突拒绝；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-046 | D02.5/D05 | DONE | 016,020,035,036 | pay.entitlement | EntitlementReconciliationService/Impl；O05，对账任务/差异结果持久化按008补设计 | EntitlementReconciliationFlowTest：发行/余次/冻结/分录各自核对，重复跑幂等、不跨量纲相加、不自动调平；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-047 | D02.5/D06.3 | DONE | 024,042 | pay/app ops | EntitlementLifecycleService冻结/解冻、OpsEntitlementController；O07及原因审计 | EntitlementFreezeFlowTest：受限权限、同请求幂等、不清余额、不绕过原预占终态/已承诺交易；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-048 | D03.1 | DONE | 004,008 | pay.migration | EntOwnerRegistry/Mapper；旧mkt/pmt逐实例owner及精确映射 | EntitlementOwnerRegistryTest：同号不同来源可区分、无owner不猜测回退、唯一写者约束；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-049 | D03.2 | DONE | 016,020,021,022,041,048 | app/旧core券 | LegacyCouponCompatibilityService、CouponPort路由及旧券Controller/服务适配 | EntitlementLegacyCompatibilityTest：旧DTO/URL/券码/报价/售后结果一致；原markUsed时点核对、已迁实例不双写双扣；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-050 | D03.1/3.3工具 | DONE | 046,048,049 | pay.migration | EntitlementMigrationService/Impl；导入、对账、断点、冻结/排空/切owner工具与离线演练 | EntitlementMigrationFlowTest：mkt/pmt样本总量/余次/冻结/流水一致、重跑幂等、导入不发新通知；不执行生产切换；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-051 | D02.5/D04.2/7 | DONE | 014,031,035,041,042,045 | core/pay jobs | ActivityReservationRecoveryJob、EntitlementRecoveryJob、EntitlementExpiryJob；持久租约与恢复 | EntitlementRecoveryFlowTest：重启/重复任务安全、未知不释放、取消确认竞争、到期与提醒去重、死信入异常；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-052 | D04/D06能力验收 | DONE | 028,030,032,035,040,041 | core/pay集成 | 40AP/6EP逐项能力映射；既有团期/赠品/积分接口复用与限制 | PresetCapabilityAcceptanceTest：逐模板证明可执行或明确缺能力；未验收关闭，付费产品无无限次/自动续费；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-053 | D01.2/D07后端 | DONE | 010,011,017,025,026,027,033,038,039,043,044,045,046,047 | API/权限/生成物 | 公开路由及内部写/query的OpenAPI、权限、三语、BeanValidation、运营五处登记总对账 | EntitlementApiContractCoverageTest：每条路由真实Handler、权限拒绝用例、DTO一致；生成物无新增失配；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-054 | D01.4/D02.6/D07.2 | DOING | 041,049,050,051,052,053 | 部署/验证 | 本地pay与独立pay后端装配、真实目标数据库版本适配、升级/回退演练 | EntitlementDeploymentAcceptanceTest：生产profile关开组合、增量升级/新库、JSON/CHECK/锁/时区/字符集；不等于部署生产；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |
| JB-055 | D07.2后端收口 | DOING | 054 | 全Java后端 | U01–U30后端映射、场景链路、故障恢复、全量回归与代码审查收口 | 美业充值→到账→核销→本人明细，餐卡/游泳卡购买→发卡→扣次/日限；无未解决严重缺陷；最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。 |

### 第二阶段执行入口

[第二阶段任务清单](./任务清单-第二阶段权益卡券储值.md)列出全部37项剩余交付、模块/表/API、批次验收和实际执行顺序。仅补计划，不改变本表状态。031与041按所需活动/发行/使用/储值子项作为开工条件，父任务全部验收后才DONE，避免依赖成环。历史开工项为JB-015，现已完成；当前下一批次以§1.3的N01为准。

### 2.7 每项统一执行清单

以下清单适用于每个TODO，不因拆表/补类而跳过。表中测试类名除已完成项外为计划名称，实施时记录最终真实方法名；不能当作已经通过。

- [ ] 读取对应API、DDL及类清单，列出Create/Modify/Test的精确文件；核对复用路径、迁移版本和上游接口签名。契约有缺口先在JB-008对应文档补齐。
- [ ] 写表中关键失败行为用例，运行并确认RED到达目标行为；数据库/状态机用真实Mapper和事务，不用空实现或仅验证mock被调用。
- [ ] 实现最小闭环：增量迁移+实体Mapper+Service/Impl+SPI/HTTP适配；写入与读回、权限与错误码同项接线。
- [ ] 小阶段跑受影响模块编译和关键定向测试；能力批次完成后合并验证金额/次数/容量并发、JSON/CHECK与目标数据库事务。文档与SQL守卫仅在对应文件变化时执行；不逐任务执行全量测试。尚未合并验证的验收项明确挂账。
- [ ] 回填提交号、实际文件、U/API/表映射、命令/退出码/测试方法、部署模式及未通过项，再限定提交；全部条件满足才改DONE。

按变更选用的验证命令（不要求逐项全部执行；替换测试名，JDK21路径按环境调整）：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -o -f backend/pom.xml -pl shop-app -am -Dtest=目标测试名 -Dsurefire.failIfNoSpecifiedTests=false test
node scripts/check-v2-docs.mjs
node scripts/check-sql-portability.mjs
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH node scripts/verify-entitlement-mysql.mjs
git diff --check
```

原生运行器当前覆盖目录、授权、售卡快照及权益事件4类场景；新能力在对应批次补充独立数据库验收，不能用已有场景代替新业务。改shop-core后需按Java规范install再验证运行实例；最终装配验证用新产物，不能依赖旧.m2或正在运行的旧jar。

## 3. 数据库、API与Java类覆盖对账

### 3.1 目标逻辑表到任务

下列每行同时负责对应实体及Mapper；正式迁移号实施时分配，不提前假设V426仍空闲。原表已有时采用增量扩展并保留历史消费者。

| 模块 | 目标表 | 实体 / Mapper | 主要任务 | 当前状态 |
|---|---|---|---|---|
| promotion | pmt_preset_template | PmtPresetTemplate / PmtPresetTemplateMapper | JB-028 | DONE（core实体/Mapper及V430/V431/V432；不代表pay联动完成） |
| promotion | pmt_activity | PmtActivity / PmtActivityMapper | JB-029 | DONE（core实体/Mapper及V430/V431/V432；不代表pay联动完成） |
| promotion | pmt_activity_version | PmtActivityVersion / PmtActivityVersionMapper | JB-029 | DONE（core实体/Mapper及V430/V431/V432；不代表pay联动完成） |
| promotion | pmt_activity_scope | PmtActivityScope / PmtActivityScopeMapper | JB-029 | DONE（core实体/Mapper及V430/V431/V432；不代表pay联动完成） |
| promotion | pmt_activity_audience | PmtActivityAudience / PmtActivityAudienceMapper | JB-029 | DONE（core实体/Mapper及V430/V431/V432；不代表pay联动完成） |
| promotion | pmt_recharge_tier | PmtRechargeTier / PmtRechargeTierMapper | JB-029 | DONE（core实体/Mapper及V430/V431/V432；不代表pay联动完成） |
| promotion | pmt_activity_budget | PmtActivityBudget / PmtActivityBudgetMapper | JB-031 | DONE（core实体/Mapper及V430/V431/V432；不代表pay联动完成） |
| promotion | pmt_activity_participant | PmtActivityParticipant / PmtActivityParticipantMapper | JB-031 | DONE（core实体/Mapper及V430/V431/V432；不代表pay联动完成） |
| promotion | pmt_activity_reservation | PmtActivityReservation / PmtActivityReservationMapper | JB-031 | DONE（core实体/Mapper及V430/V431/V432；不代表pay联动完成） |
| promotion | pmt_effect_projection | PmtEffectProjection / PmtEffectProjectionMapper | JB-044 | DONE（core实体/Mapper及V430/V431/V432；不代表pay联动完成） |
| promotion | pmt_command_dedup | PmtCommandDedup / PmtCommandDedupMapper | JB-031 | DONE（core实体/Mapper及V430/V431/V432；不代表pay联动完成） |
| product | prd_entitlement_binding | PrdEntitlementBinding / PrdEntitlementBindingMapper | JB-039 | DONE，V444及商品绑定实体/Mapper已验收；售卡交易仍属JB-040 |
| pay.entitlement | ent_template | EntTemplate / EntTemplateMapper | JB-004 | DONE，V425 |
| pay.entitlement | ent_template_version | EntTemplateVersion / EntTemplateVersionMapper | JB-004 | DONE，V425 |
| pay.entitlement | ent_issue_policy | EntIssuePolicy / EntIssuePolicyMapper | JB-015 | DONE，V433/V434/V435及真实pay Mapper |
| pay.entitlement | ent_issue_recipient | EntIssueRecipient / EntIssueRecipientMapper | JB-015 | DONE，V433/V434/V435及真实pay Mapper |
| pay.entitlement | ent_issue_batch | EntIssueBatch / EntIssueBatchMapper | JB-017 | DONE，V433/V434/V435及真实pay Mapper |
| pay.entitlement | ent_grant | EntGrant / EntGrantMapper | JB-016 | DONE，V433/V434/V435及真实pay Mapper |
| pay.entitlement | ent_sale_reservation | EntSaleReservation / EntSaleReservationMapper | JB-040 | DOING，V450付款前容量承诺及转发行内核；应用/RPC协调待接 |
| pay.entitlement | ent_instance | EntInstance / EntInstanceMapper | JB-016 | DONE，V433/V434/V435及真实pay Mapper |
| pay.entitlement | ent_period_bucket | EntPeriodBucket / EntPeriodBucketMapper | JB-042 | DONE，V436，pay事务与实体Mapper已验证 |
| trade | ord_ent_order_use | OrdEntOrderUse / EntitlementOrderUseMapper | JB-020 | DONE，V438，订单券持久协调及实际订单付款/关单已验证；父任务仍DOING |
| trade | ord_ent_payment_seal | OrdEntPaymentSeal / EntitlementPaymentSealMapper | JB-020 | DONE，V438，收银前金额封存、历史收款拒绝改价已验证 |
| pay.entitlement | ent_use_intent | EntUseIntent / EntUseIntentMapper | JB-020 | DONE，V436，pay事务与实体Mapper已验证 |
| pay.entitlement | ent_use_allocation | EntUseAllocation / EntUseAllocationMapper | JB-020 | DONE，V436，pay事务与实体Mapper已验证 |
| pay.entitlement | ent_usage | EntUsage / EntUsageMapper | JB-020 | DONE，V436，pay事务与实体Mapper已验证 |
| pay.entitlement | ent_ledger | EntLedger / EntLedgerMapper | JB-016 | DONE，V433/V434/V435及真实pay Mapper |
| pay.entitlement | ent_code_token | EntCodeToken / EntCodeTokenMapper | JB-023 | DONE，V440；本人签发、绑定解析、摘要存储、过期撤销及并发已验证 |
| pay.storedvalue | sv_account | SvAccount / SvAccountMapper | JB-035 | DONE，V445/V446及实体/Mapper已落地；端到端父任务仍DOING |
| pay.storedvalue | sv_recharge | SvRecharge / SvRechargeMapper | JB-034 | DONE，V445/V448及实体/Mapper已落地；真实来源父任务仍DOING |
| pay.storedvalue | sv_lot | SvLot / SvLotMapper | JB-035 | DONE，V445及实体/Mapper已落地；端到端父任务仍DOING |
| pay.storedvalue | sv_hold_allocation | SvHoldAllocation / SvHoldAllocationMapper | JB-036 | DOING，V449及实体/Mapper已进入未提交工作区；待迁移/事务验收 |
| pay.storedvalue | sv_voucher | SvVoucher / SvVoucherMapper | JB-035 | DONE，V445及实体/Mapper已落地；端到端父任务仍DOING |
| pay.storedvalue | sv_entry | SvEntry / SvEntryMapper | JB-035 | DONE，V445及实体/Mapper已落地；端到端父任务仍DOING |
| pay.storedvalue | sv_user_store_index | SvUserStoreIndex / SvUserStoreIndexMapper | JB-037 | DONE，V445及实体/Mapper已落地；消费/充值投影与查询仍DOING |
| pay.entitlement | ent_owner_registry | EntOwnerRegistry / EntOwnerRegistryMapper | JB-048 | DOING，V458已落实登记/解析基础；兼容切换及工具后续 |
| pay.entitlement | pay_ent_outbox | PayEntOutbox / PayEntOutboxMapper | JB-014 | DONE；V429，提交后桥接sys_outbox；SENT=统一箱已接管 |
| pay.entitlement | pay_ent_inbox | PayEntInbox / PayEntInboxMapper | JB-014 | DONE；V429，pay端计算摘要，唯一消费键同事务去重 |
| pay.entitlement | pay_ent_exception | PayEntException / PayEntExceptionMapper | JB-045 | DOING，V452及捕获/平台查询内核，恢复及HTTP待接 |
| pay.entitlement | pay_ent_exception_action | PayEntExceptionAction / PayEntExceptionActionMapper | JB-045 | DOING，V453动作审计及原号恢复内核，HTTP待接 |
| pay.entitlement | pay_ent_command_dedup | PayEntCommandDedup / PayEntCommandDedupMapper | JB-004 | DONE，V425 |
| pay.entitlement | ent_use_reversal | EntUseReversal / EntUseReversalMapper | JB-022 | DONE，V439；原成功记录/额度/反向账/事件同pay事务 |

基础47张之外，支撑DDL与数据库字典第七节有以下9张，目标合计56张（core22/pay34）；正式迁移V425为目录三张、V426为会员凭证一张。恢复租约复用既有V91 shedlock，不另建空壳表。

| 模块 | 支撑表 | 计划实体 / Mapper | 责任任务 | 状态 |
|---|---|---|---|---|
| core.member | mbr_identity_ticket | MbrIdentityTicket / MemberIdentityTicketMapper | JB-010 | DONE，V426 |
| core.trade | ord_ent_authorization | OrdEntAuthorization / TradeMappers.EntitlementAuthorizationMapper | JB-011 | DONE，V427 |
| core.trade | ord_ent_source_decision | OrdEntSourceDecision / TradeMappers.EntitlementSourceDecisionMapper | JB-011 | DONE，V427 |
| core.trade | ord_card_sale_snapshot | OrdCardSaleSnapshot / TradeMappers.CardSaleSnapshotMapper | JB-012 | DONE，V428 |
| pay.entitlement | pay_ent_recon_run | PayEntReconRun / PayEntReconRunMapper | JB-046 | DOING，V456；四轴对账内核及O05已接，完整验收待收口 |
| pay.entitlement | pay_ent_recon_diff | PayEntReconDiff / PayEntReconDiffMapper | JB-046 | DOING，V456；四轴对账内核及O05已接，完整验收待收口 |
| pay.entitlement | ent_sale_reservation | EntSaleReservation / EntSaleReservationMapper | JB-040 | DOING，V450；支付前承诺及原号接管已接 |
| pay.entitlement | pay_ent_exception_action | PayEntExceptionAction / PayEntExceptionActionMapper | JB-045 | DOING，V453/454/461；原grant/原事件恢复审计已接 |
| pay.migration | ent_migration_stage | EntMigrationStage / EntMigrationStageMapper | JB-050 | DOING，V462；加密源证据暂存，未开放导入 |

### 3.2 API到任务

下表保留原编号，并补充本轮新增操作。当前API主契约有58条文档化公开路由；本表不再把最初49条当作完整覆盖数。内部12项是领域协议负责人。JB-041建立新增内部写及query的远程承载；后续批量/运营/售卡任务同时扩展对应门面及往返测试，不等待单个适配任务替代业务接线。JB-053统一核对路由/权限/OpenAPI。已有URL的目标扩展也标TODO，不否认原功能存在。

| ID | 目标接口/操作 | 交付任务 | 状态 |
|---|---|---|---|
| C01 | GET /biz/activity-presets | JB-028 | DONE（统一模型；C03/C04/C06须model=unified） |
| C02 | GET /biz/card-product-presets | JB-028 | DONE（统一模型；C03/C04/C06须model=unified） |
| C03 | GET /biz/activities | JB-033 | DONE（统一模型；C03/C04/C06须model=unified） |
| C04 | POST /biz/activities | JB-033 | DONE（统一模型；C03/C04/C06须model=unified） |
| C05 | POST /biz/activities/{activityNo}/publish | JB-033 | DONE（统一模型；C03/C04/C06须model=unified） |
| C06 | PUT /biz/activities/{activityNo}/status | JB-033 | DONE（统一模型；C03/C04/C06须model=unified） |
| C07 | POST /biz/card-product-bindings | JB-039 | DONE |
| C08 | POST /biz/member-code/resolve | JB-010 | DONE |
| C09 | POST /mp/orders/{orderNo}/entitlement-authorizations | JB-011 | DONE |
| C10 | GET /biz/marketing-summary | JB-044 | DOING（ORDER及pay实收/到账/核销/发行分项已接，完整验收待收口） |
| C11 | POST /mp/member-code | JB-010 | DONE |
| P01 | GET /biz/entitlement-templates | JB-025 | DONE |
| P02 | POST /biz/entitlement-templates | JB-025 | DONE |
| P03 | PUT /biz/entitlement-templates/{templateNo}/draft | JB-025 | DONE |
| P04 | POST /biz/entitlement-templates/{templateNo}/publish | JB-025 | DONE |
| P05 | PUT /biz/entitlement-templates/{templateNo}/status | JB-025 | DONE |
| P06 | PUT /biz/entitlement-templates/{templateNo}/issue-policy | JB-025 | DONE |
| P07 | POST /biz/entitlement-issue-batches | JB-026 | DONE |
| P08 | GET /biz/entitlement-issue-batches/{batchNo} | JB-026 | DONE |
| P09 | GET /mp/entitlements | JB-027 | DONE |
| P10 | GET /mp/entitlements/{instanceNo} | JB-027 | DONE |
| P11 | POST /mp/entitlement-claims | JB-026 | DONE |
| P12 | POST /biz/entitlement-uses/preview | JB-026 | DONE |
| P13 | POST /biz/entitlement-uses | JB-026 | DONE |
| P14 | GET /biz/entitlement-uses/{operationNo} | JB-026 | DONE |
| P15 | POST /mp/entitlement-quotes | JB-026 | DONE |
| P16 | POST /mp/entitlements/{instanceNo}/activation-quotes | JB-026 | DONE |
| P17 | GET /mp/entitlements/{instanceNo}/allowances | JB-027 | DONE |
| P18 | POST /biz/stored-value/recharges | JB-038 | DOING |
| P19 | POST /biz/stored-value/recharges/{rechargeNo}/submit | JB-038 | DOING |
| P20 | POST /biz/stored-value/recharges/{rechargeNo}/receive | JB-038 | DOING |
| P21 | POST /biz/stored-value/recharges/{rechargeNo}/cancel | JB-038 | DOING |
| P22 | POST /biz/stored-value/consumptions | JB-038 | DOING |
| P23 | GET /mp/stored-value/stores | JB-038 | DOING |
| P24 | GET /mp/stored-value/consumptions | JB-038 | DOING |
| P25 | GET /mp/entitlement-usage/{usageNo} | JB-027 | DONE |
| P26 | GET /biz/entitlement-usage | JB-027 | DONE |
| P27 | GET /biz/stored-value/recharges/{rechargeNo} | JB-038 | DOING |
| P28 | GET /biz/stored-value/accounts | JB-038 | DOING |
| P29 | GET /biz/entitlements/{instanceNo} | JB-027 | DONE |
| P30 | POST /mp/entitlements/{instanceNo}/redeem-codes | JB-026 | DONE |
| P31 | POST /biz/stored-value/consumptions/preview | JB-038 | DOING |
| O01 | GET /ops/pay/entitlement-exceptions | JB-045 | DOING（真实ALL/MERCHANT会话聚合、分页已接；父项待验收） |
| O02 | GET /ops/pay/entitlement-exceptions/{exceptionNo} | JB-045 | DOING（HTTP/权限/定向测试已接，父项验收待收口） |
| O03 | POST /ops/pay/entitlement-exceptions/{exceptionNo}/retry-grant | JB-045 | DOING（HTTP/权限/定向测试已接，父项验收待收口） |
| O03b | POST /ops/pay/entitlement-exceptions/{exceptionNo}/retry-event | JB-045/051 | DOING（原事件重排、审计及SENT关闭已接） |
| O08 | GET /ops/pay/entitlement-dead-letters | JB-045/051 | DOING（仅ALL会话安全元数据及签名游标已接） |
| O04 | POST /ops/pay/entitlement-exceptions/{exceptionNo}/query-source | JB-045 | DOING（HTTP/权限/定向测试已接，父项验收待收口） |
| O05 | GET/POST /ops/pay/entitlement-reconciliations（及详情/差异） | JB-046 | DOING（四轴对账/分页/权限已接） |
| O06 | GET /ops/notify-logs；GET /ops/inapp-messages | JB-043 | DOING（外部渠道/INAPP分别复用既有日志入口） |
| O07 | POST /ops/pay/entitlements/{instanceNo}/freeze；GET .../lifecycle | JB-047 | DOING（冻结/解冻/状态/审计及原生幂等已验） |
| I01 | ActivityParticipationPort.reserve(ReserveActivity) | JB-031 | DOING（core实现及内部HTTP已完成；真实pay源适配待第二阶段） |
| I02 | ActivityParticipationPort.confirm(ConfirmParticipation) | JB-031 | DOING（core实现及内部HTTP已完成；真实pay源适配待第二阶段） |
| I03 | ActivityParticipationPort.release(ReleaseParticipation) | JB-031 | DOING（core实现及内部HTTP已完成；真实pay源适配待第二阶段） |
| I04 | ActivityParticipationPort.query(SourceRef) | JB-031 | DOING（core实现及内部HTTP已完成；真实pay源适配待第二阶段） |
| I05 | EntitlementGrantPort.prepare(GrantCommand) | JB-016 | DOING（pay本地协议已完成；041远程承载待接） |
| I06 | EntitlementGrantPort.commit(GrantDecision) | JB-016 | DOING（pay本地协议已完成；041远程承载待接） |
| I07 | EntitlementGrantPort.abort(AbortGrant) | JB-016 | DOING（pay本地协议已完成；041远程承载待接） |
| I08 | EntitlementUsePort.prepare(UseCommand) | JB-020 | DOING（pay本地协议已完成；订单券核心协调已接；服务核销/储值协调及041远程承载待接） |
| I09 | EntitlementUsePort.commit(UseDecision) | JB-020 | DOING（pay本地协议已完成；订单券核心协调已接；服务核销/储值协调及041远程承载待接） |
| I10 | EntitlementUsePort.release(CancelUse) | JB-020 | DOING（pay本地协议已完成；订单券核心协调已接；服务核销/储值协调及041远程承载待接） |
| I11 | EntitlementUsePort.reverse(OrderBenefitReverse) | JB-022 | TODO |
| I12 | StoredValuePort.credit(CreditRecharge) | JB-035 | DOING（到账/两种承载/正常活动闭环已接） |

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

### 4.1 当前执行队列（活动优先）

1. 已完成JB-008–014及JB-019等公共基础，保持现有成果，不重复开发。
2. 第一阶段顺序：JB-028预置目录 → JB-029活动草稿/不可变版本/规则 → JB-030定价与场景隔离 → JB-031活动侧参与/预算/限购 → JB-033活动HTTP；按实际需要同步JB-041的core内部传输子项。
3. 第一阶段同步整理JB-044活动自身统计、JB-051活动在途恢复、JB-052活动模板能力验收及JB-053活动接口权限子项。依赖pay成功事实的部分列入第二阶段；第一阶段只开放真实具备执行能力的玩法。
4. 第二阶段：JB-015 → JB-016/JB-017 → JB-020–027/JB-042 → JB-032活动权益协调/JB-018领取 → JB-034–040储值与售卡；JB-039可按依赖提前到本阶段适当位置。
5. 第二阶段按服务补齐JB-041远程适配，完成JB-043–054以及跨阶段任务剩余部分，最后由JB-055做整个Java后端全量回归及收口。

原依赖栏是整项最终验收依赖；第一阶段的子项切分和阶段验收以两阶段执行方案为准，不能通过删除依赖伪造联动能力完成。每个子项记录文件、提交、定向验证和遗留项；父任务只在全部条件满足后DONE。

### 4.2 必须先补的设计缺口（JB-008）

| 缺口 | 本次处理要求 |
|---|---|
| 授权意图/源决策、售卡快照未列入38表 | 已补core三张支撑表、查询/状态与唯一键；正式实体/Mapper/迁移归JB-011/JB-012 |
| O05对账结果、恢复租约缺少完整存储模型 | 已补pay两张对账支撑表；恢复租约复用V91 shedlock，JB-046/JB-051实施 |
| IssuePolicyCommand.issueMode与表字段对应不完整 | 已删除多余issueMode，模板allowedIssueModes决定合法途径；共享跨版本发行授权由JB-015实现 |
| 目录编辑完整详情与历史版本读取的外部契约未完全明确 | P01完整草稿/currentVersionNo、P04定义/hash、内部历史版本读取已冻结；当前49条外部路由 |
| 独立pay反向核实core本人授权/源事实的内部路由与鉴权 | API§11冻结34条内部路由、上下文、共享密钥及未知/失败语义；Handler/代理由JB-041实现 |
| 新售卡经营条款、冻结/到期剩余处理与真实支付能力 | 有限次/周期、首用/付款有效期、禁止新权益线上退款和自动续费已冻结；冻结/到期保留账与历史，不自动退款；销售须能力和经营条款验收后开放，JB-039/JB-040/JB-042 |

### 4.3 后端完成标准与后置事项

Java后端开发完成须满足：本表所有开发任务均按真实交付改DONE；44张目标逻辑表及复用存储的增量迁移/实体Mapper对齐；49条外部及内部写/query全部具备真实Handler/实现/权限/DTO；本地与远程两种部署均通过；美业储值、旧券、售卡、次卡/月卡端到端及故障恢复通过；生成物、架构、i18n及干净提交全量回归有证据。目录可见、编译成功、H2通过或仅有Service接口都不代表后端完成。

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

此前连续编码时计数：15 DONE / 4 DOING / 36 TODO；最新状态见下方第一阶段统一验收。JB-015排入第二阶段。


### 第一阶段连续编码记录（2026-10-19，未验收）

用户要求：整体编码完成后再测试，覆盖本表先前逐项RED/GREEN及小阶段验证执行顺序。当前不运行编译、Java测试或数据库测试，不将此批改动宣称可运行。

- JB-028：已写V430目录及46条默认禁用预置、PmtPresetTemplate/Mapper、ActivityPresetService/Impl与C01/C02商家入口，支持分类/场景/业态及分页。能力readiness接线仍待阶段内后续工作；现阶段全部保守不可执行。
- JB-029：已写统一类型化定义与ActivityRuleValidator；V431准备不可变版本、范围、档位、预算/个人参与、参与凭证、统计投影与core命令幂等存储；对应实体/Mapper已创建。发布/状态服务、参与逻辑及HTTP接线尚未完成。
- 全阶段仍编码中，JB-030/031/033及活动侧收口子项尚未完成；所有本批验证均待执行。


### 第一阶段连续编码记录二（2026-10-19，未编译/未测试）

- JB-029：新增ActivityActor、ActivityVersionService/Impl、core ActivityCommandDeduplicator；草稿写入、持久幂等、发布不可变快照、scope/tier/budget同事务保存、暂停/结束与门店子集可见性已编码。发布版本保存人群条件快照，引用pay定义通过SPI校验。
- JB-033：新增BizVersionedActivityController。C03/C04/C06及既有详情URL通过`model=unified`明确选择新模型；原调用不带参数仍走原Handler；C05显式发布使用原计划URL。用户/主体/租户/门店权限来自服务端会话。
- JB-030：旧活动列表、详情和定价路径隔离新统一模型；新模型尚未接订单执行，不能因此宣称新ORDER玩法已可交易。
- JB-031：已编码预算/个人参与条件预留与结算、参与凭证CAS Mapper；业务编排、可信决定查询及恢复尚未接齐。
- 新增ActivityCapabilityEvidence是服务端能力证据扩展点；目前尚无已验收provider，预置及发布保持保守关闭，不以Bean存在宣称READY。
- 所有本批代码尚未编译或执行测试。尚需完成参与Service/Port、core内部HTTP、活动统计/恢复、运营治理、目录实例化、能力接线与生成物同步后，才进入统一测试。


### 第一阶段统一验收完成（2026-10-19）

A1–A6的活动侧任务已完成，完整边界、代码落点、命令与结果见[第一阶段交付与验收](./archive/records/活动第一阶段-交付与验收.md)。本批实现提交`14f19f552`（此前目录/版本底座为`d93b45eee`、`9151ad75b`）。JB-028/029/030已DONE；JB-031/033/041/044/051/052/053仅活动侧子项完成，父任务保持DOING。当前55项为18 DONE / 7 DOING / 30 TODO，无BLOCKED。

| 跨阶段父任务 | 第一阶段已完成 | 第二阶段仍需完成 |
|---|---|---|
| 031（跨阶段） | 参与预留/确认/释放/query、可信绑定、名额预算个人限购与安全恢复 | 真实pay grant/recharge来源终态适配和跨服务恢复 |
| 033（跨阶段） | 商家统一版本管理、运营治理与旧URL互斥 | pay联动后的完整管理场景验收 |
| 041（跨阶段） | core活动内部4端点、共享DTO、服务token鉴权 | pay全量路由/远程代理、双部署真实往返 |
| 044（跨阶段） | ORDER优惠事实、分币种/门店汇总、取消剔除 | 发放/充值/消费pay事实 |
| 051（跨阶段） | 活动在途扫描、ShedLock、未知延期不释放 | pay发行/核销/到期恢复与异常闭环 |
| 052（跨阶段） | 46预置真实能力诊断，统一AP009/AP010就绪，旧玩法兼容 | pay执行器及其余明确缺失能力逐项验收 |
| 053（跨阶段） | 活动接口权限/类分层/三语/schema/生成清单对账 | 剩余权益储值API和运营登记总对账 |

统一验证：Java21离线Maven定向151项（core25/app126），0失败/错误/跳过，21项目BUILD SUCCESS；原生MySQL9.7.2独立1项实际运行、0跳过，100次并发中20次成功、每人2次、总预算2000分无超额；生成器回归1项及消融RED→GREEN；24生成器、V2 66份文档、SQL无新增方言债务、Controller聚合守卫与diff检查通过。未运行后端全量；未推送、部署或访问生产数据库。

下一执行项为第二阶段B1：JB-015发行容量与个人限额，其后JB-016 prepare/commit/abort及JB-017批量发行。沿既有任务依赖继续，权益归pay，不在core复制余额或发行账。


第二阶段B1编码记录：JB-015新增V433、发行策略/个人计数实体Mapper与Service/Impl，先写真实事务用例并确认缺类编译RED，再通过test-compile。尚待B1合并验收和原生MySQL，不标DONE；JB-016/017接续开发。


### 第二阶段B1验收（2026-10-19）

JB-015/016/017完成，状态21 DONE / 7 DOING / 27 TODO。复用[第二阶段清单](./任务清单-第二阶段权益卡券储值.md)，详见[B1交付报告](./archive/records/第二阶段B1-发行批次验收.md)。Java57个不同定向用例通过，原生MySQL1项实际运行0跳过；57项由55项合并验证+2项core受众校验组成，敞口/批次/架构复核重跑不重复累计。RR修复后另跑策略3项0失败，原生RR旧视图/当前读取差异断言通过。敞口消融RED（600/200）后恢复GREEN；当前批次未运行全量。

本地批次发行具备真实core会员受众适配；活动/领取/售卡源适配按031/032/018/040接续。MAIN与MERCHANT出资门禁显式保留，不能用本批代码冒称独立pay/其他租户事件已验收。下一执行：041目录/发行控制子项及025，再020/021/042使用领域。

### 第二阶段目录管理接口验收（JB-025 / JB-041目录子项）

P01–P06真实Handler、商家会话scope、平铺请求DTO、BeanValidation与4个pay权限码落地；只有商家级授权可管理跨门店模板，未给预置店长自动加权。列表附发行策略，写响应保留原幂等快照。独立pay管理及目录9个内部POST端点使用共享密钥，错误状态有明确映射；pay-svc补显式payTxManager别名，目录Port本地/远程互斥。Grant/Use/储值传输仍待JB-041后续子项，不将父任务标完成。

验证：`/tmp/phase-two-management-complete.log`，pay-svc2 + shop-app34 =36项通过（HTTP3、路由2、架构16、三语6、角色3、权限登记4）；补齐第一阶段4条遗漏权限登记。首轮HTTP测试2项因无Handler失败；新增架构Port包问题已修复，权限生成器补识别pay前缀。文档守卫66项通过；生成物随本批更新。未做全量测试。

### 第二阶段使用领域批次（JB-020内核 / JB-021 / JB-024 / JB-042）

JB-021/024/042已交付；JB-020保持DOING，真实core源事实及订单协调随后接线，独立部署use/query归JB-041，不以本地领域验证冒充全链路。Use预览/报价记录冻结选择，不激活、不预占；本人授权、源订单明细及金额核实在pay事务外完成。Writer锁意图→实例→原服务日bucket，实例/分配/账/使用事实/Outbox同事务。成功回复丢失重查原号；未知源决策不释放；同源未决修订不能另建预占。

V436新增5表（含ent_use_proposal）及对应实体Mapper，intent额外冻结command_snapshot、active_source_key和usage_no。报价迁入pay，旧mkt/pmt与pay复用CouponCalculation；保留折扣向下取分、CASH忽略percent封顶的既有语义，分摊对舍入尾差逐行约束且守恒。当前沿既有结算仅支持单张订单券，多券明确未开放。订单券预占由quote快照版本控制；卡/兑换沿preview的expectedAssetRevision控制。

生命周期覆盖首用成功提交激活、每日有限次数、源owner指定服务日、释放回原日和到期标记；到期不清剩余/冻结，保留原承诺可结算。到期扫描调度及通知归JB-051/043。Query仅白名单DTO，历史保留；游标绑定租户/本人/筛选且签名，需各节点一致的SHOP_PAY_ENTITLEMENT_CURSOR_KEY（>=32字符）；未配置且需要续页时明确不可用，无随机临时密钥或固定密钥兜底。

验证：本批新增14个Java用例，另覆盖旧券、SPI/架构/schema39个不同用例，共53个不同Java用例通过；最近批次`/tmp/phase-two-b2-domain-final.log`31项，报价/兼容见`/tmp/phase-two-use-query-final.log`50项（含重叠），不叠加冒称81项。`scripts/verify-entitlement-use-mysql.mjs`在一次性原生MySQL实跑1项0跳过，实际Writer/事务验证20并发重放、JSON/CHECK、跨日桶隔离、Outbox故障整笔回滚及重试唯一；最终日志`/tmp/phase-two-use-native-final.log`。测试曾发现毫秒重放精度、测试夹具本地时间冒充UTC及Service分层问题，均已修复。未做全量回归。


JB-020源事实子项进展：core真实EntitlementUseSourcePortImpl、共享订单摘要、两套旧券精确占用事实的app组合，以及pay已冻结使用事实读取SPI已落地。source3由缺provider RED开始，实际订单/账户/门店表验证；类目归属在quote后变化新增回归RED→GREEN，prepare重新计算并对比原分摊。source/auth/use/quote/Architecture合并33项通过（/tmp/phase-two-order-facts-final.log）。SourceFact追加可空serviceAt，pay按原权益时区推导服务日，不按币种猜时区。尚未交付完整订单协调，020仍DOING。


020/051一致性底座增量：V437创建pay_ent_source_fence，发行和使用在prepare/finish前先锁同一来源；可信取消即使早于实体行也会留下负向事实。晚到prepare只能返回原ABORTED/RELEASED，不能重新占容量或余额；UNKNOWN/PENDING不写取消标记。此标记不按UI/网络TTL删除。query及内部UseFacts按归属返回同一终态，不把无实际hold的已取消操作误报成成功消费。

本增量50项Java定向通过（/tmp/phase-two-source-fence-final.log），包括发放8、使用9、批次2、来源4、报价4、授权4、架构16、schema3；另2项最终重放/晚到Writer检查通过（/tmp/phase-two-source-fence-replay.log，包含重叠，不累计）。原生MySQL实跑1项0跳过（/tmp/phase-two-fence-native.log），验证已完成预检的请求在取消后进入实际Writer亦不能冻结资源，并保留并发、跨日、CHECK/JSON、事件失败原子回滚验证。源决策首次响应与数据库时间精度不一致、未知/外租户类目父级被误认成祖先，均先回归RED后修复GREEN。020订单协调及051恢复调度仍未完成。


### 第二阶段：订单券协调执行记录

JB-020仍DOING，新增订单券链路：报价→本人确认→核心持久原命令→pay预占→按冻结行分摊应用价格→现金/零元收银封存→订单终态与源决策/Outbox同事务→pay提交/释放。V438增加核心协调/金额封存两表，无跨库事务。真实订单服务付款、超时关单、事件重复、取消先于预占、已有收款与封存拒绝改价、异常回滚均有实际场景断言。

定向合并56项通过（`/tmp/phase-two-order-coordinator-acceptance.log`）；实际订单及零元事务追加11项通过（`/tmp/phase-two-order-owner-final.log`）。独立原生MySQL目标DDL/约束1项通过、0跳过（`/tmp/phase-two-order-schema-native.log`）。零元付款消融实际RED：预期WAIT_PAY却为PAID；经代理进入markPaid事务后GREEN。没有执行后端全量。服务券/次卡真实核销源、储值扣款与041远程仍按原任务继续。


### 第二阶段：JB-022受限回补执行记录

已完成pay原成功订单券回补协议、ent_use_reversal/Mapper、ReversalService/Impl、ReversalWriter及反向事件；V439。可信售后source绑定原usageNo和全部owner事实；原使用锁串行化，累计不超原优惠，归属未到齐不退整次券；原效期及冻结状态保留。响应丢失按queryReverse原号查询，同售后换号拒绝；既有整单退券策略和旧URL接回仍由049负责。

实际37项合并通过（`/tmp/phase-two-reversal-acceptance.log`）；查询/契约补充20项通过（`/tmp/phase-two-reversal-query-final.log`，含复跑），事件桥接5项通过（`/tmp/phase-two-reversal-relay-final.log`），原生目标DDL/约束1项实际通过、0跳过（`/tmp/phase-two-reversal-native-schema.log`）。没有执行后端全量。JB-023已补5项关键场景，RED为缺CodeViews/CodeService，接下来编码；统一会员码入口维持，兑换专用凭证只作默认关闭的兼容能力，不增加储值专用码或C端独立入口。


### 第二阶段：JB-023兑换凭证执行记录

EntitlementCodeService/Impl、CodeWriter/Signer及EntCodeToken/Mapper已完成，V440；CodeViews明确脱敏toString。默认关闭的code能力独立配置，且要求use开关；签名密钥缺失/变更不另发新码，数据库不持久明码。原请求幂等重建原凭证，60秒内只允许原操作/门店/操作人完全相同的解析重放；无余额扣减、预占或首用激活。付费卡/储值不能签此码。

实际38项合并通过（`/tmp/phase-two-code-acceptance.log`：场景6、签名2、契约11、架构16、schema3）；最终场景6项复跑通过（`/tmp/phase-two-code-final.log`）；原生MySQL目标DDL/消费绑定CHECK 1项通过、0跳过（`/tmp/phase-two-code-native-schema.log`）。文档70（含本轮4份活动设计）、生成物24、SQL无新欠账；无全后端全量。P30及P12/P13门面/041传输仍按原任务交付，C端统一会员码入口没有新增独立兑换/储值码页面。


文档复核补记：更新JB-023时脚本变量复用曾误覆盖API与类清单，已从0d1f64ce7恢复全部原文并追加正确增量。原66份文档守卫范围未含活动根目录的四份TDD，不能作为这四份的验证证据；现已显式纳入，标题身份检查在错误版本实际失败2项，恢复后70份通过。Java、迁移及前述业务测试结果未变。


第二阶段JB-041运行协议批次：25个pay运行POST、6个核心反向事实与1个事件接收端点已实现；
嵌入式/独立式owner互斥绑定，事件持久确认后交接。41项受影响Java用例通过（含8项运行HTTP、
3项真实核心HTTP、8项订单协调、6项事件），文档70/生成器24/SQL守卫通过；未运行全量。
本批没有新增迁移。JB-041仍DOING，储值/售卡接线及最终双部署演练尚待后续任务；
JB-020仍DOING，服务单核销与原积分分摊重算尚未完成。总计保持27 DONE / 8 DOING / 20 TODO。


第二阶段B3活动权益与领取验收：JB-018/032完成，JB-026进入DOING（P11及查询完成，其余商家核销/批次与C报价入口待补）。
V441新增核心真实来源pmt_benefit_intent；同一活动完整权益包只占一个营销名额，子发放原号恢复。
CENTER/ACTIVITY完整冻结源事实、全部prepare后决定、终态核实、本人HTTP、只读查询、持久决定事件均已实现。
AP004可在预置显式启用、实际商家准入及功能开关满足时正常草稿/发布/领取；其他未实现执行器保持关闭。
真实发布验收发现并修正core→pay模板引用用途ACTIVITY与ACTIVITY_GRANT不一致。
本批合计54项不同定向Java用例通过（权益联动11、策略3、原grant8、参与5、架构16、pay边界4、C鉴权7）；
原生目标DDL1项通过、0跳过，文档70及生成物24守卫通过；未运行后端全量。
当前55项：29 DONE / 9 DOING / 17 TODO。JB-031仍待充值终态，JB-041仍待储值/售卡承载，均不提前标完成。


第二阶段查询批次JB-027完成；P09/P10/P17/P25/P26/P29与P16/P30承载均已接入。
C身份从会话构造，详情主体可选、仍严格校验本人；B记录按逐门店读取权限过滤，指定卡详情需真实会员码识别引用。
共享merchant-usage内部POST区分null全主体与空授权集合；新增pay:ent:read权限不自动授予预置店长。
47项不同定向Java用例通过，覆盖6项真实B/C HTTP、3项领域查询、9项运行RPC及权限/架构/路由；
命令与证据记在执行账本，未运行全量。无新增迁移，V441保持不变。
当前55项为30 DONE / 9 DOING / 16 TODO；JB-026仍待P07/P08/P12/P13/P14/P15，JB-020仍待真实服务核销源与储值协调。


第二阶段JB-026补齐P07/P08商家批量发放与原批次查询。支付原事务保存批次/全部准备承诺/批次准备事件，
核心按持久事件推进原游标，单次resume最多100项，正常分段最多10轮；查询不发放，重复事件不多发。
真实会员人群→商家HTTP→pay→core HTTP事件接收→重复消费→完成已通过，独立pay装配通过。
本批33项不同定向Java用例通过（批次HTTP1、领域3、事件6、权限4、架构16、独立启动3），
文档70/生成物24及SQL守卫通过，无新增迁移。P12/P13/P14/P15仍待真实服务源与使用协调。
总计保持30 DONE / 9 DOING / 16 TODO；026仍DOING，不以新增两条入口提前关闭父任务。


第二阶段真实服务核销源及P12/P13/P14已完成：核心新增V442服务单，实际服务SKU/门店/会员事实，
C09复用本人授权，B提交后支付扣次；查询只读，取消先到也能阻止迟到准备。可选兑换码仍默认关闭，
仅定位原服务单且必须本人确认；C本人待确认列表及详情补齐，无现金收入、无本金/赠金和线上退款。
53项不同定向Java用例通过（服务3、HTTP2、原授权5、原协调8、源事实5、核心HTTP3、架构16、B权限4、C鉴权7）；
目标MySQL DDL1项实际通过、0跳过，文档70/生成物24及SQL守卫通过。架构发现Controller注入ObjectMapper触发边界守卫，
已将稳定操作编号生成留在核心服务，未放宽守卫。未运行全量回归。
当前保持30 DONE / 9 DOING / 16 TODO；026剩P15，020剩储值协调；实物赠品库存履约仍需能力接入。


第二阶段JB-026完成：P15已接本人实际订单报价；C09对订单券同事务冻结授权、原命令及准备事件，
使用进度与授权状态分别返回；原支付价格封口仍生效。准备事件消费达到已应用价格的HELD即可，
终态事件必须COMMITTED/RELEASED。服务核销仍需B提交，不因C确认自动扣次。
本批44项不同定向Java通过（报价HTTP3、服务HTTP2、服务3、原授权5、原协调8、架构16、C鉴权7），
目标MySQL迁移/字段对齐1项实际通过0跳过。消融去掉外层事务时，outbox失败后授权错误保留AUTHORIZED，
恢复事务后3个HTTP场景通过。鉴权探针补合法请求体后7项通过，未放宽鉴权或守卫。
文档70/生成物24/SQL守卫通过，无全量测试。当前55项：31 DONE / 8 DOING / 16 TODO；
JB-020仍DOING，后续推进039商品绑定及034–038储值，不把可配置付费卡视为已完成售卡。

第二阶段JB-039完成：真实CARD商品主市场SKU绑定已发布PAID_CARD版本，保存不可变规则副本及摘要；
不改SKU售价，模板新草稿/发布不自动改绑定，每件实例数与卡内次数分开。原命令回执由商品域独立保存，
不写营销域幂等表。同键异参409，新请求旧商品修订409；并发同修订只有一个成功。
本批21项不同定向Java通过（实际C07 HTTP/领域/并发综合1、架构16、B权限4），目标MySQL迁移/约束/字段对齐
1项实际通过0跳过；消融参数摘要后同键异参不拒绝的测试变红，恢复后通过。
数据域修复沿商品原有约定：店主SELF会话下，仅在显式租户/主体/商品/SKU归属查询内豁免自动SQL数据域，
不放宽会话权限或全局过滤。文档70/生成物24/SQL守卫通过，V444新增2张运行表，运行总数240，目标逻辑51。
当前55项：32 DONE / 8 DOING / 15 TODO。付款前发行占用、真实付款后发卡及取消仍属JB-040，未冒称已完成。


第二阶段JB-035完成首个内部到账内核切片，父任务仍DOING：原pay已实收充值单→账户/原批次→凭证与分录→
分池平衡管理备查账→成功储值历史目录→原到账事件同事务写入。已有实收承诺不因新业务开关关闭停账，
冻结账户可记录已欠到账；消费权限另行处理。不开放实收HTTP，未以领域收款记录夹具冒称实际活动办理完成。
21项不同定向Java通过（到账4、架构16、pay事务约定1）；原生MySQL实际生产过账服务1项通过、0跳过，
目标迁移字段/约束对齐1项通过、0跳过。原生并发发现并修复RR旧快照读不到已到账凭证/批次的重放冲突，
改为账户锁后的当前读。去掉备查分录持久化后测试实际变红（期望4行、实为0），恢复后通过。
币种及主体隔离、账号累计溢出回滚、事件失败回滚、20次并发原号到账一次均已验证。
V445新增7张pay表（含新增管理备查分录），运行表247，逻辑目标52；sv前缀明确归支付储值账务，未新增未归域欠账。
文档70/生成物24/SQL守卫通过，未全量测试。当前32 DONE / 9 DOING / 14 TODO，034真实活动源与人工实收尚待接通。


第二阶段JB-040完成先行结算校验，仍DOING：已绑定新付费卡在实际预览/建单/代客公用split中，发行流程
未就绪时返回明确能力错误，发生在库存预占和订单写入之前。禁用或软删绑定也不能退回旧售卖路径。
实际RED证明此前可经旧入口建单；GREEN后新绑定拒绝且订单/库存无变化，未绑定旧CARD仍可实际建单。
本批25项不同定向Java通过（绑定/真实结算/历史绑定综合1、原消费者订单8、架构16）；补充禁用/软删及
旧CARD实际建单后单项重跑通过。无新数据库迁移、权限或HTTP路由，文档70/生成物24/SQL守卫通过。
当前32 DONE / 10 DOING / 13 TODO；040的付款前发行预留、真实付款后逐件发卡和取消恢复继续待办。


JB-035到账内核补充总额上限：增加V446独立CHECK，V445不改；应用及实际H2/MySQL均拒绝两池单独合法、
合计溢出的入账。新回归先实际变红（旧实现允许合计Long.MAX_VALUE+1），修复后21项定向Java通过；
原生实际过账1项、目标迁移1项均通过且0跳过。测试库生成器补严格ADD CHECK保留，相关生成器测试先红后绿，
未知ALTER仍报错。运行表/目标表数量不变，父任务035仍等实际活动与人工实收整链路接通后验收。

#### JB-034/035真实来源与人工实收子批次（尚未关闭父任务）

V447在core新增pmt_recharge_intent，冻结真实会员引用、门店、市场币种、活动版本档位及参与许可；源行、营销预留和Prepared Outbox同事务，原请求重放不依赖会员引用TTL。V448在pay补独占收款凭据与未实收取消事实。新StoredValueRechargeService/Writer真实执行create/submit/receive/cancel，实收决定和Received事件同事务，到账继续使用既有唯一信用账序与平衡管理分录；不产生平台收款或现金结算收入。先取消后创建写完整来源栅栏，不能重新收款。

定向Java：真实源/人工收款/到账/营销确认与反向HTTP 6项、路由2项、架构16项、pay事务1项、原支付HTTP5项通过，共30项；未跑全量。临时MySQL使用真实支付Writer/事务，验证20并发到账、实收Outbox失败回滚、同凭据重复实收拒绝、实收/取消竞争和迟到创建栅栏，1项实跑0跳过。53张目标DDL及V447/V448迁移字段/约束原生验证1项实跑0跳过；H2运行表248张。文档70/生成器24/SQL守卫通过。

本批没有将接口声明算作业务闭环：新的6条pay充值RPC已编译，但其真实HTTP专项、应用Outbox协调、公共B/C接口、正常预置活动发布路径和储值消费仍待后续批次。JB-034/035/041保留DOING。当前32 DONE / 11 DOING / 12 TODO。V447/V448本批验收后冻结，后续追加迁移。

#### 储值应用协调与商家办理入口子批次

新增StoredValueRechargeCoordinator及Prepared/Received/Cancelled/Credited订阅，按原intent/recharge/实收决定恢复，不建立跨core/pay事务。pay实收答复RECEIVED，到账由持久事件推动；只有实际CREDITED才能确认营销参与，失败保留原实收及营销占用。迟到Prepared遇到CANCELLED只校验原取消与释放，不重开单。core来源终态从本库真实Participation读回，来源已存在但pay未应答显示PROCESSING，不能提示商家收款。P27只读，不驱动到账。

BizStoredValueController已接P18/P19/P20/P21/P27；create/receive/read三项明确权限与原门店交集分别检查，用户、主体、确认人由会话确定，不回传凭据或本金赠金。新权限不隐含授予MANAGER。新6条pay充值RPC完成真实HTTP专项（服务函数替身只隔离传输测试），真实业务和数据库事务仍由H2端到端与此前原生MySQL验证证明。正确币种、原许可、本人、门店、金额及实收决定时间完整核对；非空错误/无关事实不算成功。

最终定向：StoredValueRechargeSourceFlowTest 9项（含真实商家登录、只读门店权限、失败恢复与实际Received事件HTTP交接）、路由2项、BizPerms10项、架构16项、pay事务1项通过，共38项；pay-svc运行协议12项另通过（其中3项新增储值专项），合计50项。未运行全量。前两次验证分别发现测试误用sv_voucher.recharge_no（实际biz_no）及编码方法名误写，已修正测试，未修改生产规则迎合测试。

JB-038进入DOING，初始商家办理接口已接入，后续消费与C端接口随后续编码推进。JB-034/035待正常预置活动发布/储值消费验收后关闭，JB-041待剩余完整协议及部署切换验证。当前32 DONE / 12 DOING / 11 TODO；无push/部署。

#### 储值核销与本人查询继续编码（2026-10-19，待阶段验收）

JB-036新增pay核销报价、预占、确认/释放、批次分配与管理分录实现；V449为预占批次分配表。JB-037补齐充值/核销门店活动投影、余额汇总、仅已提交核销记录读取、按最后活动时间倒序的本人门店目录及签名游标分页。JB-038新增商家账户查询和服务单储值预览/核销，以及C端本人门店目录与消费记录读取；金额池拆分不进入C端DTO，消费使用已有会员身份，不添加独立核销码。

补齐pay内部查询/报价RPC和embedded/standalone两种owner适配；路由夹具同步增加StoredValueQueryService、StoredValueRedemptionService及远程报价Port依赖。登记商家储值计价预览P31，P22/P23/P24/P28/P31均为DOING；P28 app DTO不暴露pay账户号或双池，C端门店/核销记录路由补入登录鉴权清单，V449对应H2测试schema由迁移生成器重建为249表。尚未运行Java测试或编译，故本批只记录为代码切片，不作通过或完成声明；下一批集中完成并按约定做阶段性精简测试。父项仍为32 DONE / 12 DOING / 11 TODO，无提交或推送。


#### 本轮编译与基本接线验证（2026-10-19）

按用户最新要求恢复“每轮编译+受影响基本测试”。修复StoredValueQueryServiceImpl错误的AssetKind导入；消费夹具编号改为兼容既有32位字段，借贷守恒断言通过凭证关联分录核对。新增真实商家/C端HTTP场景与协调后查询场景；补齐独立pay的余额/门店/记录/报价HTTP往返测试及测试装配依赖。

真实HTTP回归证明：报价→C09本人确认→P22核销→P23门店目录/P24本人记录可闭合，重复核销只保留一条消费；其他消费者查不到该记录。短期会员引用过期后的重放曾实际失败（原COMMITTED结果变成无data），修正后原操作恢复成功；同一过期引用不得创建新操作。P28不返回pay accountNo或双池。原操作者/主体/门店、原payload及原授权仍由持久服务单校验。

验证命令：Java21环境下 `mvn -o -f backend/pom.xml -pl shop-app,pay/pay-svc -am test -Dtest=StoredValueAllocationPolicyTest,StoredValueConsumptionFlowTest,StoredValueConsumptionHttpFlowTest,EntitlementRuntimeRoutingTest,EntitlementRuntimeInternalApiRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false`。受影响22模块构建成功，退出0；23项测试（3+4+1+2+13）0失败/0错误/0跳过。日志：`/tmp/sv-current-final.log`；RED日志：`/tmp/sv-expiry-red.log`。未运行全量或本批原生MySQL并发验收，JB-036/037/038/041仍DOING，不以定向通过关闭父任务。当前总计32 DONE /14 DOING /9 TODO；尚未提交/推送。


#### 后续接线：目录分页与付费卡来源模式（2026-10-19）

JB-037补数据库级查询验证：仅has_recharge=1，最近活动倒序，同时间按store_no稳定分页；相同游标可重放，跨用户/跨查询用途拒绝。基本场景通过，乱序及原生边界仍待验收。

JB-040发现JB-012已持久快照的PAID_CARD_ORDER与发行规则PAID_ORDER不一致。裁定：保留已有sourceRef与来源键，通过GrantCommand.issueMode()映射规则；两种付费来源均要求paidFactRef、完整单件索引、quantity=1且无活动permit。新增测试先因缺issueMode编译失败（/tmp/card-source-red.log），实现后通过。没有接入真实付款provider、前置发行预留、逐件发行与取消，没有开放售卡；这些仍是接下来的任务。

最终Java21命令与上一批一致，额外加入CardSaleSnapshotTest；22模块编译成功，32项定向测试（8+3+13+1+5+2）0失败/0错误/0跳过，退出0。日志/tmp/followup-final.log。git diff --check通过，未全量、未原生数据库验证、未提交或推送。JB-037/040/041保持DOING，总计仍32 DONE /14 DOING /9 TODO。


#### JB-040真实付费卡来源切片（2026-10-19）

新增core.trade.port.CardSaleGrantSourcePortImpl，复用既有EntitlementGrantSourcePort及内部grantSource/grantDecision路由。来源必须匹配已持久快照的租户/主订单/订单行/单件/件内序号、模板、受益人、主体、hash与付款引用；主订单付款投影的币种、用户、原paidAt和paidFactNo必须一致。当前现金路径明确为WECHAT/ALIPAY/OFFLINE，正金额才可确认；未知、FREE、POINTS、STORED_VALUE不能作为现金证明。未付款保持PENDING，不凭状态猜取消。成功决策沿原来源键与付款时间稳定派生。

新增5项来源单元测试及1项真实H2场景：实际capture两件×两权益得到4条来源，主订单付款后markPaidOnce实际更新4行，重复通知更新0行，逐来源resolve/decision保留原付款时间，改储值通道后拒绝。临时移除通道限制时负向测试真实失败（Expecting code to raise a throwable）；恢复后通过，消融日志/tmp/card-channel-ablation.log。

最终Java21构建受影响22模块成功，38项基本/接线测试（8快照+5来源+3分配+13内部HTTP+1储值公共HTTP+1真实售卡来源DB+5储值场景+2路由）0失败/0错误/0跳过，退出0。命令在前批基础上加入CardSaleGrantSourceFlowTest、CardSaleGrantSourceTest；日志/tmp/card-source-final.log。git diff --check通过；本批无新迁移、无全量/原生MySQL测试、无提交或推送。

JB-040/041继续DOING：真实来源已接，付款前发行预留、付款后逐件发卡及取消恢复尚未接齐。裁定：不能给未付款订单伪造paidAt或paidFactRef来调用现有grant.prepare；下一子项须将前置容量预留与已付款发行转换明确分开，沿既有发行控制实现，销售门禁在闭环前保持关闭。整体仍32 DONE /14 DOING /9 TODO。


#### JB-040预留释放前置：真实取消终态（2026-10-19）

为付款前容量预留补齐可安全释放的来源依据。本批新增CardSaleSnapshotService.recordCancelledFromOrder，MANDATORY加入原core事务并锁真实主单，按单件/件内权益复用ord_ent_source_decision写GRANT_ABORT；接入OrderServiceImpl买家/平台取消及超时关闭两条路径。只有持久取消决策与主单/快照匹配时来源查询才返回CANCELLED，单纯关闭、查不到付款仍PENDING；已取消后出现矛盾的付款事实，resolve与decision均冲突拒绝，不转成功。

回归先因缺recordCancelledFromOrder编译失败（/tmp/card-cancel-red.log）；实现后新增真实H2回归证明订单终态与逐权益决策同事务回滚、重复取消保留原决策，以及迟到付款不能改写取消。第一次合并验证发现新夹具复用全局唯一订单行OL1，修正夹具为唯一订单行，不修改数据库约束。现有快照单元夹具同步新的决策写入依赖。

验证：Java21受影响22模块编译成功，原付费卡/储值精简集39项全部通过，0失败/错误/跳过（/tmp/card-cancel-verified.log）。因改动通用订单取消，额外仅跑3项既有冒烟：M3TradeFlowTest#expiredOrderIsClosedAndStockReleased、AppointmentSlotFlowTest#orderBooksAndCancelReleases、EntitlementOrderCoordinatorFlowTest#durableCancellationBeforeLatePrepareCreatesANegativePayFence，全部通过（/tmp/card-cancel-order-smoke.log）。强化回滚异常必须为预设rollback proof后，CardSaleGrantSourceFlowTest两项重跑通过（/tmp/card-cancel-rollback-verified.log），不重复累计；本轮共42项不同用例通过，git diff --check通过。无全量、无原生MySQL、新迁移、提交或推送。

边界：本批补的是预留释放的持久取消依据，尚不创建/释放pay容量预留，不假造付款事实，不开放售卡。下一子项仍为付款前容量预留→付款后转正式发行→取消时释放及恢复。JB-040/041保持DOING，整体仍32 DONE /14 DOING /9 TODO。

#### JB-040付款前容量预留与正式发行接管（2026-10-19）

已编码：shop-base新增独立ReserveSale/ReleaseSale/query和SaleSource事实协议；core真实来源增加待付款状态、截止、完整绑定和原规则读取。pay新增EntSaleReservation/Mapper、Service/Impl及Writer，V450与测试schema/设计支撑DDL同步。付款前只占既有总量/个人发行容量，不创建实例、不伪造付款事实；容量失败连同预留行回滚。取消只接受匹配的持久GRANT_ABORT，未决返回PROCESSING不释放；取消先到写RELEASED墓碑，迟到reserve不重开。

PAID_CARD_ORDER正式prepare必须已有原预留，再核实真实付款；grant事务将RESERVED移交TRANSFERRED、保存接管grantNo及ent_grant.reservation_no，不二次占额。commit/abort才结算这份容量；已预留承诺沿冻结规则履约，不重新套用今日模板状态和新发行门禁。真实发卡测试发现事件codec缺少PAID_CARD_ORDER来源，已补齐白名单，保留源身份及稳定事件号。

验证证据：

- RED：`/tmp/sale-reservation-red.log`在新类/方法不存在时失败；首个实现运行`/tmp/sale-reservation-basic.log`暴露事件来源白名单缺项，已修复。
- Java21离线Maven编译shop-app、pay-svc及依赖22模块；`/tmp/sale-reservation-verified.log`为BUILD SUCCESS。10个受影响测试类共52项，0失败/错误/跳过：预留5、源快照8、源单测5、真实源2、原发行8、储值分配3、储值消费5、储值HTTP1、路由2、运行RPC13。未跑全量。
- 消融：将正式发行的transfer替换为再次policies.reserve，暂停模板场景在IssuePolicyService.reserve明确CONFLICT，`/tmp/sale-reservation-ablation.log`为1项预期失败；已恢复原代码，整理显式import后重新编译22模块并运行预留5项，`/tmp/sale-reservation-restored.log`为BUILD SUCCESS、5项全通过。重跑不重复累计为新用例。
- 测试schema重生成为250表；数据库清单由生成器同步V449/V450。SQL方言守卫及git diff --check通过。目标设计DDL调整为54表（core22/pay32），原生设计验证脚本相应计数更新；本批未执行原生MySQL验证，不据H2宣称生产迁移验收。
- 文档规范8项中7项通过，缺状态行基线项失败（42>27）；另核HEAD与工作区同为42篇缺失，属原有失败，未上调基线或修改无关方案状态。

后续按依赖继续：①本地/远程销售预留及反向SaleSource RPC适配；②core逐单件预留、付款封口、逐件发行及取消Outbox协调；③原号恢复、真实双部署/并发验收后再解销售门禁。领域内核存在不等于订单销售已接通。JB-040/041仍DOING，整体32 DONE /14 DOING /9 TODO；本批未提交、推送或部署。

#### 自动执行顺序重新汇总（2026-10-19）

保持55项：32 DONE、14 DOING、9 TODO；未提交切片不按父项完成统计。

| 顺序 | 环节 | 已有交付 | 接下来收口 |
|---|---|---|---|
| 1 | 活动定义（core） | JB028–030：预置、草稿/发布、规则与计价 | JB031/033：参与终态与运营治理，JB052能力逐项验收 |
| 2 | 卡券/权益定义（pay） | 模板/不可变版本、COUNT/PERIOD规则、商品绑定 | 保持营销资格与权益规则分域，完善权限/契约对账 |
| 3 | 发放 | 基础发行/领取/活动协调、储值到账、付费卡预留内核 | JB040/041：预留RPC、真实逐件销售与付款封口，JB051恢复 |
| 4 | 核销 | 订单用券、服务核销、储值消费及本人记录切片 | JB020/036–038：全链路边界及验收 |
| 5 | 支撑收口 | 既有事件、权限及部分效果投影 | JB043–050通知/运营/对账/冻结/迁移，JB053–055契约及部署验收 |

本批新增付款前预留的本地Port、standalone Remote Port、三条内部认证POST与共享请求体；无新数据库迁移。反向SaleSource RPC、订单协调及真实HTTP专项仍待接，尚不能宣称独立部署销售闭环。继续自动执行，不需要逐项确认。

#### 定义、发放与核销的完成口径

- 活动定义：core只保存玩法、适用业态、受众、范围、预算和权益版本引用；发布产生不可变版本，执行能力缺失的预置不得启用。活动不保存消费者资产余额。
- 权益定义：pay统一ORDER_COUPON、STORE_EXCHANGE、PAID_CARD的规则及版本；次数/月卡是PAID_CARD的额度与有效期组合，储值是独立金额账户。禁止把活动买赠规则当作已到账资产。
- 发放完成：必须有可信来源，原业务号幂等，实例/账/事件同事务；付费卡必须实际付款后发行，储值必须实收后到账。PREPARED/PROCESSING不能对消费者显示发放成功。
- 核销完成：真实身份、订单、门店、项目、额度/余额均校验，预占之后依据持久决定确认或释放；成功核销账与消费者记录一致。仅预览不算扣减，未知结果沿原号恢复。
- 父任务完成：编码、受影响编译/基本测试、所需数据库/协议验收和证据齐全；代码切片与已验收任务分别登记，不用有接口代替完成。

本批验证：Java21离线Maven编译shop-app/pay-svc及依赖22模块成功；PaidCardReservationFlowTest 5项和EntitlementRuntimeRoutingTest 2项共7项通过，0失败/错误/跳过，日志/tmp/sale-adapters-fixed.log。首轮缺失路由夹具依赖已修复，未运行全量。三条新HTTP端点尚未做真实往返专项，不能算JB041整体验收完成。

### 售卡反向来源传输增量（2026-10-19）

新增POST /internal/core/entitlement/source/sale，共享SaleSourceQuery(context,ReserveSale)，返回SaleSourceFact；InternalSaleSourceEndpoint沿内部令牌认证、唯一owner选择与标准错误映射。pay-svc的CoreEntitlementGrantSourcePortImpl同时实现EntitlementSaleSourcePort，经既有CoreEntitlementInternalApi反查，不跨库、不在pay锁内发起网络调用。Bean工厂返回具体类型，使两个SPI在启动时均可发现。三条pay预留POST补真实HTTP测试，覆盖reserve/release/query及错误令牌拒绝调用业务。完整订单销售协调仍未验收，JB040/041保持DOING。

本批验证日志/tmp/sale-source-transport.log：Java21编译22模块BUILD SUCCESS；PaidCardReservationFlowTest 5、路由2、运行时真实HTTP14，共21项0失败/错误/跳过。无全量测试。新增反向事实接口已编码，但尚缺它的独立真实HTTP专项；不得把pay端预留HTTP验证冒充整个订单发卡闭环。接下来继续订单逐件预留/付款封口/发卡/取消及持久恢复。

### JB040逐件售卡协调实现切片

新增shop-base PaidCardSalePlanPort，由core CardSaleGrantSourcePortImpl按tenant/order读取原快照并按行、单件、件内权益排序；app CardSaleCoordinator/Impl仅消费可信计划，不接受外部传入权益清单。逐件预留沿SR+sourceKey，发卡沿CG+sourceKey；全部RESERVED才readyForPayment，部分成功保留原占用并可按原号恢复，不将超时猜作取消。真实COMMITTED来源后prepare→commit；可信CANCELLED后release；未知继续pending。所有pay调用均在core事务之外，不建立跨库事务。

本批未开放公共入口。尚须在真实建单路径冻结快照、付款入口强制封口、支付/取消Outbox投递和定时原号恢复接齐后才能开放售卡；协调器存在不表示这些调用点已接齐。验证目标包括重复付款恢复、取消释放、2件×2权益不漏不重。JB040/041仍进行中。

追加CardSalePaidEventConsumer接既有ORDER_PAID持久Outbox，完整发卡才确认消费，部分失败抛出由既有Outbox重试原号；零售卡权益订单无发卡副作用。消费者重复投递与协调器原号幂等共同验证。付款封口、真实建单快照与取消事件尚需接齐，销售门禁未放开。

验证：/tmp/card-coordinator-fixed.log为Java21编译22模块BUILD SUCCESS，PaidCardReservationFlowTest 8项及运行路由2项，共10项通过，0失败/错误/跳过；包含重复支付事件实际调用协调器、2×2权益仅发4份、取消释放。首轮新增事件消费者误读SysOutbox不存在的tenant字段导致编译失败，已按当前MAIN支持范围修复并重跑通过。未跑全量；未提交/推送。当前32 DONE/14 DOING/9 TODO，父项不提前记完成。

### JB040取消事件与付款前容量校验接线

core OrderServiceImpl的cancelPayment/closeOne在原事务内写GRANT_ABORT，存在售卡快照才发布CardSaleEvents.Cancelled（CARD_SALE_CANCELLED）。CardSaleEventConsumer替代原仅付款消费者，消费取消时必须全部RELEASED才能确认；失败沿统一Outbox重试，不因截止释放。无售卡快照不发布额外事件。

PaidCardPaymentGatePort/Impl在OrderServiceImpl.pay发起支付尝试前调用协调器，零售卡权益不影响原订单；有权益但未全部RESERVED拒绝继续。该调用点无core写事务。此处尚非所有收款路径验收，线下收款等仍待核对；商品售卡门禁保持关闭，真实建单快照尚未接齐，不宣称完整上线。新增测试验证重复取消事件不双释放，取消后的付款预留检查拒绝。

本批验证：/tmp/card-cancel-events.log编译22模块及快照8+场景8+路由2共18项通过；付款入口追加后以/tmp/card-payment-gate.log重新编译22模块并运行场景8+路由2，10项通过，0失败/错误/跳过。两轮相同场景不重复累计。新增用例覆盖取消事件重复释放与取消后付款拒绝。git diff --check通过，未跑全量。真实建单快照及线下收款校验尚未收口，父任务保持DOING。

### JB040建单权益快照接线

CardProductSalePort新增saleDefinition内部读取（无绑定返回空），SaleDefinition包含原模板版本、币种、每件权益数、已发布规则JSON。core商品owner从真实商品归属推导tenant，按原商品/SKU查绑定，仅ACTIVE且未删除可读取，不调用pay。OrderServiceImpl在真实OrdItem插入后以稳定OL+主键构建逐件/件内权益快照，检查币种并加入原建单事务；只对购买行展开，不把赠品行当付费卡发行来源。读取/快照失败原建单一起回滚。

requirePurchasable仍拒绝新绑定销售，未因接线而开放。当前测试覆盖真实绑定读取与版本副本、源快照、预留/发卡/取消；尚缺开放门禁后的真实checkout全链路专项。线下收款目前存在事务包围，需要先拆边界再接预留检查，不能在持锁事务里调用pay。

本批验证：首轮编译发现绑定实体无entityNo字段，已改为沿真实商品归属校验；/tmp/card-checkout-snapshot-fixed.log编译22模块BUILD SUCCESS，快照8+商品绑定真实场景1+预留发行8共17项通过，0失败/错误/跳过。整理显式import后/tmp/card-checkout-final-compile.log再次编译22模块成功。git diff --check通过；未跑全量，未宣称开放后的真实建单/线下收款已验收。

### JB040线下收款事务边界

MerchantOrderServiceImpl.confirmOfflinePay以NOT_SUPPORTED挂起调用方事务，先require商家/门店权限与待线下收款状态，再调用PaidCardPaymentGatePort。随后通过core transactionManager的TransactionTemplate进入原收款事务，重复require并FOR UPDATE锁主单后再次检查WAIT_OFFLINE_PAY，才写确认人/时刻并复用markPaid。并发取消在预检后成功，则锁内拒绝确认，原取消不被覆写；全部本地收款审计和付款事件仍与主单同事务。预留失败不写收款审计，已经预留但取消须沿可信取消事件释放。

验证映射：PaidCardReservationFlowTest.offlinePrepaymentRunsOutsideTransactionAndCancellationWinsBeforeCollection断言pay预检无core事务、插入并发取消后收款拒绝且无确认人/发卡；既有OfflinePayFlowTest验证普通线下收款路径兼容。当前无新增HTTP字段、表或权限，销售门禁仍待完整checkout专项。

线下收款批次验证：Java21离线编译shop-app/pay-svc及依赖22模块成功；/tmp/offline-card-verified.log为BUILD SUCCESS，OfflinePayFlowTest 10项、PaidCardReservationFlowTest 9项共19项通过（0失败/错误/跳过），包含事务外预留与预留后取消拒收。前置/tmp/offline-card-gate.log商品绑定1+原预留8共9项通过，重复用例不累计。git diff --check通过，未跑全量。销售门禁、完整checkout专项与恢复任务继续按JB040/041/051收口，父任务仍DOING。

### JB040/051售卡恢复扫描

V451只扩展core原售卡快照delivery_status及恢复索引，不新增表（运行表仍250）。PaidCardSaleRecoveryPort/Impl读取主单已PAID/CANCELLED/CLOSED且待确认的订单，支持afterOrderNo、1–200上限及稳定排序；不扫未付款单、不按TTL释放。CardSaleRecoveryService/Impl在事务外逐单调用原协调器，全部发行或全部释放才acknowledge；失败计数并继续其余订单，原记录保留PENDING。

CardSaleRecoveryJob沿JobHandler/JobDeclaration/JobSupport及ShedLock注册card-sale-recovery，受shop.job.enabled控制；shop.job.card-sale-recovery.cron默认每5分钟。每批100单，游标轮转越过故障单；成功确认持久化，进程重启只重复未完成单。调度失败由既有作业结果记录，恢复不退款、不调平、不更换原预留/发行号。源Port属于core，扫描不读取pay表。此内部扫描无新增公共端点、权限或消费者字段。

新增场景验证：sweepRecoversLostPaidEventAndPersistsAcknowledgement、failedRecoveryRetainsPendingWithoutIssuing。尚待原生迁移、调度故障/并发及最终checkout专项；父任务仍DOING，不据定向测试直接开放销售。

本批证据：/tmp/card-recovery.log Java21编译22模块BUILD SUCCESS，CardSaleSnapshotTest 8、PaidCardReservationFlowTest 11共19项0失败/错误/跳过。调整失败用例名称后/tmp/card-recovery-final.log重编译并单跑failedRecoveryRetainsPendingWithoutIssuing，1项通过（不重复累计）。V451由生成器重建H2 schema，数据库清单由生成器同步，SQL方言守卫/git diff --check通过。未运行全量或原生V451迁移验收。当前仍32 DONE/14 DOING/9 TODO；恢复定时任务的真实调度/并发、完整checkout及销售门禁最终收口仍待后续，不将父任务提前标完成。

### JB040完整checkout专项与默认关闭准入

新增shop.trade.paid-card-sale.enabled（默认false）显式开关，仅用于允许有效绑定进入core建单；ACTIVE/未删除/真实SKU与规则副本仍校验，pay侧发行/使用/付费卡开关和容量继续独立守卫。默认配置保留旧拒绝行为，不自动修改部署配置。新PaidCardCheckoutFlowTest使用真实商品绑定及真实OrderService.createFor，2件×每件2权益须持久4条来源、付款前0实例、预留4、markPaid状态机/Outbox事件后发行4，重复消费与恢复仍4。markPaid为内部集成测试调用，不代表外部通道实际收款验收。

首轮专项发现测试缺门店导致来源快照校验失败。生产快照门店改为sub.getStoreNo，与真实履约/库存落地一致；测试补真实默认门店，不放松门店必填。尚需独立部署、原生迁移、并发及真实通道演练后才建议开启新开关，父任务不提前标完成。

真实checkout专项：/tmp/paid-card-checkout-store.log Java21编译22模块BUILD SUCCESS，PaidCardCheckoutFlowTest 1项通过，实际绑定→createFor→4条原来源→预留4→markPaid→重复付款事件→4实例/总发行4/占用0。该专项此前先缺门店、再误将测试门店设为OPEN（合法值为ACTIVE）而失败，已按真实门店契约修正夹具；未放松生产状态/门店校验。新开关仅提供显式准入，部署默认false；不表示外部收款通道、原生迁移或独立部署验收完成。

默认关闭兼容验证：/tmp/paid-card-checkout-regression.log编译与12项定向测试通过（CardProductBindingFlowTest 1、PaidCardReservationFlowTest 11）。加真实checkout专项1项，本批13项不同场景通过，0失败/错误/跳过；未跑全量。git diff --check通过。JB040完整售卡切片已增加真实checkout证据，原生迁移/并发、独立部署/真实通道仍待JB041/054/055；仍32 DONE/14 DOING/9 TODO，不据本地场景标父任务全部完成。

### V450/V451原生增量与独立装配验证

新增scripts/tests/paid-card-migrations.test.mjs，仅在无端口、无网络的临时MySQL 9.7.2执行V450及V428→V451，finally清理。验证源键唯一、RESERVED规则快照必填、TRANSFERRED接管grant必填、RELEASED取消决定必填、JSON合法性；旧快照升级后默认PENDING、非法状态拒绝及恢复索引存在。/tmp/paid-card-migrations.log为1项通过，0失败/跳过，约6.8秒。该证据仅覆盖迁移与数据库约束，不冒充真实Writer并发或生产版本升级演练。

PayApplicationBootTest新增saleCapacityAndReverseSourceHaveExactlyOneStandaloneOwner，检查真实独立pay上下文的预留SPI、反向SaleSource及HTTP端点唯一，来源确为core远程适配。结合运行时HTTP与主应用embedded/standalone互斥路由测试验收本批装配；不据此宣称两个真实进程的业务链路已全部验收。

本批Java证据：/tmp/paid-card-deployment.log为Java21编译22模块BUILD SUCCESS，独立pay真实启动4项、运行时HTTP14项、主应用路由2项共20项通过，0失败/错误/跳过。另原生迁移专项1项通过；没有运行全量。git diff --check通过。下一执行重点：JB043提交后通知与失败重试、JB045–047运营异常/对账/冻结；售卡完整双进程业务和真实通道仍归最终部署验收。当前任务总数与父项状态不因本批基本验收自动改写。

### JB043交易站内通知切片

EntitlementNotificationSubscriber实现OutboxConsumer，仅处理ENTITLEMENT_GRANTED/ENTITLEMENT_USED/STORED_VALUE_CREDITED。共享codec校验收件人、业务字段，额外匹配eventNo/aggregateType/aggregateId，防止错事件身份发送；dedupKey为EN+SHA256(事件号+USER+收件人)，复用MessageService.push的全局持久去重及既有站内信发送日志。只写TRADE站内信，未开通短信/微信/推送，不新增NotifyScene通道种子，不制造未落地C路由；link为空。不展示双池、账户号、原凭据或会员码，不调用pay资金/权益写服务。失败抛回现有Outbox重投，成功消息重投仍唯一。

EntitlementNotificationFlowTest覆盖到账/发放/核销三类真实消息落库与重复去重、错头/无收件人拒绝、消息首次失败后恢复且未创建账户或发卡。当前是消费者站内通知子项；外发、商家/运营异常通知及完整O06治理仍待后续，JB043不能提前DONE。

JB043本批证据：/tmp/ent-notifications.log为Java21编译22模块BUILD SUCCESS，通知初始2项+预留发行11项通过。补消息失败恢复后/tmp/ent-notifications-final.log重编译并运行通知3项全通过；不同场景合计14项（重跑不重复累计），0失败/错误/跳过。失败注入首次抛错、后续真实MessageService落库，重复重投仍1条消息；对应用户无ent_grant或sv_account新增。git diff --check通过，未跑全量。任务状态更新为32 DONE/15 DOING/8 TODO，JB043从TODO进入DOING，仅站内通知切片已落地，不提前完成外发与运营异常通知。

### JB045支付异常记录与平台查询内核

V452建既定pay_ent_exception，PayEntException/Mapper归pay数据源。EntitlementExceptionService/Impl实现captureGrantFailure/get/list；只捕获真实PREPARED grant，先锁原grant并按tenant+kind+原grantNo唯一认领，稳定异常号EX+来源摘要，不接收调用方提供的证据或受益人。返回Evidence白名单只含原grantNo、捕获时状态、来源类型和固定DELIVERY_FAILED，不回传付款凭据、用户授权或原命令。捕获不改变容量/实例/分录。

查询限定platformActor且按tenant/entity收窄；分页1–100条，稳定createdAt/id倒序。该内部CatalogScope须后续由运营可信会话/数据域构造，不能直接绑定请求。O01–O04的HTTP/权限、独立pay适配、按原号恢复及动作审计尚待接，本批不开放伪实现的运营端点。新增真实PREPARED发行捕获/重复、脱敏、非平台和跨主体拒绝场景，父任务仍DOING。

JB045本批证据：/tmp/ent-exceptions.log为Java21编译22模块BUILD SUCCESS，PaidCardReservationFlowTest 12项（含真实待完成grant异常捕获/去重/脱敏/数据域）、EntitlementNotificationFlowTest 3项共15项通过，0失败/错误/跳过。V452测试schema与数据库清单均由生成器更新为251运行表；SQL方言守卫/git diff --check通过。未执行全量或原生V452验收，运营HTTP与恢复动作仍未开放。当前32 DONE/16 DOING/7 TODO。

### JB045原grant恢复与动作审计内核

V453新增pay_ent_exception_action及实体/Mapper；ExceptionViews.RecoveryCommand只含expectedExceptionRevision/reason，幂等键另传，不接受新受益人、金额或权益。EntitlementExceptionRecoveryService/Impl调用EntitlementExceptionActionWriter.begin认领动作与异常PROCESSING，释放pay锁后读真实来源决定并调用原grant commit/abort；UNKNOWN/PENDING不释放容量，完成动作并将异常回OPEN供下一次核实。成功原grant终态后同事务写RESOLVED与DONE安全响应。原请求中断可用同键原参续办；同键异参或新键抢PROCESSING拒绝；完成响应沿原键稳定重放。

审计冻结操作者、主体、异常号、请求摘要、原因与最终回复，不保存原支付凭据。只捕获/恢复已有grant，不重建发行单。测试模拟begin后进程中断，再同键续办；原grant只发行1次、容量只结算1次、动作只留1条，重复回复稳定，异参/非平台拒绝。当前仍为pay内核，运营权限/会话和本地远程HTTP适配随后接线，不宣称O03已经可公开调用。

恢复批次验证：/tmp/ent-exception-recovery.log Java21编译22模块BUILD SUCCESS，原售卡/异常12项通过；补中断续办断言后/tmp/ent-exception-recovery-final.log再次编译22模块并单跑capturedGrantExceptionIsDurablePrivateAndPlatformScoped，1项通过（扩展同一场景，不重复累计为第13项）。验证原key续办、稳定重放、异参/非平台拒绝、只发1份/只结算1次、持久原因/动作唯一。V453测试schema/数据库清单已生成（252运行表），目标55表；SQL方言及git diff --check通过。未跑全量或V453原生验收。JB045仍待O01–O04权限/运营会话及双部署API接线，状态仍32 DONE/16 DOING/7 TODO。

### JB045异常服务双部署传输

EntitlementExceptionAppService新增受控list/get/retryGrant；embedded由EntitlementExceptionAppServiceImpl委派pay服务，standalone由RemoteEntitlementExceptionAppService通过PayInternalApi调用。共享PayExceptionRequests及Paths三条POST，InternalEntitlementExceptionEndpoint沿内部令牌和统一异常映射；请求校验可信scope、原异常号、原恢复命令和幂等键。写请求不自动重试，返回安全ExceptionView/PageData。未将内部scope放入公共请求；O01–O04运营入口仍待可信会话/数据域及权限登记，query-source动作尚待实现。

新增真实HTTP异常读取/分页/恢复DTO往返与错误令牌拒绝调用业务，路由夹具加入两种适配及对应服务替身，断言每种部署只有一个异常AppService。

JB045传输批次证据：/tmp/ent-exception-transport.log Java21编译22模块BUILD SUCCESS，HTTP15+真实预留/异常恢复12+路由2共29项通过。补共享请求必填校验及非法HTTP请求后，/tmp/ent-exception-transport-final.log重编译pay-svc及依赖并运行HTTP16项全通过，0失败/错误/跳过。不同用例合计30项，重跑不重复累计；错误令牌和无scope/无命令均不调用恢复业务。git diff --check通过，未全量。本轮未新增迁移，运营公开Handler/权限及query-source尚未接齐，JB045/041仍DOING。

### JB045查源动作与内部传输

V454新增operation_kind区分QUERY_SOURCE/RETRY_GRANT，旧动作为RETRY_GRANT。ExceptionViews.Evidence新增可空sourceStatus；旧证据JSON及4参构造兼容。查源复用RecoveryCommand与独立key，锁外读取来源决定后把状态存入异常证据及动作响应；异常回OPEN，不调用grant commit/abort，也不结算占用。原查询键重放原结果；查询键改用于恢复冲突，后续恢复须带最新revision和新key。

新增内部POST /internal/pay/entitlement/exceptions/query-source，沿原内部令牌及共享RetryGrant请求、本地/远程AppService。无新增公开运营Handler，本批仍待O01–O04权限及可信会话/主体范围接线。测试覆盖COMMITTED来源仅查不发、原容量不动、独立动作审计、查源重放与恢复键隔离，然后实际原grant恢复；HTTP验证查源参数完整往返。

查源批次证据：/tmp/ent-exception-source.log Java21编译22模块BUILD SUCCESS；真实来源/异常恢复12、内部HTTP16、本地/远程路由2共30项通过，0失败/错误/跳过。验证来源已COMMITTED时查询仍保持原held=1/issued=0、查询重放、查询key不能恢复、再用新key恢复后只发行1份/两类动作各1条。V454 schema/清单已生成，SQL方言与git diff --check通过。未全量/原生V454验收；本轮补齐内部query-source，不将运营公开Handler/权限范围误记为已完成。状态32 DONE/16 DOING/7 TODO。

### JB045运营公开入口（主体明确限定）

OpsEntitlementController新增O01–O04，ops profile及独立read/recover/verify权限；一律要求entityNo查询参数，O03/O04还须Idempotency-Key/RecoveryCommand。OpsEntitlementScopeResolver从当前OPERATOR会话取tenant/actor，ALL或MERCHANT.refs包含指定主体才构造platform scope，缺scope/其他维度不猜主体范围。pay继续核对tenant/entity，前端不能指定platformActor。

V455注册三项后端功能点/超管授权，Perms.java及ops-web perm-map同步；不授予FINANCE/CS等普通角色，不增加UI未落地菜单（ui_ready=0）。O01首版明确主体，跨主体聚合列表仍待实现；安全ExceptionView暂不包含全动作历史，JB045不因Handler存在直接DONE。测试覆盖真实运营登录四路由、原key/命令、未登录/缺主体不调用pay，以及纯范围解析的跨主体、社区维度/消费者拒绝。

运营入口批次证据：/tmp/ops-entitlement-api-final.log Java21编译22模块BUILD SUCCESS，范围解析3、真实运营HTTP3、原业务/异常恢复12共18项通过，0失败/错误/跳过。覆盖超管四路由、原key/命令、普通support读/恢复均10403且不调用pay、未登录、缺主体10400、跨主体/不支持维度/消费者拒绝。首轮把项目业务错误HTTP200误写为HTTP400断言，已按现有10400契约修正并重跑。V455及schema2372种子同步，SQL方言/git diff --check通过；未跑全量或原生V455验收。O01–O04 Handler已编码，仍明确主体限定，跨主体聚合/完整历史及父任务验收待续，当前32 DONE/16 DOING/7 TODO。

### JB046权益次数账实对账内核

V456落既定PayEntReconRun/Diff及两个Mapper；EntitlementReconciliationService/Impl以payTxManager REPEATABLE_READ对同一主体的实例/分录做RIGHTS轴核对。CheckRights冻结日期/市场时区/afterInstanceNo/limit（1–200），RunView仅含批次、主体、量纲、检查数/差异数/下一游标，内部expected/actual JSON不直传页面。SUM余次/占用及MAX序号与实例逐份比较，一份实例无论几个字段异常只留1条BALANCE_MISMATCH，绝不调账。日限无限总次数实例不按虚构total比较余次；仍比占用/序号。

每页一个幂等批次，同键同参回原结果，异参冲突；有nextCursor必须后续用新key继续，不把单页COMPLETED当全主体完成。差异/批次与快照读取同事务，失败全部回滚。仅平台scope且按tenant/entity收窄，无ALL隐式跨主体。测试验证正常、差异、原号重放、差异不重复、余额保持29及非平台/跨主体拒绝。发行/金额/服务日桶对账、O05及本地/远程承载仍后续，JB046不能提前DONE。

JB046本批证据：/tmp/ent-reconciliation.log Java21编译22模块BUILD SUCCESS，PaidCardReservationFlowTest 13、运营HTTP3共16项0失败/错误/跳过；验证正常一致、差异持久、幂等重放、同键异参拒绝、主体隔离且不调余额。补4实例两页扫描后/tmp/ent-reconciliation-page.log重编译22模块并单跑原2×2场景，1项通过（不重复累计）。每页2份，第一页有游标，第二页无游标，两页差异0。V456及schema/数据库清单同步254运行表，SQL方言/git diff --check通过。未全量/原生V456验收，其他轴和O05仍未接。任务32 DONE/17 DOING/6 TODO。


### 连续执行N01进展（2026-10-19）

- 复用既有任务表与worktree，不重做已DONE基础。已扩展四轴对账与运营check/list/get/differences、本地/远程适配及内部token端点；仍在定向验证，JB-046/041不提前改DONE。
- RED `/tmp/ent-recon-expanded-red.log`：MONEY场景在原缺失check行为处失败。首轮GREEN `/tmp/ent-recon-expanded-green.log`：储值6+售卡13，共19项通过。
- 原生MySQL `/tmp/ent-ops-migrations.log`：新增isolated无端口容器运行V452–V456约束用例1项通过，容器已清理；验证JSON、请求键大小写、唯一源、动作种类及旧动作默认值、MONEY币种约束、差异计数和IAM幂等/不自动授予普通岗位。不是全库部署升级验收。
- 路由/四轴定向首轮发现H2保留字day别名和公开HTTP测试错误的400预期；分别改service_day_key和既有业务码10400，未改变项目响应包装规则。补逐lot/逐voucher检查，防止总额抵消。

- N01合并GREEN `/tmp/ent-recon-routes-final.log`：Java21编译22模块通过；pay-svc内部HTTP17项，shop-app储值7+使用12+运营对账2+售卡14+路由2共37项，合计54项0失败/错误/跳过。补验证日bucket缺失、发行归属错误、预占及消费后金额核对、不同lot/voucher反向误差抵消仍能检出。SQL守卫及git diff --check通过。仅定向验证，不当作JB-054部署或JB-055全量验收；累计未提交工作区仍不标父任务DONE。
- N02开始：将已验证真实来源之后的发放事务失败接入异常登记，保留原错误并支持原grant恢复；补安全动作历史，不回传requestKey、payloadHash、response_snapshot或原始命令。


### 连续执行N02进展（2026-10-19）

- RED `/tmp/ent-exception-capture-red.log`：实际发放事务故障后异常列表为空。补真实源决策确认后的writer失败登记；writer回滚后另启pay事务记录，保留原异常，登记失败追加suppressed；UNKNOWN/伪造/prepare失败不作为已确认发放故障。
- 补ActionView及运营GET actions、内部POST actions、本地/Remote查询；公开不含requestKey/hash/内部responseSnapshot。跨主体聚合列表、其他失败类型及死信联动仍挂账，JB-045/051保持DOING。
- 合并首轮用例的故障注入约束错误命中过往其他成功发放事件；已按当前grantNo收窄约束，未删除其他用例数据或改变生产逻辑。
- GREEN `/tmp/ent-exception-capture-final.log`：22模块编译通过，pay-svc内部HTTP18、shop-app售卡15+运营异常3，共36项0失败/错误/跳过；包含两次实际失败仅一条异常、原号恢复后仅发行一次、安全历史与越权拒绝。不是跨主体或所有异常类型的整体验收。
- N03开始：冻结/解冻复用既有幂等表和生命周期流水；先写已有HELD可完成、新预占拒绝、审计原因、幂等重放、解冻不复活过期权益及作用域/版本拒绝用例。


### 连续执行N03进展（2026-10-19）

- 新增FreezeCommand/FreezeView/内部FreezeAudit，LifecycleService冻结/解冻和只读state；复用pay_ent_command_dedup保存原因、前态、预期版本、actor/time及稳定结果，同事务递增asset_seq并写零delta的FREEZE/UNFREEZE流水，不新增权益业务表。
- 新增O07及只读O07a、PayLifecycleRequests、本地/Remote AppService及内部freeze/state端点。V457单独登记冻结权限，仅默认赋予SUPER_ADMIN；无新菜单。测试schema现为254表/2374种子。
- RED `/tmp/ent-freeze-red.log`：两个冻结用例命中未实现行为。GREEN `/tmp/ent-freeze-green.log`：22模块编译通过；pay-svc内部HTTP19，shop-app使用14+运营生命周期2+路由2，共37项0失败/错误/跳过。
- 原生MySQL `/tmp/ent-freeze-native.log`：真实Mapper/事务及20个并发核销重放、20个并发冻结重放、冻结阻止新预占、原HELD可释放、解冻后继续用、跨日释放及Outbox回滚，1项0跳过通过；容器已清理。`/tmp/ent-ops-freeze-migrations.log`验证V457幂等及不授予普通岗位，原生迁移1项通过。SQL守卫通过。
- JB-047由TODO进入DOING，当前32DONE/18DOING/5TODO；未完成最终接口总对账和提交证据，不提前置DONE。
- N04/N05发现实际缺口：当前活动执行策略只接纳AP004/009/010，储值源链路虽可用，但AP030等正式储值预置无法发布。接下来补受真实pay开关、会员身份与商家准入共同约束的储值能力证据，并验证正常创建发布→实收到账→核销→C端记录，不通过直接插入活动版本绕过发布。


### 连续执行N04/N05正式储值发布闭环（2026-10-19）

- RED `/tmp/beauty-preset-red.log`：正常AP030发布在requireReady被旧PRESET_EXECUTOR策略拒绝。补5个同构RECHARGE预置执行准入，以及StoredValueCapabilityPort本地/Remote/internal-token读接口；core/app整合活动版本/参与、会员码及pay到账/核销证据，缺能力仍不可发布。不修改任何预置默认enabled。
- 新增BeautyStoredValueLifecycleFlowTest真实链路：实际商家与门店/商品、正式saveDraft/publish AP030、会员码引用、商家CASH实收、原充值号恢复到账/确认营销、计价服务单、本人授权、重复核销、C记录及零余额门店保留。未直接插版本或用credit夹具。该用例采用已知LIFE_SERVICE入驻行业，AP030美业标签为推荐分类，不伪造新的入驻行业码。
- GREEN `/tmp/beauty-preset-green.log`：Java21编译22模块；core执行策略4、pay-svc真实内部HTTP20+独立装配4、app能力门禁1+完整美业链路1+部署路由/未知响应3，共33项0失败/错误/跳过。金额消费1100、只产生1笔消费记录，无本金/赠金/凭证字段，余额0仍有门店记录。
- 已补正常发布缺口，不代表JB-031/033/034–038/041/052整体验收与提交完成；原生并发、跨进程业务及能力/API总对账仍归后续批次。


### 连续执行N07在途与到期恢复（2026-10-19）

- 增加core EntitlementRecoveryScanService按4类持久非终态指针keyset扫描；app EntitlementRecoveryService在无外层事务的边界逐项调用原协调器。完成/待查/失败分列，尾页游标归零，异常不阻断后续条目，不自动收款、退款或按TTL释放。
- 新增EntitlementRecoveryJob/EntitlementExpiryJob，沿现有JobSupport、声明和持久ShedLock租约；global shop.job.enabled控制加载，未启用真实环境任务。到期本地/Remote及内部token端点接齐，pay仍按锁内状态迁移、不清余额/预占，冻结由解冻时重算到期。
- RED `/tmp/ent-recovery-red.log`：美业真实待收款指针未被扫描，期望扫描1实际0。GREEN `/tmp/ent-recovery-green.log`：Java21编译22模块通过；内部HTTP21、真实美业恢复/活动丢失响应恢复/订单取消恢复/到期行为各1，共25项0失败/错误/跳过。美业用例先证明待实收仅deferred，再证明实收后到账及COMMIT_DECIDED核销中断自动完成、完成后不再入队；无新请求或重复财务效果。
- JB-051仍DOING：死信关联异常、到期提醒、真实进程重启/任务装配与长期失败治理未据此宣称完成。N08下一步补异常提交后的B/运营站内通知及统计投影。


### 连续执行N08异常通知切片（2026-10-19）

- 首次异常登记与pay outbox同事务写稳定ExceptionOpened；提交后B端仅有效店主、运营端按真实read权限+StaffScopes主体数据域解析在职收件人，事件/收件箱类型/收件人联合去重。消息订阅者不调用任何发放/核销/调账命令，外发渠道未启用。
- 站内日志复用既有GET /ops/inapp-messages；/ops/notify-logs仅外发记录，不虚构站内“外发成功”。补API及类清单口径。
- RED `/tmp/ent-exception-notice-red.log`：异常登记后没有待通知事件。GREEN `/tmp/ent-exception-notice-green.log`：21模块编译通过，权限/主体受众1+事件协议7+站内通知4+真实失败登记1，共13项通过。
- 补查发现pay outbox桥接器有独立事件白名单；新增实际relay用例，RED `/tmp/ent-exception-relay-red.log`命中未知事件，补case后GREEN `/tmp/ent-exception-relay-green.log`事件8项通过，包含真实桥接转发及标记，不只证明生产者写了事件。
- N08统计切片开始：实际美业链路已完成但C10尚无充值/消费效果；RED `/tmp/ent-marketing-red.log`期望RECHARGE/CREDITED/CONSUMED指标实际为空。补安全pay只读归因事实，按原批次和活动投影，金额与次数分开，不把多活动贡献重复计为消费笔数。


### 连续执行N08效果投影切片（2026-10-19）

- 增加安全只读EntitlementMarketingFactsPort、本地/Remote/internal-token调用；储值按真实已提交voucher/entry/lot/recharge活动归因，按活动keyset分页，归因总额须等于原消费。权益按原grant冻结permit归因，无活动的直接售卡/定向权益不伪造活动归属。
- 补Received/Credited/Granted/Used/Reversed统计订阅与pmt_effect_projection幂等写，金额、币种、次数分列；实收/到账/消费/发放/兑现/受限回补是不同指标，禁止跨阶段求和。C10复用旧ORDER_DISCOUNT回滚过滤，支付效果按源事件去重计笔数。
- RED `/tmp/ent-marketing-red.log`：实际美业消费后统计为空。GREEN `/tmp/ent-marketing-green.log`：22模块编译、core投影/汇总5+内部HTTP22+美业1+部署路由3，共31项通过。美业扩展为两个真实AP030活动各实收1000/到账1100，合并消费2200，逐活动归因及limit=1翻页正确；乱序/重复事件后统计实收2000、到账2200、消费2200且消费笔数仅1，account_seq仍3。
- `/tmp/ent-marketing-rights.log`：额外真实AP004创建发布→发放3次服务券→会员码计次核销，1项通过；发放及使用指标只有units，无伪金额或币种，储值余额1100保持不变。
- 受限回补投影继续只消费pay既有已提交回补事件，与新储值/付费卡线上退款无关；补原使用身份/金额与次数上界校验，不执行任何回补命令。最终统计/权限/文档总对账仍归N09/N12。


### 连续执行N10 owner基础与N09覆盖进展（2026-10-19）

- V458落实已有ent_owner_registry目标表，255张实际迁移表/2374测试种子，目标逻辑模型仍55表。新增实体、pay Mapper、受信平台旧owner登记、精确解析、本地/Remote及内部token只读端点；同号不同来源/类型分开，缺owner或缺PAY目标都报错，不试另一实现。无HTTP登记/切换入口，不发券或改旧券。
- RED `/tmp/ent-owner-red.log`登记行为缺失；GREEN `/tmp/ent-owner-green.log`：22模块编译，内部HTTP23+独立装配4+owner真实库2+路由3，共32项通过。验证来源/类型碰撞、同批幂等、异批冲突、大小写精确、非平台拒绝及PAY坏目标不回退。
- `/tmp/ent-owner-migrations.log`：隔离原生MySQL V452–V458增量1项通过，含owner联合唯一键/大小写及阶段/目标/水位CHECK；容器清理。SQL、V2文档、diff格式守卫通过。JB-048进入DOING，当前32DONE/19DOING/4TODO。
- owner登记是元数据基础，不是跨库写租约。JB-049/050还需兼容协议、冻结/排空、水位核对及离线迁移，不凭一次owner读取激活生产切换。
- N09新增文档→运行时Handler与权限检查、core反向及pay内部客户端覆盖。首轮发现共享活动URL的model=unified选择条件在主表格遗漏，已补C03/C04/C06及C03a详情，并让覆盖检查按真实请求条件选择Handler，保留旧/新契约互斥规则。

- N09公开契约覆盖最终结果：`/tmp/ent-contract-coverage-final.log`，21模块编译及3项定向测试通过；覆盖56条文档化公开路由和10条core反向接口。pay上一批64条客户端接口也已找到真实Handler。此证据不等于全部OpenAPI/TS生成、逐端点越权测试或父项已完成。

### 连续执行N09预置与N06/N11双进程证据（2026-10-19）

- RED `/tmp/ent-presets-red.log`：EP001所需CARD_PRODUCT未进入共享词汇。补齐6个EP能力码、pay就绪只读Port/token HTTP及本地/Remote绑定，core组合售卡与本人确认门禁；EP只能作为付费产品建议，不能发布为营销活动。正式预置种子与开关保持原默认关闭。
- GREEN `/tmp/ent-presets-green.log`：22模块编译、36项定向测试通过。逐项核对40AP+6EP，显式全开测试配置下8AP+6EP具备当前执行条件，其余32AP列出具体缺口。完整矩阵在design/活动权益-预置能力验收矩阵.md，不把矩阵当作46条完整业务实跑证据。
- 追加HTTP token/租户/开关单测：`/tmp/ent-presets-guards.log`中pay25和core5项通过；app装配夹具漏登记新的Remote类导致1项失败，补夹具后`/tmp/ent-presets-guards-final.log`5项通过。当前pay权益/储值客户端65条有真实Handler。没有修改运行路由消除测试差异。
- `/tmp/ent-card-lifecycle-final.log`：21模块编译、3项付费卡真实购买→4份权益发行→会员识别→本人确认→扣次→C明细通过，覆盖COUNT/PERIOD_COUNT/PERIOD_DAILY；首用才激活、重复扣次幂等、日额度耗尽拒绝；门店fixture使用32字符业务号。
- `/tmp/ent-two-process.log`：先准备不含shop-core/shop-app/shop-merchant的pay运行classpath，再由独立JVM运行真实PayApplication、独立H2数据库；真实core服务进程经HTTP完成AP030发布、2笔实收到账、源中断恢复、2200核销、C明细、营销归属与事件桥。1项验收0失败/跳过。core本地pay账表保持空；该证据不代表MySQL版本验收或生产部署。临时子进程和数据库已清理。
- 正在将上述独立进程夹具复用于3类付费卡，以真实远程模板/发行策略/销售预留和源事实替代只有传输mock的验收。父任务仍DOING，未进行生产切换。

- 双进程扩展最终通过：`/tmp/ent-two-process-cards.log`，22模块编译，1项独立pay classpath检查及4项真实双进程场景全部通过、无跳过；3类售卡从远程模板/发行策略→core订单快照→pay销售容量→付款源事实→pay发行→服务扣次→C明细完成，本地core权益表保持空。与美业共享StandalonePayFixture，临时子进程/文件库按类清理；运行命令为`scripts/verify-entitlement-two-process.mjs`。这仍是H2集成证据，不替代目标MySQL/MariaDB或迁移切换验收。
- 到期提醒实现裁定：首期仅站内消息，在权威有效期到期前24小时内，对ACTIVE且仍有可用次数或日限模型的实例提示；冻结/未激活/已过期/耗尽不提醒。同一实例及同一截止时间仅一次，时间改变才属于新窗口；不在提醒任务中冻结/到期/扣次。此为JB051/043缺省时机落地，不启用外部短信或推送。

### 连续执行N07/N08到期提醒（2026-10-19）

- RED `/tmp/ent-reminders-red.log`到达缺失提醒行为；实现pay只读窗口、token内部HTTP、两种承载、app站内消息与受全局job开关约束的15分钟任务。UTC夹具最初错用本地Timestamp导致窗口不匹配，改为明确UTC LocalDateTime后`/tmp/ent-reminders-time.log`21模块及1项真实数据库场景通过；原编译遗漏已一并修复。
- `/tmp/ent-reminders-guards.log`22模块编译及34项定向测试通过：pay26（含66条内部Handler清单、最小提醒DTO/token校验），app消息失败隔离1、三语6、条件装配1。提醒单行失败后继续后续行，再扫只重试消息，不发卡/扣次/改有效期；实际消息表证明相同窗口仅一条，改真实截止时间可产生新提醒。
- V459增加到期索引，`/tmp/ent-reminders-migrations.log`隔离原生MySQL1项通过、无跳过，核对索引列顺序并保留V452–458约束校验。SQL守卫通过；重生成主库仍255表、2374种子，目标55表不变。默认job与预置开关没有开启。
- 下一切片补运营异常聚合列表，服务端按会话的ALL或商家范围过滤，详情/恢复仍必须明确主体，不能用省略entityNo放开数据域。

### 连续执行N02运营异常聚合补齐（2026-10-19）

- RED `/tmp/ent-ops-aggregate-red.log`：管理员不选商家打开列表被原必填entityNo拒绝为10400。补服务端OperatorEntityScope、会话ALL/商家集合解析、pay过滤后计数分页、token内部承载及本地/Remote接线；O01仅将entityNo改为可选筛选，详情/恢复仍显式主体。
- GREEN `/tmp/ent-ops-aggregate-final.log`：22模块编译及39项定向测试通过（pay27、范围4、HTTP4、实际数据库分页1、两种装配3）。旧“列表必须选商家”的用例改为“详情必须选商家”，新聚合用例检查真正ALL授权；另证社区范围为空、非运营拒绝、跨主体禁止、另一租户不计入total。67条pay内部客户端有真实Handler。
- V2文档与diff检查通过，未增加恢复权限或更改任何金融事实。JB045跨主体列表缺口已补，死信等其他生产者与总体验收仍待后续；不提前改父任务DONE。

### 连续执行N10/N11源预检（2026-10-19）

- 源码确认V232将mkt用户券复制到pmt而保留原行，同号可能是同一权益的旧影子，不得把两个来源各自导入为可消费余额。owner的精确来源键只解决定位，不能自行证明它们是两份独立权利。
- 新增core LegacyCouponMigrationPreflightService/Impl，只读REPEATABLE_READ按MAIN及来源主键扫描、最多100条/页；输出源/模板revision、原状态、总次/已用/剩余、原闭区间截止转目标半开截止（+1ms）、有效核销历史行数及码存在性。明确标记同号、模板缺失、平台承担/主体缺失、计数/状态/历史差异、旧订单/静态码适配及源冻结缺口；不导入、不冻结、不切owner，不返回旧码明文或其可枚举摘要。
- RED `/tmp/ent-legacy-preflight-red.log`到达缺失预检；GREEN `/tmp/ent-legacy-preflight-green.log`21模块编译及6项定向测试通过（预检1、既有新旧算价兼容5）。真实fixture证明同号mkt剩1/pmt剩3不会合并或再次发行，pmt历史缺失明确报出，旧券次数及所有pay账表不变。
- JB050/JB054进入DOING：预检及双进程验收只是子项；完整导入、源冻结排空、兼容写路由与目标数据库版本演练仍未交付。总计32 DONE /21 DOING /2 TODO，未推送/部署。

### 连续执行N09专项契约生成（2026-10-19）

- RED `/tmp/ent-openapi-red.log`到达缺失导出行为。实现真实Handler/Jackson3 DTO导出、确定次序OpenAPI、TS类型及路径/引用守卫。补充测试发现被JsonIgnore的record构造器参数仍存在于Jackson的属性描述中；按实际ignored-property集合过滤，未修改实际DTO来迁就生成器。
- `/tmp/ent-openapi-final-fixed.log`21模块编译及4项测试全部通过（公开56/core反向10/白名单及独立泛型隐私测试）。`/tmp/ent-openapi-generator-run.log`用正式生成命令再编译及3项接口检查通过，输出56公开操作/103 DTO schema；`/tmp/ent-openapi-node-tests-final.log`Node2项通过，独立tsc --noEmit --strict类型检查通过，生成物自检一致。
- 该专项spec并非全仓OpenAPI替换；构造器/上下文权限规则仍按TDD执行。JB053仍保留三端既有生成体系/权限登记的最终总对账，不把几份生成文件等同所有客户端已联调。

### 连续执行N10/N11源根写栅栏（2026-10-19）

- V460原mkt/pmt根各补4个源字段；源锁、命令审计、排空检查、HMAC水位及所有旧规则/财务写接线落地。只有本地BEGIN_DRAIN/FREEZE/ABORT，无提交/owner切换；默认空密钥禁止新迁移，超限不截断。
- RED /tmp/ent-source-fence-red.log到达缺失行为。初装配发现纯SQL接口缺@Mapper，按原扫描规则补齐；回归发现旧4参issue的default委派绕过事务，补具体入口，未削弱MANDATORY守卫。
- /tmp/ent-source-fence-verified.log：21模块编译及28项定向测试通过（源4/旧发行9/旧分摊8/双用2/活动同步4/装配1）。覆盖mkt/pmt排空冻结中止、原释放、未知历史拒绝、历史回补拒绝、重放不复活旧批次、缺密钥不写状态/审计；无目标资产或owner写。
- /tmp/ent-source-fence-migrations-final.log原生MySQL1项通过、无跳过，SQL守卫通过。V1旧排序规则仅在源根夹具归一化，未改原迁移，不宣称全库升级。schema仍255表/2374种子。
- 受信完整源快照、目标暂存/比对、源提交决定、原子导入/owner切换及按owner适配仍待继续；源冻结不等于迁移完成，父任务未改DONE。

### N02/N07/N08死信恢复切片验证（2026-10-19）

实现OUTBOX_DELIVERY原事件恢复闭环：耗尽登记、实体受限异常/审计查询、RETRY_EVENT排队、QUERY_EVENT核查、实际SENT才关闭，后台有界扫描补重启窗口。损坏事件仅ALL运营可读安全元数据，拒绝自动修改载荷或猜测主体；恢复路径不重新发放、充值或扣次，也不再发布递归异常事件。V461扩展动作CHECK，失败重试加版本栅栏。

Java21受影响22模块编译成功，50项定向测试0失败/0错误/0跳过，日志/tmp/ent-deadletter-verified.log。首次合并契约测试因两个Spring上下文共享H2重复种子失败，修正测试库隔离后重跑上述全部定向测试通过。隔离MySQL动作约束+H2生成器2项通过、0跳过，/tmp/ent-deadletter-migrations.log；专项OpenAPI与TS生成及一致性检查通过（58操作/106 DTO），Node生成器2项通过。未运行全量回归；JB-043/045/051/053仍待父任务剩余验收，统计32 DONE/21 DOING/2 TODO不变；未提交/推送或部署。

### N11冻结源证据子批次验证（2026-10-19）

已完成编码并验证LegacyCouponFrozenSnapshotPort、源HMAC现场重验、8MiB有界读取和受认证反向RPC，纯pay只依赖共享契约。真实用例识别出“调用方RR旧快照接受过时事实”，已通过冻结/导出独立事务修正并见RED→GREEN。源证据不是目标提交许可；当前V1只覆盖可见实例及关联历史，完整存量/来源比对仍需后续。

Java21受影响22模块构建成功，55项定向测试0失败/0错误/0跳过（pay事件8、内部RPC28、部署classpath1、源栅栏/导出5、core真实HTTP4、公开契约3、死信3、路由3），日志/tmp/ent-frozen-snapshot-isolation-green.log，RED见/tmp/ent-frozen-snapshot-isolation-red.log。先前夹具码长/家族预期修正后也已完整重跑；未全量回归。公开58/pay内部69/core反向11。曾发现Relay源文件回到旧签名，已恢复恢复接线并在上述55项中重新编译验证，不沿用旧class作为当前证据。

N11剩余执行项：

| 子项 | 模块 | 当前状态与验收 |
|---|---|---|
| S01 冻结源证据 | core / shared / app / pay-svc | 本地及内部HTTP已验证，待整批提交；不代表完整导入清单 |
| S02 持久暂存与校验 | pay.migration | 证据加密/幂等/校验及两种部署接线已验证；保持STAGED，不是完整导入许可 |
| S03 完整来源与规范化 | core migration / pay migration | 全量计数/完整记录保全、规则规范化差异报告及两种部署已验证；V232同号认领、额度/敞口及最终导入计划待完成 |
| S04 源提交决定与目标导入 | core / pay migration | 待实现；先持久源决定，目标资产/映射/owner同pay事务，重跑不发行/不通知，未知结果原号恢复 |
| S05 原入口兼容与离线演练 | app / 旧core券 / pay | 待实现；原DTO/URL/码/报价/取消与售后不双扣，生产切换不在本任务执行范围 |

JB-049仍TODO，JB-050/054仍DOING；总计32 DONE/21 DOING/2 TODO不变。未提交、推送、部署或执行生产迁移。

### 死信公开分页规范修正（2026-10-19）

O08不再公开afterId/nextId，改为cursor/nextCursor；采用现有EntitlementCursorCodec，签名绑定MAIN、真实操作人、OUTBOX_DEAD_ASC_V1筛选，30分钟到期。沿现有cursor-key部署配置；篡改、跨操作人使用拒绝，公开响应不包含物理ID。Mapper仍以内部id做有界升序keyset。22模块编译及38项定向测试通过，/tmp/ent-deadletter-cursor-green.log；专项OpenAPI/TS重新生成并一致性校验，58操作/106 DTO不变。

### S03库存计数子批次验证（2026-10-19）

新增完整源表计数inventory，包含删除/归档及发行来源计数，原可见scan保留。真实测试发现“实例存在、原模板缺失”的核销历史此前未被孤立计数覆盖，先见失败（/tmp/ent-inventory-orphan-red.log），后改为核实完整实例→模板关联；无数据自动修补或导入。

Java21受影响21模块构建成功，7项源库存/栅栏定向测试0失败/错误/跳过，/tmp/ent-inventory-final.log；MySQL实际库存SQL查询1项通过0跳过，/tmp/ent-inventory-native.log。未全量回归，未新增表或公开HTTP。S03的计数盘点已验证，逐条规范化、完整冻结清单及迁移保全仍待后续，不关闭JB-050。父任务统计32 DONE/21 DOING/2 TODO不变。

### S02加密源证据暂存及接线验证（2026-10-19）

新增V462及ent_migration_stage，逻辑56表（core22/pay34）、运行256表/2374种子。完成AES-256-GCM证据保存、严格来源/摘要绑定、scope/原键/同批唯一性、默认密钥关闭与只读现场校验；状态只有STAGED，importReady恒false，无目标资产/owner/金融事件写入。实现pay内部3条RPC及embedded/standalone门面，pay Handler72/core反向11/公开58。

Java21受影响22模块编译成功，35项定向测试0失败/错误/跳过（crypto1、内部HTTP29、部署classpath1、实际暂存1、路由3），/tmp/ent-stage-rpc-green.log。随后经应用门面补角色/租户检查，并实际执行embedded1及双JVM/双DB暂存1，21模块编译成功、2项通过0跳过，/tmp/ent-stage-two-process-green.log。真实两进程中仅pay有密文，core无暂存记录，双方金融/owner表均无新增；子进程及临时数据库已清理。

原生MySQL V462约束1项通过/tmp/ent-stage-native.log；设计56表整体SQL1项通过/tmp/ent-stage-target-schema.log。SQL/V2文档/diff检查通过（完整文档回填后继续复核）。本批没有执行全量回归；父任务仍32 DONE/21 DOING/2 TODO，未提交/推送/部署。S02仅完成证据暂存，完整规范化清单、源持久提交决定、目标原子导入/owner和旧入口兼容仍未完成，不能把STAGED作为切换依据。

### S03完整源家族清单及双向接线验证（2026-10-19）

完成manifestVersion=2读取/加密暂存，包含删除实例、发行、核销历史和范围。旧可见V1不被覆盖，MKT“历史未记录”独立标识。V463兼容旧写者及旧AAD/密文，不改已执行V462。删除记录变化即使不影响旧可见水位也会使完整verify拒绝；后续源提交必须重验完整hash。

Java21受影响22模块编译成功，53项定向测试0失败/错误/跳过，/tmp/ent-manifest-stage-final.log：crypto1、pay内部HTTP29、部署classpath1、暂存3、真实双进程1、源栅栏/清单6、core真实HTTP4、公开契约3、全量源计数2、路由3。pay Handler73/core反向12/公开58。原生V462→V463升级约束及旧字段保留1项通过/tmp/ent-manifest-stage-native.log；目标56表SQL1项通过/tmp/ent-manifest-target-schema.log。没有运行全量回归。

当前逻辑56（core22/pay34）、物理256、生成器2375条种子/DML语句。完整记录保全已验证；S03仍需逐条规范化、来源重叠认领与差异报告，S04源持久提交及目标原子导入、S05原入口兼容仍未完成。父任务32 DONE/21 DOING/2 TODO不变；未提交、推送或操作生产数据。

### S03规则规范化及真实市场事实验证（2026-10-20）

已接通内部POST /internal/pay/entitlement/migration/stage/assess及本地/远程委派。core完整清单绑定商家激活、实际market、跨系统模板和实例同号（含墓碑）；pay重验原密文及当前完整源后从本地市场主数据确定CNY/精度/时区。纯LegacyCouponMigrationNormalizer按实际旧订单行为翻译CASH/PERCENT、原实例有效期及历史次数，报告原配置中未生效的范围/最低件数，不静默收紧已得权益。MKT缺历史、编号冲突、状态/期限/次数不一致、异常可选金额均明确阻断；删除实例仅档案计划，importReady始终false，不改资产/owner或发通知。

Java21受影响22模块编译成功，54项定向测试0失败/错误/跳过，/tmp/ent-normalization-final.log：纯规则7、pay真实HTTP29、纯pay classpath1、暂存与真实商家评估4、真实双进程1、源栅栏/清单6、公开契约3、部署路由3。内部pay Handler74/core反向12/公开58。原生MySQL实际库存及同号查询1项通过/tmp/ent-normalization-native.log；70份文档/SQL方言/OpenAPI与TS58接口106DTO/空白检查通过。补测中的影子券夹具缺必填起止日期已修正，并在最终批次全部重跑。

不关闭JB-049/050/054；父任务32 DONE/21 DOING/2 TODO不变。接下来补额度/敞口、来源认领与最终导入计划，再完成S04源持久决定及目标原子导入、S05原入口兼容。尚未全量回归、提交或推送。

### S03发行额度与敞口基线验证（2026-10-20）

实现LegacyCouponIssuanceAssessor并纳入Report.issuance。完整实例（包括删除）核对原receivedCount，保留两套源不同的额度0/NULL语义及原逐人上限；终身发行承诺与活跃剩余权益敞口独立计算。不能以过期/删除冲回历史发行量；预算不足、逐人超限、缺记录/有限封顶及Long溢出明确BLOCKED，不扩预算、不修源。旧PMT多次券预算原来未乘使用次数，差异用LEGACY_BUDGET_EXCLUDES_REPEAT_USES报告。

Java21受影响22模块编译成功，61项定向测试0失败/错误/跳过，/tmp/ent-migration-issuance-final.log：规则8、发行基线6、内部HTTP29、classpath1、暂存/真实商家4、真实双进程1、源栅栏6、公开契约3、路由3。未全量回归；没有新增表、权限或公开接口，pay内部74/core12/公开58保持。下一步仍是来源同号认领及持久目标计划、S04提交/原子导入和S05旧入口兼容，不因只读评估通过关闭JB-049/050/054。

### S03双源认领与并发验证（2026-10-20）

实现V232未用同号家族的严格比较/持久认领、comparedAt固定摘要、当前双方重验和canonical/shadow区分。CASH的无效封顶字段、空/零门槛按实际计算语义等价；原数据不修改。已有使用/已撤销历史、身份/余次/有效期/额度差异或额外碰撞仍隔离，不靠认领覆盖。只记录平台声明与审计，importReady=false，不写资产、registry或outbox。N10旧路由仍未切PAY。

验证：/tmp/ent-migration-alias-final.log22模块编译、70项定向测试0失败/错误/跳过，包含实际双进程、原键并发及影子ABORT使主源拒绝；锁序加固后/tmp/ent-migration-alias-locks.log22模块+56项定向全绿。/tmp/ent-migration-alias-native-writer2.log以真实MySQL运行生产Writer/Mapper，20并发同键重放、重复成员及预先建立的旧RR快照下冲突全部通过，1项0跳过，失败命令不残留PENDING。/tmp/ent-alias-native.log原生V464约束与57表目标DDL共2项通过。测试夹具的Spring构造注册重载编译歧义已修正后重跑。

V464已经原生执行，后续不得修改；新增第10张支撑表ent_migration_alias，当前逻辑57（core22/pay35）、主迁移物理257、生成器2375条种子/DML语句。文档70份/SQL方言/公开58接口106DTO生成契约/空白检查通过；内部pay77/core反向12。不关闭JB父任务，32 DONE/21 DOING/2 TODO保持；仍未全量回归或提交推送。

后续优先核对旧订单PAY兼容协调，避免在core持锁markUsed中发远程扣款；再完成持久导入计划、core原提交决定及目标原子导入/owner接管和离线演练。识别/暂存/评估均不能替代提交决定或兼容能力证明。

### N10来源与owner解析子批次（2026-10-20）

新增共享LegacyCouponIdentityPort/Origin、core LegacyCouponIdentityPortImpl/LegacyCouponOriginRow及两条按tenant+user+原实例号的显式SQL，fresh RR读取双源墓碑/孤立实例。新增App LegacyCouponRouteResolver：明确OPEN/ABORT保持原CORE PMT优先；冻结/阶段混合/缺根/已删除不换写者；真正COMMIT必须有源持久计划摘要并与精确registry来源、批次、目标一致。PAY查源前拒绝环境事务。无旧来源只允许精确PAY原生登记，已知删除来源不得借该分支复活。

LegacyCouponSourceCommitPort当前只有共享只读契约，没有源提交生产者，因此实际Root COMMIT缺决定仍拒绝。此处只完成路由元数据，不接现有CouponPortRouter的PAY金融路径，也不开放新HTTP或切owner。JB-049从TODO进入DOING；父任务现32 DONE/22 DOING/1 TODO（055）。

Java21受影响22模块构建及47项定向测试通过，0失败/错误/跳过，/tmp/ent-legacy-route-final.log：路由7、源栅栏/身份7、真实pay内部HTTP30、部署路由3。已知删除不回退RED /tmp/ent-legacy-route-deleted-red.log后修复；孤立行测试补齐必填expire_at后重跑。原生实际SQL库存/身份/墓碑/孤立/租户及同号查询1项通过/tmp/ent-legacy-origin-native2.log；初次Node全局注解正则跨越新增方法，改为按准确方法就近提取文本块后通过。表/迁移/公开接口数不变。

### N10跨店报价与准备契约子批次（2026-10-20）

增加LegacyCouponCheckoutSourcePort和LegacyCouponUsePort共享记录及LegacyCouponQuotePolicy，保留原商家/门店分摊、CASH不执行百分比封顶、PERCENT有限封顶/门槛及舍入语义；未用默认0代替未知取消金额。尚无真实checkout源生产者或pay金融实现，不开放新HTTP或切owner。

RED /tmp/ent-legacy-quote-red.log；record内同名静态校验方法引用先出现编译遮蔽，显式限定接口静态方法后修正。/tmp/ent-legacy-quote-final.log Java21受影响22模块编译、47项定向测试0失败/错误/跳过（报价3、来源/路由14、真实pay内部HTTP30）。当前逻辑57/物理257，pay77/core12/公开58保持；父任务32 DONE/22 DOING/1 TODO不变。

### N10旧ORDER券pay金融内核（2026-10-20）

V465新表ent_legacy_coupon_operation持久原操作/源快照/门店分摊及真实份数账序，原EntInstance/EntLedger仍是唯一余次真源。LegacyCouponUseService锁外查core/owner，Writer事务内认领→原实例→不可变流水→状态；支持prepare/commit/release/reverse/query。关闭新入口和冻结不取消既有承诺；取消先到无优惠金额/无账序，迟到prepare沿原号返回RELEASED。QUOTE_ONLY源只能报价，不能代替CHECKOUT授权；未接任何新卡/储值绕行。

/tmp/ent-legacy-use-red.log缺实现RED；首轮夹具Timestamp按主机时区写入未来，改LocalDateTime UTC后真实事务4项通过。HOLD对账RED /tmp/ent-legacy-use-recon-red.log（1条假差异），合并真实legacy HELD后清零；QUOTE_ONLY RED /tmp/ent-legacy-quote-only-red.log后补明确意图种类。最终/tmp/ent-legacy-use-kernel-final.log22模块编译、52项定向0失败/错误/跳过（金融5、报价3、来源/路由14、pay真实HTTP30）。

/tmp/ent-legacy-use-native-schema.log原生V465状态/NULL序号约束和58表目标DDL2项通过；V465已原生执行，后续不得改脚本。/tmp/ent-legacy-use-native-writer.log真实生产Service/Writer/Mapper及MySQL JSON验证1项0跳过：20次重放、剩余最后一份争用、确认/完整退款回补/释放/无占用取消后6条原账序守恒，失败事务无PREPARING残留。Core真源和owner为该测试显式夹具，不冒充整业务验收。

当前逻辑58（core22/pay36）、支撑11、物理258、种子/DML2375；新配置shop.pay.entitlement.legacy-coupon.enabled默认false，需use.enabled。内部pay仍77/core12/公开58，因新金融HTTP/源生产者未接。通知/新旧消费历史、core结算意图/旧订单计价/取消售后、持久导入计划/源提交与原子迁入继续执行；32 DONE/22 DOING/1 TODO，未全量回归/提交/推送。

### N10旧券core来源与原订单创建进展（2026-10-20）

已完成pay六条内部操作、core三条来源只读接口及embedded/standalone适配。V466新增core不可变结算日记，当前目标59表（core23/pay36）、支撑12表（core5/pay7）、生成运行schema259表；V466已原生MySQL执行冻结。源提交决定与实际订单同core事务；core日记read/abort和pay调用不持有对方数据库锁。公开接口仍58操作/106 DTO，pay内部83/core反向15。

原OrderService已接应用协调、真实SKU/活动后金额快照、pay先预占/core建单提交/原pay完成，以及决定outbox恢复；CORE_LEGACY与无券走原事务写入。此前7项core定向及4项创建入口验证通过，正在合并验证原下单回归、决定后失败回滚与回包丢失恢复。测试中的迁移前置认领/registry为显式夹具，不意味着已经实现实际迁移切换。

父任务仍32 DONE／22 DOING／1 TODO（JB055）。下一步继续旧预览/查询/取消与售后、未决意图恢复、账单通知投影，再完成持久目标计划、core源提交生产者与pay原子导入/owner接管；最后执行统一回归与提交验收。没有修改生产数据、开启开关或切换owner。

创建批次合并验证结果：22模块编译成功，76项定向测试0失败/0异常/0跳过（`/tmp/ent-legacy-order-entry-verified.log`）。已验证实际pay提交不可用时原core决定/订单保留，原outbox在新使用关闭后完成同一操作；在core决定之后注入订单事件写入失败会整体回滚，再由ABORT释放原预占。当前进入取消/售后与未决恢复批次，未宣称旧接口完整兼容。

取消／售后／恢复批次：原取消及超时关单同core事务记录退回决定，pay原outbox恢复次数；售后完成事件提交后读取真实退款源，部分现金账本不会恢复整券。V467仅追加技术确认与有界恢复索引，无新业务表，已原生升级冻结。复用既有LEGACY_COUPON恢复分类，完成原意图有效期内不取消、过期且无订单决定时落真实ABORT、预占前后崩溃与原提交响应丢失恢复。21模块／36项定向测试通过（`/tmp/ent-legacy-source-audit-green.log`），原生SQL4项通过；未全量回归，父状态仍32／22／1。

当前继续旧预览和用户查询兼容、消费／通知投影，以及持久导入计划与源提交／pay原子接管。实际旧Root owner尚未切换，测试中的前置认领为显式夹具。

#### N10 旧券统一消费记录（2026-10-20）

- V468 增加 ent_usage.protocol/legacy_instance_no，真实旧券 operation_no 直接引用原核销操作，不生成虚假支付意图。支付 COMMIT/REVERSE 与消费事实在同一事务完成。
- 本人权益详情与消费详情可读原订单、折扣金额（非现金付款）、次数、提交时账后余次和退回状态。HELD/RELEASED 不形成消费，退回保留原消费事实并标记 REVERSED。
- 仅有明确单门店时记录门店号；未知名称留空；跨门店汇总不伪造门店、不重复整单优惠，门店权限只展示明确归属的记录，主体权限可看汇总，订单明细仍按原分摊展示。
- 原操作重放可修复缺失投影，禁止重复扣减、重复记账和历史到账通知。
- 编译及定向测试 20/20 通过（旧券真实订单/支付/恢复三组），原生 MySQL 升级约束 1/1 通过；不是全量回归，父任务仍未验收关闭。

#### N10 消费全历史分页（2026-10-20）

新增 C 端 `GET /mp/entitlements/{instanceNo}/usage`，按业务时间与记录号倒序、每页 1–50 条，包含 STANDARD/LEGACY 与退回状态。签名游标绑定本人/主体/权益/查询类型，不受最近 20 条限制。支付内部 history 由本地和独立部署适配共同实现。

22 模块编译、57 项定向测试通过（`/tmp/ent-usage-history-verified.log`），包括 31 条相同时间的稳定翻页、本人归属和游标篡改/跨权益拒绝、真实 RPC、原旧券消费历史及运行时路由。首轮 RPC 负例错误期待裸 HTTP 异常，已按实际统一 ServiceCallException 包装修正。OpenAPI/TS 按真实映射生成：公开 59 操作 / 106 DTO，支付内部 86、核心反向 15。全量回归仍安排最后执行。

#### N11 固定目标计划进展（2026-10-20）

新增 V469 ent_migration_plan 与固定目标清单：绑定完整源证据及认领来源，固定模板/历史规则版本/原实例映射，保存发行基线；消费者身份等明细 AES-GCM 加密，规划不写资产/账/registry/outbox，不补造旧消费或收款。归档实例无金融实例与发放号。计划元数据按平台主体隔离，同键原样返回；新请求仍不能改写已有目标计划。PLANNED/importReady=false 仅规划状态，尚无源 COMMIT 或目标原子导入。

21 模块编译、26 项定向测试通过（`/tmp/ent-target-plan-verified2.log`）：完整证据验证、加密/幂等/权限、不同历史期限规则、同号双来源只规划一次、影子拒绝、源变更/密文损坏、重复实例键拒绝及任务开关。V469 和 60 表设计 DDL 各一项隔离 MySQL 验证通过；V469 冻结，后续追加 V470+。运行 schema 260 表，逻辑60=core23/pay37，支撑13。正在接计划内部 HTTP 与部署适配，后续仍需源持久提交生产者、支付原子导入/owner 切换、旧定义及发行兼容、统一验收。

#### N11 原子迁入与历史闭环进展（2026-10-20）

已验证：完整固定计划、core永久来源决定、pay原子模板/实例/发行额度基线/权益基线/owner接管；同号双来源仅迁入一份资产。原生MySQL20并发同键只生成一份迁入回执；已有回执新键读取不依赖源可用。发行保持暂停，迁入不产生普通grant、历史付款或历史通知。

历史核销现可按原事实导入，C端统一消费历史包含无新实例的归档记录。真实部分退款不能回补，整单全退回补一次；pay回执确认后才展示“已退回”。真实退款事件→core不可变退回决定→pay回补账/历史REVERSED→core确认链已接通，21模块编译和9项定向测试通过（`/tmp/ent-historical-events-green.log`）。V472–V473历史映射/退回决定/回执约束在隔离MySQL完整67表DDL验证通过；两版本已执行冻结。

连续执行顺序：
1. 完成历史退回的独立部署RPC、鉴权及有界恢复验证，验证取消入口。
2. 完成旧定义/发行入口兼容及迁入发行恢复边界，核对原PAY券兼容范围。
3. 完成新建/升级数据库与embedded/standalone迁移演练，更新API、数据库和类清单。
4. 最后执行N12统一后端回归、整体审查与修复，满足验收后提交推送。

父任务状态保持32 DONE /22 DOING /1 TODO；以上子批次通过不代表N10/N11/N12整体验收完成。

历史退回接线补充：双向RPC及恢复路由复验22模块42项通过（`/tmp/ent-historical-rpc-recovery-verified.log`）；隔离MySQL20并发回补1项通过无跳过（`/tmp/ent-historical-native.log`）。真实取消入口、支付已完成但核心未确认恢复、原订单桥与公开契约合并18项通过（`/tmp/ent-historical-close-paths-verified.log`）。公开60操作/106DTO，私有pay94/core18，24生成器对账通过；N10/N11剩余项及N12状态保持。

#### N10 旧商家定义入口兼容（2026-10-20）

原CouponService/商家URL/DTO按core永久决定及精确TEMPLATE owner分流。迁入后详情真实反映pay发行暂停；显式恢复才开放发行策略。旧保存入口在同pay事务完成草稿、新版发布及策略更新，失败整体回滚；暂停状态保留，已得券规则及冻结core源不变。未迁移旧读继续支持原core事务；已迁移路径在调用pay前拒绝环境core事务。

验证：22模块41项定向通过（`/tmp/ent-legacy-template-save-verified.log`），另21模块18项合并验证通过（`/tmp/ent-legacy-template-boundaries-verified.log`），覆盖发布后策略降额失败回滚、新旧事务边界、原订单11项及历史退回2项。新增私有pay3条，当前pay97/core18；公开60操作/106DTO。旧发放/领取、MKT运营入口兼容、部署演练及N12继续，不将定义子批次作为整体完成。

#### N10 原发放、C领取及运营入口兼容进展（2026-10-20）

PMT旧发行和C领取已复用实际Core受众、pay统一batch/grant、冻结个人/总发行基线和预算。Core只保存完成的展示身份，不写冻结原券实例；耐久事件与默认关闭的有界恢复保证重复续跑不重复发券。旧券目录按永久来源目录读取当前pay定义，已发批次净使用统计来自真实支付核销/退回事实。

22模块50项定向验证通过（`/tmp/ent-legacy-issue-usage-effects-green.log`），包含实际SKU原下单抵扣及取消退回。MKT运营兼容22模块编译及1项完整场景通过（`/tmp/ent-mkt-compat-green2.log`）：单用户发两张、本人再领一张、超限拒绝、预算/未来规则改版、发放记录、归档幂等和恢复；冻结原表无双写。当前逻辑69=core27/pay42，运行269表/2375种子，私有pay102/core19，公开60操作/106DTO。

仍连续执行：旧活动引用准入边界与兼容基本回归、隔离数据库/双部署整体迁移演练、N12统一验收及审查。父任务状态保持，不以子场景通过代替整体验收。

#### N11 数据库与独立部署演练；进入N12（2026-10-20）

旧券双进程新券/历史回补4项通过（`/tmp/ent-legacy-dual-targeted3.log`），先前美业/三类付费卡/源暂存5项通过（`/tmp/ent-legacy-migration-dual.log`，该首轮新增旧券场景因测试未按主体计数失败，不将该首轮整体记为通过）。修正仅测试范围与显式测试开关后，新券真实Core源提交→pay接管→C领取→原SKU下单→取消恢复、历史旧券重复取消回补均通过；两个进程/数据库保持各自所有权。

`/tmp/ent-schema-flyway-native-fast.log`：隔离MariaDB 11.4真实Flyway（包含Java迁移）3项验证通过，无跳过；新建库397份迁移至V474，增量库347份迁移至V424后再50份至V474。两库269张表、列默认值/排序规则与索引完全一致；旧券原面额/已发计数/状态保留，无制造新权益、资金账或owner，重复migrate及validate通过。已冻结SQL保持原字节；Flyway专用连接适配MariaDB DROP CHECK语法，不更改普通业务连接，关闭迁移占位符替换以保留V121注释中的字面Spring表达式。仅一次性验证库开启JDBC批量加速，生产参数未改。

领券中心同号真实别名去重RED→GREEN，3项运营兼容和2项方言基本单测通过（`/tmp/ent-legacy-center-alias-green.log`）。当前清理架构规范导入项，随后N12单次全量后端回归、整体审查和提交；当前32 DONE/23 DOING/0 TODO，未提前关闭父任务。最终证据统一记录于[Java后端最终验收](./archive/records/活动权益-Java后端最终验收.md)。

N12定向收口更新：首轮完整后端4017项捕获规范/夹具及跨域结构问题，均已按真实原因修复；不把失败首轮当通过。22模块66项（16架构+38内部HTTP+实际源提交/迁入/发放/历史退回）通过。最新MySQL两个原生金融测试、双进程7场景和真实契约3项全部通过，无跳过；记录分别见`/tmp/ent-final-architecture-green5.log`、`/tmp/ent-final-historical-native.log`、`/tmp/ent-final-coupon-use-native.log`、`/tmp/ent-final-two-process.log`及`/tmp/ent-final-contract-extract.log`。当前进行最终整体回归与独立审查，父状态保持DOING，未推送。

### N12最终关闭（2026-10-20）

最终代码`684b0f074`；27模块完整编译及回归4019项，失败0、异常0、条件跳过18；原生数据库与双进程单独无跳过验收，见[最终验收](./archive/records/活动权益-Java后端最终验收.md)。

公开64操作/107DTO、私有pay102/core19；两项Important审查问题均RED→GREEN关闭。历史SQL保留校验和，旧活动引用先转换再切换，46预置仍默认关闭。B/C实际页面43条接口待接入，另表记录；没有生产部署或切换。

### 干净副本并发补验（重新打开4项）

正式pre-push暴露发券并发确认连接池耗尽；之前4019项通过保留为单次历史结果，不再作为修复后的最终验收。根因是`DataSourceTransactionManager`默认在无实际事务时仍开启Spring同步，`NOT_SUPPORTED`内的MyBatis读取因此将Pay连接绑定到线程；后续真实写事务再取连接，8路并发占满8连接后互相等待。

修复将Core、嵌入式Pay和独立Pay三个事务管理器设为`SYNCHRONIZATION_ON_ACTUAL_TRANSACTION`，仅真实事务启动同步。定向测试RED→GREEN：`/tmp/ent-pool-starvation-fix-targeted.log`中Core/Pay两个`NOT_SUPPORTED`资源释放及8路并发同grant只发一次共3项通过；`/tmp/ent-pay-sync-fix-targeted.log`独立Pay最小配置1项通过。Java21 Maven编译成功，`git diff --check`通过。遵照用户指示，不重跑已通过的全量4019项回归；后端提交/部署仍待完成。

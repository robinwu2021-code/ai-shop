# 活动卡券储值模块化系统设计

状态：实施中（目录基础已接入持久化，整体未上线） · 档位：2 · 2026-10-18

本期目标架构明确：活动类归核心服务，权益类归支付服务。活动管理规则、参与资格及营销预算；支付管理券模板及版本、用户券、付费次卡/月卡、储值账户、权益发放与核销账。商品、订单、会员、预约/履约继续属于核心业务。HTTP入口可以仍在shop-app，入口所在工程不代表业务/数据所有权。

本文是本轮目标架构入口。既有储值设计、统一活动扩展和UI稿中“券在promotion”“次卡在会员资产”的归属与本文冲突时，以本文提案为准；这不意味着运行代码已完成迁移。实施按用户授权的D01–D07推进，实际进度与版本号见§6。

## 文档包

- [Java后端独立任务表](./任务表-活动卡券储值-Java后端.md)：本次执行台账，Java后端优先；包含已完成证据、剩余依赖及表/API/类对账。

- [D01源码盘点](./design/活动权益-D01-现状盘点.md)：旧券读写链路、部署约束与环境待核对项。

- [代码结构与类清单](./TDD-活动卡券储值-代码结构与类清单.md)：物理包、服务/策略/端口/Controller、逐表实体Mapper及旧类迁移。

- [功能矩阵](../requirements/活动卡券储值-模块功能矩阵.md)：三端、模块、模板、接口及数据映射。
- [数据库设计](./TDD-活动卡券储值-数据库设计.md)：核心/支付分别拥有的数据字典、约束、迁移。
- [API契约](./TDD-活动卡券储值-API契约.md)：沿用接口和新增接口、请求响应、权限与内部协议。
- [核心建表草案](./design/活动权益-core-schema.sql)、[支付建表草案](./design/活动权益-pay-schema.sql)、[支撑表草案](./design/活动权益-support-schema.sql)：合计44张设计DDL，不是Flyway迁移。
- [ADR-038](./ADR/ADR-038-活动归核心权益归支付.md)。
- [40项活动及6项产品模板](../requirements/预期活动预置清单.md)、[预置JSON](./design/活动预置模板-目录草案.json)。
- [统一UI功能矩阵](../requirements/活动卡券储值-统一入口功能矩阵.md)、[29屏原型](../../prototypes/marketing-assets-unified.html)。原型未覆盖6项付费卡完整购买/激活/核销界面，其页面补齐列为设计交付后的独立工作，不冒充已画完。

## §0 现状核对

JB-010需求对账：[PRD-美业储值 §4.1/入口补充](../requirements/PRD-美业储值.md)要求复用统一会员码、扫码不直接扣款；功能矩阵U22对应C08/C11、core.member凭证表及JB-011本人授权。原型只是会员码占位，不能作为可信码来源；本批补真实签发，页面仍后置。


| 当前源码/契约 | 当前职责 | 目标处理 |
|---|---|---|
| shop-core/promotion/PmtActivity、PmtActivityRule、ActivityService | 统一活动、排期、范围、规则 | 保留，补scene、版本、参与预留 |
| shop-core/promotion/PmtCoupon、PmtUserCoupon、PmtCouponIssue、CouponService、CouponRedeemService | 核心内的券模板/实例/发放/核销 | 目标移至pay.entitlement，契约入口兼容，不能重复建写入口 |
| pmt_apply | 混合活动减价与券使用记录，供营销统计 | 核心保留订单活动优惠事实；券权威使用账迁pay，营销订阅派生统计 |
| marketing旧mkt_*和promotion新pmt_* | 当前逐笔owns路由并存 | 清点两类真实数据及调用，建立精确迁移映射，不只迁新表 |
| pay-domain、payTxManager、stl_payment | 支付事务/收退款流水 | 复用；权益子包统一用pay工厂和事务 |
| 储值sv_*草案 | 未实现的储值设计 | 改为本次pay表族，不在core保存余额 |
| CARD商品、订单、会员 | 商品与交易、身份 | core持有，商品引用已发布权益版本；支付不查core库 |
| /biz/activities、/biz/coupons、/mp/coupon等 | shop-app现有HTTP入口 | 原URL保留或显式新增，内部委派按业务所有者改造 |

本次按本地源码核对，没有查询生产券数量、发行余额、真实通道、统一会员码可用性或部署开关；实施前必须核实。

## §1 模块边界与代码结构

![活动与权益模块所有权](./diagrams/activity-entitlement-ownership.svg)

应用层协调本域事务和事件，不建立core/pay跨库事务。

| 模块 | 权威数据与行为 | 只能读取/引用 |
|---|---|---|
| core.promotion | 活动头/版本/规则、资格、参与名额、营销预算预留、活动参与、营销效果投影 | pay权益模板引用/发放结果，不能写用户余次/余额 |
| core.product | CARD可售商品、SKU售价、销售范围与上架 | 已发布权益版本，不自行定义另一份余次规则 |
| core.trade | 订单定价、销售、本人授权意图、履约/预约与售后政策 | 调pay预占、确认、释放，按回执推进订单 |
| core.member | 会员关系、统一会员码、顾客识别 | 通过pay查询权益，不存余额/余次副本 |
| pay.entitlement | 券/兑换券/付费卡模板与版本、库存/发行授权预算、发放、用户权益实例、次数与周期计数、使用账 | 活动资格凭证、订单支付/履约可信事实 |
| pay.storedvalue | 充值/实收确认、金额账户/批次、预占、记账、核销 | core活动权益快照 |
| pay.points | 既有积分改造边界 | 活动若发分调用已验证资金支持的积分协议，不借发券发行积分 |
| shop-app编排/消息 | 跨模块快照、事件协调、对外API、鉴权；消息发送 | 不直写另一模块业务表 |

```text
backend/shop-core/.../promotion/
  activity/{model,service,policy,store}     活动类型与版本、规则
  participation/{service,store,event}     reserve/confirm/release
  preset/                                40项预置玩法目录与能力过滤
  projection/                            营销效果，来自权威事件
backend/shop-core/.../{product,trade,member}/  商品售价、销售订单、身份与履约
backend/shop-base/.../spi/
  promotion/ActivityParticipationPort.java
  entitlement/{EntitlementCatalogPort,EntitlementGrantPort,EntitlementUsePort}.java
  storedvalue/StoredValuePort.java
backend/pay/pay-domain/.../pay/
  entitlement/
    catalog/ grant/ account/ hold/ use/ lifecycle/ query/ recon/ event/
    model/ policy/ posting/ store/ port/
  storedvalue/
    recharge/ account/ posting/ redemption/ query/ recon/ event/
  mapper/{entitlement,storedvalue}/         扫描归paySqlSessionFactory
backend/shop-app/.../
  portal/{biz,mp,ops}/                      保留既有风格的HTTP入口
  payclient/                               三端pay门面
  paybridge/ActivityBenefitCoordinator.java
  paybridge/CardSaleCoordinator.java
  paybridge/EntitlementOrderCoordinator.java
```

model/policy无Spring/MyBatis；业务接口与实现分开；资金/余次变化仅经posting，Controller不访问Mapper。core不import pay实体，pay不依赖shop-core；内部SPI放shop-base。pay可本地或独立部署，远程适配沿用已有内部API承载方式，不能把本地调用成功当成远程组合已通过。

## §2 预置模板与UI

四类活动模板仍归core：成交优惠、权益发放、协同成交、储值激励。40项AP模板只负责创建活动草稿，权益类活动引用pay已发布的模板版本；商品赠品仍走商品库存履约，积分走pay.points，赠金走pay.storedvalue，不把所有benefit都强塞进券账户。

6项EP产品预置：core保存可售CARD商品/售价/模板引用；pay保存权益定义与售出实例。COUNT、PERIOD_COUNT、PERIOD_DAILY分别表示总次、周期总次、每日额度。赠送多次兑换券与付费卡业务源分开。

预置目录只保存推荐标签与schema，不复制业务权威模板。默认关闭，不自动发布。40活动模板和6产品模板都有capability gates；业态标签须映射现有入驻主数据，尤其餐饮、健身、宠物等不因列入目录就开放。金额示例不直接转成正式售价或预算。

商家仍从营销→活动中心进入：活动管理由core；卡券模板、发放/核销操作委派pay；储值办理委派pay并引用core资格；付费卡售卖去商品/订单，管理余次去pay。运营同一入口按权限聚合，资金异常独立入口。C端“我的券/我的卡/会员码/储值核销记录”展示本人权益，储值列表先门店按最近成功储值/核销倒序，不显示内部双池、不单设储值码。

### 2.1 分阶段落地任务

| 阶段 | 主要修改模块 | 交付与闸门 |
|---|---|---|
| D01 契约核对 | shop-base、shop-app、core/pay | 盘点全部券来源与URL，冻结DTO、错误码、权限、迁移映射；明确会员码与订单授权可用性 |
| D02 权益基础 | pay.entitlement | 模板/版本、发行控制、实例、幂等和事件；并发限额、来源唯一性验收 |
| D03 存量迁移 | core.promotion、pay、兼容门面 | 按批迁移，逐资产核对，owner单写与老URL回归 |
| D04 统一活动 | core.promotion | 活动版本、预置目录、资格/预算预留、效果投影；未实现玩法禁用 |
| D05 储值闭环 | pay.storedvalue、应用编排 | 商家收款、到账、使用、门店目录和通知；实收/取消竞争与账实对账 |
| D06 付费卡 | core.product/trade、pay.entitlement | 售价绑定、付款后发卡、首用激活、总次/每日额度；先补UI及经营条款再开放 |
| D07 三端与上线 | biz/mp/ops、消息 | 按U01–U30验收，权限/旧契约/故障恢复、迁移演练、灰度和回滚条件 |

各阶段保留明确能力开关，先验收支付权益底座，再让活动或商品调用。DDL须转正式增量迁移后实施，不能把设计草案直接部署。

### 2.2 执行基线与已核实落点

计划状态：执行中，已完成源码盘点及支付权益目录内核，任务级状态与验收见§6。独立开发分支为codex/stored-value-entitlement-dev，工作区为/Users/robin/.codex/worktrees/stored-value-entitlement-dev，基线00d04e580。需求/设计文档已以d0b199bb6同步并提交至开发分支；原工作区的其他未提交修改未带入。

档位：2；依据为本TDD、ADR-038与U01–U30。计划不预分配Flyway版本，也不将线上数据盘点视为已完成。

| 已核实事实 | 代码落点与执行决定 |
|---|---|
| shop-app配置Flyway locations=classpath:db/migration | 增量迁移位于backend/shop-app/src/main/resources/db/migration；执行前重新查询版本占用，不能直接运行设计CREATE |
| pay-svc/application.yml关闭Flyway，当前仍连接共享主库 | 首期继续由主应用跑迁移；进程独立不等于数据库已独立；不新增第二个迁移执行者 |
| PayDataSourceConfig与PayApplication只扫描ai.neargo.shop.pay.mapper | 权益和储值Mapper放pay/mapper/entitlement及pay/mapper/storedvalue；实体/服务仍按pay/entitlement、pay/storedvalue划分 |
| payTxManager与核心事务独立 | 写服务显式指定payTxManager；编排方法无跨库事务；本地调用也走相同分阶段协议 |
| CouponPortRouter按owns在新旧模型间路由，release会调用两侧 | 迁移前登记旧模型精确owner；取消/售后按原使用事实分派，已迁资产不能再次写旧模型 |
| PayInternalApi/PayInternalPaths与InternalPayEndpoint已存在 | 沿既有内部HTTP承载方式扩展，所有新方法同时提供本地/远程契约与查询恢复 |

下面任务的类全名与方法以[类清单](./TDD-活动卡券储值-代码结构与类清单.md)为准；DTO字段以[API契约](./TDD-活动卡券储值-API契约.md)为准。表名均为[数据库目标模型](./TDD-活动卡券储值-数据库设计.md)，实施时拆为可独立验证的增量迁移。

### 2.3 可领取任务与依赖

任务状态统一使用TODO/DOING/BLOCKED/DONE；下表为原始阶段规划，初始均为TODO。当前叶子任务状态以[Java后端独立任务表](./任务表-活动卡券储值-Java后端.md)为准。完成条件必须有代码差异与真实验收证据，设计文件存在不算实现完成。每行建议形成一个范围明确的提交或PR；任务过大时按相同编号增加子任务，不改所属模块。

| 编号/依赖 | 责任模块与主要类/文件 | 实施步骤与接口/表 | 完成证据 |
|---|---|---|---|
| D01.1 / 无 | shop-app配置、core promotion/marketing、pay | 记录mkt/pmt表与所有读写入口；列支付事件、会员码、授权意图、Outbox能力；完成源表→目标表/owner映射 | 盘点清单含源码路径、存量环境待查项；未知项有明确处置，不虚构生产数据 |
| D01.2 / D01.1 | shop-base SPI、payclient、PayInternalApi/Paths | 固定SourceRef/DecisionRef、内部命令、状态与query结果；每条API登记新增/兼容、权限、错误码、三语文案与DTO | 外部47条与内部12项均有负责人/阶段；旧接口样例和字段兼容对照 |
| D01.3 / D01.1 | core.trade/member、CardSaleSnapshotService、EntitlementAuthorizationService | 核实本人码识别、授权持久化、源支付/履约决策与查询；列出订单意图、Outbox所需正式字段/表扩展 | 可信源号/状态/修订号可查询；缺少机制时补DDL/API，不以字符串引用代替授权 |
| D01.4 / D01.2 | 数据源配置、pay-svc装配、迁移脚本 | 冻结Mapper路径与事务；核实目标MySQL/MariaDB版本、JSON/CHECK/字符集；修复编译闸门脚本变量问题 | 真实编译基线、已有失败清单、迁移执行与装配方案记录 |
| D02.1 / D01.2/D01.4 | pay实体/Mapper、公共幂等与事件适配 | 建模板/版本、issue_policy/recipient、grant/instance、dedup、outbox/inbox目标表与实体；迁移先扩展不删旧列 | 全新库与存量升级均可运行；Mapper在pay工厂；重跑迁移无数据破坏 |
| D02.2 / D02.1 | EntitlementCatalogService、EntitlementRuleValidator | 模板草稿→发布版本→停发；校验kind/使用模型/范围/币种；P01–P06及内部目录查询 | 老实例规则不随模板改版；停发不撤销历史；发行上限不能低于held+used |
| D02.3 / D02.2/D01.3 | GrantService、IssueBatchService、PostingService | prepare/commit/abort/query；来源唯一、批量预检与容量预留；P07/P08/P11、I05–I07 | 同源重试同实例、异参冲突、并发不超发；跨服务未知不释放营销占用 |
| D02.4 / D02.3 | UseService、CouponQuotePolicy、CodeService | intent/allocation/usage/ledger/code/reversal；迁移报价算法，preview/hold/commit/release/reverse/query；P12–P15/P30、I08–I11 | 只试算不扣、确认释放互斥、原码使用规则兼容、部分售后不超原分配 |
| D02.5 / D02.3/D02.4 | Query/Lifecycle/Exception/ReconService | 本人券包与记录、冻结、异常、恢复、对账；P09/P10/P25/P26/P29、O01–O05/O07 | 跨租户/门店/用户拒绝；余次/分录一致；异常不任意调平 |
| D02.6 / D02.2–D02.5 | payclient本地/远程Impl、InternalPayEndpoint | 新SPI全部接通两种部署；注册内部鉴权、序列化与超时处理；query覆盖在途操作 | 本地与远程同输入同状态语义；关停/超时不产生假成功 |
| D03.1 / D02.6 | EntitlementMigrationService、ent_owner_registry | 构建离线导入/比对工具，保存源号/版本/核销/余次/券码；无真实存量则记录空迁移证据 | 导入幂等，不发通知、不增加发行量；逐资产差异报告 |
| D03.2 / D03.1 | CouponPortRouter、LegacyCouponCompatibilityService | 将旧B/C入口及CouponPort映射唯一owner；release/reverse按原使用事实路由；保留旧DTO | 旧入口回归；未迁只写旧、已迁只写pay；未知owner阻止变更并报异常 |
| D03.3 / D03.2 | 数据迁移作业与运营流程 | 演练冻结批次/排空在途/回填/核对/切换；源表只读；不立即清理旧表 | 迁移前后逐资产次数/历史/码一致；切换后无旧写入证据 |
| D04.1 / D01.2，可与D02并行 | core ActivityVersionService、RuleValidator | 扩展活动版本、scope/audience、recharge_tier；C03–C06 | ORDER与RECHARGE隔离；发布不可变，暂停不撤销已参与 |
| D04.2 / D04.1/D02.2 | ParticipationService/Port、budget/participant/reservation | reserve/confirm/release/query，校验pay已发布版本；I01–I04 | 营销预算与个人限额并发不超；ABORTED后才释放；跨版本限购累计 |
| D04.3 / D04.1 | PresetService、pmt_preset_template、预置JSON | 幂等加载40项AP+6项EP目录，业态标签与能力过滤，C01/C02；只建推荐草稿 | 重复加载不重复建活动；缺能力不可发布；不自动上架/发权益 |
| D04.4 / D04.2/D02.6 | ActivityBenefitCoordinator、MarketingEffectProjector | core资格→pay发行→core确认；事件派生分项统计，C10 | 重投不多发；事件乱序不倒退；储值赠金不计订单折扣 |
| D05.1 / D04.2/D02.1/D01.3 | RechargeService/Coordinator | sv_recharge、account、lot、voucher、entry；P18–P21/P27，保存活动快照与实收证据 | 未实收可取消，实收/取消竞争唯一结果；客户端不能指定赠金 |
| D05.2 / D05.1 | CreditService、StoredValuePostingService | I12到账；余额/批次/双池分录/事件同事务，营销按成功事实确认 | 重复receive/credit不重复到账；实收后异常持续恢复；账户=批次=分录 |
| D05.3 / D05.2/D02.4/D01.3 | RedemptionService、AllocationPolicy | sv_hold_allocation接统一intent/usage；P22/P28，校验本人授权与真实服务订单 | 同源并发不重复扣；金额不超余额；释放回原批次；未知不盲目重扣 |
| D05.4 / D05.3 | StoredValueQueryService/StoreProjector | sv_user_store_index；P23/P24及C明细 | 只列有成功储值历史门店；max储值/核销时间倒序；耗尽保留、等时稳定 |
| D05.5 / D05.2/D05.3 | NotificationSubscriber、既有MessageService | 到账/核销/异常待办通知，O06复用；订阅模板和通道授权接线 | 提交后发送、事件去重、失败只重试消息；C通知无内部双池/明码 |
| D06.1 / D02.2/D04.3 | CARD商品UI、core.product、CardProductBindingService | C07，商品SKU售价引用pay已发布版本；补售卡/激活/余次/日额度原型 | 6种产品预置字段明确；不以储值余额购买冒充真实现金付款 |
| D06.2 / D06.1/D02.3/D01.3 | CardSaleCoordinator、core订单Outbox | 付款事实按订单行/单件/件内序号发卡；订单取消/付款竞争按源决策处理 | 买多件不漏发不多发；迟到支付事件不改变原付款起算时间 |
| D06.3 / D06.2/D02.4 | LifecycleService、三种UsagePolicy、period_bucket | COUNT/PERIOD_COUNT/PERIOD_DAILY；P16/P17，首用激活、总次/每日额度与预约日核销 | 自然月/滚动天数、跨日释放、月底、过期边界正确；并发首用只激活一次 |
| D07.1 / D04.3/D04.4/D05.4/D05.5 | B端/C端/运营端页面与API适配 | 统一活动入口、券管理、储值办理/核销、C记录、运营异常与对账；按功能逐段联调 | U01–U30与页面/API逐项映射，未覆盖有能力开关，不用Mock冒充接通 |
| D07.2 / D06.3/D07.1/D03.3 | 前后端集成与发布 | 双部署装配、目标数据库、兼容/权限/并发/故障恢复；灰度试点 | 全部发布门槛有真实证据；试点账实一致；付费卡条款和能力就绪才开放 |

执行优先级已按用户最新要求调整：先完成全部Java后端，D07.1中的HTTP/DTO/权限随对应后端服务开发，页面/UI原型后置；D07.2仍为整体收口。D03.3是生产切换任务，必须在D03.1/2演练后按窗口执行，不随迁移工具提交自动执行。

### 2.4 每个任务统一执行步骤

1. **定位与收敛**：读取对应API/表/类，确认现有可复用路径，列出实际修改文件；超出设计的契约先更新TDD与ADR取舍。
2. **定义失败场景**：从矩阵验收条件选行为，例如同源重投、异参、越权、取消竞争；写有意义的用例并确认失败确实到达目标行为，记录原因。
3. **数据与实现**：读取当前最大迁移版本后分配新号，新增增量脚本、实体与Mapper；实现业务状态机/幂等/事务，然后接端口和API；不得修改已应用脚本。
4. **接通读写与部署**：核对写后查询、C字段白名单、错误码三语、权限及菜单/OpenAPI登记；HTTP与内部服务都接真实实现，验证本地/远程选择互斥。
5. **验证与回填**：运行受影响用例与适当守卫；关键事务另在目标数据库验证；将真实命令、退出码、用例名称、未通过项回填§6，不填预计通过。
6. **限定提交**：审查diff与本任务文件清单，只提交相关变化；更新任务状态。上线、存量切换与代码提交分别记录，不以合并代替上线成功。

### 2.5 验证方案与证据登记

本节为后续实施的验证要求，本轮整理计划不运行业务测试。JDK21、离线Maven和具体模块选择遵守仓库Java规范。用例名下表为拟新增，不能当作已有或已经执行。

| 场景 | 拟新增用例/验收记录 | 实施位置/依赖 |
|---|---|---|
| 模板版本与发行限额 | EntitlementCatalogFlowTest、EntitlementGrantFlowTest | pay-domain纯规则+shop-app真实Mapper场景；目标数据库并发 |
| 幂等/回滚/预占竞争 | EntitlementUseFlowTest、EntitlementRecoveryFlowTest | pay事务及源决策替身；补真实HTTP超时/重复投递场景 |
| 活动预算 | ActivityParticipationFlowTest | core真实Mapper；预算/个人行条件更新 |
| 旧券兼容与迁移 | EntitlementMigrationFlowTest，扩展现有CouponModelCompatTest/CouponIssueFlowTest | 旧mkt/pmt样本、owner及历史券码 |
| 储值到账和金额使用 | StoredValueRechargeFlowTest、StoredValueConsumptionFlowTest | pay账户/批次/分录同事务；实收取消与同源并发 |
| 付费卡 | PaidCardSaleFlowTest、PaidCardAllowanceFlowTest | 订单支付事件、多件发卡、时区/月末/跨日释放 |
| C端隐私/排序/权限 | StoredValueConsumerQueryTest | 本人身份、DTO白名单、乱序事件与稳定分页 |
| 双部署与通知 | EntitlementInternalApiRoundTripTest、EntitlementNotificationFlowTest | pay-svc内部HTTP与shop-app门面、消息去重 |

现有ArchitectureTest、PayTxManagerConventionTest、PayStandaloneAssemblyTest、PayDeploymentModePairingTest按受影响范围执行；修复基线编译脚本错误后用真实退出码记录。SQL portability、entity alignment、接口/权限/i18n/生成物守卫按变更运行；H2不替代目标数据库DDL/CHECK/锁竞争。前端组件使用端侧既有测试方式，补交互与权限行为验证，不写只镜像实现的用例。

每个DONE任务附：提交号、修改文件、U/AP/EP/API映射、验证命令与结果、目标数据库/部署形态、已知问题。失败可按已确认基线分别登记，但不得为了通过修改失败棘轮或跳过校验。

### 2.6 交付批次、估算与上线条件

| 里程碑 | 包含任务 | 可交付能力 | 开放条件 |
|---|---|---|---|
| M0 实施基线 | D01全部 | 契约/迁移路径/可信源决策落点确定 | 未查清真实存量不执行迁移切换 |
| M1 权益底座 | D02全部、D03.1/2 | pay券模板/发行/使用、旧URL兼容及迁移工具 | 双部署、幂等、账户边界验证；旧券按批演练后切换 |
| M2 美业储值试点 | D04、D05、对应D07.1 | 商家收款、活动充赠、核销、C消费记录、通知和运营异常 | 每项功能对应真实接口，账实一致、未知状态可恢复、成员授权接通 |
| M3 次卡/月卡 | D06、剩余D07 | 餐卡/游泳等6类产品、售价绑定、发卡、每日额度 | 产品条款/原型、支付来源与履约能力齐备；无自动续费/无限次 |

初步排期只作容量参考：按2名后端+1名前端+1名QA、相关产品/运营可及时参与估计，M0约3–5工作日，M1约10–15工作日，M2约10–15工作日，M3约10–15工作日；总周期约7–10周并预留约20%集成/迁移缓冲。不是工期承诺，D01完成后按实际存量和基础设施重新估算。若只有单人实施按同样依赖串行推进，不套用此日历周期。

首批可领取顺序：D01.1→D01.2/1.3/1.4→D02.1→D02.2；D04.1可在契约确定后并行。能力开关名称在D01.2登记，未实现不新造可点击入口。40项预置可先完整入目录，按底层能力逐项开放，不因目录齐全承诺40玩法都可运行。生产迁移与新能力发布分别控制。

灰度先选一个明确商家/门店和受控参与人群，按活动/充值/发放/使用原号核对；发现超扣、重复发放、跨主体访问或无法解释的账差立即停止新增受理并保留查询与恢复。具体告警阈值、观察时长与值班人于D07发布单确定，不能缺省放行。

回退采用扩展优先：未切owner可退应用保留新表；已切但目标无新写、已证明一致时方可切回；目标已有发放/核销则停止新增受理、继续查询和恢复，前滚修复或执行审计迁移，禁止直接翻回旧账。关闭活动不影响已到账权益，通知失败不回滚账务。


### 2.7 Java后端优先执行台账（2026-10-18）

第二阶段（2026-10-19）按[第二阶段任务清单](./任务清单-第二阶段权益卡券储值.md)执行：复用第一阶段，覆盖剩余37项；031/041按所需子项推进，B1发行先行，B2领域与B3联动先于对应HTTP，B4储值售卡，B5兼容与部署，B6最终验收。仅规划不改API/DDL或任务状态。

用户要求先完成全部Java后端，并将任务整理为独立表。执行入口为[任务表-活动卡券储值-Java后端](./任务表-活动卡券储值-Java后端.md)：55个交付单元，当前14 DONE/0 DOING/41 TODO；覆盖基础38张及支撑6张目标逻辑表、47条外部及12项内部写操作，并登记恢复query与类清单。计数是任务拆分结果，不是工作量完成率。

保留D01–D07作为设计阶段归属；JB-001–055为叶子任务唯一执行状态。§6只记录实际提交和验证证据。原UI同步开发建议由本次Java优先要求替代；经营规则、HTTP契约、权限和真实后端测试仍必须同步。JB-008至JB-013已按任务表分别交付；JB-014事件Outbox/inbox适配为下一执行项，既有目录底座直接复用。


### 2.5 JB-008运行门禁与错误契约收口批次（2026-10-18）

基于API第十二节及独立任务表008，在既有隔离工作区执行；不修改旧活动/券通道。新增pay配置PayEntitlementProperties与PayEntitlementFeatureGate，绑定catalog/issue/use/stored-value/paid-card五个默认false开关；新增core PromotionFeatureProperties/Gate绑定presets/activity。ActivityCapability固定预置实际35码，core门禁仅接受可信owner给出的就绪集合，未知要求仍报缺失，不因开关打开自动READY。运行消费者先接已完成的目录写入及新引用；其余消费者随016/020/025/029/032等任务接线。

| AC/实现步骤 | 预期验证 |
|---|---|
| 缺省关闭、7开关真实绑定且互不串开 | EntitlementFeatureGateTest默认拒绝/全开/单开隔离 |
| 付费卡须catalog+issue+use+paid-card全就绪开关 | paid-card单开拒绝及组合开启允许 |
| 仅打开推荐开关不能抹掉缺失或未知能力 | 预置能力缺失/未知/重复排序及不可变快照 |
| 目录新写入/新引用受门禁，读历史和暂停/结束不阻断 | EntitlementCatalogGateFlowTest，实际Mapper行数与版本验证 |
| 原幂等成功请求在关闭后仍回放，不产生新写 | 关闭后原create/publish请求重放；新键拒绝且无PENDING残留 |
| 关闭功能与没有角色权限有不同用户反馈 | 专用ErrorCode及中文/英语/阿语；BackendI18nParityTest |

测试先缺类型RED→实现GREEN，另移除目录create门禁观察用例RED后恢复。目录既有测试明确开启catalog能力，原生MySQL夹具亦显式注册门禁；不能给全局test profile开总开关掩盖缺省关闭行为。008只交付契约/门禁基础，不把后续HTTP、远程、会员码、发放及核销owner实现计作已完成。已有成功请求的恢复不能在幂等缓存读取之前被新开关误拦，门禁置于幂等工作回调内。

### JB-010统一会员凭证实施补充（2026-10-18）

档位2。当前无可信会员码签发者，新增C11 POST /mp/member-code，C会话取本人；C08商家解析统一会员码。沿用biz:customer权限与门店子集，并核实目标门店功能权限，不创建储值专用码。核心会员域新增mbr_identity_ticket（V426计划），目的MEMBER_CODE与CUSTOMER_REFERENCE隔离，256位随机原文只返回当次调用，数据库仅SHA-256摘要。码60秒、引用120秒、UTC半开有效期。引用绑定租户、主体、门店、商家操作人及顾客；识别不是扣款授权，仍须JB-011持久本人确认。

新增UserScopeFactsPort查用户有效状态；CustomerIdentityPort向其他领域提供受范围限制的引用查询。新签发/解析受shop.member-code.enabled控制且默认关闭，既有引用只读查询不受新发行开关影响，但每次重验有效顾客/商家/门店。正式内部HTTP代理仍由JB-041接线。目标外部接口增至48，内部传输目标增至34，设计表增至44（core16/pay28，基础38+支撑6）；实际迁移交付分别登记。无资金表变更，不部署、不接生产库。

验证：真实H2持久签发/解析与SPI查询，伪造/过期/跨租户主体门店操作人/失效顾客拒绝，默认关闭及关闭后引用读取，HTTP会话与权限；关键操作人隔离消融；新增迁移另在隔离原生MySQL验证，生成schema/ERD/OpenAPI按仓库守卫对账。

## §3 完整调用流程

### 3.1 活动配置与发券

1. B/O界面经shop-app调用core创建活动草稿；若利益为券，查询pay已发布权益模板，选定templateVersionNo。
2. core发布不可变活动版本，保存pay模板引用及必要展示摘要。发布时通过内部端口验证引用属于该主体、出资方/币种兼容，不能只相信客户端传的名称。
3. C主动领取或B选人发放，core校验活动/受众/限购并预留营销预算，返回绑定sourceBizNo的资格凭证。
4. 应用层调pay grant.prepare：校验模板版本、发放途径、数量及发行授权额度，持久保留发行容量；commit以同一grantNo发放实例/流水/事件。pay自身发行额度与core营销预算分别有独立上限，不互相重复扣同一个预算。
5. pay发放成功事实回core，将营销占用转已用，创建统计投影；消息域通知。重复请求/事件不多发。
6. 无活动的独立券领取/定向发放，pay依据模板自身发行规则、经签名受众选择事实执行，不强造空活动。core提供会员受众查询，不拥有权益发行账。

### 3.2 储值活动与充值

1. core储值活动选择档位、参与门店、使用范围、预算及每人次数；充值源号由应用层预生成。
2. core.reserve保存参与及预算占用、不可变本金/赠金及范围快照。
3. pay创建充值单并绑定reservationNo；商家实收确认在pay串行推进，确认金额与快照本金一致。
4. pay按来源唯一过账，写账户、批次、凭证、分录、门店投影、Credited事件，原子提交。
5. core消费成功事实确认预算，消息发送总额到账通知。消费核销以后只看pay历史快照，不再实时问core活动是否仍有效。
6. 未实收取消必须先在pay提交取消决定，再凭事件释放core占用。超时或服务不可达只查不释放；收到实收的充值禁止取消。线下已收钱但源单已关闭是人工异常，不伪造自动退款。

### 3.3 付费次卡/月卡购买与使用

1. pay发布卡权益版本：可用服务、总次/日额度、有效期/起算、预约范围；core CARD商品引用该版本并设置商家售价。
2. 消费者购买沿现有商品订单与真实现金支付；core源事务保存订单权益行快照及Outbox，携带orderLineNo、unitIndex、件内entitlementIndex和paidFactNo。
3. pay按订单行+单件序号+件内权益序号唯一发卡（unitsPerItem=1时，同一行买2张发2个实例），记录原支付引用，不伪造重复收款。未付款、无可信支付事实不发卡。
4. 激活起算方式首期选择PAY_SUCCESS或FIRST_USE：前者以权威支付成功时间起算，即使发卡延迟也不重置；后者首次确认使用时原子固定窗口。自然月按商家市场时区确定当月起止，滚动30天按本地日历规则明示；不把自然月写成固定30天。
5. 会员码识别本人后，core订单/预约核验服务和资源，生成本人授权意图；pay preview确认可用权益、prepare占用次数及日额度，commit扣次并写统一使用流水。查结果可恢复，不因网络超时再扣一次。
6. 商品券原有券码仍可定位该券，统一会员码不是全部权益的万能扣款凭证。新增付费卡不新增储值专用码，仍经本人授权和明确选卡。

### 3.4 订单用券及核销

订单券报价由core提供可信行项与门店/币种/优惠后计价基数，pay返回具体实例的试算金额及quoteRef。core先活动后券，试算不扣权益；下单重新核验并hold。支付成功确认使用，取消且未成功则release。合法售后按原订单分摊做内部reverse，累计不超原使用；不因本次储值无线上退款而删除既有订单券售后行为。

STORE_CODE兑换券只能到店核销，不允许同时进入订单抵扣；付费次卡走明确服务订单来源，不能把兑换当成赠品订单折扣额。兑换次数与储值金额使用各自分配行，使用记录统一查询时注明assetKind，不能直接相加为收入。

### 3.5 跨服务一致性与授权

每个源模块自己的状态/Outbox同源事务；目标模块Inbox/账/出站同目标事务。core营销事务与payTxManager不共享，禁止跨库事务或在长事务里HTTP。reserve/prepare/commit/release/query所有命令有稳定业务号、幂等键和payloadHash；重投同参返回原结果，异参409。

core营销预留RESERVED→COMMITTED/RELEASED；pay发放PREPARED→COMMITTED/ABORTED。只有pay持久确认未发放且已禁止后续提交的ABORTED事实，才可释放core预算，不能由TTL直接释放。支付已实收/订单已支付的在途义务须继续发放或进入人工待办，不能通过活动结束撤销。反向confirm使用eventNo+权威业务号去重。

使用预占HELD→COMMITTED/RELEASED互斥。core先持久化订单/本人授权决策，冻结版本；pay按可信decisionNo提交。超时任务查询core决策：明确取消才释放，成功决策只确认，未知继续挂账。伪造普通HTTP authorizationRef不获得授权；内部调用服务鉴权、主体及消费人一致性都必须校验。

### 3.6 通知节点与事件协议

| 时机（只以事务提交后的权威事实触发） | 事件所有者 | 收件人/通知类型 | 展示与动作 |
|---|---|---|---|
| 充值提交待实收 | pay.storedvalue / RechargeSubmitted | 商家有权确认人，业务待办 | 金额、门店、充值单；跳P27，禁止在待办中宣称到账 |
| 充值到账 | pay.storedvalue / RechargeCredited | C服务通知；商家结果通知 | 到账总额、门店、时间、记录入口，不拆本金赠金 |
| 券/卡发放成功 | pay.entitlement / EntitlementGranted | C权益到账通知 | 券/卡名称、次数/期限、使用说明；活动仅提供来源展示 |
| 核销成功 | pay / EntitlementUsed、StoredValueConsumed | C消费通知；商家操作回执 | 项目、门店、金额或次数、时间、详情入口；付费卡展示剩余次数 |
| 首次使用激活 | pay.entitlement / EntitlementActivated | C使用成功消息附激活信息 | 起止日期、每日额度；与同次核销合并一条，避免重复打扰 |
| 即将到期 | pay.entitlement / EntitlementExpiring | C提醒（默认提前3个服务日一次） | 仅有剩余且尚有效卡券；储值本金不套券有效期提醒 |
| 发放/入账/核销长期未知或对账差异 | pay / ExceptionOpened | 商家或运营异常待办 | 原业务号、状态、允许动作；不得给C发失败通知诱导再次付款 |
| 未实收取消/驳回 | pay.storedvalue / RechargeCancelled | 商家经办人结果通知 | 原因；未承诺到账不发“退款成功”通知 |
| 活动暂停/结束/预算不足 | core.promotion / ActivityStateChanged | 活动负责人管理通知 | 影响后续参与，不撤销已到账权益 |

消息发送由现有消息模块执行，core/pay只发布业务事件。最低事件信封：eventNo、eventType、schemaVersion、tenantNo、entityNo、aggregateNo、aggregateRevision、sourceRef、occurredAt、traceId、payload；消费者按tenant+eventNo幂等。收件人由主体关系与岗位计算，不接受外部任意手机号。消息载荷不带明码、授权引用或内部资金拆分。

站内消息作为首期可控通道，小程序订阅消息按用户授权及已配置模板发送；短信仅按商家已开通配置及授权发送，不默认开通营销短信。消息失败仅重试发送，不回滚到账/核销；失败可在既有通知日志查询。消息去重键为业务事件+接收人+模板+渠道，业务重试沿用原事件身份；提醒按instance+截止版本+提醒批次去重。通道模板未接入前明确标记能力缺失，不宣称实际送达。

## §4 月卡/次卡规则与账务

COUNT以整数totalUnits计数，可有期限；PERIOD_COUNT同时限制有效期与总次数；PERIOD_DAILY按serviceDay+periodKey独立记每日quota，未使用额度不结转。30次餐卡可以一次扣多次，但请求units必须>0、在服务订单数量与限额内。不限总次数月卡本期不开放。

每日预占计入reserved，commit转used；释放在原服务日计数器回补，不按释放时的日期操作。hold过期且跨日须核对原源决策；不能把昨天的未结单在今天免费再占。预约日期可不同于下单日期，serviceDay来自可信预约/核销意图并由模板决定允许范围；提前预占、改约需新版本重新占用。

锁顺序：先持久业务头/幂等操作，再账户或权益实例，再period bucket，再稳定排序批次/分配；grant预算计数在固定顺序内，不与核销反向获取core活动锁。账户/实例余额与分录/流水同事务提交。pay使用同一dataSource可对券/金额组合做短事务，但首期储值不叠积分或券，不能擅自开放组合支付。

首期不提供储值线上退款，付费卡线上退款也不在当前UI范围；新售卡的退卡/停业处理/到期剩余规则是开放售卖前必须确认的经营条款。既有券业务已承诺的订单取消/售后继续支持受限内部补偿。冻结/到期不允许新用，历史查询保留；金额账户本金不因券/卡到期清零。

## §5 迁移与兼容

原pmt_coupon*及旧mkt_coupon*的模板、实例、余次、核销码、发放来源和历史核销事实都需盘点。不得只建立新pay表然后仍允许core继续改旧余次。

1. 清点来源表及URL/SPI/统计消费者，记录样本总量、剩余权利与原始关联；是否存在真实存量在实施前确认。
2. pay建新目标表及映射，保留全部业务号；发生同号冲突时内部使用(sourceSystem,sourceNo)区分，兼容层记录精确映射。迁移凭证标明历史来源，不编造付款或发卡日期。
3. 在明确截止水位冻结本批实例写入、排空源在途操作，快照迁移；增量应用及校验按sourceSeq幂等。导入过程不发新权益或新通知。
4. 校验模板、状态、余额/次数、冻结、日额度、核销流水、券码查询全部一致；切换精确owner registry后pay成为该批唯一写者。不同批可逐步迁移，但同实例不双写/双扣。
5. 原HTTP入口继续通过鉴权门面按明确迁移归属访问；不是按“查不到新表就回旧表”猜归属。旧core账务表只读归档，原pmt_apply券记录不再被订单优惠汇总重复累计。
6. 回退只能在目标无新写且可证明一致时切回；pay已经产生新承诺/核销后不能直接翻开关回旧表，要以前滚修复或可审计迁移处理。

未应用的sv_campaign草案取消；已应用Flyway不修改。权益模板管理API运行入口仍可保留，内部owner改为pay；不要把重命名包当作完成资金迁移。

### JB-010身份验收映射

| AC | 真实测试方法 | 实现落点 |
|---|---|---|
| C本人统一码，无储值专用码 | MemberCodeHttpFlowTest.consumerIssuesCodeAndMerchantResolvesWithoutLeakingAccountIdentifiers | C11/C08、core.member |
| 伪造/过期/用途混用拒绝 | MemberCodeResolveFlowTest.rejectsForgedCodesUserNumbersReferencesAndExpiredCodes / expiredReferencesFailEvenWithValidScope | ticket hash/purpose/UTC半开有效期 |
| 引用绑定租户/主体/门店/B操作人/顾客 | MemberCodeResolveFlowTest.referenceBindingIncludesTenantEntityStoreAndActor / rechecksLiveUserForIssueScanAndReferenceReads | requireReference + 用户有效事实SPI |
| 店员仅可在授权门店识别，权限实时生效 | MemberCodeHttpFlowTest.staffNeedsLiveCustomerPermissionAndCannotScanForAnotherStore / customerPermissionAtOneStoreCannotBeBorrowedForAnotherAssignedStore | BizContext门店子集 + biz:customer |
| 原文不持久化、不记日志 | MemberCodeResolveFlowTest.issuesUnifiedCodeAndResolvesDurableReferenceWithOnlyMaskedIdentity / MemberCodeHttpFlowTest.successAndValidationErrorsDoNotWriteRawCredentialsToLogs | hash-only表、脱敏DTO、既有安全日志 |
| 缺省关闭，停止新签发不破坏在途引用查询 | MemberCodeResolveFlowTest.disabledFeatureBlocksNewCredentialsButAllowsExistingReferenceQuery | MemberCodeProperties |

以上仅证明身份识别，扣款本人授权验收仍归JB-011，未提前声明已完成。

## §6 验收与交付边界

功能验收见矩阵U01–U30及API端点映射；每个矩阵项在实施时对应真实测试方法。必须验证：模板改版不变历史、core预算与pay发行额度并发不超发、同订单行多件幂等发卡、卡余次与每日额度条件非负、过期码及越权拒绝、源取消与confirm竞争、迁移后无双写、事件乱序不倒退门店排序、C DTO和通知无本金赠金内部拆分。

目标数据库须执行DDL/JSON/CHECK、索引与事务并发测试，不能仅凭H2。后端编译闸门脚本现有变量报错须先修复并以真实退出码验收。按本地/远程pay、既有会员授权、订单事件和凭据存储分别验收。

设计阶段先交付文档、设计DDL和契约清单；代码实施进度见下方记录，生产上线与存量切换独立执行。预置模板可见不等于能力可用。涉及付费卡的购买/激活/余次/每日额度原型，以及统一活动模板四类目录，列为后续原型补齐工作。

### 设计阶段静态校验记录（2026-10-18，早于下方实施记录）

已检查文档包相对链接、U01–U30/AP001–AP040/EP001–EP006编号、47条外部接口与12条内部操作编号及表格列数、核心12张/支付26张逻辑表的主键/租户/索引字段与字典对应、预置JSON的模块归属，以及模块图SVG XML。`node scripts/check-v2-docs.mjs`通过；`git diff --check`通过。这些均为静态校验，未执行目标MySQL DDL、Java编译、并发事务或真实通道通知测试。

### 第一批实施记录（2026-10-18）

| 任务 | 状态 | 实际落点/证据 | 尚未完成 |
|---|---|---|---|
| D01.1 | 源码盘点DONE | design/活动权益-D01-现状盘点.md；包括mkt/pmt、活动/邀请奖励/交易/售后与旧URL | 生产存量核查留到迁移前，不宣称已查 |
| D01.2 | DOING | shop-base/spi/entitlement/SourceRef；4条测试RED→GREEN | 其他SPI/DTO、权限/i18n与OpenAPI尚未接通 |
| D01.3 | DOING | 确认OrderServiceImpl用事务Outbox发布OrderPaid；JB-011授权/源决策、JB-012售卡快照完成 | 真实支付事实由主订单paidAt/payTradeNo读取，售卡发放编排仍待JB-040；OrderPaid接线需要后续链路 |
| D01.4 | DOING | JDK21离线pay-domain及依赖test-compile退出0；编译闸门变量展开回归1条RED→GREEN | 完整后端闸门与双部署验收未完成 |
| D02基础规则预研 | 纯规则已实现 | UsageQuota.Count/Daily；5条测试RED→GREEN | 未接数据库状态机/接口，不代表D02完成 |

SourceRef提供grantKey/useIntentKey/settledKey，按长度前缀SHA256编码，数据库唯一键仍需含tenant。unitIndex和entitlementIndex从1开始，必须带父级身份；修订号只进入预占意图键。UsageQuota是不可变纯计算模型，不替代持久化条件更新和状态机。

目标38张设计表已在隔离MySQL 9.7.2新库执行成功，不代表存量增量升级完成。正式迁移前仍需对齐BaseEntity的version/deleted与目标revision、审计时区及主体数据域登记。

数据库约束自动回归：`node --test scripts/tests/entitlement-schema.test.mjs scripts/tests/head-compiles-ref.test.mjs`通过2项检查；验证日额度CHECK、租户复合唯一、条件预占返回1后0、非法JSON拒绝，以及闸门变量展开。容器每次独立创建并清理，不连接已配置业务数据库。

### 对齐补充

已用失败检查复现模板无草稿持久字段与公共version列缺失，再补齐ent_template.draft_snapshot及全部目标表version/deleted。MySQL目标表检查恢复通过。数据库设计中的version/deleted对齐已完成；正式增量迁移、领域审计UTC/actor填充和实体Mapper集成仍待D02.1实现。SourceRef消融验证：临时忽略useIntent修订号后用例失败，恢复实现后4项通过；Count/Daily另5项通过。

### 编译与全量回归结果

验证代码提交97dc22b22：`bash scripts/check-head-compiles.sh HEAD`从干净副本test-compile成功；全量20模块3513用例，9项失败，退出1。失败为M6bCouponFlowTest.funderIsRecordedSeparately及ProxyOrderFlowTest的8项，均返回FULFILLMENT_NOT_SUPPORTED(70013)，不能宣称全量通过。

在未修改基线00d04e580隔离工作区复跑`mvn -o -q -f backend/pom.xml -pl shop-app -am -Dtest=M6bCouponFlowTest,ProxyOrderFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`：27+10=37项全部通过。说明单独执行可通过，组合执行状态污染仍待定位；未把这9项加入known-failures，未修改其他业务功能。D01.4继续DOING。后续3e58086fe仅调整设计DDL及对应独立数据库检查，Node两项回归已通过。

实际交付为源码盘点、SourceRef协议身份、UsageQuota纯规则、闸门修复及设计对齐；运行API、正式增量迁移、余额/次数持久账与三端功能均尚未完成。下一实施点仍是D01.2/1.3/1.4和D02.1，不跳过可信授权、存量核对或幂等持久化。

### 第二批实施记录（2026-10-18）

| 任务 | 实际交付 | 未完成边界 |
|---|---|---|
| D01.4 全量失败定位 | 按字母序组合AddressSalutationTest/M6bCouponFlowTest/ProxyOrderFlowTest，41项复现9红；AddressSalutationTest恢复G0001原履约配置后41项全绿 | 全量干净副本重验另记结果，不能只凭组合绿宣称全量绿 |
| D01.2 目录端口 | shop-base EntitlementDefinition/EntitlementCatalogPort；pay本地PortImpl验证发布状态、主体、币种及ACTIVITY_GRANT/PAID_PRODUCT用途，停发仍可读历史 | 新对外权限/HTTP DTO、活动参与/发放/使用端口和独立pay远程接线仍待实现 |
| D02.1 目录表子集 | V425__entitlement_catalog.sql，三张表ent_template/ent_template_version/pay_ent_command_dedup；对应实体/Mapper归pay工厂，H2 schema由生成器更新 | 不包含发行控制、实例、核销账、Outbox/Inbox或存量迁移；未连接生产 |
| D02.2 目录内核 | create/saveDraft/publish/changeStatus/get/getVersion/list；payTxManager原子提交业务与幂等响应；UTC/可信actor；不可变发布快照；终止状态和平台出资守卫 | P01–P05 HTTP入口及P06发行控制未开放。范围refs只做结构校验，对外接线前还须由core核实真实项目/门店归属与权限 |

EntitlementCatalogFlowTest本批12项通过，覆盖持久回读、稳定幂等/异参冲突、发布与历史快照、跨主体/租户、暂停/结束、pay事务回滚、UTC审计、SQL分页、三类卡模型、并发同键、平台出资写权限、本地SPI引用、过期绝对期限不发布与键大小写/空白。已观察缺实现失败、平台状态守卫失败、空白键失败后修复；并发测试验证同键4线程只一份。原有架构/Mapper/Schema/支付部署装配守卫按受影响范围通过。

隔离mysql:9.7.2验证V425可执行/重复执行，三张正式目录表与目标模型列集合、类型及空值约束一致；仍验证38张逻辑设计表及JSON/CHECK/条件更新。新增原生MySQL检查复现默认collation把KeyA/keya合并，修复request_key为utf8mb4_bin；应用拒绝首尾空白，保持键不透明且大小写敏感。该检查不等于Java全服务已在生产数据库完成集成验收。

生成物同步：schema-test.sql、ER图/词汇；ER补ent/pay前缀及表级模板引用，避免与商品/消息template_no误连。实体字段与血缘检查20项通过。原有gen-model-align被MemberSetting错误字段映射阻塞，未修改生成器或伪造模型报告；在未修改基线00d04e580复现同样错误。spec-completeness有3项字段说明失败，基线与本分支相同（C/B/运营字段JSDoc），未加入豁免或改失败清单；本批未改变TS运行契约。

正式迁移版本号V425在本隔离分支分配，集成时须再次检查其他分支版本号占用。绝对期限在发布和新引用时验证未过期；历史读取继续保留，不自动撤销已取得权益。


### 原生事务补充验证（2026-10-18）

提交9ab17b5a1的干净副本已完成test-compile及20模块全量回归：3525项、0失败，耗时586秒。该结果对应修复下述原生死锁前的提交，不把它记为后续提交的全量证据。

新增可选原生服务检查：`JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH node scripts/verify-entitlement-mysql.mjs`。脚本仅连接自己创建的临时MySQL 9.7.2，端口绑定127.0.0.1，库名固定为entitlement_native_fixture，退出清理容器；不会读取业务数据库配置。未设置专用环境变量时JUnit默认跳过，脚本要求实际运行1项、失败0且跳过0，避免把未运行误记为通过。

原生检查先复现4线程同键的InnoDB死锁：普通重复INSERT取得共享锁，随后FOR UPDATE升级相互等待；[MySQL官方锁说明](https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html)描述了该模式。改为`INSERT ... ON DUPLICATE KEY UPDATE id=id`先取得排他锁，再读取稳定响应；不改原命令参数、审计及响应。新行PENDING与业务及COMPLETED同事务，异常整体回滚，不允许独立提交PENDING。原生Java服务检查修复后通过，覆盖真实JSON回读、4线程仅创建一份、历史幂等响应不变及外层pay事务回滚后可重试；H2目录12项和SourceRef/UsageQuota共9项通过，SQL可移植性扫描无新增方言欠账。

本阶段仍为目录内核，不包含权益发行/核销、储值资金账或公开HTTP接口；D01.4的独立pay远程业务接线与生产环境版本核实也未完成。


### 本批验收映射与下一执行点

下表仅覆盖已实现的目录内核；U01–U30整体功能仍按原阶段逐项验收，不能用目录测试替代发行或核销验收。方法均位于EntitlementCatalogFlowTest，原生项另列。

| 已实现条件 | 测试方法 |
|---|---|
| 草稿持久化、重放原始响应、异参冲突 | persistsDraftAndReplaysOriginalResultEvenAfterStateChanges |
| 发布版本不可改、旧修订拒绝 | publishesImmutableVersionsAndRejectsStaleWrites |
| 跨租户/主体隔离 | cannotReadOrMutateAnotherOwnerOrTenant |
| 暂停可查历史、结束不可恢复 | pausingDoesNotDestroyPublishedRightsAndEndedIsTerminal |
| 业务和幂等同pay事务回滚 | payTransactionRollsBackBusinessAndIdempotencyTogether |
| 可信操作者、UTC与数据库分页 | auditUsesTrustedActorAndUtcAndListIsDatabasePaginated |
| 三类卡模型、付费卡不能免费领取 | acceptsThreeCardModelsButCannotClaimPaidCardsForFree |
| 同键四线程只产生一份 | concurrentIdenticalRequestsProduceOnePersistedTemplate |
| 商户不能操作平台出资模板 | merchantCannotEndAPlatformFundedTemplate |
| 本地SPI的用途/币种/主体校验与历史读取 | coreReferencesValidateKindCurrencyAndIssuanceWithoutLosingHistory |
| 不透明键区分大小写、拒绝首尾空白 | opaqueIdempotencyKeysKeepCaseAndRejectPadding |
| 过期草稿可编辑、禁止新发布 | expiredAbsoluteDraftCanBeEditedButCannotBePublished |
| 原生MySQL并发幂等、JSON快照、外层事务回滚 | EntitlementCatalogNativeMysqlTest.realMysqlPreservesSnapshotsConcurrentIdempotencyAndRollback |

本批代码对账：9ab17b5a1为37文件、1476增/106删，落在共享定义/SPI、pay目录实体/Mapper/服务、三表正式迁移、生成物及对应检查；815c12c2b为5文件、194增/11删，落在同目录幂等锁修复、原生Java测试、隔离MySQL运行器与本记录。没有新增三端可点击入口、Controller或旧券数据写入。

后续仍按D01.2/D01.3→D02推进：先明确core门店/项目范围核实与可信本人授权/源决策契约，再接公开模板门面及独立pay远程适配；发行控制P06需补ent_issue_policy与recipient的正式迁移、原子额度条件更新及真实并发验证，才能进入prepare/commit/abort发放。活动发布和储值到账不得绕过该依赖直接写实例或余额。每个步骤沿用当前分支与既有任务编号，不把尚未实现的设计表登记为已部署表。


### 最新提交验证结论

验证代码提交815c12c2b：`bash scripts/check-head-compiles.sh HEAD`从干净副本编译成功；20模块共3526项、失败0、错误0，耗时586秒。含1项未设专用数据库变量的原生检查条件跳过；同项已经由隔离MySQL脚本独立实际运行两次，均为1项通过、0跳过。日志摘要：

```text
✓ HEAD 从干净副本可以编译
全量 3526 跑（20 个模块）/ 0 红（基线 0 条）
✓ 没有新增失败（基线 0 条一条没多）
Native MySQL catalog service: 1 test passed, 0 skipped
```

另有数据库/生成图/闸门回归3项通过、V2文档守卫66份通过、SQL方言扫描无新增欠账、git diff --check通过。此后仅更新本次验收对照与API/盘点文档，未改Java或迁移。全部为本地验证和提交，未推送、部署或执行生产迁移；整体D01–D07未完成。


### JB-019有效期计算细化

自然月从真实付款/首次使用起算，到该IANA时区下月1日00:00，不将生效时间回溯到购买前月初；滚动N天保持起算本地时刻加N个本地日，夏令时日可能不是24小时。绝对期限原样保留[startsAt,endsAt)。FIRST_USE未提供可信首用时间时仅返回待激活预览，不改实例；固化激活仍由JB-042的使用事务承担。COUNT/PERIOD_COUNT的新预占验证窗口，旧预占提交/释放不因现在已到期而直接失效，仍须核实原来源决策和分配。JB-019只依赖已存在的Validity与UsageQuota，移除对JB-008未完成协议的过度依赖。


### Java优先批次：JB-008协议子项

EntitlementContractTest先观察缺类编译失败，再6项通过，扩展查询/储值边界后9项通过。已加入可信内部上下文、决策类型、来源事实/授权查询，以及活动参与、发放、使用、权益查询、储值端口的record命令和响应；列表防御性复制，查询分页1–100，未知状态不得带成功事实。PAID_ORDER要求单件身份及付款事实引用；金额账户与实例次数互斥，I11拒绝储值退款。源码仍无新HTTP Handler、端口业务实现或远程适配。JB-008仅协议子项落地，剩余完整query/报价/预览、权限/能力码和补充持久模型未完成，不标DONE。


### JB-013公共幂等验收

EntitlementCommandDeduplicator.execute从目录内提取为唯一组件，保留原tenant+operation+大小写敏感requestKey、主体/操作者/命令指纹及稳定响应；明确MANDATORY payTxManager，不另开事务。目录四条写路径委派该组件，原生测试显式装配新组件。新增3项测试验证无事务拒绝、不同operation命名空间独立、业务和嵌套幂等一起回滚后可重试；原12项目录回归通过。真实MySQL的4线程同键、JSON回读、原响应重放及外层事务回滚检查1项通过、0跳过。移除MANDATORY后目标测试出现“Expecting code to raise a throwable”失败，恢复后整组通过。JB-013改DONE；未新增公开接口或资金流水。


### JB-019规则验收

EntitlementValidityPolicy共享发布校验与时间计算；CountUsagePolicy/PeriodCountUsagePolicy复用UsageQuota计数。5项纯规则测试通过，覆盖闰年2月本地月末、夏令时23小时日、首次使用待激活与可信首用时间、绝对期限和缺少源时间、新预占到期拒绝而旧预占仍可结算/释放。消融将until改为包含结束时刻后，paymentBasedCalendarMonthEndsAtLocalNextMonthBoundary失败；恢复后5项及原目录/协议/幂等/架构整组通过。JB-019改DONE，只是纯规则交付；没有宣称实例激活、数据库扣次或P16/P17已接通。


### 本批提交与定向证据

| 提交 | 交付 | 当前证据 |
|---|---|---|
| 50f1c32c4 | JB-008共享协议子项，15文件815增/5删 | 首批9项协议回归通过；整个JB-008未完成 |
| 855afc35e | JB-013公共幂等，7文件204增/46删 | 新增3项、原目录12项、原生MySQL1项通过；MANDATORY消融红后恢复绿 |
| 685a15805 | JB-019时间和计数策略，8文件220增/21删 | 5项纯规则、结束时刻消融红后恢复绿；无状态激活/扣次落库 |

后续协议复核发现并修复C端页上限51仍接受、CursorPage缺hasMore，新增失败边界后修正为1–50及一致性约束。恢复后的定向命令包含EntitlementContractTest、EntitlementCommandDeduplicatorTest、EntitlementValidityPolicyTest、EntitlementCatalogFlowTest、SourceRefTest、UsageQuotaTest、ArchitectureTest、PayTxManagerConventionTest：共56项、0失败/错误/跳过。隔离原生MySQL服务检查另1项实际通过、0跳过。新枚举词汇表已重新生成；文档66份、SQL方言无新增欠账、git diff --check通过。

全量干净副本正在单独验证685a15805，结果以随后记录为准；此定向证据不冒充全量。当前9 DONE/1 DOING/45 TODO；发放、核销、储值账务和三端HTTP尚未完成。


### 本批最终验证边界

`bash scripts/check-head-compiles.sh HEAD`对685a15805的干净副本完成test-compile与全量测试：20模块3543项、0失败/错误，耗时618秒；原生MySQL条件测试1项默认跳过，该项由独立隔离脚本实际通过。此后244ee7f4c修正C端50条上限及CursorPage.hasMore，并更新生成词典；最新功能代码定向56项通过、原生1项实际通过。244ee7f4c另以`SKIP_TEST_GATE=1 bash scripts/check-head-compiles.sh HEAD`验证干净副本test-compile，明确仅编译，不宣称它重跑全量。两组证据分别记录，不将3543项冒认为后续提交的全量结果。

后续Java差异仅将4处长行换行至120列内；逐文件去除空白比较与244ee7f4c相同，没有逻辑变化。文档守卫66份及diff检查通过，工作区验证产生的原生容器/干净编译副本已清理。本轮本地提交，未推送、部署或执行生产迁移。全Java后端仍未完成：JB-008其余契约/权限/持久模型继续DOING，发放、核销、储值账及三端接口等45项仍TODO。


### JB-009范围事实预检与JB-008补充协议

档位1，继续依据本TDD §2及Java独立任务表。未改既有迁移或生产环境。新增实现按owner落位：shop-base ProductScopeFactsPort/MerchantScopeFactsPort；shop-core product.port.ProductScopeFactsPortImpl及promotion.service.EntitlementScopeValidationService/Impl；shop-merchant merchant.port.MerchantScopeFactsPortImpl；shop-app paybridge.EntitlementScopeResolver。测试均在shop-app/paybridge；没有新增HTTP Controller。

| 验收目标 | 真实测试方法（EntitlementScopeValidationTest） |
|---|---|
| 平台类目与本主体资源有效，READONLY可保留配置 | acceptsOwnedStoreGoodsAndPlatformCategoryWithoutInventingCategoryOwner |
| 跨商家/租户门店、商品、类目拒绝 | rejectsCrossEntityAndCrossTenantFactsEvenWhenBusinessNumbersExist |
| 软删除/停用/重复/不合结构范围拒绝 | rejectsMissingInactiveDeletedAndMalformedScope |
| 从商家会话取得身份，不能伪造平台执行者 | trustedMerchantScopeUsesAuthenticatedIdentityAndNeverElevatesPlatformActor |
| 员工仅可配置分配门店 | limitedStaffCannotConfigureOtherStoresOrAllMerchantStores |
| 赠品商品不能绕过范围归属 | giftGoodsMustBelongToTheSameMerchantEvenWithAllStoreScope |
| 201条分批末项也必须校验 | validatesEveryBatchIncludingTheLastReference |

上述范围测试7项先缺类RED再GREEN；赠品新增方法另有缺方法RED。移除员工门店子集判断，消融测试1失败（Expecting code to raise a throwable），恢复后定向70项全部通过。70项含Scope7、Preview7、Contract10、Catalog12、Validity5、Dedup3、Source4、Quota5、Architecture16、PayTx1。quote/preview/activation相关record及UseCommand引用修订同步落入shop-base三个端口与ContractChecks；SourceDecisionPort未知授权不得携带业务事实。词典生成物同步更新，未将SPI声明记为业务实现。

新增support-schema.sql仅补充设计存储，core授权/源决策/售卡快照3表、pay对账批次/差异2表；数据库字典、API及类清单/任务表已对齐。Node隔离MySQL原生约束/ER/构建闸门3项通过，V2既有66份受管文档守卫及SQL可移植性通过；额外核对本批文档本地链接、55任务及43表唯一性。原生检查证明取消修订不阻塞下一修订成功，但同一成功源跨修订只能成功一次。

JB-009只依赖006已有定义，不受008剩余协议收口阻塞；事实预检使用显式tenant与分批200查询，配置时允许READONLY门店和未上架商品，实际交易另查实时状态。售卡/本人授权/对账支撑表正式实体与迁移仍归011/012/046；恢复租约复用shedlock，禁止未知事实按超时猜取消。JB-008仍DOING，剩余鉴权路由、权限/能力门禁及完整错误语义未关闭；其他交易链路、HTTP与远程适配未完成，整体Java后端不宣称完成。


后续契约核对：API第十一节列出33项内部传输路由，含pay反查core授权/源事实及全部恢复query；正文context与SPI参数、共享密钥、超时、未知/传输失败区别已明确。仍是设计，Handler/HTTP代理/双部署拒绝测试由JB-041实现，不将此项记录为已开放服务。


### c29c4fe4a干净提交最终验证

实际代码提交c29c4fe4a：23 files changed，1152 insertions，22 deletions；新增两个owner事实SPI及实现、core范围服务/Impl、app可信scope解析器、Scope与Preview测试、支撑设计DDL，其余为协议和设计/生成物对齐，文件归属与上方清单一致。

`bash scripts/check-head-compiles.sh HEAD`固定c29c4fe4a8c4edad9de6a07fcf8fbbe83d5056fe，从干净副本test-compile通过，全量20模块3558项、0失败，耗时617秒，退出码0；未修改失败基线。默认全量中的原生MySQL条件用例仍跳过，不能将此写成Java业务已在原生库全量通过；本批隔离MySQL设计DDL约束实际通过。此前定向70项、standalone范围7项（报告确认JVM deployment=standalone）及Node3项分别通过。standalone范围守卫测试没有调用尚未实现的权益RPC。

c29之后仅修改API内部33路由/35能力及7个默认关闭开关的设计登记、任务状态证据与本段；Java/正式迁移/运行配置未改，未重复运行同一全量。V2受管文档66份守卫、本文档包本地链接及git diff --check通过。JB-009已完成；JB-008及其余44个TODO仍按独立任务表执行，整体后端未完成。本批仅本地提交，无部署或生产迁移。


### JB-008门禁基础与协议冻结收口（2026-10-18）

档位1，依据§2.5执行。shop-base新增spi.promotion.ActivityCapability（35码）及ErrorCode 40040；pay新增config.PayEntitlementProperties与policy.PayEntitlementFeatureGate，目录Service/Port委派新业务门禁；core新增promotion.config.PromotionFeatureProperties与policy.PromotionFeatureGate。7个开关缺省关闭，不能用开关充当实现/商家/行业/门店就绪事实。已有卡新使用只要求use，不依赖仍可新发行或销售；实际业务接线随所属任务实施。没有改变旧营销/券入口和正式迁移。

| AC | 真实测试与结果 |
|---|---|
| 缺省关闭、全开、独立开关不串开 | EntitlementFeatureGateTest.defaultsRejectNewWorkEvenWithEveryCapabilityPresent / allSevenExplicitFlagsPermitTheirOwnNewWork / enablingCatalogDoesNotEnableOtherBusinessOrPromotionFlags |
| 付费卡各前置开关必须分别满足 | everyPaidCardFoundationIsRequiredIndependently（三组）及paidCardFlagCannotBypassItsCatalogIssuanceAndUsageDependencies |
| 停止发行/销售不撤回已有卡的新使用能力 | stoppingNewIssuanceAndCardSalesDoesNotDisableUseOfExistingAssets |
| 缺失/未知能力、输入快照不可变、结构拒绝 | anOpenFlagStillReportsMissingAndUnknownCapabilitiesWithoutAcceptingThemAsReady / malformedRequirementsCannotBecomeAnEmptyReadyPreset |
| 目录关闭不留claim；历史/暂停/结束/原幂等可用、新工作拒绝 | EntitlementCatalogGateFlowTest.disabledCatalogRejectsCreateAndRollsBackTheCommandClaim / closingCatalogPreservesHistoryAndExactRepliesButBlocksNewWork |
| 新消息码三语与既有架构约束 | BackendI18nParityTest 6项、ArchitectureTest 16项、PayTxManagerConventionTest 1项 |

测试先观察缺类型RED；目录接线前2项预期失败，随后恢复GREEN；immutable Set.of输入暴露contains(null)会抛NPE，改为流式检查null后通过。不同开关上下文首次共用H2导致初始化唯一键冲突，已隔离ent-catalog-on/off，未修改历史迁移、schema或known-failures。去除create门禁，消融1项失败；恢复后88项定向全部通过。原生Java/MySQL1项实际通过0跳过，包含关闭后缓存回放与失败claim回滚；首次默认JDK被构建约束拒绝，指定Java21后重跑通过。

生成词典同步；V2受管66份文档、SQL方言无新增欠账通过，ER/构建闸门Node2项通过，额外核对35能力码与46预置要求一致及文档本地链接。没有新增数据库迁移，因此不虚称正式表已扩至43张。API§11–13冻结内部传输、门禁与错误/权限边界，数据库支撑表设计及IssuePolicy/目录详情歧义已在前批收口。

执行裁定：008完成指协议/门禁基础验收，不等待消费者任务的HTTP/远程代理、真实交易、正式持久化和角色登记；这些本来依赖008，反向等待会阻塞开发队列。新权限只随真实Handler登记，避免空授权。业务细分错误如需扩充，由相应owner任务同步API/枚举/三语和真实失败测试。独立任务表008改DONE，整体后端仍未完成，接下来按依赖推进010。


### f16cfff0d最终验证与设计实现对账

`git show --stat f16cfff0d`：24 files changed，725 insertions，25 deletions。新增7个Java文件为PayEntitlementProperties、PayEntitlementFeatureGate、PromotionFeatureProperties、PromotionFeatureGate、ActivityCapability、EntitlementFeatureGateTest、EntitlementCatalogGateFlowTest；其余Java修改为目录Service/Port接线、既有目录/幂等/原生夹具与ErrorCode，三份messages及词典生成物同步，文件落点与§2.5一致。无新公网Handler、Flyway或角色授权变更。

`bash scripts/check-head-compiles.sh HEAD`实际固定f16cfff0d403d8775a0efbfa3a1cdd95ef1f2019，干净test-compile及全量20模块3570项0失败，605秒、退出0，失败基线保持0条。默认原生MySQL条件用例仍跳过；单独Java21运行`node scripts/verify-entitlement-mysql.mjs`已真实1项通过0跳过。定向88项、Node2项、35能力对46预置要求一致、V2受管66份文档及SQL无新增方言债务分别通过。后续提交仅文档证据，不把它写成又一次全量。

008已完成契约/门禁基础，独立任务表11 DONE/0 DOING/44 TODO；010会员码及短期customerRef是下一执行项。只读核对发现旧MpMyMemberController仅有会员关系/消息开关，TokenStore为登录凭证，均不能直接充当会员码；下一项须补可信统一会员凭证来源，不新增储值专用二维码。完整交易链路、三端HTTP、远程及恢复仍未完成。当前只本地提交，无部署或生产迁移。


### JB-010实现与阶段验证（2026-10-18）

按上述JB-010计划实现，新增16个Java源/测试文件及V426一表；修改核心数据域登记、ErrorCode及三语；C/B端仅增加接口类型、HTTP绑定、拒绝伪造身份的演示占位和OpenAPI，无页面开发。数据库、API（48外部/34内部目标）、类清单、功能矩阵U22和ADR-038同步；生成schema/ERD/词典/端点与权限清单。不变更旧Flyway、旧支付业务或已有角色权限。

RED：MemberCodeResolveFlowTest因缺新Service/SPI无法编译；HTTP新路径无Handler而失败。GREEN：Java21离线Maven定向45项通过（身份7、HTTP4、范围事实7、架构16、权限4、i18n6、schema1）；首次过期夹具错误使用本地CURRENT_TIMESTAMP，改显式UTC后过期拒绝通过。HTTP最初缺mch_account关系导致10403，补真实关系后成功；架构要求Impl分包，已移至service.impl；权限守卫要求注解置Mapping之前，按既有样式调整且登记REQUIRED，不修改扫描器或豁免权限。

消融：临时移除requireReference的actor绑定条件，referenceBindingIncludesTenantEntityStoreAndActor一项失败（Expecting code to raise a throwable，退出1）；恢复后7项身份测试全部通过。另用有效的其他主体/同主体第二门店测试引用范围，避免在前置商家不存在处假失败。新Service对象可读取同一持久引用，证明不依赖原实例内存。

隔离MySQL9.7.2设计验证1项通过：44张目标表、V425/V426重复应用、正式/设计字段逐列一致；会员凭证租户唯一键、未知用途、过期先于签发、引用缺范围及统一码携带商家范围的CHECK均拒绝。C/B vue-tsc通过；API契约无新增缺口（旧myCards/fulfillmentImpact两项基线保留）；SQL无新增方言债务；V2受管66份及24个生成器通过。source git diff --check通过（排除既有B OpenAPI序列化器生成的尾空格，未改变其生成风格）。尚需本次提交干净HEAD全量回归，JB-010保持DOING直到回归证据补齐。财务本人确认仍未实现，归JB-011；内部远程身份Handler仍归JB-041。


补充验证：同11项身份/HTTP测试在-Dshop.pay.deployment=standalone下全部通过，Surefire系统属性确认实际为standalone；此为核心身份在两种pay配置下装配，不代替JB-041远程RPC往返。共享Vitest三文件13项：12通过、1失败。失败为api-path-naming.test.ts的“新端点必须跟随本域约定”，命中既有/biz/inventory/transfers/{no}/void、/ops/banned-word/{id}/remove、/ops/merchant/{entityNo}/nudge、/ops/merchant/{entityNo}/stock-doubt、/ops/product/{goodsNo}/chain五条；对比a51244cd3的三份OpenAPI，违规集合完全相同，本批新增违规0。未修改线上旧路径或增加豁免；biz-contract-fields与message-placeholder通过。启动命令首次误写.mts配置、第二次从错误cwd未发现测试，均非通过证据；实际运行采用npm test --workspace @ai-shop/shared。上述遗留失败与Java全量分开报告。


设计→实现对账：git show --stat 51d10402b：63 files changed，1393 insertions，89 deletions。16个新增Java文件由2个Controller、2个FlowTest、2个SPI、core.member的config1/dto3/entity1/mapper1/port1/service1/impl1及core.user.port1构成，与JB-010计划/类清单一致；正式迁移为V426，生成H2 schema同步。其余文件为ErrorCode/i18n、数据域与权限登记、三端共享类型/两端接口绑定、既有生成器登记及文档生成物，没有新页面。

额外模型对齐生成器仍被既有MemberSetting→mbr_setting映射阻断（6字段只对上2字段，与先前批次同一问题）；它不是“24个受管生成器通过”的一部分。未放宽模型守卫。短期身份凭证的新签发不保存原文幂等结果，允许重试得到新码/引用，是所有财务/资源命令持久幂等要求的明确非财务例外。


第一次干净51d10402b全量：编译通过，20模块3581项，605秒，1项失败MpEndpointAuthTest.everyMpEndpointIsClassified，原因C11尚未登记C端登录分类；其余无失败/错误，原有原生条件1跳过。按守卫要求将POST /mp/member-code登记到REQUIRES_LOGIN（不是匿名/免检），让既有守卫实际匿名请求验证401；不改守卫逻辑或SecurityConfig。修复后另跑定向和干净全量，不将首次结果写成全绿。

C11登录分类修复定向：MpEndpointAuthTest 7项（含真实匿名401）、MemberCodeHttpFlowTest 4项、MemberCodeResolveFlowTest 7项，共18项0失败/错误/跳过，退出0。24受管生成器及V2 66份再次通过。该次修改仅分类登记一行及对账文档，不变更业务实现；提交后重新跑干净全量。


门店权限补充回归：核对BizContext.can仅判断当前请求头门店，MemberCodeHttpFlowTest.customerPermissionAtOneStoreCannotBeBorrowedForAnotherAssignedStore新增真实两个已授权门店的不同角色（A为biz:customer、B仅biz:goods），头固定A、正文选B。RED实际code=0，预期70006，说明门店子集不能替代目标门店功能权限。C08在子集校验后另用BizContext.withStore(targetStore).can(biz:customer)校验目标店；所有者仍可跨本主体店操作，普通员工不能借A店权限识别B店顾客。不改权限码、源边界或其他接口。此项仍属于U22门店隔离，补入验收，修复后必须再验证。

第二轮干净6156975bb编译及全量3581项/20模块通过（600秒、0失败/错误，原有条件1跳过）。后续目标门店校验已由新增用例先RED code=0→恢复GREEN code=70006；最终定向53项通过（HTTP5、身份7、C登录7、B权限4、架构16、i18n6、范围事实7、schema1）。最终代码还需以修正后的提交进行干净全量，不拿615旧快照冒充最终验证。

最终门店权限修正同12项身份/HTTP测试在standalone配置下全部通过（HTTP5、身份7），退出0；24生成器/66份V2及源码diff检查无新增问题。


### JB-010最终干净交付证据

实际测试SHA ecfda1ebd0fd589e445aee751ef61f36e2d19bb7，Java21离线`bash scripts/check-head-compiles.sh HEAD`：干净test-compile通过，全量3582项/20模块、0失败/错误，608秒、退出0；原有原生条件1项跳过。最后门店权限修正stat为4 files changed/40 insertions/3 deletions（Controller3行、HTTP回归及契约/本记录）。测试读取的SHA与最后业务提交完全一致，后续仅文档状态回填。此前6156975bb的3581项通过不用于冒充本次结果。

JB-010按AC及实际文件完成，独立任务表更新12 DONE/0 DOING/43 TODO；正式表/两条HTTP路由同步DONE。保留既有共享Vitest命名失败及模型对齐阻断的独立报告，不宣称所有仓库闸门全绿。下一项JB-011仍TODO，财务本人授权、发行/核销/储值账及远程身份Handler尚未交付；本批无push/部署/生产操作。

### JB-011最终交付记录（DONE）

从干净基线9eb06ccaa开始，新增C09本人确认Controller、可信core报价意图staging service、授权/源决策实体Mapper、SourceDecisionPort持久查询与源owner终态决策写入service；V427创建`ord_ent_authorization`和`ord_ent_source_decision`。订单确认期间锁定主单/子单/明细，校验当前消费者、WAIT_PAY、订单修订、支付币种/金额边界、pay quoteRef/selectionDigest、完整序列化订单快照摘要和意图过期时刻。PENDING没有authorizationRef/authorizedAt；同本人requestKey只回放原授权，换请求键冲突。`SHOP_ENTITLEMENT_AUTHORIZATION_ENABLED`默认false。

RED：初始AuthorizationFlowTest因缺少状态类无法编译，预期退出1；状态模型完成后4个规则用例GREEN。第一次H2持久集成RED：测试schema未含V427表；运行官方`backend/scripts/gen-test-schema.py`更新生成schema后恢复。干净全量78f430311跑3590项/20模块，635秒，3项失败：BizKeyConventionTest捕获本批UUID拼号，改用BizKey.next并补EA/EI/ED前缀；MediaRefCoverageTest被“意图”注释中的“图”误判，改为“预确认记录键”；OtpTestPhoneFlowTest随后独立8/8通过。之后定向持久/规则/Preview/MP鉴权/schema/BizKey/MediaRef/OTP共34项0失败/错误/跳过（退出0）。覆盖订单行改价失效、他人确认拒绝、同意图同键重启实例回放、旧修订取消→新修订成功、跨修订成功源唯一及成功源回退查询。Disposable MySQL9.7.2最终V427原生DDL及唯一键/CHECK场景2项通过，0跳过。

JB-011按验收完成。最终干净HEAD f116a02ab1cb68dd0148a8270c3cfbdf918edb5f通过test-compile及20模块3590项全量测试，0红、约9分钟、退出0；V427原生MySQL9.7.2 DDL/唯一键/CHECK 2项通过；聚焦34项、24生成器、V2 66份、SQL方言、C/B API契约守卫通过。第一次全量暴露的BizKey内联UUID与MediaRef注释假阳性已修复；OTP 8项独立复测通过。当前任务表13 DONE/0 DOING/42 TODO。功能开关`SHOP_ENTITLEMENT_AUTHORIZATION_ENABLED`默认false；真实pay报价生产者仍待权益报价任务接入`stageTrustedIntent`，接通并完成部署配置前不会开放C09。未部署、未写生产库、未push。


### 第一阶段设计与实现对账（2026-10-19）

本批按既有两阶段方案实现A1–A6的core活动子集；完整权益/储值阶段仍未完成。模块、API、正式迁移与能力限制同步到[交付报告](./活动第一阶段-交付与验收.md)、API契约、数据库设计和类清单。Service接口/Impl、核心SqlSessionFactory、Port只在共享SPI、源事实owner读取均沿既有架构。

| 阶段验收条件 | 具体验证落点 |
|---|---|
| 目录不冒充可执行能力 | ActivityPresetServiceImplTest、ActivityPresetExecutionPolicyTest；ActivityPhaseOneNativeMysqlTest核对46项默认禁用 |
| 草稿修改/空字段清除、发布冻结、状态与重放 | UnifiedActivityManagementFlowTest.draftNullClearPublishPauseResumeAndEndPersistAndReplay |
| 同一发布机制与运营停止、数据范围 | UnifiedActivityManagementFlowTest.statusPublicationAndOperationsStopSharePersistentVersionAndRevision、UnifiedActivityHttpContractTest |
| 订单固定CUT、金额门槛/件数/上限、取消 | UnifiedOrderActivityPricingServiceTest、UnifiedOrderActivityAccountingTest、UnifiedOrderActivityTransactionTest |
| 参与名额预算个人限购的原子性、可信终态与未知保持 | ActivityParticipationTransactionTest、ActivityParticipationServiceImplTest、ActivityPhaseOneNativeMysqlTest |
| 内部鉴权 | InternalActivityParticipationEndpointTest |
| 统计币种及员工门店隔离、事实去重 | ActivityMarketingSummaryServiceTest、MarketingEffectProjectorTest、UnifiedOrderActivityTransactionTest |
| 旧团购/集单/赠品/排期兼容 | ActivityGroupBuyTest、ActivityCutTriggerFlowTest、GoodsActivityOnlyFlowTest、ComboActivityFlowTest、ActivityScheduleTest及同批活动场景 |
| 架构与三语约束 | ArchitectureTest、BackendI18nParityTest |

偏差说明：统一执行器首批仅AP009/AP010、MAIN/CNY固定CUT；其余预置明确显示缺失能力，旧玩法保留原执行链路，不为46项目录制造46个空Service。pay来源适配器由第二阶段提供，缺失时拒绝新参与；超时不能伪造失败并释放资源。跨阶段父任务只验活动子项。

集中验收发现两个聚合Mapper需要显式@Mapper和定向扫描，已补齐；新增ALTER JSON字段暴露测试schema类型转换和跨表同名约束的H2差异，在生成器最终输出阶段修复，保留所有已应用迁移不变。新开关/源提供者的集成用例使用独立H2库，避免旧种子重复UPDATE污染。最终实际命令和结果以交付报告为准，不用历史全量结果代替本批定向结果。


第一阶段最终实现提交`14f19f552`；`git show --stat`：81 files changed, 3186 insertions(+), 128 deletions(-)，对应上方管理、参与、订单、统计、传输、owner事实及验证分层。Java21离线定向151项0失败/错误/跳过，原生MySQL独立1项0跳过，生成器1项及消融RED→GREEN通过；24生成器/V2 66文档/SQL/Controller守卫通过。无全量回归，完整命令及能力边界见阶段交付报告。本阶段DONE不改变pay权益/储值未完成的事实。


### 第二阶段B1开工：JB-015（2026-10-19）

Create：pay entitlement/entity/EntIssuePolicy、EntIssueRecipient，mapper/entitlement对应Mapper；dto/IssuePolicyViews；service/EntitlementIssuePolicyService及impl；主应用新增V433发行容量迁移（实施时再次检查版本）。使用CatalogScope、已有目录头锁、PayEntitlementFeatureGate与EntitlementCommandDeduplicator。配置save/get；内部reserve/settle仅MANDATORY加入既有pay事务，发放操作幂等由JB-016拥有，不开放裸计数HTTP。

发行计数按templateNo跨版本/途径累计；policy+recipient条件更新同事务。新增预留要求真实ACTIVE模板、开启策略与发行门禁；确认/释放既有占用不依赖新发行开关。发行控制revision使用CAS，下调全局或个人上限不得小于已有held+issued。服务端scope核实owner及平台出资权限。对应EntitlementIssuePolicyFlowTest和隔离原生MySQL验证，批次验收前不记DONE。


B1实际验收：JB-015/016/017的实体Mapper/Service/本地SPI、V433–V435、真实core受众适配已完成，57个不同Java定向用例及隔离MySQL1项通过；RR当前读取修复后策略3项重跑通过，敞口消融RED→GREEN通过。对应[交付报告](./第二阶段B1-发行批次验收.md)和最新独立任务表；非全量/非公共HTTP全量交付。源码提交`e78d7fc3a`，55文件2230新增/37删除。


### JB-025及041目录/策略承载（开工）

新增EntitlementManagementAppService及embedded/standalone实现、pay-svc内部目录管理端点；复用PayInternalApi/Paths和服务token，不增加第三套客户端。公开BizEntitlementTemplateController交付P01–P06，租户/主体/操作人来自会话，范围校验复用EntitlementScopeResolver。新增四项冻结权限pay:ent:template:read/write/publish与pay:ent:issue:manage，默认OWNER全权，其他角色须显式授权，不默认扩大MANAGER权限。

裁定：模板状态与发行策略影响跨门店、跨版本全部持有人，因此本批P01–P06要求主体全域授权（allowedStoresOrAll=null），受限门店员工不因传入本店STORES范围获得主体级管理；后续核销与本人查询仍按各任务的门店子集执行。成本：受限门店管理员即使配置了新权限也不能管理主体模板，须由全域管理者处理；不以分页后过滤制造总数泄漏。新模板/修改/发布仍逐项验证真实门店/商品/类目归属。

内部分开管理协议与冻结的getPublishedVersion/validateReference SPI；CatalogPort本地与远程在bootstrap互斥装配，pay域不感知部署模式。独立pay提供payTxManager以保证目录写入真实参与其数据源事务。测试包含P01–P06真实HTTP写后读/越权/字段校验、共享客户端打真实pay-svc、失联分类和装配互斥；批次后集中运行。


### JB-020订单券协调补充（执行中）

核心服务以`ord_ent_order_use`保存原操作、授权、命令与原订单摘要，先持久化PENDING再向pay预占；pay返回的真实冻结分摊经核心服务核对后一次应用价格。`ord_ent_payment_seal`在调用外部收银前由核心服务锁单封存应付金额；已发起过收款或已封存的订单不能新增优惠。核心服务终态事务同时记录USE_COMMIT/USE_CANCEL及Outbox；应用协调器只提交/释放原operationNo，丢响应按原操作恢复，不以超时推断取消。暂停新增能力不阻止已有承诺完成。

本切片仅接ORDER_COUPON；服务券/次卡的真实服务意图和储值扣款仍在原任务内继续，不能用普通现金订单支付成功冒充已完成扣次。每单首期只接受一张新订单券；预占后不支持替换券，需取消原订单后重新下单。新表均在核心库；不跨库事务，不直接访问pay实体。


订单券协调切片验证：合并56项及实际订单/零元付款追加11项通过，原生目标DDL1项通过、0跳过。零元checkout改为通过OrderService代理进入markPaid事务，防止自调用绕过事务造成订单PAID而源决策回滚。关键持久事实查询刷新MyBatis会话缓存，避免挂起事务的空同步范围复用预写入NOT_FOUND。当前逻辑模型共48表（core18/pay30）；历史段落计数保留当时范围，不作为本轮完成统计。父任务JB-020仍DOING。


第二阶段B3：核心服务新增V441领取来源（当前目标49表，core19/pay30）。
CENTER与AP004主动领取活动使用同一冻结权益包与支付发放协议；多项权益共用一次营销预留，
支付每项以稳定子来源去重。先全部准备，再记录不可反悔核心决定，最后按全部pay真实终态确认营销参与。
决定和核心outbox同事务；跨模块调用不在资源锁内发起。C只看领取状态和实例号，原操作查询没有发放副作用。
订单条件触发及其他预置执行器继续按能力门槛关闭，预置没有自动启用。


第二阶段JB-027：C统一资产与核销详情按登录本人查询，主体可选但不允许错误主体回退；
商家记录按主体与逐门店读取权限交集过滤，顾客卡详情仍须会员码识别引用。
激活预览、日额度与记录接口均只读。独立pay复用新增内部商家查询端点，null全主体与空授权集合严格区分。

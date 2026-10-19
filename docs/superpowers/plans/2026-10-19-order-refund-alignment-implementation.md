# 订单、退款与权益状态机对齐开发计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans，按任务顺序执行并以复核证据更新勾选项。本计划不授权自动委派子代理。

**Goal（2026-10-19 用户收敛）:** 统一现金、积分和零现金整单退款的结清及恢复，只支持现有商家子单全额退款；屏蔽部分退款，不实施 M2/M3 的部分退款扩展。

**Architecture:** 复用四台业务状态机、既有退款执行和原号恢复协议。新增结清判定、持久权益义务及商品闭合模型；外部资金调用保持事务外，凭据回写后重新评估售后和订单终态。

**Tech Stack:** 仓库现有 Java/Spring、MyBatis、MySQL/Flyway、Maven，以及三端现有 TypeScript/Vue/React 工具链；不升级依赖。

**Spec:** [订单退款对齐设计](../specs/2026-10-19-order-refund-alignment-design.md)。此前完整方案的 D2–D5 是目标设计，非当前上线记录。

## Global Constraints

- 当前一期只允许一次整单全额退款；开放部分退款前不得删除 BasicRefundPolicy 的限制。
- 现金已成功不再次发送；UNKNOWN 只查原指令；PREPARED 不计实退。
- 退款决定、资金执行、消费者权益、商品闭合、库存恢复分别存储事实。
- 订单级锁内 current read、状态 CAS、幂等凭据和 outbox；外部调用不持有订单事务。
- 保留原 READY 结算基数、已付事实和追偿事实；缺可信基数不得猜算。
- 库存恢复失败不抹掉现金成功；券是否应退按原影响集判定。
- 新协议版本持久化；旧任务继续原号恢复。暂停新增不能停止在途恢复。
- 保留用户已有工作区改动；每个提交只包含本任务文件。迁移版本执行时分配，不复用已发布编号。

## Review Focus

1. 当前部分退款不可达：T0/T1 验证入口和准备两层拒绝，避免按错误 Review 修复。
2. 现金成功后权益失败及进程重启：T2 验证不重复退钱且最终收尾。
3. 商品取消与最后收货并发：T4 验证订单级串行和数量闭合。
4. 全积分分母为零、连续部分退及舍入：T5 验证不会首次全退结算。
5. 多店券影响集、旧执行及缺快照：T2/T3/T6 验证不提前退券、不误切协议。

## 本轮范围裁决

用户于执行中明确“屏蔽掉部分退款的分支，只支持整单退款”。因此 T4/T5 保留研究记录与待评审附录，不编码、不分配迁移、不开放入口。T6/T7 只验收 M1 对应契约和兼容恢复；真实资金认证、生产历史盘点及发布不算本地已完成。T8 仅交付接缝设计和独立子计划，不重复实施其他工作区的储值项目。实现与流程真源见 [M1 流程与契约](../../technical/订单退款结清-M1流程与契约.md)。

## 里程碑与依赖

|里程碑|任务|交付/退出条件|
|---|---|---|
|M0 事实基线|T0|更正前轮可达性结论，锁定现有全额行为|
|M1 统一结清|T1→T2→T3|现金、积分、零现金按可信义务收尾；仍只开放全额|
|M2 商品与部分退款|T4，依赖 M1|数量模型、批准快照、履约闭合完成后开放受控入口|
|M3 全积分结算|T5，依赖 T4|零现金分母独立协议与累计比例验收|
|M4 三端/交付|T6→T7|状态表达、文档、迁移及版本恢复验收|
|M5 储值|T8，独立设计|冻结业务规则和接缝后另开实现计划|

T4/T5 涉及新数据库和 SPI 结构，先完成任务内契约设计子步骤，再编码；T8 本期交付设计与子计划，不宣称储值已可实施。M1 可单独发布，M2/M3 未完成时维持原入口限制。

## T0：校正 Review 并固定基线

**Files:** `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/BasicRefundPolicy.java`；`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/impl/RefundPreparationServiceImpl.java`；`backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/AfterSaleServiceImpl.java`；本设计及计划。

**Interfaces:** 消费现有申请、批准、准备与执行调用链；产出范围矩阵：现金/积分 × 全退/部分退 × 新建/重放，标明拒绝位置和已知缺口。

- [x] 记录执行 HEAD、工作区及一期业务依据，逐入口核实 BasicRefundPolicy.full/validateRequest、approved 金额和历史已退限制。
- [x] 在执行记录明确：常量 fullyRefunded=true 的风险是未来放开部分退款的前置问题，不能作为现行可达缺陷；将普通现金权益异步恢复登记为协议变更。
- [x] 运行现有 BasicReturnRefundFlowTest、BasicZeroRefundFlowTest、RefundPreparationConflictFlowTest，保存结果和失败原因；不引用旧通过记录替代本次基线。
- [x] 单独提交文档基线：`docs: correct refund reachability and alignment scope`。

## T1：统一结清模型，保持一期限制

**Create:** `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/model/RefundClosureFacts.java`、`RefundClosureDecision.java`；`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/policy/RefundClosurePolicy.java`。

**Modify:** 同域 `service/impl/RefundResultServiceImpl.java`；`backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/AfterSaleServiceImpl.java`。

**Test:** 新建 `backend/shop-core/src/test/java/ai/neargo/shop/trade/order/refund/policy/RefundClosurePolicyTest.java`；扩展 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/AfterSaleRefundExecutionTest.java`。

**Interfaces（新增设计）:** `RefundClosurePolicy.decide(RefundClosureFacts facts): RefundClosureDecision`；结果为 cashProgress、goodsClosed、allGoodsTerminated、approvedObligationsSettled、可空 targetOrderStatus。Facts 从权威读模型构造，不允许请求参数直接提供结清布尔值。

- [x] 先写策略用例：部分现金/未交付→不终态；交付后补偿→COMPLETED；取消与交付闭合→COMPLETED；全部终止但权益未完→不退款终态；零现金权益完成且全部终止→REFUNDED。
- [x] 运行用例确认缺少策略导致失败，再实现纯函数；这是目标规则测试，不能宣称现行入口已经支持全部场景。
- [x] 接入现行现金全退路径（积分公共收尾延至 T3，见执行记录）：只从完整原订单行/退款计划和执行凭据构造一期 Facts；无法证明的事实拒绝新协议收尾，保留恢复待办，不能默认 true。
- [x] 在申请和准备层保留全退限制；回归全退、零元、金额不匹配和重复回执，确保没有顺带开放部分退。
- [x] 提交：`refactor: centralize refund closure decisions`。

## T2：持久权益义务及现金成功后的恢复

**Create:** 同域 `service/RefundObligationService.java`、`service/impl/RefundObligationServiceImpl.java`、`mapper/RefundObligationMapper.java`；对应新增 Flyway 迁移。

**Modify:** `service/impl/RefundResultServiceImpl.java`、`RefundResourceServiceImpl.java`、`RefundExecutionServiceImpl.java`；积分协调 `order/points/impl/OrderPointsRefundCoordinatorImpl.java`；退款恢复任务的实际调用点执行时按引用定位。

**Test:** 扩展 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/RefundResourceRecoveryFlowTest.java`、`RefundBenefitsAndHistoryFlowTest.java`、`PointsOrderRefundStageTest.java`。

**Interfaces（新增设计）:** `RefundObligationService.prepare(String executionNo)` 保存政策和义务快照；`recover(String executionNo)` 只驱动未结清步骤；`settled(String executionNo): boolean` 读取可信成功/NOT_REQUIRED 凭据。积分既有确认流程保留，义务层引用其结果，不再创建第二份积分退款。

- [x] 先冻结 schema/API：按执行号+义务类型+原权益标识唯一；保存输入 hash、协议版本、原影响集、PENDING/PROCESSING/UNKNOWN/SUCCEEDED/NOT_REQUIRED、权威凭据、重试时间和错误。只有具备安全中止凭据才能释放预占。
- [x] 写失败用例：现金成功后券调用失败、积分确认超时、重启重放、多店只有一店退款、无应退权益。断言现金只发送一次，未结清售后仍 REFUNDING，NOT_REQUIRED 可正常结束。
- [x] 实现现金成功、实退与义务意图在本地事务持久化；外部权益调用在事务外，完成后短事务记凭据并调用 T1 重评。
- [x] 库存继续独立恢复；移除依赖“所有子单已经 REFUNDED”才产生券义务的循环，改由券原影响集和批准商品范围计算资格。未达返还条件的单次决定记 NOT_REQUIRED，最后满足条件的决定创建唯一返还义务。
- [x] 为新执行持久化结清协议版本；旧成功但资源未完成的执行继续原恢复协议，不因当前开关切换改义。
- [x] 验证新增 schema 默认值、唯一约束、恢复扫描索引及 MySQL 迁移；提交：`feat: persist refund obligations and recover closure`。

## T3：状态机事件、守卫与零现金统一

**Modify:** `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/machine/aftersale/AfterSaleEvent.java`、`AfterSaleContext.java`、`AfterSaleGuards.java`、`AfterSaleMachine.java`；`order/machine/order/OrderContext.java`、`OrderMachine.java`；`AfterSaleServiceImpl.java` 的零现金/非零现金/线下确认收尾。

**Test:** `backend/shop-core/src/test/java/ai/neargo/shop/trade/order/machine/MachineInvariantsTest.java`；`backend/shop-app/src/test/java/ai/neargo/shop/scenario/PointsOrderRefundStageTest.java`。

**Interfaces:** 新增 BENEFITS_SETTLED，限 SYSTEM、现金应退为零及 T2 结清凭据；REFUND_SUCCEEDED 要求非零现金成功与应退权益结清。新协议上下文只能由内部权威事实组装；旧执行通过明确兼容适配器接入。

- [x] 先写守卫拒绝测试：无批准决定、缺现金凭据、零现金误发 REFUND_SUCCEEDED、权益未完、客户端伪造结清。补 allowed/fire 一致性。
- [x] 实现 A13/A21/A22 的事实守卫和收尾；现金已成功后权益完成只重评 A13，零现金只走 A22，不新发现金指令。
- [x] 将 PREPARATION_NOT_STARTED_REFUND、COMPENSATED_CLOSE 纳入迁移表及不变式；保留未备货买家入口既有授权，不误删合法退款例外。
- [x] 验证旧执行重放、线下未知原路退款阻断、终态幂等；提交：`feat: align refund terminal events with settlement proof`。

## T4：商品数量、不可变批准决定与部分退款（已延期，不属本轮实施）

**Modify:** `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/model/RefundPlan.java`、`RefundLine.java`、`service/RefundLedger.java`、`service/impl/RefundPreparationServiceImpl.java`、`RefundResultServiceImpl.java`；`order/mapper/RefundLedgerMapper.java`、`ShipmentMapper.java`；`AfterSaleServiceImpl.java`；现有发货/收货/核销调用点按引用定位。

**Test:** 扩展 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/LineRefundFlowTest.java`、`RefundCapacityDatabaseTest.java`、`RefundMultiStoreFlowTest.java`；新建 `GoodsClosureConcurrencyFlowTest.java`。

**Interfaces:** 本任务产出批准决定快照与订单行 current-read 闭合事实，供 T1 判定；退款目的分 CANCEL_GOODS/RETURN_GOODS/AMOUNT_ADJUSTMENT，不能再仅由现金额推导件数。

- [ ] 先写数量/数据库设计附录：决定号和执行版本；覆盖、取消、退回、已交付数量；金额调整不占数量；运费和合法保留额；旧数据可证明范围；新 API 与旧客户端能力门槛。审阅该附录后才编码和分配迁移号。
- [ ] 写失败用例：两件退一件、同件重复申请、少额补偿不耗件、保留运费后商品全终止、交付+取消闭合、两包裹并发最后收货、退款与核销并发。
- [ ] 实现决定/容量/执行幂等键、行数量条件更新、订单级串行与 current read；有多个执行时第一笔成功只记自身账，全部结清才售后终态。
- [ ] 所有收货/核销/取消数量变更都接 T1，同事务写 CAS 和 outbox；库存只按本次批准的实际退货/取消范围恢复。
- [ ] 先在影子计算中比较新旧结果，核对差异后对有快照、通道支持、客户端可表达的订单开放部分退款；其余保持一期全退约束。
- [ ] 运行 MySQL 原生并发与容量验收；提交：`feat: close orders by goods quantities and approved refunds`。

## T5：全积分部分退款与结算基数版本化（已延期，不属本轮实施）

**Modify:** `backend/shop-base/src/main/java/ai/neargo/shop/spi/settle/SettlePort.java`；`backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/settle/refund/PreparedRefundShares.java`、`DefaultSettlementRefundPolicy.java`、`impl/CashRefundSplitServiceImpl.java`；商品退款→结算及积分准备的调用点。

**Test:** 扩展 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/PointsSettlementRefundPathsTest.java`、`SettlementRefundBasisFlowTest.java`；`backend/pay/pay-domain/src/test/java/ai/neargo/shop/pay/settle/refund/DefaultSettlementRefundPolicyTest.java`。

**Interfaces:** 新版退款基数契约携带版本、原商品快照标识、原经济价值及本次覆盖价值、现金原额/累计实退、积分原数量/累计退回。经济价值按下单时冻结的积分折算政策确定，禁止退款时汇率重算；旧 SplitReversal 仅服务可证明的旧现金协议。

- [ ] 先冻结 SPI 版本及持久化兼容附录；逐项列出现金分账、补差、积分成本各自基数，避免现金和积分重复计入结算份额。
- [ ] 写失败用例：全积分两件退一件→仅一半份额；再退另一件→恰好全部；非整除三次退款尾差；零经济价值；旧基数缺失；PAID/未付/已分账三类路径。
- [ ] 统一按固定原额乘退后累计占比减退前累计占比，禁止 base<=0 自动全退；无可信原额报人工核对，不反推金额。
- [ ] 保留 hold、UNKNOWN 原号查询、付款/导出阻断及已付追偿；重放不得重复记录银行回退或追偿。
- [ ] 完成 MySQL 并发/重放及原始基数验收后提交：`feat: version settlement refund bases for points-only orders`。

## T6：三端契约、历史差异与流程图对齐

**Modify:** `packages/shared/src/strategies/after-sale.ts`；`c-app/src/pages/after-sale/index.vue`；`b-app/src/pages/after-sale/index.vue`；`ops-web/app/after-sales/page.tsx`；对应三端 API 类型、词条与测试；完整方案、TDD-订单状态机、三端状态关联文档。

**Interfaces:** 在既有业务状态之外新增结清进度投影，分别返回现金结果、权益结果和资源恢复状态；原 refundProgress 保持现金语义。三端仅消费服务端允许动作，不凭进度自行推导权限。

- [x] 冻结字段与枚举契约，兼容旧端；补现金成功权益处理中、零现金权益完成、需人工核对的展示用例，避免显示“等待现金到账”。
- [x] 更新三端和词条；库存恢复待办仅向有权限的商家/运营展示，消费者不暴露内部资金池或账务实现。
- [ ] 生成生产只读历史差异清单（已交付并用合成MySQL夹具验证 backend/scripts/refund-closure-audit.sql；未连接生产）：订单状态、现金凭据、商品数量、权益义务、结算差异；缺证据不自动改状态，不批量将 REFUNDED 改回。
- [x] 按本轮M1每一条变更的状态迁移建立文档→代码→测试索引，流程图明确标注已实现/未实现；通过契约检查后提交：`feat: expose refund closure progress across clients`。

## T7：发布门禁与兼容恢复

**Files:** 本计划执行记录、新迁移及仓库现行发布流程；不创建绕过现有部署锁的脚本。

- [x] 按任务运行定向测试（本轮最终67项通过）；Java 场景命令模板：在 backend 执行 `mvn -pl shop-app -am -Dtest=AfterSaleRefundExecutionTest,PointsOrderRefundStageTest -Dsurefire.failIfNoSpecifiedTests=false test`。其余 T0–T6 用各任务列出的测试类替换列表；纯策略测试使用 `-pl shop-core -am`，结算策略使用 `-pl pay/pay-domain -am`。新增测试未实现前不声称命令已通过。
- [x] 三端使用各 package.json 中现有脚本执行相关测试、类型检查（54项定向通过，扩大范围既有失败单列）；不安装新测试框架。归档命令、退出码及报告，既有失败与新增失败分别记录。
- [ ] 用 MySQL 验证迁移、唯一键、current read 和并发锁顺序；H2 不能代替原生锁验收。真实资金通道认证单列，不以 stub 通过替代。
- [ ] 发布前用干净提交产物核对 shop-app/pay-svc SPI 兼容性，采用新增兼容接口后再切调用方；新执行协议开关默认关闭，先验证旧执行恢复。
- [ ] 逐阶段开放；回退关闭新入口并保留在途查询、义务恢复和新增字段。旧包不能处理新执行版本时，不得直接回旧包，采用兼容修复版本收尾。
- [x] 用户已授权实现和本地提交；本轮不执行生产部署。后续发布需补齐真实通道、历史核对、干净产物和发布锁证据。

## T8：储值独立设计及开发子计划

**Read:** `docs/technical/TDD-美业储值-系统详细设计.md`、`TDD-活动卡券储值-模块化系统设计.md`、`TDD-储值活动-统一活动模型扩展.md` 及最新裁决。

**Create:** 独立储值接入设计与开发计划；本主计划不预设尚未冻结的 sv 表、HTTP 路由或线上退款能力。

- [x] 整理已确认/拟议/排除能力矩阵，明确活动归核心、权益归支付的接缝及首期现金/线上限制。
- [x] 冻结账户币种/主体、原消费本金赠金分配、预占/确认/释放、退款可退范围及原分配退回规则。
- [x] 对已获确认的支付形态设计结清接入：现金与储值分别凭据，部分成功仍处理中；不把积分或现金余额当储值余额。
- [x] 形成独立 schema/SPI/API/权限/迁移任务及测试矩阵：最后余额并发预占、重复扣减回执、混合部分成功、退款重放、跨主体/币种拒绝。
- [ ] 完成独立方案审阅后才进入储值实现；M1–M4 验收不能标记储值已上线。

T8 设计交付：[储值接缝](../specs/2026-10-19-stored-value-order-closure-seam.md)、[独立接入子计划](./2026-10-19-stored-value-order-closure-integration.md)。最终 schema/API 评审与储值运行时代码仍由独立项目交付；未决事项没有视为已批准。

## 执行记录要求

每个任务记录起止 HEAD、改动文件、测试命令和结果、未覆盖边界、迁移/兼容风险及提交号。任务完成后再勾选；失败项不通过删除守卫、改变金额口径或修改失败基线掩盖。计划自审已核对现行一期限制、零现金已有保护、商品闭合、权益恢复、结算和储值分期的依赖；执行中已完成部分以勾选及执行记录为准；延期、生产验收与未完成项不因本地实现而视为完成。

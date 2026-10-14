# TDD-订单重构修复与三端接线

状态：**实施中**（2026-10-14 用户「基于开发计划进行开发」授权按推荐设计推进；未确定的供应商资金来源/权限及新积分能力保持入口关闭）
关联需求：[TDD-订单状态机 §2D](./TDD-订单状态机.md)、[订单状态机完整方案](../superpowers/specs/2026-10-14-订单状态机-完整方案.md)、[退货退款逻辑](../superpowers/specs/2026-10-14-退货退款逻辑.md)、[第 5 步对象模型](../superpowers/specs/2026-10-14-第5步-对象模型.md)。
创建：2026-10-14 · 最后更新：2026-10-14

用户补充的目录目标：已有订单2.0内核当前在 `ai.neargo.shop.trade.order`；将来迁到 `ai.neargo.shop.order`。现状核查已完成，本轮按开发计划逐批实施，包迁移在M02验收后完成。当前实况及迁移边界见§1.3–§1.4，不能把“内核构件已建”记作“全链路重构完成”。

档位：2。本文是代码实施前的精确分批合同；总体计划、历史实现和历史测试结果仍在订单状态机 TDD。本轮基于实际接线核查重排开发顺序：§2.6是执行主线，§2.5的F01–F18为缺陷修复与业务合同的详细任务库，不重复实施。

## §0 对账一 · 问题与验收落点

| AC | Given / When / Then | 批次 |
|---|---|---|
| RF01 | 正常退款成功回调到达时，按 REFUND 方向认领；重复回调不改变首次成功时间，不认领 PAY 方向行 | F01、F11 |
| RF02 | 自动收货写流水失败时，状态与完成时间全部回滚；一张失败不阻断其他订单 | F02 |
| RF03 | 两次备货同时发生，只有一个成功事实和流水；进行中售后与备货不能在冲突事实下同时成功 | F03 |
| RF04 | 整笔取消只允许完整影响集合获授权；成功则全部子单及占用一致释放，域外信息不泄露 | F04 |
| RF05 | 从 10 元子单进入含另一 20 元子单的支付，展示/发起/回查都按同一个 30 元支付单 | F05 |
| RF06 | 待当面收款、取消和已退款不显示支付成功；回调延迟有界回查，隐藏页面后停止后台请求 | F05 |
| RF07 | 商品实付 300、原商家所得 270，三次分账前退款各 100，累计扣减 270，无残留 80 | F06、F07 |
| RF08 | 分账前退款后再分账、再退款，原始快照与应付/已付/回退位置守恒；合法保留运费仍能结算 | F07、F12 |
| RF09 | 3 件实付 3000，金额调整 1000，不消耗可退件数、不回补库存，剩余金额仍可申请 | F08 |
| RF10 | 全部商品退回且买家接受损耗，协议留存额不被误算为欠退；零现金商品可以结清权益及订单 | F08、F11、F12 |
| RF11 | 通道受理 PROCESSING 或请求结果未知时，售后仍退款中、实退额不增加；成功回执仅落实退一次 | F09–F11 |
| RF12 | 批准提交后崩溃、外部成功后本地失败、重复事件及租约过期，都使用原执行号恢复，不改金额、不双退 | F09–F11 |
| RF13 | 现金已到账但权益处理失败，恢复权益不再次退现金；真实退货回补失败也可按原业务键恢复 | F12 |
| RF14 | 归集已付供应商时，买家退款与供应商追偿有独立结果；未确认的垫付/追偿政策不自动启用 | F12 |
| RF15 | 一行发货前取消、另一行收货，订单可完成；最后两个包裹并发收货也必然完成 | F13 |
| RF16 | 漏揽收直接收到可信签收快照可以收敛；作废运单的迟到事件不得推进替代发货单 | F14 |
| RF17 | 第 8 天普通原因过期、质量原因仍可申请；预览与申请共用规则，预览不占槽不记账 | F15 |
| RF18 | 动作合同仅包含当前资源、权限、数据域和守卫允许的事件；空集合不回落放行，提交重新校验 | F16 |
| RF19 | 三端按同一行级金额与执行结果展示，商家少退必须经过协商接受或平台裁决 | F17 |
| RF20 | 旧买家客户端不支持接受/确认时，新商家端不能创建等待该动作的新状态；关闭创建不截断已有单出口 | F18 |

**当前证据边界：** 两轮 review 的调用链及隔离复现用于定位问题；此前当前 HEAD 的 5 组 25 条测试通过，不代表 RF01–RF20 已通过。本篇所有新增测试均待实施、待运行。

| 开发验收 | 目标 | 责任批次 |
|---|---|---|
| DEV01 | 基线、旧调用方及类型归属可追溯；不重建已有118个内核文件 | B00、M01 |
| DEV02 | 门店订单列表一店一行；支付聚合视图仍只用于收银台；越权无法查到 | I01 |
| DEV03 | 完整保留拼团、预约、活动、门店、多地址、优惠/积分/运费及行快照；建单并发幂等且资源失败可恢复 | I02、I03 |
| DEV04 | 真Controller/作业调用2.0写门及生产Store；包裹按itemId和版本独立更新 | I04、F13、F14 |
| DEV05 | 旧服务只保留兼容转换；订单实体、Mapper、规则、查询/写服务及作业归属完整收拢 | I05 |
| DEV06 | order顶层域已登记、Spring/MyBatis装配正确；无order→trade或trade→order直接业务依赖；旧端点/JSON不因迁包变化 | M02 |
| DEV07 | 三端消费同一资源/金额/动作合同，缺能力不创建无法处理的新状态 | F15–F18 |
| DEV08 | 独立包迁移、资金执行、旧端兼容和存量续跑均有验收记录及可执行恢复方案 | M02、F18 |

## §1 现状、文件与复用边界

### 1.1 当前实现事实

- `RefundLedger.record` 目前在外部退款前直接累加已退件数/金额；没有独立执行预占。
- `SettlePort.refund` 返回资金流水号，不能表达受理、成功、明确失败和未知；资金流水本身已有 PENDING/SUCCESS/FAILED。
- `stl_bill_refund` 仅记录回退合计，未存原始佣金/服务费等逐项基数，不能据此无损还原所有旧快照。
- `SettleServiceImpl.refund` 当前强依赖StlBill，缺账单就返回null；原收款真实存在但未建结算单的退款必须单独覆盖，不能把“尚未结算”当作“没有收款”。
- `StockPort.restore` 的幂等目前依赖调用方 `stock_restored`；改成跨域异步恢复后，目的域必须保证业务键去重，不能只依赖售后标记。
- C 端 `Order` 已有 `payOrderNo/subOrders/amount`，`PayInit.orderNo` 已返回主单号；后端已有 `/mp/order/{orderNo}/pay-result`，C 端适配缺失。支付修复复用这些接口。
- 运营兄弟订单查询按数据域过滤；旧运营 T2 规则允许写路径不受域过滤。整笔取消的全影响集合授权是本轮拟新增的明确例外，不扩散到所有运营写操作。

### 1.2 路径字典

以下缩写只缩短路径展示；`T/xx.java` 表示右侧完整目录加文件名，不代表已有该文件。各批次显式区分“修改”和“新增”。

| 缩写 | 完整仓库相对目录 |
|---|---|
| T | `backend/shop-core/src/main/java/ai/neargo/shop/trade/` |
| O | `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/`（迁移前2.0内核） |
| N | `backend/shop-core/src/main/java/ai/neargo/shop/order/`（M02后的目标订单域） |
| P | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/` |
| S | `backend/shop-base/src/main/java/ai/neargo/shop/spi/` |
| B | `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/` |
| IT | `backend/shop-app/src/test/java/ai/neargo/shop/scenario/` |
| UT | `backend/shop-core/src/test/java/ai/neargo/shop/trade/order/` |
| PU | `backend/pay/pay-domain/src/test/java/ai/neargo/shop/pay/` |

### 1.3 订单2.0完成状态核查（2026-10-14）

核查基线：`df2adc6412e081889cd3b20fd52aaa8c7b761572`。当前工作区后端没有未提交修改；`trade.order`含118个生产Java文件、25个测试类，目标目录`backend/shop-core/src/main/java/ai/neargo/shop/order/`尚不存在。这里的“已接线”指当前源码中的实际调用和Spring装配，不证明生产环境已经部署。

| 部分 | 当前完成状态 | 接线证据或缺口 |
|---|---|---|
| 通用状态机与Payment/Order/Shipment/AfterSale四聚合机器 | 定义与单测已存在；部分聚合已被业务服务使用 | `TradeStatusWriter`实际调用Payment/Order/AfterSale机器；Shipment机器未接入真实发货/收货主链 |
| 对外状态投影 | 已接线 | 旧`OrderStatusView`委托`OrderStatusProjector`，`OrderServiceImpl.payView`使用聚合投影 |
| CAS转换门及流水 | 部分写路径已接线 | `TradeStatusWriter`为Spring组件，支付、取消、收货、备货、干预、售后调用它；旧商家发货仍直接写状态。早期`OrderTransitions`仍由旁路Handler使用，不是当前业务主链的写门 |
| 行级退款、售后期和售后占位 | 已接入旧售后服务 | `RefundDomainBeans`装配planner/executor/store，`AfterSaleServiceImpl`实际调用；异步执行预占、新用途/闭合和权益恢复等F08–F12仍未实现 |
| 下单内核 | 构件已建且有单测，未接真实建单入口 | `CreateOrderHandler`、拆单/草稿/持久化编排存在；生产源码无其外部调用，无`OrderWriteStore`生产实现，定价/优惠/运费解析仍在旧`OrderServiceImpl` |
| 发货单与收货内核 | 构件/表/Mapper已存在，未完成主链接线 | `ShipHandler/ReceiptHandler`有单测；四个Shipment/Receipt读写Store无生产实现，当前入口仍使用旧服务和子单运单字段。Mapper已注册不代表Handler已接线 |
| 统一查询与装配 | 旁路实现存在，未开放为当前查询入口 | `OrderQueryServiceImpl`明确不注册Spring，`OrderReadStore`无生产实现；现有Controller仍调用旧查询服务。旧主单/packages合同不能直接充当新的门店订单合同 |
| 新写命令总入口 | 仅接口 | `OrderCommandService`无实现，也没有生产调用方 |
| 三端切换 | 部分售后状态兼容已落地，未整体切换2.0业务合同 | 当前Controller仍调用`trade.service`；双窗口报价、动作描述符、新协商/确认能力门槛及行级页面接线仍按F15–F18安排 |

本轮在当前HEAD的隔离源码副本使用JDK21、离线Maven执行：订单2.0的25个测试类121条；相关接线/退款/收货/线下支付/结算及售后场景11类85条；ArchitectureTest16条。合计37类222条，失败0、错误0、跳过0，两次Maven均BUILD SUCCESS。证据目录：`/tmp/order20-status-uexotqao/`，日志`order20-tests.log`、`order20-wiring-tests.log`及各模块surefire报告。这是本次定向验证，不是全仓测试或F01–F18新反例通过记录；此前review指出的缺陷仍需修复。

### 1.4 将来迁到shop.order的目标与边界

- **用户明确的目标包：** `ai.neargo.shop.trade.order` → `ai.neargo.shop.order`，先核实当前构件和接线状态，再制定迁移批次；本轮未搬文件、未改Java包名。本目标本身不包含新增Maven模块、独立数据库或微服务拆分。
- **不止目录改名：** 当前内核直接import旧`trade`的11种实体/Mapper/规则类型，包括OrdOrder/OrdSubOrder/OrdItem/OrdAfterSale/OrdStatusLog、TradeMappers及OrderStateMachine/AfterSaleRules/FulfillStage。迁移计划必须逐项确定订单自有类型迁移、旧入口兼容委托与跨域SPI边界，不能靠全量替换包名留下order→trade实体依赖。
- **装配与守卫随迁：** `ArchitectureTest.DOMAINS`尚未登记order，顶层包白名单及域间依赖棘轮需同步；`MybatisPlusConfig`对非BaseMapper显式扫描`trade.order.mapper`，迁移时改到新包并验证Bean装配、SQL绑定及真实入口。测试包、静态检查、配置/全限定引用和文档路径也须核对。
- **保留领域责任：** 结算退款基数/回退/资金流水仍属于pay，库存目的域幂等仍属于product，跨域契约仍属于spi；不会因为订单域独立而把这些实现搬进order。
- **开发计划已重排：** §2.6补入B00/M01/M02和I01–I05，分别验证归属、缺陷修复、Handler接线、包迁移和三端切换。F01–F18的现状路径在M02完成时同步改为新包路径，不把这些动作合成一次未经核对的重命名。

## §2 详细执行计划

> **For agentic workers:** 使用 `superpowers:executing-plans` 逐批执行；用户另选代理执行时使用 `superpowers:subagent-driven-development`。每批必须完成失败测试、最小实现、通过验证、消融和独立 review 后再提交，不能把计划期望输出当作运行结果。

**Goal:** 基于已建且部分接线的订单2.0，分26个唯一批次完成关键缺陷修复、完整业务接线、旧实现收口、shop.order包迁移及三端合同切换。

**Architecture:** 先修确定性入口问题，随后扩展数据和纯模型，再接入持久化执行/结果归并，最后切换三端和新状态入口。退款决定与执行分开，商品、现金、权益和结算分别结清；每个领域只写自己的库。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、Flyway、数据库租约/CAS、Outbox；Vue/uni-app、Next.js、TypeScript、Vitest。

**Spec:** 本文 §0/§3，以及上述三份本轮文档修订。新增业务选择保持待评审，不引用历史确认代替本轮批准。

**开发主线：** §2.6的7个阶段、26个唯一批次（F01–F18 + B00/M01/M02/I01–I05）。已有构件优先复用，阶段以验收出口推进，不把目录存在、编译成功或单测通过当成接线完成。

### 2.1 全局约束与交接类型

1. 单类 ≤200 行、单方法 ≤50 行；状态/计数条件写 0 行必须处理；新规则落 policy/model/service 小对象，大服务只编排。
2. 原子范围按数据源区分。外部发送在请求意图提交后进行，不把网络调用放进持有订单锁的数据库事务。
3. 只增量迁移；当前生产迁移不可修改。新表名均为拟定，实施前验证不存在，版本号当天检查后分配。
4. 退款金额使用整数最小币种单位，不使用浮点数；验证币种、原收款和执行金额一致。普通售后 7 天、质量类 15 天。
5. 集群逻辑使用数据库租约/唯一键，不引入 Redis；不开启新业务入口来验证恢复流程。
6. 新接口/字段通过开关和客户端能力扩展，旧接口保留；已产生执行数据后不能回滚到不认识执行记录的旧退款器。
7. 下列 Java/TS 签名均为**拟新增或拟调整**。兼容适配只能集中在一个入口，不能让各端自行猜用途和数量。
8. M02前新增订单实现放O；M02后放N，支付/商品/SPI仍放P/product/S。T下旧服务/控制器只作现状定位和迁移前兼容接线，不能继续增加订单核心规则。M02必须同步回写§2.5文件路径，后续批次不再按旧包创建类。

| 类型与位置 | 精确合同 |
|---|---|
| `T/order/refund/model/RefundPurpose.java`（新增） | `CANCEL_ITEMS, RETURN_ITEMS, REFUND_WITHOUT_RETURN, MONEY_ADJUSTMENT`。最后一项不关闭商品数量、不回补库存；仅退款的未发货取消与已交付免退回分别映射前者和第三项 |
| `T/order/refund/model/RefundPlanLine.java`（新增） | `record RefundPlanLine(long itemId, int coveredQty, int closedQty, int returnedQty, int cancelledQty, long cashMinor, long agreedRetainedMinor)`；数量是互相独立的事实，不能把 `coveredQty` 当成真实退件 |
| `T/order/refund/model/ApprovedRefund.java`（新增） | `record ApprovedRefund(String afterSaleNo, String subOrderNo, String originPaymentNo, RefundPurpose purpose, List<RefundPlanLine> lines, long freightCashMinor, long freightRetainedMinor, String agreementNo, String approvalEvidenceRef, long planVersion)`；金额、数量、运费留存和批准凭据在批准时冻结，协商少退携带已接受协议，重试不得改写 |
| `T/order/refund/model/RefundClosure.java`（新增） | `record RefundClosure(RefundProgress cashProgress, boolean goodsClosed, boolean benefitsSettled, String orderTarget)`；对外仍用旧 `refundProgress` 表示现金比例，另下发 `goodsClosed/benefitsStatus` |
| `T/order/refund/policy/OrderClosurePolicy.java`（新增） | `RefundClosure evaluate(OrderClosureFacts facts)`；纯函数判断全部商品终止或交付/取消混合闭合，不做权益调用。`OrderClosureFacts` 包含currentOrderStatus、逐行ordered/fulfilled/cancelled/closed数量、cashRefundedMinor/cashPaidMinor、cashLiabilitiesSettled/benefitsSettled/reservationsOpen，由查询适配组装；CANCELLED等既有终态不能被迟到资金回执复活 |
| `S/settle/SettlePort.java`（扩展） | `RefundDispatchResult requestRefund(RefundRequest request)`；`RefundDispatchResult queryRefundExecution(String executionNo)`；旧 `String refund(...)` 不供新编排判断到账 |
| `S/settle/RefundRequest.java`（新增） | `record RefundRequest(String executionNo, String afterSaleNo, String subOrderNo, String originPaymentNo, long amountMinor, String currency, String reason)`；币种取原成功收款，业务执行号唯一 |
| `S/settle/RefundDispatchResult.java`（新增） | `record RefundDispatchResult(String executionNo, String paymentNo, DispatchStatus status, String errorCode, boolean retryable, Long succeededAt)`；`DispatchStatus = ACCEPTED/SUCCEEDED/FAILED/UNKNOWN`，受理不等于到账 |
| `T/order/refund/service/RefundExecutionService.java`（新增接口） | `String prepare(ApprovedRefund approved)`；`void dispatch(String executionNo)`；`void applyResult(RefundDispatchResult result)`；`void recover(String executionNo)`；实现放同包 `impl/RefundExecutionServiceImpl.java`，进一步分派到小对象 |
| `P/settle/refund/SettlementRefundBasis.java`（新增） | 原始 `goodsPaidMinor/grossMinor/merchantGoodsMinor/commissionMinor/serviceFeeMinor/pointsFeeMinor/pointsCostMinor/subsidyMinor/freightIncomeMinor/freightCostMinor/freightShipMode`，另含 `paymentRoute/originPaymentNo/accountSnapshotRef`；全为批准结算时的不可变值，账户引用指向保留历史版本的收付账户快照，不随商家改账户变化 |
| `P/settle/refund/SettlementRefundPolicy.java`（调整） | `BillRefundShare share(SettlementRefundBasis basis, long refundedGoodsBefore, long refundGoodsMinor, long refundFreightMinor)`；固定原始分母计算累计差，实际应付位置另存 |
| `T/order/service/receipt/OrderReceiptCompleter.java`（新增接口） | `boolean completeAuto(String subOrderNo, long now, int shippedDays, int signedDays)`；公开代理方法每单事务，重新判断当前事实 |
| `T/order/refund/service/AfterSalePreviewService.java`（新增接口） | `RefundableFacts facts(String subOrderNo)`；`AfterSalePreview preview(String subOrderNo, AfterSalePreviewRequest request)`；与最终申请共用 planner |

### 2.2 数据、锁和恢复协议

| 数据合同（拟定） | 最低字段/约束 | 写入者和迁移办法 |
|---|---|---|
| `ord_refund_execution` | execution_no、after_sale_no、sub_order_no、origin_payment_no、plan_version、plan_hash、plan_snapshot、approval_evidence_ref、amount_minor、currency、status、split_status、payment_no、next_attempt_at、lease_until、claim_token、version、error_code；唯一 execution_no/after_sale_no，扫描索引 `(status,next_attempt_at,id)` | trade 在批准事务内建；状态 `PREPARED/DISPATCHING/PROCESSING/UNKNOWN/FAILED/SUCCEEDED`。快照含行数量、货款/运费现金、合法留存与权益意图；只复制冻结后的计划，不从变化中的申请额重算 |
| `ord_item`/`ord_sub_order` 的预占 | `reserved_refund_minor`；商品行 `reserved_closed_qty/closed_qty/returned_qty/cancelled_qty`；子单 `refund_model_version/benefits_status` | 已退金额仍使用旧列；原 `refunded_qty` 保留历史兼容，不悄悄重定义。新模型各计数有条件增量；历史不明数量进入核对清单 |
| `ord_after_sale_item` 的冻结计划 | purpose、covered_qty、closed_qty、returned_qty、cancelled_qty、agreed_retained_minor、plan_version；保留旧 qty/refund_minor 兼容列 | 新申请写明确字段；旧金额请求经 `LegacyRefundRequestMapper` 转换。未知历史单不能凭旧 qty 推断真实退件 |
| `stl_bill_refund_basis` | settle_no 唯一、sub_order_no、上述原始 Basis 字段、basis_version、source、created_at | pay 在结算单初次生成同事务落快照；只对未发生退款的旧单可信补录。旧退款流水没有完整逐项 cut 时不得拼凑快照 |
| 资金流水扩展 | `refund_execution_no/origin_payment_no`；新执行行唯一业务执行号，原 out_refund_no 分配后冻结 | pay 独占；不要依赖 `count + 1` 在并发重试时重新生成商户退款号。旧行只补可证实映射 |
| 退款副作用结果 | `(execution_no,effect_type)` 唯一、状态、next_attempt_at、result_ref；使用现有 Outbox 可靠触发 | trade 记录意图，目的域保证幂等。库存恢复需在 product 以稳定业务键和写入同事务去重，不能只检查售后布尔标记 |
| `prd_stock_restore`（目的域去重） | restore_no、sku_no、store_no、qty、target_source、payload_hash、created_at；唯一 `(restore_no,sku_no,target_source)` | product 在实际真源的增量事务内抢唯一键再回补；相同键不同店/数量拒绝，真实写入失败则整笔回滚。target_source 在首次处理时冻结，运行中的恢复不能因配置变化改写另一个真源 |

本地顺序：批准与冻结计划 → 预占和 PREPARED 执行记录同事务提交 → worker 认领 → 先恢复/确认分账回退结果 → 原路退款 → 结果回执/回查 → 实退账本与售后终态 → 独立权益/库存结果及订单收尾。资金请求超时进入 UNKNOWN；在原请求可能成功时不能切换线下退款或重拍退款。

锁顺序：支付级操作先锁 Payment，再按订单号排序锁子单；单子单操作锁 Order，再锁 AfterSale/Execution，再按 itemId 排序锁行。单子单事务**不能中途升级获取 Payment 锁**。跨子单退券判断由提交后的可靠事件启动独立事务，按支付级顺序检查商品终止事实，先创建返券意图，再依据真实权益结果结束订单，避免返券与 REFUNDED 循环等待。

租约是减少重复发送的协调手段，资金幂等仍由稳定外部请求号保证。worker 回写必须携带 claim_token；过期 worker 不能覆盖新处理结果。成功不可退回失败，重复成功不再累加账本；无法确定外部请求未受理时只回查，不自行分配新退款号。

### 2.3 批次依赖及实施门槛

| 批次 | 交付 | 前置 | 业务待评审项 |
|---|---|---|---|
| F01 | 退款回调方向和幂等认领 | 无 | 无新业务规则 |
| F02 | 自动收货每单事务 | 无 | 无新业务规则 |
| F03 | 条件备货及正确流水 | 无 | 无新业务规则 |
| F04 | 完整取消集合与授权 | 文档 D1 | 运营 T2 全影响集合例外 |
| F05 | C 支付视图、结果和回查 | 无；运营确认 UI 待 F04 | 无新支付资源 |
| F06 | 原始结算基数写入 | D5 | 新数据合同 |
| F07 | 分账前后累计切分 | F06 | FinancialClosure 与合法留存 |
| F08 | 用途、数量、协议与零现金模型 | D2/D3 | 新商品结清语义 |
| F09 | 批准冻结、预占与执行记录 | F08 | D4 的事务/恢复合同 |
| F10 | 支付请求先落账再发送，显式结果 | F01、F07、F09 | D4；分账回退未知结果同样禁止继续退现金 |
| F11 | 现金回执及对账驱动实退 | F10 | D4 |
| F12 | 权益、库存、追偿独立恢复 | F11 | D5/D6；新积分体系不在本批实施 |
| F13 | 取消与交付混合、并发收货收敛 | F02/F08/F11 | D3 |
| F14 | 发货接线与物流乱序/版本隔离 | F13 | 补齐现有发货模型，暂不支持行内分批发货 |
| F15 | 可退事实、双窗口、只读预览 | F08/F09 | D6 接口合同 |
| F16 | 动作描述符及提交重验 | F04/F13/F15 | D6 |
| F17 | 三端行级售后与协商/线下动作 | F11/F12/F15/F16；F18 的能力门槛先就绪 | D2/D6 |
| F18 | 能力门槛、演练与发布包 | 能力门槛可提前；最终交付需 F01–F17 | 版本和恢复边界 |

可先准备 F01/F02/F03/F05 的测试及精确修复；不把 F04 的授权变更或 F08 的用途重定义伪装成同一批无契约小修。本文已进入实施，所有批次实际启动需使用已评审的对应合同。

### 2.4 验证方式

后端命令在仓库根执行，使用 JDK 21 和已准备好的 Maven 缓存：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -o -B -f backend/pom.xml -pl shop-app -am \
  -Dtest=该批次指定的测试类 -Dsurefire.failIfNoSpecifiedTests=false test
```

各批次的测试类列在下面；缺测试类、缺依赖、编译未启动属于环境/脚手架问题，不能计作业务红灯。新场景要因旧行为的具体断言失败而变红。三端命令复用根 workspaces：`npm run test -w ai-shop-c-app -- <测试路径>`，B/运营端同理；类型检查用 C/B 的 `type-check` 和运营端 `typecheck`。本轮没有 node_modules，不宣称前端测试已通过。

每批完成最后一步前：目标测试全部通过；能变红的消融运行过；涉及端点/字段/迁移时契约、实体、枚举、i18n 和生成物同步；涉及 Vue 时运行 vue-tsc，界面变更实际预览并更新 ui-catalog。然后独立 review、只提交该批精确文件，记录提交号和范围。

### 2.5 独立修复批次

#### F01：正确认领退款回调

**修改：** `P/service/impl/PaymentLedgerServiceImpl.java` 的 `settleRefundByOutTradeNo/byOutTradeNo`；测试 `IT/ChannelPayCallbackFlowTest.java`，新增 `IT/PaymentLedgerRefundCallbackTest.java`（真实Spring/MyBatis/H2装配属于shop-app，避免用mock查询代替真实方向过滤）。

**接口：** 新增私有 `byOutTradeNo(String no, String direction)` 或等价明确方向查询；付款调用始终传 PAY，退款调用始终传 REFUND，不改变公开字符串返回合同。

- [x] 在真实 REFUND 行上调用回执，断言 `status=SUCCESS`、返回原支付单号；重复回执保持 succeeded_at；不存在退款号及 PAY 行不被认领。
- [x] 运行 `ChannelPayCallbackFlowTest,PaymentLedgerRefundCallbackTest`，确认旧实现因查不到退款行失败。
- [x] 只修方向查询及调用点，检查不影响记录付款人与支付认领。
- [x] 同测试通过；消融改回 PAY 筛选必须变红，再精确提交。此批不解决售后提前终态，后者属于 F11。

#### F02：自动收货按单事务

**修改：** `T/service/impl/OrderServiceImpl.java#autoConfirmReceipt`、`O/mapper/StatusCasMapper.java`；**新增：** `O/service/receipt/OrderReceiptCompleter.java` 和 `impl/OrderReceiptCompleterImpl.java`、`IT/OrderAutoReceiptTransactionFlowTest.java`；保留 `IT/OrderAutoReceiptFlowTest.java`。Job已调用真实三参入口，无需修改。

**接口：** `completeAuto(subOrderNo,now,shippedDays,signedDays)` 在公共代理方法上声明REQUIRES_NEW事务，调用方整轮事务回滚不撤销已成功的单笔；扫描器不包整轮长事务，也不自调用绕过代理。

- [x] 经真实 `OrderAutoReceiptJob.run` 入口注入流水写入失败，断言子单仍 FULFILLING、completed_at 未写、无半条完成流水；再构造两单，第一失败第二正常完成。
- [x] 运行 `OrderAutoReceiptFlowTest`，确认旧三参入口留下部分写入而失败。
- [x] 每单锁 Order 后重新读取到期事实与售后占位，状态、流水、时间和本地事件一次提交；失败记录单号并继续下一单。
- [x] 测试通过并消融事务，验证不会把模拟异常吞掉而算成功；精确提交。

#### F03：备货事实与流水原子提交

**修改：** `T/service/impl/MerchantOrderServiceImpl.java#prepare/save`、`T/order/mapper/StatusCasMapper.java`、`T/order/transition/TradeStatusWriter.java`；测试 `IT/TradeStatusGateFlowTest.java`，新增 `IT/PrepareFactFlowTest.java`。

**接口：** `int prepareIfEligible(String no,long preparedAt)`；WHERE 同时约束 WAIT_FULFILL、prepared_at IS NULL、open_after_sale_no IS NULL，并递增 version。操作者使用现有流水的operatorNo，当前库没有prepared_by，不为这次确定性修复新增该列。门的原地事件只能在事实条件写成功后记流水，不能先记再忽略 0 行。

- [x] 用屏障控制两次备货，断言恰一次新事实/流水；已备货重放保留第一次时间且不重复流水；再验证售后先占槽时备货拒绝。
- [x] 运行 `TradeStatusGateFlowTest,PrepareFactFlowTest`，确认旧实现能返回双成功或重复流水。
- [x] 保留 PREPARE 为原地业务事件，条件写更新事实而不伪造状态迁移；如更新 0 行，重读区分已备货幂等与真实冲突。
- [x] 同测试通过，去掉条件/行数检查使并发用例变红；精确提交。不要一律把所有 stay 事件改成无条件增加版本。

#### F04：整笔取消影响集合及授权

**修改：** `T/service/impl/OrderServiceImpl.java#cancelByPlatform/cancelPayment/createFor`、`T/service/impl/MerchantOrderServiceImpl.java#confirmOfflinePay`、`T/api/ops/OpsOrderController.java`、`T/service/PlatformOrderService.java` 及 impl；测试 `IT/TradeStatusGateFlowTest.java`、`IT/OpsDataScopeFlowTest.java`、`IT/OfflinePayFlowTest.java`。

**新增：** `T/order/policy/PaymentImpactPolicy.java`、`T/order/contract/CancellationImpact.java`；拟 GET `/ops/orders/{subOrderNo}/cancel-impact`。可取消时返回 requestedOrderNo/payOrderNo/totalPayableMinor/affectedOrders；不可完整授权时只返回 canCancel=false、blockedCode，不返回域外明细或金额。

- [ ] 复用 OpsDataScopeFlowTest 的 staffWithScope，主单含 M0001/M0002，仅授权 M0001：预览不泄露 M0002，POST拒绝，主子状态与占用全部不变。ALL账号取消则全部 CANCELLED、资源只释放一次。
- [ ] 运行 `TradeStatusGateFlowTest,OpsDataScopeFlowTest`；增加预览后付款、再取消必须拒绝的场景。
- [ ] 服务端读取未过滤的真实集合后逐对象授权，成功才执行限定支付单的完整事务；不得用按域过滤的 siblings 查询证明完整。
- [ ] 明确取消是旧 T2 写策略的专项例外，保留其他运营写操作原规则；同测试及接口对账通过、消融全集合校验变红，精确提交。
- [ ] 按 D1 在 `createFor` 落库/锁库存前验证 OFFLINE 分组恰为单门店，`confirmOfflinePay` 重新验证实际兄弟集合及收款店。新增跨店篮子拒绝且无占用、历史跨店单不被一店收款全部推进的测试，运行 `OfflinePayFlowTest`；此业务门槛未评审时不启用。

#### F05：收银台粒度、结果与有界回查

**修改：** `c-app/src/pages/pay/index.vue`、`c-app/src/api/{contract,http,endpoints}.ts`、`c-app/src/api/mocks/trade.ts`、三语 locale；**新增：** `c-app/tests/pay-page.test.ts`，必要时 `c-app/src/shared/payment-result.ts`。

**接口：** 补 `payResult(payOrderNo:string):Promise<Order>`，复用后端现有端点和现有 Order/PayInit。先取详情，再按 payOrderNo 重取支付视角；支付方法/付款/回查统一使用该号。

- [ ] 挂载页面：子单1000+兄弟单2000，断言金额3000及两店，所有支付调用均用主单号。WAIT_OFFLINE_PAY/CANCELLED/REFUNDED均不显示支付成功；零元 settled 不调用 requestPayment。
- [ ] 运行 `npm run test -w ai-shop-c-app -- tests/pay-page.test.ts tests/order-page.test.ts tests/one-tap-pay.test.ts`，确认旧金额/状态行为失败，不能只用源码扫描代替挂载测试。
- [ ] `paymentPhase` 显式区分待支付、待收款、确认中、成功、取消、退款、未知；支付交互成功后串行回查，拟延迟1/1/2/3/5秒，共5次、最多12秒。超时显示待确认与手动刷新，不自动再次发起付款。
- [ ] fake timers 验证 WAIT_PAY→WAIT_PAY→PAID 收敛、达到上限/隐藏/卸载停止请求、旧请求不覆盖新单；保留订阅授权在回查 await 之前，确认付款后才清 checkout key。
- [ ] 类型检查与实际多店/延迟结果预览通过、精确提交。运营取消抽屉等待 F04 合同，单独修改 `ops-web/app/orders/proxy-tab.tsx` 展示全部已授权影响集合。

#### F06：结算原始基数，先写不切读

**新增：** `P/settle/refund/SettlementRefundBasis.java`、`RefundBasisStore.java` 及数据库实现；增量迁移 `stl_bill_refund_basis`；**修改：** `P/impl/SettleServiceImpl.java` 的结算生成处。测试拟 `IT/SettlementRefundBasisFlowTest.java`。

- [ ] 验证建结算单同时落原始快照，重放不重复；未退款旧单可补录，已有退款且无法还原逐项原值的单标记 NEEDS_REVIEW，不猜测佣金或补贴。
- [ ] 运行 `SettlementRefundBasisFlowTest`，确认当前无快照而失败。
- [ ] 写入时从原订单金额与已确定结算政策生成 Basis，和结算单同 payTxManager 事务提交；已有基数不可因后续退款被覆盖。
- [ ] 先退款后首次建结算单：仍以原订单金额生成原始Basis，再按已成功退款分配累计扣减，实际初始应付仅为剩余份额；退款执行与结算创建共用支付域的串行点/唯一来源版本，避免初始应付漏扣或重复扣。本批先准备数据合同和测试，切换生成行为在F07/F10完成。
- [ ] 迁移在 H2 与实际支持的 MySQL/MariaDB 环境验证；原退款读路径仍不切换，完整快照断言及实体对账通过后精确提交。

#### F07：固定基数累计切分与独立资金结清

**修改：** `P/settle/refund/{SettlementRefundPolicy,DefaultSettlementRefundPolicy,PreSplitAdjuster,PostSplitReverser,BillRefundShare}.java`、`P/mapper/BillRefundMapper.java`；**新增：** 同退款包 `FinancialClosurePolicy.java`。测试现有 `PU/settle/refund/DefaultSettlementRefundPolicyTest.java`、`IT/SettlementPartialReverseTest.java`。

**合同：** 同一金额项的本次份额 = floor(original × after / originalGoodsPaid) − floor(original × before / originalGoodsPaid)。不能用已扣减的 current net/gross 做 original；运费单独计已退额与合法留存。

- [ ] 加入真实会更新结算金额的三次分账前退款；核心断言 `assertThat(sumMerchantCuts).isEqualTo(270L)`、`assertThat(remainingRefundableMerchantMinor).isZero()`。再加先退款后分账、尾差、重复执行和保留运费仍有应付。
- [ ] 运行 `DefaultSettlementRefundPolicyTest,SettlementPartialReverseTest`，确认90/60/40的旧结果使新场景失败。
- [ ] 策略只消费 Basis；累计位置使用 CAS，商家应付/实际已付/已追回/合法留存分别计算。没有合法剩余应付且无未决资金结果才可整笔 REVERSED。原商品现金为0时禁止除法，纯现金份额显式NONE；非现金权益成本依赖未确认政策时转可见核对，不猜比例。
- [ ] 同测试通过；改回当前金额作基数应变红。对 NEEDS_REVIEW 旧单不启用新自动退款路径；精确提交。
- [ ] 尚未建StlBill且查证无分账/供应商付款意图时，回退阶段显式NOT_REQUIRED，而非虚构回退成功流水；若存在未决付款或账单应存在却缺失则进入核对。后续首次结算消费F06原始Basis及已成功退款累计，不把此前现金退款遗漏成商家应付。

#### F08：用途、数量与协议模型

**新增：** §2.1 的 RefundPurpose/RefundPlanLine/ApprovedRefund/RefundClosure、`T/order/refund/model/OrderClosureFacts.java`、`T/order/refund/policy/OrderClosurePolicy.java`，以及 `T/order/refund/service/LegacyRefundRequestMapper.java`；**修改：** RefundPlan/RefundPlanner/DefaultApprovedAmountAllocator、OrdItem/OrdAfterSale及增量列。测试 `IT/LineRefundFlowTest.java`，拟 `UT/refund/policy/OrderClosurePolicyTest.java`。

- [ ] 测金额调整1000不耗3件；退回3件并约定保留1000时真实关闭3件、留存不作欠退；零现金不得因total=0拒绝合法商品/权益售后。
- [ ] 运行 `LineRefundFlowTest,OrderClosurePolicyTest`，确认旧qty/金额绑死行为失败。
- [ ] 覆盖数量与真实返还/取消/关闭数量分开。旧客户端只给少额且未指明件数，保守映射 MONEY_ADJUSTMENT；明确按件取消映射 CANCEL_ITEMS，已交付仅退款映射 REFUND_WITHOUT_RETURN。不能从现金比例反推出退件数量。
- [ ] 将按件金额政策改为当前剩余可退现金按剩余可关闭件数切分；退完剩余件数取现金余款。调整1000后3件余2000，逐件关闭应退666/667/667，不再按原3000每件1000。剩余现金扣实退、预占及已接受留存；批准后的金额固定，恢复不重报价。
- [ ] APPROVE只接受完整申请计划；少退须 OFFER→ACCEPT_OFFER 或平台裁决，协议号、接受者、版本和留存额冻结。新协商入口等待 F18 买家能力，不利用旧approvedMinor绕过接受。
- [ ] 同测试及迁移/实体验证通过，消融金额调整不耗件后变红，精确提交。历史部分退款数量无法确定的记录列人工核对，保留旧列以便追溯。

#### F09：冻结执行意图与预占

**新增：** `T/order/refund/service/RefundExecutionService.java`、impl、`T/order/refund/mapper/RefundExecutionMapper.java`、执行实体及 ord_refund_execution；**修改：** `T/order/refund/service/RefundLedger.java` 和现有 mapper；测试拟 `IT/RefundExecutionReservationFlowTest.java`。

**接口：** `prepare(ApprovedRefund)`；`reserve(executionNo,plan)`、`commitReservation(executionNo)`、`releaseConfirmedFailedReservation(executionNo)`，均按执行号幂等，不靠 amount>0 跳过零现金数量。

- [ ] 两次相同批准只一条执行；金额/计划hash变化重放拒绝。PREPARED时 `refunded_minor==0`，只增加reserved；超上限并发只有一方成功；批准提交后崩溃仍有可认领记录。
- [ ] 运行 `RefundExecutionReservationFlowTest,RefundLedgerStoreTest`，确认旧无预占/无持久化意图行为失败。
- [ ] 锁 Order→AfterSale→Execution→Item，冻结计划、售后REFUNDING、预占和Outbox同事务；保存执行号不调用外部渠道。增加索引扫描和claim_token条件认领。
- [ ] `prepare` 仅对应实际进入资金执行的决定；同意退货进入WAIT_RETURN时只冻结提议/协议，不创建现金执行，不伪造已退回事实。收到退货、确认退款或裁决真正进入REFUNDING后才建立执行与预占。
- [ ] 同测试通过、消融业务唯一键/预占原子性变红，精确提交。UNKNOWN/PROCESSING不可释放预占；失败释放必须有“外部确定未成功”证据。

#### F10：支付域先落命令，再发外部请求

**修改：** `S/settle/SettlePort.java`、`P/service/PaymentLedgerService.java` 及 impl、`P/impl/SettleServiceImpl.java`、`B/port/SettlePortImpl.java`；**新增：** §2.1 的跨域 DTO，以及 `P/service/refund/RefundDispatchService.java` 和执行存储适配。测试 `IT/RefundReachesChannelTest.java`，拟 `IT/RefundDispatchRecoveryFlowTest.java`。

- [ ] 通道PROCESSING返回ACCEPTED，确定拒绝返回FAILED，网络结果不明返回UNKNOWN；原收款找不到是显式失败而非null当成功。相同execution重放沿用同一originPaymentNo/outRefundNo。
- [ ] 运行 `RefundReachesChannelTest,RefundDispatchRecoveryFlowTest`，确认旧字符串返回不能满足新结果断言。
- [ ] 资金域在 payTxManager 事务内落原收款引用、退款流水及发送意图，提交后才调用渠道；发送结果另事务回填。分账回退同样持久化请求/结果，未知回退时不得继续现金退款。
- [ ] 普通已付款售后与交易不成立的原路退回均直接认领原成功收款，不以存在StlBill作为退钱前提。新增 `paidUnshippedRefundWithoutSettlementBill`：无结算单、无付款意图的已付款未发订单照常退款，后续建结算只含余款；另加 `latePayOnClosedPaymentRefundsWithoutSettlementBill`：原PAY流水真实到账、主单CANCELLED且无结算单，仍能建立原路退款并等到账，订单不复活、已释放库存不回补第二次。
- [ ] worker持有租约不持订单锁调用外部；过期回写被claim_token拒绝，实际外部重复请求由原商户退款号幂等。回查仅用原请求，禁止重新count生成号。
- [ ] 故障注入“提交后发送前崩溃”和“渠道成功后回填前崩溃”均恢复一次；同测试、SPI分层、跨库事务检查通过后精确提交。

#### F11：回执/回查驱动实退与终态

**修改：** `T/order/refund/service/AfterSaleRefundExecutor.java`、`T/service/impl/AfterSaleServiceImpl.java#doRefund/settleRefundTerminal`、`P/service/impl/PaymentLedgerServiceImpl.java#markRefundSettled`、`B/PaymentReconReconciler.java`、`T/job/RefundRetryJob.java`；**新增：** `T/order/refund/service/RefundResultApplier.java` 及结果事件适配；测试拟 `IT/RefundExecutionRecoveryFlowTest.java`。

- [ ] ACCEPTED/UNKNOWN后售后REFUNDING，实退0且预占保留；成功事件后实退增加一次、现金预占转实退，执行SUCCEEDED；无应退权益或权益均完成才将售后REFUNDED，权益待处理则保持REFUNDING并单独展示“现金已到账、权益待处理”。重复成功不增加、迟到失败不回退。不能仅mock字符串退款号就断言成功。
- [ ] 运行 `RefundExecutionRecoveryFlowTest,ChannelPayCallbackFlowTest,LineRefundFlowTest`；旧LineRefundFlowTest即时终态fixture改为真实发出成功回执后再断言，不删掉金额/流水断言。
- [ ] 通道回执和资金对账统一发布/可靠传递资金成功结果；应用校验execution/payment/金额/币种匹配，在trade事务内落实退、更新售后及本地副作用意图。跨库不依赖回调请求的一次本地提交。
- [ ] 零现金执行只处理数量/权益，通过BENEFITS_SETTLED完成，不编造支付流水。非零现金的权益成功事件依据已持久化的现金成功凭据重新评估A13，线下则依据已保存的买家确认评估A21；不重新退钱、不伪造第二次通道回执。FAILED进入可见人工处理或有证据的重试，恢复器覆盖PREPARED/UNKNOWN等执行记录，不仅扫描售后REFUNDING。
- [ ] 系统TRADE_VOID继续严格限原三类原因，使用同一持久化执行与资金上限；已取消/已释放商品不再次占用关闭件数或回補库存，资金回执不把CANCELLED订单推进为REFUNDED。其幂等键关联原收款与明确交易不成立原因，不另开跳过执行记录的旁路。
- [ ] 重复/乱序/重启与跨库失败测试通过，消融“只成功回执落实退”应变红，精确提交。

#### F12：权益、库存、追偿各自恢复

**修改：** `T/service/impl/AfterSaleServiceImpl.java`、`T/port/SettleSourcePortImpl.java`、`backend/shop-core/src/main/java/ai/neargo/shop/product/port/StockPortImpl.java`、`S/product/StockPort.java`、现有coupon/points适配；**新增：** `T/order/refund/service/RefundEffectCoordinator.java`、`P/settle/refund/RecoveryPosition.java`、`backend/shop-core/src/main/java/ai/neargo/shop/product/stock/StockRestoreLedger.java` 和各自存储适配，目的域去重表增量迁移；测试拟 `IT/RefundBenefitsRecoveryFlowTest.java`。

- [ ] 现金已成功、积分返还失败：现金仅一笔，权益PENDING后重试成功。退货回补已提交但ACK丢失：同一restoreNo不再加库存。金额调整无库存变化；确认取消未发商品按精确数量回补已扣库存。
- [ ] 运行 `RefundBenefitsRecoveryFlowTest,RefundLeavesLedgerTest`；明确区分真实退货/取消事实和退款到账事实。
- [ ] 建立每个效果的稳定业务键和结果记录；保留 `StockPort.restore(restoreNo,items)` 签名，把其幂等责任移到真实库存目的域 `StockRestoreLedger`（§2.2）。不能把提交后的重试继续交给只有调用方布尔标记的restore。已有真源提供同等稳定唯一键时复用并在实现偏差说明记录，不重复造库存总账。
- [ ] 主单全部商品终止时先持久化返券意图，结果再参与订单闭合，禁止等待REFUNDED才发意图。积分比例接口尚待独立方案确认时，显式记录待处理依赖，不返回伪成功；补偿不得触发整单退券/抵扣积分。
- [ ] 归集已付供应商生成独立应收追偿，合法运费/损耗应付由FinancialClosure提供结算就绪事实，不以订单REFUNDED直接标结算REVERSED。责任、垫付账户、权限未评审时此入口保持关闭。
- [ ] 效果重放、返回丢失、跨域失败与合法留存结算验证通过后精确提交。此批不实施18张积分域重整表。

#### F13：商品闭合与多包裹完成

**修改：** `T/order/service/receipt/{OrderCompletion,ReceiptHandler,ReceiptReadStore,ReceiptWriteStore}.java`、订单状态机/写门；**新增：** `T/order/mapper/OrderWriteLockMapper.java` 与 `OrderClosureCoordinator.java`；测试拟 `IT/OrderCompletionRaceFlowTest.java`。

**接口：** `recompute(String subOrderNo)` 在锁定Order后读取当前数量/有效Shipment/现金权益结果。仍需履约数量排除真实取消，已交付与取消混合闭合→COMPLETED；商品全部交易终止且退款/权益完成→REFUNDED。

- [ ] A未发取消、B正常收货可完成；最后两个包裹屏障并发收货后订单COMPLETED、完成流水一行；归零商品待权益时不提前最终结清。
- [ ] 运行 `OrderCompletionRaceFlowTest,OrderAutoReceiptFlowTest`，确认旧“所有行已发”及独立CAS检查不收敛。
- [ ] 所有包裹更新先取得所属Order串行点，再当前读重算；将取消/退款数量变化也纳入重算事件。受理时阶段快照保留，执行时当前物理事实用于防止已发商品当作未发取消，不擅改已批准金额。
- [ ] 同测试通过并消融订单级串行点，验证两最后包裹并发必然结束；精确提交。不支持的行内分批发货仍明确拒绝。

#### F14：发货写路径与物流事件收敛

**修改：** `T/order/service/ship/{ShipHandler,ShipmentWriteStore,ShipmentReadStore}.java`、`T/service/impl/MerchantOrderServiceImpl.java#ship`、发货单状态机与物流订阅适配；**新增：** `T/order/service/ship/ShipmentSnapshotConverger.java`；测试拟 `IT/ShipmentEventConvergenceFlowTest.java`。

- [ ] CREATED收到可信签收快照补齐必要迁移；重复/迟到揽收不降级；改单号后旧运单签收不推进替代单；两包裹各自轨迹互不覆盖。
- [ ] 运行 `ShipmentEventConvergenceFlowTest` 和现有ShipHandler单测，确认旧单运单路径不能满足多包裹断言。
- [ ] 事件持有shipmentNo/waybillId/版本，改单号作废旧发货单并建新单；相应行归属更新同Order事务。逻辑快照单调推进，处理重复和漏事件，物流状态与人工确认收货不是同一个事实。
- [ ] 发货在相同Order串行点校验closed/reservedClosed/cancelled数量；资金执行已批准的取消范围不能继续发出。申请阶段快照不等于永久冻结真实物流，无法按原用途执行时保留金额和决定证据，进入明确协商/人工路径，不静默改额。
- [ ] 装配真实Store适配并从Controller端走通，B/C多包裹实际预览、迁移/契约/版本测试通过后精确提交。

#### F15：可退事实与只读报价

**新增：** `T/order/refund/service/AfterSalePreviewService.java` 与实现、`T/order/refund/contract/{RefundableFacts,AfterSalePreviewRequest,AfterSalePreview}.java`；**修改：** `T/api/mp/MpTradeController.java`的订单路由接替者、RefundDomainBeans、申请planner；测试拟 `IT/RefundPreviewAndActionsFlowTest.java`。按§2.6阶段6执行时这些新类实际位于N，不重建T下旧控制器。

**合同：** GET `/mp/order/{subOrderNo}/refundable` 返回逐行remainingQty/remainingMinor、剩余运费、normalDeadlineAt/qualityDeadlineAt、渠道能力、version。POST `/mp/order/{subOrderNo}/after-sale-preview` 输入type/reasonCode/items/refundMinor?，返回逐行与货款/运费/总额、effectiveDeadlineAt、applicable/blockedCode、version。两个接口均只读。

- [ ] 第8天普通原因拒绝/质量允许；选一行与全剩余运费不同；预览后占槽/售后/账本/资金表均无新增；跨订单item与非属主拒绝；提交时余额变化不能按旧预览退款。
- [ ] 运行 `RefundPreviewAndActionsFlowTest,LineRefundFlowTest`，确认当前无双窗口报价接口而失败。
- [ ] 预览和申请调用同一RefundPlanner/WindowPolicy，提交实时重验。OrderVO的旧afterSaleDeadlineAt若保留只表示普通窗口，不能据此封闭质量入口。
- [ ] 契约、三语错误码、SQL访问预算与只读断言通过后精确提交；C页面接入在F17，不提前创建未来新状态。

#### F16：动作描述符与授权上下文

**新增：** `T/order/contract/ActionDescriptor.java`、`T/order/projection/ActionDescriptorProjector.java`；**修改：** OrderVO/AfterSaleVO及Ops装配、`packages/shared/src/types/trade.ts`、运营类型。测试拟 `UT/projection/ActionDescriptorProjectorTest.java`。按§2.6阶段6执行时订单类/测试解析到M02迁移后的新包。

**合同：** `actionContractVersion=1`、`allowedActions:ActionDescriptor[]`；描述符字段aggregateType/resourceNo/event/targetStatuses?/requiredFields?/minAmountMinor?/maxAmountMinor?。事件到端点的映射是受控客户端合同，不下发任意可执行URL。

- [ ] 同Actor不同权限得到不同集合；域外资源或整笔影响未授权无动作；INTERVENE只给合法非资金目标；空集合禁用，合同缺失才进入旧端兼容策略。
- [ ] 运行 `ActionDescriptorProjectorTest,RefundPreviewAndActionsFlowTest`，确认当前缺合同而失败。
- [ ] 从实际身份、权限、数据域、渠道和当前事实构建上下文，不能直接用默认全通过builder。展示资格与依赖输入的提交守卫分开，提供目标和金额约束；提交仍fire完整守卫。
- [ ] 描述符与真实执行矩阵及生成契约验证通过，消融权限交集应变红，精确提交。

#### F17：三端行级售后及现有动作接线

**修改：** `c-app/src/pages/{after-sale,order}/index.vue`、`b-app/src/pages/{after-sale,orders}/index.vue`、`ops-web/app/orders/`；对应API/请求表/类型/mock/locale、ui-catalog。测试新增 `c-app/tests/after-sale-preview.test.ts`，复用 `b-app/tests/after-sale-approve.test.ts`，补运营退款/动作行为测试。

- [ ] C选行件数改变真实报价，B显示申请/协商/真实已退金额及行明细，运营区分退款执行中/到账/权益待处理；返券或积分待处理不能显示成现金未到账。
- [ ] 分端跑挂载/行为测试，新增断言失败后才接页面；B若无DOM设施，先补可用测试环境，不能用字符串扫描替代按钮交互验证。
- [ ] 请求携带明确用途/原因/选行，少退使用协商接受；旧接口适配集中处理。新响应空allowedActions不放行，页面进出时重新取当前动作。
- [ ] F18的能力门槛先落地，新协商/线下入口先关闭；三端类型、API/enum/i18n守卫及实际各状态页面预览通过，更新界面清单后精确提交。

#### F18：能力门槛、迁移及发布准备

**修改：** `T/api/mp/MpTradeController.java`、`T/api/biz/BizAfterSaleController.java`、`T/service/AfterSaleService.java` 及 impl、`T/entity/OrdAfterSale.java`、三端HTTP适配；**新增：** `T/order/contract/TradeCapabilities.java`、`TradeContractGate.java`、`T/order/refund/port/BuyerInteractionCapabilityStore.java` 及数据库适配。售后交互快照与买家能力存储均需增量迁移；创建开关接现有配置系统，部署说明在 F18 交付时按仓库发布规范落位。测试拟 `IT/AfterSaleContractCompatibilityFlowTest.java`。不在本轮执行部署。

**合同：** 拟头 `X-Trade-Capabilities` 受控集合：`actions-v1/refund-preview-v1/negotiation-v1/offline-refund-ack-v1`。缺失按旧端；不能用新B端能力代替买家能力。能力只表达交互支持，不授予身份或功能权限。

`TradeCapabilities.parse(String header)`拒绝未知/过长能力项；`TradeContractGate.canCreate(event,buyerCaps,actorCaps,flags)`与`canHandle(existingStatus,actor)`分开。买家能力只能由本人认证请求登记：存buyerNo/clientInstanceNo/capabilities/observedAt，售后保存所选买家交互会话的能力快照；旧端当前会话或能力不明时不创建依赖新动作的状态。商家主动退款不能仅凭另一设备曾支持新能力就假定当前买家可确认，能力选择和过期规则属于D6评审合同。

- [ ] 旧C+新B不能创建NEGOTIATING/OFFLINE_REFUNDED；服务端开关关闭也不创建；两端具备能力才开启。关闭创建后已有新状态仍能接受/确认/恢复。
- [ ] `AfterSaleContractCompatibilityFlowTest` 先失败再落能力识别和默认关闭门槛；然后才允许F17新页面声明能力。创建开关与存量处理开关必须分开。
- [ ] 迁移演练包含已退款、零现金、部分历史退款数量不明、缺结算快照、多门店及执行中单；输出无法自动还原清单，禁止拍脑袋回填财务数据。
- [ ] 在指定SHA干净副本跑全量闸门与三端测试；注入进程重启/ACK丢失/租约过期，检查库存、现金、权益和结算守恒；记录实际输出。
- [ ] 发布包明确扩表→捕获快照/能力门槛→新执行器就绪→三端合同→小范围新入口的顺序。回滚只关闭新增，不停已有执行/结果出口；数据已有新语义时保留兼容执行者或前向修复。独立review后精确提交发布说明，生产操作另行执行。

### 2.6 基于代码现状的开发主线（本轮新计划，未实施）

#### 2.6.1 路线选择及里程碑

推荐**先修实际主链 → 补齐2.0接线 → 收拢归属 → 迁顶层包 → 三端切换**。直接搬118个内核文件会留下order→trade实体/Mapper依赖，也不会让未装配的Handler投入使用；一开始重写全部订单服务则会把已有支付、预约、优惠和拼团能力重新做一遍。以下路线复用已验证构件，在每个阶段交付可运行的结果。

| 阶段 | 批次及推荐次序 | 交付/退出条件 |
|---|---|---|
| 0 基线与归属 | B00 | 当前SHA、调用清单、类/表归属、旧请求及跨域消费方有清单；已确认与待评审规则分开 |
| 1 确定性修复 | F01 → F02 → F03 → F05 | 退款回调认领、自动收货事务、条件备货、收银台金额/结果各有新增反例及真实入口测试；可独立交付 |
| 2 资金闭环与基础归属 | M01；F04；F06 → F07；F08 → F09 → F10 → F11 → F12 | Mapper归属独立，退款预占/到账/权益分别有事实；资金未知不双退；D1–D6只阻断各自相关入口。归集垫付及未确认积分能力保持关闭 |
| 3 业务接线 | I01 → I02 → I03 → I04；F13 → F14 | 门店订单查询、完整定价建单、支付/取消/收货总入口、真实Shipment Store和物流事件主链可用；既有功能不丢失 |
| 4 旧服务收口 | I05 | 订单规则、实体、Mapper、DTO、作业和订单API已收拢到trade.order；旧入口仅转换/委托，遗留直接消费者经SPI接替，无双写器 |
| 5 独立订单包 | M02 | 完整订单能力迁到shop.order，扫描/域登记/测试/生成物同步；迁包本身不改库表、端点或状态语义；无order↔trade直接业务依赖 |
| 6 三端合同及发布准备 | F15 → F16 → F18能力门槛 → F17 → F18综合验收 | C/B/运营真实页面与合同一致，新入口有能力门槛，存量新状态/执行有出口；发布包就绪，生产操作不在本计划执行范围 |

依赖以§2.3为底，并新增：F04/F08的新增订单Mapper消费M01；I01依赖M01/F08，I02依赖M01，I03依赖I02/F04，I04依赖I03及F01/F02/F03/F09–F12，F13/F14真实Store接线依赖I04；I05依赖I01–I04/F13/F14；M02依赖I05；F15–F18最终执行位置为N。准备性只读设计/测试不等于提前启用新规则。不设日历工期：每批由验收出口计完成，遇到真实通道或权益域依赖先交付可见待处理及关闭的新入口。

**Review Focus（各有责任测试）：**

1. 同一用户同一幂等键并发，库存/券/预约只能占一次；落库失败不能遗留不可追溯占用 → I03。
2. 新下单模型截掉groupNo/appointmentSlotNo/storeChoices/addressChoices等字段，导致能下单但价格/门店错 → I02。
3. 按门店业务号查询却装配整笔支付，或通过兄弟单泄露域外信息 → I01、F04。
4. 迁包后SQL Mapper未装配、旧域消费者改成新非法依赖或重复Controller路由 → M02。
5. 同一SKU多行、旧运单迟到、取消数量与发货相撞，导致错误包裹推进或漏完成 → I04、F13/F14。

#### B00：冻结基线和迁移归属清单

**文件：** 更新本文§1.3/§5/§6；实施时新增`docs/technical/design/order-domain-migration-map.json`，记录每个生产/测试文件source/target/owner、Bean名、表、外部消费者及验证责任。ADR在实施前按仓库空闲编号登记“订单域从trade独立”的包边界决定，不把本计划当作已实施ADR。

**产出合同：** 清单以完整类名和方法为键；所有订单生产文件含明确定义的target，trade剩余购物车能力与pay/product/SPI明确留原域。此次118个文件/222条测试是已记录历史基线，实施启动时重新核对SHA和增量。

- [x] 固定实施起点SHA，在隔离checkout读当前代码与未提交差异；扫描import、全限定名、注解扫描、XML/SQL绑定、反射字符串、Controller、Job和SPI实现，写归属清单。
- [x] 按§2.4运行25个订单2.0测试类及§1.3的11类场景+ArchitectureTest，核对实际37类222条或逐项解释已接受的新基线；保存XML和输出，不用BUILD SUCCESS替代测试清单。
- [x] 输出订单旧服务每个公开方法→目标能力/兼容入口；确认D1–D6状态、资金责任和真实通道依赖，记录各批停止条件。
- [x] 校验清单无遗漏/重复目标、没有把Cart/pay/product搬进order；精确提交文档。B00是只读基线工作，不制造业务失败测试或勾选实现步骤。

#### M01：先拆订单Mapper和规则归属，不迁顶层包

**修改：** `T/mapper/TradeMappers.java`及B00列出的引用方；`T/service/{OrderStateMachine,OrderStatusView,AfterSaleRules,FulfillStage}.java`。
**新增/提取到：** `O/mapper/{PaymentMapper,StoreOrderMapper,OrderItemMapper,OrderStatusLogMapper,AfterSaleMapper,InvoiceRequestMapper,ShippingUploadMapper,ExpressPickupMapper,SeqMapper}.java`；规则到`O/policy/{LegacyOrderStateCompatibility,AfterSaleRules,FulfillStage}.java`。已有O/mapper中Shipment/行级退款/状态CAS Mapper复用。
**测试：** 既有`IT/StateTransitionStoreTest.java`、`IT/LineRefundDataFlowTest.java`、`backend/shop-app/src/test/java/ai/neargo/shop/arch/MapperSmokeTest.java`；拟新增`IT/OrderMapperOwnershipFlowTest.java`。

**合同：** SQL、@TableName、版本、数据域和预约释放CAS保持原义；TradeMappers最终只保留CartItemMapper，订单Mapper同表只有一套生产实现。旧状态兼容仅负责历史值和入口映射，不建立第二套事件/守卫政策。

- [ ] 新测试`orderMappersUsePlatformFactoryAndSameRows`：旧夹具数据通过新Mapper读取一致，预约释放重复只一次；`everyOrderMapperHasOneBean`断言无重复装配或遗漏。
- [ ] 运行`OrderMapperOwnershipFlowTest,MapperSmokeTest,StateTransitionStoreTest,LineRefundDataFlowTest`；新增装配断言先红，单纯移动的等价断言作为回归基线先记录绿。
- [ ] 按上表提取Mapper并改全部引用；规则从旧服务抽到O，旧调用委托；不改变表名、资金路径、状态机或API形状。
- [ ] 同测试及ArchitectureTest通过；在隔离副本撤掉一个Mapper扫描使对应测试红；核对B00清单和生成实体清单，独立review并精确提交。

#### I01：门店订单查询的生产适配

**新增：** `O/service/StoreOrderQueryService.java`、`O/service/impl/StoreOrderQueryServiceImpl.java`、`O/service/query/{StoreOrderReadStore,MybatisStoreOrderReadStore,StoreOrderAssembler}.java`、`O/contract/{StoreOrderView,StoreOrderSummary,StoreOrderQuery}.java`；拟`IT/StoreOrderQueryWiringFlowTest.java`。
**修改：** 旧`OrderServiceImpl.list/detailOf`和商家/运营查询接线点；现有`OrderQueryService/UnifiedOrderVO/OrderAssembler`保留为历史付款视图适配，不注册成新的门店业务查询。

**接口：** `StoreOrderView detail(String subOrderNo,String buyerNo)`、`PageData<StoreOrderSummary> list(String buyerNo,StoreOrderQuery query,long page,long size)`。StoreOrderView含subOrderNo/payOrderNo/storeNo、金额/行/有效Shipment/当前状态、退款事实和版本；列表按subOrderNo分页，取状态投影及当前真实事实。B/运营授权来自现有上下文，不能用buyerNo参数替代商家/数据域验证。

- [ ] `twoStoresProduceTwoBusinessRowsButOnePaymentView`：1000+2000得到两行门店订单，收银台仍3000；`foreignBuyerAndScopedStaffCannotReadHiddenSibling`验证属主/域隔离；`listDoesNotWriteOrQueryPerAction`验证只读与批量访问。
- [ ] 运行`StoreOrderQueryWiringFlowTest,OrderStatusProjectionFlowTest,MultiStoreOrderScopeTest`确认当前主单Facade无法满足新增门店分页场景。
- [ ] 注册真实Store与查询服务，用批量查行/发货单/售后事实装配；旧OrderVO由唯一兼容转换器输出。缺历史Shipment的旧单按明确legacy来源展示，不在GET中补写发货单。
- [ ] 同测试、SQL访问预算及C/B/运营查询契约对账通过；预览已有页面无金额/状态丢失后独立review/提交。新动作字段待F16，不伪造allowedActions。

#### I02：完整下单命令和定价草稿

**修改：** `O/command/CreateOrderCommand.java`、`O/service/create/{CartLine,PaymentDraftFactory,OrderDraftFactory}.java`；提取旧`OrderServiceImpl`中的split/storesOf/discountsOf/doCreate准备部分。
**新增：** `O/command/OrderActor.java`、`O/service/create/{CheckoutPreparationService,PreparedOrder}.java`及`impl/CheckoutPreparationServiceImpl.java`，按职责拆落店/金额/履约校验小对象；拟`IT/OrderCreatePreparationParityTest.java`。

**接口：** `PreparedOrder prepare(CreateOrderCommand command,OrderActor actor)`；`PreparedOrder(PaymentDraft draft,String inputHash)`保留完整已校验草稿。`record OrderActor(Actor actorKind,String operatorNo,String buyerNo,String merchantNo,Set<String> storeNos,Integer payMinutes)`由认证/代客授权构造，客户端不能决定身份或覆盖代客付款时限。
命令沿用现有2.0字段并补全`payScene,appointmentSlotNo,groupNo,openGroup,activityChoices,storeChoices,addressChoices`，Line保留`goodsNo/skuNo/qty`；代理付款时限作为授权上下文的明确参数传递，不丢旧createFor的payMinutes。userNo只作服务器赋值字段。PaymentDraft需包含旧实体上的地址/履约/费用/优惠/积分/活动/期限与行实付快照，不能仅保留Σ单价×数量。

- [ ] `fullLegacyCommandRoundTripsWithoutLosingChoices`逐字段比对旧命令→新命令；`sameInputProducesSameAmountsAndStores`核对折扣、运费、积分、门店、地址和LineShares；`prepareDoesNotReserveResources`断言准备阶段不锁库存/券/预约。
- [ ] 运行`OrderCreatePreparationParityTest,StoreSplitOrderFlowTest,AppointmentSlotFlowTest,GroupOrderFlowTest`，确认当前简化命令/草稿丢字段或金额而失败。
- [ ] 从旧实现提取真实准备逻辑，只调用已有SPI，不重算一套优惠规则；新旧以相同事实快照对账。履约沿用`Fulfillments`实际六值：STORE_PICKUP/NEIGHBOR_PICKUP/MERCHANT_DELIVERY/EXPRESS/STORE_VERIFY/APPOINTMENT。
- [ ] 核对优惠、平台补贴、运费、应付、零现金、发分端和逐行实付；同测试及已有M3TradeFlowTest通过，消融一个选择字段应红，独立review/提交。

#### I03：下单生产Store、幂等和资源失败恢复

**修改：** `O/service/create/{CreateOrderHandler,OrderPersister,OrderWriteStore,OrderNoGen}.java`；旧`OrderServiceImpl.create/createFor`逐步委托。
**新增：** `O/service/create/{MybatisOrderWriteStore,ExistingOrderNoGenAdapter,OrderCreateTransaction,OrderCreateRecoveryStore,OrderCreateRecovery}.java`及恢复存储适配；拟`IT/OrderCreateWiringFlowTest.java`。复用`backend/shop-store-mybatis/src/main/java/ai/neargo/shop/idem/IdempotencyService.java`，不另造通用幂等服务。订单创建执行记录只负责未完成资源占用的恢复，成功API响应缓存继续由现有幂等服务负责。

**接口：** `String create(PreparedOrder prepared,OrderActor actor,String idemKey)`替换Handler的“原价CartLine即落库”接线方式；`OrderCreateTransaction.create(PreparedOrder,OrderActor,String idemKey)`为真实公共事务代理入口，完成本地完整落库及事件。原外部`POST /mp/order`幂等scope保留；旧缓存OrderVO与新内部结果通过外层唯一兼容转换处理，不能因换返回类型把原key当新请求。

**现状纠正及恢复合同：** 当前SysIdempotent唯一键为(idem_key,endpoint)，replay查询没有userNo条件，不能宣称已按用户隔离；修改IdempotencyService.replay使不匹配属主的旧记录明确拒绝，不返回其缓存。此批不修改全局唯一键，不使其他端点改变幂等scope。增加订单专属`ord_create_execution`增量表：execution_no/pay_no唯一、buyer_no/source_endpoint/source_idem_key/active_request_key/active_until/input_hash/plan_snapshot/status/resource_results/next_attempt_at/lease_until/version；active_request_key按buyer/endpoint/key确定，非空时唯一。明确完成后保留原24h有效窗口，到期且无未结清资源才清空active_request_key；UNKNOWN不因24h到期释放此键，原source字段保留审计。缺key的旧请求不改变其原语义，每次请求用独立executionNo追踪资源。外部资源调用前通过独立公共事务持久化稳定payNo与冻结计划，同一用户/端点/非空key查到未结清执行只能恢复原payNo。该记录不随创建业务事务回滚而消失。
`OrderCreateRecoveryStore.prepare(String payNo,String buyerNo,String idemKey,PreparedOrder plan)`→executionNo；`recordResourceResult(String executionNo,String resourceKind,String resultRef)`、`complete(String executionNo)`；`OrderCreateRecovery.recover(String executionNo)`按事实回查/释放并记录结果。明确成功的本地完整订单不得被恢复器释放资源；外部占用未知时只回查，不重建订单或分配新payNo。lease/version条件回写和回查证据参照§2.2原则。创建执行迁移及独立事务作为I03的数据合同一并评审。

- [ ] `sameKeyRaceCreatesOnePaymentAndOneSetOfReservations`控制并发，断言一支付单、两店订单及库存/券/积分/预约各一次；`saveFailureLeavesRecoverableResources`在最后一行落库故障后验证无半订单，恢复记录仍在，所有已成功外域占用有释放或可恢复证据；`legacyIdempotentResponseReplaysSamePayment`覆盖旧缓存；`otherBuyerCannotReplaySameKeyResponse`验证跨用户同key不返回他人结果；`crashAfterExternalReservationResumesOriginalPayNo`覆盖进程重启与回执丢失。
- [ ] 运行`OrderCreateWiringFlowTest,M3TradeFlowTest,StoreSplitOrderFlowTest,CouponDoubleUseTest,AppointmentSlotFlowTest`，确认当前旁路Store和先查后锁不足以满足真实并发/失败场景。
- [ ] 接真实Mapper及已有幂等服务，修属主重放校验；本地创建和独立恢复意图事务均通过公共代理，不自调用。只有持有创建资格的一方占资源；独立数据源的占用不能声称靠本地回滚恢复，按稳定payNo/业务键回查及释放。恢复器不得在本地成功提交与complete回写之间误释放：按payNo先核验完整订单，并与创建者的执行租约/版本协调。
- [ ] 原零元、线下、预约、拼团、周期及优惠链路完整回归；从真实MpTradeController及运营代客入口调用，行快照/事件齐全后独立review/提交。未知资源结果不自动重建第二单。

#### I04：订单写命令门面及真实履约接线

**修改：** `O/service/OrderCommandService.java`、`O/command/ShipCommand.java`、早期`OrderTransitions`和发货/收货Handler；旧`OrderServiceImpl`、`MerchantOrderServiceImpl`、`FulfillmentQueryPortImpl`及相关Job。
**新增：** `O/service/impl/OrderCommandServiceImpl.java`、`O/contract/CreateOrderResult.java`、`O/service/lifecycle/{PaymentLifecycleService,OrderCancellationService}.java`及impl；`O/service/ship/{MybatisShipmentReadStore,MybatisShipmentWriteStore}.java`、`O/service/receipt/{MybatisReceiptReadStore,MybatisReceiptWriteStore}.java`；拟`IT/OrderCommandWiringFlowTest.java`。

**合同：** create(CreateOrderCommand,String idemKey,OrderActor)→CreateOrderResult(payOrderNo,subOrderNos)，不再返回旧主单UnifiedOrderVO；ship(String subNo,ShipCommand,OrderActor)→ShipResult；confirmReceipt(String subNo,String shipmentNo,OrderActor)→void；cancel(String payOrSubNo,String reason,OrderActor)→void；markPaid(String payNo,String channel,String tradeNo)→void。旧API仍输出旧JSON，接替门面负责唯一映射。
ShipCommand改为`List<Long> itemIds`，查真实行ID且校验订单归属，不能把String itemIds直接当skuNos。旧整单收货入口由适配器展开当前有效发货单；历史无Shipment单走明确legacy事实转换，不凭空改写现金/数量。

- [ ] `controllerAndJobUseOneCommandWriter`验证付款回调/取消/备货/收货真实主链流水一次；`twoRowsWithSameSkuShipByItemId`验证只绑定指定行；`storeFailureRollsBackShipmentAndOrder`验证完整事务。
- [ ] 运行`OrderCommandWiringFlowTest,TradeStatusGateFlowTest,OrderAutoReceiptFlowTest,M4FulfillmentFlowTest`，确认现有接口无实现/旧SKU选行/无Store时无法满足真实入口场景。
- [ ] 装配Service及Store，统一调用StateTransitionGate/TradeStatusWriter；早期OrderTransitions不再是第二个写门，已迁完引用后删除或只委托，不保留裸写状态。真实Shipment顺序/取消数量/最后包裹完成按F13/F14补齐，并逐批评审。
- [ ] 对比旧Controller、SPI、核销/自送/自动收货，基本Store与门面调用测试通过后独立review/提交I04；之后F13/F14负责并发完成和物流收敛，不形成I04反向等待F13的依赖。阶段3在全部批次验证后才宣告完整履约接线。门面本身不实现另一套原路退款。

#### I05：订单能力归属收口与外部消费者清理

**修改范围以B00逐文件清单为准：** `T/entity`除TrdCartItem外的订单相关实体、`T/dto`除CartItemVO外的订单合同、订单service/impl/job/support/port/API及引用方；移入O对应entity/contract/service/job/support/port/api职责目录。CartService/CartItemMapper/TrdCartItem/CartItemVO继续留trade。
**目标细分：** `O/service/query/`查询和装配；`O/service/lifecycle/`支付/取消/履约；`O/refund/service/`售后执行与结果；`O/service/create/`准备和建单；`O/service/invoice/`开票；`O/service/pickup/`快递揽收；现有规则提取后旧OrderServiceImpl/MerchantOrderServiceImpl/AfterSaleServiceImpl仅保留入口兼容编排。非购物车API分别落`O/api/{mp,biz,ops,callback}`。
**拟新增测试：** `IT/OrderCompatibilityFacadeFlowTest.java`、`backend/shop-app/src/test/java/ai/neargo/shop/arch/OrderOwnershipTest.java`；旧invoice/express/upload/购买限制/报表消费方均列入回归。

**跨域合同：** 复用S/trade现有OrderSceneQueryPort/SettleSourcePort/OrderRepairPort/GroupOrderPort等能力，生产实现随订单所有者搬迁，SPI包名不强制改为order。确实缺能力时先在spi定义最小DTO/Port，参数/返回值不得引用order或trade实体。旧域接口的直接业务依赖须替换，不通过添加known-domain-deps新行豁免。

- [ ] `legacyEndpointsAndJsonStayCompatible`枚举旧路由、字段、权限和状态；`cartStaysTradeAndOrderOwnsOrderTables`断言实体/Mapper归属；`externalConsumersUseSpiOnly`覆盖全限定名及旧内部Service直接注入。
- [ ] 运行`OrderCompatibilityFacadeFlowTest,OrderOwnershipTest,InvoiceRequestFlowTest,ExpressPickupFlowTest,WxShippingUploadFlowTest,ArchitectureTest,PayReverseDependencyBudgetTest`，确认当前所有权分散、直接消费者未收口而失败。
- [ ] 按B00清单每个能力分别提取、测等价、切调用；旧大服务按职责拆为≤200行/方法≤50行，不把3800行旧实现换个目录称为完成。混合MpTradeController分出`O/api/mp/MpOrderController`承接原订单路由，原类保留购物车；每次仅一个Controller拥有某路由。
- [ ] 从Java、XML、反射/序列化、配置和所有消费者再次扫描；旧trade不再拥有订单写规则/订单Mapper；行为等价与单写器验证通过。多个能力各自独立review/提交，最后以完整归属测试验收I05。

#### M02：迁到shop.order，单独验证包边界

**移动：** 已收口的O生产树→N，同步`backend/shop-core/src/test/java/ai/neargo/shop/trade/order/`→`.../ai/neargo/shop/order/`；B00迁移清单、全部消费者、资源绑定和§2.5路径更新。
**修改：** `backend/shop-app/src/main/java/ai/neargo/shop/config/MybatisPlusConfig.java`、`backend/shop-app/src/test/java/ai/neargo/shop/arch/ArchitectureTest.java`的DOMAINS、扫描/源码守卫和生成文档；拟新增`IT/OrderDomainAssemblyFlowTest.java`、`backend/shop-app/src/test/java/ai/neargo/shop/arch/OrderDomainBoundaryTest.java`。

**合同：** 类职责、表名、事务管理器、HTTP路由、i18n和已有JSON保持原义；order是独立顶层业务包，pay/product/logistics只经SPI交互。未结清退款执行与旧请求号继续使用原数据，不因改包清空/重建。

保留的是HTTP/JSON兼容，不承诺旧内部Java包名兼容：旧服务兼容转换器随订单能力搬入N的compat目录或在生产引用归零后删除，不留trade服务直接注入N服务的临时通道。现有跨域SPI消费者改用其端口实现，不直接import新订单实体。

- [ ] `newOrderDomainIsRegisteredAndOldImplementationPackageAbsent`断言order在DOMAINS且生产无trade.order类；`everyOrderMapperAndApiIsBoundOnce`从启动上下文验证Mapper/路由；`noDirectOrderTradeDomainDependency`断言双向无实体/Mapper/Service依赖；`pendingRefundAndCreateExecutionsSurvivePackageRename`以迁移前保存的执行快照验证迁移后原号续跑。
- [ ] 运行`OrderDomainAssemblyFlowTest,OrderDomainBoundaryTest,ArchitectureTest,MapperSmokeTest,ConditionalBeanWiringTest,PayReverseDependencyBudgetTest`；迁移验收断言在旧树上先红，原行为场景作为回归。
- [ ] 按归属清单原子移动、改包/引用/扫描/测试/资源路径；声明order域，不把它放进infra豁免，不放宽跨域规则；只有确实消除的旧依赖可从棘轮清单删除。更新本文路径：后续F15–F18及新增订单实现只使用N。
- [ ] 用clean构建跑原订单全部内核/场景及装配测试，以生产profile组合和隔离测试数据源验证启动装配；核对Flyway checksum、Controller路由和待处理执行续跑。执行快照使用持久化schema版本而非Java全限定类名，迁包不改变其金额/hash/外部请求号；消融Mapper新包扫描后装配测试应红。
- [ ] 独立review包迁移diff，排除附带金额/状态语义变化；精确提交。此提交可按兼容运行者条件单独回退，不与新增数据库语义切换合并。

#### 2.6.2 各阶段统一验收及计划自检

每个产品代码批次都遵守§2.4：真实失败断言→最小实现→通过→消融→独立review→精确提交；纯移动/基线步骤保留原行为回归，不以编译失败或缺Bean脚手架代替业务缺陷反例。全量闸门在M02和F18明确SHA的干净副本运行，恢复/迁移场景按阶段重放，避免在共享未提交工作区产生“全量通过”结论。

计划覆盖自检：RF01–RF20仍由F01–F18负责；DEV01–DEV08映射到B00/M01/I01–I05/M02及末端批次。四种事实（现金、商品、权益、结算）、旧客户端出口和旧功能回归均有责任任务。迁顶层包前消除直接跨域依赖；新建单不再使用简化金额草稿；新业务列表不复用主单分页；发货按itemId而非SKU。开发计划未运行新增测试、未移动代码；D1–D6的业务待评审状态保持原样。

## §3 待评审业务决策与暂停条件

| 决策 | 本轮建议设计 | 阻断范围 |
|---|---|---|
| D1 | 新线下支付单单门店；整笔取消须全影响集合授权，作为旧运营T2专项例外；存量跨店线下单人工对账 | F04和线下入口启用 |
| D2 | 四种用途、四类数量事实与现金独立；旧少额无件数映射金额调整；少退走买家接受或平台裁决 | F08以后的新用途写入 |
| D3 | 现金比例与商品结清独立；全商品终止且资金/权益结清→REFUNDED，交付+取消混合闭合→COMPLETED | F08/F13新终态 |
| D4 | 批准先提交、预占非实退、回执/回查驱动；UNKNOWN锁原路，禁止双路退款 | F09–F11新执行入口 |
| D5 | 原始Basis固定；合法留存应付不因订单退款抹掉；归集追偿与买家退款分离 | F07/F12涉及新资金规则的切读写；垫付资金及权限未定时不启用 |
| D6 | 双窗事实+只读POST报价、动作描述符v1、买家能力门槛；积分重整依赖单列 | F15–F18新合同；不批准整套积分重整 |

文档修改已获授权，历史确定性问题的精确修复设计可以独立评审。本表业务建议仍为修订稿；不能改测试期待值来代替确认，也不能因开关默认关闭而跳过迁移和恢复验证。

## §4 风险、兼容与范围限制

- 历史部分退款件数不明及已扣减结算原值不可恢复：报告并进入人工核对，不默认全部退件，也不静默复制当前金额作原始值。
- 多数据源：订单能力迁移前在trade.order、迁移后在order，只写平台订单数据；pay只写资金库。本计划不新增订单独立数据库，跨域效果依赖意图/结果事件与目的域业务幂等，单个@Transactional不能替代此协议。
- 外部通道能力：渠道受理与到账返回值须在真实适配器核对。未接通的能力显式不支持，不能靠桩网关true决定上线。
- 回滚：新执行器产生执行记录后，旧退款Job必须跳过该执行号；关闭新入口仍保留处理存量的worker/回查和回执归并。
- 性能：扫描使用状态+到期索引并分页；批量事实读取防N+1。禁止为每个动作逐行查全表，或在网络调用期间持订单行锁。
- 本篇不安排Redis、多实例上线、行内分批发货、新积分18表、生产补账或自动修改历史协议。

## §5 对账三 · 测试证据

| 批次 | 实际验证 | 结果 | 范围 |
|---|---|---|---|
| F01 | PaymentLedgerRefundCallbackTest（3）+ChannelPayCallbackFlowTest（14）；初始业务红1/17，恢复修复后绿17/17；PAY方向消融红1/3 | 红→绿及消融通过；独立review无Critical/Important | 真实退款行、重复成功时间、未知/PAY拒认领、付款人写入方向 |
| F02 | 原收货7+新增故障/外层回滚/待签收售后/未知占槽4+Architecture16；流水故障红1/8；移除事务红1/1；外层回滚红1/2后改REQUIRES_NEW；最终相关集合38/38 | 红→绿及消融通过；独立review及复核无剩余问题 | 坏单回滚、好单继续、调用方回滚不撤销单笔完成 |
| F03 | PrepareFactFlowTest2+TradeStatusGateFlowTest3；红2/5；PREPARE旁路消融红2/2；最终相关集合38/38 | 红→绿及消融通过；独立review及复核无剩余问题 | 双读者并发只一条流水/版本；重放不变；售后占槽拒绝 |
| B00 | baseline-selectors.txt列出的37类；JDK21离线Maven -pl shop-app -am | 222条，失败0/错误0/跳过0；BUILD SUCCESS | df2adc641的订单内核与相关场景，不计作新修复验收 |

RF01–RF20 的测试责任和具体反例映射到§2.5，DEV01–DEV08映射到§2.6。已完成批次的实际红/绿输出、消融结果和范围见上表；其他新增场景仍待执行。§1.3的222条是当前实现定向基线，不是开发批次的验收通过记录。

## §6 对账二 · 文件与实现偏差

B00新增迁移归属JSON、ADR-037及执行状态；只复制本任务的订单文档修订，未复制共享主目录其他会话的积分/登录/原型/locale修改。B00本身没有产品代码变更。

F01提交`f031043f4`：PaymentLedgerServiceImpl方向查询重载、shop-app的PaymentLedgerRefundCallbackTest及本文验证记录。测试路径由计划的pay-domain单测调整到shop-app真实Spring/MyBatis/H2装配，没有mock方向查询，未改端点/库表。独立review仅指出文档记录未同步，现已修正。

F02提交`cf6280783`，由独立真实装配故障测试补充旧收货场景；StatusCasMapper补锁当前订单/售后槽号查询。公共完成器使用REQUIRES_NEW而非默认REQUIRED，外层回滚反例已验证。独立review发现WAIT_RECEIPT被过宽阻断，新增Job回归后恢复原自动签收与商家提醒，保持售后状态且不自动退款；未知占槽仍阻断。严格占槽条件消融业务红1/4，恢复后38/38绿。F03使用真实事务屏障测试替代拟议纯内核测试，锁后重读事实、条件写成功才记流水，仅改变PREPARE路径，其他原地事件不受影响。

后续按批记录 `git show --stat`，与修改/新增文件逐项核对；新增辅助类、字段或接口先更新设计，不让实现与本篇分叉。

## §7 本轮交付与下一步

开发计划共7阶段、26个唯一批次，B00/F01/F02/F03已完成，F05实施中，其余待实施。用户已授权基于本计划开发；未确定的供应商资金账户/权限及新积分能力保持关闭，新入口按批次验证和能力门槛启用。

计划制定时的文档验证：3份文档、551个本地链接、代码围栏和空白检查通过，7阶段/26个唯一批次、RF01–RF20与DEV01–DEV08完整，旧订单TDD的§5/§6记录保持原文。字段/接口一致性及依赖无环已核对；开发后的业务测试与实现证据追加到§5/§6。

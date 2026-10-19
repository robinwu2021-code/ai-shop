# 业务库存与进销存优化执行计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统一库存业务口径，建立可追溯的初始化及同步闭环，再实现支付占用、履约出库与退货验收入库。

**Architecture:** 保留交易库存与进销存两个领域，通过 shop-app/invbridge 编排，统一 JDBC outbox 传递跨库事实。先扩展查询和保护，再引入版本化库存生命周期；前端按服务端数量口径、操作状态和权限展示。

**Tech Stack:** Java/Spring、现有 MyBatis/JDBC 与 Flyway、Vue/uni-app、TypeScript、现有统一 Outbox。

**Spec:** [库存领域与前后端优化方案](../../technical/design/reviews/库存业务与进销存-领域流程与前后端优化方案-2026-10-19.md)

## Global Constraints

- 工作仓库 ai-shop-codex，统一分支 codex/ai-shop-codex；不覆盖其他工作区改动。
- 不使用生产数据作为开发前提；本计划不直接修改线上库存、不切全局权威来源。
- 不测并发压力、不跑无关全量回归；数量守恒、幂等、旧事件与旧订单兼容必须定向验证。
- 实物、预留、可用、线上可售、每日配额各自独立；目标态安全库存只作预警，销售保留使用销售规则；首批保留现有 StockAt.available() 扣安全库存的回写行为，单独返回 supplyAvailable，策略变更另行预览。
- 复用现有 outbox、原库存来源恢复、单据过账与规则计算；不新建平行框架。
- 每个任务完成后单独记录改动及验证结果；只提交本任务文件。

## Review Focus

- 同仓多门店：不得向每店复制整仓额度；任务2阻止尚不支持的配置。
- 已退款但库存恢复失败：退款保持成功，资源恢复独立待处理；任务7验证。
- 盘点期间已存在支付回调：不能阻断资金事实或释放已付款货物；任务6验证。
- 初始化预览后库存变化：确认必须重新校验，旧预览不得覆盖新库存；任务3验证。
- 切店、超时、旧事件重放：不能串店、重复写入或覆盖新结果；任务2/4验证。

## 1. 交付顺序与发布批次

| 批次 | 任务 | 可独立交付的结果 | 前置 |
|---|---|---|---|
| A | 1、2、3、4 | 库存口径、同步保护、初始化预览与B端交互 | 无 |
| B | 5、6、7 | 支付占用、真实交付出库、盘点保护、退货验收闭环 | A完成；新旧生命周期契约固定 |
| C | 8 | 三端统一追溯、发布包与人工验收 | A/B相应接口完成 |

首个可用版本是批次A。批次B改变库存记账时点，必须独立发布；不能随纯UI修改一起无开关切换。共享仓动态额度分配、BOM、称重、多单位暂不实施；共仓先做阻断保护。

## 2. 验证命令约定

仓库根目录执行；Java命令进入 backend：

```bash
mvn -pl shop-app -am test -Dtest=StockSyncWritebackTest -Dsurefire.failIfNoSpecifiedTests=false
npm --prefix b-app run type-check
npm --prefix b-app test -- tests/stock-entries.test.ts
```

各任务以自己的定向测试类替换 `StockSyncWritebackTest`，多类用逗号分隔。先运行新增业务断言确认失败，再实现并运行同组测试。验证命令在实施时结合本地JDK/项目构建前置条件确认；不能把计划中的命令当作已通过记录。纯文案不新增镜像实现的测试。

## Task 1：统一数量口径与库存查询

**Files:**
- 新增 `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/InventoryOverviewQuery.java`。
- 扩展 `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizStockSyncController.java`。
- 扩展 `packages/shared/src/types/inventory.ts`。
- 新增 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/InventoryOverviewFlowTest.java`。

**Interfaces:** `GET /biz/store/{storeNo}/stock-overview?skuNo=...`；返回明确的 `onHand/reserved/available/onlineSellable/unit/accountingMode/ruleSource/syncHealth/asOf/actions`。未建账用 null 与初始化状态，不能用0伪装；初期 accountingMode=PAYMENT_DEDUCT 时显示账面结存，后续支持 HANDOFF_DEDUCT。

- [ ] 测试实存20、预留5时可用15；销售CAP10时线上可售不超过10；安全预警值不重复扣可用。
- [ ] 测试未管理商品、未建物料、多门店越权、缺失门店库存分别返回正确状态或拒绝。
- [ ] 组合现有 StockSyncService、InventoryAclService 的查询，前端不重新计算有效规则或权威来源。
- [ ] 定向运行 InventoryOverviewFlowTest；审查返回成本等敏感字段，按权限裁剪。
- [ ] 提交任务1，并记录已验证口径。

## Task 2：同步保护与统一操作入口

**Files:**
- 修改 `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/InventoryWritebackService.java`、`StockSyncAppService.java`。
- 新增同目录 `StockReconcileService.java`、`InventoryDeliveryGuard.java`。
- 在 `backend/shop-store-mybatis/src/main/java/ai/neargo/shop/event/outbox/runtime/` 新增 `OutboxDeliveryQuery.java`，只读复用实际legacy/unified模式。
- 新增 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/StockReconcileProtectionTest.java`，扩展 StockSyncWritebackTest。

**Interfaces:** `reconcile(entityNo, storeNo, skuNo, reason, operationNo)` 是启用同步、规则变化、过账、人工重算的共同入口。`InventoryDeliveryGuard` 输出 READY/WAITING/BLOCKED 及关联事件，不能通过 payload 模糊匹配推断SKU归属；读取事件的结构化关联，旧事件通过受控解析兼容。

- [ ] 先测失败、未知结果、处理中、等待重试均不能当作已追平；成功/明确不适用才能放行。
- [ ] 先测同源库位被多店独立全量同步时拒绝启用，并列出冲突门店；同库单店正常。
- [ ] 引入 operationNo 幂等：相同号相同请求返回原结果，相同号不同内容拒绝；旧版本事件不能抬高新额度。
- [ ] 将旧入口委托统一编排，保留接口兼容；关闭同步后未完成旧任务不得再写额度。
- [ ] 定向运行 StockReconcileProtectionTest、StockSyncWritebackTest；提交任务2。

## Task 3：可恢复的期初对齐批次

**Files:**
- 新增 `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/StockAlignmentService.java`。
- 修改 BizStockSyncController、StockSyncAppService；新增批次/行持久化对象及mapper于现有产品库存持久层。
- 新增平台 Flyway 迁移（执行时取下一未占用版本，不硬编码旧版本号）。
- 新增 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/StockAlignmentBatchTest.java`。

**Interfaces:** 保留清单查询；新增 `POST /biz/store/{storeNo}/stock-alignment/preview` 返回 batchNo、expiresAt、内容指纹及逐行before/after；新增 `/stock-alignment/batches/{batchNo}/confirm` 接收 operationNo、snapshotVersion；GET同一路径查看结果。

**Persistence:** batch保存主体/门店/模式/预览版本/有效期/状态/操作者；line保存SKU、原数量及锁定/预留快照、目标数量、子操作号、结果及错误码。子操作号由batch+line稳定生成，防止跨库成功后重试再盘一次。任务2的业务操作结果复用该持久化模式，表名及字段归同次迁移评审。

- [ ] 测试库存/锁定/规则/发货源任一变化使预览过期，重复确认不会重复盘点。
- [ ] 测试部分失败保留已成功结果，重试仅恢复未完成行；越店batch拒绝。
- [ ] MALL仅允许明确初始化场景；普通日常修改额度不生成实物盘点。
- [ ] 仅在所有适用行成功后标记aligned；跳过/阻断行须明确列出，不能整店假成功。
- [ ] 定向运行 StockAlignmentBatchTest、StockSyncWritebackTest；提交任务3。

## Task 4：B端库存关系卡及初始化/同步交互

**Files:**
- 修改 `b-app/src/pages/stock/index.vue`、`stock-detail/index.vue`、`stock-settings/index.vue`、`stock-align/index.vue`。
- 同步 `b-app/src/api/contract.ts`、`endpoints.ts`、`http.ts`、`mocks/inventory.ts` 与共享类型。
- 更新 `b-app/src/i18n/locale/zh-CN.ts` 及对应英文文案。

**Interfaces:** 消费任务1 overview和任务3批次接口；操作超时以operationNo查询结果。沿用现有页完成预览→确认→结果，不额外增加入口重复的页面。

- [ ] 列表显示“账面/实物、占用、可用”，线上可售独立一行；详情显示数量关系和有效规则来源。
- [ ] “盘这一件”与“设置线上额度”分组；设置页把开关和同步健康分开，显示最近操作结果与阻断原因。
- [ ] 对齐前展示受影响数量和逐项变化；处理后显示成功/失败/跳过，不用toast代替持久结果。
- [ ] 切店清除旧数据并校验请求代次；超时进入结果确认中，防重复点击。
- [ ] type-check通过，手工检查单店/多店、手动规则、无权限、未建账、部分失败、长名称及窄屏；文案不引入系统技术状态。
- [ ] 提交任务4；批次A可准备独立发布。

## Task 5：版本化的支付占用与履约出库

**Files:**
- 修改 `backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/service/ReservationService.java` 及 `impl/ReservationServiceImpl.java`、`support/InvEnums.java`。
- 修改 `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/port/DualWriteStockPort.java`、`port/InventoryStockPort.java`、`InventoryMirrorConsumer.java`。
- 修改 `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java` 支付入口及 `trade/order` 实际履约写入点；实施时从真实调用链确认，不根据包名猜测。
- 新增 `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/FulfillmentStockIssueService.java`。
- 新增平台与进销存增量迁移；新增 `InventoryPaidAllocationFlowTest`、`InventoryHandoffIssueFlowTest`（scenario目录）。

**Interfaces:** reserve→allocatePaid→issueByFulfillment；沿用原commit处理旧版本。订单创建快照 `inventoryLifecycleVersion=1|2`：旧订单与缺失值按1，新能力启用后新订单按2；出库以fulfillmentRef+line+operationNo幂等。按原库位出库，不按最新门店配置重算来源。

- [ ] 测试V2支付后onHand不变、预留总量不变且转已付款；只有实际交付/交接出库减实存及对应占用。
- [ ] 测试已付款不被待支付TTL释放；重复付款、重复交接、修正运单不重复扣减。
- [ ] 测试旧订单支付即出库后，再发货不二次出库；部分履约累计不得超过原预留数量。
- [ ] 实施者先列出快递、自送、自提真实动作：录运单若兼任交接，必须明确命名与确认；不能把物流轨迹当成重复出库触发器。
- [ ] 原预留增加分行来源及已核销数量；按行汇总头状态，跨库使用统一outbox恢复，预留/余额/凭证同本地事务。
- [x] 定向运行InventoryFlowTest、InventoryDualWriteTest、OutboxRoutingTest、FulfillmentStockIssueConsumerTest及迁移检查；新生命周期默认关闭。

## Task 6：盘点截止与冻结守卫

**Files:**
- 修改 `backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/service/impl/StockCountServiceImpl.java`、ReservationServiceImpl、StockPostingServiceImpl。
- 新增同service目录 `InventoryMutationGuard.java`；修改 `b-app/src/pages/stock-check/index.vue`。
- 增量迁移增加盘点冻结范围及审计；新增 `InventoryCountCutoffFlowTest`。

**Interfaces:** 冻结键owner+location+item；未盘选中行必须录入或显式排除。守卫覆盖预留新增与实物过账；支付仅分类占用，允许通过；被阻断履约保留待处理业务操作。

- [x] 测试开盘后实物交接出库被阻断；盘点调整仅产生一次差异。
- [x] 测试未录入与实盘0不同；未录入拒绝过账、实盘0正常过账。
- [x] 取消/完成释放冻结，超时只提醒与受控取消，不静默丢弃未完成盘点。
- [x] UI确认冻结范围、受影响动作；显示未录行数及恢复入口。
- [ ] 定向运行 InventoryCountCutoffFlowTest、InventoryFlowTest，B端type-check；已增加实物移动暂停提示，仍欠盘点超时/恢复入口与更细的冻结影响范围。

## Task 7：退款资源恢复与退货验收入库分离

**Files:**
- 修改 `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/impl/RefundPreparationServiceImpl.java`、RefundResourceServiceImpl。
- 修改 `backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/service/impl/OriginalReservationRestoreServiceImpl.java`。
- 新增 `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/ReturnReceiptService.java` 及验收接口、明细表与对应前端售后接口。
- 新增 scenario测试 `InventoryRefundReceiptFlowTest`，复用 BasicReturnRefundFlowTest、RefundResourceRecoveryFlowTest。

**Interfaces:** 未出库退款撤销已付款占用；已出库仅退款不回库；退货验收以afterSaleNo+line+receiptNo记实收和quality。V1退款路径保留原恢复机制；V2不能同时由退款成功和验收生成入库。

- [ ] 测试退款成功且恢复失败时资金状态保持成功、库存显示待处理，重复恢复不超额。
- [ ] 测试已出库仅退款不增库存；可售退货进入可售库，不可售退货进入隔离/报损流程。
- [ ] 验收允许与资金执行先后独立，但均要求有效售后授权；累计验收不超过可退数量。
- [ ] 复用原来源恢复数量上限和凭证幂等；页面同时展示退款、退货、库存处理状态。
- [ ] 定向运行上述三组测试；提交任务7。

## Task 8：三端追溯及发布验收

**Files:**
- 修改 `b-app/src/pages/stock-docs/index.vue`、stock-detail/index.vue与实际订单/售后详情页。
- 修改 `ops-web/app/inventory/health-tab.tsx`、`link-health-tab.tsx`、`recon-tab.tsx`、`ledger-tab.tsx`及对应API类型。
- 检查C端商品、下单及售后页现有可售契约；只在存在口径分歧时修改，页面路径由路由清单核实。
- 更新设计方案、功能矩阵与发布记录；运营端改动遵守 ops-web/AGENTS.md。

- [ ] B/运营端按订单→预留→出库→退货→同步串联查询，显示来源、操作人、前后值；按权限提供重试。
- [ ] C端只展示可购买状态；库存变化保留购物车输入；创建结果未知先查询原操作结果。
- [ ] 完成单店快递、自送、自提、仅退款、退货验收人工路径；选一单V1、一单V2验证兼容。
- [ ] 生成后端/B端/小程序发布产物前，登记对应提交和数据库版本；先扩展迁移及后端兼容，再更新前端，最后开放新订单V2。
- [ ] 回滚：停止新建V2，不把已有V2订单切回V1；保留处理已有订单所需后端与增量表，不删账或反向重跑迁移。
- [ ] 人工验收通过后记录结果；部署和安装单独按当次指令执行，不将“产物已打包”写成“已上线”。

## 3. 完成定义

每项任务须记录：接口和数量行为、关联迁移、定向验证命令与实际结果、前端检查结果、未完成项。不得把文档、测试框架或后端完成等同于三端闭环完成。

进入批次B前需完成一次面向实现的设计复核，固定交接动作、旧订单版本路由及盘点冻结影响；本计划给出推荐默认方案，不授权在生产直接热切库存权威来源。

## 执行记录：2026-10-19 首批

### 后续执行记录：任务5/6（2026-10-19）

- 任务5完成：订单创建快照V1/V2，支付后V2转已付款占用，履约交接经统一Outbox投递；消费端先确认占用再按子单出库，修正运单重放不会重复扣减；退款未交接按子单释放。V1仍保持支付即扣减。
- V2退货来源证明按已出库凭证逐行核验，并以实际出库数量作为可恢复上限，避免把未交接预留误当退回实物。库存状态使用现有VARCHAR(16)可容纳的`PARTIAL_COMMIT`。
- 任务6服务端部分完成：盘点期间的入/出库过账受截止守卫保护，盘盈/盘亏只允许其盘点单明细通过；缺少实盘值不能过账，输入0仍有效；付款预留不改实存，保持允许。
- 定向验证通过：InventoryFlowTest 25、InventoryDualWriteTest 12、OutboxRoutingTest 2、FulfillmentStockIssueConsumerTest 1、InvMirrorEventCoverageTest 1、SchemaDriftTest 2、SchemaGeneratorTest 1；B端type-check通过，库存总览/库存明细15项通过。
- 任务6随后补齐：盘点超过24小时只显示提醒，不自动解冻；详情页明确实物出入库暂停及付款/占用仍可继续，未录行数实时展示且未全部填写前禁止过账；保留确认作废和过账两个恢复路径。B端 `type-check` 及库存相关15项用例通过，模拟器只读加载盘点选品页，未创建/写入盘点。
- Review发现并已修复两项一致性风险：过期任务扫描候选后锁行复核状态，避免释放已付款占用；盘点开单与所有实物过账锁定同一库位行，避免快照截止与过账交叉。相关7组后端定向测试44项通过；没有运行并发压力测试。
- 任务7的持久化退货验收凭证、质量分流，以及任务8的业务链路追溯和人工订单路径仍未完成；本阶段部署和模拟器只读验证不代表整个计划完成。

### 执行记录：盘点一致性修复与部署（2026-10-19）

- 后端提交 `601a5b620` 已部署：`shop-app-20261019-1353-601a5b620.jar`，部署脚本 MD5 一致，服务健康检查 200；公网 `/actuator/health` 为 UP。
- B端提交 `779e7c5e7` 已部署：`/b/VERSION` 返回该提交，入口引用的3个 JS/CSS 资源均为 HTTP 200。
- Android 模拟器 `emulator-5554` 安装并启动 `0.5.82 (315)`；工作台及盘点选品页正常加载，未新建盘点或改变库存。应用进程存活且无 FATAL；模拟器内置 shell 的网络探测未返回健康状态，公网健康检查通过。

- 已实现：只读库存总览接口；区分账面可用与扣安全库存后供给量，保留现有支付扣减语义；店铺与商品归属校验。
- 已实现：按 inventory-mirror 投递状态判断，兼容旧 outbox 状态；失败/处理中/未知的关联事件阻止回写；无法解析的历史事件按SKU或原锁定关联确定影响，未关联的异常保留outbox待处理，不全平台阻断。
- 已实现：共享发货库位阻止独立全量同步，保护启用、初始化、单据回写及手动额度入口。
- 已补充：六屏B端原型 `prototypes/inventory-business-flow.html`，正式B端页面尚未修改。
- 尚未完成：持久化初始化批次、统一操作号入口、完整同步健康投影、共享类型契约、支付占用/履约出库、盘点冻结、验收入库。任务1/2按已交付子项记录，不能视为全部八任务完成。
- 实现调整：安全库存扣减是现有真实行为，不是注释错误；首批保持行为，展示两个口径，避免意外扩大可售量。

### 首批复核修正

- 物料映射存在不证明初始化完成：当前接口使用MAPPED，不返回READY。
- 库存查询在明确校验主体和门店后执行读查询，避免通用门店数据范围过滤误挡主体SKU；商品归属仍由服务端二次确认。
- 无关主体的坏事件不能阻塞全平台；无法解析但可通过原库存锁定位SKU的事件继续阻断对应SKU。
- 延后优化：整店同步的逐SKU未完成事件检查可改为批量读，当前先确保范围与结果正确。

### 首批验证结果

2026-10-19：使用本地JDK21运行 `mvn -f backend/pom.xml -pl shop-app -am test -Dtest=InventoryOverviewQueryTest,InventoryDeliveryGuardTest,InventorySharedSourceTest,StockSyncWritebackTest -Dsurefire.failIfNoSpecifiedTests=false -q`，退出0；19项测试，0失败、0错误。独立review发现的状态误报和异常范围问题已通过先失败再修复的定向用例覆盖。后端diff空白检查通过。

原型六屏在浏览器加载检查，初始化预览及确认反馈已人工点击验证；未连接真实API，正式B端页面尚未开发。原型已登记并更新原型索引。当前改动尚未提交、未部署。

### 原型修订：遵循当前产品组件

按用户要求，将六屏自由布局稿重排为八屏基于当前产品的增量原型。库存保留四格统计、筛选与底部操作面板；明细保留数字格、标识、安全库存、库位与流水；设置保留三块分组；期初对齐保留差异卡及双操作；确认与数量录入分别映射sh-dialog/sh-confirm和sh-prompt，实收数量映射sh-stepper。新增信息进入现有组件，新增样式仅在proto.css的proto-stock范围内按真实组件尺寸缩放。正式B端代码未改动。

修订后8屏ID、控件样式、JavaScript语法及原型索引检查通过。浏览器工具拒绝读取file协议，未对修订稿完成浏览器视觉复核，不沿用前版截图作为修订稿验证证据。

### 后续执行记录：任务7退货验收与退款资源分离（2026-10-19）

- 已新增进销存退货验收凭证与 `V13__return_receipt.sql`：凭证以收货号/行号幂等，来源必须匹配原销售出库及已出库数量；可售商品进入原来源库位，不可售商品进入虚拟隔离库位；验收数量与退款资源恢复分别记账。
- V2商家收货接口逐商品提交实收数量与质量；B端复用现有 `sh-stepper` / `sh-option`，C/B售后详情显示独立的库存处理进度。统一Outbox可按收货号读取投递状态，旧版V1售后仍保留原库存恢复分支。
- 当前售后退款政策仍按整单全额处理，因此本次要求申请退货商品全部收齐后才允许确认退款；缺货/部分实收不能先按全额退款。部分验收后续需要单独实现退款金额分摊与多次收货协议。
- 定向后端用例：`InventoryFlowTest` 26、`RefundShippedResourceFlowTest` 3、`BasicReturnRefundFlowTest` 2、`OutboxUnifiedFlowTest` 4，共35项通过。B端/C端type-check通过，B端售后页面/接口用例10项、C端售后用例2项通过。本段代码尚未部署。
- 任务8追溯接口与两端页面已在本轮补齐；运营端及B端都可按订单号读取预留、来源库位、履约出库、流水前后余额、操作人和退货验收凭证。C端保留买家可见的售后/库存处理进度，不暴露内部库位与成本字段。

### 执行记录：任务8追溯收尾与发布验证（2026-10-19）

- 新增运营端 `GET /ops/inventory/order-trace?orderNo=` 与 B 端授权订单下 `GET /biz/order/{subOrderNo}/inventory-trace`；B端订单详情展示来源库位、预留/出库量、出库凭证、库存流水前后余额、操作人及退货验收去向。运营端账本页可按订单号查询相同事实。
- 后端定向验证：`InventoryFlowTest` 26、`OutboxUnifiedFlowTest` 4、`RefundShippedResourceFlowTest` 3、`BasicReturnRefundFlowTest` 2 均通过（共35）。B端 type-check 通过，售后/订单接口测试8项通过；运维端 typecheck 通过、契约与 OpenAPI parity 10项通过；C端 type-check 通过、售后测试2项通过。
- 仍需真实环境人工验收：快递/自送/自提、退款和V1/V2订单须用指定测试账号及可退款测试订单完整点验。当前模拟器只做商家端只读界面检查，不创建真实订单、不触发付款或库存变更；不将自动化服务端用例冒充人工业务验收。
- 服务端 `759c865e2` 已部署为 `/data/app/ai-shop/shop-app/shop-app-20261019-1424-759c865e2.jar`，MD5 一致，systemd 确认运行该版本，`https://www.hxmall.top/actuator/health` 返回 HTTP 200 / UP。追溯 API 的未认证请求返回 401，符合受保护端点预期。
- B端H5、C端H5与运营端部署版本 `58cccd339`；`/b/VERSION`、`/c/VERSION` 和 `/ops-web/VERSION` 均匹配，入口资源分别3个、3个、18个且全部 HTTP 200。
- APK `虹选商家-0.5.83-316.apk` 通过签名包体检查并安装至 `emulator-5554`；系统包信息为 versionName 0.5.83 / versionCode 316，进程存活且无 FATAL。人工查看工作台、订单列表、已退款订单详情与库存页，页面可加载并显示数据，未创建订单、付款或更改库存。
- 部署提交与APK提交对应：后端 `759c865e2`；前端/Android `58cccd339`。剩余人工验收范围是使用指定 V1/V2 测试订单走完快递、自送、自提、退款及退货验收；当前模拟器账号没有可验证的新V2退货订单，未以线上订单进行有资金/库存副作用的操作。

### 最终复核：服务器与本地Android模拟器（2026-10-19）

- 服务器再次核验：`/actuator/health` 为 `UP`；`/b/VERSION`、`/c/VERSION`、`/ops-web/VERSION` 均为 `58cccd339`。后端部署版本为 `759c865e2`，对应增量迁移 `V13__return_receipt.sql` 已随部署版本发布。
- 本机JDK21定向运行库存总览、写回保护、共享库存、库存同步、库存生命周期、统一Outbox、退款资源恢复、已发货退款、基本退货退款共10个测试类；Surefire报告合计63项，0失败、0错误。B端type-check通过，库存与售后页面定向测试17项通过。
- 检查模拟器时发现之前的 `ai.neargo.shop.b` 是 `0.1.0` 的旧占位应用。已通过 `adb install -r` 安装正确包 `虹选商家-0.5.83-316.apk`（包名 `top.hxmall.bapp`，versionCode 316），显式启动实际入口。工作台、库存列表、订单列表及订单详情均可加载；订单详情显示运单和物流状态；进程持续运行，无 `FATAL EXCEPTION`。此过程仅查看页面，没有提交盘点、支付、发货、退款或库存变更。
- 线上与模拟器均已验证完成可部署范围。业务人工验收尚缺专用测试订单：模拟器当前只有历史订单，无法证明V2订单的快递/自送/自提、退款及退货验收完整闭环；不对现有真实订单执行这些有资金或库存副作用的操作。该项保留为人工验收待办，不以自动化测试或只读页面检查替代。

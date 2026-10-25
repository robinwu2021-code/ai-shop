# 打印中心开发计划：全量开发 → 统一编译 → 集中测试验收

> 执行约定：按用户要求，先完成全部业务代码、测试代码和装配，再统一编译构建，最后集中测试验收。开发任务中的测试内容仅为待执行用例，不要求逐任务红绿测试。本计划不授权发布或生产设备操作。

**Goal:** 独立封装打印业务，打通订单场景小票、隔离模拟、PDF导出及真实云端/本地打印。

**Architecture:** print-api定义合同，print-domain拥有任务/模板/模拟/导出，print-channel适配PDF、私有存储及真实设备；shop-app装配，订单所有者原子写ord_print_fact和Outbox。模拟结果、PDF生成和真实出纸分别记录。

**Tech Stack:** Java 21、Spring Boot 4.0.x、MyBatis、MariaDB/MySQL8、现有Outbox/Job/notify、PDFBox 3.0.8、B端uni-app/Vue3；首个桌面代理Java21＋SQLite＋Windows系统打印队列。

**Spec:** [完整方案与模块封装](../../technical/design/打印中心-完整方案与模块封装.md)，并读取其链接的数据库/API/场景合同。

> Java路径细化：[类结构设计](../../technical/design/打印中心-Java类结构设计.md)与[JSON](../../technical/design/schema/print-java-class-design.json)是类/方法真源。任务职责占位名以该设计统一，不另建同义类。

## 全局约束

- 本文件是待执行开发任务，不代表产品代码已完成；本轮不执行任务、不发布、不接入生产设备。
- 所有候选文件路径以仓库根目录为基准；实施前读取适用AGENTS.md并创建隔离工作区，保留当前未提交更改。
- 固定Java21；Maven从已配置本机仓库解析父工程，PDFBox版本在根dependencyManagement统一锁3.0.8。
- 打印域22张prn_*表；订单所有者1张ord_print_fact；不修改已应用迁移，新增版本号执行时分配。
- 场景与来源、模板变体、模板版本、布局/字体版本均须校验并冻结；未知结果不盲重试。
- 模拟PASSED和PDF READY均不代表设备出纸，不改订单/支付/履约，不发送真实完成通知。
- 出票金额使用子单所有者已分摊事实；不能重算资金账或把整单总额复制到各店。
- 文件默认7天，元数据180天，下载授权60秒；模拟最多10份/500事件/3MiB/30秒；导出50票/200页/20MiB。
- 实机品牌、首个桌面系统为设计者默认建议，替换只改变相应adapter任务；首次实现先Windows，Android原生执行端后续接正式uni包。

## 评审重点

1. PRT-08/09：同笔支付跨店/重复回调/免支付，事实仍精确且只生成一套票；事务回滚不能留孤儿事实。
2. PRT-03/06：超长商品名、混合单位、字体缺字、500行长票及多页系统警示，不能静默截断或错金额。
3. PRT-05/10：真实订单模拟与恶意伪造模拟回执，真实设备调用和生产任务更新必须为0。
4. PRT-07/10：上传后崩溃、授权撤销和短链过期，恢复幂等且未经当前授权不可导出。
5. PRT-04/13/14：发包后崩溃、迟到取消及迟到回执，不能重印、误取消下一票或清除新任务闸门。

## 文件结构与任务依赖

业务源码按template/layout/service/dispatch/simulation/export/persistence分包，各包单一职责。控制器留在shop-app/portal。16项任务全部归入阶段A。M1–M5仅表示代码组织批次，不是中途测试、验收或上线节点。阶段A不运行产品测试、不逐项编译；阶段B统一编译全部模块和测试源码；阶段C开展集中验收。PRT-15须列明每个场景的来源实现与外部依赖，未实现来源不能计为代码完成。


## 执行阶段与准入条件

| 阶段 | 工作内容 | 退出条件 |
|---|---|---|
| A 全量代码开发 | PRT-01～16，业务代码、测试代码、迁移、前端、云通道、本地代理、安装配置及全部装配 | 任务代码清单全部完成；无占位成功实现；用例与资源清单齐备；仅声明“代码完成，未验证” |
| B 统一编译构建 | 后端全反应堆及测试源码、B端类型检查与H5构建、Windows代理安装包 | 所有规定构建通过；记录版本与构建产物；测试尚未执行 |
| C 集中测试验收 | 按T01～T10统一执行，问题修复与回归仍属于本轮验收 | 必需用例通过且证据完整；阻塞项清零；人工验收结论可核对 |

开发顺序为PRT-01→02→03→04→05→06→07→08→09→10→11→12→13→14→15→16。依赖表允许调整同批次编码先后，但不得提前宣告子功能验收通过。开发阶段允许阅读代码、核对合同、静态审查和准备环境；不把运行测试或编译设为任务完成的前置步骤。

阶段A开始时登记资源负责人及到位时间：Java21/Maven依赖、MariaDB与MySQL8隔离库、私有对象存储、供应商账号/回调测试条件、Windows构建机、真实打印机及纸张。缺少环境可以继续不依赖它的编码，但不得用模拟结果代替实机验收；代码本身缺少必要的来源合同或供应商协议时，对应任务保持阻塞，不用空实现填满清单。

测试代码随对应任务业务代码之后编写，统一到PRT-16完成跨模块夹具和测试入口；本阶段不执行。代码提交可以按任务组织，标明“待统一构建和验收”，提交本身不代表验证通过。

## PRT-01 · 独立模块与公开合同

阶段：M1；前置：无。

**文件：**

- 新增 backend/print/pom.xml、print-api/pom.xml、print-domain/pom.xml、print-channel/pom.xml
- 修改 backend/pom.xml、backend/shop-core/pom.xml、backend/shop-app/pom.xml
- 新增 backend/print/print-api/src/main/java/ai/neargo/shop/print/api/{PrintFacade,PrintSourceFactsPort,PrintSimulationFacade,PrintExportFacade,PrintDispatchPort,PrintPdfPort,PrintArtifactStorePort,PrintFontMetricsPort}.java
- 新增同包 PrintActor、PrintFactRef、PrintFact、PrintIntent、PrintDocument、PrintLayout、PrintSubmission、PrintJobRef、PrintBatchRef、PrintOperation 及总方案2.1列出的辅助record文件
- 新增 backend/shop-app/src/test/java/ai/neargo/shop/arch/PrintBoundaryTest.java；修改同目录ArchitectureTest.java

**接口：** 消费：shop-base公共类型；产出：总方案2.1全部公开签名，后续任务只实现这些合同。

- [ ] **1. 完成本任务代码。** 独立业务域包ai.neargo.shop.print；保持模块化单体，注册ArchitectureTest域名print，既有跨域闸门仅放行ai.neargo.shop.print.api..契约包；不创建第二父工程或独立服务。

- [ ] **2. 准备测试源码与夹具，暂不执行。** PrintBoundaryTest.noReverseBusinessDependency：print-domain不能导入trade/pay/行业实现/通道SDK；print-api无Mapper/Controller；shop-core不导入print-domain；通道不能调用订单Mapper。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `mvn -f backend/pom.xml -pl shop-app -am -Dtest=PrintBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## PRT-02 · 数据、幂等与布局文件记账

阶段：M1；前置：PRT-01。

**文件：**

- 新增 backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/下22实体，persistence/mapper/PrintMappers.java内22个BaseMapper
- 新增同模块 persistence/entity/PrnRow.java、persistence/mapper/PrintMappers.java、operation/PrintOperationService.java及operation/impl/PrintOperationServiceImpl.java
- 新增 backend/print/print-domain/src/test/java/ai/neargo/shop/print/persistence/PrintPersistenceContractTest.java
- 新增 backend/shop-app/src/main/resources/db/migration/ 的打印建表迁移；版本由执行时最新Flyway版本递增分配，不硬写可能冲突编号

**接口：** 消费：PRT-01合同与print-center-schema.sql；产出：PrintOperationService.execute(PrintActor,String commandType,String requestHash,Supplier<PrintOperation>)及22表仓储。

- [ ] **1. 完成本任务代码。** 以DDL为真源，CAS revision和门店stream锁落实；原始id不外露；测试包含布局快照/outputOptions和模拟、导出各自独立记录。

- [ ] **2. 准备测试源码与夹具，暂不执行。** PrintPersistenceContractTest.sameKeySameCommandReturnsOriginal、sameKeyDifferentCommandRejects、copyKeyRejectsDuplicate、streamSequenceCannotSkipLateCommit、exportRegisteredBeforePut；在MariaDB和MySQL8隔离库各执行迁移。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `mvn -f backend/pom.xml -pl print/print-domain -am -Dtest=PrintPersistenceContractTest -Dsurefire.failIfNoSpecifiedTests=false test`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## PRT-03 · 场景模板、首张小票与共用布局

阶段：M1；前置：PRT-01,PRT-02。

**文件：**

- 新增 backend/print/print-domain/src/main/java/ai/neargo/shop/print/template/{PrintSceneRegistry,PrintTemplateService,DefaultPrintTemplateResolver,DefaultPrintDocumentCompiler}.java
- 新增同模块 layout/{DefaultPrintLayoutEngine,LayoutProfileRegistry}.java；新增print-channel的font/{PdfBoxFontMetrics,PdfFontBundle}.java及字体TTF/许可证/hash；根POM锁PDFBox3.0.8
- 新增同模块 src/main/resources/print/{print-scene-catalog.json,print-template-definitions.json,print-scene-examples.json}及种子发布加载器
- 新增同模块 src/test/java/ai/neargo/shop/print/template/ReceiptTemplateContractTest.java 与 layout/ReceiptLayoutTest.java；新增print-channel/src/test/java/ai/neargo/shop/print/channel/font/PrintFontMetricsTest.java

**接口：** 消费：PrintFact、22表配置仓储；产出：resolve(TemplateResolutionCommand)、compile(PrintFact,ResolvedTemplate,SystemPrintSlots)、layout(PrintDocument,LayoutProfile)。

- [ ] **1. 完成本任务代码。** 本任务提供PrintSourceRegistry（允许空集合）、PrintFontMetricsPort与同字体hash的channel实现，再落地白名单DSL与omitIfEmpty；首票ORDER_PAID同时覆盖免支付0金额事实项；ROLL80_V1左右4mm/宽72mm、ROLL58_V1左右4mm/宽50mm，联高≤270mm；保存可复用PrintLayout，不让PDF自行选择新版模板。

- [ ] **2. 准备测试源码与夹具，暂不执行。** ReceiptTemplateContractTest.orderPaid80HasCorrectTotals：36+64-12+0=88；mixedUnitDoesNotClaimThreePieces；prebillCannotUsePaidTemplate；pinnedFailureBlocks；requiredNoticeCannotBeHidden；ReceiptLayoutTest.long500LinesNoLoss、everyPageRetainsSystemWarnings；PrintFontMetricsTest.sameFontHashAndAdvanceAsPdfGlyph、missingGlyphReported；参数化20场景×58/80mm。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `mvn -f backend/pom.xml -pl print/print-domain,print/print-channel -am -Dtest=ReceiptTemplateContractTest,ReceiptLayoutTest,PrintFontMetricsTest -Dsurefire.failIfNoSpecifiedTests=false test`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## PRT-04 · 需求生成、设备配置与任务生命周期

阶段：M1；前置：PRT-02,PRT-03。

**文件：**

- 新增 backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/{DefaultPrintFacade,PrintDeviceServiceImpl,PrintRequestServiceImpl,PrintReprintServiceImpl}.java
- 新增同模块 dispatch/PrintStatePolicy.java及dispatch/impl/{PrintDispatchServiceImpl,PrintReceiptServiceImpl,PrinterLeaseServiceImpl}.java；对应接口在dispatch；PRT-04同时提供PrintAdapterRegistry及基础PrintIncidentService/Impl
- 新增同模块 src/test/java/ai/neargo/shop/print/dispatch/PrintLifecycleTest.java

**接口：** 消费：来源Port、模板/布局、仓储和PrintDispatchPort；产出：PrintFacade、submit/reprint/extra/cancel/query/confirm命令与不可变job布局快照。

- [ ] **1. 完成本任务代码。** 网络调用前写attempt；未知不清闸门；一机一未决；模拟不注册生产adapter；request冻结outputOptions，伴随导出PENDING记录由事务内事件驱动，实际PDF执行留PRT-07。

- [ ] **2. 准备测试源码与夹具，暂不执行。** PrintLifecycleTest.replayedFactCreatesOneBatch、existingFactManualReturnsOriginal、unknownNeverBlindRetries、lateReceiptCannotClearNextLease、cancelNeedsQueueTermination、crossRequestReprintCreatesAtomicGroupedBatches、replacementDoesNotRewriteOriginalFailure。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `mvn -f backend/pom.xml -pl print/print-domain -am -Dtest=PrintLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false test`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## PRT-05 · 独立模拟执行及异常fixture

阶段：M2；前置：PRT-03,PRT-04。

**文件：**

- 新增simulation/impl/DefaultPrintSimulationFacade.java及simulation/{SimulationRunner,SimulatedDispatchPort,SimulationFixtures}.java
- 新增同模块 src/test/java/ai/neargo/shop/print/simulation/PrintSimulationIsolationTest.java

**接口：** 消费：模板编译/布局与纯PrintStatePolicy；产出：PrintSimulationFacade.create/get、PRT-06/07消费的冻结模拟文档集合。

- [ ] **1. 完成本任务代码。** 9种fixture见总方案，虚拟时钟，10份文档/500事件/3MiB/30秒；模拟仓储与生产任务仓储分离；允许新增模拟、operation、action_log记录，生产request/batch/job/attempt/receipt/runtime和订单不变；fixtureVersion冻结；不得调用PrintReceiptService写生产结果。

- [ ] **2. 准备测试源码与夹具，暂不执行。** PrintSimulationIsolationTest.nineFixturesMatchExpected、unknownCanPassButIsNotPrinted、realDispatchCallCountIsZero、productionRequestBatchJobAttemptReceiptRuntimeAndOrderUnchanged、restartReplaysSameInputOnce、simulationWatermarkOnEveryPage。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `mvn -f backend/pom.xml -pl print/print-domain -am -Dtest=PrintSimulationIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## PRT-06 · PDF渲染与视觉验收

阶段：M2；前置：PRT-03,PRT-05。

**文件：**

- 复用PRT-03已锁定的org.apache.pdfbox:pdfbox:3.0.8，不再另设版本
- 新增 backend/print/print-channel/src/main/java/ai/neargo/shop/print/channel/pdf/PdfReceiptRenderer.java；复用channel/font/PdfFontBundle.java
- 复用PRT-03的print-channel字体包、许可证及hash清单；补充缺字/字体嵌入回归
- 新增同模块src/test/java/ai/neargo/shop/print/channel/pdf/ReceiptPdfTest.java

**接口：** 消费：PrintLayout/PdfRenderInput；产出：PrintPdfPort.render返回PdfArtifact(bytes,contentHash,pageCount,widthsMm,rendererVersion,fontBundleVersion)。

- [ ] **1. 完成本任务代码。** 仅绘制冻结布局，不查订单和模板；固定PDF元数据时间/ID和字体版本，保证同export输入重试字节一致；ORIGINAL_ROLL/A4_SHEET两种输出，单联≤270mm，最多200页/20MiB。产出首张80mm小票及58mm/长单回归样张。

- [ ] **2. 准备测试源码与夹具，暂不执行。** ReceiptPdfTest.correct58And80PageWidth、allChineseAndMoneyExtractable、longReceiptNoLostLines、a4KeepsPhysicalScale、sameExportInputHasStableHash、missingGlyphBlocks、simulationAndExportMarksOnEveryPage；用PDFBox渲染测试PDF为PNG逐页视觉检查。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `mvn -f backend/pom.xml -pl print/print-channel -am -Dtest=ReceiptPdfTest -Dsurefire.failIfNoSpecifiedTests=false test`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## PRT-07 · 异步导出、私有存储与伴随打印

阶段：M2；前置：PRT-02,PRT-04,PRT-06。

**文件：**

- 新增 backend/print/print-domain/src/main/java/ai/neargo/shop/print/export/{DefaultPrintExportFacade,PrintExportWorker,PrintExportRecoveryJob,PrintExportCleanupJob}.java
- 新增 backend/print/print-channel/src/main/java/ai/neargo/shop/print/channel/artifact/{LocalPrintArtifactStore,CosPrintArtifactStore}.java
- 新增 backend/print/print-domain/src/test/java/ai/neargo/shop/print/export/PrintExportRecoveryTest.java；新增shop-app/printbridge/PrintArtifactJobHandlers.java、PrintArtifactWakeConsumer.java和PrintArtifactFlowTest.java，注册模拟/PDF唤醒、恢复、清理

**接口：** 消费：PrintPdfPort、PrintArtifactStorePort、冻结job/batch/simulation；产出：PrintExportFacade与下载授权；存储Port额外提供ArtifactVerification verify(String objectKey)。

- [ ] **1. 完成本任务代码。** 本任务即完成Job/Outbox装配，模拟/PDF无需真实通道即可从PENDING推进；PENDING预登记固定key，freeze source/profile/font，私有原子写后hash确认READY；PDF重试最多3次、退避5/30/120秒；文件7天、元数据180天、下载60秒；每次下载重新鉴权；本任务另跑 `mvn -f backend/pom.xml -pl shop-app -am -Dtest=PrintArtifactFlowTest -Dsurefire.failIfNoSpecifiedTests=false test` 验证无真实adapter也推进后台记录；伴随导出最多一批一个export，key按batch/profile/文档集合hash幂等。

- [ ] **2. 准备测试源码与夹具，暂不执行。** PrintExportRecoveryTest.metadataBeforeBytes、crashAfterUploadRecoversByHash、staleWorkerCannotChangeArtifact、expiredOrRevokedDownloadRejected、simulationCannotExportAsLive、pdfFailureDoesNotReprint、readyDoesNotCompletePrintJob；PrintArtifactFlowTest.enqueuedSimulationAndPdfRunWithoutAnyRealAdapter、restartResumesPendingAndCleanupRuns。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `mvn -f backend/pom.xml -pl print/print-domain -am -Dtest=PrintExportRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## PRT-08 · 订单所有者的不可变打印事实

阶段：M2；前置：PRT-01。

**文件：**

- 新增 backend/shop-core/src/main/java/ai/neargo/shop/trade/order/printing/{OrderPrintFact,OrderPrintFactMapper,OrderPrintFactRecorder}.java；printing/service/OrderPrintFactService.java及service/impl/OrderPrintFactServiceImpl.java
- 新增 backend/shop-core/src/test/java/ai/neargo/shop/trade/order/printing/OrderPrintFactTest.java
- 新增 backend/shop-app/src/main/resources/db/migration/ 的ord_print_fact迁移，按执行时可用版本编号

**接口：** 消费：可信PaymentLifecycleService.ConfirmedPayment(channel,tradeNo)和现有订单/子单/行/支付分摊合同；产出：recordPaid(String orderNo,ConfirmedPayment confirmed):List<PrintFactRef>、snapshot(PrintFactRef):PrintFact、scan(PrintFactCursor,int):PrintFactPage、executionGuard(PrintFactRef):PrintExecutionGuard。

- [ ] **1. 完成本任务代码。** 只写ord_print_fact；快照不依赖渲染器。sourceFactKey由最终支付确认与子单规范身份构造；子单金额/优惠/配送费/混合收款必须取所有者已定分摊结果，不能打印模块重算；业务不支持时明确写printability=BLOCKED、blockCode及diagnostics，仍提交事实和Ready事件；消费者落BLOCKED，不能抛业务不支持异常回滚支付。仅落库/Outbox持久化故障回滚；不提供改写原始冻结事实的自动修复。

- [ ] **2. 准备测试源码与夹具，暂不执行。** OrderPrintFactTest.eachStoreGetsOnlyOwnLinesAndAllocatedAmounts、receiptIntentUniqueAcrossScenes、repeatedRecordKeepsOriginalSnapshot、noCashHasNoFakeTradeNo、unsupportedAmountComponentsPersistBlockedFactAndStillCommitReadyEvent。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `mvn -f backend/pom.xml -pl shop-core -am -Dtest=OrderPrintFactTest -Dsurefire.failIfNoSpecifiedTests=false test`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## PRT-09 · 支付事务及Outbox订单接线

阶段：M2；前置：PRT-04,PRT-07,PRT-08。

**文件：**

- 修改 backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java 的markPaidSource及cancel/cancelByPlatform；修改trade/order/refund/service/impl/RefundResultServiceImpl.java的退款确认持久化处
- 新增 backend/print/print-api/src/main/java/ai/neargo/shop/print/api/{OrderPrintFactReady,OrderPrintControlChanged}.java
- 新增shop-app/printbridge/port/OrderPrintSourceFactsPort.java及printbridge/{PrintFactReadyConsumer,PrintControlChangedConsumer,PrintBridgeConfiguration}.java
- 修改 backend/shop-app/src/main/java/ai/neargo/shop/config/OutboxEventRoutes.java 仅在现有规则需要时补显式路由
- 新增 backend/shop-app/src/test/java/ai/neargo/shop/scenario/OrderPrintFactFlowTest.java

**接口：** 消费：PRT-08事实服务与PRT-04 PrintFacade.onFact；产出：eventType=ORDER_PRINT_FACT_READY、handlerId=print-fact-ready、PLATFORM/ORDER:<orderNo>流；ORDER_PRINT_CONTROL_CHANGED及print-control-changed消费者。

- [ ] **1. 完成本任务代码。** 订单事务只冻结事实和发事件，print消费者通过Port校验hash再提交；保留原OrderPaid/SubOrderPaid；历史缺事实只能可靠回填或BLOCKED，不消费时临时读当前数据。取消/退款控制事件与原业务事务一起提交，generation/dispatch双重守卫；历史付款收据允许重现，作业票按取消/冲销/UNKNOWN裁决。补跑PRT-07的异步集成测试验证无真实adapter仍能输出PDF。

- [ ] **2. 准备测试源码与夹具，暂不执行。** OrderPrintFactFlowTest.rollbackCreatesNoFactOrReadyEvent、sameTransactionFreezesFacts、legacyPaidEventsDoNotDoublePrint、replayedReadyAndScanHitSameRequest、printerOrPdfOutageCannotRollbackPaidOrder、noRouteStillAllowsFactSimulation、cancelBeforeGenerateAndBeforeDispatchBlocksExecution、latePaidFactCannotRestoreCancelledExecution、refundedHistoricalReceiptStillAllowed、orderToPdfRunsWithNoCloudOrLocalAdapter。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `mvn -f backend/pom.xml -pl shop-app -am -Dtest=OrderPrintFactFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## PRT-10 · 商家API、权限与模拟导出合同

阶段：M3；前置：PRT-04,PRT-05,PRT-07,PRT-09。

**文件：**

- 新增shop-app/portal/biz下11个BizPrint*Controller，名单/83端点签名以Java结构JSON为准；另有ops/device/callback三个入口
- 修改 backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java 与统一权限/错误码注册点
- 新增 backend/shop-app/src/test/java/ai/neargo/shop/portal/biz/PrintApiScopeTest.java

**接口：** 消费：独立Facade与service接口；产出：API设计P/A/C/J/M商家接口及S01–03/E01–04；尚未接入的真实设备动作显式不可用。

- [ ] **1. 完成本任务代码。** Controller只做上下文/DTO转换，统一封装和PageData；sourceRefs不可提升主体；新增simulate/export权限；POST下载授权禁止缓存，不泄露objectKey/签名URL至日志。

- [ ] **2. 准备测试源码与夹具，暂不执行。** PrintApiScopeTest.crossStoreReferenceDenied、viewDoesNotGrantExport、sameKeyReplaysOperation、sameKeyDifferentPayloadFails、simulationCannotBecomeProductionReceipt、downloadRequiresCurrentSourceReadAndFieldPermissions、pdfCreateDoesNotCreatePrintJob。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `mvn -f backend/pom.xml -pl shop-app -am -Dtest=PrintApiScopeTest -Dsurefire.failIfNoSpecifiedTests=false test`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## PRT-11 · B端打印中心与订单入口

阶段：M3；前置：PRT-10。

**文件：**

- 新增 b-app/src/api/print.ts、b-app/src/shared/print-view.ts
- 新增 b-app/src/pages/print-center/index.vue、devices.vue、simulations.vue、exports.vue
- 修改 b-app/src/pages/order/index.vue、pages/orders/index.vue、pages.json及API契约/端点注册
- 新增 b-app/tests/print-center.test.ts 与 print-order-entry.test.ts

**接口：** 消费：J11摘要、S01–03模拟、E01–04导出及任务API；产出：设备/任务/模拟/PDF管理和订单详情入口。

- [ ] **1. 完成本任务代码。** 订单详情显示真实打印摘要、PDF状态和模拟入口；无生产路由仍允许授权模拟；App通过受控下载/分享能力打开PDF，H5下载需跨浏览器验证；所有新增文案走现有i18n，不能硬编码中文进入多语言页面。

- [ ] **2. 准备测试源码与夹具，暂不执行。** print-order-entry.test.ts：预览/模拟/真实打印按钮分开；模拟PASSED不显示已出纸；PDF READY不推进订单；PDF失败不提示补打；无权限无导出；免支付/混合支付金额按服务端显示。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `npm run test -w ai-shop-b-app -- print-center.test.ts print-order-entry.test.ts`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## PRT-12 · 首家云打印适配器

阶段：M4；前置：PRT-04,PRT-10。

**文件：**

- 新增 backend/print/print-channel/src/main/java/ai/neargo/shop/print/channel/feie/{FeiePrintAdapter,FeieResponseMapper,FeieCallbackVerifier}.java
- 新增 backend/shop-app/src/main/java/ai/neargo/shop/portal/callback/PrintCallbackController.java
- 修改 ApiResponseWrapper 对已登记打印回调精确放行；新增PrintChannelConfiguration
- 新增 backend/print/print-channel/src/test/java/ai/neargo/shop/print/channel/feie/FeiePrintContractTest.java

**接口：** 消费：PrintDispatchPort、receipt入口、凭据版本；产出：默认候选FEIE适配器与可信provider结果映射。

- [ ] **1. 完成本任务代码。** 飞鹅为设计者默认首家候选，可由实际设备替换此任务而不改domain。按当前厂商正式合同确定是否有逐单回调，若没有则仅主动查询，不伪造webhook；厂商ACK原样输出；配置默认关闭，无凭据不得stub成功。

- [ ] **2. 准备测试源码与夹具，暂不执行。** FeiePrintContractTest.submitIdIsNotCompleted、falseQueryIsStillQueued、timeoutWithoutIdIsUnknown、oldCredentialCanQueryExistingAttempt、wrongSignatureRejected、unmatchedEarlyReceiptPersisted；再真机核对中文/切纸/缺纸。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `mvn -f backend/pom.xml -pl print/print-channel -am -Dtest=FeiePrintContractTest -Dsurefire.failIfNoSpecifiedTests=false test`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## PRT-13 · 本地代理协议与首个桌面执行端

阶段：M4；前置：PRT-04,PRT-06,PRT-10。

**文件：**

- 修改 backend/print/pom.xml登记print-agent，不添加shop-app到agent的依赖
- 新增 backend/print/print-agent/pom.xml 与src/main/java/ai/neargo/shop/print/agent/下AgentMain、四个本机Port及实现、AgentExecutionLoop、AgentRecoveryCoordinator、AgentModels，签名以Java清单为准
- 新增DevicePrintController、PrintAgentAuthenticationFilter、API PrintAgentAuthenticationService及domain Impl；D01–D10实现
- 新增 backend/print/print-agent/src/test/java/ai/neargo/shop/print/agent/AgentRecoveryTest.java
- 新增 backend/print/print-agent/packaging/Windows安装/启动配置及sqlite迁移

**接口：** 消费：D01–D10与PrintLayout；产出：Java21常驻桌面代理，SQLite持久化，首发Windows系统打印队列；应用不依赖代理artifact。

- [ ] **1. 完成本任务代码。** 代理模块只依赖print-api、HTTP/SQLite/PDF/本地系统库，不依赖domain/业务库。Java Print Service按PrintLayout/相同字体hash通过Graphics2D绘制系统队列；系统队列“已接收”仅ACCEPTED，无法确认出纸时需人工确认。凭据使用Windows受保护凭据存储，不写明文配置；SQLite/JDBC库实施时锁定版本并做依赖审查。Android正式uni插件作为后续执行端，不改预览android-shell冒充交付。

- [ ] **2. 准备测试源码与夹具，暂不执行。** AgentRecoveryTest.persistBeforeAck、restartSubmittingBecomesUnknown、oldSessionCannotExecute、lateCancelTargetsOriginalAttempt、leaseExpiredCannotStartNewSend、spoolAcceptedIsNotPhysicalCompletion；Windows真实驱动队列验证USB/网口设备。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `mvn -f backend/pom.xml -pl print/print-agent -am -Dtest=AgentRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## PRT-14 · 对账巡检、异常通知及文件恢复调度

阶段：M4；前置：PRT-07,PRT-09,PRT-12,PRT-13。

**文件：**

- 新增 backend/print/print-domain/src/main/java/ai/neargo/shop/print/job/{PrintReconcileJob,PrintIncidentService,PrintNotificationEvents}.java
- 新增 backend/shop-app/src/main/java/ai/neargo/shop/printbridge/{PrintJobHandlers,PrintNotifyBridge}.java
- 新增 backend/shop-app/src/test/java/ai/neargo/shop/scenario/PrintReconcileNotifyFlowTest.java

**接口：** 消费：来源scan、receipt、PRT-07已装配的模拟/PDF恢复/清理服务、现有Job与notify；产出：业务→需求→任务→结果对账及独立文件/模拟维护Job。

- [ ] **1. 完成本任务代码。** 复用PRT-07现有Job装配，新增真实设备巡检与Outbox通知，不把首次模拟/PDF调度推迟到本任务；未知不自动补打；回填超过保留边界拒绝自动修复；首票/补打解决/故障恢复文案区分；PDF重试超限单独incident。

- [ ] **2. 准备测试源码与夹具，暂不执行。** PrintReconcileNotifyFlowTest.missingRequestRecoveredOnce、oldPolicyDoesNotRetroPrint、cursorOnlyAdvancesAfterDurableWrite、replacementHasDistinctCompletionCopy、notificationFailureDoesNotReprint、pdfExpiredCleanedWithoutTouchingReceipt、simulationHasNoProductionNotification。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `mvn -f backend/pom.xml -pl shop-app -am -Dtest=PrintReconcileNotifyFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## PRT-15 · 其余场景的来源分批接线

阶段：M5；前置：PRT-03,PRT-09,PRT-14。

**文件：**

- 新增各来源所有者的PrintSourceFactsPort实现/适配，例如backend-plugins/shop-industry-food/内printing包及正式美业模块的对应包
- 扩展 backend/shop-app/src/main/java/ai/neargo/shop/printbridge/PrintSourceRegistry.java
- 新增 backend/shop-app/src/test/java/ai/neargo/shop/scenario/PrintSceneSourceContractTest.java

**接口：** 消费：20场景注册目录与各所有者的不可变事实；产出：已具备来源的场景逐个启用，未具备合同者保持禁用并可预览。

- [ ] **1. 完成本任务代码。** 按来源能力交付：共用收银/退款/储值→餐饮下达/加退催/预结/结账→美业预约/开完工/耗次→交班日结。各场景需正式来源守恒测试及门店开关才能启用；不通过复制当前表查询补造历史事实。

- [ ] **2. 准备测试源码与夹具，暂不执行。** PrintSceneSourceContractTest.addOnlyNewLines、voidUsesOriginalStation、urgeDoesNotCreateProductionQuantity、prebillNotPaid、cardBalanceUsesHistoricalPostBalance、shiftUsesSealedReport、onlyOneDefaultReceiptIntent。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `mvn -f backend/pom.xml -pl shop-app -am -Dtest=PrintSceneSourceContractTest -Dsurefire.failIfNoSpecifiedTests=false test`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## PRT-16 · 全量装配、测试代码与验收材料准备

阶段：M5；前置：PRT-11,PRT-12,PRT-13,PRT-14,PRT-15。

**文件：**

- 新增 docs/technical/打印中心-联调与发布记录.md
- 新增 backend/shop-app/src/test/java/ai/neargo/shop/scenario/OrderPrintEndToEndTest.java
- 新增 scripts/verify-print-fixtures.py 与其检查入口；维护docs/api/backend-ui-delivery.json登记实际完成范围

**接口：** 消费：前15项交付；产出：完整启动装配、端到端测试源码、统一测试入口、验收记录模板与开关说明；PDF/PNG和实测证据在阶段C生成。

- [ ] **1. 完成本任务代码。** 补齐所有模块、worker、接口、前端与代理的配置接线及测试夹具；准备静态合同、模拟、PDF视觉、供应商沙箱、真实硬件证据的独立记录模板。默认关闭自动真实打印，编写后续授权灰度与回退说明；本任务不启动验收或发布，也不批量补印历史订单。

- [ ] **2. 准备测试源码与夹具，暂不执行。** OrderPrintEndToEndTest：支付→事实→模板→生产任务→PDF，模拟完全独立；云/本地故障恢复不误完成；跨店与文件下载安全；20场景模板守恒。

- [ ] **3. 核对装配与交付清单。** 完成配置、依赖、权限、迁移及文档登记；移除占位实现。记录未解决依赖，代码状态与验证状态分列。

**阶段C待运行的定向测试入口：** `mvn -f backend/pom.xml -pl shop-app -am -Dtest=OrderPrintEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false test`。此命令仅作为集中验收及失败后的定向回归入口，不在阶段A执行。

## 阶段B：统一编译构建

仅在PRT-01～16代码清单完成后开始。先冻结代码版本，记录提交号、JDK、Node及依赖锁文件版本。

- [ ] 后端全模块构建：`mvn -f backend/pom.xml -DskipTests -DskipITs package`。print聚合及print-agent必须已纳入根反应堆；包含与根工程分离的行业插件时，补充其正式构建入口并登记。
- [ ] 保留测试源码编译，禁止用`-Dmaven.test.skip=true`绕过。核对Surefire/Failsafe和自定义插件配置，保证本阶段不执行测试；实际参数以仓库配置为准。
- [ ] B端统一类型检查：`npm run type-check -w ai-shop-b-app`；构建：`npm run build:h5 -w ai-shop-b-app`。不把H5构建通过当作页面交互通过。
- [ ] Windows构建机生成代理可执行安装包，打包运行时、字体、SQLite迁移及受保护凭据配置；打包通过不代表真实队列已验证。
- [ ] 修复全部编译、依赖、资源及打包错误后，重新完成规定构建。输出后端包、前端包、代理包及hash，形成阶段C候选版本。

不将“后端部分模块编译通过”视作统一构建完成。构建失败时不进入集中验收。

## 阶段C：集中测试与一次性验收

“一次性”指全部功能进入同一个验收批次，不要求所有问题只能运行一次，也不限制修复后的必要回归。测试环境使用隔离数据和授权测试设备，完整记录执行命令、版本、结果、证据和问题单。

| 编号 | 测试组 | 对应任务 | 必需证据 |
|---|---|---|---|
| T01 | 架构、实体、Mapper、接口合同与自动化单测 | 01～04、10 | 无反向业务依赖；23实体匹配DDL；22嵌套Mapper；83端点与权限映射；状态机及幂等通过 |
| T02 | 数据库迁移、并发、租约及操作幂等 | 02、04、08 | MariaDB/MySQL8隔离迁移；唯一键、CAS、闸门、并发重放与事务回滚证据 |
| T03 | 订单端到端与来源事实 | 08、09、15、16 | 跨店分摊、免支付、重复事件、取消/退款控制、漏建恢复；正式来源逐场景核对 |
| T04 | 场景模板与PDF视觉 | 03、06、07、15 | 20场景×58/80mm；首票88元；数量合计3；中文、缺字、500行、分页及警示；原宽/A4逐页检查 |
| T05 | 隔离模拟 | 05、16 | 9类fixture及重启重演；真实设备调用为0；生产任务与完成通知不被改写 |
| T06 | PDF文件与权限安全 | 07、10 | 上传崩溃恢复、hash一致、超限、7天过期/清理、60秒下载授权、撤权与跨租户拒绝 |
| T07 | 云打印实机与故障恢复 | 12、14 | 供应商沙箱与实机分别记录；离线、缺纸、超时、未知、重复/乱序回执、明确完成证据 |
| T08 | 本地代理实机与故障恢复 | 13、14 | Windows安装/升级/重启、SQLite持久化、旧session、精确取消、凭据轮换；队列接收与实际完成区分 |
| T09 | 对账、补打、巡检与通知 | 04、14 | 不打印/漏打处理、UNKNOWN不盲重试、原票与补票分离、通知失败不重打、完成通知去重 |
| T10 | B端交互与整体验收 | 10、11、16 | 订单入口、设备/模板/任务管理、模拟与PDF下载、权限拒绝、异常提示；交付矩阵与操作文档 |

执行顺序：T01→T02→T03→T04→T05→T06→T07→T08→T09→T10。各任务保留的定向测试入口在本阶段使用。后端基线以全反应堆`mvn -f backend/pom.xml verify`执行，并确认集成测试插件实际纳入相应测试；B端执行正式测试入口及交互验收，行业插件使用其正式测试入口。不得仅靠`-pl shop-app -am`漏掉未被应用依赖的print-agent测试。

补充自动化用例：PrnEntityMappingTest、PrintMapperWiringTest、PrintApiClassAlignmentTest；把23实体、22 Mapper和83端点核对纳入T01。按生产接线方式启动，检验空来源/空adapter集合、模拟/PDF worker和恢复/清理Job；不只在单测中手工new对象。

失败处理：登记问题→修复代码→重新构建受影响产物→定向回归→跨模块影响回归。最终候选版本重新完成统一构建、自动化基线及受影响场景验收；设备/布局/权限代码有变更时重做对应实机、视觉或安全验收。禁止通过删除用例、降低断言或跳过失败项获取通过结果。

结果统一为PASS / FAIL / BLOCKED / NOT_RUN。供应商凭据、正式来源或真实打印机未到位时，相关项保持BLOCKED/NOT_RUN，不能用模拟、PDF或队列接收证明真实出纸完成，也不能宣布整体验收通过。

## 最终交付与完成条件

- [ ] PRT-01～16代码与测试源码全部提交，原有无关修改不混入；无未登记的占位/未接线功能。
- [ ] 阶段B全部构建通过，实际版本与阶段C证据对应。
- [ ] T01～T10必需项通过；影响金额、重复/漏打、权限、数据恢复及完成真实性的问题全部解决。
- [ ] 20场景逐项登记模板、来源、开关和验证状态。来源业务尚不存在时如实列为依赖阻塞，不能以模板可预览宣称生产场景已完成；需要缩减范围时另行明确范围决定。
- [ ] 交付源码、迁移、构建包、代理安装包、PDF/PNG样张、测试报告、缺陷闭环、部署配置与回退说明。
- [ ] 汇总验收结论供用户一次性核对。发布/生产灰度另行安排，不因开发验收完成自动部署。

本次只调整开发计划；尚未开始产品代码开发、编译或测试。

## 执行记录（2026-10-23）

本轮按 A→B→C 执行。实现清单以 `docs/technical/design/schema/print-implementation-inventory.json` 为准，测试合并到集中用例，原分任务测试名称不再逐个生成空壳类。

| 计划项 | 实现情况 | 验收说明 |
| --- | --- | --- |
| PRT-01～11 | 公共合同、数据表、配置/模板/路由、冻结文档与任务、生命周期、模拟/PDF、订单支付来源、HTTP 与商家端已实现 | 统一编译和软件定向验收已执行；最终全量结果见验收记录 |
| PRT-12 | 飞鹅渠道、凭据冻结/轮换、探测/查询与失败分类已实现 | 正式账号和真实布局/出纸 NOT_RUN；未伪造回调或逐票取消能力 |
| PRT-13 | 桌面协议、配对/会话/领取/租约、SQLite/JPS/DPAPI、安装脚本已实现 | 软件协议与持久化回归已执行；Windows 打包安装/驱动和设备 NOT_RUN |
| PRT-14 | Job、回执应用、超时/漏建对账、事件/通知与文件维护已实现 | 数据库闭环和独立审查回归已执行；生产灰度未执行 |
| PRT-15 | 20 个场景模板/样例可用；正式来源只接入 ORDER_PAID | 其余 19 个上游事实合同未到位，保留能力门禁，不能宣称已接通生产 |
| PRT-16 | 模块装配、集中测试、PDF 样票、配置与验收材料已准备 | 软件验收与未执行项目逐项记录；不以模拟代替实机 |

详细结果、配置、命令及限制见 `docs/technical/acceptance/print-center/README.md`。未创建提交、未混入或撤销工作区原有其他任务修改，未执行合并/推送/部署。原计划的“提交”步骤本轮以工作区可审查交付替代。

### 2026-10-23 最终软件验收记录

当前实现范围：统一打包 30 模块、打印相关 54 项、真实 MySQL 7 项、B 端 266 项及类型检查/H5 构建通过。40 份 PDF 样票已生成。全仓回归未通过：shop-app 报告 1507 项、4 failures、53 errors、19 skipped 后 JVM 内存耗尽；因此系统级验收尚未完成。用户确认实机资源暂未准备；Windows/飞鹅/实体打印机和私有 COS 另行验收，19 个场景正式来源继续关闭。详见 `docs/technical/acceptance/print-center/README.md`。

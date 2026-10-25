# 打印中心Java类与方法清单

状态：设计参考；实施与验收进度见对应任务表和验收记录。

> 设计稿 · 2026-10-23 · 未生成产品Java源码。真源：[类结构JSON](./schema/print-java-class-design.json)；阅读入口：[Java结构设计](./打印中心-Java类结构设计.md)。

本清单登记250个顶层类型；嵌套DTO和22个Mapper不是额外模块。83项业务API均有Controller→Service方法映射，厂商回调另列。仅为目标结构，不代表已实现。

## 一、逐类职责及方法

### ai.neargo.shop.print.api.PrintScope

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintScope.java`。

字段合同：`String tenantNo, String entityNo, String storeNo`。

### ai.neargo.shop.print.api.PrintActor

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintActor.java`。

字段合同：`String tenantNo, String entityNo, String storeNo, String actorType, String actorNo, String idempotencyKey, Set<String> dataScopes`。

### ai.neargo.shop.print.api.PrintOpsActor

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintOpsActor.java`。

字段合同：`String tenantNo, String staffNo, Set<String> entityNos, Set<String> storeNos, Set<String> dataScopes, String idempotencyKey`。

### ai.neargo.shop.print.api.PrintAgentIdentity

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintAgentIdentity.java`。

字段合同：`String tenantNo, String entityNo, String storeNo, String agentNo, int credentialVersion, long sessionEpoch`。

### ai.neargo.shop.print.api.PrintFactRef

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintFactRef.java`。

字段合同：`String tenantNo, String entityNo, String storeNo, String sourceType, String factNo, String bizRevision, String scene, String snapshotHash`。

### ai.neargo.shop.print.api.PrintSourceReference

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintSourceReference.java`。

字段合同：`String type, String no, String factNo, String revision, String scene`。

### ai.neargo.shop.print.api.PrintFact

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintFact.java`。

字段合同：`PrintFactRef ref, String orderNo, String subOrderNo, String triggerType, String triggerInstance, Instant occurredAt, int payloadSchemaVersion, FrozenJson immutablePayload, Printability printability, String blockCode, List<String> diagnostics`。

### ai.neargo.shop.print.api.FrozenJson

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/FrozenJson.java`。

字段合同：`String canonicalJson, String sha256`。

### ai.neargo.shop.print.api.PrintIntent

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintIntent.java`。

字段合同：`PrintFactRef factRef, PrintMode printMode, PrintOutputOptions outputOptions`。

### ai.neargo.shop.print.api.PrintOutputOptions

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintOutputOptions.java`。

字段合同：`boolean pdfEnabled, PdfOutputProfile pdfProfile`。

### ai.neargo.shop.print.api.PrintFactCursor

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintFactCursor.java`。

字段合同：`PrintScope scope, String sourceKind, Instant from, Instant to, Instant watermarkAt, String watermarkNo`。

### ai.neargo.shop.print.api.PrintFactPage

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintFactPage.java`。

字段合同：`List<PrintFactRef> facts, PrintFactCursor nextCursor, boolean hasMore`。

### ai.neargo.shop.print.api.PrintExecutionGuard

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintExecutionGuard.java`。

字段合同：`boolean allowed, GuardDecision decision, String currentBizRevision, boolean cancelled, String reason`。

### ai.neargo.shop.print.api.PrintSubmission

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintSubmission.java`。

字段合同：`String requestNo, List<String> batchNos, List<String> jobNos, String operationNo, List<String> exportNos`。

### ai.neargo.shop.print.api.PrintJobRef

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintJobRef.java`。

字段合同：`String tenantNo, String entityNo, String storeNo, String jobNo, Long expectedRevision`。

### ai.neargo.shop.print.api.PrintBatchRef

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintBatchRef.java`。

字段合同：`String tenantNo, String entityNo, String storeNo, String batchNo, Long expectedRevision`。

### ai.neargo.shop.print.api.PrintOperation

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintOperation.java`。

字段合同：`String operationNo, String type, PrintOperationStatus status, String targetType, String targetNo, FrozenJson result, PrintOperationError error, Instant createdAt, Instant finishedAt`。

### ai.neargo.shop.print.api.PrintOperationError

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintOperationError.java`。

字段合同：`String key, boolean retryable, String traceNo, FrozenJson conflicts`。

### ai.neargo.shop.print.api.PrintMoney

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintMoney.java`。

字段合同：`String minor, String currency, int scale`。

### ai.neargo.shop.print.api.PrintDocument

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintDocument.java`。

字段合同：`String documentKey, String scene, int payloadSchemaVersion, FrozenJson businessData, SystemPrintSlots systemSlots, Set<String> requiredDataScopes`。

### ai.neargo.shop.print.api.SystemPrintSlots

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/SystemPrintSlots.java`。

字段合同：`String ticketNo, PrintJobKind jobKind, int copyIndex, Instant generatedAt, String originalTicketNo, String reason, Set<String> warnings, OriginalTicketState originalTicketState`。

### ai.neargo.shop.print.api.OriginalTicketState

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/OriginalTicketState.java`。

字段合同：`String jobNo, long revision, PrintJobStatus status, Instant observedAt`。

### ai.neargo.shop.print.api.PrintLayout

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintLayout.java`。

字段合同：`String profileVersion, String fontBundleVersion, String fontHash, BigDecimal pageWidthMm, List<PrintPage> pages, String contentHash`。

### ai.neargo.shop.print.api.PrintPage

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintPage.java`。

字段合同：`int pageIndex, BigDecimal widthMm, BigDecimal heightMm, List<PrintDrawing> drawings, List<LayoutSlot> systemSlots`。

### ai.neargo.shop.print.api.LayoutProfile

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/LayoutProfile.java`。

字段合同：`String code, String version, BigDecimal paperWidthMm, BigDecimal printableWidthMm, BigDecimal leftMarginMm, BigDecimal rightMarginMm, BigDecimal maxHeightMm, int dpi, FontRef font`。

### ai.neargo.shop.print.api.LayoutSlot

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/LayoutSlot.java`。

字段合同：`String name, FrozenJson geometry, FrozenJson style, Set<String> allowedValues`。

### ai.neargo.shop.print.api.FontRef

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/FontRef.java`。

字段合同：`String bundleVersion, String sha256, String fontName`。

### ai.neargo.shop.print.api.GlyphMetrics

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/GlyphMetrics.java`。

字段合同：`BigDecimal advancePt, FrozenJson boundsPt, Set<Integer> missingCodePoints`。

### ai.neargo.shop.print.api.TemplateResolutionCommand

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/TemplateResolutionCommand.java`。

字段合同：`PrintScope scope, String scene, int payloadSchemaVersion, String variantCode, TemplateSelection selection, ResolutionPurpose purpose, Set<String> dataScopes`。

### ai.neargo.shop.print.api.TemplateSelection

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/TemplateSelection.java`。

字段合同：`TemplateSelectionMode mode, String variantCode, String templateVersionNo`。

### ai.neargo.shop.print.api.ResolvedTemplate

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/ResolvedTemplate.java`。

字段合同：`String templateNo, String templateVersionNo, String builtinKey, int payloadSchemaVersion, String scene, String variantCode, FrozenJson content, FrozenJson resolutionSnapshot`。

### ai.neargo.shop.print.api.SimulationCommand

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/SimulationCommand.java`。

字段合同：`PrintScope scope, String scene, SimulationSource source, String variantCode, SimulationFixtureCode fixtureCode, TemplateSelection templateSelection, PrintOutputOptions outputOptions`。

### ai.neargo.shop.print.api.SimulationSource

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/SimulationSource.java`。

字段合同：`SimulationSourceType type, String no, PrintFactRef factRef, Long expectedRevision`。

### ai.neargo.shop.print.api.ExportCommand

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/ExportCommand.java`。

字段合同：`PrintScope scope, ExportSource source, PdfOutputProfile profile`。

### ai.neargo.shop.print.api.ExportSource

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/ExportSource.java`。

字段合同：`ExportSourceType type, String no, long expectedRevision`。

### ai.neargo.shop.print.api.PdfRenderInput

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PdfRenderInput.java`。

字段合同：`String exportNo, PrintArtifactKind artifactKind, PdfOutputProfile profile, List<PrintLayout> layouts, String rendererVersion, String fontBundleVersion, Instant exportCreatedAt`。

### ai.neargo.shop.print.api.PdfArtifact

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PdfArtifact.java`。

字段合同：`byte[] bytes, String contentHash, int pageCount, List<BigDecimal> widthsMm, String rendererVersion, String fontBundleVersion`。

### ai.neargo.shop.print.api.PrivateArtifact

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrivateArtifact.java`。

字段合同：`String objectKey, byte[] bytes, String contentHash, String contentType`。

### ai.neargo.shop.print.api.ArtifactVerification

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/ArtifactVerification.java`。

字段合同：`boolean exists, String objectKey, long size, String sha256`。

### ai.neargo.shop.print.api.DownloadGrant

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/DownloadGrant.java`。

字段合同：`URI url, Instant expiresAt, String fileName, String contentType`。

### ai.neargo.shop.print.api.PrintDispatch

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintDispatch.java`。

字段合同：`PrintScope scope, String jobNo, String attemptNo, String dispatchKey, String printerNo, long fenceEpoch, long connectionRevision, String credentialRef, String adapterCode, String providerScope, FrozenJson connectionSnapshot, PrintLayout layout`。

### ai.neargo.shop.print.api.PrintAttemptRef

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintAttemptRef.java`。

字段合同：`PrintScope scope, String attemptNo, String providerScope, String providerJobId, String dispatchKey, String credentialRef, FrozenJson connectionSnapshot`。

### ai.neargo.shop.print.api.PrintAdapterCapabilities

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintAdapterCapabilities.java`。

字段合同：`String adapterCode, boolean submitIdempotency, boolean lookupByDispatchKey, boolean queryJob, boolean cancelJob, Set<Integer> paperWidthsMm, Set<PrintEvidenceLevel> evidenceLevels`。

### ai.neargo.shop.print.api.DispatchResult

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/DispatchResult.java`。

字段合同：`DispatchOutcome outcome, String providerJobId, FrozenJson evidence, String errorKey`。

### ai.neargo.shop.print.api.QueryResult

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/QueryResult.java`。

字段合同：`ObservedPrintStatus observedStatus, PrintEvidenceLevel evidenceLevel, boolean queueTerminated, boolean partialOutput, FrozenJson evidence`。

### ai.neargo.shop.print.api.CancelResult

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/CancelResult.java`。

字段合同：`ObservedPrintStatus observedStatus, boolean queueTerminated, boolean partialOutput, FrozenJson evidence`。

### ai.neargo.shop.print.api.RawPrintCallback

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/RawPrintCallback.java`。

字段合同：`String adapterCode, String accountRef, byte[] body, Map<String,String> headers, Instant receivedAt`。

### ai.neargo.shop.print.api.VerifiedPrintReceipt

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/VerifiedPrintReceipt.java`。

字段合同：`PrintScope scope, String sourceScope, String sourceReceiptKey, String attemptNo, ObservedPrintStatus status, PrintEvidenceLevel evidenceLevel, Long sourceSequence, Instant occurredAt, FrozenJson sanitizedEvidence`。

### ai.neargo.shop.print.api.EncryptedPrintCredential

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/EncryptedPrintCredential.java`。

字段合同：`String credentialRef, String encryptionKeyVersion, byte[] ciphertext`。

### ai.neargo.shop.print.api.PrintControlFact

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintControlFact.java`。

字段合同：`PrintScope scope, String orderNo, String subOrderNo, String sourceControlFactNo, String controlType, String sourceRevision, Instant occurredAt, List<PrintFactRef> affectedFacts`。

### ai.neargo.shop.print.api.PrintMaintenanceCommand

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintMaintenanceCommand.java`。

字段合同：`PrintScope scope, String sourceKind, Instant from, Instant to, int limit, boolean dryRun`。

### ai.neargo.shop.print.api.PrintMaintenanceReport

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintMaintenanceReport.java`。

字段合同：`int scanned, int changed, int blocked, int failed, boolean hasMore, String summary`。

### ai.neargo.shop.print.api.PrintWorkerBudget

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintWorkerBudget.java`。

字段合同：`int maxItems, Duration timeBudget, String workerNo`。

### ai.neargo.shop.print.api.PrintWorkReport

模块：`print-api`；种类：`record`；任务：`PRT-01`。

不可变合同；实体、SDK或HTTP上下文不得进入此类型

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintWorkReport.java`。

字段合同：`int claimed, int succeeded, int failed, boolean hasMore`。

### ai.neargo.shop.print.api.Printability

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/Printability.java`。

取值：`READY / BLOCKED`。

### ai.neargo.shop.print.api.PrintMode

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintMode.java`。

取值：`CURRENT_VERSION / EXISTING_FACT`。

### ai.neargo.shop.print.api.PrintOperationStatus

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintOperationStatus.java`。

取值：`PENDING / UNKNOWN / SUCCEEDED / FAILED`。

### ai.neargo.shop.print.api.PrintJobStatus

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintJobStatus.java`。

取值：`PENDING / DISPATCHING / ACCEPTED / PRINTING / COMPLETED / RETRY_WAIT / UNKNOWN / FAILED / CANCELLED / STALE`。

### ai.neargo.shop.print.api.PrintRequestStatus

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintRequestStatus.java`。

取值：`PENDING / BLOCKED / GENERATED / CANCELLED / SKIPPED`。

### ai.neargo.shop.print.api.PrintBatchStatus

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintBatchStatus.java`。

取值：`PENDING / PROCESSING / PARTIAL / ATTENTION / COMPLETED / CANCELLED`。

### ai.neargo.shop.print.api.PrintJobKind

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintJobKind.java`。

取值：`INITIAL / REPRINT / EXTRA / VOID / TEST`。

### ai.neargo.shop.print.api.PrintEvidenceLevel

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintEvidenceLevel.java`。

取值：`NONE / DEVICE_CONFIRMED / PROVIDER_CONFIRMED / MANUAL_CONFIRMED`。

### ai.neargo.shop.print.api.GuardDecision

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/GuardDecision.java`。

取值：`ALLOW_HISTORICAL_RECEIPT / CANCEL_UNSUBMITTED_EXECUTION / REQUIRE_VOID / HOLD_UNKNOWN`。

### ai.neargo.shop.print.api.TemplateSelectionMode

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/TemplateSelectionMode.java`。

取值：`SCENE_DEFAULT / PINNED_VERSION`。

### ai.neargo.shop.print.api.ResolutionPurpose

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/ResolutionPurpose.java`。

取值：`PRODUCTION / SAMPLE_PREVIEW`。

### ai.neargo.shop.print.api.SimulationStatus

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/SimulationStatus.java`。

取值：`PENDING / RUNNING / PASSED / FAILED`。

### ai.neargo.shop.print.api.SimulationSourceType

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/SimulationSourceType.java`。

取值：`SAMPLE / JOB / FACT`。

### ai.neargo.shop.print.api.SimulationFixtureCode

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/SimulationFixtureCode.java`。

取值：`SUCCESS / NOT_ACCEPTED / OFFLINE / PAPER_OUT / ACCEPTED_NO_RESULT / TIMEOUT_UNKNOWN / DUPLICATE_RECEIPT / LATE_RECEIPT / PARTIAL_COPIES`。

### ai.neargo.shop.print.api.ExportStatus

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/ExportStatus.java`。

取值：`PENDING / GENERATING / READY / FAILED / EXPIRED`。

### ai.neargo.shop.print.api.ExportSourceType

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/ExportSourceType.java`。

取值：`JOB / BATCH / SIMULATION`。

### ai.neargo.shop.print.api.PdfOutputProfile

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PdfOutputProfile.java`。

取值：`ORIGINAL_ROLL / A4_SHEET`。

### ai.neargo.shop.print.api.PrintArtifactKind

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintArtifactKind.java`。

取值：`LIVE_EXPORT / SIMULATION_EXPORT`。

### ai.neargo.shop.print.api.DispatchOutcome

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/DispatchOutcome.java`。

取值：`ACCEPTED / NOT_ACCEPTED / UNKNOWN`。

### ai.neargo.shop.print.api.ObservedPrintStatus

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/ObservedPrintStatus.java`。

取值：`RECEIVED / SUBMITTING / ACCEPTED / PRINTING / CONFIRMED / NOT_ACCEPTED / UNKNOWN / FAILED / CANCELLED`。

### ai.neargo.shop.print.api.PrinterConnectionType

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrinterConnectionType.java`。

取值：`CLOUD / LOCAL`。

### ai.neargo.shop.print.api.PrinterAdminStatus

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrinterAdminStatus.java`。

取值：`ACTIVE / PAUSED / RETIRED`。

### ai.neargo.shop.print.api.PrinterHealthStatus

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrinterHealthStatus.java`。

取值：`ONLINE / OFFLINE / PAPER_OUT / ERROR / UNKNOWN`。

### ai.neargo.shop.print.api.PrintReceiptStatus

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintReceiptStatus.java`。

取值：`PENDING / APPLIED / IGNORED / CONFLICT / UNMATCHED`。

### ai.neargo.shop.print.api.PrintAttemptStatus

模块：`print-api`；种类：`enum`；任务：`PRT-01`。

只定义本领域状态，不混用打印/模拟/导出成功语义

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintAttemptStatus.java`。

取值：`PREPARED / SUBMITTING / ACCEPTED / PRINTING / CONFIRMED / NOT_ACCEPTED / UNKNOWN / FAILED / CANCELLED`。

### ai.neargo.shop.print.api.PrintDrawing

模块：`print-api`；种类：`sealed-interface`；任务：`PRT-03`。

有界绘制原语，禁止URL/脚本/设备控制字节

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintDrawing.java`。

嵌套record字段：

```java
public record TextRun(String text, FontRef font, BigDecimal pointSize, BigDecimal xMm, BigDecimal yMm, List<BigDecimal> advancesMm) implements PrintDrawing {}
public record RuleLine(BigDecimal x1Mm, BigDecimal y1Mm, BigDecimal x2Mm, BigDecimal y2Mm, BigDecimal strokeMm) implements PrintDrawing {}
public record ImageRun(byte[] embeddedBytes, String contentHash, BigDecimal xMm, BigDecimal yMm, BigDecimal widthMm, BigDecimal heightMm) implements PrintDrawing {}
```

嵌套类型：`TextRun`, `RuleLine`, `ImageRun`。

### ai.neargo.shop.print.api.PrintSourceFactsPort

模块：`print-api`；种类：`interface`；任务：`PRT-01`。

可信事实的发现、快照读取、扫描和投递守卫

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintSourceFactsPort.java`。

```java
Set<String> sourceTypes();
PrintFactRef resolveReference(PrintSourceReference reference, PrintScope scope);
PrintFact snapshot(PrintFactRef ref);
PrintFactPage scan(PrintFactCursor cursor, int limit);
PrintExecutionGuard executionGuard(PrintFactRef ref);
```

### ai.neargo.shop.print.api.PrintTemplateResolver

模块：`print-api`；种类：`interface`；任务：`PRT-01`。

同场景同宽默认/固定版本解析

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintTemplateResolver.java`。

```java
ResolvedTemplate resolve(TemplateResolutionCommand command);
```

### ai.neargo.shop.print.api.PrintDocumentCompiler

模块：`print-api`；种类：`interface`；任务：`PRT-01`。

schema校验、字段裁剪和不可删系统标识

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintDocumentCompiler.java`。

```java
PrintDocument compile(PrintFact fact, ResolvedTemplate template, SystemPrintSlots slots);
```

### ai.neargo.shop.print.api.PrintFontMetricsPort

模块：`print-api`；种类：`interface`；任务：`PRT-01`。

字体缺字和度量，不暴露PDFBox

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintFontMetricsPort.java`。

```java
GlyphMetrics measure(String text, FontRef font, BigDecimal pointSize);
```

### ai.neargo.shop.print.api.PrintLayoutEngine

模块：`print-api`；种类：`interface`；任务：`PRT-01`。

分页排版与受控系统插槽

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintLayoutEngine.java`。

```java
PrintLayout layout(PrintDocument document, LayoutProfile profile);
```

### ai.neargo.shop.print.api.PrintDispatchPort

模块：`print-api`；种类：`interface`；任务：`PRT-01`。

仅服务端主动提交的真实云适配器；本地设备走领取协议

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintDispatchPort.java`。

```java
String adapterCode();
PrintAdapterCapabilities capabilities();
DispatchResult submit(PrintDispatch dispatch);
QueryResult query(PrintAttemptRef ref);
CancelResult cancel(PrintAttemptRef ref);
```

### ai.neargo.shop.print.api.PrintPdfPort

模块：`print-api`；种类：`interface`；任务：`PRT-01`。

冻结布局到PDF，不能查订单

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintPdfPort.java`。

```java
PdfArtifact render(PdfRenderInput input);
```

### ai.neargo.shop.print.api.PrintArtifactStorePort

模块：`print-api`；种类：`interface`；任务：`PRT-01`。

私有原子写、字节校验和短期下载

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintArtifactStorePort.java`。

```java
void put(PrivateArtifact input);
boolean exists(String objectKey);
ArtifactVerification verify(String objectKey);
DownloadGrant signedDownload(String objectKey, Duration ttl);
void delete(String objectKey);
```

### ai.neargo.shop.print.api.PrintCredentialLookupPort

模块：`print-api`；种类：`interface`；任务：`PRT-01`。

仅供受信任通道读取本次执行固定加密版本

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintCredentialLookupPort.java`。

```java
EncryptedPrintCredential get(PrintScope scope, String credentialRef);
```

### ai.neargo.shop.print.api.PrintCredentialCipherPort

模块：`print-api`；种类：`interface`；任务：`PRT-01`。

加密/解密适配，不将明文凭据暴露给网页

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintCredentialCipherPort.java`。

```java
EncryptedPrintCredential encrypt(PrintScope scope, String credentialRef, byte[] secret);
byte[] decrypt(PrintScope scope, EncryptedPrintCredential credential);
```

### ai.neargo.shop.print.api.PrintCallbackVerifierPort

模块：`print-api`；种类：`interface`；任务：`PRT-01`。

验签及主体映射后才产生可入库证据

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintCallbackVerifierPort.java`。

```java
String adapterCode();
VerifiedPrintReceipt verify(RawPrintCallback callback);
```

### ai.neargo.shop.print.api.PrintDeviceService

模块：`print-api`；种类：`interface`；任务：`PRT-04`。

对外业务入口；校验来源/对象范围后委托本域服务

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintDeviceService.java`。

```java
List<AdapterVO> listAdapters(ListAdaptersQuery query, PrintActor actor);
PageData<PrinterVO> listPrinters(ListPrintersQuery query, PrintActor actor);
PrintOperation createPrinter(CreatePrinterReq request, PrintActor actor);
PrinterDetailVO getPrinter(String printerNo, PrintActor actor);
PrintOperation updatePrinter(String printerNo, UpdatePrinterReq request, PrintActor actor);
PrintOperation changeConnection(String printerNo, ChangeConnectionReq request, PrintActor actor);
PrintOperation changeStatus(String printerNo, ChangeStatusReq request, PrintActor actor);
PrintOperation rotateCredential(String printerNo, RotateCredentialReq request, PrintActor actor);
PrintOperation probe(String printerNo, ProbeReq request, PrintActor actor);
PrintOperation testPrint(String printerNo, TestPrintReq request, PrintActor actor);
```

### ai.neargo.shop.print.service.impl.PrintDeviceServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-04`。

实现PrintDeviceService；本域Mapper＋短事务，不接受HTTP身份字段

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/PrintDeviceServiceImpl.java`。

实现：`PrintDeviceService`。

直接依赖：`PrintDeviceService`。

```java
List<AdapterVO> listAdapters(ListAdaptersQuery query, PrintActor actor);
PageData<PrinterVO> listPrinters(ListPrintersQuery query, PrintActor actor);
PrintOperation createPrinter(CreatePrinterReq request, PrintActor actor);
PrinterDetailVO getPrinter(String printerNo, PrintActor actor);
PrintOperation updatePrinter(String printerNo, UpdatePrinterReq request, PrintActor actor);
PrintOperation changeConnection(String printerNo, ChangeConnectionReq request, PrintActor actor);
PrintOperation changeStatus(String printerNo, ChangeStatusReq request, PrintActor actor);
PrintOperation rotateCredential(String printerNo, RotateCredentialReq request, PrintActor actor);
PrintOperation probe(String printerNo, ProbeReq request, PrintActor actor);
PrintOperation testPrint(String printerNo, TestPrintReq request, PrintActor actor);
```

### ai.neargo.shop.print.api.PrintAgentService

模块：`print-api`；种类：`interface`；任务：`PRT-13`。

对外业务入口；校验来源/对象范围后委托本域服务

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintAgentService.java`。

```java
PageData<AgentVO> listAgents(ListAgentsQuery query, PrintActor actor);
PrintOperation createAgent(CreateAgentReq request, PrintActor actor);
AgentDetailVO getAgent(String agentNo, PrintActor actor);
PrintOperation issueEnrollment(String agentNo, IssueEnrollmentReq request, PrintActor actor);
PrintOperation revokeAgent(String agentNo, RevokeAgentReq request, PrintActor actor);
PrintOperation rotateAgentCredential(String agentNo, RotateAgentCredentialReq request, PrintActor actor);
```

### ai.neargo.shop.print.service.impl.PrintAgentServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-13`。

实现PrintAgentService；本域Mapper＋短事务，不接受HTTP身份字段

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/PrintAgentServiceImpl.java`。

实现：`PrintAgentService`。

直接依赖：`PrintAgentService`。

```java
PageData<AgentVO> listAgents(ListAgentsQuery query, PrintActor actor);
PrintOperation createAgent(CreateAgentReq request, PrintActor actor);
AgentDetailVO getAgent(String agentNo, PrintActor actor);
PrintOperation issueEnrollment(String agentNo, IssueEnrollmentReq request, PrintActor actor);
PrintOperation revokeAgent(String agentNo, RevokeAgentReq request, PrintActor actor);
PrintOperation rotateAgentCredential(String agentNo, RotateAgentCredentialReq request, PrintActor actor);
```

### ai.neargo.shop.print.api.PrintScenePolicyService

模块：`print-api`；种类：`interface`；任务：`PRT-03`。

对外业务入口；校验来源/对象范围后委托本域服务

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintScenePolicyService.java`。

```java
List<SceneVO> listScenes(ListScenesQuery query, PrintActor actor);
List<ScenePolicyVO> listScenePolicies(ListScenePoliciesQuery query, PrintActor actor);
PrintOperation saveScenePolicy(String scene, SaveScenePolicyReq request, PrintActor actor);
PageData<PolicyVersionVO> listPolicyVersions(String scene, ListPolicyVersionsQuery query, PrintActor actor);
SceneFieldCatalogVO getSceneFields(String scene, GetSceneFieldsQuery query, PrintActor actor);
GetSceneExampleVO getSceneExample(String scene, GetSceneExampleQuery query, PrintActor actor);
```

### ai.neargo.shop.print.service.impl.PrintScenePolicyServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-03`。

实现PrintScenePolicyService；本域Mapper＋短事务，不接受HTTP身份字段

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/PrintScenePolicyServiceImpl.java`。

实现：`PrintScenePolicyService`。

直接依赖：`PrintScenePolicyService`。

```java
List<SceneVO> listScenes(ListScenesQuery query, PrintActor actor);
List<ScenePolicyVO> listScenePolicies(ListScenePoliciesQuery query, PrintActor actor);
PrintOperation saveScenePolicy(String scene, SaveScenePolicyReq request, PrintActor actor);
PageData<PolicyVersionVO> listPolicyVersions(String scene, ListPolicyVersionsQuery query, PrintActor actor);
SceneFieldCatalogVO getSceneFields(String scene, GetSceneFieldsQuery query, PrintActor actor);
GetSceneExampleVO getSceneExample(String scene, GetSceneExampleQuery query, PrintActor actor);
```

### ai.neargo.shop.print.api.PrintTemplateService

模块：`print-api`；种类：`interface`；任务：`PRT-03`。

对外业务入口；校验来源/对象范围后委托本域服务

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintTemplateService.java`。

```java
PageData<TemplateVO> listTemplates(ListTemplatesQuery query, PrintActor actor);
PrintOperation createTemplate(CreateTemplateReq request, PrintActor actor);
PrintOperation createTemplateVersion(String templateNo, CreateTemplateVersionReq request, PrintActor actor);
PrintOperation updateTemplateDraft(String versionNo, UpdateTemplateDraftReq request, PrintActor actor);
PreviewVO previewTemplate(String versionNo, PreviewTemplateReq request, PrintActor actor);
PrintOperation publishTemplate(String versionNo, PublishTemplateReq request, PrintActor actor);
PageData<TemplateVersionVO> listTemplateVersions(String templateNo, ListTemplateVersionsQuery query, PrintActor actor);
TemplateResolutionPreviewVO previewResolution(PreviewResolutionReq request, PrintActor actor);
```

### ai.neargo.shop.print.service.impl.PrintTemplateServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-03`。

实现PrintTemplateService；本域Mapper＋短事务，不接受HTTP身份字段

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/PrintTemplateServiceImpl.java`。

实现：`PrintTemplateService`。

直接依赖：`PrintTemplateService`。

```java
PageData<TemplateVO> listTemplates(ListTemplatesQuery query, PrintActor actor);
PrintOperation createTemplate(CreateTemplateReq request, PrintActor actor);
PrintOperation createTemplateVersion(String templateNo, CreateTemplateVersionReq request, PrintActor actor);
PrintOperation updateTemplateDraft(String versionNo, UpdateTemplateDraftReq request, PrintActor actor);
PreviewVO previewTemplate(String versionNo, PreviewTemplateReq request, PrintActor actor);
PrintOperation publishTemplate(String versionNo, PublishTemplateReq request, PrintActor actor);
PageData<TemplateVersionVO> listTemplateVersions(String templateNo, ListTemplateVersionsQuery query, PrintActor actor);
TemplateResolutionPreviewVO previewResolution(PreviewResolutionReq request, PrintActor actor);
```

### ai.neargo.shop.print.api.PrintRouteService

模块：`print-api`；种类：`interface`；任务：`PRT-03`。

对外业务入口；校验来源/对象范围后委托本域服务

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintRouteService.java`。

```java
PageData<RouteVO> listRoutes(ListRoutesQuery query, PrintActor actor);
PrintOperation createRoute(CreateRouteReq request, PrintActor actor);
PrintOperation createRouteVersion(String routeNo, CreateRouteVersionReq request, PrintActor actor);
RouteDryRunVO dryRunRoute(String versionNo, DryRunRouteReq request, PrintActor actor);
PrintOperation publishRoute(String versionNo, PublishRouteReq request, PrintActor actor);
PageData<RouteVersionVO> listRouteVersions(String routeNo, ListRouteVersionsQuery query, PrintActor actor);
```

### ai.neargo.shop.print.service.impl.PrintRouteServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-03`。

实现PrintRouteService；本域Mapper＋短事务，不接受HTTP身份字段

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/PrintRouteServiceImpl.java`。

实现：`PrintRouteService`。

直接依赖：`PrintRouteService`。

```java
PageData<RouteVO> listRoutes(ListRoutesQuery query, PrintActor actor);
PrintOperation createRoute(CreateRouteReq request, PrintActor actor);
PrintOperation createRouteVersion(String routeNo, CreateRouteVersionReq request, PrintActor actor);
RouteDryRunVO dryRunRoute(String versionNo, DryRunRouteReq request, PrintActor actor);
PrintOperation publishRoute(String versionNo, PublishRouteReq request, PrintActor actor);
PageData<RouteVersionVO> listRouteVersions(String routeNo, ListRouteVersionsQuery query, PrintActor actor);
```

### ai.neargo.shop.print.api.PrintRequestService

模块：`print-api`；种类：`interface`；任务：`PRT-04`。

对外业务入口；校验来源/对象范围后委托本域服务

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintRequestService.java`。

```java
PageData<PrintRequestVO> listRequests(ListRequestsQuery query, PrintActor actor);
RequestDetailVO getRequest(String requestNo, PrintActor actor);
PrintOperation retryGeneration(String requestNo, RetryGenerationReq request, PrintActor actor);
```

### ai.neargo.shop.print.service.impl.PrintRequestServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-04`。

实现PrintRequestService；本域Mapper＋短事务，不接受HTTP身份字段

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/PrintRequestServiceImpl.java`。

实现：`PrintRequestService`。

直接依赖：`PrintRequestService`。

```java
PageData<PrintRequestVO> listRequests(ListRequestsQuery query, PrintActor actor);
RequestDetailVO getRequest(String requestNo, PrintActor actor);
PrintOperation retryGeneration(String requestNo, RetryGenerationReq request, PrintActor actor);
```

### ai.neargo.shop.print.api.PrintFacade

模块：`print-api`；种类：`interface`；任务：`PRT-04`。

对外业务入口；校验来源/对象范围后委托本域服务

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintFacade.java`。

```java
PrintOperation submitBySource(SubmitPrintRequestReq request, PrintActor actor);
PrintSubmission submit(PrintIntent intent, PrintActor actor);
PrintSubmission onFact(PrintFactRef ref);
PrintJobView getJob(PrintJobRef ref, PrintActor actor);
```

### ai.neargo.shop.print.service.impl.DefaultPrintFacade

模块：`print-domain`；种类：`class`；任务：`PRT-04`。

实现PrintFacade；本域Mapper＋短事务，不接受HTTP身份字段

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/DefaultPrintFacade.java`。

实现：`PrintFacade`。

直接依赖：`PrintFacade`。

```java
PrintOperation submitBySource(SubmitPrintRequestReq request, PrintActor actor);
PrintSubmission submit(PrintIntent intent, PrintActor actor);
PrintSubmission onFact(PrintFactRef ref);
PrintJobView getJob(PrintJobRef ref, PrintActor actor);
```

### ai.neargo.shop.print.api.PrintQueryService

模块：`print-api`；种类：`interface`；任务：`PRT-04`。

对外业务入口；校验来源/对象范围后委托本域服务

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintQueryService.java`。

```java
PrintBatchVO getBatch(String batchNo, PrintActor actor);
PageData<PrintJobVO> listJobs(ListJobsQuery query, PrintActor actor);
PrintJobView getJob(PrintJobRef ref, PrintActor actor);
PageData<PrintAttemptVO> listAttempts(String jobNo, ListAttemptsQuery query, PrintActor actor);
PageData<PrintReceiptVO> listReceipts(String jobNo, ListReceiptsQuery query, PrintActor actor);
DocumentPreviewVO getDocument(String jobNo, PrintActor actor);
OrderPrintSummaryVO getOrderSummary(String orderNo, GetOrderSummaryQuery query, PrintActor actor);
PrintOperation getOperation(String operationNo, PrintActor actor);
```

### ai.neargo.shop.print.service.impl.PrintQueryServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-04`。

实现PrintQueryService；本域Mapper＋短事务，不接受HTTP身份字段

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/PrintQueryServiceImpl.java`。

实现：`PrintQueryService`。

直接依赖：`PrintQueryService`。

```java
PrintBatchVO getBatch(String batchNo, PrintActor actor);
PageData<PrintJobVO> listJobs(ListJobsQuery query, PrintActor actor);
PrintJobView getJob(PrintJobRef ref, PrintActor actor);
PageData<PrintAttemptVO> listAttempts(String jobNo, ListAttemptsQuery query, PrintActor actor);
PageData<PrintReceiptVO> listReceipts(String jobNo, ListReceiptsQuery query, PrintActor actor);
DocumentPreviewVO getDocument(String jobNo, PrintActor actor);
OrderPrintSummaryVO getOrderSummary(String orderNo, GetOrderSummaryQuery query, PrintActor actor);
PrintOperation getOperation(String operationNo, PrintActor actor);
```

### ai.neargo.shop.print.api.PrintReprintService

模块：`print-api`；种类：`interface`；任务：`PRT-04`。

对外业务入口；校验来源/对象范围后委托本域服务

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintReprintService.java`。

```java
PrintOperation reprint(String jobNo, ReprintReq request, PrintActor actor);
PrintOperation batchReprint(BatchReprintReq request, PrintActor actor);
PrintOperation extraCopies(String jobNo, ExtraCopiesReq request, PrintActor actor);
```

### ai.neargo.shop.print.service.impl.PrintReprintServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-04`。

实现PrintReprintService；本域Mapper＋短事务，不接受HTTP身份字段

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/PrintReprintServiceImpl.java`。

实现：`PrintReprintService`。

直接依赖：`PrintReprintService`。

```java
PrintOperation reprint(String jobNo, ReprintReq request, PrintActor actor);
PrintOperation batchReprint(BatchReprintReq request, PrintActor actor);
PrintOperation extraCopies(String jobNo, ExtraCopiesReq request, PrintActor actor);
```

### ai.neargo.shop.print.api.PrintJobCommandService

模块：`print-api`；种类：`interface`；任务：`PRT-04`。

对外业务入口；校验来源/对象范围后委托本域服务

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintJobCommandService.java`。

```java
PrintOperation retryJob(String jobNo, RetryJobReq request, PrintActor actor);
PrintOperation cancelJob(String jobNo, CancelJobReq request, PrintActor actor);
PrintOperation queryResult(String jobNo, QueryResultReq request, PrintActor actor);
PrintOperation confirmManually(String jobNo, ConfirmManuallyReq request, PrintActor actor);
PrintOperation releaseStale(String jobNo, ReleaseStaleReq request, PrintActor actor);
PrintOperation resolveDeviceUncertainty(String printerNo, ResolveDeviceUncertaintyReq request, PrintActor actor);
PrintMaintenanceReport applyControl(PrintControlFact fact);
```

### ai.neargo.shop.print.service.impl.PrintJobCommandServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-04`。

实现PrintJobCommandService；本域Mapper＋短事务，不接受HTTP身份字段

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/PrintJobCommandServiceImpl.java`。

实现：`PrintJobCommandService`。

直接依赖：`PrintJobCommandService`。

```java
PrintOperation retryJob(String jobNo, RetryJobReq request, PrintActor actor);
PrintOperation cancelJob(String jobNo, CancelJobReq request, PrintActor actor);
PrintOperation queryResult(String jobNo, QueryResultReq request, PrintActor actor);
PrintOperation confirmManually(String jobNo, ConfirmManuallyReq request, PrintActor actor);
PrintOperation releaseStale(String jobNo, ReleaseStaleReq request, PrintActor actor);
PrintOperation resolveDeviceUncertainty(String printerNo, ResolveDeviceUncertaintyReq request, PrintActor actor);
PrintMaintenanceReport applyControl(PrintControlFact fact);
```

### ai.neargo.shop.print.api.PrintAgentProtocolService

模块：`print-api`；种类：`interface`；任务：`PRT-13`。

对外业务入口；校验来源/对象范围后委托本域服务

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintAgentProtocolService.java`。

```java
DeviceEnrollVO enroll(DeviceEnrollReq request);
DeviceOpenSessionVO openSession(DeviceOpenSessionReq request, PrintAgentIdentity identity);
DeviceHeartbeatVO heartbeat(DeviceHeartbeatReq request, PrintAgentIdentity identity);
DeviceClaimVO claim(DeviceClaimReq request, PrintAgentIdentity identity);
DeviceAcceptDispatchVO acceptDispatch(String dispatchKey, DeviceAcceptDispatchReq request, PrintAgentIdentity identity);
DeviceRenewLeaseVO renewLease(String dispatchKey, DeviceRenewLeaseReq request, PrintAgentIdentity identity);
DeviceReportEventsVO reportEvents(DeviceReportEventsReq request, PrintAgentIdentity identity);
DeviceGetDispatchVO getDispatch(String dispatchKey, PrintAgentIdentity identity);
DeviceRecoverVO recover(DeviceRecoverReq request, PrintAgentIdentity identity);
DeviceReportCommandResultVO reportCommandResult(String commandNo, DeviceReportCommandResultReq request, PrintAgentIdentity identity);
```

### ai.neargo.shop.print.service.impl.PrintAgentProtocolServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-13`。

实现PrintAgentProtocolService；本域Mapper＋短事务，不接受HTTP身份字段

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/PrintAgentProtocolServiceImpl.java`。

实现：`PrintAgentProtocolService`。

直接依赖：`PrintAgentProtocolService`。

```java
DeviceEnrollVO enroll(DeviceEnrollReq request);
DeviceOpenSessionVO openSession(DeviceOpenSessionReq request, PrintAgentIdentity identity);
DeviceHeartbeatVO heartbeat(DeviceHeartbeatReq request, PrintAgentIdentity identity);
DeviceClaimVO claim(DeviceClaimReq request, PrintAgentIdentity identity);
DeviceAcceptDispatchVO acceptDispatch(String dispatchKey, DeviceAcceptDispatchReq request, PrintAgentIdentity identity);
DeviceRenewLeaseVO renewLease(String dispatchKey, DeviceRenewLeaseReq request, PrintAgentIdentity identity);
DeviceReportEventsVO reportEvents(DeviceReportEventsReq request, PrintAgentIdentity identity);
DeviceGetDispatchVO getDispatch(String dispatchKey, PrintAgentIdentity identity);
DeviceRecoverVO recover(DeviceRecoverReq request, PrintAgentIdentity identity);
DeviceReportCommandResultVO reportCommandResult(String commandNo, DeviceReportCommandResultReq request, PrintAgentIdentity identity);
```

### ai.neargo.shop.print.api.PrintMonitorService

模块：`print-api`；种类：`interface`；任务：`PRT-14`。

对外业务入口；校验来源/对象范围后委托本域服务

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintMonitorService.java`。

```java
GetHealthVO getHealth(GetHealthQuery query, PrintActor actor);
PageData<IncidentVO> listIncidents(ListIncidentsQuery query, PrintActor actor);
PrintOperation acknowledgeIncident(String incidentNo, AcknowledgeIncidentReq request, PrintActor actor);
PageData<ActionLogVO> listActionLogs(ListActionLogsQuery query, PrintActor actor);
ChangePageVO listChanges(ListChangesQuery query, PrintActor actor);
```

### ai.neargo.shop.print.service.impl.PrintMonitorServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-14`。

实现PrintMonitorService；本域Mapper＋短事务，不接受HTTP身份字段

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/PrintMonitorServiceImpl.java`。

实现：`PrintMonitorService`。

直接依赖：`PrintMonitorService`。

```java
GetHealthVO getHealth(GetHealthQuery query, PrintActor actor);
PageData<IncidentVO> listIncidents(ListIncidentsQuery query, PrintActor actor);
PrintOperation acknowledgeIncident(String incidentNo, AcknowledgeIncidentReq request, PrintActor actor);
PageData<ActionLogVO> listActionLogs(ListActionLogsQuery query, PrintActor actor);
ChangePageVO listChanges(ListChangesQuery query, PrintActor actor);
```

### ai.neargo.shop.print.api.PrintOpsService

模块：`print-api`；种类：`interface`；任务：`PRT-14`。

对外业务入口；校验来源/对象范围后委托本域服务

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintOpsService.java`。

```java
PrintOperation reconcile(OpsReconcileReq request, PrintOpsActor actor);
PrintOperation getOperation(String operationNo, OpsGetOperationQuery query, PrintOpsActor actor);
FleetHealthVO getFleetHealth(OpsGetFleetHealthQuery query, PrintOpsActor actor);
PageData<IncidentVO> listIncidents(OpsListIncidentsQuery query, PrintOpsActor actor);
```

### ai.neargo.shop.print.service.impl.PrintOpsServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-14`。

实现PrintOpsService；本域Mapper＋短事务，不接受HTTP身份字段

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/PrintOpsServiceImpl.java`。

实现：`PrintOpsService`。

直接依赖：`PrintOpsService`。

```java
PrintOperation reconcile(OpsReconcileReq request, PrintOpsActor actor);
PrintOperation getOperation(String operationNo, OpsGetOperationQuery query, PrintOpsActor actor);
FleetHealthVO getFleetHealth(OpsGetFleetHealthQuery query, PrintOpsActor actor);
PageData<IncidentVO> listIncidents(OpsListIncidentsQuery query, PrintOpsActor actor);
```

### ai.neargo.shop.print.api.PrintSimulationFacade

模块：`print-api`；种类：`interface`；任务：`PRT-05`。

对外业务入口；校验来源/对象范围后委托本域服务

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintSimulationFacade.java`。

```java
PrintOperation create(SimulationCommand command, PrintActor actor);
PageData<SimulationView> page(SimulationQuery query, PrintActor actor);
SimulationView get(String simulationNo, PrintActor actor);
```

### ai.neargo.shop.print.simulation.impl.DefaultPrintSimulationFacade

模块：`print-domain`；种类：`class`；任务：`PRT-05`。

实现PrintSimulationFacade；本域Mapper＋短事务，不接受HTTP身份字段

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/simulation/impl/DefaultPrintSimulationFacade.java`。

实现：`PrintSimulationFacade`。

直接依赖：`PrintSimulationFacade`。

```java
PrintOperation create(SimulationCommand command, PrintActor actor);
PageData<SimulationView> page(SimulationQuery query, PrintActor actor);
SimulationView get(String simulationNo, PrintActor actor);
```

### ai.neargo.shop.print.api.PrintExportFacade

模块：`print-api`；种类：`interface`；任务：`PRT-07`。

对外业务入口；校验来源/对象范围后委托本域服务

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintExportFacade.java`。

```java
PrintOperation create(ExportCommand command, PrintActor actor);
PageData<ExportView> page(ExportQuery query, PrintActor actor);
ExportView get(String exportNo, PrintActor actor);
DownloadGrant download(String exportNo, PrintActor actor);
```

### ai.neargo.shop.print.export.impl.DefaultPrintExportFacade

模块：`print-domain`；种类：`class`；任务：`PRT-07`。

实现PrintExportFacade；本域Mapper＋短事务，不接受HTTP身份字段

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/export/impl/DefaultPrintExportFacade.java`。

实现：`PrintExportFacade`。

直接依赖：`PrintExportFacade`。

```java
PrintOperation create(ExportCommand command, PrintActor actor);
PageData<ExportView> page(ExportQuery query, PrintActor actor);
ExportView get(String exportNo, PrintActor actor);
DownloadGrant download(String exportNo, PrintActor actor);
```

### ai.neargo.shop.print.api.dto.PrintDeviceDtos

模块：`print-api`；种类：`dto-container`；任务：`PRT-10`。

按业务模块组织record Req/Query/VO，不含Spring/Servlet注解；字段沿对应API合同

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/dto/PrintDeviceDtos.java`。

| API | 请求类型 | 业务输入字段 | 返回类型 |
|---|---|---|---|
| P01 | ListAdaptersQuery | connectionType? | List<AdapterVO> |
| P02 | ListPrintersQuery | storeNo,status?,healthStatus?,connectionType?,page,size | PageData<PrinterVO> |
| P03 | CreatePrinterReq | CreatePrinterReq | PrintOperation |
| P04 | 无Body/Query | — | PrinterDetailVO |
| P05 | UpdatePrinterReq | expectedRevision,name,paperWidthMm | PrintOperation |
| P06 | ChangeConnectionReq | expectedRevision,connection,reason | PrintOperation |
| P07 | ChangeStatusReq | expectedRevision,status,reason | PrintOperation |
| P08 | RotateCredentialReq | expectedRevision,credentials | PrintOperation |
| P09 | ProbeReq | expectedRevision | PrintOperation |
| P10 | TestPrintReq | expectedRevision,testPattern | PrintOperation |

### ai.neargo.shop.print.api.dto.PrintAgentDtos

模块：`print-api`；种类：`dto-container`；任务：`PRT-10`。

按业务模块组织record Req/Query/VO，不含Spring/Servlet注解；字段沿对应API合同

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/dto/PrintAgentDtos.java`。

| API | 请求类型 | 业务输入字段 | 返回类型 |
|---|---|---|---|
| A01 | ListAgentsQuery | storeNo,status?,page,size | PageData<AgentVO> |
| A02 | CreateAgentReq | storeNo,name,platform | PrintOperation |
| A03 | 无Body/Query | — | AgentDetailVO |
| A04 | IssueEnrollmentReq | expectedRevision,clientPublicKey | PrintOperation |
| A05 | RevokeAgentReq | expectedRevision,reason | PrintOperation |
| A06 | RotateAgentCredentialReq | expectedRevision,clientPublicKey,reason | PrintOperation |

### ai.neargo.shop.print.api.dto.PrintConfigDtos

模块：`print-api`；种类：`dto-container`；任务：`PRT-10`。

按业务模块组织record Req/Query/VO，不含Spring/Servlet注解；字段沿对应API合同

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/dto/PrintConfigDtos.java`。

| API | 请求类型 | 业务输入字段 | 返回类型 |
|---|---|---|---|
| C01 | ListScenesQuery | storeNo | List<SceneVO> |
| C02 | ListScenePoliciesQuery | storeNo | List<ScenePolicyVO> |
| C03 | SaveScenePolicyReq | storeNo,expectedRevision,policy | PrintOperation |
| C04 | ListPolicyVersionsQuery | storeNo,page,size | PageData<PolicyVersionVO> |
| C05 | ListTemplatesQuery | storeNo,scene?,variantCode?,page,size | PageData<TemplateVO> |
| C06 | CreateTemplateReq | storeNo,scene,variantCode,name | PrintOperation |
| C07 | CreateTemplateVersionReq | expectedRevision,format,schemaVersion,payloadSchemaVersion,paperWidthMm,content,compatibleCapabilities | PrintOperation |
| C08 | UpdateTemplateDraftReq | expectedRevision,payloadSchemaVersion,paperWidthMm,content,compatibleCapabilities | PrintOperation |
| C09 | PreviewTemplateReq | sampleSourceRef,paperWidthMm | PreviewVO |
| C10 | PublishTemplateReq | expectedRevision,expectedTemplateRevision | PrintOperation |
| C11 | ListTemplateVersionsQuery | page,size | PageData<TemplateVersionVO> |
| C12 | ListRoutesQuery | storeNo,scene?,page,size | PageData<RouteVO> |
| C13 | CreateRouteReq | storeNo,scene | PrintOperation |
| C14 | CreateRouteVersionReq | expectedRevision,rules | PrintOperation |
| C15 | DryRunRouteReq | sourceRef | RouteDryRunVO |
| C16 | PublishRouteReq | expectedRevision,expectedRouteRevision | PrintOperation |
| C17 | ListRouteVersionsQuery | page,size | PageData<RouteVersionVO> |
| C18 | PreviewResolutionReq | storeNo,scene,sourceRef,printerNo,templateSelection? | TemplateResolutionPreviewVO |
| C19 | GetSceneFieldsQuery | storeNo,payloadSchemaVersion | SceneFieldCatalogVO |
| C20 | GetSceneExampleQuery | storeNo,variantCode | GetSceneExampleVO |

### ai.neargo.shop.print.api.dto.PrintJobDtos

模块：`print-api`；种类：`dto-container`；任务：`PRT-10`。

按业务模块组织record Req/Query/VO，不含Spring/Servlet注解；字段沿对应API合同

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/dto/PrintJobDtos.java`。

| API | 请求类型 | 业务输入字段 | 返回类型 |
|---|---|---|---|
| J01 | ListRequestsQuery | storeNo,orderNo?,subOrderNo?,scene?,status?,from?,to?,page,size | PageData<PrintRequestVO> |
| J02 | 无Body/Query | — | RequestDetailVO |
| J03 | SubmitPrintRequestReq | storeNo,sourceRef,scene,expectedSourceRevision,printMode,outputOptions? | PrintOperation |
| J04 | RetryGenerationReq | expectedRevision,reason | PrintOperation |
| J05 | 无Body/Query | — | PrintBatchVO |
| J06 | ListJobsQuery | storeNo,requestNo?,batchNo?,orderNo?,scene?,status?,jobKind?,printerNo?,from?,to?,page,size | PageData<PrintJobVO> |
| J07 | 无Body/Query | — | PrintJobView |
| J08 | ListAttemptsQuery | page,size | PageData<PrintAttemptVO> |
| J09 | ListReceiptsQuery | page,size | PageData<PrintReceiptVO> |
| J10 | 无Body/Query | — | DocumentPreviewVO |
| J11 | GetOrderSummaryQuery | storeNo | OrderPrintSummaryVO |
| J12 | 无Body/Query | — | PrintOperation |
| J13 | ReprintReq | ReprintReq | PrintOperation |
| J14 | BatchReprintReq | storeNo,jobs[{jobNo,expectedRevision}],reason,targetPrinterNo?,riskAcknowledged | PrintOperation |
| J15 | ExtraCopiesReq | expectedRevision,copies,reason,targetPrinterNo? | PrintOperation |
| J16 | RetryJobReq | expectedRevision,reason | PrintOperation |
| J17 | CancelJobReq | expectedRevision,reason | PrintOperation |
| J18 | QueryResultReq | expectedRevision | PrintOperation |
| J19 | ConfirmManuallyReq | ManualConfirmationReq | PrintOperation |
| J20 | ReleaseStaleReq | expectedRevision,reason,expectedSourceRevision | PrintOperation |
| J21 | ResolveDeviceUncertaintyReq | DeviceResolutionReq | PrintOperation |

### ai.neargo.shop.print.api.dto.PrintProtocolDtos

模块：`print-api`；种类：`dto-container`；任务：`PRT-10`。

按业务模块组织record Req/Query/VO，不含Spring/Servlet注解；字段沿对应API合同

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/dto/PrintProtocolDtos.java`。

嵌套record字段：

```java
public record DeviceClaimVO(String claimNo, List<ClaimDispatch> dispatches) {}
public record DeviceAcceptDispatchVO(boolean accepted, Instant leaseUntil, String leaseToken) {}
public record DeviceRenewLeaseVO(Instant leaseUntil, String leaseToken) {}
public record ClaimDispatch(String dispatchKey, String jobNo, String attemptNo, String printerNo, long sessionEpoch, long fenceEpoch, long connectionRevision, String leaseToken, Instant leaseUntil, Instant executeBefore, PrintLayout layout, String documentHash, FrozenJson connection, boolean cancelRequested) {}
public record AgentReceiptEvent(String eventNo, String dispatchKey, String attemptNo, long fenceEpoch, long sequence, ObservedPrintStatus status, PrintEvidenceLevel evidenceLevel, String providerJobId, Instant occurredAt, FrozenJson details) {}
public record RecoveryDispatch(String dispatchKey, String attemptNo, ObservedPrintStatus localStatus, String providerJobId) {}
public record PrinterObservation(String printerNo, long connectionRevision, Instant observedAt, PrinterHealthStatus health, FrozenJson capabilitySummary) {}
```

关键协议请求record：

```java
public record DeviceEnrollReq(String agentNo, String enrollmentCode, String clientPublicKey, String requestId, String platform, String softwareVersion) {}
public record DeviceOpenSessionReq(String requestId, String softwareVersion, Long previousSessionEpoch) {}
public record DeviceHeartbeatReq(String requestId, long sessionEpoch, List<PrinterObservation> printers, int localPendingCount) {}
public record DeviceClaimReq(String requestId, long sessionEpoch, List<String> printerNos, int maxJobs, int waitSeconds) {}
public record DeviceAcceptDispatchReq(String requestId, long sessionEpoch, long fenceEpoch, String leaseToken, String documentHash) {}
public record DeviceRenewLeaseReq(String requestId, long sessionEpoch, long fenceEpoch, String leaseToken) {}
public record DeviceReportEventsReq(long sessionEpoch, List<AgentReceiptEvent> events) {}
public record DeviceRecoverReq(String requestId, long sessionEpoch, List<RecoveryDispatch> unresolvedDispatches) {}
public record DeviceReportCommandResultReq(String requestId, long sessionEpoch, String printerNo, String commandType, String dispatchKey, String attemptNo, long connectionRevision, Long fenceEpoch, String status, FrozenJson result) {}
```

| API | 请求类型 | 业务输入字段 | 返回类型 |
|---|---|---|---|
| D01 | DeviceEnrollReq | agentNo,enrollmentCode,clientPublicKey,requestId,platform,softwareVersion | DeviceEnrollVO |
| D02 | DeviceOpenSessionReq | requestId,softwareVersion,previousSessionEpoch? | DeviceOpenSessionVO |
| D03 | DeviceHeartbeatReq | requestId,sessionEpoch,printers[],localPendingCount | DeviceHeartbeatVO |
| D04 | DeviceClaimReq | requestId,sessionEpoch,printerNos,maxJobs=1,waitSeconds=0..25 | DeviceClaimVO |
| D05 | DeviceAcceptDispatchReq | requestId,sessionEpoch,fenceEpoch,leaseToken,documentHash | DeviceAcceptDispatchVO |
| D06 | DeviceRenewLeaseReq | requestId,sessionEpoch,fenceEpoch,leaseToken | DeviceRenewLeaseVO |
| D07 | DeviceReportEventsReq | sessionEpoch,events[] | DeviceReportEventsVO |
| D08 | 无Body/Query | — | DeviceGetDispatchVO |
| D09 | DeviceRecoverReq | requestId,sessionEpoch,unresolvedDispatches[] | DeviceRecoverVO |
| D10 | DeviceReportCommandResultReq | requestId,sessionEpoch,printerNo,commandType,dispatchKey?,attemptNo?,connectionRevision,fenceEpoch?,status,result | DeviceReportCommandResultVO |

### ai.neargo.shop.print.api.dto.PrintMonitorDtos

模块：`print-api`；种类：`dto-container`；任务：`PRT-10`。

按业务模块组织record Req/Query/VO，不含Spring/Servlet注解；字段沿对应API合同

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/dto/PrintMonitorDtos.java`。

| API | 请求类型 | 业务输入字段 | 返回类型 |
|---|---|---|---|
| M01 | GetHealthQuery | storeNo | GetHealthVO |
| M02 | ListIncidentsQuery | storeNo,status?,severity?,targetNo?,page,size | PageData<IncidentVO> |
| M03 | AcknowledgeIncidentReq | expectedRevision,reason | PrintOperation |
| M04 | ListActionLogsQuery | storeNo,targetType,targetNo,page,size | PageData<ActionLogVO> |
| M05 | ListChangesQuery | storeNo,afterCursor?,limit=1..100 | ChangePageVO |
| M06 | OpsReconcileReq | tenantNo,entityNo,storeNo,sourceKind,from,to,dryRun,reason | PrintOperation |
| M07 | OpsGetOperationQuery | 目标范围上下文 | PrintOperation |
| M08 | OpsGetFleetHealthQuery | tenantNo,entityNo?,storeNo? | FleetHealthVO |
| M09 | OpsListIncidentsQuery | tenantNo,entityNo?,storeNo?,status?,page,size | PageData<IncidentVO> |

### ai.neargo.shop.print.api.dto.PrintSimulationDtos

模块：`print-api`；种类：`dto-container`；任务：`PRT-10`。

按业务模块组织record Req/Query/VO，不含Spring/Servlet注解；字段沿对应API合同

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/dto/PrintSimulationDtos.java`。

| API | 请求类型 | 业务输入字段 | 返回类型 |
|---|---|---|---|
| S01 | SimulationReq | SimulationReq | PrintOperation |
| S02 | SimulationQuery | storeNo,scene?,status?,page,size | PageData<SimulationView> |
| S03 | 无Body/Query | — | SimulationView |

### ai.neargo.shop.print.api.dto.PrintExportDtos

模块：`print-api`；种类：`dto-container`；任务：`PRT-10`。

按业务模块组织record Req/Query/VO，不含Spring/Servlet注解；字段沿对应API合同

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/dto/PrintExportDtos.java`。

| API | 请求类型 | 业务输入字段 | 返回类型 |
|---|---|---|---|
| E01 | ExportReq | ExportReq | PrintOperation |
| E02 | ExportQuery | storeNo,sourceType?,sourceNo?,status?,page,size | PageData<ExportView> |
| E03 | 无Body/Query | — | ExportView |
| E04 | 无Body/Query | — | DownloadGrant |

### ai.neargo.shop.portal.biz.BizPrintDeviceController

模块：`shop-app`；种类：`controller`；任务：`PRT-10`。

仅路径/请求绑定、当前认证和功能权限；返回业务VO/PageData，不返回实体

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPrintDeviceController.java`。

直接依赖：`PrintDeviceService`。

```java
List<AdapterVO> listAdapters(ListAdaptersQuery query);
PageData<PrinterVO> listPrinters(ListPrintersQuery query);
PrintOperation createPrinter(CreatePrinterReq request, String idempotencyKey);
PrinterDetailVO getPrinter(String printerNo);
PrintOperation updatePrinter(String printerNo, UpdatePrinterReq request, String idempotencyKey);
PrintOperation changeConnection(String printerNo, ChangeConnectionReq request, String idempotencyKey);
PrintOperation changeStatus(String printerNo, ChangeStatusReq request, String idempotencyKey);
PrintOperation rotateCredential(String printerNo, RotateCredentialReq request, String idempotencyKey);
PrintOperation probe(String printerNo, ProbeReq request, String idempotencyKey);
PrintOperation testPrint(String printerNo, TestPrintReq request, String idempotencyKey);
```

### ai.neargo.shop.portal.biz.BizPrintAgentController

模块：`shop-app`；种类：`controller`；任务：`PRT-10`。

仅路径/请求绑定、当前认证和功能权限；返回业务VO/PageData，不返回实体

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPrintAgentController.java`。

直接依赖：`PrintAgentService`。

```java
PageData<AgentVO> listAgents(ListAgentsQuery query);
PrintOperation createAgent(CreateAgentReq request, String idempotencyKey);
AgentDetailVO getAgent(String agentNo);
PrintOperation issueEnrollment(String agentNo, IssueEnrollmentReq request, String idempotencyKey);
PrintOperation revokeAgent(String agentNo, RevokeAgentReq request, String idempotencyKey);
PrintOperation rotateAgentCredential(String agentNo, RotateAgentCredentialReq request, String idempotencyKey);
```

### ai.neargo.shop.portal.biz.BizPrintSceneController

模块：`shop-app`；种类：`controller`；任务：`PRT-10`。

仅路径/请求绑定、当前认证和功能权限；返回业务VO/PageData，不返回实体

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPrintSceneController.java`。

直接依赖：`PrintScenePolicyService`。

```java
List<SceneVO> listScenes(ListScenesQuery query);
List<ScenePolicyVO> listScenePolicies(ListScenePoliciesQuery query);
PrintOperation saveScenePolicy(String scene, SaveScenePolicyReq request, String idempotencyKey);
PageData<PolicyVersionVO> listPolicyVersions(String scene, ListPolicyVersionsQuery query);
SceneFieldCatalogVO getSceneFields(String scene, GetSceneFieldsQuery query);
GetSceneExampleVO getSceneExample(String scene, GetSceneExampleQuery query);
```

### ai.neargo.shop.portal.biz.BizPrintTemplateController

模块：`shop-app`；种类：`controller`；任务：`PRT-10`。

仅路径/请求绑定、当前认证和功能权限；返回业务VO/PageData，不返回实体

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPrintTemplateController.java`。

直接依赖：`PrintTemplateService`。

```java
PageData<TemplateVO> listTemplates(ListTemplatesQuery query);
PrintOperation createTemplate(CreateTemplateReq request, String idempotencyKey);
PrintOperation createTemplateVersion(String templateNo, CreateTemplateVersionReq request, String idempotencyKey);
PrintOperation updateTemplateDraft(String versionNo, UpdateTemplateDraftReq request, String idempotencyKey);
PreviewVO previewTemplate(String versionNo, PreviewTemplateReq request);
PrintOperation publishTemplate(String versionNo, PublishTemplateReq request, String idempotencyKey);
PageData<TemplateVersionVO> listTemplateVersions(String templateNo, ListTemplateVersionsQuery query);
TemplateResolutionPreviewVO previewResolution(PreviewResolutionReq request);
```

### ai.neargo.shop.portal.biz.BizPrintRouteController

模块：`shop-app`；种类：`controller`；任务：`PRT-10`。

仅路径/请求绑定、当前认证和功能权限；返回业务VO/PageData，不返回实体

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPrintRouteController.java`。

直接依赖：`PrintRouteService`。

```java
PageData<RouteVO> listRoutes(ListRoutesQuery query);
PrintOperation createRoute(CreateRouteReq request, String idempotencyKey);
PrintOperation createRouteVersion(String routeNo, CreateRouteVersionReq request, String idempotencyKey);
RouteDryRunVO dryRunRoute(String versionNo, DryRunRouteReq request);
PrintOperation publishRoute(String versionNo, PublishRouteReq request, String idempotencyKey);
PageData<RouteVersionVO> listRouteVersions(String routeNo, ListRouteVersionsQuery query);
```

### ai.neargo.shop.portal.biz.BizPrintRequestController

模块：`shop-app`；种类：`controller`；任务：`PRT-10`。

仅路径/请求绑定、当前认证和功能权限；返回业务VO/PageData，不返回实体

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPrintRequestController.java`。

直接依赖：`PrintFacade`, `PrintRequestService`。

```java
PageData<PrintRequestVO> listRequests(ListRequestsQuery query);
RequestDetailVO getRequest(String requestNo);
PrintOperation submitBySource(SubmitPrintRequestReq request, String idempotencyKey);
PrintOperation retryGeneration(String requestNo, RetryGenerationReq request, String idempotencyKey);
```

### ai.neargo.shop.portal.biz.BizPrintJobQueryController

模块：`shop-app`；种类：`controller`；任务：`PRT-10`。

仅路径/请求绑定、当前认证和功能权限；返回业务VO/PageData，不返回实体

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPrintJobQueryController.java`。

直接依赖：`PrintQueryService`。

```java
PrintBatchVO getBatch(String batchNo);
PageData<PrintJobVO> listJobs(ListJobsQuery query);
PrintJobView getJob(String jobNo);
PageData<PrintAttemptVO> listAttempts(String jobNo, ListAttemptsQuery query);
PageData<PrintReceiptVO> listReceipts(String jobNo, ListReceiptsQuery query);
DocumentPreviewVO getDocument(String jobNo);
OrderPrintSummaryVO getOrderSummary(String orderNo, GetOrderSummaryQuery query);
PrintOperation getOperation(String operationNo);
```

### ai.neargo.shop.portal.biz.BizPrintJobCommandController

模块：`shop-app`；种类：`controller`；任务：`PRT-10`。

仅路径/请求绑定、当前认证和功能权限；返回业务VO/PageData，不返回实体

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPrintJobCommandController.java`。

直接依赖：`PrintJobCommandService`, `PrintReprintService`。

```java
PrintOperation reprint(String jobNo, ReprintReq request, String idempotencyKey);
PrintOperation batchReprint(BatchReprintReq request, String idempotencyKey);
PrintOperation extraCopies(String jobNo, ExtraCopiesReq request, String idempotencyKey);
PrintOperation retryJob(String jobNo, RetryJobReq request, String idempotencyKey);
PrintOperation cancelJob(String jobNo, CancelJobReq request, String idempotencyKey);
PrintOperation queryResult(String jobNo, QueryResultReq request, String idempotencyKey);
PrintOperation confirmManually(String jobNo, ConfirmManuallyReq request, String idempotencyKey);
PrintOperation releaseStale(String jobNo, ReleaseStaleReq request, String idempotencyKey);
PrintOperation resolveDeviceUncertainty(String printerNo, ResolveDeviceUncertaintyReq request, String idempotencyKey);
```

### ai.neargo.shop.portal.device.DevicePrintController

模块：`shop-app`；种类：`controller`；任务：`PRT-13`。

仅路径/请求绑定、当前认证和功能权限；返回业务VO/PageData，不返回实体

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/device/DevicePrintController.java`。

直接依赖：`PrintAgentProtocolService`。

```java
DeviceEnrollVO enroll(DeviceEnrollReq request);
DeviceOpenSessionVO openSession(DeviceOpenSessionReq request);
DeviceHeartbeatVO heartbeat(DeviceHeartbeatReq request);
DeviceClaimVO claim(DeviceClaimReq request);
DeviceAcceptDispatchVO acceptDispatch(String dispatchKey, DeviceAcceptDispatchReq request);
DeviceRenewLeaseVO renewLease(String dispatchKey, DeviceRenewLeaseReq request);
DeviceReportEventsVO reportEvents(DeviceReportEventsReq request);
DeviceGetDispatchVO getDispatch(String dispatchKey);
DeviceRecoverVO recover(DeviceRecoverReq request);
DeviceReportCommandResultVO reportCommandResult(String commandNo, DeviceReportCommandResultReq request);
```

### ai.neargo.shop.portal.biz.BizPrintMonitorController

模块：`shop-app`；种类：`controller`；任务：`PRT-10`。

仅路径/请求绑定、当前认证和功能权限；返回业务VO/PageData，不返回实体

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPrintMonitorController.java`。

直接依赖：`PrintMonitorService`。

```java
GetHealthVO getHealth(GetHealthQuery query);
PageData<IncidentVO> listIncidents(ListIncidentsQuery query);
PrintOperation acknowledgeIncident(String incidentNo, AcknowledgeIncidentReq request, String idempotencyKey);
PageData<ActionLogVO> listActionLogs(ListActionLogsQuery query);
ChangePageVO listChanges(ListChangesQuery query);
```

### ai.neargo.shop.portal.ops.OpsPrintController

模块：`shop-app`；种类：`controller`；任务：`PRT-10`。

仅路径/请求绑定、当前认证和功能权限；返回业务VO/PageData，不返回实体

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/ops/OpsPrintController.java`。

直接依赖：`PrintOpsService`。

```java
PrintOperation reconcile(OpsReconcileReq request, String idempotencyKey);
PrintOperation getOperation(String operationNo, OpsGetOperationQuery query);
FleetHealthVO getFleetHealth(OpsGetFleetHealthQuery query);
PageData<IncidentVO> listIncidents(OpsListIncidentsQuery query);
```

### ai.neargo.shop.portal.biz.BizPrintSimulationController

模块：`shop-app`；种类：`controller`；任务：`PRT-10`。

仅路径/请求绑定、当前认证和功能权限；返回业务VO/PageData，不返回实体

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPrintSimulationController.java`。

直接依赖：`PrintSimulationFacade`。

```java
PrintOperation create(SimulationReq request, String idempotencyKey);
PageData<SimulationView> page(SimulationQuery query);
SimulationView get(String simulationNo);
```

### ai.neargo.shop.portal.biz.BizPrintExportController

模块：`shop-app`；种类：`controller`；任务：`PRT-10`。

仅路径/请求绑定、当前认证和功能权限；返回业务VO/PageData，不返回实体

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPrintExportController.java`。

直接依赖：`PrintExportFacade`。

```java
PrintOperation create(ExportReq request, String idempotencyKey);
PageData<ExportView> page(ExportQuery query);
ExportView get(String exportNo);
DownloadGrant download(String exportNo);
```

### ai.neargo.shop.print.template.PrintSceneRegistry

模块：`print-domain`；种类：`class`；任务：`PRT-03`。

20场景schema/强制字段/触发白名单注册

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/template/PrintSceneRegistry.java`。

```java
SceneDefinition require(String scene);
void validateSource(PrintFact fact);
```

### ai.neargo.shop.print.source.PrintSourceRegistry

模块：`print-domain`；种类：`class`；任务：`PRT-03`。

按sourceTypes选择可信所有者，空集合允许启动，重复注册启动失败

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/source/PrintSourceRegistry.java`。

```java
PrintSourceFactsPort require(String sourceType);
```

### ai.neargo.shop.print.template.DefaultPrintTemplateResolver

模块：`print-domain`；种类：`class`；任务：`PRT-03`。

配置头/版本一致读取，默认降级与固定模式阻断

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/template/DefaultPrintTemplateResolver.java`。

实现：`PrintTemplateResolver`。

直接依赖：`PrintTemplateResolver`, `PrintSceneRegistry`。

```java
ResolvedTemplate resolve(TemplateResolutionCommand command);
```

### ai.neargo.shop.print.template.DefaultPrintDocumentCompiler

模块：`print-domain`；种类：`class`；任务：`PRT-03`。

限定字段与typed模板AST编译成冻结业务文档

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/template/DefaultPrintDocumentCompiler.java`。

实现：`PrintDocumentCompiler`。

直接依赖：`PrintDocumentCompiler`, `PrintSceneRegistry`, `PrintJsonCodec`。

```java
PrintDocument compile(PrintFact fact, ResolvedTemplate template, SystemPrintSlots slots);
```

### ai.neargo.shop.print.layout.DefaultPrintLayoutEngine

模块：`print-domain`；种类：`class`；任务：`PRT-03`。

按同字体度量分页并保留受控插槽

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/layout/DefaultPrintLayoutEngine.java`。

实现：`PrintLayoutEngine`。

直接依赖：`PrintLayoutEngine`, `PrintFontMetricsPort`, `PrintSystemSlotPolicy`。

```java
PrintLayout layout(PrintDocument document, LayoutProfile profile);
```

### ai.neargo.shop.print.layout.LayoutProfileRegistry

模块：`print-domain`；种类：`class`；任务：`PRT-03`。

注册版本化纸宽/边距/DPI/字体档案

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/layout/LayoutProfileRegistry.java`。

```java
LayoutProfile require(String code, String version);
```

### ai.neargo.shop.print.layout.PrintSystemSlotPolicy

模块：`print-domain`；种类：`class`；任务：`PRT-03`。

生成初打/补打/加印/未知原票/模拟及导出系统块

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/layout/PrintSystemSlotPolicy.java`。

```java
SystemPrintSlots initial(PrintFact fact, String jobNo, int copyIndex);
PrintLayout instantiateCopy(PrintLayout source, SystemPrintSlots newSlots);
```

### ai.neargo.shop.print.support.PrintJsonCodec

模块：`print-domain`；种类：`class`；任务：`PRT-03`。

Jackson3规范JSON＋hash＋深度/大小限制

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/support/PrintJsonCodec.java`。

```java
FrozenJson freeze(String json);
void verify(FrozenJson json);
```

### ai.neargo.shop.print.dispatch.PrintStatePolicy

模块：`print-domain`；种类：`class`；任务：`PRT-04`。

纯状态机：不依赖Mapper/Clock/外网，模拟可复用

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/dispatch/PrintStatePolicy.java`。

```java
PrintTransition decide(PrintStateSnapshot before, PrintObservation observation);
```

### ai.neargo.shop.print.dispatch.PrintDispatchService

模块：`print-domain`；种类：`interface`；任务：`PRT-04`。

接口：云端准备、提交、查询；LOCAL由设备领取路径准备

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/dispatch/PrintDispatchService.java`。

```java
PrintWorkReport dispatchCloud(PrintWorkerBudget budget);
void reconcileAttempt(PrintAttemptRef ref);
```

### ai.neargo.shop.print.dispatch.impl.PrintDispatchServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-04`。

短事务prepare→事务外网络→短事务收敛

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/dispatch/impl/PrintDispatchServiceImpl.java`。

实现：`PrintDispatchService`。

直接依赖：`PrintDispatchService`。

```java
PrintWorkReport dispatchCloud(PrintWorkerBudget budget);
void reconcileAttempt(PrintAttemptRef ref);
```

### ai.neargo.shop.print.dispatch.PrinterLeaseService

模块：`print-domain`；种类：`interface`；任务：`PRT-04`。

接口：单物理机串行、fence和未决闸门

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/dispatch/PrinterLeaseService.java`。

```java
PreparedPrintAttempt prepare(PrintScope scope, String printerNo, String workerNo);
void releaseIfCurrent(PrintScope scope, String printerNo, String attemptNo, long fenceEpoch);
```

### ai.neargo.shop.print.dispatch.impl.PrinterLeaseServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-04`。

按stream/runtime/rootJob顺序锁定并CAS

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/dispatch/impl/PrinterLeaseServiceImpl.java`。

实现：`PrinterLeaseService`。

直接依赖：`PrinterLeaseService`。

```java
PreparedPrintAttempt prepare(PrintScope scope, String printerNo, String workerNo);
void releaseIfCurrent(PrintScope scope, String printerNo, String attemptNo, long fenceEpoch);
```

### ai.neargo.shop.print.dispatch.PrintReceiptService

模块：`print-domain`；种类：`interface`；任务：`PRT-04`。

接口：可信证据收件箱与状态应用

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/dispatch/PrintReceiptService.java`。

```java
ReceiptAck ingest(VerifiedPrintReceipt receipt);
PrintWorkReport applyPending(PrintWorkerBudget budget);
```

### ai.neargo.shop.print.dispatch.impl.PrintReceiptServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-04`。

证据去重、乱序裁决、任务/批次/Outbox原子更新

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/dispatch/impl/PrintReceiptServiceImpl.java`。

实现：`PrintReceiptService`。

直接依赖：`PrintReceiptService`。

```java
ReceiptAck ingest(VerifiedPrintReceipt receipt);
PrintWorkReport applyPending(PrintWorkerBudget budget);
```

### ai.neargo.shop.print.dispatch.PrintAdapterRegistry

模块：`print-domain`；种类：`class`；任务：`PRT-04`。

只接受真实云端PrintDispatchPort，不注册模拟；空集合可启动，调用不存在adapter明确不可用

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/dispatch/PrintAdapterRegistry.java`。

```java
PrintDispatchPort requireCloud(String adapterCode);
```

### ai.neargo.shop.print.operation.PrintOperationService

模块：`print-domain`；种类：`interface`；任务：`PRT-02`。

接口：命令指纹及durable操作台账

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/operation/PrintOperationService.java`。

```java
PrintOperation execute(PrintActor actor, String commandType, String requestHash, Supplier<PrintOperation> localAction);
```

### ai.neargo.shop.print.operation.impl.PrintOperationServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-02`。

业务写副作用与operation同事务；外网动作只登记不等待

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/operation/impl/PrintOperationServiceImpl.java`。

实现：`PrintOperationService`。

直接依赖：`PrintOperationService`。

```java
PrintOperation execute(PrintActor actor, String commandType, String requestHash, Supplier<PrintOperation> localAction);
```

### ai.neargo.shop.print.dispatch.PrintBatchProjection

模块：`print-domain`；种类：`class`；任务：`PRT-04`。

根据原票和replacement关系计算批次实际/解决状态

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/dispatch/PrintBatchProjection.java`。

```java
BatchCounts calculate(List<PrintJobSnapshot> jobs);
```

### ai.neargo.shop.print.simulation.SimulationRunner

模块：`print-domain`；种类：`class`；任务：`PRT-05`。

确定性虚拟时钟执行，CAS一次结算

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/simulation/SimulationRunner.java`。

直接依赖：`SimulatedDispatchPort`, `PrintStatePolicy`, `SimulationFixtures`。

```java
PrintWorkReport runNext(PrintWorkerBudget budget);
```

### ai.neargo.shop.print.simulation.SimulatedDispatchPort

模块：`print-domain`；种类：`interface`；任务：`PRT-05`。

仅模拟接口，不extends或implements生产PrintDispatchPort

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/simulation/SimulatedDispatchPort.java`。

```java
SimulationStep submit(SimulationDispatch dispatch);
SimulationStep query(SimulationDispatch dispatch);
```

### ai.neargo.shop.print.simulation.SimulationFixtures

模块：`print-domain`；种类：`class`；任务：`PRT-05`。

9个固定fixture及期望轨迹，禁止脚本

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/simulation/SimulationFixtures.java`。

```java
SimulationFixture require(SimulationFixtureCode code, String version);
```

### ai.neargo.shop.print.export.PrintExportWorker

模块：`print-domain`；种类：`class`；任务：`PRT-07`。

从冻结布局生成PDF，按epoch/lease提交结果

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/export/PrintExportWorker.java`。

直接依赖：`PrintPdfPort`, `PrintArtifactStorePort`。

```java
PrintWorkReport generateNext(PrintWorkerBudget budget);
```

### ai.neargo.shop.print.export.PrintExportRecoveryJob

模块：`print-domain`；种类：`class`；任务：`PRT-07`。

对GENERATING/上传中断按私有字节hash恢复

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/export/PrintExportRecoveryJob.java`。

```java
PrintWorkReport recover(PrintWorkerBudget budget);
```

### ai.neargo.shop.print.export.PrintExportCleanupJob

模块：`print-domain`；种类：`class`；任务：`PRT-07`。

先EXPIRED再幂等删除私有文件

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/export/PrintExportCleanupJob.java`。

```java
PrintWorkReport expire(PrintWorkerBudget budget);
```

### ai.neargo.shop.print.monitor.PrintIncidentService

模块：`print-domain`；种类：`interface`；任务：`PRT-04`。

接口：故障去重、确认和证据驱动恢复；通知装配可后接

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/monitor/PrintIncidentService.java`。

```java
String open(PrintIncidentCommand command);
void resolveByEvidence(PrintScope scope, String incidentNo, FrozenJson evidence);
```

### ai.neargo.shop.print.monitor.impl.PrintIncidentServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-04`。

基础故障代次及审计先提供；确认不等于打印完成

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/monitor/impl/PrintIncidentServiceImpl.java`。

实现：`PrintIncidentService`。

直接依赖：`PrintIncidentService`。

```java
String open(PrintIncidentCommand command);
void resolveByEvidence(PrintScope scope, String incidentNo, FrozenJson evidence);
```

### ai.neargo.shop.print.monitor.PrintReconcileJob

模块：`print-domain`；种类：`class`；任务：`PRT-14`。

业务→需求→票据→设备结果对账

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/monitor/PrintReconcileJob.java`。

```java
PrintMaintenanceReport reconcile(PrintMaintenanceCommand command);
```

### ai.neargo.shop.print.audit.PrintActionAppender

模块：`print-domain`；种类：`class`；任务：`PRT-02`。

门店提交有序序列、追加审计及变更流

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/audit/PrintActionAppender.java`。

```java
long appendInCurrentTransaction(PrintScope scope, PrintAction action);
```

### ai.neargo.shop.print.dispatch.PrintStateModels

模块：`print-domain`；种类：`record-container`；任务：`PRT-04`。

包含PrintStateSnapshot/PrintObservation/PrintTransition/PreparedPrintAttempt/BatchCounts/PrintJobSnapshot等内部只读计算值

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/dispatch/PrintStateModels.java`。

嵌套类型：`PrintStateSnapshot`, `PrintObservation`, `PrintTransition`, `PreparedPrintAttempt`, `BatchCounts`, `PrintJobSnapshot`, `ReceiptAck`, `PrintIncidentCommand`, `PrintAction`。

### ai.neargo.shop.print.simulation.SimulationModels

模块：`print-domain`；种类：`record-container`；任务：`PRT-05`。

包含SimulationDispatch/SimulationStep/SimulationFixture等仅模拟值

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/simulation/SimulationModels.java`。

嵌套类型：`SimulationDispatch`, `SimulationStep`, `SimulationFixture`。

### ai.neargo.shop.print.persistence.entity.PrnRow

模块：`print-domain`；种类：`abstract-class`；任务：`PRT-02`。

仅含DDL共有六列，不extends BaseEntity，不含逻辑删除

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnRow.java`。

字段合同：`Long id, String tenantNo, String entityNo, Long revision, LocalDateTime createdAt, LocalDateTime updatedAt`。

### ai.neargo.shop.print.persistence.entity.PrnAgent

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_agent，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnAgent.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| agent_no | agentNo | String | PrnAgent |
| store_no | storeNo | String | PrnAgent |
| name | name | String | PrnAgent |
| platform | platform | String | PrnAgent |
| status | status | String | PrnAgent |
| enrollment_hash | enrollmentHash | String | PrnAgent |
| enrollment_expires_at | enrollmentExpiresAt | LocalDateTime | PrnAgent |
| credential_hash | credentialHash | String | PrnAgent |
| credential_version | credentialVersion | Integer | PrnAgent |
| session_epoch | sessionEpoch | Long | PrnAgent |
| software_version | softwareVersion | String | PrnAgent |
| capabilities | capabilities | String | PrnAgent |
| last_seen_at | lastSeenAt | LocalDateTime | PrnAgent |
| revoked_at | revokedAt | LocalDateTime | PrnAgent |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnPrinter

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_printer，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnPrinter.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| printer_no | printerNo | String | PrnPrinter |
| store_no | storeNo | String | PrnPrinter |
| name | name | String | PrnPrinter |
| physical_key | physicalKey | String | PrnPrinter |
| connection_type | connectionType | String | PrnPrinter |
| adapter_code | adapterCode | String | PrnPrinter |
| vendor_account_ref | vendorAccountRef | String | PrnPrinter |
| device_sn | deviceSn | String | PrnPrinter |
| agent_no | agentNo | String | PrnPrinter |
| connection_config | connectionConfig | String | PrnPrinter |
| credential_ref | credentialRef | String | PrnPrinter |
| paper_width_mm | paperWidthMm | Integer | PrnPrinter |
| capabilities | capabilities | String | PrnPrinter |
| status | status | String | PrnPrinter |
| health_status | healthStatus | String | PrnPrinter |
| health_reason | healthReason | String | PrnPrinter |
| health_observed_at | healthObservedAt | LocalDateTime | PrnPrinter |
| connection_revision | connectionRevision | Long | PrnPrinter |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnCredentialVersion

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_credential_version，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnCredentialVersion.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| credential_ref | credentialRef | String | PrnCredentialVersion |
| printer_no | printerNo | String | PrnCredentialVersion |
| version_number | versionNumber | Integer | PrnCredentialVersion |
| credential_ciphertext | credentialCiphertext | String | PrnCredentialVersion |
| encryption_key_version | encryptionKeyVersion | String | PrnCredentialVersion |
| status | status | String | PrnCredentialVersion |
| retired_at | retiredAt | LocalDateTime | PrnCredentialVersion |
| created_by | createdBy | String | PrnCredentialVersion |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnPrinterRuntime

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_printer_runtime，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnPrinterRuntime.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| printer_no | printerNo | String | PrnPrinterRuntime |
| active_job_no | activeJobNo | String | PrnPrinterRuntime |
| active_attempt_no | activeAttemptNo | String | PrnPrinterRuntime |
| lease_owner | leaseOwner | String | PrnPrinterRuntime |
| lease_token_hash | leaseTokenHash | String | PrnPrinterRuntime |
| lease_until | leaseUntil | LocalDateTime | PrnPrinterRuntime |
| fence_epoch | fenceEpoch | Long | PrnPrinterRuntime |
| blocked_reason | blockedReason | String | PrnPrinterRuntime |
| last_dispatch_at | lastDispatchAt | LocalDateTime | PrnPrinterRuntime |
| last_sequence | lastSequence | Long | PrnPrinterRuntime |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnTemplate

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_template，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnTemplate.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| template_no | templateNo | String | PrnTemplate |
| scope_key | scopeKey | String | PrnTemplate |
| store_no | storeNo | String | PrnTemplate |
| scene | scene | String | PrnTemplate |
| variant_code | variantCode | String | PrnTemplate |
| name | name | String | PrnTemplate |
| current_version_no | currentVersionNo | String | PrnTemplate |
| status | status | String | PrnTemplate |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnTemplateVersion

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_template_version，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnTemplateVersion.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| template_version_no | templateVersionNo | String | PrnTemplateVersion |
| template_no | templateNo | String | PrnTemplateVersion |
| version_number | versionNumber | Integer | PrnTemplateVersion |
| format | format | String | PrnTemplateVersion |
| schema_version | schemaVersion | Integer | PrnTemplateVersion |
| payload_schema_version | payloadSchemaVersion | Integer | PrnTemplateVersion |
| paper_width_mm | paperWidthMm | Integer | PrnTemplateVersion |
| content | content | String | PrnTemplateVersion |
| content_hash | contentHash | String | PrnTemplateVersion |
| compatible_capabilities | compatibleCapabilities | String | PrnTemplateVersion |
| status | status | String | PrnTemplateVersion |
| published_at | publishedAt | LocalDateTime | PrnTemplateVersion |
| created_by | createdBy | String | PrnTemplateVersion |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnRoute

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_route，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnRoute.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| route_no | routeNo | String | PrnRoute |
| store_no | storeNo | String | PrnRoute |
| scene | scene | String | PrnRoute |
| current_version_no | currentVersionNo | String | PrnRoute |
| status | status | String | PrnRoute |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnRouteVersion

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_route_version，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnRouteVersion.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| route_version_no | routeVersionNo | String | PrnRouteVersion |
| route_no | routeNo | String | PrnRouteVersion |
| version_number | versionNumber | Integer | PrnRouteVersion |
| rules | rules | String | PrnRouteVersion |
| content_hash | contentHash | String | PrnRouteVersion |
| status | status | String | PrnRouteVersion |
| published_at | publishedAt | LocalDateTime | PrnRouteVersion |
| created_by | createdBy | String | PrnRouteVersion |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnScenePolicy

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_scene_policy，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnScenePolicy.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| policy_no | policyNo | String | PrnScenePolicy |
| store_no | storeNo | String | PrnScenePolicy |
| scene | scene | String | PrnScenePolicy |
| current_version_no | currentVersionNo | String | PrnScenePolicy |
| enabled | enabled | Integer | PrnScenePolicy |
| critical | critical | Integer | PrnScenePolicy |
| trigger_types | triggerTypes | String | PrnScenePolicy |
| fallback_printer_no | fallbackPrinterNo | String | PrnScenePolicy |
| submit_timeout_seconds | submitTimeoutSeconds | Integer | PrnScenePolicy |
| result_timeout_seconds | resultTimeoutSeconds | Integer | PrnScenePolicy |
| max_retry_count | maxRetryCount | Integer | PrnScenePolicy |
| backlog_alert_seconds | backlogAlertSeconds | Integer | PrnScenePolicy |
| stale_after_seconds | staleAfterSeconds | Integer | PrnScenePolicy |
| notification_policy | notificationPolicy | String | PrnScenePolicy |
| output_options | outputOptions | String | PrnScenePolicy |
| policy_snapshot_hash | policySnapshotHash | String | PrnScenePolicy |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnPolicyVersion

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_policy_version，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnPolicyVersion.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| policy_version_no | policyVersionNo | String | PrnPolicyVersion |
| policy_no | policyNo | String | PrnPolicyVersion |
| version_number | versionNumber | Integer | PrnPolicyVersion |
| effective_from | effectiveFrom | LocalDateTime | PrnPolicyVersion |
| policy_snapshot | policySnapshot | String | PrnPolicyVersion |
| content_hash | contentHash | String | PrnPolicyVersion |
| created_by | createdBy | String | PrnPolicyVersion |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnRequest

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_request，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnRequest.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| request_no | requestNo | String | PrnRequest |
| store_no | storeNo | String | PrnRequest |
| order_no | orderNo | String | PrnRequest |
| sub_order_no | subOrderNo | String | PrnRequest |
| biz_type | bizType | String | PrnRequest |
| biz_no | bizNo | String | PrnRequest |
| biz_revision | bizRevision | String | PrnRequest |
| scene | scene | String | PrnRequest |
| trigger_type | triggerType | String | PrnRequest |
| source_event_no | sourceEventNo | String | PrnRequest |
| source_ref | sourceRef | String | PrnRequest |
| trigger_key | triggerKey | String | PrnRequest |
| source_occurred_at | sourceOccurredAt | LocalDateTime | PrnRequest |
| policy_version_no | policyVersionNo | String | PrnRequest |
| policy_snapshot | policySnapshot | String | PrnRequest |
| output_options | outputOptions | String | PrnRequest |
| source_snapshot | sourceSnapshot | String | PrnRequest |
| source_snapshot_hash | sourceSnapshotHash | String | PrnRequest |
| status | status | String | PrnRequest |
| block_code | blockCode | String | PrnRequest |
| next_check_at | nextCheckAt | LocalDateTime | PrnRequest |
| deadline_at | deadlineAt | LocalDateTime | PrnRequest |
| cancelled_at | cancelledAt | LocalDateTime | PrnRequest |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnBatch

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_batch，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnBatch.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| batch_no | batchNo | String | PrnBatch |
| request_no | requestNo | String | PrnBatch |
| store_no | storeNo | String | PrnBatch |
| batch_kind | batchKind | String | PrnBatch |
| generation_key | generationKey | String | PrnBatch |
| parent_batch_no | parentBatchNo | String | PrnBatch |
| route_version_no | routeVersionNo | String | PrnBatch |
| status | status | String | PrnBatch |
| expected_count | expectedCount | Integer | PrnBatch |
| confirmed_count | confirmedCount | Integer | PrnBatch |
| failed_count | failedCount | Integer | PrnBatch |
| unknown_count | unknownCount | Integer | PrnBatch |
| cancelled_count | cancelledCount | Integer | PrnBatch |
| resolved_count | resolvedCount | Integer | PrnBatch |
| resolved_status | resolvedStatus | String | PrnBatch |
| completed_at | completedAt | LocalDateTime | PrnBatch |
| resolved_at | resolvedAt | LocalDateTime | PrnBatch |
| created_by | createdBy | String | PrnBatch |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnJob

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_job，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnJob.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| job_no | jobNo | String | PrnJob |
| request_no | requestNo | String | PrnJob |
| batch_no | batchNo | String | PrnJob |
| store_no | storeNo | String | PrnJob |
| scene | scene | String | PrnJob |
| document_key | documentKey | String | PrnJob |
| route_rule_key | routeRuleKey | String | PrnJob |
| target_key | targetKey | String | PrnJob |
| copy_index | copyIndex | Integer | PrnJob |
| required | required | Integer | PrnJob |
| printer_no | printerNo | String | PrnJob |
| template_version_no | templateVersionNo | String | PrnJob |
| renderer_version | rendererVersion | String | PrnJob |
| layout_profile_snapshot | layoutProfileSnapshot | String | PrnJob |
| layout_snapshot | layoutSnapshot | String | PrnJob |
| template_resolution_snapshot | templateResolutionSnapshot | String | PrnJob |
| payload_snapshot | payloadSnapshot | String | PrnJob |
| document_snapshot | documentSnapshot | String | PrnJob |
| document_hash | documentHash | String | PrnJob |
| route_snapshot | routeSnapshot | String | PrnJob |
| status | status | String | PrnJob |
| priority | priority | Integer | PrnJob |
| printer_sequence | printerSequence | Long | PrnJob |
| retry_count | retryCount | Integer | PrnJob |
| max_retry_count | maxRetryCount | Integer | PrnJob |
| next_retry_at | nextRetryAt | LocalDateTime | PrnJob |
| deadline_at | deadlineAt | LocalDateTime | PrnJob |
| current_attempt_no | currentAttemptNo | String | PrnJob |
| result_evidence | resultEvidence | String | PrnJob |
| confirmed_at | confirmedAt | LocalDateTime | PrnJob |
| last_error_code | lastErrorCode | String | PrnJob |
| last_error_summary | lastErrorSummary | String | PrnJob |
| parent_job_no | parentJobNo | String | PrnJob |
| root_job_no | rootJobNo | String | PrnJob |
| job_kind | jobKind | String | PrnJob |
| resolution_status | resolutionStatus | String | PrnJob |
| replacement_job_no | replacementJobNo | String | PrnJob |
| cancel_requested_at | cancelRequestedAt | LocalDateTime | PrnJob |
| finished_at | finishedAt | LocalDateTime | PrnJob |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnAttempt

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_attempt，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnAttempt.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| attempt_no | attemptNo | String | PrnAttempt |
| job_no | jobNo | String | PrnAttempt |
| printer_no | printerNo | String | PrnAttempt |
| attempt_index | attemptIndex | Integer | PrnAttempt |
| dispatch_key | dispatchKey | String | PrnAttempt |
| agent_no | agentNo | String | PrnAttempt |
| agent_session_epoch | agentSessionEpoch | Long | PrnAttempt |
| fence_epoch | fenceEpoch | Long | PrnAttempt |
| connection_revision | connectionRevision | Long | PrnAttempt |
| credential_ref | credentialRef | String | PrnAttempt |
| connection_snapshot | connectionSnapshot | String | PrnAttempt |
| adapter_version | adapterVersion | String | PrnAttempt |
| status | status | String | PrnAttempt |
| provider_scope | providerScope | String | PrnAttempt |
| provider_job_id | providerJobId | String | PrnAttempt |
| provider_reference_hash | providerReferenceHash | String | PrnAttempt |
| request_hash | requestHash | String | PrnAttempt |
| transport_hash | transportHash | String | PrnAttempt |
| submitted_at | submittedAt | LocalDateTime | PrnAttempt |
| accepted_at | acceptedAt | LocalDateTime | PrnAttempt |
| result_observed_at | resultObservedAt | LocalDateTime | PrnAttempt |
| next_query_at | nextQueryAt | LocalDateTime | PrnAttempt |
| last_error_code | lastErrorCode | String | PrnAttempt |
| result_summary | resultSummary | String | PrnAttempt |
| finished_at | finishedAt | LocalDateTime | PrnAttempt |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnReceipt

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_receipt，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnReceipt.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| receipt_no | receiptNo | String | PrnReceipt |
| attempt_no | attemptNo | String | PrnReceipt |
| source_type | sourceType | String | PrnReceipt |
| source_scope | sourceScope | String | PrnReceipt |
| source_receipt_key | sourceReceiptKey | String | PrnReceipt |
| reported_status | reportedStatus | String | PrnReceipt |
| evidence_level | evidenceLevel | String | PrnReceipt |
| source_sequence | sourceSequence | Long | PrnReceipt |
| source_occurred_at | sourceOccurredAt | LocalDateTime | PrnReceipt |
| received_at | receivedAt | LocalDateTime | PrnReceipt |
| payload_hash | payloadHash | String | PrnReceipt |
| sanitized_payload | sanitizedPayload | String | PrnReceipt |
| status | status | String | PrnReceipt |
| process_error | processError | String | PrnReceipt |
| processed_at | processedAt | LocalDateTime | PrnReceipt |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnOperation

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_operation，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnOperation.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| operation_no | operationNo | String | PrnOperation |
| store_no | storeNo | String | PrnOperation |
| operation_type | operationType | String | PrnOperation |
| actor_type | actorType | String | PrnOperation |
| actor_no | actorNo | String | PrnOperation |
| idempotency_key | idempotencyKey | String | PrnOperation |
| request_hash | requestHash | String | PrnOperation |
| target_type | targetType | String | PrnOperation |
| target_no | targetNo | String | PrnOperation |
| command_payload | commandPayload | String | PrnOperation |
| status | status | String | PrnOperation |
| result_ref | resultRef | String | PrnOperation |
| error_code | errorCode | String | PrnOperation |
| reason | reason | String | PrnOperation |
| next_check_at | nextCheckAt | LocalDateTime | PrnOperation |
| finished_at | finishedAt | LocalDateTime | PrnOperation |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnIncident

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_incident，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnIncident.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| incident_no | incidentNo | String | PrnIncident |
| store_no | storeNo | String | PrnIncident |
| incident_type | incidentType | String | PrnIncident |
| target_type | targetType | String | PrnIncident |
| target_no | targetNo | String | PrnIncident |
| incident_key | incidentKey | String | PrnIncident |
| severity | severity | String | PrnIncident |
| status | status | String | PrnIncident |
| occurrence_count | occurrenceCount | Integer | PrnIncident |
| first_seen_at | firstSeenAt | LocalDateTime | PrnIncident |
| last_seen_at | lastSeenAt | LocalDateTime | PrnIncident |
| acknowledged_by | acknowledgedBy | String | PrnIncident |
| acknowledged_at | acknowledgedAt | LocalDateTime | PrnIncident |
| resolved_at | resolvedAt | LocalDateTime | PrnIncident |
| resolution_code | resolutionCode | String | PrnIncident |
| details | details | String | PrnIncident |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnStoreStream

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_store_stream，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnStoreStream.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| store_no | storeNo | String | PrnStoreStream |
| last_sequence | lastSequence | Long | PrnStoreStream |
| retained_from_sequence | retainedFromSequence | Long | PrnStoreStream |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnActionLog

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_action_log，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnActionLog.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| log_no | logNo | String | PrnActionLog |
| store_sequence | storeSequence | Long | PrnActionLog |
| store_no | storeNo | String | PrnActionLog |
| operation_no | operationNo | String | PrnActionLog |
| target_type | targetType | String | PrnActionLog |
| target_no | targetNo | String | PrnActionLog |
| action | action | String | PrnActionLog |
| actor_type | actorType | String | PrnActionLog |
| actor_no | actorNo | String | PrnActionLog |
| before_revision | beforeRevision | Long | PrnActionLog |
| after_revision | afterRevision | Long | PrnActionLog |
| before_status | beforeStatus | String | PrnActionLog |
| after_status | afterStatus | String | PrnActionLog |
| reason | reason | String | PrnActionLog |
| safe_details | safeDetails | String | PrnActionLog |
| trace_no | traceNo | String | PrnActionLog |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnReconcileCursor

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_reconcile_cursor，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnReconcileCursor.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| cursor_no | cursorNo | String | PrnReconcileCursor |
| store_no | storeNo | String | PrnReconcileCursor |
| source_kind | sourceKind | String | PrnReconcileCursor |
| watermark_at | watermarkAt | LocalDateTime | PrnReconcileCursor |
| watermark_no | watermarkNo | String | PrnReconcileCursor |
| overlap_seconds | overlapSeconds | Integer | PrnReconcileCursor |
| last_full_scan_at | lastFullScanAt | LocalDateTime | PrnReconcileCursor |
| lease_owner | leaseOwner | String | PrnReconcileCursor |
| lease_until | leaseUntil | LocalDateTime | PrnReconcileCursor |
| last_success_at | lastSuccessAt | LocalDateTime | PrnReconcileCursor |
| last_error_code | lastErrorCode | String | PrnReconcileCursor |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnSimulationRun

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_simulation_run，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnSimulationRun.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| simulation_no | simulationNo | String | PrnSimulationRun |
| store_no | storeNo | String | PrnSimulationRun |
| operation_no | operationNo | String | PrnSimulationRun |
| scene | scene | String | PrnSimulationRun |
| source_type | sourceType | String | PrnSimulationRun |
| source_ref | sourceRef | String | PrnSimulationRun |
| fixture_code | fixtureCode | String | PrnSimulationRun |
| fixture_version | fixtureVersion | String | PrnSimulationRun |
| input_snapshot | inputSnapshot | String | PrnSimulationRun |
| template_resolution_snapshot | templateResolutionSnapshot | String | PrnSimulationRun |
| document_snapshots | documentSnapshots | String | PrnSimulationRun |
| input_hash | inputHash | String | PrnSimulationRun |
| status | status | String | PrnSimulationRun |
| expected_results | expectedResults | String | PrnSimulationRun |
| actual_results | actualResults | String | PrnSimulationRun |
| event_journal | eventJournal | String | PrnSimulationRun |
| claim_token_hash | claimTokenHash | String | PrnSimulationRun |
| lease_until | leaseUntil | LocalDateTime | PrnSimulationRun |
| next_run_at | nextRunAt | LocalDateTime | PrnSimulationRun |
| last_error_code | lastErrorCode | String | PrnSimulationRun |
| finished_at | finishedAt | LocalDateTime | PrnSimulationRun |
| created_by | createdBy | String | PrnSimulationRun |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.entity.PrnExportTask

模块：`print-domain`；种类：`entity`；任务：`PRT-02`。

仅映射prn_export_task，不跨模块返回

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/entity/PrnExportTask.java`。

继承：`PrnRow`。

直接依赖：`PrnRow`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | PrnRow |
| tenant_no | tenantNo | String | PrnRow |
| entity_no | entityNo | String | PrnRow |
| export_no | exportNo | String | PrnExportTask |
| store_no | storeNo | String | PrnExportTask |
| operation_no | operationNo | String | PrnExportTask |
| export_key | exportKey | String | PrnExportTask |
| source_type | sourceType | String | PrnExportTask |
| source_no | sourceNo | String | PrnExportTask |
| source_revision | sourceRevision | Long | PrnExportTask |
| source_snapshot | sourceSnapshot | String | PrnExportTask |
| output_profile | outputProfile | String | PrnExportTask |
| artifact_kind | artifactKind | String | PrnExportTask |
| document_hash | documentHash | String | PrnExportTask |
| renderer_version | rendererVersion | String | PrnExportTask |
| font_bundle_version | fontBundleVersion | String | PrnExportTask |
| required_data_scopes | requiredDataScopes | String | PrnExportTask |
| status | status | String | PrnExportTask |
| object_key | objectKey | String | PrnExportTask |
| content_hash | contentHash | String | PrnExportTask |
| content_type | contentType | String | PrnExportTask |
| file_size | fileSize | Long | PrnExportTask |
| page_count | pageCount | Integer | PrnExportTask |
| claim_token_hash | claimTokenHash | String | PrnExportTask |
| lease_until | leaseUntil | LocalDateTime | PrnExportTask |
| next_retry_at | nextRetryAt | LocalDateTime | PrnExportTask |
| retry_count | retryCount | Integer | PrnExportTask |
| generation_epoch | generationEpoch | Long | PrnExportTask |
| last_error_code | lastErrorCode | String | PrnExportTask |
| ready_at | readyAt | LocalDateTime | PrnExportTask |
| expires_at | expiresAt | LocalDateTime | PrnExportTask |
| created_by | createdBy | String | PrnExportTask |
| revision | revision | Long | PrnRow |
| created_at | createdAt | LocalDateTime | PrnRow |
| updated_at | updatedAt | LocalDateTime | PrnRow |

### ai.neargo.shop.print.persistence.mapper.PrintMappers

模块：`print-domain`；种类：`mapper-container`；任务：`PRT-02`。

22个嵌套BaseMapper；仅新增锁/CAS/扫描/投影查询，不增加Repository包装

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/persistence/mapper/PrintMappers.java`。

关键自定义Mapper方法（其他CRUD继承BaseMapper）：

`PrintMappers.PrnPrinterRuntimeMapper`

```java
PrnPrinterRuntime selectForUpdate(PrintScope scope, String printerNo);
int releaseIfCurrent(PrintScope scope, String printerNo, String activeAttemptNo, long fenceEpoch, long expectedRevision);
```

`PrintMappers.PrnRequestMapper`

```java
PrnRequest selectByTriggerKey(PrintScope scope, String triggerKey);
List<PrnRequest> selectDue(String tenantNo, Instant now, int limit);
```

`PrintMappers.PrnBatchMapper`

```java
PrnBatch selectForUpdate(PrintScope scope, String batchNo);
```

`PrintMappers.PrnJobMapper`

```java
PrnJob selectNextForUpdate(PrintScope scope, String printerNo, Instant now);
List<PrnJob> selectByRootForUpdate(PrintScope scope, String rootJobNo);
int casStatus(PrintScope scope, String jobNo, long expectedRevision, PrintJobStatus expectedStatus, PrintJobStatus newStatus, Instant updatedAt);
```

`PrintMappers.PrnAttemptMapper`

```java
PrnAttempt selectByProviderHash(String tenantNo, String providerReferenceHash);
List<PrnAttempt> selectDueQuery(String tenantNo, Instant now, int limit);
```

`PrintMappers.PrnReceiptMapper`

```java
PrnReceipt selectBySourceKey(PrintScope scope, String sourceScope, String sourceReceiptKey);
List<PrnReceipt> selectPending(String tenantNo, int limit);
```

`PrintMappers.PrnOperationMapper`

```java
PrnOperation selectByIdempotency(PrintScope scope, String actorType, String actorNo, String idempotencyKey);
```

`PrintMappers.PrnStoreStreamMapper`

```java
PrnStoreStream selectForUpdate(PrintScope scope);
int allocateSequences(PrintScope scope, long expectedRevision, int count);
```

`PrintMappers.PrnSimulationRunMapper`

```java
List<PrnSimulationRun> selectDue(String tenantNo, Instant now, int limit);
int claim(PrintScope scope, String simulationNo, long expectedRevision, String claimTokenHash, Instant leaseUntil);
```

`PrintMappers.PrnExportTaskMapper`

```java
List<PrnExportTask> selectDue(String tenantNo, Instant now, int limit);
int claim(PrintScope scope, String exportNo, long expectedRevision, long generationEpoch, String claimTokenHash, Instant leaseUntil);
int markReadyIfCurrent(PrintScope scope, String exportNo, long generationEpoch, String claimTokenHash, String contentHash, long fileSize, int pageCount);
```

嵌套类型：`PrnAgentMapper`, `PrnPrinterMapper`, `PrnCredentialVersionMapper`, `PrnPrinterRuntimeMapper`, `PrnTemplateMapper`, `PrnTemplateVersionMapper`, `PrnRouteMapper`, `PrnRouteVersionMapper`, `PrnScenePolicyMapper`, `PrnPolicyVersionMapper`, `PrnRequestMapper`, `PrnBatchMapper`, `PrnJobMapper`, `PrnAttemptMapper`, `PrnReceiptMapper`, `PrnOperationMapper`, `PrnIncidentMapper`, `PrnStoreStreamMapper`, `PrnActionLogMapper`, `PrnReconcileCursorMapper`, `PrnSimulationRunMapper`, `PrnExportTaskMapper`。

### ai.neargo.shop.print.channel.feie.FeiePrintAdapter

模块：`print-channel`；种类：`class`；任务：`PRT-12`。

首家候选云打印，提交/查询不伪造完成

候选文件：`backend/print/print-channel/src/main/java/ai/neargo/shop/print/channel/feie/FeiePrintAdapter.java`。

实现：`PrintDispatchPort`。

直接依赖：`PrintDispatchPort`。

```java
String adapterCode();
PrintAdapterCapabilities capabilities();
DispatchResult submit(PrintDispatch dispatch);
QueryResult query(PrintAttemptRef ref);
CancelResult cancel(PrintAttemptRef ref);
```

### ai.neargo.shop.print.channel.feie.FeieResponseMapper

模块：`print-channel`；种类：`class`；任务：`PRT-12`。

厂商码到ACCEPTED/NOT_ACCEPTED/UNKNOWN和证据映射

候选文件：`backend/print/print-channel/src/main/java/ai/neargo/shop/print/channel/feie/FeieResponseMapper.java`。

### ai.neargo.shop.print.channel.feie.FeieCallbackVerifier

模块：`print-channel`；种类：`class`；任务：`PRT-12`。

仅供应商确有回调能力时装配，验签及account范围映射

候选文件：`backend/print/print-channel/src/main/java/ai/neargo/shop/print/channel/feie/FeieCallbackVerifier.java`。

实现：`PrintCallbackVerifierPort`。

直接依赖：`PrintCallbackVerifierPort`。

```java
String adapterCode();
VerifiedPrintReceipt verify(RawPrintCallback callback);
```

### ai.neargo.shop.print.channel.font.PdfBoxFontMetrics

模块：`print-channel`；种类：`class`；任务：`PRT-03`。

用固定TTF/hash测量，与PDF使用同一字形advance

候选文件：`backend/print/print-channel/src/main/java/ai/neargo/shop/print/channel/font/PdfBoxFontMetrics.java`。

实现：`PrintFontMetricsPort`。

直接依赖：`PrintFontMetricsPort`。

```java
GlyphMetrics measure(String text, FontRef font, BigDecimal pointSize);
```

### ai.neargo.shop.print.channel.font.PdfFontBundle

模块：`print-channel`；种类：`class`；任务：`PRT-03`。

固定字体资源与许可证/hash，不读取宿主字体

候选文件：`backend/print/print-channel/src/main/java/ai/neargo/shop/print/channel/font/PdfFontBundle.java`。

### ai.neargo.shop.print.channel.pdf.PdfReceiptRenderer

模块：`print-channel`；种类：`class`；任务：`PRT-06`。

PDFBox绘制冻结布局、固定元数据和ID

候选文件：`backend/print/print-channel/src/main/java/ai/neargo/shop/print/channel/pdf/PdfReceiptRenderer.java`。

实现：`PrintPdfPort`。

直接依赖：`PrintPdfPort`。

```java
PdfArtifact render(PdfRenderInput input);
```

### ai.neargo.shop.print.channel.artifact.LocalPrintArtifactStore

模块：`print-channel`；种类：`class`；任务：`PRT-07`。

受控目录临时写/fsync/原子rename和私有读取hash

候选文件：`backend/print/print-channel/src/main/java/ai/neargo/shop/print/channel/artifact/LocalPrintArtifactStore.java`。

实现：`PrintArtifactStorePort`。

直接依赖：`PrintArtifactStorePort`。

```java
void put(PrivateArtifact input);
boolean exists(String objectKey);
ArtifactVerification verify(String objectKey);
DownloadGrant signedDownload(String objectKey, Duration ttl);
void delete(String objectKey);
```

### ai.neargo.shop.print.channel.artifact.CosPrintArtifactStore

模块：`print-channel`；种类：`class`；任务：`PRT-07`。

私有对象存储、完整字节hash、短期签名，不走公有图片API

候选文件：`backend/print/print-channel/src/main/java/ai/neargo/shop/print/channel/artifact/CosPrintArtifactStore.java`。

实现：`PrintArtifactStorePort`。

直接依赖：`PrintArtifactStorePort`。

```java
void put(PrivateArtifact input);
boolean exists(String objectKey);
ArtifactVerification verify(String objectKey);
DownloadGrant signedDownload(String objectKey, Duration ttl);
void delete(String objectKey);
```

### ai.neargo.shop.print.channel.credential.PrintCredentialCipher

模块：`print-channel`；种类：`class`；任务：`PRT-04`。

版本化加密密钥及短生命周期明文缓冲

候选文件：`backend/print/print-channel/src/main/java/ai/neargo/shop/print/channel/credential/PrintCredentialCipher.java`。

实现：`PrintCredentialCipherPort`。

直接依赖：`PrintCredentialCipherPort`。

```java
EncryptedPrintCredential encrypt(PrintScope scope, String credentialRef, byte[] secret);
byte[] decrypt(PrintScope scope, EncryptedPrintCredential credential);
```

### ai.neargo.shop.print.port.PrintCredentialLookupPortImpl

模块：`print-domain`；种类：`class`；任务：`PRT-04`。

校验scope后返回固定加密版本，不发明文到Controller

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/port/PrintCredentialLookupPortImpl.java`。

实现：`PrintCredentialLookupPort`。

直接依赖：`PrintCredentialLookupPort`。

```java
EncryptedPrintCredential get(PrintScope scope, String credentialRef);
```

### ai.neargo.shop.trade.order.printing.OrderPrintFact

模块：`shop-core`；种类：`entity`；任务：`PRT-08`。

对应ord_print_fact，独立声明相同基础字段，不extends PrnRow/BaseEntity

候选文件：`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/printing/OrderPrintFact.java`。

| DDL列 | Java字段 | 类型 | 所属 |
|---|---|---|---|
| id | id | Long | OrderPrintFact |
| tenant_no | tenantNo | String | OrderPrintFact |
| entity_no | entityNo | String | OrderPrintFact |
| store_no | storeNo | String | OrderPrintFact |
| print_fact_no | printFactNo | String | OrderPrintFact |
| order_no | orderNo | String | OrderPrintFact |
| sub_order_no | subOrderNo | String | OrderPrintFact |
| source_fact_key | sourceFactKey | String | OrderPrintFact |
| receipt_intent_key | receiptIntentKey | String | OrderPrintFact |
| biz_revision | bizRevision | String | OrderPrintFact |
| scene | scene | String | OrderPrintFact |
| trigger_type | triggerType | String | OrderPrintFact |
| payload_schema_version | payloadSchemaVersion | Integer | OrderPrintFact |
| source_occurred_at | sourceOccurredAt | LocalDateTime | OrderPrintFact |
| snapshot | snapshot | String | OrderPrintFact |
| snapshot_hash | snapshotHash | String | OrderPrintFact |
| printability | printability | String | OrderPrintFact |
| block_code | blockCode | String | OrderPrintFact |
| diagnostics | diagnostics | String | OrderPrintFact |
| revision | revision | Long | OrderPrintFact |
| created_at | createdAt | LocalDateTime | OrderPrintFact |
| updated_at | updatedAt | LocalDateTime | OrderPrintFact |

### ai.neargo.shop.trade.order.printing.OrderPrintFactMapper

模块：`shop-core`；种类：`mapper`；任务：`PRT-08`。

唯一sourceFactKey/receiptIntentKey、锁、扫描；仅此所有者写ord_print_fact

候选文件：`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/printing/OrderPrintFactMapper.java`。

```java
List<OrderPrintFact> selectPage(PrintFactCursor cursor, int limit);
OrderPrintFact selectBySourceKeyForUpdate(PrintScope scope, String sourceFactKey);
```

### ai.neargo.shop.trade.order.printing.service.OrderPrintFactService

模块：`shop-core`；种类：`interface`；任务：`PRT-08`。

订单事实记录、读回、扫描及当前投递守卫

候选文件：`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/printing/service/OrderPrintFactService.java`。

```java
List<PrintFactRef> recordPaid(String orderNo, ConfirmedPayment confirmed);
PrintFact snapshot(PrintFactRef ref);
PrintFactPage scan(PrintFactCursor cursor, int limit);
PrintExecutionGuard executionGuard(PrintFactRef ref);
```

### ai.neargo.shop.trade.order.printing.service.impl.OrderPrintFactServiceImpl

模块：`shop-core`；种类：`class`；任务：`PRT-08`。

与支付源事务同源，先保存真实证据再发Ready事件；不渲染/不外网

候选文件：`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/printing/service/impl/OrderPrintFactServiceImpl.java`。

实现：`OrderPrintFactService`。

直接依赖：`OrderPrintFactService`, `OrderPrintFactMapper`。

```java
List<PrintFactRef> recordPaid(String orderNo, ConfirmedPayment confirmed);
PrintFact snapshot(PrintFactRef ref);
PrintFactPage scan(PrintFactCursor cursor, int limit);
PrintExecutionGuard executionGuard(PrintFactRef ref);
```

### ai.neargo.shop.trade.order.printing.OrderPrintFactRecorder

模块：`shop-core`；种类：`class`；任务：`PRT-08`。

来源快照构建和printability分类，业务不支持仍落BLOCKED事实

候选文件：`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/printing/OrderPrintFactRecorder.java`。

```java
OrderPrintFact capturePaidSubOrder(String orderNo, String subOrderNo, ConfirmedPayment confirmed);
```

### ai.neargo.shop.print.api.OrderPrintFactReady

模块：`print-api`；种类：`event`；任务：`PRT-09`。

事实已可靠保存，不代表可打印

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/OrderPrintFactReady.java`。

实现：`DomainEvent`。

```java
String aggregateType();
String aggregateId();
String eventType();
```

### ai.neargo.shop.print.api.OrderPrintControlChanged

模块：`print-api`；种类：`event`；任务：`PRT-09`。

取消/退款控制事实，保留权威来源版本

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/OrderPrintControlChanged.java`。

实现：`DomainEvent`。

```java
String aggregateType();
String aggregateId();
String eventType();
```

### ai.neargo.shop.print.api.PrintCallbackService

模块：`print-api`；种类：`interface`；任务：`PRT-12`。

原始回调经过受信任verifier后才入收件箱

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintCallbackService.java`。

```java
CallbackAck receive(RawPrintCallback callback);
```

### ai.neargo.shop.print.service.impl.PrintCallbackServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-12`。

校验适配器能力、调用verifier、落receipt后才确认

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/PrintCallbackServiceImpl.java`。

实现：`PrintCallbackService`。

直接依赖：`PrintCallbackService`, `PrintCallbackVerifierPort`, `PrintReceiptService`。

```java
CallbackAck receive(RawPrintCallback callback);
```

### ai.neargo.shop.portal.callback.PrintCallbackController

模块：`shop-app`；种类：`controller`；任务：`PRT-12`。

返回厂商精确ACK，不经过统一信封；账户映射不能信任body

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/callback/PrintCallbackController.java`。

直接依赖：`PrintCallbackService`。

```java
ResponseEntity<byte[]> receive(String adapterCode, String accountRef, byte[] body, HttpServletRequest request);
```

### ai.neargo.shop.printbridge.port.OrderPrintSourceFactsPort

模块：`shop-app`；种类：`class`；任务：`PRT-09`。

按sourceTypes=SUB_ORDER委托trade服务，不直接引订单Mapper

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/printbridge/port/OrderPrintSourceFactsPort.java`。

实现：`PrintSourceFactsPort`。

直接依赖：`PrintSourceFactsPort`, `OrderPrintFactService`。

```java
Set<String> sourceTypes();
PrintFactRef resolveReference(PrintSourceReference reference, PrintScope scope);
PrintFact snapshot(PrintFactRef ref);
PrintFactPage scan(PrintFactCursor cursor, int limit);
PrintExecutionGuard executionGuard(PrintFactRef ref);
```

### ai.neargo.shop.printbridge.PrintFactReadyConsumer

模块：`shop-app`；种类：`class`；任务：`PRT-09`。

OutboxConsumer：handlerId=print-fact-ready，只消费ORDER_PRINT_FACT_READY

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/printbridge/PrintFactReadyConsumer.java`。

直接依赖：`PrintFacade`。

```java
String handlerId();
boolean supports(String eventType);
void consume(SysOutbox event);
```

### ai.neargo.shop.printbridge.PrintControlChangedConsumer

模块：`shop-app`；种类：`class`；任务：`PRT-09`。

OutboxConsumer：handlerId=print-control-changed，取消/退款双重守卫

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/printbridge/PrintControlChangedConsumer.java`。

直接依赖：`PrintJobCommandService`。

```java
String handlerId();
boolean supports(String eventType);
void consume(SysOutbox event);
```

### ai.neargo.shop.printbridge.PrintBridgeConfiguration

模块：`shop-app`；种类：`configuration`；任务：`PRT-09`。

装配来源/Facade/受控系统调用，不把shop-core依赖带入domain

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/printbridge/PrintBridgeConfiguration.java`。

### ai.neargo.shop.printbridge.PrintArtifactJobHandlers

模块：`shop-app`；种类：`configuration`；任务：`PRT-07`。

M2即装配模拟/PDF运行、恢复、清理JobHandler

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/printbridge/PrintArtifactJobHandlers.java`。

直接依赖：`SimulationRunner`, `PrintExportWorker`, `PrintExportRecoveryJob`, `PrintExportCleanupJob`。

### ai.neargo.shop.printbridge.PrintJobHandlers

模块：`shop-app`；种类：`configuration`；任务：`PRT-14`。

M4注册生产投递/结果核查/事实对账JobHandler

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/printbridge/PrintJobHandlers.java`。

直接依赖：`PrintDispatchService`, `PrintReconcileJob`。

### ai.neargo.shop.printbridge.PrintNotifyBridge

模块：`shop-app`；种类：`class`；任务：`PRT-14`。

真实领域事件到既有notify，不导入模拟完成作为真实通知

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/printbridge/PrintNotifyBridge.java`。

### ai.neargo.shop.portal.printsupport.PrintActorFactory

模块：`shop-app`；种类：`class`；任务：`PRT-10`。

从Biz/ops可信身份生成单店actor，读调用idempotencyKey可空

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/printsupport/PrintActorFactory.java`。

### ai.neargo.shop.portal.printsupport.PrintAgentAuthenticationFilter

模块：`shop-app`；种类：`class`；任务：`PRT-13`。

独立代理令牌/会话身份，设备API不复用商家Token

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/printsupport/PrintAgentAuthenticationFilter.java`。

继承：`OncePerRequestFilter`。

直接依赖：`PrintAgentAuthenticationService`。

```java
void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain);
```

### ai.neargo.shop.portal.printsupport.PrintProtocolAckWriter

模块：`shop-app`；种类：`class`；任务：`PRT-12`。

统一控制厂商ACK及设备响应是否封装，保留HTTP原始字节

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/portal/printsupport/PrintProtocolAckWriter.java`。

### ai.neargo.shop.print.agent.AgentMain

模块：`print-agent`；种类：`class`；任务：`PRT-13`。

启动、单实例进程锁、后台循环和受控退出

候选文件：`backend/print/print-agent/src/main/java/ai/neargo/shop/print/agent/AgentMain.java`。

直接依赖：`AgentExecutionLoop`, `AgentRecoveryCoordinator`。

```java
public static void main(String[] args);
```

### ai.neargo.shop.print.agent.AgentHttpClient

模块：`print-agent`；种类：`interface`；任务：`PRT-13`。

D01–D10 HTTPS协议，旧会话只能补传证据

候选文件：`backend/print/print-agent/src/main/java/ai/neargo/shop/print/agent/AgentHttpClient.java`。

直接依赖：`PrintAgentIdentity`。

```java
DeviceEnrollVO enroll(DeviceEnrollReq request);
DeviceOpenSessionVO openSession(DeviceOpenSessionReq request);
DeviceHeartbeatVO heartbeat(DeviceHeartbeatReq request);
DeviceClaimVO claim(DeviceClaimReq request);
DeviceAcceptDispatchVO accept(String dispatchKey, DeviceAcceptDispatchReq request);
DeviceRenewLeaseVO renew(String dispatchKey, DeviceRenewLeaseReq request);
DeviceReportEventsVO report(DeviceReportEventsReq request);
DeviceGetDispatchVO getDispatch(String dispatchKey);
DeviceRecoverVO recover(DeviceRecoverReq request);
DeviceReportCommandResultVO reportCommand(String commandNo, DeviceReportCommandResultReq request);
```

### ai.neargo.shop.print.agent.DefaultAgentHttpClient

模块：`print-agent`；种类：`class`；任务：`PRT-13`。

实现AgentHttpClient，不访问服务端数据库

候选文件：`backend/print/print-agent/src/main/java/ai/neargo/shop/print/agent/DefaultAgentHttpClient.java`。

实现：`AgentHttpClient`。

直接依赖：`AgentHttpClient`。

```java
DeviceEnrollVO enroll(DeviceEnrollReq request);
DeviceOpenSessionVO openSession(DeviceOpenSessionReq request);
DeviceHeartbeatVO heartbeat(DeviceHeartbeatReq request);
DeviceClaimVO claim(DeviceClaimReq request);
DeviceAcceptDispatchVO accept(String dispatchKey, DeviceAcceptDispatchReq request);
DeviceRenewLeaseVO renew(String dispatchKey, DeviceRenewLeaseReq request);
DeviceReportEventsVO report(DeviceReportEventsReq request);
DeviceGetDispatchVO getDispatch(String dispatchKey);
DeviceRecoverVO recover(DeviceRecoverReq request);
DeviceReportCommandResultVO reportCommand(String commandNo, DeviceReportCommandResultReq request);
```

### ai.neargo.shop.print.agent.LocalDispatchStore

模块：`print-agent`；种类：`interface`；任务：`PRT-13`。

SQLite先落盘再ACK；记录SUBMITTING和event outbox

候选文件：`backend/print/print-agent/src/main/java/ai/neargo/shop/print/agent/LocalDispatchStore.java`。

```java
void receiveAndCommit(ClaimDispatch dispatch);
Optional<LocalDispatch> find(String dispatchKey);
void applyLeaseGrant(String dispatchKey, long expectedRevision, String leaseToken, Instant leaseUntil);
void markSubmittingAndAppendEvent(String dispatchKey, long expectedRevision, LocalEvent event);
void recordObservationAndAppendEvent(String dispatchKey, QueueObservation observation, LocalEvent event);
List<LocalDispatch> unresolved(int limit);
List<LocalEvent> pendingEvents(int limit);
void markEventsAcknowledged(Set<String> eventNos);
```

### ai.neargo.shop.print.agent.SqliteLocalDispatchStore

模块：`print-agent`；种类：`class`；任务：`PRT-13`。

local_dispatch＋local_event，原子提交与重启恢复

候选文件：`backend/print/print-agent/src/main/java/ai/neargo/shop/print/agent/SqliteLocalDispatchStore.java`。

实现：`LocalDispatchStore`。

直接依赖：`LocalDispatchStore`。

```java
void receiveAndCommit(ClaimDispatch dispatch);
Optional<LocalDispatch> find(String dispatchKey);
void applyLeaseGrant(String dispatchKey, long expectedRevision, String leaseToken, Instant leaseUntil);
void markSubmittingAndAppendEvent(String dispatchKey, long expectedRevision, LocalEvent event);
void recordObservationAndAppendEvent(String dispatchKey, QueueObservation observation, LocalEvent event);
List<LocalDispatch> unresolved(int limit);
List<LocalEvent> pendingEvents(int limit);
void markEventsAcknowledged(Set<String> eventNos);
```

### ai.neargo.shop.print.agent.AgentCredentialStore

模块：`print-agent`；种类：`interface`；任务：`PRT-13`。

令牌封装，不输出明文配置/日志

候选文件：`backend/print/print-agent/src/main/java/ai/neargo/shop/print/agent/AgentCredentialStore.java`。

```java
Optional<OpaqueAgentCredential> load();
void saveSealedCredential(String sealedCredential, String clientKeyAlias);
void revokeLocalCredential();
```

### ai.neargo.shop.print.agent.WindowsAgentCredentialStore

模块：`print-agent`；种类：`class`；任务：`PRT-13`。

Windows受保护存储凭据；不依赖预览Android壳

候选文件：`backend/print/print-agent/src/main/java/ai/neargo/shop/print/agent/WindowsAgentCredentialStore.java`。

实现：`AgentCredentialStore`。

直接依赖：`AgentCredentialStore`。

```java
Optional<OpaqueAgentCredential> load();
void saveSealedCredential(String sealedCredential, String clientKeyAlias);
void revokeLocalCredential();
```

### ai.neargo.shop.print.agent.DesktopPrintExecutor

模块：`print-agent`；种类：`interface`；任务：`PRT-13`。

只负责本地队列提交/查询/取消，不认业务订单

候选文件：`backend/print/print-agent/src/main/java/ai/neargo/shop/print/agent/DesktopPrintExecutor.java`。

```java
QueueObservation submit(LocalDispatch dispatch);
QueueObservation query(LocalDispatch dispatch);
QueueObservation cancel(LocalDispatch dispatch);
```

### ai.neargo.shop.print.agent.JavaPrintServiceExecutor

模块：`print-agent`；种类：`class`；任务：`PRT-13`。

PrintLayout→本地渲染/系统驱动，队列受理不冒充出纸

候选文件：`backend/print/print-agent/src/main/java/ai/neargo/shop/print/agent/JavaPrintServiceExecutor.java`。

实现：`DesktopPrintExecutor`。

直接依赖：`DesktopPrintExecutor`。

```java
QueueObservation submit(LocalDispatch dispatch);
QueueObservation query(LocalDispatch dispatch);
QueueObservation cancel(LocalDispatch dispatch);
```

### ai.neargo.shop.print.agent.AgentExecutionLoop

模块：`print-agent`；种类：`class`；任务：`PRT-13`。

领取→持久化→ACK→复核lease→发包→证据补传

候选文件：`backend/print/print-agent/src/main/java/ai/neargo/shop/print/agent/AgentExecutionLoop.java`。

直接依赖：`AgentHttpClient`, `LocalDispatchStore`, `DesktopPrintExecutor`。

```java
void tick(PrintWorkerBudget budget);
void executeAccepted(LocalDispatch dispatch);
```

### ai.neargo.shop.print.agent.AgentRecoveryCoordinator

模块：`print-agent`；种类：`class`；任务：`PRT-13`。

重启SUBMITTING先查询/UNKNOWN，不自动重发

候选文件：`backend/print/print-agent/src/main/java/ai/neargo/shop/print/agent/AgentRecoveryCoordinator.java`。

直接依赖：`LocalDispatchStore`, `DesktopPrintExecutor`。

```java
void recoverOnStartup(PrintWorkerBudget budget);
```

### ai.neargo.shop.print.agent.AgentModels

模块：`print-agent`；种类：`record-container`；任务：`PRT-13`。

LocalDispatch/LocalEvent/SpoolResult等本地值对象

候选文件：`backend/print/print-agent/src/main/java/ai/neargo/shop/print/agent/AgentModels.java`。

嵌套record字段：

```java
public record LocalDispatch(String dispatchKey, String jobNo, String attemptNo, String printerNo, long sessionEpoch, long fenceEpoch, long connectionRevision, String leaseToken, Instant leaseUntil, Instant executeBefore, PrintLayout layout, String documentHash, FrozenJson connection, ObservedPrintStatus status, String systemJobId, long revision, Instant receivedAt, Instant submittingAt, Instant finishedAt) {}
public record LocalEvent(String eventNo, String dispatchKey, String attemptNo, long sessionEpoch, long fenceEpoch, long sequence, ObservedPrintStatus status, PrintEvidenceLevel evidenceLevel, FrozenJson evidence, Instant occurredAt, Instant acknowledgedAt) {}
public record QueueObservation(ObservedPrintStatus status, String systemJobId, boolean queueTerminated, boolean partialOutput, PrintEvidenceLevel evidenceLevel, FrozenJson evidence) {}
```

### ai.neargo.shop.print.api.PrintTaskCompleted

模块：`print-api`；种类：`event`；任务：`PRT-14`。

scope＋目标业务号＋stateRevision＋时间/证据级别；仅可靠事件引用，不带完整票据

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintTaskCompleted.java`。

实现：`DomainEvent`。

```java
String aggregateType();
String aggregateId();
String eventType();
```

### ai.neargo.shop.print.api.PrintBatchResolved

模块：`print-api`；种类：`event`；任务：`PRT-14`。

scope＋目标业务号＋stateRevision＋时间/证据级别；仅可靠事件引用，不带完整票据

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintBatchResolved.java`。

实现：`DomainEvent`。

```java
String aggregateType();
String aggregateId();
String eventType();
```

### ai.neargo.shop.print.api.PrintIncidentOpened

模块：`print-api`；种类：`event`；任务：`PRT-14`。

scope＋目标业务号＋stateRevision＋时间/证据级别；仅可靠事件引用，不带完整票据

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintIncidentOpened.java`。

实现：`DomainEvent`。

```java
String aggregateType();
String aggregateId();
String eventType();
```

### ai.neargo.shop.print.api.PrintIncidentResolved

模块：`print-api`；种类：`event`；任务：`PRT-14`。

scope＋目标业务号＋stateRevision＋时间/证据级别；仅可靠事件引用，不带完整票据

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintIncidentResolved.java`。

实现：`DomainEvent`。

```java
String aggregateType();
String aggregateId();
String eventType();
```

### ai.neargo.shop.print.api.PrintExportRequested

模块：`print-api`；种类：`event`；任务：`PRT-07`。

scope＋目标业务号＋stateRevision＋时间/证据级别；仅可靠事件引用，不带完整票据

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintExportRequested.java`。

实现：`DomainEvent`。

```java
String aggregateType();
String aggregateId();
String eventType();
```

### ai.neargo.shop.print.api.PrintSimulationRequested

模块：`print-api`；种类：`event`；任务：`PRT-07`。

scope＋目标业务号＋stateRevision＋时间/证据级别；仅可靠事件引用，不带完整票据

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintSimulationRequested.java`。

实现：`DomainEvent`。

```java
String aggregateType();
String aggregateId();
String eventType();
```

### ai.neargo.shop.printbridge.PrintArtifactWakeConsumer

模块：`shop-app`；种类：`class`；任务：`PRT-07`。

仅唤醒已持久化模拟/PDF任务；唤醒丢失由周期扫描恢复，不在外层事务等待网络

候选文件：`backend/shop-app/src/main/java/ai/neargo/shop/printbridge/PrintArtifactWakeConsumer.java`。

直接依赖：`SimulationRunner`, `PrintExportWorker`。

```java
String handlerId();
boolean supports(String eventType);
void consume(SysOutbox event);
```

### ai.neargo.shop.print.api.AgentRequestPurpose

模块：`print-api`；种类：`enum`；任务：`PRT-13`。

区分新执行与旧任务证据回报

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/AgentRequestPurpose.java`。

取值：`OPEN_SESSION / HEARTBEAT / CLAIM / ACKNOWLEDGE / RENEW_LEASE / REPORT_EVIDENCE / RECOVER / COMMAND_RESULT / READ_DISPATCH`。

### ai.neargo.shop.print.api.OpaqueAgentCredential

模块：`print-api`；种类：`record`；任务：`PRT-13`。

敏感令牌，只在认证边界短暂使用，toString必须脱敏

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/OpaqueAgentCredential.java`。

字段合同：`String value`。

### ai.neargo.shop.print.api.PrintAgentAuthenticationService

模块：`print-api`；种类：`interface`；任务：`PRT-13`。

验证当前凭据并返回可信代理身份；不相信Body的会话身份

候选文件：`backend/print/print-api/src/main/java/ai/neargo/shop/print/api/PrintAgentAuthenticationService.java`。

```java
PrintAgentIdentity authenticate(OpaqueAgentCredential credential, AgentRequestPurpose purpose);
```

### ai.neargo.shop.print.service.impl.PrintAgentAuthenticationServiceImpl

模块：`print-domain`；种类：`class`；任务：`PRT-13`。

查本域代理hash/撤销状态并校验，旧会话证据与新执行分别裁决

候选文件：`backend/print/print-domain/src/main/java/ai/neargo/shop/print/service/impl/PrintAgentAuthenticationServiceImpl.java`。

实现：`PrintAgentAuthenticationService`。

直接依赖：`PrintAgentAuthenticationService`。

```java
PrintAgentIdentity authenticate(OpaqueAgentCredential credential, AgentRequestPurpose purpose);
```

## 二、83项API到类/方法映射

| API | HTTP | Controller | Service / Facade | 方法 |
|---|---|---|---|---|
| P01 | GET `/biz/print/adapters` | BizPrintDeviceController | PrintDeviceService | `List<AdapterVO> listAdapters(ListAdaptersQuery query, PrintActor actor)` |
| P02 | GET `/biz/print/printers` | BizPrintDeviceController | PrintDeviceService | `PageData<PrinterVO> listPrinters(ListPrintersQuery query, PrintActor actor)` |
| P03 | POST `/biz/print/printers` | BizPrintDeviceController | PrintDeviceService | `PrintOperation createPrinter(CreatePrinterReq request, PrintActor actor)` |
| P04 | GET `/biz/print/printers/{printerNo}` | BizPrintDeviceController | PrintDeviceService | `PrinterDetailVO getPrinter(String printerNo, PrintActor actor)` |
| P05 | PUT `/biz/print/printers/{printerNo}` | BizPrintDeviceController | PrintDeviceService | `PrintOperation updatePrinter(String printerNo, UpdatePrinterReq request, PrintActor actor)` |
| P06 | POST `/biz/print/printers/{printerNo}/connection-changes` | BizPrintDeviceController | PrintDeviceService | `PrintOperation changeConnection(String printerNo, ChangeConnectionReq request, PrintActor actor)` |
| P07 | POST `/biz/print/printers/{printerNo}/status-changes` | BizPrintDeviceController | PrintDeviceService | `PrintOperation changeStatus(String printerNo, ChangeStatusReq request, PrintActor actor)` |
| P08 | POST `/biz/print/printers/{printerNo}/credential-rotations` | BizPrintDeviceController | PrintDeviceService | `PrintOperation rotateCredential(String printerNo, RotateCredentialReq request, PrintActor actor)` |
| P09 | POST `/biz/print/printers/{printerNo}/probes` | BizPrintDeviceController | PrintDeviceService | `PrintOperation probe(String printerNo, ProbeReq request, PrintActor actor)` |
| P10 | POST `/biz/print/printers/{printerNo}/test-jobs` | BizPrintDeviceController | PrintDeviceService | `PrintOperation testPrint(String printerNo, TestPrintReq request, PrintActor actor)` |
| A01 | GET `/biz/print/agents` | BizPrintAgentController | PrintAgentService | `PageData<AgentVO> listAgents(ListAgentsQuery query, PrintActor actor)` |
| A02 | POST `/biz/print/agents` | BizPrintAgentController | PrintAgentService | `PrintOperation createAgent(CreateAgentReq request, PrintActor actor)` |
| A03 | GET `/biz/print/agents/{agentNo}` | BizPrintAgentController | PrintAgentService | `AgentDetailVO getAgent(String agentNo, PrintActor actor)` |
| A04 | POST `/biz/print/agents/{agentNo}/enrollments` | BizPrintAgentController | PrintAgentService | `PrintOperation issueEnrollment(String agentNo, IssueEnrollmentReq request, PrintActor actor)` |
| A05 | POST `/biz/print/agents/{agentNo}/revocations` | BizPrintAgentController | PrintAgentService | `PrintOperation revokeAgent(String agentNo, RevokeAgentReq request, PrintActor actor)` |
| A06 | POST `/biz/print/agents/{agentNo}/credential-rotations` | BizPrintAgentController | PrintAgentService | `PrintOperation rotateAgentCredential(String agentNo, RotateAgentCredentialReq request, PrintActor actor)` |
| C01 | GET `/biz/print/scenes` | BizPrintSceneController | PrintScenePolicyService | `List<SceneVO> listScenes(ListScenesQuery query, PrintActor actor)` |
| C02 | GET `/biz/print/scene-policies` | BizPrintSceneController | PrintScenePolicyService | `List<ScenePolicyVO> listScenePolicies(ListScenePoliciesQuery query, PrintActor actor)` |
| C03 | PUT `/biz/print/scene-policies/{scene}` | BizPrintSceneController | PrintScenePolicyService | `PrintOperation saveScenePolicy(String scene, SaveScenePolicyReq request, PrintActor actor)` |
| C04 | GET `/biz/print/scene-policies/{scene}/versions` | BizPrintSceneController | PrintScenePolicyService | `PageData<PolicyVersionVO> listPolicyVersions(String scene, ListPolicyVersionsQuery query, PrintActor actor)` |
| C05 | GET `/biz/print/templates` | BizPrintTemplateController | PrintTemplateService | `PageData<TemplateVO> listTemplates(ListTemplatesQuery query, PrintActor actor)` |
| C06 | POST `/biz/print/templates` | BizPrintTemplateController | PrintTemplateService | `PrintOperation createTemplate(CreateTemplateReq request, PrintActor actor)` |
| C07 | POST `/biz/print/templates/{templateNo}/versions` | BizPrintTemplateController | PrintTemplateService | `PrintOperation createTemplateVersion(String templateNo, CreateTemplateVersionReq request, PrintActor actor)` |
| C08 | PUT `/biz/print/template-versions/{versionNo}` | BizPrintTemplateController | PrintTemplateService | `PrintOperation updateTemplateDraft(String versionNo, UpdateTemplateDraftReq request, PrintActor actor)` |
| C09 | POST `/biz/print/template-versions/{versionNo}/preview` | BizPrintTemplateController | PrintTemplateService | `PreviewVO previewTemplate(String versionNo, PreviewTemplateReq request, PrintActor actor)` |
| C10 | POST `/biz/print/template-versions/{versionNo}/publish` | BizPrintTemplateController | PrintTemplateService | `PrintOperation publishTemplate(String versionNo, PublishTemplateReq request, PrintActor actor)` |
| C11 | GET `/biz/print/templates/{templateNo}/versions` | BizPrintTemplateController | PrintTemplateService | `PageData<TemplateVersionVO> listTemplateVersions(String templateNo, ListTemplateVersionsQuery query, PrintActor actor)` |
| C12 | GET `/biz/print/routes` | BizPrintRouteController | PrintRouteService | `PageData<RouteVO> listRoutes(ListRoutesQuery query, PrintActor actor)` |
| C13 | POST `/biz/print/routes` | BizPrintRouteController | PrintRouteService | `PrintOperation createRoute(CreateRouteReq request, PrintActor actor)` |
| C14 | POST `/biz/print/routes/{routeNo}/versions` | BizPrintRouteController | PrintRouteService | `PrintOperation createRouteVersion(String routeNo, CreateRouteVersionReq request, PrintActor actor)` |
| C15 | POST `/biz/print/route-versions/{versionNo}/dry-run` | BizPrintRouteController | PrintRouteService | `RouteDryRunVO dryRunRoute(String versionNo, DryRunRouteReq request, PrintActor actor)` |
| C16 | POST `/biz/print/route-versions/{versionNo}/publish` | BizPrintRouteController | PrintRouteService | `PrintOperation publishRoute(String versionNo, PublishRouteReq request, PrintActor actor)` |
| C17 | GET `/biz/print/routes/{routeNo}/versions` | BizPrintRouteController | PrintRouteService | `PageData<RouteVersionVO> listRouteVersions(String routeNo, ListRouteVersionsQuery query, PrintActor actor)` |
| C18 | POST `/biz/print/template-resolutions/preview` | BizPrintTemplateController | PrintTemplateService | `TemplateResolutionPreviewVO previewResolution(PreviewResolutionReq request, PrintActor actor)` |
| C19 | GET `/biz/print/scenes/{scene}/fields` | BizPrintSceneController | PrintScenePolicyService | `SceneFieldCatalogVO getSceneFields(String scene, GetSceneFieldsQuery query, PrintActor actor)` |
| C20 | GET `/biz/print/scenes/{scene}/examples` | BizPrintSceneController | PrintScenePolicyService | `GetSceneExampleVO getSceneExample(String scene, GetSceneExampleQuery query, PrintActor actor)` |
| J01 | GET `/biz/print/requests` | BizPrintRequestController | PrintRequestService | `PageData<PrintRequestVO> listRequests(ListRequestsQuery query, PrintActor actor)` |
| J02 | GET `/biz/print/requests/{requestNo}` | BizPrintRequestController | PrintRequestService | `RequestDetailVO getRequest(String requestNo, PrintActor actor)` |
| J03 | POST `/biz/print/requests` | BizPrintRequestController | PrintFacade | `PrintOperation submitBySource(SubmitPrintRequestReq request, PrintActor actor)` |
| J04 | POST `/biz/print/requests/{requestNo}/generation-retries` | BizPrintRequestController | PrintRequestService | `PrintOperation retryGeneration(String requestNo, RetryGenerationReq request, PrintActor actor)` |
| J05 | GET `/biz/print/batches/{batchNo}` | BizPrintJobQueryController | PrintQueryService | `PrintBatchVO getBatch(String batchNo, PrintActor actor)` |
| J06 | GET `/biz/print/jobs` | BizPrintJobQueryController | PrintQueryService | `PageData<PrintJobVO> listJobs(ListJobsQuery query, PrintActor actor)` |
| J07 | GET `/biz/print/jobs/{jobNo}` | BizPrintJobQueryController | PrintQueryService | `PrintJobView getJob(PrintJobRef ref, PrintActor actor)` |
| J08 | GET `/biz/print/jobs/{jobNo}/attempts` | BizPrintJobQueryController | PrintQueryService | `PageData<PrintAttemptVO> listAttempts(String jobNo, ListAttemptsQuery query, PrintActor actor)` |
| J09 | GET `/biz/print/jobs/{jobNo}/receipts` | BizPrintJobQueryController | PrintQueryService | `PageData<PrintReceiptVO> listReceipts(String jobNo, ListReceiptsQuery query, PrintActor actor)` |
| J10 | GET `/biz/print/jobs/{jobNo}/document` | BizPrintJobQueryController | PrintQueryService | `DocumentPreviewVO getDocument(String jobNo, PrintActor actor)` |
| J11 | GET `/biz/print/orders/{orderNo}/summary` | BizPrintJobQueryController | PrintQueryService | `OrderPrintSummaryVO getOrderSummary(String orderNo, GetOrderSummaryQuery query, PrintActor actor)` |
| J12 | GET `/biz/print/operations/{operationNo}` | BizPrintJobQueryController | PrintQueryService | `PrintOperation getOperation(String operationNo, PrintActor actor)` |
| J13 | POST `/biz/print/jobs/{jobNo}/reprints` | BizPrintJobCommandController | PrintReprintService | `PrintOperation reprint(String jobNo, ReprintReq request, PrintActor actor)` |
| J14 | POST `/biz/print/reprints` | BizPrintJobCommandController | PrintReprintService | `PrintOperation batchReprint(BatchReprintReq request, PrintActor actor)` |
| J15 | POST `/biz/print/jobs/{jobNo}/extra-copies` | BizPrintJobCommandController | PrintReprintService | `PrintOperation extraCopies(String jobNo, ExtraCopiesReq request, PrintActor actor)` |
| J16 | POST `/biz/print/jobs/{jobNo}/retries` | BizPrintJobCommandController | PrintJobCommandService | `PrintOperation retryJob(String jobNo, RetryJobReq request, PrintActor actor)` |
| J17 | POST `/biz/print/jobs/{jobNo}/cancellations` | BizPrintJobCommandController | PrintJobCommandService | `PrintOperation cancelJob(String jobNo, CancelJobReq request, PrintActor actor)` |
| J18 | POST `/biz/print/jobs/{jobNo}/result-queries` | BizPrintJobCommandController | PrintJobCommandService | `PrintOperation queryResult(String jobNo, QueryResultReq request, PrintActor actor)` |
| J19 | POST `/biz/print/jobs/{jobNo}/manual-confirmations` | BizPrintJobCommandController | PrintJobCommandService | `PrintOperation confirmManually(String jobNo, ConfirmManuallyReq request, PrintActor actor)` |
| J20 | POST `/biz/print/jobs/{jobNo}/stale-releases` | BizPrintJobCommandController | PrintJobCommandService | `PrintOperation releaseStale(String jobNo, ReleaseStaleReq request, PrintActor actor)` |
| J21 | POST `/biz/print/printers/{printerNo}/uncertainty-resolutions` | BizPrintJobCommandController | PrintJobCommandService | `PrintOperation resolveDeviceUncertainty(String printerNo, ResolveDeviceUncertaintyReq request, PrintActor actor)` |
| D01 | POST `/device/print/v1/enrollments` | DevicePrintController | PrintAgentProtocolService | `DeviceEnrollVO enroll(DeviceEnrollReq request)` |
| D02 | POST `/device/print/v1/sessions` | DevicePrintController | PrintAgentProtocolService | `DeviceOpenSessionVO openSession(DeviceOpenSessionReq request, PrintAgentIdentity identity)` |
| D03 | POST `/device/print/v1/heartbeats` | DevicePrintController | PrintAgentProtocolService | `DeviceHeartbeatVO heartbeat(DeviceHeartbeatReq request, PrintAgentIdentity identity)` |
| D04 | POST `/device/print/v1/claims` | DevicePrintController | PrintAgentProtocolService | `DeviceClaimVO claim(DeviceClaimReq request, PrintAgentIdentity identity)` |
| D05 | POST `/device/print/v1/dispatches/{dispatchKey}/acceptances` | DevicePrintController | PrintAgentProtocolService | `DeviceAcceptDispatchVO acceptDispatch(String dispatchKey, DeviceAcceptDispatchReq request, PrintAgentIdentity identity)` |
| D06 | POST `/device/print/v1/dispatches/{dispatchKey}/lease-renewals` | DevicePrintController | PrintAgentProtocolService | `DeviceRenewLeaseVO renewLease(String dispatchKey, DeviceRenewLeaseReq request, PrintAgentIdentity identity)` |
| D07 | POST `/device/print/v1/events` | DevicePrintController | PrintAgentProtocolService | `DeviceReportEventsVO reportEvents(DeviceReportEventsReq request, PrintAgentIdentity identity)` |
| D08 | GET `/device/print/v1/dispatches/{dispatchKey}` | DevicePrintController | PrintAgentProtocolService | `DeviceGetDispatchVO getDispatch(String dispatchKey, PrintAgentIdentity identity)` |
| D09 | POST `/device/print/v1/recoveries` | DevicePrintController | PrintAgentProtocolService | `DeviceRecoverVO recover(DeviceRecoverReq request, PrintAgentIdentity identity)` |
| D10 | POST `/device/print/v1/commands/{commandNo}/results` | DevicePrintController | PrintAgentProtocolService | `DeviceReportCommandResultVO reportCommandResult(String commandNo, DeviceReportCommandResultReq request, PrintAgentIdentity identity)` |
| M01 | GET `/biz/print/health` | BizPrintMonitorController | PrintMonitorService | `GetHealthVO getHealth(GetHealthQuery query, PrintActor actor)` |
| M02 | GET `/biz/print/incidents` | BizPrintMonitorController | PrintMonitorService | `PageData<IncidentVO> listIncidents(ListIncidentsQuery query, PrintActor actor)` |
| M03 | POST `/biz/print/incidents/{incidentNo}/acknowledgements` | BizPrintMonitorController | PrintMonitorService | `PrintOperation acknowledgeIncident(String incidentNo, AcknowledgeIncidentReq request, PrintActor actor)` |
| M04 | GET `/biz/print/action-logs` | BizPrintMonitorController | PrintMonitorService | `PageData<ActionLogVO> listActionLogs(ListActionLogsQuery query, PrintActor actor)` |
| M05 | GET `/biz/print/changes` | BizPrintMonitorController | PrintMonitorService | `ChangePageVO listChanges(ListChangesQuery query, PrintActor actor)` |
| M06 | POST `/ops/print/reconciliations` | OpsPrintController | PrintOpsService | `PrintOperation reconcile(OpsReconcileReq request, PrintOpsActor actor)` |
| M07 | GET `/ops/print/operations/{operationNo}` | OpsPrintController | PrintOpsService | `PrintOperation getOperation(String operationNo, OpsGetOperationQuery query, PrintOpsActor actor)` |
| M08 | GET `/ops/print/health` | OpsPrintController | PrintOpsService | `FleetHealthVO getFleetHealth(OpsGetFleetHealthQuery query, PrintOpsActor actor)` |
| M09 | GET `/ops/print/incidents` | OpsPrintController | PrintOpsService | `PageData<IncidentVO> listIncidents(OpsListIncidentsQuery query, PrintOpsActor actor)` |
| S01 | POST `/biz/print/simulations` | BizPrintSimulationController | PrintSimulationFacade | `PrintOperation create(SimulationCommand command, PrintActor actor)` |
| S02 | GET `/biz/print/simulations` | BizPrintSimulationController | PrintSimulationFacade | `PageData<SimulationView> page(SimulationQuery query, PrintActor actor)` |
| S03 | GET `/biz/print/simulations/{simulationNo}` | BizPrintSimulationController | PrintSimulationFacade | `SimulationView get(String simulationNo, PrintActor actor)` |
| E01 | POST `/biz/print/exports` | BizPrintExportController | PrintExportFacade | `PrintOperation create(ExportCommand command, PrintActor actor)` |
| E02 | GET `/biz/print/exports` | BizPrintExportController | PrintExportFacade | `PageData<ExportView> page(ExportQuery query, PrintActor actor)` |
| E03 | GET `/biz/print/exports/{exportNo}` | BizPrintExportController | PrintExportFacade | `ExportView get(String exportNo, PrintActor actor)` |
| E04 | POST `/biz/print/exports/{exportNo}/download-grants` | BizPrintExportController | PrintExportFacade | `DownloadGrant download(String exportNo, PrintActor actor)` |

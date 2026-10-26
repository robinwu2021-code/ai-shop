# 美业系统 Java 类与方法清单

> 状态：方法级目标与实施对照 · 2026-10-21 · 已实现范围和部署结果以主TDD为准，不能把整张清单当已完成。
> 由 `python3 scripts/gen-beauty-java-method-design.py` 生成，勿手改；真源：[Java方法设计JSON](./schema/beauty-java-method-design.json)。
> 依据：[统一Java规范§七](../../../.claude/skills/ai-shop-dev-standards/references/java.md) · [独立项目结构](../TDD-美业系统-独立项目与包依赖方案.md) · [API清单](./美业系统-API清单.md) · [数据库结构](./美业系统-数据库结构设计.md)。

## 一、范围与规范

目标包含42个美业用户Controller、28个Service及同名Impl、25个内嵌Mapper、25个Entity、171个Req/Query/VO和7个必要辅助类。95条美业HTTP方法与6条内部业务方法合计101条Service方法。按需提供同接口远程实现、协议客户端和内部入口，不能把目标清单当已生成源码。

11个共用HTTP目标另列其所属域的类/方法，其中5个人员、2个资源和1个班表查询已编码，另补1个P1周规则写入口，54个CONTRACT是能力接入点而非54条已定Java方法。共享资源、账户、打印、报表等不复制到美业；需要补齐的正式契约在原所有者评审。

![模块依赖](../diagrams/beauty-java-module-dependencies.svg)

图由项目结构真源生成。签名清单沿api/core/web/channel/server交付，业务归属不因同库或同名改变。逐API调用与Mapper约束见[方法清单](./美业系统-Controller-Service-Mapper方法清单.md)。

1. Controller仅绑定路径/Body/Query/协议头、鉴权与调用Service，返回业务VO/PageData；沿平台ApiResponseWrapper，不再包ApiResult。声明省略import、类级条件、Mapping和权限注解，HTTP方法/路径在标题；实施必须补齐其对应注解及“为什么”的公开API Javadoc。
2. Service接口归beauty-api/service，Impl归beauty-core/service/impl且同名后缀Impl；Service方法无HTTP注解，Impl直接注入本域Mapper。禁止新增Repository、等义Application/DomainService或一个巨型BeautyManager。
3. 经营主体用entityNo；BizContext.requireMerchantNo()是现有兼容来源，在Web接入处映射为该语义。currentStoreNo/actorNo来自可信上下文，actorNo是账号/用户/运营操作身份，实际执行人员由共用账号关联及任职核验取得，不能把无登录人员伪装成操作者。
4. Service签名显式接收可信范围；租户沿平台当前MAIN及数据域规则，不另启租户体系。详情按对象解析归属后与获准范围相交，SQL仍限entity/门店/受控父链。技师本人范围由可信数据域/共用任职授权解析，不采信query.personnelNo；非受权跨店请求拒绝。
5. 消费者本人方法接userNo，由SecurityUtils.requireUser()解析；publicStore/publicService/publicAvailability只由真实门店/目录确定主体，允许公开查询，不要求伪造商家身份。本人方法每次验证资源属主，不接收客户端自报userNo/entityNo/actorNo。
6. ops的entityNo与authorizedStoreNos是平台授权目标，不是客户端任意主体；actorNo是当前运营身份，不代商家。技术恢复仍执行同一业务校验，不代顾客同意或代技师完工。跨店报表只读authorizedStoreNos与查询条件交集。
7. Req/Query按统一Validation校验必填、长度、枚举、正数/精度与时间窗口；普通page>=1、size默认20且最大50，日历资源页/窗口沿工作台合同。错误沿统一ErrorCode/BizException及三语词条，不另造BeautyError体系。Req/Query只包含业务字段，VO不含物理id。body/query字段沿API输入合同，必须剔除路径字段及仅属身份的可信参数；预览编号绑定原操作者/主体/门店/窗口/版本，不能换人拿号提交。
8. 版本请求expectedRevision映射到聚合BaseEntity.version；scopeRevision/bindingRevision/appointmentRevision/planRevision分别是范围、来源、安排、计划版本，不能混用。步骤/参与子行CAS版本取锁内真实行，根版本仍按用户期望检查。
9. 业务写命令带Idempotency-Key，同键同载荷取原号、异参拒绝；预览与查询POST无业务副作用，不强制业务幂等头。长流程操作号/请求指纹由共用幂等编排与本域ServiceSubmission持久记录，不因超时换键。
10. Mapper继承BaseMapper，常用CRUD不重写空壳；只对稳定行锁、CAS、数量上限等新增自定义方法。BaseMapper虽暴露删除/按id等方法，本域事实业务不使用delete/archive覆盖历史，也不把业务号传selectById。
11. 普通分页以用例的真实业务根/组合投影分页，返回IPage后用MybatisPages.of转换PageData；数据量大的列表不用PageData.ofAll。CalendarSnapshotVO/CalendarPageVO是快照协议，不套普通分页，不将未加载/延迟当空闲。批量获取任务/来源/资源，禁止每卡片循环跨域N+1。
12. API用带偏移时间并按门店IANA时区显示；本域Entity/自定义SQL采用与BaseEntity一致的LocalDateTime，边界明确转换成UTC本地值。区间[from,to)，数量BigDecimal，金额整数最小单位+币种；不引入浮点金额或全局时区重构。
13. LOCAL_ATOMIC仅指本域短事务；ORCHESTRATED先保存意图再外部调用、后短事务确认/补偿。调用清单是候选分支，不是一次必须全执行的线性脚本；HTTP不在长锁事务中等待，UNKNOWN按原号恢复。
14. 多行业同名短类通过受控扫描/必要Bean命名策略避免冲突；新增类正常import、构造器注入，不复制零售存量全限定注解/通配import等历史欠账。规范以统一Java文档为准，餐饮只参考模块隔离/薄适配和方法追溯。

## 二、Controller类与直接方法

全部位于beauty-web。以下为方法声明，注解中未使用全限定名；实现时用正常import。

### ai.neargo.shop.beauty.api.biz.BizAppointmentController

```java
AppointmentPreviewVO previewAppointment(@Valid @RequestBody PreviewAppointmentReq req, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
AppointmentVO createAppointment(@Valid @RequestBody CreateAppointmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
PageData<AppointmentSummaryVO> listAppointments(@Valid @ModelAttribute AppointmentQuery query, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
AppointmentDetailVO getAppointment(@PathVariable("appointmentNo") String appointmentNo, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
AppointmentChangePreviewVO previewAppointmentChange(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody PreviewAppointmentChangeReq req, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
AppointmentVO changeAppointment(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody ChangeAppointmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
CancellationVO cancelAppointment(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody CancelAppointmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
NoShowVO recordNoShow(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody RecordNoShowReq req, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
WorkOrderVO openWorkOrderFromAppointment(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody OpenAppointmentWorkOrderReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-038、BAPI-039、BAPI-040、BAPI-041、BAPI-042、BAPI-043、BAPI-044、BAPI-045、BAPI-052。

### ai.neargo.shop.beauty.api.biz.BizAppointmentWorkbenchController

```java
CalendarSnapshotVO getCalendarSnapshot(@Valid @ModelAttribute CalendarQuery query);
CalendarPageVO getCalendarPage(@Valid @ModelAttribute CalendarPageQuery query);
WorkbenchSummaryVO getWorkbenchSummary(@Valid @ModelAttribute WorkbenchSummaryQuery query);
PageData<ExceptionWorkitemVO> listWorkbenchExceptions(@Valid @ModelAttribute WorkbenchExceptionQuery query);
```

追溯：BAPI-025、BAPI-026、BAPI-117、BAPI-124。

### ai.neargo.shop.beauty.api.biz.BizAssetDisputeController

```java
AssetDisputeVO registerAssetDispute(@Valid @RequestBody RegisterAssetDisputeReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-103。

### ai.neargo.shop.beauty.api.biz.BizAvailabilityController

```java
AvailabilityVO queryAvailability(@Valid @RequestBody AvailabilityQuery query, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

追溯：BAPI-010。

### ai.neargo.shop.beauty.api.biz.BizBusinessTraceController

```java
BusinessTraceVO getTrace(@PathVariable("businessNo") String businessNo);
```

追溯：BAPI-137。

### ai.neargo.shop.beauty.api.biz.BizCapabilityApplicationController

```java
CapabilityApplicationVO applyCapability(@Valid @RequestBody ApplyCapabilityReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-004。

### ai.neargo.shop.beauty.api.biz.BizCapabilityController

```java
CapabilityStatusVO getCapabilityStatus(@Valid @ModelAttribute CapabilityStatusQuery query);
```

追溯：BAPI-135。

### ai.neargo.shop.beauty.api.biz.BizCareProfileController

```java
CareProfileVO getCareProfile(@PathVariable("customerNo") String customerNo, @Valid @ModelAttribute CareProfileQuery query);
CareNoteVO appendCareNote(@PathVariable("customerNo") String customerNo, @Valid @RequestBody AppendCareNoteReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
MediaAuthorizationVO authorizeCareMedia(@PathVariable("customerNo") String customerNo, @Valid @RequestBody AuthorizeCareMediaReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
PrivacyRequestVO requestCarePrivacy(@PathVariable("customerNo") String customerNo, @Valid @RequestBody CarePrivacyReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-034、BAPI-035、BAPI-036、BAPI-037。

### ai.neargo.shop.beauty.api.biz.BizCommissionEntryController

```java
PageData<CommissionEntryVO> listCommissionEntries(@Valid @ModelAttribute CommissionEntryQuery query);
```

追溯：BAPI-108。

### ai.neargo.shop.beauty.api.biz.BizCommissionPolicyController

```java
PageData<CommissionPolicyVO> listCommissionPolicies(@Valid @ModelAttribute CommissionPolicyQuery query);
CommissionPolicyVersionVO publishCommissionPolicy(@Valid @RequestBody PublishCommissionPolicyReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-106、BAPI-107。

### ai.neargo.shop.beauty.api.biz.BizCommissionSettlementRecordController

```java
SettlementRecordVO recordCommissionSettlement(@Valid @RequestBody RecordCommissionSettlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-110。

### ai.neargo.shop.beauty.api.biz.BizCustomerTimelineController

```java
PageData<CustomerTimelineVO> listCustomerTimeline(@PathVariable("customerNo") String customerNo, @Valid @ModelAttribute CustomerTimelineQuery query);
```

追溯：BAPI-116。

### ai.neargo.shop.beauty.api.biz.BizExceptionWorkitemController

```java
ExceptionResolutionVO resolveException(@PathVariable("workitemNo") String workitemNo, @Valid @RequestBody ResolveServiceExceptionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-125。

### ai.neargo.shop.beauty.api.biz.BizFollowUpTaskController

```java
PageData<FollowUpTaskVO> listFollowUps(@Valid @ModelAttribute FollowUpQuery query);
FollowUpTaskVO planFollowUp(@Valid @RequestBody PlanFollowUpReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
FollowUpEntryVO appendFollowUpEntry(@PathVariable("followUpNo") String followUpNo, @Valid @RequestBody AppendFollowUpEntryReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-126、BAPI-127、BAPI-128。

### ai.neargo.shop.beauty.api.biz.BizGroupSchedulingController

```java
GroupSchedulePreviewVO previewGroupSchedule(@Valid @RequestBody GroupScheduleQuery query);
```

追溯：BAPI-029。

### ai.neargo.shop.beauty.api.biz.BizGuestRegistrationController

```java
GuestRegistrationVO registerGuest(@Valid @RequestBody RegisterGuestReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
GuestClaimVO claimGuest(@PathVariable("guestNo") String guestNo, @Valid @RequestBody ClaimGuestReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-031、BAPI-032。

### ai.neargo.shop.beauty.api.biz.BizPerformanceAdjustmentCaseController

```java
AdjustmentCaseVO requestPerformanceAdjustment(@Valid @RequestBody PerformanceAdjustmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-109。

### ai.neargo.shop.beauty.api.biz.BizReceptionController

```java
ArrivalVO checkIn(@Valid @RequestBody CheckInReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
WalkInVO receiveWalkIn(@Valid @RequestBody ReceiveWalkInReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
ReceptionExceptionVO coordinateReceptionException(@Valid @RequestBody ReceptionExceptionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-047、BAPI-048、BAPI-053。

### ai.neargo.shop.beauty.api.biz.BizReportExportController

```java
ReportExportVO requestReportExport(@Valid @RequestBody RequestReportExportReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
ReportExportVO getReportExport(@PathVariable("exportNo") String exportNo);
```

追溯：BAPI-121、BAPI-122。

### ai.neargo.shop.beauty.api.biz.BizResourceChangeController

```java
ResourceChangePreviewVO previewResourceChange(@Valid @RequestBody PreviewResourceChangeReq req);
ResourceChangeResultVO changeResources(@Valid @RequestBody ChangeResourcesReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-027、BAPI-028。

### ai.neargo.shop.beauty.api.biz.BizServiceController

```java
ServiceDetailVO getService(@PathVariable("skuNo") String skuNo, @Valid @ModelAttribute ServiceDetailQuery query);
```

追溯：BAPI-009。

### ai.neargo.shop.beauty.api.biz.BizServiceDefinitionController

```java
PageData<ServiceDefinitionVO> listDefinitions(@Valid @ModelAttribute ServiceDefinitionQuery query, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
ServiceDefinitionVersionVO publishDefinitionVersion(@Valid @RequestBody PublishServiceDefinitionVersionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

追溯：BAPI-007、BAPI-008。

### ai.neargo.shop.beauty.api.biz.BizServiceOperationController

```java
OperationVO getOperation(@PathVariable("operationNo") String operationNo, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

追溯：BAPI-160。

### ai.neargo.shop.beauty.api.biz.BizServicePrintDocumentController

```java
PrintDocumentVO createPrintDocument(@Valid @RequestBody CreateServicePrintDocumentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-130。

### ai.neargo.shop.beauty.api.biz.BizServiceRemedyCaseController

```java
RemedyCaseVO requestServiceRemedy(@Valid @RequestBody RequestServiceRemedyReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-102。

### ai.neargo.shop.beauty.api.biz.BizServiceReportController

```java
SchedulingReportVO getSchedulingReport(@Valid @ModelAttribute SchedulingReportQuery query);
ServiceReportVO getServiceReport(@Valid @ModelAttribute ServiceReportQuery query);
```

追溯：BAPI-119、BAPI-120。

### ai.neargo.shop.beauty.api.biz.BizServiceSubmissionController

```java
ServiceSubmissionVO getSubmission(@PathVariable("submissionNo") String submissionNo);
RecoveryRequestVO requestSubmissionRecovery(@PathVariable("submissionNo") String submissionNo, @Valid @RequestBody RecoverSubmissionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-072、BAPI-073。

### ai.neargo.shop.beauty.api.biz.BizServiceTaskController

```java
PageData<ServiceTaskVO> listTasks(@Valid @ModelAttribute ServiceTaskQuery query);
TaskActionResultVO acceptTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody AcceptTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
TaskActionResultVO startTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody StartTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
ServiceStepVO completeStep(@PathVariable("taskNo") String taskNo, @PathVariable("stepNo") String stepNo, @Valid @RequestBody CompleteServiceStepReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
TaskHandoverPreviewVO previewHandover(@PathVariable("taskNo") String taskNo, @Valid @RequestBody PreviewTaskHandoverReq req);
TaskHandoverVO handoverTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody HandoverTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
TaskActionResultVO interruptTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody InterruptTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
TaskCompletionVO completeTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody CompleteTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
ServiceTaskDetailVO getTask(@PathVariable("taskNo") String taskNo);
TaskCancellationVO cancelTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody CancelTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
ServiceEvidenceVO appendServiceEvidence(@PathVariable("taskNo") String taskNo, @Valid @RequestBody AppendServiceEvidenceReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
ParticipationVO listParticipations(@PathVariable("taskNo") String taskNo);
```

追溯：BAPI-057、BAPI-058、BAPI-059、BAPI-060、BAPI-061、BAPI-062、BAPI-063、BAPI-064、BAPI-159、BAPI-100、BAPI-101、BAPI-105。

### ai.neargo.shop.beauty.api.biz.BizStorePolicyController

```java
StorePolicyVO getPolicy(@Valid @ModelAttribute StorePolicyQuery query, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
PolicyVersionVO publishPolicyVersion(@Valid @RequestBody PublishStorePolicyVersionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

追溯：BAPI-001、BAPI-002。

### ai.neargo.shop.beauty.api.biz.BizTreatmentPlanController

```java
PageData<TreatmentPlanVO> listTreatmentPlans(@Valid @ModelAttribute TreatmentPlanQuery query);
TreatmentPlanVO createTreatmentPlan(@Valid @RequestBody CreateTreatmentPlanReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-086、BAPI-087。

### ai.neargo.shop.beauty.api.biz.BizWaitingTicketController

```java
PageData<WaitingTicketVO> listWaitingTickets(@Valid @ModelAttribute WaitingTicketQuery query);
WaitingTicketVO joinWaitingQueue(@Valid @RequestBody JoinWaitingQueueReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
WaitingTicketVO actOnWaitingTicket(@PathVariable("ticketNo") String ticketNo, @Valid @RequestBody WaitingTicketActionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-049、BAPI-050、BAPI-051。

### ai.neargo.shop.beauty.api.biz.BizWorkOrderController

```java
WorkOrderVO openWorkOrder(@Valid @RequestBody OpenWorkOrderReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
WorkOrderDetailVO getWorkOrder(@PathVariable("workOrderNo") String workOrderNo);
ServiceSubmissionVO submitServices(@PathVariable("workOrderNo") String workOrderNo, @Valid @RequestBody SubmitServicesReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
ClosurePreviewVO previewClosure(@PathVariable("workOrderNo") String workOrderNo, @Valid @RequestBody PreviewWorkOrderClosureReq req);
WorkOrderClosureVO closeWorkOrder(@PathVariable("workOrderNo") String workOrderNo, @Valid @RequestBody CloseWorkOrderReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
StagePlanVO planStages(@PathVariable("workOrderNo") String workOrderNo, @Valid @RequestBody PlanWorkOrderStagesReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
WorkOrderBillVO getBill(@PathVariable("workOrderNo") String workOrderNo);
```

追溯：BAPI-054、BAPI-055、BAPI-056、BAPI-065、BAPI-066、BAPI-067、BAPI-071。

### ai.neargo.shop.beauty.api.mp.MpAppointmentController

```java
AppointmentPreviewVO previewMyAppointment(@Valid @RequestBody PreviewMyAppointmentReq req);
MyAppointmentVO createMyAppointment(@Valid @RequestBody CreateMyAppointmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
PageData<MyAppointmentVO> listMyAppointments(@Valid @ModelAttribute MyAppointmentQuery query);
MyAppointmentDetailVO getMyAppointment(@PathVariable("appointmentNo") String appointmentNo);
AppointmentChangePreviewVO previewMyAppointmentChange(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody PreviewMyAppointmentChangeReq req);
MyAppointmentVO changeMyAppointment(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody ChangeAppointmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
CancellationVO cancelMyAppointment(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody CancelAppointmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-147、BAPI-148、BAPI-149、BAPI-150、BAPI-151、BAPI-152、BAPI-153。

### ai.neargo.shop.beauty.api.mp.MpAvailabilityController

```java
PublicAvailabilityVO queryPublicAvailability(@Valid @RequestBody PublicAvailabilityQuery query);
```

追溯：BAPI-146。

### ai.neargo.shop.beauty.api.mp.MpCustomerTimelineController

```java
PageData<MyTimelineVO> listMyTimeline(@Valid @ModelAttribute MyTimelineQuery query);
```

追溯：BAPI-158。

### ai.neargo.shop.beauty.api.mp.MpReceptionController

```java
MyArrivalVO checkInMyAppointment(@Valid @RequestBody MyCheckInReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-154。

### ai.neargo.shop.beauty.api.mp.MpServiceController

```java
PublicServiceVO getPublicService(@PathVariable("skuNo") String skuNo, @Valid @ModelAttribute ServiceDetailQuery query);
```

追溯：BAPI-145。

### ai.neargo.shop.beauty.api.mp.MpServiceDisputeController

```java
ServiceDisputeVO raiseMyServiceDispute(@Valid @RequestBody RaiseMyServiceDisputeReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-157。

### ai.neargo.shop.beauty.api.mp.MpStoreController

```java
PublicStoreVO getPublicStore(@PathVariable("storeNo") String storeNo);
```

追溯：BAPI-144。

### ai.neargo.shop.beauty.api.mp.MpTreatmentPlanController

```java
PageData<MyTreatmentPlanVO> listMyTreatmentPlans(@Valid @ModelAttribute MyTreatmentPlanQuery query);
```

追溯：BAPI-156。

### ai.neargo.shop.beauty.api.mp.MpWorkOrderController

```java
MyWorkOrderVO getMyWorkOrder(@PathVariable("workOrderNo") String workOrderNo);
```

追溯：BAPI-155。

### ai.neargo.shop.beauty.api.ops.OpsServiceRecoveryController

```java
PageData<RecoveryWorkitemVO> listRecoveryWorkitems(@Valid @ModelAttribute RecoveryWorkitemQuery query);
RecoveryRequestVO requestRecovery(@Valid @RequestBody RequestServiceRecoveryReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

追溯：BAPI-138、BAPI-139。

## 三、Service接口与同名Impl方法

每组接口位于beauty-api，同名Impl位于beauty-core；Impl覆写同一签名，不再人为复制一组不同的Command/DTO。

### ai.neargo.shop.beauty.service.AppointmentAvailabilityService

实现：`ai.neargo.shop.beauty.service.impl.AppointmentAvailabilityServiceImpl`。

```java
AvailabilityVO queryAvailability(String entityNo, String currentStoreNo, String actorNo, AvailabilityQuery query);
PublicAvailabilityVO queryPublicAvailability(PublicAvailabilityQuery query);
```

### ai.neargo.shop.beauty.service.AppointmentService

实现：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`。

```java
AppointmentPreviewVO previewAppointment(String entityNo, String currentStoreNo, String actorNo, PreviewAppointmentReq req);
AppointmentVO createAppointment(String entityNo, String currentStoreNo, String actorNo, CreateAppointmentReq req, String idempotencyKey);
PageData<AppointmentSummaryVO> listAppointments(String entityNo, String currentStoreNo, String actorNo, AppointmentQuery query);
AppointmentDetailVO getAppointment(String entityNo, String currentStoreNo, String actorNo, String appointmentNo);
AppointmentChangePreviewVO previewAppointmentChange(String entityNo, String currentStoreNo, String actorNo, String appointmentNo, PreviewAppointmentChangeReq req);
AppointmentVO changeAppointment(String entityNo, String currentStoreNo, String actorNo, String appointmentNo, ChangeAppointmentReq req, String idempotencyKey);
CancellationVO cancelAppointment(String entityNo, String currentStoreNo, String actorNo, String appointmentNo, CancelAppointmentReq req, String idempotencyKey);
NoShowVO recordNoShow(String entityNo, String currentStoreNo, String actorNo, String appointmentNo, RecordNoShowReq req, String idempotencyKey);
AppointmentPreviewVO previewMyAppointment(String userNo, PreviewMyAppointmentReq req);
MyAppointmentVO createMyAppointment(String userNo, CreateMyAppointmentReq req, String idempotencyKey);
PageData<MyAppointmentVO> listMyAppointments(String userNo, MyAppointmentQuery query);
MyAppointmentDetailVO getMyAppointment(String userNo, String appointmentNo);
AppointmentChangePreviewVO previewMyAppointmentChange(String userNo, String appointmentNo, PreviewMyAppointmentChangeReq req);
MyAppointmentVO changeMyAppointment(String userNo, String appointmentNo, ChangeAppointmentReq req, String idempotencyKey);
CancellationVO cancelMyAppointment(String userNo, String appointmentNo, CancelAppointmentReq req, String idempotencyKey);
WorkOrderVO openWorkOrderFromAppointment(String entityNo, String currentStoreNo, String actorNo, String appointmentNo, OpenAppointmentWorkOrderReq req, String idempotencyKey);
```

### ai.neargo.shop.beauty.service.AppointmentWorkbenchQueryService

实现：`ai.neargo.shop.beauty.service.impl.AppointmentWorkbenchQueryServiceImpl`。

```java
CalendarSnapshotVO getCalendarSnapshot(String entityNo, String currentStoreNo, String actorNo, CalendarQuery query);
CalendarPageVO getCalendarPage(String entityNo, String currentStoreNo, String actorNo, CalendarPageQuery query);
WorkbenchSummaryVO getWorkbenchSummary(String entityNo, String currentStoreNo, String actorNo, WorkbenchSummaryQuery query);
PageData<ExceptionWorkitemVO> listWorkbenchExceptions(String entityNo, String currentStoreNo, String actorNo, WorkbenchExceptionQuery query);
```

### ai.neargo.shop.beauty.service.CapabilityService

实现：`ai.neargo.shop.beauty.service.impl.CapabilityServiceImpl`。

```java
CapabilityApplicationVO applyCapability(String entityNo, String currentStoreNo, String actorNo, ApplyCapabilityReq req, String idempotencyKey);
CapabilityStatusVO getCapabilityStatus(String entityNo, String currentStoreNo, String actorNo, CapabilityStatusQuery query);
```

### ai.neargo.shop.beauty.service.CareProfileService

实现：`ai.neargo.shop.beauty.service.impl.CareProfileServiceImpl`。

```java
CareProfileVO getCareProfile(String entityNo, String currentStoreNo, String actorNo, String customerNo, CareProfileQuery query);
CareNoteVO appendCareNote(String entityNo, String currentStoreNo, String actorNo, String customerNo, AppendCareNoteReq req, String idempotencyKey);
MediaAuthorizationVO authorizeCareMedia(String entityNo, String currentStoreNo, String actorNo, String customerNo, AuthorizeCareMediaReq req, String idempotencyKey);
PrivacyRequestVO requestCarePrivacy(String entityNo, String currentStoreNo, String actorNo, String customerNo, CarePrivacyReq req, String idempotencyKey);
```

### ai.neargo.shop.beauty.service.CommissionPolicyService

实现：`ai.neargo.shop.beauty.service.impl.CommissionPolicyServiceImpl`。

```java
PageData<CommissionPolicyVO> listCommissionPolicies(String entityNo, String currentStoreNo, String actorNo, CommissionPolicyQuery query);
CommissionPolicyVersionVO publishCommissionPolicy(String entityNo, String currentStoreNo, String actorNo, PublishCommissionPolicyReq req, String idempotencyKey);
```

### ai.neargo.shop.beauty.service.CommissionService

实现：`ai.neargo.shop.beauty.service.impl.CommissionServiceImpl`。

```java
PageData<CommissionEntryVO> listCommissionEntries(String entityNo, String currentStoreNo, String actorNo, CommissionEntryQuery query);
SettlementRecordVO recordCommissionSettlement(String entityNo, String currentStoreNo, String actorNo, RecordCommissionSettlementReq req, String idempotencyKey);
List<CommissionEntryVO> accrueFromAllocation(String entityNo, String currentStoreNo, String actorNo, String allocationNo, String commissionPolicyNo);
List<CommissionEntryVO> reverseAccrual(String entityNo, String currentStoreNo, String actorNo, String originalEntryNo, String approvedAdjustmentNo);
```

`accrueFromAllocation`：P3计提业务键幂等；金额用整数最小单位和币种，不按商品原价默认计提。

内部流程：**LOCAL_ATOMIC**；读取：bty_performance_allocation、bty_performance_share、bty_commission_policy、bty_commission_entry；写入：bty_commission_entry、bty_action_log。

守卫：每份share/政策/计提类别形成稳定businessKey唯一，重复返回原账。

`reverseAccrual`：P3受支持逆向只追加冲回，原计提金额不可覆盖；并非资金退款。

内部流程：**ORCHESTRATED**；读取：bty_commission_entry；写入：bty_commission_entry、bty_action_log。

守卫：锁定原业务事实并唯一追加反向凭证，不能用update改原金额。

### ai.neargo.shop.beauty.service.CustomerTimelineQueryService

实现：`ai.neargo.shop.beauty.service.impl.CustomerTimelineQueryServiceImpl`。

```java
PageData<CustomerTimelineVO> listCustomerTimeline(String entityNo, String currentStoreNo, String actorNo, String customerNo, CustomerTimelineQuery query);
PageData<MyTimelineVO> listMyTimeline(String userNo, MyTimelineQuery query);
```

### ai.neargo.shop.beauty.service.FollowUpCoordinationService

实现：`ai.neargo.shop.beauty.service.impl.FollowUpCoordinationServiceImpl`。

```java
PageData<FollowUpTaskVO> listFollowUps(String entityNo, String currentStoreNo, String actorNo, FollowUpQuery query);
FollowUpTaskVO planFollowUp(String entityNo, String currentStoreNo, String actorNo, PlanFollowUpReq req, String idempotencyKey);
FollowUpEntryVO appendFollowUpEntry(String entityNo, String currentStoreNo, String actorNo, String followUpNo, AppendFollowUpEntryReq req, String idempotencyKey);
```

### ai.neargo.shop.beauty.service.GroupSchedulingService

实现：`ai.neargo.shop.beauty.service.impl.GroupSchedulingServiceImpl`。

```java
GroupSchedulePreviewVO previewGroupSchedule(String entityNo, String currentStoreNo, String actorNo, GroupScheduleQuery query);
```

### ai.neargo.shop.beauty.service.GuestRegistrationService

实现：`ai.neargo.shop.beauty.service.impl.GuestRegistrationServiceImpl`。

```java
GuestRegistrationVO registerGuest(String entityNo, String currentStoreNo, String actorNo, RegisterGuestReq req, String idempotencyKey);
GuestClaimVO claimGuest(String entityNo, String currentStoreNo, String actorNo, String guestNo, ClaimGuestReq req, String idempotencyKey);
```

### ai.neargo.shop.beauty.service.PerformanceService

实现：`ai.neargo.shop.beauty.service.impl.PerformanceServiceImpl`。

```java
AdjustmentCaseVO requestPerformanceAdjustment(String entityNo, String currentStoreNo, String actorNo, PerformanceAdjustmentReq req, String idempotencyKey);
PerformanceAllocationVO allocateCompletedTask(String entityNo, String currentStoreNo, String actorNo, String taskNo, long taskRevision, String ruleVersionNo);
```

`allocateCompletedTask`：P3内部产出分配，按实际参与和可信来源事实，首版唯一锚点/后续追加；返回受控处理结果，非新售后案件。

内部流程：**ORCHESTRATED**；读取：bty_service_task、bty_service_participation、bty_trade_binding、bty_performance_allocation；写入：bty_performance_allocation、bty_performance_share、bty_action_log。

守卫：首版并发靠唯一键，后续版本锁首版锚点；分摊合计守恒、售卡与服务分开。

### ai.neargo.shop.beauty.service.ReceptionService

实现：`ai.neargo.shop.beauty.service.impl.ReceptionServiceImpl`。

```java
ArrivalVO checkIn(String entityNo, String currentStoreNo, String actorNo, CheckInReq req, String idempotencyKey);
WalkInVO receiveWalkIn(String entityNo, String currentStoreNo, String actorNo, ReceiveWalkInReq req, String idempotencyKey);
PageData<WaitingTicketVO> listWaitingTickets(String entityNo, String currentStoreNo, String actorNo, WaitingTicketQuery query);
WaitingTicketVO joinWaitingQueue(String entityNo, String currentStoreNo, String actorNo, JoinWaitingQueueReq req, String idempotencyKey);
WaitingTicketVO actOnWaitingTicket(String entityNo, String currentStoreNo, String actorNo, String ticketNo, WaitingTicketActionReq req, String idempotencyKey);
ReceptionExceptionVO coordinateReceptionException(String entityNo, String currentStoreNo, String actorNo, ReceptionExceptionReq req, String idempotencyKey);
MyArrivalVO checkInMyAppointment(String userNo, MyCheckInReq req, String idempotencyKey);
```

### ai.neargo.shop.beauty.service.ResourceChangeService

实现：`ai.neargo.shop.beauty.service.impl.ResourceChangeServiceImpl`。

```java
ResourceChangePreviewVO previewResourceChange(String entityNo, String currentStoreNo, String actorNo, PreviewResourceChangeReq req);
ResourceChangeResultVO changeResources(String entityNo, String currentStoreNo, String actorNo, ChangeResourcesReq req, String idempotencyKey);
```

### ai.neargo.shop.beauty.service.ServiceCaseCoordinationService

实现：`ai.neargo.shop.beauty.service.impl.ServiceCaseCoordinationServiceImpl`。

```java
RemedyCaseVO requestServiceRemedy(String entityNo, String currentStoreNo, String actorNo, RequestServiceRemedyReq req, String idempotencyKey);
AssetDisputeVO registerAssetDispute(String entityNo, String currentStoreNo, String actorNo, RegisterAssetDisputeReq req, String idempotencyKey);
ServiceDisputeVO raiseMyServiceDispute(String userNo, RaiseMyServiceDisputeReq req, String idempotencyKey);
```

### ai.neargo.shop.beauty.service.ServiceCatalogQueryService

实现：`ai.neargo.shop.beauty.service.impl.ServiceCatalogQueryServiceImpl`。

```java
PublicStoreVO getPublicStore(String storeNo);
ServiceDetailVO getService(String entityNo, String currentStoreNo, String actorNo, String skuNo, ServiceDetailQuery query);
PublicServiceVO getPublicService(String skuNo, ServiceDetailQuery query);
```

### ai.neargo.shop.beauty.service.ServiceDefinitionService

实现：`ai.neargo.shop.beauty.service.impl.ServiceDefinitionServiceImpl`。

```java
PageData<ServiceDefinitionVO> listDefinitions(String entityNo, String currentStoreNo, String actorNo, ServiceDefinitionQuery query);
ServiceDefinitionVersionVO publishDefinitionVersion(String entityNo, String currentStoreNo, String actorNo, PublishServiceDefinitionVersionReq req, String idempotencyKey);
```

### ai.neargo.shop.beauty.service.ServiceExceptionService

实现：`ai.neargo.shop.beauty.service.impl.ServiceExceptionServiceImpl`。

```java
ExceptionResolutionVO resolveException(String entityNo, String currentStoreNo, String actorNo, String workitemNo, ResolveServiceExceptionReq req, String idempotencyKey);
```

### ai.neargo.shop.beauty.service.ServiceOperationQueryService

实现：`ai.neargo.shop.beauty.service.impl.ServiceOperationQueryServiceImpl`。

```java
OperationVO getOperation(String entityNo, String currentStoreNo, String actorNo, String operationNo);
```

### ai.neargo.shop.beauty.service.ServicePrintDocumentService

实现：`ai.neargo.shop.beauty.service.impl.ServicePrintDocumentServiceImpl`。

```java
PrintDocumentVO createPrintDocument(String entityNo, String currentStoreNo, String actorNo, CreateServicePrintDocumentReq req, String idempotencyKey);
```

### ai.neargo.shop.beauty.service.ServiceRecoveryService

实现：`ai.neargo.shop.beauty.service.impl.ServiceRecoveryServiceImpl`。

```java
PageData<RecoveryWorkitemVO> listRecoveryWorkitems(String entityNo, Set<String> authorizedStoreNos, String actorNo, RecoveryWorkitemQuery query);
RecoveryRequestVO requestRecovery(String entityNo, Set<String> authorizedStoreNos, String actorNo, RequestServiceRecoveryReq req, String idempotencyKey);
```

### ai.neargo.shop.beauty.service.ServiceReportQueryService

实现：`ai.neargo.shop.beauty.service.impl.ServiceReportQueryServiceImpl`。

```java
SchedulingReportVO getSchedulingReport(String entityNo, String currentStoreNo, String actorNo, Set<String> authorizedStoreNos, SchedulingReportQuery query);
ServiceReportVO getServiceReport(String entityNo, String currentStoreNo, String actorNo, Set<String> authorizedStoreNos, ServiceReportQuery query);
ReportExportVO requestReportExport(String entityNo, String currentStoreNo, String actorNo, Set<String> authorizedStoreNos, RequestReportExportReq req, String idempotencyKey);
ReportExportVO getReportExport(String entityNo, String currentStoreNo, String actorNo, String exportNo);
```

### ai.neargo.shop.beauty.service.ServiceSubmissionService

实现：`ai.neargo.shop.beauty.service.impl.ServiceSubmissionServiceImpl`。

```java
ServiceSubmissionVO getSubmission(String entityNo, String currentStoreNo, String actorNo, String submissionNo);
RecoveryRequestVO requestSubmissionRecovery(String entityNo, String currentStoreNo, String actorNo, String submissionNo, RecoverSubmissionReq req, String idempotencyKey);
ServiceSubmissionVO submitForWorkOrder(String entityNo, String currentStoreNo, String actorNo, String workOrderNo, SubmitServicesReq req, String idempotencyKey);
ServiceSubmissionVO recoverPending(String entityNo, String currentStoreNo, String actorNo, String submissionNo);
```

`submitForWorkOrder`：WorkOrderService.submitServices委托；保存原来源意图，不另开HTTP入口。

内部流程：**ORCHESTRATED**；读取：bty_work_order、bty_service_task、bty_source_allocation_guard；写入：bty_service_submission、bty_trade_binding、bty_source_allocation_guard、bty_service_task、bty_work_order、bty_action_log。

守卫：来源写者仍在Core/Pay；先保存意图和范围版本，再绑定原来源。

`recoverPending`：业务恢复Job/已审核恢复命令共用此方法；用意图原键，不接收新请求键替换来源。

内部流程：**ORCHESTRATED**；读取：bty_service_submission、bty_trade_binding、bty_source_allocation_guard、bty_work_order、bty_service_task；写入：bty_service_submission、bty_trade_binding、bty_source_allocation_guard、bty_service_task、bty_action_log。

守卫：只按原键查询/幂等恢复，不新号支付或重复分配；涉及任务绑定才按workOrder→task→提交/guard锁序确认，冻结/绑定修订变化时拒绝旧结果覆盖；纯预约资源恢复分支不伪造任务。

### ai.neargo.shop.beauty.service.ServiceTaskService

实现：`ai.neargo.shop.beauty.service.impl.ServiceTaskServiceImpl`。

```java
PageData<ServiceTaskVO> listTasks(String entityNo, String currentStoreNo, String actorNo, ServiceTaskQuery query);
TaskActionResultVO acceptTask(String entityNo, String currentStoreNo, String actorNo, String taskNo, AcceptTaskReq req, String idempotencyKey);
TaskActionResultVO startTask(String entityNo, String currentStoreNo, String actorNo, String taskNo, StartTaskReq req, String idempotencyKey);
ServiceStepVO completeStep(String entityNo, String currentStoreNo, String actorNo, String taskNo, String stepNo, CompleteServiceStepReq req, String idempotencyKey);
TaskHandoverPreviewVO previewHandover(String entityNo, String currentStoreNo, String actorNo, String taskNo, PreviewTaskHandoverReq req);
TaskHandoverVO handoverTask(String entityNo, String currentStoreNo, String actorNo, String taskNo, HandoverTaskReq req, String idempotencyKey);
TaskActionResultVO interruptTask(String entityNo, String currentStoreNo, String actorNo, String taskNo, InterruptTaskReq req, String idempotencyKey);
TaskCompletionVO completeTask(String entityNo, String currentStoreNo, String actorNo, String taskNo, CompleteTaskReq req, String idempotencyKey);
ServiceTaskDetailVO getTask(String entityNo, String currentStoreNo, String actorNo, String taskNo);
TaskCancellationVO cancelTask(String entityNo, String currentStoreNo, String actorNo, String taskNo, CancelTaskReq req, String idempotencyKey);
ServiceEvidenceVO appendServiceEvidence(String entityNo, String currentStoreNo, String actorNo, String taskNo, AppendServiceEvidenceReq req, String idempotencyKey);
ParticipationVO listParticipations(String entityNo, String currentStoreNo, String actorNo, String taskNo);
TaskCompletionVO confirmFulfillment(String entityNo, String currentStoreNo, String taskNo, long bindingRevision, String sourceOperationNo, String eventNo);
```

`confirmFulfillment`：验签/去重后再权威查询；只确认当前绑定的实际履约，不把资金事件当服务完工；不再调用recordCompletion或增加doneQty，重复回调只返回原结果。

内部流程：**ORCHESTRATED**；读取：bty_work_order、bty_service_task、bty_trade_binding、bty_service_submission；写入：bty_service_submission、bty_action_log。

守卫：验签、去重和绑定版本核对后，仅确认原来源履约结果/提交检查点；服务完工事实此前已提交，不增加doneQty、不重做参与段结束、不重扣资产；重复回调返回原状态。

### ai.neargo.shop.beauty.service.StorePolicyService

实现：`ai.neargo.shop.beauty.service.impl.StorePolicyServiceImpl`。

```java
StorePolicyVO getPolicy(String entityNo, String currentStoreNo, String actorNo, StorePolicyQuery query);
PolicyVersionVO publishPolicyVersion(String entityNo, String currentStoreNo, String actorNo, PublishStorePolicyVersionReq req, String idempotencyKey);
```

### ai.neargo.shop.beauty.service.TreatmentPlanService

实现：`ai.neargo.shop.beauty.service.impl.TreatmentPlanServiceImpl`。

```java
PageData<TreatmentPlanVO> listTreatmentPlans(String entityNo, String currentStoreNo, String actorNo, TreatmentPlanQuery query);
TreatmentPlanVO createTreatmentPlan(String entityNo, String currentStoreNo, String actorNo, CreateTreatmentPlanReq req, String idempotencyKey);
PageData<MyTreatmentPlanVO> listMyTreatmentPlans(String userNo, MyTreatmentPlanQuery query);
```

### ai.neargo.shop.beauty.service.WorkOrderBillService

实现：`ai.neargo.shop.beauty.service.impl.WorkOrderBillServiceImpl`。

```java
BusinessTraceVO getTrace(String entityNo, String currentStoreNo, String actorNo, String businessNo);
```

### ai.neargo.shop.beauty.service.WorkOrderService

实现：`ai.neargo.shop.beauty.service.impl.WorkOrderServiceImpl`。

```java
WorkOrderVO openWorkOrder(String entityNo, String currentStoreNo, String actorNo, OpenWorkOrderReq req, String idempotencyKey);
WorkOrderDetailVO getWorkOrder(String entityNo, String currentStoreNo, String actorNo, String workOrderNo);
ServiceSubmissionVO submitServices(String entityNo, String currentStoreNo, String actorNo, String workOrderNo, SubmitServicesReq req, String idempotencyKey);
ClosurePreviewVO previewClosure(String entityNo, String currentStoreNo, String actorNo, String workOrderNo, PreviewWorkOrderClosureReq req);
WorkOrderClosureVO closeWorkOrder(String entityNo, String currentStoreNo, String actorNo, String workOrderNo, CloseWorkOrderReq req, String idempotencyKey);
StagePlanVO planStages(String entityNo, String currentStoreNo, String actorNo, String workOrderNo, PlanWorkOrderStagesReq req, String idempotencyKey);
MyWorkOrderVO getMyWorkOrder(String userNo, String workOrderNo);
WorkOrderBillVO getBill(String entityNo, String currentStoreNo, String actorNo, String workOrderNo);
```

## 四、远程Service、客户端和内部Controller

standalone时需要以下按资源切片的目标类；embedded不装Remote实现。RemoteXxxService覆写上节同一Service的全部方法，仅做协议/错误映射；内部调用采用签名身份与动作/对象/范围委托，不能将可信Service参数直接作为可伪造Body。

协议client方法与内部Controller使用同一业务方法名和返回值；用户入口方法沿原Path/Body/Query/幂等头绑定，可信范围由内部安全层验证委托后恢复再调用Service。非HTTP补充动作使用Typed Body绑定其业务参数，entity/store/actor依然取可信委托。每个操作使用固定、显式内部路由白名单，不注册按任意方法名反射调用的万能RPC；内部路由最终发布前单列协议并做契约测试。

| Service名 | beauty-web远程实现完整名 | beauty-channel协议客户端完整名 | beauty-core内部Controller完整名 |
|---|---|---|---|
| AppointmentAvailabilityService | `ai.neargo.shop.beautyclient.impl.RemoteAppointmentAvailabilityService` | `ai.neargo.shop.beauty.client.AppointmentAvailabilityInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalAppointmentAvailabilityController` |
| AppointmentService | `ai.neargo.shop.beautyclient.impl.RemoteAppointmentService` | `ai.neargo.shop.beauty.client.AppointmentInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalAppointmentController` |
| AppointmentWorkbenchQueryService | `ai.neargo.shop.beautyclient.impl.RemoteAppointmentWorkbenchQueryService` | `ai.neargo.shop.beauty.client.AppointmentWorkbenchInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalAppointmentWorkbenchController` |
| CapabilityService | `ai.neargo.shop.beautyclient.impl.RemoteCapabilityService` | `ai.neargo.shop.beauty.client.CapabilityInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalCapabilityController` |
| CareProfileService | `ai.neargo.shop.beautyclient.impl.RemoteCareProfileService` | `ai.neargo.shop.beauty.client.CareProfileInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalCareProfileController` |
| CommissionPolicyService | `ai.neargo.shop.beautyclient.impl.RemoteCommissionPolicyService` | `ai.neargo.shop.beauty.client.CommissionPolicyInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalCommissionPolicyController` |
| CommissionService | `ai.neargo.shop.beautyclient.impl.RemoteCommissionService` | `ai.neargo.shop.beauty.client.CommissionInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalCommissionController` |
| CustomerTimelineQueryService | `ai.neargo.shop.beautyclient.impl.RemoteCustomerTimelineQueryService` | `ai.neargo.shop.beauty.client.CustomerTimelineInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalCustomerTimelineController` |
| FollowUpCoordinationService | `ai.neargo.shop.beautyclient.impl.RemoteFollowUpCoordinationService` | `ai.neargo.shop.beauty.client.FollowUpCoordinationInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalFollowUpCoordinationController` |
| GroupSchedulingService | `ai.neargo.shop.beautyclient.impl.RemoteGroupSchedulingService` | `ai.neargo.shop.beauty.client.GroupSchedulingInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalGroupSchedulingController` |
| GuestRegistrationService | `ai.neargo.shop.beautyclient.impl.RemoteGuestRegistrationService` | `ai.neargo.shop.beauty.client.GuestRegistrationInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalGuestRegistrationController` |
| PerformanceService | `ai.neargo.shop.beautyclient.impl.RemotePerformanceService` | `ai.neargo.shop.beauty.client.PerformanceInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalPerformanceController` |
| ReceptionService | `ai.neargo.shop.beautyclient.impl.RemoteReceptionService` | `ai.neargo.shop.beauty.client.ReceptionInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalReceptionController` |
| ResourceChangeService | `ai.neargo.shop.beautyclient.impl.RemoteResourceChangeService` | `ai.neargo.shop.beauty.client.ResourceChangeInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalResourceChangeController` |
| ServiceCaseCoordinationService | `ai.neargo.shop.beautyclient.impl.RemoteServiceCaseCoordinationService` | `ai.neargo.shop.beauty.client.ServiceCaseCoordinationInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalServiceCaseCoordinationController` |
| ServiceCatalogQueryService | `ai.neargo.shop.beautyclient.impl.RemoteServiceCatalogQueryService` | `ai.neargo.shop.beauty.client.ServiceCatalogInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalServiceCatalogController` |
| ServiceDefinitionService | `ai.neargo.shop.beautyclient.impl.RemoteServiceDefinitionService` | `ai.neargo.shop.beauty.client.ServiceDefinitionInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalServiceDefinitionController` |
| ServiceExceptionService | `ai.neargo.shop.beautyclient.impl.RemoteServiceExceptionService` | `ai.neargo.shop.beauty.client.ServiceExceptionInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalServiceExceptionController` |
| ServiceOperationQueryService | `ai.neargo.shop.beautyclient.impl.RemoteServiceOperationQueryService` | `ai.neargo.shop.beauty.client.ServiceOperationInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalServiceOperationController` |
| ServicePrintDocumentService | `ai.neargo.shop.beautyclient.impl.RemoteServicePrintDocumentService` | `ai.neargo.shop.beauty.client.ServicePrintDocumentInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalServicePrintDocumentController` |
| ServiceRecoveryService | `ai.neargo.shop.beautyclient.impl.RemoteServiceRecoveryService` | `ai.neargo.shop.beauty.client.ServiceRecoveryInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalServiceRecoveryController` |
| ServiceReportQueryService | `ai.neargo.shop.beautyclient.impl.RemoteServiceReportQueryService` | `ai.neargo.shop.beauty.client.ServiceReportInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalServiceReportController` |
| ServiceSubmissionService | `ai.neargo.shop.beautyclient.impl.RemoteServiceSubmissionService` | `ai.neargo.shop.beauty.client.ServiceSubmissionInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalServiceSubmissionController` |
| ServiceTaskService | `ai.neargo.shop.beautyclient.impl.RemoteServiceTaskService` | `ai.neargo.shop.beauty.client.ServiceTaskInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalServiceTaskController` |
| StorePolicyService | `ai.neargo.shop.beautyclient.impl.RemoteStorePolicyService` | `ai.neargo.shop.beauty.client.StorePolicyInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalStorePolicyController` |
| TreatmentPlanService | `ai.neargo.shop.beautyclient.impl.RemoteTreatmentPlanService` | `ai.neargo.shop.beauty.client.TreatmentPlanInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalTreatmentPlanController` |
| WorkOrderBillService | `ai.neargo.shop.beautyclient.impl.RemoteWorkOrderBillService` | `ai.neargo.shop.beauty.client.WorkOrderBillInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalWorkOrderBillController` |
| WorkOrderService | `ai.neargo.shop.beautyclient.impl.RemoteWorkOrderService` | `ai.neargo.shop.beauty.client.WorkOrderInternalClient` | `ai.neargo.shop.beauty.api.internal.InternalWorkOrderController` |

该表是结构定位，未虚构已经发布的内部HTTP路径、现有委托DTO或已运行的RPC能力。具体Controller→Service→Impl业务方法在本清单已直接给出，内部薄传输不复制业务算法。

## 五、Mapper与Entity类

| 表 | Entity完整名（beauty-core） | 内嵌Mapper完整名（beauty-core） | 自定义方法数 |
|---|---|---|---:|
| bty_store_policy | `ai.neargo.shop.beauty.entity.BtyStorePolicy` | `ai.neargo.shop.beauty.mapper.BeautyMappers.StorePolicyMapper` | 2 |
| bty_store_policy_version | `ai.neargo.shop.beauty.entity.BtyStorePolicyVersion` | `ai.neargo.shop.beauty.mapper.BeautyMappers.StorePolicyVersionMapper` | 0 |
| bty_service_definition | `ai.neargo.shop.beauty.entity.BtyServiceDefinition` | `ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceDefinitionMapper` | 2 |
| bty_service_definition_version | `ai.neargo.shop.beauty.entity.BtyServiceDefinitionVersion` | `ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceDefinitionVersionMapper` | 0 |
| bty_service_requirement | `ai.neargo.shop.beauty.entity.BtyServiceRequirement` | `ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceRequirementMapper` | 0 |
| bty_appointment | `ai.neargo.shop.beauty.entity.BtyAppointment` | `ai.neargo.shop.beauty.mapper.BeautyMappers.AppointmentMapper` | 7 |
| bty_appointment_item | `ai.neargo.shop.beauty.entity.BtyAppointmentItem` | `ai.neargo.shop.beauty.mapper.BeautyMappers.AppointmentItemMapper` | 0 |
| bty_appointment_assignment | `ai.neargo.shop.beauty.entity.BtyAppointmentAssignment` | `ai.neargo.shop.beauty.mapper.BeautyMappers.AppointmentAssignmentMapper` | 1 |
| bty_work_order | `ai.neargo.shop.beauty.entity.BtyWorkOrder` | `ai.neargo.shop.beauty.mapper.BeautyMappers.WorkOrderMapper` | 6 |
| bty_service_task | `ai.neargo.shop.beauty.entity.BtyServiceTask` | `ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceTaskMapper` | 5 |
| bty_service_step | `ai.neargo.shop.beauty.entity.BtyServiceStep` | `ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceStepMapper` | 2 |
| bty_service_participation | `ai.neargo.shop.beauty.entity.BtyServiceParticipation` | `ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceParticipationMapper` | 2 |
| bty_service_submission | `ai.neargo.shop.beauty.entity.BtyServiceSubmission` | `ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceSubmissionMapper` | 5 |
| bty_trade_binding | `ai.neargo.shop.beauty.entity.BtyTradeBinding` | `ai.neargo.shop.beauty.mapper.BeautyMappers.TradeBindingMapper` | 1 |
| bty_action_log | `ai.neargo.shop.beauty.entity.BtyActionLog` | `ai.neargo.shop.beauty.mapper.BeautyMappers.ActionLogMapper` | 0 |
| bty_care_profile | `ai.neargo.shop.beauty.entity.BtyCareProfile` | `ai.neargo.shop.beauty.mapper.BeautyMappers.CareProfileMapper` | 2 |
| bty_care_note | `ai.neargo.shop.beauty.entity.BtyCareNote` | `ai.neargo.shop.beauty.mapper.BeautyMappers.CareNoteMapper` | 0 |
| bty_care_media | `ai.neargo.shop.beauty.entity.BtyCareMedia` | `ai.neargo.shop.beauty.mapper.BeautyMappers.CareMediaMapper` | 1 |
| bty_treatment_plan | `ai.neargo.shop.beauty.entity.BtyTreatmentPlan` | `ai.neargo.shop.beauty.mapper.BeautyMappers.TreatmentPlanMapper` | 2 |
| bty_treatment_session | `ai.neargo.shop.beauty.entity.BtyTreatmentSession` | `ai.neargo.shop.beauty.mapper.BeautyMappers.TreatmentSessionMapper` | 1 |
| bty_performance_allocation | `ai.neargo.shop.beauty.entity.BtyPerformanceAllocation` | `ai.neargo.shop.beauty.mapper.BeautyMappers.PerformanceAllocationMapper` | 1 |
| bty_performance_share | `ai.neargo.shop.beauty.entity.BtyPerformanceShare` | `ai.neargo.shop.beauty.mapper.BeautyMappers.PerformanceShareMapper` | 0 |
| bty_commission_policy | `ai.neargo.shop.beauty.entity.BtyCommissionPolicy` | `ai.neargo.shop.beauty.mapper.BeautyMappers.CommissionPolicyMapper` | 1 |
| bty_commission_entry | `ai.neargo.shop.beauty.entity.BtyCommissionEntry` | `ai.neargo.shop.beauty.mapper.BeautyMappers.CommissionEntryMapper` | 2 |
| bty_source_allocation_guard | `ai.neargo.shop.beauty.entity.BtySourceAllocationGuard` | `ai.neargo.shop.beauty.mapper.BeautyMappers.SourceAllocationGuardMapper` | 4 |

外部文件为`ai/neargo/shop/beauty/mapper/BeautyMappers.java`，每个嵌套接口继承其BaseMapper<Entity>。Entity继承`ai.neargo.shop.common.BaseEntity`，使用零售相同Getter/Setter、审计/乐观锁/逻辑删除基类；entityNo等自身字段按表补充。Entity不添加业务跨域调用，字段、唯一键和类型以数据库字段字典为准。所有Mapper继承方法与自定义完整签名见方法清单§四。

## 六、Req / Query / VO类型

全部位于beauty-api的`ai.neargo.shop.beauty.dto`；以下合同摘要同时包含路径/查询/body说明，定义record字段时按绑定来源拆分，不把actor/entity或路径键再塞一遍body。签名明确指向具体类型，不用Map<String,Object>代替公开入参。相同payload且授权由方法区分时复用同一Req，例如顾客/商家取消复用CancelAppointmentReq。VO集合和状态细节沿API/日历契约，此表不另造第二套JSON字段定义。

| 完整类名 | 类型 | 合同摘要 | 追溯 |
|---|---|---|---|
| `ai.neargo.shop.beauty.dto.AcceptTaskReq` | REQ | expectedRevision | BAPI-058 |
| `ai.neargo.shop.beauty.dto.AdjustmentCaseVO` | VO | AdjustmentCaseVO追加不覆盖 | BAPI-109 |
| `ai.neargo.shop.beauty.dto.AppendCareNoteReq` | REQ | taskNo,content,consentRef,correctsNoteNo? | BAPI-035 |
| `ai.neargo.shop.beauty.dto.AppendFollowUpEntryReq` | REQ | content,replyConsent,expectedRevision | BAPI-128 |
| `ai.neargo.shop.beauty.dto.AppendServiceEvidenceReq` | REQ | expectedRevision,kind,quantity,evidenceRefs | BAPI-101 |
| `ai.neargo.shop.beauty.dto.ApplyCapabilityReq` | REQ | storeNo,capability,evidence | BAPI-004 |
| `ai.neargo.shop.beauty.dto.AppointmentChangePreviewVO` | VO | AppointmentChangePreviewVO含报价影响；AppointmentChangePreviewVO | BAPI-042、BAPI-151 |
| `ai.neargo.shop.beauty.dto.AppointmentDetailVO` | VO | AppointmentDetailVO含版本与可用动作 | BAPI-041 |
| `ai.neargo.shop.beauty.dto.AppointmentPreviewVO` | VO | AppointmentPreviewVO无占位承诺；AppointmentPreviewVO | BAPI-038、BAPI-147 |
| `ai.neargo.shop.beauty.dto.AppointmentQuery` | QUERY | storeNo,from,to,status,personnelNo,page,size,assignmentState | BAPI-040 |
| `ai.neargo.shop.beauty.dto.AppointmentSummaryVO` | VO | PageData<AppointmentSummaryVO> | BAPI-040 |
| `ai.neargo.shop.beauty.dto.AppointmentVO` | VO | AppointmentVO与资源确认结果；AppointmentVO保留旧安排版本 | BAPI-039、BAPI-043 |
| `ai.neargo.shop.beauty.dto.ArrivalVO` | VO | appointmentNo,revision,status,checkedInAt,timing,plannedStartAt,plannedEndAt,operationNo,operationState；仅到店，无工单/资金 | BAPI-047 |
| `ai.neargo.shop.beauty.dto.AssetDisputeVO` | VO | AssetDisputeVO保留原账 | BAPI-103 |
| `ai.neargo.shop.beauty.dto.AuthorizeCareMediaReq` | REQ | taskNo,action,mediaNo?,purpose | BAPI-036 |
| `ai.neargo.shop.beauty.dto.AvailabilityQuery` | QUERY | storeNo,items,from,to,requestedPersonnelNo | BAPI-010 |
| `ai.neargo.shop.beauty.dto.AvailabilityVO` | VO | AvailabilityVO含候选组合与过期时间 | BAPI-010 |
| `ai.neargo.shop.beauty.dto.BusinessTraceVO` | VO | Task58 actual local record: String businessNo, String workOrderNo, String appointmentNo, long scopeRevision, List<ServiceSubmissionVO> submissions, WorkOrderBillVO bill, boolean complete, List<String> blockingReasons | BAPI-137 |
| `ai.neargo.shop.beauty.dto.CalendarPageQuery` | QUERY | snapshotToken,resourceCursor | BAPI-026 |
| `ai.neargo.shop.beauty.dto.CalendarPageVO` | VO | CalendarPageVO过期要求重载 | BAPI-026 |
| `ai.neargo.shop.beauty.dto.CalendarQuery` | QUERY | storeNo,from,to,view,resourceTypes,resourceCursor | BAPI-025 |
| `ai.neargo.shop.beauty.dto.CalendarSnapshotVO` | VO | CalendarSnapshotVO见工作台专篇 | BAPI-025 |
| `ai.neargo.shop.beauty.dto.CancelAppointmentReq` | REQ | expectedRevision,reason | BAPI-044、BAPI-153 |
| `ai.neargo.shop.beauty.dto.CancelTaskReq` | REQ | expectedRevision,reason | BAPI-100 |
| `ai.neargo.shop.beauty.dto.CancellationVO` | VO | CancellationVO含来源处理进度；CancellationVO | BAPI-044、BAPI-153 |
| `ai.neargo.shop.beauty.dto.CapabilityApplicationVO` | VO | CapabilityApplicationVO不直接启用 | BAPI-004 |
| `ai.neargo.shop.beauty.dto.CapabilityStatusQuery` | QUERY | storeNo,clientVersion | BAPI-135 |
| `ai.neargo.shop.beauty.dto.CapabilityStatusVO` | VO | CapabilityStatusVO缺省关闭 | BAPI-135 |
| `ai.neargo.shop.beauty.dto.CareNoteVO` | VO | CareNoteVO | BAPI-035 |
| `ai.neargo.shop.beauty.dto.CarePrivacyReq` | REQ | requestType,scope,reason | BAPI-037 |
| `ai.neargo.shop.beauty.dto.CareProfileQuery` | QUERY | purpose,taskNo? | BAPI-034 |
| `ai.neargo.shop.beauty.dto.CareProfileVO` | VO | CareProfileVO按字段授权 | BAPI-034 |
| `ai.neargo.shop.beauty.dto.ChangeAppointmentReq` | REQ | previewNo,expectedRevision,reason,consentRef?；previewNo,expectedRevision,consentRef? | BAPI-043、BAPI-152 |
| `ai.neargo.shop.beauty.dto.ChangeResourcesReq` | REQ | previewNo,expectedRevision,reason | BAPI-028 |
| `ai.neargo.shop.beauty.dto.CheckInProof` | REQ | memberCode,arrivalChallenge；真实mc1与同店90秒挑战均必填 | 内部产出分配 |
| `ai.neargo.shop.beauty.dto.CheckInReq` | REQ | appointmentNo,identityProof,expectedRevision；revision必填且>=0 | BAPI-047 |
| `ai.neargo.shop.beauty.dto.ClaimGuestReq` | REQ | String verifiedIdentity(cr1_43), claimToken(base64url43)；toString脱敏 | BAPI-032 |
| `ai.neargo.shop.beauty.dto.CloseWorkOrderReq` | REQ | previewNo,expectedRevision | BAPI-066 |
| `ai.neargo.shop.beauty.dto.ClosurePreviewVO` | VO | ClosurePreviewVO含阻断项 | BAPI-065 |
| `ai.neargo.shop.beauty.dto.CommissionEntryQuery` | QUERY | storeNo,personnelNo,period,page,size | BAPI-108 |
| `ai.neargo.shop.beauty.dto.CommissionEntryVO` | VO | PageData<CommissionEntryVO> | BAPI-108 |
| `ai.neargo.shop.beauty.dto.CommissionPolicyQuery` | QUERY | storeNo,at,page,size | BAPI-106 |
| `ai.neargo.shop.beauty.dto.CommissionPolicyVO` | VO | PageData<CommissionPolicyVO> | BAPI-106 |
| `ai.neargo.shop.beauty.dto.CommissionPolicyVersionVO` | VO | CommissionPolicyVersionVO | BAPI-107 |
| `ai.neargo.shop.beauty.dto.CompleteServiceStepReq` | REQ | expectedRevision,completedQuantity,evidenceRef? | BAPI-060 |
| `ai.neargo.shop.beauty.dto.CompleteTaskReq` | REQ | expectedRevision,bindingRevision,quantity,consentRef? | BAPI-064 |
| `ai.neargo.shop.beauty.dto.CreateAppointmentReq` | REQ | previewNo,customerRef,sourceRefs?,consentRef?,guestNo?；保留原4参构造器 | BAPI-039 |
| `ai.neargo.shop.beauty.dto.CreateMyAppointmentReq` | REQ | previewNo,sourceRefs?,consentRef? | BAPI-148 |
| `ai.neargo.shop.beauty.dto.CreateServicePrintDocumentReq` | REQ | documentType,workOrderNo/sourceRef,templateVersion | BAPI-130 |
| `ai.neargo.shop.beauty.dto.CreateTreatmentPlanReq` | REQ | customerNo,entitlementRef?,sessions | BAPI-087 |
| `ai.neargo.shop.beauty.dto.CustomerIdentityVO` | VO | kind,reference；规范身份，非凭据，C视图不返回 | BAPI-055、BAPI-155 |
| `ai.neargo.shop.beauty.dto.CustomerTimelineQuery` | QUERY | storeNo,from,to,page,size | BAPI-116 |
| `ai.neargo.shop.beauty.dto.CustomerTimelineVO` | VO | PageData<CustomerTimelineVO> | BAPI-116 |
| `ai.neargo.shop.beauty.dto.ExceptionResolutionVO` | VO | ExceptionResolutionVO不绕过原聚合校验 | BAPI-125 |
| `ai.neargo.shop.beauty.dto.ExceptionWorkitemVO` | VO | PageData<ExceptionWorkitemVO> | BAPI-124 |
| `ai.neargo.shop.beauty.dto.FollowUpEntryVO` | VO | FollowUpEntryVO | BAPI-128 |
| `ai.neargo.shop.beauty.dto.FollowUpQuery` | QUERY | storeNo,personnelNo,status,page,size | BAPI-126 |
| `ai.neargo.shop.beauty.dto.FollowUpTaskVO` | VO | PageData<FollowUpTaskVO>；FollowUpTaskVO | BAPI-126、BAPI-127 |
| `ai.neargo.shop.beauty.dto.GroupSchedulePreviewVO` | VO | GroupSchedulePreviewVO仅规划 | BAPI-029 |
| `ai.neargo.shop.beauty.dto.GroupScheduleQuery` | QUERY | participants,items,locations,requirements | BAPI-029 |
| `ai.neargo.shop.beauty.dto.GuestClaimVO` | VO | String operationNo,guestNo,userNo; Long claimRevision; String operationState,recoveryAction | BAPI-032 |
| `ai.neargo.shop.beauty.dto.GuestRegistrationVO` | VO | String operationNo(nullable), guestNo, claimToken, state; Long revision; String operationState,recoveryAction；unknown无本域operation，REQUIRES_ORIGINAL_ACTOR | BAPI-031 |
| `ai.neargo.shop.beauty.dto.HandoverTaskReq` | REQ | previewNo,expectedRevision,reason | BAPI-062 |
| `ai.neargo.shop.beauty.dto.InterruptTaskReq` | REQ | expectedRevision,action,reason,actualQuantity | BAPI-063 |
| `ai.neargo.shop.beauty.dto.JoinWaitingQueueReq` | REQ | customerRef,items | BAPI-050 |
| `ai.neargo.shop.beauty.dto.MediaAuthorizationVO` | VO | 短期MediaAuthorizationVO | BAPI-036 |
| `ai.neargo.shop.beauty.dto.MyAppointmentDetailVO` | VO | MyAppointmentDetailVO | BAPI-150 |
| `ai.neargo.shop.beauty.dto.MyAppointmentQuery` | QUERY | from,to,status,page,size | BAPI-149 |
| `ai.neargo.shop.beauty.dto.MyAppointmentVO` | VO | MyAppointmentVO；PageData<MyAppointmentVO> | BAPI-148、BAPI-149、BAPI-152 |
| `ai.neargo.shop.beauty.dto.MyArrivalVO` | VO | appointmentNo,revision,status,checkedInAt,timing,plannedStartAt,plannedEndAt,operationNo,operationState；本人到店视图 | BAPI-154 |
| `ai.neargo.shop.beauty.dto.MyCheckInReq` | REQ | appointmentNo,checkInProof,expectedRevision；严格CONSUMER/USR本人 | BAPI-154 |
| `ai.neargo.shop.beauty.dto.MyTimelineQuery` | QUERY | from,to,page,size | BAPI-158 |
| `ai.neargo.shop.beauty.dto.MyTimelineVO` | VO | PageData<MyTimelineVO> | BAPI-158 |
| `ai.neargo.shop.beauty.dto.MyTreatmentPlanQuery` | QUERY | page,size | BAPI-156 |
| `ai.neargo.shop.beauty.dto.MyTreatmentPlanVO` | VO | PageData<MyTreatmentPlanVO> | BAPI-156 |
| `ai.neargo.shop.beauty.dto.MyWorkOrderVO` | VO | workOrder,originalScopeRevision,tasks,availableActions；USER或同scope不可变claim receipt授权，不回显身份 | BAPI-155 |
| `ai.neargo.shop.beauty.dto.NoShowVO` | VO | NoShowVO不自动扣款扣次 | BAPI-045 |
| `ai.neargo.shop.beauty.dto.OpenAppointmentWorkOrderReq` | REQ | expectedRevision,assignments,startAt?；CHECKED_IN方可开单，原键完整hash | BAPI-052 |
| `ai.neargo.shop.beauty.dto.OpenWorkOrderReq` | REQ | customerRef?,storeNo,items,appointmentNo?,guestNo?,expectedRevision?,assignments,startAt?,memberNo?；无预约customerRef/guestNo/memberNo恰一且qty展开≤10；memberNo不用于预约开单，旧8/4构造和null JSON指纹保留 | BAPI-054 |
| `ai.neargo.shop.beauty.dto.OperationVO` | VO | operationNo,appointmentNo,state,action,recoveryAction,workOrderNo,failureCode; compatible five-argument constructor | BAPI-160 |
| `ai.neargo.shop.beauty.dto.ParticipationVO` | VO | ParticipationVO含历史交接区间 | BAPI-105 |
| `ai.neargo.shop.beauty.dto.PerformanceAdjustmentReq` | REQ | entryNo,sourceRevision,evidence,reason | BAPI-109 |
| `ai.neargo.shop.beauty.dto.PerformanceAllocationVO` | VO | allocationNo,sourceRef,basisCode,revisionIndex,shares,status,currency,totalMinor | 内部产出分配 |
| `ai.neargo.shop.beauty.dto.PlanFollowUpReq` | REQ | customerNo,taskNo,dueAt,assignee | BAPI-127 |
| `ai.neargo.shop.beauty.dto.PlanWorkOrderStagesReq` | REQ | stageItems,resourceRequirements,expectedRevision | BAPI-067 |
| `ai.neargo.shop.beauty.dto.PolicyVersionVO` | VO | PolicyVersionVO | BAPI-002 |
| `ai.neargo.shop.beauty.dto.PreviewAppointmentChangeReq` | REQ | expectedRevision,items,startAt,assignments | BAPI-042 |
| `ai.neargo.shop.beauty.dto.PreviewAppointmentReq` | REQ | storeNo,customerRef,items,requestedAssignments,startAt,guestNo?；保留原5参构造器 | BAPI-038 |
| `ai.neargo.shop.beauty.dto.PreviewMyAppointmentChangeReq` | REQ | expectedRevision,startAt,items,requestedPersonnelNo | BAPI-151 |
| `ai.neargo.shop.beauty.dto.PreviewMyAppointmentReq` | REQ | storeNo,items,startAt,requestedPersonnelNo | BAPI-147 |
| `ai.neargo.shop.beauty.dto.PreviewResourceChangeReq` | REQ | targetRef,newAssignments,newEndAt,expectedRevision | BAPI-027 |
| `ai.neargo.shop.beauty.dto.PreviewTaskHandoverReq` | REQ | expectedRevision,newPersonnelNo,role,effectiveAt | BAPI-061 |
| `ai.neargo.shop.beauty.dto.PreviewWorkOrderClosureReq` | REQ | expectedRevision | BAPI-065 |
| `ai.neargo.shop.beauty.dto.PrintDocumentVO` | VO | PrintDocumentVO | BAPI-130 |
| `ai.neargo.shop.beauty.dto.PrivacyRequestVO` | VO | PrivacyRequestVO | BAPI-037 |
| `ai.neargo.shop.beauty.dto.PublicAvailabilityQuery` | QUERY | storeNo,items,from,to | BAPI-146 |
| `ai.neargo.shop.beauty.dto.PublicAvailabilityVO` | VO | PublicAvailabilityVO | BAPI-146 |
| `ai.neargo.shop.beauty.dto.PublicSalesCatalogVO` | VO | versionNo,validFrom,timezone,groups（共用Group，只含visible=true） | BAPI-144 |
| `ai.neargo.shop.beauty.dto.PublicServiceVO` | VO | storeNo,skuNo,goodsNo,title,cover,priceMinor,definitionVersionNo,duration,notes,store,sales（时区/周窗口/saleableNow；非预约许可） | BAPI-145 |
| `ai.neargo.shop.beauty.dto.PublicStoreVO` | VO | storeNo,name,logo,status,openHours,address,policyVersionNo,validFrom,policy,catalog（仅可见分组） | BAPI-144 |
| `ai.neargo.shop.beauty.dto.PublishCommissionPolicyReq` | REQ | scope,basis,shares,validity,expectedRevision | BAPI-107 |
| `ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq` | REQ | skuNo,expectedRevision,duration{minutes,bufferBeforeMin,bufferAfterMin},requirements[{roleCode,resourceType,skillGoodsNo,qty,sequenceNo,offsetMin,durationMin,selectionRule}],notes?(max20,eachNonblankMax500;default[]) | BAPI-008 |
| `ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq` | REQ | expectedRevision,validFrom,policy | BAPI-002 |
| `ai.neargo.shop.beauty.dto.RaiseMyServiceDisputeReq` | REQ | taskNo,reason,evidenceRefs | BAPI-157 |
| `ai.neargo.shop.beauty.dto.ReceiveWalkInReq` | REQ | storeNo,customerRef,guestNo,items,assignments,startAt | BAPI-048 |
| `ai.neargo.shop.beauty.dto.ReceptionExceptionReq` | REQ | appointmentNo,kind,proposal,reason,expectedRevision；kind=EARLY/LATE/UNAVAILABLE/CONFLICT/IDENTITY_REQUIRED；proposal=REQUEST_RESCHEDULE/KEEP_WAITING/REQUEST_CANCEL | BAPI-053 |
| `ai.neargo.shop.beauty.dto.ReceptionExceptionVO` | VO | requestNo,appointmentNo,state,affectedTaskNos,operationNo；PENDING_REVIEW，requestNo为本域ActionLog号，不宣称外部待办已建 | BAPI-053 |
| `ai.neargo.shop.beauty.dto.ReceptionIdentityProof` | REQ | customerRef,guestNo,claimToken；USER cr1与GUEST guestNo+token互斥；toString脱敏 | 内部产出分配 |
| `ai.neargo.shop.beauty.dto.RecordCommissionSettlementReq` | REQ | entryNos,approvalRef,paymentEvidence | BAPI-110 |
| `ai.neargo.shop.beauty.dto.RecordNoShowReq` | REQ | expectedRevision,reason,evidenceRef? | BAPI-045 |
| `ai.neargo.shop.beauty.dto.RecoverSubmissionReq` | REQ | Task58 actual local record: long expectedRevision, String reason | BAPI-073 |
| `ai.neargo.shop.beauty.dto.RecoveryRequestVO` | VO | Task58 actual local record: String recoveryNo, String submissionNo, String operationNo, long acceptedRevision, String state, String recoveryAction | BAPI-073、BAPI-139 |
| `ai.neargo.shop.beauty.dto.RecoveryWorkitemQuery` | QUERY | entityNo,storeNo,kind,page,size | BAPI-138 |
| `ai.neargo.shop.beauty.dto.RecoveryWorkitemVO` | VO | PageData<RecoveryWorkitemVO> | BAPI-138 |
| `ai.neargo.shop.beauty.dto.RegisterAssetDisputeReq` | REQ | sourceRef,assetUseNo,reason,evidence | BAPI-103 |
| `ai.neargo.shop.beauty.dto.RegisterGuestReq` | REQ | String contact, String displayName, boolean consent；031 owner HMAC同键变参校验，本域不存联系资料hash | BAPI-031 |
| `ai.neargo.shop.beauty.dto.RemedyCaseVO` | VO | RemedyCaseVO审批不等于退款成功 | BAPI-102 |
| `ai.neargo.shop.beauty.dto.ReportExportVO` | VO | ReportExportVO含水位与到期下载；ReportExportVO下载二次鉴权 | BAPI-121、BAPI-122 |
| `ai.neargo.shop.beauty.dto.RequestReportExportReq` | REQ | storeNos,reportType,filters,purpose | BAPI-121 |
| `ai.neargo.shop.beauty.dto.RequestServiceRecoveryReq` | REQ | workitemNo,expectedRevision,reason,approvalRef? | BAPI-139 |
| `ai.neargo.shop.beauty.dto.RequestServiceRemedyReq` | REQ | taskNo,sourceRef,proposal,evidence | BAPI-102 |
| `ai.neargo.shop.beauty.dto.ResolveServiceExceptionReq` | REQ | expectedRevision,action,targetRef,reason | BAPI-125 |
| `ai.neargo.shop.beauty.dto.ResourceChangePreviewVO` | VO | ResourceChangePreviewVO | BAPI-027 |
| `ai.neargo.shop.beauty.dto.ResourceChangeResultVO` | VO | ResourceChangeResultVO或冲突 | BAPI-028 |
| `ai.neargo.shop.beauty.dto.SchedulingReportQuery` | QUERY | storeNo,from,to,resourceType | BAPI-119 |
| `ai.neargo.shop.beauty.dto.SchedulingReportVO` | VO | SchedulingReportVO含计划/实际口径 | BAPI-119 |
| `ai.neargo.shop.beauty.dto.ServiceDefinitionQuery` | QUERY | storeNo,skuNo,at,page=1,size=20(max100) | BAPI-007 |
| `ai.neargo.shop.beauty.dto.ServiceDefinitionVO` | VO | definitionNo,storeNo,skuNo,revision,definitionVersionNo,effectiveRevision,publishedAt,duration,requirements,snapshotHash,notes | BAPI-007 |
| `ai.neargo.shop.beauty.dto.ServiceDefinitionVersionVO` | VO | definitionNo,definitionVersionNo,revision,snapshotHash | BAPI-008 |
| `ai.neargo.shop.beauty.dto.ServiceDetailQuery` | QUERY | storeNo(required,max32) | BAPI-009、BAPI-145 |
| `ai.neargo.shop.beauty.dto.ServiceDetailVO` | VO | service,definitionNo,revision | BAPI-009 |
| `ai.neargo.shop.beauty.dto.ServiceDisputeVO` | VO | ServiceDisputeVO | BAPI-157 |
| `ai.neargo.shop.beauty.dto.ServiceEvidenceVO` | VO | ServiceEvidenceVO不可覆盖原事实 | BAPI-101 |
| `ai.neargo.shop.beauty.dto.ServiceReportQuery` | QUERY | storeNo,period,dimensions | BAPI-120 |
| `ai.neargo.shop.beauty.dto.ServiceReportVO` | VO | ServiceReportVO各事实水位 | BAPI-120 |
| `ai.neargo.shop.beauty.dto.ServiceSalesVO` | VO | catalogVersionNo,groupKey,position,timezone,salesMode,windows,saleableNow；引用共用SalesMode/Window | BAPI-145 |
| `ai.neargo.shop.beauty.dto.ServiceStepVO` | VO | ServiceStepVO | BAPI-060 |
| `ai.neargo.shop.beauty.dto.ServiceSubmissionVO` | VO | Task67 current record: String submissionNo, String workOrderNo, String action, long revision, long originalScopeRevision, long targetScopeRevision, String status, String phase, String checkoutOperationNo, CheckoutPort.CheckoutQuote quote, String orderNo, String subOrderNo, SourceObservationVO source, List<SubmissionTaskVO> tasks, String recoveryAction, List<String> blockingReasons, CounterCheckoutQuote counterQuote | BAPI-056、BAPI-072 |
| `ai.neargo.shop.beauty.dto.ServiceTaskDetailVO` | VO | ServiceTaskDetailVO含来源状态及允许动作 | BAPI-159 |
| `ai.neargo.shop.beauty.dto.ServiceTaskQuery` | QUERY | storeNo,from,to,status,personnelNo,page,size,assignmentState | BAPI-057 |
| `ai.neargo.shop.beauty.dto.ServiceTaskVO` | VO | PageData<ServiceTaskVO> | BAPI-057 |
| `ai.neargo.shop.beauty.dto.SettlementRecordVO` | VO | SettlementRecordVO不新增银行代发 | BAPI-110 |
| `ai.neargo.shop.beauty.dto.StagePlanVO` | VO | StagePlanVO仅能力开放后 | BAPI-067 |
| `ai.neargo.shop.beauty.dto.StartTaskReq` | REQ | expectedRevision,expectedBindingRevision | BAPI-059 |
| `ai.neargo.shop.beauty.dto.StorePolicyQuery` | QUERY | storeNo,at | BAPI-001 |
| `ai.neargo.shop.beauty.dto.StorePolicyVO` | VO | policyNo,storeNo,revision,policyVersionNo,effectiveRevision,validFrom,policy | BAPI-001 |
| `ai.neargo.shop.beauty.dto.SubmitServicesReq` | REQ | long expectedRevision, long expectedScopeRevision, List<TaskSelection> tasks, List<AddedItem> additions, SourceSelection source；SourceSelection支持MANUAL_PREPARE/MANUAL_EXISTING及追加CounterCustomerRef counterCustomer；旧V2输入和构造保留 | BAPI-056 |
| `ai.neargo.shop.beauty.dto.TaskActionResultVO` | VO | TaskActionResultVO；TaskActionResultVO含实际开始时间；TaskActionResultVO暂停不默认释放资源 | BAPI-058、BAPI-059、BAPI-063 |
| `ai.neargo.shop.beauty.dto.TaskCancellationVO` | VO | TaskCancellationVO源取消另查进度 | BAPI-100 |
| `ai.neargo.shop.beauty.dto.TaskCompletionVO` | VO | TaskCompletionVO分开服务和履约状态 | BAPI-064 |
| `ai.neargo.shop.beauty.dto.TaskHandoverPreviewVO` | VO | TaskHandoverPreviewVO | BAPI-061 |
| `ai.neargo.shop.beauty.dto.TaskHandoverVO` | VO | TaskHandoverVO | BAPI-062 |
| `ai.neargo.shop.beauty.dto.TreatmentPlanQuery` | QUERY | customerNo,storeNo,page,size | BAPI-086 |
| `ai.neargo.shop.beauty.dto.TreatmentPlanVO` | VO | PageData<TreatmentPlanVO>；TreatmentPlanVO计划不提前扣次 | BAPI-086、BAPI-087 |
| `ai.neargo.shop.beauty.dto.WaitingTicketActionReq` | REQ | action,expectedRevision | BAPI-051 |
| `ai.neargo.shop.beauty.dto.WaitingTicketQuery` | QUERY | storeNo,status,page,size | BAPI-049 |
| `ai.neargo.shop.beauty.dto.WaitingTicketVO` | VO | PageData<WaitingTicketVO>；WaitingTicketVO | BAPI-049、BAPI-050、BAPI-051 |
| `ai.neargo.shop.beauty.dto.WalkInVO` | VO | operationNo,operationState,appointmentNo,workOrder,startAt,recoveryAction | BAPI-048 |
| `ai.neargo.shop.beauty.dto.WorkOrderBillVO` | VO | Task58 actual local record: String workOrderNo, long scopeRevision, String localWatermark, Instant observedAt, List<SourceBillVO> sources, List<CurrencyBillVO> currencies, boolean complete, List<String> blockingReasons | BAPI-071 |
| `ai.neargo.shop.beauty.dto.WorkOrderClosureVO` | VO | WorkOrderClosureVO | BAPI-066 |
| `ai.neargo.shop.beauty.dto.WorkOrderDetailVO` | VO | workOrder,customer,originalScopeRevision,tasks,availableActions；SQL同店root/context授权，legacy只读UNKNOWN动作空 | BAPI-055 |
| `ai.neargo.shop.beauty.dto.WorkOrderParticipationVO` | VO | participationNo,personnelNo,roleCode,startedAt,endedAt；SQL完整父链真实参与 | BAPI-055、BAPI-155 |
| `ai.neargo.shop.beauty.dto.WorkOrderRequirementVO` | VO | requirementNo,roleCode,resourceType,skillGoodsNo,qty,sequenceNo,offsetMin,durationMin,selectionRule | 内部产出分配 |
| `ai.neargo.shop.beauty.dto.WorkOrderStepTemplateVO` | VO | stepCode,sequenceNo | 内部产出分配 |
| `ai.neargo.shop.beauty.dto.WorkOrderStepVO` | VO | stepNo,stepCode,sequenceNo,status,startedAt,finishedAt；SQL完整父链真实步骤 | BAPI-055、BAPI-155 |
| `ai.neargo.shop.beauty.dto.WorkOrderTaskSnapshotVO` | VO | schemaVersion,goodsNo,skuNo,definitionVersionNo,definitionHash,durationMin,bufferBeforeMin,bufferAfterMin,requirements,stepsTemplate,notes；原不可变规则，列表防御复制 | 内部产出分配 |
| `ai.neargo.shop.beauty.dto.WorkOrderTaskVO` | VO | taskNo,appointmentItemNo,scopeRevision,skuNo,definitionVersionNo,qty,doneQty,voidedQty,uom,status,bindingRevision,assignmentState,sourceState,canStart,plannedStartAt,plannedEndAt,actualStartedAt,assignments,taskSnapshot,steps,participations,availableActions；初始PENDING/0/UNBOUND/false，执行子行只读真实持久事实 | BAPI-055、BAPI-155 |
| `ai.neargo.shop.beauty.dto.WorkOrderVO` | VO | workOrderNo,appointmentNo,scopeRevision,status,submissionNo,sourceState,operationNo,operationState,taskNos,plannedStartAt；初始OPEN/1/UNBOUND，UNKNOWN返回PENDING无工单号 | BAPI-052、BAPI-054 |
| `ai.neargo.shop.beauty.dto.WorkbenchExceptionQuery` | QUERY | storeNo,from,to,kind,page,size | BAPI-124 |
| `ai.neargo.shop.beauty.dto.WorkbenchSummaryQuery` | QUERY | storeNo,businessDate | BAPI-117 |
| `ai.neargo.shop.beauty.dto.WorkbenchSummaryVO` | VO | WorkbenchSummaryVO含asOf独立水位 | BAPI-117 |

## 七、必要辅助类与方法

### ai.neargo.shop.beauty.policy.AppointmentPolicy

模块：beauty-core；本域规则辅助，资源匹配与锁由共用所有者执行。

```java
void requireBookable(BtyStorePolicyVersion policy, LocalDateTime startAt, LocalDateTime now);
void requireChangeAllowed(BtyAppointment appointment, boolean hasStartedTask);
```

### ai.neargo.shop.beauty.statemachine.ServiceTaskStateMachine

模块：beauty-core；状态迁移不替代来源/资源/数量验证。

```java
void requireTransition(String currentStatus, String action);
boolean isTerminal(String status);
```

### ai.neargo.shop.beauty.assembler.CalendarSnapshotAssembler

模块：beauty-core；复用同一传输读模型，资源提供者映射为本域规范底图后装配；不新造多个等义CalendarDTO。

```java
CalendarSnapshotVO assemble(CalendarSnapshotVO resourceSnapshot, List<AppointmentSummaryVO> appointments, List<ServiceTaskVO> tasks);
```

### ai.neargo.shop.beauty.assembler.ServicePrintDocumentAssembler

模块：beauty-core；只构造脱敏内容，投递与补打状态归共享打印。

```java
PrintDocumentVO assemble(WorkOrderDetailVO workOrder, WorkOrderBillVO bill, String templateVersion);
```

### ai.neargo.shop.beauty.job.ServiceSubmissionRecoveryJob

模块：beauty-core；implements ai.neargo.job.api.JobHandler；短事务claimDue+nextRetryAt推进，再调原号recoverPending；业务失败按JobResult.failed，不用HTTP异常代替。

```java
String name();
JobResult run(JobInvocation invocation);
```

### ai.neargo.shop.beauty.event.ServiceSourceResultConsumer

模块：beauty-core；采用真实Core/Pay事件适配；验签/去重与权威重查后调用Service，不凭消息载荷改支付成功或服务完工。

```java
void onSourceResult(String eventNo, String entityNo, String storeNo, String submissionNo, String sourceOperationNo);
```

### ai.neargo.shop.beauty.event.ResourceImpactConsumer

模块：beauty-core；资源所有者事实触发本域协调/待办；不直接释放共用容量桶。

```java
void onResourceImpact(String eventNo, String entityNo, String storeNo, String resourceOperationNo);
```

## 八、装配类边界

| 完整类名 | 模块 | 方法/职责 |
|---|---|---|
| ai.neargo.shop.beauty.config.BeautyAutoConfiguration | beauty-core | 条件装配本域Service、Mapper、Job/事件；只按真实Bean签名创建@Bean工厂，无业务API方法 |
| ai.neargo.shop.beauty.config.BeautyWebAutoConfiguration | beauty-web | 独立Web条件；不得依赖core内配置类/条件注解 |
| ai.neargo.shop.beautyclient.BeautyClientConfiguration | beauty-web | standalone选择RemoteXxxService，embedded选本地同接口；不得双提供方 |
| ai.neargo.shop.beauty.server.BeautyApplication | beauty-server | `public static void main(String[] args)`；仅受控扫描启动包与显式导入业务/内部安全 |
| ai.neargo.shop.beauty.server.config.BeautyInternalSecurityConfiguration | beauty-server | 验证内部身份、重放防护、委托动作/范围；具体工厂沿平台安全栈，不复制新用户登录体系 |

DTO类型引用已逐项核对；字段完整定义沿API与数据库源，内部RPC、配置工厂尚无已发布协议。配置Properties、PersistenceConfiguration等按项目结构和首次接线实际签名增补；不为“类清单齐全”虚构大量@Bean方法。完整DTO字段定稿、内部RPC协议及Java编译/装配/并发验证是实施阶段工作，本轮只交付可审阅的类/方法设计。

## BM03-a1增量实施清单（2026-10-21）

原目标JSON/49任务保持基线；本节是私有V2的内部存储增量，未注册来源provider、Service或预约Controller。完整状态见[主TDD§2.15/§26](../TDD-美业系统-后端开发计划.md)。

| 类 | 实际职责 |
|---|---|
| beauty.entity.BtyAppointmentResourceIntent | 原始计划LONGTEXT、命令/来源修订/摘要、决定与对账阶段；继承BaseEntity |
| beauty.entity.BtyAppointmentResourceFence | 预约永久根、单一活动意图指针及版本；继承BaseEntity |
| BeautyMappers.AppointmentResourceIntentMapper | scoped command锁读、authorize/reconcile CAS；调用参数不能证明权限或终态真实性 |
| BeautyMappers.AppointmentResourceFenceMapper | scoped root锁读、acquire/releaseReconciled CAS；不按许可TTL释放 |
| BeautyMappers.AppointmentMapper | changeSchedule/changeStatus/markArrived统一检查活动fence，含确认/取消 |

以下方法均在原beauty-core包；每个写事务先锁预约根→永久fence→intent，Mapper仅持久化。

```java
BtyAppointmentResourceFence selectScopedForUpdate(String entityNo, String storeNo, String appointmentNo);
int acquire(String entityNo, String storeNo, String appointmentNo, String intentNo,
        long expectedFenceVersion, String actorNo, LocalDateTime updatedAt);
int releaseReconciled(String entityNo, String storeNo, String appointmentNo, String intentNo,
        long expectedFenceVersion, String actorNo, LocalDateTime updatedAt);
BtyAppointmentResourceIntent selectByCommandForUpdate(String entityNo, String storeNo, String commandNo);
int authorize(String entityNo, String storeNo, String intentNo, long expectedVersion, String decisionNo,
        LocalDateTime issuedAt, LocalDateTime validUntil, String actorNo, LocalDateTime updatedAt);
int reconcile(String entityNo, String storeNo, String intentNo, long expectedVersion,
        int terminalCode, String terminalHash, String actorNo, LocalDateTime updatedAt);
```

a2仍须实现正式权限、持久预约生成计划、同Binding幂等及可信资源终态核实；本批不增加可调用预约HTTP。

## BM03-a2-1共用核验增量（2026-10-21）

本节为已编码的所有者只读前置，不改变原49任务或160编号；不是完整美业来源授权、预约或终态解冻。

| 模块 / 完整类 | 职责与实际方法 |
|---|---|
| shop-base / ai.neargo.shop.spi.user.MerchantOperatorAccessPort | boolean allowed(Query query)；显式租户/主体/店/操作者realm+subjectKind/权限 |
| shop-base / ai.neargo.shop.spi.resource.ResourceCommandFactsPort | Lookup lookup(ResourceSourceAuthorityPort.Request request)；完整Binding，原命令状态/结果/摘要；不以当前持有头替代历史结果 |
| shop-base / ai.neargo.shop.spi.resource.ResourceRecoveryInternalApi | Boolean operatorAccess(Query query)；Lookup command(Request request)；PLATFORM内部协议 |
| shop-merchant / ai.neargo.shop.merchant.port.MerchantOperatorAccessPortImpl | 实际有效成员、店授权、角色权限精确或裸*匹配；MCH/USR隔离，歧义拒绝，无静态角色兜底 |
| shop-merchant / ai.neargo.shop.merchant.port.ResourceCommandFactsPortImpl | 实际原command/owner父链，完整Binding比对，REJECTED/SUCCEEDED摘要及ACCEPTED非终态 |
| shop-merchant / ai.neargo.shop.merchant.mapper.MerchantMappers.OperatorAccessMapper | List<OperatorAccessRow> selectAccess(Query query)；最多2行用于拒绝歧义 |
| shop-merchant / ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceCommandMapper | ResourceCommandFactRow selectOriginalFact(ResourceCommandScopeRow scope, String commandNo)；只读投影 |
| shop-app / ai.neargo.shop.portal.internal.InternalResourceRecoveryController | ResponseEntity<?> operatorAccess(Query query,String given) / command(Request request,String given)；先机器令牌、保留错误HTTP状态 |
| beauty-channel / ai.neargo.shop.beauty.port.RemoteMerchantOperatorAccessPort | boolean allowed(Query query)；真实PLATFORM链路，不把失败当false |
| beauty-channel / ai.neargo.shop.beauty.port.RemoteResourceCommandFactsPort | Lookup lookup(Request request)；完整返回Binding比对，不把失败当缺失 |
| beauty-channel / ai.neargo.shop.beauty.config.BeautyMerchantClientConfiguration | 条件缺失时接远程两个Port，embedded仍使用本地owner |

内部Lookup必须显式command字段，允许显式null；缺字段/200错误信封拒绝，未知owner运行异常503；只读OperatorAccessMapper显式@Mapper由宿主实际注解扫描注册。

接口、owner及远程适配均在事务外读取；事实只说明读取时状态，不是来源许可。未新增可授权provider/预约Controller，待a2-2基于真实持久业务计划与来源根锁继续实现。


## Task52 BM03端点与独立双向机器协议增量（2026-10-21）

以下仅为已编码/协议测试的增量；原160项设计编号保留。Task53负责实际JAR/进程部署验收，BM04/06未打开。

| API / 方法 | Controller / 实际Service | 状态 |
|---|---|---|
| BAPI-010 / `queryAvailability` | ai.neargo.shop.beauty.api.biz.BizAvailabilityController<br>ai.neargo.shop.beauty.service.AppointmentAvailabilityService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-146 / `queryPublicAvailability` | ai.neargo.shop.beauty.api.mp.MpAvailabilityController<br>ai.neargo.shop.beauty.service.AppointmentAvailabilityService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-038 / `previewAppointment` | ai.neargo.shop.beauty.api.biz.BizAppointmentController<br>ai.neargo.shop.beauty.service.AppointmentService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-039 / `createAppointment` | ai.neargo.shop.beauty.api.biz.BizAppointmentController<br>ai.neargo.shop.beauty.service.AppointmentService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-040 / `listAppointments` | ai.neargo.shop.beauty.api.biz.BizAppointmentController<br>ai.neargo.shop.beauty.service.AppointmentService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-041 / `getAppointment` | ai.neargo.shop.beauty.api.biz.BizAppointmentController<br>ai.neargo.shop.beauty.service.AppointmentService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-042 / `previewAppointmentChange` | ai.neargo.shop.beauty.api.biz.BizAppointmentController<br>ai.neargo.shop.beauty.service.AppointmentService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-043 / `changeAppointment` | ai.neargo.shop.beauty.api.biz.BizAppointmentController<br>ai.neargo.shop.beauty.service.AppointmentService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-044 / `cancelAppointment` | ai.neargo.shop.beauty.api.biz.BizAppointmentController<br>ai.neargo.shop.beauty.service.AppointmentService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-045 / `recordNoShow` | ai.neargo.shop.beauty.api.biz.BizAppointmentController<br>ai.neargo.shop.beauty.service.AppointmentService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-147 / `previewMyAppointment` | ai.neargo.shop.beauty.api.mp.MpAppointmentController<br>ai.neargo.shop.beauty.service.AppointmentService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-148 / `createMyAppointment` | ai.neargo.shop.beauty.api.mp.MpAppointmentController<br>ai.neargo.shop.beauty.service.AppointmentService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-149 / `listMyAppointments` | ai.neargo.shop.beauty.api.mp.MpAppointmentController<br>ai.neargo.shop.beauty.service.AppointmentService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-150 / `getMyAppointment` | ai.neargo.shop.beauty.api.mp.MpAppointmentController<br>ai.neargo.shop.beauty.service.AppointmentService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-151 / `previewMyAppointmentChange` | ai.neargo.shop.beauty.api.mp.MpAppointmentController<br>ai.neargo.shop.beauty.service.AppointmentService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-152 / `changeMyAppointment` | ai.neargo.shop.beauty.api.mp.MpAppointmentController<br>ai.neargo.shop.beauty.service.AppointmentService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| BAPI-153 / `cancelMyAppointment` | ai.neargo.shop.beauty.api.mp.MpAppointmentController<br>ai.neargo.shop.beauty.service.AppointmentService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |
| `POST /biz/x/beauty/operations/{operationNo}/recoveries`<br>`OperationVO recoverOperation(String entityNo,String currentStoreNo,String actorNo,String operationNo)` | ai.neargo.shop.beauty.api.biz.BizServiceOperationController<br>ai.neargo.shop.beauty.service.ServiceOperationRecoveryService | IMPLEMENTED_TASK52_PROTOCOL_TESTED_DEPLOYMENT_PENDING |

| 内部方法 / 路径 | 所有者 | 请求 | 返回 |
|---|---|---|---|
| `POST /internal/beauty/booking/queryAvailability` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.QueryAvailability | AvailabilityVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/queryPublicAvailability` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.QueryPublicAvailability | PublicAvailabilityVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/previewAppointment` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.PreviewAppointment | AppointmentPreviewVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/createAppointment` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.CreateAppointment | AppointmentVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/listAppointments` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.ListAppointments | PageData<AppointmentSummaryVO>；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/getAppointment` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.GetAppointment | AppointmentDetailVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/previewAppointmentChange` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.PreviewAppointmentChange | AppointmentChangePreviewVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/changeAppointment` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.ChangeAppointment | AppointmentVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/cancelAppointment` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.CancelAppointment | CancellationVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/recordNoShow` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.RecordNoShow | NoShowVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/previewMyAppointment` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.PreviewMyAppointment | AppointmentPreviewVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/createMyAppointment` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.CreateMyAppointment | MyAppointmentVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/listMyAppointments` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.ListMyAppointments | PageData<MyAppointmentVO>；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/getMyAppointment` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.GetMyAppointment | MyAppointmentDetailVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/previewMyAppointmentChange` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.PreviewMyAppointmentChange | AppointmentChangePreviewVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/changeMyAppointment` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.ChangeMyAppointment | MyAppointmentVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/cancelMyAppointment` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.CancelMyAppointment | CancellationVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/getOperation` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.GetOperation | OperationVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/recoverOperation` | beauty-server / InternalBeautyBookingController | BeautyBookingInternalApi.RecoverOperation | OperationVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/appointment-source/freeze` | beauty-server / 实际AppointmentSourceAuthorityPortImpl | BeautySourceInternalApi.Delegation(identity,request) | Decision；UNKNOWN非许可；完整Binding比对 |
| `POST /internal/beauty/appointment-source/query` | beauty-server / 实际AppointmentSourceAuthorityPortImpl | BeautySourceInternalApi.Delegation(identity,request) | Decision；UNKNOWN非许可；完整Binding比对 |
| `POST /internal/resource-booking/accept` | PLATFORM / InternalResourceBookingController → 共用真实resource owner | Acceptance | Receipt；错误不作为空事实 |
| `POST /internal/resource-booking/query` | PLATFORM / InternalResourceBookingController → 共用真实resource owner | Command | Lookup(Boolean found,Receipt)；错误不作为空事实 |
| `POST /internal/resource-booking/execute` | PLATFORM / InternalResourceBookingController → 共用真实resource owner | Command | Receipt；错误不作为空事实 |
| `POST /internal/resource-booking/hold` | PLATFORM / InternalResourceBookingController → 共用真实resource owner | HoldQuery | BoundHold；错误不作为空事实 |
| `POST /internal/resource-booking/reconcile` | PLATFORM / InternalResourceBookingController → 共用真实resource owner | Binding | Lookup(Boolean found,Receipt)；错误不作为空事实 |
| `POST /internal/resource-booking/reconcile-hold` | PLATFORM / InternalResourceBookingController → 共用真实resource owner | BoundHoldQuery | BoundHold；错误不作为空事实 |
| `POST /internal/resource-booking/candidates` | PLATFORM / InternalResourceBookingController → 共用真实resource owner | ResourceCandidatesPort.Query | List<ResourceFact>；错误不作为空事实 |
| `POST /internal/resource-booking/capacity` | PLATFORM / InternalResourceBookingController → 共用真实resource owner | ResourceCandidatesPort.CapacityQuery（独立factAt、Replacement完整Binding+Target） | List<Capacity>；错误不作为空事实 |
| `POST /internal/customer-identity/resolve-reference` | PLATFORM / InternalCustomerIdentityController → MemberCustomerIdentityPortImpl | CustomerIdentityInternalApi.Request(identity,context,query) | Resolution含完整身份/上下文/query与ResolvedCustomer；远端比对并验证userNo |
| `POST /internal/guest-registration/register` | PLATFORM / InternalGuestRegistrationController → MemberGuestRegistrationPortImpl (shop-core/member) | GuestRegistrationInternalApi.Register(scope,RegisterCommand(contact?,displayName?,consent,idempotencyKey)) | Registered(request,Registration(guestNo,claimToken,state,revision))；完整请求回显；服务端随机原凭证，加密持久原键重放 |
| `POST /internal/guest-registration/get` | PLATFORM / InternalGuestRegistrationController → MemberGuestRegistrationPortImpl (shop-core/member) | GuestRegistrationInternalApi.Get(scope,guestNo) | Found(request,GuestFact(guestNo,state,userNo?,revision))；完整请求回显，无contact/密文 |
| `POST /internal/guest-registration/verify` | PLATFORM / InternalGuestRegistrationController → MemberGuestRegistrationPortImpl (shop-core/member) | GuestRegistrationInternalApi.Verify(scope,guestNo,claimToken) | Verified(request,GuestFact)；完整请求回显，不提供支付授权 |
| `POST /internal/guest-registration/claim` | PLATFORM / InternalGuestRegistrationController → MemberGuestRegistrationPortImpl (shop-core/member) | GuestRegistrationInternalApi.ClaimRequest(scope,guestNo,ClaimCommand(verifiedIdentity,claimToken,idempotencyKey)) | Claimed(request,Claim(guestNo,userNo,revision))；完整请求回显，不改绑或创建账号/订单 |
| `POST /internal/customer-identity/verify-consumer-proof` | PLATFORM / InternalCustomerIdentityController → MemberCustomerIdentityPortImpl | CustomerIdentityInternalApi.ProofRequest(identity,EntitlementContext,memberCode) | ProofResolution(request,ResolvedCustomer)；完整身份/上下文/原memberCode请求回显，必须解析为当前用户；过期/伪造mc1为400 |
| `POST /internal/customer-identity/resolve-claim-reference` | PLATFORM / InternalCustomerIdentityController → MemberCustomerIdentityPortImpl | CustomerIdentityInternalApi.ClaimReferenceRequest(identity,EntitlementContext,ReferenceQuery) | ClaimReferenceResolution(request,ResolvedCustomer)；全请求回显核对，不是资金授权 |
| `POST /internal/beauty/reception/registerGuest` | beauty-server / InternalBeautyGuestController → GuestRegistrationService | InternalCall<BeautyReceptionInternalApi.RegisterGuest> | InternalReply<GuestRegistrationVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/claimGuest` | beauty-server / InternalBeautyGuestController → GuestRegistrationService | InternalCall<BeautyReceptionInternalApi.ClaimGuest> | InternalReply<GuestClaimVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/checkIn` | beauty-server / InternalBeautyReceptionController → ReceptionService | InternalCall<BeautyReceptionInternalApi.CheckIn> | InternalReply<ArrivalVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/receiveWalkIn` | beauty-server / InternalBeautyReceptionController → ReceptionService | InternalCall<BeautyReceptionInternalApi.ReceiveWalkIn> | InternalReply<WalkInVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/coordinateReceptionException` | beauty-server / InternalBeautyReceptionController → ReceptionService | InternalCall<BeautyReceptionInternalApi.CoordinateException> | InternalReply<ReceptionExceptionVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/issueArrivalChallenge` | beauty-server / InternalBeautyReceptionController → ReceptionService | InternalCall<BeautyReceptionInternalApi.IssueArrivalChallenge> | InternalReply<ArrivalChallengeVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/openWorkOrderFromAppointment` | beauty-server / InternalBeautyWorkOrderController → AppointmentService | InternalCall<BeautyReceptionInternalApi.OpenAppointment> | InternalReply<WorkOrderVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/openWorkOrder` | beauty-server / InternalBeautyWorkOrderController → WorkOrderService | InternalCall<BeautyReceptionInternalApi.OpenWorkOrder> | InternalReply<WorkOrderVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/getWorkOrder` | beauty-server / InternalBeautyWorkOrderController → WorkOrderService | InternalCall<BeautyReceptionInternalApi.WorkOrderRef> | InternalReply<WorkOrderDetailVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/getMyWorkOrder` | beauty-server / InternalBeautyWorkOrderController → WorkOrderService | InternalCall<BeautyReceptionInternalApi.WorkOrderRef> | InternalReply<MyWorkOrderVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/checkInMyAppointment` | beauty-server / InternalBeautyReceptionController → ReceptionService | InternalCall<BeautyReceptionInternalApi.CheckInMine> | InternalReply<MyArrivalVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/submitServices` | beauty-server / InternalBeautyWorkOrderController → WorkOrderService | InternalCall<BeautyReceptionInternalApi.SubmitServices> | InternalReply<ServiceSubmissionVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/getBill` | beauty-server / InternalBeautyWorkOrderController → WorkOrderService | InternalCall<BeautyReceptionInternalApi.WorkOrderRef> | InternalReply<WorkOrderBillVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/getSubmission` | beauty-server / InternalBeautySubmissionController → ServiceSubmissionService | InternalCall<BeautyReceptionInternalApi.SubmissionRef> | InternalReply<ServiceSubmissionVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/requestSubmissionRecovery` | beauty-server / InternalBeautySubmissionController → ServiceSubmissionService | InternalCall<BeautyReceptionInternalApi.RequestSubmissionRecovery> | InternalReply<RecoveryRequestVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/recoverPending` | beauty-server / InternalBeautySubmissionController → ServiceSubmissionService | InternalCall<BeautyReceptionInternalApi.SubmissionRef> | InternalReply<ServiceSubmissionVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/getTrace` | beauty-server / InternalBeautyTraceController → WorkOrderBillService | InternalCall<BeautyReceptionInternalApi.BusinessRef> | InternalReply<BusinessTraceVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/getOperation` | beauty-server / InternalBeautyOperationController → ServiceOperationQueryService | InternalCall<BeautyReceptionInternalApi.OperationRef> | InternalReply<OperationVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/beauty/reception/recoverOperation` | beauty-server / InternalBeautyOperationController → ServiceOperationRecoveryService | InternalCall<BeautyReceptionInternalApi.OperationRef> | InternalReply<OperationVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| `POST /internal/trade/checkout/bound/prepare` | HOST / InternalCheckoutController → actual CheckoutPortImpl | InternalCall<CheckoutInternalApi.BoundPrepare> | InternalReply<CheckoutPort.CheckoutQuote>; full binding digest and strict typed result |
| `POST /internal/trade/checkout/bound/readQuote` | HOST / InternalCheckoutController → actual CheckoutPortImpl | InternalCall<CheckoutInternalApi.BoundOperation> | InternalReply<CheckoutPort.CheckoutQuote>; full binding digest and strict typed result |
| `POST /internal/trade/checkout/bound/findOperation` | HOST / InternalCheckoutController → actual CheckoutPortImpl | InternalCall<CheckoutInternalApi.BoundOperation> | InternalReply<CheckoutPort.CheckoutResult>; full binding digest and strict typed result |
| `POST /internal/trade/checkout/bound/source` | HOST / InternalCheckoutController → actual CheckoutPortImpl | InternalCall<CheckoutInternalApi.BoundSource> | InternalReply<CheckoutPort.CheckoutSourceFacts>; full binding digest and strict typed result |

| 部署形态 | 唯一来源provider | owner / 装配 |
|---|---|---|
| embedded HOST | beauty-core / ai.neargo.shop.beauty.port.AppointmentSourceAuthorityPortImpl | 本地ResourceBookingPortImpl/ResourceCandidatesPortImpl/MemberCustomerIdentityPortImpl/MemberGuestRegistrationPortImpl<br>唯一local source；channel自动配置因core class存在不注册remote source |
| standalone HOST | beauty-channel / ai.neargo.shop.beauty.port.RemoteAppointmentSourceAuthorityPort | 平台本地真实共用owner；无beauty-core<br>BeautyChannelAutoConfiguration；真实反向BEAUTY目标；四个typed Remote*Service |
| standalone SERVER | beauty-core / ai.neargo.shop.beauty.port.AppointmentSourceAuthorityPortImpl | BeautyMerchantClientConfiguration / RemoteResourceBookingPort,RemoteResourceCandidatesPort,RemoteCustomerIdentityPort,RemoteGuestRegistrationPort<br>core存在；HOST remote source不注册；api默认profile使恢复Job接线 |
| disabled / ops / no API | disabled无新增source provider | 不改变共用owner<br>public controllers仅api+enabled；恢复Job仅api；独立SERVER默认api且没有beauty-web |

机器身份为shop-base的DelegatedIdentity与shop-base-auth的DelegatedIdentityContext；只在令牌校验后以consumer/merchantByUser/merchant恢复精确命名空间，finally恢复先前上下文。无存储用户名伪造身份。
BOOKING VIEW或MANAGE可读、MANAGE写；C私有入口显式CONSUMER/USR；公开availability匿名。search incomplete独立ErrorCode80104（三语），内部422保留语义，availability返回complete=false。
所有远程请求在事务外；Lookup显式found，失败不作为absence；source完整Binding和owner完整返回身份校验，capacity透传独立factAt及完整Replacement。CustomerIdentity只读真实成员所有者，不增加金融许可。


## Task55A身份/认领/到店码增量

Task55A+B+C+D local services; D independent review pending. Original 160 identifiers unchanged. HTTP/remote wiring, execution, trade/payment/source submission and deployment remain Tasks56-60.

```java
GuestRegistrationVO GuestRegistrationService.registerGuest(String entityNo, String storeNo, String actorNo, RegisterGuestReq req, String idempotencyKey)
GuestClaimVO GuestRegistrationService.claimGuest(String entityNo, String storeNo, String actorNo, String guestNo, ClaimGuestReq req, String idempotencyKey)
ArrivalChallengeVO ReceptionService.issueArrivalChallenge(String entityNo, String storeNo, String actorNo, String idempotencyKey)
GuestClaimVO GuestRegistrationServiceImpl.recoverClaim(String entityNo, String storeNo, String actorNo, String operationNo) [internal]
ArrivalVO ReceptionService.checkIn(String entityNo,String currentStoreNo,String actorNo,CheckInReq req,String idempotencyKey)
MyArrivalVO ReceptionService.checkInMyAppointment(String userNo,MyCheckInReq req,String idempotencyKey)
ReceptionExceptionVO ReceptionService.coordinateReceptionException(String entityNo,String currentStoreNo,String actorNo,ReceptionExceptionReq req,String idempotencyKey)
BtyAppointmentOperation ReceptionCoordinator.reassign(String entity,String store,String actor,String appointment,long revision,List<AppointmentAssignmentReq> assignments,OffsetDateTime startAt,String key) [internal; null means verified unchanged arrangement]
HoldEvidence AppointmentCoordinator.currentArrangementEvidence(BtyAppointment root) [internal]
PreparedWorkOrderScope WorkOrderPreparation.fromAppointment(BtyAppointment root); void verifyLocked(PreparedWorkOrderScope scope) [internal]
Matched AppointmentMatching.matchFrozen(Scope scope,FrozenAppointmentScope frozen,List<AppointmentAssignmentReq> assignments,OffsetDateTime startAt,Replacement replacement) [internal]
WorkOrderVO AppointmentService.openWorkOrderFromAppointment(String entityNo,String currentStoreNo,String actorNo,String appointmentNo,OpenAppointmentWorkOrderReq req,String idempotencyKey) [local override; remote fail-closed default until Task59]
WorkOrderVO WorkOrderService.openWorkOrder(String entityNo,String currentStoreNo,String actorNo,OpenWorkOrderReq req,String idempotencyKey)
WorkOrderDetailVO WorkOrderService.getWorkOrder(String entityNo,String currentStoreNo,String actorNo,String workOrderNo)
MyWorkOrderVO WorkOrderService.getMyWorkOrder(String userNo,String workOrderNo)
WorkOrderDetailVO WorkOrderQuery.detail(BtyWorkOrder authorizedRoot) [ServiceImpl current access then direct WorkOrderMapper SQL root authorization; projection only]
PreparedWorkOrderScope WorkOrderPreparation.unassigned(String entity,String store,VerifiedIdentity identity,List<AppointmentItemReq> items) [real directory/P1 definition, no hold]
BtyAppointmentOperation ReceptionReassignment.reassign(String entity,String store,String actor,String appointment,long revision,List<AppointmentAssignmentReq> assignments,OffsetDateTime startAt,String key,String receptionOperationNo) [single replacement implementation; old overload/delegate retained]
BtyAppointmentOperation AppointmentPreparation.prepareReception(BtyAppointmentPreparation preview,BtyAppointment target,long expected,String key,String hash,PreparedWorkOrderScope scope,WorkOrderPreparation frozen,String requestEvidence,String receptionOperationNo) [links original outer operation in existing root/fence/current short TX]
BtyWorkOrderContext WorkOrderContextMapper.selectScoped(String entityNo,String storeNo,String workOrderNo) [MAIN+active+complete scope]
BtyReceptionOperation ReceptionOperationMapper.selectScopedForUpdate(String entityNo,String storeNo,String operationNo) [permanent original reception row lock]
BtyReceptionOperation ReceptionOperationMapper.selectOpenedForWorkOrder(String entityNo,String storeNo,String workOrderNo,String operationNo) [SQL exact original context/root/canonical identity + OPEN_WORK_ORDER+BAPI-052/054 or RECEIVE_WALK_IN+BAPI-048, SUCCEEDED; no guessed success]
WorkOrderVO WorkOrderCoordinator.completedOrThrow(String entity,String store,String actor,String endpoint,String key,String hash,BtyReceptionOperation original,BizException stale) [private; CONFLICT only, exact original namespace/hash/operation SUCCEEDED]
boolean WorkOrderCoordinator.sameOperation(BtyReceptionOperation expected,BtyReceptionOperation actual) [private; full tenant/entity/store/actor realm-kind/endpoint/key/hash/operation binding]
PreparedWorkOrderScope WorkOrderCoordinator.preparedForIdentity(BtyAppointment root,PreparedWorkOrderScope prepared,VerifiedIdentity canonical) [private; no historical appointment rewrite, USER bridge only same-scope immutable receipt]
WalkInVO ReceptionService.receiveWalkIn(String entityNo,String currentStoreNo,String actorNo,ReceiveWalkInReq req,String idempotencyKey)
WalkInVO ReceptionCoordinator.recoverWalkIn(String entity,String store,String actor,String operationNo) [original verified identity and namespace; current employee]
Matched AppointmentMatching.matchWalkIn(Scope scope,List<AppointmentItemReq> items,List<AppointmentAssignmentReq> assignments,OffsetDateTime requestedStartAt,Instant observedNow)
BtyAppointmentOperation AppointmentPreparation.prepareReception(String entity,String store,String actor,String receptionOperationNo) [atomic CREATE preview/root/fence/current/operation/outer link]
WorkOrderVO WorkOrderCoordinator.openReception(String entity,String store,String actor,String receptionOperationNo) [single C writer, typed WalkInVO result in same transaction]
WorkOrderVO WorkOrderCoordinator.recover(String entity,String store,String actor,String receptionOperationNo)
ResourceBookingPort.Hold AppointmentCoordinator.releasedHold(BtyAppointmentOperation release,String expectedHoldNo) [original release receipt/intent/current hold evidence]
void WorkOrderPreparation.verifyCreateLocked(AppointmentMatching.Matched match) [active local heads must still select original frozen versions before CREATE command commits]
int ReceptionRecovery.scan() [due qualified RESOURCE_PENDING only, LIMIT50; fresh per-item lease30s and completion retry30s/failure60s; reconcile only]
List<BtyReceptionOperation> ReceptionOperationMapper.selectResourceRecoverable(LocalDateTime now); int claimResource(String entityNo,String storeNo,String operationNo,long version,LocalDateTime now,LocalDateTime until)
JobResult ReceptionRecoveryJob.run(JobInvocation invocation) [existing JobHandler, api profile only]
```

ArrivalChallengeVO(challengeNo,storeNo,code,issuedAt,expiresAt)：SecureRandom32字节43字符；UTC毫秒；固定90秒原键不续期，禁止进入日志/operation/outbox。
ReceptionAccess/Identity/Preparation/CustomerOwnership：当前CUSTOMER核验、真实GuestRegistrationPort/CustomerIdentityPort、immutable guest/USER intent、同tenant/entity/store receipt归属；sql先过滤再page/count。
BeautyMappers新增ReceptionOperationMapper.selectScopedForUpdate(entityNo,storeNo,operationNo)及WorkOrderContextMapper/GuestClaimReceiptMapper/ArrivalChallengeMapper继承BaseMapper；entity继承BaseEntity。
REGISTER直接owner派生完整namespace key；本域无登记operation或低熵联系资料摘要。CLAIM本域成功时receipt+operation+ActionLog+BeautyReceptionStateChanged/outbox同事务。
ResultUnknown只表示通信结果未知；Remote明确HTTP403/503及NOT_CONFIGURED保持失败；合法owner CLAIMED仅在先存规范USER匹配后允许当前员工补receipt，不冒充原actor。
真实member/mc1/cr1消费bridge位于shop-app；core owner/resource边界用controlled fixture，非真实资源网络验收。
Task55B：currentArrangementEvidence按current.arrangementRevision+成功operation.previewNo取原CONFIRM/REPLACE，后续失败receipt不可覆盖；sourceRevision与根revision独立。
047/154：CONFIRMED→CHECKED_IN；EARLY/ON_TIME/LATE；end到期明确BEAUTY_ARRIVAL_REQUIRES_RESCHEDULE。全namespace幂等，成功重放仍核当前权限；无hold释放/订单核销/工单或任务。
内部RECEPTION_CHANGE仅CHECKED_IN且无工单/关联task；原不可变定义/requirements/policy/hash/缓冲/数量核验，当前资格/排班/容量；同一个Slot/allocate/occupied有界算法；完整展开itemIndex，新itemNo与old→new日志映射；新时段未来5分钟网格且before buffer未来。
永久fence acquire/intent authorize SQL仅在current指向PENDING RECEPTION_CHANGE、REPLACE且无工单/task时允许CHECKED_IN。普通CHANGE不扩状态。UNKNOWN原号/current/fence保留，拒绝保旧，成功保留checkedInAt/CHECKED_IN。
ReceptionPreparation泛化完整endpoint/actor命名空间find/begin，typed ArrivalPayload/ExceptionPayload，无原proof持久化；A claim路径不变。
053仅append-only ActionLog+原requestNo+实际受影响task及共用安全Outbox，同事务原键重放，无公共审批owner假成功。
V1–V6冻结不变；55B修复追加V7将assignment资源组合唯一索引改同列普通索引，保留每unit安排号唯一键。就绪/严格manifest为7；其余Mapper守卫SQL、Service/DTO/Bean DAG、host真实member消费及controlled资源fixture边界不变。
C范围：CHECKED_IN预约原冻结项目或真实未安排SERVICE/P1定义；工单OPEN/scope1，task逐项目PENDING/donevoid0/binding0/UNBOUND/canStartfalse/actualStartnull；无模板伪step/participation。
C原键：当前BOOKING_MANAGE+CUSTOMER→完整endpoint请求hash→成功原结果；旧revision/过期cr1成功重放，变参冲突；异key同预约输家CONFLICT。REPLACE UNKNOWN持久原outer/derived/appointment operation，不创建范围。
C最终单TX：root→fence→current→原接待op→稳定去重heads→workOrder/context/tasks/OPEN RECEIVED草稿/log/safeOutbox/op成功；任一失败全回滚。无owner IO/嵌套REQUIRES_NEW。
CscopeHash显式schema/scope/规范身份/scopeRev/原item或展开index/SKU/原定义hash及typed规则/qtyCOUNT/原policy规则/全部资源份额与时间；不含最终随机业务号、ObservedHeads或临时hold观测revision。context保留完整typed冻结证据与真实task映射；草稿同hash/真实taskRefs，无proof/外部下单指令/nextRetryAt。
C读取ServiceImpl先当前权限/consumer本人并直接SQL授权root/context编码，再WorkOrderQuery.detail投影真实子行；C guest仅同scope不可变receipt可见。无context仅B legacy UNKNOWN只读，C拒；子行完整活跃父链。
C修复I1/I2：begin返回SUCCEEDED立即回原result；stale scope CONFLICT只同完整namespace/key/hash及同outer成功重读，当前权限/身份owner错误不吞。新建intent显式tenantNo与DB一致，不放宽namespace比较。REPLACE短TX锁内先SUCCEEDED不改payload/phase/result；完整原绑定RESOURCE_PENDING→OPEN_PENDING，已OPEN_PENDING同binding/openingRevision幂等恢复。
C修复I3/I4：新052/省略身份054锁外真实guest.get，REGISTERED/revision1/user空；CLAIMED/revision2/user非空须同scope不可变receipt。只规范新prepared，原预约GUEST保留；已有pending取原outer canonical/hash不重生成。最终根/fence/current/outer锁内复用countWorkOrders阻断已有工单或实际task，保留软删历史CAS/唯一键。
D048 persists RECEIVED canonical identity and stable derived key before matching; atomically links original CREATE preparation before resource RPC; no identity tickets or contact in frozen payload.
Default start is earliest strictly future five-minute service grid accounting for every raw offset/before-buffer. Selected start, complete plan and command bytes never change during recovery. Expired commands may reconcile, but no new acceptance/arrival/opening; PENDING ARRIVAL_REQUIRES_RESCHEDULE.
RESOURCE_PENDING -> ARRIVAL_PENDING -> OPEN_PENDING -> SUCCEEDED; sole C writer stores complete WalkInVO plus OPEN/UNBOUND order, PENDING tasks and local OPEN draft in one TX. Unknown resources create no order/task.
Original-number SQL reads reception first in trusted tenant/entity/store; GET is pure read; action/endpoint pairs determine projection. Current BOOKING_MANAGE+CUSTOMER authorizes recovery by another employee without changing original namespace/canonical bytes.
Only proven original RESERVE rejection without hold, or confirmed explicit CANCEL/RELEASE of the original hold, closes REJECTED. CONFIRM rejection stays PENDING RESOURCE_RELEASE_REQUIRED until that proof.
Background qualification before LIMIT50 requires receipt + complete original scope/source/plan/command intent binding. Only existing coordinator.recover; no new accept, guest get, staff identity, check-in or opening. Lease30s, retry30s/failure60s, ORDER BY next_retry_at,id.
D fix R1: final 048 writer rechecks every original interval after lock acquisition, persisting reschedule-PENDING without scope on expiry; ordinary 052 retains its before-end rule. New CREATE locks active local heads and checks original selected versions without rematching; after all preparation writes, immediately before commit it rechecks every frozen interval against the existing reception Clock, rolling preparation back on expiry and preserving original reschedule-PENDING. Explicit authorization/capability and all nonzero remote HTTP errors propagate; unavailable or unproven release evidence stays PENDING.
D adds no migration: V1-V7 unchanged, ready7 and 27 private tables. HTTP and remote acceptance remain Task59; deployment debt Task60.


## Task58 actual local source submission, binding and bill

Task58 local source kernels accepted; Task59 public/typed Remote code and protocol tests implemented; real-owner deployment deferred. BM05 remains closed.

| Module / owner | Actual signature |
|---|---|
| beauty-api / ai.neargo.shop.beauty.service.ServiceSubmissionService | `ServiceSubmissionVO submitForWorkOrder(String entityNo, String currentStoreNo, String actorNo, String workOrderNo, SubmitServicesReq req, String idempotencyKey);` |
| beauty-api / ai.neargo.shop.beauty.service.ServiceSubmissionService | `ServiceSubmissionVO getSubmission(String entityNo, String currentStoreNo, String actorNo, String submissionNo);` |
| beauty-api / ai.neargo.shop.beauty.service.ServiceSubmissionService | `RecoveryRequestVO requestSubmissionRecovery(String entityNo, String currentStoreNo, String actorNo, String submissionNo, RecoverSubmissionReq req, String idempotencyKey);` |
| beauty-api / ai.neargo.shop.beauty.service.ServiceSubmissionService | `ServiceSubmissionVO recoverPending(String entityNo, String currentStoreNo, String actorNo, String submissionNo);` |
| beauty-api / ai.neargo.shop.beauty.service.WorkOrderBillService | `WorkOrderBillVO getBill(String entityNo, String currentStoreNo, String actorNo, String workOrderNo);` |
| beauty-api / ai.neargo.shop.beauty.service.WorkOrderBillService | `BusinessTraceVO getTrace(String entityNo, String currentStoreNo, String actorNo, String businessNo);` |
| beauty-api / ai.neargo.shop.beauty.service.WorkOrderService | `ServiceSubmissionVO submitServices(String entityNo, String currentStoreNo, String actorNo, String workOrderNo, SubmitServicesReq req, String idempotencyKey);` |
| beauty-api / ai.neargo.shop.beauty.service.WorkOrderService | `WorkOrderBillVO getBill(String entityNo, String currentStoreNo, String actorNo, String workOrderNo);` |

| DTO record | Actual fields |
|---|---|
| `SubmitServicesReq` | `long expectedRevision, long expectedScopeRevision, List<TaskSelection> tasks, List<AddedItem> additions, SourceSelection source` |
| `RecoverSubmissionReq` | `long expectedRevision, String reason` |
| `ServiceSubmissionVO` | `String submissionNo, String workOrderNo, String action, long revision, long originalScopeRevision, long targetScopeRevision, String status, String phase, String checkoutOperationNo, CheckoutPort.CheckoutQuote quote, String orderNo, String subOrderNo, SourceObservationVO source, List<SubmissionTaskVO> tasks, String recoveryAction, List<String> blockingReasons, CounterCheckoutQuote counterQuote` |
| `SubmissionTaskVO` | `String taskNo, String skuNo, String definitionVersionNo, BigDecimal qty, String uom, long taskRevision, long bindingRevision, String sourceLineNo, String sourceState` |
| `SourceObservationVO` | `String state, long observationEpoch, Long sourceRevision, String sourceHash, Long lastVerifiedRevision, String lastVerifiedHash, Instant observedAt, String reasonCode, boolean canStart, String admissionKind, String manualState, Long manualRevision, String manualHash` |
| `RecoveryRequestVO` | `String recoveryNo, String submissionNo, String operationNo, long acceptedRevision, String state, String recoveryAction` |
| `WorkOrderBillVO` | `String workOrderNo, long scopeRevision, String localWatermark, Instant observedAt, List<SourceBillVO> sources, List<CurrencyBillVO> currencies, boolean complete, List<String> blockingReasons` |
| `SourceBillVO` | `String checkoutOperationNo, String orderNo, String sourceNo, String currency, Long dueMinor, Long receivedMinor, Long refundedMinor, Long netReceivedMinor, Long outstandingMinor, String amountScope, List<ReceiptSummaryVO> receipts, List<SubmissionTaskVO> tasks, SourceObservationVO observation, boolean complete, List<String> blockingReasons, String admissionKind, String financialVerification, ManualReceiptSummaryVO manualReceipt, Long manualDeclaredMinor` |
| `CurrencyBillVO` | `String currency, Long dueMinor, Long receivedMinor, Long refundedMinor, Long netReceivedMinor, Long outstandingMinor, boolean complete, Long manualDeclaredMinor, String financialVerification` |
| `ReceiptSummaryVO` | `String paymentOperationNo, String paymentNo, String outTradeNo, String receiptNo, String channel, String status, long amountMinor, String currency` |
| `BusinessTraceVO` | `String businessNo, String workOrderNo, String appointmentNo, long scopeRevision, List<ServiceSubmissionVO> submissions, WorkOrderBillVO bill, boolean complete, List<String> blockingReasons` |
| `ManualReceiptSummaryVO` | `String declarationNo, String method, long declaredMinor, String currency, Instant receivedAt, Instant recordedAt, String provenance, String financialVerification` |

| Concrete helper / Bean | Contract |
|---|---|
| beauty-core / ServiceSubmissionServiceImpl | Durable endpoint SOURCE_SUBMITTED receipt + immutable intent; original actor/request recovery, leased original operation, no confirm/place/pay. |
| beauty-core / ServiceSubmissionAccess + ServiceSubmissionQuery | Current MERCHANT MCH/USR grants, original complete parent chain, USER or same-store immutable guest claim receipt; proof never persisted. |
| beauty-core / ServiceSourceObservation | V8 canonical caller/operation/suborder domain; claim epoch UNKNOWN before unlocked source IO; monotonic highest/verified pairs including superseded higher and trusted UNKNOWN invalidation. |
| beauty-core / ServiceSourceBinding | Local TX only: observation -> sorted guards -> workOrder -> sorted tasks -> submission; net refundable ceiling vs held local quantity; first binding only, no release. |
| beauty-core / WorkOrderBillServiceImpl + ServiceSourceFacts | RR keyset manifest then unlocked owner reads, bounded one retry, per-currency exact arithmetic and canonical receipt dedup; no private money ledger. |
| beauty-core / BeautySourceConfiguration | ConditionalOnBeautyEnabled concrete one-way Bean graph; ObjectProvider CheckoutPort is optional/fail closed, no production owner stub; disabled installs no source beans. |

BAPI-056 namespace: MAIN/entity/store/realm/kind/actor/endpoint/rawKeyHash, excludes derived action/target. Payload includes workOrder and all request fields. BTSK truncated actionNo is compared with full256 namespace/payload. Same TX stores scope/tasks/submission/receipt; duplicate transaction rolls back before original receipt read.

BAPI-073 immutable acceptance and original ServiceOperationRecoveryService dispatch preserve the same submission/checkout number. Another actor cannot claim definitive prepare absence or replay the original short proof.

GET submission/bill may update observation projection and temporarily make READY UNKNOWN; it never binds, confirms, creates an order or initiates payment. Current auth errors propagate; known dependency failure remains incomplete/pending.

Highest revision belongs to one canonical checkout operation, not a cross-operation integer order. Unknown never lowers highest. Superseded trusted UNKNOWN invalidates in-flight tickets even without pair. Complete same highest pair or higher is required for READY.

verified_lines persists only identity/quantity (sourceLine/clientRef/goods/sku/ordered/refundable/uom); dueMinor and receipt amounts remain ephemeral owner facts.

READY is observed local bind eligibility only. Shared owner quantity is historical/net-refund observation, not shared atomic consumption. Binding preserves all historical held quantities. canStart=false; no BM05 execution.

Whole-source money remains visible for trustworthy complete BLOCKED/closed historical owner facts, but partial allocation never becomes work-order attributable money. UNKNOWN due0 projects null. Currency overflow/receipt conflicts/owner partial failure force affected totals incomplete.

Actual embedded HOST CheckoutPort and member owners are exercised by true C HTTP read/confirmation/order/payment and current B cash receipt HTTP. Refund bill test changes controlled owner ledger facts, not a complete reverse API.

Standalone missing checkout provider returns explicit SOURCE_UNAVAILABLE; Task59 must supply real transport wiring. No new Remote or Controller is claimed.

Only actual BEAUTY_SOURCE_CUSTOMER_REQUIRED exception80106 is registered with three languages. Proposed quantity/revision/scope/dependency exception placeholders remain durable safe phases instead.


## Task59 BM04 actual public and typed owner protocol

IMPLEMENTED_TASK59_CODE_PROTOCOL_TESTED_DEPLOYMENT_DEFERRED

| API / HTTP | Actual controller / Service signature | Authority |
|---|---|---|
| BAPI-031 / `POST /biz/x/beauty/guest-registrations` | `ai.neargo.shop.beauty.api.biz.BizGuestRegistrationController`<br>`GuestRegistrationVO registerGuest(String entityNo, String currentStoreNo, String actorNo, RegisterGuestReq req, String idempotencyKey);` | U |
| BAPI-032 / `POST /biz/x/beauty/guest-registrations/{guestNo}/claims` | `ai.neargo.shop.beauty.api.biz.BizGuestRegistrationController`<br>`GuestClaimVO claimGuest(String entityNo, String currentStoreNo, String actorNo, String guestNo, ClaimGuestReq req, String idempotencyKey);` | U |
| BAPI-047 / `POST /biz/x/beauty/arrivals` | `ai.neargo.shop.beauty.api.biz.BizReceptionController`<br>`ArrivalVO checkIn(String entityNo, String currentStoreNo, String actorNo, CheckInReq req, String idempotencyKey);` | W + U |
| BAPI-048 / `POST /biz/x/beauty/walk-ins` | `ai.neargo.shop.beauty.api.biz.BizReceptionController`<br>`WalkInVO receiveWalkIn(String entityNo, String currentStoreNo, String actorNo, ReceiveWalkInReq req, String idempotencyKey);` | W + U |
| BAPI-053 / `POST /biz/x/beauty/reception-exceptions` | `ai.neargo.shop.beauty.api.biz.BizReceptionController`<br>`ReceptionExceptionVO coordinateReceptionException(String entityNo, String currentStoreNo, String actorNo, ReceptionExceptionReq req, String idempotencyKey);` | W |
| BM04-ARRIVAL-CHALLENGE / `POST /biz/x/beauty/arrival-challenges` | `ai.neargo.shop.beauty.api.biz.BizArrivalChallengeController`<br>`ArrivalChallengeVO issueArrivalChallenge(String entityNo, String currentStoreNo, String actorNo, String idempotencyKey);` | W |
| BAPI-052 / `POST /biz/x/beauty/appointments/{appointmentNo}/work-orders` | `ai.neargo.shop.beauty.api.biz.BizAppointmentController`<br>`WorkOrderVO openWorkOrderFromAppointment(String entityNo, String currentStoreNo, String actorNo, String appointmentNo, OpenAppointmentWorkOrderReq req, String idempotencyKey);` | W + U |
| BAPI-054 / `POST /biz/x/beauty/work-orders` | `ai.neargo.shop.beauty.api.biz.BizWorkOrderController`<br>`WorkOrderVO openWorkOrder(String entityNo, String currentStoreNo, String actorNo, OpenWorkOrderReq req, String idempotencyKey);` | W + U |
| BAPI-055 / `GET /biz/x/beauty/work-orders/{workOrderNo}` | `ai.neargo.shop.beauty.api.biz.BizWorkOrderController`<br>`WorkOrderDetailVO getWorkOrder(String entityNo, String currentStoreNo, String actorNo, String workOrderNo);` | R |
| BAPI-155 / `GET /mp/x/beauty/work-orders/{workOrderNo}` | `ai.neargo.shop.beauty.api.mp.MpWorkOrderController`<br>`MyWorkOrderVO getMyWorkOrder(String userNo, String workOrderNo);` | current C + active owner |
| BAPI-154 / `POST /mp/x/beauty/arrivals` | `ai.neargo.shop.beauty.api.mp.MpReceptionController`<br>`MyArrivalVO checkInMyAppointment(String userNo, MyCheckInReq req, String idempotencyKey);` | current C + own appointment + memberCode + store challenge |
| BAPI-056 / `POST /biz/x/beauty/work-orders/{workOrderNo}/service-submissions` | `ai.neargo.shop.beauty.api.biz.BizWorkOrderController`<br>`ServiceSubmissionVO submitServices( String entityNo, String currentStoreNo, String actorNo, String workOrderNo, SubmitServicesReq req, String idempotencyKey);` | W + U + O; PREPARE also P |
| BAPI-071 / `GET /biz/x/beauty/work-orders/{workOrderNo}/bill` | `ai.neargo.shop.beauty.api.biz.BizWorkOrderController`<br>`WorkOrderBillVO getBill( String entityNo, String currentStoreNo, String actorNo, String workOrderNo);` | R + U + O |
| BAPI-072 / `GET /biz/x/beauty/service-submissions/{submissionNo}` | `ai.neargo.shop.beauty.api.biz.BizServiceSubmissionController`<br>`ServiceSubmissionVO getSubmission( String entityNo, String currentStoreNo, String actorNo, String submissionNo);` | R + U + O |
| BAPI-073 / `POST /biz/x/beauty/service-submissions/{submissionNo}/recovery-requests` | `ai.neargo.shop.beauty.api.biz.BizServiceSubmissionController`<br>`RecoveryRequestVO requestSubmissionRecovery( String entityNo, String currentStoreNo, String actorNo, String submissionNo, RecoverSubmissionReq req, String idempotencyKey);` | W + U + O |
| BAPI-137 / `GET /biz/x/beauty/traces/{businessNo}` | `ai.neargo.shop.beauty.api.biz.BizBusinessTraceController`<br>`BusinessTraceVO getTrace( String entityNo, String currentStoreNo, String actorNo, String businessNo);` | R + U + O |
| BAPI-160 / `GET /biz/x/beauty/operations/{operationNo}` | `ai.neargo.shop.beauty.api.biz.BizServiceOperationController`<br>`OperationVO getOperation(String entityNo, String currentStoreNo, String actorNo, String operationNo);` | actual operation family current authorization |
| BM03-OPERATION-RECOVERY / `POST /biz/x/beauty/operations/{operationNo}/recoveries` | `ai.neargo.shop.beauty.api.biz.BizServiceOperationController`<br>`OperationVO recoverOperation(String entityNo, String currentStoreNo, String actorNo, String operationNo);` | actual operation family current recovery authorization |

| Direction / exact private route | Typed payload / result |
|---|---|
| beauty-server / InternalBeautyGuestController → GuestRegistrationService<br>`POST /internal/beauty/reception/registerGuest` | `InternalCall<BeautyReceptionInternalApi.RegisterGuest>`<br>InternalReply<GuestRegistrationVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautyGuestController → GuestRegistrationService<br>`POST /internal/beauty/reception/claimGuest` | `InternalCall<BeautyReceptionInternalApi.ClaimGuest>`<br>InternalReply<GuestClaimVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautyReceptionController → ReceptionService<br>`POST /internal/beauty/reception/checkIn` | `InternalCall<BeautyReceptionInternalApi.CheckIn>`<br>InternalReply<ArrivalVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautyReceptionController → ReceptionService<br>`POST /internal/beauty/reception/receiveWalkIn` | `InternalCall<BeautyReceptionInternalApi.ReceiveWalkIn>`<br>InternalReply<WalkInVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautyReceptionController → ReceptionService<br>`POST /internal/beauty/reception/coordinateReceptionException` | `InternalCall<BeautyReceptionInternalApi.CoordinateException>`<br>InternalReply<ReceptionExceptionVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautyReceptionController → ReceptionService<br>`POST /internal/beauty/reception/issueArrivalChallenge` | `InternalCall<BeautyReceptionInternalApi.IssueArrivalChallenge>`<br>InternalReply<ArrivalChallengeVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautyWorkOrderController → AppointmentService<br>`POST /internal/beauty/reception/openWorkOrderFromAppointment` | `InternalCall<BeautyReceptionInternalApi.OpenAppointment>`<br>InternalReply<WorkOrderVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautyWorkOrderController → WorkOrderService<br>`POST /internal/beauty/reception/openWorkOrder` | `InternalCall<BeautyReceptionInternalApi.OpenWorkOrder>`<br>InternalReply<WorkOrderVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautyWorkOrderController → WorkOrderService<br>`POST /internal/beauty/reception/getWorkOrder` | `InternalCall<BeautyReceptionInternalApi.WorkOrderRef>`<br>InternalReply<WorkOrderDetailVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautyWorkOrderController → WorkOrderService<br>`POST /internal/beauty/reception/getMyWorkOrder` | `InternalCall<BeautyReceptionInternalApi.WorkOrderRef>`<br>InternalReply<MyWorkOrderVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautyReceptionController → ReceptionService<br>`POST /internal/beauty/reception/checkInMyAppointment` | `InternalCall<BeautyReceptionInternalApi.CheckInMine>`<br>InternalReply<MyArrivalVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautyWorkOrderController → WorkOrderService<br>`POST /internal/beauty/reception/submitServices` | `InternalCall<BeautyReceptionInternalApi.SubmitServices>`<br>InternalReply<ServiceSubmissionVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautyWorkOrderController → WorkOrderService<br>`POST /internal/beauty/reception/getBill` | `InternalCall<BeautyReceptionInternalApi.WorkOrderRef>`<br>InternalReply<WorkOrderBillVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautySubmissionController → ServiceSubmissionService<br>`POST /internal/beauty/reception/getSubmission` | `InternalCall<BeautyReceptionInternalApi.SubmissionRef>`<br>InternalReply<ServiceSubmissionVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautySubmissionController → ServiceSubmissionService<br>`POST /internal/beauty/reception/requestSubmissionRecovery` | `InternalCall<BeautyReceptionInternalApi.RequestSubmissionRecovery>`<br>InternalReply<RecoveryRequestVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautySubmissionController → ServiceSubmissionService<br>`POST /internal/beauty/reception/recoverPending` | `InternalCall<BeautyReceptionInternalApi.SubmissionRef>`<br>InternalReply<ServiceSubmissionVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautyTraceController → WorkOrderBillService<br>`POST /internal/beauty/reception/getTrace` | `InternalCall<BeautyReceptionInternalApi.BusinessRef>`<br>InternalReply<BusinessTraceVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautyOperationController → ServiceOperationQueryService<br>`POST /internal/beauty/reception/getOperation` | `InternalCall<BeautyReceptionInternalApi.OperationRef>`<br>InternalReply<OperationVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| beauty-server / InternalBeautyOperationController → ServiceOperationRecoveryService<br>`POST /internal/beauty/reception/recoverOperation` | `InternalCall<BeautyReceptionInternalApi.OperationRef>`<br>InternalReply<OperationVO>; binding + HMAC-SHA256 typed complete request digest + result; strict presence/nullability |
| HOST / InternalCheckoutController → actual CheckoutPortImpl<br>`POST /internal/trade/checkout/bound/prepare` | `InternalCall<CheckoutInternalApi.BoundPrepare>`<br>InternalReply<CheckoutPort.CheckoutQuote>; full binding digest and strict typed result |
| HOST / InternalCheckoutController → actual CheckoutPortImpl<br>`POST /internal/trade/checkout/bound/readQuote` | `InternalCall<CheckoutInternalApi.BoundOperation>`<br>InternalReply<CheckoutPort.CheckoutQuote>; full binding digest and strict typed result |
| HOST / InternalCheckoutController → actual CheckoutPortImpl<br>`POST /internal/trade/checkout/bound/findOperation` | `InternalCall<CheckoutInternalApi.BoundOperation>`<br>InternalReply<CheckoutPort.CheckoutResult>; full binding digest and strict typed result |
| HOST / InternalCheckoutController → actual CheckoutPortImpl<br>`POST /internal/trade/checkout/bound/source` | `InternalCall<CheckoutInternalApi.BoundSource>`<br>InternalReply<CheckoutPort.CheckoutSourceFacts>; full binding digest and strict typed result |

Public api-only + enabled; embedded actual core, no-core HOST Remote, private SERVER real core with production reverse owners; disabled/ops no new public routes.

Existing static X-Internal-Token and delegated MAIN MERCHANT MCH/USR or CONSUMER USR; no nonce/session transport added. Current HOST token-store authentication and owner live authorization remain authoritative.

InternalCall strict recursive required keys including nullable fields; canonical HMAC independent of ObjectMapper escaping; result includes binding + digest, not incoming contact/proof/raw key. No proof or C token in durable intent/log.

GuestRegistrationVO.claimToken and ArrivalChallengeVO.code remain authorized issuance/replay exceptions. Original past expiry is not rejected by transport.

Opt-in non2xx headers X-Internal-Error-Code and X-Internal-Request-Digest; blank/wrong token401 before digest/authority; bound owner404 differs from transport404; unbound/200error/null/mismatch → dependency unavailable.

Appointment OPEN052/054 canonical052 schema2 stores only original endpoint/fullhash/canonicalhash; schema1 original054/recovery supported; full054 first→minimal052 replay,052 first→nonminimal054409; dual legacy conflict.

Default BizEndpointPermTest scans backend only and excludes this external plugin reactor. No false single-code annotation or PUBLIC grant is introduced; composite permissions are enforced by actual Service policy and owner scopes.

Unit/HTTP/native core evidence is distinct from full real-owner two-JVM acceptance, which is DEFERRED by the user until unified P1–P4 deployment verification. No Task60 packaged JAR claim.


## Task62 BM05 shared current-personnel contract

IMPLEMENTED_TASK62_S0_CODE_TESTED_DEPLOYMENT_DEFERRED_NOT_START_AUTHORITY

| Owner / class | Method |
|---|---|
| `ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort` | `Observation current(Query query);` |
| `ai.neargo.shop.spi.personnel.PersonnelOperatorInternalApi` | `InternalReply<Observation> current(InternalCall<Query> request);` |
| `ai.neargo.shop.merchant.port.PersonnelOperatorFactsPortImpl` | `Observation current(Query query);` |
| `ai.neargo.shop.merchant.mapper.MerchantMappers.PersonnelAccountMapper` | `List<OperatorPersonnelFactRow> selectCurrentOperatorPersonnel(String tenantNo, String entityNo, String storeNo, String accountNo, LocalDateTime at);` |
| `ai.neargo.shop.portal.internal.InternalPersonnelOperatorController` | `ResponseEntity<?> current(InternalCall<Query> request, String given);` |
| `ai.neargo.shop.beauty.port.RemotePersonnelOperatorFactsPort` | `Observation current(Query query);` |

InternalReply<PersonnelOperatorFactsPort.Observation>; LINKED/UNLINKED, observedAt, nullable personnel; personnelNo, assignmentNo, personnelRevision, assignmentRevision, linkRevision, validFrom, nullable validTo

S0 code and isolated owner/HTTP tests accepted. Canonical current-permission account resolution stays at the personnel owner; accountNo never leaves that owner. No new schema, role seed grant or public API.

Only biz:service:task:self/view/coordinate/approve/close exact purposes. Business-person link, effective assignment and action permission are separate. Authorized absence is UNLINKED; denial or dependency failure is never absence.

Effective assignment: validFrom <= observedAt AND (validTo IS NULL OR observedAt < validTo). NULL validTo means indefinite; JSON key remains required and explicitly nullable. No manufactured future date.

Bound v1 endpoint PERSONNEL_OPERATOR_CURRENT/action CURRENT/target operatorNo; exact MERCHANT realm MCH/USR identity, tenant/entity/store and complete request digest; current authorization outside caller transaction.

Owner uses an RR observation with canonical account consistency checks before/inside/after; not durable service permission. Remote transport is five seconds, bad/absent binding and malformed success fail closed as 80107; opaque owner failures remain503.

No ServiceTask start/fulfillment enablement or canStart change. Final current five suites33/0F/0E/0skip, label17, runner5 and affected current retail/food compilation passed; expiry ablation/restoration is explicitly versioned. Focused bean-registration tests do not prove deployed profiles. Formal deployment remains deferred.


## 现金线下核心：共用人工订单准入（Task64 M1）

IMPLEMENTED_TASK64_M1_CODE_TESTED_REVIEWED_NOT_DEPLOYED

主TDD§0.3/2.41/2.42；ADR050；Task63现金线下核心设计§1–4/8–10；不改变原160目标API编号

| Owner / class | Method |
|---|---|
| `ai.neargo.shop.spi.trade.ManualServiceAdmissionPort` | `CheckoutQuote prepare(CheckoutScope scope, PrepareCheckoutReq request);` |
| `ai.neargo.shop.spi.trade.ManualServiceAdmissionPort` | `ManualAdmissionView confirm(CheckoutScope scope, ConfirmManualReceipt request);` |
| `ai.neargo.shop.spi.trade.ManualServiceAdmissionPort` | `ManualAdmissionView query(CheckoutScope scope, ManualLookup request);` |
| `ai.neargo.shop.spi.trade.ManualServiceAdmissionPort` | `ManualSourceFacts source(CheckoutScope scope, SourceLookup request);` |
| `ai.neargo.shop.trade.service.ManualServiceAdmissionService` | `int recoverPending(int limit);` |
| `ai.neargo.shop.spi.product.StockDispositionPort` | `Disposition confirmOriginal(Confirmation command);` |
| `ai.neargo.shop.spi.product.StockDispositionPort` | `Disposition observeOriginal(Lookup query);` |
| `ai.neargo.shop.trade.service.ManualServiceAdmissionService` | `ManualAdmissionView confirmForOrder(CheckoutScope scope, String orderNo, ConfirmManualReceipt request);` |
| `ai.neargo.shop.trade.service.ManualServiceAdmissionService` | `ManualAdmissionView queryForOrder(CheckoutScope scope, String orderNo, ManualLookup request);` |
| `ai.neargo.shop.trade.service.ManualServiceAdmissionService` | `ManualSourceFacts sourceForOrder(CheckoutScope scope, String orderNo, SourceLookup request);` |
| `ai.neargo.shop.trade.mapper.ManualAdmissionMapper` | `int afterSaleConflicts(String sub);` |
| `ai.neargo.shop.auth.BoundActionContext` | `boolean present();` |
| `ai.neargo.shop.auth.BoundActionContext` | `void require(InternalCall<?> expected);` |
| `ai.neargo.shop.product.service.StockDispositionService` | `Disposition confirmOriginal(Confirmation command);` |
| `ai.neargo.shop.product.service.StockDispositionService` | `Disposition observeOriginal(Lookup query);` |
| `ai.neargo.shop.inventory.service.ReservationDispositionService` | `Disposition confirmOriginal(Confirmation command);` |
| `ai.neargo.shop.inventory.service.ReservationDispositionService` | `Disposition observeOriginal(Lookup query);` |
| `ai.neargo.shop.trade.service.ManualServiceAdmissionService` | `CheckoutQuote prepare(CheckoutScope scope, PrepareCheckoutReq request);` |
| `ai.neargo.shop.trade.service.ManualServiceAdmissionService` | `ManualAdmissionView confirm(CheckoutScope scope, ConfirmManualReceipt request);` |
| `ai.neargo.shop.trade.service.ManualServiceAdmissionService` | `ManualAdmissionView query(CheckoutScope scope, ManualLookup request);` |
| `ai.neargo.shop.trade.service.ManualServiceAdmissionService` | `ManualSourceFacts source(CheckoutScope scope, SourceLookup request);` |
| `ai.neargo.shop.trade.order.refund.service.RefundExecutionStore` | `OrdRefundExecution currentSource(String no);` |
| `ai.neargo.shop.trade.order.refund.service.RefundExecutionStore` | `static OrdRefundExecution requireFunded(OrdRefundExecution row);` |
| `ai.neargo.shop.trade.order.mapper.RefundExecutionMapper` | `int fundedSource(String order, String sub, String afterSale, String tenant, String entity);` |

精确类/DTO/Mapper/SQL/AC清单：主TDD§2.42及Task63设计§9；原160目标编号不变。

M1仅已识别真实顾客、普通正价单店SERVICE/STORE_VERIFY/COUNT；C购买确认不等于在线支付，不冒充C，临客M2仍属本阶段必做。

MANUAL_OFFLINE只由专用服务器操作持久；旧在线/资产/正式资金来源不能改标签降级。商品/价格/门店/业务资格仍复用，支付渠道配置/资金查询/点数协调不作为人工模式前置。

受权现金经办人+CASH_RECEIVE+trade:manual-service:confirm；原额/CNY/时间/作用域及原键幂等校验，真实声明可推进人工PAID；不调用旧markPaid或发布普通金融已付事件。

原库存命令已接受或void调用成功不等于成功结果；必须读取冻结原权威、生命周期及完整行数量的一致终态；未知保留原号和实收声明，不能ADMITTED。

人工准入/来源观察与金融回执分开；旧source保持UNKNOWN且不得先查Pay；金额及经办审计按权限裁剪。M1不开放canStart/任务实际占用/履约。原订单详情仅业务状态，专用B查询/source提供人工声明；M2前台账单/展示整合必须显式区分人工登记与Pay核验，开放完整现金闭环前完成，M1不声称页面/全部资金看板已实现。

PAID/paid_at自动结算选择器在LIMIT前排除人工；普通支付/正式线下receipt/售后退款/修复/通用履约入口拒绝人工；原资金路径及SERVICE_EXECUTION旧完成门禁保持。退款编排的实际派发/查询/成功回调核验和现金、点数两类独立资源恢复都须在外部效果前，以短事务按子单优先锁序核对当前来源及原执行/任务身份；导入矛盾原记录持久保留冲突，不得访问Pay、释放/恢复库存或记成功。扫描排除冲突不能替代直接入口门禁；跨所有者IO不持Core根/执行/任务锁，保留普通资金所有者本地事务。

Task64 M1已完成代码、分版本定向验证和独立审查；正式部署未执行，M2～M7完整核心尚未完成。M2临客及绑定→M3数量许可→M4真实占用→M5作业→M6履约关闭→M7具体任务日历。

远程人工CONFIRM仅在机器凭证和精确路由通过后安装的BoundActionContext中接受：绑定完整原请求digest/作用域/操作，随后查当前CASH_RECEIVE+MANUAL_CONFIRM同一真实账号；上下文finally恢复，不转发HOST或C令牌。HOST入口及原正式现金接口仍要求真实当前会话；通用delegation不能替代现金授权。

绑定返回还须对应完整原操作：所有operation相等，quote/source门店和实体相等，指定subOrder/declaration相等，CONFIRM返回的原method/amount/currency/receivedAt必须相等（不豁免AWAITING_RECEIPT）；结构合法但对应错误仍为依赖不可用。

本地Service接口独立声明方法并引用SPI DTO，由.port适配器实现跨域Port；Service不继承Port。相同待处理观察只延后重试，不改变准入修订、来源摘要或业务事件，避免队首长期未知记录阻塞后续恢复。


## 柜台购买与人工来源增量（M2分阶段实施状态）

M2B_M2C_CODE_COMPILED_STATIC_REVIEWED_UNIFIED_TEST_PENDING

主TDD§0.4/§2.45–§2.47。Task67及Task68A/B1/B2/C均已编码、编译或语法检查并独立静态复审；C的45Node/53Java用例、golden及旧意图材料生产均未运行。三行业编译通过，统一测试部署后置，canStart=false，Pay后置。

| Owner / class | Planned method |
|---|---|
| `ai.neargo.shop.spi.trade.ManualServiceAdmissionPort` | `CounterCheckoutQuote prepareCounter(CheckoutScope scope, PrepareCounterReq request)` |
| `ai.neargo.shop.spi.trade.ManualServiceAdmissionPort` | `CounterCheckoutQuote readCounterQuote(CheckoutScope scope, OperationRef operation)` |
| `ai.neargo.shop.spi.trade.ManualServiceAdmissionPort` | `CounterConfirmation acceptCounter(CheckoutScope scope, AcceptCounterReq request)` |
| `ai.neargo.shop.spi.trade.ManualServiceAdmissionPort` | `CheckoutResult placeCounter(CheckoutScope scope, PlaceCounterReq request)` |
| `ai.neargo.shop.spi.member.CustomerIdentityPort` | `ResolvedCustomer resolveCounterReference(EntitlementContext context, CounterReferenceQuery query)` |
| `ai.neargo.shop.spi.member.GuestRegistrationPort` | `CurrentClaim currentClaim(ConsumerScope scope, GuestRef guest)` |
| `ai.neargo.shop.spi.member.GuestRegistrationPort` | `List<CurrentClaim> currentClaims(ConsumerScope scope)` |
| `ai.neargo.shop.spi.product.StockDispositionPort` | `ReservationView reserveOriginal(Reservation command)` |
| `ai.neargo.shop.spi.product.StockDispositionPort` | `ReservationView observeReservation(ReservationLookup query)` |
| `ai.neargo.shop.spi.product.StoreSalesCatalogPort` | `Snapshot lockForCounterCheckout(String tenant, String entity, String store, Instant at)` |
| `ai.neargo.shop.spi.product.GoodsQueryPort` | `Map<String,SkuSnapshot> snapshotForCounter(CustomerParty party, List<String> skus, Map<String,String> stores)` |
| `ai.neargo.shop.trade.service.CheckoutOrderWriter` | `CounterQuoteSnapshot quoteManualFor(CustomerParty party, CreateOrderCommand command, CheckoutPricingScope scope)` |
| `ai.neargo.shop.trade.service.CheckoutOrderWriter` | `CounterOrderPlan stageCounterConfirmedFor(CustomerParty party, CreateOrderCommand command, String key, CounterPurchaseAuthorization authorization)` |
| `ai.neargo.shop.trade.service.CheckoutOrderWriter` | `OrderVO createCounterConfirmedFor(CustomerParty party, CounterOrderPlan plan, CounterPurchaseAuthorization authorization)` |
| `ai.neargo.shop.trade.order.service.create.CheckoutQuoteGuard` | `CheckoutPricingScope counterContext(CounterPurchaseAuthorization authorization)` |
| `ai.neargo.shop.trade.order.service.create.CheckoutQuoteGuard` | `void requireCounterMatches(CounterPurchaseAuthorization authorization, CheckoutEconomicSnapshot economic)` |
| `ai.neargo.shop.trade.order.service.create.CheckoutQuoteGuard` | `void completeCounter(CounterPurchaseAuthorization authorization, String orderNo, String subOrderNo)` |
| `ai.neargo.shop.trade.order.service.create.CounterOrderPlacementService` | `CheckoutResult place(CheckoutScope scope, PlaceCounterReq request)` |
| `ai.neargo.shop.trade.order.service.create.CounterOrderPlacementService` | `int recoverPending(int limit)` |
| `ai.neargo.shop.trade.order.service.create.OrderCreationRecoveryService` | `CounterOrderPlan registerCounter(CounterOrderPlan plan, CounterPurchaseAuthorization authorization)` |
| `ai.neargo.shop.trade.order.service.create.OrderCreationRecoveryService` | `void recordCounterReservation(String orderNo, long intentRevision, ReservationView reservation)` |
| `ai.neargo.shop.trade.order.service.create.OrderCreationRecoveryService` | `OrdCreateIntent findCounter(String operationNo)` |
| `ai.neargo.shop.trade.order.service.query.OrderCustomerAccess` | `CustomerReadScope currentReadScope()` |
| `ai.neargo.shop.trade.order.service.query.OrderCustomerAccess` | `void requireReadable(OrdOrder order, CustomerReadScope scope)` |
| `ai.neargo.shop.trade.order.service.query.StoreOrderReadStore` | `StoreOrderMaterial findReadable(String orderNo, CustomerReadScope scope)` |
| `ai.neargo.shop.trade.order.service.query.StoreOrderReadStore` | `PageData<StoreOrderMaterial> pageReadable(CustomerReadScope scope, StoreOrderQuery query, long page, long size)` |
| `ai.neargo.shop.beauty.policy.ServiceSubmissionAccess` | `CustomerParty manualParty(BtyWorkOrderContext context, CounterCheckoutQuote quote)` |
| `ai.neargo.shop.beauty.policy.ServiceSubmissionAccess` | `CustomerParty manualParty(BtyWorkOrderContext context, ManualSourceFacts facts)` |
| `ai.neargo.shop.beauty.service.impl.ServiceSourceObservation` | `ManualObserved observeManual(ManualCanonical source, String actorNo)` |
| `ai.neargo.shop.beauty.service.impl.ServiceSourceObservation` | `BtySourceObservation manualHistory(ManualCanonical source)` |
| `ai.neargo.shop.beauty.service.impl.ServiceSourceObservation` | `BtySourceObservation invalidateManual(ManualCanonical source, String actorNo)` |
| `ai.neargo.shop.beauty.service.impl.ServiceSourceObservation` | `PlaceholderTicket claimManualPlaceholder(ManualCanonical source, String actorNo)` |
| `ai.neargo.shop.beauty.service.impl.ServiceSourceObservation` | `BtySourceObservation adoptManualPlaceholder(ManualCanonical source, ManualSourceFacts facts, PlaceholderTicket ticket, String actorNo)` |
| `ai.neargo.shop.beauty.service.impl.ServiceSourceBinding` | `Result bindManual(BtyServiceSubmission submission, Command command, ManualObserved observed, String actor)` |
| `ai.neargo.shop.beauty.mapper.BeautyMappers.SourceObservationMapper` | `int adoptManualPlaceholder(BtySourceObservation row, long expectedVersion, long epoch)` |
| `ai.neargo.shop.beauty.mapper.BeautyMappers.SourceObservationMapper` | `int completeManualObservation(BtySourceObservation row, long expectedVersion)` |
| `ai.neargo.shop.beauty.mapper.BeautyMappers.SourceAllocationGuardMapper` | `int allocateManualObservedQuantity(String entityNo, String storeNo, String guardNo, long expectedVersion, long sourceRevision, String sourceHash, long epoch, BigDecimal incrementQty, BigDecimal netQuantity, String actorNo, LocalDateTime updatedAt)` |
| `ai.neargo.shop.spi.marketing.CampaignPort` | `List<AppliedActivity> counterCandidates(CustomerParty party, List<MerchantAmount> amounts)` |
| `ai.neargo.shop.spi.marketing.CampaignPort` | `void requireCounterSelection(CustomerParty party, Discount discount)` |
| `ai.neargo.shop.spi.marketing.CampaignPort` | `Map<String,Long> counterFlashPrices(CustomerParty party, Collection<String> skus)` |
| `ai.neargo.shop.spi.marketing.CampaignPort` | `Map<String,GiftRule> counterGiftRules(CustomerParty party, Collection<String> skus)` |
| `ai.neargo.shop.spi.marketing.CampaignPort` | `void commitCounter(CustomerParty party, String orderNo, Discount discount)` |
| `ai.neargo.shop.promotion.service.ActivityPricingService` | `void rejectUnsupportedCounterChoices(List<MerchantAmount> amounts, Map<String,String> choices)` |
| `ai.neargo.shop.promotion.service.ActivityPricingService` | `void rejectUnsupportedCounterPriceEffects(Collection<String> goodsNos)` |
| `ai.neargo.shop.spi.member.GuestRegistrationPort` | `ClaimedCustomer resolveClaimedCustomer(Scope scope, String guestNo, String claimToken)` |
| `ai.neargo.shop.spi.product.StockDispositionPort` | `ReleaseView releaseOriginal(ReservationLookup request)` |
| `ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCustomerResolver` | `CounterCustomerResolution resolveBound(CheckoutScope scope, PrepareCounterReq request)` |
| `ai.neargo.shop.beauty.policy.ReceptionIdentity` | `String customerMember(String entity, String store, String memberNo)` |
| `ai.neargo.shop.beauty.mapper.BeautyMappers.WorkOrderMapper` | `BtyWorkOrder selectClaimedCandidate(String userNo, String workOrderNo)` |
| `ai.neargo.shop.beauty.mapper.BeautyMappers.WorkOrderMapper` | `BtyWorkOrder selectClaimed(String entityNo, String storeNo, String guestNo, String workOrderNo)` |
| `ai.neargo.shop.beauty.mapper.BeautyMappers.SourceAllocationGuardMapper` | `int refreshManualSnapshot(String entityNo, String guardNo, long expectedVersion, long expectedSourceRevision, long newSourceRevision, BigDecimal newSourceQty, String sourceHash, String actorNo, LocalDateTime updatedAt)` |
| `ai.neargo.shop.beauty.mapper.BeautyMappers.SourceObservationMapper` | `boolean hasAllocations(String entityNo, String sourceNo)` |
| `ai.neargo.shop.beauty.mapper.BeautyMappers.SourceObservationMapper` | `int claimManualObservation(BtySourceObservation row, long expectedVersion)` |
| `ai.neargo.shop.beauty.service.impl.ServiceSourceObservation.SourceDomainConflict` | `String reasonCode()` |
| `ai.neargo.shop.spi.marketing.CampaignPort` | `CounterCandidates counterCandidates(CustomerParty party, List<MerchantAmount> groups, Map<String,String> choices)` |
| `ai.neargo.shop.spi.marketing.CampaignPort` | `void requireCounterSelection(CustomerParty party, List<MerchantAmount> originalGroups, Map<String,String> originalChoices, CounterActivitySeal originalSeal, Discount originalSelected)` |
| `ai.neargo.shop.marketing.port.CampaignPortImpl` | `CounterCandidates evaluateCounter(CustomerParty party, List<MerchantAmount> groups, Map<String,String> choices, long ownerNowMillis)` |
| `ai.neargo.shop.promotion.service.ActivityPricingService` | `void rejectUnsupportedCounterChoices(List<MerchantAmount> groups, Map<String,String> choices, long ownerNowMillis)` |
| `ai.neargo.shop.promotion.service.ActivityPricingService` | `void rejectUnsupportedCounterPriceEffects(List<MerchantAmount> groups, long ownerNowMillis)` |
| `ai.neargo.shop.marketing.campaign.CounterCampaignFence` | `void lock(String tenant, List<String> entityNos)` |
| `ai.neargo.shop.marketing.campaign.CounterCampaignFence` | `void validateSchema()` |
| `ai.neargo.shop.marketing.campaign.mapper.CounterCampaignFenceMapper` | `Long lockShared(String tenant, String kind, String no)` |
| `ai.neargo.shop.marketing.campaign.mapper.CounterCampaignFenceMapper` | `int ensure(String tenant, String kind, String no)` |
| `ai.neargo.shop.trade.order.service.create.CounterEconomicCodec` | `CounterQuoteSnapshot readQuoteSnapshot(String pricingJson)` |
| `ai.neargo.shop.trade.order.service.create.CounterEconomicCodec` | `CheckoutEconomicSnapshot readQuote(String pricingJson)` |
| `ai.neargo.shop.trade.order.service.create.CounterEconomicCodec` | `CounterOrderPlan readPlan(String counterPlanJson)` |
| `ai.neargo.shop.trade.order.service.create.CounterEconomicCodec` | `String writeQuote(CounterQuoteSnapshot quote)` |
| `ai.neargo.shop.trade.order.service.create.CounterEconomicCodec` | `String writePlan(CounterOrderPlan plan)` |
| `ai.neargo.shop.trade.order.service.create.CounterEconomicCodec` | `String planHash(CounterOrderPlan plan)` |
| `ai.neargo.shop.trade.order.service.create.CounterEconomicCodec` | `boolean legacyZero(CheckoutEconomicSnapshot economics, CreateOrderCommand command)` |
| `ai.neargo.shop.trade.order.service.create.CounterEconomicCodec` | `void requireQuoteHash(CustomerParty party, List<CheckoutLineReq> originalLines, CheckoutEconomicSnapshot economics, String persistedQuoteHash)` |
| `ai.neargo.shop.trade.service.impl.ManualServiceAdmissionServiceImpl` | `private void requireAdmissionEconomics(OrdCheckoutOperation op, OrdOrder parent, OrdSubOrder child, OrdCheckoutQuote quote, CheckoutEconomicSnapshot economics, List<OrdCheckoutLine> sourceLines, List<OrdItem> originalItems)` |
| `ai.neargo.shop.trade.service.impl.ManualServiceAdmissionServiceImpl` | `private void requireDeclarationEconomics(OrdManualServiceAdmission declaration, Facts facts)` |
| `ai.neargo.shop.marketing.campaign.mapper.CounterCampaignFenceMapper` | `List<Map<String,Object>> columns()` |
| `ai.neargo.shop.marketing.campaign.mapper.CounterCampaignFenceMapper` | `List<Map<String,Object>> table()` |
| `ai.neargo.shop.marketing.campaign.mapper.CounterCampaignFenceMapper` | `List<Map<String,Object>> ownerIdentityBindings()` |
| `ai.neargo.shop.marketing.campaign.mapper.CounterCampaignFenceMapper` | `List<String> primaryKey()` |
| `ai.neargo.shop.marketing.campaign.mapper.CounterCampaignFenceMapper` | `List<Map<String,Object>> checks()` |
| `ai.neargo.shop.marketing.campaign.mapper.CounterCampaignFenceMapper` | `long hasCheckEnforcementColumn()` |
| `ai.neargo.shop.marketing.campaign.mapper.CounterCampaignFenceMapper` | `List<String> checkEnforcement()` |
| `ai.neargo.shop.marketing.campaign.mapper.CounterCampaignFenceMapper` | `List<Map<String,Object>> triggers()` |
| `ai.neargo.shop.marketing.campaign.CounterCampaignFence` | `void afterSingletonsInstantiated()` |
| `ai.neargo.shop.spi.marketing.CounterCampaignContract` | `static List<MerchantAmount> canonicalGroups(List<MerchantAmount> groups)` |
| `ai.neargo.shop.spi.marketing.CounterCampaignContract` | `static Map<String,String> normalizeChoices(List<MerchantAmount> groups, Map<String,String> choices)` |
| `ai.neargo.shop.spi.marketing.CounterCampaignContract` | `static List<CounterChoice> resolveChoices(List<MerchantAmount> groups, Map<String,String> choices)` |
| `ai.neargo.shop.spi.marketing.CounterCampaignContract` | `static CounterActivitySeal seal(ObjectMapper json, CustomerParty party, List<MerchantAmount> groups, Map<String,String> choices, List<CounterRule> selectedRules)` |
| `ai.neargo.shop.spi.marketing.CounterCampaignContract` | `static String sealDigest(ObjectMapper json, CounterActivitySeal seal)` |
| `ai.neargo.shop.spi.marketing.CounterCampaignContract` | `static void requireSeal(ObjectMapper json, CounterActivitySeal seal)` |
| `ai.neargo.shop.spi.marketing.CounterCampaignContract` | `static void requireSelection(ObjectMapper json, CounterActivitySeal seal, CustomerParty originalParty, List<MerchantAmount> originalGroups, Map<String,String> originalChoices, Discount originalSelected)` |
| `ai.neargo.shop.marketing.port.CampaignPortImpl` | `void lockCounter(List<MerchantAmount> groups)` |
| `ai.neargo.shop.marketing.port.CampaignPortImpl` | `void requireCounterEvidence(CustomerParty party, List<MerchantAmount> groups, Map<String,String> choices, CounterActivitySeal seal, Discount selected)` |
| `ai.neargo.shop.marketing.port.CampaignPortImpl` | `void requireSameCounter(CounterActivitySeal original, CounterCandidates current)` |
| `ai.neargo.shop.marketing.campaign.CounterCampaignRules` | `static List<String> goodsNos(ObjectMapper json, String raw)` |
| `ai.neargo.shop.marketing.campaign.CounterCampaignRules` | `static boolean active(MktCampaign row, long ownerNowMillis)` |
| `ai.neargo.shop.marketing.campaign.CounterCampaignRules` | `static boolean supportedEligible(MktCampaign row, List<String> goodsNos, MerchantAmount group, long ownerNowMillis)` |
| `ai.neargo.shop.marketing.campaign.CounterCampaignRules` | `static boolean scopedFullCutEffect(MktCampaign row, List<String> goodsNos, MerchantAmount group, long ownerNowMillis)` |
| `ai.neargo.shop.marketing.campaign.CounterCampaignRules` | `static boolean mandatoryPriceOrGiftEffect(MktCampaign row, List<String> goodsNos, MerchantAmount group, long ownerNowMillis)` |
| `ai.neargo.shop.marketing.campaign.CounterCampaignRules` | `static CounterRule selectedRule(MktCampaign row, List<String> goodsNos, MerchantAmount group, long ownerNowMillis)` |
| `ai.neargo.shop.marketing.campaign.CounterCampaignRules` | `static AppliedActivity candidate(MktCampaign row, List<String> goodsNos, MerchantAmount group, long ownerNowMillis)` |
| `ai.neargo.shop.marketing.campaign.CounterCampaignRules` | `static CounterActivitySeal seal(ObjectMapper json, CustomerParty party, List<MerchantAmount> groups, Map<String, String> choices, List<CounterRule> selectedRules)` |

实施顺序：M2a柜台购买/类型化顾客/原库存/认领隐私→M2b人工观察/绑定/账单→M2c既有商家FULL_CUT；原49任务/160API/118功能/41表目标计数保持，以下是另列共用增量计划，不是已注册接口。

有效报价固定prepare时的真实顾客身份；报价后认领不重写历史GUEST，新的报价查询会员当前事实。USER无真实所有者版本时counter identityRevision允许空，GUEST必须真实revision；旧C/M1哈希保持。

共用原报价/确认/订单/明细/来源行/creation intent/库存，不新增会员购买见证或美业金额账。STOCK_RESERVE_V1与STOCK_CONFIRM_V1区分，丢响应只查/恢复原号；CREATED重试不反向锁既有parent。

人工观察使用独立manual epoch/hash/verified pair。既有空UNKNOWN占位仅在权威来源核验、无金融证据/绑定/占用后同一行一次性adopt；V9条件SQL和旧写防护不抹除真实funded证据。

MANUAL_EXISTING使用ManualSourceFacts身份重载，支持M1 USER/C及counter USER/GUEST，不伪造CounterCheckoutQuote；保留旧V2提交，升级用例必须保留其active门禁场景。

账单明确MANUAL_DECLARATION/NOT_APPLICABLE_MANUAL，人工金额单列，既有金融核验不被改成READY。MP155与C订单认领读取由会员当前所有权核验，历史party不改。

初期优惠仅既有mkt_campaign FULL_CUT，复用原分摊及最终经济封印；账户/积分/卡券/pmt/FLASH/GIFT不支持时明确拒绝，不静默丢弃，不允许零价。

所有具体DTO/Controller/Service/Mapper、候选字段、旧写兼容、AC→方法和有限验证见[冻结实施设计](../../../.superpowers/sdd/beauty-backend/bm05-m2-counter-source-design.md)。服务接口独立声明，不能extends SPI Port。M3许可/M4占用/M5作业/M6履约/M7日历后续，Pay链条暂缓。

Task66源码修正（待代码审查，非Task65追认）：Guest.claim不创建门店会员，显式CLAIMED_GUEST selector经现有会员核验原token/CLAIMED/当前active user后解析为USER，无C会话、不自动建membership/person、不把guest号放member号；既有GUEST报价不改。完整约束与真实无会员用例见[Task66 brief](../../../.superpowers/sdd/beauty-backend/task-66-brief.md)末尾。

Task66源码补充（待代码审查，非Task65追认）：原号补偿新增StockDispositionPort.releaseOriginal(ReservationLookup):ReleaseView，按冻结authority调用原所有者；仅完整真实RELEASED/EXPIRED证明可终结。既有intent status/版本化JSON保存COMPENSATING/RELEASED及原证据，无新DDL；DUAL原预留/释放须复用既有shadow outbox生产。完整合同见Task66 brief末尾。

Task66实际嵌套调用修正（待代码审查）：HOST CounterCustomerOwnerBridge先验证完整COUNTER_PREPARE，再经实际会员Controller的既有token-first绑定生命周期调用不同会员目的，逐字核对原请求/返回并恢复外层证明。Core仅在真实bound USER/CLAIMED_GUEST场景消费该resolver，缺桥关闭；不修改generic auth、造会话或放宽目的，不新增HTTP/secret/角色。

Task66收银交接修正（待代码审查）：已确认原订单的place幂等键使用经核对的原operation/confirmation authority namespace；当前收银员仍独立通过全部现时权限/账号/门店及IO后复核。允许A待恢复→授权B继续原订单及双方重试，不改原接受人、顾客或凭据，不允许更换requestKey/payload；真实回归先取得业务断言RED。

Task66实际映射修正（代码已验收）：真实同事务诊断确认SQL空identity_revision被实体初值1覆盖；六个可空映射字段移除误导初值，旧普通创建入口显式保留revision1，counter USER保持真实空值、GUEST保持所有者revision。精确补充PaymentDraftFactory/OrderDraftFactory两个兼容修改点，不改已执行迁移、全局MyBatis配置或报价一致性门禁。

Task66实际库存诊断修正（代码已验收）：DUAL原所有者预留在空category缓存键异常后回滚，CategoryServiceImpl缓存仅排除null/blank，保留其既有null返回与库存默认策略；ReservationServiceImpl的HELD取消/过期必须在原inv事务内真实写回每行releasedQty=qty并核验balance/line/head各1行，失败全回滚、终态重试无重复释放，tracked revision沿原规则只增一次。保留严格ReleaseView，不修造旧终态数量、不改已执行DDL、库存路由或Outbox。

Task66独立审查I1修复：readCounterQuote在共用所有者边界必须验证完整COUNTER_READ/InternalCall<Scoped<OperationRef>>（当前身份、endpoint/action/target、完整scope/body），无bound时必须真实MERCHANT token/session校验；继续仅要求CHECKOUT_PREPARE和ORDER_VIEW及当前账号/门店，不追加CASH_RECEIVE/MANUAL_CONFIRM，不改变授权新收银员读取原订单的能力。读取前拒绝原始delegation、错误purpose/body/operation/scope。复用既有策略的完整绑定/真实token逻辑，保持其他购买/收款权限与既有哈希。

Task67 M2b准备增补：工单OpenWorkOrderReq追加可选memberNo，仅未指定预约开单；沿真实共用会员所有者识别USER，不造C凭证。旧构造、空字段序列化/指纹及预约身份语义不变。V9同步四个schema启动校验所有者；嵌入仍平台主数据源，第三schema仅隔离V8→V9测试。人工来源V3与MP155当前认领证明已实现并分批验证，仍待有效消融与独立审查；SOURCE_DOMAIN_CONFLICT为内部安全诊断，HTTP冲突结构不变，拒绝时不改资金来源行；canStart=false。

Task67实际Mapper签名对账：人工分配沿既有COUNT原子算法保留增量、当前净可用数量和actor/time审计参数（11参数），不是新数量账本；补录claim/refresh/hasAllocations及当前认领候选/精确读取方法，内部SOURCE_DOMAIN_CONFLICT原因不扩展HTTP字段。

Task67当前DTO：ServiceSubmissionVO(String submissionNo, String workOrderNo, String action, long revision, long originalScopeRevision, long targetScopeRevision, String status, String phase, String checkoutOperationNo, CheckoutPort.CheckoutQuote quote, String orderNo, String subOrderNo, SourceObservationVO source, List<SubmissionTaskVO> tasks, String recoveryAction, List<String> blockingReasons, CounterCheckoutQuote counterQuote)；旧构造保留，人工声明不映射支付核验。

Task67当前DTO：SourceObservationVO(String state, long observationEpoch, Long sourceRevision, String sourceHash, Long lastVerifiedRevision, String lastVerifiedHash, Instant observedAt, String reasonCode, boolean canStart, String admissionKind, String manualState, Long manualRevision, String manualHash)；旧构造保留，人工声明不映射支付核验。

Task67当前DTO：SourceBillVO(String checkoutOperationNo, String orderNo, String sourceNo, String currency, Long dueMinor, Long receivedMinor, Long refundedMinor, Long netReceivedMinor, Long outstandingMinor, String amountScope, List<ReceiptSummaryVO> receipts, List<SubmissionTaskVO> tasks, SourceObservationVO observation, boolean complete, List<String> blockingReasons, String admissionKind, String financialVerification, ManualReceiptSummaryVO manualReceipt, Long manualDeclaredMinor)；旧构造保留，人工声明不映射支付核验。

Task67当前DTO：CurrencyBillVO(String currency, Long dueMinor, Long receivedMinor, Long refundedMinor, Long netReceivedMinor, Long outstandingMinor, boolean complete, Long manualDeclaredMinor, String financialVerification)；旧构造保留，人工声明不映射支付核验。

Task67当前DTO：ManualReceiptSummaryVO(String declarationNo, String method, long declaredMinor, String currency, Instant receivedAt, Instant recordedAt, String provenance, String financialVerification)；旧构造保留，人工声明不映射支付核验。

Task68/C02补充方案：[选择、封存、候选展示、最终仲裁与收款核验](../../../.superpowers/sdd/beauty-backend/task-68-design-addendum.md)。原Task65冻结设计保留，冲突以本补充和主TDD§0.4为准；新增内部方法为待编码清单，不能视为已经实现。

choices只接受真实entity/store键，冲突别名BAD_REQUEST，foreign含NONE不可用；省略AUTO、NONE显式退出、指定合格非最优FULL_CUT均可。仅空goodsNos商家FULL_CUT；完整合格候选及最终NONE映射既有promotions，无新HTTP/DTO字段；其他机制依适用性明确拒绝。

CounterActivitySeal(schema=1,policy=LEGACY_FULL_CUT_V1,party,groups,requestedChoices,resolvedChoices,selectedRules,digest)保留真实version/值/范围/时效；CheckoutEconomicSnapshot仅追加属性级NON_NULL可空seal，精确保留旧18参数构造及旧hash。CounterQuoteSnapshot(schema,economic,promotions)封存在原pricing_json，展示不进经济hash/计划hash；读和原键重放不重新查询候选。

最终共用marketing平台S→排序entity S fence，READ_COMMITTED同主库；相关旧SQL/新增/版本/明细/平台报名由数据库触发器X协调。新V543及descriptor缺失或漂移则本能力不可用，普通零售/M1不随之失效；不使用选中行FOR UPDATE代替空集合防护。

旧schema0报价/待恢复计划只从原持久化操作与确认导出FROZEN_M2A_ZERO，保留旧零优惠/基价/hash/原库存；新促销不能重定价旧意图，当前基价/权限变化仍拒绝。golden必须来自真实旧序列化器，现仅固定旧源码/类，生成与对比后置统一B。

收款准入仅schema1已CONSUMED柜台报价/确认允许merchantMinor=discountMinor=真实子单商家分摊，platform/coupon/points/gift均无且due>0；核对父子金额及逐行paidShare。已售后不重新判断当前优惠有效性；M1/schema0保持零优惠，不改来源hash/库存协议，不调用Pay或普通金融已付事件。

迁移规划：共用marketing V543 owner fence；未创建M3由V543顺延V544、M4由V544顺延V545，美业V10不变。仅新增候选SQL，阶段A不执行迁移；实际开工再核号。

Task68A-I1修订：新fence tenant_no/scope_no的比较须与七项owner tenant_no及三项head/enrollment entity_no的有效列collation一致，结构校验逐列证明；scope_kind及不可变键仍字节精确。数据库默认不等于有效历史列，任一不一致关闭新能力。原binary tenant/scope设计由主TDD§2.47限定裁决覆盖，旧迁移不改、既有大小写/重音变体不静默忽略。

Task68A代码阶段完成：精确11文件共享marketing fence/Mapper/21触发器/descriptor/严格guard已提交5aa1769f2c1719cd86f0cdf4528699527827d7e6，I1锁身份等价修复限定复审PASS。实际24模块编译40.347s，core1067主/86测试源码、host413主/750测试源码；18个native、4个Node及lineage用例均未执行。V543仅新增源码/未执行，不代表Task68B报价或完整现金链条已完成。

Task68B限定设计复审PASS/APPROVED：共享纯SPI CounterCampaignContract统一groups/choices/seal形状与摘要，promotion/trade只依赖SPI，marketing不引用trade哈希，禁止新增私有跨域依赖或known-*豁免。真实SQL范围查询建立旧表身份变体→唯一规范group的归属；冻结原始rule事实及规范applied/share，消费后核验冻结映射，不再查询当前营销。B1精确9源码先实现协议/codec兼容基础，B2精确14源码完成owner报价与现金准入，C补有限runner；均只编码编译，运行统一B。

Task68B1精确9源码已编码编译和静态复审，18项测试仅编写/编译。R0 I1兼容夹具丢失原选择已限定修复；schema1计划保留原typed party/choices，旧baseline构造不变。真实writer/reader/owner/cash在B2接入，6份golden仍待B真实生成；本片不开放优惠业务或执行许可。

源码方法对账补充：共用方法增量登记100项，补齐实际owner组合的lockCounter/requireCounterEvidence/requireSameCounter及本域CounterCampaignRules八个纯静态方法。evaluateCounter先由既有HOST Router或legacy入口取得owner fence，单次时间贯穿组合；这些是现有拥有方内部方法，不新增HTTP或跨域私有依赖。规范groups/choices/seal摘要仍唯一由SPI提供，guard从codec+原/实际经济比较取得组/数量/总价证明，不再造重复counterGroups。

Task68B2精确14源码已编码编译与静态复审：候选/封存/原计划/现金准入串联真实共用拥有方；普通零售/M1读取及来源hash保持。R0三项测试问题经R3关闭，17项场景仅编写编译，非运行成功。别名实际SQL等价、双行总额不变分摊负例已补齐；prd_sku库存断言只对应PLATFORM，不能推导INVENTORY/DUAL已验证。真正旧Task66生产器四份产物与六份旧序列化golden仍由C整理/B执行，不能用假记录替代。


## 服务执行数量授权（M3，计划方法非已实现）

M3_DESIGN_STATIC_REVIEWED_A1_CODE_PHASE_RELEASED

主TDD§0.4/§2.48；设计R2 Spec PASS/Architecture APPROVED。当前仅发布A1精确23文件编码；A2–A5逐片核对后发布，全部测试及部署后置。

| Owner / class | Planned signature | Slice / state |
|---|---|---|
| `ServiceExecutionPort` | `Grant prepare(PrepareExecution command)` | A3 / PLANNED_NOT_IMPLEMENTED |
| `ServiceExecutionPort` | `Grant query(ExecutionLookup lookup)` | A3 / PLANNED_NOT_IMPLEMENTED |
| `ServiceExecutionPort` | `Grant queryOriginal(ExecutionLookup lookup)` | A3 / PLANNED_NOT_IMPLEMENTED |
| `ServiceExecutionSourcePort` | `StartIntent freeze(FreezeStart command)` | A2 / PLANNED_NOT_IMPLEMENTED |
| `ServiceExecutionSourcePort` | `StartIntent inspect(StartIntentLookup lookup)` | A2 / PLANNED_NOT_IMPLEMENTED |
| `ServiceExecutionSourcePort` | `StartIntent inspectOriginal(StartIntentLookup lookup)` | A2 / PLANNED_NOT_IMPLEMENTED |
| `ServiceExecutionContract` | `String bindingHash(ExecutionBinding binding)` | A1 / PLANNED_NOT_IMPLEMENTED |
| `ServiceTaskExecutionService` | `ExecutionPreparation prepareTask(FreezeStart command)` | A2 / PLANNED_NOT_IMPLEMENTED |
| `ServiceTaskExecutionService` | `ExecutionPreparation queryTask(StartIntentLookup lookup)` | A2 / PLANNED_NOT_IMPLEMENTED |
| `ServiceExecutionService` | `int requestSuspendLocked(ExecutionCoordinates coordinates, String reason)` | A3 / PLANNED_NOT_IMPLEMENTED |
| `ServiceExecutionService` | `int recoverPending(int limit)` | A5 / PLANNED_NOT_IMPLEMENTED |
| `ServiceTaskExecutionService` | `int recoverPending(int limit)` | A5 / PLANNED_NOT_IMPLEMENTED |
| `Beauty.ServiceExecutionFenceCoordinator` | `void onManualCompletionLocked(BtySourceObservation row, long appliedEpoch, String actor)` | A2 / PLANNED_NOT_IMPLEMENTED |
| `Core.ServiceExecutionServiceImpl` | `boolean applySourceSuspensionLocked(ExecutionBinding binding, SourceSuspension proof)` | A3 / PLANNED_NOT_IMPLEMENTED |
| `Core.ServiceExecutionSchemaVerifier` | `void requireAvailable()` | A1 / PLANNED_NOT_IMPLEMENTED |
| `Beauty.ServiceExecutionSchemaVerifier` | `Installation installation()` | A2 / PLANNED_NOT_IMPLEMENTED |

共用Core拥有订单行Q及永久(sourceLineNo,unitOrdinal)→task关联，美业不复制订单或数量账；任务仅支持精确COUNT1，1.000000合法，聚合及小数历史不猜测拆分。

永久单位与尝试分离，换键/取消重建/新绑定不得复用他人单位；reserved+performed+voided<=ordered，UNKNOWN保留单位与原号，M3仅attempt1/PREPARED/query/suspend。

START意图在美业本地提交后才调用Core；当前SELF/真实人员任职必须重验，原号机器查询不保存或重放人工身份。

实际completeManual证据异常在同一事务持久挂起；三处pre-owner刷新只产生PENDING，未变化有效结果恢复连续性，不能抹除真正的既有挂起。

Core锁父单→排序子单→来源行/单位/授权→操作/声明明细，真正失效写入同根协调；旧sourceHash/schema0/seal与柜台经济结果字节不改。

V1–V9基础28私表严格校验；V10候选新增1表共29，base readiness9与执行能力分开；仅已知扩展损坏隔离到受影响操作，基础或未知结构损坏仍拒绝启动。

Core/Beauty恢复Bean分别coreServiceExecutionRecoveryJob/beautyServiceExecutionRecoveryJob，声明和逻辑任务名亦不同，禁止开启bean覆盖。

M4真实占用/双向单位证明、生命周期2库存保留及头→行锁序是实际开工硬前置；M5人工参与和M6履约独立，M3不开放canStart、doneQty或支付链。

5条内部HTTP为A4待实现，未新增公开开工空壳；权限复用Task62，未新增权限码、默认授权、配置键或i18n词条；所有计划方法均非运行验收。

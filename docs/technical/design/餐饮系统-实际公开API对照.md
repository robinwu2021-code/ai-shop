# 餐饮系统实际公开API对照

核对日期：2026-10-25。源码基线：`d5ea1b066`。本册是该基线的静态合同快照，关联[功能与API评审](../TDD-餐饮功能与API实现对齐评审.md)，不代替运行注册、权限及门店验收。

本轮本地验收修复未增加公开映射：厨房活动队列status仅接受HELD/QUEUED/MAKING/READY；冲销恢复只处理原冲销；MVC参数校验失败统一返回10400，返回值校验失败仍10500。修复尚未提交部署，详见[验收报告](../TDD-餐饮功能验收与接口测试.md)。

范围为 food-web 的 B端、C端和平台端 Controller；不将 food-server 内部协议、共享商品/定价/支付/库存接口计入餐饮公开API数量。

共 59 个 Controller、252 条映射声明：B端226、C端23、平台端3。报表 query 的一个正则映射覆盖10种view，因此按其明确可选路径展开为261个功能路径；后者不是261个独立Handler。本次仅核对声明未发现重复HTTP方法与路径模板。

“直接委托”从 Controller 方法体及其Service/Port字段提取，只说明首跳；DTO校验、服务端默认值、鉴权及最终owner边界仍以对应源码为准。涉及完整调用链的人工审查结果见主评审。部分Handler先调用本类授权/参数方法，再调用表内委托。成功返回一般经过平台ApiResult统一包装；SSE和文件下载以原Controller类型为准。

所有当前门店、商家及操作者以认证上下文和原目录事实为准。不得根据参数声明，推断客户端可以自行指定租户或替代他人授权。C端无B端PreAuthorize注解不代表匿名公开，需遵循各接口的注册顾客、桌码会话或设备会话合同。

## BizBundleController

[BizBundleController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBundleController.java)；4条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[BundleServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/BundleServiceImpl.java)、[RemoteBundleService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteBundleService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `PUT /biz/x/food/bundles/{skuNo}/definition` | `replaceDefinition → BundleMutationVO` | `@PathVariable("skuNo") @NotBlank @Size(max=32) String skuNo; @Valid @RequestBody ReplaceBundleDefinitionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `BundleService.replaceDefinition` |
| `GET /biz/x/food/stores/{storeNo}/bundles` | `listBundles → PageData<BundleConfigVO>` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListBundlesQuery query` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `BundleService.listBundles` |
| `POST /biz/x/food/stores/{storeNo}/bundles` | `createBundle → CreateBundleResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateBundleReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `BundleService.createBundle` |
| `GET /biz/x/food/stores/{storeNo}/bundles/{configNo}` | `getBundle → GetBundleVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String number` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `BundleService.getBundle` |

## BizBusinessCloseController

[BizBusinessCloseController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessCloseController.java)；9条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[BusinessCloseServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/BusinessCloseServiceImpl.java)、[RemoteBusinessCloseService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteBusinessCloseService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /biz/x/food/business-days/{date}:open` | `open → DayVO` | `@PathVariable LocalDate date; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")` | `BusinessCloseService.open` |
| `POST /biz/x/food/closes:adjust` | `adjust → OperationVO` | `@Valid @RequestBody AdjustmentReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")` | `BusinessCloseService.adjust` |
| `POST /biz/x/food/closes:preview` | `preview → CloseVO` | `@Valid @RequestBody PreviewReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")` | `BusinessCloseService.preview` |
| `POST /biz/x/food/closes:sign` | `sign → CloseVO` | `@Valid @RequestBody SignReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")` | `BusinessCloseService.sign` |
| `GET /biz/x/food/closes/{closeNo}` | `get → CloseVO` | `@PathVariable @NotBlank @Size(max=32) String closeNo` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `BusinessCloseService.get` |
| `GET /biz/x/food/closes/{closeNo}/exceptions` | `exceptions → List<ExceptionVO>` | `@PathVariable @NotBlank @Size(max=32) String closeNo` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `BusinessCloseService.exceptions` |
| `POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:approve-carry` | `approveCarry → ExceptionVO` | `@PathVariable @NotBlank @Size(max=32) String closeNo; @PathVariable @NotBlank @Size(max=32) String exceptionNo; @Valid @RequestBody ApproveCarryReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")` | `BusinessCloseService.approveCarry` |
| `POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:propose-carry` | `proposeCarry → ExceptionVO` | `@PathVariable @NotBlank @Size(max=32) String closeNo; @PathVariable @NotBlank @Size(max=32) String exceptionNo; @Valid @RequestBody ProposeCarryReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")` | `BusinessCloseService.proposeCarry` |
| `GET /biz/x/food/reports/business-day` | `report → ReportVO` | `@RequestParam LocalDate businessDate; @RequestParam String currency` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `BusinessCloseService.report` |

## BizBusinessOperationsController

[BizBusinessOperationsController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessOperationsController.java)；43条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)`。

本地或远程实现：[BusinessOperationsServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/BusinessOperationsServiceImpl.java)、[RemoteBusinessOperationsService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteBusinessOperationsService.java)、[CheckCollaborationServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/CheckCollaborationServiceImpl.java)、[RemoteCheckCollaborationService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteCheckCollaborationService.java)、[FoodFeeSourceServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/FoodFeeSourceServiceImpl.java)、[RemoteFoodFeeSourceService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteFoodFeeSourceService.java)、[QueueNotificationServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/QueueNotificationServiceImpl.java)、[RemoteQueueNotificationService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteQueueNotificationService.java)、[ReservationPreorderServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ReservationPreorderServiceImpl.java)、[RemoteReservationPreorderService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteReservationPreorderService.java)、[ServiceFormServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ServiceFormServiceImpl.java)、[RemoteServiceFormService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteServiceFormService.java)、[ServiceSlotServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ServiceSlotServiceImpl.java)、[RemoteServiceSlotService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteServiceSlotService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /biz/x/food/business-operations/businessOperations/accept` | `businessOperationsAccept → BusinessOperationsContracts.HandoverVO` | `@Valid @RequestBody BusinessOperationsAcceptRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `BusinessOperationsService.accept` |
| `POST /biz/x/food/business-operations/businessOperations/cancel` | `businessOperationsCancel → BusinessOperationsContracts.HandoverVO` | `@Valid @RequestBody BusinessOperationsCancelRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `BusinessOperationsService.cancel` |
| `POST /biz/x/food/business-operations/businessOperations/cases` | `businessOperationsCases → List<BusinessOperationsContracts.CaseVO>` | `@Valid @RequestBody BusinessOperationsCasesRequest r` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `BusinessOperationsService.cases` |
| `POST /biz/x/food/business-operations/businessOperations/evidence` | `businessOperationsEvidence → BusinessOperationsContracts.Evidence` | `@Valid @RequestBody BusinessOperationsEvidenceRequest r` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `BusinessOperationsService.evidence` |
| `POST /biz/x/food/business-operations/businessOperations/handover` | `businessOperationsHandover → BusinessOperationsContracts.HandoverVO` | `@Valid @RequestBody BusinessOperationsHandoverRequest r` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `BusinessOperationsService.handover` |
| `POST /biz/x/food/business-operations/businessOperations/openCase` | `businessOperationsOpenCase → BusinessOperationsContracts.CaseVO` | `@Valid @RequestBody BusinessOperationsOpenCaseRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `BusinessOperationsService.openCase` |
| `POST /biz/x/food/business-operations/businessOperations/propose` | `businessOperationsPropose → BusinessOperationsContracts.HandoverVO` | `@Valid @RequestBody BusinessOperationsProposeRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `BusinessOperationsService.propose` |
| `POST /biz/x/food/business-operations/businessOperations/recoverCase` | `businessOperationsRecoverCase → BusinessOperationsContracts.CaseVO` | `@Valid @RequestBody BusinessOperationsRecoverCaseRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `BusinessOperationsService.recoverCase` |
| `POST /biz/x/food/business-operations/businessOperations/resolveCase` | `businessOperationsResolveCase → BusinessOperationsContracts.CaseVO` | `@Valid @RequestBody BusinessOperationsResolveCaseRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `BusinessOperationsService.resolveCase` |
| `POST /biz/x/food/business-operations/checkCollaboration/currentLineOwner` | `checkCollaborationCurrentLineOwner → String` | `@Valid @RequestBody CheckCollaborationCurrentLineOwnerRequest r` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `CheckCollaborationService.currentLineOwner` |
| `POST /biz/x/food/business-operations/checkCollaboration/freeze` | `checkCollaborationFreeze → CheckCollaborationContracts.FreezeVO` | `@Valid @RequestBody CheckCollaborationFreezeRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `CheckCollaborationService.freeze` |
| `POST /biz/x/food/business-operations/checkCollaboration/moveBatches` | `checkCollaborationMoveBatches → CheckCollaborationContracts.MoveVO` | `@Valid @RequestBody CheckCollaborationMoveBatchesRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `CheckCollaborationService.moveBatches` |
| `POST /biz/x/food/business-operations/checkCollaboration/moveLines` | `checkCollaborationMoveLines → CheckCollaborationContracts.LineMoveVO` | `@Valid @RequestBody CheckCollaborationMoveLinesRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `CheckCollaborationService.moveLines` |
| `POST /biz/x/food/business-operations/checkCollaboration/previewReceivable` | `checkCollaborationPreviewReceivable → CheckCollaborationContracts.ReceivablePreview` | `@Valid @RequestBody CheckCollaborationPreviewReceivableRequest r` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `CheckCollaborationService.previewReceivable` |
| `POST /biz/x/food/business-operations/checkCollaboration/release` | `checkCollaborationRelease → CheckCollaborationContracts.FreezeVO` | `@Valid @RequestBody CheckCollaborationReleaseRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `CheckCollaborationService.release` |
| `POST /biz/x/food/business-operations/checkCollaboration/releaseService` | `checkCollaborationReleaseService → OperationVO` | `@Valid @RequestBody CheckCollaborationReleaseServiceRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `CheckCollaborationService.releaseService` |
| `POST /biz/x/food/business-operations/checkCollaboration/sourceHash` | `checkCollaborationSourceHash → String` | `@Valid @RequestBody CheckCollaborationSourceHashRequest r` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `CheckCollaborationService.sourceHash` |
| `POST /biz/x/food/business-operations/foodFeeSource/bind` | `foodFeeSourceBind → FoodFeeSourceContracts.BindingVO` | `@Valid @RequestBody FoodFeeSourceBindRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_PUBLISH + "')")` | `FoodFeeSourceService.bind` |
| `POST /biz/x/food/business-operations/foodFeeSource/bindings` | `foodFeeSourceBindings → List<FoodFeeSourceContracts.BindingVO>` | `@Valid @RequestBody FoodFeeSourceBindingsRequest r` | `@PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_READ + "')")` | `FoodFeeSourceService.bindings` |
| `POST /biz/x/food/business-operations/queueNotification/list` | `queueNotificationList → List<QueueNotificationService.NoticeVO>` | `@Valid @RequestBody QueueNotificationListRequest r` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `QueueNotificationService.list` |
| `POST /biz/x/food/business-operations/queueNotification/recover` | `queueNotificationRecover → QueueNotificationService.NoticeVO` | `@Valid @RequestBody QueueNotificationRecoverRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `QueueNotificationService.recover` |
| `POST /biz/x/food/business-operations/reservationPreorder/cancel` | `reservationPreorderCancel → ReservationPreorderContracts.PreorderVO` | `@Valid @RequestBody ReservationPreorderCancelRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ReservationPreorderService.cancel` |
| `POST /biz/x/food/business-operations/reservationPreorder/convert` | `reservationPreorderConvert → DiningOrderBatchVO` | `@Valid @RequestBody ReservationPreorderConvertRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ReservationPreorderService.convert` |
| `POST /biz/x/food/business-operations/reservationPreorder/get` | `reservationPreorderGet → ReservationPreorderContracts.PreorderVO` | `@Valid @RequestBody ReservationPreorderGetRequest r` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `ReservationPreorderService.get` |
| `POST /biz/x/food/business-operations/reservationPreorder/prepareCart` | `reservationPreorderPrepareCart → CartResultVO` | `@Valid @RequestBody ReservationPreorderPrepareCartRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ReservationPreorderService.prepareCart` |
| `POST /biz/x/food/business-operations/reservationPreorder/save` | `reservationPreorderSave → ReservationPreorderContracts.PreorderVO` | `@Valid @RequestBody ReservationPreorderSaveRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ReservationPreorderService.save` |
| `POST /biz/x/food/business-operations/serviceForm/admit` | `serviceFormAdmit → ServiceFormContracts.AttendeeVO` | `@Valid @RequestBody ServiceFormAdmitRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ServiceFormService.admit` |
| `POST /biz/x/food/business-operations/serviceForm/attendees` | `serviceFormAttendees → List<ServiceFormContracts.AttendeeVO>` | `@Valid @RequestBody ServiceFormAttendeesRequest r` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `ServiceFormService.attendees` |
| `POST /biz/x/food/business-operations/serviceForm/bindBatch` | `serviceFormBindBatch → ServiceFormContracts.FormVO` | `@Valid @RequestBody ServiceFormBindBatchRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ServiceFormService.bindBatch` |
| `POST /biz/x/food/business-operations/serviceForm/book` | `serviceFormBook → ServiceFormContracts.FormVO` | `@Valid @RequestBody ServiceFormBookRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ServiceFormService.book` |
| `POST /biz/x/food/business-operations/serviceForm/cancel` | `serviceFormCancel → ServiceFormContracts.FormVO` | `@Valid @RequestBody ServiceFormCancelRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ServiceFormService.cancel` |
| `POST /biz/x/food/business-operations/serviceForm/enter` | `serviceFormEnter → ServiceFormContracts.FormVO` | `@Valid @RequestBody ServiceFormEnterRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ServiceFormService.enter` |
| `POST /biz/x/food/business-operations/serviceForm/exit` | `serviceFormExit → ServiceFormContracts.AttendeeVO` | `@Valid @RequestBody ServiceFormExitRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ServiceFormService.exit` |
| `POST /biz/x/food/business-operations/serviceForm/get` | `serviceFormGet → ServiceFormContracts.FormVO` | `@Valid @RequestBody ServiceFormGetRequest r` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `ServiceFormService.get` |
| `POST /biz/x/food/business-operations/serviceForm/leave` | `serviceFormLeave → ServiceFormContracts.FormVO` | `@Valid @RequestBody ServiceFormLeaveRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ServiceFormService.leave` |
| `POST /biz/x/food/business-operations/serviceForm/noShow` | `serviceFormNoShow → ServiceFormContracts.FormVO` | `@Valid @RequestBody ServiceFormNoShowRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ServiceFormService.noShow` |
| `POST /biz/x/food/business-operations/serviceForm/registerOrganization` | `serviceFormRegisterOrganization → ServiceFormContracts.OrganizationVO` | `@Valid @RequestBody ServiceFormRegisterOrganizationRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ServiceFormService.registerOrganization` |
| `POST /biz/x/food/business-operations/serviceForm/revise` | `serviceFormRevise → ServiceFormContracts.FormVO` | `@Valid @RequestBody ServiceFormReviseRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ServiceFormService.revise` |
| `POST /biz/x/food/business-operations/serviceSlot/create` | `serviceSlotCreate → ServiceSlotContracts.SlotVO` | `@Valid @RequestBody ServiceSlotCreateRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ServiceSlotService.create` |
| `POST /biz/x/food/business-operations/serviceSlot/dispatchDue` | `serviceSlotDispatchDue → boolean` | `@Valid @RequestBody ServiceSlotDispatchDueRequest r` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `ServiceSlotService.dispatchDue` |
| `POST /biz/x/food/business-operations/serviceSlot/get` | `serviceSlotGet → ServiceSlotContracts.SlotVO` | `@Valid @RequestBody ServiceSlotGetRequest r` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `ServiceSlotService.get` |
| `POST /biz/x/food/business-operations/serviceSlot/reserve` | `serviceSlotReserve → ServiceSlotContracts.ClaimVO` | `@Valid @RequestBody ServiceSlotReserveRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ServiceSlotService.reserve` |
| `POST /biz/x/food/business-operations/serviceSlot/transition` | `serviceSlotTransition → ServiceSlotContracts.ClaimVO` | `@Valid @RequestBody ServiceSlotTransitionRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `ServiceSlotService.transition` |

## BizBusinessReturnApprovalController

[BizBusinessReturnApprovalController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessReturnApprovalController.java)；3条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)`。

本地或远程实现：[BusinessReturnApprovalServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/BusinessReturnApprovalServiceImpl.java)、[RemoteBusinessReturnApprovalService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteBusinessReturnApprovalService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /biz/x/food/business-returns/{returnNo}:approve` | `approve → ApprovalVO` | `@PathVariable String returnNo; @RequestBody ApproveReq req; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")` | `BusinessReturnApprovalService.approve` |
| `POST /biz/x/food/business-returns/{returnNo}:link-replacement` | `link → ApprovalVO` | `@PathVariable String returnNo; @RequestBody ReplacementReq req; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")` | `BusinessReturnApprovalService.linkReplacement` |
| `GET /biz/x/food/business-returns/{returnNo}/approval` | `get → ApprovalVO` | `@PathVariable String returnNo` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `BusinessReturnApprovalService.get` |

## BizBusinessReturnController

[BizBusinessReturnController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessReturnController.java)；3条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[BusinessReturnServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/BusinessReturnServiceImpl.java)、[RemoteBusinessReturnService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteBusinessReturnService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /biz/x/food/batches/{batchNo}/business-returns` | `create → ReturnVO` | `@PathVariable @NotBlank @Size(max=32) String batchNo; @Valid @RequestBody CreateReq request; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')")` | `BusinessReturnService.create` |
| `GET /biz/x/food/business-returns/{returnNo}` | `get → ReturnVO` | `@PathVariable @NotBlank @Size(max=32) String returnNo` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `BusinessReturnService.get` |
| `POST /biz/x/food/business-returns/{returnNo}:cancel` | `cancel → ReturnVO` | `@PathVariable @NotBlank @Size(max=32) String returnNo; @Valid @RequestBody CancelReq request; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')")` | `BusinessReturnService.cancel` |

## BizBusinessShareController

[BizBusinessShareController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessShareController.java)；4条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[BusinessShareServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/BusinessShareServiceImpl.java)、[RemoteBusinessShareService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteBusinessShareService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /biz/x/food/checks/{checkNo}/business-share-plans` | `create → PlanVO` | `@PathVariable @NotBlank @Size(max=32) String checkNo; @Valid @RequestBody CreateReq request; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `BusinessShareService.create` |
| `GET /biz/x/food/checks/{checkNo}/business-share-plans/{planNo}` | `get → PlanVO` | `@PathVariable @NotBlank @Size(max=32) String checkNo; @PathVariable @NotBlank @Size(max=32) String planNo` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `BusinessShareService.get` |
| `POST /biz/x/food/checks/{checkNo}/business-share-plans/{planNo}:cancel` | `cancel → PlanVO` | `@PathVariable @NotBlank @Size(max=32) String checkNo; @PathVariable @NotBlank @Size(max=32) String planNo; @Valid @RequestBody CancelReq request; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `BusinessShareService.cancel` |
| `POST /biz/x/food/checks/{checkNo}/business-share-plans/{planNo}:confirm` | `confirm → PlanVO` | `@PathVariable @NotBlank @Size(max=32) String checkNo; @PathVariable @NotBlank @Size(max=32) String planNo; @Valid @RequestBody ConfirmReq request; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `BusinessShareService.confirm` |

## BizCartController

[BizCartController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCartController.java)；6条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[CheckCartServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/CheckCartServiceImpl.java)、[RemoteCheckCartService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteCheckCartService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/carts/{cartNo}` | `getCart → CartResultVO` | `@PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `CheckCartService.getCart` |
| `POST /biz/x/food/carts/{cartNo}:archive` | `archiveCart → OperationResultVO` | `@PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo; @Valid @RequestBody CartVersionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `CheckCartService.archiveCart` |
| `PUT /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}` | `replaceLine → CartVersionVO` | `@PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo; @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef; @Valid @RequestBody ReplaceCartLineReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `CheckCartService.replaceLine` |
| `POST /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive` | `archiveLine → CartVersionVO` | `@PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo; @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef; @Valid @RequestBody CartVersionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `CheckCartService.archiveLine` |
| `POST /biz/x/food/checks/{checkNo}/cart-sessions` | `authorizeSession → CartSessionGrantVO` | `@PathVariable("checkNo") @NotBlank @Size(max = 32) String checkNo; @Valid @RequestBody AuthorizeCartSessionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `CheckCartService.authorizeSession` |
| `POST /biz/x/food/stores/{storeNo}/carts` | `createCart → CartResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody CreateCartReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `CheckCartService.createCart` |

## BizCatalogController

[BizCatalogController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCatalogController.java)；1条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[CatalogServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/CatalogServiceImpl.java)、[RemoteCatalogService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteCatalogService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/stores/{storeNo}/catalog` | `read → CatalogVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute CatalogQuery query` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `CatalogService.read` |

## BizCheckActionController

[BizCheckActionController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCheckActionController.java)；1条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[CheckServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/CheckServiceImpl.java)、[RemoteCheckService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteCheckService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/checks/{checkNo}/actions` | `listActions → PageData<ActionLogVO>` | `@PathVariable("checkNo") @NotBlank @Size(max=32) String check; @Valid @ModelAttribute ListCheckActionsQuery query` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `CheckService.listActions` |

## BizCheckController

[BizCheckController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCheckController.java)；6条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[CheckServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/CheckServiceImpl.java)、[RemoteCheckService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteCheckService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/checks/{checkNo}` | `getCheck → CheckDetailVO` | `@PathVariable("checkNo") @NotBlank @Size(max=32) String check` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `CheckService.getCheck` |
| `POST /biz/x/food/checks/{checkNo}:clear` | `clearCheck → OperationVO` | `@PathVariable("checkNo") @NotBlank @Size(max=32) String check; @Valid @RequestBody ClearCheckReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `CheckService.clearCheck` |
| `POST /biz/x/food/checks/{checkNo}:combine-tables` | `combineTables → OperationResultVO` | `@PathVariable("checkNo") @NotBlank @Size(max=32) String check; @Valid @RequestBody CombineTablesReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `CheckService.combineTables` |
| `POST /biz/x/food/checks/{checkNo}:transfer` | `transferCheck → OperationResultVO` | `@PathVariable("checkNo") @NotBlank @Size(max=32) String check; @Valid @RequestBody TransferCheckReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `CheckService.transferCheck` |
| `GET /biz/x/food/stores/{storeNo}/checks` | `listChecks → PageData<CheckVO>` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String store; @Valid @ModelAttribute ListChecksQuery query` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `CheckService.listChecks` |
| `POST /biz/x/food/stores/{storeNo}/checks` | `openCheck → CheckResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String store; @Valid @RequestBody OpenCheckReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `CheckService.openCheck` |

## BizCheckSeatController

[BizCheckSeatController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCheckSeatController.java)；1条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[CheckServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/CheckServiceImpl.java)、[RemoteCheckService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteCheckService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `PUT /biz/x/food/checks/{checkNo}/seats` | `saveSeats → CheckResultVO` | `@PathVariable("checkNo") @NotBlank @Size(max=32) String check; @Valid @RequestBody SaveCheckSeatsReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `CheckService.saveSeats` |

## BizConfigRevisionController

[BizConfigRevisionController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizConfigRevisionController.java)；8条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[ConfigRevisionServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ConfigRevisionServiceImpl.java)、[RemoteConfigRevisionService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteConfigRevisionService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/config-revisions` | `listRevisions → PageData<ConfigRevisionVO>` | `@Valid @ModelAttribute ListConfigRevisionsQuery query` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `ConfigRevisionService.listRevisions` |
| `POST /biz/x/food/config-revisions/{revisionNo}:republish` | `republishRevision → PublishConfigResultVO` | `@PathVariable("revisionNo") @NotBlank @Size(max=32) String revisionNo; @Valid @RequestBody RepublishConfigReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `ConfigRevisionService.republishRevision` |
| `POST /biz/x/food/modifiers/{modifierNo}:publish` | `publishModifier → PublishConfigResultVO` | `@PathVariable("modifierNo") @NotBlank @Size(max=32) String modifierNo; @Valid @RequestBody PublishConfigReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `ConfigRevisionService.publishModifier` |
| `POST /biz/x/food/recipes/{recipeNo}:publish` | `publishRecipe → PublishConfigResultVO` | `@PathVariable("recipeNo") @NotBlank @Size(max=32) String recipeNo; @Valid @RequestBody PublishConfigReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `ConfigRevisionService.publishRecipe` |
| `POST /biz/x/food/stores/{storeNo}/bundles/{configNo}:publish` | `publishBundle → PublishConfigResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String configNo; @Valid @RequestBody PublishConfigReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `ConfigRevisionService.publishBundle` |
| `POST /biz/x/food/stores/{storeNo}/menus/{configNo}:publish` | `publishMenu → PublishConfigResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String configNo; @Valid @RequestBody PublishConfigReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `ConfigRevisionService.publishMenu` |
| `POST /biz/x/food/stores/{storeNo}/option-groups/{configNo}:publish` | `publishOptionGroup → PublishConfigResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String configNo; @Valid @RequestBody PublishConfigReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `ConfigRevisionService.publishOptionGroup` |
| `POST /biz/x/food/stores/{storeNo}/stations/{configNo}:publish` | `publishStation → PublishConfigResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String configNo; @Valid @RequestBody PublishConfigReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `ConfigRevisionService.publishStation` |

## BizCourseController

[BizCourseController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCourseController.java)；4条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[KitchenTaskServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/KitchenTaskServiceImpl.java)、[RemoteKitchenTaskService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteKitchenTaskService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/checks/{checkNo}/courses` | `courses → List<CourseVO>` | `@PathVariable @NotBlank @Size(max=32) String checkNo` | `@PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")` | `KitchenTaskService.courses` |
| `POST /biz/x/food/checks/{checkNo}/courses` | `create → CourseVO` | `@PathVariable @NotBlank @Size(max=32) String checkNo; @Valid @RequestBody CourseReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")` | `KitchenTaskService.createCourse` |
| `POST /biz/x/food/courses/{courseNo}:act` | `act → CourseVO` | `@PathVariable @NotBlank @Size(max=32) String courseNo; @Valid @RequestBody CourseActionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")` | `KitchenTaskService.actCourse` |
| `POST /biz/x/food/courses/{courseNo}:fire` | `fire → CourseVO` | `@PathVariable @NotBlank @Size(max=32) String courseNo; @Valid @RequestBody CourseFireReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")` | `KitchenTaskService.actCourse` |

## BizDiningBatchQueryController

[BizDiningBatchQueryController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningBatchQueryController.java)；1条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[DiningBatchQueryServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningBatchQueryServiceImpl.java)、[RemoteDiningBatchQueryService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningBatchQueryService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/stores/{storeNo}/batches` | `list → PageData<DiningBatchSummaryVO>` | `@PathVariable @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListDiningBatchesQuery query` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `DiningBatchQueryService.list` |

## BizDiningDeliveryController

[BizDiningDeliveryController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningDeliveryController.java)；2条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)`。

本地或远程实现：[DiningDeliveryServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningDeliveryServiceImpl.java)、[RemoteDiningDeliveryService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningDeliveryService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/carts/{cartNo}/delivery` | `get → DeliverySnapshot` | `@PathVariable @NotBlank @Size(max = 32) String cartNo` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `DiningDeliveryService.getStaff` |
| `PUT /biz/x/food/carts/{cartNo}/delivery` | `save → DeliverySnapshot` | `@PathVariable @NotBlank @Size(max = 32) String cartNo; @Valid @RequestBody SaveReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `DiningDeliveryService.saveStaff` |

## BizDiningHandoverController

[BizDiningHandoverController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningHandoverController.java)；3条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[KitchenTaskServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/KitchenTaskServiceImpl.java)、[RemoteKitchenTaskService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteKitchenTaskService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /biz/x/food/batches/{batchNo}:confirm-packing` | `confirmPacking → CustodyReceiptVO` | `@PathVariable @NotBlank @Size(max=32) String batchNo; @Valid @RequestBody PackingReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")` | `KitchenTaskService.confirmPacking` |
| `POST /biz/x/food/batches/{batchNo}:handover` | `handover → HandoverVO` | `@PathVariable @NotBlank @Size(max=32) String batchNo; @Valid @RequestBody HandoverReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `KitchenTaskService.handover` |
| `GET /biz/x/food/batches/{batchNo}/custody-receipts` | `custodyReceipts → List<CustodyReceiptVO>` | `@PathVariable @NotBlank @Size(max=32) String batchNo` | `@PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")` | `KitchenTaskService.custodyReceipts` |

## BizDiningIdentityController

[BizDiningIdentityController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningIdentityController.java)；6条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)`。

本地或远程实现：[DiningIdentityServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningIdentityServiceImpl.java)、[RemoteDiningIdentityService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningIdentityService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /biz/x/food/dining-devices` | `register → DeviceCredentialVO` | `@Valid @RequestBody RegisterDeviceReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")` | `DiningIdentityService.registerDevice` |
| `GET /biz/x/food/dining-devices/{deviceNo}` | `device → DeviceVO` | `@PathVariable @NotBlank @Size(max = 32) String deviceNo` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")` | `DiningIdentityService.device` |
| `POST /biz/x/food/dining-devices/{deviceNo}:revoke` | `revokeDevice → DeviceVO` | `@PathVariable @NotBlank @Size(max = 32) String deviceNo; @Valid @RequestBody VersionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")` | `DiningIdentityService.revokeDevice` |
| `POST /biz/x/food/parties` | `createParty → SessionCredentialVO` | `@Valid @RequestBody StaffPartyReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `DiningIdentityService.createStaffParty` |
| `GET /biz/x/food/parties/{sessionNo}` | `party → PartyVO` | `@PathVariable @NotBlank @Size(max = 32) String sessionNo` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `DiningIdentityService.party` |
| `POST /biz/x/food/parties/{sessionNo}:revoke` | `revokeParty → PartyVO` | `@PathVariable @NotBlank @Size(max = 32) String sessionNo; @Valid @RequestBody VersionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `DiningIdentityService.revokeParty` |

## BizDiningPickupVerificationController

[BizDiningPickupVerificationController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningPickupVerificationController.java)；2条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[DiningPickupVerificationServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningPickupVerificationServiceImpl.java)、[RemoteDiningPickupVerificationService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningPickupVerificationService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /biz/x/food/batches/{batchNo}:verify-pickup` | `verify → PickupVerificationVO` | `@PathVariable @NotBlank @Size(max=32) String batchNo; @Valid @RequestBody VerifyPickupReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `DiningPickupVerificationService.verify` |
| `GET /biz/x/food/batches/{batchNo}/pickup-verification` | `get → PickupVerificationVO` | `@PathVariable @NotBlank @Size(max=32) String batchNo; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `DiningPickupVerificationService.get` |

## BizDiningPricingController

[BizDiningPricingController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningPricingController.java)；3条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[DiningPricingServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningPricingServiceImpl.java)、[RemoteDiningPricingService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningPricingService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/quotes/{quoteNo}` | `get → QuoteVO` | `@PathVariable @NotBlank @Size(max = 32) String quoteNo; @RequestParam @NotBlank @Size(max = 32) String cartNo; @RequestParam @Min(0) long version` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `DiningPricingService.staffGet` |
| `POST /biz/x/food/quotes/{quoteNo}:confirm` | `confirm → QuoteVO` | `@PathVariable @NotBlank @Size(max = 32) String quoteNo; @Valid @RequestBody DiningQuoteConfirmReq body` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `DiningPricingService.staffConfirm` |
| `POST /biz/x/food/stores/{storeNo}/quotes` | `quote → QuoteVO` | `@PathVariable @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody DiningQuoteReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `DiningPricingService.staffQuote` |

## BizDiningReportController

[BizDiningReportController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningReportController.java)；4条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@ConditionalOnProperty(name="shop.report.food.enabled",havingValue="true")`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[DiningReportServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningReportServiceImpl.java)、[RemoteDiningReportService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningReportService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/operating-reports/{view:daily\|period\|trend\|payments\|discounts\|refunds\|profit\|rankings\|inventory\|health}` | `query → Result` | `@PathVariable String view; @RequestParam(required=false) LocalDate from; @RequestParam(required=false) LocalDate to; @RequestParam(required=false) LocalDate businessDate; @RequestParam(defaultValue="DAY") Granularity granularity; @RequestParam(defaultValue="BALANCE") InventoryView inventoryView; @RequestParam(required=false) String unit; @RequestParam(required=false) String currency; @RequestParam(required=false) List<String> storeNos; @RequestParam(required=false) Integer metricVersion; @RequestParam(required=false) Set<String> metricCodes; @RequestParam(defaultValue="1000") int limit` | `@PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")` | `DiningReportService.query` |
| `GET /biz/x/food/operating-reports/inventory/realtime` | `inventoryRealtime → ReportRealtimePort.Result` | `@RequestParam(defaultValue="1") int metricVersion` | `@PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"') and @perm.canBiz('"+BizPerms.STOCK+"')")` | `DiningReportService.realtime` |
| `GET /biz/x/food/operating-reports/metrics` | `metrics → List<MetricDefinition>` | `无外部输入；读取认证上下文` | `@PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")` | `DiningReportService.metrics` |
| `GET /biz/x/food/operating-reports/realtime` | `realtime → ReportRealtimePort.Result` | `@RequestParam(defaultValue="1") int metricVersion` | `@PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")` | `DiningReportService.realtime` |

## BizDiningReportExportController

[BizDiningReportExportController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningReportExportController.java)；3条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`；`@ConditionalOnProperty(name={"shop.report.food.enabled","shop.report.export.enabled"},havingValue="true")`。

本地或远程实现：[DiningReportServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningReportServiceImpl.java)、[RemoteDiningReportService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningReportService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /biz/x/food/operating-reports/exports` | `create → ReportExportPort.Task` | `@Valid @RequestBody DiningReportExportReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"') and @perm.canBiz('"+BizPerms.REPORT_EXPORT+"')")` | `DiningReportService.export` |
| `GET /biz/x/food/operating-reports/exports/{exportNo}` | `get → ReportExportPort.Task` | `@PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,32}") String exportNo` | `@PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"') and @perm.canBiz('"+BizPerms.REPORT_EXPORT+"')")` | `DiningReportService.getExport` |
| `GET /biz/x/food/operating-reports/exports/{exportNo}/download` | `download → ResponseEntity<ByteArrayResource>` | `@PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,32}") String exportNo` | `@PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"') and @perm.canBiz('"+BizPerms.REPORT_EXPORT+"')")` | `DiningReportService.downloadExport` |

## BizDiningReturnController

[BizDiningReturnController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningReturnController.java)；1条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[DiningReturnServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningReturnServiceImpl.java)、[RemoteDiningReturnService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningReturnService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/returns/{returnNo}` | `get → DiningReturnVO` | `@PathVariable @NotBlank @Size(max = 32) String returnNo` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `DiningReturnService.staffGet` |

## BizDishController

[BizDishController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDishController.java)；3条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[DishServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DishServiceImpl.java)、[RemoteDishService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDishService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/stores/{storeNo}/dishes` | `listDishes → PageData<DishCatalogItemVO>` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListDishesQuery query` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `DishService.listDishes` |
| `GET /biz/x/food/stores/{storeNo}/dishes/{goodsNo}` | `getDish → DishDetailResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("goodsNo") @NotBlank @Size(max=32) String goodsNo` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `DishService.getDish` |
| `PUT /biz/x/food/stores/{storeNo}/dishes/{goodsNo}` | `saveDish → DishResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("goodsNo") @NotBlank @Size(max=32) String goodsNo; @Valid @RequestBody SaveDishReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `DishService.saveDish` |

## BizFoodKdsController

[BizFoodKdsController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizFoodKdsController.java)；4条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)`。

本地或远程实现：[FoodKdsServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/FoodKdsServiceImpl.java)、[RemoteFoodKdsService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteFoodKdsService.java)、[FoodKdsStreamServiceImpl](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/service/impl/FoodKdsStreamServiceImpl.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/kitchen-events` | `replay → ReplayVO` | `@RequestParam @NotBlank @Size(max = 32) String subscriptionNo; @RequestParam(defaultValue = "0") @Min(0) long after; @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit` | `@PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")` | `FoodKdsService.replay` |
| `GET /biz/x/food/kitchen-stream` | `stream → SseEmitter` | `@RequestParam @NotBlank @Size(max = 32) String subscriptionNo; @RequestParam(defaultValue = "0") @Min(0) long after; @RequestHeader(value = "Last-Event-ID", required = false) String last` | `@PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")` | `FoodKdsStreamService.subscribe` |
| `POST /biz/x/food/kitchen-subscriptions` | `grant → SubscriptionVO` | `@Valid @RequestBody GrantReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")` | `FoodKdsService.grant` |
| `POST /biz/x/food/kitchen-subscriptions/{subscriptionNo}:revoke` | `revoke → SubscriptionVO` | `@PathVariable @NotBlank @Size(max = 32) String subscriptionNo; @Valid @RequestBody RevokeReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")` | `FoodKdsService.revoke` |

## BizFoodStationRuntimeController

[BizFoodStationRuntimeController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizFoodStationRuntimeController.java)；4条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)`。

本地或远程实现：[FoodStationRuntimeServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/FoodStationRuntimeServiceImpl.java)、[RemoteFoodStationRuntimeService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteFoodStationRuntimeService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /biz/x/food/kitchen-subscriptions/{subscriptionNo}/voice:acknowledge` | `acknowledge → CommandVO` | `@PathVariable @NotBlank @Size(max = 32) String subscriptionNo; @Valid @RequestBody VoiceReceipt req` | `@PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")` | `FoodStationRuntimeService.acknowledge` |
| `POST /biz/x/food/kitchen-subscriptions/{subscriptionNo}/voice:claim` | `claim → VoicePoll` | `@PathVariable @NotBlank @Size(max = 32) String subscriptionNo` | `@PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")` | `FoodStationRuntimeService.claim` |
| `GET /biz/x/food/stations/{stationNo}/runtime` | `state → StateVO` | `@PathVariable @NotBlank @Size(max = 32) String stationNo` | `@PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")` | `FoodStationRuntimeService.state` |
| `POST /biz/x/food/stations/{stationNo}/runtime` | `control → CommandVO` | `@PathVariable @NotBlank @Size(max = 32) String stationNo; @Valid @RequestBody ControlReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")` | `FoodStationRuntimeService.control` |

## BizKitchenReturnController

[BizKitchenReturnController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenReturnController.java)；2条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[KitchenReturnServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/KitchenReturnServiceImpl.java)、[RemoteKitchenReturnService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteKitchenReturnService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/returns/{returnNo}/kitchen` | `get → ReturnKitchenVO` | `@PathVariable @NotBlank @Size(max=32) String returnNo` | `@PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')")` | `KitchenReturnService.get` |
| `POST /biz/x/food/returns/{returnNo}/kitchen` | `handle → HandledVO` | `@PathVariable @NotBlank @Size(max=32) String returnNo; @Valid @RequestBody HandleReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')")` | `KitchenReturnService.handle` |

## BizKitchenTaskController

[BizKitchenTaskController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java)；11条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[KitchenTaskServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/KitchenTaskServiceImpl.java)、[RemoteKitchenTaskService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteKitchenTaskService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/stores/{storeNo}/kitchen/tasks` | `queue → PageData<TaskVO>` | `@PathVariable @NotBlank @Size(max=32) String storeNo; @RequestParam @NotBlank @Size(max=32) String stationNo; @RequestParam(required=false) String status; @RequestParam(defaultValue="1") int page; @RequestParam(defaultValue="20") int size` | `@PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")` | `KitchenTaskService.queue` |
| `POST /biz/x/food/tasks/{taskNo}:fire` | `fire → EventVO` | `@PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")` | `KitchenTaskService.act` |
| `POST /biz/x/food/tasks/{taskNo}:hold` | `hold → EventVO` | `@PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")` | `KitchenTaskService.act` |
| `POST /biz/x/food/tasks/{taskNo}:loss` | `loss → EventVO` | `@PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.STOCK+"')")` | `KitchenTaskService.act` |
| `POST /biz/x/food/tasks/{taskNo}:ready` | `ready → EventVO` | `@PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")` | `KitchenTaskService.act` |
| `POST /biz/x/food/tasks/{taskNo}:remake` | `remake → TicketVO` | `@PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody RemakeReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')")` | `KitchenTaskService.remake` |
| `POST /biz/x/food/tasks/{taskNo}:serve` | `serve → EventVO` | `@PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")` | `KitchenTaskService.act` |
| `POST /biz/x/food/tasks/{taskNo}:start` | `start → EventVO` | `@PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")` | `KitchenTaskService.act` |
| `POST /biz/x/food/tasks/{taskNo}:urge` | `urge → EventVO` | `@PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")` | `KitchenTaskService.act` |
| `POST /biz/x/food/tasks/{taskNo}:void` | `voidTask → EventVO` | `@PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')")` | `KitchenTaskService.act` |
| `GET /biz/x/food/tasks/{taskNo}/events` | `events → List<EventVO>` | `@PathVariable @NotBlank @Size(max=32) String taskNo` | `@PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")` | `KitchenTaskService.events` |

## BizMenuChannelBindingController

[BizMenuChannelBindingController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizMenuChannelBindingController.java)；4条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[MenuChannelBindingServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/MenuChannelBindingServiceImpl.java)、[RemoteMenuChannelBindingService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteMenuChannelBindingService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/menu-channel-bindings/{bindingNo}` | `getBinding → MenuChannelBindingVO` | `@PathVariable("bindingNo") @NotBlank @Size(max=32) String number` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `MenuChannelBindingService.getBinding` |
| `PUT /biz/x/food/menu-channel-bindings/{bindingNo}` | `updateBinding → MenuChannelBindingVO` | `@PathVariable("bindingNo") @NotBlank @Size(max=32) String number; @Valid @RequestBody UpdateMenuChannelBindingReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `MenuChannelBindingService.updateBinding` |
| `GET /biz/x/food/stores/{storeNo}/menu-channel-bindings` | `listBindings → PageData<MenuChannelBindingVO>` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @RequestParam(value="page",required=false) Integer page; @RequestParam(value="size",required=false) Integer size; @RequestParam(value="provider",required=false) String provider; @RequestParam(value="menuNo",required=false) String menuNo` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `MenuChannelBindingService.listBindings` |
| `POST /biz/x/food/stores/{storeNo}/menu-channel-bindings` | `createBinding → MenuChannelBindingVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateMenuChannelBindingReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `MenuChannelBindingService.createBinding` |

## BizMenuController

[BizMenuController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizMenuController.java)；6条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[MenuServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/MenuServiceImpl.java)、[RemoteMenuService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteMenuService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `PUT /biz/x/food/menus/{menuNo}` | `updateMenu → MenuResultVO` | `@PathVariable("menuNo") @NotBlank @Size(max=32) String menuNo; @Valid @RequestBody UpdateMenuReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `MenuService.updateMenu` |
| `POST /biz/x/food/menus/{menuNo}:disable` | `disableMenu → MenuResultVO` | `@PathVariable("menuNo") @NotBlank @Size(max=32) String number; @Valid @RequestBody DisableMenuReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `MenuService.disableMenu` |
| `PUT /biz/x/food/menus/{menuNo}/definition` | `replaceDefinition → Object` | `@PathVariable("menuNo") @NotBlank @Size(max=32) String number; @Valid @RequestBody MenuDefinitionCommand req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `MenuService.replaceContexts`; `MenuService.replaceSchedule`; `MenuService.replaceComposition` |
| `GET /biz/x/food/stores/{storeNo}/menus` | `listMenus → PageData<MenuVO>` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListMenusQuery query` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `MenuService.listMenus` |
| `POST /biz/x/food/stores/{storeNo}/menus` | `createMenu → CreateMenuResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateMenuReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `MenuService.createMenu` |
| `GET /biz/x/food/stores/{storeNo}/menus/{configNo}` | `getMenu → MenuConfigResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String number` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `MenuService.getMenu` |

## BizModifierController

[BizModifierController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizModifierController.java)；3条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[ModifierServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ModifierServiceImpl.java)、[RemoteModifierService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteModifierService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `PUT /biz/x/food/modifiers/{modifierNo}` | `updateModifier → ModifierMutationVO` | `@PathVariable("modifierNo") @NotBlank @Size(max=32) String modifierNo; @Valid @RequestBody UpdateModifierReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `ModifierService.updateModifier` |
| `GET /biz/x/food/stores/{storeNo}/modifiers` | `listModifiers → PageData<ModifierConfigVO>` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListModifiersQuery query` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `ModifierService.listModifiers` |
| `POST /biz/x/food/stores/{storeNo}/modifiers` | `createModifier → CreateModifierResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateModifierReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `ModifierService.createModifier` |

## BizOptionGroupController

[BizOptionGroupController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizOptionGroupController.java)；4条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[OptionGroupServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/OptionGroupServiceImpl.java)、[RemoteOptionGroupService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteOptionGroupService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `PUT /biz/x/food/option-groups/{groupNo}/items` | `replaceOptionGroupItems → OptionGroupMutationVO` | `@PathVariable("groupNo") @NotBlank @Size(max=32) String groupNo; @Valid @RequestBody ReplaceOptionGroupItemsReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `OptionGroupService.replaceOptionGroupItems` |
| `GET /biz/x/food/stores/{storeNo}/option-groups` | `listOptionGroups → PageData<OptionGroupConfigVO>` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListOptionGroupsQuery query` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `OptionGroupService.listOptionGroups` |
| `POST /biz/x/food/stores/{storeNo}/option-groups` | `createOptionGroup → CreateOptionGroupResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateOptionGroupReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `OptionGroupService.createOptionGroup` |
| `GET /biz/x/food/stores/{storeNo}/option-groups/{configNo}` | `getOptionGroup → OptionGroupResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String configNo` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `OptionGroupService.getOptionGroup` |

## BizOrderBatchController

[BizOrderBatchController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizOrderBatchController.java)；3条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[DiningOrderServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningOrderServiceImpl.java)、[RemoteDiningOrderService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningOrderService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/batches/{batchNo}` | `get → DiningOrderBatchVO` | `@PathVariable @NotBlank @Size(max = 32) String batchNo` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `DiningOrderService.staffGet` |
| `POST /biz/x/food/batches/{batchNo}:cancel` | `cancel → DiningOrderBatchVO` | `@PathVariable @NotBlank @Size(max = 32) String batchNo; @Valid @RequestBody CancelDiningOrderReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `DiningOrderService.staffCancel` |
| `POST /biz/x/food/stores/{storeNo}/batches` | `place → DiningOrderBatchVO` | `@PathVariable @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody CreateStaffDiningOrderReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `DiningOrderService.staffPlace` |

## BizPrintConfigurationController

[BizPrintConfigurationController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintConfigurationController.java)；3条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[PrintPortImpl](../../../backend/shop-core/src/main/java/ai/neargo/shop/printing/port/PrintPortImpl.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/print-configuration` | `configuration → Configuration` | `无外部输入；读取认证上下文` | `@PreAuthorize("@perm.canBiz('"+BizPerms.STORE_ADMIN+"')")` | `PrintPort.configuration` |
| `POST /biz/x/food/print-configuration/routes` | `route → RouteReq` | `@Valid @RequestBody RouteReq req` | `@PreAuthorize("@perm.canBiz('"+BizPerms.STORE_ADMIN+"')")` | `PrintPort.putRoute` |
| `POST /biz/x/food/print-configuration/templates` | `publish → TemplateReq` | `@Valid @RequestBody TemplateReq req` | `@PreAuthorize("@perm.canBiz('"+BizPerms.STORE_ADMIN+"')")` | `PrintPort.publishTemplate` |

## BizPrintDeviceController

[BizPrintDeviceController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintDeviceController.java)；5条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[PrintPortImpl](../../../backend/shop-core/src/main/java/ai/neargo/shop/printing/port/PrintPortImpl.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /biz/x/food/print-devices` | `bind → DeviceVO` | `@Valid @RequestBody DeviceReq req` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")` | `PrintPort.bindDevice` |
| `POST /biz/x/food/print-devices/{deviceNo}:acknowledge` | `acknowledge → JobVO` | `@PathVariable("deviceNo") @NotBlank @Size(max = 32) String no; @Valid @RequestBody Acknowledge req` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `PrintPort.acknowledge` |
| `POST /biz/x/food/print-devices/{deviceNo}:claim` | `claim → Claim` | `@PathVariable("deviceNo") @NotBlank @Size(max = 32) String no` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `PrintPort.claim` |
| `POST /biz/x/food/print-devices/{deviceNo}:heartbeat` | `heartbeat → DeviceVO` | `@PathVariable("deviceNo") @NotBlank @Size(max = 32) String no; @Valid @RequestBody HeartbeatReq req` | `@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")` | `PrintPort.heartbeat` |
| `POST /biz/x/food/print-devices/{deviceNo}:revoke` | `revoke → DeviceVO` | `@PathVariable("deviceNo") @NotBlank @Size(max = 32) String no; @Valid @RequestBody RevokeReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")` | `PrintPort.revokeDevice` |

## BizPrintJobController

[BizPrintJobController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintJobController.java)；5条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[FoodPrintServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/FoodPrintServiceImpl.java)、[RemoteFoodPrintService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteFoodPrintService.java)、[PrintPortImpl](../../../backend/shop-core/src/main/java/ai/neargo/shop/printing/port/PrintPortImpl.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/print-jobs` | `list → List<JobVO>` | `@RequestParam(value="afterJobNo",required=false) String after; @RequestParam(value="limit",defaultValue="20") int limit` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `PrintPort.list` |
| `POST /biz/x/food/print-jobs` | `enqueue → List<JobVO>` | `@Valid @RequestBody PrintDocumentReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `FoodPrintService.enqueue` |
| `GET /biz/x/food/print-jobs/{jobNo}` | `get → JobVO` | `@PathVariable("jobNo") @NotBlank @Size(max=32) String no` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `PrintPort.get` |
| `POST /biz/x/food/print-jobs/{jobNo}:reprint` | `reprint → JobVO` | `@PathVariable("jobNo") @NotBlank @Size(max=32) String no; @Valid @RequestBody ReprintReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `PrintPort.reprint` |
| `POST /biz/x/food/print-jobs/{jobNo}:retry` | `retry → JobVO` | `@PathVariable("jobNo") @NotBlank @Size(max=32) String no` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `PrintPort.retry` |

## BizProductionConsumptionController

[BizProductionConsumptionController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizProductionConsumptionController.java)；5条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[ProductionConsumptionServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ProductionConsumptionServiceImpl.java)、[RemoteProductionConsumptionService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteProductionConsumptionService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/production-events/{eventNo}` | `get → ConsumptionVO` | `@PathVariable @NotBlank @Size(max = 32) String eventNo` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `ProductionConsumptionService.get` |
| `POST /biz/x/food/production-events/{eventNo}:consume` | `consume → ConsumptionVO` | `@PathVariable @NotBlank @Size(max = 32) String eventNo` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")` | `ProductionConsumptionService.consume` |
| `POST /biz/x/food/production-events/{eventNo}:reverse` | `reverse → ConsumptionVO` | `@PathVariable @NotBlank @Size(max = 32) String eventNo; @RequestBody ProductionContracts.ReverseReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")` | `ProductionConsumptionService.reverse` |
| `POST /biz/x/food/production-tasks/{taskNo}:adjust` | `adjust → ConsumptionVO` | `@PathVariable @NotBlank @Size(max = 32) String taskNo; @RequestBody ProductionContracts.AdjustmentReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")` | `ProductionConsumptionService.adjust` |
| `GET /biz/x/food/stores/{storeNo}/production-consumptions` | `list → PageData<ConsumptionVO>` | `@PathVariable @NotBlank @Size(max = 32) String storeNo; @RequestParam LocalDate from; @RequestParam LocalDate to; @RequestParam(required = false) Integer page; @RequestParam(required = false) Integer size; @RequestParam(required = false) String skuNo; @RequestParam(required = false) String itemRef; @RequestParam(required = false) String status` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `ProductionConsumptionService.list` |

## BizQueueController

[BizQueueController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java)；9条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[QueueServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/QueueServiceImpl.java)、[RemoteQueueService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteQueueService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/queue-tickets` | `list → PageData<QueueVO>` | `@RequestParam LocalDate businessDate; @RequestParam(required=false) String status; @RequestParam(required=false) Integer page; @RequestParam(required=false) Integer size` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `QueueService.list` |
| `POST /biz/x/food/queue-tickets` | `issue → QueueVO` | `@Valid @RequestBody IssueReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `QueueService.issue` |
| `POST /biz/x/food/queue-tickets:call-next` | `callNext → QueueVO` | `@RequestParam LocalDate businessDate; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `QueueService.callNext` |
| `GET /biz/x/food/queue-tickets/{ticketNo}` | `get → QueueVO` | `@PathVariable @NotBlank @Size(max=32) String ticketNo` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `QueueService.get` |
| `POST /biz/x/food/queue-tickets/{ticketNo}:call` | `call → QueueVO` | `@PathVariable @NotBlank @Size(max=32) String ticketNo; @Valid @RequestBody ChangeReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `QueueService.change` |
| `POST /biz/x/food/queue-tickets/{ticketNo}:cancel` | `cancel → QueueVO` | `@PathVariable @NotBlank @Size(max=32) String ticketNo; @Valid @RequestBody ChangeReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `QueueService.change` |
| `POST /biz/x/food/queue-tickets/{ticketNo}:no-show` | `noShow → QueueVO` | `@PathVariable @NotBlank @Size(max=32) String ticketNo; @Valid @RequestBody ChangeReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `QueueService.change` |
| `POST /biz/x/food/queue-tickets/{ticketNo}:recover-seating` | `recover → QueueVO` | `@PathVariable @NotBlank @Size(max=32) String ticketNo` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `QueueService.recoverSeating` |
| `POST /biz/x/food/queue-tickets/{ticketNo}:seat` | `seat → QueueVO` | `@PathVariable @NotBlank @Size(max=32) String ticketNo; @Valid @RequestBody SeatQueueReq body` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `QueueService.seat` |

## BizRecipeController

[BizRecipeController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizRecipeController.java)；4条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[RecipeServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/RecipeServiceImpl.java)、[RemoteRecipeService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteRecipeService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/recipes/{recipeNo}` | `getRecipe → RecipeResultVO` | `@PathVariable("recipeNo") @NotBlank @Size(max=32) String number` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `RecipeService.getRecipe` |
| `PUT /biz/x/food/recipes/{recipeNo}/definition` | `replaceDefinition → RecipeResultVO` | `@PathVariable("recipeNo") @NotBlank @Size(max=32) String recipeNo; @Valid @RequestBody ReplaceRecipeDefinitionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `RecipeService.replaceDefinition` |
| `GET /biz/x/food/stores/{storeNo}/recipes` | `listRecipes → PageData<RecipeVO>` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListRecipesQuery query` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `RecipeService.listRecipes` |
| `POST /biz/x/food/stores/{storeNo}/recipes` | `createRecipe → RecipeResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateRecipeReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `RecipeService.createRecipe` |

## BizReservationController

[BizReservationController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizReservationController.java)；7条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[ReservationServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ReservationServiceImpl.java)、[RemoteReservationService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteReservationService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/reservations/{reservationNo}` | `get → DetailVO` | `@PathVariable @NotBlank @Size(max=32) String reservationNo` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `ReservationService.get` |
| `POST /biz/x/food/reservations/{reservationNo}:arrive` | `arrive → DetailVO` | `@PathVariable @NotBlank @Size(max=32) String reservationNo; @Valid @RequestBody ArriveReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `ReservationService.arrive` |
| `POST /biz/x/food/reservations/{reservationNo}:cancel` | `cancel → DetailVO` | `@PathVariable @NotBlank @Size(max=32) String reservationNo; @Valid @RequestBody TransitionReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `ReservationService.cancel` |
| `POST /biz/x/food/reservations/{reservationNo}:change` | `change → DetailVO` | `@PathVariable @NotBlank @Size(max=32) String reservationNo; @Valid @RequestBody ChangeReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `ReservationService.change` |
| `POST /biz/x/food/reservations/{reservationNo}:no-show` | `noShow → DetailVO` | `@PathVariable @NotBlank @Size(max=32) String reservationNo; @Valid @RequestBody TransitionReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `ReservationService.noShow` |
| `GET /biz/x/food/stores/{storeNo}/reservations` | `listReservations → PageData<ReservationVO>` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String store; @Valid @ModelAttribute ListReservationsQuery query` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `ReservationService.listReservations` |
| `POST /biz/x/food/stores/{storeNo}/reservations` | `create → DetailVO` | `@PathVariable @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateReq body; @RequestHeader("Idempotency-Key") String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `ReservationService.create` |

## BizSalesRestrictionController

[BizSalesRestrictionController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizSalesRestrictionController.java)；2条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[SalesRestrictionServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/SalesRestrictionServiceImpl.java)、[RemoteSalesRestrictionService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteSalesRestrictionService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/stores/{storeNo}/sales-restrictions` | `list → PageData<SalesRestrictionVO>` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute SalesRestrictionQuery query` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `SalesRestrictionService.list` |
| `POST /biz/x/food/stores/{storeNo}/sales-restrictions` | `manage → SalesRestrictionVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody SalesRestrictionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `SalesRestrictionService.manage` |

## BizServiceCalendarController

[BizServiceCalendarController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizServiceCalendarController.java)；1条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[StoreOperationServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/StoreOperationServiceImpl.java)、[RemoteStoreOperationService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteStoreOperationService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `PUT /biz/x/food/stores/{storeNo}/calendar/{localDate}` | `saveCalendar → OperationVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("localDate") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date; @Valid @RequestBody SaveCalendarReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")` | `StoreOperationService.saveCalendar` |

## BizSettlementController

[BizSettlementController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizSettlementController.java)；1条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[DiningCheckoutServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningCheckoutServiceImpl.java)、[RemoteDiningCheckoutService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningCheckoutService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/settlements/{settlementNo}` | `get → DiningSettlementVO` | `@PathVariable @NotBlank @Size(max = 32) String settlementNo` | `@PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")` | `DiningCheckoutService.staffGet` |

## BizStationController

[BizStationController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizStationController.java)；6条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[StationServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/StationServiceImpl.java)、[RemoteStationService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteStationService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/stores/{storeNo}/sku-routes/{skuNo}` | `getRoutes → StationRoutesVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("skuNo") @NotBlank @Size(max=32) String sku` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `StationService.getRoutes` |
| `PUT /biz/x/food/stores/{storeNo}/sku-routes/{skuNo}` | `replaceRoutes → OperationVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("skuNo") @NotBlank @Size(max=32) String sku; @Valid @RequestBody ReplaceStationRoutesReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `StationService.replaceRoutes` |
| `GET /biz/x/food/stores/{storeNo}/stations` | `listStations → PageData<StationVO>` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @RequestParam(value="page",required=false) Integer page; @RequestParam(value="size",required=false) Integer size` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `StationService.listStations` |
| `POST /biz/x/food/stores/{storeNo}/stations` | `createStation → StationMutationVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateStationReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `StationService.createStation` |
| `GET /biz/x/food/stores/{storeNo}/stations/{configNo}` | `getStation → GetStationVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String number` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `StationService.getStation` |
| `PUT /biz/x/food/stores/{storeNo}/stations/{configNo}` | `updateStation → StationMutationVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String number; @Valid @RequestBody UpdateStationReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")` | `StationService.updateStation` |

## BizStoreOperationPolicyController

[BizStoreOperationPolicyController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizStoreOperationPolicyController.java)；2条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[StoreOperationServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/StoreOperationServiceImpl.java)、[RemoteStoreOperationService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteStoreOperationService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /biz/x/food/stores/{storeNo}/policies` | `createPolicy → OperationVO` | `@PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody CreatePolicyReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")` | `StoreOperationService.createPolicy` |
| `GET /biz/x/food/stores/{storeNo}/policy` | `getPolicy → StoreOperationPolicyResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo; @Valid @ModelAttribute GetPolicyQuery query` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")` | `StoreOperationService.getPolicy` |

## BizTableAreaController

[BizTableAreaController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizTableAreaController.java)；4条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[TableServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/TableServiceImpl.java)、[RemoteTableService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteTableService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/stores/{storeNo}/table-areas` | `listTableAreas → PageData<TableAreaVO>` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListTableAreasQuery query` | `@PreAuthorize("@perm.canAnyBiz('" + BizPerms.STORE + "','" + BizPerms.ORDER_VIEW + "')")` | `TableService.listTableAreas` |
| `POST /biz/x/food/stores/{storeNo}/table-areas` | `createTableArea → TableAreaResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateTableAreaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")` | `TableService.createTableArea` |
| `PUT /biz/x/food/table-areas/{areaNo}` | `saveTableArea → TableAreaResultVO` | `@PathVariable("areaNo") @NotBlank @Size(max=32) String areaNo; @Valid @RequestBody SaveTableAreaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")` | `TableService.saveTableArea` |
| `POST /biz/x/food/table-areas/{areaNo}:set-status` | `setStatusTableArea → TableAreaResultVO` | `@PathVariable("areaNo") @NotBlank @Size(max=32) String areaNo; @Valid @RequestBody SetStatusTableAreaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")` | `TableService.setStatusTableArea` |

## BizTableController

[BizTableController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizTableController.java)；6条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[TableServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/TableServiceImpl.java)、[RemoteTableService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteTableService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/stores/{storeNo}/tables` | `listTables → PageData<TableVO>` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListTablesQuery query` | `@PreAuthorize("@perm.canAnyBiz('" + BizPerms.STORE + "','" + BizPerms.ORDER_VIEW + "')")` | `TableService.listTables` |
| `POST /biz/x/food/stores/{storeNo}/tables` | `createTable → TableResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateTableReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")` | `TableService.createTable` |
| `GET /biz/x/food/tables/{tableNo}` | `getTable → TableResultVO` | `@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo` | `@PreAuthorize("@perm.canAnyBiz('" + BizPerms.STORE + "','" + BizPerms.ORDER_VIEW + "')")` | `TableService.getTable` |
| `PUT /biz/x/food/tables/{tableNo}` | `saveTable → TableResultVO` | `@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo; @Valid @RequestBody SaveTableReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")` | `TableService.saveTable` |
| `POST /biz/x/food/tables/{tableNo}:archive` | `archiveTable → OperationResultVO` | `@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo; @Valid @RequestBody ArchiveTableReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")` | `TableService.archiveTable` |
| `POST /biz/x/food/tables/{tableNo}:set-status` | `setStatusTable → TableResultVO` | `@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo; @Valid @RequestBody SetStatusTableReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")` | `TableService.setStatusTable` |

## BizTableOperationController

[BizTableOperationController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizTableOperationController.java)；1条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[CheckServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/CheckServiceImpl.java)、[RemoteCheckService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteCheckService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /biz/x/food/table-operations/{operationNo}` | `getTableOperation → OperationResultVO` | `@PathVariable("operationNo") @NotBlank @Size(max=32) String number` | `@PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")` | `CheckService.getTableOperation` |

## BizTableSharingController

[BizTableSharingController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizTableSharingController.java)；1条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[CheckServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/CheckServiceImpl.java)、[RemoteCheckService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteCheckService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /biz/x/food/tables/{tableNo}:share` | `openSharedCheck → CheckResultVO` | `@PathVariable("tableNo") @NotBlank @Size(max=32) String table; @Valid @RequestBody OpenSharedCheckReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")` | `CheckService.openSharedCheck` |

## BizTableTokenController

[BizTableTokenController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizTableTokenController.java)；2条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[DiningSessionServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningSessionServiceImpl.java)、[RemoteDiningSessionService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningSessionService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /biz/x/food/tables/{tableNo}:revoke-token` | `revokeTableToken → OperationResultVO` | `@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo; @Valid @RequestBody RevokeTableTokenReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")` | `DiningSessionService.revokeTableToken` |
| `POST /biz/x/food/tables/{tableNo}:rotate-token` | `rotateTableToken → TableTokenVO` | `@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo; @Valid @RequestBody RotateTableTokenReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `@PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")` | `DiningSessionService.rotateTableToken` |

## MpCartController

[MpCartController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpCartController.java)；4条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[CheckCartServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/CheckCartServiceImpl.java)、[RemoteCheckCartService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteCheckCartService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /mp/x/food/carts/{cartNo}` | `customerGetCart → CartResultVO` | `@PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo; @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token` | `由顾客身份/会话合同校验；见Handler和Service` | `CheckCartService.customerGetCart` |
| `PUT /mp/x/food/carts/{cartNo}/lines/{sourceLineRef}` | `customerReplaceLine → CartVersionVO` | `@PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo; @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef; @Valid @RequestBody ReplaceCartLineReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token` | `由顾客身份/会话合同校验；见Handler和Service` | `CheckCartService.customerReplaceLine` |
| `POST /mp/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive` | `customerArchiveLine → CartVersionVO` | `@PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo; @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef; @Valid @RequestBody CartVersionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token` | `由顾客身份/会话合同校验；见Handler和Service` | `CheckCartService.customerArchiveLine` |
| `POST /mp/x/food/stores/{storeNo}/carts` | `customerCreateCart → CartResultVO` | `@PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody CreateCustomerCartReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token` | `由顾客身份/会话合同校验；见Handler和Service` | `CheckCartService.customerCreateCart` |

## MpCatalogController

[MpCatalogController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpCatalogController.java)；1条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[CatalogServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/CatalogServiceImpl.java)、[RemoteCatalogService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteCatalogService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /mp/x/food/catalog` | `read → CatalogVO` | `@RequestParam("storeNo") @NotBlank @Size(max=32) String store; @RequestParam("orderSource") @NotBlank String source; @RequestParam("fulfillment") @NotBlank String fulfillment; @RequestParam(value="sessionNo", required=false) @Size(max=32) String session; @RequestHeader(value="X-Food-Session", required=false) @Size(max=128) String access` | `由顾客身份/会话合同校验；见Handler和Service` | `CatalogService.publicCatalog`; `CatalogService.guest` |

## MpDiningDeliveryController

[MpDiningDeliveryController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpDiningDeliveryController.java)；2条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)`。

本地或远程实现：[DiningDeliveryServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningDeliveryServiceImpl.java)、[RemoteDiningDeliveryService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningDeliveryService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /mp/x/food/carts/{cartNo}/delivery` | `get → DeliverySnapshot` | `@PathVariable @NotBlank @Size(max = 32) String cartNo; @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String session; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningDeliveryService.getCustomer` |
| `PUT /mp/x/food/carts/{cartNo}/delivery` | `save → DeliverySnapshot` | `@PathVariable @NotBlank @Size(max = 32) String cartNo; @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String session; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token; @Valid @RequestBody SaveReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningDeliveryService.saveCustomer` |

## MpDiningDeviceSessionController

[MpDiningDeviceSessionController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpDiningDeviceSessionController.java)；1条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)`。

本地或远程实现：[DiningIdentityServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningIdentityServiceImpl.java)、[RemoteDiningIdentityService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningIdentityService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /mp/x/food/stores/{storeNo}/dining-device-sessions` | `create → SessionCredentialVO` | `@PathVariable @NotBlank @Size(max = 32) String storeNo; @RequestHeader("X-Food-Device") @NotBlank @Size(max = 32) String device; @RequestHeader("X-Food-Device-Credential") @NotBlank @Size(max = 128) String credential; @Valid @RequestBody DeviceSessionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningIdentityService.createDeviceSession`; `StoreDirectoryPort.cards` |

## MpDiningPricingController

[MpDiningPricingController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpDiningPricingController.java)；3条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[DiningPricingServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningPricingServiceImpl.java)、[RemoteDiningPricingService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningPricingService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /mp/x/food/quotes/{quoteNo}` | `get → QuoteVO` | `@PathVariable @NotBlank @Size(max = 32) String quoteNo; @RequestParam @NotBlank @Size(max = 32) String cartNo; @RequestParam @Min(0) long version; @RequestParam(required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningPricingService.customerGet` |
| `POST /mp/x/food/quotes/{quoteNo}:confirm` | `confirm → QuoteVO` | `@PathVariable @NotBlank @Size(max = 32) String quoteNo; @Valid @RequestBody DiningQuoteConfirmReq body; @RequestParam(required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningPricingService.customerConfirm` |
| `POST /mp/x/food/stores/{storeNo}/quotes` | `quote → QuoteVO` | `@PathVariable @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody DiningQuoteReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; @RequestParam(required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningPricingService.customerQuote` |

## MpDiningReturnController

[MpDiningReturnController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpDiningReturnController.java)；3条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[DiningReturnServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningReturnServiceImpl.java)、[RemoteDiningReturnService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningReturnService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /mp/x/food/returns/{returnNo}` | `get → DiningReturnVO` | `@PathVariable @NotBlank @Size(max = 32) String returnNo` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningReturnService.customerGet` |
| `POST /mp/x/food/returns/{returnNo}:cancel` | `cancel → DiningReturnVO` | `@PathVariable @NotBlank @Size(max = 32) String returnNo; @RequestBody(required = false) byte[] body` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningReturnService.customerCancel` |
| `POST /mp/x/food/stores/{storeNo}/returns` | `apply → DiningReturnVO` | `@PathVariable @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody DiningReturnApplyReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningReturnService.customerApply` |

## MpDiningSessionController

[MpDiningSessionController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpDiningSessionController.java)；2条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[DiningSessionServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningSessionServiceImpl.java)、[RemoteDiningSessionService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningSessionService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /mp/x/food/sessions` | `startSession → DiningSessionVO` | `@Valid @RequestBody StartDiningSessionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningSessionService.resolveTableToken`; `DiningSessionService.startSession`; `StoreDirectoryPort.cards` |
| `POST /mp/x/food/sessions/{sessionNo}:end` | `endSession → OperationResultVO` | `@PathVariable("sessionNo") @NotBlank @Size(max=32) String session; @RequestHeader("X-Food-Session") @NotBlank @Size(max=128) String access; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningSessionService.endSession` |

## MpOrderBatchController

[MpOrderBatchController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpOrderBatchController.java)；2条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[DiningOrderServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningOrderServiceImpl.java)、[RemoteDiningOrderService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningOrderService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /mp/x/food/batches/{batchNo}` | `get → DiningOrderBatchVO` | `@PathVariable @NotBlank @Size(max = 32) String batchNo; @RequestParam(required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningOrderService.customerGet` |
| `POST /mp/x/food/stores/{storeNo}/batches` | `place → DiningOrderBatchVO` | `@PathVariable @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody CreateDiningOrderReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; @RequestParam(required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningOrderService.customerPlace` |

## MpSettlementController

[MpSettlementController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpSettlementController.java)；5条映射声明。

类级装配条件：`@Profile("api")`；`@ConditionalOnFoodWebEnabled`；`@Conditional(PolicyEnabled.class)`。

本地或远程实现：[DiningCheckoutServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningCheckoutServiceImpl.java)、[RemoteDiningCheckoutService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningCheckoutService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `POST /mp/x/food/batches/{batchNo}/settlements` | `create → DiningSettlementVO` | `@PathVariable @NotBlank @Size(max = 32) String batchNo; @Valid @RequestBody CreateDiningSettlementReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningCheckoutService.customerCreate` |
| `GET /mp/x/food/settlements/{settlementNo}` | `get → DiningSettlementVO` | `@PathVariable @NotBlank @Size(max = 32) String settlementNo` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningCheckoutService.customerQuery` |
| `POST /mp/x/food/settlements/{settlementNo}:cancel` | `cancel → DiningSettlementVO` | `@PathVariable @NotBlank @Size(max = 32) String settlementNo; @Valid @RequestBody DiningSettlementVersionReq body` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningCheckoutService.customerCancel` |
| `POST /mp/x/food/settlements/{settlementNo}:finalize` | `finalizeCheckout → DiningSettlementVO` | `@PathVariable @NotBlank @Size(max = 32) String settlementNo` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningCheckoutService.customerFinalize` |
| `POST /mp/x/food/settlements/{settlementNo}:pay` | `pay → DiningSettlementVO` | `@PathVariable @NotBlank @Size(max = 32) String settlementNo; @Valid @RequestBody PayDiningSettlementReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key` | `由顾客身份/会话合同校验；见Handler和Service` | `DiningCheckoutService.customerPay` |

## OpsFoodSupportController

[OpsFoodSupportController](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/ops/OpsFoodSupportController.java)；3条映射声明。

类级装配条件：`@Profile("ops")`；`@ConditionalOnFoodWebEnabled`。

本地或远程实现：[FoodSupportServiceImpl](../../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/FoodSupportServiceImpl.java)、[RemoteFoodSupportService](../../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteFoodSupportService.java)。

| HTTP及路径 | Handler与返回类型 | 输入声明 | 权限声明 | 直接委托 |
|---|---|---|---|---|
| `GET /ops/x/food/support/cases` | `cases → List<CaseVO>` | `@RequestParam @NotBlank @Size(max = 32) String entityNo; @RequestParam @NotBlank @Size(max = 32) String storeNo; @RequestParam(required = false) String status` | `@PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")` | `FoodSupportService.cases` |
| `GET /ops/x/food/support/readiness` | `readiness → ReadinessVO` | `@RequestParam @NotBlank @Size(max = 32) String entityNo; @RequestParam @NotBlank @Size(max = 32) String storeNo` | `@PreAuthorize("@perm.can('" + Perms.SYSTEM_INDUSTRY_READ + "')")` | `FoodSupportService.readiness` |
| `GET /ops/x/food/support/source` | `evidence → Evidence` | `@RequestParam @NotBlank @Size(max = 32) String entityNo; @RequestParam @NotBlank @Size(max = 32) String storeNo; @RequestParam @NotBlank @Size(max = 16) String kind; @RequestParam @NotBlank @Size(max = 64) String sourceNo` | `@PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")` | `FoodSupportService.evidence` |

## 共享能力入口

下列接口属于原共享模块，不复制为餐饮Controller；不包含在上述252条声明中。

| 能力 | 实际公开入口 | 实现归属 |
|---|---|---|
| 商家税配置 | `GET/PUT /biz/pricing-tax-settings`；`POST /biz/pricing-tax-settings/reset` | BizPricingTaxSettingController → PricingTaxSettingService |
| 商品优惠及税分类 | `GET/PUT /biz/goods/{goodsNo}/skus/{skuNo}/pricing-policy` | BizGoodsPricingPolicyController → GoodsPricingPolicyService |
| 规则草稿与发布 | `/biz/pricing-configs` 下 initialize、draft、preview、publish、rollback、revisions、reset、upgrade | BizPricingConfigController → StorePricingConfigService |
| 人工调整 | `/biz/pricing-manual-policies/{currency}/{reasonCode}/{kind}`；`/biz/pricing-manual-adjustments` 及详情、approve、quote | BizPricingManualAdjustmentController → PricingManualAdjustmentService |
| 费用规则与调整 | `/biz/pricing/fee-rules/{ruleNo}`；`/biz/pricing/fee-scopes/{scopeNo}/adjustments` 及 `/{applicationNo}:apply` | 原费用owner；普通规则配置不代表餐饮已付金额被修改 |

支付、库存、打印账本和历史统计由原owner持有。具体内部协议和客户端适配参考原API设计当前源码清单及主评审，不向终端开放内部接口以绕过公开权限。

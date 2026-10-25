# 餐饮系统API设计

## 当前交付证据

记录日期：2026-10-25。生产已核验提交：`fef7f00982c978819645a527d26b819d343d8aab`（`fef7f0098`），餐饮集成部署；迁移基线 FOOD V25 / CORE V581 / INVENTORY V14来自既有迁移核验记录，本节不代表持续在线监测。

状态分开阅读：代码已交付；专项验证见验收矩阵；生产部署已核验；终端、真实设备和跨owner完整营业验收仍待完成。报表生产未启用，本批未发布支付资金能力。后文V15、H5/PICKUP及CODED_PENDING_VERIFICATION保留为历史切片/完整目标验收状态，不表示当前所有后端尚未编译测试，也不表示所有完整目标均已交付。

本地新增修复尚未提交或部署；待发布迁移 CORE V587 / INVENTORY V15。旧规则目录失配须显式新发布，不能静默放宽。见[本轮原生记录](../餐饮非支付原生链路验收记录.md)。

证据：[验收执行及发布记录](../TDD-餐饮功能验收与接口测试.md) · [逐项验收矩阵](./餐饮功能验收标准与证据矩阵.md) · [实际公开API](./餐饮系统-实际公开API对照.md) · [BIZ迁移及交付记录](../TDD-餐饮系统-BIZ01-12-交付验证.md)。

> 历史与源码发现口径： 源码核对：2026-10-25；云端基线 FOOD V15（83张业务表＋历史表＝84张），源码迁移目标 FOOD V25 / CORE V587 / INVENTORY V15（仅源码发现，不代表已部署）。交付核验状态：DEPLOYED_EMBEDDED_VERIFIED；独立适配/原生专项及发布进展见交付验证记录，历史 V1/73 仅为当时初始化记录。PLANNED 对象保留为目标，不代表实现。
> 由 `node scripts/gen-food-system-design.mjs` 生成；真源：[设计源](./schema/food-system-design.json)。实际 API、方法与迁移从原源码发现；不执行迁移。

## 当前源码实现清单（交付核验见记录）

> 状态：静态源码投影，编译、专项测试和部署证据见[本批交付验证](../TDD-餐饮系统-BIZ01-12-交付验证.md)。

云端基线为 FOOD V15，83 张业务表加 fnb_flyway_history 共84张；当前源码目标 FOOD V25 / CORE V581 / INVENTORY V14。实际三端注册数量以本节路由为准，未精确匹配的原ops专项目标继续保留规划。实际共享内部入口按原owner列出。本节仅静态读取源码，不表示测试通过、迁移已执行或已部署。后文目标规划及历史验收记录分别保留。

| 端 | HTTP | 实际路径 | Controller方法 | 参数声明 | 返回类型 | 权限声明（源码） |
|---|---|---|---|---|---|---|
| biz | GET | /biz/pricing/fee-rules/{ruleNo} | ai.neargo.shop.portal.biz.BizPricingFeeRuleController.get | @PathVariable String ruleNo; HttpServletRequest http | Rule | @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_READ + "')") |
| biz | POST | /biz/pricing/fee-rules/{ruleNo} | ai.neargo.shop.portal.biz.BizPricingFeeRuleController.save | @PathVariable String ruleNo; @Valid @RequestBody PricingFeeRuleService.SaveReq req; @RequestHeader("Idempotency-Key") String key; HttpServletRequest http | Rule | @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_PUBLISH + "')") |
| biz | GET | /biz/pricing/fee-scopes/{scopeNo}/adjustments | ai.neargo.shop.portal.biz.BizPricingFeeScopeController.pending | @PathVariable String scopeNo; HttpServletRequest http | List<PricingFeeScopePort.AdjustmentAuthority> | @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_READ + "')") |
| biz | POST | /biz/pricing/fee-scopes/{scopeNo}/adjustments/{applicationNo}:apply | ai.neargo.shop.portal.biz.BizPricingFeeScopeController.apply | @PathVariable String scopeNo; @PathVariable String applicationNo; @RequestBody ApplyReq req; @RequestHeader("Idempotency-Key") String key; HttpServletRequest http | IndustryOrderAmendmentPort.Receipt | @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_PUBLISH + "')") |
| biz | GET | /biz/x/food/batches/{batchNo} | ai.neargo.shop.food.api.biz.BizOrderBatchController.get | @PathVariable @NotBlank @Size(max = 32) String batchNo; HttpServletRequest request | DiningOrderBatchVO | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/batches/{batchNo}:cancel | ai.neargo.shop.food.api.biz.BizOrderBatchController.cancel | @PathVariable @NotBlank @Size(max = 32) String batchNo; @Valid @RequestBody CancelDiningOrderReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | DiningOrderBatchVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/batches/{batchNo}:confirm-packing | ai.neargo.shop.food.api.biz.BizDiningHandoverController.confirmPacking | @PathVariable @NotBlank @Size(max=32) String batchNo; @Valid @RequestBody PackingReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | CustodyReceiptVO | @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')") |
| biz | POST | /biz/x/food/batches/{batchNo}:handover | ai.neargo.shop.food.api.biz.BizDiningHandoverController.handover | @PathVariable @NotBlank @Size(max=32) String batchNo; @Valid @RequestBody HandoverReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | HandoverVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/batches/{batchNo}:verify-pickup | ai.neargo.shop.food.api.biz.BizDiningPickupVerificationController.verify | @PathVariable @NotBlank @Size(max=32) String batchNo; @Valid @RequestBody VerifyPickupReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | PickupVerificationVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/batches/{batchNo}/business-returns | ai.neargo.shop.food.api.biz.BizBusinessReturnController.create | @PathVariable @NotBlank @Size(max=32) String batchNo; @Valid @RequestBody CreateReq request; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest http | ReturnVO | @PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')") |
| biz | GET | /biz/x/food/batches/{batchNo}/custody-receipts | ai.neargo.shop.food.api.biz.BizDiningHandoverController.custodyReceipts | @PathVariable @NotBlank @Size(max=32) String batchNo; HttpServletRequest request | List<CustodyReceiptVO> | @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')") |
| biz | GET | /biz/x/food/batches/{batchNo}/pickup-verification | ai.neargo.shop.food.api.biz.BizDiningPickupVerificationController.get | @PathVariable @NotBlank @Size(max=32) String batchNo; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | PickupVerificationVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | PUT | /biz/x/food/bundles/{skuNo}/definition | ai.neargo.shop.food.api.biz.BizBundleController.replaceDefinition | @PathVariable("skuNo") @NotBlank @Size(max=32) String skuNo; @Valid @RequestBody ReplaceBundleDefinitionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | BundleMutationVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/business-days/{date}:open | ai.neargo.shop.food.api.biz.BizBusinessCloseController.open | @PathVariable LocalDate date; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | DayVO | @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')") |
| biz | POST | /biz/x/food/business-operations/businessOperations/accept | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.businessOperationsAccept | @Valid @RequestBody BusinessOperationsAcceptRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | BusinessOperationsContracts.HandoverVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/businessOperations/cancel | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.businessOperationsCancel | @Valid @RequestBody BusinessOperationsCancelRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | BusinessOperationsContracts.HandoverVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/businessOperations/cases | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.businessOperationsCases | @Valid @RequestBody BusinessOperationsCasesRequest r; HttpServletRequest http | List<BusinessOperationsContracts.CaseVO> | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/business-operations/businessOperations/evidence | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.businessOperationsEvidence | @Valid @RequestBody BusinessOperationsEvidenceRequest r; HttpServletRequest http | BusinessOperationsContracts.Evidence | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/business-operations/businessOperations/handover | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.businessOperationsHandover | @Valid @RequestBody BusinessOperationsHandoverRequest r; HttpServletRequest http | BusinessOperationsContracts.HandoverVO | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/business-operations/businessOperations/openCase | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.businessOperationsOpenCase | @Valid @RequestBody BusinessOperationsOpenCaseRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | BusinessOperationsContracts.CaseVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/businessOperations/propose | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.businessOperationsPropose | @Valid @RequestBody BusinessOperationsProposeRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | BusinessOperationsContracts.HandoverVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/businessOperations/recoverCase | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.businessOperationsRecoverCase | @Valid @RequestBody BusinessOperationsRecoverCaseRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | BusinessOperationsContracts.CaseVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/businessOperations/resolveCase | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.businessOperationsResolveCase | @Valid @RequestBody BusinessOperationsResolveCaseRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | BusinessOperationsContracts.CaseVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/checkCollaboration/currentLineOwner | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.checkCollaborationCurrentLineOwner | @Valid @RequestBody CheckCollaborationCurrentLineOwnerRequest r; HttpServletRequest http | String | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/business-operations/checkCollaboration/freeze | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.checkCollaborationFreeze | @Valid @RequestBody CheckCollaborationFreezeRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | CheckCollaborationContracts.FreezeVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/checkCollaboration/moveBatches | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.checkCollaborationMoveBatches | @Valid @RequestBody CheckCollaborationMoveBatchesRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | CheckCollaborationContracts.MoveVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/checkCollaboration/moveLines | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.checkCollaborationMoveLines | @Valid @RequestBody CheckCollaborationMoveLinesRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | CheckCollaborationContracts.LineMoveVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/checkCollaboration/previewReceivable | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.checkCollaborationPreviewReceivable | @Valid @RequestBody CheckCollaborationPreviewReceivableRequest r; HttpServletRequest http | CheckCollaborationContracts.ReceivablePreview | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/business-operations/checkCollaboration/release | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.checkCollaborationRelease | @Valid @RequestBody CheckCollaborationReleaseRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | CheckCollaborationContracts.FreezeVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/checkCollaboration/releaseService | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.checkCollaborationReleaseService | @Valid @RequestBody CheckCollaborationReleaseServiceRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | OperationVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/checkCollaboration/sourceHash | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.checkCollaborationSourceHash | @Valid @RequestBody CheckCollaborationSourceHashRequest r; HttpServletRequest http | String | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/business-operations/foodFeeSource/bind | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.foodFeeSourceBind | @Valid @RequestBody FoodFeeSourceBindRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | FoodFeeSourceContracts.BindingVO | @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_PUBLISH + "')") |
| biz | POST | /biz/x/food/business-operations/foodFeeSource/bindings | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.foodFeeSourceBindings | @Valid @RequestBody FoodFeeSourceBindingsRequest r; HttpServletRequest http | List<FoodFeeSourceContracts.BindingVO> | @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_READ + "')") |
| biz | POST | /biz/x/food/business-operations/queueNotification/list | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.queueNotificationList | @Valid @RequestBody QueueNotificationListRequest r; HttpServletRequest http | List<QueueNotificationService.NoticeVO> | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/business-operations/queueNotification/recover | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.queueNotificationRecover | @Valid @RequestBody QueueNotificationRecoverRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | QueueNotificationService.NoticeVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/reservationPreorder/cancel | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.reservationPreorderCancel | @Valid @RequestBody ReservationPreorderCancelRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | ReservationPreorderContracts.PreorderVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/reservationPreorder/convert | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.reservationPreorderConvert | @Valid @RequestBody ReservationPreorderConvertRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | DiningOrderBatchVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/reservationPreorder/get | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.reservationPreorderGet | @Valid @RequestBody ReservationPreorderGetRequest r; HttpServletRequest http | ReservationPreorderContracts.PreorderVO | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/business-operations/reservationPreorder/prepareCart | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.reservationPreorderPrepareCart | @Valid @RequestBody ReservationPreorderPrepareCartRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | CartResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/reservationPreorder/save | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.reservationPreorderSave | @Valid @RequestBody ReservationPreorderSaveRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | ReservationPreorderContracts.PreorderVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/serviceForm/admit | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceFormAdmit | @Valid @RequestBody ServiceFormAdmitRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | ServiceFormContracts.AttendeeVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/serviceForm/attendees | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceFormAttendees | @Valid @RequestBody ServiceFormAttendeesRequest r; HttpServletRequest http | List<ServiceFormContracts.AttendeeVO> | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/business-operations/serviceForm/bindBatch | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceFormBindBatch | @Valid @RequestBody ServiceFormBindBatchRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | ServiceFormContracts.FormVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/serviceForm/book | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceFormBook | @Valid @RequestBody ServiceFormBookRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | ServiceFormContracts.FormVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/serviceForm/cancel | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceFormCancel | @Valid @RequestBody ServiceFormCancelRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | ServiceFormContracts.FormVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/serviceForm/enter | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceFormEnter | @Valid @RequestBody ServiceFormEnterRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | ServiceFormContracts.FormVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/serviceForm/exit | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceFormExit | @Valid @RequestBody ServiceFormExitRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | ServiceFormContracts.AttendeeVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/serviceForm/get | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceFormGet | @Valid @RequestBody ServiceFormGetRequest r; HttpServletRequest http | ServiceFormContracts.FormVO | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/business-operations/serviceForm/leave | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceFormLeave | @Valid @RequestBody ServiceFormLeaveRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | ServiceFormContracts.FormVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/serviceForm/noShow | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceFormNoShow | @Valid @RequestBody ServiceFormNoShowRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | ServiceFormContracts.FormVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/serviceForm/registerOrganization | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceFormRegisterOrganization | @Valid @RequestBody ServiceFormRegisterOrganizationRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | ServiceFormContracts.OrganizationVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/serviceForm/revise | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceFormRevise | @Valid @RequestBody ServiceFormReviseRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | ServiceFormContracts.FormVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/serviceSlot/create | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceSlotCreate | @Valid @RequestBody ServiceSlotCreateRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | ServiceSlotContracts.SlotVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/serviceSlot/dispatchDue | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceSlotDispatchDue | @Valid @RequestBody ServiceSlotDispatchDueRequest r; HttpServletRequest http | boolean | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/business-operations/serviceSlot/get | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceSlotGet | @Valid @RequestBody ServiceSlotGetRequest r; HttpServletRequest http | ServiceSlotContracts.SlotVO | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/business-operations/serviceSlot/reserve | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceSlotReserve | @Valid @RequestBody ServiceSlotReserveRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | ServiceSlotContracts.ClaimVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/business-operations/serviceSlot/transition | ai.neargo.shop.food.api.biz.BizBusinessOperationsController.serviceSlotTransition | @Valid @RequestBody ServiceSlotTransitionRequest r; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest http | ServiceSlotContracts.ClaimVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | GET | /biz/x/food/business-returns/{returnNo} | ai.neargo.shop.food.api.biz.BizBusinessReturnController.get | @PathVariable @NotBlank @Size(max=32) String returnNo; HttpServletRequest http | ReturnVO | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | POST | /biz/x/food/business-returns/{returnNo}:approve | ai.neargo.shop.food.api.biz.BizBusinessReturnApprovalController.approve | @PathVariable String returnNo; @RequestBody ApproveReq req; @RequestHeader("Idempotency-Key") String key; HttpServletRequest http | ApprovalVO | @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')") |
| biz | POST | /biz/x/food/business-returns/{returnNo}:cancel | ai.neargo.shop.food.api.biz.BizBusinessReturnController.cancel | @PathVariable @NotBlank @Size(max=32) String returnNo; @Valid @RequestBody CancelReq request; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest http | ReturnVO | @PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')") |
| biz | POST | /biz/x/food/business-returns/{returnNo}:link-replacement | ai.neargo.shop.food.api.biz.BizBusinessReturnApprovalController.link | @PathVariable String returnNo; @RequestBody ReplacementReq req; @RequestHeader("Idempotency-Key") String key; HttpServletRequest http | ApprovalVO | @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')") |
| biz | GET | /biz/x/food/business-returns/{returnNo}/approval | ai.neargo.shop.food.api.biz.BizBusinessReturnApprovalController.get | @PathVariable String returnNo; HttpServletRequest http | ApprovalVO | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | GET | /biz/x/food/carts/{cartNo} | ai.neargo.shop.food.api.biz.BizCartController.getCart | @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo; HttpServletRequest request | CartResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/carts/{cartNo}:archive | ai.neargo.shop.food.api.biz.BizCartController.archiveCart | @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo; @Valid @RequestBody CartVersionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | OperationResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | GET | /biz/x/food/carts/{cartNo}/delivery | ai.neargo.shop.food.api.biz.BizDiningDeliveryController.get | @PathVariable @NotBlank @Size(max = 32) String cartNo; HttpServletRequest request | DeliverySnapshot | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | PUT | /biz/x/food/carts/{cartNo}/delivery | ai.neargo.shop.food.api.biz.BizDiningDeliveryController.save | @PathVariable @NotBlank @Size(max = 32) String cartNo; @Valid @RequestBody SaveReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | DeliverySnapshot | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | PUT | /biz/x/food/carts/{cartNo}/lines/{sourceLineRef} | ai.neargo.shop.food.api.biz.BizCartController.replaceLine | @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo; @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef; @Valid @RequestBody ReplaceCartLineReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | CartVersionVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive | ai.neargo.shop.food.api.biz.BizCartController.archiveLine | @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo; @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef; @Valid @RequestBody CartVersionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | CartVersionVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | GET | /biz/x/food/checks/{checkNo} | ai.neargo.shop.food.api.biz.BizCheckController.getCheck | @PathVariable("checkNo") @NotBlank @Size(max=32) String check; HttpServletRequest request | CheckDetailVO | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | POST | /biz/x/food/checks/{checkNo}:clear | ai.neargo.shop.food.api.biz.BizCheckController.clearCheck | @PathVariable("checkNo") @NotBlank @Size(max=32) String check; @Valid @RequestBody ClearCheckReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | OperationVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/checks/{checkNo}:combine-tables | ai.neargo.shop.food.api.biz.BizCheckController.combineTables | @PathVariable("checkNo") @NotBlank @Size(max=32) String check; @Valid @RequestBody CombineTablesReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | OperationResultVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/checks/{checkNo}:transfer | ai.neargo.shop.food.api.biz.BizCheckController.transferCheck | @PathVariable("checkNo") @NotBlank @Size(max=32) String check; @Valid @RequestBody TransferCheckReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | OperationResultVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | GET | /biz/x/food/checks/{checkNo}/actions | ai.neargo.shop.food.api.biz.BizCheckActionController.listActions | @PathVariable("checkNo") @NotBlank @Size(max=32) String check; @Valid @ModelAttribute ListCheckActionsQuery query; HttpServletRequest request | PageData<ActionLogVO> | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | POST | /biz/x/food/checks/{checkNo}/business-share-plans | ai.neargo.shop.food.api.biz.BizBusinessShareController.create | @PathVariable @NotBlank @Size(max=32) String checkNo; @Valid @RequestBody CreateReq request; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest http | PlanVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | GET | /biz/x/food/checks/{checkNo}/business-share-plans/{planNo} | ai.neargo.shop.food.api.biz.BizBusinessShareController.get | @PathVariable @NotBlank @Size(max=32) String checkNo; @PathVariable @NotBlank @Size(max=32) String planNo; HttpServletRequest http | PlanVO | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | POST | /biz/x/food/checks/{checkNo}/business-share-plans/{planNo}:cancel | ai.neargo.shop.food.api.biz.BizBusinessShareController.cancel | @PathVariable @NotBlank @Size(max=32) String checkNo; @PathVariable @NotBlank @Size(max=32) String planNo; @Valid @RequestBody CancelReq request; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest http | PlanVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/checks/{checkNo}/business-share-plans/{planNo}:confirm | ai.neargo.shop.food.api.biz.BizBusinessShareController.confirm | @PathVariable @NotBlank @Size(max=32) String checkNo; @PathVariable @NotBlank @Size(max=32) String planNo; @Valid @RequestBody ConfirmReq request; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest http | PlanVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/checks/{checkNo}/cart-sessions | ai.neargo.shop.food.api.biz.BizCartController.authorizeSession | @PathVariable("checkNo") @NotBlank @Size(max = 32) String checkNo; @Valid @RequestBody AuthorizeCartSessionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | CartSessionGrantVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | GET | /biz/x/food/checks/{checkNo}/courses | ai.neargo.shop.food.api.biz.BizCourseController.courses | @PathVariable @NotBlank @Size(max=32) String checkNo; HttpServletRequest request | List<CourseVO> | @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')") |
| biz | POST | /biz/x/food/checks/{checkNo}/courses | ai.neargo.shop.food.api.biz.BizCourseController.create | @PathVariable @NotBlank @Size(max=32) String checkNo; @Valid @RequestBody CourseReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | CourseVO | @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')") |
| biz | PUT | /biz/x/food/checks/{checkNo}/seats | ai.neargo.shop.food.api.biz.BizCheckSeatController.saveSeats | @PathVariable("checkNo") @NotBlank @Size(max=32) String check; @Valid @RequestBody SaveCheckSeatsReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | CheckResultVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/closes:adjust | ai.neargo.shop.food.api.biz.BizBusinessCloseController.adjust | @Valid @RequestBody AdjustmentReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | OperationVO | @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')") |
| biz | POST | /biz/x/food/closes:preview | ai.neargo.shop.food.api.biz.BizBusinessCloseController.preview | @Valid @RequestBody PreviewReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | CloseVO | @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')") |
| biz | POST | /biz/x/food/closes:sign | ai.neargo.shop.food.api.biz.BizBusinessCloseController.sign | @Valid @RequestBody SignReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | CloseVO | @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')") |
| biz | GET | /biz/x/food/closes/{closeNo} | ai.neargo.shop.food.api.biz.BizBusinessCloseController.get | @PathVariable @NotBlank @Size(max=32) String closeNo; HttpServletRequest request | CloseVO | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | GET | /biz/x/food/closes/{closeNo}/exceptions | ai.neargo.shop.food.api.biz.BizBusinessCloseController.exceptions | @PathVariable @NotBlank @Size(max=32) String closeNo; HttpServletRequest request | List<ExceptionVO> | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | POST | /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:approve-carry | ai.neargo.shop.food.api.biz.BizBusinessCloseController.approveCarry | @PathVariable @NotBlank @Size(max=32) String closeNo; @PathVariable @NotBlank @Size(max=32) String exceptionNo; @Valid @RequestBody ApproveCarryReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | ExceptionVO | @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')") |
| biz | POST | /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:propose-carry | ai.neargo.shop.food.api.biz.BizBusinessCloseController.proposeCarry | @PathVariable @NotBlank @Size(max=32) String closeNo; @PathVariable @NotBlank @Size(max=32) String exceptionNo; @Valid @RequestBody ProposeCarryReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | ExceptionVO | @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')") |
| biz | GET | /biz/x/food/config-revisions | ai.neargo.shop.food.api.biz.BizConfigRevisionController.listRevisions | @Valid @ModelAttribute ListConfigRevisionsQuery query; HttpServletRequest request | PageData<ConfigRevisionVO> | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/config-revisions/{revisionNo}:republish | ai.neargo.shop.food.api.biz.BizConfigRevisionController.republishRevision | @PathVariable("revisionNo") @NotBlank @Size(max=32) String revisionNo; @Valid @RequestBody RepublishConfigReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | PublishConfigResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/courses/{courseNo}:act | ai.neargo.shop.food.api.biz.BizCourseController.act | @PathVariable @NotBlank @Size(max=32) String courseNo; @Valid @RequestBody CourseActionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | CourseVO | @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')") |
| biz | POST | /biz/x/food/courses/{courseNo}:fire | ai.neargo.shop.food.api.biz.BizCourseController.fire | @PathVariable @NotBlank @Size(max=32) String courseNo; @Valid @RequestBody CourseFireReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | CourseVO | @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')") |
| biz | POST | /biz/x/food/dining-devices | ai.neargo.shop.food.api.biz.BizDiningIdentityController.register | @Valid @RequestBody RegisterDeviceReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | DeviceCredentialVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')") |
| biz | GET | /biz/x/food/dining-devices/{deviceNo} | ai.neargo.shop.food.api.biz.BizDiningIdentityController.device | @PathVariable @NotBlank @Size(max = 32) String deviceNo; HttpServletRequest request | DeviceVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')") |
| biz | POST | /biz/x/food/dining-devices/{deviceNo}:revoke | ai.neargo.shop.food.api.biz.BizDiningIdentityController.revokeDevice | @PathVariable @NotBlank @Size(max = 32) String deviceNo; @Valid @RequestBody VersionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | DeviceVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')") |
| biz | GET | /biz/x/food/kitchen-events | ai.neargo.shop.food.api.biz.BizFoodKdsController.replay | @RequestParam @NotBlank @Size(max = 32) String subscriptionNo; @RequestParam(defaultValue = "0") @Min(0) long after; @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit; HttpServletRequest request | ReplayVO | @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')") |
| biz | GET | /biz/x/food/kitchen-stream | ai.neargo.shop.food.api.biz.BizFoodKdsController.stream | @RequestParam @NotBlank @Size(max = 32) String subscriptionNo; @RequestParam(defaultValue = "0") @Min(0) long after; @RequestHeader(value = "Last-Event-ID", required = false) String last; HttpServletRequest request | SseEmitter | @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')") |
| biz | POST | /biz/x/food/kitchen-subscriptions | ai.neargo.shop.food.api.biz.BizFoodKdsController.grant | @Valid @RequestBody GrantReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | SubscriptionVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')") |
| biz | POST | /biz/x/food/kitchen-subscriptions/{subscriptionNo}:revoke | ai.neargo.shop.food.api.biz.BizFoodKdsController.revoke | @PathVariable @NotBlank @Size(max = 32) String subscriptionNo; @Valid @RequestBody RevokeReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | SubscriptionVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')") |
| biz | POST | /biz/x/food/kitchen-subscriptions/{subscriptionNo}/voice:acknowledge | ai.neargo.shop.food.api.biz.BizFoodStationRuntimeController.acknowledge | @PathVariable @NotBlank @Size(max = 32) String subscriptionNo; @Valid @RequestBody VoiceReceipt req; HttpServletRequest request | CommandVO | @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')") |
| biz | POST | /biz/x/food/kitchen-subscriptions/{subscriptionNo}/voice:claim | ai.neargo.shop.food.api.biz.BizFoodStationRuntimeController.claim | @PathVariable @NotBlank @Size(max = 32) String subscriptionNo; HttpServletRequest request | VoicePoll | @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')") |
| biz | GET | /biz/x/food/menu-channel-bindings/{bindingNo} | ai.neargo.shop.food.api.biz.BizMenuChannelBindingController.getBinding | @PathVariable("bindingNo") @NotBlank @Size(max=32) String number; HttpServletRequest request | MenuChannelBindingVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | PUT | /biz/x/food/menu-channel-bindings/{bindingNo} | ai.neargo.shop.food.api.biz.BizMenuChannelBindingController.updateBinding | @PathVariable("bindingNo") @NotBlank @Size(max=32) String number; @Valid @RequestBody UpdateMenuChannelBindingReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | MenuChannelBindingVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | PUT | /biz/x/food/menus/{menuNo} | ai.neargo.shop.food.api.biz.BizMenuController.updateMenu | @PathVariable("menuNo") @NotBlank @Size(max=32) String menuNo; @Valid @RequestBody UpdateMenuReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | MenuResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/menus/{menuNo}:disable | ai.neargo.shop.food.api.biz.BizMenuController.disableMenu | @PathVariable("menuNo") @NotBlank @Size(max=32) String number; @Valid @RequestBody DisableMenuReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | MenuResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | PUT | /biz/x/food/menus/{menuNo}/definition | ai.neargo.shop.food.api.biz.BizMenuController.replaceDefinition | @PathVariable("menuNo") @NotBlank @Size(max=32) String number; @Valid @RequestBody MenuDefinitionCommand req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | Object | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | PUT | /biz/x/food/modifiers/{modifierNo} | ai.neargo.shop.food.api.biz.BizModifierController.updateModifier | @PathVariable("modifierNo") @NotBlank @Size(max=32) String modifierNo; @Valid @RequestBody UpdateModifierReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | ModifierMutationVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/modifiers/{modifierNo}:publish | ai.neargo.shop.food.api.biz.BizConfigRevisionController.publishModifier | @PathVariable("modifierNo") @NotBlank @Size(max=32) String modifierNo; @Valid @RequestBody PublishConfigReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | PublishConfigResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/operating-reports/{view:daily\|period\|trend\|payments\|discounts\|refunds\|profit\|rankings\|inventory\|health} | ai.neargo.shop.food.api.biz.BizDiningReportController.query | @PathVariable String view; @RequestParam(required=false) LocalDate from; @RequestParam(required=false) LocalDate to; @RequestParam(required=false) LocalDate businessDate; @RequestParam(defaultValue="DAY") Granularity granularity; @RequestParam(defaultValue="BALANCE") InventoryView inventoryView; @RequestParam(required=false) String unit; @RequestParam(required=false) String currency; @RequestParam(required=false) List<String> storeNos; @RequestParam(required=false) Integer metricVersion; @RequestParam(required=false) Set<String> metricCodes; @RequestParam(defaultValue="1000") int limit; HttpServletRequest request | Result | @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')") |
| biz | POST | /biz/x/food/operating-reports/exports | ai.neargo.shop.food.api.biz.BizDiningReportExportController.create | @Valid @RequestBody DiningReportExportReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | ReportExportPort.Task | @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"') and @perm.canBiz('"+BizPerms.REPORT_EXPORT+"')") |
| biz | GET | /biz/x/food/operating-reports/exports/{exportNo} | ai.neargo.shop.food.api.biz.BizDiningReportExportController.get | @PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,32}") String exportNo; HttpServletRequest request | ReportExportPort.Task | @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"') and @perm.canBiz('"+BizPerms.REPORT_EXPORT+"')") |
| biz | GET | /biz/x/food/operating-reports/exports/{exportNo}/download | ai.neargo.shop.food.api.biz.BizDiningReportExportController.download | @PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,32}") String exportNo; HttpServletRequest request | ResponseEntity<ByteArrayResource> | @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"') and @perm.canBiz('"+BizPerms.REPORT_EXPORT+"')") |
| biz | GET | /biz/x/food/operating-reports/inventory/realtime | ai.neargo.shop.food.api.biz.BizDiningReportController.inventoryRealtime | @RequestParam(defaultValue="1") int metricVersion; HttpServletRequest request | ReportRealtimePort.Result | @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"') and @perm.canBiz('"+BizPerms.STOCK+"')") |
| biz | GET | /biz/x/food/operating-reports/metrics | ai.neargo.shop.food.api.biz.BizDiningReportController.metrics | HttpServletRequest request | List<MetricDefinition> | @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')") |
| biz | GET | /biz/x/food/operating-reports/realtime | ai.neargo.shop.food.api.biz.BizDiningReportController.realtime | @RequestParam(defaultValue="1") int metricVersion; HttpServletRequest request | ReportRealtimePort.Result | @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')") |
| biz | PUT | /biz/x/food/option-groups/{groupNo}/items | ai.neargo.shop.food.api.biz.BizOptionGroupController.replaceOptionGroupItems | @PathVariable("groupNo") @NotBlank @Size(max=32) String groupNo; @Valid @RequestBody ReplaceOptionGroupItemsReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | OptionGroupMutationVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/parties | ai.neargo.shop.food.api.biz.BizDiningIdentityController.createParty | @Valid @RequestBody StaffPartyReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | SessionCredentialVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | GET | /biz/x/food/parties/{sessionNo} | ai.neargo.shop.food.api.biz.BizDiningIdentityController.party | @PathVariable @NotBlank @Size(max = 32) String sessionNo; HttpServletRequest request | PartyVO | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/parties/{sessionNo}:revoke | ai.neargo.shop.food.api.biz.BizDiningIdentityController.revokeParty | @PathVariable @NotBlank @Size(max = 32) String sessionNo; @Valid @RequestBody VersionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | PartyVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | GET | /biz/x/food/print-configuration | ai.neargo.shop.food.api.biz.BizPrintConfigurationController.configuration | HttpServletRequest request | Configuration | @PreAuthorize("@perm.canBiz('"+BizPerms.STORE_ADMIN+"')") |
| biz | POST | /biz/x/food/print-configuration/routes | ai.neargo.shop.food.api.biz.BizPrintConfigurationController.route | @Valid @RequestBody RouteReq req; HttpServletRequest request | RouteReq | @PreAuthorize("@perm.canBiz('"+BizPerms.STORE_ADMIN+"')") |
| biz | POST | /biz/x/food/print-configuration/templates | ai.neargo.shop.food.api.biz.BizPrintConfigurationController.publish | @Valid @RequestBody TemplateReq req; HttpServletRequest request | TemplateReq | @PreAuthorize("@perm.canBiz('"+BizPerms.STORE_ADMIN+"')") |
| biz | POST | /biz/x/food/print-devices | ai.neargo.shop.food.api.biz.BizPrintDeviceController.bind | @Valid @RequestBody DeviceReq req; HttpServletRequest request | DeviceVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')") |
| biz | POST | /biz/x/food/print-devices/{deviceNo}:acknowledge | ai.neargo.shop.food.api.biz.BizPrintDeviceController.acknowledge | @PathVariable("deviceNo") @NotBlank @Size(max = 32) String no; @Valid @RequestBody Acknowledge req; HttpServletRequest request | JobVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/print-devices/{deviceNo}:claim | ai.neargo.shop.food.api.biz.BizPrintDeviceController.claim | @PathVariable("deviceNo") @NotBlank @Size(max = 32) String no; HttpServletRequest request | Claim | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/print-devices/{deviceNo}:heartbeat | ai.neargo.shop.food.api.biz.BizPrintDeviceController.heartbeat | @PathVariable("deviceNo") @NotBlank @Size(max = 32) String no; @Valid @RequestBody HeartbeatReq req; HttpServletRequest request | DeviceVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | POST | /biz/x/food/print-devices/{deviceNo}:revoke | ai.neargo.shop.food.api.biz.BizPrintDeviceController.revoke | @PathVariable("deviceNo") @NotBlank @Size(max = 32) String no; @Valid @RequestBody RevokeReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | DeviceVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')") |
| biz | GET | /biz/x/food/print-jobs | ai.neargo.shop.food.api.biz.BizPrintJobController.list | @RequestParam(value="afterJobNo",required=false) String after; @RequestParam(value="limit",defaultValue="20") int limit; HttpServletRequest request | List<JobVO> | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | POST | /biz/x/food/print-jobs | ai.neargo.shop.food.api.biz.BizPrintJobController.enqueue | @Valid @RequestBody PrintDocumentReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | List<JobVO> | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | GET | /biz/x/food/print-jobs/{jobNo} | ai.neargo.shop.food.api.biz.BizPrintJobController.get | @PathVariable("jobNo") @NotBlank @Size(max=32) String no; HttpServletRequest request | JobVO | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | POST | /biz/x/food/print-jobs/{jobNo}:reprint | ai.neargo.shop.food.api.biz.BizPrintJobController.reprint | @PathVariable("jobNo") @NotBlank @Size(max=32) String no; @Valid @RequestBody ReprintReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | JobVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/print-jobs/{jobNo}:retry | ai.neargo.shop.food.api.biz.BizPrintJobController.retry | @PathVariable("jobNo") @NotBlank @Size(max=32) String no; HttpServletRequest request | JobVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | GET | /biz/x/food/production-events/{eventNo} | ai.neargo.shop.food.api.biz.BizProductionConsumptionController.get | @PathVariable @NotBlank @Size(max = 32) String eventNo; HttpServletRequest request | ConsumptionVO | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/production-events/{eventNo}:consume | ai.neargo.shop.food.api.biz.BizProductionConsumptionController.consume | @PathVariable @NotBlank @Size(max = 32) String eventNo; HttpServletRequest request | ConsumptionVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')") |
| biz | POST | /biz/x/food/production-events/{eventNo}:reverse | ai.neargo.shop.food.api.biz.BizProductionConsumptionController.reverse | @PathVariable @NotBlank @Size(max = 32) String eventNo; @RequestBody ProductionContracts.ReverseReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | ConsumptionVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')") |
| biz | POST | /biz/x/food/production-tasks/{taskNo}:adjust | ai.neargo.shop.food.api.biz.BizProductionConsumptionController.adjust | @PathVariable @NotBlank @Size(max = 32) String taskNo; @RequestBody ProductionContracts.AdjustmentReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | ConsumptionVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')") |
| biz | GET | /biz/x/food/queue-tickets | ai.neargo.shop.food.api.biz.BizQueueController.list | @RequestParam LocalDate businessDate; @RequestParam(required=false) String status; @RequestParam(required=false) Integer page; @RequestParam(required=false) Integer size; HttpServletRequest request | PageData<QueueVO> | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | POST | /biz/x/food/queue-tickets | ai.neargo.shop.food.api.biz.BizQueueController.issue | @Valid @RequestBody IssueReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | QueueVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/queue-tickets:call-next | ai.neargo.shop.food.api.biz.BizQueueController.callNext | @RequestParam LocalDate businessDate; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | QueueVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | GET | /biz/x/food/queue-tickets/{ticketNo} | ai.neargo.shop.food.api.biz.BizQueueController.get | @PathVariable @NotBlank @Size(max=32) String ticketNo; HttpServletRequest request | QueueVO | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | POST | /biz/x/food/queue-tickets/{ticketNo}:call | ai.neargo.shop.food.api.biz.BizQueueController.call | @PathVariable @NotBlank @Size(max=32) String ticketNo; @Valid @RequestBody ChangeReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | QueueVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/queue-tickets/{ticketNo}:cancel | ai.neargo.shop.food.api.biz.BizQueueController.cancel | @PathVariable @NotBlank @Size(max=32) String ticketNo; @Valid @RequestBody ChangeReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | QueueVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/queue-tickets/{ticketNo}:no-show | ai.neargo.shop.food.api.biz.BizQueueController.noShow | @PathVariable @NotBlank @Size(max=32) String ticketNo; @Valid @RequestBody ChangeReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | QueueVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/queue-tickets/{ticketNo}:recover-seating | ai.neargo.shop.food.api.biz.BizQueueController.recover | @PathVariable @NotBlank @Size(max=32) String ticketNo; HttpServletRequest request | QueueVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/queue-tickets/{ticketNo}:seat | ai.neargo.shop.food.api.biz.BizQueueController.seat | @PathVariable @NotBlank @Size(max=32) String ticketNo; @Valid @RequestBody SeatQueueReq body; HttpServletRequest request | QueueVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | GET | /biz/x/food/quotes/{quoteNo} | ai.neargo.shop.food.api.biz.BizDiningPricingController.get | @PathVariable @NotBlank @Size(max = 32) String quoteNo; @RequestParam @NotBlank @Size(max = 32) String cartNo; @RequestParam @Min(0) long version; HttpServletRequest request | QuoteVO | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/quotes/{quoteNo}:confirm | ai.neargo.shop.food.api.biz.BizDiningPricingController.confirm | @PathVariable @NotBlank @Size(max = 32) String quoteNo; @Valid @RequestBody DiningQuoteConfirmReq body; HttpServletRequest request | QuoteVO | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | GET | /biz/x/food/recipes/{recipeNo} | ai.neargo.shop.food.api.biz.BizRecipeController.getRecipe | @PathVariable("recipeNo") @NotBlank @Size(max=32) String number; HttpServletRequest request | RecipeResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/recipes/{recipeNo}:publish | ai.neargo.shop.food.api.biz.BizConfigRevisionController.publishRecipe | @PathVariable("recipeNo") @NotBlank @Size(max=32) String recipeNo; @Valid @RequestBody PublishConfigReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | PublishConfigResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | PUT | /biz/x/food/recipes/{recipeNo}/definition | ai.neargo.shop.food.api.biz.BizRecipeController.replaceDefinition | @PathVariable("recipeNo") @NotBlank @Size(max=32) String recipeNo; @Valid @RequestBody ReplaceRecipeDefinitionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | RecipeResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/reports/business-day | ai.neargo.shop.food.api.biz.BizBusinessCloseController.report | @RequestParam LocalDate businessDate; @RequestParam String currency; HttpServletRequest request | ReportVO | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | GET | /biz/x/food/reservations/{reservationNo} | ai.neargo.shop.food.api.biz.BizReservationController.get | @PathVariable @NotBlank @Size(max=32) String reservationNo; HttpServletRequest request | DetailVO | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | POST | /biz/x/food/reservations/{reservationNo}:arrive | ai.neargo.shop.food.api.biz.BizReservationController.arrive | @PathVariable @NotBlank @Size(max=32) String reservationNo; @Valid @RequestBody ArriveReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | DetailVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/reservations/{reservationNo}:cancel | ai.neargo.shop.food.api.biz.BizReservationController.cancel | @PathVariable @NotBlank @Size(max=32) String reservationNo; @Valid @RequestBody TransitionReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | DetailVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/reservations/{reservationNo}:change | ai.neargo.shop.food.api.biz.BizReservationController.change | @PathVariable @NotBlank @Size(max=32) String reservationNo; @Valid @RequestBody ChangeReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | DetailVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/reservations/{reservationNo}:no-show | ai.neargo.shop.food.api.biz.BizReservationController.noShow | @PathVariable @NotBlank @Size(max=32) String reservationNo; @Valid @RequestBody TransitionReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | DetailVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | GET | /biz/x/food/returns/{returnNo} | ai.neargo.shop.food.api.biz.BizDiningReturnController.get | @PathVariable @NotBlank @Size(max = 32) String returnNo; HttpServletRequest request | DiningReturnVO | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | GET | /biz/x/food/returns/{returnNo}/kitchen | ai.neargo.shop.food.api.biz.BizKitchenReturnController.get | @PathVariable @NotBlank @Size(max=32) String returnNo; HttpServletRequest request | ReturnKitchenVO | @PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')") |
| biz | POST | /biz/x/food/returns/{returnNo}/kitchen | ai.neargo.shop.food.api.biz.BizKitchenReturnController.handle | @PathVariable @NotBlank @Size(max=32) String returnNo; @Valid @RequestBody HandleReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | HandledVO | @PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')") |
| biz | GET | /biz/x/food/settlements/{settlementNo} | ai.neargo.shop.food.api.biz.BizSettlementController.get | @PathVariable @NotBlank @Size(max = 32) String settlementNo; HttpServletRequest request | DiningSettlementVO | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | GET | /biz/x/food/stations/{stationNo}/runtime | ai.neargo.shop.food.api.biz.BizFoodStationRuntimeController.state | @PathVariable @NotBlank @Size(max = 32) String stationNo; HttpServletRequest request | StateVO | @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')") |
| biz | POST | /biz/x/food/stations/{stationNo}/runtime | ai.neargo.shop.food.api.biz.BizFoodStationRuntimeController.control | @PathVariable @NotBlank @Size(max = 32) String stationNo; @Valid @RequestBody ControlReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | CommandVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/batches | ai.neargo.shop.food.api.biz.BizDiningBatchQueryController.list | @PathVariable @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListDiningBatchesQuery query; HttpServletRequest request | PageData<DiningBatchSummaryVO> | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | POST | /biz/x/food/stores/{storeNo}/batches | ai.neargo.shop.food.api.biz.BizOrderBatchController.place | @PathVariable @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody CreateStaffDiningOrderReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | DiningOrderBatchVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/bundles | ai.neargo.shop.food.api.biz.BizBundleController.listBundles | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListBundlesQuery query; HttpServletRequest request | PageData<BundleConfigVO> | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/bundles | ai.neargo.shop.food.api.biz.BizBundleController.createBundle | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateBundleReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | CreateBundleResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/bundles/{configNo} | ai.neargo.shop.food.api.biz.BizBundleController.getBundle | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String number; HttpServletRequest request | GetBundleVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/bundles/{configNo}:publish | ai.neargo.shop.food.api.biz.BizConfigRevisionController.publishBundle | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String configNo; @Valid @RequestBody PublishConfigReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | PublishConfigResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | PUT | /biz/x/food/stores/{storeNo}/calendar/{localDate} | ai.neargo.shop.food.api.biz.BizServiceCalendarController.saveCalendar | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("localDate") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date; @Valid @RequestBody SaveCalendarReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | OperationVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/carts | ai.neargo.shop.food.api.biz.BizCartController.createCart | @PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody CreateCartReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | CartResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/catalog | ai.neargo.shop.food.api.biz.BizCatalogController.read | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute CatalogQuery query; HttpServletRequest request | CatalogVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/checks | ai.neargo.shop.food.api.biz.BizCheckController.listChecks | @PathVariable("storeNo") @NotBlank @Size(max=32) String store; @Valid @ModelAttribute ListChecksQuery query; HttpServletRequest request | PageData<CheckVO> | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | POST | /biz/x/food/stores/{storeNo}/checks | ai.neargo.shop.food.api.biz.BizCheckController.openCheck | @PathVariable("storeNo") @NotBlank @Size(max=32) String store; @Valid @RequestBody OpenCheckReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | CheckResultVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | GET | /biz/x/food/stores/{storeNo}/dishes | ai.neargo.shop.food.api.biz.BizDishController.listDishes | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListDishesQuery query; HttpServletRequest request | PageData<DishCatalogItemVO> | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/dishes/{goodsNo} | ai.neargo.shop.food.api.biz.BizDishController.getDish | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("goodsNo") @NotBlank @Size(max=32) String goodsNo; HttpServletRequest request | DishDetailResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | PUT | /biz/x/food/stores/{storeNo}/dishes/{goodsNo} | ai.neargo.shop.food.api.biz.BizDishController.saveDish | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("goodsNo") @NotBlank @Size(max=32) String goodsNo; @Valid @RequestBody SaveDishReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | DishResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/kitchen/tasks | ai.neargo.shop.food.api.biz.BizKitchenTaskController.queue | @PathVariable @NotBlank @Size(max=32) String storeNo; @RequestParam @NotBlank @Size(max=32) String stationNo; @RequestParam(required=false) String status; @RequestParam(defaultValue="1") int page; @RequestParam(defaultValue="20") int size; HttpServletRequest request | PageData<TaskVO> | @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')") |
| biz | GET | /biz/x/food/stores/{storeNo}/menu-channel-bindings | ai.neargo.shop.food.api.biz.BizMenuChannelBindingController.listBindings | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @RequestParam(value="page",required=false) Integer page; @RequestParam(value="size",required=false) Integer size; @RequestParam(value="provider",required=false) String provider; @RequestParam(value="menuNo",required=false) String menuNo; HttpServletRequest request | PageData<MenuChannelBindingVO> | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/menu-channel-bindings | ai.neargo.shop.food.api.biz.BizMenuChannelBindingController.createBinding | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateMenuChannelBindingReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | MenuChannelBindingVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/menus | ai.neargo.shop.food.api.biz.BizMenuController.listMenus | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListMenusQuery query; HttpServletRequest request | PageData<MenuVO> | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/menus | ai.neargo.shop.food.api.biz.BizMenuController.createMenu | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateMenuReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | CreateMenuResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/menus/{configNo} | ai.neargo.shop.food.api.biz.BizMenuController.getMenu | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String number; HttpServletRequest request | MenuConfigResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/menus/{configNo}:publish | ai.neargo.shop.food.api.biz.BizConfigRevisionController.publishMenu | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String configNo; @Valid @RequestBody PublishConfigReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | PublishConfigResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/modifiers | ai.neargo.shop.food.api.biz.BizModifierController.listModifiers | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListModifiersQuery query; HttpServletRequest request | PageData<ModifierConfigVO> | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/modifiers | ai.neargo.shop.food.api.biz.BizModifierController.createModifier | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateModifierReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | CreateModifierResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/option-groups | ai.neargo.shop.food.api.biz.BizOptionGroupController.listOptionGroups | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListOptionGroupsQuery query; HttpServletRequest request | PageData<OptionGroupConfigVO> | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/option-groups | ai.neargo.shop.food.api.biz.BizOptionGroupController.createOptionGroup | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateOptionGroupReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | CreateOptionGroupResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/option-groups/{configNo} | ai.neargo.shop.food.api.biz.BizOptionGroupController.getOptionGroup | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String configNo; HttpServletRequest request | OptionGroupResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/option-groups/{configNo}:publish | ai.neargo.shop.food.api.biz.BizConfigRevisionController.publishOptionGroup | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String configNo; @Valid @RequestBody PublishConfigReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | PublishConfigResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/policies | ai.neargo.shop.food.api.biz.BizStoreOperationPolicyController.createPolicy | @PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody CreatePolicyReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | OperationVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/policy | ai.neargo.shop.food.api.biz.BizStoreOperationPolicyController.getPolicy | @PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo; @Valid @ModelAttribute GetPolicyQuery query; HttpServletRequest request | StoreOperationPolicyResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/production-consumptions | ai.neargo.shop.food.api.biz.BizProductionConsumptionController.list | @PathVariable @NotBlank @Size(max = 32) String storeNo; @RequestParam LocalDate from; @RequestParam LocalDate to; @RequestParam(required = false) Integer page; @RequestParam(required = false) Integer size; @RequestParam(required = false) String skuNo; @RequestParam(required = false) String itemRef; @RequestParam(required = false) String status; HttpServletRequest request | PageData<ConsumptionVO> | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/quotes | ai.neargo.shop.food.api.biz.BizDiningPricingController.quote | @PathVariable @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody DiningQuoteReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | QuoteVO | @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/recipes | ai.neargo.shop.food.api.biz.BizRecipeController.listRecipes | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListRecipesQuery query; HttpServletRequest request | PageData<RecipeVO> | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/recipes | ai.neargo.shop.food.api.biz.BizRecipeController.createRecipe | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateRecipeReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | RecipeResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/reservations | ai.neargo.shop.food.api.biz.BizReservationController.listReservations | @PathVariable("storeNo") @NotBlank @Size(max=32) String store; @Valid @ModelAttribute ListReservationsQuery query; HttpServletRequest request | PageData<ReservationVO> | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | POST | /biz/x/food/stores/{storeNo}/reservations | ai.neargo.shop.food.api.biz.BizReservationController.create | @PathVariable @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateReq body; @RequestHeader("Idempotency-Key") String key; HttpServletRequest request | DetailVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | GET | /biz/x/food/stores/{storeNo}/sales-restrictions | ai.neargo.shop.food.api.biz.BizSalesRestrictionController.list | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute SalesRestrictionQuery query; HttpServletRequest request | PageData<SalesRestrictionVO> | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/sales-restrictions | ai.neargo.shop.food.api.biz.BizSalesRestrictionController.manage | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody SalesRestrictionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | SalesRestrictionVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/sku-routes/{skuNo} | ai.neargo.shop.food.api.biz.BizStationController.getRoutes | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("skuNo") @NotBlank @Size(max=32) String sku; HttpServletRequest request | StationRoutesVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | PUT | /biz/x/food/stores/{storeNo}/sku-routes/{skuNo} | ai.neargo.shop.food.api.biz.BizStationController.replaceRoutes | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("skuNo") @NotBlank @Size(max=32) String sku; @Valid @RequestBody ReplaceStationRoutesReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | OperationVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/stations | ai.neargo.shop.food.api.biz.BizStationController.listStations | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @RequestParam(value="page",required=false) Integer page; @RequestParam(value="size",required=false) Integer size; HttpServletRequest request | PageData<StationVO> | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/stations | ai.neargo.shop.food.api.biz.BizStationController.createStation | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateStationReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | StationMutationVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/stations/{configNo} | ai.neargo.shop.food.api.biz.BizStationController.getStation | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String number; HttpServletRequest request | GetStationVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | PUT | /biz/x/food/stores/{storeNo}/stations/{configNo} | ai.neargo.shop.food.api.biz.BizStationController.updateStation | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String number; @Valid @RequestBody UpdateStationReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | StationMutationVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/stations/{configNo}:publish | ai.neargo.shop.food.api.biz.BizConfigRevisionController.publishStation | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @PathVariable("configNo") @NotBlank @Size(max=32) String configNo; @Valid @RequestBody PublishConfigReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | PublishConfigResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/table-areas | ai.neargo.shop.food.api.biz.BizTableAreaController.listTableAreas | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListTableAreasQuery query; HttpServletRequest request | PageData<TableAreaVO> | @PreAuthorize("@perm.canAnyBiz('" + BizPerms.STORE + "','" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/table-areas | ai.neargo.shop.food.api.biz.BizTableAreaController.createTableArea | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateTableAreaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | TableAreaResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')") |
| biz | GET | /biz/x/food/stores/{storeNo}/tables | ai.neargo.shop.food.api.biz.BizTableController.listTables | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @ModelAttribute ListTablesQuery query; HttpServletRequest request | PageData<TableVO> | @PreAuthorize("@perm.canAnyBiz('" + BizPerms.STORE + "','" + BizPerms.ORDER_VIEW + "')") |
| biz | POST | /biz/x/food/stores/{storeNo}/tables | ai.neargo.shop.food.api.biz.BizTableController.createTable | @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo; @Valid @RequestBody CreateTableReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | TableResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')") |
| biz | PUT | /biz/x/food/table-areas/{areaNo} | ai.neargo.shop.food.api.biz.BizTableAreaController.saveTableArea | @PathVariable("areaNo") @NotBlank @Size(max=32) String areaNo; @Valid @RequestBody SaveTableAreaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | TableAreaResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')") |
| biz | POST | /biz/x/food/table-areas/{areaNo}:set-status | ai.neargo.shop.food.api.biz.BizTableAreaController.setStatusTableArea | @PathVariable("areaNo") @NotBlank @Size(max=32) String areaNo; @Valid @RequestBody SetStatusTableAreaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | TableAreaResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')") |
| biz | GET | /biz/x/food/table-operations/{operationNo} | ai.neargo.shop.food.api.biz.BizTableOperationController.getTableOperation | @PathVariable("operationNo") @NotBlank @Size(max=32) String number; HttpServletRequest request | OperationResultVO | @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')") |
| biz | GET | /biz/x/food/tables/{tableNo} | ai.neargo.shop.food.api.biz.BizTableController.getTable | @PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo; HttpServletRequest request | TableResultVO | @PreAuthorize("@perm.canAnyBiz('" + BizPerms.STORE + "','" + BizPerms.ORDER_VIEW + "')") |
| biz | PUT | /biz/x/food/tables/{tableNo} | ai.neargo.shop.food.api.biz.BizTableController.saveTable | @PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo; @Valid @RequestBody SaveTableReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | TableResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')") |
| biz | POST | /biz/x/food/tables/{tableNo}:archive | ai.neargo.shop.food.api.biz.BizTableController.archiveTable | @PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo; @Valid @RequestBody ArchiveTableReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | OperationResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')") |
| biz | POST | /biz/x/food/tables/{tableNo}:revoke-token | ai.neargo.shop.food.api.biz.BizTableTokenController.revokeTableToken | @PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo; @Valid @RequestBody RevokeTableTokenReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | OperationResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')") |
| biz | POST | /biz/x/food/tables/{tableNo}:rotate-token | ai.neargo.shop.food.api.biz.BizTableTokenController.rotateTableToken | @PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo; @Valid @RequestBody RotateTableTokenReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | TableTokenVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')") |
| biz | POST | /biz/x/food/tables/{tableNo}:set-status | ai.neargo.shop.food.api.biz.BizTableController.setStatusTable | @PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo; @Valid @RequestBody SetStatusTableReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | TableResultVO | @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')") |
| biz | POST | /biz/x/food/tables/{tableNo}:share | ai.neargo.shop.food.api.biz.BizTableSharingController.openSharedCheck | @PathVariable("tableNo") @NotBlank @Size(max=32) String table; @Valid @RequestBody OpenSharedCheckReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | CheckResultVO | @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')") |
| biz | POST | /biz/x/food/tasks/{taskNo}:fire | ai.neargo.shop.food.api.biz.BizKitchenTaskController.fire | @PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | EventVO | @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')") |
| biz | POST | /biz/x/food/tasks/{taskNo}:hold | ai.neargo.shop.food.api.biz.BizKitchenTaskController.hold | @PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | EventVO | @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')") |
| biz | POST | /biz/x/food/tasks/{taskNo}:loss | ai.neargo.shop.food.api.biz.BizKitchenTaskController.loss | @PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | EventVO | @PreAuthorize("@perm.canBiz('"+BizPerms.STOCK+"')") |
| biz | POST | /biz/x/food/tasks/{taskNo}:ready | ai.neargo.shop.food.api.biz.BizKitchenTaskController.ready | @PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | EventVO | @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')") |
| biz | POST | /biz/x/food/tasks/{taskNo}:remake | ai.neargo.shop.food.api.biz.BizKitchenTaskController.remake | @PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody RemakeReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | TicketVO | @PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')") |
| biz | POST | /biz/x/food/tasks/{taskNo}:serve | ai.neargo.shop.food.api.biz.BizKitchenTaskController.serve | @PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | EventVO | @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')") |
| biz | POST | /biz/x/food/tasks/{taskNo}:start | ai.neargo.shop.food.api.biz.BizKitchenTaskController.start | @PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | EventVO | @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')") |
| biz | POST | /biz/x/food/tasks/{taskNo}:urge | ai.neargo.shop.food.api.biz.BizKitchenTaskController.urge | @PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | EventVO | @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')") |
| biz | POST | /biz/x/food/tasks/{taskNo}:void | ai.neargo.shop.food.api.biz.BizKitchenTaskController.voidTask | @PathVariable @NotBlank @Size(max=32) String taskNo; @Valid @RequestBody TaskDeltaReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key; HttpServletRequest request | EventVO | @PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')") |
| biz | GET | /biz/x/food/tasks/{taskNo}/events | ai.neargo.shop.food.api.biz.BizKitchenTaskController.events | @PathVariable @NotBlank @Size(max=32) String taskNo; HttpServletRequest request | List<EventVO> | @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')") |
| internal | POST | /internal/food/amendment-source/query | ai.neargo.shop.food.server.api.InternalBusinessReturnApprovalController.source | @RequestBody IndustryAmendmentSourceInternalApi.Query c | IndustryAmendmentSourcePort.Evidence |  |
| internal | POST | /internal/food/batch-query/list | ai.neargo.shop.food.server.api.InternalDiningBatchQueryController.list | @Valid @RequestBody DiningBatchQueryInternalApi.Listing request | PageData<DiningBatchSummaryVO> |  |
| internal | POST | /internal/food/bundles/create | ai.neargo.shop.food.server.api.InternalBundleController.createBundle | @Valid @RequestBody BundleInternalApi.CreateCommand c | CreateBundleResultVO |  |
| internal | POST | /internal/food/bundles/get | ai.neargo.shop.food.server.api.InternalBundleController.getBundle | @Valid @RequestBody BundleInternalApi.GetQuery c | GetBundleVO |  |
| internal | POST | /internal/food/bundles/list | ai.neargo.shop.food.server.api.InternalBundleController.listBundles | @Valid @RequestBody BundleInternalApi.Lookup c | PageData<BundleConfigVO> |  |
| internal | POST | /internal/food/bundles/replace-definition | ai.neargo.shop.food.server.api.InternalBundleController.replaceDefinition | @Valid @RequestBody BundleInternalApi.UpdateCommand c | BundleMutationVO |  |
| internal | POST | /internal/food/business-close/adjust | ai.neargo.shop.food.server.api.InternalBusinessCloseController.adjust | @Valid @RequestBody AdjustCommand c | OperationVO |  |
| internal | POST | /internal/food/business-close/approve-carry | ai.neargo.shop.food.server.api.InternalBusinessCloseController.approveCarry | @Valid @RequestBody ApproveCarryCommand c | ExceptionVO |  |
| internal | POST | /internal/food/business-close/exceptions | ai.neargo.shop.food.server.api.InternalBusinessCloseController.exceptions | @Valid @RequestBody GetQuery c | List<ExceptionVO> |  |
| internal | POST | /internal/food/business-close/get | ai.neargo.shop.food.server.api.InternalBusinessCloseController.get | @Valid @RequestBody GetQuery c | CloseVO |  |
| internal | POST | /internal/food/business-close/open | ai.neargo.shop.food.server.api.InternalBusinessCloseController.open | @Valid @RequestBody OpenCommand c | DayVO |  |
| internal | POST | /internal/food/business-close/preview | ai.neargo.shop.food.server.api.InternalBusinessCloseController.preview | @Valid @RequestBody PreviewCommand c | CloseVO |  |
| internal | POST | /internal/food/business-close/propose-carry | ai.neargo.shop.food.server.api.InternalBusinessCloseController.proposeCarry | @Valid @RequestBody ProposeCarryCommand c | ExceptionVO |  |
| internal | POST | /internal/food/business-close/report | ai.neargo.shop.food.server.api.InternalBusinessCloseController.report | @Valid @RequestBody ReportQuery c | ReportVO |  |
| internal | POST | /internal/food/business-close/sign | ai.neargo.shop.food.server.api.InternalBusinessCloseController.sign | @Valid @RequestBody SignCommand c | CloseVO |  |
| internal | POST | /internal/food/business-operations/businessOperations/accept | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.businessOperationsAccept | @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsAcceptCommand c | BusinessOperationsContracts.HandoverVO |  |
| internal | POST | /internal/food/business-operations/businessOperations/cancel | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.businessOperationsCancel | @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsCancelCommand c | BusinessOperationsContracts.HandoverVO |  |
| internal | POST | /internal/food/business-operations/businessOperations/cases | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.businessOperationsCases | @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsCasesCommand c | List<BusinessOperationsContracts.CaseVO> |  |
| internal | POST | /internal/food/business-operations/businessOperations/evidence | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.businessOperationsEvidence | @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsEvidenceCommand c | BusinessOperationsContracts.Evidence |  |
| internal | POST | /internal/food/business-operations/businessOperations/handover | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.businessOperationsHandover | @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsHandoverCommand c | BusinessOperationsContracts.HandoverVO |  |
| internal | POST | /internal/food/business-operations/businessOperations/openCase | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.businessOperationsOpenCase | @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsOpenCaseCommand c | BusinessOperationsContracts.CaseVO |  |
| internal | POST | /internal/food/business-operations/businessOperations/propose | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.businessOperationsPropose | @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsProposeCommand c | BusinessOperationsContracts.HandoverVO |  |
| internal | POST | /internal/food/business-operations/businessOperations/recoverCase | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.businessOperationsRecoverCase | @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsRecoverCaseCommand c | BusinessOperationsContracts.CaseVO |  |
| internal | POST | /internal/food/business-operations/businessOperations/resolveCase | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.businessOperationsResolveCase | @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsResolveCaseCommand c | BusinessOperationsContracts.CaseVO |  |
| internal | POST | /internal/food/business-operations/checkCollaboration/currentLineOwner | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.checkCollaborationCurrentLineOwner | @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationCurrentLineOwnerCommand c | String |  |
| internal | POST | /internal/food/business-operations/checkCollaboration/freeze | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.checkCollaborationFreeze | @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationFreezeCommand c | CheckCollaborationContracts.FreezeVO |  |
| internal | POST | /internal/food/business-operations/checkCollaboration/moveBatches | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.checkCollaborationMoveBatches | @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationMoveBatchesCommand c | CheckCollaborationContracts.MoveVO |  |
| internal | POST | /internal/food/business-operations/checkCollaboration/moveLines | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.checkCollaborationMoveLines | @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationMoveLinesCommand c | CheckCollaborationContracts.LineMoveVO |  |
| internal | POST | /internal/food/business-operations/checkCollaboration/previewReceivable | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.checkCollaborationPreviewReceivable | @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationPreviewReceivableCommand c | CheckCollaborationContracts.ReceivablePreview |  |
| internal | POST | /internal/food/business-operations/checkCollaboration/release | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.checkCollaborationRelease | @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationReleaseCommand c | CheckCollaborationContracts.FreezeVO |  |
| internal | POST | /internal/food/business-operations/checkCollaboration/releaseService | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.checkCollaborationReleaseService | @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationReleaseServiceCommand c | OperationVO |  |
| internal | POST | /internal/food/business-operations/checkCollaboration/sourceHash | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.checkCollaborationSourceHash | @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationSourceHashCommand c | String |  |
| internal | POST | /internal/food/business-operations/foodFeeSource/bind | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.foodFeeSourceBind | @Valid @RequestBody BusinessOperationsInternalApi.FoodFeeSourceBindCommand c | FoodFeeSourceContracts.BindingVO |  |
| internal | POST | /internal/food/business-operations/foodFeeSource/bindings | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.foodFeeSourceBindings | @Valid @RequestBody BusinessOperationsInternalApi.FoodFeeSourceBindingsCommand c | List<FoodFeeSourceContracts.BindingVO> |  |
| internal | POST | /internal/food/business-operations/queueNotification/list | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.queueNotificationList | @Valid @RequestBody BusinessOperationsInternalApi.QueueNotificationListCommand c | List<QueueNotificationService.NoticeVO> |  |
| internal | POST | /internal/food/business-operations/queueNotification/recover | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.queueNotificationRecover | @Valid @RequestBody BusinessOperationsInternalApi.QueueNotificationRecoverCommand c | QueueNotificationService.NoticeVO |  |
| internal | POST | /internal/food/business-operations/reservationPreorder/cancel | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.reservationPreorderCancel | @Valid @RequestBody BusinessOperationsInternalApi.ReservationPreorderCancelCommand c | ReservationPreorderContracts.PreorderVO |  |
| internal | POST | /internal/food/business-operations/reservationPreorder/convert | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.reservationPreorderConvert | @Valid @RequestBody BusinessOperationsInternalApi.ReservationPreorderConvertCommand c | DiningOrderBatchVO |  |
| internal | POST | /internal/food/business-operations/reservationPreorder/get | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.reservationPreorderGet | @Valid @RequestBody BusinessOperationsInternalApi.ReservationPreorderGetCommand c | ReservationPreorderContracts.PreorderVO |  |
| internal | POST | /internal/food/business-operations/reservationPreorder/prepareCart | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.reservationPreorderPrepareCart | @Valid @RequestBody BusinessOperationsInternalApi.ReservationPreorderPrepareCartCommand c | CartResultVO |  |
| internal | POST | /internal/food/business-operations/reservationPreorder/save | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.reservationPreorderSave | @Valid @RequestBody BusinessOperationsInternalApi.ReservationPreorderSaveCommand c | ReservationPreorderContracts.PreorderVO |  |
| internal | POST | /internal/food/business-operations/serviceForm/admit | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceFormAdmit | @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormAdmitCommand c | ServiceFormContracts.AttendeeVO |  |
| internal | POST | /internal/food/business-operations/serviceForm/attendees | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceFormAttendees | @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormAttendeesCommand c | List<ServiceFormContracts.AttendeeVO> |  |
| internal | POST | /internal/food/business-operations/serviceForm/bindBatch | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceFormBindBatch | @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormBindBatchCommand c | ServiceFormContracts.FormVO |  |
| internal | POST | /internal/food/business-operations/serviceForm/book | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceFormBook | @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormBookCommand c | ServiceFormContracts.FormVO |  |
| internal | POST | /internal/food/business-operations/serviceForm/cancel | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceFormCancel | @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormCancelCommand c | ServiceFormContracts.FormVO |  |
| internal | POST | /internal/food/business-operations/serviceForm/enter | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceFormEnter | @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormEnterCommand c | ServiceFormContracts.FormVO |  |
| internal | POST | /internal/food/business-operations/serviceForm/exit | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceFormExit | @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormExitCommand c | ServiceFormContracts.AttendeeVO |  |
| internal | POST | /internal/food/business-operations/serviceForm/get | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceFormGet | @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormGetCommand c | ServiceFormContracts.FormVO |  |
| internal | POST | /internal/food/business-operations/serviceForm/leave | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceFormLeave | @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormLeaveCommand c | ServiceFormContracts.FormVO |  |
| internal | POST | /internal/food/business-operations/serviceForm/noShow | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceFormNoShow | @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormNoShowCommand c | ServiceFormContracts.FormVO |  |
| internal | POST | /internal/food/business-operations/serviceForm/registerOrganization | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceFormRegisterOrganization | @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormRegisterOrganizationCommand c | ServiceFormContracts.OrganizationVO |  |
| internal | POST | /internal/food/business-operations/serviceForm/revise | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceFormRevise | @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormReviseCommand c | ServiceFormContracts.FormVO |  |
| internal | POST | /internal/food/business-operations/serviceSlot/create | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceSlotCreate | @Valid @RequestBody BusinessOperationsInternalApi.ServiceSlotCreateCommand c | ServiceSlotContracts.SlotVO |  |
| internal | POST | /internal/food/business-operations/serviceSlot/dispatchDue | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceSlotDispatchDue | @Valid @RequestBody BusinessOperationsInternalApi.ServiceSlotDispatchDueCommand c | boolean |  |
| internal | POST | /internal/food/business-operations/serviceSlot/get | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceSlotGet | @Valid @RequestBody BusinessOperationsInternalApi.ServiceSlotGetCommand c | ServiceSlotContracts.SlotVO |  |
| internal | POST | /internal/food/business-operations/serviceSlot/reserve | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceSlotReserve | @Valid @RequestBody BusinessOperationsInternalApi.ServiceSlotReserveCommand c | ServiceSlotContracts.ClaimVO |  |
| internal | POST | /internal/food/business-operations/serviceSlot/transition | ai.neargo.shop.food.server.api.InternalBusinessOperationsController.serviceSlotTransition | @Valid @RequestBody BusinessOperationsInternalApi.ServiceSlotTransitionCommand c | ServiceSlotContracts.ClaimVO |  |
| internal | POST | /internal/food/business-returns/cancel | ai.neargo.shop.food.server.api.InternalBusinessReturnController.cancel | @Valid @RequestBody BusinessReturnInternalApi.CancelCommand command | ReturnVO |  |
| internal | POST | /internal/food/business-returns/create | ai.neargo.shop.food.server.api.InternalBusinessReturnController.create | @Valid @RequestBody BusinessReturnInternalApi.CreateCommand command | ReturnVO |  |
| internal | POST | /internal/food/business-returns/get | ai.neargo.shop.food.server.api.InternalBusinessReturnController.get | @Valid @RequestBody BusinessReturnInternalApi.Lookup query | ReturnVO |  |
| internal | POST | /internal/food/business-shares/cancel | ai.neargo.shop.food.server.api.InternalBusinessShareController.cancel | @Valid @RequestBody BusinessShareInternalApi.CancelCommand command | PlanVO |  |
| internal | POST | /internal/food/business-shares/confirm | ai.neargo.shop.food.server.api.InternalBusinessShareController.confirm | @Valid @RequestBody BusinessShareInternalApi.ConfirmCommand command | PlanVO |  |
| internal | POST | /internal/food/business-shares/create | ai.neargo.shop.food.server.api.InternalBusinessShareController.create | @Valid @RequestBody BusinessShareInternalApi.CreateCommand command | PlanVO |  |
| internal | POST | /internal/food/business-shares/get | ai.neargo.shop.food.server.api.InternalBusinessShareController.get | @Valid @RequestBody BusinessShareInternalApi.Lookup query | PlanVO |  |
| internal | POST | /internal/food/carts/archive | ai.neargo.shop.food.server.api.InternalCartController.archive | @Valid @RequestBody CartInternalApi.ArchiveCommand c | OperationResultVO |  |
| internal | POST | /internal/food/carts/archive-line | ai.neargo.shop.food.server.api.InternalCartController.archiveLine | @Valid @RequestBody CartInternalApi.ArchiveLineCommand c | CartVersionVO |  |
| internal | POST | /internal/food/carts/authorize-session | ai.neargo.shop.food.server.api.InternalCartController.authorize | @Valid @RequestBody CartInternalApi.AuthorizeCommand c | CartSessionGrantVO |  |
| internal | POST | /internal/food/carts/create | ai.neargo.shop.food.server.api.InternalCartController.create | @Valid @RequestBody CartInternalApi.CreateCommand c | CartResultVO |  |
| internal | POST | /internal/food/carts/customer-archive-line | ai.neargo.shop.food.server.api.InternalCartController.customerArchiveLine | @Valid @RequestBody CartInternalApi.CustomerArchiveLineCommand c | CartVersionVO |  |
| internal | POST | /internal/food/carts/customer-create | ai.neargo.shop.food.server.api.InternalCartController.customerCreate | @Valid @RequestBody CartInternalApi.CustomerCreateCommand c | CartResultVO |  |
| internal | POST | /internal/food/carts/customer-get | ai.neargo.shop.food.server.api.InternalCartController.customerGet | @Valid @RequestBody CartInternalApi.CustomerIdentity c | CartResultVO |  |
| internal | POST | /internal/food/carts/customer-replace-line | ai.neargo.shop.food.server.api.InternalCartController.customerReplaceLine | @Valid @RequestBody CartInternalApi.CustomerReplaceLineCommand c | CartVersionVO |  |
| internal | POST | /internal/food/carts/get | ai.neargo.shop.food.server.api.InternalCartController.get | @Valid @RequestBody CartInternalApi.Identity c | CartResultVO |  |
| internal | POST | /internal/food/carts/replace-line | ai.neargo.shop.food.server.api.InternalCartController.replaceLine | @Valid @RequestBody CartInternalApi.ReplaceLineCommand c | CartVersionVO |  |
| internal | POST | /internal/food/catalog/guest | ai.neargo.shop.food.server.api.InternalCatalogController.guest | @Valid @RequestBody CatalogInternalApi.GuestQuery q | CatalogVO |  |
| internal | POST | /internal/food/catalog/public | ai.neargo.shop.food.server.api.InternalCatalogController.publicCatalog | @Valid @RequestBody CatalogInternalApi.PublicQuery q | CatalogVO |  |
| internal | POST | /internal/food/catalog/read | ai.neargo.shop.food.server.api.InternalCatalogController.read | @Valid @RequestBody CatalogInternalApi.Query q | CatalogVO |  |
| internal | POST | /internal/food/catalog/scheduled-cart | ai.neargo.shop.food.server.api.InternalCatalogController.scheduledCart | @RequestBody CatalogInternalApi.ScheduledQuery query | CatalogVO |  |
| internal | POST | /internal/food/checks/actions | ai.neargo.shop.food.server.api.InternalCheckController.actions | @Valid @RequestBody CheckInternalApi.ActionsLookup c | PageData<ActionLogVO> |  |
| internal | POST | /internal/food/checks/clear | ai.neargo.shop.food.server.api.InternalCheckController.clear | @Valid @RequestBody CheckInternalApi.ClearCommand c | OperationVO |  |
| internal | POST | /internal/food/checks/combine-tables | ai.neargo.shop.food.server.api.InternalCheckController.combine | @Valid @RequestBody CheckInternalApi.CombineCommand c | OperationResultVO |  |
| internal | POST | /internal/food/checks/get | ai.neargo.shop.food.server.api.InternalCheckController.get | @Valid @RequestBody CheckInternalApi.Identity c | CheckDetailVO |  |
| internal | POST | /internal/food/checks/list | ai.neargo.shop.food.server.api.InternalCheckController.list | @Valid @RequestBody CheckInternalApi.Lookup c | PageData<CheckVO> |  |
| internal | POST | /internal/food/checks/open | ai.neargo.shop.food.server.api.InternalCheckController.open | @Valid @RequestBody CheckInternalApi.OpenCommand c | CheckResultVO |  |
| internal | POST | /internal/food/checks/seats | ai.neargo.shop.food.server.api.InternalCheckController.seats | @Valid @RequestBody CheckInternalApi.SeatsCommand c | CheckResultVO |  |
| internal | POST | /internal/food/checks/transfer | ai.neargo.shop.food.server.api.InternalCheckController.transfer | @Valid @RequestBody CheckInternalApi.TransferCommand c | OperationResultVO |  |
| internal | POST | /internal/food/config-revisions/get | ai.neargo.shop.food.server.api.InternalConfigRevisionController.getRevision | @Valid @RequestBody ConfigRevisionInternalApi.Identity c | ConfigRevisionContentVO |  |
| internal | POST | /internal/food/config-revisions/list | ai.neargo.shop.food.server.api.InternalConfigRevisionController.listRevisions | @Valid @RequestBody ConfigRevisionInternalApi.Lookup c | PageData<ConfigRevisionVO> |  |
| internal | POST | /internal/food/config-revisions/publish-bundle | ai.neargo.shop.food.server.api.InternalConfigRevisionController.publishBundle | @Valid @RequestBody ConfigRevisionInternalApi.Command c | PublishConfigResultVO |  |
| internal | POST | /internal/food/config-revisions/publish-menu | ai.neargo.shop.food.server.api.InternalConfigRevisionController.publishMenu | @Valid @RequestBody ConfigRevisionInternalApi.Command c | PublishConfigResultVO |  |
| internal | POST | /internal/food/config-revisions/publish-modifier | ai.neargo.shop.food.server.api.InternalConfigRevisionController.publishModifier | @Valid @RequestBody ConfigRevisionInternalApi.Command c | PublishConfigResultVO |  |
| internal | POST | /internal/food/config-revisions/publish-option-group | ai.neargo.shop.food.server.api.InternalConfigRevisionController.publishOptionGroup | @Valid @RequestBody ConfigRevisionInternalApi.Command c | PublishConfigResultVO |  |
| internal | POST | /internal/food/config-revisions/publish-recipe | ai.neargo.shop.food.server.api.InternalConfigRevisionController.publishRecipe | @Valid @RequestBody ConfigRevisionInternalApi.Command c | PublishConfigResultVO |  |
| internal | POST | /internal/food/config-revisions/publish-station | ai.neargo.shop.food.server.api.InternalConfigRevisionController.publishStation | @Valid @RequestBody ConfigRevisionInternalApi.Command c | PublishConfigResultVO |  |
| internal | POST | /internal/food/config-revisions/republish | ai.neargo.shop.food.server.api.InternalConfigRevisionController.republishRevision | @Valid @RequestBody ConfigRevisionInternalApi.RepublishCommand c | PublishConfigResultVO |  |
| internal | POST | /internal/food/config-revisions/resolve | ai.neargo.shop.food.server.api.InternalConfigRevisionController.resolveRevision | @Valid @RequestBody ConfigRevisionInternalApi.Resolve c | ConfigRevisionContentVO |  |
| internal | POST | /internal/food/delivery-source/get-customer | ai.neargo.shop.food.server.api.InternalDiningDeliveryController.getCustomer | @RequestBody DiningDeliveryInternalApi.CustomerQuery c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/delivery-source/get-staff | ai.neargo.shop.food.server.api.InternalDiningDeliveryController.getStaff | @RequestBody DiningDeliveryInternalApi.Source c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/delivery-source/save-customer | ai.neargo.shop.food.server.api.InternalDiningDeliveryController.saveCustomer | @RequestBody DiningDeliveryInternalApi.CustomerSave c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/delivery-source/save-staff | ai.neargo.shop.food.server.api.InternalDiningDeliveryController.saveStaff | @RequestBody DiningDeliveryInternalApi.StaffSave c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/delivery-source/source | ai.neargo.shop.food.server.api.InternalDiningDeliveryController.source | @RequestBody DiningDeliveryInternalApi.Source c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/dining-returns/customer/apply | ai.neargo.shop.food.server.api.InternalDiningReturnController.apply | @RequestBody DiningReturnInternalApi.CustomerApply r | DiningReturnVO |  |
| internal | POST | /internal/food/dining-returns/customer/cancel | ai.neargo.shop.food.server.api.InternalDiningReturnController.cancel | @RequestBody DiningReturnInternalApi.CustomerReference r | DiningReturnVO |  |
| internal | POST | /internal/food/dining-returns/customer/get | ai.neargo.shop.food.server.api.InternalDiningReturnController.get | @RequestBody DiningReturnInternalApi.CustomerReference r | DiningReturnVO |  |
| internal | POST | /internal/food/dining-returns/recover-original | ai.neargo.shop.food.server.api.InternalDiningReturnController.recoverOriginal | @RequestBody DiningReturnInternalApi.Original request | DiningReturnVO |  |
| internal | POST | /internal/food/dining-returns/staff/get | ai.neargo.shop.food.server.api.InternalDiningReturnController.staff | @RequestBody DiningReturnInternalApi.StaffReference r | DiningReturnVO |  |
| internal | POST | /internal/food/dining-sessions/end | ai.neargo.shop.food.server.api.InternalDiningSessionController.end | @Valid @RequestBody DiningSessionInternalApi.EndCommand c | OperationResultVO |  |
| internal | POST | /internal/food/dining-sessions/resolve-table | ai.neargo.shop.food.server.api.InternalDiningSessionController.resolve | @Valid @RequestBody DiningSessionInternalApi.ResolveCommand c | DiningSessionTableVO |  |
| internal | POST | /internal/food/dining-sessions/start | ai.neargo.shop.food.server.api.InternalDiningSessionController.start | @Valid @RequestBody DiningSessionInternalApi.StartCommand c | DiningSessionVO |  |
| internal | POST | /internal/food/dishes/get | ai.neargo.shop.food.server.api.InternalDishController.getDish | @Valid @RequestBody DishInternalApi.Identity query | DishDetailResultVO |  |
| internal | POST | /internal/food/dishes/list | ai.neargo.shop.food.server.api.InternalDishController.listDishes | @Valid @RequestBody DishInternalApi.Lookup query | PageData<DishCatalogItemVO> |  |
| internal | POST | /internal/food/dishes/save | ai.neargo.shop.food.server.api.InternalDishController.saveDish | @Valid @RequestBody DishInternalApi.SaveCommand c | DishResultVO |  |
| internal | POST | /internal/food/identity/device | ai.neargo.shop.food.server.api.InternalDiningIdentityController.device | @RequestBody DiningIdentityInternalApi.DeviceQuery c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/identity/device-session | ai.neargo.shop.food.server.api.InternalDiningIdentityController.deviceSession | @RequestBody DiningIdentityInternalApi.DeviceSession c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/identity/party | ai.neargo.shop.food.server.api.InternalDiningIdentityController.party | @RequestBody DiningIdentityInternalApi.PartyQuery c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/identity/register-device | ai.neargo.shop.food.server.api.InternalDiningIdentityController.register | @RequestBody DiningIdentityInternalApi.Register c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/identity/revoke-device | ai.neargo.shop.food.server.api.InternalDiningIdentityController.revokeDevice | @RequestBody DiningIdentityInternalApi.DeviceRevoke c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/identity/revoke-party | ai.neargo.shop.food.server.api.InternalDiningIdentityController.revokeParty | @RequestBody DiningIdentityInternalApi.PartyRevoke c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/identity/staff-party | ai.neargo.shop.food.server.api.InternalDiningIdentityController.staffParty | @RequestBody DiningIdentityInternalApi.StaffParty c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/identity/validate-device-session | ai.neargo.shop.food.server.api.InternalDiningIdentityController.validate | @RequestBody DiningIdentityInternalApi.SessionQuery c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/kds/append | ai.neargo.shop.food.server.api.InternalFoodKdsController.append | @RequestBody EventDraft c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/kds/grant | ai.neargo.shop.food.server.api.InternalFoodKdsController.grant | @RequestBody FoodKdsInternalApi.Grant c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/kds/replay | ai.neargo.shop.food.server.api.InternalFoodKdsController.replay | @RequestBody FoodKdsInternalApi.Replay c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/kds/revoke | ai.neargo.shop.food.server.api.InternalFoodKdsController.revoke | @RequestBody FoodKdsInternalApi.Revoke c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/kitchen/accepted | ai.neargo.shop.food.server.api.InternalKitchenController.accepted | @Valid @RequestBody KitchenInternalApi.DispatchCommand c | TicketVO |  |
| internal | POST | /internal/food/kitchen/act | ai.neargo.shop.food.server.api.InternalKitchenController.act | @Valid @RequestBody KitchenInternalApi.TaskCommand c | EventVO |  |
| internal | POST | /internal/food/kitchen/act-course | ai.neargo.shop.food.server.api.InternalKitchenController.actCourse | @Valid @RequestBody KitchenInternalApi.CourseCommand c | CourseVO |  |
| internal | POST | /internal/food/kitchen/courses | ai.neargo.shop.food.server.api.InternalKitchenController.courses | @Valid @RequestBody KitchenInternalApi.CourseQuery c | List<CourseVO> |  |
| internal | POST | /internal/food/kitchen/create-course | ai.neargo.shop.food.server.api.InternalKitchenController.createCourse | @Valid @RequestBody KitchenInternalApi.CreateCourseCommand c | CourseVO |  |
| internal | POST | /internal/food/kitchen/custody-receipts | ai.neargo.shop.food.server.api.InternalKitchenController.custodyReceipts | @Valid @RequestBody KitchenInternalApi.ReceiptQuery c | List<CustodyReceiptVO> |  |
| internal | POST | /internal/food/kitchen/events | ai.neargo.shop.food.server.api.InternalKitchenController.events | @Valid @RequestBody KitchenInternalApi.TaskQuery c | List<EventVO> |  |
| internal | POST | /internal/food/kitchen/freeze-routes | ai.neargo.shop.food.server.api.InternalKitchenController.freezeRoutes | @Valid @RequestBody KitchenInternalApi.FreezeQuery c | FrozenRoutes |  |
| internal | POST | /internal/food/kitchen/handover | ai.neargo.shop.food.server.api.InternalKitchenController.handover | @Valid @RequestBody KitchenInternalApi.HandoverCommand c | HandoverVO |  |
| internal | POST | /internal/food/kitchen/packing | ai.neargo.shop.food.server.api.InternalKitchenController.confirmPacking | @Valid @RequestBody KitchenInternalApi.PackingCommand c | CustodyReceiptVO |  |
| internal | POST | /internal/food/kitchen/queue | ai.neargo.shop.food.server.api.InternalKitchenController.queue | @Valid @RequestBody KitchenInternalApi.QueueQuery c | List<TaskVO> |  |
| internal | POST | /internal/food/kitchen/remake | ai.neargo.shop.food.server.api.InternalKitchenController.remake | @Valid @RequestBody KitchenInternalApi.RemakeCommand c | TicketVO |  |
| internal | POST | /internal/food/menu-channel-bindings/create | ai.neargo.shop.food.server.api.InternalMenuChannelBindingController.createBinding | @Valid @RequestBody MenuChannelBindingInternalApi.CreateCommand c | MenuChannelBindingVO |  |
| internal | POST | /internal/food/menu-channel-bindings/get | ai.neargo.shop.food.server.api.InternalMenuChannelBindingController.getBinding | @Valid @RequestBody MenuChannelBindingInternalApi.GetQuery c | MenuChannelBindingVO |  |
| internal | POST | /internal/food/menu-channel-bindings/list | ai.neargo.shop.food.server.api.InternalMenuChannelBindingController.listBindings | @Valid @RequestBody MenuChannelBindingInternalApi.Lookup c | PageData<MenuChannelBindingVO> |  |
| internal | POST | /internal/food/menu-channel-bindings/update | ai.neargo.shop.food.server.api.InternalMenuChannelBindingController.updateBinding | @Valid @RequestBody MenuChannelBindingInternalApi.UpdateCommand c | MenuChannelBindingVO |  |
| internal | POST | /internal/food/menus/create | ai.neargo.shop.food.server.api.InternalMenuController.createMenu | @Valid @RequestBody MenuInternalApi.CreateCommand c | CreateMenuResultVO |  |
| internal | POST | /internal/food/menus/disable | ai.neargo.shop.food.server.api.InternalMenuController.disableMenu | @Valid @RequestBody MenuInternalApi.DisableCommand c | MenuResultVO |  |
| internal | POST | /internal/food/menus/get | ai.neargo.shop.food.server.api.InternalMenuController.getMenu | @Valid @RequestBody MenuInternalApi.GetQuery c | MenuConfigResultVO |  |
| internal | POST | /internal/food/menus/list | ai.neargo.shop.food.server.api.InternalMenuController.listMenus | @Valid @RequestBody MenuInternalApi.Lookup c | PageData<MenuVO> |  |
| internal | POST | /internal/food/menus/replace-composition | ai.neargo.shop.food.server.api.InternalMenuController.replaceComposition | @Valid @RequestBody MenuInternalApi.CompositionCommand c | MenuCompositionResultVO |  |
| internal | POST | /internal/food/menus/replace-contexts | ai.neargo.shop.food.server.api.InternalMenuController.replaceContexts | @Valid @RequestBody MenuInternalApi.ContextsCommand c | MenuContextsResultVO |  |
| internal | POST | /internal/food/menus/replace-schedule | ai.neargo.shop.food.server.api.InternalMenuController.replaceSchedule | @Valid @RequestBody MenuInternalApi.ScheduleCommand c | MenuScheduleResultVO |  |
| internal | POST | /internal/food/menus/update | ai.neargo.shop.food.server.api.InternalMenuController.updateMenu | @Valid @RequestBody MenuInternalApi.UpdateCommand c | MenuResultVO |  |
| internal | POST | /internal/food/modifiers/create | ai.neargo.shop.food.server.api.InternalModifierController.createModifier | @Valid @RequestBody ModifierInternalApi.CreateCommand c | CreateModifierResultVO |  |
| internal | POST | /internal/food/modifiers/list | ai.neargo.shop.food.server.api.InternalModifierController.listModifiers | @Valid @RequestBody ModifierInternalApi.Lookup c | PageData<ModifierConfigVO> |  |
| internal | POST | /internal/food/modifiers/update | ai.neargo.shop.food.server.api.InternalModifierController.updateModifier | @Valid @RequestBody ModifierInternalApi.UpdateCommand c | ModifierMutationVO |  |
| internal | POST | /internal/food/operating-report/affected-days | ai.neargo.shop.food.server.api.InternalDiningReportController.affectedDays | @RequestBody ReportAffectedDaysPort.Change change | List<ReportAffectedDaysPort.AffectedDay> |  |
| internal | POST | /internal/food/operating-report/current-day | ai.neargo.shop.food.server.api.InternalDiningReportController.currentDay | @RequestBody Scope scope | OperatingDay |  |
| internal | POST | /internal/food/operating-report/day | ai.neargo.shop.food.server.api.InternalDiningReportController.day | @RequestBody DayQuery q | DayEvidence |  |
| internal | POST | /internal/food/operating-report/export | ai.neargo.shop.food.server.api.InternalDiningReportController.export | @RequestBody ExportQuery command | ReportExportPort.Task |  |
| internal | POST | /internal/food/operating-report/export-download | ai.neargo.shop.food.server.api.InternalDiningReportController.exportDownload | @RequestBody ExportReference ref | ReportExportPort.Download |  |
| internal | POST | /internal/food/operating-report/export-status | ai.neargo.shop.food.server.api.InternalDiningReportController.exportStatus | @RequestBody ExportReference ref | ReportExportPort.Task |  |
| internal | POST | /internal/food/operating-report/facts | ai.neargo.shop.food.server.api.InternalDiningReportController.facts | @RequestBody SourceQuery q | SourceSnapshot |  |
| internal | POST | /internal/food/operating-report/metrics | ai.neargo.shop.food.server.api.InternalDiningReportController.metrics |  | List<MetricDefinition> |  |
| internal | POST | /internal/food/operating-report/query | ai.neargo.shop.food.server.api.InternalDiningReportController.query | @RequestBody Query q | Result |  |
| internal | POST | /internal/food/operating-report/realtime | ai.neargo.shop.food.server.api.InternalDiningReportController.realtime | @RequestBody RealtimeQuery q | ReportRealtimePort.Result |  |
| internal | POST | /internal/food/option-groups/create | ai.neargo.shop.food.server.api.InternalOptionGroupController.createOptionGroup | @Valid @RequestBody OptionGroupInternalApi.CreateCommand c | CreateOptionGroupResultVO |  |
| internal | POST | /internal/food/option-groups/get | ai.neargo.shop.food.server.api.InternalOptionGroupController.getOptionGroup | @Valid @RequestBody OptionGroupInternalApi.Identity query | OptionGroupResultVO |  |
| internal | POST | /internal/food/option-groups/list | ai.neargo.shop.food.server.api.InternalOptionGroupController.listOptionGroups | @Valid @RequestBody OptionGroupInternalApi.Lookup query | PageData<OptionGroupConfigVO> |  |
| internal | POST | /internal/food/option-groups/replace-items | ai.neargo.shop.food.server.api.InternalOptionGroupController.replaceOptionGroupItems | @Valid @RequestBody OptionGroupInternalApi.ReplaceCommand c | OptionGroupMutationVO |  |
| internal | POST | /internal/food/order-authority/delivery | ai.neargo.shop.food.server.api.InternalDiningOrderAuthorityController.delivery | @RequestBody Command command | Delivery |  |
| internal | POST | /internal/food/order-authority/party | ai.neargo.shop.food.server.api.InternalDiningOrderAuthorityController.party | @RequestBody Command command | Party |  |
| internal | POST | /internal/food/order-authority/plan | ai.neargo.shop.food.server.api.InternalDiningOrderAuthorityController.plan | @RequestBody DiningOrderAuthorityInternalApi.PlanCommand command | Plan |  |
| internal | POST | /internal/food/order-batches/authorize-cancellation | ai.neargo.shop.food.server.api.InternalDiningOrderController.authorizeCancellation | @RequestBody DiningOrderInternalApi.CancellationSource request | Snapshot |  |
| internal | POST | /internal/food/order-batches/customer/get | ai.neargo.shop.food.server.api.InternalDiningOrderController.customerGet | @RequestBody DiningOrderInternalApi.CustomerGet r | DiningOrderBatchVO |  |
| internal | POST | /internal/food/order-batches/place | ai.neargo.shop.food.server.api.InternalDiningOrderController.place | @RequestBody DiningOrderInternalApi.Place r | DiningOrderBatchVO |  |
| internal | POST | /internal/food/order-batches/recover-original | ai.neargo.shop.food.server.api.InternalDiningOrderController.recoverOriginal | @RequestBody DiningOrderInternalApi.Original request | DiningOrderBatchVO |  |
| internal | POST | /internal/food/order-batches/staff/cancel | ai.neargo.shop.food.server.api.InternalDiningOrderController.staffCancel | @RequestBody DiningOrderInternalApi.StaffCancel request | DiningOrderBatchVO |  |
| internal | POST | /internal/food/order-batches/staff/get | ai.neargo.shop.food.server.api.InternalDiningOrderController.staffGet | @RequestBody DiningOrderInternalApi.StaffGet r | DiningOrderBatchVO |  |
| internal | POST | /internal/food/order-batches/staff/place | ai.neargo.shop.food.server.api.InternalDiningOrderController.staffPlace | @RequestBody DiningOrderInternalApi.StaffPlace request | DiningOrderBatchVO |  |
| internal | POST | /internal/food/pickup-verification/get | ai.neargo.shop.food.server.api.InternalDiningPickupVerificationController.get | @Valid @RequestBody DiningPickupVerificationInternalApi.Query c | PickupVerificationVO |  |
| internal | POST | /internal/food/pickup-verification/verify | ai.neargo.shop.food.server.api.InternalDiningPickupVerificationController.verify | @Valid @RequestBody DiningPickupVerificationInternalApi.Command c | PickupVerificationVO |  |
| internal | POST | /internal/food/pricing-sources/cart | ai.neargo.shop.food.server.api.InternalPricingSourceController.staff | @Valid @RequestBody PricingSourceInternalApi.StaffCommand c | DiningPriceFacts |  |
| internal | POST | /internal/food/pricing-sources/customer-cart | ai.neargo.shop.food.server.api.InternalPricingSourceController.customer | @Valid @RequestBody PricingSourceInternalApi.CustomerCommand c | DiningPriceFacts |  |
| internal | POST | /internal/food/print-documents/enqueue | ai.neargo.shop.food.server.api.InternalFoodPrintController.enqueue | @RequestBody FoodPrintInternalApi.Command command; @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/production/adjust | ai.neargo.shop.food.server.api.InternalProductionConsumptionController.adjust | @RequestBody ProductionConsumptionInternalApi.AdjustmentCommand c | ConsumptionVO |  |
| internal | POST | /internal/food/production/consume | ai.neargo.shop.food.server.api.InternalProductionConsumptionController.consume | @Valid @RequestBody Command c | ConsumptionVO |  |
| internal | POST | /internal/food/production/get | ai.neargo.shop.food.server.api.InternalProductionConsumptionController.get | @Valid @RequestBody Command c | ConsumptionVO |  |
| internal | POST | /internal/food/production/list | ai.neargo.shop.food.server.api.InternalProductionConsumptionController.list | @Valid @RequestBody ListCommand c | PageData<ConsumptionVO> |  |
| internal | POST | /internal/food/production/reverse | ai.neargo.shop.food.server.api.InternalProductionConsumptionController.reverse | @RequestBody ProductionConsumptionInternalApi.ReverseCommand c | ConsumptionVO |  |
| internal | POST | /internal/food/queue/callNext | ai.neargo.shop.food.server.api.InternalQueueController.callNext | @Valid @RequestBody CallNextCommand c | QueueVO |  |
| internal | POST | /internal/food/queue/change | ai.neargo.shop.food.server.api.InternalQueueController.change | @Valid @RequestBody ChangeCommand c | QueueVO |  |
| internal | POST | /internal/food/queue/get | ai.neargo.shop.food.server.api.InternalQueueController.get | @Valid @RequestBody Identity c | QueueVO |  |
| internal | POST | /internal/food/queue/issue | ai.neargo.shop.food.server.api.InternalQueueController.issue | @Valid @RequestBody IssueCommand c | QueueVO |  |
| internal | POST | /internal/food/queue/list | ai.neargo.shop.food.server.api.InternalQueueController.list | @Valid @RequestBody ListQuery c | PageData<QueueVO> |  |
| internal | POST | /internal/food/queue/recoverSeating | ai.neargo.shop.food.server.api.InternalQueueController.recoverSeating | @Valid @RequestBody Identity c | QueueVO |  |
| internal | POST | /internal/food/queue/seat | ai.neargo.shop.food.server.api.InternalQueueController.seat | @Valid @RequestBody SeatCommand c | QueueVO |  |
| internal | POST | /internal/food/quotes/customer/confirm | ai.neargo.shop.food.server.api.InternalDiningPricingController.customerConfirm | @RequestBody DiningPricingInternalApi.CustomerConfirm r | QuoteVO |  |
| internal | POST | /internal/food/quotes/customer/get | ai.neargo.shop.food.server.api.InternalDiningPricingController.customerGet | @RequestBody DiningPricingInternalApi.CustomerGet r | QuoteVO |  |
| internal | POST | /internal/food/quotes/customer/quote | ai.neargo.shop.food.server.api.InternalDiningPricingController.customerQuote | @RequestBody DiningPricingInternalApi.CustomerQuote r | QuoteVO |  |
| internal | POST | /internal/food/quotes/staff/confirm | ai.neargo.shop.food.server.api.InternalDiningPricingController.staffConfirm | @RequestBody DiningPricingInternalApi.StaffConfirm r | QuoteVO |  |
| internal | POST | /internal/food/quotes/staff/get | ai.neargo.shop.food.server.api.InternalDiningPricingController.staffGet | @RequestBody DiningPricingInternalApi.StaffGet r | QuoteVO |  |
| internal | POST | /internal/food/quotes/staff/quote | ai.neargo.shop.food.server.api.InternalDiningPricingController.staffQuote | @RequestBody DiningPricingInternalApi.StaffQuote r | QuoteVO |  |
| internal | POST | /internal/food/recipes/create | ai.neargo.shop.food.server.api.InternalRecipeController.createRecipe | @Valid @RequestBody RecipeInternalApi.CreateCommand c | RecipeResultVO |  |
| internal | POST | /internal/food/recipes/get | ai.neargo.shop.food.server.api.InternalRecipeController.getRecipe | @Valid @RequestBody RecipeInternalApi.GetQuery c | RecipeResultVO |  |
| internal | POST | /internal/food/recipes/list | ai.neargo.shop.food.server.api.InternalRecipeController.listRecipes | @Valid @RequestBody RecipeInternalApi.Lookup c | PageData<RecipeVO> |  |
| internal | POST | /internal/food/recipes/replace-definition | ai.neargo.shop.food.server.api.InternalRecipeController.replaceDefinition | @Valid @RequestBody RecipeInternalApi.UpdateCommand c | RecipeResultVO |  |
| internal | POST | /internal/food/reservations/arrive | ai.neargo.shop.food.server.api.InternalReservationController.arrive | @Valid @RequestBody ReservationInternalApi.ArriveCommand c | DetailVO |  |
| internal | POST | /internal/food/reservations/cancel | ai.neargo.shop.food.server.api.InternalReservationController.cancel | @Valid @RequestBody ReservationInternalApi.TransitionCommand c | DetailVO |  |
| internal | POST | /internal/food/reservations/change | ai.neargo.shop.food.server.api.InternalReservationController.change | @Valid @RequestBody ReservationInternalApi.ChangeCommand c | DetailVO |  |
| internal | POST | /internal/food/reservations/create | ai.neargo.shop.food.server.api.InternalReservationController.create | @Valid @RequestBody ReservationInternalApi.CreateCommand c | DetailVO |  |
| internal | POST | /internal/food/reservations/get | ai.neargo.shop.food.server.api.InternalReservationController.get | @Valid @RequestBody ReservationInternalApi.NumberLookup c | DetailVO |  |
| internal | POST | /internal/food/reservations/list | ai.neargo.shop.food.server.api.InternalReservationController.list | @Valid @RequestBody ReservationInternalApi.Lookup c | PageData<ReservationVO> |  |
| internal | POST | /internal/food/reservations/noShow | ai.neargo.shop.food.server.api.InternalReservationController.noShow | @Valid @RequestBody ReservationInternalApi.TransitionCommand c | DetailVO |  |
| internal | POST | /internal/food/return-approval/approve | ai.neargo.shop.food.server.api.InternalBusinessReturnApprovalController.approve | @RequestBody Approve c | ApprovalVO |  |
| internal | POST | /internal/food/return-approval/get | ai.neargo.shop.food.server.api.InternalBusinessReturnApprovalController.get | @RequestBody Query c | ApprovalVO |  |
| internal | POST | /internal/food/return-approval/replacement | ai.neargo.shop.food.server.api.InternalBusinessReturnApprovalController.replacement | @RequestBody Replacement c | ApprovalVO |  |
| internal | POST | /internal/food/returns/kitchen/get | ai.neargo.shop.food.server.api.InternalKitchenReturnController.get | @Valid @RequestBody KitchenReturnInternalApi.Query c | ReturnKitchenVO |  |
| internal | POST | /internal/food/returns/kitchen/handle | ai.neargo.shop.food.server.api.InternalKitchenReturnController.handle | @Valid @RequestBody KitchenReturnInternalApi.Command c | HandledVO |  |
| internal | POST | /internal/food/sales-restrictions/evaluate | ai.neargo.shop.food.server.api.InternalSalesRestrictionController.evaluate | @Valid @RequestBody SalesRestrictionInternalApi.Evaluation c | SalesRestrictionEvaluationVO |  |
| internal | POST | /internal/food/sales-restrictions/list | ai.neargo.shop.food.server.api.InternalSalesRestrictionController.list | @Valid @RequestBody SalesRestrictionInternalApi.Lookup c | PageData<SalesRestrictionVO> |  |
| internal | POST | /internal/food/sales-restrictions/manage | ai.neargo.shop.food.server.api.InternalSalesRestrictionController.manage | @Valid @RequestBody SalesRestrictionInternalApi.Command c | SalesRestrictionVO |  |
| internal | POST | /internal/food/service-calendar/save | ai.neargo.shop.food.server.api.InternalServiceCalendarController.saveCalendar | @Valid @RequestBody ServiceCalendarInternalApi.Command command | OperationVO |  |
| internal | POST | /internal/food/settlements/cancel | ai.neargo.shop.food.server.api.InternalDiningCheckoutController.cancel | @RequestBody DiningCheckoutInternalApi.Cancel r | DiningSettlementVO |  |
| internal | POST | /internal/food/settlements/create | ai.neargo.shop.food.server.api.InternalDiningCheckoutController.create | @RequestBody DiningCheckoutInternalApi.Create r | DiningSettlementVO |  |
| internal | POST | /internal/food/settlements/finalize | ai.neargo.shop.food.server.api.InternalDiningCheckoutController.finalizeCheckout | @RequestBody DiningCheckoutInternalApi.Reference r | DiningSettlementVO |  |
| internal | POST | /internal/food/settlements/get | ai.neargo.shop.food.server.api.InternalDiningCheckoutController.get | @RequestBody DiningCheckoutInternalApi.Reference r | DiningSettlementVO |  |
| internal | POST | /internal/food/settlements/pay | ai.neargo.shop.food.server.api.InternalDiningCheckoutController.pay | @RequestBody DiningCheckoutInternalApi.Pay r | DiningSettlementVO |  |
| internal | POST | /internal/food/settlements/query | ai.neargo.shop.food.server.api.InternalDiningCheckoutController.query | @RequestBody DiningCheckoutInternalApi.Reference r | DiningSettlementVO |  |
| internal | POST | /internal/food/settlements/recover-original | ai.neargo.shop.food.server.api.InternalDiningCheckoutController.recoverOriginal | @RequestBody DiningCheckoutInternalApi.Original request | DiningSettlementVO |  |
| internal | POST | /internal/food/settlements/staff/get | ai.neargo.shop.food.server.api.InternalDiningCheckoutController.staffGet | @RequestBody DiningCheckoutInternalApi.StaffGet r | DiningSettlementVO |  |
| internal | POST | /internal/food/station-runtime/acknowledge | ai.neargo.shop.food.server.api.InternalFoodStationRuntimeController.acknowledge | @RequestBody FoodStationRuntimeInternalApi.Receipt c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/station-runtime/claim | ai.neargo.shop.food.server.api.InternalFoodStationRuntimeController.claim | @RequestBody FoodStationRuntimeInternalApi.Poll c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/station-runtime/control | ai.neargo.shop.food.server.api.InternalFoodStationRuntimeController.control | @RequestBody FoodStationRuntimeInternalApi.Control c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/station-runtime/require-running | ai.neargo.shop.food.server.api.InternalFoodStationRuntimeController.running | @RequestBody FoodStationRuntimeInternalApi.Scope c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/station-runtime/state | ai.neargo.shop.food.server.api.InternalFoodStationRuntimeController.state | @RequestBody FoodStationRuntimeInternalApi.Scope c; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/stations/create | ai.neargo.shop.food.server.api.InternalStationController.createStation | @Valid @RequestBody StationInternalApi.CreateCommand c | StationMutationVO |  |
| internal | POST | /internal/food/stations/get | ai.neargo.shop.food.server.api.InternalStationController.getStation | @Valid @RequestBody StationInternalApi.GetQuery c | GetStationVO |  |
| internal | POST | /internal/food/stations/get-routes | ai.neargo.shop.food.server.api.InternalStationController.getRoutes | @Valid @RequestBody StationInternalApi.GetQuery c | StationRoutesVO |  |
| internal | POST | /internal/food/stations/list | ai.neargo.shop.food.server.api.InternalStationController.listStations | @Valid @RequestBody StationInternalApi.Lookup c | PageData<StationVO> |  |
| internal | POST | /internal/food/stations/replace-routes | ai.neargo.shop.food.server.api.InternalStationController.replaceRoutes | @Valid @RequestBody StationInternalApi.RoutesCommand c | OperationVO |  |
| internal | POST | /internal/food/stations/update | ai.neargo.shop.food.server.api.InternalStationController.updateStation | @Valid @RequestBody StationInternalApi.UpdateCommand c | StationMutationVO |  |
| internal | POST | /internal/food/store-policy/create | ai.neargo.shop.food.server.api.InternalStoreOperationPolicyController.createPolicy | @Valid @RequestBody StorePolicyPublicationInternalApi.Command command | OperationVO |  |
| internal | POST | /internal/food/store-policy/get | ai.neargo.shop.food.server.api.InternalStoreOperationPolicyController.getPolicy | @Valid @RequestBody StoreOperationInternalApi.Lookup request | StoreOperationPolicyResultVO |  |
| internal | POST | /internal/food/support/cases | ai.neargo.shop.food.server.api.InternalFoodSupportController.cases | @Valid @RequestBody FoodSupportInternalApi.CasesQuery request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/support/readiness | ai.neargo.shop.food.server.api.InternalFoodSupportController.readiness | @Valid @RequestBody FoodSupportInternalApi.ReadinessQuery request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/support/source | ai.neargo.shop.food.server.api.InternalFoodSupportController.evidence | @Valid @RequestBody FoodSupportInternalApi.SourceQuery request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/food/table-areas/create | ai.neargo.shop.food.server.api.InternalTableAreaController.createTableArea | @Valid @RequestBody TableInternalApi.CreateCommand c | TableAreaResultVO |  |
| internal | POST | /internal/food/table-areas/list | ai.neargo.shop.food.server.api.InternalTableAreaController.listTableAreas | @Valid @RequestBody TableInternalApi.Lookup c | PageData<TableAreaVO> |  |
| internal | POST | /internal/food/table-areas/save | ai.neargo.shop.food.server.api.InternalTableAreaController.saveTableArea | @Valid @RequestBody TableInternalApi.SaveCommand c | TableAreaResultVO |  |
| internal | POST | /internal/food/table-areas/set-status | ai.neargo.shop.food.server.api.InternalTableAreaController.setStatusTableArea | @Valid @RequestBody TableInternalApi.StatusCommand c | TableAreaResultVO |  |
| internal | POST | /internal/food/table-operations/get | ai.neargo.shop.food.server.api.InternalTableOperationController.operation | @Valid @RequestBody CheckInternalApi.OperationLookup c | OperationResultVO |  |
| internal | POST | /internal/food/table-tokens/revoke | ai.neargo.shop.food.server.api.InternalDiningSessionController.revoke | @Valid @RequestBody DiningSessionInternalApi.RevokeCommand c | OperationResultVO |  |
| internal | POST | /internal/food/table-tokens/rotate | ai.neargo.shop.food.server.api.InternalDiningSessionController.rotate | @Valid @RequestBody DiningSessionInternalApi.RotateCommand c | TableTokenVO |  |
| internal | POST | /internal/food/tables/archive | ai.neargo.shop.food.server.api.InternalTableController.archiveTable | @Valid @RequestBody TableInternalApi.TableArchiveCommand c | OperationResultVO |  |
| internal | POST | /internal/food/tables/create | ai.neargo.shop.food.server.api.InternalTableController.createTable | @Valid @RequestBody TableInternalApi.TableCreateCommand c | TableResultVO |  |
| internal | POST | /internal/food/tables/get | ai.neargo.shop.food.server.api.InternalTableController.getTable | @Valid @RequestBody TableInternalApi.TableIdentity c | TableResultVO |  |
| internal | POST | /internal/food/tables/list | ai.neargo.shop.food.server.api.InternalTableController.listTables | @Valid @RequestBody TableInternalApi.TableLookup c | PageData<TableVO> |  |
| internal | POST | /internal/food/tables/save | ai.neargo.shop.food.server.api.InternalTableController.saveTable | @Valid @RequestBody TableInternalApi.TableSaveCommand c | TableResultVO |  |
| internal | POST | /internal/food/tables/set-status | ai.neargo.shop.food.server.api.InternalTableController.setStatusTable | @Valid @RequestBody TableInternalApi.TableStatusCommand c | TableResultVO |  |
| internal | POST | /internal/food/tables/share | ai.neargo.shop.food.server.api.InternalTableSharingController.share | @Valid @RequestBody CheckInternalApi.ShareCommand c | CheckResultVO |  |
| internal | POST | /internal/food/transaction-recovery | ai.neargo.shop.food.server.api.InternalDiningTransactionRecoveryController.recover | @RequestBody DiningTransactionRecoveryInternalApi.Scan request | DiningTransactionRecoveryService.Report |  |
| internal | POST | /internal/inventory/demand-plan/accept-unpaid | ai.neargo.shop.inventory.api.internal.InternalInventoryDemandPlanController.accept | @RequestBody byte[] b; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t | ResponseEntity<?> |  |
| internal | POST | /internal/inventory/demand-plan/allocate-paid | ai.neargo.shop.inventory.api.internal.InternalInventoryDemandPlanController.allocate | @RequestBody byte[] b; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t | ResponseEntity<?> |  |
| internal | POST | /internal/inventory/demand-plan/issue | ai.neargo.shop.inventory.api.internal.InternalInventoryDemandPlanController.issue | @RequestBody byte[] b; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t | ResponseEntity<?> |  |
| internal | POST | /internal/inventory/demand-plan/issue-delta | ai.neargo.shop.inventory.api.internal.InternalInventoryDemandPlanController.issueDelta | @RequestBody byte[] b; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t | ResponseEntity<?> |  |
| internal | POST | /internal/inventory/demand-plan/query | ai.neargo.shop.inventory.api.internal.InternalInventoryDemandPlanController.query | @RequestBody byte[] b; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t | ResponseEntity<?> |  |
| internal | POST | /internal/inventory/demand-plan/release | ai.neargo.shop.inventory.api.internal.InternalInventoryDemandPlanController.release | @RequestBody byte[] b; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t | ResponseEntity<?> |  |
| internal | POST | /internal/inventory/demand-plan/reserve | ai.neargo.shop.inventory.api.internal.InternalInventoryDemandPlanController.reserve | @RequestBody byte[] b; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t | ResponseEntity<?> |  |
| internal | POST | /internal/inventory/demand-plan/reverse | ai.neargo.shop.inventory.api.internal.InternalInventoryDemandPlanController.reverse | @RequestBody byte[] b; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t | ResponseEntity<?> |  |
| internal | POST | /internal/inventory/demand-plan/supplement | ai.neargo.shop.inventory.api.internal.InternalInventoryDemandPlanController.supplement | @RequestBody byte[] b; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t | ResponseEntity<?> |  |
| internal | POST | /internal/inventory/production/consume | ai.neargo.shop.inventory.api.internal.InternalInventoryProductionController.consume | @RequestBody(required = false) byte[] body; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/inventory/production/query | ai.neargo.shop.inventory.api.internal.InternalInventoryProductionController.query | @RequestBody(required = false) byte[] body; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/inventory/production/reverse | ai.neargo.shop.inventory.api.internal.InternalInventoryProductionController.reverse | @RequestBody(required = false) byte[] body; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/printing/acknowledge | ai.neargo.shop.printing.api.internal.InternalPrintController.acknowledge | @RequestBody PrintInternalApi.AckCommand request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/printing/bindDevice | ai.neargo.shop.printing.api.internal.InternalPrintController.bindDevice | @RequestBody PrintInternalApi.DeviceCommand request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/printing/claim | ai.neargo.shop.printing.api.internal.InternalPrintController.claim | @RequestBody PrintInternalApi.DeviceQuery request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/printing/configuration | ai.neargo.shop.printing.api.internal.InternalPrintController.configuration | @RequestBody Scope scope; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/printing/deviceRecovery | ai.neargo.shop.printing.api.internal.InternalPrintController.deviceRecovery | @RequestBody RecoveryQuery request; @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/printing/enqueue | ai.neargo.shop.printing.api.internal.InternalPrintController.enqueue | @RequestBody PrintInternalApi.EnqueueCommand request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/printing/get | ai.neargo.shop.printing.api.internal.InternalPrintController.get | @RequestBody PrintInternalApi.JobQuery request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/printing/heartbeat | ai.neargo.shop.printing.api.internal.InternalPrintController.heartbeat | @RequestBody PrintInternalApi.HeartbeatCommand request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/printing/list | ai.neargo.shop.printing.api.internal.InternalPrintController.list | @RequestBody PrintInternalApi.ListQuery request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/printing/publishTemplate | ai.neargo.shop.printing.api.internal.InternalPrintController.publishTemplate | @RequestBody PrintInternalApi.TemplateCommand request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/printing/putRoute | ai.neargo.shop.printing.api.internal.InternalPrintController.putRoute | @RequestBody PrintInternalApi.RouteCommand request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/printing/reprint | ai.neargo.shop.printing.api.internal.InternalPrintController.reprint | @RequestBody PrintInternalApi.ReprintCommand request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/printing/resolveDeviceUncertainty | ai.neargo.shop.printing.api.internal.InternalPrintController.resolveDeviceUncertainty | @RequestBody ResolveDeviceCommand request; @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/printing/retry | ai.neargo.shop.printing.api.internal.InternalPrintController.retry | @RequestBody PrintInternalApi.JobQuery request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/printing/revokeDevice | ai.neargo.shop.printing.api.internal.InternalPrintController.revokeDevice | @RequestBody PrintInternalApi.RevokeDeviceCommand request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/trade/industry-after-sales/apply | ai.neargo.shop.portal.internal.InternalIndustryAfterSaleController.apply | @RequestBody IndustryAfterSalePort.Apply request; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/trade/industry-after-sales/cancel | ai.neargo.shop.portal.internal.InternalIndustryAfterSaleController.cancel | @RequestBody IndustryAfterSalePort.Reference reference; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/trade/industry-after-sales/get | ai.neargo.shop.portal.internal.InternalIndustryAfterSaleController.get | @RequestBody IndustryAfterSalePort.Reference reference; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| internal | POST | /internal/trade/industry-after-sales/query-apply | ai.neargo.shop.portal.internal.InternalIndustryAfterSaleController.queryApply | @RequestBody IndustryAfterSalePort.QueryApply query; @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given | ResponseEntity<?> |  |
| mp | GET | /mp/x/food/batches/{batchNo} | ai.neargo.shop.food.api.mp.MpOrderBatchController.get | @PathVariable @NotBlank @Size(max = 32) String batchNo; @RequestParam(required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token; HttpServletRequest request | DiningOrderBatchVO |  |
| mp | POST | /mp/x/food/batches/{batchNo}/settlements | ai.neargo.shop.food.api.mp.MpSettlementController.create | @PathVariable @NotBlank @Size(max = 32) String batchNo; @Valid @RequestBody CreateDiningSettlementReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | DiningSettlementVO |  |
| mp | GET | /mp/x/food/carts/{cartNo} | ai.neargo.shop.food.api.mp.MpCartController.customerGetCart | @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo; @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token; HttpServletRequest request | CartResultVO |  |
| mp | GET | /mp/x/food/carts/{cartNo}/delivery | ai.neargo.shop.food.api.mp.MpDiningDeliveryController.get | @PathVariable @NotBlank @Size(max = 32) String cartNo; @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String session; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token | DeliverySnapshot |  |
| mp | PUT | /mp/x/food/carts/{cartNo}/delivery | ai.neargo.shop.food.api.mp.MpDiningDeliveryController.save | @PathVariable @NotBlank @Size(max = 32) String cartNo; @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String session; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token; @Valid @RequestBody SaveReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key | DeliverySnapshot |  |
| mp | PUT | /mp/x/food/carts/{cartNo}/lines/{sourceLineRef} | ai.neargo.shop.food.api.mp.MpCartController.customerReplaceLine | @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo; @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef; @Valid @RequestBody ReplaceCartLineReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token; HttpServletRequest request | CartVersionVO |  |
| mp | POST | /mp/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive | ai.neargo.shop.food.api.mp.MpCartController.customerArchiveLine | @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo; @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef; @Valid @RequestBody CartVersionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token; HttpServletRequest request | CartVersionVO |  |
| mp | GET | /mp/x/food/catalog | ai.neargo.shop.food.api.mp.MpCatalogController.read | @RequestParam("storeNo") @NotBlank @Size(max=32) String store; @RequestParam("orderSource") @NotBlank String source; @RequestParam("fulfillment") @NotBlank String fulfillment; @RequestParam(value="sessionNo", required=false) @Size(max=32) String session; @RequestHeader(value="X-Food-Session", required=false) @Size(max=128) String access; HttpServletRequest request | CatalogVO |  |
| mp | GET | /mp/x/food/quotes/{quoteNo} | ai.neargo.shop.food.api.mp.MpDiningPricingController.get | @PathVariable @NotBlank @Size(max = 32) String quoteNo; @RequestParam @NotBlank @Size(max = 32) String cartNo; @RequestParam @Min(0) long version; @RequestParam(required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token; HttpServletRequest request | QuoteVO |  |
| mp | POST | /mp/x/food/quotes/{quoteNo}:confirm | ai.neargo.shop.food.api.mp.MpDiningPricingController.confirm | @PathVariable @NotBlank @Size(max = 32) String quoteNo; @Valid @RequestBody DiningQuoteConfirmReq body; @RequestParam(required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token; HttpServletRequest request | QuoteVO |  |
| mp | GET | /mp/x/food/returns/{returnNo} | ai.neargo.shop.food.api.mp.MpDiningReturnController.get | @PathVariable @NotBlank @Size(max = 32) String returnNo; HttpServletRequest request | DiningReturnVO |  |
| mp | POST | /mp/x/food/returns/{returnNo}:cancel | ai.neargo.shop.food.api.mp.MpDiningReturnController.cancel | @PathVariable @NotBlank @Size(max = 32) String returnNo; @RequestBody(required = false) byte[] body; HttpServletRequest request | DiningReturnVO |  |
| mp | POST | /mp/x/food/sessions | ai.neargo.shop.food.api.mp.MpDiningSessionController.startSession | @Valid @RequestBody StartDiningSessionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key | DiningSessionVO |  |
| mp | POST | /mp/x/food/sessions/{sessionNo}:end | ai.neargo.shop.food.api.mp.MpDiningSessionController.endSession | @PathVariable("sessionNo") @NotBlank @Size(max=32) String session; @RequestHeader("X-Food-Session") @NotBlank @Size(max=128) String access; @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key | OperationResultVO |  |
| mp | GET | /mp/x/food/settlements/{settlementNo} | ai.neargo.shop.food.api.mp.MpSettlementController.get | @PathVariable @NotBlank @Size(max = 32) String settlementNo; HttpServletRequest request | DiningSettlementVO |  |
| mp | POST | /mp/x/food/settlements/{settlementNo}:cancel | ai.neargo.shop.food.api.mp.MpSettlementController.cancel | @PathVariable @NotBlank @Size(max = 32) String settlementNo; @Valid @RequestBody DiningSettlementVersionReq body; HttpServletRequest request | DiningSettlementVO |  |
| mp | POST | /mp/x/food/settlements/{settlementNo}:finalize | ai.neargo.shop.food.api.mp.MpSettlementController.finalizeCheckout | @PathVariable @NotBlank @Size(max = 32) String settlementNo; HttpServletRequest request | DiningSettlementVO |  |
| mp | POST | /mp/x/food/settlements/{settlementNo}:pay | ai.neargo.shop.food.api.mp.MpSettlementController.pay | @PathVariable @NotBlank @Size(max = 32) String settlementNo; @Valid @RequestBody PayDiningSettlementReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | DiningSettlementVO |  |
| mp | POST | /mp/x/food/stores/{storeNo}/batches | ai.neargo.shop.food.api.mp.MpOrderBatchController.place | @PathVariable @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody CreateDiningOrderReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; @RequestParam(required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token; HttpServletRequest request | DiningOrderBatchVO |  |
| mp | POST | /mp/x/food/stores/{storeNo}/carts | ai.neargo.shop.food.api.mp.MpCartController.customerCreateCart | @PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody CreateCustomerCartReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token; HttpServletRequest request | CartResultVO |  |
| mp | POST | /mp/x/food/stores/{storeNo}/dining-device-sessions | ai.neargo.shop.food.api.mp.MpDiningDeviceSessionController.create | @PathVariable @NotBlank @Size(max = 32) String storeNo; @RequestHeader("X-Food-Device") @NotBlank @Size(max = 32) String device; @RequestHeader("X-Food-Device-Credential") @NotBlank @Size(max = 128) String credential; @Valid @RequestBody DeviceSessionReq req; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key | SessionCredentialVO |  |
| mp | POST | /mp/x/food/stores/{storeNo}/quotes | ai.neargo.shop.food.api.mp.MpDiningPricingController.quote | @PathVariable @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody DiningQuoteReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; @RequestParam(required = false) @Size(max = 32) String sessionNo; @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token; HttpServletRequest request | QuoteVO |  |
| mp | POST | /mp/x/food/stores/{storeNo}/returns | ai.neargo.shop.food.api.mp.MpDiningReturnController.apply | @PathVariable @NotBlank @Size(max = 32) String storeNo; @Valid @RequestBody DiningReturnApplyReq body; @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key; HttpServletRequest request | DiningReturnVO |  |
| ops | GET | /ops/x/food/support/cases | ai.neargo.shop.food.api.ops.OpsFoodSupportController.cases | @RequestParam @NotBlank @Size(max = 32) String entityNo; @RequestParam @NotBlank @Size(max = 32) String storeNo; @RequestParam(required = false) String status | List<CaseVO> | @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')") |
| ops | GET | /ops/x/food/support/readiness | ai.neargo.shop.food.api.ops.OpsFoodSupportController.readiness | @RequestParam @NotBlank @Size(max = 32) String entityNo; @RequestParam @NotBlank @Size(max = 32) String storeNo | ReadinessVO | @PreAuthorize("@perm.can('" + Perms.SYSTEM_INDUSTRY_READ + "')") |
| ops | GET | /ops/x/food/support/source | ai.neargo.shop.food.api.ops.OpsFoodSupportController.evidence | @RequestParam @NotBlank @Size(max = 32) String entityNo; @RequestParam @NotBlank @Size(max = 32) String storeNo; @RequestParam @NotBlank @Size(max = 16) String kind; @RequestParam @NotBlank @Size(max = 64) String sourceNo | Evidence | @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')") |

### 实际record DTO字段（原声明，含校验注解）

| 源码类 | record | 字段声明 |
|---|---|---|
| ai.neargo.shop.food.dto.ActionLogVO | ActionLogVO | String actionNo<br>String action<br>String actorRef<br>Instant occurredAt<br>String reason |
| ai.neargo.shop.food.dto.ArchiveTableReq | ArchiveTableReq | @NotNull @Min(0) Long version<br>@NotBlank @Size(max=255) String reason |
| ai.neargo.shop.food.dto.AuthorizeCartSessionReq | AuthorizeCartSessionReq | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version |
| ai.neargo.shop.food.dto.BundleChoiceInput | BundleChoiceInput | @NotBlank @Size(max=32) String choiceNo<br>@NotBlank @Size(max=32) String groupNo<br>@NotBlank @Size(max=32) String skuNo<br>@NotNull BigDecimal unitsPerPick<br>@NotBlank @Size(max=24) String priceMode<br>@NotNull BigDecimal defaultQty<br>@NotNull BigDecimal minQty<br>@NotNull BigDecimal maxQty<br>@NotNull @Min(0) Integer sortNo |
| ai.neargo.shop.food.dto.BundleConfigVO | BundleConfigVO | String configNo<br>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version<br>String status<br>Data data |
| ai.neargo.shop.food.dto.BundleConfigVO | Data | @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long configVersion<br>String publishedRevisionNo<br>List<BundleGroupInput> groups<br>List<BundleChoiceInput> choices |
| ai.neargo.shop.food.dto.BundleGroupInput | BundleGroupInput | @NotBlank @Size(max=32) String groupNo<br>@NotBlank @Size(max=64) String name<br>@NotNull BigDecimal minQty<br>@NotNull BigDecimal maxQty<br>@Size(max=32) String baseSkuNo<br>@NotNull @Min(0) Integer sortNo |
| ai.neargo.shop.food.dto.BundleMutationVO | BundleMutationVO | String configNo<br>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version |
| ai.neargo.shop.food.dto.BundlePublicationSnapshot | BundlePublicationSnapshot | int contentSchema<br>String configKind<br>String configNo<br>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long sourceVersion<br>BundleConfigVO bundle<br>List<SkuIdentityFact> skuFacts<br>String approvalRef |
| ai.neargo.shop.food.dto.BusinessCloseContracts | DayVO | String dayNo<br>LocalDate businessDate<br>String policyNo<br>LocalDateTime startsAt<br>LocalDateTime endsAt<br>String status<br>String currentCloseRefs<br>long version |
| ai.neargo.shop.food.dto.BusinessCloseContracts | Issue | String kind<br>String sourceRef |
| ai.neargo.shop.food.dto.BusinessCloseContracts | ReportVO | LocalDate businessDate<br>String currency<br>long batchCount<br>Map<String, BigDecimal> quantitiesByUnit<br>Map<String, BigDecimal> originalOrderServiceQuantitiesByActionUnit<br>String returnAndServiceScope<br>long settlementAttemptCount<br>long requestedDueMinor<br>long returnCount<br>String salesCompleteness<br>String settlementCompleteness<br>String refundCompleteness<br>List<Issue> issues<br>String sourceHash<br>@JsonInclude(JsonInclude.Include.NON_NULL) List<OrderReceivable> originalOrderReceivables<br>@JsonInclude(JsonInclude.Include.NON_NULL) Long effectiveReceivableMinor<br>@JsonInclude(JsonInclude.Include.NON_NULL) Long receivableAdjustmentMinor |
| ai.neargo.shop.food.dto.BusinessCloseContracts | OrderReceivable | String batchNo<br>String orderNo<br>String subOrderNo<br>String currency<br>long originalMinor<br>long effectiveMinor<br>long adjustmentMinor<br>String amendmentSourceHash<br>String evidenceHash |
| ai.neargo.shop.food.dto.BusinessCloseContracts | CloseVO | String closeNo<br>String dayNo<br>String currency<br>int revision<br>String status<br>String snapshotHash<br>String previousCloseNo<br>String signedBy<br>LocalDateTime signedAt<br>ReportVO report<br>long version |
| ai.neargo.shop.food.dto.BusinessCloseContracts | PreviewReq | @NotNull LocalDate businessDate<br>@NotBlank @Pattern(regexp = "[A-Z]{3}") String currency |
| ai.neargo.shop.food.dto.BusinessCloseContracts | SignReq | @NotBlank @Size(max = 32) String closeNo<br>@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String snapshotHash |
| ai.neargo.shop.food.dto.BusinessCloseContracts | ExceptionVO | String exceptionNo<br>String closeNo<br>String kind<br>String sourceRef<br>String status<br>String ownerNo<br>String nextDayNo<br>String reason<br>String proposedBy<br>String approvedBy<br>LocalDateTime approvedAt<br>String approvalRef<br>long version |
| ai.neargo.shop.food.dto.BusinessCloseContracts | ProposeCarryReq | @NotNull @Min(0) Long version<br>@NotBlank @Size(max = 32) String ownerNo<br>@NotBlank @Size(max = 32) String nextDayNo<br>@NotBlank @Size(max = 255) String reason |
| ai.neargo.shop.food.dto.BusinessCloseContracts | ApproveCarryReq | @NotNull @Min(0) Long version |
| ai.neargo.shop.food.dto.BusinessCloseContracts | AdjustmentReq | @NotBlank @Size(max = 32) String originalCloseNo<br>@NotBlank @Size(max = 32) String currentDayNo<br>@NotBlank @Size(max = 64) String coreReference<br>@NotBlank @Size(max = 255) String reason<br>@NotBlank @Size(max = 64) String approvalRef |
| ai.neargo.shop.food.dto.BusinessOperationsContracts | Source | @NotBlank String kind<br>@NotBlank String sourceNo |
| ai.neargo.shop.food.dto.BusinessOperationsContracts | Evidence | Source source<br>String status<br>long version<br>String sourceHash<br>boolean terminal<br>String currentOwner |
| ai.neargo.shop.food.dto.BusinessOperationsContracts | HandoverReq | @NotBlank String receiverNo<br>@NotEmpty @Size(max = 100) List<@Valid Source> sources<br>@NotBlank @Size(max = 255) String reason |
| ai.neargo.shop.food.dto.BusinessOperationsContracts | HandoverVO | String handoverNo<br>String fromActor<br>String receiverNo<br>String status<br>String sourceHash<br>long version |
| ai.neargo.shop.food.dto.BusinessOperationsContracts | CaseReq | @NotNull @Valid Source source<br>@NotBlank String ownerNo<br>@NotBlank @Size(max = 255) String reason |
| ai.neargo.shop.food.dto.BusinessOperationsContracts | CaseVO | String caseNo<br>Source source<br>String ownerNo<br>String status<br>String sourceHash<br>String resolutionHash<br>long version |
| ai.neargo.shop.food.dto.BusinessOperationsContracts | DecisionReq | @Min(0) long version<br>@NotBlank @Size(max = 255) String reason |
| ai.neargo.shop.food.dto.BusinessReturnApprovalContracts | ApproveReq | Long version<br>String reason<br>String replacementCartNo |
| ai.neargo.shop.food.dto.BusinessReturnApprovalContracts | ReplacementReq | Long version<br>String replacementBatchNo<br>String reason |
| ai.neargo.shop.food.dto.BusinessReturnApprovalContracts | ApprovalVO | String approvalNo<br>String returnNo<br>long version<br>String status<br>boolean pending<br>boolean originalPayableChanged<br>Receipt originalAmendment<br>String replacementBatchNo<br>String replacementOrderNo |
| ai.neargo.shop.food.dto.BusinessReturnContracts | Line | @NotBlank @Size(max = 32) String foodLineNo<br>@JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty<br>String componentNo |
| ai.neargo.shop.food.dto.BusinessReturnContracts | CreateReq | @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long batchVersion<br>@NotNull @Size(min = 1, max = 256) List<@Valid Line> lines<br>@NotBlank @Size(max = 255) String reason |
| ai.neargo.shop.food.dto.BusinessReturnContracts | CancelReq | @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version<br>@NotBlank @Size(max = 255) String reason |
| ai.neargo.shop.food.dto.BusinessReturnContracts | ComponentSource | String sourceComponentRef<br>String kind<br>String allocationNo<br>Long orderItemId<br>BigDecimal originalQty<br>String unit<br>String coreSnapshotHash<br>Long allocationVersion<br>String componentNo<br>Long componentVersion |
| ai.neargo.shop.food.dto.BusinessReturnContracts | SourceItem | String foodLineNo<br>String sourceLineRef<br>String mainAllocationNo<br>Long mainOrderItemId<br>BigDecimal qty<br>BigDecimal originalQty<br>Long lineVersion<br>List<ComponentSource> components |
| ai.neargo.shop.food.dto.BusinessReturnContracts | OwnerRead | String checkedAt<br>Result original |
| ai.neargo.shop.food.dto.BusinessReturnContracts | Frozen | String kind<br>int schema<br>String entityNo<br>String storeNo<br>String batchNo<br>Long batchVersion<br>String cartNo<br>Long cartVersion<br>String bindingNo<br>Long bindingVersion<br>String buyerNo<br>String actorNo<br>String reason<br>String sourceHash<br>OwnerRead ownerRead<br>List<SourceItem> items |
| ai.neargo.shop.food.dto.BusinessReturnContracts | ItemVO | String returnItemNo<br>String foodLineNo<br>String sourceLineRef<br>BigDecimal qty<br>String kitchenDisposition<br>String mainAllocationNo |
| ai.neargo.shop.food.dto.BusinessReturnContracts | ReturnVO | String returnNo<br>String batchNo<br>String storeNo<br>long version<br>String status<br>String financialStatus<br>String kitchenStatus<br>String coreCaseRef<br>boolean refundRequested<br>boolean originalPayableChanged<br>String reason<br>String actorNo<br>List<ItemVO> items<br>Frozen source |
| ai.neargo.shop.food.dto.BusinessShareContracts | Person | @NotBlank @Size(max = 32) String personNo<br>@JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long amountMinor |
| ai.neargo.shop.food.dto.BusinessShareContracts | LineWeight | @NotBlank @Size(max = 32) String foodLineNo<br>@NotBlank @Size(max = 32) String personNo<br>@JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long weight |
| ai.neargo.shop.food.dto.BusinessShareContracts | CreateReq | @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long checkVersion<br>@NotBlank String mode<br>@NotNull @Size(min = 1, max = 50) List<@Valid Person> people<br>@Size(max = 1000) List<@Valid LineWeight> lineWeights<br>@NotBlank @Size(max = 255) String reason |
| ai.neargo.shop.food.dto.BusinessShareContracts | ConfirmReq | @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version<br>@NotBlank @Size(min = 64, max = 64) String sourceHash<br>@NotBlank @Size(min = 64, max = 64) String planHash |
| ai.neargo.shop.food.dto.BusinessShareContracts | CancelReq | @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version<br>@NotBlank @Size(max = 255) String reason |
| ai.neargo.shop.food.dto.BusinessShareContracts | MembershipScope | String membershipNo<br>String checkNo<br>String batchNo<br>Long version<br>LocalDateTime attachedAt<br>String operationNo |
| ai.neargo.shop.food.dto.BusinessShareContracts | BatchSource | String batchNo<br>String originalCheckNo<br>Long version<br>String bindingNo<br>Long bindingVersion<br>String orderNo<br>String storeOrderNo<br>String frozenRequestHash<br>List<MembershipScope> memberships<br>@JsonInclude(Include.NON_NULL) String amendmentSourceHash |
| ai.neargo.shop.food.dto.BusinessShareContracts | AllocationSource | String allocationNo<br>String batchNo<br>String foodLineNo<br>String sourceLineRef<br>String sourceComponentRef<br>Long orderItemId<br>BigDecimal originalQty<br>String unit<br>String coreSnapshotHash<br>Long allocationVersion<br>Long lineVersion<br>Long selectionVersion<br>String lineSnapshotHash<br>String currency<br>long originalAmountMinor<br>@JsonInclude(Include.NON_NULL) BigDecimal effectiveQty<br>@JsonInclude(Include.NON_NULL) Long effectiveAmountMinor<br>@JsonInclude(Include.NON_NULL) String amendmentSourceHash |
| ai.neargo.shop.food.dto.BusinessShareContracts | SourceSnapshot | String entityNo<br>String storeNo<br>String checkNo<br>Long checkVersion<br>String currency<br>long originalAmountMinor<br>List<BatchSource> batches<br>List<AllocationSource> allocations<br>@JsonInclude(Include.NON_NULL) Long effectiveAmountMinor |
| ai.neargo.shop.food.dto.BusinessShareContracts | ShareCell | String allocationNo<br>String foodLineNo<br>Long orderItemId<br>String personNo<br>long amountMinor |
| ai.neargo.shop.food.dto.BusinessShareContracts | PersonTotal | String personNo<br>long amountMinor |
| ai.neargo.shop.food.dto.BusinessShareContracts | Division | List<PersonTotal> people<br>List<ShareCell> cells |
| ai.neargo.shop.food.dto.BusinessShareContracts | PlanVO | String planNo<br>String checkNo<br>long version<br>String status<br>String storedStatus<br>String mode<br>String currency<br>long originalAmountMinor<br>String sourceHash<br>String planHash<br>boolean localSourcesCurrent<br>boolean settlementEligible<br>boolean financialAuthorityPending<br>SourceSnapshot source<br>Division division<br>CreateReq definition<br>String reason<br>String createdBy<br>String confirmedBy<br>String cancelledBy<br>@JsonInclude(Include.NON_NULL) Long effectiveAmountMinor |
| ai.neargo.shop.food.dto.CancelDiningOrderReq | CancelDiningOrderReq | @NotNull @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long version<br>@NotBlank @Size(max = 255) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String reason |
| ai.neargo.shop.food.dto.CartAccess | CartAccess | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String accessToken<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String buyerNo |
| ai.neargo.shop.food.dto.CartBundleChoiceInput | CartBundleChoiceInput | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String choiceNo<br>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal qty |
| ai.neargo.shop.food.dto.CartResultVO | CartResultVO | CartVO cart |
| ai.neargo.shop.food.dto.CartSelectionInput | CartSelectionInput | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String groupNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sourceKind<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sourceNo<br>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal qty<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long configVersion |
| ai.neargo.shop.food.dto.CartSessionGrantVO | CartSessionGrantVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String checkNo<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version |
| ai.neargo.shop.food.dto.CartVO | CartVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String cartNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String checkNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String buyerNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment<br>Instant scheduledAt<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>List<OrderLineInput> lines |
| ai.neargo.shop.food.dto.CartVersionReq | CartVersionReq | @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version |
| ai.neargo.shop.food.dto.CartVersionVO | CartVersionVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String cartNo<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version |
| ai.neargo.shop.food.dto.CatalogDependencyVO | CatalogDependencyVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String revisionNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configKind<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long revision<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String contentHash<br>OptionGroupConfigVO optionGroup<br>CatalogModifierVO modifier<br>BundleConfigVO bundle<br>CatalogRecipeVO recipe<br>List<CatalogSpecValueVO> specValues |
| ai.neargo.shop.food.dto.CatalogItemVO | CatalogItemVO | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuItemNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String categoryNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String categoryName<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String goodsNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String title<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String cover<br>DishVO dish<br>@JsonDeserialize(contentUsing = MaterialCatalogFacts.TextDeserializer.class) List<String> optionGroupNos<br>List<CatalogSkuVO> skus<br>@JsonInclude(JsonInclude.Include.NON_NULL) List<MenuSkuPriceInput> skuPrices |
| ai.neargo.shop.food.dto.CatalogMenuVO | CatalogMenuVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String revisionNo<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long revision<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name<br>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer priority<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String exclusiveGroup<br>List<CatalogDependencyVO> dependencies |
| ai.neargo.shop.food.dto.CatalogModifierVO | CatalogModifierVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String kind<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String salesSkuNo<br>CatalogSkuVO salesOffer |
| ai.neargo.shop.food.dto.CatalogQuery | CatalogQuery | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String channelBindingNo |
| ai.neargo.shop.food.dto.CatalogRecipeVO | CatalogRecipeVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long configVersion |
| ai.neargo.shop.food.dto.CatalogSkuVO | CatalogSkuVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String spec<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo<br>@JsonDeserialize(using=DishOfferFacts.MinorDeserializer.class) Long priceMinor<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String currency<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String pricingStatus<br>@JsonDeserialize(using=DishOfferFacts.AvailableDeserializer.class) Integer available<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stockStatus<br>@JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean onSale<br>@JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean restricted<br>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal remainingQuota<br>@JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean transactable<br>@JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean requiresSelectionQuote<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String reason |
| ai.neargo.shop.food.dto.CatalogSpecValueVO | CatalogSpecValueVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String valueNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String dimNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String label<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String dimName<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String valueType<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String usageType<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long valueVersion<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long dimVersion |
| ai.neargo.shop.food.dto.CatalogVO | CatalogVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment<br>@JsonFormat(shape=JsonFormat.Shape.STRING) @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant asOf<br>List<CatalogMenuVO> menus<br>List<CatalogItemVO> items |
| ai.neargo.shop.food.dto.CheckCollaborationContracts | CheckVersion | @NotBlank String checkNo<br>@Min(0) long version |
| ai.neargo.shop.food.dto.CheckCollaborationContracts | MoveReq | @NotNull @Valid CheckVersion source<br>@NotNull @Valid CheckVersion target<br>@NotEmpty @Size(max = 100) List<@NotBlank String> batchNos<br>@NotBlank @Size(max = 255) String reason |
| ai.neargo.shop.food.dto.CheckCollaborationContracts | MoveVO | String operationNo<br>String sourceCheckNo<br>String targetCheckNo<br>List<String> batchNos<br>long sourceVersion<br>long targetVersion<br>String status |
| ai.neargo.shop.food.dto.CheckCollaborationContracts | LineMoveReq | @NotNull @Valid CheckVersion source<br>@NotNull @Valid CheckVersion target<br>@NotEmpty @Size(max = 100) List<@NotBlank String> foodLineNos<br>@NotBlank @Size(max = 255) String reason |
| ai.neargo.shop.food.dto.CheckCollaborationContracts | LineMoveVO | String operationNo<br>String sourceCheckNo<br>String targetCheckNo<br>List<String> foodLineNos<br>String status |
| ai.neargo.shop.food.dto.CheckCollaborationContracts | ReceivableBatch | String batchNo<br>String orderNo<br>String subOrderNo<br>String currency<br>Long originalMinor<br>Long effectiveMinor<br>Long adjustmentMinor<br>String amendmentSourceHash |
| ai.neargo.shop.food.dto.CheckCollaborationContracts | ReceivablePreview | String checkNo<br>List<ReceivableBatch> batches<br>String sourceHash<br>boolean financialActionAllowed |
| ai.neargo.shop.food.dto.CheckCollaborationContracts | FreezeReq | @Min(0) long checkVersion<br>@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String sourceHash<br>@NotBlank @Size(max = 255) String reason |
| ai.neargo.shop.food.dto.CheckCollaborationContracts | FreezeVO | String freezeNo<br>String checkNo<br>String sourceHash<br>String status<br>long version |
| ai.neargo.shop.food.dto.CheckDetailVO | CheckDetailVO | CheckVO check<br>List<SeatReq> seats<br>List<OccupancyVO> occupancies |
| ai.neargo.shop.food.dto.CheckResultVO | CheckResultVO | CheckVO check |
| ai.neargo.shop.food.dto.CheckVO | CheckVO | String checkNo<br>String storeNo<br>LocalDate businessDate<br>String status<br>String payMode<br>int partySize<br>List<String> tableNos<br>long version |
| ai.neargo.shop.food.dto.ClearCheckReq | ClearCheckReq | @NotNull @Min(0) Long version<br>@NotBlank @Size(max=255) String reason<br>@Size(max=64) String approvalRef<br>@NotNull @Size(max=100) List<@NotBlank @Size(max=32) String> occupancyNos |
| ai.neargo.shop.food.dto.CombineTablesReq | CombineTablesReq | @NotNull @Min(0) Long version<br>@NotEmpty @Valid @Size(max=100) List<TableVersionReq> tableVersions<br>@NotBlank @Size(max=255) String reason |
| ai.neargo.shop.food.dto.ConfigRevisionContentVO | ConfigRevisionContentVO | ConfigRevisionVO publication<br>String definitionSnapshot |
| ai.neargo.shop.food.dto.ConfigRevisionVO | ConfigRevisionVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String revisionNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configKind<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) long revision<br>OffsetDateTime effectiveAt<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String contentHash<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status |
| ai.neargo.shop.food.dto.CreateBundleReq | CreateBundleReq | @NotBlank @Size(max=32) String skuNo |
| ai.neargo.shop.food.dto.CreateBundleResultVO | CreateBundleResultVO | String configNo<br>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version<br>String status |
| ai.neargo.shop.food.dto.CreateCartReq | CreateCartReq | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String checkNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String buyerNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment<br>Instant scheduledAt |
| ai.neargo.shop.food.dto.CreateCustomerCartReq | CreateCustomerCartReq | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String checkNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment<br>Instant scheduledAt |
| ai.neargo.shop.food.dto.CreateDiningOrderReq | CreateDiningOrderReq | @NotBlank @Size(max = 32) String cartNo<br>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version<br>@NotBlank @Size(max = 32) String quoteNo<br>@NotBlank @Pattern(regexp = "[0-9a-f]{64}") String confirmationDigest<br>@NotBlank @Pattern(regexp = "PREPAY\|POSTPAY") String payMode |
| ai.neargo.shop.food.dto.CreateDiningSettlementReq | CreateDiningSettlementReq | @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String confirmationDigest |
| ai.neargo.shop.food.dto.CreateMenuChannelBindingReq | CreateMenuChannelBindingReq | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String provider<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String channelAccountRef<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String externalStoreRef |
| ai.neargo.shop.food.dto.CreateMenuReq | CreateMenuReq | @NotBlank @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name<br>@NotBlank @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuType<br>@NotBlank @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String timezone<br>@NotNull @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer priority<br>@Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String exclusiveGroup |
| ai.neargo.shop.food.dto.CreateMenuResultVO | CreateMenuResultVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status |
| ai.neargo.shop.food.dto.CreateModifierReq | CreateModifierReq | @NotBlank @Size(max=64) String name<br>@NotBlank @Size(max=16) String kind<br>@Size(max=32) String salesSkuNo<br>@NotNull @Size(max=100) List<@NotNull @Valid ModifierComponentInput> components |
| ai.neargo.shop.food.dto.CreateModifierResultVO | CreateModifierResultVO | String configNo<br>long version<br>String status |
| ai.neargo.shop.food.dto.CreateOptionGroupReq | CreateOptionGroupReq | @NotBlank @Size(max=64) String name<br>@NotNull @DecimalMin("0") @Digits(integer=12, fraction=6) BigDecimal minQty<br>@NotNull @DecimalMin("0") @Digits(integer=12, fraction=6) BigDecimal maxQty |
| ai.neargo.shop.food.dto.CreateOptionGroupResultVO | CreateOptionGroupResultVO | String configNo<br>long version<br>String status |
| ai.neargo.shop.food.dto.CreatePolicyReq | CreatePolicyReq | @NotBlank @Size(max = 64) String timezone<br>@NotNull @Min(0) @Max(1439) Integer cutoverMinute<br>@NotNull Instant effectiveFrom<br>@NotBlank @Pattern(regexp = "PREPAY\|POSTPAY") String payPolicy<br>@NotNull @Size(max = 64) List<@NotBlank @Size(max = 64) String> capabilities<br>@Size(max = 64) String approvalRef<br>@NotNull @Min(0) Long version |
| ai.neargo.shop.food.dto.CreateRecipeReq | CreateRecipeReq | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeCode<br>@NotNull BigDecimal yieldQty<br>@NotBlank @Size(max=16) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String yieldUnit |
| ai.neargo.shop.food.dto.CreateStaffDiningOrderReq | CreateStaffDiningOrderReq | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @Size(max = 32) String buyerNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo<br>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String quoteNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String confirmationDigest<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Pattern(regexp = "POSTPAY") String payMode<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max = 32) String partyNo |
| ai.neargo.shop.food.dto.CreateTableAreaReq | CreateTableAreaReq | @NotBlank @Size(max=32) String code<br>@NotBlank @Size(max=64) String name<br>@NotNull Integer sortNo |
| ai.neargo.shop.food.dto.CreateTableReq | CreateTableReq | @NotBlank @Size(max=32) String areaNo<br>@NotBlank @Size(max=32) String code<br>@NotBlank @Size(max=64) String name<br>@NotNull @Min(1) Integer capacity<br>@NotNull Integer sortNo<br>@Size(max=32) String resourceNo |
| ai.neargo.shop.food.dto.DiningBatchSummaryVO | DiningBatchSummaryVO | String batchNo<br>String storeNo<br>String originalCheckNo<br>List<String> currentCheckNos<br>boolean membershipAuthoritative<br>String status<br>String orderSource<br>LocalDate businessDate<br>String fulfillment<br>String payMode<br>String quoteNo<br>String quoteHash<br>Instant createdAt<br>Instant acceptedAt<br>Instant scheduledAt<br>long version<br>List<OriginalBindingVO> originalBindings |
| ai.neargo.shop.food.dto.DiningBatchSummaryVO | OriginalBindingVO | String bindingNo<br>String paymentRootNo<br>String storeOrderNo<br>String bindingStatus |
| ai.neargo.shop.food.dto.DiningDeliveryContracts | SaveReq | @NotNull @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long cartVersion<br>@NotBlank @Size(max = 64) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String receiverName<br>@NotBlank @Size(max = 32) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String receiverPhone<br>@NotBlank @Size(max = 512) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String address<br>@Size(max = 255) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String deliveryInstruction<br>@JsonInclude(Include.NON_NULL) Integer latE6<br>@JsonInclude(Include.NON_NULL) Integer lngE6 |
| ai.neargo.shop.food.dto.DiningDeliveryContracts | DeliverySnapshot | String entityNo<br>String storeNo<br>String cartNo<br>long cartVersion<br>long revision<br>String receiverName<br>String receiverPhone<br>String address<br>String deliveryInstruction<br>@JsonInclude(Include.NON_NULL) Integer latE6<br>@JsonInclude(Include.NON_NULL) Integer lngE6 |
| ai.neargo.shop.food.dto.DiningIdentityContracts | RegisterDeviceReq | @NotBlank @Size(max = 32) String deviceNo<br>@JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version<br>@NotBlank @Size(max = 64) String displayName |
| ai.neargo.shop.food.dto.DiningIdentityContracts | VersionReq | @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version<br>@NotBlank @Size(max = 255) String reason |
| ai.neargo.shop.food.dto.DiningIdentityContracts | DeviceVO | String entityNo<br>String storeNo<br>String deviceNo<br>String displayName<br>String status<br>long generation<br>long version |
| ai.neargo.shop.food.dto.DiningIdentityContracts | DeviceCredentialVO | DeviceVO device<br>String rawCredential |
| ai.neargo.shop.food.dto.DiningIdentityContracts | StaffPartyReq | @Size(max = 32) String checkNo<br>@NotBlank @Size(max = 64) String displayName<br>@Min(60) @Max(86400) int lifetimeSeconds |
| ai.neargo.shop.food.dto.DiningIdentityContracts | DeviceSessionReq | @Min(60) @Max(86400) int lifetimeSeconds |
| ai.neargo.shop.food.dto.DiningIdentityContracts | PartyVO | String kind<br>String entityNo<br>String storeNo<br>String sessionNo<br>String deviceNo<br>String displayName<br>Instant expiresAt<br>List<String> scope<br>String checkNo<br>String buyerNo<br>long deviceGeneration<br>long version |
| ai.neargo.shop.food.dto.DiningIdentityContracts | SessionCredentialVO | PartyVO party<br>String accessToken |
| ai.neargo.shop.food.dto.DiningOrderBatchVO | DiningOrderBatchVO | String batchNo<br>String storeNo<br>String checkNo<br>String cartNo<br>long cartVersion<br>String quoteNo<br>String status<br>String payMode<br>LocalDate businessDate<br>String orderNo<br>String subOrderNo<br>String coreStatus<br>String currency<br>Long amountMinor<br>long version |
| ai.neargo.shop.food.dto.DiningPickupContracts | VerifyPickupReq | @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long expectedVersion<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Pattern(regexp = "[0-9]{6}") String verifyCode<br>String custodyOperationNo |
| ai.neargo.shop.food.dto.DiningPickupContracts | PickupVerificationVO | String batchNo<br>boolean localHandoverRecorded<br>Receipt originalFulfillment |
| ai.neargo.shop.food.dto.DiningPriceFacts | DiningPriceFacts | @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) long schemaVersion<br>Payload payload<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String sourceHash |
| ai.neargo.shop.food.dto.DiningPriceFacts | Payload | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String subjectNo<br>CartVO cart<br>List<MenuRef> menus<br>List<SkuIdentityFact> identities<br>List<Offer> offers<br>List<Restriction> restrictions<br>List<Unit> units<br>Composition composition<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String currency<br>@JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long amountMinor<br>@JsonInclude(Include.NON_NULL) DiningDeliveryContracts.DeliverySnapshot delivery<br>@JsonInclude(Include.NON_NULL) List<Source> feeSources |
| ai.neargo.shop.food.dto.DiningPriceFacts | Composition | List<CatalogMenuVO> menus<br>List<CatalogItemVO> items |
| ai.neargo.shop.food.dto.DiningPriceFacts | Unit | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String sourceLineNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String componentRef<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String kind<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String goodsNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String priceRevision<br>@JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quantity<br>@JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long unitPriceMinor<br>@JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long amountMinor |
| ai.neargo.shop.food.dto.DiningPriceFacts | MenuRef | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String revisionNo<br>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) long revision<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String contentHash<br>List<DependencyRef> dependencies |
| ai.neargo.shop.food.dto.DiningPriceFacts | DependencyRef | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String kind<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String configNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String revisionNo<br>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) long revision<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String contentHash |
| ai.neargo.shop.food.dto.DiningPriceFacts | Restriction | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@JsonDeserialize(using = StoreCardFacts.BooleanDeserializer.class) Boolean blocked<br>@JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal remainingQuota<br>@JsonDeserialize(using = StoreCardFacts.BooleanDeserializer.class) Boolean originalStockKnown<br>List<SalesRestrictionVO> rules |
| ai.neargo.shop.food.dto.DiningQuoteConfirmReq | DiningQuoteConfirmReq | @NotBlank @Size(max = 32) String cartNo<br>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version<br>@NotBlank @Pattern(regexp = "[0-9a-f]{64}") String confirmationDigest |
| ai.neargo.shop.food.dto.DiningQuoteReq | DiningQuoteReq | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo<br>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version<br>@Valid Selection selection |
| ai.neargo.shop.food.dto.DiningReportExportReq | DiningReportExportReq | @NotNull View view<br>LocalDate from<br>LocalDate to<br>LocalDate businessDate<br>@Size(max=30) List<String> storeNos<br>Integer metricVersion<br>@Size(max=64) Set<String> metricCodes<br>Integer limit<br>MeasureFilter measure |
| ai.neargo.shop.food.dto.DiningReturnApplyReq | DiningReturnApplyReq | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String batchNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String type<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String reason<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String reasonCode<br>@JsonDeserialize(contentUsing = MaterialCatalogFacts.TextDeserializer.class) List<String> images<br>List<Line> lines |
| ai.neargo.shop.food.dto.DiningReturnApplyReq | Line | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String sourceLineRef<br>@JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quantity |
| ai.neargo.shop.food.dto.DiningReturnVO | DiningReturnVO | String returnNo<br>String batchNo<br>String storeNo<br>String status<br>String financialStatus<br>String kitchenStatus<br>String coreCaseRef<br>String reason<br>List<Line> lines<br>IndustryAfterSalePort.Result financialCase |
| ai.neargo.shop.food.dto.DiningReturnVO | Line | String sourceLineRef<br>BigDecimal quantity<br>String kitchenDisposition |
| ai.neargo.shop.food.dto.DiningSessionTableVO | DiningSessionTableVO | String entityNo<br>String storeNo<br>String tableNo<br>Instant expiresAt |
| ai.neargo.shop.food.dto.DiningSessionVO | DiningSessionVO | String sessionNo<br>String accessToken<br>Instant expiresAt<br>String checkNo |
| ai.neargo.shop.food.dto.DiningSettlementVO | DiningSettlementVO | String settlementNo<br>String storeNo<br>String batchNo<br>String checkNo<br>String orderNo<br>String subOrderNo<br>String status<br>String currency<br>long dueMinor<br>String scopeHash<br>String confirmationDigest<br>long version<br>String batchStatus<br>String coreOrderStatus<br>PaymentVO payment<br>boolean readyToAccept |
| ai.neargo.shop.food.dto.DiningSettlementVersionReq | DiningSettlementVersionReq | @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version |
| ai.neargo.shop.food.dto.DisableMenuReq | DisableMenuReq | @NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>@NotBlank @Size(max=255) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String reason |
| ai.neargo.shop.food.dto.DishCatalogItemVO | DishCatalogItemVO | DishVO dish<br>GoodsSummary product |
| ai.neargo.shop.food.dto.DishDetailResultVO | DishDetailResultVO | DishVO dish<br>List<OptionGroupConfigVO> optionGroups<br>String coreCatalogRef<br>GoodsFact coreCatalog |
| ai.neargo.shop.food.dto.DishPresentation | DishPresentation | String displayName<br>List<LocalizedName> localizedNames<br>List<String> imageRefs<br>List<String> tags<br>List<String> allergenNotes |
| ai.neargo.shop.food.dto.DishPresentation | LocalizedName | String locale<br>String name |
| ai.neargo.shop.food.dto.DishResultVO | DishResultVO | DishVO dish |
| ai.neargo.shop.food.dto.DishVO | DishVO | String goodsNo<br>String dishKind<br>int prepSeconds<br>DishPresentation presentation<br>MeasurementPolicy measurement<br>long configVersion<br>long version |
| ai.neargo.shop.food.dto.ExceptionInput | ExceptionInput | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String localDate<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String mode<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String startTime<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String endTime<br>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer endDayOffset |
| ai.neargo.shop.food.dto.FoodFeeSourceContracts | BindReq | long expectedVersion<br>String checkNo<br>@NotBlank String ruleNo<br>@NotBlank String revisionNo<br>@NotNull Kind kind<br>@NotBlank String unit<br>BigDecimal packageQuantity<br>List<String> basisSourceRefs<br>String waiverEvidence<br>@NotBlank String reason<br>boolean enabled<br>String contractScopeNo |
| ai.neargo.shop.food.dto.FoodFeeSourceContracts | BindingVO | String bindingNo<br>String checkNo<br>String ruleNo<br>String revisionNo<br>Kind kind<br>String unit<br>BigDecimal packageQuantity<br>List<String> basisSourceRefs<br>String waiverEvidence<br>boolean enabled<br>long version<br>String contractScopeNo |
| ai.neargo.shop.food.dto.FoodKdsContracts | GrantReq | @NotBlank @Size(max = 32) String deviceNo<br>@NotBlank @Size(max = 32) String deviceActorNo<br>@NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 32) String> stationNos<br>@Min(60) @Max(86400) int lifetimeSeconds |
| ai.neargo.shop.food.dto.FoodKdsContracts | RevokeReq | @JsonProperty(value = "version", required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version<br>@NotBlank @Size(max = 255) String reason |
| ai.neargo.shop.food.dto.FoodKdsContracts | SubscriptionVO | String subscriptionNo<br>String entityNo<br>String storeNo<br>String deviceNo<br>String actorNo<br>List<String> stationNos<br>String expiresAt<br>String status<br>long version |
| ai.neargo.shop.food.dto.FoodKdsContracts | EventDraft | String eventNo<br>String entityNo<br>String storeNo<br>String stationNo<br>String eventType<br>String sourceNo<br>long sourceVersion<br>String payload<br>String occurredAt |
| ai.neargo.shop.food.dto.FoodKdsContracts | EventVO | long cursor<br>String eventNo<br>String stationNo<br>String eventType<br>String sourceNo<br>long sourceVersion<br>String payload<br>String occurredAt |
| ai.neargo.shop.food.dto.FoodKdsContracts | ReplayVO | String subscriptionNo<br>String entityNo<br>String storeNo<br>List<EventVO> events<br>long nextCursor<br>long watermark<br>boolean hasMore |
| ai.neargo.shop.food.dto.FoodStationRuntimeContracts | ControlReq | @JsonProperty(value = "version", required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version<br>@NotBlank String action<br>@NotBlank @Size(max = 255) String reason<br>@Size(max = 32) String deviceNo<br>@Size(max = 128) String voiceText |
| ai.neargo.shop.food.dto.FoodStationRuntimeContracts | StateVO | String stationNo<br>String status<br>long version |
| ai.neargo.shop.food.dto.FoodStationRuntimeContracts | CommandVO | String commandNo<br>String stationNo<br>String action<br>String status<br>long sourceVersion<br>String reason |
| ai.neargo.shop.food.dto.FoodStationRuntimeContracts | VoiceClaim | String commandNo<br>String stationNo<br>String deviceNo<br>String voiceText<br>String leaseToken<br>String leaseUntil |
| ai.neargo.shop.food.dto.FoodStationRuntimeContracts | VoiceReceipt | @NotBlank @Size(max = 32) String commandNo<br>@NotBlank @Size(max = 64) String leaseToken<br>@NotBlank String status<br>@Size(max = 64) String responseCode |
| ai.neargo.shop.food.dto.FoodStationRuntimeContracts | VoicePoll | VoiceClaim claim |
| ai.neargo.shop.food.dto.FoodSupportContracts | ReadinessVO | String deployment<br>boolean ready<br>boolean schemaValidated<br>String schemaVersion<br>List<String> missingCapabilities<br>Map<String, Integer> typedProviderCounts |
| ai.neargo.shop.food.dto.GetBundleVO | GetBundleVO | BundleConfigVO config |
| ai.neargo.shop.food.dto.GetPolicyQuery | GetPolicyQuery | @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at |
| ai.neargo.shop.food.dto.KitchenContracts | FrozenRoute | String skuNo<br>String stationNo<br>String taskRole<br>long routeVersion<br>String revisionNo<br>String contentHash |
| ai.neargo.shop.food.dto.KitchenContracts | FrozenRoutes | List<FrozenRoute> routes |
| ai.neargo.shop.food.dto.KitchenContracts | TaskActionReq | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max=24) String action<br>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty<br>@Size(max=255) String reason |
| ai.neargo.shop.food.dto.KitchenContracts | TaskDeltaReq | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version<br>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty<br>@Size(max=255) String reason |
| ai.neargo.shop.food.dto.KitchenContracts | RemakeReq | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version<br>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty<br>@NotBlank @Size(max=255) String reason |
| ai.neargo.shop.food.dto.KitchenContracts | HandoverLine | @NotBlank String foodLineNo<br>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty |
| ai.neargo.shop.food.dto.KitchenContracts | HandoverReq | @NotBlank String kind<br>@NotNull List<HandoverLine> lines<br>@NotBlank @Size(max=255) String reason<br>@Size(max=64) String recipientNo<br>@Size(max=128) String recipientRef |
| ai.neargo.shop.food.dto.KitchenContracts | PackingReq | @NotNull List<HandoverLine> lines<br>@NotBlank @Size(max=255) String reason |
| ai.neargo.shop.food.dto.KitchenContracts | CustodyReceiptVO | String operationNo<br>String entityNo<br>String storeNo<br>String batchNo<br>String orderNo<br>String storeOrderNo<br>String bindingNo<br>Long bindingVersion<br>Long batchVersion<br>String frozenRequestHash<br>String action<br>String kind<br>List<HandoverLine> lines<br>List<CustodySourceLine> sources<br>String recipientNo<br>String recipientRef<br>String actorNo<br>String recordedAt<br>boolean complete<br>boolean coreDeliveryPending |
| ai.neargo.shop.food.dto.KitchenContracts | CustodySourceLine | String foodLineNo<br>String sourceLineRef<br>List<CoreLineScope> originalLines<br>List<KitchenTaskScope> tasks |
| ai.neargo.shop.food.dto.KitchenContracts | CoreLineScope | String allocationNo<br>Long orderItemId<br>String sourceComponentRef<br>BigDecimal originalQty<br>String unit<br>String coreSnapshotHash<br>Long version |
| ai.neargo.shop.food.dto.KitchenContracts | KitchenTaskScope | String taskNo<br>String componentNo<br>String stationNo<br>String taskRole<br>long routeVersion<br>long version |
| ai.neargo.shop.food.dto.KitchenContracts | HandoverVO | String operationNo<br>String batchNo<br>String status<br>boolean deliveryPending<br>String coreDeliveryRef |
| ai.neargo.shop.food.dto.KitchenContracts | CourseReq | @NotBlank @Size(max=64) String name<br>@NotNull Integer sequenceNo |
| ai.neargo.shop.food.dto.KitchenContracts | CourseActionReq | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version<br>@NotBlank String action<br>@Size(max=255) String reason<br>List<String> seatNos |
| ai.neargo.shop.food.dto.KitchenContracts | CourseFireReq | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version<br>@NotNull @Size(max=100) List<@NotBlank String> seatNos |
| ai.neargo.shop.food.dto.KitchenContracts | CourseVO | String courseNo<br>String checkNo<br>String name<br>int sequenceNo<br>String status<br>long version |
| ai.neargo.shop.food.dto.KitchenContracts | TaskVO | String taskNo<br>String ticketNo<br>String batchNo<br>String foodLineNo<br>String componentNo<br>String stationNo<br>String taskRole<br>long routeVersion<br>BigDecimal qty<br>BigDecimal makingQty<br>BigDecimal readyQty<br>BigDecimal servedQty<br>BigDecimal voidQty<br>String status<br>String seatNo<br>String courseNo<br>long version<br>String ticketKind<br>String originalTicketNo |
| ai.neargo.shop.food.dto.KitchenContracts | EventVO | String eventNo<br>String taskNo<br>String action<br>BigDecimal qty<br>String reason<br>String inventoryDocNo |
| ai.neargo.shop.food.dto.KitchenContracts | TicketVO | String ticketNo<br>String batchNo<br>String kind<br>String originalTicketNo<br>String status<br>List<TaskVO> tasks |
| ai.neargo.shop.food.dto.KitchenReturnContracts | ItemDecision | @NotBlank String returnItemNo<br>@NotBlank String disposition |
| ai.neargo.shop.food.dto.KitchenReturnContracts | HandleReq | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version<br>@NotNull @Size(min=1,max=1000) List<@Valid ItemDecision> decisions<br>@NotBlank @Size(max=255) String reason |
| ai.neargo.shop.food.dto.KitchenReturnContracts | ItemVO | String returnItemNo<br>String foodLineNo<br>String componentNo<br>BigDecimal qty<br>String disposition |
| ai.neargo.shop.food.dto.KitchenReturnContracts | ReturnKitchenVO | String returnNo<br>String batchNo<br>String kitchenStatus<br>long version<br>List<ItemVO> items |
| ai.neargo.shop.food.dto.KitchenReturnContracts | HandledVO | String operationNo<br>String returnNo<br>String kitchenStatus |
| ai.neargo.shop.food.dto.ListBundlesQuery | ListBundlesQuery | @Min(1) Integer page<br>@Min(1) @Max(100) Integer size |
| ai.neargo.shop.food.dto.ListCheckActionsQuery | ListCheckActionsQuery | @Min(1) Integer page<br>@Min(1) @Max(100) Integer size |
| ai.neargo.shop.food.dto.ListChecksQuery | ListChecksQuery | @Min(1) Integer page<br>@Min(1) @Max(100) Integer size<br>String status<br>LocalDate businessDate |
| ai.neargo.shop.food.dto.ListConfigRevisionsQuery | ListConfigRevisionsQuery | @NotBlank @Size(max=24) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configKind<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo<br>@Min(1) @JsonDeserialize(using=ListRecipesQuery.PageDeserializer.class) Integer page<br>@Min(1) @Max(100) @JsonDeserialize(using=ListRecipesQuery.PageDeserializer.class) Integer size |
| ai.neargo.shop.food.dto.ListDiningBatchesQuery | ListDiningBatchesQuery | @Min(1) @Max(10000) Integer page<br>@Min(1) @Max(100) Integer size<br>@Size(max=32) String originalCheckNo<br>@Size(max=32) String checkNo<br>@Size(max=24) String status<br>@DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate businessDate<br>@Size(max=24) String orderSource |
| ai.neargo.shop.food.dto.ListDishesQuery | ListDishesQuery | @Min(1) Integer page<br>@Min(1) @Max(100) Integer size<br>@Size(max=128) String keyword<br>@Pattern(regexp="SINGLE\|BUNDLE\|ADDON") String dishKind |
| ai.neargo.shop.food.dto.ListMenusQuery | ListMenusQuery | @Min(1) @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer page<br>@Min(1) @Max(100) @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer size<br>@Pattern(regexp="DRAFT\|PUBLISHED\|DISABLED") @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status<br>@Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String keyword |
| ai.neargo.shop.food.dto.ListModifiersQuery | ListModifiersQuery | @Min(1) Integer page<br>@Min(1) @Max(100) Integer size |
| ai.neargo.shop.food.dto.ListOptionGroupsQuery | ListOptionGroupsQuery | @Min(1) Integer page<br>@Min(1) @Max(100) Integer size |
| ai.neargo.shop.food.dto.ListRecipesQuery | ListRecipesQuery | @Min(1) @JsonDeserialize(using=PageDeserializer.class) Integer page<br>@Min(1) @Max(100) @JsonDeserialize(using=PageDeserializer.class) Integer size<br>@Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@Pattern(regexp="DRAFT\|ACTIVE\|DISABLED") @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status |
| ai.neargo.shop.food.dto.ListReservationsQuery | ListReservationsQuery | @Min(1) Integer page<br>@Min(1) @Max(100) Integer size<br>@NotNull Instant from<br>@NotNull Instant to<br>String status |
| ai.neargo.shop.food.dto.ListTableAreasQuery | ListTableAreasQuery | @Min(1) Integer page<br>@Min(1) @Max(100) Integer size<br>@Pattern(regexp="ACTIVE\|DISABLED") String status |
| ai.neargo.shop.food.dto.ListTablesQuery | ListTablesQuery | @Min(1) Integer page<br>@Min(1) @Max(100) Integer size<br>@Size(max=32) String areaNo<br>@Pattern(regexp="ACTIVE\|DISABLED") String status<br>@Pattern(regexp="IDLE\|IN_USE\|NEEDS_CLEANING") String turnoverState<br>@Size(max=64) String keyword |
| ai.neargo.shop.food.dto.MeasurementPolicy | MeasurementPolicy | String unit<br>String mode<br>Integer scale<br>BigDecimal allowedDeviation |
| ai.neargo.shop.food.dto.MenuCategoryInput | MenuCategoryInput | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String categoryNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name<br>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer sortNo<br>List<MenuItemInput> items |
| ai.neargo.shop.food.dto.MenuChannelBindingVO | MenuChannelBindingVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String bindingNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String provider<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String channelAccountRef<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String externalStoreRef<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status<br>@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version |
| ai.neargo.shop.food.dto.MenuCompositionResultVO | MenuCompositionResultVO | MenuVO menu<br>MenuCompositionVO composition |
| ai.neargo.shop.food.dto.MenuCompositionVO | MenuCompositionVO | List<MenuCategoryInput> categories |
| ai.neargo.shop.food.dto.MenuConfigResultVO | MenuConfigResultVO | MenuVO config<br>MenuScheduleVO schedule<br>MenuCompositionVO composition<br>List<MenuContextInput> contexts |
| ai.neargo.shop.food.dto.MenuContextInput | MenuContextInput | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String channelBindingNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String pricePolicyRef |
| ai.neargo.shop.food.dto.MenuContextsResultVO | MenuContextsResultVO | MenuVO menu<br>List<MenuContextInput> contexts |
| ai.neargo.shop.food.dto.MenuItemInput | MenuItemInput | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuItemNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String goodsNo<br>@JsonDeserialize(using = MenuDefinitionPolicy.IntegerDeserializer.class) Integer sortNo<br>@JsonDeserialize(using = MenuDefinitionPolicy.BooleanDeserializer.class) Boolean enabled<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuScope<br>@JsonDeserialize(contentUsing = MaterialCatalogFacts.TextDeserializer.class) List<String> skuNos<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String optionMode<br>@JsonDeserialize(contentUsing = MaterialCatalogFacts.TextDeserializer.class) List<String> optionGroupNos<br>List<MenuSkuRecipeInput> skuRecipes<br>@JsonInclude(JsonInclude.Include.NON_NULL) List<MenuSkuPriceInput> skuPrices |
| ai.neargo.shop.food.dto.MenuPublicationSnapshot | MenuPublicationSnapshot | int contentSchema<br>String configKind<br>String configNo<br>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long sourceVersion<br>Captured menu<br>List<SkuIdentityFact> skuFacts<br>List<GoodsFact> goodsFacts<br>String approvalRef |
| ai.neargo.shop.food.dto.MenuPublicationSnapshot | Captured | String entityNo<br>String storeNo<br>MenuConfigResultVO definition<br>List<DishVO> dishes<br>List<ItemOptions> itemOptions<br>List<Dependency> dependencies<br>List<MenuChannelBindingVO> channelBindings |
| ai.neargo.shop.food.dto.MenuPublicationSnapshot | ItemOptions | String menuItemNo<br>List<String> optionGroupNos |
| ai.neargo.shop.food.dto.MenuPublicationSnapshot | Dependency | String revisionNo<br>String configKind<br>String configNo<br>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long revision<br>int contentSchema<br>String contentHash<br>String definitionSnapshot |
| ai.neargo.shop.food.dto.MenuResultVO | MenuResultVO | MenuVO menu |
| ai.neargo.shop.food.dto.MenuScheduleResultVO | MenuScheduleResultVO | MenuVO menu<br>MenuScheduleVO schedule |
| ai.neargo.shop.food.dto.MenuScheduleVO | MenuScheduleVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String validFrom<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String validTo<br>List<WeekScheduleInput> weekSchedules<br>List<ExceptionInput> exceptions |
| ai.neargo.shop.food.dto.MenuSkuPriceInput | MenuSkuPriceInput | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long priceMinor<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String currency |
| ai.neargo.shop.food.dto.MenuSkuRecipeInput | MenuSkuRecipeInput | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo |
| ai.neargo.shop.food.dto.MenuVO | MenuVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuType<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String timezone<br>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer priority<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String exclusiveGroup<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long configVersion |
| ai.neargo.shop.food.dto.ModifierComponentInput | ModifierComponentInput | @NotBlank @Size(max=32) String memberNo<br>@NotNull @DecimalMin(value="0", inclusive=false) @Digits(integer=9, fraction=3) BigDecimal quantity |
| ai.neargo.shop.food.dto.ModifierConfigVO | ModifierConfigVO | String configNo<br>String kind<br>long version<br>String status<br>Data data |
| ai.neargo.shop.food.dto.ModifierConfigVO | Data | String name<br>String salesSkuNo<br>String publishedRevisionNo<br>List<Component> components |
| ai.neargo.shop.food.dto.ModifierConfigVO | Component | String componentNo<br>String memberNo<br>BigDecimal quantity |
| ai.neargo.shop.food.dto.ModifierMutationVO | ModifierMutationVO | String configNo<br>long version |
| ai.neargo.shop.food.dto.OccupancyVO | OccupancyVO | String occupancyNo<br>String tableNo<br>String checkNo<br>int seats<br>Instant startedAt |
| ai.neargo.shop.food.dto.OpenCheckReq | OpenCheckReq | @NotNull @Size(max=100) List<@NotBlank @Size(max=32) String> tableNos<br>@NotNull @Min(1) Integer partySize<br>@NotBlank String payMode<br>@Size(max=32) String reservationNo<br>@NotNull @Valid @Size(max=100) List<TableVersionReq> tableVersions<br>@NotBlank String sharingMode<br>@NotNull @Valid @Size(max=1000) List<SeatReq> seats<br>@Size(max=32) String serviceEmployeeNo |
| ai.neargo.shop.food.dto.OpenSharedCheckReq | OpenSharedCheckReq | @NotNull @Min(0) Long version<br>@NotNull @Min(1) Integer partySize<br>@NotBlank String payMode |
| ai.neargo.shop.food.dto.OperationResultVO | OperationResultVO | OperationVO operation |
| ai.neargo.shop.food.dto.OperationVO | OperationVO | String operationNo<br>String status<br>long version<br>String resultRef |
| ai.neargo.shop.food.dto.OptionGroupConfigVO | OptionGroupConfigVO | String configNo<br>String kind<br>long revision<br>String status<br>Data data |
| ai.neargo.shop.food.dto.OptionGroupConfigVO | Data | String name<br>BigDecimal minQty<br>BigDecimal maxQty<br>String publishedRevisionNo<br>Map<String, Object> selectionRules<br>List<Item> items |
| ai.neargo.shop.food.dto.OptionGroupConfigVO | Item | String optionItemNo<br>String sourceKind<br>String specValueNo<br>String modifierNo<br>BigDecimal minQty<br>BigDecimal maxQty<br>BigDecimal defaultQty<br>int sortNo |
| ai.neargo.shop.food.dto.OptionGroupMutationVO | OptionGroupMutationVO | String configNo<br>long version |
| ai.neargo.shop.food.dto.OptionGroupResultVO | OptionGroupResultVO | OptionGroupConfigVO config |
| ai.neargo.shop.food.dto.OptionItemInput | OptionItemInput | @NotBlank @Size(max=32) String optionItemNo<br>@NotBlank String sourceKind<br>@Size(max=64) String specValueNo<br>@Size(max=32) String modifierNo<br>@NotNull @DecimalMin("0") @Digits(integer=12, fraction=6) BigDecimal minQty<br>@NotNull @DecimalMin("0") @Digits(integer=12, fraction=6) BigDecimal maxQty<br>@NotNull @DecimalMin("0") @Digits(integer=12, fraction=6) BigDecimal defaultQty<br>@NotNull @Min(0) Integer sortNo |
| ai.neargo.shop.food.dto.OrderLineInput | OrderLineInput | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sourceLineRef<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuItemNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuRevisionNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String goodsNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal qty<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String seatNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String courseNo<br>List<CartSelectionInput> selections<br>List<CartBundleChoiceInput> bundleChoices |
| ai.neargo.shop.food.dto.PayDiningSettlementReq | PayDiningSettlementReq | @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version<br>@NotBlank @Pattern(regexp = "[0-9a-f]{64}") String confirmationDigest<br>@NotBlank @Size(max = 32) String payChannel |
| ai.neargo.shop.food.dto.PrintDocumentReq | PrintDocumentReq | @NotBlank @Size(max=16) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String scene<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sourceNo<br>@Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationNo |
| ai.neargo.shop.food.dto.ProductionContracts | ListQuery | Integer page<br>Integer size<br>LocalDate from<br>LocalDate to<br>String skuNo<br>String itemRef<br>String status |
| ai.neargo.shop.food.dto.ProductionContracts | AdjustmentReq | Long version<br>String kind<br>BigDecimal qty<br>String reason |
| ai.neargo.shop.food.dto.ProductionContracts | ReverseReq | Long version<br>String reason |
| ai.neargo.shop.food.dto.ProductionContracts | LineVO | int lineNo<br>String componentPath<br>String itemRef<br>BigDecimal theoryQty<br>String unit<br>String recipeRevisionNo<br>String inventoryReceiptRef<br>BigDecimal inventoryIssuedQty<br>Long costMinor<br>String currency<br>String status |
| ai.neargo.shop.food.dto.ProductionContracts | ConsumptionVO | String consumptionNo<br>String sourceEventNo<br>String purpose<br>String status<br>String requestHash<br>String inventoryOperationRef<br>LocalDate businessDate<br>LocalDateTime occurredAt<br>List<LineVO> lines<br>String quantityEvidence<br>boolean consumptionPending<br>Long totalCostMinor<br>String costCurrency<br>long version |
| ai.neargo.shop.food.dto.PublicationSnapshot | PublicationSnapshot | int contentSchema<br>String configKind<br>String configNo<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) long sourceVersion<br>ModifierConfigVO modifier<br>OptionGroupConfigVO optionGroup<br>List<Dependency> dependencies<br>List<SkuIdentityFact> skuFacts<br>List<SpecValueFact> specFacts<br>String approvalRef |
| ai.neargo.shop.food.dto.PublicationSnapshot | Dependency | String revisionNo<br>String configKind<br>String configNo<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) long revision<br>String contentHash |
| ai.neargo.shop.food.dto.PublishConfigReq | PublishConfigReq | @NotNull @Min(0) @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>@NotNull OffsetDateTime effectiveAt<br>@Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String approvalRef |
| ai.neargo.shop.food.dto.PublishConfigResultVO | PublishConfigResultVO | ConfigRevisionVO publication |
| ai.neargo.shop.food.dto.QueueContracts | IssueReq | @NotNull @Min(1) @Max(1000) Integer partySize<br>@Size(max=64) String contactRef |
| ai.neargo.shop.food.dto.QueueContracts | Query | @NotNull LocalDate businessDate<br>String status<br>Integer page<br>Integer size |
| ai.neargo.shop.food.dto.QueueContracts | ChangeReq | @NotNull @Min(0) Long version<br>@Size(max=255) String reason |
| ai.neargo.shop.food.dto.QueueContracts | SeatQueueReq | @NotNull @Min(0) Long version<br>@NotNull @Valid OpenCheckReq check |
| ai.neargo.shop.food.dto.QueueContracts | QueueVO | String ticketNo<br>LocalDate businessDate<br>int partySize<br>String status<br>LocalDateTime calledAt<br>String checkNo<br>long version<br>String notificationStatus |
| ai.neargo.shop.food.dto.RecipeAdjustmentInput | RecipeAdjustmentInput | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String adjustmentNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String optionItemNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String targetLineNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String action<br>BigDecimal qty<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String unit |
| ai.neargo.shop.food.dto.RecipeAdjustmentVO | RecipeAdjustmentVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String adjustmentNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String optionItemNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String targetLineNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String action<br>BigDecimal qty<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String unit<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String optionIdentityHash |
| ai.neargo.shop.food.dto.RecipeLineInput | RecipeLineInput | @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String lineNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String itemRef<br>@NotNull BigDecimal qty<br>@NotBlank @Size(max=16) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String unit<br>@NotNull BigDecimal lossRate |
| ai.neargo.shop.food.dto.RecipeLineVO | RecipeLineVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String lineNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String itemRef<br>BigDecimal qty<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String unit<br>BigDecimal lossRate<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String materialDefinitionHash |
| ai.neargo.shop.food.dto.RecipeOptionGroupSnapshot | RecipeOptionGroupSnapshot | String goodsNo<br>Dependency publication<br>PublicationSnapshot definition |
| ai.neargo.shop.food.dto.RecipePublicationSnapshot | RecipePublicationSnapshot | int contentSchema<br>String configKind<br>String configNo<br>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long sourceVersion<br>RecipeVO recipe<br>List<SkuIdentityFact> skuFacts<br>List<MaterialFact> materialFacts<br>List<RecipeOptionGroupSnapshot> optionGroups<br>String approvalRef |
| ai.neargo.shop.food.dto.RecipeResultVO | RecipeResultVO | RecipeVO recipe |
| ai.neargo.shop.food.dto.RecipeVO | RecipeVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeCode<br>BigDecimal yieldQty<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String yieldUnit<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) long version<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) long configVersion<br>List<RecipeLineVO> lines<br>List<RecipeAdjustmentVO> adjustments |
| ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq | ReplaceBundleDefinitionReq | @NotNull @Min(0) @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>@NotNull @Size(max=30) List<@NotNull @Valid BundleGroupInput> groups<br>@NotNull @Size(max=100) List<@NotNull @Valid BundleChoiceInput> choices |
| ai.neargo.shop.food.dto.ReplaceCartLineReq | ReplaceCartLineReq | OrderLineInput line<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version |
| ai.neargo.shop.food.dto.ReplaceMenuCompositionReq | ReplaceMenuCompositionReq | @NotNull @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String section<br>@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>@NotNull @Size(max=30) List<MenuCategoryInput> categories |
| ai.neargo.shop.food.dto.ReplaceMenuContextsReq | ReplaceMenuContextsReq | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String section<br>@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>@NotNull @Size(max=50) List<@Valid MenuContextInput> contexts |
| ai.neargo.shop.food.dto.ReplaceMenuScheduleReq | ReplaceMenuScheduleReq | @NotNull @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String section<br>@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>@NotNull @Size(max=100) List<@NotNull @Valid WeekScheduleInput> weekSchedules<br>@NotNull @Size(max=366) List<@NotNull @Valid ExceptionInput> exceptions<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String validFrom<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String validTo |
| ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq | ReplaceOptionGroupItemsReq | @NotNull @Min(0) Long version<br>@NotNull @Size(max=100) List<@Valid OptionItemInput> items<br>@NotNull @Size(max=100) List<String> goodsNos<br>@NotNull @Valid SelectionRules selectionRules |
| ai.neargo.shop.food.dto.ReplaceRecipeDefinitionReq | ReplaceRecipeDefinitionReq | @NotNull @Min(0) @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>@NotNull BigDecimal yieldQty<br>@NotBlank @Size(max=16) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String yieldUnit<br>@NotNull @Size(max=100) List<@NotNull @Valid RecipeLineInput> lines<br>@JsonInclude(JsonInclude.Include.NON_NULL) @Size(max=100) List<@NotNull @Valid RecipeAdjustmentInput> adjustments |
| ai.neargo.shop.food.dto.RepublishConfigReq | RepublishConfigReq | @NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>@NotNull OffsetDateTime effectiveAt<br>@NotBlank @Size(max=255) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String reason<br>@Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String approvalRef |
| ai.neargo.shop.food.dto.ReservationContracts | CreateReq | @NotNull @Min(1) Integer partySize<br>@NotNull Instant startAt<br>@NotNull Instant endAt<br>@NotBlank @Size(max=64) String contactRef<br>@NotNull @Size(min=1,max=100) List<@Valid TableVersionReq> tables |
| ai.neargo.shop.food.dto.ReservationContracts | ChangeReq | @NotNull @Min(0) Long version<br>@NotNull @Valid CreateReq appointment<br>@NotBlank @Size(max=255) String reason |
| ai.neargo.shop.food.dto.ReservationContracts | TransitionReq | @NotNull @Min(0) Long version<br>@NotBlank @Size(max=255) String reason |
| ai.neargo.shop.food.dto.ReservationContracts | ArriveReq | @NotNull @Min(0) Long version<br>@NotNull @Size(min=1,max=100) List<@Valid TableVersionReq> tables |
| ai.neargo.shop.food.dto.ReservationContracts | DetailVO | ReservationVO reservation<br>List<String> tableNos |
| ai.neargo.shop.food.dto.ReservationPreorderContracts | SaveReq | @Min(0) long reservationVersion<br>@NotNull Instant cutoffAt<br>@NotEmpty @Size(max = 100) List<@Valid OrderLineInput> lines |
| ai.neargo.shop.food.dto.ReservationPreorderContracts | PreorderVO | String preorderNo<br>String reservationNo<br>String status<br>Instant cutoffAt<br>List<OrderLineInput> lines<br>String cartNo<br>String batchNo<br>long version |
| ai.neargo.shop.food.dto.ReservationPreorderContracts | ConvertReq | @Min(0) long version<br>@NotNull @Valid CreateStaffDiningOrderReq order |
| ai.neargo.shop.food.dto.ReservationVO | ReservationVO | String reservationNo<br>int partySize<br>Instant startAt<br>Instant endAt<br>String status<br>String depositRef<br>String checkNo<br>long version |
| ai.neargo.shop.food.dto.RevokeTableTokenReq | RevokeTableTokenReq | @NotNull @Min(0) Long version<br>@NotBlank @Size(max=255) String reason |
| ai.neargo.shop.food.dto.RotateTableTokenReq | RotateTableTokenReq | @NotNull Instant expiresAt<br>@NotNull @Min(0) Long version |
| ai.neargo.shop.food.dto.SalesQuotaClaimSource | SalesQuotaClaimSource | String sourceRef<br>String skuNo<br>BigDecimal quantity |
| ai.neargo.shop.food.dto.SalesQuotaClaimVO | SalesQuotaClaimVO | String claimNo<br>String restrictionNo<br>String batchNo<br>BigDecimal quantity<br>String status |
| ai.neargo.shop.food.dto.SalesRestrictionEvaluationVO | SalesRestrictionEvaluationVO | @JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean blocked<br>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal remainingQuota<br>@JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean originalStockKnown<br>@JsonFormat(shape=JsonFormat.Shape.STRING) @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant asOf<br>List<SalesRestrictionVO> restrictions |
| ai.neargo.shop.food.dto.SalesRestrictionQuery | SalesRestrictionQuery | @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer page<br>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer size<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status |
| ai.neargo.shop.food.dto.SalesRestrictionReq | SalesRestrictionReq | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String mode<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String restrictionNo<br>@JsonDeserialize(using=SalesRestrictionPolicy.OptionalVersionDeserializer.class) Long version<br>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quota<br>LocalDate businessDate<br>@JsonFormat(shape=JsonFormat.Shape.STRING) @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant startsAt<br>@JsonFormat(shape=JsonFormat.Shape.STRING) @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant expiresAt<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String reason<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment |
| ai.neargo.shop.food.dto.SalesRestrictionVO | SalesRestrictionVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String restrictionNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String mode<br>LocalDate businessDate<br>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quota<br>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal reservedQty<br>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal consumedQty<br>@JsonFormat(shape=JsonFormat.Shape.STRING) @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant startsAt<br>@JsonFormat(shape=JsonFormat.Shape.STRING) @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant expiresAt<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String reason<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version |
| ai.neargo.shop.food.dto.SaveCalendarReq | SaveCalendarReq | @NotBlank @Pattern(regexp = "OPEN\|CLOSED") String mode<br>@NotNull @Size(max = 32) List<@NotBlank String> intervals<br>@NotNull @Min(0) Long version |
| ai.neargo.shop.food.dto.SaveCheckSeatsReq | SaveCheckSeatsReq | @NotNull @Min(0) Long version<br>@NotNull @Min(1) Integer partySize<br>@NotNull @Valid @Size(max=1000) List<SeatReq> seats<br>@NotBlank @Size(max=255) String reason |
| ai.neargo.shop.food.dto.SaveDishReq | SaveDishReq | @NotBlank @Pattern(regexp="SINGLE\|BUNDLE\|ADDON") String dishKind<br>@NotNull @Min(0) Integer prepSeconds<br>@Valid @NotNull DishPresentation presentation<br>@Valid @NotNull MeasurementPolicy measurement<br>@Min(0) Long version |
| ai.neargo.shop.food.dto.SaveTableAreaReq | SaveTableAreaReq | @NotBlank @Size(max=64) String name<br>@NotNull Integer sortNo<br>@NotNull @Min(0) Long version |
| ai.neargo.shop.food.dto.SaveTableReq | SaveTableReq | @NotBlank @Size(max=32) String areaNo<br>@NotBlank @Size(max=64) String name<br>@NotNull @Min(1) Integer capacity<br>@NotNull Integer sortNo<br>@NotNull @Min(0) Long version |
| ai.neargo.shop.food.dto.SeatReq | SeatReq | @Size(max=32) String seatNo<br>@NotBlank @Size(max=32) String label<br>@Size(max=32) String guestRef |
| ai.neargo.shop.food.dto.SelectionRules | SelectionRules | @NotNull @Size(max=100) List<List<String>> mutexSets<br>@NotNull @Size(max=100) List<@Valid SelectionDependency> requires |
| ai.neargo.shop.food.dto.SelectionRules | SelectionDependency | String optionItemNo<br>List<String> requiresItemNos |
| ai.neargo.shop.food.dto.ServiceFormContracts | OrganizationReq | @NotBlank String name<br>@NotBlank String contactRef<br>@NotBlank String externalRef |
| ai.neargo.shop.food.dto.ServiceFormContracts | OrganizationVO | String organizationNo<br>String name<br>String contactRef<br>String externalRef<br>String status |
| ai.neargo.shop.food.dto.ServiceFormContracts | BookReq | @NotNull Kind kind<br>String reservationNo<br>String slotNo<br>String menuNo<br>String menuRevisionNo<br>@Min(1) int guaranteedPersons<br>@NotNull Instant startsAt<br>@NotNull Instant endsAt<br>String organizationNo<br>String contactRef<br>@Min(1) @Max(100) int maxEntriesPerPerson<br>@NotBlank String reason |
| ai.neargo.shop.food.dto.ServiceFormContracts | Guest | @NotBlank @Size(max = 64) String guestRef<br>String seatNo<br>@NotBlank String personClass |
| ai.neargo.shop.food.dto.ServiceFormContracts | EnterReq | @Min(0) long version<br>@NotBlank String checkNo<br>@Min(0) long checkVersion<br>@NotEmpty @Size(max = 1000) List<@Valid Guest> guests<br>@NotBlank String reason |
| ai.neargo.shop.food.dto.ServiceFormContracts | ReviseReq | @Min(0) long version<br>@Min(1) int guaranteedPersons<br>String menuNo<br>String menuRevisionNo<br>@NotBlank String reason |
| ai.neargo.shop.food.dto.ServiceFormContracts | VersionReq | @Min(0) long version<br>@NotBlank String reason |
| ai.neargo.shop.food.dto.ServiceFormContracts | BatchReq | @Min(0) long version<br>@NotBlank String batchNo<br>@NotBlank String reason |
| ai.neargo.shop.food.dto.ServiceFormContracts | FormVO | String formNo<br>Kind kind<br>String status<br>String reservationNo<br>String slotNo<br>String menuNo<br>String menuRevisionNo<br>int guaranteedPersons<br>Integer actualPersons<br>String checkNo<br>Instant startsAt<br>Instant endsAt<br>String organizationNo<br>String contactRef<br>int maxEntriesPerPerson<br>String sourceTransition<br>long version |
| ai.neargo.shop.food.dto.ServiceFormContracts | AttendeeVO | String attendeeNo<br>String guestRef<br>String seatNo<br>String personClass<br>String status<br>int entryCount<br>long version |
| ai.neargo.shop.food.dto.ServiceSlotContracts | CreateReq | @NotNull Instant startsAt<br>@NotNull Instant endsAt<br>@NotNull Instant orderCutoffAt<br>@NotNull Instant dispatchAt<br>@NotNull @DecimalMin("0.000001") BigDecimal capacity<br>@NotBlank String unit |
| ai.neargo.shop.food.dto.ServiceSlotContracts | SlotVO | String slotNo<br>Instant startsAt<br>Instant endsAt<br>Instant orderCutoffAt<br>Instant dispatchAt<br>BigDecimal capacity<br>BigDecimal reserved<br>BigDecimal consumed<br>String unit<br>String status<br>long version |
| ai.neargo.shop.food.dto.ServiceSlotContracts | ClaimReq | @NotBlank String slotNo<br>@NotBlank String sourceNo<br>@NotNull @DecimalMin("0.000001") BigDecimal quantity |
| ai.neargo.shop.food.dto.ServiceSlotContracts | ClaimVO | String claimNo<br>String slotNo<br>String sourceNo<br>BigDecimal quantity<br>String status<br>long version |
| ai.neargo.shop.food.dto.SetStatusTableAreaReq | SetStatusTableAreaReq | @NotBlank @Pattern(regexp="ACTIVE\|DISABLED") String status<br>@NotNull @Min(0) Long version<br>@NotBlank @Size(max=255) String reason |
| ai.neargo.shop.food.dto.SetStatusTableReq | SetStatusTableReq | @NotBlank @Pattern(regexp="ACTIVE\|DISABLED") String status<br>@NotNull @Min(0) Long version<br>@NotBlank @Size(max=255) String reason |
| ai.neargo.shop.food.dto.StartDiningSessionReq | StartDiningSessionReq | @NotBlank @Size(max=128) String tableToken<br>@Size(max=512) String deviceCredential |
| ai.neargo.shop.food.dto.StationContracts | CreateStationReq | @NotBlank @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name<br>@NotBlank @Size(max=24) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationType |
| ai.neargo.shop.food.dto.StationContracts | UpdateStationReq | @NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>@NotBlank @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name<br>@NotBlank @Size(max=24) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationType<br>@NotNull @JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean enabled |
| ai.neargo.shop.food.dto.StationContracts | StationRouteInput | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationNo<br>@NotBlank @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String taskRole |
| ai.neargo.shop.food.dto.StationContracts | ReplaceStationRoutesReq | @NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>@NotNull @Size(max=100) List<@Valid StationRouteInput> routes |
| ai.neargo.shop.food.dto.StationContracts | StationVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo<br>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationType<br>@JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) boolean enabled<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String publishedRevisionNo |
| ai.neargo.shop.food.dto.StationContracts | StationMutationVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo<br>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status |
| ai.neargo.shop.food.dto.StationContracts | GetStationVO | StationVO config |
| ai.neargo.shop.food.dto.StationContracts | StationRoutesVO | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version<br>List<StationRouteInput> routes |
| ai.neargo.shop.food.dto.StationPublicationSnapshot | StationPublicationSnapshot | int contentSchema<br>String configKind<br>String configNo<br>long sourceVersion<br>String entityNo<br>String storeNo<br>StationVO station<br>List<Route> routes<br>List<SkuIdentityFact> skuFacts<br>String approvalRef |
| ai.neargo.shop.food.dto.StationPublicationSnapshot | Route | String skuNo<br>String taskRole<br>long routeVersion |
| ai.neargo.shop.food.dto.StoreOperationPolicyResultVO | StoreOperationPolicyResultVO | String policyNo<br>String timezone<br>int cutoverMinute<br>String payPolicy<br>List<String> capabilities<br>long version |
| ai.neargo.shop.food.dto.TableAreaResultVO | TableAreaResultVO | TableAreaVO area |
| ai.neargo.shop.food.dto.TableAreaVO | TableAreaVO | String areaNo<br>String storeNo<br>String code<br>String name<br>int sortNo<br>String status<br>long version |
| ai.neargo.shop.food.dto.TableResultVO | TableResultVO | TableVO table |
| ai.neargo.shop.food.dto.TableTokenVO | TableTokenVO | String token<br>Instant expiresAt |
| ai.neargo.shop.food.dto.TableVO | TableVO | String tableNo<br>String storeNo<br>String areaNo<br>String code<br>String name<br>int capacity<br>int sortNo<br>String status<br>String turnoverState<br>String resourceNo<br>long version |
| ai.neargo.shop.food.dto.TableVersionReq | TableVersionReq | @NotBlank @Size(max=32) String tableNo<br>@NotNull @Min(0) Long version |
| ai.neargo.shop.food.dto.TransferCheckReq | TransferCheckReq | @NotNull @Min(0) Long version<br>@NotBlank @Size(max=255) String reason<br>@Size(max=64) String approvalRef<br>@NotEmpty @Size(max=100) List<@NotBlank @Size(max=32) String> targetTableNos<br>@NotEmpty @Valid @Size(max=200) List<TableVersionReq> tableVersions |
| ai.neargo.shop.food.dto.UpdateMenuChannelBindingReq | UpdateMenuChannelBindingReq | @NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String externalStoreRef |
| ai.neargo.shop.food.dto.UpdateMenuReq | UpdateMenuReq | @NotBlank @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name<br>@NotBlank @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuType<br>@NotBlank @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String timezone<br>@NotNull @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer priority<br>@Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String exclusiveGroup<br>@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version |
| ai.neargo.shop.food.dto.UpdateModifierReq | UpdateModifierReq | @NotBlank @Size(max=64) String name<br>@NotBlank @Size(max=16) String kind<br>@Size(max=32) String salesSkuNo<br>@NotNull @Size(max=100) List<@NotNull @Valid ModifierComponentInput> components<br>@NotNull @Min(0) Long version |
| ai.neargo.shop.food.dto.WeekScheduleInput | WeekScheduleInput | @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer weekday<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String startTime<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String endTime<br>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer endDayOffset |
| ai.neargo.shop.food.service.DiningTransactionRecoveryService | Report | int claimed<br>int complete<br>int pending<br>int failed |
| ai.neargo.shop.food.service.QueueNotificationService | NoticeVO | String noticeNo<br>String ticketNo<br>long ticketVersion<br>String status<br>String providerRef<br>long version |
| ai.neargo.shop.food.service.ScheduledDiningDispatchService | Report | int claimed<br>int completed<br>int pending |
| ai.neargo.shop.food.spi.BundleInternalApi | CreateCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@NotNull @Valid CreateBundleReq definition |
| ai.neargo.shop.food.spi.BundleInternalApi | UpdateCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String skuNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@NotNull @Valid ReplaceBundleDefinitionReq definition |
| ai.neargo.shop.food.spi.BundleInternalApi | GetQuery | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String skuNo |
| ai.neargo.shop.food.spi.BundleInternalApi | Lookup | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@Valid ListBundlesQuery query |
| ai.neargo.shop.food.spi.BusinessCloseInternalApi | ProposeCarryCommand | @NotBlank @Size(max=32) String entity<br>@NotBlank @Size(max=32) String store<br>@NotBlank @Size(max=32) String actor<br>@NotBlank @Size(max=32) String closeNo<br>@NotBlank @Size(max=32) String exceptionNo<br>@NotNull @Valid ProposeCarryReq request<br>@NotBlank @Size(max=128) String key |
| ai.neargo.shop.food.spi.BusinessCloseInternalApi | ApproveCarryCommand | @NotBlank @Size(max=32) String entity<br>@NotBlank @Size(max=32) String store<br>@NotBlank @Size(max=32) String actor<br>@NotBlank @Size(max=32) String closeNo<br>@NotBlank @Size(max=32) String exceptionNo<br>@NotNull @Valid ApproveCarryReq request<br>@NotBlank @Size(max=128) String key |
| ai.neargo.shop.food.spi.BusinessCloseInternalApi | OpenCommand | @NotBlank @Size(max=32) String entity<br>@NotBlank @Size(max=32) String store<br>@NotBlank @Size(max=32) String actor<br>@NotNull LocalDate date<br>@NotBlank @Size(max=128) String key |
| ai.neargo.shop.food.spi.BusinessCloseInternalApi | ReportQuery | @NotBlank @Size(max=32) String entity<br>@NotBlank @Size(max=32) String store<br>@NotBlank @Size(max=32) String actor<br>@NotNull @Valid PreviewReq request |
| ai.neargo.shop.food.spi.BusinessCloseInternalApi | PreviewCommand | @NotBlank @Size(max=32) String entity<br>@NotBlank @Size(max=32) String store<br>@NotBlank @Size(max=32) String actor<br>@NotNull @Valid PreviewReq request<br>@NotBlank @Size(max=128) String key |
| ai.neargo.shop.food.spi.BusinessCloseInternalApi | GetQuery | @NotBlank @Size(max=32) String entity<br>@NotBlank @Size(max=32) String store<br>@NotBlank @Size(max=32) String actor<br>@NotBlank @Size(max=32) String closeNo |
| ai.neargo.shop.food.spi.BusinessCloseInternalApi | SignCommand | @NotBlank @Size(max=32) String entity<br>@NotBlank @Size(max=32) String store<br>@NotBlank @Size(max=32) String actor<br>@NotNull @Valid SignReq request<br>@NotBlank @Size(max=128) String key |
| ai.neargo.shop.food.spi.BusinessCloseInternalApi | AdjustCommand | @NotBlank @Size(max=32) String entity<br>@NotBlank @Size(max=32) String store<br>@NotBlank @Size(max=32) String actor<br>@NotNull @Valid AdjustmentReq request<br>@NotBlank @Size(max=128) String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceFormRegisterOrganizationCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@Valid @NotNull ServiceFormContracts.OrganizationReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceFormBookCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@Valid @NotNull ServiceFormContracts.BookReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceFormGetCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String form |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceFormReviseCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String form<br>@Valid @NotNull ServiceFormContracts.ReviseReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceFormEnterCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String form<br>@Valid @NotNull ServiceFormContracts.EnterReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceFormLeaveCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String form<br>@Valid @NotNull ServiceFormContracts.VersionReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceFormCancelCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String form<br>@Valid @NotNull ServiceFormContracts.VersionReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceFormNoShowCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String form<br>@Valid @NotNull ServiceFormContracts.VersionReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceFormBindBatchCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String form<br>@Valid @NotNull ServiceFormContracts.BatchReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceFormAttendeesCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String form |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceFormAdmitCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String form<br>@NotBlank String attendee<br>@Valid @NotNull ServiceFormContracts.VersionReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceFormExitCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String form<br>@NotBlank String attendee<br>@Valid @NotNull ServiceFormContracts.VersionReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | FoodFeeSourceBindCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@Valid @NotNull FoodFeeSourceContracts.BindReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | FoodFeeSourceBindingsCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String check |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | QueueNotificationListCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String ticket |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | QueueNotificationRecoverCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String notice<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | CheckCollaborationPreviewReceivableCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String check |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | CheckCollaborationSourceHashCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String check |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | CheckCollaborationReleaseServiceCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String check<br>long version<br>@NotBlank String reason<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | CheckCollaborationCurrentLineOwnerCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String foodLineNo |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | CheckCollaborationMoveBatchesCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@Valid @NotNull CheckCollaborationContracts.MoveReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | CheckCollaborationMoveLinesCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@Valid @NotNull CheckCollaborationContracts.LineMoveReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | CheckCollaborationFreezeCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String check<br>@Valid @NotNull CheckCollaborationContracts.FreezeReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | CheckCollaborationReleaseCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String freeze<br>long version<br>@NotBlank String reason<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ReservationPreorderSaveCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String reservation<br>@Valid @NotNull ReservationPreorderContracts.SaveReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ReservationPreorderGetCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String reservation<br>@NotBlank String preorder |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ReservationPreorderPrepareCartCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String reservation<br>@NotBlank String preorder<br>long version<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ReservationPreorderConvertCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String reservation<br>@NotBlank String preorder<br>@Valid @NotNull ReservationPreorderContracts.ConvertReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ReservationPreorderCancelCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String reservation<br>@NotBlank String preorder<br>long version<br>@NotBlank String reason<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceSlotCreateCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@Valid @NotNull ServiceSlotContracts.CreateReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceSlotGetCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String slot |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceSlotReserveCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@Valid @NotNull ServiceSlotContracts.ClaimReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceSlotTransitionCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String claim<br>long version<br>@NotBlank String action<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceSlotDispatchDueCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String sourceNo |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | BusinessOperationsEvidenceCommand | @NotBlank String entity<br>@NotBlank String store<br>@Valid @NotNull BusinessOperationsContracts.Source source |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | BusinessOperationsProposeCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@Valid @NotNull BusinessOperationsContracts.HandoverReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | BusinessOperationsHandoverCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String handover |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | BusinessOperationsAcceptCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String handover<br>@Valid @NotNull BusinessOperationsContracts.DecisionReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | BusinessOperationsCancelCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String handover<br>@Valid @NotNull BusinessOperationsContracts.DecisionReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | BusinessOperationsOpenCaseCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@Valid @NotNull BusinessOperationsContracts.CaseReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | BusinessOperationsCasesCommand | @NotBlank String entity<br>@NotBlank String store<br>String status |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | BusinessOperationsRecoverCaseCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String caseNo<br>@Valid @NotNull BusinessOperationsContracts.DecisionReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessOperationsInternalApi | BusinessOperationsResolveCaseCommand | @NotBlank String entity<br>@NotBlank String store<br>@NotBlank String actor<br>@NotBlank String caseNo<br>@Valid @NotNull BusinessOperationsContracts.DecisionReq request<br>@NotBlank String key |
| ai.neargo.shop.food.spi.BusinessReturnApprovalInternalApi | Approve | String entity<br>String store<br>String actor<br>String returnNo<br>ApproveReq request<br>String key |
| ai.neargo.shop.food.spi.BusinessReturnApprovalInternalApi | Query | String entity<br>String store<br>String actor<br>String returnNo |
| ai.neargo.shop.food.spi.BusinessReturnApprovalInternalApi | Replacement | String entity<br>String store<br>String actor<br>String returnNo<br>ReplacementReq request<br>String key |
| ai.neargo.shop.food.spi.BusinessReturnInternalApi | CreateCommand | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String actorNo<br>@NotBlank String batchNo<br>@Valid @NotNull CreateReq definition<br>@NotBlank String idempotencyKey |
| ai.neargo.shop.food.spi.BusinessReturnInternalApi | Lookup | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String returnNo |
| ai.neargo.shop.food.spi.BusinessReturnInternalApi | CancelCommand | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String actorNo<br>@NotBlank String returnNo<br>@Valid @NotNull CancelReq definition<br>@NotBlank String idempotencyKey |
| ai.neargo.shop.food.spi.BusinessShareInternalApi | CreateCommand | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String actorNo<br>@NotBlank String checkNo<br>@Valid @NotNull CreateReq definition<br>@NotBlank String idempotencyKey |
| ai.neargo.shop.food.spi.BusinessShareInternalApi | Lookup | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String checkNo<br>@NotBlank String planNo |
| ai.neargo.shop.food.spi.BusinessShareInternalApi | ConfirmCommand | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String actorNo<br>@NotBlank String checkNo<br>@NotBlank String planNo<br>@Valid @NotNull ConfirmReq definition<br>@NotBlank String idempotencyKey |
| ai.neargo.shop.food.spi.BusinessShareInternalApi | CancelCommand | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String actorNo<br>@NotBlank String checkNo<br>@NotBlank String planNo<br>@Valid @NotNull CancelReq definition<br>@NotBlank String idempotencyKey |
| ai.neargo.shop.food.spi.CartInternalApi | CreateCommand | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String entityNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String actorNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey<br>@Valid @NotNull CreateCartReq request |
| ai.neargo.shop.food.spi.CartInternalApi | Identity | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String entityNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo |
| ai.neargo.shop.food.spi.CartInternalApi | ReplaceLineCommand | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String entityNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 64) String sourceLineRef<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String actorNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey<br>@Valid @NotNull ReplaceCartLineReq request |
| ai.neargo.shop.food.spi.CartInternalApi | ArchiveLineCommand | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String entityNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 64) String sourceLineRef<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String actorNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey<br>@Valid @NotNull CartVersionReq request |
| ai.neargo.shop.food.spi.CartInternalApi | ArchiveCommand | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String entityNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String actorNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey<br>@Valid @NotNull CartVersionReq request |
| ai.neargo.shop.food.spi.CartInternalApi | AuthorizeCommand | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String entityNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String checkNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String actorNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey<br>@Valid @NotNull AuthorizeCartSessionReq request |
| ai.neargo.shop.food.spi.CartInternalApi | CustomerCreateCommand | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo<br>@Valid @NotNull CartAccess access<br>@Valid @NotNull CreateCustomerCartReq request<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey |
| ai.neargo.shop.food.spi.CartInternalApi | CustomerIdentity | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo<br>@Valid @NotNull CartAccess access |
| ai.neargo.shop.food.spi.CartInternalApi | CustomerReplaceLineCommand | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 64) String sourceLineRef<br>@Valid @NotNull CartAccess access<br>@Valid @NotNull ReplaceCartLineReq request<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey |
| ai.neargo.shop.food.spi.CartInternalApi | CustomerArchiveLineCommand | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 64) String sourceLineRef<br>@Valid @NotNull CartAccess access<br>@Valid @NotNull CartVersionReq request<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey |
| ai.neargo.shop.food.spi.CatalogInternalApi | ScheduledQuery | String entityNo<br>String storeNo<br>String cartNo |
| ai.neargo.shop.food.spi.CatalogInternalApi | Query | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotNull @Valid CatalogQuery definition |
| ai.neargo.shop.food.spi.CatalogInternalApi | PublicQuery | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment |
| ai.neargo.shop.food.spi.CatalogInternalApi | GuestQuery | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String accessToken<br>@Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String buyerNo |
| ai.neargo.shop.food.spi.CheckInternalApi | OpenCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull OpenCheckReq request |
| ai.neargo.shop.food.spi.CheckInternalApi | Lookup | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@Valid ListChecksQuery query |
| ai.neargo.shop.food.spi.CheckInternalApi | Identity | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String checkNo |
| ai.neargo.shop.food.spi.CheckInternalApi | OperationLookup | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String operationNo |
| ai.neargo.shop.food.spi.CheckInternalApi | ActionsLookup | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String checkNo<br>@Valid ListCheckActionsQuery query |
| ai.neargo.shop.food.spi.CheckInternalApi | SeatsCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String checkNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull SaveCheckSeatsReq request |
| ai.neargo.shop.food.spi.CheckInternalApi | ClearCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String checkNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull ClearCheckReq request |
| ai.neargo.shop.food.spi.CheckInternalApi | TransferCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String checkNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull TransferCheckReq request |
| ai.neargo.shop.food.spi.CheckInternalApi | CombineCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String checkNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull CombineTablesReq request |
| ai.neargo.shop.food.spi.CheckInternalApi | ShareCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String tableNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull OpenSharedCheckReq request |
| ai.neargo.shop.food.spi.ConfigRevisionInternalApi | PageReceipt | List<ConfigRevisionVO> records<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long total<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long page<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long size |
| ai.neargo.shop.food.spi.ConfigRevisionInternalApi | Command | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid PublishConfigReq publication |
| ai.neargo.shop.food.spi.ConfigRevisionInternalApi | RepublishCommand | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String revisionNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid RepublishConfigReq publication |
| ai.neargo.shop.food.spi.ConfigRevisionInternalApi | Lookup | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotNull @Valid ListConfigRevisionsQuery query |
| ai.neargo.shop.food.spi.ConfigRevisionInternalApi | Identity | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String revisionNo |
| ai.neargo.shop.food.spi.ConfigRevisionInternalApi | Resolve | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=24) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configKind<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo<br>@NotNull OffsetDateTime asOf |
| ai.neargo.shop.food.spi.DiningBatchQueryInternalApi | Listing | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String actorNo<br>@Valid @NotNull ListDiningBatchesQuery query |
| ai.neargo.shop.food.spi.DiningCheckoutInternalApi | Create | CartAccess access<br>String batchNo<br>CreateDiningSettlementReq request<br>String key |
| ai.neargo.shop.food.spi.DiningCheckoutInternalApi | Reference | CartAccess access<br>String settlementNo |
| ai.neargo.shop.food.spi.DiningCheckoutInternalApi | Pay | CartAccess access<br>String settlementNo<br>PayDiningSettlementReq request<br>String key |
| ai.neargo.shop.food.spi.DiningCheckoutInternalApi | Cancel | CartAccess access<br>String settlementNo<br>DiningSettlementVersionReq request |
| ai.neargo.shop.food.spi.DiningCheckoutInternalApi | StaffGet | String entity<br>String store<br>String actor<br>String settlementNo |
| ai.neargo.shop.food.spi.DiningCheckoutInternalApi | Original | String entity<br>String store<br>String number |
| ai.neargo.shop.food.spi.DiningDeliveryInternalApi | StaffSave | String entityNo<br>String storeNo<br>String actorNo<br>String cartNo<br>SaveReq definition<br>String key |
| ai.neargo.shop.food.spi.DiningDeliveryInternalApi | CustomerSave | String cartNo<br>CartAccess access<br>SaveReq definition<br>String key |
| ai.neargo.shop.food.spi.DiningDeliveryInternalApi | Source | String entityNo<br>String storeNo<br>String cartNo |
| ai.neargo.shop.food.spi.DiningDeliveryInternalApi | CustomerQuery | String cartNo<br>CartAccess access |
| ai.neargo.shop.food.spi.DiningIdentityInternalApi | DeviceQuery | String entityNo<br>String storeNo<br>String deviceNo |
| ai.neargo.shop.food.spi.DiningIdentityInternalApi | Register | String entityNo<br>String storeNo<br>String actorNo<br>RegisterDeviceReq definition<br>String key |
| ai.neargo.shop.food.spi.DiningIdentityInternalApi | DeviceRevoke | String entityNo<br>String storeNo<br>String actorNo<br>String deviceNo<br>VersionReq definition<br>String key |
| ai.neargo.shop.food.spi.DiningIdentityInternalApi | DeviceSession | String entityNo<br>String storeNo<br>String deviceNo<br>String credential<br>DeviceSessionReq definition<br>String key |
| ai.neargo.shop.food.spi.DiningIdentityInternalApi | StaffParty | String entityNo<br>String storeNo<br>String actorNo<br>StaffPartyReq definition<br>String key |
| ai.neargo.shop.food.spi.DiningIdentityInternalApi | PartyRevoke | String entityNo<br>String storeNo<br>String actorNo<br>String sessionNo<br>VersionReq definition<br>String key |
| ai.neargo.shop.food.spi.DiningIdentityInternalApi | PartyQuery | String entityNo<br>String storeNo<br>String sessionNo |
| ai.neargo.shop.food.spi.DiningIdentityInternalApi | SessionQuery | String sessionNo |
| ai.neargo.shop.food.spi.DiningOrderAuthorityInternalApi | PlanCommand | Command command<br>String orderNo<br>String subOrderNo |
| ai.neargo.shop.food.spi.DiningOrderChanged | DiningOrderChanged | String entityNo<br>String storeNo<br>String checkNo<br>String batchNo<br>String changeNo<br>String action<br>long sourceVersion<br>List<String> foodLineNos |
| ai.neargo.shop.food.spi.DiningOrderInternalApi | CancellationSource | Command command<br>String key<br>String fulfillment |
| ai.neargo.shop.food.spi.DiningOrderInternalApi | StaffCancel | String entity<br>String store<br>String actor<br>String batchNo<br>CancelDiningOrderReq request<br>String key |
| ai.neargo.shop.food.spi.DiningOrderInternalApi | StaffPlace | String entity<br>String store<br>String actor<br>CreateStaffDiningOrderReq request<br>String key |
| ai.neargo.shop.food.spi.DiningOrderInternalApi | Place | String store<br>CartAccess access<br>CreateDiningOrderReq request<br>String key |
| ai.neargo.shop.food.spi.DiningOrderInternalApi | CustomerGet | CartAccess access<br>String batchNo |
| ai.neargo.shop.food.spi.DiningOrderInternalApi | StaffGet | String entity<br>String store<br>String actor<br>String batchNo |
| ai.neargo.shop.food.spi.DiningOrderInternalApi | Original | String entity<br>String store<br>String number |
| ai.neargo.shop.food.spi.DiningPickupVerificationInternalApi | Command | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String actorNo<br>@NotBlank String batchNo<br>@NotBlank String idempotencyKey<br>@Valid @NotNull VerifyPickupReq definition |
| ai.neargo.shop.food.spi.DiningPickupVerificationInternalApi | Query | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String actorNo<br>@NotBlank String batchNo<br>@NotBlank String idempotencyKey |
| ai.neargo.shop.food.spi.DiningPricingInternalApi | StaffQuote | String entity<br>String store<br>String actor<br>DiningQuoteReq request<br>String key |
| ai.neargo.shop.food.spi.DiningPricingInternalApi | StaffGet | String entity<br>String store<br>String actor<br>String cart<br>long version<br>String quote |
| ai.neargo.shop.food.spi.DiningPricingInternalApi | StaffConfirm | String entity<br>String store<br>String actor<br>String quote<br>DiningQuoteConfirmReq request |
| ai.neargo.shop.food.spi.DiningPricingInternalApi | CustomerQuote | String store<br>CartAccess access<br>DiningQuoteReq request<br>String key |
| ai.neargo.shop.food.spi.DiningPricingInternalApi | CustomerGet | CartAccess access<br>String cart<br>long version<br>String quote |
| ai.neargo.shop.food.spi.DiningPricingInternalApi | CustomerConfirm | CartAccess access<br>String quote<br>DiningQuoteConfirmReq request |
| ai.neargo.shop.food.spi.DiningReportInternalApi | RealtimeQuery | String entity<br>String store<br>String actor<br>int version<br>boolean inventory |
| ai.neargo.shop.food.spi.DiningReportInternalApi | ExportQuery | Query query<br>String key |
| ai.neargo.shop.food.spi.DiningReportInternalApi | ExportReference | String entity<br>String actor<br>String exportNo |
| ai.neargo.shop.food.spi.DiningReportInternalApi | Query | String entity<br>List<String> stores<br>String actor<br>View view<br>LocalDate from<br>LocalDate to<br>Integer version<br>Set<String> metricCodes<br>int limit<br>MeasureFilter measure |
| ai.neargo.shop.food.spi.DiningReportInternalApi | DayQuery | Scope scope<br>LocalDate date |
| ai.neargo.shop.food.spi.DiningReturnInternalApi | CustomerApply | String store<br>CartAccess access<br>DiningReturnApplyReq request<br>String key |
| ai.neargo.shop.food.spi.DiningReturnInternalApi | CustomerReference | CartAccess access<br>String returnNo |
| ai.neargo.shop.food.spi.DiningReturnInternalApi | StaffReference | String entity<br>String store<br>String actor<br>String returnNo |
| ai.neargo.shop.food.spi.DiningReturnInternalApi | Original | String entity<br>String store<br>String number |
| ai.neargo.shop.food.spi.DiningSessionInternalApi | RotateCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String tableNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull RotateTableTokenReq request |
| ai.neargo.shop.food.spi.DiningSessionInternalApi | RevokeCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String tableNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull RevokeTableTokenReq request |
| ai.neargo.shop.food.spi.DiningSessionInternalApi | ResolveCommand | @NotBlank @Size(max=128) String tableToken |
| ai.neargo.shop.food.spi.DiningSessionInternalApi | StartCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@Size(max=32) String buyerNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull StartDiningSessionReq request |
| ai.neargo.shop.food.spi.DiningSessionInternalApi | EndCommand | @NotBlank @Size(max=32) String sessionNo<br>@NotBlank @Size(max=128) String accessToken<br>@Size(max=32) String buyerNo<br>@NotBlank @Size(max=128) String idempotencyKey |
| ai.neargo.shop.food.spi.DiningTransactionRecoveryInternalApi | Scan | @JsonDeserialize(using = InventoryReservationPort.IntegerValue.class) int limit |
| ai.neargo.shop.food.spi.DishInternalApi | Lookup | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@Valid ListDishesQuery query |
| ai.neargo.shop.food.spi.DishInternalApi | Identity | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String goodsNo |
| ai.neargo.shop.food.spi.DishInternalApi | SaveCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String goodsNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull SaveDishReq dish |
| ai.neargo.shop.food.spi.FoodKdsInternalApi | Grant | String entityNo<br>String storeNo<br>String actorNo<br>GrantReq definition<br>String key |
| ai.neargo.shop.food.spi.FoodKdsInternalApi | Revoke | String entityNo<br>String storeNo<br>String actorNo<br>String subscriptionNo<br>RevokeReq definition<br>String key |
| ai.neargo.shop.food.spi.FoodKdsInternalApi | Replay | String entityNo<br>String storeNo<br>String actorNo<br>String subscriptionNo<br>long after<br>int limit |
| ai.neargo.shop.food.spi.FoodPrintInternalApi | Command | Scope scope<br>PrintDocumentReq definition<br>String requestKey |
| ai.neargo.shop.food.spi.FoodStationRuntimeInternalApi | Scope | String entityNo<br>String storeNo<br>String stationNo |
| ai.neargo.shop.food.spi.FoodStationRuntimeInternalApi | Control | String entityNo<br>String storeNo<br>String actorNo<br>String stationNo<br>ControlReq definition<br>String key |
| ai.neargo.shop.food.spi.FoodStationRuntimeInternalApi | Poll | String entityNo<br>String storeNo<br>String actorNo<br>String subscriptionNo |
| ai.neargo.shop.food.spi.FoodStationRuntimeInternalApi | Receipt | String entityNo<br>String storeNo<br>String actorNo<br>String subscriptionNo<br>VoiceReceipt definition |
| ai.neargo.shop.food.spi.FoodSupportInternalApi | ReadinessQuery |  |
| ai.neargo.shop.food.spi.FoodSupportInternalApi | CasesQuery | @NotBlank String entityNo<br>@NotBlank String storeNo<br>String status |
| ai.neargo.shop.food.spi.FoodSupportInternalApi | SourceQuery | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@Valid @NotNull Source source |
| ai.neargo.shop.food.spi.KitchenInternalApi | PackingCommand | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String actorNo<br>@NotBlank String batchNo<br>@NotBlank String idempotencyKey<br>@Valid @NotNull PackingReq definition |
| ai.neargo.shop.food.spi.KitchenInternalApi | ReceiptQuery | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String batchNo |
| ai.neargo.shop.food.spi.KitchenInternalApi | HandoverCommand | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String actorNo<br>@NotBlank String batchNo<br>@NotBlank String idempotencyKey<br>@Valid @NotNull HandoverReq definition |
| ai.neargo.shop.food.spi.KitchenInternalApi | FreezeQuery | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotNull List<@NotBlank String> skus |
| ai.neargo.shop.food.spi.KitchenInternalApi | DispatchCommand | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String batchNo<br>@NotBlank String dispatchKey |
| ai.neargo.shop.food.spi.KitchenInternalApi | QueueQuery | @NotBlank String entityNo<br>@NotBlank String storeNo<br>String stationNo<br>String batchNo |
| ai.neargo.shop.food.spi.KitchenInternalApi | TaskQuery | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String taskNo |
| ai.neargo.shop.food.spi.KitchenInternalApi | CourseQuery | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String checkNo |
| ai.neargo.shop.food.spi.KitchenInternalApi | TaskCommand | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String actorNo<br>@NotBlank String taskNo<br>@NotBlank String idempotencyKey<br>@Valid @NotNull TaskActionReq definition |
| ai.neargo.shop.food.spi.KitchenInternalApi | RemakeCommand | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String actorNo<br>@NotBlank String taskNo<br>@NotBlank String idempotencyKey<br>@Valid @NotNull RemakeReq definition |
| ai.neargo.shop.food.spi.KitchenInternalApi | CreateCourseCommand | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String actorNo<br>@NotBlank String checkNo<br>@NotBlank String idempotencyKey<br>@Valid @NotNull CourseReq definition |
| ai.neargo.shop.food.spi.KitchenInternalApi | CourseCommand | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String actorNo<br>@NotBlank String courseNo<br>@NotBlank String idempotencyKey<br>@Valid @NotNull CourseActionReq definition |
| ai.neargo.shop.food.spi.KitchenReturnInternalApi | Query | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String returnNo |
| ai.neargo.shop.food.spi.KitchenReturnInternalApi | Command | @NotBlank String entityNo<br>@NotBlank String storeNo<br>@NotBlank String actorNo<br>@NotBlank String returnNo<br>@NotBlank String idempotencyKey<br>@NotNull @Valid HandleReq definition |
| ai.neargo.shop.food.spi.MenuChannelBindingInternalApi | CreateCommand | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid CreateMenuChannelBindingReq definition |
| ai.neargo.shop.food.spi.MenuChannelBindingInternalApi | UpdateCommand | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String bindingNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid UpdateMenuChannelBindingReq definition |
| ai.neargo.shop.food.spi.MenuChannelBindingInternalApi | GetQuery | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String bindingNo |
| ai.neargo.shop.food.spi.MenuChannelBindingInternalApi | Lookup | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer page<br>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer size<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String provider<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo |
| ai.neargo.shop.food.spi.MenuInternalApi | PageReceipt | List<MenuVO> records<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long total<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long page<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long size |
| ai.neargo.shop.food.spi.MenuInternalApi | CreateCommand | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid CreateMenuReq definition |
| ai.neargo.shop.food.spi.MenuInternalApi | UpdateCommand | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid UpdateMenuReq definition |
| ai.neargo.shop.food.spi.MenuInternalApi | ScheduleCommand | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid ReplaceMenuScheduleReq definition |
| ai.neargo.shop.food.spi.MenuInternalApi | CompositionCommand | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid ReplaceMenuCompositionReq definition |
| ai.neargo.shop.food.spi.MenuInternalApi | ContextsCommand | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid ReplaceMenuContextsReq definition |
| ai.neargo.shop.food.spi.MenuInternalApi | DisableCommand | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid DisableMenuReq definition |
| ai.neargo.shop.food.spi.MenuInternalApi | GetQuery | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo |
| ai.neargo.shop.food.spi.MenuInternalApi | Lookup | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@Valid ListMenusQuery query |
| ai.neargo.shop.food.spi.ModifierInternalApi | CreateCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@NotNull @Valid CreateModifierReq definition |
| ai.neargo.shop.food.spi.ModifierInternalApi | UpdateCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String modifierNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@NotNull @Valid UpdateModifierReq definition |
| ai.neargo.shop.food.spi.ModifierInternalApi | Lookup | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@Valid ListModifiersQuery query |
| ai.neargo.shop.food.spi.OptionGroupInternalApi | ReplaceCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String groupNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@NotNull @Valid ReplaceOptionGroupItemsReq definition |
| ai.neargo.shop.food.spi.OptionGroupInternalApi | CreateCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull CreateOptionGroupReq group |
| ai.neargo.shop.food.spi.OptionGroupInternalApi | Identity | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String configNo |
| ai.neargo.shop.food.spi.OptionGroupInternalApi | Lookup | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@Valid ListOptionGroupsQuery query |
| ai.neargo.shop.food.spi.PricingSourceInternalApi | StaffCommand | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String entityNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo<br>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo<br>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long expectedVersion |
| ai.neargo.shop.food.spi.PricingSourceInternalApi | CustomerCommand | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo<br>@Valid @NotNull CartAccess access<br>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long expectedVersion |
| ai.neargo.shop.food.spi.ProductionConsumptionInternalApi | AdjustmentCommand | String entity<br>String store<br>String actor<br>String taskNo<br>ProductionContracts.AdjustmentReq request<br>String key |
| ai.neargo.shop.food.spi.ProductionConsumptionInternalApi | ReverseCommand | String entity<br>String store<br>String actor<br>String sourceEventNo<br>ProductionContracts.ReverseReq request<br>String key |
| ai.neargo.shop.food.spi.ProductionConsumptionInternalApi | ListCommand | @NotBlank @Size(max = 32) String entity<br>@NotBlank @Size(max = 32) String store<br>@NotBlank @Size(max = 32) String actor<br>@Valid @NotNull ListQuery query |
| ai.neargo.shop.food.spi.ProductionConsumptionInternalApi | Command | @NotBlank @Size(max = 32) String entity<br>@NotBlank @Size(max = 32) String store<br>@NotBlank @Size(max = 32) String actor<br>@NotBlank @Size(max = 32) String sourceEventNo |
| ai.neargo.shop.food.spi.QueueInternalApi | IssueCommand | @NotBlank @Size(max=32) String entity<br>@NotBlank @Size(max=32) String store<br>@NotBlank @Size(max=32) String actor<br>@NotNull @Valid IssueReq request<br>@NotBlank @Size(max=128) String key |
| ai.neargo.shop.food.spi.QueueInternalApi | ListQuery | @NotBlank @Size(max=32) String entity<br>@NotBlank @Size(max=32) String store<br>@NotBlank @Size(max=32) String actor<br>@NotNull @Valid Query query |
| ai.neargo.shop.food.spi.QueueInternalApi | Identity | @NotBlank @Size(max=32) String entity<br>@NotBlank @Size(max=32) String store<br>@NotBlank @Size(max=32) String actor<br>@NotBlank @Size(max=32) String ticketNo |
| ai.neargo.shop.food.spi.QueueInternalApi | CallNextCommand | @NotBlank @Size(max=32) String entity<br>@NotBlank @Size(max=32) String store<br>@NotBlank @Size(max=32) String actor<br>@NotNull LocalDate businessDate<br>@NotBlank @Size(max=128) String key |
| ai.neargo.shop.food.spi.QueueInternalApi | ChangeCommand | @NotBlank @Size(max=32) String entity<br>@NotBlank @Size(max=32) String store<br>@NotBlank @Size(max=32) String actor<br>@NotBlank @Size(max=32) String ticketNo<br>@NotBlank @Size(max=16) String action<br>@NotNull @Valid ChangeReq request<br>@NotBlank @Size(max=128) String key |
| ai.neargo.shop.food.spi.QueueInternalApi | SeatCommand | @NotBlank @Size(max=32) String entity<br>@NotBlank @Size(max=32) String store<br>@NotBlank @Size(max=32) String actor<br>@NotBlank @Size(max=32) String ticketNo<br>@NotNull @Valid SeatQueueReq request |
| ai.neargo.shop.food.spi.QueueNotificationPort | Request | String entityNo<br>String storeNo<br>String ticketNo<br>String contactRef<br>String originalKey |
| ai.neargo.shop.food.spi.QueueNotificationPort | Receipt | String originalKey<br>String status<br>String providerRef |
| ai.neargo.shop.food.spi.RecipeInternalApi | PageReceipt | List<RecipeVO> records<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long total<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long page<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long size |
| ai.neargo.shop.food.spi.RecipeInternalApi | CreateCommand | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid CreateRecipeReq definition |
| ai.neargo.shop.food.spi.RecipeInternalApi | UpdateCommand | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid ReplaceRecipeDefinitionReq definition |
| ai.neargo.shop.food.spi.RecipeInternalApi | GetQuery | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo |
| ai.neargo.shop.food.spi.RecipeInternalApi | Lookup | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@Valid ListRecipesQuery query |
| ai.neargo.shop.food.spi.ReservationInternalApi | Lookup | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@Valid @NotNull ListReservationsQuery query |
| ai.neargo.shop.food.spi.ReservationInternalApi | NumberLookup | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String reservationNo |
| ai.neargo.shop.food.spi.ReservationInternalApi | CreateCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String actorNo<br>@Valid @NotNull CreateReq request<br>@NotBlank @Size(max=128) String key |
| ai.neargo.shop.food.spi.ReservationInternalApi | ChangeCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=32) String reservationNo<br>@Valid @NotNull ChangeReq request<br>@NotBlank @Size(max=128) String key |
| ai.neargo.shop.food.spi.ReservationInternalApi | TransitionCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=32) String reservationNo<br>@Valid @NotNull TransitionReq request<br>@NotBlank @Size(max=128) String key |
| ai.neargo.shop.food.spi.ReservationInternalApi | ArriveCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=32) String reservationNo<br>@Valid @NotNull ArriveReq request<br>@NotBlank @Size(max=128) String key |
| ai.neargo.shop.food.spi.SalesRestrictionInternalApi | PageReceipt | List<SalesRestrictionVO> records<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long total<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long page<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long size |
| ai.neargo.shop.food.spi.SalesRestrictionInternalApi | Command | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid SalesRestrictionReq definition |
| ai.neargo.shop.food.spi.SalesRestrictionInternalApi | Lookup | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@Valid SalesRestrictionQuery query |
| ai.neargo.shop.food.spi.SalesRestrictionInternalApi | Evaluation | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment |
| ai.neargo.shop.food.spi.ServiceCalendarInternalApi | Command | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotNull LocalDate localDate<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@NotNull @Valid SaveCalendarReq calendar |
| ai.neargo.shop.food.spi.StationInternalApi | CreateCommand | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid CreateStationReq definition |
| ai.neargo.shop.food.spi.StationInternalApi | UpdateCommand | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid UpdateStationReq definition |
| ai.neargo.shop.food.spi.StationInternalApi | RoutesCommand | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo<br>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey<br>@NotNull @Valid ReplaceStationRoutesReq definition |
| ai.neargo.shop.food.spi.StationInternalApi | GetQuery | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo |
| ai.neargo.shop.food.spi.StationInternalApi | Lookup | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo<br>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo<br>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer page<br>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer size |
| ai.neargo.shop.food.spi.StoreOperationInternalApi | Lookup | @NotBlank @Size(max = 32) String entityNo<br>@NotBlank @Size(max = 32) String storeNo<br>Instant at |
| ai.neargo.shop.food.spi.StorePolicyPublicationInternalApi | Command | @NotBlank @Size(max = 32) String entityNo<br>@NotBlank @Size(max = 32) String storeNo<br>@NotBlank @Size(max = 32) String actorNo<br>@NotBlank @Size(max = 128) String idempotencyKey<br>@NotNull @Valid CreatePolicyReq policy |
| ai.neargo.shop.food.spi.TableInternalApi | Lookup | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@Valid ListTableAreasQuery query |
| ai.neargo.shop.food.spi.TableInternalApi | CreateCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull CreateTableAreaReq area |
| ai.neargo.shop.food.spi.TableInternalApi | SaveCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String areaNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull SaveTableAreaReq area |
| ai.neargo.shop.food.spi.TableInternalApi | StatusCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String areaNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull SetStatusTableAreaReq area |
| ai.neargo.shop.food.spi.TableInternalApi | TableLookup | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@Valid ListTablesQuery query |
| ai.neargo.shop.food.spi.TableInternalApi | TableIdentity | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String tableNo |
| ai.neargo.shop.food.spi.TableInternalApi | TableCreateCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull CreateTableReq table |
| ai.neargo.shop.food.spi.TableInternalApi | TableSaveCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String tableNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull SaveTableReq table |
| ai.neargo.shop.food.spi.TableInternalApi | TableStatusCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String tableNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull SetStatusTableReq table |
| ai.neargo.shop.food.spi.TableInternalApi | TableArchiveCommand | @NotBlank @Size(max=32) String entityNo<br>@NotBlank @Size(max=32) String storeNo<br>@NotBlank @Size(max=32) String tableNo<br>@NotBlank @Size(max=32) String actorNo<br>@NotBlank @Size(max=128) String idempotencyKey<br>@Valid @NotNull ArchiveTableReq table |
| ai.neargo.shop.spi.food.BundleChanged | BundleChanged | String entityNo<br>String storeNo<br>String skuNo<br>String action<br>long version<br>String operationNo<br>BundleConfigVO after |
| ai.neargo.shop.spi.food.CartChanged | CartChanged | String entityNo<br>String storeNo<br>String cartNo<br>String action<br>long version<br>String operationNo |
| ai.neargo.shop.spi.food.CheckChanged | CheckChanged | String entityNo<br>String storeNo<br>String checkNo<br>String action<br>long version<br>String operationNo<br>CheckVO before<br>CheckVO after<br>String reason<br>List<SeatReq> beforeSeats<br>List<SeatReq> afterSeats |
| ai.neargo.shop.spi.food.ConfigPublished | ConfigPublished | String entityNo<br>String storeNo<br>String operationNo<br>ConfigRevisionVO publication |
| ai.neargo.shop.spi.food.DiningDeviceChanged | DiningDeviceChanged | String entityNo<br>String storeNo<br>String deviceNo<br>String action<br>long version<br>long generation<br>String operationNo |
| ai.neargo.shop.spi.food.DiningOrderAccepted | DiningOrderAccepted | String batchNo<br>String entityNo<br>String storeNo<br>String checkNo<br>String orderNo<br>String subOrderNo<br>LocalDate businessDate<br>String fulfillment<br>Instant acceptedAt |
| ai.neargo.shop.spi.food.DiningQueueChanged | DiningQueueChanged | String entityNo<br>String storeNo<br>String ticketNo<br>LocalDate businessDate<br>String status<br>int partySize<br>String checkNo<br>String actorNo<br>long version |
| ai.neargo.shop.spi.food.DiningServiceEvent | DiningServiceEvent | String eventNo<br>String entityNo<br>String storeNo<br>String batchNo<br>String ticketNo<br>String taskNo<br>String foodLineNo<br>String componentNo<br>String stationNo<br>String taskRole<br>long routeVersion<br>String action<br>BigDecimal qty<br>String reason<br>String actorNo<br>Instant occurredAt |
| ai.neargo.shop.spi.food.DiningSessionChanged | DiningSessionChanged | String entityNo<br>String storeNo<br>String tableNo<br>String sessionNo<br>String action<br>long version<br>String operationNo |
| ai.neargo.shop.spi.food.DiningStationRuntimeChanged | DiningStationRuntimeChanged | String entityNo<br>String storeNo<br>String stationNo<br>String commandNo<br>String action<br>long sourceVersion<br>String status<br>String reason<br>Instant occurredAt |
| ai.neargo.shop.spi.food.DishChanged | DishChanged | String entityNo<br>String storeNo<br>String goodsNo<br>String action<br>long version<br>String operationNo<br>DishVO before<br>DishVO after |
| ai.neargo.shop.spi.food.MenuChanged | MenuChanged | String entityNo<br>String storeNo<br>String menuNo<br>String action<br>long version<br>String operationNo<br>MenuVO after<br>MenuScheduleVO schedule<br>MenuCompositionVO composition<br>List<MenuContextInput> contexts |
| ai.neargo.shop.spi.food.MenuChannelBindingChanged | MenuChannelBindingChanged | String entityNo<br>String storeNo<br>String bindingNo<br>String action<br>long version<br>String operationNo<br>MenuChannelBindingVO after |
| ai.neargo.shop.spi.food.ModifierChanged | ModifierChanged | String entityNo<br>String storeNo<br>String modifierNo<br>String action<br>long version<br>String operationNo<br>ModifierConfigVO after |
| ai.neargo.shop.spi.food.OptionGroupChanged | OptionGroupChanged | String entityNo<br>String storeNo<br>String groupNo<br>String action<br>long version<br>String operationNo<br>OptionGroupConfigVO after |
| ai.neargo.shop.spi.food.RecipeChanged | RecipeChanged | String entityNo<br>String storeNo<br>String recipeNo<br>String action<br>long version<br>String operationNo<br>RecipeVO after |
| ai.neargo.shop.spi.food.ReservationChanged | ReservationChanged | String entityNo<br>String storeNo<br>String reservationNo<br>String action<br>long version<br>String operationNo<br>DetailVO after<br>String reason |
| ai.neargo.shop.spi.food.SalesRestrictionChanged | SalesRestrictionChanged | String entityNo<br>String storeNo<br>String restrictionNo<br>String action<br>String operationNo<br>SalesRestrictionVO after |
| ai.neargo.shop.spi.food.ServiceCalendarSaved | ServiceCalendarSaved | String entityNo<br>String storeNo<br>String calendarNo<br>LocalDate localDate<br>String mode<br>long version<br>String operationNo |
| ai.neargo.shop.spi.food.StationChanged | StationChanged | String entityNo<br>String storeNo<br>String configNo<br>String action<br>long version<br>String operationNo<br>Object after |
| ai.neargo.shop.spi.food.StorePolicyPublished | StorePolicyPublished | String entityNo<br>String storeNo<br>String policyNo<br>long version<br>Instant effectiveFrom<br>String operationNo |
| ai.neargo.shop.spi.food.TableAreaChanged | TableAreaChanged | String entityNo<br>String storeNo<br>String areaNo<br>String action<br>long version<br>String operationNo<br>TableAreaVO before<br>TableAreaVO after<br>String reason |
| ai.neargo.shop.spi.food.TableChanged | TableChanged | String entityNo<br>String storeNo<br>String tableNo<br>String action<br>long version<br>String operationNo<br>TableVO before<br>TableVO after<br>String reason |
| ai.neargo.shop.food.channel.port.CoreInventoryReservationPortImpl | Key | String skuNo<br>String fulfillmentRef |
| ai.neargo.shop.food.config.FoodProperties | FoodProperties | boolean enabled<br>@DefaultValue("embedded") Deployment deployment<br>@DefaultValue Migration migration |
| ai.neargo.shop.food.config.FoodProperties | Migration | @DefaultValue("validate") MigrationMode mode |
| ai.neargo.shop.food.config.FoodSchemaReady | FoodSchemaReady | boolean validated<br>String version<br>List<String> missingCapabilities |
| ai.neargo.shop.food.entity.FnbKdsEvent | FnbKdsEvent | String entityNo<br>String storeNo<br>long sequenceNo<br>String eventNo<br>String stationNo<br>String eventType<br>String sourceNo<br>long sourceVersion<br>String payload<br>Instant occurredAt |
| ai.neargo.shop.food.entity.FnbKdsSubscription | FnbKdsSubscription | String subscriptionNo<br>String entityNo<br>String storeNo<br>String deviceNo<br>String actorNo<br>String stations<br>long deviceVersion<br>Instant expiresAt<br>String status<br>long version<br>String requestedBy<br>String requestKey<br>String requestHash<br>String revokeKey<br>String revokeReason<br>String revokedBy |
| ai.neargo.shop.food.mapper.DiningBatchQueryMapper | SourceRow | String batchNo<br>String storeNo<br>String originalCheckNo<br>String status<br>String orderSource<br>LocalDate businessDate<br>String fulfillment<br>String payMode<br>String quoteNo<br>String quoteHash<br>LocalDateTime createdAt<br>LocalDateTime acceptedAt<br>LocalDateTime scheduledAt<br>long version<br>boolean membershipAuthoritative |
| ai.neargo.shop.food.mapper.DiningBatchQueryMapper | MembershipRow | String batchNo<br>String checkNo |
| ai.neargo.shop.food.mapper.DiningBatchQueryMapper | BindingRow | String batchNo<br>String bindingNo<br>String paymentRootNo<br>String storeOrderNo<br>String bindingStatus |
| ai.neargo.shop.food.policy.BundlePublicationSource | Captured | BundleConfigVO definition<br>List<String> skuNos |
| ai.neargo.shop.food.policy.BusinessSharePolicy | Remainder | String person<br>BigInteger remainder |
| ai.neargo.shop.food.policy.MenuConflictPolicy | ChannelIdentity | String entity<br>String store<br>String provider<br>String account<br>String externalStore |
| ai.neargo.shop.food.policy.MenuConflictPolicy | LocalWindow | LocalDateTime start<br>LocalDateTime end |
| ai.neargo.shop.food.policy.MenuConflictPolicy | Window | Instant start<br>Instant end |
| ai.neargo.shop.food.policy.RecipePublicationSource | Group | long workingVersion<br>String workingStatus<br>Dependency publication<br>PublicationSnapshot definition<br>List<String> goodsNos |
| ai.neargo.shop.food.policy.RecipePublicationSource | Captured | RecipeVO recipe<br>List<Group> groups |
| ai.neargo.shop.food.policy.ServiceCalendarPolicy | DayRule | LocalDate date<br>String mode<br>List<String> intervals |
| ai.neargo.shop.food.policy.StationPublicationSource | Route | String skuNo<br>String taskRole<br>long routeVersion |
| ai.neargo.shop.food.policy.StationPublicationSource | Captured | StationVO definition<br>List<Route> routes<br>List<String> skuNos |
| ai.neargo.shop.food.service.impl.BusinessShareServiceImpl | PlanReference | String planNo |
| ai.neargo.shop.food.service.impl.CatalogServiceImpl | Selected | ConfigRevisionContentVO revision<br>MenuPublicationSnapshot body |
| ai.neargo.shop.food.service.impl.CheckCartServiceImpl | Envelope | int schema<br>OrderLineInput line |
| ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl | RepublicationReason | String reason<br>String approvalRef |
| ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl | Candidate | long version<br>ModifierConfigVO modifier<br>OptionGroupConfigVO optionGroup<br>BundleConfigVO bundle<br>List<Dependency> dependencies<br>List<String> skuNos<br>List<String> specNos<br>RecipePublicationSource.Captured recipe<br>MenuPublicationSnapshot.Captured menu<br>StationPublicationSource.Captured station |
| ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl | Frozen | int schema<br>String batchNo<br>String buyerNo<br>String root<br>String sub<br>String quote<br>long sourceVersion<br>String sourceHash<br>String economicsHash<br>String confirmationDigest<br>String currency<br>long amountMinor<br>long deadlineAt |
| ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl | PayFrozen | int schema<br>PayDiningSettlementReq request<br>PayCommand command |
| ai.neargo.shop.food.service.impl.DiningDeliveryServiceImpl | Receipt | String keyHash<br>String requestHash<br>DeliverySnapshot result |
| ai.neargo.shop.food.service.impl.DiningOrderServiceImpl | Frozen | int schema<br>DiningPriceFacts facts<br>QuoteVO quote<br>StoreOperationPolicyResultVO policy<br>FrozenRoutes kitchenRoutes<br>Plan inventoryPlan |
| ai.neargo.shop.food.service.impl.DiningPickupVerificationServiceImpl | PartKey | String line<br>String component |
| ai.neargo.shop.food.service.impl.DiningReturnServiceImpl | SourceItem | String foodLineNo<br>String sourceLineRef<br>String componentRef<br>String allocationNo<br>BigDecimal quantity |
| ai.neargo.shop.food.service.impl.DiningReturnServiceImpl | Frozen | int schema<br>IndustryAfterSalePort.Apply command<br>List<SourceItem> items |
| ai.neargo.shop.food.service.impl.DiningTransactionRecoveryServiceImpl | Candidate | Kind kind<br>long id<br>String entity<br>String store<br>String number<br>long version<br>LocalDateTime due<br>LocalDateTime created |
| ai.neargo.shop.food.service.impl.ProductionConsumptionServiceImpl | BlockedProjection | int schema<br>String blockReason<br>String entityNo<br>String storeNo<br>String sourceEventNo<br>String taskNo<br>String ticketNo<br>String foodLineNo<br>String componentNo<br>String frozenSourceHash<br>Command theory |
| ai.neargo.shop.food.service.impl.ProductionConsumptionServiceImpl | Recipe | String revisionNo<br>RecipePublicationSnapshot body |
| ai.neargo.shop.food.api.FoodBizDataScopeInterceptor | Saved | Object handler<br>DataScopeSpec previous |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceFormRegisterOrganizationRequest | @Valid @NotNull ServiceFormContracts.OrganizationReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceFormBookRequest | @Valid @NotNull ServiceFormContracts.BookReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceFormGetRequest | @NotBlank String form |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceFormReviseRequest | @NotBlank String form<br>@Valid @NotNull ServiceFormContracts.ReviseReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceFormEnterRequest | @NotBlank String form<br>@Valid @NotNull ServiceFormContracts.EnterReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceFormLeaveRequest | @NotBlank String form<br>@Valid @NotNull ServiceFormContracts.VersionReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceFormCancelRequest | @NotBlank String form<br>@Valid @NotNull ServiceFormContracts.VersionReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceFormNoShowRequest | @NotBlank String form<br>@Valid @NotNull ServiceFormContracts.VersionReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceFormBindBatchRequest | @NotBlank String form<br>@Valid @NotNull ServiceFormContracts.BatchReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceFormAttendeesRequest | @NotBlank String form |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceFormAdmitRequest | @NotBlank String form<br>@NotBlank String attendee<br>@Valid @NotNull ServiceFormContracts.VersionReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceFormExitRequest | @NotBlank String form<br>@NotBlank String attendee<br>@Valid @NotNull ServiceFormContracts.VersionReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | FoodFeeSourceBindRequest | @Valid @NotNull FoodFeeSourceContracts.BindReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | FoodFeeSourceBindingsRequest | @NotBlank String check |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | QueueNotificationListRequest | @NotBlank String ticket |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | QueueNotificationRecoverRequest | @NotBlank String notice |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | CheckCollaborationPreviewReceivableRequest | @NotBlank String check |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | CheckCollaborationSourceHashRequest | @NotBlank String check |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | CheckCollaborationReleaseServiceRequest | @NotBlank String check<br>long version<br>@NotBlank String reason |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | CheckCollaborationCurrentLineOwnerRequest | @NotBlank String foodLineNo |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | CheckCollaborationMoveBatchesRequest | @Valid @NotNull CheckCollaborationContracts.MoveReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | CheckCollaborationMoveLinesRequest | @Valid @NotNull CheckCollaborationContracts.LineMoveReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | CheckCollaborationFreezeRequest | @NotBlank String check<br>@Valid @NotNull CheckCollaborationContracts.FreezeReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | CheckCollaborationReleaseRequest | @NotBlank String freeze<br>long version<br>@NotBlank String reason |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ReservationPreorderSaveRequest | @NotBlank String reservation<br>@Valid @NotNull ReservationPreorderContracts.SaveReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ReservationPreorderGetRequest | @NotBlank String reservation<br>@NotBlank String preorder |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ReservationPreorderPrepareCartRequest | @NotBlank String reservation<br>@NotBlank String preorder<br>long version |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ReservationPreorderConvertRequest | @NotBlank String reservation<br>@NotBlank String preorder<br>@Valid @NotNull ReservationPreorderContracts.ConvertReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ReservationPreorderCancelRequest | @NotBlank String reservation<br>@NotBlank String preorder<br>long version<br>@NotBlank String reason |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceSlotCreateRequest | @Valid @NotNull ServiceSlotContracts.CreateReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceSlotGetRequest | @NotBlank String slot |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceSlotReserveRequest | @Valid @NotNull ServiceSlotContracts.ClaimReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceSlotTransitionRequest | @NotBlank String claim<br>long version<br>@NotBlank String action |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | ServiceSlotDispatchDueRequest | @NotBlank String sourceNo |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | BusinessOperationsEvidenceRequest | @Valid @NotNull BusinessOperationsContracts.Source source |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | BusinessOperationsProposeRequest | @Valid @NotNull BusinessOperationsContracts.HandoverReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | BusinessOperationsHandoverRequest | @NotBlank String handover |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | BusinessOperationsAcceptRequest | @NotBlank String handover<br>@Valid @NotNull BusinessOperationsContracts.DecisionReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | BusinessOperationsCancelRequest | @NotBlank String handover<br>@Valid @NotNull BusinessOperationsContracts.DecisionReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | BusinessOperationsOpenCaseRequest | @Valid @NotNull BusinessOperationsContracts.CaseReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | BusinessOperationsCasesRequest | String status |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | BusinessOperationsRecoverCaseRequest | @NotBlank String caseNo<br>@Valid @NotNull BusinessOperationsContracts.DecisionReq request |
| ai.neargo.shop.food.api.biz.BizBusinessOperationsController | BusinessOperationsResolveCaseRequest | @NotBlank String caseNo<br>@Valid @NotNull BusinessOperationsContracts.DecisionReq request |
| ai.neargo.shop.food.api.biz.BizPrintDeviceController | RevokeReq | @NotBlank @Size(max = 255) String reason |
| ai.neargo.shop.food.api.biz.BizPrintDeviceController | HeartbeatReq | boolean online |
| ai.neargo.shop.food.api.biz.BizPrintJobController | ReprintReq | @NotBlank @Size(max=256) String reason |
| ai.neargo.shop.portal.biz.BizPricingFeeScopeController | ApplyReq | long expectedSubVersion<br>String sourceHash<br>String reason |
| ai.neargo.shop.printing.api.internal.InternalPrintController | ResolveDeviceCommand | Scope scope<br>PrintService.DeviceResolution resolution |
| ai.neargo.shop.printing.api.internal.InternalPrintController | RecoveryQuery | Scope scope<br>String deviceNo |
| ai.neargo.shop.common.pricing.PricingFeeTaxEngine | Result | Map<PricingPortion, Long> fees<br>Map<PricingPortion, Long> taxes |
| ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi | Reserve | Plan plan<br>long ttlSeconds<br>int lifecycleVersion |
| ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi | Reference | Scope scope |
| ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi | Change | Scope scope<br>String operationRef<br>String planHash<br>String phase<br>List<Target> targets |
| ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi | Reverse | Scope scope<br>String operationRef<br>String originalOperationRef<br>String planHash |
| ai.neargo.shop.spi.inventory.InventoryDemandPlanPort | Leaf | String leafRef<br>String itemRef<br>BigDecimal quantity<br>String unit<br>String definitionHash |
| ai.neargo.shop.spi.inventory.InventoryDemandPlanPort | Branch | String sourceComponentRef<br>String fulfillmentRef<br>String storeNo<br>String kind<br>BigDecimal quantity<br>String unit<br>List<Leaf> leaves<br>String recipeRevisionNo |
| ai.neargo.shop.spi.inventory.InventoryDemandPlanPort | Plan | Scope scope<br>String planNo<br>String planHash<br>List<Branch> branches |
| ai.neargo.shop.spi.inventory.InventoryDemandPlanPort | Target | String leafRef<br>BigDecimal cumulativeQty |
| ai.neargo.shop.spi.inventory.InventoryDemandPlanPort | Progress | String leafRef<br>String sourceComponentRef<br>String fulfillmentRef<br>String kind<br>String itemRef<br>BigDecimal originalQty<br>BigDecimal issuedQty<br>BigDecimal releasedQty<br>String unit<br>String definitionHash<br>String documentNo<br>String ledgerRef<br>Long costMinor<br>String currency<br>String valuationStatus |
| ai.neargo.shop.spi.inventory.InventoryDemandPlanPort | Receipt | Scope scope<br>String planNo<br>String planHash<br>String status<br>int lifecycleVersion<br>Instant expiresAt<br>List<Progress> lines |
| ai.neargo.shop.spi.inventory.InventoryProductionPort | Line | String itemRef<br>BigDecimal quantity<br>String unit<br>String definitionHash |
| ai.neargo.shop.spi.inventory.InventoryProductionPort | Command | String entityNo<br>String storeNo<br>String eventNo<br>String purpose<br>Instant occurredAt<br>List<Line> lines<br>@JsonInclude(JsonInclude.Include.NON_NULL) PlanProof planProof |
| ai.neargo.shop.spi.inventory.InventoryProductionPort | SourceLeaf | String leafRef<br>BigDecimal quantity |
| ai.neargo.shop.spi.inventory.InventoryProductionPort | PlanProof | InventoryReservationPort.Scope scope<br>String planHash<br>String sourceComponentRef<br>List<SourceLeaf> leaves |
| ai.neargo.shop.spi.inventory.InventoryProductionPort | Reverse | Command original<br>String reverseEventNo<br>String reason |
| ai.neargo.shop.spi.inventory.InventoryProductionPort | IssuedLine | String leafRef<br>String itemRef<br>BigDecimal quantity<br>String unit<br>String documentNo<br>String ledgerRef<br>Long costMinor<br>String currency<br>String batchNo<br>String valuationStatus |
| ai.neargo.shop.spi.inventory.InventoryProductionPort | Receipt | String eventNo<br>String requestHash<br>String status<br>String documentNo<br>List<Line> lines<br>Long costMinor<br>String currency<br>List<IssuedLine> issuedLines |
| ai.neargo.shop.spi.printing.PrintInternalApi | ClaimResult | @JsonProperty(value = "claim", required = true) Claim claim |
| ai.neargo.shop.spi.printing.PrintInternalApi | DeviceCommand | Scope scope<br>DeviceReq definition |
| ai.neargo.shop.spi.printing.PrintInternalApi | RevokeDeviceCommand | Scope scope<br>String deviceNo<br>String reason<br>String requestKey |
| ai.neargo.shop.spi.printing.PrintInternalApi | TemplateCommand | Scope scope<br>TemplateReq definition |
| ai.neargo.shop.spi.printing.PrintInternalApi | RouteCommand | Scope scope<br>RouteReq definition |
| ai.neargo.shop.spi.printing.PrintInternalApi | EnqueueCommand | Scope scope<br>Enqueue definition |
| ai.neargo.shop.spi.printing.PrintInternalApi | ListQuery | Scope scope<br>String afterJobNo<br>int limit |
| ai.neargo.shop.spi.printing.PrintInternalApi | JobQuery | Scope scope<br>String jobNo |
| ai.neargo.shop.spi.printing.PrintInternalApi | ReprintCommand | Scope scope<br>String jobNo<br>String reason<br>String requestKey |
| ai.neargo.shop.spi.printing.PrintInternalApi | HeartbeatCommand | Scope scope<br>String deviceNo<br>boolean online |
| ai.neargo.shop.spi.printing.PrintInternalApi | DeviceQuery | Scope scope<br>String deviceNo |
| ai.neargo.shop.spi.printing.PrintInternalApi | AckCommand | Scope scope<br>String deviceNo<br>Acknowledge receipt |
| ai.neargo.shop.spi.printing.PrintJobChanged | PrintJobChanged | String entityNo<br>String storeNo<br>String jobNo<br>String status<br>String deviceNo<br>int attemptCount |
| ai.neargo.shop.spi.printing.PrintPort | Scope | String entityNo<br>String storeNo<br>String actorNo |
| ai.neargo.shop.spi.printing.PrintPort | DeviceReq | String deviceNo<br>String vendor<br>String connectionType<br>String credentialRef<br>String bridgeActorNo<br>String capabilities<br>boolean active |
| ai.neargo.shop.spi.printing.PrintPort | DeviceVO | String deviceNo<br>String vendor<br>String connectionType<br>String bridgeActorNo<br>String status<br>String capabilities<br>LocalDateTime lastSeenAt<br>boolean online<br>long authorizationVersion |
| ai.neargo.shop.spi.printing.PrintPort | TemplateReq | String templateNo<br>String templateCode<br>int revision<br>String scene<br>String content |
| ai.neargo.shop.spi.printing.PrintPort | RouteReq | String routeNo<br>String scene<br>String stationNo<br>String deviceNo<br>String templateNo<br>int copies |
| ai.neargo.shop.spi.printing.PrintPort | Configuration | List<DeviceVO> devices<br>List<TemplateReq> templates<br>List<RouteReq> routes |
| ai.neargo.shop.spi.printing.PrintPort | Line | String label<br>String quantity<br>Long amountMinor<br>String note |
| ai.neargo.shop.spi.printing.PrintPort | Document | int schemaVersion<br>String scene<br>String sourceRef<br>String title<br>String currency<br>List<Line> lines<br>String notice |
| ai.neargo.shop.spi.printing.PrintPort | Enqueue | String sourceEventNo<br>String stationNo<br>String partNo<br>Document document |
| ai.neargo.shop.spi.printing.PrintPort | AttemptVO | String attemptNo<br>int sequenceNo<br>String status<br>String responseCode<br>LocalDateTime startedAt<br>LocalDateTime finishedAt |
| ai.neargo.shop.spi.printing.PrintPort | JobVO | String jobNo<br>String storeNo<br>String sourceEventNo<br>String scene<br>String deviceNo<br>String partNo<br>int copyIndex<br>String templateNo<br>String status<br>String originalJobNo<br>String reprintReason<br>String requestedBy<br>Document document<br>String renderedContent<br>List<AttemptVO> attempts |
| ai.neargo.shop.spi.printing.PrintPort | Claim | String jobNo<br>String deviceNo<br>String attemptNo<br>String leaseToken<br>LocalDateTime leaseUntil<br>String renderedContent |
| ai.neargo.shop.spi.printing.PrintPort | Acknowledge | String attemptNo<br>String leaseToken<br>String status<br>String channelJobNo<br>String responseCode |
| ai.neargo.shop.spi.trade.IndustryAfterSalePort | Identity | String tenantNo<br>String entityNo<br>String storeNo<br>String buyerNo<br>String actorNo |
| ai.neargo.shop.spi.trade.IndustryAfterSalePort | Binding | PricingSourceRef source<br>String orderNo<br>String subOrderNo |
| ai.neargo.shop.spi.trade.IndustryAfterSalePort | LineQty | String sourceLineNo<br>String componentRef<br>int qty |
| ai.neargo.shop.spi.trade.IndustryAfterSalePort | Apply | Identity identity<br>Binding binding<br>String requestKey<br>String type<br>String reason<br>String reasonCode<br>List<String> images<br>List<LineQty> lines |
| ai.neargo.shop.spi.trade.IndustryAfterSalePort | QueryApply | Identity identity<br>Binding binding<br>String requestKey |
| ai.neargo.shop.spi.trade.IndustryAfterSalePort | Lookup | boolean found<br>Result result |
| ai.neargo.shop.spi.trade.IndustryAfterSalePort | Reference | Identity identity<br>Binding binding<br>String afterSaleNo |
| ai.neargo.shop.spi.trade.IndustryAfterSalePort | Progress | String executionNo<br>String status<br>String phase<br>String errorCode<br>String resourceStatus<br>String resourceErrorCode<br>String closureStatus<br>String cashStatus<br>String benefitsStatus<br>Long succeededAt |
| ai.neargo.shop.spi.trade.IndustryAfterSalePort | Result | String afterSaleNo<br>String orderNo<br>String subOrderNo<br>String status<br>long refundMinor<br>String currency<br>Progress progress<br>String returnInventoryStatus |
| ai.neargo.shop.spi.trade.IndustryAmendmentSourceInternalApi | Query | IndustryFulfillmentPort.Reference reference<br>String sourceReturnNo |
| ai.neargo.shop.spi.trade.IndustryAmendmentSourcePort | Evidence | Reference reference<br>String returnNo<br>String sourceHash<br>String kitchenStatus<br>String actorNo<br>List<Reduction> lines<br>String replacementCartNo |
| ai.neargo.shop.spi.trade.IndustryCheckoutInternalApi | Reference | Identity identity<br>String orderNo |
| ai.neargo.shop.spi.trade.IndustryCheckoutPort | PayCommand | Identity identity<br>String orderNo<br>String quoteNo<br>PricingSourceRef source<br>String confirmationDigest<br>String payChannel |
| ai.neargo.shop.spi.trade.IndustryCheckoutPort | ChannelVO | String payChannel<br>boolean available<br>String reason |
| ai.neargo.shop.spi.trade.IndustryCheckoutPort | PaymentVO | String outTradeNo<br>String payChannel<br>String status<br>Map<String, Object> payParams<br>String paramsExpiresAt |
| ai.neargo.shop.spi.trade.IndustryCheckoutPort | View | String orderNo<br>String subOrderNo<br>String quoteNo<br>long sourceVersion<br>String sourceHash<br>String economicsHash<br>String confirmationDigest<br>String orderStatus<br>String currency<br>long amountMinor<br>Long payDeadlineAt<br>List<ChannelVO> channels<br>PaymentVO payment<br>boolean readyToAccept |
| ai.neargo.shop.spi.trade.IndustryFulfillmentInternalApi | Partial | IndustryFulfillmentPort.Fulfill command<br>String key |
| ai.neargo.shop.spi.trade.IndustryFulfillmentInternalApi | Verify | VerifyPickup command<br>String key |
| ai.neargo.shop.spi.trade.IndustryFulfillmentInternalApi | Query | Reference reference<br>String actorNo<br>String key |
| ai.neargo.shop.spi.trade.IndustryFulfillmentPort | Reference | Scope scope<br>PricingSourceRef source<br>String orderNo<br>String subOrderNo |
| ai.neargo.shop.spi.trade.IndustryFulfillmentPort | VerifyPickup | Reference reference<br>String actorNo<br>@JsonDeserialize(using=CatalogRevisionDeserializer.class) long expectedVersion<br>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String verifyCode |
| ai.neargo.shop.spi.trade.IndustryFulfillmentPort | ItemTarget | Long orderItemId<br>String sourceComponentRef<br>BigDecimal cumulativeQty<br>String unit |
| ai.neargo.shop.spi.trade.IndustryFulfillmentPort | Fulfill | Reference reference<br>String actorNo<br>long expectedVersion<br>String kind<br>String sourceOperationRef<br>String planHash<br>String verifyCode<br>String recipientNo<br>String recipientRef<br>List<ItemTarget> lines |
| ai.neargo.shop.spi.trade.IndustryFulfillmentPort | FulfilledLine | Long orderItemId<br>String sourceComponentRef<br>BigDecimal fulfilledQty<br>String unit |
| ai.neargo.shop.spi.trade.IndustryFulfillmentPort | Receipt | String fulfillmentNo<br>Reference reference<br>String status<br>String subOrderStatus<br>boolean requireBuyerConfirm<br>boolean pending<br>String inventoryReservationNo<br>String inventoryState<br>int inventoryLifecycleVersion<br>long subOrderVersion<br>List<FulfilledLine> lines |
| ai.neargo.shop.spi.trade.IndustryOrderAmendmentInternalApi | Amend | IndustryOrderAmendmentPort.Command command<br>String key |
| ai.neargo.shop.spi.trade.IndustryOrderAmendmentInternalApi | Fee | IndustryOrderAmendmentPort.FeeScopeAdjustment command<br>String key |
| ai.neargo.shop.spi.trade.IndustryOrderAmendmentInternalApi | Query | IndustryFulfillmentPort.Reference reference<br>String actorNo<br>String key |
| ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort | Reduction | Long orderItemId<br>String sourceComponentRef<br>BigDecimal quantity<br>String unit<br>String disposition |
| ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort | Command | Reference reference<br>String actorNo<br>long expectedSubVersion<br>String sourceReturnNo<br>String sourceHash<br>List<Reduction> lines<br>String reason<br>String replacementCartNo |
| ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort | FeeScopeAdjustment | Reference reference<br>String actorNo<br>long expectedSubVersion<br>String feeApplicationNo<br>long targetFeeMinor<br>String sourceHash<br>String reason |
| ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort | Line | Long orderItemId<br>String sourceComponentRef<br>BigDecimal reducedQty<br>BigDecimal physicalReductionQty<br>String unit<br>long receivableReductionMinor |
| ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort | Receipt | String amendmentNo<br>Reference reference<br>String status<br>boolean pending<br>String currency<br>Long receivableAdjustmentMinor<br>Long effectiveReceivableMinor<br>long subOrderVersion<br>List<Line> lines<br>String replacementCartNo |
| ai.neargo.shop.spi.trade.IndustryOrderInternalApi | ServicePlace | Command command<br>String key<br>String fulfillment<br>boolean staff |
| ai.neargo.shop.spi.trade.IndustryOrderInternalApi | StoreCancel | Command command<br>String key<br>String fulfillment |
| ai.neargo.shop.spi.trade.IndustryOrderInternalApi | StoreGet | Scope scope<br>String actorNo<br>String orderNo |
| ai.neargo.shop.spi.trade.IndustryOrderInternalApi | Place | Command command<br>String key |
| ai.neargo.shop.spi.trade.IndustryOrderInternalApi | Query | Identity identity<br>String key |
| ai.neargo.shop.spi.trade.IndustryOrderInternalApi | Get | Identity identity<br>String orderNo |
| ai.neargo.shop.spi.trade.IndustryOrderPort | Identity | String tenantNo<br>String entityNo<br>String storeNo<br>String buyerNo<br>String actorNo<br>@JsonInclude(JsonInclude.Include.NON_NULL) String partyNo |
| ai.neargo.shop.spi.trade.IndustryOrderPort | Command | Identity identity<br>PricingSourceRef source<br>Credentials credentials<br>String quoteNo<br>String confirmationDigest<br>Mode mode<br>String payScene |
| ai.neargo.shop.spi.trade.IndustryOrderPort | Allocation | String sourceLineNo<br>String componentRef<br>long orderItemId<br>int qty<br>long amountMinor<br>String currency<br>String hash<br>BigDecimal saleQuantity<br>String quantityUnit |
| ai.neargo.shop.spi.trade.IndustryOrderPort | Result | String orderNo<br>String subOrderNo<br>String quoteNo<br>String status<br>String currency<br>Long payAmountMinor<br>long sourceVersion<br>int inventoryLifecycleVersion<br>List<Allocation> allocations<br>String orderMode<br>String fulfillmentStatus<br>boolean acceptedUnpaid<br>Plan inventoryPlan<br>@JsonInclude(JsonInclude.Include.NON_NULL) Long effectiveReceivableMinor<br>@JsonInclude(JsonInclude.Include.NON_NULL) Long receivableAdjustmentMinor<br>@JsonInclude(JsonInclude.Include.NON_NULL) List<Allocation> effectiveAllocations<br>@JsonInclude(JsonInclude.Include.NON_NULL) String amendmentSourceHash |
| ai.neargo.shop.spi.trade.PricingFeeFacts | Source | String ruleNo<br>String revisionNo<br>String scopeNo<br>Kind kind<br>BigDecimal quantity<br>String unit<br>List<String> basisSourceRefs<br>String waiverEvidence |
| ai.neargo.shop.spi.trade.PricingFeeFacts | Definition | Kind kind<br>Method method<br>String unit<br>String currency<br>long unitAmountMinor<br>Integer rateBp<br>TaxClass taxClass |
| ai.neargo.shop.spi.trade.PricingFeeFacts | Rule | Scope scope<br>String ruleNo<br>String revisionNo<br>long version<br>String status<br>Definition definition |
| ai.neargo.shop.spi.trade.PricingFeeFacts | ScopeBasis | String scopeNo<br>String ruleNo<br>String currency<br>long goodsBasisMinor<br>long feeMinor<br>long version |
| ai.neargo.shop.spi.trade.PricingFeeFacts | Entry | PricingPortion portion<br>long amountMinor<br>TaxClass taxClass<br>String invoiceRef<br>String ruleNo<br>String revisionNo<br>String scopeNo |
| ai.neargo.shop.spi.trade.PricingFeeFacts | Correction | String applicationNo<br>long currentFeeMinor<br>long targetFeeMinor<br>String sourceHash |
| ai.neargo.shop.spi.trade.PricingFeeFacts | Resolved | Source source<br>Rule rule<br>ScopeBasis previous<br>long currentGoodsBasisMinor<br>long targetFeeMinor<br>long additionalFeeMinor<br>List<Entry> entries<br>List<Correction> corrections |
| ai.neargo.shop.spi.trade.PricingFeeFacts | Plan | List<Resolved> fees<br>String planHash |
| ai.neargo.shop.spi.trade.PricingFeeFacts | Application | String applicationNo<br>String orderNo<br>String subOrderNo<br>String quoteNo<br>String ruleNo<br>String revisionNo<br>String scopeNo<br>String currency<br>long feeMinor<br>long adjustedFeeMinor<br>String sourceHash |
| ai.neargo.shop.spi.trade.PricingFeeScopeInternalApi | Query | @Valid @NotNull Scope scope<br>@NotBlank String scopeNo |
| ai.neargo.shop.spi.trade.PricingFeeScopeInternalApi | OriginalQuery | @Valid @NotNull Scope scope<br>@NotBlank String applicationNo |
| ai.neargo.shop.spi.trade.PricingFeeScopePort | AdjustmentAuthority | String applicationNo<br>String orderNo<br>String subOrderNo<br>String currency<br>long currentFeeMinor<br>long targetFeeMinor<br>String sourceHash |
| ai.neargo.shop.platform.dto.IndustryVO | IndustryVO | String industry<br>String name<br>int sort<br>boolean enabled<br>boolean wechatMicroAllowed<br>boolean alipayMicroAllowed<br>boolean pointsForced<br>String remark<br>long merchantCount |
| ai.neargo.shop.printing.center.dispatch.PrintStateModels | PrintStateSnapshot | PrintJobStatus status<br>boolean cancelRequested<br>boolean partialOutput<br>int retryCount<br>int maxRetries |
| ai.neargo.shop.printing.center.dispatch.PrintStateModels | PrintObservation | ObservedPrintStatus status<br>PrintEvidenceLevel evidence<br>boolean queueTerminated<br>boolean partialOutput |
| ai.neargo.shop.printing.center.dispatch.PrintStateModels | PrintTransition | PrintJobStatus status<br>boolean releaseGate<br>boolean safeToRetry<br>String reason |
| ai.neargo.shop.printing.center.dispatch.PrintStateModels | PreparedPrintAttempt | PrintDispatch dispatch<br>String connectionType<br>String leaseToken<br>Instant leaseUntil |
| ai.neargo.shop.printing.center.dispatch.PrintStateModels | ReceiptAck | String receiptNo<br>String status |
| ai.neargo.shop.printing.center.dispatch.PrintStateModels | PrintIncidentCommand | PrintScope scope<br>String type<br>String targetType<br>String targetNo<br>String severity<br>FrozenJson details |
| ai.neargo.shop.printing.center.dispatch.PrintStateModels | PrintAction | String operationNo<br>String targetType<br>String targetNo<br>String action<br>String actorType<br>String actorNo<br>Long beforeRevision<br>Long afterRevision<br>String beforeStatus<br>String afterStatus<br>String reason<br>FrozenJson safeDetails |
| ai.neargo.shop.printing.center.dispatch.PrintStateModels | PrintJobSnapshot | PrintJobStatus status<br>boolean required<br>boolean replacementResolved |
| ai.neargo.shop.printing.center.dispatch.PrintStateModels | BatchCounts | int expected<br>int confirmed<br>int failed<br>int unknown<br>int cancelled<br>int resolved<br>PrintBatchStatus status |
| ai.neargo.shop.printing.center.simulation.SimulationModels | SimulationDispatch | String documentKey<br>SimulationFixtureCode code<br>int queryIndex |
| ai.neargo.shop.printing.center.simulation.SimulationModels | SimulationStep | long virtualMillis<br>String eventNo<br>PrintObservation observation |
| ai.neargo.shop.printing.center.simulation.SimulationModels | SimulationFixture | SimulationFixtureCode code<br>String version<br>List<SimulationStep> steps<br>PrintJobStatus expectedStatus |
| ai.neargo.shop.printing.center.simulation.SimulationModels | SimulationInput | List<PrintLayout> layouts<br>Set<String> requiredDataScopes |
| ai.neargo.shop.printing.center.template.PrintSceneRegistry | SceneDefinition | String code<br>JsonNode definition<br>JsonNode example<br>Map<String, JsonNode> templates |
| ai.neargo.shop.printing.service.PrintService | DeviceResolution | String deviceNo<br>long expectedVersion<br>String activeAttemptNo<br>String reason<br>String evidenceRef<br>boolean oldExecutorStopped<br>String requestKey |
| ai.neargo.shop.printing.service.PrintService | PendingExecution | String jobNo<br>String attemptNo<br>String status |
| ai.neargo.shop.printing.service.PrintService | DeviceRecovery | String deviceNo<br>long version<br>String activeAttemptNo<br>List<PendingExecution> pending |
| ai.neargo.shop.printing.service.impl.PrintServiceImpl | RevokeAudit | String key<br>String actor<br>String reason<br>DeviceVO response |
| ai.neargo.shop.printing.service.impl.PrintServiceImpl | ResolutionAudit | String key<br>String actor<br>String attempt<br>String reason<br>String evidence<br>DeviceVO response |
| ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows | Journal | Long id<br>String tenantNo<br>String entityNo<br>String storeNo<br>String orderNo<br>String subOrderNo<br>String amendmentNo<br>String operationKey<br>String requestHash<br>String kind<br>String sourceReturnNo<br>String sourceHash<br>String commandSnapshot<br>String evidenceSnapshot<br>String inventorySnapshot<br>String status<br>String actorNo<br>long expectedSubVersion<br>String currency<br>long receivableDeltaMinor<br>String replacementCartNo<br>Long netDeltaMinor<br>Long taxDeltaMinor |
| ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows | Line | Long orderItemId<br>String sourceComponentRef<br>BigDecimal quantity<br>BigDecimal physicalReductionQty<br>BigDecimal cancelQty<br>String unit<br>String disposition<br>long receivableReductionMinor<br>long netReductionMinor<br>long taxReductionMinor |
| ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows | FeeApplication | String quoteNo<br>String ruleNo<br>String revisionNo<br>String scopeNo<br>long feeMinor |
| ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows | FeeLine | Long orderItemId<br>String sourceComponentRef<br>long amountMinor |
| ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows | Applied | String amendmentNo<br>String sourceHash<br>String kind<br>String inventorySnapshot<br>long receivableDeltaMinor |
| ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows | ReturnDraft | String tenantNo<br>String entityNo<br>String storeNo<br>String orderNo<br>String subOrderNo<br>String amendmentNo<br>String operationKey<br>String requestHash<br>String kind<br>String sourceReturnNo<br>String sourceHash<br>String commandSnapshot<br>String evidenceSnapshot<br>String status<br>String actorNo<br>long expectedSubVersion<br>String currency<br>long receivableDeltaMinor<br>String replacementCartNo |
| ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows | Debt | String tenantNo<br>String entityNo<br>String storeNo<br>String orderNo<br>String subOrderNo<br>String amendmentNo<br>String currency<br>long amountMinor<br>String sourceHash<br>String reason<br>String actorNo<br>Long netDeltaMinor<br>Long taxDeltaMinor |
| ai.neargo.shop.trade.service.IndustryOrderAmendmentService | EffectiveProjection | List<IndustryOrderPort.Allocation> allocations<br>String sourceHash<br>long receivableAdjustmentMinor |
| ai.neargo.shop.trade.service.IndustryTransactionRecoveryService | Batch | int claimed<br>int complete<br>int pending<br>int failed |
| ai.neargo.shop.trade.service.PricingFeeRuleService | SaveReq | long expectedVersion<br>Definition definition<br>String status<br>String reason |
| ai.neargo.shop.trade.service.impl.IndustryAfterSaleServiceImpl | Bound | OrdIndustryOrderSource source<br>OrdSubOrder order<br>String currency |
| ai.neargo.shop.trade.service.impl.IndustryAfterSaleServiceImpl | LineKey | String sourceLineNo<br>String componentRef |
| ai.neargo.shop.trade.service.impl.IndustryCheckoutServiceImpl | Bound | Identity identity<br>OrdIndustryOrderSource binding<br>OrdOrder order<br>OrdSubOrder sub |
| ai.neargo.shop.trade.service.impl.IndustryFulfillmentServiceImpl | Facts | OrdIndustryOrderSource source<br>OrdOrder order<br>OrdSubOrder sub<br>List<OrdItem> items<br>OrdCreateIntent intent<br>boolean openAfterSale |
| ai.neargo.shop.trade.service.impl.IndustryOrderAmendmentServiceImpl | Facts | OrdIndustryOrderSource source<br>OrdOrder order<br>OrdSubOrder sub<br>List<OrdItem> items |
| ai.neargo.shop.trade.service.impl.IndustryOrderAmendmentServiceImpl | Calculated | Reduction line<br>BigDecimal physical<br>long money<br>long net<br>long tax |
| ai.neargo.shop.trade.service.impl.IndustryOrderAmendmentServiceImpl | FeeAuthorityEvidence | PricingFeeScopePort.AdjustmentAuthority authority<br>String frozenTax |
| ai.neargo.shop.trade.service.impl.IndustryOrderAmendmentServiceImpl | FeeTaxBasis | MerchantTaxSnapshot merchantTax<br>String originalInvoice<br>TaxLineResult before<br>TaxLineResult after |
| ai.neargo.shop.trade.service.impl.IndustryOrderAmendmentServiceImpl | FeeMoney | long grossDelta<br>long netDelta<br>long taxDelta<br>String taxSnapshot<br>List<IndustryOrderAmendmentRows.FeeLine> lines |
| ai.neargo.shop.trade.service.impl.IndustryOrderAmendmentServiceImpl | MoneyBasis | long gross<br>long net |
| ai.neargo.shop.trade.service.impl.IndustryOrderServiceImpl | Guard | Map<String, SkuSnapshot> skus<br>String entityName<br>String buyerNickname<br>boolean buyerConfirm<br>String trafficSource<br>String communityNo<br>int minutes<br>Party party<br>Delivery delivery<br>MerchantQueryPort.DeliveryOrigin deliveryOrigin |
| ai.neargo.shop.trade.service.impl.IndustryOrderServiceImpl | DeliveryEvidence | Delivery source<br>MerchantQueryPort.DeliveryOrigin origin |
| ai.neargo.shop.trade.service.impl.IndustryOrderServiceImpl | BoundAllocations | List<Allocation> allocations<br>List<Object> evidence |
| ai.neargo.shop.inventory.mapper.InventoryDemandPlanMapper | Head | Long id<br>String planNo<br>String planHash<br>int lifecycleVersion<br>String status<br>LocalDateTime expiresAt |
| ai.neargo.shop.inventory.mapper.InventoryDemandPlanMapper | Operation | String planNo<br>String requestHash<br>int reversed<br>String kind<br>String deltaSnapshot<br>String receiptSnapshot |
| ai.neargo.shop.inventory.mapper.InventoryDemandPlanMapper | Leaf | Long id<br>String leafRef<br>String componentRef<br>String fulfillmentRef<br>String branchKind<br>String itemId<br>String locationId<br>BigDecimal qty<br>BigDecimal issuedQty<br>BigDecimal releasedQty<br>String unit<br>String definitionHash<br>String documentNo<br>String ledgerRef<br>Long costMinor<br>String currency |
| ai.neargo.shop.inventory.mapper.InventoryDemandPlanMapper | Valuation | Long defaultCostMinor<br>String costCurrency<br>int trackBatch |
| ai.neargo.shop.inventory.mapper.InventoryDemandPlanMapper | Lot | BigDecimal qty<br>Long unitCostMinor<br>String currency<br>String batchNo<br>String lotRef |
| ai.neargo.shop.inventory.mapper.InventoryProductionMapper | Operation | Long id<br>String requestHash<br>String receiptSnapshot<br>String reverseEventNo<br>String reverseHash<br>String operationRef |
| ai.neargo.shop.inventory.mapper.InventoryProductionMapper | Delta | String deltaSnapshot |
| ai.neargo.shop.inventory.mapper.InventoryProductionMapper | Ledger | BigDecimal qtyDelta<br>Long unitCostMinor<br>String costCurrency<br>String docNo<br>String batchNo |
| ai.neargo.shop.inventory.port.InventoryDemandPlanPortImpl | BatchPosting | BigDecimal qty<br>Long cost<br>String currency<br>String batchNo<br>String lotRef |
| ai.neargo.shop.inventory.port.InventoryDemandPlanPortImpl | Delta | String leafRef<br>BigDecimal qty<br>String item<br>String location<br>Long unitCost<br>String currency<br>String batchNo<br>String lotRef<br>String ledgerRef |

## 保留的目标规划及历史记录

## 零、按业务模块阅读

先查主责模块，再查表字段或接口编号。13个业务模块与A–L持久化域不是一一对应；模块可协作调用相同服务，不能据此复制实体。109项追踪结果：目标已定义32项，依赖共享/核心合同60项，尚缺专项设计17项。以上均为设计状态，非实现验收。

| 模块 | 名称 | 需求数 | 关联表数 | 关联API数 |
|---|---|---|---|---|
| MOD-01 | 桌台与就餐管理 | 8 | 24 | 40 |
| MOD-02 | 菜品与餐牌管理 | 12 | 27 | 41 |
| MOD-03 | 点餐与订单管理 | 9 | 18 | 30 |
| MOD-04 | 厨房与履约管理 | 10 | 10 | 17 |
| MOD-05 | 收银与结账管理 | 11 | 7 | 13 |
| MOD-06 | 退餐与售后管理 | 8 | 8 | 12 |
| MOD-07 | 打印、通知与设备管理 | 8 | 13 | 3 |
| MOD-08 | 班次与现金管理 | 4 | 5 | 5 |
| MOD-09 | 营业日、日结与对账管理 | 6 | 6 | 6 |
| MOD-10 | 经营报表管理 | 14 | 3 | 4 |
| MOD-11 | 专项业态管理 | 6 | 10 | 8 |
| MOD-12 | 第三方接入与离线营业 | 7 | 8 | 7 |
| MOD-13 | 营业配置与权限治理 | 6 | 7 | 7 |

逐需求表/API引用与未决缺口见API册第八节；首三模块扩展及评审结论见[评审对齐记录](../TDD-餐饮系统-数据库与API评审对齐.md)。

## 一、协议与能力边界

165个目标端点，统一JSON语义；路径为建议合同，未全部注册在当前Controller。本地适配器和远程适配器遵守相同DTO、权限和幂等。

- /biz为商家作业，/mp为顾客，/ops为平台，/internal仅服务身份。老板App/收银/厨房复用biz服务，按角色门店工位鉴权，不把平台管理员当商家收银员。
- Authorization为获准身份；X-Request-Id用于追踪。所有有副作用操作必需Idempotency-Key；作用域含entity/store/operation/key，规范payload哈希同键异参返回409。operator/entity从身份获取，不接受客户端冒充。
- 版本字段为CAS expected version；配置发布、结账冻结、日结签核还核验规则版/指纹/水位。path同名字段不得在body覆盖。GET只读，普通分页沿用PageData(records,total,page,size)，page默认1、size默认20最大100；报告专有metadata不改变统一外层。
- 输出沿用ApiResult<T>：{code:0,msg:"success",data:T}，Controller直接返回VO，由ApiResponseWrapper包装；异常沿用ErrorCode/BizException与现有全局处理器。追踪号使用既有链路，不新增必填requestId响应字段。业务处理中状态放data.status，HTTP状态/异步语义按宿主异常与包装链校验，不建立另一套HTTP-only错误规范。
- 普通分页沿用PageData(records,total,page,size)；DTO示意不得迫使现有前端改分页壳。int64金额与业务号遵循现有共享序列化，不能将全项目金额强制改字符串；新增decimal数量使用明确精度合同。UTC对外时刻与数据库LocalDateTime转换必须通过现有配置验证，date明确营业日或自然日基准。
- B端复用Realm.MERCHANT和BizContext；X-Store-No用于解析主体门店。路径/body显式门店必须与已解析的获准门店匹配，不能因过滤器回落默认店而把显式目标写到另一店。跨店报表逐店验证主体及权限集合。
- 参数表“是”为必须出现；条件字段的必填性由说明与守卫补充。写入枚举限定域值，服务端拒绝未知字段/未授权能力。通用配置data按路径对应数据库字段白名单解析，不能作为任意属性注入口。

## 二、分域接口目录

| 域 | 编号 | 方法与路径 | 功能 |
|---|---|---|---|
| A | FOOD-API-001 | GET `/biz/x/food/stores/{storeNo}/policy` | 查询营业策略 |
| A | FOOD-API-002 | POST `/biz/x/food/stores/{storeNo}/policies` | 发布营业策略 |
| A | FOOD-API-003 | PUT `/biz/x/food/stores/{storeNo}/calendar/{localDate}` | 餐饮接单日历例外 |
| B | FOOD-API-004 | POST `/biz/x/food/stores/{storeNo}/menus` | 创建menus |
| B | FOOD-API-005 | GET `/biz/x/food/stores/{storeNo}/menus/{configNo}` | 读取menus |
| B | FOOD-API-006 | POST `/biz/x/food/stores/{storeNo}/menus/{configNo}:publish` | 发布餐牌 |
| B | FOOD-API-007 | POST `/biz/x/food/stores/{storeNo}/option-groups` | 创建option-groups |
| B | FOOD-API-008 | GET `/biz/x/food/stores/{storeNo}/option-groups/{configNo}` | 读取option-groups |
| B | FOOD-API-009 | POST `/biz/x/food/stores/{storeNo}/option-groups/{configNo}:publish` | 发布option-groups |
| B | FOOD-API-010 | POST `/biz/x/food/stores/{storeNo}/bundles` | 创建bundles |
| B | FOOD-API-011 | GET `/biz/x/food/stores/{storeNo}/bundles/{configNo}` | 读取bundles |
| B | FOOD-API-012 | POST `/biz/x/food/stores/{storeNo}/bundles/{configNo}:publish` | 发布bundles |
| B | FOOD-API-013 | POST `/biz/x/food/stores/{storeNo}/stations` | 创建stations |
| B | FOOD-API-014 | GET `/biz/x/food/stores/{storeNo}/stations/{configNo}` | 读取stations |
| B | FOOD-API-015 | POST `/biz/x/food/stores/{storeNo}/stations/{configNo}:publish` | 发布工位 |
| B | FOOD-API-016 | PUT `/biz/x/food/stores/{storeNo}/dishes/{goodsNo}` | 菜品餐饮属性 |
| B | FOOD-API-017 | PUT `/biz/x/food/menus/{menuNo}/definition` | 菜单组成与时段配置 |
| B | FOOD-API-018 | PUT `/biz/x/food/bundles/{skuNo}/definition` | 套餐选择组配置 |
| B | FOOD-API-019 | PUT `/biz/x/food/option-groups/{groupNo}/items` | 配置选择项 |
| B | FOOD-API-020 | PUT `/biz/x/food/modifiers/{modifierNo}` | 配料组合配置 |
| B | FOOD-API-021 | PUT `/biz/x/food/stores/{storeNo}/sku-routes/{skuNo}` | 工位路由配置 |
| B | FOOD-API-022 | POST `/biz/x/food/stores/{storeNo}/sales-restrictions` | 沽清与恢复 |
| B | FOOD-API-023 | GET `/mp/x/food/catalog` | 顾客菜单视图 |
| C | FOOD-API-024 | POST `/biz/x/food/stores/{storeNo}/tables` | 建桌 |
| C | FOOD-API-025 | POST `/biz/x/food/tables/{tableNo}:rotate-token` | 桌码轮换 |
| C | FOOD-API-026 | POST `/mp/x/food/sessions` | 桌码换短会话 |
| C | FOOD-API-027 | POST `/biz/x/food/stores/{storeNo}/reservations` | 创建预约 |
| C | FOOD-API-028 | POST `/biz/x/food/reservations/{reservationNo}:cancel` | 预约cancel |
| C | FOOD-API-029 | POST `/biz/x/food/reservations/{reservationNo}:no-show` | 预约no-show |
| C | FOOD-API-030 | POST `/biz/x/food/reservations/{reservationNo}:arrive` | 预约arrive |
| C | FOOD-API-031 | POST `/biz/x/food/stores/{storeNo}/checks` | 开台 |
| C | FOOD-API-032 | GET `/biz/x/food/stores/{storeNo}/checks` | 台账桌台查询 |
| C | FOOD-API-033 | POST `/biz/x/food/checks/{checkNo}:transfer` | 台账transfer |
| C | FOOD-API-034 | POST `/biz/x/food/checks/{checkNo}:merge` | 合并就餐服务台账 |
| C | FOOD-API-035 | POST `/biz/x/food/checks/{checkNo}:split` | 拆分就餐服务台账 |
| C | FOOD-API-036 | POST `/biz/x/food/checks/{checkNo}:clear` | 台账clear |
| D | FOOD-API-037 | GET `/biz/x/food/carts/{cartNo}` | 购物车读取 |
| D | FOOD-API-038 | POST `/biz/x/food/stores/{storeNo}/carts` | 创建购物车 |
| D | FOOD-API-039 | PUT `/biz/x/food/carts/{cartNo}/lines/{sourceLineRef}` | 替换草稿行 |
| D | FOOD-API-040 | POST `/biz/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive` | 归档购物车草稿行 |
| D | FOOD-API-041 | POST `/biz/x/food/stores/{storeNo}/quotes` | 点单报价 |
| D | FOOD-API-042 | POST `/biz/x/food/stores/{storeNo}/batches` | 提交点餐批次 |
| D | FOOD-API-043 | GET `/biz/x/food/batches/{batchNo}` | 批次结果查询 |
| E | FOOD-API-044 | GET `/biz/x/food/stores/{storeNo}/kitchen/tasks` | 工位队列 |
| E | FOOD-API-045 | POST `/biz/x/food/tasks/{taskNo}:start` | 制作任务start |
| E | FOOD-API-046 | POST `/biz/x/food/tasks/{taskNo}:ready` | 制作任务ready |
| E | FOOD-API-047 | POST `/biz/x/food/tasks/{taskNo}:serve` | 制作任务serve |
| E | FOOD-API-048 | POST `/biz/x/food/tasks/{taskNo}:hold` | 制作任务hold |
| E | FOOD-API-049 | POST `/biz/x/food/tasks/{taskNo}:fire` | 制作任务fire |
| E | FOOD-API-050 | POST `/biz/x/food/tasks/{taskNo}:urge` | 制作任务urge |
| E | FOOD-API-051 | POST `/biz/x/food/tasks/{taskNo}:void` | 制作任务void |
| E | FOOD-API-052 | POST `/biz/x/food/tasks/{taskNo}:remake` | 制作任务remake |
| E | FOOD-API-053 | POST `/biz/x/food/checks/{checkNo}/courses` | 创建course |
| E | FOOD-API-054 | POST `/biz/x/food/courses/{courseNo}:fire` | 道次叫起 |
| E | FOOD-API-055 | POST `/biz/x/food/batches/{batchNo}:handover` | 交付及配送交接 |
| F | FOOD-API-056 | POST `/biz/x/food/stores/{storeNo}/settlements` | 冻结结账范围 |
| F | FOOD-API-057 | GET `/biz/x/food/settlements/{settlementNo}` | 读取预结账 |
| F | FOOD-API-058 | POST `/biz/x/food/settlements/{settlementNo}:pay` | 结账pay |
| F | FOOD-API-059 | POST `/biz/x/food/settlements/{settlementNo}:cancel` | 结账cancel |
| F | FOOD-API-060 | POST `/biz/x/food/settlements/{settlementNo}:finalize` | 结账finalize |
| F | FOOD-API-061 | POST `/biz/x/food/settlements/{settlementNo}/adjustments` | 收费优惠调整申请 |
| G | FOOD-API-062 | POST `/biz/x/food/stores/{storeNo}/returns` | 退餐申请 |
| G | FOOD-API-063 | POST `/biz/x/food/returns/{returnNo}:execute` | 退餐审批执行 |
| G | FOOD-API-064 | GET `/biz/x/food/returns/{returnNo}` | 退餐查询 |
| H | FOOD-API-065 | POST `/biz/print/jobs` | 提交打印 |
| H | FOOD-API-066 | GET `/biz/print/jobs/{jobNo}` | 打印查询 |
| H | FOOD-API-067 | POST `/biz/print/jobs/{jobNo}:reprint` | 人工补打 |
| I | FOOD-API-068 | POST `/biz/x/food/stores/{storeNo}/cash-shifts` | 开班 |
| I | FOOD-API-069 | POST `/biz/x/food/cash-shifts/{shiftNo}/entries` | 非销售现金操作 |
| I | FOOD-API-070 | POST `/biz/x/food/cash-shifts/{shiftNo}:count` | 实盘与交班 |
| I | FOOD-API-071 | POST `/biz/x/food/cash-shifts/{shiftNo}:handover` | 接班确认 |
| I | FOOD-API-072 | POST `/biz/x/food/stores/{storeNo}/closes` | 营业日预结 |
| I | FOOD-API-073 | GET `/biz/x/food/closes/{closeNo}` | 日结查询 |
| I | FOOD-API-074 | POST `/biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:carry` | 异常结转申请 |
| I | FOOD-API-075 | POST `/biz/x/food/closes/{closeNo}:sign` | 签核日结 |
| I | FOOD-API-076 | POST `/biz/x/food/closes/{closeNo}/adjustments` | 关账后更正 |
| J | FOOD-API-077 | GET `/biz/x/food/reports/{reportCode}` | 经营指标报表 |
| J | FOOD-API-078 | POST `/biz/x/food/report-exports` | 异步报表导出 |
| J | FOOD-API-079 | GET `/biz/x/food/report-exports/{exportNo}` | 导出结果 |
| J | FOOD-API-080 | POST `/ops/x/food/report-rebuilds` | 重算投影 |
| K | FOOD-API-081 | PUT `/ops/x/food/stores/{storeNo}/capabilities` | 平台能力配置 |
| K | FOOD-API-082 | PUT `/biz/x/food/integrations/{provider}/mappings` | 外部映射 |
| K | FOOD-API-083 | GET `/ops/x/food/integration-exceptions` | 接入异常查询 |
| K | FOOD-API-084 | POST `/internal/food/sync-batches` | 边缘增量认领 |
| L | FOOD-API-085 | POST `/biz/x/food/checks/{checkNo}/entitlements` | 自助权益入场 |
| L | FOOD-API-086 | POST `/biz/x/food/entitlements/{entitlementNo}:extend` | 续时及最后点单校验 |
| L | FOOD-API-087 | POST `/biz/x/food/stores/{storeNo}/banquet-plans` | 宴会计划 |
| L | FOOD-API-088 | POST `/biz/x/food/banquet-plans/{planNo}:confirm-attendance` | 宴会人数及加席 |
| L | FOOD-API-089 | POST `/biz/x/food/stores/{storeNo}/custodies` | 寄存建账 |
| L | FOOD-API-090 | POST `/biz/x/food/custodies/{custodyNo}/entries` | 寄存取出及报损 |
| L | FOOD-API-091 | POST `/biz/x/food/settlements/{settlementNo}/room-charges` | 房账转挂 |
| C | FOOD-API-092 | GET `/biz/x/food/stores/{storeNo}/table-areas` | 桌区列表 |
| C | FOOD-API-093 | POST `/biz/x/food/stores/{storeNo}/table-areas` | 创建桌区 |
| C | FOOD-API-094 | PUT `/biz/x/food/table-areas/{areaNo}` | 编辑桌区 |
| C | FOOD-API-095 | POST `/biz/x/food/table-areas/{areaNo}:set-status` | 桌区启停 |
| C | FOOD-API-096 | GET `/biz/x/food/stores/{storeNo}/tables` | 桌台列表 |
| C | FOOD-API-097 | GET `/biz/x/food/tables/{tableNo}` | 桌台详情 |
| C | FOOD-API-098 | PUT `/biz/x/food/tables/{tableNo}` | 编辑桌台 |
| C | FOOD-API-099 | POST `/biz/x/food/tables/{tableNo}:set-status` | 桌台启停 |
| C | FOOD-API-100 | POST `/biz/x/food/tables/{tableNo}:archive` | 归档桌台 |
| C | FOOD-API-101 | POST `/biz/x/food/tables/{tableNo}:revoke-token` | 吊销桌码 |
| C | FOOD-API-102 | GET `/biz/x/food/checks/{checkNo}` | 就餐台账详情 |
| C | FOOD-API-103 | PUT `/biz/x/food/checks/{checkNo}/seats` | 修改人数与座位 |
| C | FOOD-API-104 | POST `/biz/x/food/checks/{checkNo}:combine-tables` | 并入桌台 |
| C | FOOD-API-105 | POST `/biz/x/food/tables/{tableNo}:share` | 同桌新开独立台账 |
| C | FOOD-API-106 | GET `/biz/x/food/table-operations/{operationNo}` | 桌台操作恢复查询 |
| C | FOOD-API-107 | GET `/biz/x/food/stores/{storeNo}/reservations` | 预约列表 |
| C | FOOD-API-108 | GET `/biz/x/food/checks/{checkNo}/actions` | 就餐操作历史 |
| B | FOOD-API-109 | GET `/biz/x/food/stores/{storeNo}/menus` | 餐牌列表 |
| B | FOOD-API-110 | PUT `/biz/x/food/menus/{menuNo}` | 编辑餐牌头 |
| B | FOOD-API-111 | POST `/biz/x/food/menus/{menuNo}:disable` | 餐牌停用 |
| B | FOOD-API-112 | GET `/biz/x/food/stores/{storeNo}/dishes` | 菜品列表 |
| B | FOOD-API-113 | GET `/biz/x/food/stores/{storeNo}/dishes/{goodsNo}` | 菜品详情 |
| B | FOOD-API-114 | GET `/biz/x/food/stores/{storeNo}/catalog` | 经营端可售餐牌 |
| B | FOOD-API-115 | GET `/biz/x/food/config-revisions` | 配置发布历史 |
| B | FOOD-API-116 | POST `/biz/x/food/config-revisions/{revisionNo}:republish` | 回滚配置为新修订 |
| B | FOOD-API-117 | POST `/biz/x/food/modifiers/{modifierNo}:publish` | 配料组合发布 |
| B | FOOD-API-118 | GET `/biz/x/food/stores/{storeNo}/option-groups` | 选配组列表 |
| B | FOOD-API-119 | GET `/biz/x/food/stores/{storeNo}/bundles` | 套餐列表 |
| B | FOOD-API-120 | GET `/biz/x/food/stores/{storeNo}/modifiers` | 配料列表 |
| B | FOOD-API-121 | GET `/biz/x/food/stores/{storeNo}/stations` | 工位列表 |
| B | FOOD-API-122 | GET `/biz/x/food/stores/{storeNo}/sales-restrictions` | 可售限制查询 |
| D | FOOD-API-123 | GET `/biz/x/food/stores/{storeNo}/batches` | 按台账或条件查询批次 |
| D | FOOD-API-124 | POST `/biz/x/food/batches/{batchNo}:cancel` | 取消正式批次 |
| D | FOOD-API-125 | GET `/biz/x/food/batches/{batchNo}/operations` | 批次操作恢复查询 |
| D | FOOD-API-126 | POST `/biz/x/food/carts/{cartNo}:archive` | 关闭草稿购物车 |
| D | FOOD-API-127 | GET `/mp/x/food/carts/{cartNo}` | 顾客购物车读取 |
| D | FOOD-API-128 | POST `/mp/x/food/stores/{storeNo}/carts` | 顾客创建购物车 |
| D | FOOD-API-129 | PUT `/mp/x/food/carts/{cartNo}/lines/{sourceLineRef}` | 顾客替换草稿行 |
| D | FOOD-API-130 | POST `/mp/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive` | 顾客归档购物车草稿行 |
| D | FOOD-API-131 | POST `/mp/x/food/stores/{storeNo}/quotes` | 顾客点单报价 |
| D | FOOD-API-132 | POST `/mp/x/food/stores/{storeNo}/batches` | 顾客提交点餐批次 |
| D | FOOD-API-133 | GET `/mp/x/food/batches/{batchNo}` | 顾客批次结果查询 |
| F | FOOD-API-134 | GET `/mp/x/food/settlements/{settlementNo}` | 顾客读取预结账 |
| F | FOOD-API-135 | POST `/mp/x/food/settlements/{settlementNo}:pay` | 顾客结账pay |
| G | FOOD-API-136 | POST `/mp/x/food/stores/{storeNo}/returns` | 顾客退餐申请 |
| G | FOOD-API-137 | GET `/mp/x/food/returns/{returnNo}` | 顾客退餐查询 |
| C | FOOD-API-138 | GET `/mp/x/food/checks/{checkNo}` | 顾客就餐进度 |
| C | FOOD-API-139 | POST `/mp/x/food/sessions/{sessionNo}:end` | 主动结束顾客会话 |
| F | FOOD-API-140 | POST `/mp/x/food/checks/{checkNo}/settlements` | 顾客申请获准范围结账 |
| B | FOOD-API-141 | POST `/biz/x/food/stores/{storeNo}/modifiers` | 创建配料或配料组合 |
| B | FOOD-API-142 | POST `/biz/x/food/stores/{storeNo}/menu-channel-bindings` | 创建餐牌渠道绑定 |
| B | FOOD-API-143 | GET `/biz/x/food/stores/{storeNo}/menu-channel-bindings` | 渠道绑定列表 |
| B | FOOD-API-144 | GET `/biz/x/food/menu-channel-bindings/{bindingNo}` | 渠道绑定详情 |
| B | FOOD-API-145 | PUT `/biz/x/food/menu-channel-bindings/{bindingNo}` | 修改渠道绑定 |
| K | FOOD-API-146 | POST `/biz/x/food/menu-channel-bindings/{bindingNo}/syncs` | 发起餐牌渠道同步 |
| K | FOOD-API-147 | GET `/biz/x/food/catalog-syncs/{syncNo}` | 查询餐牌同步结果 |
| B | FOOD-API-148 | POST `/biz/x/food/stores/{storeNo}/recipes` | 创建配方工作头 |
| B | FOOD-API-149 | GET `/biz/x/food/stores/{storeNo}/recipes` | 配方列表 |
| B | FOOD-API-150 | GET `/biz/x/food/recipes/{recipeNo}` | 配方详情 |
| B | FOOD-API-151 | PUT `/biz/x/food/recipes/{recipeNo}/definition` | 维护配方物料与选配调整 |
| B | FOOD-API-152 | POST `/biz/x/food/recipes/{recipeNo}:publish` | 发布配方 |
| E | FOOD-API-153 | GET `/biz/x/food/stores/{storeNo}/production-consumptions` | 制作耗用列表 |
| E | FOOD-API-154 | GET `/biz/x/food/production-consumptions/{consumptionNo}` | 制作耗用详情 |
| B | FOOD-API-155 | GET `/biz/x/food/stores/{storeNo}/sku-routes/{skuNo}` | 读取SKU工位路由 |
| D | FOOD-API-156 | POST `/biz/x/food/checks/{checkNo}/cart-sessions` | 授权扫码会话加入共享购物车 |
| C | FOOD-API-157 | POST `/biz/x/food/queue-tickets` | 排队取号 |
| C | FOOD-API-158 | GET `/biz/x/food/queue-tickets` | 排队列表 |
| C | FOOD-API-159 | GET `/biz/x/food/queue-tickets/{ticketNo}` | 原排队票 |
| C | FOOD-API-160 | POST `/biz/x/food/queue-tickets:call-next` | 叫下一位 |
| C | FOOD-API-161 | POST `/biz/x/food/queue-tickets/{ticketNo}:call` | 指定叫号 |
| C | FOOD-API-162 | POST `/biz/x/food/queue-tickets/{ticketNo}:cancel` | 取消排队 |
| C | FOOD-API-163 | POST `/biz/x/food/queue-tickets/{ticketNo}:no-show` | 排队过号 |
| C | FOOD-API-164 | POST `/biz/x/food/queue-tickets/{ticketNo}:seat` | 排队转原开台 |
| C | FOOD-API-165 | POST `/biz/x/food/queue-tickets/{ticketNo}:recover-seating` | 原排队就座恢复 |

## 三、公共DTO输入输出字段

### Money

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| minor | int64 | 是 | 最小货币单位 |
| currency | string | 是 | ISO币种 |

### Selection

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| groupNo | string | 是 | 选择组 |
| sourceKind | string | 是 | SPEC_VALUE或MODIFIER |
| sourceNo | string | 是 | 编号 |
| qty | decimal | 是 | 数量 |
| configVersion | int64 | 是 | 规则版本 |

### OrderLineInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceLineRef | string | 是 | 端侧稳定源行最长64 |
| menuNo | string | 是 | 当前餐牌 |
| menuItemNo | string | 是 | 餐牌菜项 |
| menuRevisionNo | string | 是 | 仍有效的冻结餐牌修订 |
| goodsNo | string | 是 | 原商品 |
| skuNo | string | 是 | 原SKU |
| qty | decimal | 是 | COUNT正整数不超过999999 |
| seatNo | string | 否 | 同台账座位 |
| courseNo | string | 否 | 当前阶段拒绝道次 |
| selections | Selection[] | 是 | 每份菜选择必需数组 |
| bundleChoices | CartBundleChoiceInput[] | 是 | 套餐选择必需数组 |

### QuoteLine

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceLineRef | string | 是 | 源行 |
| skuNo | string | 是 | SKU |
| qty | decimal | 是 | 数量 |
| amount | Money | 是 | 核心金额 |
| coreBreakdownRef | string | 是 | 核心分摊引用 |

### Quote

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| quoteNo | string | 是 | 报价 |
| expiresAt | datetime | 是 | 失效 |
| scopeHash | string | 是 | 范围指纹 |
| lines | QuoteLine[] | 是 | 逐行金额 |
| total | Money | 是 | 应收 |
| capabilities | string[] | 是 | 可用能力 |

### Check

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 是 | 台账 |
| storeNo | string | 是 | 门店 |
| businessDate | date | 是 | 开台营业日 |
| status | string | 是 | 营业状态 |
| payMode | string | 是 | 先后付 |
| partySize | int32 | 是 | 人数 |
| tableNos | string[] | 是 | 占桌 |
| version | int64 | 是 | 版本 |

### Task

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string | 是 | 任务 |
| foodLineNo | string | 是 | 菜品源行 |
| stationNo | string | 是 | 工位 |
| status | string | 是 | 状态 |
| targetQty | decimal | 是 | 目标量 |
| readyQty | decimal | 是 | 出品量 |
| servedQty | decimal | 是 | 上菜量 |
| voidQty | decimal | 是 | 撤销量 |
| version | int64 | 是 | 版本 |

### Batch

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string | 是 | 批次 |
| checkNo | string | 否 | 原台账 |
| storeNo | string | 是 | 门店 |
| businessDate | date | 是 | 本批归期 |
| payMode | string | 是 | 先后付 |
| orderSource | string | 是 | 真实入口 |
| fulfillment | string | 是 | 履约 |
| status | string | 是 | 含UNKNOWN及CANCEL_PENDING |
| paymentRootNos | string[] | 是 | 核心支付根 |
| storeOrderNos | string[] | 是 | 门店单 |
| foodLineNos | string[] | 是 | 餐饮行 |
| version | int64 | 是 | 版本 |

### MetricCell

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| metricCode | string | 是 | 指标 |
| value | decimal | 是 | 值 |
| unit | string | 是 | 单位 |
| currency | string | 否 | 金额币种 |
| definitionVersion | int64 | 是 | 口径版本 |

### Report

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| rows | MetricCell[] | 是 | 指标行 |
| asOf | datetime | 是 | 数据截至 |
| complete | boolean | 是 | 完整性 |
| timeBasis | string | 是 | 时间基准 |
| watermark | string | 是 | 源水位 |
| total | int64 | 是 | 总行数 |
| page | int32 | 是 | 页码 |
| size | int32 | 是 | 每页条数 |

### ScopeItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeOrderNo | string | 是 | 核心门店单 |
| coreAllocationRef | string | 否 | 部分分摊引用 |

### Settlement

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string | 是 | 结账 |
| status | string | 是 | 状态 |
| scopeHash | string | 是 | 范围 |
| due | Money | 是 | 冻结应收 |
| coreCashierRef | string | 否 | 权威收银引用 |
| version | int64 | 是 | 版本 |

### Close

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| closeNo | string | 是 | 日结 |
| businessDate | date | 是 | 营业日 |
| currency | string | 是 | 币种 |
| revision | int32 | 是 | 版本 |
| status | string | 是 | 状态 |
| balances | MetricCell[] | 是 | 快照 |
| exceptionNos | string[] | 是 | 异常 |
| watermark | string | 是 | 源水位 |
| snapshotHash | string | 是 | 结果指纹 |
| version | int64 | 是 | 对象版本 |

### Config

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 对象编号 |
| kind | string | 是 | 受控配置类型 |
| revision | int64 | 是 | 版本 |
| status | string | 是 | 草稿或已发布 |
| data | object | 是 | 按对应数据库字段定义且拒未知字段 |

### ReturnItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| foodLineNo | string | 是 | 源行 |
| componentNo | string | 否 | 组件 |
| qty | decimal | 是 | 退量 |
| disposition | string | 是 | CANCEL或LOSS或KEEP或REMAKE |

### Page

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | object[] | 是 | 该端点明确的业务VO |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页码 |
| size | int32 | 是 | 每页条数 |

### MenuCategoryInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| categoryNo | string | 否 | 新分类省略由服务端生成，旧号必须属于本餐牌 |
| name | string | 是 | 分类名 |
| sortNo | int32 | 是 | 同级非负唯一 |
| items | MenuItemInput[] | 是 | 必需，允许空 |

### MenuItemInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuItemNo | string | 是 | 项号 |
| goodsNo | string | 是 | 商品 |
| sortNo | int32 | 是 | 排序 |
| enabled | boolean | 是 | 启用 |
| skuScope | string | 是 | ALL或EXPLICIT |
| skuNos | string[] | 是 | EXPLICIT必须非空且属于商品 |
| optionMode | string | 是 | INHERIT或REPLACE |
| optionGroupNos | string[] | 是 | REPLACE显式组，空表示禁选 |
| skuRecipes | MenuSkuRecipeInput[] | 是 | 特定SKU配方，必须EXPLICIT白名单 |

### WeekScheduleInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| weekday | int32 | 是 | 1至7 |
| startTime | string | 是 | 严格HH:mm |
| endTime | string | 是 | 严格HH:mm |
| endDayOffset | int32 | 是 | 0或1；时长大于0不超过24小时，周内含跨周重叠拒绝 |

### ExceptionInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| localDate | date | 是 | 本地开始日期且在适用范围内 |
| mode | string | 是 | OPEN或CLOSED |
| startTime | string | 否 | OPEN必填 |
| endTime | string | 否 | OPEN必填 |
| endDayOffset | int32 | 是 | 0或1，CLOSED为0且无时间；日期唯一 |

### ContextInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| orderSource | string | 是 | 受控来源 |
| fulfillment | string | 是 | 受控履约 |
| channelBindingNo | string | 否 | 受权渠道绑定，本地省略 |
| pricePolicyRef | string | 否 | 权威价格策略引用，不接受金额 |

### BundleGroupInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| groupNo | string | 是 | 组号 |
| name | string | 是 | 名称 |
| minQty | decimal | 是 | 最少 |
| maxQty | decimal | 是 | 最多 |
| baseSkuNo | string | 否 | 补差基准 |
| sortNo | int32 | 是 | 排序 |

### BundleChoiceInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| choiceNo | string | 是 | 选项 |
| groupNo | string | 是 | 组 |
| skuNo | string | 是 | SKU |
| unitsPerPick | decimal | 是 | 每选用量 |
| priceMode | string | 是 | INCLUDED或ADD_SKU或DIFF_BASE |
| defaultQty | decimal | 是 | 默认 |
| minQty | decimal | 是 | 下限 |
| maxQty | decimal | 是 | 上限 |
| sortNo | int32 | 是 | 排序 |

### OptionItemInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| optionItemNo | string | 是 | 稳定业务引用最长32 |
| sourceKind | string | 是 | SPEC_VALUE或MODIFIER |
| specValueNo | string | 否 | 原字典值最长64 |
| modifierNo | string | 否 | 配料 |
| maxQty | decimal | 是 | 上限 |
| defaultQty | decimal | 是 | 默认 |
| sortNo | int32 | 是 | 排序 |
| minQty | decimal | 是 | 本选项下限 |

### ComponentInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| memberNo | string | 是 | 叶子配料 |
| quantity | decimal | 是 | 正用量 |

### RouteInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| stationNo | string | 是 | 同店启用工位 |
| taskRole | string | 是 | MAKE或ASSEMBLE或PACK，关系版本只由服务端产生 |

### VersionInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 是 | 台账 |
| version | int64 | 是 | 版本 |

### DeliveryLineInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| foodLineNo | string | 是 | 餐饮行 |
| qty | decimal | 是 | 交付量 |

### TableArea

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| areaNo | string | 是 | 桌区号 |
| storeNo | string | 是 | 门店 |
| code | string | 是 | 编码 |
| name | string | 是 | 名称 |
| sortNo | int32 | 是 | 排序 |
| status | string | 是 | 启停 |
| version | int64 | 是 | 版本 |

### Table

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string | 是 | 桌号 |
| storeNo | string | 是 | 门店 |
| areaNo | string | 是 | 桌区 |
| code | string | 是 | 店内编码 |
| name | string | 是 | 名称 |
| capacity | int32 | 是 | 容量 |
| sortNo | int32 | 是 | 排序 |
| status | string | 是 | ACTIVE或DISABLED |
| turnoverState | string | 是 | IDLE或IN_USE或NEEDS_CLEANING |
| resourceNo | string | 否 | 共享资源引用，绑定写入待权威合同 |
| version | int64 | 是 | 版本 |

### Occupancy

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| occupancyNo | string | 是 | 占用号 |
| tableNo | string | 是 | 桌台 |
| checkNo | string | 是 | 获准台账 |
| seats | int32 | 是 | 占座 |
| startedAt | datetime | 是 | 开始 |

### SeatInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| seatNo | string | 否 | 创建省略由服务端发号，更新只能引用本台账座位 |
| label | string | 是 | 台账内唯一座位标签 |
| guestRef | string | 否 | 获准匿名或顾客引用 |

### Dish

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| goodsNo | string | 是 | 商品 |
| dishKind | string | 是 | SINGLE或BUNDLE或ADDON |
| prepSeconds | int32 | 是 | 制作时间 |
| presentation | DishPresentation | 是 | 覆盖展示 |
| measurement | MeasurementPolicy | 是 | 计量 |
| configVersion | int64 | 是 | 规则版本 |
| version | int64 | 是 | 并发版本 |

### DishPresentation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| displayName | string | 否 | 餐饮别名 |
| localizedNames | LocalizedName[] | 是 | 语言名称 |
| imageRefs | string[] | 是 | 已有媒资引用 |
| tags | string[] | 是 | 标签 |
| allergenNotes | string[] | 是 | 食材过敏提示非医疗保证 |

### LocalizedName

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| locale | string | 是 | 受控语言 |
| name | string | 是 | 名称 |

### MeasurementPolicy

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| unit | string | 是 | 受控单位 |
| mode | string | 是 | COUNT或WEIGHT |
| scale | int32 | 是 | 精度 |
| allowedDeviation | decimal | 否 | 需顾客确认的允差 |

### ConfigRevision

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| revisionNo | string | 是 | 冻结发布号 |
| configKind | string | 是 | OPTION_GROUP或MODIFIER或BUNDLE或RECIPE |
| configNo | string | 是 | 对象 |
| revision | int64 | 是 | 发布修订非工作CAS |
| effectiveAt | datetime | 是 | 生效时刻 |
| contentHash | string | 是 | 规范摘要 |
| status | string | 是 | SCHEDULED或EFFECTIVE或CANCELLED |

### Menu

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string | 是 | 餐牌 |
| storeNo | string | 是 | 当前授权绑定门店 |
| name | string | 是 | 名称 |
| menuType | string | 是 | STANDARD或TIME或SEASON或EVENT |
| timezone | string | 是 | 当前门店策略时区 |
| priority | int32 | 是 | 优先级 |
| exclusiveGroup | string | 否 | 互斥组 |
| status | string | 是 | DRAFT或PUBLISHED或DISABLED |
| version | int64 | 是 | 并发版 |
| configVersion | int64 | 是 | 规则版；CAT-02A为工作头，不冒充已生效修订 |

### BatchDetail

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batch | Batch | 是 | 批次 |
| lines | FoodLine[] | 是 | 冻结源行 |
| financialStatus | string | 是 | 核心资金投影 |
| fulfillmentStatus | string | 是 | 核心履约投影 |
| pendingOperationNos | string[] | 是 | 未知操作 |

### FoodLine

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| foodLineNo | string | 是 | 餐饮行 |
| sourceLineRef | string | 是 | 原源行 |
| goodsNo | string | 是 | 商品 |
| skuNo | string | 是 | SKU |
| qty | decimal | 是 | 数量 |
| unit | string | 是 | 单位 |
| seatNo | string | 否 | 座位 |
| configRevisionNos | string[] | 是 | 发布版本 |
| displaySnapshot | object | 是 | 受控历史展示 |
| coreAllocations | CoreLineAllocation[] | 是 | 受权核心分配 |

### CoreLineAllocation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| allocationNo | string | 是 | 分配号 |
| qty | decimal | 是 | 数量 |
| amount | Money | 是 | 核心分配金额 |
| coreSnapshotHash | string | 是 | 快照摘要 |

### Cart

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| storeNo | string | 是 | 真实门店 |
| checkNo | string | 否 | 台账 |
| sessionNo | string | 否 | 无台会话或首建来源不构成共享车授权 |
| buyerNo | string | 否 | B端真实买家且C端始终隐去 |
| status | string | 是 | OPEN或SUBMITTING或CLOSED |
| orderSource | string | 是 | POS或WAITER或QR或H5 |
| fulfillment | string | 是 | DINE_IN或PICKUP或DELIVERY |
| scheduledAt | datetime | 否 | 本阶段非空拒绝 |
| version | int64 | 是 | 整个草稿CAS版本 |
| lines | OrderLineInput[] | 是 | 活动草稿行 |

### Operation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作 |
| status | string | 是 | 含处理中未知失败 |
| version | int64 | 是 | 当前版本 |
| resultRef | string | 否 | 结果号 |

### TableVersionInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string | 是 | 桌台 |
| version | int64 | 是 | 资源版本 |

### SelectionRules

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| mutexSets | string[][] | 是 | 不可同选的optionItemNo集合 |
| requires | SelectionDependency[] | 是 | 选择依赖 |

### SelectionDependency

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| optionItemNo | string | 是 | 被选项 |
| requiresItemNos | string[] | 是 | 必须同时选项 |

### Reservation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string | 是 | 预约 |
| partySize | int32 | 是 | 人数 |
| startAt | datetime | 是 | 开始 |
| endAt | datetime | 是 | 结束 |
| status | string | 是 | 资源状态 |
| depositRef | string | 否 | 获准订金凭据 |
| checkNo | string | 否 | 到店台账 |
| version | int64 | 是 | 版本 |

### ActionLog

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| actionNo | string | 是 | 记录 |
| action | string | 是 | 动作 |
| actorRef | string | 是 | 审计操作人 |
| occurredAt | datetime | 是 | 时间 |
| reason | string | 否 | 原因 |

### CatalogItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string | 是 | 餐牌 |
| menuItemNo | string | 是 | 菜项身份，不能仅按SKU合并 |
| categoryNo | string | 是 | 分类 |
| categoryName | string | 是 | 分类名 |
| goodsNo | string | 是 | 原商品 |
| title | string | 是 | 当前原标题 |
| cover | string | 否 | 当前原封面 |
| dish | Dish | 是 | 冻结餐饮属性 |
| optionGroupNos | string[] | 是 | 冻结显式选配 |
| skus | CatalogSku[] | 是 | 当前权威及限制提示 |

### SalesRestriction

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| restrictionNo | string | 是 | 约束 |
| storeNo | string | 是 | 授权店 |
| skuNo | string | 是 | 原SKU |
| mode | string | 是 | SOLD_OUT或QUOTA或PREORDER_LIMIT |
| businessDate | date | 否 | 业务日 |
| quota | decimal | 否 | 额度 |
| reservedQty | decimal | 是 | 原子预占 |
| consumedQty | decimal | 是 | 已用 |
| startsAt | datetime | 是 | 开始 |
| expiresAt | datetime | 否 | 到期 |
| status | string | 是 | 当前时点状态含EXPIRED |
| reason | string | 是 | 原因 |
| orderSource | string | 否 | 来源过滤 |
| fulfillment | string | 否 | 履约过滤 |
| version | int64 | 是 | CAS |

### MenuSkuRecipeInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 本菜品SKU |
| recipeNo | string | 是 | 本SKU获准配方变体 |

### MenuChannelBinding

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string | 是 | 服务端业务号 |
| menuNo | string | 是 | 本店餐牌 |
| storeNo | string | 是 | 门店 |
| provider | string | 是 | 渠道 |
| channelAccountRef | string | 是 | 仅经营授权端可读账号引用 |
| externalStoreRef | string | 是 | 外部门店引用 |
| status | string | 是 | DRAFT或ACTIVE或DISABLED |
| version | int64 | 是 | CAS |

### CatalogSync

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| syncNo | string | 是 | 任务 |
| bindingNo | string | 是 | 绑定 |
| revisionNo | string | 是 | 冻结修订 |
| status | string | 是 | 真实同步状态 |
| items | CatalogSyncItem[] | 是 | 分项结果 |
| version | int64 | 是 | CAS |

### CatalogSyncItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| lineNo | int32 | 是 | 行 |
| menuItemNo | string | 是 | 菜项 |
| status | string | 是 | 状态 |
| externalObjectRef | string | 否 | 外部编号 |
| errorCode | string | 否 | 脱敏错误 |
| confirmedRevisionNo | string | 否 | 确认修订 |

### Recipe

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipeNo | string | 是 | 配方 |
| storeNo | string | 是 | 门店 |
| skuNo | string | 是 | SKU |
| recipeCode | string | 是 | DEFAULT或受控变体 |
| yieldQty | decimal | 是 | 正配置份数 |
| yieldUnit | string | 是 | 固定EA，非物理单位 |
| status | string | 是 | DRAFT或ACTIVE；发布后编辑回DRAFT |
| configVersion | int64 | 是 | 内容版本 |
| version | int64 | 是 | CAS |
| lines | RecipeLineVO[] | 是 | 保存的物料事实 |
| adjustments | RecipeAdjustmentVO[] | 是 | 选配工作调整，旧回执缺失时空数组 |

### RecipeLineInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| lineNo | string | 否 | 新行省略服务端发号，既有行不能换物料 |
| itemRef | string | 是 | 32位内获准库存物料 |
| qty | decimal | 是 | 正净用量18,6 |
| unit | string | 是 | 必须等于物料baseUom |
| lossRate | decimal | 是 | 0至1间损失比例，最多6位小数 |

### RecipeAdjustmentInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| adjustmentNo | string | 否 | 创建省略服务端发号 |
| optionItemNo | string | 是 | 选项 |
| targetLineNo | string | 是 | 本配方行 |
| action | string | 是 | SET或ADD或REMOVE |
| qty | decimal | 否 | SET/ADD非负，REMOVE省略 |
| unit | string | 是 | 与目标一致 |

### ProductionConsumption

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| consumptionNo | string | 是 | 耗用号 |
| storeNo | string | 是 | 门店 |
| sourceEventNo | string | 是 | 原制作事件 |
| purpose | string | 是 | 耗用目的 |
| status | string | 是 | 真实状态 |
| businessDate | date | 是 | 营业日 |
| inventoryOperationRef | string | 否 | 库存原操作 |
| lines | ConsumptionLine[] | 是 | 明细 |

### ConsumptionLine

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| lineNo | int32 | 是 | 序号 |
| componentPath | string | 是 | 实例路径 |
| itemRef | string | 是 | 物料 |
| theoryQty | decimal | 是 | 理论量 |
| unit | string | 是 | 权威单位 |
| recipeRevisionNo | string | 是 | 历史配方 |
| status | string | 是 | 回执状态 |
| actualQty | decimal | 否 | 仅真实库存回执 |
| cost | Money | 否 | 仅真实历史总成本 |
| inventoryReceiptRef | string | 否 | 回执行号 |

### ChannelQuoteReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| entityNo | string | 是 | 可信服务身份解析的经营主体 |
| storeNo | string | 是 | 已授权门店 |
| channelBindingNo | string | 否 | 仅适配器认证所得渠道绑定 |
| menuRevisionNo | string | 是 | 不可变餐牌修订 |
| menuItemNo | string | 是 | 原餐牌条目不可按SKU合并 |
| sourceLineRef | string | 是 | 购买实例引用 |
| skuNo | string | 是 | 当前菜项内实际购买规格 |
| quantity | decimal | 是 | 购买份数 |
| selections | QuoteSelection[] | 是 | 按购买实例绑定的口味加料套餐选择 |
| pricePolicyRef | string | 否 | 报价权威校验的策略引用 |
| currency | string | 是 | ISO币种 |
| selectionRevisionNos | string[] | 是 | 已发布选配套餐配方修订 |
| demands | FrozenMaterialDemand[] | 是 | 唯一库存需求树 |

### ChannelQuoteResult

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| quoteRef | string | 是 | 权威报价引用 |
| policyRevisionNo | string | 是 | 权威策略版本 |
| expiresAt | datetime | 是 | 到期时需重报 |
| currency | string | 是 | 币种 |
| totalMinor | int64 | 是 | 最小货币单位金额 |
| allocations | QuoteLineAllocation[] | 是 | 核心行分摊合计等于总额 |
| stockDemandTree | FrozenMaterialDemand[] | 是 | 与受理一致且只执行一次的需求树 |

### QuoteLineAllocation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceLineRef | string | 是 | 购买实例引用 |
| componentPath | string | 是 | 完整套餐选配实例路径 |
| coreLineRef | string | 是 | 核心行分配引用 |
| qty | decimal | 是 | 数量 |
| amountMinor | int64 | 是 | 优惠后权威分摊金额 |

### FrozenMaterialDemand

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceLineRef | string | 是 | 购买实例引用 |
| componentPath | string | 是 | 不能仅按SKU折叠的路径 |
| executionMode | string | 是 | SELLABLE_SKU/RECIPE_LEAVES/NONE |
| itemRef | string | 是 | 库存权威物料或成品引用 |
| recipeRevisionNo | string | 否 | 配方不可变修订 |
| qty | decimal | 是 | 按权威数量精度的毛需求 |
| unit | string | 是 | 库存权威单位 |
| conversionRevision | string | 是 | 不可变换算版本 |

### InventoryMaterialFacts

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| itemRef | string | 是 | 权威物料引用 |
| storeNo | string | 是 | 所属门店 |
| unit | string | 是 | 库存基本单位 |
| conversionRevision | string | 是 | 单位换算版本 |
| quantityScale | int32 | 是 | 数量有效小数位 |
| valuationScale | int32 | 是 | 单位成本内部精度 |
| roundingMode | string | 是 | 权威数量及成本舍入方式 |
| status | string | 是 | ACTIVE/DISABLED |

### MaterialConsumptionReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| entityNo | string | 是 | 可信主体 |
| storeNo | string | 是 | 获准门店 |
| productionEventNo | string | 是 | 稳定制作事实编号 |
| requestKey | string | 是 | 重试不变的业务键 |
| requestHash | string | 是 | 同键异载荷冲突检测 |
| originalReservationRef | string | 是 | 受理时原预占 |
| purpose | string | 是 | PRODUCTION/REMAKE/MATERIAL_WASTE/REVERSAL |
| originalOperationRef | string | 否 | 冲回必须引用原已确认操作 |
| demands | FrozenMaterialDemand[] | 是 | 来自受理冻结树的本次制作数量 |

### MaterialConsumptionResult

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationRef | string | 是 | 库存权威操作编号 |
| requestKey | string | 是 | 原请求键 |
| status | string | 是 | CONFIRMED/PARTIAL/UNKNOWN/FAILED |
| receipts | MaterialConsumptionReceipt[] | 是 | 逐行确认不以请求数量冒充实耗 |
| occurredAt | datetime | 是 | 权威事实发生时点 |

### MaterialConsumptionReceipt

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceLineRef | string | 是 | 原购买实例 |
| componentPath | string | 是 | 原冻结路径 |
| receiptRef | string | 否 | 已确认库存流水引用 |
| status | string | 是 | CONFIRMED/UNKNOWN/FAILED |
| actualQty | decimal | 否 | 仅确认后返回实耗不得用理论量填充 |
| unit | string | 是 | 库存单位 |
| costMinor | int64 | 否 | 权威历史分摊成本未知为null |
| currency | string | 否 | 成本币种 |
| valuationRevision | string | 否 | 历史计价依据 |

### QuoteSelection

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| componentPath | string | 是 | 购买实例内完整组件路径 |
| groupRevisionNo | string | 是 | 已发布组修订 |
| optionItemNo | string | 是 | 该修订内合法选项 |
| quantity | decimal | 是 | 选择份数 |
| selectionInstanceRef | string | 是 | 同SKU不同选择仍独立的实例号 |

### ProductSummary

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| goodsNo | string | 是 | 基础商品编号 |
| entityNo | string | 是 | 已验证主体 |
| storeNo | string | 是 | 已验证门店 |
| title | string | 是 | 商品名称 |
| cover | string | 否 | 原商品图片 |
| baseOnSale | boolean | 是 | 基础上下架事实非餐饮可售 |
| auditStatus | string | 是 | 审核事实 |
| version | int64 | 是 | 原商品版本 |

### ProductSku

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 基础规格编号 |
| spec | string | 否 | 规格展示 |
| version | int64 | 是 | 基础规格版本 |

### ProductCatalog

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| goodsNo | string | 是 | 基础商品编号 |
| entityNo | string | 是 | 已验证主体 |
| storeNo | string | 是 | 已验证门店 |
| title | string | 是 | 商品名称 |
| cover | string | 否 | 原商品图片 |
| baseOnSale | boolean | 是 | 基础上下架事实非餐饮可售 |
| auditStatus | string | 是 | 审核事实 |
| version | int64 | 是 | 原商品版本 |
| skus | ProductSku[] | 是 | 原商品规格事实，不含金额库存 |

### DishCatalogItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| dish | Dish | 是 | 餐饮属性 |
| product | ProductSummary | 是 | 当前门店商品权威图文与管理状态 |

### DishOptionItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| optionItemNo | string | 是 | 当前组条目 |
| sourceKind | string | 是 | SPEC_VALUE或MODIFIER |
| specValueNo | string | 否 | 原口味值 |
| modifierNo | string | 否 | 餐饮配料 |
| minQty | decimal | 是 | 下限 |
| maxQty | decimal | 是 | 上限 |
| defaultQty | decimal | 是 | 默认量 |
| sortNo | int32 | 是 | 顺序 |

### DishOptionData

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 组名称 |
| minQty | decimal | 是 | 组下限 |
| maxQty | decimal | 是 | 组上限 |
| publishedRevisionNo | string | 否 | 已发布引用独立于当前草稿 |
| selectionRules | object | 是 | 存储的管理选择规则非执行授权 |
| items | DishOptionItem[] | 是 | 真实当前条目 |

### DishOptionGroup

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 选择组编号 |
| kind | string | 是 | 固定OPTION_GROUP |
| revision | int64 | 是 | 当前管理草稿并发版本 |
| status | string | 是 | 管理状态 |
| data | DishOptionData | 是 | 实际工作头与条目 |

### ModifierComponentInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| memberNo | string | 是 | 单项配料业务号 |
| quantity | decimal | 是 | 正组合份数DECIMAL12,3 |

### ModifierComponentVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| componentNo | string | 是 | 服务端生成明细业务号 |
| memberNo | string | 是 | 单项配料 |
| quantity | decimal | 是 | 每组合份数 |

### ModifierConfigData

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 名称 |
| salesSkuNo | string | 否 | 原计价SKU |
| publishedRevisionNo | string | 否 | 已有发布引用 |
| components | ModifierComponentVO[] | 是 | 当前工作成员 |

### ModifierConfigVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配料业务号 |
| kind | string | 是 | ADDON或COMBO |
| version | int64 | 是 | 工作头CAS |
| status | string | 是 | DRAFT或ACTIVE或DISABLED |
| data | ModifierConfigData | 是 | 管理定义 |

### PublishConfigReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 工作头CAS整数 |
| effectiveAt | datetime | 是 | 带时区毫秒精度 |
| approvalRef | string | 否 | 审批业务引用 |

### ConfigRevisionContent

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 修订元数据 |
| definitionSnapshot | string | 是 | 仅内部读取的完整JSON冻结文档 |

### PublicationDependency

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| revisionNo | string | 是 | 依赖修订 |
| configKind | string | 是 | 类型 |
| configNo | string | 是 | 对象 |
| revision | int64 | 是 | 单调修订 |
| contentHash | string | 是 | 规范摘要 |

### CreateBundleReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 原主SKU业务号 |

### CreateBundleResultVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 原SKU |
| version | int64 | 是 | 0 |
| status | string | 是 | DRAFT |

### ReplaceBundleDefinitionReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 严格非负整数CAS |
| groups | BundleGroupInput[] | 是 | 最多30 |
| choices | BundleChoiceInput[] | 是 | 最多100 |

### BundleMutationVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 原SKU |
| version | int64 | 是 | 更新后工作头CAS |

### BundleConfigVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 原SKU |
| version | int64 | 是 | 工作头CAS |
| status | string | 是 | DRAFT或ACTIVE或DISABLED |
| data | BundleConfigData | 是 | 真实管理定义 |

### BundleConfigData

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configVersion | int64 | 是 | 定义变更次数非发布修订 |
| publishedRevisionNo | string | 否 | 管理提示 |
| groups | BundleGroupInput[] | 是 | 有序组 |
| choices | BundleChoiceInput[] | 是 | 有序选项 |

### GetBundleVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| config | BundleConfigVO | 是 | 真实管理定义 |

### ListBundlesQuery

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

### BundlePublicationSnapshot

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| contentSchema | int32 | 是 | 固定2 |
| configKind | string | 是 | 固定BUNDLE |
| configNo | string | 是 | 主SKU |
| sourceVersion | int64 | 是 | 严格整数工作CAS |
| bundle | BundleConfigVO | 是 | 完整冻结定义 |
| skuFacts | SkuIdentityFact[] | 是 | 所有显式SKU身份版本 |
| approvalRef | string | 否 | 仅审批引用 |

### SkuIdentityFact

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 原SKU |
| goodsNo | string | 是 | 原商品 |
| entityNo | string | 是 | 权威经营主体 |
| storeNo | string | 是 | 验真门店 |
| version | int64 | 是 | 严格非负整数身份版本 |

### RecipeLineVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| lineNo | string | 是 | 行号 |
| itemRef | string | 是 | 物料 |
| qty | decimal | 是 | 净用量 |
| unit | string | 是 | 保存基础单位 |
| lossRate | decimal | 是 | 损失比例 |
| materialDefinitionHash | string | 是 | 真实物料定义摘要，不是CAS或换算版本 |

### RecipeAdjustmentVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| adjustmentNo | string | 是 | 服务端稳定调整编号 |
| optionItemNo | string | 是 | 选项 |
| targetLineNo | string | 是 | 本配方行 |
| action | string | 是 | SET或ADD或REMOVE |
| qty | decimal | 否 | SET/ADD非负，REMOVE省略 |
| unit | string | 是 | 与目标一致 |
| optionIdentityHash | string | 是 | 服务端保存来源身份摘要，非物理行号或CAS |

### RecipePublicationSnapshot

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| contentSchema | int32 | 是 | 固定3 |
| configKind | string | 是 | RECIPE |
| configNo | string | 是 | 配方号 |
| sourceVersion | int64 | 是 | 工作CAS |
| recipe | Recipe | 是 | 完整工作定义 |
| skuFacts | SkuIdentityFact[] | 是 | 根规格 |
| materialFacts | MaterialFact[] | 是 | 真实基础单位物料 |
| optionGroups | RecipeOptionGroupSnapshot[] | 是 | 完整组选项依赖 |
| approvalRef | string | 否 | 审批依据 |

### MaterialFact

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| itemId | string | 是 | 原库存物料 |
| entityNo | string | 是 | 所属主体 |
| name | string | 是 | 来源名称 |
| baseUom | string | 是 | 基础单位 |
| weighed | int32 | 是 | 原计量标记 |
| dataSource | string | 是 | 原来源 |
| unitSupport | string | 是 | BASE_ONLY |
| postingSupport | string | 是 | INTEGER_ONLY |
| definitionHash | string | 是 | 语义摘要非CAS |

### RecipeOptionGroupSnapshot

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| goodsNo | string | 是 | 实际根商品绑定 |
| publication | PublicationDependency | 是 | 不可变依赖 |
| definition | object | 是 | 完整schema1组选项文档 |

### CreateMenuReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 1到64文本 |
| menuType | string | 是 | STANDARD或TIME或SEASON或EVENT |
| timezone | string | 是 | 门店当前生效IANA时区 |
| priority | int32 | 是 | 必需 |
| exclusiveGroup | string | 否 | 1到32文本；禁止自报主体/门店/状态/价格 |

### UpdateMenuReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 1到64文本 |
| menuType | string | 是 | STANDARD或TIME或SEASON或EVENT |
| timezone | string | 是 | 门店当前生效IANA时区 |
| priority | int32 | 是 | 必需 |
| exclusiveGroup | string | 否 | 1到32文本；禁止自报主体/门店/状态/价格 |
| version | int64 | 是 | 非负且小于Long.MAX_VALUE |

### MenuSchedule

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| validFrom | date | 否 | 来源起始日含首日 |
| validTo | date | 否 | 来源起始日含末日 |
| weekSchedules | WeekScheduleInput[] | 是 | 必需 |
| exceptions | ExceptionInput[] | 是 | 必需，日期覆盖周规则 |

### ReplaceMenuScheduleReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| section | string | 是 | 本批仅SCHEDULE |
| version | int64 | 是 | 草稿CAS |
| weekSchedules | WeekScheduleInput[] | 是 | 必需，允许[]不允许null |
| exceptions | ExceptionInput[] | 是 | 必需，允许[]不允许null |
| validFrom | date | 否 | 含首日 |
| validTo | date | 否 | 含末日；完整storeNos/contexts/categories分支尚未实现，不接受这些字段 |

### MenuComposition

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| categories | MenuCategoryInput[] | 是 | 显式空清空组成，不改时段和上下文 |

### Station

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 工位号 |
| version | int64 | 是 | 工作CAS |
| status | string | 是 | 工作状态 |
| storeNo | string | 是 | 授权门店 |
| name | string | 是 | 名称 |
| stationType | string | 是 | HOT或COLD或BAR或PACK |
| enabled | boolean | 是 | 启用 |
| publishedRevisionNo | string | 否 | 历史发布指针 |

### StationRoutes

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 原SKU |
| version | int64 | 是 | 整个店SKU路由版本，清空仍保留计数 |
| routes | RouteInput[] | 是 | 当前有效路由 |

### Catalog

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 获准店 |
| orderSource | string | 是 | 可信来源 |
| fulfillment | string | 是 | 履约 |
| asOf | datetime | 是 | 服务端当前时点 |
| menus | CatalogMenu[] | 是 | 生效发布 |
| items | CatalogItem[] | 是 | 保留菜项身份 |

### CatalogMenu

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string | 是 | 餐牌 |
| revisionNo | string | 是 | 冻结发布号 |
| revision | int64 | 是 | 发布序号 |
| name | string | 是 | 冻结名称 |
| priority | int32 | 是 | 优先级 |
| exclusiveGroup | string | 否 | 互斥组 |
| dependencies | CatalogDependency[] | 是 | 冻结依赖 |

### CatalogDependency

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| revisionNo | string | 是 | 冻结发布号 |
| configKind | string | 是 | 类型 |
| configNo | string | 是 | 对象 |
| revision | int64 | 是 | 序号 |
| contentHash | string | 是 | 校验 |
| optionGroup | DishOptionGroup | 否 | 公开选配定义 |
| modifier | CatalogModifier | 否 | 公开小料元数据 |
| bundle | BundleConfigVO | 否 | 公开套餐选项 |
| recipe | CatalogRecipe | 否 | 仅配方身份，无原料和耗用 |
| specValues | CatalogSpecValue[] | 是 | 冻结选项标签及版本 |

### CatalogSku

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 原SKU |
| spec | string | 否 | 当前规格 |
| recipeNo | string | 否 | 冻结配方 |
| priceMinor | int64 | 否 | 真实原CN价格，未知为null，不伪造0 |
| currency | string | 否 | 已知CN价为CNY |
| pricingStatus | string | 是 | KNOWN或UNAVAILABLE或UNKNOWN |
| available | int32 | 否 | 仅权威PLATFORM物理可售件数，未知为null |
| stockStatus | string | 是 | KNOWN或UNKNOWN或UNAVAILABLE |
| onSale | boolean | 是 | 当前原上架及审核 |
| restricted | boolean | 是 | 当前餐饮限制 |
| remainingQuota | decimal | 否 | 剩余额度 |
| transactable | boolean | 是 | 保守当前展示提示，不是报价或下单承诺 |
| requiresSelectionQuote | boolean | 是 | 选配小料套餐配方必须重新权威报价 |
| reason | string | 否 | 不可成交原因 |

### RepublishConfigReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 当前工作CAS |
| effectiveAt | datetime | 是 | 新生效时刻 |
| reason | string | 是 | 必需原因 |
| approvalRef | string | 否 | 审批引用 |

### CatalogModifier

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 小料号 |
| version | int64 | 是 | 版本 |
| status | string | 是 | 状态 |
| name | string | 是 | 名称 |
| kind | string | 是 | ADDON或COMPONENT |
| salesSkuNo | string | 否 | 销售SKU |
| salesOffer | CatalogSku | 否 | 当前原SKU展示提示，不含组件用量 |

### CatalogRecipe

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipeNo | string | 是 | 配方号 |
| skuNo | string | 是 | 原SKU |
| storeNo | string | 是 | 授权门店 |
| version | int64 | 是 | 版本 |
| configVersion | int64 | 是 | 配置版，不公开原料配比 |

### CatalogSpecValue

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| valueNo | string | 是 | 选项 |
| dimNo | string | 是 | 维度 |
| label | string | 是 | 公开标签 |
| dimName | string | 是 | 公开维度名 |
| valueType | string | 是 | 值类型 |
| usageType | string | 是 | 用途 |
| valueVersion | int64 | 是 | 值版本 |
| dimVersion | int64 | 是 | 维度版本 |

### CartBundleChoiceInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| choiceNo | string | 是 | 冻结套餐选项 |
| qty | decimal | 是 | 每份套餐选择数量 |

### QueueTicketView

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticketNo | string | 是 | 原排队号 |
| businessDate | date | 是 | 营业日 |
| partySize | int32 | 是 | 人数 |
| status | string | 是 | 真实排队状态 |
| calledAt | datetime | 否 | 叫号时间 |
| checkNo | string | 否 | 原开台回执 |
| version | int64 | 是 | CAS |
| notificationStatus | string | 是 | 真实通知状态 |

### QueueTicketPage

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | QueueTicketView[] | 是 | 当前门店排队票 |
| total | int64 | 是 | 同条件总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

## 四、逐接口输入输出

### FOOD-API-001 · 查询营业策略

`GET /biz/x/food/stores/{storeNo}/policy`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| at | datetime | 否 | 查询时点 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| policyNo | string | 是 | 策略 |
| timezone | string | 是 | IANA时区 |
| cutoverMinute | int32 | 是 | 切点 |
| payPolicy | string | 是 | 先后付 |
| capabilities | string[] | 是 | 启用能力 |
| version | int64 | 是 | 版本 |

### FOOD-API-002 · 发布营业策略

`POST /biz/x/food/stores/{storeNo}/policies`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等；version首次0、追加递增且effectiveFrom晚于最新发布；能力与审批未交付前非空拒绝。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| timezone | string | 是 | IANA时区 |
| cutoverMinute | int32 | 是 | 0至1439 |
| effectiveFrom | datetime | 是 | 生效 |
| payPolicy | string | 是 | PREPAY或POSTPAY |
| capabilities | string[] | 是 | 能力码 |
| approvalRef | string | 否 | 审批 |
| version | int64 | 是 | 最新发布版，首次0，含未来版 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-003 · 餐饮接单日历例外

`PUT /biz/x/food/stores/{storeNo}/calendar/{localDate}`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等；不是门店openHours编辑入口，不修改共享营业时间；localDate按策略营业日切点；CLOSED关闭该营业日，OPEN与原营业时间取交集；policy_no仅追溯。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| localDate | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| mode | string | 是 | OPEN或CLOSED |
| intervals | string[] | 是 | 本地起止时段 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-004 · 创建menus

`POST /biz/x/food/stores/{storeNo}/menus`

权限与守卫：原GOODS权限/BizContext/StoreDirectory与实际同主体门店绑定；未知字段拒绝；写操作CAS/幂等/审计/Outbox同事务；timezone与当前生效门店策略一致；只写单店无override头，多店编辑409；完成回执优先重放。

能力依赖：已实现餐牌工作定义管理；发布与历史读取独立，不能将草稿当可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 菜单名 |
| menuType | string | 是 | STANDARD或TIME或SEASON或EVENT |
| timezone | string | 是 | 时区 |
| priority | int32 | 是 | 优先级 |
| exclusiveGroup | string | 否 | 互斥组 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配置号 |
| version | int64 | 是 | 草稿版本 |
| status | string | 是 | DRAFT |

### FOOD-API-005 · 读取menus

`GET /biz/x/food/stores/{storeNo}/menus/{configNo}`

权限与守卫：原GOODS权限/BizContext/StoreDirectory与实际同主体门店绑定；未知字段拒绝；写操作CAS/幂等/审计/Outbox同事务；timezone与当前生效门店策略一致；只写单店无override头，多店编辑409；完成回执优先重放。

能力依赖：已实现餐牌工作定义管理；发布与历史读取独立，不能将草稿当可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| config | Menu | 是 | 当前工作头 |
| schedule | MenuSchedule | 否 | 旧提供方缺省为未知 |
| composition | MenuComposition | 否 | 分类菜项SKU及选配配方 |
| contexts | ContextInput[] | 是 | 明确来源及履约 |

### FOOD-API-006 · 发布餐牌

`POST /biz/x/food/stores/{storeNo}/menus/{configNo}:publish`

权限与守卫：真实GOODS及获准门店；MENU开关独立；外部原身份先于本地事务核验；店级仲裁锁/CAS/actor绑定幂等；冻结定义与依赖、原商品身份且不含价格库存；完成回执先重放；历史内容不变；CHANNEL互斥按冻结的主体/门店/提供方/渠道账号/外部门店身份比较，不能按餐牌私有bindingNo判离散。

能力依赖：ALL-03/04已实现，生效时间及门店范围内选择冻结修订；不表示订单厨房事务已接入。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 草稿版本 |
| effectiveAt | datetime | 是 | 生效时刻 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 冻结发布修订 |

### FOOD-API-007 · 创建option-groups

`POST /biz/x/food/stores/{storeNo}/option-groups`

权限与守卫：原商品权限及当前主体门店校验；主体共享配置；服务端编号与DRAFT/0；数量DECIMAL(18,6)非负且min≤max；归一化幂等、审计与Outbox同事务；拒绝未知字段。

能力依赖：已实现草稿管理，不代表选项维护或发布。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 组名 |
| minQty | decimal | 是 | 非负下限 |
| maxQty | decimal | 是 | 不小于下限 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配置号 |
| version | int64 | 是 | 草稿版本 |
| status | string | 是 | DRAFT |

### FOOD-API-008 · 读取option-groups

`GET /biz/x/food/stores/{storeNo}/option-groups/{configNo}`

权限与守卫：原商品权限及当前主体门店校验；仅返回当前主体共享工作头与真实有序条目；跨主体404；拒绝查询参数；不作为已发布授权。

能力依赖：已实现管理查询，不代表可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| config | Config | 是 | 具体配置，类型由路径确定 |

### FOOD-API-009 · 发布option-groups

`POST /biz/x/food/stores/{storeNo}/option-groups/{configNo}:publish`

权限与守卫：已实现；原GOODS及门店目录权限，主体共享头；完整聚合及可满足性校验；按门店和父生效时点冻结不可变依赖；事务外原SKU/规格权威预读，锁头重验一致；CAS/幂等/审计/Outbox原子；禁止历史回填。

能力依赖：已实现OPTION_GROUP/MODIFIER配置发布；不授予价格库存或可售权限。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 工作头CAS |
| effectiveAt | datetime | 是 | 带时区毫秒精度生效时刻 |
| approvalRef | string | 否 | 审批业务引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 冻结发布修订 |

### FOOD-API-010 · 创建bundles

`POST /biz/x/food/stores/{storeNo}/bundles`

权限与守卫：已实现；原GOODS及当前门店目录；原SKU身份事务外验真，主SKU唯一且不得已为其他套餐成员或补差基准；CAS/幂等回执/审计/Outbox；不接受自报主体或价格库存。

能力依赖：已实现套餐空草稿登记；冻结发布API012及成交报价后续。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 主SKU |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 主SKU业务号 |
| version | int64 | 是 | 草稿CAS |
| status | string | 是 | DRAFT |

### FOOD-API-011 · 读取bundles

`GET /biz/x/food/stores/{storeNo}/bundles/{configNo}`

权限与守卫：已实现；原GOODS及当前门店目录；主体共享工作头，跨主体404；拒绝查询参数，不依赖上游在线。

能力依赖：已实现草稿查询，不代表门店可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| config | BundleConfigVO | 是 | 真实套餐工作头和有序组/选项 |

### FOOD-API-012 · 发布bundles

`POST /biz/x/food/stores/{storeNo}/bundles/{configNo}:publish`

权限与守卫：已实现；原GOODS及门店目录；完整非空可行套餐、SKU身份和禁止嵌套校验；事务外权威预读后SERIALIZABLE锁头重验；schema2冻结完整定义及SKU身份版本，CAS/幂等/审计/Outbox原子；禁止回填历史。

能力依赖：已实现；shop.food.bundle-publication-enabled默认false，先升级schema2读端再开发布；不授予报价库存或可售权限。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 严格非负整数CAS |
| effectiveAt | datetime | 是 | 带时区毫秒精度 |
| approvalRef | string | 否 | 仅审批业务引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 冻结发布修订 |

### FOOD-API-013 · 创建stations

`POST /biz/x/food/stores/{storeNo}/stations`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：ALL-04已实现店内工位管理；不派发厨房订单。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 工位名 |
| stationType | string | 是 | HOT或COLD或BAR或PACK |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配置号 |
| version | int64 | 是 | 草稿版本 |
| status | string | 是 | DRAFT |

### FOOD-API-014 · 读取stations

`GET /biz/x/food/stores/{storeNo}/stations/{configNo}`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

能力依赖：ALL-04已实现。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| config | Station | 是 | 授权工位工作定义及发布指针 |

### FOOD-API-015 · 发布工位

`POST /biz/x/food/stores/{storeNo}/stations/{configNo}:publish`

权限与守卫：真实GOODS及获准门店；STATION开关独立；外部原身份先于本地事务核验；店级仲裁锁/CAS/actor绑定幂等；冻结定义与依赖、原商品身份且不含价格库存；完成回执先重放；历史内容不变。

能力依赖：ALL-03/04已实现，生效时间及门店范围内选择冻结修订；不表示订单厨房事务已接入。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 草稿版本 |
| effectiveAt | datetime | 是 | 生效时刻 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 冻结发布修订 |

### FOOD-API-016 · 菜品餐饮属性

`PUT /biz/x/food/stores/{storeNo}/dishes/{goodsNo}`

权限与守卫：商品同主体；缺version仅当餐饮扩展不存在时创建，存在则冲突；传version只允许CAS修改已存在扩展；基础图文价格库存仍在商品域；当前仅COUNT/EA整数份数展示；自定义imageRefs/WEIGHT待权威合同，明确拒绝。

能力依赖：已实现餐饮属性草稿维护；COUNT/EA展示、原商品图片沿用；不含称重、自定义媒资及可售发布。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| goodsNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| dishKind | string | 是 | SINGLE或BUNDLE或ADDON |
| prepSeconds | int32 | 是 | 非负 |
| presentation | DishPresentation | 是 | 餐饮展示覆盖 |
| measurement | MeasurementPolicy | 是 | 计量规则 |
| version | int64 | 否 | 创建时不传，修改时必须CAS |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| dish | Dish | 是 | 菜品 |

### FOOD-API-017 · 菜单组成与时段配置

`PUT /biz/x/food/menus/{menuNo}/definition`

权限与守卫：三个分段只替换自身；真实同主体当前店；CAS及actor绑定幂等审计Outbox同事务；原Goods/SKU事实先在本地事务外批量核验；分类及菜项ID不能借用别的餐牌；ALL/EXPLICIT、INHERIT/REPLACE严格区分；配方同店同SKU；时段按来源日期覆盖、左闭右开；不改历史。

能力依赖：ALL-01/02已实现工作组成、时段及上下文；发布另验依赖和冲突，不提供价格或成交授权。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| section | string | 是 | SCHEDULE或COMPOSITION或CONTEXT显式互斥分段 |
| version | int64 | 是 | 当前草稿CAS |
| weekSchedules | WeekScheduleInput[] | 否 | SCHEDULE必需 |
| exceptions | ExceptionInput[] | 否 | SCHEDULE必需 |
| validFrom | date | 否 | SCHEDULE来源起日 |
| validTo | date | 否 | SCHEDULE来源末日 |
| categories | MenuCategoryInput[] | 否 | COMPOSITION必需，空清空 |
| contexts | ContextInput[] | 否 | CONTEXT必需，空清空 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menu | Menu | 是 | 新工作CAS |
| schedule | MenuSchedule | 否 | 仅SCHEDULE分段回执 |
| composition | MenuComposition | 否 | 仅COMPOSITION分段回执 |
| contexts | ContextInput[] | 否 | 仅CONTEXT分段回执 |

### FOOD-API-018 · 套餐选择组配置

`PUT /biz/x/food/bundles/{skuNo}/definition`

权限与守卫：已实现；原GOODS及当前门店；原主SKU/成员/补差基准身份事务外验真；SERIALIZABLE双向禁止嵌套；业务号不可盗用或切换身份；数量精度/默认/容量校验，DIFF_BASE必须有基准；CAS/幂等/审计/Outbox原子；只替换工作定义。

能力依赖：已实现固定/自选套餐草稿；金额由原报价权威确定，正式库存展开后续。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 严格非负整数CAS |
| groups | BundleGroupInput[] | 是 | 完整组集合最多30 |
| choices | BundleChoiceInput[] | 是 | 完整选项集合最多100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | SKU |
| version | int64 | 是 | 新版本 |

### FOOD-API-019 · 配置选择项

`PUT /biz/x/food/option-groups/{groupNo}/items`

权限与守卫：原GOODS权限、可信主体及当前门店；V3规格引用64字符；原规格ENUM或主体有效配料工作头；循环/互斥矛盾/默认约束拒绝；CAS幂等审计Outbox同事务；保留外店及无法确认归属的原绑定；仅修改工作头。

能力依赖：已实现工作头选项与当前门店商品绑定，不代表发布或可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| groupNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| items | OptionItemInput[] | 是 | 最多100 |
| goodsNos | string[] | 是 | 当前获准店绑定完整集合最多100 |
| selectionRules | SelectionRules | 是 | 互斥依赖结构 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 组 |
| version | int64 | 是 | 新版本 |

### FOOD-API-020 · 配料组合配置

`PUT /biz/x/food/modifiers/{modifierNo}`

权限与守卫：已实现草稿管理；同主体CAS更新，kind不可改变；COMBO仅同主体有效ADDON成员，拒重复/嵌套；仅组合份数，不承担物料单位换算；SKU原权威主体门店校验。

能力依赖：已实现草稿管理；发布与收费成交待后续P1/P3。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| modifierNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 名称 |
| kind | string | 是 | ADDON或COMBO创建后不可改变 |
| salesSkuNo | string | 否 | 原计价SKU可显式清空 |
| components | ModifierComponentInput[] | 是 | ≤100成员及正数量 |
| version | int64 | 是 | 工作头CAS |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配料 |
| version | int64 | 是 | 新版本 |

### FOOD-API-021 · 工位路由配置

`PUT /biz/x/food/stores/{storeNo}/sku-routes/{skuNo}`

权限与守卫：原SKU同主体当前店；只允许同店启用工位；店级仲裁行锁；清空墓碑保留路由计数防ABA；actor绑定幂等及审计Outbox同事务；不改实存、不派发历史厨房任务。

能力依赖：ALL-04已实现路由配置，派工读取冻结发布需订单阶段集成。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| skuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 整个店SKU关系CAS，首次0 |
| routes | RouteInput[] | 是 | 最多100个stationNo及taskRole，空清空，不接受客户端routeVersion |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-022 · 沽清与恢复

`POST /biz/x/food/stores/{storeNo}/sales-restrictions`

权限与守卫：店SKU仲裁行锁；CLEAR只清指定版本；完成回执优先；过期按服务器时点恢复；餐饮额度通过既有OnlineQuotaPort本地原子预占消费释放一次，不重复建立实存账；UNKNOWN不释放；orderSource非空只允许POS/WAITER/KIOSK/QR/H5/APP/CHANNEL，fulfillment非空只允许DINE_IN/PICKUP/DELIVERY，CHANNEL显式履约只允许DELIVERY；null保留通配语义，非法值不得写审计/Outbox。

能力依赖：ALL-05已实现餐饮限制及在线额度；不表示物理库存或订单报价可用。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 同店原SKU |
| mode | string | 是 | SOLD_OUT或QUOTA或PREORDER_LIMIT或CLEAR |
| restrictionNo | string | 否 | 编辑或CLEAR对象 |
| version | int64 | 否 | 已有对象必需CAS |
| quota | decimal | 否 | 额度模式正decimal(18,6)，物理件数与交易计量另验 |
| businessDate | date | 否 | 适用营业日 |
| startsAt | datetime | 否 | 缺省当前 |
| expiresAt | datetime | 否 | 到期 |
| reason | string | 是 | 原因 |
| orderSource | string | 否 | 受控来源 |
| fulfillment | string | 否 | 履约 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| restrictionNo | string | 是 | 约束 |
| storeNo | string | 是 | 授权店 |
| skuNo | string | 是 | 原SKU |
| mode | string | 是 | SOLD_OUT或QUOTA或PREORDER_LIMIT |
| businessDate | date | 否 | 业务日 |
| quota | decimal | 否 | 额度 |
| reservedQty | decimal | 是 | 原子预占 |
| consumedQty | decimal | 是 | 已用 |
| startsAt | datetime | 是 | 开始 |
| expiresAt | datetime | 否 | 到期 |
| status | string | 是 | 当前时点状态含EXPIRED |
| reason | string | 是 | 原因 |
| orderSource | string | 否 | 来源过滤 |
| fulfillment | string | 否 | 履约过滤 |
| version | int64 | 是 | CAS |

### FOOD-API-023 · 顾客菜单视图

`GET /mp/x/food/catalog`

权限与守卫：服务器当前时间；仅已发布冻结定义及其时段来源履约；每菜项保留身份；当前原商品审核上架、原CN/CNY门店及特价和餐饮限制重新读取；未知价格及库存为null且不可成交；复杂选配/套餐/配方需权威报价；公开及顾客目录不暴露channelAccount账号、外部门店、pricePolicyRef或内部依赖快照，渠道上下文不能由用户伪造；不接受任意at未来预览。

能力依赖：ALL-06已实现保守当前展示；PLATFORM物理库存可明确读取，DUAL/INVENTORY或多市场歧义为UNKNOWN；没有报价保留禁成交，不保证下单。

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 公开门店或原顾客会话门店 |
| orderSource | string | 是 | H5公开或QR原顾客会话，不能伪造CHANNEL |
| fulfillment | string | 是 | H5仅PICKUP或DELIVERY，QR仅DINE_IN |
| sessionNo | string | 否 | QR必需，另需X-Food-Session头原会话凭据 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 获准店 |
| orderSource | string | 是 | 可信来源 |
| fulfillment | string | 是 | 履约 |
| asOf | datetime | 是 | 服务端当前时点 |
| menus | CatalogMenu[] | 是 | 生效发布 |
| items | CatalogItem[] | 是 | 保留菜项身份 |

### FOOD-API-024 · 建桌

`POST /biz/x/food/stores/{storeNo}/tables`

权限与守卫：主体门店及启用桌区一致；锁桌区仲裁行后创建；同店code精确唯一且归档后不复用；初始ACTIVE/IDLE、version0；不接受客户端占用状态；非空resourceNo在权威查询合同未齐时拒绝。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| areaNo | string | 是 | 桌区 |
| code | string | 是 | 门店唯一桌台编码 |
| name | string | 是 | 名称 |
| capacity | int32 | 是 | 正座位数 |
| sortNo | int32 | 是 | 排序 |
| resourceNo | string | 否 | 共享资源 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| table | Table | 是 | 桌台 |

### FOOD-API-025 · 桌码轮换

`POST /biz/x/food/tables/{tableNo}:rotate-token`

权限与守卫：原STORE权限及主体门店范围；桌区→桌台稳定锁和CAS；轮换撤销旧码及该桌旧会话；只保存SHA-256，明文仅首次响应；幂等收据不存凭证，同键重放CONFLICT且不再次轮换。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| expiresAt | datetime | 是 | 到期 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| token | string | 是 | 仅本次返回明文 |
| expiresAt | datetime | 是 | 到期 |

### FOOD-API-026 · 桌码换短会话

`POST /mp/x/food/sessions`

权限与守卫：桌码哈希校验后才解析主体门店，经原StoreDirectoryPort确认ACTIVE；签发最长30分钟且不超过桌码到期的TABLE_MENU会话；不自动绑定现有check。明文只首次返回，同键重放CONFLICT；设备认证合同未齐时非空deviceCredential拒绝。。

能力依赖：餐饮新增。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableToken | string | 是 | 桌码 |
| deviceCredential | string | 否 | 可验证设备凭据，不信任deviceNo自报 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sessionNo | string | 是 | 会话 |
| accessToken | string | 是 | 短期令牌 |
| expiresAt | datetime | 是 | 到期 |
| checkNo | string | 否 | 获准台账 |

### FOOD-API-027 · 创建预约

`POST /biz/x/food/stores/{storeNo}/reservations`

权限与守卫：目标沿共享resourceNo/hold权威做预约、释放和到店接力，资源取消不等于订金退款；当前共享资源合同未验收，此写入口未开放，不生成假hold引用。资源与代办授权。

能力依赖：共享预约订金契约。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| resourceNo | string | 是 | 资源 |
| startAt | datetime | 是 | 开始 |
| endAt | datetime | 是 | 结束 |
| partySize | int32 | 是 | 人数 |
| buyerNo | string | 否 | 顾客 |
| contactRef | string | 是 | 加密联系人 |
| depositQuoteRef | string | 否 | 订金报价 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string | 是 | 预约 |
| resourceHoldNo | string | 是 | 占位 |
| status | string | 是 | 状态 |

### FOOD-API-028 · 预约cancel

`POST /biz/x/food/reservations/{reservationNo}:cancel`

权限与守卫：目标沿共享resourceNo/hold权威做预约、释放和到店接力，资源取消不等于订金退款；当前共享资源合同未验收，此写入口未开放，不生成假hold引用。预约权限及版本；订金退款/没收单独走资金审批合同，资源取消不意味着已退款。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 取消或爽约原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 资源释放和资金处理进度 |

### FOOD-API-029 · 预约no-show

`POST /biz/x/food/reservations/{reservationNo}:no-show`

权限与守卫：目标沿共享resourceNo/hold权威做预约、释放和到店接力，资源取消不等于订金退款；当前共享资源合同未验收，此写入口未开放，不生成假hold引用。预约权限及版本；订金退款/没收单独走资金审批合同，资源取消不意味着已退款。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 取消或爽约原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 资源释放和资金处理进度 |

### FOOD-API-030 · 预约arrive

`POST /biz/x/food/reservations/{reservationNo}:arrive`

权限与守卫：目标沿共享resourceNo/hold权威做预约、释放和到店接力，资源取消不等于订金退款；当前共享资源合同未验收，此写入口未开放，不生成假hold引用。新占用失败保留原预约资源；到店只生成一个台账；原预约和资金凭据保留。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 预约版本 |
| tableNos | string[] | 是 | 占桌 |
| tableVersions | TableVersionInput[] | 是 | 资源版本 |
| partySize | int32 | 是 | 实际人数 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作 |
| status | string | 是 | 状态 |
| checkNo | string | 否 | 开台结果 |

### FOOD-API-031 · 开台

`POST /biz/x/food/stores/{storeNo}/checks`

权限与守卫：原VERIFY权限及门店范围；所有目标桌完整CAS，桌区→桌台有序锁后原子新增check/occupancy/seat/audit/outbox；V2持久化EXCLUSIVE/SHARED；营业日复用门店策略。当前只开放无交易PREPAY，POSTPAY及未经验证的预约/员工/顾客引用拒绝；空tableNos支持无桌接待。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNos | string[] | 是 | 空为无桌 |
| partySize | int32 | 是 | 正人数 |
| payMode | string | 是 | PREPAY或POSTPAY |
| reservationNo | string | 否 | 来源预约 |
| tableVersions | TableVersionInput[] | 是 | 所有目标桌版本 |
| sharingMode | string | 是 | EXCLUSIVE或SHARED |
| seats | SeatInput[] | 是 | 座位 |
| serviceEmployeeNo | string | 否 | 获准员工 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| check | Check | 是 | 台账 |

### FOOD-API-032 · 台账桌台查询

`GET /biz/x/food/stores/{storeNo}/checks`

权限与守卫：原ORDER_VIEW权限；主体及当前门店固定过滤，状态/营业日与records/total同口径，分页1/20最大100。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| status | string | 否 | 过滤 |
| businessDate | date | 否 | 营业日 |
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Check[] | 是 | 列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 当前页 |
| size | int32 | 是 | 每页条数 |

### FOOD-API-033 · 台账transfer

`POST /biz/x/food/checks/{checkNo}:transfer`

权限与守卫：原VERIFY权限、check CAS及全部来源/目标桌CAS；当前仅无批次/结账/退餐/权益/未完成草稿的PREPAY台账；先核验并占完整目标再结束原占用，同主库事务回滚不丢原桌；approvalRef未核验时拒绝；不搬订单或资金。 外部resourceNo/resourceClaimNo或未完成资源意图拒绝本地变更。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |
| targetTableNos | string[] | 是 | 转入桌台 |
| tableVersions | TableVersionInput[] | 是 | 所有来源目标桌版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 转台受理及恢复结果 |

### FOOD-API-034 · 合并就餐服务台账

`POST /biz/x/food/checks/{checkNo}:merge`

权限与守卫：目标保留原批次及资金归属，按完整membership与冻结合同合拆；当前ORD-03/PAY-01未齐，此写入口未开放。仅合并服务/占用展示归属；原批次check及核心资金归属不改；结账冻结/未知资金时拒绝；合账收款另走settlement。

能力依赖：餐饮桌台操作意图及追加归属；资金合账取决于核心收银。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 来源版本 |
| targetCheckNo | string | 是 | 目标台账 |
| targetVersion | int64 | 是 | 目标版本 |
| tableVersions | TableVersionInput[] | 是 | 桌台版本 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 协作结果 |

### FOOD-API-035 · 拆分就餐服务台账

`POST /biz/x/food/checks/{checkNo}:split`

权限与守卫：目标保留原批次及资金归属，按完整membership与冻结合同合拆；当前ORD-03/PAY-01未齐，此写入口未开放。仅调整批次服务归属，不改成交和资金；需锁批次且拒绝冻结/未知资金范围；按菜拆账走核心应收分摊。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 来源版本 |
| targetCheckNo | string | 是 | 目标台账 |
| targetVersion | int64 | 是 | 目标版本 |
| selectedBatchNos | string[] | 是 | 允许调整展示归属的完整批次 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 协作结果 |

### FOOD-API-036 · 台账clear

`POST /biz/x/food/checks/{checkNo}:clear`

权限与守卫：原VERIFY权限及check CAS；仅释放本check明确occupancyNos，空数组只允许本来无桌；任何交易事实或未完成/未知草稿拒绝普通清台。当前只验收无交易接待，最后释放记VOIDED/RELEASED，其他客组保留；已清洁空桌IDLE；不声称资金结清或支持异常放行。 外部resourceNo/resourceClaimNo或未完成资源意图拒绝本地变更。 部分清台先锁全部现有桌位，按原人数重分配剩余占座；剩余容量不足则整笔拒绝。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |
| occupancyNos | string[] | 是 | 结束占用不删除未收应收 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-037 · 购物车读取

`GET /biz/x/food/carts/{cartNo}`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cart | Cart | 是 | 获准购物车 |

### FOOD-API-038 · 创建购物车

`POST /biz/x/food/stores/{storeNo}/carts`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 真实OPEN台账 |
| sessionNo | string | 否 | 已获准会话 |
| buyerNo | string | 否 | 当前拒绝客户端代客 |
| orderSource | string | 是 | POS或WAITER |
| fulfillment | string | 是 | 履约 |
| scheduledAt | datetime | 否 | 当前拒绝 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cart | Cart | 是 | 唯一OPEN共享车 |

### FOOD-API-039 · 替换草稿行

`PUT /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| sourceLineRef | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| line | OrderLineInput | 是 | 完整替换源行 |
| version | int64 | 是 | 当前整个车版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| version | int64 | 是 | 整体新版本 |

### FOOD-API-040 · 归档购物车草稿行

`POST /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| sourceLineRef | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 当前整个车版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| version | int64 | 是 | 整体新版本 |

### FOOD-API-041 · 点单报价

`POST /biz/x/food/stores/{storeNo}/quotes`

权限与守卫：报价绑定主体门店/车版本/源行内容/规则版本；确认价格变化；服务端最终重验可售及核心库存能力。

能力依赖：已有报价适配；选配计量等分项门控。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 台账 |
| buyerNo | string | 否 | 真实买家 |
| fulfillment | string | 是 | 履约 |
| orderSource | string | 是 | 经认证来源 |
| lines | OrderLineInput[] | 是 | 源行 |
| cartNo | string | 是 | 源车 |
| cartVersion | int64 | 是 | 车版本 |
| scheduledAt | datetime | 否 | 出餐时点 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| quote | Quote | 是 | 权威报价 |

### FOOD-API-042 · 提交点餐批次

`POST /biz/x/food/stores/{storeNo}/batches`

权限与守卫：提交必须匹配报价指纹、源行集合和车版本；冻结selected行；绑定核心成功后仅清理这些行；UNKNOWN同键恢复；payMode由获准策略校验。

能力依赖：当前OrderService适配及通用缺口。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| cartVersion | int64 | 是 | 版本 |
| selectedLineRefs | string[] | 是 | 本次行 |
| quoteNo | string | 是 | 已确认报价 |
| payMode | string | 是 | 先后付 |
| splitByPerson | boolean | 是 | 预分单 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batch | Batch | 是 | 绑定或处理中 |

### FOOD-API-043 · 批次结果查询

`GET /biz/x/food/batches/{batchNo}`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| detail | BatchDetail | 是 | 原批次及资金履约独立状态 |

### FOOD-API-044 · 工位队列

`GET /biz/x/food/stores/{storeNo}/kitchen/tasks`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| stationNo | string | 是 | 工位 |
| status | string | 否 | 状态 |
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Task[] | 是 | 任务 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 当前页 |
| size | int32 | 是 | 每页条数 |

### FOOD-API-045 · 制作任务start

`POST /biz/x/food/tasks/{taskNo}:start`

权限与守卫：获准工位/传菜权限；只推进合法前态数量，CAS及累计数量守恒；重放不重复加量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 是 | 本次正数量 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 数量事实 |

### FOOD-API-046 · 制作任务ready

`POST /biz/x/food/tasks/{taskNo}:ready`

权限与守卫：获准工位/传菜权限；只推进合法前态数量，CAS及累计数量守恒；重放不重复加量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 是 | 本次正数量 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 数量事实 |

### FOOD-API-047 · 制作任务serve

`POST /biz/x/food/tasks/{taskNo}:serve`

权限与守卫：获准工位/传菜权限；只推进合法前态数量，CAS及累计数量守恒；重放不重复加量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 是 | 本次正数量 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 数量事实 |

### FOOD-API-048 · 制作任务hold

`POST /biz/x/food/tasks/{taskNo}:hold`

权限与守卫：获准工位/服务权限；hold/fire仅操作尚未制作部分，urge只记录催菜不加制作数量或销量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| reason | string | 否 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 状态事实 |

### FOOD-API-049 · 制作任务fire

`POST /biz/x/food/tasks/{taskNo}:fire`

权限与守卫：获准工位/服务权限；hold/fire仅操作尚未制作部分，urge只记录催菜不加制作数量或销量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| reason | string | 否 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 状态事实 |

### FOOD-API-050 · 制作任务urge

`POST /biz/x/food/tasks/{taskNo}:urge`

权限与守卫：获准工位/服务权限；hold/fire仅操作尚未制作部分，urge只记录催菜不加制作数量或销量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| reason | string | 否 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 状态事实 |

### FOOD-API-051 · 制作任务void

`POST /biz/x/food/tasks/{taskNo}:void`

权限与守卫：工位/传菜/退餐分别鉴权；累计数量守恒；重做审批不直接改资金。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 是 | 正撤销量 |
| reason | string | 是 | 原因 |
| returnNo | string | 是 | 已获准退餐凭据 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 撤菜事实 |

### FOOD-API-052 · 制作任务remake

`POST /biz/x/food/tasks/{taskNo}:remake`

权限与守卫：工位/传菜/退餐分别鉴权；累计数量守恒；重做审批不直接改资金。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 是 | 正重做量 |
| reason | string | 是 | 原因 |
| approvalRef | string | 是 | 重做授权 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 原任务 |
| eventNo | string | 是 | 重做事实 |
| newTaskNo | string | 是 | 关联重做任务 |

### FOOD-API-053 · 创建course

`POST /biz/x/food/checks/{checkNo}/courses`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 道次名 |
| sequenceNo | int32 | 是 | 顺序 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| courseNo | string | 是 | 道次 |
| status | string | 是 | HELD |
| version | int64 | 是 | 版本 |

### FOOD-API-054 · 道次叫起

`POST /biz/x/food/courses/{courseNo}:fire`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| courseNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| seatNos | string[] | 是 | 座位范围 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-055 · 交付及配送交接

`POST /biz/x/food/batches/{batchNo}:handover`

权限与守卫：履约权限；核销码防重复。

能力依赖：堂食通用交付缺口，其余复用已有履约。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| kind | string | 是 | DINE_IN或PICKUP或DELIVERY |
| coreFulfillmentRef | string | 否 | 核心履约凭据 |
| lines | DeliveryLineInput[] | 是 | foodLineNo及交付qty |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作 |
| coreDeliveryRef | string | 是 | 正式交付引用 |
| status | string | 是 | 结果 |

### FOOD-API-056 · 冻结结账范围

`POST /biz/x/food/stores/{storeNo}/settlements`

权限与守卫：收银权限及核心范围独占防双收。

能力依赖：跨单/部分分摊取决于核心收银。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| items | ScopeItem[] | 是 | 范围 |
| currency | string | 是 | 币种 |
| quoteNo | string | 是 | 权威应收 |
| checkVersions | VersionInput[] | 是 | checkNo和version |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlement | Settlement | 是 | 结账 |

### FOOD-API-057 · 读取预结账

`GET /biz/x/food/settlements/{settlementNo}`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlement | Settlement | 是 | 当前状态 |
| confirmedAmount | Money | 是 | 核心已确认 |
| pendingIntentNos | string[] | 是 | 未知请求 |

### FOOD-API-058 · 结账pay

`POST /biz/x/food/settlements/{settlementNo}:pay`

权限与守卫：服务端查询核心资金；不接受markPaid。现金仅biz收银权限且班次/完整单根影响集匹配，找零由服务端计算；顾客端只获准线上工具；未知状态按原意图恢复。

能力依赖：核心支付/现金确认；混合与跨根需扩展。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| tool | string | 是 | 支付工具 |
| amount | Money | 是 | 本次金额 |
| corePaymentToken | string | 否 | 临时凭证 |
| shiftNo | string | 否 | 现金时必填当班 |
| cashTendered | Money | 否 | 现金时必填实收 |
| cashEvidenceRef | string | 否 | 现金凭据引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| intentNo | string | 是 | 请求意图 |
| status | string | 是 | 含UNKNOWN |
| coreReference | string | 否 | 核心结果 |
| paymentAction | object | 否 | 核心返回端上支付动作 |

### FOOD-API-059 · 结账cancel

`POST /biz/x/food/settlements/{settlementNo}:cancel`

权限与守卫：仅无成功/未知不可撤销资金时由核心释放；与付款回调串行仲裁；不得仅按客户端状态解冻。

能力依赖：核心支付/现金确认；混合与跨根需扩展。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 取消冻结范围结果 |

### FOOD-API-060 · 结账finalize

`POST /biz/x/food/settlements/{settlementNo}:finalize`

权限与守卫：scopeHash与冻结版本一致；核心结清且无未知资金；不能接受markPaid或以上菜/清台作为已付证据。

能力依赖：核心支付/现金确认；混合与跨根需扩展。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| scopeHash | string | 是 | 冻结范围指纹 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlement | Settlement | 是 | 核心已结清且无未知请求时终结 |

### FOOD-API-061 · 收费优惠调整申请

`POST /biz/x/food/settlements/{settlementNo}/adjustments`

权限与守卫：费用小费分期门控及授权审批。

能力依赖：核心应收调整或AR能力。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| kind | string | 是 | DISCOUNT或FEE或TIP或AR_TRANSFER |
| amount | Money | 是 | 申请金额 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |
| coreQuoteRef | string | 是 | 报价 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| intentNo | string | 是 | 意图 |
| coreAdjustmentRef | string | 否 | 核心调整 |
| status | string | 是 | 结果 |

### FOOD-API-062 · 退餐申请

`POST /biz/x/food/stores/{storeNo}/returns`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 台账 |
| items | ReturnItem[] | 是 | 逐行退量 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string | 是 | 申请 |
| status | string | 是 | REQUESTED |
| version | int64 | 是 | 版本 |

### FOOD-API-063 · 退餐审批执行

`POST /biz/x/food/returns/{returnNo}:execute`

权限与守卫：申请审批责任分离；当前部分退款禁用。

能力依赖：核心取消/售后；行级调整退款缺口。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| approvalRef | string | 是 | 共享审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作 |
| financialStatus | string | 是 | 财务轨道 |
| kitchenStatus | string | 是 | 厨房轨道 |
| coreCaseRef | string | 否 | 售后 |

### FOOD-API-064 · 退餐查询

`GET /biz/x/food/returns/{returnNo}`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string | 是 | 申请 |
| items | ReturnItem[] | 是 | 明细 |
| status | string | 是 | 申请状态 |
| financialStatus | string | 是 | 资金状态 |
| coreCaseRef | string | 否 | 核心售后 |
| version | int64 | 是 | 版本 |
| kitchenStatus | string | 是 | 厨房轨道 |

### FOOD-API-065 · 提交打印

`POST /biz/print/jobs`

权限与守卫：设备与场景权限；金额内容由服务端组装不信客户端。

能力依赖：共享打印待建设。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceEventNo | string | 是 | 事件 |
| scene | string | 是 | 受控场景 |
| sourceRef | string | 是 | 业务凭据 |
| deviceNos | string[] | 否 | 授权设备 |
| templateVersion | int64 | 否 | 模板版 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| jobNos | string[] | 是 | 分单任务 |
| status | string | 是 | 受理 |

### FOOD-API-066 · 打印查询

`GET /biz/print/jobs/{jobNo}`

权限与守卫：门店打印范围。

能力依赖：共享打印待建设。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| jobNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| jobNo | string | 是 | 任务 |
| status | string | 是 | 含UNKNOWN |
| attempts | object[] | 是 | 次数渠道任务回执 |
| originalJobNo | string | 否 | 原任务 |

### FOOD-API-067 · 人工补打

`POST /biz/print/jobs/{jobNo}:reprint`

权限与守卫：补打权限及审计不再次派工。

能力依赖：共享打印待建设。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| jobNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reason | string | 是 | 原因 |
| deviceNo | string | 否 | 获准新设备 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| jobNo | string | 是 | 新补打任务 |
| originalJobNo | string | 是 | 原任务 |
| status | string | 是 | 状态 |

### FOOD-API-068 · 开班

`POST /biz/x/food/stores/{storeNo}/cash-shifts`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| drawerNo | string | 是 | 钱箱 |
| currency | string | 是 | 币种 |
| openingMinor | int64 | 是 | 非负备用金 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| shiftNo | string | 是 | 班次 |
| status | string | 是 | OPEN |
| version | int64 | 是 | 版本 |

### FOOD-API-069 · 非销售现金操作

`POST /biz/x/food/cash-shifts/{shiftNo}/entries`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| shiftNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| kind | string | 是 | FLOAT_IN或EXPENSE或DEPOSIT或TRANSFER |
| amountMinor | int64 | 是 | 正金额 |
| direction | string | 是 | IN或OUT |
| voucherRef | string | 是 | 凭证 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| entryNo | string | 是 | 事实 |
| status | string | 是 | 结果 |

### FOOD-API-070 · 实盘与交班

`POST /biz/x/food/cash-shifts/{shiftNo}:count`

权限与守卫：盲盘前不泄露应有金额；差异须审批。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| shiftNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| countedMinor | int64 | 是 | 实盘 |
| version | int64 | 是 | 班次版本 |
| reason | string | 否 | 差异说明 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| countNo | string | 是 | 盘点 |
| differenceMinor | int64 | 否 | 获准后展示差异 |
| status | string | 是 | 状态 |

### FOOD-API-071 · 接班确认

`POST /biz/x/food/cash-shifts/{shiftNo}:handover`

权限与守卫：接班人登录确认不得客户端传acceptedBy冒充。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| shiftNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| countNo | string | 是 | 实盘 |
| version | int64 | 是 | 版本 |
| approvalRef | string | 否 | 差异审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| shiftNo | string | 是 | 班次 |
| status | string | 是 | HANDED_OVER |

### FOOD-API-072 · 营业日预结

`POST /biz/x/food/stores/{storeNo}/closes`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| businessDate | date | 是 | 营业日 |
| currency | string | 是 | 币种 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| close | Close | 是 | 草稿或处理中 |

### FOOD-API-073 · 日结查询

`GET /biz/x/food/closes/{closeNo}`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| closeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| close | Close | 是 | 版本快照 |

### FOOD-API-074 · 异常结转申请

`POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:carry`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| closeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| exceptionNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| targetBusinessDate | date | 是 | 下一归属日 |
| ownerNo | string | 是 | 责任人 |
| reason | string | 是 | 原因 |
| approvalRef | string | 是 | 审批 |
| version | int64 | 是 | 日结版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-075 · 签核日结

`POST /biz/x/food/closes/{closeNo}:sign`

权限与守卫：日结签核权限和责任分离；关键未知仅列例外不伪造成功。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| closeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| snapshotHash | string | 是 | 待签指纹 |
| watermark | string | 是 | 待签水位 |
| approvalRef | string | 否 | 强制例外审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| close | Close | 是 | 正式版本 |

### FOOD-API-076 · 关账后更正

`POST /biz/x/food/closes/{closeNo}/adjustments`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| closeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| coreReference | string | 是 | 已发生核心调整 |
| currentBusinessDate | date | 是 | 发生日 |
| reason | string | 是 | 原因 |
| approvalRef | string | 是 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| adjustmentNo | string | 是 | 更正 |
| newCloseNo | string | 否 | 重审版本 |
| status | string | 是 | 状态 |

### FOOD-API-077 · 经营指标报表

`GET /biz/x/food/reports/{reportCode}`

权限与守卫：reportCode受控目录；门店币种日期权限；最大同步日期范围按配置。

能力依赖：已有shop-report适配＋餐饮投影新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reportCode | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNos | string[] | 是 | 授权门店 |
| from | date | 是 | 起日 |
| to | date | 是 | 止日不含 |
| timeBasis | string | 是 | BUSINESS_DATE或NATURAL_DATE |
| currency | string | 是 | 币种 |
| metricVersion | int64 | 否 | 口径 |
| stationNo | string | 否 | 工位 |
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| report | Report | 是 | 报表 |

### FOOD-API-078 · 异步报表导出

`POST /biz/x/food/report-exports`

权限与守卫：导出权限水印脱敏，按查询权限重新校验。

能力依赖：餐饮新增。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reportCode | string | 是 | 受控报表 |
| storeNos | string[] | 是 | 授权门店 |
| from | date | 是 | 起日 |
| to | date | 是 | 止日不含 |
| currency | string | 是 | 币种 |
| timeBasis | string | 是 | 日期基准 |
| format | string | 是 | CSV或XLSX |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| exportNo | string | 是 | 任务 |
| status | string | 是 | 状态 |

### FOOD-API-079 · 导出结果

`GET /biz/x/food/report-exports/{exportNo}`

权限与守卫：本人或获准导出范围。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| exportNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| exportNo | string | 是 | 任务 |
| status | string | 是 | 状态 |
| downloadUrl | string | 否 | 短时签名链接 |
| expiresAt | datetime | 否 | 过期 |

### FOOD-API-080 · 重算投影

`POST /ops/x/food/report-rebuilds`

权限与守卫：工单授权只重建投影不重开日结。

能力依赖：餐饮新增。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 门店 |
| from | date | 是 | 起日 |
| to | date | 是 | 止日不含 |
| metricVersion | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| runNo | string | 是 | 任务 |
| status | string | 是 | 状态 |

### FOOD-API-081 · 平台能力配置

`PUT /ops/x/food/stores/{storeNo}/capabilities`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| capabilities | string[] | 是 | 允许能力 |
| version | int64 | 是 | 配置版 |
| workOrderRef | string | 是 | 授权工单 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-082 · 外部映射

`PUT /biz/x/food/integrations/{provider}/mappings`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等；映射按账号范围/修订追加，旧订单不按当前映射重解释。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| provider | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 门店 |
| objectType | string | 是 | 受控类型 |
| externalNo | string | 是 | 外部编号 |
| internalNo | string | 是 | 内部编号 |
| authority | string | 是 | 权威角色 |
| channelBindingNo | string | 否 | 外部账号绑定，本地省略 |
| mappingRevisionNo | string | 是 | 不可变映射修订 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-083 · 接入异常查询

`GET /ops/x/food/integration-exceptions`

权限与守卫：工单范围脱敏诊断。

能力依赖：餐饮新增。

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 授权门店 |
| status | string | 否 | 状态 |
| page | int32 | 否 | 页码 |
| size | int32 | 否 | 每页条数 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | object[] | 是 | 编号来源类型状态责任人 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 当前页 |
| size | int32 | 是 | 每页条数 |

### FOOD-API-084 · 边缘增量认领

`POST /internal/food/sync-batches`

权限与守卫：服务身份签名租约顺序校验；事件类型白名单。

能力依赖：边缘导入正式合同待实施。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 门店 |
| edgeNo | string | 是 | 节点 |
| epoch | int64 | 是 | 代次 |
| sequenceFrom | int64 | 是 | 起点 |
| sequenceTo | int64 | 是 | 终点 |
| payloadHash | string | 是 | 签名摘要 |
| events | object[] | 是 | 事件ID类型来源号版本与冻结事实 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| syncNo | string | 是 | 批次 |
| status | string | 是 | 状态 |
| acceptedThrough | int64 | 是 | 连续接收水位 |
| conflictRefs | string[] | 是 | 冲突 |

### FOOD-API-085 · 自助权益入场

`POST /biz/x/food/checks/{checkNo}/entitlements`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| coreOrderRef | string | 是 | 有效消费单 |
| personClass | string | 是 | ADULT或CHILD或OTHER |
| persons | int32 | 是 | 人数 |
| ruleVersion | int64 | 是 | 门店规则 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| entitlementNo | string | 是 | 权益 |
| startsAt | datetime | 是 | 开始 |
| expiresAt | datetime | 是 | 到期 |
| version | int64 | 是 | 版本 |

### FOOD-API-086 · 续时及最后点单校验

`POST /biz/x/food/entitlements/{entitlementNo}:extend`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| entitlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| minutes | int32 | 是 | 正时长 |
| coreAdjustmentRef | string | 是 | 正式收费或减免 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-087 · 宴会计划

`POST /biz/x/food/stores/{storeNo}/banquet-plans`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string | 是 | 预约 |
| guaranteedPersons | int32 | 是 | 保证人数 |
| menuVersion | int64 | 是 | 餐牌 |
| coreQuoteRef | string | 是 | 合同价 |
| depositRef | string | 否 | 订金 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| planNo | string | 是 | 计划 |
| status | string | 是 | 状态 |
| version | int64 | 是 | 版本 |

### FOOD-API-088 · 宴会人数及加席

`POST /biz/x/food/banquet-plans/{planNo}:confirm-attendance`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| planNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| actualPersons | int32 | 是 | 实际人数 |
| coreQuoteRef | string | 是 | 加席调整报价 |
| reason | string | 否 | 变化原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-089 · 寄存建账

`POST /biz/x/food/stores/{storeNo}/custodies`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ownerRef | string | 是 | 客户 |
| skuNo | string | 是 | 酒水 |
| qty | decimal | 是 | 数量 |
| unit | string | 是 | 单位 |
| expiresAt | datetime | 是 | 期限 |
| voucherRef | string | 是 | 凭证 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| custodyNo | string | 是 | 编号 |
| version | int64 | 是 | 版本 |

### FOOD-API-090 · 寄存取出及报损

`POST /biz/x/food/custodies/{custodyNo}/entries`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| custodyNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| kind | string | 是 | WITHDRAW或LOSS |
| qty | decimal | 是 | 正数量 |
| version | int64 | 是 | 版本 |
| reason | string | 否 | 报损原因 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-091 · 房账转挂

`POST /biz/x/food/settlements/{settlementNo}/room-charges`

权限与守卫：房账权限不含支付成功确认。

能力依赖：核心应收与PMS契约。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| propertyNo | string | 是 | 酒店 |
| roomRef | string | 是 | 已校验房账 |
| guestAuthorization | string | 是 | 授权凭据 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| intentNo | string | 是 | 意图 |
| status | string | 是 | 含UNKNOWN |
| externalRef | string | 否 | PMS结果 |

### FOOD-API-092 · 桌区列表

`GET /biz/x/food/stores/{storeNo}/table-areas`

权限与守卫：原canAnyBiz(STORE,ORDER_VIEW)读取桌区/桌台配置元数据；配置写入仍需STORE。客组占用沿获准check详情，不在Table配置对象内嵌；主体门店及原始请求头继续一致核验。主体门店对象一致；列表与total同时按主体及当前门店过滤；page默认1、size默认20最大100；status省略含ACTIVE和DISABLED；按sortNo、areaNo升序；不暴露物理id。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| status | string | 否 | 状态 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | TableArea[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-093 · 创建桌区

`POST /biz/x/food/stores/{storeNo}/table-areas`

权限与守卫：主体门店对象一致；原STORE权限、可信actor及幂等；code/name去两端空白，同店code精确唯一且不可修改，名称可重名；初始ACTIVE、version沿平台0；不依赖支付库存。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| code | string | 是 | 店内唯一 |
| name | string | 是 | 名称 |
| sortNo | int32 | 是 | 排序 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| area | TableArea | 是 | 桌区 |

### FOOD-API-094 · 编辑桌区

`PUT /biz/x/food/table-areas/{areaNo}`

权限与守卫：主体及当前门店与对象一致；原STORE权限、CAS版本及幂等；只改name和sortNo，保留code及归属；摘要包含areaNo与version；不暴露物理id。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| areaNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 名称 |
| sortNo | int32 | 是 | 排序 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| area | TableArea | 是 | 桌区 |

### FOOD-API-095 · 桌区启停

`POST /biz/x/food/table-areas/{areaNo}:set-status`

权限与守卫：主体及当前门店与对象一致；原STORE权限、CAS版本及幂等；有启用桌台或活动占用禁止停用；reason非空最长255并写原审计；同状态版本正确时无变更成功，不级联桌台或清台。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| areaNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| status | string | 是 | ACTIVE或DISABLED |
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| area | TableArea | 是 | 桌区 |

### FOOD-API-096 · 桌台列表

`GET /biz/x/food/stores/{storeNo}/tables`

权限与守卫：原canAnyBiz(STORE,ORDER_VIEW)读取桌区/桌台配置元数据；配置写入仍需STORE。客组占用沿获准check详情，不在Table配置对象内嵌；主体门店及原始请求头继续一致核验。主体及当前门店过滤records与total；默认page1/size20最大100；areaNo/status/turnoverState及字面关键词过滤；按sortNo/tableNo稳定排序；排除归档，不以支付状态代替桌态。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| areaNo | string | 否 | 桌区 |
| status | string | 否 | 配置状态 |
| turnoverState | string | 否 | 资源状态 |
| keyword | string | 否 | 编码名称 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Table[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-097 · 桌台详情

`GET /biz/x/food/tables/{tableNo}`

权限与守卫：原canAnyBiz(STORE,ORDER_VIEW)读取桌区/桌台配置元数据；配置写入仍需STORE。客组占用沿获准check详情，不在Table配置对象内嵌；主体门店及原始请求头继续一致核验。主体及当前门店与对象一致；沿原门店目录核验；归档不显示，不暴露物理id。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| table | Table | 是 | 桌台详情 |

### FOOD-API-098 · 编辑桌台

`PUT /biz/x/food/tables/{tableNo}`

权限与守卫：主体及当前门店与对象一致；原STORE权限、CAS版本和幂等；按编号锁原/目标桌区再锁桌台并复核；移区目标须启用，有活动占用禁止移区缩容；code及归属不改。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| areaNo | string | 是 | 同店桌区 |
| name | string | 是 | 名称 |
| capacity | int32 | 是 | 正容量 |
| sortNo | int32 | 是 | 排序 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| table | Table | 是 | 桌台 |

### FOOD-API-099 · 桌台启停

`POST /biz/x/food/tables/{tableNo}:set-status`

权限与守卫：主体及当前门店与对象一致；原STORE权限、CAS版本和幂等；锁桌区再桌台；重新启用要求桌区启用，有活动占用禁止停用；同状态且版本正确无变更成功；reason非空最长255。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| status | string | 是 | ACTIVE或DISABLED |
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| table | Table | 是 | 桌台 |

### FOOD-API-100 · 归档桌台

`POST /biz/x/food/tables/{tableNo}:archive`

权限与守卫：主体及当前门店与对象一致；原STORE权限、CAS版本和幂等；先停用且无活动占用；锁桌区再桌台并CAS逻辑归档；code唯一键及历史保留；原key重放Operation回执；reason非空最长255。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 归档记录 |

### FOOD-API-101 · 吊销桌码

`POST /biz/x/food/tables/{tableNo}:revoke-token`

权限与守卫：原STORE权限、主体门店及桌台CAS；同事务撤销有效码及该桌旧会话，不取消订单；安全操作收据可重放，不返回凭证。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 桌台版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 吊销结果 |

### FOOD-API-102 · 就餐台账详情

`GET /biz/x/food/checks/{checkNo}`

权限与守卫：原ORDER_VIEW权限，台账主体门店与当前门店一致；返回该check的活动占用和座位，不混入同桌其他客组。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| check | Check | 是 | 台账 |
| seats | SeatInput[] | 是 | 座位 |
| occupancies | Occupancy[] | 是 | 占用 |

### FOOD-API-103 · 修改人数与座位

`PUT /biz/x/food/checks/{checkNo}/seats`

权限与守卫：原VERIFY权限及check CAS；共用稳定容量锁，保留座位号/标签历史，创建省略seatNo由服务端发号，已有seatNo必须属于本check；未验证guestRef拒绝。当前有正式交易或未完成/未知草稿则拒绝，等待成交座位合同；不挤占他组座位。 外部resourceNo/resourceClaimNo或未完成资源意图拒绝本地变更。 无交易座位改名保留seatNo与变更前后快照；标签唯一冲突整笔回滚。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 台账版本 |
| partySize | int32 | 是 | 人数 |
| seats | SeatInput[] | 是 | 完整座位 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| check | Check | 是 | 台账 |

### FOOD-API-104 · 并入桌台

`POST /biz/x/food/checks/{checkNo}:combine-tables`

权限与守卫：原VERIFY权限、check CAS及新增桌CAS；同check并用多桌，共用有序资源锁与容量仲裁；当前限无交易台账，同事务完成并可按原operationNo查询；不合并资金/订单。 外部resourceNo/resourceClaimNo或未完成资源意图拒绝本地变更。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 台账版本 |
| tableVersions | TableVersionInput[] | 是 | 新增桌台 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 并桌结果 |

### FOOD-API-105 · 同桌新开独立台账

`POST /biz/x/food/tables/{tableNo}:share`

权限与守卫：原VERIFY权限、桌版本和SHARED容量原子仲裁；EXCLUSIVE占用即使桌容量扩大仍拒绝拼桌；新check独立，不继承原客组/购物车/支付权限；当前只PREPAY无交易接待。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 桌版本 |
| partySize | int32 | 是 | 正人数 |
| payMode | string | 是 | 获准先后付 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| check | Check | 是 | 新独立台账 |

### FOOD-API-106 · 桌台操作恢复查询

`GET /biz/x/food/table-operations/{operationNo}`

权限与守卫：原ORDER_VIEW及主体门店范围；读取真实fnb_table_operation及冻结结果版本，原operationNo结果稳定。当前本地无交易拓扑仅APPLIED，不伪造外部资源恢复成功。 既存未完成操作返回真实持久化状态及操作版本，不伪造已落定的check版本。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 受权操作进度 |

### FOOD-API-107 · 预约列表

`GET /biz/x/food/stores/{storeNo}/reservations`

权限与守卫：原ORDER_VIEW及主体门店范围；from/to为UTC毫秒半开窗口，按`startAt < to`且`endAt > from`重叠筛选；起止合法、状态白名单与分页同过滤；不返回联系人或buyer，depositRef仅引用不表示订金已结。创建/释放仍待共享资源合同。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| from | datetime | 是 | 起含 |
| to | datetime | 是 | 止不含 |
| status | string | 否 | 状态 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Reservation[] | 是 | 预约 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-108 · 就餐操作历史

`GET /biz/x/food/checks/{checkNo}/actions`

权限与守卫：原ORDER_VIEW及台账主体门店范围；真实fnb_action_log分页，保留可信操作人及原因，不输出令牌、联系人或幂等原文。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | ActionLog[] | 是 | 获准审计 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-109 · 餐牌列表

`GET /biz/x/food/stores/{storeNo}/menus`

权限与守卫：原GOODS权限/BizContext/StoreDirectory与实际同主体门店绑定；未知字段拒绝；写操作CAS/幂等/审计/Outbox同事务；timezone与当前生效门店策略一致；只写单店无override头，多店编辑409；完成回执优先重放；keyword字面包含按数据库排序规则匹配，%/_非通配符；按menuNo稳定排序后真实分页。

能力依赖：CAT-02A已实现餐牌头管理；时段规则、菜项与发布仍待后续，不表示可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| status | string | 否 | 状态 |
| keyword | string | 否 | 名称 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Menu[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-110 · 编辑餐牌头

`PUT /biz/x/food/menus/{menuNo}`

权限与守卫：原GOODS权限/BizContext/StoreDirectory与实际同主体门店绑定；未知字段拒绝；写操作CAS/幂等/审计/Outbox同事务；timezone与当前生效门店策略一致；只写单店无override头，多店编辑409；完成回执优先重放。

能力依赖：已实现餐牌工作定义管理；发布与历史读取独立，不能将草稿当可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 名称 |
| menuType | string | 是 | 受控类型 |
| timezone | string | 是 | 时区 |
| priority | int32 | 是 | 优先级 |
| exclusiveGroup | string | 否 | 互斥组 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menu | Menu | 是 | 草稿头 |

### FOOD-API-111 · 餐牌停用

`POST /biz/x/food/menus/{menuNo}:disable`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id；旧订单快照保留。

能力依赖：已实现餐牌工作定义管理；发布与历史读取独立，不能将草稿当可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menu | Menu | 是 | 停用后的工作头，不影响旧订单快照 |

### FOOD-API-112 · 菜品列表

`GET /biz/x/food/stores/{storeNo}/dishes`

权限与守卫：当前主体门店和原商品权限；先按原商品权威过滤及名称匹配，再统计与分页；goodsNo稳定排序；不接受未知或重复查询参数；不返回价格库存假值。

能力依赖：已实现管理查询，不代表可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| keyword | string | 否 | 最长128商品名或餐饮别名多语言名，忽略大小写字面子串 |
| dishKind | string | 否 | SINGLE或BUNDLE或ADDON |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | DishCatalogItem[] | 是 | 当前店授权菜品与原商品图文 |
| total | int64 | 是 | 全部授权筛选后数量 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-113 · 菜品详情

`GET /biz/x/food/stores/{storeNo}/dishes/{goodsNo}`

权限与守卫：当前主体门店原商品权限；真实组绑定及条目同主体；目标丢失/跨主体绑定CONFLICT，不用空组隐藏；发布引用独立返回，管理草稿不作为可售定义。

能力依赖：已实现管理详情，不代表发布或可交易。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| goodsNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| dish | Dish | 是 | 当前餐饮配置 |
| optionGroups | DishOptionGroup[] | 是 | 当前真实管理草稿组 |
| coreCatalogRef | string | 是 | 已核验的基础商品编号 |
| coreCatalog | ProductCatalog | 是 | 基础图文及规格 |

### FOOD-API-114 · 经营端可售餐牌

`GET /biz/x/food/stores/{storeNo}/catalog`

权限与守卫：服务器当前时间；仅已发布冻结定义及其时段来源履约；每菜项保留身份；当前原商品审核上架、原CN/CNY门店及特价和餐饮限制重新读取；未知价格及库存为null且不可成交；复杂选配/套餐/配方需权威报价；公开及顾客目录不暴露channelAccount账号、外部门店、pricePolicyRef或内部依赖快照，渠道上下文不能由用户伪造；不接受任意at未来预览。

能力依赖：ALL-06已实现保守当前展示；PLATFORM物理库存可明确读取，DUAL/INVENTORY或多市场歧义为UNKNOWN；没有报价保留禁成交，不保证下单。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| orderSource | string | 是 | POS或WAITER或KIOSK或QR或H5或APP |
| fulfillment | string | 是 | DINE_IN或PICKUP或DELIVERY |
| channelBindingNo | string | 否 | 尚无真实渠道目录授权时拒绝 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 获准店 |
| orderSource | string | 是 | 可信来源 |
| fulfillment | string | 是 | 履约 |
| asOf | datetime | 是 | 服务端当前时点 |
| menus | CatalogMenu[] | 是 | 生效发布 |
| items | CatalogItem[] | 是 | 保留菜项身份 |

### FOOD-API-115 · 配置发布历史

`GET /biz/x/food/config-revisions`

权限与守卫：已实现；原GOODS及可信当前门店；主体与冻结门店范围过滤先于分页；status按查询时点派生，不使用可变工作头指针作为发布权威。

能力依赖：已实现OPTION_GROUP/MODIFIER/BUNDLE发布历史；完整快照读取及按时点解析为内部typed契约；schema1旧内容及schema2套餐分别解码。

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configKind | string | 是 | OPTION_GROUP或MODIFIER或BUNDLE |
| configNo | string | 是 | 配置号 |
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | ConfigRevision[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-116 · 回滚配置为新修订

`POST /biz/x/food/config-revisions/{revisionNo}:republish`

权限与守卫：原主体门店及当前工作CAS；只允许已实现kind白名单；重新验真当前原身份及依赖授权，用旧冻结内容追加新发布号；保留原内容哈希及旧引用，不覆盖历史；理由与actor审计同事务；CHANNEL互斥按冻结的主体/门店/提供方/渠道账号/外部门店身份比较，不能按餐牌私有bindingNo判离散。

能力依赖：ALL-03已实现新修订回滚；开关和冲突时间线继续适用；不回滚历史订单事实。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| revisionNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 当前头版本 |
| effectiveAt | datetime | 是 | 生效 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 新发布号 |

### FOOD-API-117 · 配料组合发布

`POST /biz/x/food/modifiers/{modifierNo}:publish`

权限与守卫：已实现；原GOODS及门店目录权限，主体共享头；完整聚合及可满足性校验；按门店和父生效时点冻结不可变依赖；事务外原SKU/规格权威预读，锁头重验一致；CAS/幂等/审计/Outbox原子；禁止历史回填。

能力依赖：已实现OPTION_GROUP/MODIFIER配置发布；不授予价格库存或可售权限。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| modifierNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 工作头CAS |
| effectiveAt | datetime | 是 | 带时区毫秒精度生效时刻 |
| approvalRef | string | 否 | 审批业务引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 发布快照 |

### FOOD-API-118 · 选配组列表

`GET /biz/x/food/stores/{storeNo}/option-groups`

权限与守卫：原商品权限及当前主体门店校验；先主体过滤再SQL统计分页，groupNo稳定排序，批量读取本页真实条目；拒绝未知或重复参数；不作为已发布授权。

能力依赖：已实现管理查询，不代表可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Config[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-119 · 套餐列表

`GET /biz/x/food/stores/{storeNo}/bundles`

权限与守卫：已实现；原GOODS及门店入口权限；主体过滤先于count分页，SKU稳定排序，真实有序组/选项；拒绝未知/重复参数；无物理id。

能力依赖：已实现草稿管理，非可售目录。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | BundleConfigVO[] | 是 | 主体共享管理列表 |
| total | int64 | 是 | 真实总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-120 · 配料列表

`GET /biz/x/food/stores/{storeNo}/modifiers`

权限与守卫：已实现管理查询；原GOODS及门店入口权限；主体过滤先于count分页，批量读回真实成员；不含价格库存或可售授权。

能力依赖：已实现草稿管理；门店可售目录待发布及交易校验。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | ModifierConfigVO[] | 是 | 主体共享配料管理列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-121 · 工位列表

`GET /biz/x/food/stores/{storeNo}/stations`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id。

能力依赖：ALL-04已实现。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Station[] | 是 | 授权工位 |
| total | int64 | 是 | 相同过滤总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-122 · 可售限制查询

`GET /biz/x/food/stores/{storeNo}/sales-restrictions`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id。

能力依赖：ALL-05已实现服务器时点状态及主体门店过滤。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| skuNo | string | 否 | SKU |
| status | string | 否 | 状态 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | SalesRestriction[] | 是 | 约束 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-123 · 按台账或条件查询批次

`GET /biz/x/food/stores/{storeNo}/batches`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id；资金按原核心单归属，查询不更新历史。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| checkNo | string | 否 | 原/当前服务台账 |
| membershipMode | string | 否 | ORIGINAL或CURRENT |
| status | string | 否 | 受理状态 |
| businessDate | date | 否 | 批次营业日 |
| orderSource | string | 否 | 来源 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Batch[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-124 · 取消正式批次

`POST /biz/x/food/batches/{batchNo}:cancel`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id；调用核心取消；支付竞争/已制作按核心守卫和退餐处理；未知不换键重建。

能力依赖：核心取消及后付库存合同。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 批次版 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 取消进度 |
| batch | Batch | 是 | 批次 |

### FOOD-API-125 · 批次操作恢复查询

`GET /biz/x/food/batches/{batchNo}/operations`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Operation[] | 是 | 建单取消受理恢复 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-126 · 关闭草稿购物车

`POST /biz/x/food/carts/{cartNo}:archive`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 当前整个车版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 归档回执 |

### FOOD-API-127 · 顾客购物车读取

`GET /mp/x/food/carts/{cartNo}`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。sessionNo query及X-Food-Session头不属于业务DTO。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sessionNo | string | 否 | 扫码必需且H5可省略 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cart | Cart | 是 | 脱敏获准草稿 |

### FOOD-API-128 · 顾客创建购物车

`POST /mp/x/food/stores/{storeNo}/carts`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 必须等于已获准会话台账 |
| sessionNo | string | 否 | 原扫码会话或服务端H5会话 |
| fulfillment | string | 是 | 履约 |
| scheduledAt | datetime | 否 | 当前拒绝 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cart | Cart | 是 | 脱敏获准草稿 |

### FOOD-API-129 · 顾客替换草稿行

`PUT /mp/x/food/carts/{cartNo}/lines/{sourceLineRef}`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。sessionNo query及X-Food-Session头不属于业务DTO。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| sourceLineRef | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| line | OrderLineInput | 是 | 完整替换源行 |
| version | int64 | 是 | 当前整个车版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| version | int64 | 是 | 整体新版本 |

### FOOD-API-130 · 顾客归档购物车草稿行

`POST /mp/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。sessionNo query及X-Food-Session头不属于业务DTO。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| sourceLineRef | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 当前整个车版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| version | int64 | 是 | 整体新版本 |

### FOOD-API-131 · 顾客点单报价

`POST /mp/x/food/stores/{storeNo}/quotes`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

能力依赖：已有报价适配；选配计量等分项门控。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 台账 |
| fulfillment | string | 是 | 履约 |
| orderSource | string | 是 | 经认证来源 |
| lines | OrderLineInput[] | 是 | 源行 |
| cartNo | string | 是 | 源车 |
| cartVersion | int64 | 是 | 车版本 |
| scheduledAt | datetime | 否 | 出餐时点 |
| sessionNo | string | 是 | 获准会话 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| quote | Quote | 是 | 权威报价 |

### FOOD-API-132 · 顾客提交点餐批次

`POST /mp/x/food/stores/{storeNo}/batches`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

能力依赖：当前OrderService适配及通用缺口。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| cartVersion | int64 | 是 | 版本 |
| selectedLineRefs | string[] | 是 | 本次行 |
| quoteNo | string | 是 | 已确认报价 |
| payMode | string | 是 | 先后付 |
| splitByPerson | boolean | 是 | 预分单 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batch | Batch | 是 | 绑定或处理中 |

### FOOD-API-133 · 顾客批次结果查询

`GET /mp/x/food/batches/{batchNo}`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| detail | BatchDetail | 是 | 原批次及资金履约独立状态 |

### FOOD-API-134 · 顾客读取预结账

`GET /mp/x/food/settlements/{settlementNo}`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlement | Settlement | 是 | 当前状态 |
| confirmedAmount | Money | 是 | 核心已确认 |
| pendingIntentNos | string[] | 是 | 未知请求 |

### FOOD-API-135 · 顾客结账pay

`POST /mp/x/food/settlements/{settlementNo}:pay`

权限与守卫：服务端查询核心资金；不接受markPaid。现金仅biz收银权限且班次/完整单根影响集匹配，找零由服务端计算；顾客端只获准线上工具；未知状态按原意图恢复。

能力依赖：核心支付/现金确认；混合与跨根需扩展。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| tool | string | 是 | 获准线上工具 |
| amount | Money | 是 | 本次付款 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| intentNo | string | 是 | 请求意图 |
| status | string | 是 | 含UNKNOWN |
| coreReference | string | 否 | 核心结果 |
| paymentAction | object | 否 | 核心返回端上支付动作 |

### FOOD-API-136 · 顾客退餐申请

`POST /mp/x/food/stores/{storeNo}/returns`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 台账 |
| items | ReturnItem[] | 是 | 逐行退量 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string | 是 | 申请 |
| status | string | 是 | REQUESTED |
| version | int64 | 是 | 版本 |

### FOOD-API-137 · 顾客退餐查询

`GET /mp/x/food/returns/{returnNo}`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string | 是 | 申请 |
| items | ReturnItem[] | 是 | 明细 |
| status | string | 是 | 申请状态 |
| financialStatus | string | 是 | 资金状态 |
| coreCaseRef | string | 否 | 核心售后 |
| version | int64 | 是 | 版本 |
| kitchenStatus | string | 是 | 厨房轨道 |

### FOOD-API-138 · 顾客就餐进度

`GET /mp/x/food/checks/{checkNo}`

权限与守卫：目标仅显式获准的本人check/session membership，不能扫码即读取同桌其他客组；当前入席、正式批次/资金视图合同未齐，本入口未开放。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| check | Check | 是 | 获准视图 |
| foodLines | FoodLine[] | 是 | 仅获准行 |
| financialStatus | string | 是 | 权限过滤的资金状态 |

### FOOD-API-139 · 主动结束顾客会话

`POST /mp/x/food/sessions/{sessionNo}:end`

权限与守卫：X-Food-Session短凭证及原Idempotency-Key必需；只允许有效本人/匿名持有人会话，已绑定buyer须匹配平台CONSUMER，匿名持有人登录后可结束但不自动认领买家身份。过期或撤销拒绝新命令，已成功安全收据可重放；不取消原订单。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sessionNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 吊销结果 |

### FOOD-API-140 · 顾客申请获准范围结账

`POST /mp/x/food/checks/{checkNo}/settlements`

权限与守卫：会话本人范围；服务端解析核心单及分配，不接受任意核心单号；整桌代付须台账明确授权；冻结排他与未知资金守卫同biz；不得创建现金/免单/跨币种结账。

能力依赖：核心应收冻结；部分/跨根能力门控。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 台账版本 |
| foodLineNos | string[] | 是 | 获准源行范围，空代表本人获准未结范围 |
| scopeHash | string | 是 | 顾客已确认应收摘要 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlement | Settlement | 是 | 核心冻结范围 |

### FOOD-API-141 · 创建配料或配料组合

`POST /biz/x/food/stores/{storeNo}/modifiers`

权限与守卫：已实现草稿创建；原GOODS与门店权限、主体共享库、创建幂等；SKU原权威主体门店校验；COMBO仅同主体有效ADDON且不重复/嵌套；服务端生成业务号，拒客户端价格库存。

能力依赖：已实现草稿管理；发布与收费成交待后续P1/P3。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 名称 |
| kind | string | 是 | ADDON或COMBO |
| salesSkuNo | string | 否 | 原计价SKU |
| components | ModifierComponentInput[] | 是 | 组合成员或空 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 服务端生成配料业务号 |
| version | int64 | 是 | 草稿CAS版本 |
| status | string | 是 | DRAFT |

### FOOD-API-142 · 创建餐牌渠道绑定

`POST /biz/x/food/stores/{storeNo}/menu-channel-bindings`

权限与守卫：同主体当前店餐牌；新绑定为DRAFT，不宣称已获账号授权；服务端号/CAS/actor绑定幂等/审计Outbox；创建及修改失效餐牌工作CAS。

能力依赖：ALL-02已实现渠道引用草稿及受控启停；真实渠道账号/价格授权缺失时ACTIVE拒绝；Keeta仅规划，无真实同步或成交。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string | 是 | 本店餐牌 |
| provider | string | 是 | 受控渠道 |
| channelAccountRef | string | 是 | 已有受权账号引用 |
| externalStoreRef | string | 是 | 受权外部门店 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string | 是 | 服务端业务号 |
| menuNo | string | 是 | 本店餐牌 |
| storeNo | string | 是 | 门店 |
| provider | string | 是 | 渠道 |
| channelAccountRef | string | 是 | 仅经营授权端可读账号引用 |
| externalStoreRef | string | 是 | 外部门店引用 |
| status | string | 是 | DRAFT或ACTIVE或DISABLED |
| version | int64 | 是 | CAS |

### FOOD-API-143 · 渠道绑定列表

`GET /biz/x/food/stores/{storeNo}/menu-channel-bindings`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。。

能力依赖：ALL-02已实现渠道引用草稿及受控启停；真实渠道账号/价格授权缺失时ACTIVE拒绝；Keeta仅规划，无真实同步或成交。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| provider | string | 否 | 渠道 |
| menuNo | string | 否 | 餐牌 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | MenuChannelBinding[] | 是 | 授权记录 |
| total | int64 | 是 | 同过滤总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-144 · 渠道绑定详情

`GET /biz/x/food/menu-channel-bindings/{bindingNo}`

权限与守卫：真实GOODS权限及获准对象门店；仅经营端返回账号引用，公开目录不得返回。

能力依赖：ALL-02已实现渠道引用草稿及受控启停；真实渠道账号/价格授权缺失时ACTIVE拒绝；Keeta仅规划，无真实同步或成交。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string | 是 | 服务端业务号 |
| menuNo | string | 是 | 本店餐牌 |
| storeNo | string | 是 | 门店 |
| provider | string | 是 | 渠道 |
| channelAccountRef | string | 是 | 仅经营授权端可读账号引用 |
| externalStoreRef | string | 是 | 外部门店引用 |
| status | string | 是 | DRAFT或ACTIVE或DISABLED |
| version | int64 | 是 | CAS |

### FOOD-API-145 · 修改渠道绑定

`PUT /biz/x/food/menu-channel-bindings/{bindingNo}`

权限与守卫：对象门店/CAS/actor绑定幂等；ACTIVE需真实账号及外部门店授权，Keeta或缺提供方拒绝；provider/账号/menu/store不可换义；停用不取消历史订单。

能力依赖：ALL-02已实现渠道引用草稿及受控启停；真实渠道账号/价格授权缺失时ACTIVE拒绝；Keeta仅规划，无真实同步或成交。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | CAS |
| status | string | 是 | ACTIVE或DISABLED |
| externalStoreRef | string | 是 | 获准外部门店 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string | 是 | 服务端业务号 |
| menuNo | string | 是 | 本店餐牌 |
| storeNo | string | 是 | 门店 |
| provider | string | 是 | 渠道 |
| channelAccountRef | string | 是 | 仅经营授权端可读账号引用 |
| externalStoreRef | string | 是 | 外部门店引用 |
| status | string | 是 | DRAFT或ACTIVE或DISABLED |
| version | int64 | 是 | CAS |

### FOOD-API-146 · 发起餐牌渠道同步

`POST /biz/x/food/menu-channel-bindings/{bindingNo}/syncs`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。当前Keeta没有启用适配；无真实提供方拒绝，不能返回虚构成功。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| revisionNo | string | 是 | 本绑定餐牌已发布修订 |
| version | int64 | 是 | 绑定CAS |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sync | CatalogSync | 是 | 原号任务 |

### FOOD-API-147 · 查询餐牌同步结果

`GET /biz/x/food/catalog-syncs/{syncNo}`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。部分/未知分列；旧ACK不能覆盖新目标。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| syncNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sync | CatalogSync | 是 | 持久化真实状态 |

### FOOD-API-148 · 创建配方工作头

`POST /biz/x/food/stores/{storeNo}/recipes`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；本批仅EA计份、真实物料baseUom；无库存命令；内部Token不替代B端门店权限。单位与物料目录未联通时不得假校验。。

能力依赖：RECIPE-01A/01B草稿已实现：基础物料及选配调整，本地和typed远程一致；发布及实际选择求值未实现。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 本店SKU |
| recipeCode | string | 是 | DEFAULT或受控变体 |
| yieldQty | decimal | 是 | 正产出 |
| yieldUnit | string | 是 | 受控单位 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipe | Recipe | 是 | 服务端生成配方 |

### FOOD-API-149 · 配方列表

`GET /biz/x/food/stores/{storeNo}/recipes`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；本批仅EA计份、真实物料baseUom；无库存命令；内部Token不替代B端门店权限。。

能力依赖：RECIPE-01A/01B草稿已实现：基础物料及选配调整，本地和typed远程一致；发布及实际选择求值未实现。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| skuNo | string | 否 | 本店SKU |
| status | string | 否 | 状态 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Recipe[] | 是 | 记录 |
| total | int64 | 是 | 同过滤总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-150 · 配方详情

`GET /biz/x/food/recipes/{recipeNo}`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；本批仅EA计份、真实物料baseUom；无库存命令；内部Token不替代B端门店权限。。

能力依赖：RECIPE-01A/01B草稿已实现：基础物料及选配调整，本地和typed远程一致；发布及实际选择求值未实现。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipe | Recipe | 是 | 获准配置 |

### FOOD-API-151 · 维护配方物料与选配调整

`PUT /biz/x/food/recipes/{recipeNo}/definition`

权限与守卫：可信主体门店及商品权限；幂等/CAS；基础单位验真；调整目标为本次保留的既有基础行，选项同主体且组DRAFT/ACTIVE；既有调整编号不能重绑，单位与目标一致；SET/ADD非负、REMOVE空；仅草稿配置，不进行选择求值/扣料/发布。。

能力依赖：RECIPE-01A/01B草稿已实现：基础物料及选配调整，本地和typed远程一致；发布及实际选择求值未实现。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | CAS |
| yieldQty | decimal | 是 | 正产出 |
| yieldUnit | string | 是 | 固定EA配置份数 |
| lines | RecipeLineInput[] | 是 | 最多100基础行 |
| adjustments | RecipeAdjustmentInput[] | 否 | 最多100调整，省略或null保留、空数组清空 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipe | Recipe | 是 | 新草稿版本 |

### FOOD-API-152 · 发布配方

`POST /biz/x/food/recipes/{recipeNo}:publish`

权限与守卫：原商品权限/可信主体门店/actor/幂等/CAS；recipe-publication-enabled默认false；schema3冻结基础单位物料事实和实际绑定的已发布组选项；来源身份/hash变化拒绝，锁内重捕获；不意味着报价/扣料已就绪。。

能力依赖：RECIPE-01C已实现：原ConfigRevisionService单店定时不可变发布及schema3读；写入默认关闭，部署须先升级读者。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | CAS |
| effectiveAt | datetime | 是 | 生效时间 |
| approvalRef | string | 否 | 可选审批依据 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 不可变修订 |

### FOOD-API-153 · 制作耗用列表

`GET /biz/x/food/stores/{storeNo}/production-consumptions`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；本批真实只读route与DTO见nonPaymentImplementation；保留目标合同差异与待验收状态。只读不补扣库存；未知成本与实际量保持为空。。

能力依赖：餐饮目标合同保留；本批真实SQL分页/COUNT与DTO已编码，CODED_PENDING_VERIFICATION；共享PageData字段类型差异见nonPaymentImplementation，不代表目标模型与全部业务已验收。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| from | date | 是 | 起日 |
| to | date | 是 | 止日不含 |
| skuNo | string | 否 | 本店SKU |
| itemRef | string | 否 | 物料 |
| status | string | 否 | 真实状态 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | ProductionConsumption[] | 是 | 获准事实 |
| total | int64 | 是 | 同过滤总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-154 · 制作耗用详情

`GET /biz/x/food/production-consumptions/{consumptionNo}`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。只读核验逐行回执，不能以退款推返料。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| consumptionNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| consumption | ProductionConsumption | 是 | 原事实与回执 |

### FOOD-API-155 · 读取SKU工位路由

`GET /biz/x/food/stores/{storeNo}/sku-routes/{skuNo}`

权限与守卫：原GOODS权限及真实同主体当前门店；只读店SKU当前路由及整体关系版本，不返回物理id，不写审计或库存。

能力依赖：ALL-04已实现；StationService.getRoutes/BizStationController.getRoutes，空关系保留版本而非重置。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| skuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 原SKU |
| version | int64 | 是 | 整个店SKU路由版本，清空仍保留计数 |
| routes | RouteInput[] | 是 | 当前有效路由 |

### FOOD-API-156 · 授权扫码会话加入共享购物车

`POST /biz/x/food/checks/{checkNo}/cart-sessions`

权限与守卫：原VERIFY门店权限；锁当前区域桌台/check/session，当前有效桌台占用必须属于台账；不可改绑其他台账；原会话撤销过期立即生效。

能力依赖：ORD01辅助合同，复用原GuestSession.checkNo及scope追加CHECK_CART，不增加成员表；审计及DiningSessionChanged Outbox同事务。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sessionNo | string | 是 | 原扫码会话 |
| version | int64 | 是 | 会话当前版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sessionNo | string | 是 | 获准会话 |
| checkNo | string | 是 | 当前OPEN台账 |
| version | int64 | 是 | 会话新版本 |

### FOOD-API-157 · 排队取号

`POST /biz/x/food/queue-tickets`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| partySize | int32 | 是 | 正整数至1000 |
| contactRef | string | 否 | 已有联系引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 原键创建结果 |

### FOOD-API-158 · 排队列表

`GET /biz/x/food/queue-tickets`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| businessDate | date | 是 | 营业日 |
| status | string | 否 | 状态 |
| page | int32 | 否 | 页 |
| size | int32 | 否 | 页长 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | QueueTicketPage | 是 | 门店FIFO列表 |

### FOOD-API-159 · 原排队票

`GET /biz/x/food/queue-tickets/{ticketNo}`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticketNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 当前票 |

### FOOD-API-160 · 叫下一位

`POST /biz/x/food/queue-tickets:call-next`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| businessDate | date | 是 | 营业日 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 实际最早WAITING票 |

### FOOD-API-161 · 指定叫号

`POST /biz/x/food/queue-tickets/{ticketNo}:call`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticketNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 原CAS |
| reason | string | 否 | 依据 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 已叫号 |

### FOOD-API-162 · 取消排队

`POST /biz/x/food/queue-tickets/{ticketNo}:cancel`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticketNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 原CAS |
| reason | string | 否 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 已取消 |

### FOOD-API-163 · 排队过号

`POST /biz/x/food/queue-tickets/{ticketNo}:no-show`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticketNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 原CAS |
| reason | string | 否 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 真实过号 |

### FOOD-API-164 · 排队转原开台

`POST /biz/x/food/queue-tickets/{ticketNo}:seat`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticketNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 原CAS |
| check | object | 是 | 原OpenCheckReq完整受控开台参数，见QueueContracts |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 原子开台成功SEATED；旧SEATING仅兼容查原回执 |

### FOOD-API-165 · 原排队就座恢复

`POST /biz/x/food/queue-tickets/{ticketNo}:recover-seating`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticketNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 旧SEATING只恢复原冻结开台回执，未知仍保留 |

## 五、顾客与设备端复用

顾客catalog/session与购物车、报价、批次、进度、结账、退餐申请/查询、主动结束会话均已逐项登记API编号。只有目录中的 /mp/x/food 白名单可实现，不能将整个biz控制器自动暴露到mp。顾客作用域绑定本人或明确授权的台账/源行，桌码仅授权桌级会话，不自动共享同桌其他独立台账。顾客禁止代客buyerNo、现金确认、审批、签核、任务操作和自造corePaymentToken；点餐机使用已验证设备凭据与短会话。

支付动作对象由核心通道按真实客户端环境产生，业务服务不自拼收银参数。共享审批、商品编辑、会员资产、发票、税费、配送、排队及房账平台有各自合同；此处端点不重复实现它们的核心逻辑。

## 六、示例与错误恢复

点餐输入示例（选配能力启用时）：

```json
{"cartNo":"CART-1","cartVersion":1,"checkNo":"CHECK-1","buyerNo":"BUYER-1","fulfillment":"DINE_IN","orderSource":"WAITER","lines":[{"sourceLineRef":"LINE-1","goodsNo":"G-1","skuNo":"SKU-1","qty":"1","selections":[{"groupNo":"ICE","sourceKind":"SPEC_VALUE","sourceNo":"LESS_ICE","qty":"1","configVersion":3}]}]}
```

批次受理返回示例：

```json
{"code":0,"msg":"success","data":{"batch":{"batchNo":"BATCH-1","status":"PLACING","paymentRootNos":[],"storeOrderNos":[],"foodLineNos":[],"version":1}}}
```

data.status为PLACING/UNKNOWN时按batchNo查询；HTTP状态仍沿宿主包装及异常协议，不强制返回202。请求受理后，不换幂等键重建；合法受理后也需按先付/后付条件判断是否制作。冲突错误沿宿主错误码返回当前version或冲突资源引用，但不泄露无权业务。日结签核前水位变化返回CLOSE_WATERMARK_CHANGED，重新预结审阅；依赖能力未开返回CAPABILITY_NOT_READY，不尝试餐饮直接写核心数据。

## 七、餐饮与基础域的输入输出合同

> 状态：待评审 · 2026-10-19 · 本文件是API生成输入片段；正式阅读入口为技术设计目录中的《餐饮系统API设计》。

Catalog.goodsMetadata已实现为GoodsCatalogQueryPort及typed内部查询；其余名称仍为目标端口，不是已存在Java类。集成部署实现为宿主桥接，独立部署实现为服务调用；不将跨库实现细节传入餐饮领域。字段中的ref均须受权查询，不允许客户端自造已付或已退款凭据。

| 端口 | 输入（必填，?为可选） | 输出 | 当前边界 |
|---|---|---|---|
| Identity.authorize | actorToken:string, entityNo:string, storeNo:string, action:string, objectRef?:string | actorNo:string, buyerScope:string[], allowed:boolean, limits:Money[] | 复用身份；匿名就餐主体与代办需正式合同 |
| Catalog.goodsMetadata | entityNo:string, storeNo:string, goodsNo:string | GoodsFact:{goodsNo,entityNo,storeNo,title,cover?,baseOnSale,auditStatus?,version,skus:{skuNo,spec?,version}[]} | CAT-01A基础管理事实；明确商品真实归属及全部CN规格，无价格库存，不表示最终可售；独立经GoodsCatalogInternalApi读取PLATFORM |
| Catalog.snapshot | entityNo:string, storeNo:string, skuNos:string[], channel:string, at:datetime | items:{goodsNo,skuNo,title,unit,currency,priceMinor,onSale,availableQty,version}[] | 读取商品服务，不直接跨库JOIN |
| Catalog.validateSelections | skuNo:string, selections:Selection[], configVersion:int64 | valid:boolean, normalized:Selection[], recipeRef?:string | 强销售约束提交前校验及收费组合为缺口 |
| Order.quote | buyerNo:string, storeNo:string, fulfillment:string, channel:string, lines:OrderLineInput[] | Quote及sourceLineRef到报价行映射 | 当前preview可适配基础SKU；选配小数不直接传Item |
| Order.place | requestKey:string, quoteNo:string, buyerNo:string, storeNo:string, lines:OrderLineInput[], payMode:string | paymentRootNos:string[], storeOrderNos:string[], stableLines:{sourceRef,orderItemId,qty,amount,kind}[], phase:string | 当前create/createFor适配；源行和后付需补能力 |
| Order.findPlacement | entityNo:string, requestKey:string | phase:string, originalResult?:Batch | 超时恢复必须查同键，不任意重建 |
| Inventory.acceptDeferred | storeOrderNos:string[], acceptanceKey:string, expiresPolicy:string | commitmentRef:string, status:string, expiresAt?:datetime | 后付承诺与取消守卫缺口，不能餐饮自行二次锁库存 |
| Fulfillment.deliver | storeOrderNo:string, lineQty:{orderItemId,qty}[], operationKey:string, kind:string | deliveryRef:string, status:string | 自取配送适配现有；堂食及未付交付需补合同 |
| Cashier.freeze | scopes:ScopeItem[], currency:string, scopeHash:string | cashierRef:string, due:Money, version:int64, expiresAt:datetime | 通用应收范围预占和跨单收银缺口 |
| Payment.open | cashierRef或合法paymentRootNo:string, tool:string, amount:Money, actorNo:string, requestKey:string | paymentRef:string, status:string, clientAction?:object | 现有单根pay可适配；混合/跨根分配不可伪造 |
| Payment.query | paymentRef:string, entityNo:string | status:string, confirmedAmount:Money, allocations:{orderRef,amount}[], occurredAt?:datetime | 唯一可信支付结果，退款保持原分配追踪 |
| Payment.confirmCash | storeOrderNos:string[], requestKey:string, actorNo:string, cashEvidence:string | corePaymentRef:string, status:string | 当前confirmOfflinePay完整单店影响集限制保留 |
| Order.adjustReceivable | scope:ScopeItem[], kind:string, amount:Money, reason:string, approvalRef:string, requestKey:string | adjustmentRef:string, newQuote:Quote, status:string | 订单金额不可餐饮直接update；通用调整缺口 |
| AfterSale.request | storeOrderNo:string, lines?:{orderItemId,qty}[], reason:string, requestKey:string | caseNo:string, refundable:Money, status:string | 当前仅门店整单全额；lines部分退受能力门控 |
| Resource.holdOrOccupy | storeNo:string, resourceNos:string[], startAt?:datetime, endAt?:datetime, seats:int32, requestKey:string | claimNos:string[], status:string, expiresAt?:datetime | 时段预约与开放占用分开，统一仲裁者 |
| Resource.transfer | oldClaims:string[], newResources:string[], expectedVersion:int64, requestKey:string | newClaims:string[], status:string, recoveryRef?:string | 先占后放，失败保留可恢复占用 |
| Approval.requestOrVerify | actorNo:string, action:string, amount?:Money, objectRef:string, policyVersion:int64, approvalRef?:string | decision:string, approvalRef:string, approverNo?:string | 复用审批；未存在时建共享能力，不把审批塞进任意JSON |
| Reporting.queryOrBuild | storeNos:string[], range:{from,to}, timeBasis:string, currency:string, metricVersion:int64 | Report及runRef:string | 复用shop-report，新增营业日投影不改旧统计 |
| Reconcile.evidence | storeNo:string, dateRange:{from,to}, channel:string, currency:string, watermark?:string | totals:MetricCell[], differences:{ref,type,amount,status}[], asOf:datetime | 支付对账非餐饮重建资金账 |
| Assets.applyOrReverse | settlementRef:string, assetRef:string, amount:Money, requestKey:string, originalRef?:string | operationRef:string, allocations:{principal,gift}[], status:string | 会员资产唯一扣减，充值不计消费收入 |
| AR.transferOrApply | customerRef:string, scope:ScopeItem[], amount:Money, approvalRef:string, requestKey:string | arRef:string, balance:Money, status:string | 企业挂账/PMS房账待通用应收能力 |
| Print.submit | sourceEventNo:string, scene:string, sourceRef:string, contentVersion:int64 | jobNos:string[], status:string | 通用打印业务与渠道分层；内容服务端冻结 |

核心命令需要最终校验报价、权限、库存及资金版本；餐饮预校验不能替代核心提交校验。发票、排队、PMS、税费与第三方专项协议以其权威系统合同为准，实施前补充协议版本及失败恢复，不在此臆造现成接口。

## 八、逐需求与业务模块对齐

目标已定义仅表示本轮结构与目标合同有明确落点，不表示接口已实现。依赖共享/核心合同必须由权威模块接通验证；尚缺专项设计不能作为已覆盖需求进入开发完成统计。跨域关系为逻辑引用，不允许餐饮直接写核心表。

### MOD-01 桌台与就餐管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-A04 | 目标已定义 | `fnb_table_area`、`fnb_table`、`fnb_table_token` | FOOD-API-024、FOOD-API-025、FOOD-API-092、FOOD-API-093、FOOD-API-094、FOOD-API-095、FOOD-API-096、FOOD-API-097、FOOD-API-098、FOOD-API-099、FOOD-API-100、FOOD-API-101 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-C01 | 尚缺专项设计 | `fnb_reservation` | FOOD-API-027、FOOD-API-030、FOOD-API-107 | 预约资源已设计；预点菜草稿到预约的引用与预点命令未定义，实施预约预点前补齐，不能提前生成交易单 |
| FNB-C02 | 依赖共享/核心合同 | `fnb_reservation` | FOOD-API-027、FOOD-API-028、FOOD-API-029 | 订金/预付款核销、没收审批与退款状态取权威资金合同；deposit_ref只存引用，不表示已覆盖全部资金动作 |
| FNB-C03 | 依赖共享/核心合同 | `fnb_queue_ticket`、`fnb_check`、`fnb_action_log` | FOOD-API-157、FOOD-API-158、FOOD-API-159、FOOD-API-160、FOOD-API-161、FOOD-API-162、FOOD-API-163、FOOD-API-164、FOOD-API-165 | 已有正式排队创建/叫号/过号/取消/原开台就座恢复合同；共享开台仲裁及真实通知分别验收，不把排队号当取餐号或伪造短信成功。 |
| FNB-C04 | 目标已定义 | `fnb_reservation`、`fnb_table_operation`、`fnb_table_occupancy` | FOOD-API-027、FOOD-API-030、FOOD-API-031 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-C05 | 目标已定义 | `fnb_check`、`fnb_guest_session`、`fnb_seat`、`fnb_table_occupancy` | FOOD-API-026、FOOD-API-031、FOOD-API-102、FOOD-API-103、FOOD-API-105、FOOD-API-138、FOOD-API-139 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-C06 | 目标已定义 | `fnb_table_operation`、`fnb_table_occupancy`、`fnb_check_relation`、`fnb_check_batch_membership` | FOOD-API-033、FOOD-API-034、FOOD-API-035、FOOD-API-104、FOOD-API-105、FOOD-API-106 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-C08 | 目标已定义 | `fnb_check`、`fnb_table_occupancy`、`fnb_table_operation` | FOOD-API-036、FOOD-API-096、FOOD-API-102、FOOD-API-108 | 结构与目标接口已定义；仍需实现与业务验收 |

### MOD-02 菜品与餐牌管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-B01 | 依赖共享/核心合同 | `fnb_dish` | FOOD-API-016、FOOD-API-112、FOOD-API-113 | 基础SKU/上下架/价格库存复用商品服务；本文不新增餐饮商品副本 |
| FNB-B02 | 目标已定义 | `fnb_menu`、`fnb_menu_store`、`fnb_menu_context`、`fnb_menu_schedule`、`fnb_menu_exception`、`fnb_menu_category`、`fnb_menu_item`、`fnb_menu_item_sku`、`fnb_config_revision`、`fnb_menu_channel_binding`、`fnb_menu_item_option_group` | FOOD-API-004、FOOD-API-005、FOOD-API-006、FOOD-API-017、FOOD-API-109、FOOD-API-110、FOOD-API-111、FOOD-API-114、FOOD-API-115、FOOD-API-116、FOOD-API-142、FOOD-API-143、FOOD-API-144、FOOD-API-145 | 时段/渠道范围及菜项覆盖已入设计；报价引用须权威核验，Keeta仅规划，实际发布/同步未实现。 |
| FNB-B03 | 目标已定义 | `fnb_dish`、`fnb_option_group`、`fnb_option_group_item`、`fnb_dish_option_group`、`fnb_config_revision` | FOOD-API-007、FOOD-API-008、FOOD-API-009、FOOD-API-016、FOOD-API-019、FOOD-API-118 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-B04 | 目标已定义 | `fnb_dish`、`fnb_option_group`、`fnb_option_group_item`、`fnb_dish_option_group`、`fnb_config_revision`、`fnb_menu_item_option_group` | FOOD-API-007、FOOD-API-008、FOOD-API-009、FOOD-API-016、FOOD-API-019、FOOD-API-118 | 商品默认选配与餐牌REPLACE显式分开；免费口味复用SPEC_VALUE，未实现运行时选择校验。 |
| FNB-B05 | 依赖共享/核心合同 | `fnb_bundle`、`fnb_bundle_group`、`fnb_bundle_choice`、`fnb_config_revision` | FOOD-API-010、FOOD-API-011、FOOD-API-012、FOOD-API-018、FOOD-API-041、FOOD-API-119 | 配置及版本已定义，套餐成交分摊、补差、组件库存展开需核心组合报价合同 |
| FNB-B06 | 依赖共享/核心合同 | `fnb_modifier`、`fnb_modifier_component`、`fnb_option_group_item`、`fnb_config_revision` | FOOD-API-019、FOOD-API-020、FOOD-API-041、FOOD-API-117、FOOD-API-120、FOOD-API-141 | 组合配置防循环；双拼/半份的单位换算与收费必须核心计量/报价通过后启用 |
| FNB-B07 | 依赖共享/核心合同 | `fnb_dish`、`fnb_store_policy` | FOOD-API-041、FOOD-API-061 | 餐段/渠道价、茶位低消服务费等规则归共享报价；当前缺明确通用费用规则配置合同，不在settings任意JSON里影子计价 |
| FNB-B08 | 目标已定义 | `fnb_sales_restriction`、`fnb_sales_quota_claim` | FOOD-API-022、FOOD-API-122 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-B09 | 尚缺专项设计 | `fnb_dish`、`fnb_order_line` | FOOD-API-016、FOOD-API-041 | 计量规则有位置；标称/实称/允差确认及补差动作尚缺正式DTO和命令，不能只配置就售卖称重菜 |
| FNB-B10 | 依赖共享/核心合同 | `fnb_dish`、`fnb_store_policy` | FOOD-API-041、FOOD-API-061 | 餐段/渠道价、茶位低消服务费等规则归共享报价；当前缺明确通用费用规则配置合同，不在settings任意JSON里影子计价 |
| FNB-B11 | 尚缺专项设计 | `fnb_dish`、`fnb_config_revision` | FOOD-API-016、FOOD-API-112、FOOD-API-113 | 展示字段及冻结已补；批量导入预检/结果/导出尚依赖商品导入合同，明确接入后才算完成 |
| FNB-K01 | 目标已定义 | `fnb_config_revision`、`fnb_menu_store` | FOOD-API-006、FOOD-API-017、FOOD-API-115、FOOD-API-116 | 结构与目标接口已定义；仍需实现与业务验收 |

### MOD-03 点餐与订单管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-D01 | 依赖共享/核心合同 | `fnb_guest_session`、`fnb_check_cart`、`fnb_order_batch` | FOOD-API-026、FOOD-API-038、FOOD-API-041、FOOD-API-042、FOOD-API-127、FOOD-API-128、FOOD-API-129、FOOD-API-130、FOOD-API-131、FOOD-API-132、FOOD-API-133、FOOD-API-138、FOOD-API-139 | 顾客路由已逐项编号；匿名主体、代客、设备鉴权及各客户端支付参数必须沿身份/资金权威合同 |
| FNB-D02 | 依赖共享/核心合同 | `fnb_guest_session`、`fnb_check_cart`、`fnb_order_batch` | FOOD-API-026、FOOD-API-038、FOOD-API-041、FOOD-API-042、FOOD-API-127、FOOD-API-128、FOOD-API-129、FOOD-API-130、FOOD-API-131、FOOD-API-132、FOOD-API-133、FOOD-API-138、FOOD-API-139 | 顾客路由已逐项编号；匿名主体、代客、设备鉴权及各客户端支付参数必须沿身份/资金权威合同 |
| FNB-D03 | 目标已定义 | `fnb_check_cart`、`fnb_check_cart_item`、`fnb_seat` | FOOD-API-037、FOOD-API-038、FOOD-API-039、FOOD-API-040、FOOD-API-103、FOOD-API-126、FOOD-API-127、FOOD-API-128、FOOD-API-129、FOOD-API-130 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-D04 | 依赖共享/核心合同 | `fnb_check_cart`、`fnb_order_batch` | FOOD-API-041、FOOD-API-131 | 报价绑定草稿版本及指纹；价格变化须顾客确认；核心报价是权威 |
| FNB-D05 | 依赖共享/核心合同 | `fnb_order_batch`、`fnb_order_line`、`fnb_core_line_allocation`、`fnb_order_modifier`、`fnb_order_component` | FOOD-API-042、FOOD-API-043、FOOD-API-123、FOOD-API-125、FOOD-API-132、FOOD-API-133 | 源行/批次追踪已补；当前核心Item缺源行映射，需正式扩展合同 |
| FNB-D06 | 尚缺专项设计 | `fnb_return`、`fnb_return_item`、`fnb_charge_intent` | FOOD-API-061、FOOD-API-062、FOOD-API-063 | 赠菜/换菜/缺货替换的顾客确认和原新源行关系未定义专用命令；不能仅凭调整或退餐接口宣称覆盖 |
| FNB-D07 | 依赖共享/核心合同 | `fnb_order_batch`、`fnb_charge_intent` | FOOD-API-042、FOOD-API-058、FOOD-API-124、FOOD-API-125、FOOD-API-132、FOOD-API-135 | 先付权威回调与核心取消竞争；UNKNOWN恢复不能重建 |
| FNB-D08 | 依赖共享/核心合同 | `fnb_check`、`fnb_order_batch`、`fnb_settlement` | FOOD-API-031、FOOD-API-042、FOOD-API-056、FOOD-API-058 | 后付受理、库存承诺、未付堂食交付、超时取消守卫需核心扩展，不能用延长支付期限代替 |
| FNB-D09 | 尚缺专项设计 | `fnb_check_cart`、`fnb_order_batch` | FOOD-API-038、FOOD-API-041、FOOD-API-042 | scheduled_at位置已补；预售截止/产能预占/定时派工的正式策略及恢复合同仍待细化 |

### MOD-04 厨房与履约管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-E01 | 目标已定义 | `fnb_kitchen_ticket`、`fnb_kitchen_task` | FOOD-API-044、FOOD-API-045 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-E02 | 目标已定义 | `fnb_kitchen_task`、`fnb_service_event` | FOOD-API-048、FOOD-API-049、FOOD-API-050 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-E03 | 目标已定义 | `fnb_kitchen_task`、`fnb_service_event` | FOOD-API-046、FOOD-API-047 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-E04 | 目标已定义 | `fnb_course`、`fnb_kitchen_task` | FOOD-API-053、FOOD-API-054、FOOD-API-048、FOOD-API-049 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-E05 | 尚缺专项设计 | `fnb_service_event`、`fnb_kitchen_task` | FOOD-API-046、FOOD-API-047、FOOD-API-055 | 出品/上菜已分轨，撤回交接和漏菜反馈动作需明确数量冲销规则及端点，不以负qty复用serve |
| FNB-E06 | 尚缺专项设计 | `fnb_kitchen_task`、`fnb_order_component` | FOOD-API-044、FOOD-API-046、FOOD-API-055 | 有组件与跨工位任务，但齐套/打包清单确认缺专用合同，不能仅全部任务ready替代人工打包核对 |
| FNB-E07 | 依赖共享/核心合同 | `fnb_order_batch` | FOOD-API-055 | 叫号/核销/配送状态复用对应履约配送合同；handover不能代替叫号及防重核销全部步骤 |
| FNB-E08 | 依赖共享/核心合同 | `fnb_order_batch` | FOOD-API-055 | 叫号/核销/配送状态复用对应履约配送合同；handover不能代替叫号及防重核销全部步骤 |
| FNB-E09 | 依赖共享/核心合同 | `fnb_kitchen_task`、`fnb_service_event`、`fnb_return_item` | FOOD-API-051、FOOD-API-052、FOOD-API-062、FOOD-API-063 | 制作损耗及退餐回库由库存权威判定；重做不自动计新增销量 |
| FNB-E10 | 尚缺专项设计 | `fnb_store_policy`、`fnb_kitchen_task` | FOOD-API-044、FOOD-API-081 | 工位队列已有；超时阈值/拥堵暂停的配置、告警订阅与恢复命令尚未明确 |

### MOD-05 收银与结账管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-C07 | 依赖共享/核心合同 | `fnb_settlement`、`fnb_settlement_item`、`fnb_charge_intent` | FOOD-API-056、FOOD-API-057、FOOD-API-058、FOOD-API-059、FOOD-API-060、FOOD-API-140 | 主责MOD-05；桌台merge/split只改服务归属，资金拆合必须核心应收范围及分配合同 |
| FNB-F01 | 依赖共享/核心合同 | `fnb_settlement`、`fnb_settlement_item` | FOOD-API-056、FOOD-API-057、FOOD-API-059、FOOD-API-060、FOOD-API-134、FOOD-API-140 | 冻结范围及防重复占应收需核心权威仲裁；预结不是已付款 |
| FNB-F02 | 依赖共享/核心合同 | `fnb_charge_intent`、`fnb_cash_shift`、`fnb_cash_payment_link` | FOOD-API-058、FOOD-API-068、FOOD-API-070、FOOD-API-071、FOOD-API-135 | 单根支付可适配；现金仍遵守完整门店单影响集限制，班次/证据必须齐全 |
| FNB-F03 | 依赖共享/核心合同 | `fnb_settlement`、`fnb_settlement_item`、`fnb_charge_intent` | FOOD-API-056、FOOD-API-058、FOOD-API-140 | 跨根、按人按菜AA、混合支付及会员资产原路退款分配需核心合同；单根成功不能markPaid其他单 |
| FNB-F04 | 依赖共享/核心合同 | `fnb_settlement`、`fnb_settlement_item`、`fnb_charge_intent` | FOOD-API-056、FOOD-API-058、FOOD-API-140 | 跨根、按人按菜AA、混合支付及会员资产原路退款分配需核心合同；单根成功不能markPaid其他单 |
| FNB-F05 | 依赖共享/核心合同 | `fnb_settlement`、`fnb_settlement_item`、`fnb_charge_intent` | FOOD-API-056、FOOD-API-058、FOOD-API-140 | 跨根、按人按菜AA、混合支付及会员资产原路退款分配需核心合同；单根成功不能markPaid其他单 |
| FNB-F06 | 依赖共享/核心合同 | `fnb_charge_intent` | FOOD-API-061 | 折扣抹零免单及税费小费归权威报价/审批；小费保留P4且一期不收超额，不能覆盖旧成交快照 |
| FNB-F07 | 依赖共享/核心合同 | `fnb_charge_intent` | FOOD-API-061 | 折扣抹零免单及税费小费归权威报价/审批；小费保留P4且一期不收超额，不能覆盖旧成交快照 |
| FNB-F08 | 依赖共享/核心合同 | `fnb_charge_intent`、`fnb_room_charge_intent` | FOOD-API-061、FOOD-API-091 | 企业挂账及房账须权威应收/信用核销合同；不能通过餐饮调整伪装已付款 |
| FNB-F09 | 依赖共享/核心合同 | 共享服务/运行合同 | 待专项合同，不假造端点 | 押金器具租赁引用共享资金/资源合同，当前未设计完整动作和引用模型；押金不得计营收，须专项细化后启用 |
| FNB-F10 | 依赖共享/核心合同 | 共享服务/运行合同 | 待专项合同，不假造端点 | 税务发票/电子收据/冲红由共享票据服务提供正式合同；普通打印不是发票 |

### MOD-06 退餐与售后管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-G01 | 依赖共享/核心合同 | `fnb_check_cart_item`、`fnb_order_batch` | FOOD-API-040、FOOD-API-124、FOOD-API-126 | 草稿归档已定义；正式取消受核心支付/制作守卫约束 |
| FNB-G02 | 依赖共享/核心合同 | `fnb_return`、`fnb_return_item` | FOOD-API-062、FOOD-API-063、FOOD-API-064、FOOD-API-136、FOOD-API-137 | 当前只可整门店单全额；未付应收调整/部分退分摊均需合同。退款按原请求恢复，厨房结果分轨 |
| FNB-G03 | 依赖共享/核心合同 | `fnb_return`、`fnb_return_item` | FOOD-API-062、FOOD-API-063、FOOD-API-064、FOOD-API-136、FOOD-API-137 | 当前只可整门店单全额；未付应收调整/部分退分摊均需合同。退款按原请求恢复，厨房结果分轨 |
| FNB-G04 | 依赖共享/核心合同 | `fnb_return`、`fnb_return_item` | FOOD-API-062、FOOD-API-063、FOOD-API-064、FOOD-API-136、FOOD-API-137 | 当前只可整门店单全额；未付应收调整/部分退分摊均需合同。退款按原请求恢复，厨房结果分轨 |
| FNB-G05 | 依赖共享/核心合同 | `fnb_return`、`fnb_return_item` | FOOD-API-062、FOOD-API-063、FOOD-API-064、FOOD-API-136、FOOD-API-137 | 当前只可整门店单全额；未付应收调整/部分退分摊均需合同。退款按原请求恢复，厨房结果分轨 |
| FNB-G06 | 尚缺专项设计 | `fnb_action_log`、`fnb_order_batch` | FOOD-API-108、FOOD-API-125 | 错单重开/离线补录认领关系及申请审批命令未定义，不能删除旧单重造销量 |
| FNB-G07 | 依赖共享/核心合同 | `fnb_close_adjustment`、`fnb_charge_intent` | FOOD-API-076、FOOD-API-061 | 关账后更正有模型；反结账和坏账必须权威应收审批合同，不重写原签核 |
| FNB-G08 | 依赖共享/核心合同 | `fnb_action_log` | FOOD-API-108 | 审计表与查询已设计；复用全平台不可篡改审计存储/权限，餐饮本地日志本身不构成不可抵赖证据 |

### MOD-07 打印、通知与设备管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-A08 | 尚缺专项设计 | `prn_device`、`prn_route` | FOOD-API-065、FOOD-API-066 | 设备自检/开班检查聚合查询尚无明确请求响应；不可用打印任务接口代替，模块07实施前补齐 |
| FNB-H01 | 尚缺专项设计 | `prn_device`、`prn_template`、`prn_route` | FOOD-API-065 | 打印配置表已设计，设备绑定/模板版本/路由维护与测试端点尚缺明确共享合同 |
| FNB-H02 | 依赖共享/核心合同 | `prn_job`、`prn_template` | FOOD-API-065、FOOD-API-066、FOOD-API-067 | 内容由餐饮冻结业务凭据，投递归共享打印；日结/交班票依赖对应模块真实版本 |
| FNB-H03 | 依赖共享/核心合同 | `prn_job`、`prn_template` | FOOD-API-065、FOOD-API-066、FOOD-API-067 | 内容由餐饮冻结业务凭据，投递归共享打印；日结/交班票依赖对应模块真实版本 |
| FNB-H04 | 依赖共享/核心合同 | `prn_job`、`prn_template` | FOOD-API-065、FOOD-API-066、FOOD-API-067 | 内容由餐饮冻结业务凭据，投递归共享打印；日结/交班票依赖对应模块真实版本 |
| FNB-H05 | 目标已定义 | `prn_job`、`prn_attempt` | FOOD-API-066、FOOD-API-067 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-H06 | 尚缺专项设计 | `prn_device` | 待专项合同，不假造端点 | KDS订阅/通知/语音和远程设备诊断权限、配置下发、状态失效合同未定义；不能以任务查询代替推送治理 |
| FNB-H07 | 尚缺专项设计 | `prn_device` | 待专项合同，不假造端点 | KDS订阅/通知/语音和远程设备诊断权限、配置下发、状态失效合同未定义；不能以任务查询代替推送治理 |

### MOD-08 班次与现金管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-I01 | 目标已定义 | `fnb_cash_shift`、`fnb_cash_entry`、`fnb_cash_payment_link`、`fnb_cash_count` | FOOD-API-068、FOOD-API-069、FOOD-API-070、FOOD-API-071 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-I02 | 目标已定义 | `fnb_cash_shift`、`fnb_cash_entry`、`fnb_cash_payment_link`、`fnb_cash_count` | FOOD-API-068、FOOD-API-069、FOOD-API-070、FOOD-API-071 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-I03 | 目标已定义 | `fnb_cash_shift`、`fnb_cash_entry`、`fnb_cash_payment_link`、`fnb_cash_count` | FOOD-API-068、FOOD-API-069、FOOD-API-070、FOOD-API-071 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-I07 | 尚缺专项设计 | `fnb_cash_shift`、`fnb_close_exception` | FOOD-API-071、FOOD-API-074 | 班次交接有入口，跨班未收责任移交/认领需细化非现金应收责任合同，不迁移原营业日 |

### MOD-09 营业日、日结与对账管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-I04 | 依赖共享/核心合同 | `fnb_business_day`、`fnb_close_run`、`fnb_close_balance`、`fnb_close_exception` | FOOD-API-072、FOOD-API-073 | 预结快照引用权威对账和支付证据；通道账期不等同营业日 |
| FNB-I05 | 依赖共享/核心合同 | `fnb_business_day`、`fnb_close_run`、`fnb_close_balance`、`fnb_close_exception` | FOOD-API-072、FOOD-API-073 | 预结快照引用权威对账和支付证据；通道账期不等同营业日 |
| FNB-I06 | 目标已定义 | `fnb_close_run`、`fnb_close_balance` | FOOD-API-072、FOOD-API-073、FOOD-API-075 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-I08 | 依赖共享/核心合同 | `fnb_close_exception`、`fnb_close_run` | FOOD-API-074、FOOD-API-075 | 例外需获准审批；资金UNKNOWN只能移交证据，不能改成成功 |
| FNB-I09 | 目标已定义 | `fnb_close_adjustment`、`fnb_close_run` | FOOD-API-076 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-I10 | 依赖共享/核心合同 | `fnb_report_run`、`fnb_close_run` | FOOD-API-080 | 复用任务水位/幂等补跑；投影重建不等于重新签核或重开钱箱 |

### MOD-10 经营报表管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-J01 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J02 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J03 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J04 | 依赖共享/核心合同 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 应收账龄依赖共享AR核销流水 |
| FNB-J05 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J06 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J07 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J08 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J09 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J10 | 依赖共享/核心合同 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 会员充值/本金/赠金/消费沿资产权威，不重复记销售 |
| FNB-J11 | 依赖共享/核心合同 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily`、`fnb_recipe`、`fnb_recipe_line`、`fnb_recipe_adjustment`、`fnb_production_consumption`、`fnb_production_consumption_line` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 配方/制作理论耗用/真实回执分开；单位数量及历史估值合同PENDING，当前不能把领料当逐菜实耗或估算为实际成本。 |
| FNB-J12 | 依赖共享/核心合同 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 预约排队/客诉的权威事实及分母合同需补齐 |
| FNB-J13 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-L06 | 依赖共享/核心合同 | `fnb_metric_definition`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079 | 专项报表只对已开通能力开放，依赖对应事实和口径定义 |

### MOD-11 专项业态管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-D10 | 依赖共享/核心合同 | `fnb_banquet_plan` | FOOD-API-087、FOOD-API-088 | 宴会保证人数、实际加席及收费依赖预约/报价资金合同；冻结菜单与计划 |
| FNB-L01 | 依赖共享/核心合同 | `fnb_dining_entitlement`、`fnb_charge_intent` | FOOD-API-085、FOOD-API-086、FOOD-API-061 | 人数限时规则已有；频控/最后点单在提交守卫校验，超时浪费费用需核心报价和审批 |
| FNB-L02 | 依赖共享/核心合同 | `fnb_dining_entitlement`、`fnb_charge_intent` | FOOD-API-085、FOOD-API-086、FOOD-API-061 | 人数限时规则已有；频控/最后点单在提交守卫校验，超时浪费费用需核心报价和审批 |
| FNB-L03 | 依赖共享/核心合同 | `fnb_dining_entitlement`、`fnb_charge_intent` | FOOD-API-085、FOOD-API-086、FOOD-API-061 | 人数限时规则已有；频控/最后点单在提交守卫校验，超时浪费费用需核心报价和审批 |
| FNB-L04 | 依赖共享/核心合同 | `fnb_bottle_custody`、`fnb_custody_entry` | FOOD-API-089、FOOD-API-090 | 寄存归属及授权须实名/凭据合同；超领CAS拒绝，报损不等同销售 |
| FNB-L05 | 依赖共享/核心合同 | `fnb_room_charge_intent` | FOOD-API-091 | PMS授权、应收转挂及原键查询撤销需专项合同，UNKNOWN不能结清 |

### MOD-12 第三方接入与离线营业

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-K02 | 尚缺专项设计 | `fnb_external_mapping`、`fnb_integration_exception` | FOOD-API-082、FOOD-API-083 | 仅映射与异常查询，第三方订单导入/回调鉴权/商品同步/配送及对账适配仍需选定供应商协议；不能宣称已完整接入 |
| FNB-K03 | 尚缺专项设计 | `fnb_edge_lease`、`fnb_sync_batch`、`fnb_integration_exception` | FOOD-API-084 | 批次认领已有；租约获取/续租/隔离、离线可售额度与冲突处理协议未完整定义，需专项合同并实机演练 |
| FNB-K04 | 尚缺专项设计 | `fnb_edge_lease`、`fnb_sync_batch`、`fnb_integration_exception` | FOOD-API-084 | 批次认领已有；租约获取/续租/隔离、离线可售额度与冲突处理协议未完整定义，需专项合同并实机演练 |
| FNB-K05 | 依赖共享/核心合同 | `fnb_store_policy` | FOOD-API-081 | 双部署本地远程同合同、开关及版本灰度需装配/CI验证；HTTP接口不能证明可独立部署 |
| FNB-K06 | 依赖共享/核心合同 | `fnb_store_policy` | FOOD-API-081 | 双部署本地远程同合同、开关及版本灰度需装配/CI验证；HTTP接口不能证明可独立部署 |
| FNB-K07 | 依赖共享/核心合同 | `fnb_action_log` | FOOD-API-081、FOOD-API-083、FOOD-API-108 | 沿底座隔离、脱敏、审计和导出权限；平台诊断无营业收退款权 |
| FNB-K08 | 依赖共享/核心合同 | 共享服务/运行合同 | 待专项合同，不假造端点 | 备份恢复告警灾备为运行合同和演练，不能伪造餐饮CRUD接口覆盖 |

### MOD-13 营业配置与权限治理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-A01 | 依赖共享/核心合同 | `fnb_store_policy`、`fnb_action_log` | FOOD-API-001、FOOD-API-081 | 沿现有身份/门店授权及共享审批；金额阈值和权限种子需实施时逐项登记，不能由客户端自报主体或审批人 |
| FNB-A02 | 目标已定义 | `fnb_store_policy`、`fnb_service_calendar`、`fnb_business_day` | FOOD-API-001、FOOD-API-002、FOOD-API-003 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-A03 | 目标已定义 | `fnb_store_policy`、`fnb_config_revision` | FOOD-API-001、FOOD-API-002、FOOD-API-081 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-A05 | 依赖共享/核心合同 | `fnb_store_policy`、`fnb_check_cart` | FOOD-API-001、FOOD-API-002、FOOD-API-038、FOOD-API-041 | 渠道/履约/先后付独立字段；核心需确认堂食和后付可用合同 |
| FNB-A06 | 依赖共享/核心合同 | `fnb_store_policy`、`fnb_service_calendar`、`fnb_sales_restriction` | FOOD-API-002、FOOD-API-003、FOOD-API-022 | 暂停/节日限制可配置；全局结构化营业时间与接单容量依赖共享合同，当前openHours文本不能假定覆盖多时段多时区硬校验 |
| FNB-A07 | 依赖共享/核心合同 | `fnb_store_policy`、`fnb_action_log` | FOOD-API-001、FOOD-API-081 | 沿现有身份/门店授权及共享审批；金额阈值和权限种子需实施时逐项登记，不能由客户端自报主体或审批人 |

## 九、增强跨域合同（目标交易合同与已实现只读管理分开验收）

字段类型见上方DTO字典。主体、渠道由可信服务身份解析；终端不可自报价格策略授权。

| 权威 | 输入DTO | 输出DTO | 实现状态 | 失败与恢复 |
|---|---|---|---|---|
| pricing / PLATFORM product/quote | ChannelQuoteReq | ChannelQuoteResult | PENDING | BAD_REQUEST/FORBIDDEN/NOT_FOUND/CONFLICT/UNAVAILABLE/UNKNOWN；用原requestKey/operationRef查询UNKNOWN；不可另起业务键重发 |
| inventory / INVENTORY | MaterialConsumptionReq | MaterialConsumptionResult | PENDING | BAD_REQUEST/FORBIDDEN/NOT_FOUND/CONFLICT/UNAVAILABLE/UNKNOWN；用原requestKey/operationRef查询UNKNOWN；不可另起业务键重发 |
| specValues / PLATFORM original product/spec dictionary | SpecValueCatalogInternalApi.BatchLookup | SpecValueCatalogQueryPort.SpecValueFact[] | IMPLEMENTED_IDENTITY_ONLY | BAD_REQUEST/FORBIDDEN/NOT_FOUND/CONFLICT/UNAVAILABLE；纯只读查询，无写入UNKNOWN；权威不可用时失败，不伪造空数组 |

Keeta：首批规划渠道，按用户决定暂不接入；不注册真实适配器或同步任务。真实报价和库存合同未验收前，增强交易保持关闭。

## 十、历史结账与售后切片（保留原验收范围）

完整目标保留在前文。以下保留既有切片记录，原证据见开发计划§2.4和§6；本批明确排除支付执行。POSTPAY及当前非支付实际路由见第十一节，历史切片不改标为本批全部完成。

| 接口 | 输入 | 输出 | 授权及剩余边界 |
|---|---|---|---|
| `POST /biz/x/food/stores/{storeNo}/quotes` | DiningQuoteReq{cartNo,version,selection?:{couponNos,storedValueAccountNo,pointsToUse}}；Idempotency-Key | PricingCheckoutPort.QuoteVO{quoteNo,status,expiresAt,sourceVersion,sourceHash,currency,lines,totals,confirmationDigest} | biz:order:view；认证主体/当前门店由FoodBizStoreScope解析；actor来自登录；不授予资金写权限；完整原购物车受控报价；权益/活动及复杂场景按owner门控；不接原目标lines/buyer/价格字段 |
| `GET /biz/x/food/quotes/{quoteNo}` | query cartNo,version | PricingCheckoutPort.QuoteVO{quoteNo,status,expiresAt,sourceVersion,sourceHash,currency,lines,totals,confirmationDigest} | biz:order:view；认证主体/当前门店由FoodBizStoreScope解析；actor来自登录；不授予资金写权限；绑定原车及版本读取，不是任意报价号授权 |
| `POST /biz/x/food/quotes/{quoteNo}:confirm` | DiningQuoteConfirmReq{cartNo,version,confirmationDigest} | PricingCheckoutPort.QuoteVO{quoteNo,status,expiresAt,sourceVersion,sourceHash,currency,lines,totals,confirmationDigest} | biz:order:view；认证主体/当前门店由FoodBizStoreScope解析；actor来自登录；不授予资金写权限；仅revalidate；不扣款/核销权益，不代替正式提交 |
| `POST /mp/x/food/stores/{storeNo}/quotes` | DiningQuoteReq{cartNo,version,selection?:{couponNos,storedValueAccountNo,pointsToUse}}；Idempotency-Key；optional query sessionNo/header X-Food-Session | PricingCheckoutPort.QuoteVO{quoteNo,status,expiresAt,sourceVersion,sourceHash,currency,lines,totals,confirmationDigest} | CartAccess由consumer登录或原sessionNo/X-Food-Session生成；原购物车服务逐次鉴权；完整原购物车受控报价；权益/活动及复杂场景按owner门控；不接原目标lines/buyer/价格字段 |
| `GET /mp/x/food/quotes/{quoteNo}` | query cartNo,version；optional query sessionNo/header X-Food-Session | PricingCheckoutPort.QuoteVO{quoteNo,status,expiresAt,sourceVersion,sourceHash,currency,lines,totals,confirmationDigest} | CartAccess由consumer登录或原sessionNo/X-Food-Session生成；原购物车服务逐次鉴权；绑定原车及版本读取，不是任意报价号授权 |
| `POST /mp/x/food/quotes/{quoteNo}:confirm` | DiningQuoteConfirmReq{cartNo,version,confirmationDigest}；optional query sessionNo/header X-Food-Session | PricingCheckoutPort.QuoteVO{quoteNo,status,expiresAt,sourceVersion,sourceHash,currency,lines,totals,confirmationDigest} | CartAccess由consumer登录或原sessionNo/X-Food-Session生成；原购物车服务逐次鉴权；仅revalidate；不扣款/核销权益，不代替正式提交 |
| `POST /mp/x/food/stores/{storeNo}/batches` | CreateDiningOrderReq{cartNo,version,quoteNo,confirmationDigest,payMode}；Idempotency-Key；optional session/token经原鉴权 | DiningOrderBatchVO{batchNo,storeNo,checkNo,cartNo,cartVersion,quoteNo,status,payMode,businessDate,orderNo,subOrderNo,coreStatus,currency,amountMinor,version} | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；仅H5/PICKUP/PREPAY、MAIN整数完整车；DTO接受POSTPAY但服务拒绝；无selectedLineRefs/splitByPerson |
| `GET /mp/x/food/batches/{batchNo}` | optional query sessionNo/header X-Food-Session | DiningOrderBatchVO{batchNo,storeNo,checkNo,cartNo,cartVersion,quoteNo,status,payMode,businessDate,orderNo,subOrderNo,coreStatus,currency,amountMinor,version} | CartAccess由consumer登录或原sessionNo/X-Food-Session生成；原购物车服务逐次鉴权；原本人范围；UNKNOWN不是受理/支付成功 |
| `GET /biz/x/food/batches/{batchNo}` | 无body | DiningOrderBatchVO{batchNo,storeNo,checkNo,cartNo,cartVersion,quoteNo,status,payMode,businessDate,orderNo,subOrderNo,coreStatus,currency,amountMinor,version} | biz:order:view；认证主体/当前门店由FoodBizStoreScope解析；actor来自登录；不授予资金写权限；B端042正式提交仍未实现 |
| `POST /mp/x/food/batches/{batchNo}/settlements` | CreateDiningSettlementReq{confirmationDigest}；Idempotency-Key | DiningSettlementVO{settlementNo,storeNo,batchNo,checkNo,orderNo,subOrderNo,status,currency,dueMinor,scopeHash,confirmationDigest,version,batchStatus,coreOrderStatus,payment,readyToAccept} | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；单完整原批次/单支付根增量；不实现140台账范围冻结，不支持AA/整桌代付/跨根 |
| `GET /mp/x/food/settlements/{settlementNo}` | 无body/query | DiningSettlementVO{settlementNo,storeNo,batchNo,checkNo,orderNo,subOrderNo,status,currency,dueMinor,scopeHash,confirmationDigest,version,batchStatus,coreOrderStatus,payment,readyToAccept} | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；customerQuery查询原支付并合并持久状态，非纯本地只读；无目标confirmedAmount/pendingIntentNos字段 |
| `POST /mp/x/food/settlements/{settlementNo}:pay` | PayDiningSettlementReq{version,confirmationDigest,payChannel}；Idempotency-Key | DiningSettlementVO{settlementNo,storeNo,batchNo,checkNo,orderNo,subOrderNo,status,currency,dueMinor,scopeHash,confirmationDigest,version,batchStatus,coreOrderStatus,payment,readyToAccept} | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；canonical路由一致；仅真实owner支持线上渠道；无客户端amount/tool/现金；单根单尝试，UNKNOWN按原意图查回 |
| `POST /mp/x/food/settlements/{settlementNo}:finalize` | 无body/query | DiningSettlementVO{settlementNo,storeNo,batchNo,checkNo,orderNo,subOrderNo,status,currency,dueMinor,scopeHash,confirmationDigest,version,batchStatus,coreOrderStatus,payment,readyToAccept} | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；依原核心支付/库存证明推进；厨房实际处理独立；非B端060 |
| `POST /mp/x/food/settlements/{settlementNo}:cancel` | DiningSettlementVersionReq{version} | DiningSettlementVO{settlementNo,storeNo,batchNo,checkNo,orderNo,subOrderNo,status,currency,dueMinor,scopeHash,confirmationDigest,version,batchStatus,coreOrderStatus,payment,readyToAccept} | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；受未知/成功支付仲裁约束；非B端059，不以本地状态冒充渠道关单 |
| `GET /biz/x/food/settlements/{settlementNo}` | 无body | DiningSettlementVO{settlementNo,storeNo,batchNo,checkNo,orderNo,subOrderNo,status,currency,dueMinor,scopeHash,confirmationDigest,version,batchStatus,coreOrderStatus,payment,readyToAccept} | biz:order:view；认证主体/当前门店由FoodBizStoreScope解析；actor来自登录；不授予资金写权限；仅门店读取；056/058/059/060/061写入口未实现；响应非目标包装 |
| `POST /mp/x/food/stores/{storeNo}/returns` | DiningReturnApplyReq{batchNo,type:REFUND_ONLY/RETURN_REFUND,reason,reasonCode?,images[],lines:[{sourceLineRef,quantity}]}；Idempotency-Key | DiningReturnVO{returnNo,batchNo,storeNo,status,financialStatus,kitchenStatus,coreCaseRef,reason,lines,financialCase}；没有version | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；选中原MAIN整数行退量，走原核心售后/退款上限；无客户端金额/任意itemId/厨房命令；不实现B审批执行；部分/余量必须原子单WAIT_FULFILL且所有原行shippedQty为0，混合履约拒绝 |
| `GET /mp/x/food/returns/{returnNo}` | 无body/query | DiningReturnVO{returnNo,batchNo,storeNo,status,financialStatus,kitchenStatus,coreCaseRef,reason,lines,financialCase}；没有version | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；查询原核心售后并刷新持久状态；financialCase可空；厨房独立，不返回目标version |
| `POST /mp/x/food/returns/{returnNo}:cancel` | 空body且无query | DiningReturnVO{returnNo,batchNo,storeNo,status,financialStatus,kitchenStatus,coreCaseRef,reason,lines,financialCase}；没有version | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；撤回本人原售后；未知申请保留CANCEL_PENDING并查原键，不是退款执行 |
| `GET /biz/x/food/returns/{returnNo}` | 无body/query | DiningReturnVO{returnNo,batchNo,storeNo,status,financialStatus,kitchenStatus,coreCaseRef,reason,lines,financialCase}；没有version | biz:order:view；认证主体/当前门店由FoodBizStoreScope解析；actor来自登录；不授予资金写权限；仅本地授权读取，financialCase可空；062/063未实现，不返回目标version |

未实现目标：FOOD-API-042、FOOD-API-056、FOOD-API-058、FOOD-API-059、FOOD-API-060、FOOD-API-061、FOOD-API-062、FOOD-API-063、FOOD-API-140。台账范围结账FOOD-API-140仍未开放；当前按单一原批次冻结，不能代表AA或跨根结账。

## 十一、本批非支付实际API与目标对账

50条实际公开biz路由，均为 **CODED_PENDING_VERIFICATION**。该标记是逐接口完整目标/营业验收状态，后端批次编译及测试结果独立记录在verification和原TDD收据。此处逐项记录现有Controller/DTO/权限，与前文目标API并列；目标路径相同不表示DTO或完整业务验收一致。原始接口编号、表及历史V1迁移哈希保留。

历史早期差异（后续已扩展员工及匿名/设备POSTPAY，当前入口见实际公开API对照）：该切片POSTPAY仅沿真实已登录H5/PICKUP身份和门店POSTPAY策略，采用原库存lifecycle3接受未付款履约；父单WAIT_PAY、paidAt为空。额度消费、FOOD ACCEPTED及厨房出站事实同本域事务，不代表收款。

- No actual printer hardware implementation
- No KDS/SSE/push subscription or notification delivery proof from polling APIs
- Generic report exports API078/079 and close exception carry API074 have no matching new public route
- Printing and business close target paths differ and require explicit target reconciliation, rather than silently assigning exact implementation status
- POSTPAY仅已登录H5/PICKUP现有身份：真实lifecycle3 reserve/acceptUnpaid，原父单保持WAIT_PAY/paidAt为空，原子单履约可推进；不能称已收款。
- FOOD ACCEPTED、额度consume及DINING_ORDER_ACCEPTED出站事实同本域事务；物理出纸、通知推送与财务验收必须由各owner真回执另证。
- 原料执行只接受真实冻结叶子计划；缺少既有配方权威计划时保留BLOCKED，不能通过成品/原料双扣或虚构成本完成。
- 日结/报表仅输出已核实源事实与完整性状态，财务未闭合不补造SIGNED/资金成功；异步导出和异常结转目标仍未交付。
- 新就座锁定原票后，在同一food primary事务内调用CheckService.openQueuedCheck，仅限内部Java调用，不能经HTTP/remote；原开台、SEATED、queue动作与outbox同提交。冲突整体回滚，原CALLED票可重选；不把永久SEATING作为新流程。
- 旧SEATING仅兼容原冻结命令/OPEN_CHECK原键回执恢复；未知保持未知，不创建替代开台。新seat走同primary原子事务。
- 首次打印身份不包含客户端Idempotency-Key；ORDER/KITCHEN/RETURN按原单据+scene/station固定，PREBILL按原文档snapshot版本；换key同内容不新增首次任务。额外纸张必须走关联原任务+原因+操作人的审计reprint；UNKNOWN禁止普通retry。
- 制作耗用列表API153已编码真实scope-bound SQL分页/COUNT；共享PageData<long page/size>及ConsumptionVO与目标模型差异仍显式登记；查询不触发原料出库、恢复或补造成本。
- DINING_ORDER_ACCEPTED出站处理先幂等持久化原MAKE票，再按已配置ORDER/KITCHEN纸张route入队；无route不造任务。claim/ACK才提供设备领取与回执，自动入队不代表已出纸。
- KDS默认未指定batchNo时仅返回ACCEPTED批次的未CLOSED票和非SERVED/VOIDED任务；显式batchNo可查历史，保留原MAKE/REMAKE及原票关系；轮询不等于实时推送。

### 11.1 实际路由、权限与目标映射

| 实际编号 | 实际方法与路径 | 目标编号/对齐状态 | 请求DTO或参数 | data返回类型 | 实际权限 |
|---|---|---|---|---|---|
| FOOD-NONPAY-IMPL-001 | `POST /biz/x/food/queue-tickets` | FOOD-API-157 / EXACT_PATH | ai.neargo.shop.food.dto.QueueContracts.IssueReq | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-002 | `GET /biz/x/food/queue-tickets` | FOOD-API-158 / EXACT_PATH | query:businessDate LocalDate; query:status String; query:page Integer; query:size Integer | `ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.QueueContracts.QueueVO>` | `biz:order:view` |
| FOOD-NONPAY-IMPL-003 | `GET /biz/x/food/queue-tickets/{ticketNo}` | FOOD-API-159 / EXACT_PATH | path:ticketNo String | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:order:view` |
| FOOD-NONPAY-IMPL-004 | `POST /biz/x/food/queue-tickets:call-next` | FOOD-API-160 / EXACT_PATH | query:businessDate LocalDate | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-005 | `POST /biz/x/food/queue-tickets/{ticketNo}:call` | FOOD-API-161 / EXACT_PATH | ai.neargo.shop.food.dto.QueueContracts.ChangeReq | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-006 | `POST /biz/x/food/queue-tickets/{ticketNo}:cancel` | FOOD-API-162 / EXACT_PATH | ai.neargo.shop.food.dto.QueueContracts.ChangeReq | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-007 | `POST /biz/x/food/queue-tickets/{ticketNo}:no-show` | FOOD-API-163 / EXACT_PATH | ai.neargo.shop.food.dto.QueueContracts.ChangeReq | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-008 | `POST /biz/x/food/queue-tickets/{ticketNo}:seat` | FOOD-API-164 / EXACT_PATH | ai.neargo.shop.food.dto.QueueContracts.SeatQueueReq | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-009 | `POST /biz/x/food/queue-tickets/{ticketNo}:recover-seating` | FOOD-API-165 / EXACT_PATH | path:ticketNo String | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-010 | `GET /biz/x/food/stores/{storeNo}/kitchen/tasks` | FOOD-API-044 / EXACT_PATH | path:storeNo String; query:stationNo String; query:status String; query:page int; query:size int | `ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.KitchenContracts.TaskVO>` | `biz:receive` |
| FOOD-NONPAY-IMPL-011 | `POST /biz/x/food/tasks/{taskNo}:start` | FOOD-API-045 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-012 | `POST /biz/x/food/tasks/{taskNo}:ready` | FOOD-API-046 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-013 | `POST /biz/x/food/tasks/{taskNo}:serve` | FOOD-API-047 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-014 | `POST /biz/x/food/tasks/{taskNo}:hold` | FOOD-API-048 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-015 | `POST /biz/x/food/tasks/{taskNo}:fire` | FOOD-API-049 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-016 | `POST /biz/x/food/tasks/{taskNo}:urge` | FOOD-API-050 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-017 | `POST /biz/x/food/tasks/{taskNo}:void` | FOOD-API-051 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:aftersale` |
| FOOD-NONPAY-IMPL-018 | `POST /biz/x/food/tasks/{taskNo}:loss` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:stock` |
| FOOD-NONPAY-IMPL-019 | `POST /biz/x/food/tasks/{taskNo}:remake` | FOOD-API-052 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.RemakeReq | `ai.neargo.shop.food.dto.KitchenContracts.TicketVO` | `biz:aftersale` |
| FOOD-NONPAY-IMPL-020 | `GET /biz/x/food/tasks/{taskNo}/events` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:taskNo String | `java.util.List<ai.neargo.shop.food.dto.KitchenContracts.EventVO>` | `biz:receive` |
| FOOD-NONPAY-IMPL-021 | `POST /biz/x/food/checks/{checkNo}/courses` | FOOD-API-053 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.CourseReq | `ai.neargo.shop.food.dto.KitchenContracts.CourseVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-022 | `GET /biz/x/food/checks/{checkNo}/courses` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:checkNo String | `java.util.List<ai.neargo.shop.food.dto.KitchenContracts.CourseVO>` | `biz:receive` |
| FOOD-NONPAY-IMPL-023 | `POST /biz/x/food/courses/{courseNo}:act` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.food.dto.KitchenContracts.CourseActionReq | `ai.neargo.shop.food.dto.KitchenContracts.CourseVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-024 | `POST /biz/x/food/courses/{courseNo}:fire` | FOOD-API-054 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.CourseFireReq | `ai.neargo.shop.food.dto.KitchenContracts.CourseVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-025 | `POST /biz/x/food/batches/{batchNo}:handover` | FOOD-API-055 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.HandoverReq | `ai.neargo.shop.food.dto.KitchenContracts.HandoverVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-026 | `GET /biz/x/food/batches/{batchNo}/pickup-verification` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:batchNo String | `ai.neargo.shop.food.dto.DiningPickupContracts.PickupVerificationVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-027 | `POST /biz/x/food/batches/{batchNo}:verify-pickup` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.food.dto.DiningPickupContracts.VerifyPickupReq | `ai.neargo.shop.food.dto.DiningPickupContracts.PickupVerificationVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-028 | `POST /biz/x/food/print-jobs` | FOOD-API-065 / PATH_CHANGED | ai.neargo.shop.food.dto.PrintDocumentReq | `java.util.List<ai.neargo.shop.spi.printing.PrintPort.JobVO>` | `biz:verify` |
| FOOD-NONPAY-IMPL-029 | `GET /biz/x/food/print-jobs` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | query:afterJobNo String; query:limit int | `java.util.List<ai.neargo.shop.spi.printing.PrintPort.JobVO>` | `biz:order:view` |
| FOOD-NONPAY-IMPL-030 | `GET /biz/x/food/print-jobs/{jobNo}` | FOOD-API-066 / PATH_CHANGED | path:jobNo String | `ai.neargo.shop.spi.printing.PrintPort.JobVO` | `biz:order:view` |
| FOOD-NONPAY-IMPL-031 | `POST /biz/x/food/print-jobs/{jobNo}:retry` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:jobNo String | `ai.neargo.shop.spi.printing.PrintPort.JobVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-032 | `POST /biz/x/food/print-jobs/{jobNo}:reprint` | FOOD-API-067 / PATH_CHANGED | ai.neargo.shop.food.api.biz.BizPrintJobController.ReprintReq | `ai.neargo.shop.spi.printing.PrintPort.JobVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-033 | `POST /biz/x/food/print-devices` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.spi.printing.PrintPort.DeviceReq | `ai.neargo.shop.spi.printing.PrintPort.DeviceVO` | `biz:store:admin` |
| FOOD-NONPAY-IMPL-034 | `POST /biz/x/food/print-devices/{deviceNo}:heartbeat` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.food.api.biz.BizPrintDeviceController.HeartbeatReq | `ai.neargo.shop.spi.printing.PrintPort.DeviceVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-035 | `POST /biz/x/food/print-devices/{deviceNo}:claim` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:deviceNo String | `ai.neargo.shop.spi.printing.PrintPort.Claim` | `biz:verify` |
| FOOD-NONPAY-IMPL-036 | `POST /biz/x/food/print-devices/{deviceNo}:acknowledge` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.spi.printing.PrintPort.Acknowledge | `ai.neargo.shop.spi.printing.PrintPort.JobVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-037 | `GET /biz/x/food/print-configuration` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | 无Body | `ai.neargo.shop.spi.printing.PrintPort.Configuration` | `biz:store:admin` |
| FOOD-NONPAY-IMPL-038 | `POST /biz/x/food/print-configuration/templates` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.spi.printing.PrintPort.TemplateReq | `ai.neargo.shop.spi.printing.PrintPort.TemplateReq` | `biz:store:admin` |
| FOOD-NONPAY-IMPL-039 | `POST /biz/x/food/print-configuration/routes` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.spi.printing.PrintPort.RouteReq | `ai.neargo.shop.spi.printing.PrintPort.RouteReq` | `biz:store:admin` |
| FOOD-NONPAY-IMPL-040 | `POST /biz/x/food/business-days/{date}:open` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:date LocalDate | `ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO` | `biz:finance` |
| FOOD-NONPAY-IMPL-041 | `GET /biz/x/food/reports/business-day` | FOOD-API-077 / PARTIAL_TARGET_SUBSET | query:businessDate LocalDate; query:currency String | `ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO` | `biz:order:view` |
| FOOD-NONPAY-IMPL-042 | `POST /biz/x/food/closes:preview` | FOOD-API-072 / PATH_AND_INPUT_CHANGED | ai.neargo.shop.food.dto.BusinessCloseContracts.PreviewReq | `ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO` | `biz:finance` |
| FOOD-NONPAY-IMPL-043 | `GET /biz/x/food/closes/{closeNo}` | FOOD-API-073 / EXACT_PATH | path:closeNo String | `ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO` | `biz:order:view` |
| FOOD-NONPAY-IMPL-044 | `POST /biz/x/food/closes:sign` | FOOD-API-075 / PATH_AND_INPUT_CHANGED | ai.neargo.shop.food.dto.BusinessCloseContracts.SignReq | `ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO` | `biz:finance` |
| FOOD-NONPAY-IMPL-045 | `POST /biz/x/food/closes:adjust` | FOOD-API-076 / PATH_AND_INPUT_CHANGED | ai.neargo.shop.food.dto.BusinessCloseContracts.AdjustmentReq | `ai.neargo.shop.food.dto.OperationVO` | `biz:finance` |
| FOOD-NONPAY-IMPL-046 | `POST /biz/x/food/production-events/{eventNo}:consume` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:eventNo String | `ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO` | `biz:stock` |
| FOOD-NONPAY-IMPL-047 | `GET /biz/x/food/production-events/{eventNo}` | FOOD-API-154 / DIFFERENT_RESOURCE_KEY | path:eventNo String | `ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO` | `biz:order:view` |
| FOOD-NONPAY-IMPL-048 | `GET /biz/x/food/returns/{returnNo}/kitchen` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:returnNo String | `ai.neargo.shop.food.dto.KitchenReturnContracts.ReturnKitchenVO` | `biz:aftersale` |
| FOOD-NONPAY-IMPL-049 | `POST /biz/x/food/returns/{returnNo}/kitchen` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.food.dto.KitchenReturnContracts.HandleReq | `ai.neargo.shop.food.dto.KitchenReturnContracts.HandledVO` | `biz:aftersale` |
| FOOD-NONPAY-IMPL-050 | `GET /biz/x/food/stores/{storeNo}/production-consumptions` | FOOD-API-153 / EXACT_PATH | path:storeNo String; query:from LocalDate; query:to LocalDate; query:page Integer; query:size Integer; query:skuNo String; query:itemRef String; query:status String | `ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO>` | `biz:order:view` |

### 11.2 实际参数与合同差异

#### FOOD-NONPAY-IMPL-001 · BizQueueController.issue

`POST /biz/x/food/queue-tickets` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:46`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | body | `ai.neargo.shop.food.dto.QueueContracts.IssueReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-157 `POST /biz/x/food/queue-tickets`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：排队号不等于取餐号；notificationStatus是事实状态，不表示已发送通知。

#### FOOD-NONPAY-IMPL-002 · BizQueueController.list

`GET /biz/x/food/queue-tickets` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:53`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| query | businessDate | `java.time.LocalDate` | 是 | — | @RequestParam |
| query | status | `String` | 否 | — | @RequestParam(required=false) |
| query | page | `Integer` | 否 | — | @RequestParam(required=false) |
| query | size | `Integer` | 否 | — | @RequestParam(required=false) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.QueueContracts.QueueVO>`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-158 `GET /biz/x/food/queue-tickets`；EXACT_PATH。


边界：排队号不等于取餐号；notificationStatus是事实状态，不表示已发送通知。

#### FOOD-NONPAY-IMPL-003 · BizQueueController.get

`GET /biz/x/food/queue-tickets/{ticketNo}` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:60`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | ticketNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-159 `GET /biz/x/food/queue-tickets/{ticketNo}`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：排队号不等于取餐号；notificationStatus是事实状态，不表示已发送通知。

#### FOOD-NONPAY-IMPL-004 · BizQueueController.callNext

`POST /biz/x/food/queue-tickets:call-next` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:66`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| query | businessDate | `java.time.LocalDate` | 是 | — | @RequestParam |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-160 `POST /biz/x/food/queue-tickets:call-next`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：排队号不等于取餐号；notificationStatus是事实状态，不表示已发送通知。

#### FOOD-NONPAY-IMPL-005 · BizQueueController.call

`POST /biz/x/food/queue-tickets/{ticketNo}:call` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:73`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | ticketNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | body | `ai.neargo.shop.food.dto.QueueContracts.ChangeReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-161 `POST /biz/x/food/queue-tickets/{ticketNo}:call`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：排队号不等于取餐号；notificationStatus是事实状态，不表示已发送通知。

#### FOOD-NONPAY-IMPL-006 · BizQueueController.cancel

`POST /biz/x/food/queue-tickets/{ticketNo}:cancel` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:80`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | ticketNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | body | `ai.neargo.shop.food.dto.QueueContracts.ChangeReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-162 `POST /biz/x/food/queue-tickets/{ticketNo}:cancel`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：排队号不等于取餐号；notificationStatus是事实状态，不表示已发送通知。

#### FOOD-NONPAY-IMPL-007 · BizQueueController.noShow

`POST /biz/x/food/queue-tickets/{ticketNo}:no-show` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:87`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | ticketNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | body | `ai.neargo.shop.food.dto.QueueContracts.ChangeReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-163 `POST /biz/x/food/queue-tickets/{ticketNo}:no-show`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：排队号不等于取餐号；notificationStatus是事实状态，不表示已发送通知。

#### FOOD-NONPAY-IMPL-008 · BizQueueController.seat

`POST /biz/x/food/queue-tickets/{ticketNo}:seat` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:95`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | ticketNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | body | `ai.neargo.shop.food.dto.QueueContracts.SeatQueueReq` | 是 | — | @Valid @RequestBody |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-164 `POST /biz/x/food/queue-tickets/{ticketNo}:seat`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：新就座锁定原票后，在同一food primary事务内调用CheckService.openQueuedCheck，仅限内部Java调用，不能经HTTP/remote；原开台、SEATED、queue动作与outbox同提交。冲突整体回滚，原CALLED票可重选；不把永久SEATING作为新流程。

#### FOOD-NONPAY-IMPL-009 · BizQueueController.recover

`POST /biz/x/food/queue-tickets/{ticketNo}:recover-seating` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:102`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | ticketNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-165 `POST /biz/x/food/queue-tickets/{ticketNo}:recover-seating`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：旧SEATING仅兼容原冻结命令/OPEN_CHECK原键回执恢复；未知保持未知，不创建替代开台。新seat走同primary原子事务。

#### FOOD-NONPAY-IMPL-010 · BizKitchenTaskController.queue

`GET /biz/x/food/stores/{storeNo}/kitchen/tasks` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:52`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | storeNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| query | stationNo | `String` | 是 | — | @RequestParam @NotBlank @Size(max=32) |
| query | status | `String` | 否 | — | @RequestParam(required=false) |
| query | page | `int` | 否 | 1 | @RequestParam(defaultValue="1") |
| query | size | `int` | 否 | 20 | @RequestParam(defaultValue="20") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.KitchenContracts.TaskVO>`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-044 `GET /biz/x/food/stores/{storeNo}/kitchen/tasks`；EXACT_PATH。


边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-011 · BizKitchenTaskController.start

`POST /biz/x/food/tasks/{taskNo}:start` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:67`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-045 `POST /biz/x/food/tasks/{taskNo}:start`；EXACT_PATH。

- ACTUAL_INPUT_FIELDS_ADDED：`reason`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-012 · BizKitchenTaskController.ready

`POST /biz/x/food/tasks/{taskNo}:ready` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:76`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-046 `POST /biz/x/food/tasks/{taskNo}:ready`；EXACT_PATH。

- ACTUAL_INPUT_FIELDS_ADDED：`reason`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-013 · BizKitchenTaskController.serve

`POST /biz/x/food/tasks/{taskNo}:serve` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:85`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-047 `POST /biz/x/food/tasks/{taskNo}:serve`；EXACT_PATH。

- ACTUAL_INPUT_FIELDS_ADDED：`reason`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-014 · BizKitchenTaskController.hold

`POST /biz/x/food/tasks/{taskNo}:hold` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:94`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-048 `POST /biz/x/food/tasks/{taskNo}:hold`；EXACT_PATH。

- ACTUAL_INPUT_FIELDS_ADDED：`qty`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-015 · BizKitchenTaskController.fire

`POST /biz/x/food/tasks/{taskNo}:fire` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:103`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-049 `POST /biz/x/food/tasks/{taskNo}:fire`；EXACT_PATH。

- ACTUAL_INPUT_FIELDS_ADDED：`qty`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-016 · BizKitchenTaskController.urge

`POST /biz/x/food/tasks/{taskNo}:urge` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:112`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-050 `POST /biz/x/food/tasks/{taskNo}:urge`；EXACT_PATH。

- ACTUAL_INPUT_FIELDS_ADDED：`qty`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-017 · BizKitchenTaskController.voidTask

`POST /biz/x/food/tasks/{taskNo}:void` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:121`。权限：`biz:aftersale`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:aftersale。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-051 `POST /biz/x/food/tasks/{taskNo}:void`；EXACT_PATH。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`returnNo`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-018 · BizKitchenTaskController.loss

`POST /biz/x/food/tasks/{taskNo}:loss` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:130`。权限：`biz:stock`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:stock。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-019 · BizKitchenTaskController.remake

`POST /biz/x/food/tasks/{taskNo}:remake` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:139`。权限：`biz:aftersale`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:aftersale。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.RemakeReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.TicketVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-052 `POST /biz/x/food/tasks/{taskNo}:remake`；EXACT_PATH。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`approvalRef`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`eventNo`、`newTaskNo`、`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-020 · BizKitchenTaskController.events

`GET /biz/x/food/tasks/{taskNo}/events` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:147`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`java.util.List<ai.neargo.shop.food.dto.KitchenContracts.EventVO>`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-021 · BizCourseController.create

`POST /biz/x/food/checks/{checkNo}/courses` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCourseController.java:48`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | checkNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.CourseReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.CourseVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-053 `POST /biz/x/food/checks/{checkNo}/courses`；EXACT_PATH。


边界：源事实权限与版本守卫；本次为只读代码对账。

#### FOOD-NONPAY-IMPL-022 · BizCourseController.courses

`GET /biz/x/food/checks/{checkNo}/courses` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCourseController.java:55`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | checkNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`java.util.List<ai.neargo.shop.food.dto.KitchenContracts.CourseVO>`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：源事实权限与版本守卫；本次为只读代码对账。

#### FOOD-NONPAY-IMPL-023 · BizCourseController.act

`POST /biz/x/food/courses/{courseNo}:act` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCourseController.java:60`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | courseNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.CourseActionReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.CourseVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：源事实权限与版本守卫；本次为只读代码对账。

#### FOOD-NONPAY-IMPL-024 · BizCourseController.fire

`POST /biz/x/food/courses/{courseNo}:fire` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCourseController.java:67`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | courseNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.CourseFireReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.CourseVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-054 `POST /biz/x/food/courses/{courseNo}:fire`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`operationNo`、`resultRef`。

边界：源事实权限与版本守卫；本次为只读代码对账。

#### FOOD-NONPAY-IMPL-025 · BizDiningHandoverController.handover

`POST /biz/x/food/batches/{batchNo}:handover` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningHandoverController.java:45`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | batchNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.HandoverReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.HandoverVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-055 `POST /biz/x/food/batches/{batchNo}:handover`；EXACT_PATH。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`coreFulfillmentRef`。
- ACTUAL_INPUT_FIELDS_ADDED：`reason`。

边界：本地交接不自动等于核心交付；deliveryPending/coreDeliveryRef必须按实际返回值解释。

#### FOOD-NONPAY-IMPL-026 · BizDiningPickupVerificationController.get

`GET /biz/x/food/batches/{batchNo}/pickup-verification` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningPickupVerificationController.java:46`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | batchNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.DiningPickupContracts.PickupVerificationVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：读取/核销原核心履约收据；当前GET也要求Idempotency-Key，可能进行原证据对账，不可省略该头。

#### FOOD-NONPAY-IMPL-027 · BizDiningPickupVerificationController.verify

`POST /biz/x/food/batches/{batchNo}:verify-pickup` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningPickupVerificationController.java:53`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | batchNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.DiningPickupContracts.VerifyPickupReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.DiningPickupContracts.PickupVerificationVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：读取/核销原核心履约收据；当前GET也要求Idempotency-Key，可能进行原证据对账，不可省略该头。

#### FOOD-NONPAY-IMPL-028 · BizPrintJobController.enqueue

`POST /biz/x/food/print-jobs` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintJobController.java:46`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | req | `ai.neargo.shop.food.dto.PrintDocumentReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`java.util.List<ai.neargo.shop.spi.printing.PrintPort.JobVO>`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-065 `POST /biz/print/jobs`；PATH_CHANGED。共享打印目标以餐饮适配入口实现；仅ORDER/KITCHEN/PREBILL/RETURN，非通用共享biz入口。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`deviceNos`、`sourceEventNo`、`sourceRef`、`templateVersion`。
- ACTUAL_INPUT_FIELDS_ADDED：`sourceNo`、`stationNo`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`jobNos`、`status`。

边界：首次打印身份不包含客户端Idempotency-Key；ORDER/KITCHEN/RETURN按原单据+scene/station固定，PREBILL按原文档snapshot版本；换key同内容不新增首次任务。额外纸张必须走关联原任务+原因+操作人的审计reprint；UNKNOWN禁止普通retry。

#### FOOD-NONPAY-IMPL-029 · BizPrintJobController.list

`GET /biz/x/food/print-jobs` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintJobController.java:52`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| query | afterJobNo | `String` | 否 | — | @RequestParam(value="afterJobNo",required=false) |
| query | limit | `int` | 否 | 20 | @RequestParam(value="limit",defaultValue="20") |

成功输出为ApiResult&lt;T&gt;的data：`java.util.List<ai.neargo.shop.spi.printing.PrintPort.JobVO>`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：首次打印身份不包含客户端Idempotency-Key；ORDER/KITCHEN/RETURN按原单据+scene/station固定，PREBILL按原文档snapshot版本；换key同内容不新增首次任务。额外纸张必须走关联原任务+原因+操作人的审计reprint；UNKNOWN禁止普通retry。

#### FOOD-NONPAY-IMPL-030 · BizPrintJobController.get

`GET /biz/x/food/print-jobs/{jobNo}` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintJobController.java:58`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | jobNo | `String` | 是 | — | @PathVariable("jobNo") @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.JobVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-066 `GET /biz/print/jobs/{jobNo}`；PATH_CHANGED。共享目标查询经餐饮网关暴露，实际路径不同。


边界：首次打印身份不包含客户端Idempotency-Key；ORDER/KITCHEN/RETURN按原单据+scene/station固定，PREBILL按原文档snapshot版本；换key同内容不新增首次任务。额外纸张必须走关联原任务+原因+操作人的审计reprint；UNKNOWN禁止普通retry。

#### FOOD-NONPAY-IMPL-031 · BizPrintJobController.retry

`POST /biz/x/food/print-jobs/{jobNo}:retry` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintJobController.java:63`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | jobNo | `String` | 是 | — | @PathVariable("jobNo") @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.JobVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：首次打印身份不包含客户端Idempotency-Key；ORDER/KITCHEN/RETURN按原单据+scene/station固定，PREBILL按原文档snapshot版本；换key同内容不新增首次任务。额外纸张必须走关联原任务+原因+操作人的审计reprint；UNKNOWN禁止普通retry。

#### FOOD-NONPAY-IMPL-032 · BizPrintJobController.reprint

`POST /biz/x/food/print-jobs/{jobNo}:reprint` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintJobController.java:69`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | jobNo | `String` | 是 | — | @PathVariable("jobNo") @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.api.biz.BizPrintJobController.ReprintReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.JobVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-067 `POST /biz/print/jobs/{jobNo}:reprint`；PATH_CHANGED。实际路径不同；保留原任务、原因、操作人、幂等键。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`deviceNo`。

边界：首次打印身份不包含客户端Idempotency-Key；ORDER/KITCHEN/RETURN按原单据+scene/station固定，PREBILL按原文档snapshot版本；换key同内容不新增首次任务。额外纸张必须走关联原任务+原因+操作人的审计reprint；UNKNOWN禁止普通retry。

#### FOOD-NONPAY-IMPL-033 · BizPrintDeviceController.bind

`POST /biz/x/food/print-devices` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintDeviceController.java:45`。权限：`biz:store:admin`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:store:admin。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | req | `ai.neargo.shop.spi.printing.PrintPort.DeviceReq` | 是 | — | @Valid @RequestBody |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.DeviceVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：bridge actor必须与设备绑定一致；claim空闲为null，SENT不代表已出纸，超时为UNKNOWN。

#### FOOD-NONPAY-IMPL-034 · BizPrintDeviceController.heartbeat

`POST /biz/x/food/print-devices/{deviceNo}:heartbeat` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintDeviceController.java:51`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | deviceNo | `String` | 是 | — | @PathVariable("deviceNo") @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.api.biz.BizPrintDeviceController.HeartbeatReq` | 是 | — | @Valid @RequestBody |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.DeviceVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：bridge actor必须与设备绑定一致；claim空闲为null，SENT不代表已出纸，超时为UNKNOWN。

#### FOOD-NONPAY-IMPL-035 · BizPrintDeviceController.claim

`POST /biz/x/food/print-devices/{deviceNo}:claim` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintDeviceController.java:57`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | deviceNo | `String` | 是 | — | @PathVariable("deviceNo") @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.Claim`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：bridge actor必须与设备绑定一致；claim空闲为null，SENT不代表已出纸，超时为UNKNOWN。

#### FOOD-NONPAY-IMPL-036 · BizPrintDeviceController.acknowledge

`POST /biz/x/food/print-devices/{deviceNo}:acknowledge` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintDeviceController.java:62`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | deviceNo | `String` | 是 | — | @PathVariable("deviceNo") @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.spi.printing.PrintPort.Acknowledge` | 是 | — | @Valid @RequestBody |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.JobVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：bridge actor必须与设备绑定一致；claim空闲为null，SENT不代表已出纸，超时为UNKNOWN。

#### FOOD-NONPAY-IMPL-037 · BizPrintConfigurationController.configuration

`GET /biz/x/food/print-configuration` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintConfigurationController.java:44`。权限：`biz:store:admin`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:store:admin。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.Configuration`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：设备路由、模板归共享打印owner；模板发布后不可覆盖。

#### FOOD-NONPAY-IMPL-038 · BizPrintConfigurationController.publish

`POST /biz/x/food/print-configuration/templates` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintConfigurationController.java:47`。权限：`biz:store:admin`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:store:admin。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | req | `ai.neargo.shop.spi.printing.PrintPort.TemplateReq` | 是 | — | @Valid @RequestBody |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.TemplateReq`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：设备路由、模板归共享打印owner；模板发布后不可覆盖。

#### FOOD-NONPAY-IMPL-039 · BizPrintConfigurationController.route

`POST /biz/x/food/print-configuration/routes` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintConfigurationController.java:52`。权限：`biz:store:admin`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:store:admin。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | req | `ai.neargo.shop.spi.printing.PrintPort.RouteReq` | 是 | — | @Valid @RequestBody |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.RouteReq`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：设备路由、模板归共享打印owner；模板发布后不可覆盖。

#### FOOD-NONPAY-IMPL-040 · BizBusinessCloseController.open

`POST /biz/x/food/business-days/{date}:open` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessCloseController.java:46`。权限：`biz:finance`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:finance。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | date | `java.time.LocalDate` | 是 | — | @PathVariable |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：仅按实际源事实提供报表/关账证据；资金完整性缺口和异常不可由本地状态伪造消除。

#### FOOD-NONPAY-IMPL-041 · BizBusinessCloseController.report

`GET /biz/x/food/reports/business-day` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessCloseController.java:53`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| query | businessDate | `java.time.LocalDate` | 是 | — | @RequestParam |
| query | currency | `String` | 是 | — | @RequestParam |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-077 `GET /biz/x/food/reports/{reportCode}`；PARTIAL_TARGET_SUBSET。只实现营业日固定报表+businessDate/currency，未实现任意reportCode报表。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`from`、`metricVersion`、`page`、`size`、`stationNo`、`storeNos`、`timeBasis`、`to`。
- ACTUAL_INPUT_FIELDS_ADDED：`businessDate`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`report`。

边界：仅按实际源事实提供报表/关账证据；资金完整性缺口和异常不可由本地状态伪造消除。

#### FOOD-NONPAY-IMPL-042 · BizBusinessCloseController.preview

`POST /biz/x/food/closes:preview` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessCloseController.java:61`。权限：`biz:finance`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:finance。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | body | `ai.neargo.shop.food.dto.BusinessCloseContracts.PreviewReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-072 `POST /biz/x/food/stores/{storeNo}/closes`；PATH_AND_INPUT_CHANGED。storeNo来自可信当前门店，营业日与currency来自PreviewReq；不是目标stores/{storeNo}/closes路径。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`close`。

边界：仅按实际源事实提供报表/关账证据；资金完整性缺口和异常不可由本地状态伪造消除。

#### FOOD-NONPAY-IMPL-043 · BizBusinessCloseController.get

`GET /biz/x/food/closes/{closeNo}` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessCloseController.java:68`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | closeNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-073 `GET /biz/x/food/closes/{closeNo}`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`close`。

边界：仅按实际源事实提供报表/关账证据；资金完整性缺口和异常不可由本地状态伪造消除。

#### FOOD-NONPAY-IMPL-044 · BizBusinessCloseController.sign

`POST /biz/x/food/closes:sign` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessCloseController.java:74`。权限：`biz:finance`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:finance。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | body | `ai.neargo.shop.food.dto.BusinessCloseContracts.SignReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-075 `POST /biz/x/food/closes/{closeNo}:sign`；PATH_AND_INPUT_CHANGED。closeNo由SignReq提供；真实资金与异常守卫仍生效，存在端点不等于当前具备完整财务签核能力。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`approvalRef`、`version`、`watermark`。
- ACTUAL_INPUT_FIELDS_ADDED：`closeNo`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`close`。

边界：仅按实际源事实提供报表/关账证据；资金完整性缺口和异常不可由本地状态伪造消除。

#### FOOD-NONPAY-IMPL-045 · BizBusinessCloseController.adjust

`POST /biz/x/food/closes:adjust` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessCloseController.java:81`。权限：`biz:finance`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:finance。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | body | `ai.neargo.shop.food.dto.BusinessCloseContracts.AdjustmentReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.OperationVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-076 `POST /biz/x/food/closes/{closeNo}/adjustments`；PATH_AND_INPUT_CHANGED。originalCloseNo/currentDayNo/coreReference/approvalRef均在请求体；不得修改原已签快照。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`currentBusinessDate`。
- ACTUAL_INPUT_FIELDS_ADDED：`currentDayNo`、`originalCloseNo`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`adjustmentNo`、`newCloseNo`。

边界：仅按实际源事实提供报表/关账证据；资金完整性缺口和异常不可由本地状态伪造消除。

#### FOOD-NONPAY-IMPL-046 · BizProductionConsumptionController.consume

`POST /biz/x/food/production-events/{eventNo}:consume` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizProductionConsumptionController.java:50`。权限：`biz:stock`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:stock。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | eventNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：只允许引用既存source event；BLOCKED、UNKNOWN和原库存回执均保真；不接受客户端数量/金额。

#### FOOD-NONPAY-IMPL-047 · BizProductionConsumptionController.get

`GET /biz/x/food/production-events/{eventNo}` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizProductionConsumptionController.java:56`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | eventNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-154 `GET /biz/x/food/production-consumptions/{consumptionNo}`；DIFFERENT_RESOURCE_KEY。实际按source eventNo读耗用；目标按consumptionNo读取，不可宣称路径或资源键对齐。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`consumption`。

边界：只允许引用既存source event；BLOCKED、UNKNOWN和原库存回执均保真；不接受客户端数量/金额。

#### FOOD-NONPAY-IMPL-048 · BizKitchenReturnController.get

`GET /biz/x/food/returns/{returnNo}/kitchen` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenReturnController.java:47`。权限：`biz:aftersale`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:aftersale。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | returnNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenReturnContracts.ReturnKitchenVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：只处理厨房处置，不触发退款成功或资金执行。

#### FOOD-NONPAY-IMPL-049 · BizKitchenReturnController.handle

`POST /biz/x/food/returns/{returnNo}/kitchen` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenReturnController.java:53`。权限：`biz:aftersale`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:aftersale。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | returnNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenReturnContracts.HandleReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenReturnContracts.HandledVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：只处理厨房处置，不触发退款成功或资金执行。

#### FOOD-NONPAY-IMPL-050 · BizProductionConsumptionController.list

`GET /biz/x/food/stores/{storeNo}/production-consumptions` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizProductionConsumptionController.java:39`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | storeNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| query | from | `java.time.LocalDate` | 是 | — | @RequestParam |
| query | to | `java.time.LocalDate` | 是 | — | @RequestParam |
| query | page | `Integer` | 否 | 1 | @RequestParam(required=false) |
| query | size | `Integer` | 否 | 20 | @RequestParam(required=false) |
| query | skuNo | `String` | 否 | — | @RequestParam(required=false) |
| query | itemRef | `String` | 否 | — | @RequestParam(required=false) |
| query | status | `String` | 否 | — | @RequestParam(required=false) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO>`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-153 `GET /biz/x/food/stores/{storeNo}/production-consumptions`；EXACT_PATH。

- PAGINATION_SCALAR_TYPE_DIFFERS_FROM_TARGET：`page`、`size`。

边界：原storeNo路径+可信actor/门店归属；from/to为营业日且to不含，范围最多93日；page默认1、size默认20且最大100；SKU/物料/status均与同过滤COUNT分页。只读已持久耗用事实，不补扣库存；ConsumptionVO/LineVO递归返回原回执，BLOCKED/UNKNOWN、实际物耗未测量与未知成本保持原值。

### 11.3 实际DTO字段声明

以下来自真实Java record；声明约束为空不推断运行时字段必定非空。来源对象、权限及owner真回执仍由Service验证。

#### `ai.neargo.shop.common.PageData`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/common/PageData.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| records | `List<T>` |  |
| total | `long` |  |
| page | `long` |  |
| size | `long` |  |

#### `ai.neargo.shop.food.api.biz.BizPrintDeviceController.HeartbeatReq`

来源：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintDeviceController.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| online | `boolean` |  |

#### `ai.neargo.shop.food.api.biz.BizPrintJobController.ReprintReq`

来源：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintJobController.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| reason | `String` | @NotBlank @Size(max=256) |

#### `ai.neargo.shop.food.dto.BusinessCloseContracts.AdjustmentReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| originalCloseNo | `String` | @NotBlank @Size(max=32) |
| currentDayNo | `String` | @NotBlank @Size(max=32) |
| coreReference | `String` | @NotBlank @Size(max=64) |
| reason | `String` | @NotBlank @Size(max=255) |
| approvalRef | `String` | @NotBlank @Size(max=64) |

#### `ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| closeNo | `String` |  |
| dayNo | `String` |  |
| currency | `String` |  |
| revision | `int` |  |
| status | `String` |  |
| snapshotHash | `String` |  |
| previousCloseNo | `String` |  |
| signedBy | `String` |  |
| signedAt | `LocalDateTime` |  |
| report | `ReportVO` |  |
| version | `long` |  |

#### `ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| dayNo | `String` |  |
| businessDate | `LocalDate` |  |
| policyNo | `String` |  |
| startsAt | `LocalDateTime` |  |
| endsAt | `LocalDateTime` |  |
| status | `String` |  |
| currentCloseRefs | `String` |  |
| version | `long` |  |

#### `ai.neargo.shop.food.dto.BusinessCloseContracts.Issue`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| kind | `String` |  |
| sourceRef | `String` |  |

#### `ai.neargo.shop.food.dto.BusinessCloseContracts.PreviewReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| businessDate | `LocalDate` | @NotNull |
| currency | `String` | @NotBlank @Pattern(regexp="[A-Z]{3}") |

#### `ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| businessDate | `LocalDate` |  |
| currency | `String` |  |
| batchCount | `long` |  |
| quantitiesByUnit | `Map<String, BigDecimal>` |  |
| originalOrderServiceQuantitiesByActionUnit | `Map<String, BigDecimal>` |  |
| returnAndServiceScope | `String` |  |
| settlementAttemptCount | `long` |  |
| requestedDueMinor | `long` |  |
| returnCount | `long` |  |
| salesCompleteness | `String` |  |
| settlementCompleteness | `String` |  |
| refundCompleteness | `String` |  |
| issues | `List<Issue>` |  |
| sourceHash | `String` |  |

#### `ai.neargo.shop.food.dto.BusinessCloseContracts.SignReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| closeNo | `String` | @NotBlank @Size(max=32) |
| snapshotHash | `String` | @NotBlank @Pattern(regexp="[a-f0-9]{64}") |

#### `ai.neargo.shop.food.dto.DiningPickupContracts.PickupVerificationVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningPickupContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| batchNo | `String` |  |
| localHandoverRecorded | `boolean` |  |
| originalFulfillment | `Receipt` |  |

#### `ai.neargo.shop.food.dto.DiningPickupContracts.VerifyPickupReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningPickupContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| expectedVersion | `Long` | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| verifyCode | `String` | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Pattern(regexp="[0-9]{6}") |

#### `ai.neargo.shop.food.dto.KitchenContracts.CourseActionReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| version | `Long` | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| action | `String` | @NotBlank |
| reason | `String` | @Size(max=255) |
| seatNos | `List<String>` |  |

#### `ai.neargo.shop.food.dto.KitchenContracts.CourseFireReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| version | `Long` | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| seatNos | `List< String>` | @NotNull @Size(max=100) @NotBlank |

#### `ai.neargo.shop.food.dto.KitchenContracts.CourseReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| name | `String` | @NotBlank @Size(max=64) |
| sequenceNo | `Integer` | @NotNull |

#### `ai.neargo.shop.food.dto.KitchenContracts.CourseVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| courseNo | `String` |  |
| checkNo | `String` |  |
| name | `String` |  |
| sequenceNo | `int` |  |
| status | `String` |  |
| version | `long` |  |

#### `ai.neargo.shop.food.dto.KitchenContracts.EventVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| eventNo | `String` |  |
| taskNo | `String` |  |
| action | `String` |  |
| qty | `BigDecimal` |  |
| reason | `String` |  |
| inventoryDocNo | `String` |  |

#### `ai.neargo.shop.food.dto.KitchenContracts.HandoverLine`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| foodLineNo | `String` | @NotBlank |
| qty | `BigDecimal` | @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull |

#### `ai.neargo.shop.food.dto.KitchenContracts.HandoverReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| kind | `String` | @NotBlank |
| lines | `List<HandoverLine>` | @NotNull |
| reason | `String` | @NotBlank @Size(max=255) |

#### `ai.neargo.shop.food.dto.KitchenContracts.HandoverVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| operationNo | `String` |  |
| batchNo | `String` |  |
| status | `String` |  |
| deliveryPending | `boolean` |  |
| coreDeliveryRef | `String` |  |

#### `ai.neargo.shop.food.dto.KitchenContracts.RemakeReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| version | `Long` | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| qty | `BigDecimal` | @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull |
| reason | `String` | @NotBlank @Size(max=255) |

#### `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| version | `Long` | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| qty | `BigDecimal` | @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull |
| reason | `String` | @Size(max=255) |

#### `ai.neargo.shop.food.dto.KitchenContracts.TaskVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| taskNo | `String` |  |
| ticketNo | `String` |  |
| batchNo | `String` |  |
| foodLineNo | `String` |  |
| componentNo | `String` |  |
| stationNo | `String` |  |
| taskRole | `String` |  |
| routeVersion | `long` |  |
| qty | `BigDecimal` |  |
| makingQty | `BigDecimal` |  |
| readyQty | `BigDecimal` |  |
| servedQty | `BigDecimal` |  |
| voidQty | `BigDecimal` |  |
| status | `String` |  |
| seatNo | `String` |  |
| courseNo | `String` |  |
| version | `long` |  |
| ticketKind | `String` |  |
| originalTicketNo | `String` |  |

#### `ai.neargo.shop.food.dto.KitchenContracts.TicketVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| ticketNo | `String` |  |
| batchNo | `String` |  |
| kind | `String` |  |
| originalTicketNo | `String` |  |
| status | `String` |  |
| tasks | `List<TaskVO>` |  |

#### `ai.neargo.shop.food.dto.KitchenReturnContracts.HandleReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenReturnContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| version | `Long` | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| decisions | `List< ItemDecision>` | @NotNull @Size(min=1,max=1000) @Valid |
| reason | `String` | @NotBlank @Size(max=255) |

#### `ai.neargo.shop.food.dto.KitchenReturnContracts.HandledVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenReturnContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| operationNo | `String` |  |
| returnNo | `String` |  |
| kitchenStatus | `String` |  |

#### `ai.neargo.shop.food.dto.KitchenReturnContracts.ItemDecision`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenReturnContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| returnItemNo | `String` | @NotBlank |
| disposition | `String` | @NotBlank |

#### `ai.neargo.shop.food.dto.KitchenReturnContracts.ItemVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenReturnContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| returnItemNo | `String` |  |
| foodLineNo | `String` |  |
| componentNo | `String` |  |
| qty | `BigDecimal` |  |
| disposition | `String` |  |

#### `ai.neargo.shop.food.dto.KitchenReturnContracts.ReturnKitchenVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenReturnContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| returnNo | `String` |  |
| batchNo | `String` |  |
| kitchenStatus | `String` |  |
| version | `long` |  |
| items | `List<ItemVO>` |  |

#### `ai.neargo.shop.food.dto.OperationVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OperationVO.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| operationNo | `String` |  |
| status | `String` |  |
| version | `long` |  |
| resultRef | `String` |  |

#### `ai.neargo.shop.food.dto.PrintDocumentReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/PrintDocumentReq.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| scene | `String` | @NotBlank @Size(max=16) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) |
| sourceNo | `String` | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) |
| stationNo | `String` | @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) |

#### `ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ProductionContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| consumptionNo | `String` |  |
| sourceEventNo | `String` |  |
| purpose | `String` |  |
| status | `String` |  |
| requestHash | `String` |  |
| inventoryOperationRef | `String` |  |
| businessDate | `LocalDate` |  |
| occurredAt | `LocalDateTime` |  |
| lines | `List<LineVO>` |  |
| quantityEvidence | `String` |  |
| consumptionPending | `boolean` |  |
| totalCostMinor | `Long` |  |
| costCurrency | `String` |  |
| version | `long` |  |

#### `ai.neargo.shop.food.dto.ProductionContracts.LineVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ProductionContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| lineNo | `int` |  |
| componentPath | `String` |  |
| itemRef | `String` |  |
| theoryQty | `BigDecimal` |  |
| unit | `String` |  |
| recipeRevisionNo | `String` |  |
| inventoryReceiptRef | `String` |  |
| inventoryIssuedQty | `BigDecimal` |  |
| costMinor | `Long` |  |
| currency | `String` |  |
| status | `String` |  |

#### `ai.neargo.shop.food.dto.ProductionContracts.ListQuery`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ProductionContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| page | `Integer` |  |
| size | `Integer` |  |
| from | `LocalDate` |  |
| to | `LocalDate` |  |
| skuNo | `String` |  |
| itemRef | `String` |  |
| status | `String` |  |

#### `ai.neargo.shop.food.dto.QueueContracts.ChangeReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/QueueContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| version | `Long` | @NotNull @Min(0) |
| reason | `String` | @Size(max=255) |

#### `ai.neargo.shop.food.dto.QueueContracts.IssueReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/QueueContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| partySize | `Integer` | @NotNull @Min(1) @Max(1000) |
| contactRef | `String` | @Size(max=64) |

#### `ai.neargo.shop.food.dto.QueueContracts.QueueVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/QueueContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| ticketNo | `String` |  |
| businessDate | `LocalDate` |  |
| partySize | `int` |  |
| status | `String` |  |
| calledAt | `LocalDateTime` |  |
| checkNo | `String` |  |
| version | `long` |  |
| notificationStatus | `String` |  |

#### `ai.neargo.shop.food.dto.QueueContracts.SeatQueueReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/QueueContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| version | `Long` | @NotNull @Min(0) |
| check | `OpenCheckReq` | @NotNull @Valid |

#### `ai.neargo.shop.spi.printing.PrintPort.Acknowledge`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| attemptNo | `String` |  |
| leaseToken | `String` |  |
| status | `String` |  |
| channelJobNo | `String` |  |
| responseCode | `String` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.AttemptVO`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| attemptNo | `String` |  |
| sequenceNo | `int` |  |
| status | `String` |  |
| responseCode | `String` |  |
| startedAt | `LocalDateTime` |  |
| finishedAt | `LocalDateTime` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.Claim`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| jobNo | `String` |  |
| deviceNo | `String` |  |
| attemptNo | `String` |  |
| leaseToken | `String` |  |
| leaseUntil | `LocalDateTime` |  |
| renderedContent | `String` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.Configuration`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| devices | `List<DeviceVO>` |  |
| templates | `List<TemplateReq>` |  |
| routes | `List<RouteReq>` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.DeviceReq`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| deviceNo | `String` |  |
| vendor | `String` |  |
| connectionType | `String` |  |
| credentialRef | `String` |  |
| bridgeActorNo | `String` |  |
| capabilities | `String` |  |
| active | `boolean` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.DeviceVO`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| deviceNo | `String` |  |
| vendor | `String` |  |
| connectionType | `String` |  |
| bridgeActorNo | `String` |  |
| status | `String` |  |
| capabilities | `String` |  |
| lastSeenAt | `LocalDateTime` |  |
| online | `boolean` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.Document`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| schemaVersion | `int` |  |
| scene | `String` |  |
| sourceRef | `String` |  |
| title | `String` |  |
| currency | `String` |  |
| lines | `List<Line>` |  |
| notice | `String` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.JobVO`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| jobNo | `String` |  |
| storeNo | `String` |  |
| sourceEventNo | `String` |  |
| scene | `String` |  |
| deviceNo | `String` |  |
| partNo | `String` |  |
| copyIndex | `int` |  |
| templateNo | `String` |  |
| status | `String` |  |
| originalJobNo | `String` |  |
| reprintReason | `String` |  |
| requestedBy | `String` |  |
| document | `Document` |  |
| renderedContent | `String` |  |
| attempts | `List<AttemptVO>` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.Line`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| label | `String` |  |
| quantity | `String` |  |
| amountMinor | `Long` |  |
| note | `String` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.RouteReq`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| routeNo | `String` |  |
| scene | `String` |  |
| stationNo | `String` |  |
| deviceNo | `String` |  |
| templateNo | `String` |  |
| copies | `int` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.TemplateReq`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| templateNo | `String` |  |
| templateCode | `String` |  |
| revision | `int` |  |
| scene | `String` |  |
| content | `String` |  |

#### `ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Receipt`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryFulfillmentPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| fulfillmentNo | `String` |  |
| reference | `Reference` |  |
| status | `String` |  |
| subOrderStatus | `String` |  |
| requireBuyerConfirm | `boolean` |  |
| pending | `boolean` |  |
| inventoryReservationNo | `String` |  |
| inventoryState | `String` |  |
| inventoryLifecycleVersion | `int` |  |
| subOrderVersion | `long` |  |

#### `ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryFulfillmentPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| scope | `Scope` |  |
| source | `PricingSourceRef` |  |
| orderNo | `String` |  |
| subOrderNo | `String` |  |

#### `ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/PricingSourceFactsPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| tenantNo | `String` |  |
| entityNo | `String` |  |
| storeNo | `String` |  |

### 11.4 未对齐目标与设备未知回执

| 目标 | 目标路径 | 本批对齐状态 | 实际路径 |
|---|---|---|---|
| FOOD-API-044 | `GET /biz/x/food/stores/{storeNo}/kitchen/tasks` | EXACT_PATH / PATH_AND_TOP_LEVEL_FIELD_NAMES_ALIGNED | `/biz/x/food/stores/{storeNo}/kitchen/tasks` |
| FOOD-API-045 | `POST /biz/x/food/tasks/{taskNo}:start` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:start` |
| FOOD-API-046 | `POST /biz/x/food/tasks/{taskNo}:ready` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:ready` |
| FOOD-API-047 | `POST /biz/x/food/tasks/{taskNo}:serve` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:serve` |
| FOOD-API-048 | `POST /biz/x/food/tasks/{taskNo}:hold` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:hold` |
| FOOD-API-049 | `POST /biz/x/food/tasks/{taskNo}:fire` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:fire` |
| FOOD-API-050 | `POST /biz/x/food/tasks/{taskNo}:urge` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:urge` |
| FOOD-API-051 | `POST /biz/x/food/tasks/{taskNo}:void` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:void` |
| FOOD-API-052 | `POST /biz/x/food/tasks/{taskNo}:remake` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:remake` |
| FOOD-API-053 | `POST /biz/x/food/checks/{checkNo}/courses` | EXACT_PATH / PATH_AND_TOP_LEVEL_FIELD_NAMES_ALIGNED | `/biz/x/food/checks/{checkNo}/courses` |
| FOOD-API-054 | `POST /biz/x/food/courses/{courseNo}:fire` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/courses/{courseNo}:fire` |
| FOOD-API-055 | `POST /biz/x/food/batches/{batchNo}:handover` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/batches/{batchNo}:handover` |
| FOOD-API-065 | `POST /biz/print/jobs` | PATH_CHANGED / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/print-jobs` |
| FOOD-API-066 | `GET /biz/print/jobs/{jobNo}` | PATH_CHANGED / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/print-jobs/{jobNo}` |
| FOOD-API-067 | `POST /biz/print/jobs/{jobNo}:reprint` | PATH_CHANGED / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/print-jobs/{jobNo}:reprint` |
| FOOD-API-072 | `POST /biz/x/food/stores/{storeNo}/closes` | PATH_AND_INPUT_CHANGED / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/closes:preview` |
| FOOD-API-073 | `GET /biz/x/food/closes/{closeNo}` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/closes/{closeNo}` |
| FOOD-API-074 | `POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:carry` | NO_IMPLEMENTED_PUBLIC_ROUTE_IN_THIS_BATCH / TARGET_NOT_IMPLEMENTED_IN_THIS_BATCH | 未注册对应实际路由 |
| FOOD-API-075 | `POST /biz/x/food/closes/{closeNo}:sign` | PATH_AND_INPUT_CHANGED / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/closes:sign` |
| FOOD-API-076 | `POST /biz/x/food/closes/{closeNo}/adjustments` | PATH_AND_INPUT_CHANGED / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/closes:adjust` |
| FOOD-API-077 | `GET /biz/x/food/reports/{reportCode}` | PARTIAL_TARGET_SUBSET / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/reports/business-day` |
| FOOD-API-078 | `POST /biz/x/food/report-exports` | NO_IMPLEMENTED_PUBLIC_ROUTE_IN_THIS_BATCH / TARGET_NOT_IMPLEMENTED_IN_THIS_BATCH | 未注册对应实际路由 |
| FOOD-API-079 | `GET /biz/x/food/report-exports/{exportNo}` | NO_IMPLEMENTED_PUBLIC_ROUTE_IN_THIS_BATCH / TARGET_NOT_IMPLEMENTED_IN_THIS_BATCH | 未注册对应实际路由 |
| FOOD-API-153 | `GET /biz/x/food/stores/{storeNo}/production-consumptions` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/stores/{storeNo}/production-consumptions` |
| FOOD-API-154 | `GET /biz/x/food/production-consumptions/{consumptionNo}` | DIFFERENT_RESOURCE_KEY / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/production-events/{eventNo}` |
| FOOD-API-157 | `POST /biz/x/food/queue-tickets` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets` |
| FOOD-API-158 | `GET /biz/x/food/queue-tickets` | EXACT_PATH / PATH_AND_TOP_LEVEL_FIELD_NAMES_ALIGNED | `/biz/x/food/queue-tickets` |
| FOOD-API-159 | `GET /biz/x/food/queue-tickets/{ticketNo}` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets/{ticketNo}` |
| FOOD-API-160 | `POST /biz/x/food/queue-tickets:call-next` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets:call-next` |
| FOOD-API-161 | `POST /biz/x/food/queue-tickets/{ticketNo}:call` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets/{ticketNo}:call` |
| FOOD-API-162 | `POST /biz/x/food/queue-tickets/{ticketNo}:cancel` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets/{ticketNo}:cancel` |
| FOOD-API-163 | `POST /biz/x/food/queue-tickets/{ticketNo}:no-show` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets/{ticketNo}:no-show` |
| FOOD-API-164 | `POST /biz/x/food/queue-tickets/{ticketNo}:seat` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets/{ticketNo}:seat` |
| FOOD-API-165 | `POST /biz/x/food/queue-tickets/{ticketNo}:recover-seating` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets/{ticketNo}:recover-seating` |

内部打印claim采用ClaimResult信封：显式claim=null表示成功空闲，信封缺失仍报错。历史P2已编码修复：claim必需字段、非空有界key及规范UUID校验；ACK只认最新连续唯一尝试、规范状态及responseCode一致。保留owner允许的FAILED→retry/PENDING重放，远程适配器不以本地时钟拒绝过去租约。失去claim响应可能已留下SENT租约；超时为UNKNOWN，不自动补投。普通retry仅限真实NOT_PRINTED失败，未知回执需原尝试对账或带原单原因的人工补打。

### 11.5 自动打印、默认KDS与本批已编码修复

打印归属：实际公开BizPrintJobController位于food-web的ai.neargo.shop.food.api.biz。enqueue先调用FoodPrintService冻结文档，再调用shop-base的ai.neargo.shop.spi.printing.PrintPort；list/get/retry/reprint直接调用该PrintPort。共享PrintService/PrintServiceImpl、prn Entity与PrintMappers唯一归shop-core的ai.neargo.shop.printing。原/biz/print/jobs仍为PLANNED_SHARED_B_ENTRY_NOT_REGISTERED，目标Req/VO仅PLANNED_TRANSPORT_CONTRACT；实际DTO以本节真实Java声明为准。

DINING_ORDER_ACCEPTED先持久化/重放原MAKE票，再读取共享打印配置，对已配置ORDER及原工位KITCHEN route自动入队；与手动首次打印共用原逻辑文档身份。无配置route不造打印任务，入队不代表物理出纸。

默认KDS只读真实ACCEPTED批次、未CLOSED票及非SERVED/VOIDED任务；显式batchNo可查原历史。MAKE/REMAKE及原票引用保持，轮询不提供SSE/推送送达证明。

CorePrint回执历史审查保留为历史证据；当前修复登记为CODED_PENDING_VERIFICATION，覆盖显式空闲/缺失信封、最新attempt与规范状态/responseCode、key与UUID及晚到/重复原回执。测试源码：`backend-plugins/shop-industry-food/food-channel/src/test/java/ai/neargo/shop/food/channel/CorePrintPortTest.java`。这不替代硬件真回执或将全目标判定完成。

## 十二、当前员工代客下单切片（本批后端编译及测试已执行）

本节覆盖后续员工下单增量；前两节保留历史切片的原时点。实际状态为BACKEND_TESTED_SCOPE_LIMITED，不代表FOOD-API-042完整目标完成。当前支持POS/WAITER、POSTPAY、真实注册顾客、PICKUP或有有效台账的DINE_IN，以及完整整数MAIN及已支持ADDON；员工身份与顾客分开，禁止冒充消费者Credentials，不执行收款或资产消费。

`POST /biz/x/food/stores/{storeNo}/batches` · BizOrderBatchController.place · biz:verify

请求：`ai.neargo.shop.food.dto.CreateStaffDiningOrderReq`，Header：`Idempotency-Key`；输出data：`ai.neargo.shop.food.dto.DiningOrderBatchVO`。

| 输入字段 | 类型 | 必填 | 含义 |
|---|---|---|---|
| buyerNo | String | 是 | 真实已注册顾客；非操作员工，不授予付款授权 |
| cartNo | String | 是 | 原当前门店POS/WAITER完整购物车 |
| version | long | 是 | 原车版本，与报价一致 |
| quoteNo | String | 是 | 当前员工创建的staff报价 |
| confirmationDigest | String | 是 | 原展示确认摘要，服务端重验 |
| payMode | String | 是 | 当前只支持POSTPAY |

守卫：FoodBizStoreScope.path + real registered buyer + authenticated staff; POS/WAITER POSTPAY PICKUP or DINE_IN integer MAIN/ADDON whole cart; DINE_IN live check/cart locks and original frozen hashes; quote authority and original identity recovery。剩余边界：匿名/设备、外送正式下单、堂食核心履约完成、套餐库存策略、小数成交、selectedLineRefs/splitByPerson；无新增支付/退款/资产执行。

员工身份持久化复用原fnb批次、原核心订单及来源绑定，组件等结构增量见第十三节；buyer_no保存顾客，operator_no保存员工，actor_realm沿MERCHANT原枚举。内部合同：DiningOrderInternalApi.staffPlace、IndustryOrderPort.placeForStore、IndustryOrderPort.queryForStore、IndustryOrderPort.placeForDiningRoom、IndustryOrderInternalApi.placeForDiningRoom。恢复只查持久化原员工/顾客/批次键，不重开单。

本批已完成记录范围的后端编译和集中测试；真实结果及跳过项见原TDD§2.14。未完成业务与目标合同差异仍保持待办。

## 十三、连续业务编码的实际合同（本批后端编译及测试已执行）

涉及29条实际路由，其中20条为相对原提交的新增公开路由；已有路由的DTO/业务扩展与新增分开展示。当前批次状态为BACKEND_TESTED_SCOPE_LIMITED，仅表示本批已记录的源码或测试范围；原50条非支付收据仍保留其历史时点，目标API与完整需求差异不自动消除。

| 编码编号 | 方法与实际路径 | Java入口 | 请求 | data响应 | 新增路由 | 目标映射 |
|---|---|---|---|---|---|---|
| FOOD-BUSINESS-CODE-001 | `POST /biz/x/food/stores/{storeNo}/batches` | BizOrderBatchController.place | storeNo:String:path;body:CreateStaffDiningOrderReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.DiningOrderBatchVO | 是 | FOOD-API-042 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-002 | `POST /biz/x/food/batches/{batchNo}:cancel` | BizOrderBatchController.cancel | batchNo:String:path;body:CancelDiningOrderReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.DiningOrderBatchVO | 是 | FOOD-API-124 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-003 | `GET /biz/x/food/batches/{batchNo}` | BizOrderBatchController.get | batchNo:String:path | ai.neargo.shop.food.dto.DiningOrderBatchVO | 沿用/扩展 | FOOD-API-043 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-004 | `GET /biz/x/food/stores/{storeNo}/reservations` | BizReservationController.listReservations | storeNo:String:path;query:ListReservationsQuery:query-object | ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.ReservationVO> | 沿用/扩展 | FOOD-API-107 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-005 | `GET /biz/x/food/reservations/{reservationNo}` | BizReservationController.get | reservationNo:String:path | ai.neargo.shop.food.dto.ReservationContracts.DetailVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-006 | `POST /biz/x/food/stores/{storeNo}/reservations` | BizReservationController.create | storeNo:String:path;body:CreateReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.ReservationContracts.DetailVO | 沿用/扩展 | FOOD-API-027 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-007 | `POST /biz/x/food/reservations/{reservationNo}:change` | BizReservationController.change | reservationNo:String:path;body:ChangeReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.ReservationContracts.DetailVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-008 | `POST /biz/x/food/reservations/{reservationNo}:cancel` | BizReservationController.cancel | reservationNo:String:path;body:TransitionReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.ReservationContracts.DetailVO | 是 | FOOD-API-028 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-009 | `POST /biz/x/food/reservations/{reservationNo}:no-show` | BizReservationController.noShow | reservationNo:String:path;body:TransitionReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.ReservationContracts.DetailVO | 是 | FOOD-API-029 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-010 | `POST /biz/x/food/reservations/{reservationNo}:arrive` | BizReservationController.arrive | reservationNo:String:path;body:ArriveReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.ReservationContracts.DetailVO | 是 | FOOD-API-030 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-011 | `POST /biz/x/food/batches/{batchNo}:handover` | BizDiningHandoverController.handover | batchNo:String:path;req:HandoverReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.KitchenContracts.HandoverVO | 沿用/扩展 | FOOD-API-055 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-012 | `POST /biz/x/food/batches/{batchNo}:confirm-packing` | BizDiningHandoverController.confirmPacking | batchNo:String:path;req:PackingReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.KitchenContracts.CustodyReceiptVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-013 | `GET /biz/x/food/batches/{batchNo}/custody-receipts` | BizDiningHandoverController.custodyReceipts | batchNo:String:path | java.util.List<ai.neargo.shop.food.dto.KitchenContracts.CustodyReceiptVO> | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-014 | `POST /biz/x/food/business-days/{date}:open` | BizBusinessCloseController.open | date:LocalDate:path;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO | 沿用/扩展 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-016 | `POST /biz/x/food/closes:preview` | BizBusinessCloseController.preview | body:PreviewReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO | 沿用/扩展 | FOOD-API-072 / TARGET_RECONCILIATION_REQUIRED |
| FOOD-BUSINESS-CODE-017 | `GET /biz/x/food/closes/{closeNo}` | BizBusinessCloseController.get | closeNo:String:path | ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO | 沿用/扩展 | FOOD-API-073 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-018 | `POST /biz/x/food/closes:sign` | BizBusinessCloseController.sign | body:SignReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO | 沿用/扩展 | FOOD-API-075 / TARGET_RECONCILIATION_REQUIRED |
| FOOD-BUSINESS-CODE-019 | `POST /biz/x/food/closes:adjust` | BizBusinessCloseController.adjust | body:AdjustmentReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.OperationVO | 沿用/扩展 | FOOD-API-076 / TARGET_RECONCILIATION_REQUIRED |
| FOOD-BUSINESS-CODE-020 | `GET /biz/x/food/closes/{closeNo}/exceptions` | BizBusinessCloseController.exceptions | closeNo:String:path | java.util.List<ai.neargo.shop.food.dto.BusinessCloseContracts.ExceptionVO> | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-021 | `POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:propose-carry` | BizBusinessCloseController.proposeCarry | closeNo:String:path;exceptionNo:String:path;body:ProposeCarryReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessCloseContracts.ExceptionVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-022 | `POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:approve-carry` | BizBusinessCloseController.approveCarry | closeNo:String:path;exceptionNo:String:path;body:ApproveCarryReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessCloseContracts.ExceptionVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-023 | `POST /biz/x/food/checks/{checkNo}/business-share-plans` | BizBusinessShareController.create | checkNo:String:path;request:CreateReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-024 | `GET /biz/x/food/checks/{checkNo}/business-share-plans/{planNo}` | BizBusinessShareController.get | checkNo:String:path;planNo:String:path | ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-025 | `POST /biz/x/food/checks/{checkNo}/business-share-plans/{planNo}:confirm` | BizBusinessShareController.confirm | checkNo:String:path;planNo:String:path;request:ConfirmReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-026 | `POST /biz/x/food/checks/{checkNo}/business-share-plans/{planNo}:cancel` | BizBusinessShareController.cancel | checkNo:String:path;planNo:String:path;request:CancelReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-027 | `GET /biz/x/food/stores/{storeNo}/batches` | BizDiningBatchQueryController.list | storeNo:String:path;query:ListDiningBatchesQuery:query-object | ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.DiningBatchSummaryVO> | 是 | FOOD-API-123 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-028 | `POST /biz/x/food/batches/{batchNo}/business-returns` | BizBusinessReturnController.create | batchNo:String:path;request:CreateReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessReturnContracts.ReturnVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-029 | `GET /biz/x/food/business-returns/{returnNo}` | BizBusinessReturnController.get | returnNo:String:path | ai.neargo.shop.food.dto.BusinessReturnContracts.ReturnVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-030 | `POST /biz/x/food/business-returns/{returnNo}:cancel` | BizBusinessReturnController.cancel | returnNo:String:path;request:CancelReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessReturnContracts.ReturnVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |

### 共享人工调价（shop-core）

复用 CouponCalculation + PricingEngine + TaxEngine，真实门店策略与审批只授权让价，报价在下单时重新核验；不执行收款、退款或资产消费。员工自审限额固定0。

| 方法 | 实际路径 | 输入 | 输出 |
|---|---|---|---|
| GET | /biz/pricing-manual-policies/{currency}/{reasonCode}/{kind} | currency/reasonCode/kind path | PolicyVO |
| PUT | /biz/pricing-manual-policies/{currency}/{reasonCode}/{kind} | Save(expectedVersion,enabled,selfLimitMinor=0,maxLimitMinor,minPayableRateBp); Idempotency-Key | PolicyVO |
| POST | /biz/pricing-manual-adjustments | Request(source,kind,amountMinor?,payableRateBp?,reasonCode,reason); Idempotency-Key | AdjustmentVO |
| GET | /biz/pricing-manual-adjustments/{no} | no path | AdjustmentVO |
| POST | /biz/pricing-manual-adjustments/{no}:approve | Approve(version); Idempotency-Key | AdjustmentVO |
| POST | /biz/pricing-manual-adjustments/{no}:quote | no path; Idempotency-Key | PricingCheckoutPort.QuoteVO |

阻断项：cash rounding；employee self-limit above zero without cumulative quota owner；bundles；unsupported tax basis；funds execution。

### 处理中批次撤销

仅MERCHANT POS/WAITER POSTPAY original batch with no local binding, PREPARED/PLACING/UNKNOWN only。CANCEL_PENDING表示已保存撤销意图，只有原核心RELEASED才能标取消（无原意图时写持久tombstone，有原预占时须原库存确认释放）；正式订单先创建则保留原债务并记录撤销拒绝。

来源验证采用原批次冻结价格事实、完整原Command/履约/键及持久撤销审计，不重新检查当前菜单、剩余额度、库存、规则或税配置。缺能力默认拒绝，不回退实时可售接口。复用fnb_order_batch/fnb_action_log，无新表或迁移；已受理退餐、退款及返库不在该取消合同内。本批后端编译及测试结果见原TDD§2.14。

### 实际DTO字段（当前源码）

#### ai.neargo.shop.common.PageData

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| records | List<T> | 否 |  |
| total | long | 是 |  |
| page | long | 是 |  |
| size | long | 是 |  |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.AdjustmentReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| originalCloseNo | String | 是 | @NotBlank @Size(max=32) |
| currentDayNo | String | 是 | @NotBlank @Size(max=32) |
| coreReference | String | 是 | @NotBlank @Size(max=64) |
| reason | String | 是 | @NotBlank @Size(max=255) |
| approvalRef | String | 是 | @NotBlank @Size(max=64) |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.ApproveCarryReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @NotNull @Min(0) |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| closeNo | String | 否 |  |
| dayNo | String | 否 |  |
| currency | String | 否 |  |
| revision | int | 是 |  |
| status | String | 否 |  |
| snapshotHash | String | 否 |  |
| previousCloseNo | String | 否 |  |
| signedBy | String | 否 |  |
| signedAt | LocalDateTime | 否 |  |
| report | ReportVO | 否 |  |
| version | long | 是 |  |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| dayNo | String | 否 |  |
| businessDate | LocalDate | 否 |  |
| policyNo | String | 否 |  |
| startsAt | LocalDateTime | 否 |  |
| endsAt | LocalDateTime | 否 |  |
| status | String | 否 |  |
| currentCloseRefs | String | 否 |  |
| version | long | 是 |  |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.ExceptionVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| exceptionNo | String | 否 |  |
| closeNo | String | 否 |  |
| kind | String | 否 |  |
| sourceRef | String | 否 |  |
| status | String | 否 |  |
| ownerNo | String | 否 |  |
| nextDayNo | String | 否 |  |
| reason | String | 否 |  |
| proposedBy | String | 否 |  |
| approvedBy | String | 否 |  |
| approvedAt | LocalDateTime | 否 |  |
| approvalRef | String | 否 |  |
| version | long | 是 |  |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.Issue

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| kind | String | 否 |  |
| sourceRef | String | 否 |  |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.PreviewReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| businessDate | LocalDate | 是 | @NotNull |
| currency | String | 是 | @NotBlank @Pattern(regexp="[A-Z]{3}") |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.ProposeCarryReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @NotNull @Min(0) |
| ownerNo | String | 是 | @NotBlank @Size(max=32) |
| nextDayNo | String | 是 | @NotBlank @Size(max=32) |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| businessDate | LocalDate | 否 |  |
| currency | String | 否 |  |
| batchCount | long | 是 |  |
| quantitiesByUnit | Map<String, BigDecimal> | 否 |  |
| originalOrderServiceQuantitiesByActionUnit | Map<String, BigDecimal> | 否 |  |
| returnAndServiceScope | String | 否 |  |
| settlementAttemptCount | long | 是 |  |
| requestedDueMinor | long | 是 |  |
| returnCount | long | 是 |  |
| salesCompleteness | String | 否 |  |
| settlementCompleteness | String | 否 |  |
| refundCompleteness | String | 否 |  |
| issues | List<Issue> | 否 |  |
| sourceHash | String | 否 |  |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.SignReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| closeNo | String | 是 | @NotBlank @Size(max=32) |
| snapshotHash | String | 是 | @NotBlank @Pattern(regexp="[a-f0-9]{64}") |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.CancelReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.ComponentSource

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| sourceComponentRef | String | 否 |  |
| kind | String | 否 |  |
| allocationNo | String | 否 |  |
| orderItemId | Long | 否 |  |
| originalQty | BigDecimal | 否 |  |
| unit | String | 否 |  |
| coreSnapshotHash | String | 否 |  |
| allocationVersion | Long | 否 |  |
| componentNo | String | 否 |  |
| componentVersion | Long | 否 |  |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.CreateReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| batchVersion | Long | 是 | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| lines | List< Line> | 是 | @NotNull @Size(min=1,max=256) @Valid |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.Frozen

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| kind | String | 否 |  |
| schema | int | 是 |  |
| entityNo | String | 否 |  |
| storeNo | String | 否 |  |
| batchNo | String | 否 |  |
| batchVersion | Long | 否 |  |
| cartNo | String | 否 |  |
| cartVersion | Long | 否 |  |
| bindingNo | String | 否 |  |
| bindingVersion | Long | 否 |  |
| buyerNo | String | 否 |  |
| actorNo | String | 否 |  |
| reason | String | 否 |  |
| sourceHash | String | 否 |  |
| ownerRead | OwnerRead | 否 |  |
| items | List<SourceItem> | 否 |  |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.ItemVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| returnItemNo | String | 否 |  |
| foodLineNo | String | 否 |  |
| sourceLineRef | String | 否 |  |
| qty | BigDecimal | 否 |  |
| kitchenDisposition | String | 否 |  |
| mainAllocationNo | String | 否 |  |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.Line

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| foodLineNo | String | 是 | @NotBlank @Size(max=32) |
| qty | BigDecimal | 是 | @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.OwnerRead

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| checkedAt | String | 否 |  |
| original | Result | 否 |  |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.ReturnVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| returnNo | String | 否 |  |
| batchNo | String | 否 |  |
| storeNo | String | 否 |  |
| version | long | 是 |  |
| status | String | 否 |  |
| financialStatus | String | 否 |  |
| kitchenStatus | String | 否 |  |
| coreCaseRef | String | 否 |  |
| refundRequested | boolean | 是 |  |
| originalPayableChanged | boolean | 是 |  |
| reason | String | 否 |  |
| actorNo | String | 否 |  |
| items | List<ItemVO> | 否 |  |
| source | Frozen | 否 |  |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.SourceItem

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| foodLineNo | String | 否 |  |
| sourceLineRef | String | 否 |  |
| mainAllocationNo | String | 否 |  |
| mainOrderItemId | Long | 否 |  |
| qty | BigDecimal | 否 |  |
| originalQty | BigDecimal | 否 |  |
| lineVersion | Long | 否 |  |
| components | List<ComponentSource> | 否 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.AllocationSource

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| allocationNo | String | 否 |  |
| batchNo | String | 否 |  |
| foodLineNo | String | 否 |  |
| sourceLineRef | String | 否 |  |
| sourceComponentRef | String | 否 |  |
| orderItemId | Long | 否 |  |
| originalQty | BigDecimal | 否 |  |
| unit | String | 否 |  |
| coreSnapshotHash | String | 否 |  |
| allocationVersion | Long | 否 |  |
| lineVersion | Long | 否 |  |
| selectionVersion | Long | 否 |  |
| lineSnapshotHash | String | 否 |  |
| currency | String | 否 |  |
| originalAmountMinor | long | 是 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.BatchSource

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| batchNo | String | 否 |  |
| originalCheckNo | String | 否 |  |
| version | Long | 否 |  |
| bindingNo | String | 否 |  |
| bindingVersion | Long | 否 |  |
| orderNo | String | 否 |  |
| storeOrderNo | String | 否 |  |
| frozenRequestHash | String | 否 |  |
| memberships | List<MembershipScope> | 否 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.CancelReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.BusinessShareContracts.ConfirmReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| sourceHash | String | 是 | @NotBlank @Size(min=64,max=64) |
| planHash | String | 是 | @NotBlank @Size(min=64,max=64) |

#### ai.neargo.shop.food.dto.BusinessShareContracts.CreateReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| checkVersion | Long | 是 | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| mode | String | 是 | @NotBlank |
| people | List< Person> | 是 | @NotNull @Size(min=1,max=50) @Valid |
| lineWeights | List< LineWeight> | 否 | @Size(max=1000) @Valid |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.BusinessShareContracts.Division

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| people | List<PersonTotal> | 否 |  |
| cells | List<ShareCell> | 否 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.LineWeight

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| foodLineNo | String | 是 | @NotBlank @Size(max=32) |
| personNo | String | 是 | @NotBlank @Size(max=32) |
| weight | Long | 是 | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |

#### ai.neargo.shop.food.dto.BusinessShareContracts.MembershipScope

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| membershipNo | String | 否 |  |
| checkNo | String | 否 |  |
| batchNo | String | 否 |  |
| version | Long | 否 |  |
| attachedAt | LocalDateTime | 否 |  |
| operationNo | String | 否 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.Person

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| personNo | String | 是 | @NotBlank @Size(max=32) |
| amountMinor | Long | 否 | @JsonDeserialize(using=DishOfferFacts.MinorDeserializer.class) |

#### ai.neargo.shop.food.dto.BusinessShareContracts.PersonTotal

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| personNo | String | 否 |  |
| amountMinor | long | 是 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| planNo | String | 否 |  |
| checkNo | String | 否 |  |
| version | long | 是 |  |
| status | String | 否 |  |
| storedStatus | String | 否 |  |
| mode | String | 否 |  |
| currency | String | 否 |  |
| originalAmountMinor | long | 是 |  |
| sourceHash | String | 否 |  |
| planHash | String | 否 |  |
| localSourcesCurrent | boolean | 是 |  |
| settlementEligible | boolean | 是 |  |
| financialAuthorityPending | boolean | 是 |  |
| source | SourceSnapshot | 否 |  |
| division | Division | 否 |  |
| definition | CreateReq | 否 |  |
| reason | String | 否 |  |
| createdBy | String | 否 |  |
| confirmedBy | String | 否 |  |
| cancelledBy | String | 否 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.ShareCell

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| allocationNo | String | 否 |  |
| foodLineNo | String | 否 |  |
| orderItemId | Long | 否 |  |
| personNo | String | 否 |  |
| amountMinor | long | 是 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.SourceSnapshot

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| entityNo | String | 否 |  |
| storeNo | String | 否 |  |
| checkNo | String | 否 |  |
| checkVersion | Long | 否 |  |
| currency | String | 否 |  |
| originalAmountMinor | long | 是 |  |
| batches | List<BatchSource> | 否 |  |
| allocations | List<AllocationSource> | 否 |  |

#### ai.neargo.shop.food.dto.CancelDiningOrderReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @NotNull @JsonDeserialize(using = CatalogRevisionDeserializer.class) |
| reason | String | 是 | @NotBlank @Size(max = 255) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) |

#### ai.neargo.shop.food.dto.CreateStaffDiningOrderReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| buyerNo | String | 是 | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) |
| cartNo | String | 是 | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) |
| version | long | 是 | @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) |
| quoteNo | String | 是 | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) |
| confirmationDigest | String | 是 | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Pattern(regexp = "[0-9a-f]{64}") |
| payMode | String | 是 | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Pattern(regexp = "POSTPAY") |

#### ai.neargo.shop.food.dto.DiningBatchSummaryVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| batchNo | String | 否 |  |
| storeNo | String | 否 |  |
| originalCheckNo | String | 否 |  |
| currentCheckNos | List<String> | 否 |  |
| membershipAuthoritative | boolean | 是 |  |
| status | String | 否 |  |
| orderSource | String | 否 |  |
| businessDate | LocalDate | 否 |  |
| fulfillment | String | 否 |  |
| payMode | String | 否 |  |
| quoteNo | String | 否 |  |
| quoteHash | String | 否 |  |
| createdAt | Instant | 否 |  |
| acceptedAt | Instant | 否 |  |
| scheduledAt | Instant | 否 |  |
| version | long | 是 |  |
| originalBindings | List<OriginalBindingVO> | 否 |  |

#### ai.neargo.shop.food.dto.DiningOrderBatchVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| batchNo | String | 否 |  |
| storeNo | String | 否 |  |
| checkNo | String | 否 |  |
| cartNo | String | 否 |  |
| cartVersion | long | 是 |  |
| quoteNo | String | 否 |  |
| status | String | 否 |  |
| payMode | String | 否 |  |
| businessDate | LocalDate | 否 |  |
| orderNo | String | 否 |  |
| subOrderNo | String | 否 |  |
| coreStatus | String | 否 |  |
| currency | String | 否 |  |
| amountMinor | Long | 否 |  |
| version | long | 是 |  |

#### ai.neargo.shop.food.dto.KitchenContracts.CoreLineScope

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| allocationNo | String | 否 |  |
| orderItemId | Long | 否 |  |
| sourceComponentRef | String | 否 |  |
| originalQty | BigDecimal | 否 |  |
| unit | String | 否 |  |
| coreSnapshotHash | String | 否 |  |
| version | Long | 否 |  |

#### ai.neargo.shop.food.dto.KitchenContracts.CustodyReceiptVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| operationNo | String | 否 |  |
| entityNo | String | 否 |  |
| storeNo | String | 否 |  |
| batchNo | String | 否 |  |
| orderNo | String | 否 |  |
| storeOrderNo | String | 否 |  |
| bindingNo | String | 否 |  |
| bindingVersion | Long | 否 |  |
| batchVersion | Long | 否 |  |
| frozenRequestHash | String | 否 |  |
| action | String | 否 |  |
| kind | String | 否 |  |
| lines | List<HandoverLine> | 否 |  |
| sources | List<CustodySourceLine> | 否 |  |
| recipientNo | String | 否 |  |
| recipientRef | String | 否 |  |
| actorNo | String | 否 |  |
| recordedAt | String | 否 |  |
| complete | boolean | 是 |  |
| coreDeliveryPending | boolean | 是 |  |

#### ai.neargo.shop.food.dto.KitchenContracts.CustodySourceLine

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| foodLineNo | String | 否 |  |
| sourceLineRef | String | 否 |  |
| originalLines | List<CoreLineScope> | 否 |  |
| tasks | List<KitchenTaskScope> | 否 |  |

#### ai.neargo.shop.food.dto.KitchenContracts.HandoverLine

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| foodLineNo | String | 是 | @NotBlank |
| qty | BigDecimal | 是 | @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull |

#### ai.neargo.shop.food.dto.KitchenContracts.HandoverReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| kind | String | 是 | @NotBlank |
| lines | List<HandoverLine> | 是 | @NotNull |
| reason | String | 是 | @NotBlank @Size(max=255) |
| recipientNo | String | 否 | @Size(max=64) |
| recipientRef | String | 否 | @Size(max=128) |

#### ai.neargo.shop.food.dto.KitchenContracts.HandoverVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| operationNo | String | 否 |  |
| batchNo | String | 否 |  |
| status | String | 否 |  |
| deliveryPending | boolean | 是 |  |
| coreDeliveryRef | String | 否 |  |

#### ai.neargo.shop.food.dto.KitchenContracts.KitchenTaskScope

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| taskNo | String | 否 |  |
| componentNo | String | 否 |  |
| stationNo | String | 否 |  |
| taskRole | String | 否 |  |
| routeVersion | long | 是 |  |
| version | long | 是 |  |

#### ai.neargo.shop.food.dto.KitchenContracts.PackingReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| lines | List<HandoverLine> | 是 | @NotNull |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.ListDiningBatchesQuery

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| page | Integer | 否 | @Min(1) @Max(10000) |
| size | Integer | 否 | @Min(1) @Max(100) |
| originalCheckNo | String | 否 | @Size(max=32) |
| checkNo | String | 否 | @Size(max=32) |
| status | String | 否 | @Size(max=24) |
| businessDate | LocalDate | 否 | @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) |
| orderSource | String | 否 | @Size(max=24) |

#### ai.neargo.shop.food.dto.ListReservationsQuery

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| page | Integer | 否 | @Min(1) |
| size | Integer | 否 | @Min(1) @Max(100) |
| from | Instant | 是 | @NotNull |
| to | Instant | 是 | @NotNull |
| status | String | 否 |  |

#### ai.neargo.shop.food.dto.OperationVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| operationNo | String | 否 |  |
| status | String | 否 |  |
| version | long | 是 |  |
| resultRef | String | 否 |  |

#### ai.neargo.shop.food.dto.ReservationContracts.ArriveReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @NotNull @Min(0) |
| tables | List< TableVersionReq> | 是 | @NotNull @Size(min=1,max=100) @Valid |

#### ai.neargo.shop.food.dto.ReservationContracts.ChangeReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @NotNull @Min(0) |
| appointment | CreateReq | 是 | @NotNull @Valid |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.ReservationContracts.CreateReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| partySize | Integer | 是 | @NotNull @Min(1) |
| startAt | Instant | 是 | @NotNull |
| endAt | Instant | 是 | @NotNull |
| contactRef | String | 是 | @NotBlank @Size(max=64) |
| tables | List< TableVersionReq> | 是 | @NotNull @Size(min=1,max=100) @Valid |

#### ai.neargo.shop.food.dto.ReservationContracts.DetailVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| reservation | ReservationVO | 否 |  |
| tableNos | List<String> | 否 |  |

#### ai.neargo.shop.food.dto.ReservationContracts.TransitionReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @NotNull @Min(0) |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.ReservationVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| reservationNo | String | 否 |  |
| partySize | int | 是 |  |
| startAt | Instant | 否 |  |
| endAt | Instant | 否 |  |
| status | String | 否 |  |
| depositRef | String | 否 |  |
| checkNo | String | 否 |  |
| version | long | 是 |  |

### 当前边界

预约为FOOD本地真实桌台时段分配，不冒充外部资源或已收订金；转桌/加桌只变同台账物理占位，不迁移原订单/资金；AA为原金额责任分摊，settlementEligible=false，不替代FOOD-API-140；包装/交接始终保留coreDeliveryPending，实物凭证不替代核心库存出库。营业日仅SYNC异常双人交接，AR/退款/支付未知不自动消除。批次查询不返回推断的当前欠款或资金终态。

未完成：anonymous and device formal order principals；DELIVERY formal ordering and DINE_IN/DELIVERY core fulfillment completion；bundles with exclusive inventory leaf strategy；fractional original order/inventory quantities；actual recipe material posting/cost；funds execution；cross-check order merging and financial AA；complete terminal UI and offline operation；real vendor hardware/network validation；reports and Keeta actual integration。完整任务保持原TDD未完成状态，不用当前源码条目数量推断全部需求已交付。

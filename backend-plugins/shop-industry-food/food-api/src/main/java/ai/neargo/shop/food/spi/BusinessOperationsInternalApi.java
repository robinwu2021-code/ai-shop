package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.BusinessOperationsContracts;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CheckCollaborationContracts;
import ai.neargo.shop.food.dto.DiningOrderBatchVO;
import ai.neargo.shop.food.dto.FoodFeeSourceContracts;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.ReservationPreorderContracts;
import ai.neargo.shop.food.dto.ServiceFormContracts;
import ai.neargo.shop.food.dto.ServiceSlotContracts;
import ai.neargo.shop.food.service.QueueNotificationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

public interface BusinessOperationsInternalApi {
    @PostExchange("/internal/food/business-operations/serviceForm/registerOrganization")
    ServiceFormContracts.OrganizationVO serviceFormRegisterOrganization(
            @RequestBody ServiceFormRegisterOrganizationCommand command);

    record ServiceFormRegisterOrganizationCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @Valid @NotNull ServiceFormContracts.OrganizationReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/serviceForm/book")
    ServiceFormContracts.FormVO serviceFormBook(@RequestBody ServiceFormBookCommand command);

    record ServiceFormBookCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @Valid @NotNull ServiceFormContracts.BookReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/serviceForm/get")
    ServiceFormContracts.FormVO serviceFormGet(@RequestBody ServiceFormGetCommand command);

    record ServiceFormGetCommand(
            @NotBlank String entity, @NotBlank String store, @NotBlank String form) {}

    @PostExchange("/internal/food/business-operations/serviceForm/revise")
    ServiceFormContracts.FormVO serviceFormRevise(@RequestBody ServiceFormReviseCommand command);

    record ServiceFormReviseCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String form,
            @Valid @NotNull ServiceFormContracts.ReviseReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/serviceForm/enter")
    ServiceFormContracts.FormVO serviceFormEnter(@RequestBody ServiceFormEnterCommand command);

    record ServiceFormEnterCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String form,
            @Valid @NotNull ServiceFormContracts.EnterReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/serviceForm/leave")
    ServiceFormContracts.FormVO serviceFormLeave(@RequestBody ServiceFormLeaveCommand command);

    record ServiceFormLeaveCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String form,
            @Valid @NotNull ServiceFormContracts.VersionReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/serviceForm/cancel")
    ServiceFormContracts.FormVO serviceFormCancel(@RequestBody ServiceFormCancelCommand command);

    record ServiceFormCancelCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String form,
            @Valid @NotNull ServiceFormContracts.VersionReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/serviceForm/noShow")
    ServiceFormContracts.FormVO serviceFormNoShow(@RequestBody ServiceFormNoShowCommand command);

    record ServiceFormNoShowCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String form,
            @Valid @NotNull ServiceFormContracts.VersionReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/serviceForm/bindBatch")
    ServiceFormContracts.FormVO serviceFormBindBatch(
            @RequestBody ServiceFormBindBatchCommand command);

    record ServiceFormBindBatchCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String form,
            @Valid @NotNull ServiceFormContracts.BatchReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/serviceForm/attendees")
    List<ServiceFormContracts.AttendeeVO> serviceFormAttendees(
            @RequestBody ServiceFormAttendeesCommand command);

    record ServiceFormAttendeesCommand(
            @NotBlank String entity, @NotBlank String store, @NotBlank String form) {}

    @PostExchange("/internal/food/business-operations/serviceForm/admit")
    ServiceFormContracts.AttendeeVO serviceFormAdmit(@RequestBody ServiceFormAdmitCommand command);

    record ServiceFormAdmitCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String form,
            @NotBlank String attendee,
            @Valid @NotNull ServiceFormContracts.VersionReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/serviceForm/exit")
    ServiceFormContracts.AttendeeVO serviceFormExit(@RequestBody ServiceFormExitCommand command);

    record ServiceFormExitCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String form,
            @NotBlank String attendee,
            @Valid @NotNull ServiceFormContracts.VersionReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/foodFeeSource/bind")
    FoodFeeSourceContracts.BindingVO foodFeeSourceBind(
            @RequestBody FoodFeeSourceBindCommand command);

    record FoodFeeSourceBindCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @Valid @NotNull FoodFeeSourceContracts.BindReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/foodFeeSource/bindings")
    List<FoodFeeSourceContracts.BindingVO> foodFeeSourceBindings(
            @RequestBody FoodFeeSourceBindingsCommand command);

    record FoodFeeSourceBindingsCommand(
            @NotBlank String entity, @NotBlank String store, @NotBlank String check) {}

    @PostExchange("/internal/food/business-operations/queueNotification/list")
    List<QueueNotificationService.NoticeVO> queueNotificationList(
            @RequestBody QueueNotificationListCommand command);

    record QueueNotificationListCommand(
            @NotBlank String entity, @NotBlank String store, @NotBlank String ticket) {}

    @PostExchange("/internal/food/business-operations/queueNotification/recover")
    QueueNotificationService.NoticeVO queueNotificationRecover(
            @RequestBody QueueNotificationRecoverCommand command);

    record QueueNotificationRecoverCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String notice,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/checkCollaboration/previewReceivable")
    CheckCollaborationContracts.ReceivablePreview checkCollaborationPreviewReceivable(
            @RequestBody CheckCollaborationPreviewReceivableCommand command);

    record CheckCollaborationPreviewReceivableCommand(
            @NotBlank String entity, @NotBlank String store, @NotBlank String check) {}

    @PostExchange("/internal/food/business-operations/checkCollaboration/sourceHash")
    String checkCollaborationSourceHash(@RequestBody CheckCollaborationSourceHashCommand command);

    record CheckCollaborationSourceHashCommand(
            @NotBlank String entity, @NotBlank String store, @NotBlank String check) {}

    @PostExchange("/internal/food/business-operations/checkCollaboration/releaseService")
    OperationVO checkCollaborationReleaseService(
            @RequestBody CheckCollaborationReleaseServiceCommand command);

    record CheckCollaborationReleaseServiceCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String check,
            long version,
            @NotBlank String reason,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/checkCollaboration/currentLineOwner")
    String checkCollaborationCurrentLineOwner(
            @RequestBody CheckCollaborationCurrentLineOwnerCommand command);

    record CheckCollaborationCurrentLineOwnerCommand(
            @NotBlank String entity, @NotBlank String store, @NotBlank String foodLineNo) {}

    @PostExchange("/internal/food/business-operations/checkCollaboration/moveBatches")
    CheckCollaborationContracts.MoveVO checkCollaborationMoveBatches(
            @RequestBody CheckCollaborationMoveBatchesCommand command);

    record CheckCollaborationMoveBatchesCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @Valid @NotNull CheckCollaborationContracts.MoveReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/checkCollaboration/moveLines")
    CheckCollaborationContracts.LineMoveVO checkCollaborationMoveLines(
            @RequestBody CheckCollaborationMoveLinesCommand command);

    record CheckCollaborationMoveLinesCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @Valid @NotNull CheckCollaborationContracts.LineMoveReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/checkCollaboration/freeze")
    CheckCollaborationContracts.FreezeVO checkCollaborationFreeze(
            @RequestBody CheckCollaborationFreezeCommand command);

    record CheckCollaborationFreezeCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String check,
            @Valid @NotNull CheckCollaborationContracts.FreezeReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/checkCollaboration/release")
    CheckCollaborationContracts.FreezeVO checkCollaborationRelease(
            @RequestBody CheckCollaborationReleaseCommand command);

    record CheckCollaborationReleaseCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String freeze,
            long version,
            @NotBlank String reason,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/reservationPreorder/save")
    ReservationPreorderContracts.PreorderVO reservationPreorderSave(
            @RequestBody ReservationPreorderSaveCommand command);

    record ReservationPreorderSaveCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String reservation,
            @Valid @NotNull ReservationPreorderContracts.SaveReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/reservationPreorder/get")
    ReservationPreorderContracts.PreorderVO reservationPreorderGet(
            @RequestBody ReservationPreorderGetCommand command);

    record ReservationPreorderGetCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String reservation,
            @NotBlank String preorder) {}

    @PostExchange("/internal/food/business-operations/reservationPreorder/prepareCart")
    CartResultVO reservationPreorderPrepareCart(
            @RequestBody ReservationPreorderPrepareCartCommand command);

    record ReservationPreorderPrepareCartCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String reservation,
            @NotBlank String preorder,
            long version,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/reservationPreorder/convert")
    DiningOrderBatchVO reservationPreorderConvert(
            @RequestBody ReservationPreorderConvertCommand command);

    record ReservationPreorderConvertCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String reservation,
            @NotBlank String preorder,
            @Valid @NotNull ReservationPreorderContracts.ConvertReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/reservationPreorder/cancel")
    ReservationPreorderContracts.PreorderVO reservationPreorderCancel(
            @RequestBody ReservationPreorderCancelCommand command);

    record ReservationPreorderCancelCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String reservation,
            @NotBlank String preorder,
            long version,
            @NotBlank String reason,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/serviceSlot/create")
    ServiceSlotContracts.SlotVO serviceSlotCreate(@RequestBody ServiceSlotCreateCommand command);

    record ServiceSlotCreateCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @Valid @NotNull ServiceSlotContracts.CreateReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/serviceSlot/get")
    ServiceSlotContracts.SlotVO serviceSlotGet(@RequestBody ServiceSlotGetCommand command);

    record ServiceSlotGetCommand(
            @NotBlank String entity, @NotBlank String store, @NotBlank String slot) {}

    @PostExchange("/internal/food/business-operations/serviceSlot/reserve")
    ServiceSlotContracts.ClaimVO serviceSlotReserve(@RequestBody ServiceSlotReserveCommand command);

    record ServiceSlotReserveCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @Valid @NotNull ServiceSlotContracts.ClaimReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/serviceSlot/transition")
    ServiceSlotContracts.ClaimVO serviceSlotTransition(
            @RequestBody ServiceSlotTransitionCommand command);

    record ServiceSlotTransitionCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String claim,
            long version,
            @NotBlank String action,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/serviceSlot/dispatchDue")
    boolean serviceSlotDispatchDue(@RequestBody ServiceSlotDispatchDueCommand command);

    record ServiceSlotDispatchDueCommand(
            @NotBlank String entity, @NotBlank String store, @NotBlank String sourceNo) {}

    @PostExchange("/internal/food/business-operations/businessOperations/evidence")
    BusinessOperationsContracts.Evidence businessOperationsEvidence(
            @RequestBody BusinessOperationsEvidenceCommand command);

    record BusinessOperationsEvidenceCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @Valid @NotNull BusinessOperationsContracts.Source source) {}

    @PostExchange("/internal/food/business-operations/businessOperations/propose")
    BusinessOperationsContracts.HandoverVO businessOperationsPropose(
            @RequestBody BusinessOperationsProposeCommand command);

    record BusinessOperationsProposeCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @Valid @NotNull BusinessOperationsContracts.HandoverReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/businessOperations/handover")
    BusinessOperationsContracts.HandoverVO businessOperationsHandover(
            @RequestBody BusinessOperationsHandoverCommand command);

    record BusinessOperationsHandoverCommand(
            @NotBlank String entity, @NotBlank String store, @NotBlank String handover) {}

    @PostExchange("/internal/food/business-operations/businessOperations/accept")
    BusinessOperationsContracts.HandoverVO businessOperationsAccept(
            @RequestBody BusinessOperationsAcceptCommand command);

    record BusinessOperationsAcceptCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String handover,
            @Valid @NotNull BusinessOperationsContracts.DecisionReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/businessOperations/cancel")
    BusinessOperationsContracts.HandoverVO businessOperationsCancel(
            @RequestBody BusinessOperationsCancelCommand command);

    record BusinessOperationsCancelCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String handover,
            @Valid @NotNull BusinessOperationsContracts.DecisionReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/businessOperations/openCase")
    BusinessOperationsContracts.CaseVO businessOperationsOpenCase(
            @RequestBody BusinessOperationsOpenCaseCommand command);

    record BusinessOperationsOpenCaseCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @Valid @NotNull BusinessOperationsContracts.CaseReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/businessOperations/cases")
    List<BusinessOperationsContracts.CaseVO> businessOperationsCases(
            @RequestBody BusinessOperationsCasesCommand command);

    record BusinessOperationsCasesCommand(
            @NotBlank String entity, @NotBlank String store, String status) {}

    @PostExchange("/internal/food/business-operations/businessOperations/recoverCase")
    BusinessOperationsContracts.CaseVO businessOperationsRecoverCase(
            @RequestBody BusinessOperationsRecoverCaseCommand command);

    record BusinessOperationsRecoverCaseCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String caseNo,
            @Valid @NotNull BusinessOperationsContracts.DecisionReq request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/business-operations/businessOperations/resolveCase")
    BusinessOperationsContracts.CaseVO businessOperationsResolveCase(
            @RequestBody BusinessOperationsResolveCaseCommand command);

    record BusinessOperationsResolveCaseCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String caseNo,
            @Valid @NotNull BusinessOperationsContracts.DecisionReq request,
            @NotBlank String key) {}
}

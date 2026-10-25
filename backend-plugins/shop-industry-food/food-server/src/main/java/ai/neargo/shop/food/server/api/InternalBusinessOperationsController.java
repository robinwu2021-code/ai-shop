package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration;
import ai.neargo.shop.food.dto.BusinessOperationsContracts;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CheckCollaborationContracts;
import ai.neargo.shop.food.dto.DiningOrderBatchVO;
import ai.neargo.shop.food.dto.FoodFeeSourceContracts;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.ReservationPreorderContracts;
import ai.neargo.shop.food.dto.ServiceFormContracts;
import ai.neargo.shop.food.dto.ServiceSlotContracts;
import ai.neargo.shop.food.service.BusinessOperationsService;
import ai.neargo.shop.food.service.CheckCollaborationService;
import ai.neargo.shop.food.service.FoodFeeSourceService;
import ai.neargo.shop.food.service.QueueNotificationService;
import ai.neargo.shop.food.service.ReservationPreorderService;
import ai.neargo.shop.food.service.ServiceFormService;
import ai.neargo.shop.food.service.ServiceSlotService;
import ai.neargo.shop.food.spi.BusinessOperationsInternalApi;

import jakarta.validation.Valid;

import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class InternalBusinessOperationsController {
    private final ServiceFormService serviceForm;
    private final FoodFeeSourceService foodFeeSource;
    private final QueueNotificationService queueNotification;
    private final CheckCollaborationService checkCollaboration;
    private final ReservationPreorderService reservationPreorder;
    private final ServiceSlotService serviceSlot;
    private final BusinessOperationsService businessOperations;

    public InternalBusinessOperationsController(
            ServiceFormService serviceForm,
            FoodFeeSourceService foodFeeSource,
            QueueNotificationService queueNotification,
            CheckCollaborationService checkCollaboration,
            ReservationPreorderService reservationPreorder,
            ServiceSlotService serviceSlot,
            BusinessOperationsService businessOperations) {
        this.serviceForm = serviceForm;
        this.foodFeeSource = foodFeeSource;
        this.queueNotification = queueNotification;
        this.checkCollaboration = checkCollaboration;
        this.reservationPreorder = reservationPreorder;
        this.serviceSlot = serviceSlot;
        this.businessOperations = businessOperations;
    }

    @PostMapping("/internal/food/business-operations/serviceForm/registerOrganization")
    public ServiceFormContracts.OrganizationVO serviceFormRegisterOrganization(
            @Valid @RequestBody
                    BusinessOperationsInternalApi.ServiceFormRegisterOrganizationCommand c) {
        return serviceForm.registerOrganization(
                c.entity(), c.store(), c.actor(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/serviceForm/book")
    public ServiceFormContracts.FormVO serviceFormBook(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormBookCommand c) {
        return serviceForm.book(c.entity(), c.store(), c.actor(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/serviceForm/get")
    public ServiceFormContracts.FormVO serviceFormGet(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormGetCommand c) {
        return serviceForm.get(c.entity(), c.store(), c.form());
    }

    @PostMapping("/internal/food/business-operations/serviceForm/revise")
    public ServiceFormContracts.FormVO serviceFormRevise(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormReviseCommand c) {
        return serviceForm.revise(c.entity(), c.store(), c.actor(), c.form(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/serviceForm/enter")
    public ServiceFormContracts.FormVO serviceFormEnter(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormEnterCommand c) {
        return serviceForm.enter(c.entity(), c.store(), c.actor(), c.form(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/serviceForm/leave")
    public ServiceFormContracts.FormVO serviceFormLeave(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormLeaveCommand c) {
        return serviceForm.leave(c.entity(), c.store(), c.actor(), c.form(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/serviceForm/cancel")
    public ServiceFormContracts.FormVO serviceFormCancel(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormCancelCommand c) {
        return serviceForm.cancel(c.entity(), c.store(), c.actor(), c.form(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/serviceForm/noShow")
    public ServiceFormContracts.FormVO serviceFormNoShow(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormNoShowCommand c) {
        return serviceForm.noShow(c.entity(), c.store(), c.actor(), c.form(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/serviceForm/bindBatch")
    public ServiceFormContracts.FormVO serviceFormBindBatch(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormBindBatchCommand c) {
        return serviceForm.bindBatch(
                c.entity(), c.store(), c.actor(), c.form(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/serviceForm/attendees")
    public List<ServiceFormContracts.AttendeeVO> serviceFormAttendees(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormAttendeesCommand c) {
        return serviceForm.attendees(c.entity(), c.store(), c.form());
    }

    @PostMapping("/internal/food/business-operations/serviceForm/admit")
    public ServiceFormContracts.AttendeeVO serviceFormAdmit(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormAdmitCommand c) {
        return serviceForm.admit(
                c.entity(), c.store(), c.actor(), c.form(), c.attendee(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/serviceForm/exit")
    public ServiceFormContracts.AttendeeVO serviceFormExit(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormExitCommand c) {
        return serviceForm.exit(
                c.entity(), c.store(), c.actor(), c.form(), c.attendee(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/foodFeeSource/bind")
    public FoodFeeSourceContracts.BindingVO foodFeeSourceBind(
            @Valid @RequestBody BusinessOperationsInternalApi.FoodFeeSourceBindCommand c) {
        return foodFeeSource.bind(c.entity(), c.store(), c.actor(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/foodFeeSource/bindings")
    public List<FoodFeeSourceContracts.BindingVO> foodFeeSourceBindings(
            @Valid @RequestBody BusinessOperationsInternalApi.FoodFeeSourceBindingsCommand c) {
        return foodFeeSource.bindings(c.entity(), c.store(), c.check());
    }

    @PostMapping("/internal/food/business-operations/queueNotification/list")
    public List<QueueNotificationService.NoticeVO> queueNotificationList(
            @Valid @RequestBody BusinessOperationsInternalApi.QueueNotificationListCommand c) {
        return queueNotification.list(c.entity(), c.store(), c.ticket());
    }

    @PostMapping("/internal/food/business-operations/queueNotification/recover")
    public QueueNotificationService.NoticeVO queueNotificationRecover(
            @Valid @RequestBody BusinessOperationsInternalApi.QueueNotificationRecoverCommand c) {
        return queueNotification.recover(c.entity(), c.store(), c.actor(), c.notice(), c.key());
    }

    @PostMapping("/internal/food/business-operations/checkCollaboration/previewReceivable")
    public CheckCollaborationContracts.ReceivablePreview checkCollaborationPreviewReceivable(
            @Valid @RequestBody
                    BusinessOperationsInternalApi.CheckCollaborationPreviewReceivableCommand c) {
        return checkCollaboration.previewReceivable(c.entity(), c.store(), c.check());
    }

    @PostMapping("/internal/food/business-operations/checkCollaboration/sourceHash")
    public String checkCollaborationSourceHash(
            @Valid @RequestBody
                    BusinessOperationsInternalApi.CheckCollaborationSourceHashCommand c) {
        return checkCollaboration.sourceHash(c.entity(), c.store(), c.check());
    }

    @PostMapping("/internal/food/business-operations/checkCollaboration/releaseService")
    public OperationVO checkCollaborationReleaseService(
            @Valid @RequestBody
                    BusinessOperationsInternalApi.CheckCollaborationReleaseServiceCommand c) {
        return checkCollaboration.releaseService(
                c.entity(), c.store(), c.actor(), c.check(), c.version(), c.reason(), c.key());
    }

    @PostMapping("/internal/food/business-operations/checkCollaboration/currentLineOwner")
    public String checkCollaborationCurrentLineOwner(
            @Valid @RequestBody
                    BusinessOperationsInternalApi.CheckCollaborationCurrentLineOwnerCommand c) {
        return checkCollaboration.currentLineOwner(c.entity(), c.store(), c.foodLineNo());
    }

    @PostMapping("/internal/food/business-operations/checkCollaboration/moveBatches")
    public CheckCollaborationContracts.MoveVO checkCollaborationMoveBatches(
            @Valid @RequestBody
                    BusinessOperationsInternalApi.CheckCollaborationMoveBatchesCommand c) {
        return checkCollaboration.moveBatches(
                c.entity(), c.store(), c.actor(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/checkCollaboration/moveLines")
    public CheckCollaborationContracts.LineMoveVO checkCollaborationMoveLines(
            @Valid @RequestBody
                    BusinessOperationsInternalApi.CheckCollaborationMoveLinesCommand c) {
        return checkCollaboration.moveLines(c.entity(), c.store(), c.actor(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/checkCollaboration/freeze")
    public CheckCollaborationContracts.FreezeVO checkCollaborationFreeze(
            @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationFreezeCommand c) {
        return checkCollaboration.freeze(
                c.entity(), c.store(), c.actor(), c.check(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/checkCollaboration/release")
    public CheckCollaborationContracts.FreezeVO checkCollaborationRelease(
            @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationReleaseCommand c) {
        return checkCollaboration.release(
                c.entity(), c.store(), c.actor(), c.freeze(), c.version(), c.reason(), c.key());
    }

    @PostMapping("/internal/food/business-operations/reservationPreorder/save")
    public ReservationPreorderContracts.PreorderVO reservationPreorderSave(
            @Valid @RequestBody BusinessOperationsInternalApi.ReservationPreorderSaveCommand c) {
        return reservationPreorder.save(
                c.entity(), c.store(), c.actor(), c.reservation(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/reservationPreorder/get")
    public ReservationPreorderContracts.PreorderVO reservationPreorderGet(
            @Valid @RequestBody BusinessOperationsInternalApi.ReservationPreorderGetCommand c) {
        return reservationPreorder.get(c.entity(), c.store(), c.reservation(), c.preorder());
    }

    @PostMapping("/internal/food/business-operations/reservationPreorder/prepareCart")
    public CartResultVO reservationPreorderPrepareCart(
            @Valid @RequestBody
                    BusinessOperationsInternalApi.ReservationPreorderPrepareCartCommand c) {
        return reservationPreorder.prepareCart(
                c.entity(),
                c.store(),
                c.actor(),
                c.reservation(),
                c.preorder(),
                c.version(),
                c.key());
    }

    @PostMapping("/internal/food/business-operations/reservationPreorder/convert")
    public DiningOrderBatchVO reservationPreorderConvert(
            @Valid @RequestBody BusinessOperationsInternalApi.ReservationPreorderConvertCommand c) {
        return reservationPreorder.convert(
                c.entity(),
                c.store(),
                c.actor(),
                c.reservation(),
                c.preorder(),
                c.request(),
                c.key());
    }

    @PostMapping("/internal/food/business-operations/reservationPreorder/cancel")
    public ReservationPreorderContracts.PreorderVO reservationPreorderCancel(
            @Valid @RequestBody BusinessOperationsInternalApi.ReservationPreorderCancelCommand c) {
        return reservationPreorder.cancel(
                c.entity(),
                c.store(),
                c.actor(),
                c.reservation(),
                c.preorder(),
                c.version(),
                c.reason(),
                c.key());
    }

    @PostMapping("/internal/food/business-operations/serviceSlot/create")
    public ServiceSlotContracts.SlotVO serviceSlotCreate(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceSlotCreateCommand c) {
        return serviceSlot.create(c.entity(), c.store(), c.actor(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/serviceSlot/get")
    public ServiceSlotContracts.SlotVO serviceSlotGet(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceSlotGetCommand c) {
        return serviceSlot.get(c.entity(), c.store(), c.slot());
    }

    @PostMapping("/internal/food/business-operations/serviceSlot/reserve")
    public ServiceSlotContracts.ClaimVO serviceSlotReserve(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceSlotReserveCommand c) {
        return serviceSlot.reserve(c.entity(), c.store(), c.actor(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/serviceSlot/transition")
    public ServiceSlotContracts.ClaimVO serviceSlotTransition(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceSlotTransitionCommand c) {
        return serviceSlot.transition(
                c.entity(), c.store(), c.actor(), c.claim(), c.version(), c.action(), c.key());
    }

    @PostMapping("/internal/food/business-operations/serviceSlot/dispatchDue")
    public boolean serviceSlotDispatchDue(
            @Valid @RequestBody BusinessOperationsInternalApi.ServiceSlotDispatchDueCommand c) {
        return serviceSlot.dispatchDue(c.entity(), c.store(), c.sourceNo());
    }

    @PostMapping("/internal/food/business-operations/businessOperations/evidence")
    public BusinessOperationsContracts.Evidence businessOperationsEvidence(
            @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsEvidenceCommand c) {
        return businessOperations.evidence(c.entity(), c.store(), c.source());
    }

    @PostMapping("/internal/food/business-operations/businessOperations/propose")
    public BusinessOperationsContracts.HandoverVO businessOperationsPropose(
            @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsProposeCommand c) {
        return businessOperations.propose(c.entity(), c.store(), c.actor(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/businessOperations/handover")
    public BusinessOperationsContracts.HandoverVO businessOperationsHandover(
            @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsHandoverCommand c) {
        return businessOperations.handover(c.entity(), c.store(), c.handover());
    }

    @PostMapping("/internal/food/business-operations/businessOperations/accept")
    public BusinessOperationsContracts.HandoverVO businessOperationsAccept(
            @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsAcceptCommand c) {
        return businessOperations.accept(
                c.entity(), c.store(), c.actor(), c.handover(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/businessOperations/cancel")
    public BusinessOperationsContracts.HandoverVO businessOperationsCancel(
            @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsCancelCommand c) {
        return businessOperations.cancel(
                c.entity(), c.store(), c.actor(), c.handover(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/businessOperations/openCase")
    public BusinessOperationsContracts.CaseVO businessOperationsOpenCase(
            @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsOpenCaseCommand c) {
        return businessOperations.openCase(c.entity(), c.store(), c.actor(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/businessOperations/cases")
    public List<BusinessOperationsContracts.CaseVO> businessOperationsCases(
            @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsCasesCommand c) {
        return businessOperations.cases(c.entity(), c.store(), c.status());
    }

    @PostMapping("/internal/food/business-operations/businessOperations/recoverCase")
    public BusinessOperationsContracts.CaseVO businessOperationsRecoverCase(
            @Valid @RequestBody
                    BusinessOperationsInternalApi.BusinessOperationsRecoverCaseCommand c) {
        return businessOperations.recoverCase(
                c.entity(), c.store(), c.actor(), c.caseNo(), c.request(), c.key());
    }

    @PostMapping("/internal/food/business-operations/businessOperations/resolveCase")
    public BusinessOperationsContracts.CaseVO businessOperationsResolveCase(
            @Valid @RequestBody
                    BusinessOperationsInternalApi.BusinessOperationsResolveCaseCommand c) {
        return businessOperations.resolveCase(
                c.entity(), c.store(), c.actor(), c.caseNo(), c.request(), c.key());
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> failure(BizException failure) {
        int code =
                switch (failure.errorCode()) {
                    case BAD_REQUEST -> 400;
                    case FORBIDDEN -> 403;
                    case NOT_FOUND -> 404;
                    case CONFLICT -> 409;
                    default -> 500;
                };
        return ResponseEntity.status(code).build();
    }
}

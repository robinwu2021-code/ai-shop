package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
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
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
public class BizBusinessOperationsController {
    private final FoodBizStoreScope scope;
    private final ServiceFormService serviceForm;
    private final FoodFeeSourceService foodFeeSource;
    private final QueueNotificationService queueNotification;
    private final CheckCollaborationService checkCollaboration;
    private final ReservationPreorderService reservationPreorder;
    private final ServiceSlotService serviceSlot;
    private final BusinessOperationsService businessOperations;

    public BizBusinessOperationsController(
            ServiceFormService serviceForm,
            FoodFeeSourceService foodFeeSource,
            QueueNotificationService queueNotification,
            CheckCollaborationService checkCollaboration,
            ReservationPreorderService reservationPreorder,
            ServiceSlotService serviceSlot,
            BusinessOperationsService businessOperations,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        this.serviceForm = serviceForm;
        this.foodFeeSource = foodFeeSource;
        this.queueNotification = queueNotification;
        this.checkCollaboration = checkCollaboration;
        this.reservationPreorder = reservationPreorder;
        this.serviceSlot = serviceSlot;
        this.businessOperations = businessOperations;
        scope = new FoodBizStoreScope(stores, validator);
    }

    public record ServiceFormRegisterOrganizationRequest(
            @Valid @NotNull ServiceFormContracts.OrganizationReq request) {}

    @PostMapping("/biz/x/food/business-operations/serviceForm/registerOrganization")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceFormContracts.OrganizationVO serviceFormRegisterOrganization(
            @Valid @RequestBody ServiceFormRegisterOrganizationRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceForm.registerOrganization(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.request(),
                key);
    }

    public record ServiceFormBookRequest(@Valid @NotNull ServiceFormContracts.BookReq request) {}

    @PostMapping("/biz/x/food/business-operations/serviceForm/book")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceFormContracts.FormVO serviceFormBook(
            @Valid @RequestBody ServiceFormBookRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceForm.book(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.request(),
                key);
    }

    public record ServiceFormGetRequest(@NotBlank String form) {}

    @PostMapping("/biz/x/food/business-operations/serviceForm/get")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public ServiceFormContracts.FormVO serviceFormGet(
            @Valid @RequestBody ServiceFormGetRequest r, HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceForm.get(c.merchantNo(), c.currentStoreNo(), r.form());
    }

    public record ServiceFormReviseRequest(
            @NotBlank String form, @Valid @NotNull ServiceFormContracts.ReviseReq request) {}

    @PostMapping("/biz/x/food/business-operations/serviceForm/revise")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceFormContracts.FormVO serviceFormRevise(
            @Valid @RequestBody ServiceFormReviseRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceForm.revise(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.form(),
                r.request(),
                key);
    }

    public record ServiceFormEnterRequest(
            @NotBlank String form, @Valid @NotNull ServiceFormContracts.EnterReq request) {}

    @PostMapping("/biz/x/food/business-operations/serviceForm/enter")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceFormContracts.FormVO serviceFormEnter(
            @Valid @RequestBody ServiceFormEnterRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceForm.enter(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.form(),
                r.request(),
                key);
    }

    public record ServiceFormLeaveRequest(
            @NotBlank String form, @Valid @NotNull ServiceFormContracts.VersionReq request) {}

    @PostMapping("/biz/x/food/business-operations/serviceForm/leave")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceFormContracts.FormVO serviceFormLeave(
            @Valid @RequestBody ServiceFormLeaveRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceForm.leave(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.form(),
                r.request(),
                key);
    }

    public record ServiceFormCancelRequest(
            @NotBlank String form, @Valid @NotNull ServiceFormContracts.VersionReq request) {}

    @PostMapping("/biz/x/food/business-operations/serviceForm/cancel")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceFormContracts.FormVO serviceFormCancel(
            @Valid @RequestBody ServiceFormCancelRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceForm.cancel(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.form(),
                r.request(),
                key);
    }

    public record ServiceFormNoShowRequest(
            @NotBlank String form, @Valid @NotNull ServiceFormContracts.VersionReq request) {}

    @PostMapping("/biz/x/food/business-operations/serviceForm/noShow")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceFormContracts.FormVO serviceFormNoShow(
            @Valid @RequestBody ServiceFormNoShowRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceForm.noShow(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.form(),
                r.request(),
                key);
    }

    public record ServiceFormBindBatchRequest(
            @NotBlank String form, @Valid @NotNull ServiceFormContracts.BatchReq request) {}

    @PostMapping("/biz/x/food/business-operations/serviceForm/bindBatch")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceFormContracts.FormVO serviceFormBindBatch(
            @Valid @RequestBody ServiceFormBindBatchRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceForm.bindBatch(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.form(),
                r.request(),
                key);
    }

    public record ServiceFormAttendeesRequest(@NotBlank String form) {}

    @PostMapping("/biz/x/food/business-operations/serviceForm/attendees")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public List<ServiceFormContracts.AttendeeVO> serviceFormAttendees(
            @Valid @RequestBody ServiceFormAttendeesRequest r, HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceForm.attendees(c.merchantNo(), c.currentStoreNo(), r.form());
    }

    public record ServiceFormAdmitRequest(
            @NotBlank String form,
            @NotBlank String attendee,
            @Valid @NotNull ServiceFormContracts.VersionReq request) {}

    @PostMapping("/biz/x/food/business-operations/serviceForm/admit")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceFormContracts.AttendeeVO serviceFormAdmit(
            @Valid @RequestBody ServiceFormAdmitRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceForm.admit(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.form(),
                r.attendee(),
                r.request(),
                key);
    }

    public record ServiceFormExitRequest(
            @NotBlank String form,
            @NotBlank String attendee,
            @Valid @NotNull ServiceFormContracts.VersionReq request) {}

    @PostMapping("/biz/x/food/business-operations/serviceForm/exit")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceFormContracts.AttendeeVO serviceFormExit(
            @Valid @RequestBody ServiceFormExitRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceForm.exit(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.form(),
                r.attendee(),
                r.request(),
                key);
    }

    public record FoodFeeSourceBindRequest(
            @Valid @NotNull FoodFeeSourceContracts.BindReq request) {}

    @PostMapping("/biz/x/food/business-operations/foodFeeSource/bind")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_PUBLISH + "')")
    public FoodFeeSourceContracts.BindingVO foodFeeSourceBind(
            @Valid @RequestBody FoodFeeSourceBindRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return foodFeeSource.bind(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.request(),
                key);
    }

    public record FoodFeeSourceBindingsRequest(@NotBlank String check) {}

    @PostMapping("/biz/x/food/business-operations/foodFeeSource/bindings")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_READ + "')")
    public List<FoodFeeSourceContracts.BindingVO> foodFeeSourceBindings(
            @Valid @RequestBody FoodFeeSourceBindingsRequest r, HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return foodFeeSource.bindings(c.merchantNo(), c.currentStoreNo(), r.check());
    }

    public record QueueNotificationListRequest(@NotBlank String ticket) {}

    @PostMapping("/biz/x/food/business-operations/queueNotification/list")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public List<QueueNotificationService.NoticeVO> queueNotificationList(
            @Valid @RequestBody QueueNotificationListRequest r, HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return queueNotification.list(c.merchantNo(), c.currentStoreNo(), r.ticket());
    }

    public record QueueNotificationRecoverRequest(@NotBlank String notice) {}

    @PostMapping("/biz/x/food/business-operations/queueNotification/recover")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public QueueNotificationService.NoticeVO queueNotificationRecover(
            @Valid @RequestBody QueueNotificationRecoverRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return queueNotification.recover(
                c.merchantNo(), c.currentStoreNo(), SecurityUtils.currentUserNo(), r.notice(), key);
    }

    public record CheckCollaborationPreviewReceivableRequest(@NotBlank String check) {}

    @PostMapping("/biz/x/food/business-operations/checkCollaboration/previewReceivable")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public CheckCollaborationContracts.ReceivablePreview checkCollaborationPreviewReceivable(
            @Valid @RequestBody CheckCollaborationPreviewReceivableRequest r,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return checkCollaboration.previewReceivable(c.merchantNo(), c.currentStoreNo(), r.check());
    }

    public record CheckCollaborationSourceHashRequest(@NotBlank String check) {}

    @PostMapping("/biz/x/food/business-operations/checkCollaboration/sourceHash")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public String checkCollaborationSourceHash(
            @Valid @RequestBody CheckCollaborationSourceHashRequest r, HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return checkCollaboration.sourceHash(c.merchantNo(), c.currentStoreNo(), r.check());
    }

    public record CheckCollaborationReleaseServiceRequest(
            @NotBlank String check, long version, @NotBlank String reason) {}

    @PostMapping("/biz/x/food/business-operations/checkCollaboration/releaseService")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public OperationVO checkCollaborationReleaseService(
            @Valid @RequestBody CheckCollaborationReleaseServiceRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return checkCollaboration.releaseService(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.check(),
                r.version(),
                r.reason(),
                key);
    }

    public record CheckCollaborationCurrentLineOwnerRequest(@NotBlank String foodLineNo) {}

    @PostMapping("/biz/x/food/business-operations/checkCollaboration/currentLineOwner")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public String checkCollaborationCurrentLineOwner(
            @Valid @RequestBody CheckCollaborationCurrentLineOwnerRequest r,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return checkCollaboration.currentLineOwner(
                c.merchantNo(), c.currentStoreNo(), r.foodLineNo());
    }

    public record CheckCollaborationMoveBatchesRequest(
            @Valid @NotNull CheckCollaborationContracts.MoveReq request) {}

    @PostMapping("/biz/x/food/business-operations/checkCollaboration/moveBatches")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public CheckCollaborationContracts.MoveVO checkCollaborationMoveBatches(
            @Valid @RequestBody CheckCollaborationMoveBatchesRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return checkCollaboration.moveBatches(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.request(),
                key);
    }

    public record CheckCollaborationMoveLinesRequest(
            @Valid @NotNull CheckCollaborationContracts.LineMoveReq request) {}

    @PostMapping("/biz/x/food/business-operations/checkCollaboration/moveLines")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public CheckCollaborationContracts.LineMoveVO checkCollaborationMoveLines(
            @Valid @RequestBody CheckCollaborationMoveLinesRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return checkCollaboration.moveLines(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.request(),
                key);
    }

    public record CheckCollaborationFreezeRequest(
            @NotBlank String check,
            @Valid @NotNull CheckCollaborationContracts.FreezeReq request) {}

    @PostMapping("/biz/x/food/business-operations/checkCollaboration/freeze")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public CheckCollaborationContracts.FreezeVO checkCollaborationFreeze(
            @Valid @RequestBody CheckCollaborationFreezeRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return checkCollaboration.freeze(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.check(),
                r.request(),
                key);
    }

    public record CheckCollaborationReleaseRequest(
            @NotBlank String freeze, long version, @NotBlank String reason) {}

    @PostMapping("/biz/x/food/business-operations/checkCollaboration/release")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public CheckCollaborationContracts.FreezeVO checkCollaborationRelease(
            @Valid @RequestBody CheckCollaborationReleaseRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return checkCollaboration.release(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.freeze(),
                r.version(),
                r.reason(),
                key);
    }

    public record ReservationPreorderSaveRequest(
            @NotBlank String reservation,
            @Valid @NotNull ReservationPreorderContracts.SaveReq request) {}

    @PostMapping("/biz/x/food/business-operations/reservationPreorder/save")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ReservationPreorderContracts.PreorderVO reservationPreorderSave(
            @Valid @RequestBody ReservationPreorderSaveRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return reservationPreorder.save(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.reservation(),
                r.request(),
                key);
    }

    public record ReservationPreorderGetRequest(
            @NotBlank String reservation, @NotBlank String preorder) {}

    @PostMapping("/biz/x/food/business-operations/reservationPreorder/get")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public ReservationPreorderContracts.PreorderVO reservationPreorderGet(
            @Valid @RequestBody ReservationPreorderGetRequest r, HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return reservationPreorder.get(
                c.merchantNo(), c.currentStoreNo(), r.reservation(), r.preorder());
    }

    public record ReservationPreorderPrepareCartRequest(
            @NotBlank String reservation, @NotBlank String preorder, long version) {}

    @PostMapping("/biz/x/food/business-operations/reservationPreorder/prepareCart")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public CartResultVO reservationPreorderPrepareCart(
            @Valid @RequestBody ReservationPreorderPrepareCartRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return reservationPreorder.prepareCart(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.reservation(),
                r.preorder(),
                r.version(),
                key);
    }

    public record ReservationPreorderConvertRequest(
            @NotBlank String reservation,
            @NotBlank String preorder,
            @Valid @NotNull ReservationPreorderContracts.ConvertReq request) {}

    @PostMapping("/biz/x/food/business-operations/reservationPreorder/convert")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public DiningOrderBatchVO reservationPreorderConvert(
            @Valid @RequestBody ReservationPreorderConvertRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return reservationPreorder.convert(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.reservation(),
                r.preorder(),
                r.request(),
                key);
    }

    public record ReservationPreorderCancelRequest(
            @NotBlank String reservation,
            @NotBlank String preorder,
            long version,
            @NotBlank String reason) {}

    @PostMapping("/biz/x/food/business-operations/reservationPreorder/cancel")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ReservationPreorderContracts.PreorderVO reservationPreorderCancel(
            @Valid @RequestBody ReservationPreorderCancelRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return reservationPreorder.cancel(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.reservation(),
                r.preorder(),
                r.version(),
                r.reason(),
                key);
    }

    public record ServiceSlotCreateRequest(
            @Valid @NotNull ServiceSlotContracts.CreateReq request) {}

    @PostMapping("/biz/x/food/business-operations/serviceSlot/create")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceSlotContracts.SlotVO serviceSlotCreate(
            @Valid @RequestBody ServiceSlotCreateRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceSlot.create(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.request(),
                key);
    }

    public record ServiceSlotGetRequest(@NotBlank String slot) {}

    @PostMapping("/biz/x/food/business-operations/serviceSlot/get")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public ServiceSlotContracts.SlotVO serviceSlotGet(
            @Valid @RequestBody ServiceSlotGetRequest r, HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceSlot.get(c.merchantNo(), c.currentStoreNo(), r.slot());
    }

    public record ServiceSlotReserveRequest(
            @Valid @NotNull ServiceSlotContracts.ClaimReq request) {}

    @PostMapping("/biz/x/food/business-operations/serviceSlot/reserve")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceSlotContracts.ClaimVO serviceSlotReserve(
            @Valid @RequestBody ServiceSlotReserveRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceSlot.reserve(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.request(),
                key);
    }

    public record ServiceSlotTransitionRequest(
            @NotBlank String claim, long version, @NotBlank String action) {}

    @PostMapping("/biz/x/food/business-operations/serviceSlot/transition")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceSlotContracts.ClaimVO serviceSlotTransition(
            @Valid @RequestBody ServiceSlotTransitionRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceSlot.transition(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.claim(),
                r.version(),
                r.action(),
                key);
    }

    public record ServiceSlotDispatchDueRequest(@NotBlank String sourceNo) {}

    @PostMapping("/biz/x/food/business-operations/serviceSlot/dispatchDue")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public boolean serviceSlotDispatchDue(
            @Valid @RequestBody ServiceSlotDispatchDueRequest r, HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return serviceSlot.dispatchDue(c.merchantNo(), c.currentStoreNo(), r.sourceNo());
    }

    public record BusinessOperationsEvidenceRequest(
            @Valid @NotNull BusinessOperationsContracts.Source source) {}

    @PostMapping("/biz/x/food/business-operations/businessOperations/evidence")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public BusinessOperationsContracts.Evidence businessOperationsEvidence(
            @Valid @RequestBody BusinessOperationsEvidenceRequest r, HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return businessOperations.evidence(c.merchantNo(), c.currentStoreNo(), r.source());
    }

    public record BusinessOperationsProposeRequest(
            @Valid @NotNull BusinessOperationsContracts.HandoverReq request) {}

    @PostMapping("/biz/x/food/business-operations/businessOperations/propose")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public BusinessOperationsContracts.HandoverVO businessOperationsPropose(
            @Valid @RequestBody BusinessOperationsProposeRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return businessOperations.propose(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.request(),
                key);
    }

    public record BusinessOperationsHandoverRequest(@NotBlank String handover) {}

    @PostMapping("/biz/x/food/business-operations/businessOperations/handover")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public BusinessOperationsContracts.HandoverVO businessOperationsHandover(
            @Valid @RequestBody BusinessOperationsHandoverRequest r, HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return businessOperations.handover(c.merchantNo(), c.currentStoreNo(), r.handover());
    }

    public record BusinessOperationsAcceptRequest(
            @NotBlank String handover,
            @Valid @NotNull BusinessOperationsContracts.DecisionReq request) {}

    @PostMapping("/biz/x/food/business-operations/businessOperations/accept")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public BusinessOperationsContracts.HandoverVO businessOperationsAccept(
            @Valid @RequestBody BusinessOperationsAcceptRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return businessOperations.accept(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.handover(),
                r.request(),
                key);
    }

    public record BusinessOperationsCancelRequest(
            @NotBlank String handover,
            @Valid @NotNull BusinessOperationsContracts.DecisionReq request) {}

    @PostMapping("/biz/x/food/business-operations/businessOperations/cancel")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public BusinessOperationsContracts.HandoverVO businessOperationsCancel(
            @Valid @RequestBody BusinessOperationsCancelRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return businessOperations.cancel(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.handover(),
                r.request(),
                key);
    }

    public record BusinessOperationsOpenCaseRequest(
            @Valid @NotNull BusinessOperationsContracts.CaseReq request) {}

    @PostMapping("/biz/x/food/business-operations/businessOperations/openCase")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public BusinessOperationsContracts.CaseVO businessOperationsOpenCase(
            @Valid @RequestBody BusinessOperationsOpenCaseRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return businessOperations.openCase(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.request(),
                key);
    }

    public record BusinessOperationsCasesRequest(String status) {}

    @PostMapping("/biz/x/food/business-operations/businessOperations/cases")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public List<BusinessOperationsContracts.CaseVO> businessOperationsCases(
            @Valid @RequestBody BusinessOperationsCasesRequest r, HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return businessOperations.cases(c.merchantNo(), c.currentStoreNo(), r.status());
    }

    public record BusinessOperationsRecoverCaseRequest(
            @NotBlank String caseNo,
            @Valid @NotNull BusinessOperationsContracts.DecisionReq request) {}

    @PostMapping("/biz/x/food/business-operations/businessOperations/recoverCase")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public BusinessOperationsContracts.CaseVO businessOperationsRecoverCase(
            @Valid @RequestBody BusinessOperationsRecoverCaseRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return businessOperations.recoverCase(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.caseNo(),
                r.request(),
                key);
    }

    public record BusinessOperationsResolveCaseRequest(
            @NotBlank String caseNo,
            @Valid @NotNull BusinessOperationsContracts.DecisionReq request) {}

    @PostMapping("/biz/x/food/business-operations/businessOperations/resolveCase")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public BusinessOperationsContracts.CaseVO businessOperationsResolveCase(
            @Valid @RequestBody BusinessOperationsResolveCaseRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return businessOperations.resolveCase(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.caseNo(),
                r.request(),
                key);
    }
}

package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.svc.InternalHttp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Concrete private routes use one token/context lifecycle and the existing business owners. */
@RestController
@ConditionalOnBeautyEnabled
@Profile("api")
public class InternalBeautyWorkOrderController {
    private final BoundDelegatedCalls calls;
    private final AppointmentService appointmentService;
    private final WorkOrderService workOrderService;

    /** Production dependencies are mandatory; there is no empty provider fallback. */
    public InternalBeautyWorkOrderController(
            AppointmentService appointmentService,
            WorkOrderService workOrderService,
            @Value("${shop.services.internal-token:}") String token) {
        calls = new BoundDelegatedCalls(token);
        this.appointmentService = appointmentService;
        this.workOrderService = workOrderService;
    }

    /** The fixed route chooses OPEN_APPOINTMENT; the caller cannot dispatch another action. */
    @PostMapping("/internal/beauty/reception/openWorkOrderFromAppointment")
    public ResponseEntity<?> openWorkOrderFromAppointment(
            @RequestBody InternalCall<BeautyReceptionInternalApi.OpenAppointment> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-052",
                "OPEN_APPOINTMENT",
                BoundDelegatedCalls.Mode.MERCHANT,
                request.request().appointmentNo(),
                () -> {
                    return appointmentService.openWorkOrderFromAppointment(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().appointmentNo(),
                            request.request().req(),
                            request.request().idempotencyKey());
                });
    }

    /** The fixed route chooses OPEN_WORK_ORDER; the caller cannot dispatch another action. */
    @PostMapping("/internal/beauty/reception/openWorkOrder")
    public ResponseEntity<?> openWorkOrder(
            @RequestBody InternalCall<BeautyReceptionInternalApi.OpenWorkOrder> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-054",
                "OPEN_WORK_ORDER",
                BoundDelegatedCalls.Mode.MERCHANT,
                request.request().req().appointmentNo(),
                () -> {
                    if (!request.binding().storeNo().equals(request.request().req().storeNo())) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    return workOrderService.openWorkOrder(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().req(),
                            request.request().idempotencyKey());
                });
    }

    /** The fixed route chooses GET_WORK_ORDER; the caller cannot dispatch another action. */
    @PostMapping("/internal/beauty/reception/getWorkOrder")
    public ResponseEntity<?> getWorkOrder(
            @RequestBody InternalCall<BeautyReceptionInternalApi.WorkOrderRef> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-055",
                "GET_WORK_ORDER",
                BoundDelegatedCalls.Mode.MERCHANT,
                request.request().workOrderNo(),
                () -> {
                    return workOrderService.getWorkOrder(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().workOrderNo());
                });
    }

    /** The fixed route chooses GET_MY_WORK_ORDER; the caller cannot dispatch another action. */
    @PostMapping("/internal/beauty/reception/getMyWorkOrder")
    public ResponseEntity<?> getMyWorkOrder(
            @RequestBody InternalCall<BeautyReceptionInternalApi.WorkOrderRef> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-155",
                "GET_MY_WORK_ORDER",
                BoundDelegatedCalls.Mode.CONSUMER,
                request.request().workOrderNo(),
                () -> {
                    return workOrderService.getMyWorkOrder(
                            request.binding().identity().actorNo(),
                            request.request().workOrderNo());
                });
    }

    /** The fixed route chooses SUBMIT_SERVICES; the caller cannot dispatch another action. */
    @PostMapping("/internal/beauty/reception/submitServices")
    public ResponseEntity<?> submitServices(
            @RequestBody InternalCall<BeautyReceptionInternalApi.SubmitServices> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-056",
                "SUBMIT_SERVICES",
                BoundDelegatedCalls.Mode.MERCHANT,
                request.request().workOrderNo(),
                () -> {
                    return workOrderService.submitServices(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().workOrderNo(),
                            request.request().req(),
                            request.request().idempotencyKey());
                });
    }

    /** The fixed route chooses GET_BILL; the caller cannot dispatch another action. */
    @PostMapping("/internal/beauty/reception/getBill")
    public ResponseEntity<?> getBill(
            @RequestBody InternalCall<BeautyReceptionInternalApi.WorkOrderRef> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-071",
                "GET_BILL",
                BoundDelegatedCalls.Mode.MERCHANT,
                request.request().workOrderNo(),
                () -> {
                    return workOrderService.getBill(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().workOrderNo());
                });
    }

    /** Pure JSON decoding can precede authentication; malformed data never reaches an owner. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformed() {
        return ResponseEntity.badRequest().build();
    }
}

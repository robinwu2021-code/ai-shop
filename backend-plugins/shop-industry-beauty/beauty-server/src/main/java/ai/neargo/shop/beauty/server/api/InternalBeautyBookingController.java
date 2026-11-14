package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.beauty.service.AppointmentAvailabilityService;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import ai.neargo.shop.beauty.spi.BeautyBookingInternalApi;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Machine token is checked before delegated actor restoration and live service authorization. */
@RestController
@ConditionalOnBeautyEnabled
public class InternalBeautyBookingController {
    private final String token;
    private final AppointmentAvailabilityService appointmentAvailabilityService;
    private final AppointmentService appointmentService;
    private final ServiceOperationQueryService serviceOperationQueryService;
    private final ServiceOperationRecoveryService serviceOperationRecoveryService;
    public InternalBeautyBookingController(AppointmentAvailabilityService appointmentAvailabilityService,
            AppointmentService appointmentService, ServiceOperationQueryService serviceOperationQueryService,
            ServiceOperationRecoveryService serviceOperationRecoveryService,
            @Value("${shop.services.internal-token:}") String token) {
        this.token = token;
        this.appointmentAvailabilityService = appointmentAvailabilityService;
        this.appointmentService = appointmentService;
        this.serviceOperationQueryService = serviceOperationQueryService;
        this.serviceOperationRecoveryService = serviceOperationRecoveryService;
    }
    @PostMapping("/internal/beauty/booking/queryAvailability")
    public ResponseEntity<?> queryAvailability(@RequestBody BeautyBookingInternalApi.QueryAvailability request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.actorNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentAvailabilityService.queryAvailability(request.entityNo(),
                    request.currentStoreNo(), request.actorNo(), request.query());
        }));
    }
    @PostMapping("/internal/beauty/booking/queryPublicAvailability")
    public ResponseEntity<?> queryPublicAvailability(@RequestBody
            BeautyBookingInternalApi.QueryPublicAvailability request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> appointmentAvailabilityService.queryPublicAvailability(request.query()));
    }
    @PostMapping("/internal/beauty/booking/previewAppointment")
    public ResponseEntity<?> previewAppointment(@RequestBody BeautyBookingInternalApi.PreviewAppointment request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.actorNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentService.previewAppointment(request.entityNo(), request.currentStoreNo(),
                    request.actorNo(), request.req());
        }));
    }
    @PostMapping("/internal/beauty/booking/createAppointment")
    public ResponseEntity<?> createAppointment(@RequestBody BeautyBookingInternalApi.CreateAppointment request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.actorNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentService.createAppointment(request.entityNo(), request.currentStoreNo(),
                    request.actorNo(), request.req(), request.idempotencyKey());
        }));
    }
    @PostMapping("/internal/beauty/booking/listAppointments")
    public ResponseEntity<?> listAppointments(@RequestBody BeautyBookingInternalApi.ListAppointments request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.actorNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentService.listAppointments(request.entityNo(), request.currentStoreNo(),
                    request.actorNo(), request.query());
        }));
    }
    @PostMapping("/internal/beauty/booking/getAppointment")
    public ResponseEntity<?> getAppointment(@RequestBody BeautyBookingInternalApi.GetAppointment request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.actorNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentService.getAppointment(request.entityNo(), request.currentStoreNo(),
                    request.actorNo(), request.appointmentNo());
        }));
    }
    @PostMapping("/internal/beauty/booking/previewAppointmentChange")
    public ResponseEntity<?> previewAppointmentChange(@RequestBody
            BeautyBookingInternalApi.PreviewAppointmentChange request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.actorNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentService.previewAppointmentChange(request.entityNo(), request.currentStoreNo(),
                    request.actorNo(), request.appointmentNo(), request.req());
        }));
    }
    @PostMapping("/internal/beauty/booking/changeAppointment")
    public ResponseEntity<?> changeAppointment(@RequestBody BeautyBookingInternalApi.ChangeAppointment request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.actorNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentService.changeAppointment(request.entityNo(), request.currentStoreNo(),
                    request.actorNo(), request.appointmentNo(), request.req(), request.idempotencyKey());
        }));
    }
    @PostMapping("/internal/beauty/booking/cancelAppointment")
    public ResponseEntity<?> cancelAppointment(@RequestBody BeautyBookingInternalApi.CancelAppointment request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.actorNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentService.cancelAppointment(request.entityNo(), request.currentStoreNo(),
                    request.actorNo(), request.appointmentNo(), request.req(), request.idempotencyKey());
        }));
    }
    @PostMapping("/internal/beauty/booking/recordNoShow")
    public ResponseEntity<?> recordNoShow(@RequestBody BeautyBookingInternalApi.RecordNoShow request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.actorNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentService.recordNoShow(request.entityNo(), request.currentStoreNo(),
                    request.actorNo(), request.appointmentNo(), request.req(), request.idempotencyKey());
        }));
    }
    @PostMapping("/internal/beauty/booking/previewMyAppointment")
    public ResponseEntity<?> previewMyAppointment(@RequestBody BeautyBookingInternalApi.PreviewMyAppointment request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.userNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentService.previewMyAppointment(request.userNo(), request.req());
        }));
    }
    @PostMapping("/internal/beauty/booking/createMyAppointment")
    public ResponseEntity<?> createMyAppointment(@RequestBody BeautyBookingInternalApi.CreateMyAppointment request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.userNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentService.createMyAppointment(request.userNo(), request.req(), request.idempotencyKey());
        }));
    }
    @PostMapping("/internal/beauty/booking/listMyAppointments")
    public ResponseEntity<?> listMyAppointments(@RequestBody BeautyBookingInternalApi.ListMyAppointments request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.userNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentService.listMyAppointments(request.userNo(), request.query());
        }));
    }
    @PostMapping("/internal/beauty/booking/getMyAppointment")
    public ResponseEntity<?> getMyAppointment(@RequestBody BeautyBookingInternalApi.GetMyAppointment request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.userNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentService.getMyAppointment(request.userNo(), request.appointmentNo());
        }));
    }
    @PostMapping("/internal/beauty/booking/previewMyAppointmentChange")
    public ResponseEntity<?> previewMyAppointmentChange(@RequestBody
            BeautyBookingInternalApi.PreviewMyAppointmentChange request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.userNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentService.previewMyAppointmentChange(request.userNo(), request.appointmentNo(),
                    request.req());
        }));
    }
    @PostMapping("/internal/beauty/booking/changeMyAppointment")
    public ResponseEntity<?> changeMyAppointment(@RequestBody BeautyBookingInternalApi.ChangeMyAppointment request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.userNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentService.changeMyAppointment(request.userNo(), request.appointmentNo(),
                    request.req(), request.idempotencyKey());
        }));
    }
    @PostMapping("/internal/beauty/booking/cancelMyAppointment")
    public ResponseEntity<?> cancelMyAppointment(@RequestBody BeautyBookingInternalApi.CancelMyAppointment request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.userNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return appointmentService.cancelMyAppointment(request.userNo(), request.appointmentNo(),
                    request.req(), request.idempotencyKey());
        }));
    }
    @PostMapping("/internal/beauty/booking/getOperation")
    public ResponseEntity<?> getOperation(@RequestBody BeautyBookingInternalApi.GetOperation request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.actorNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return serviceOperationQueryService.getOperation(request.entityNo(), request.currentStoreNo(),
                    request.actorNo(), request.operationNo());
        }));
    }
    @PostMapping("/internal/beauty/booking/recoverOperation")
    public ResponseEntity<?> recoverOperation(@RequestBody BeautyBookingInternalApi.RecoverOperation request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> DelegatedIdentityContext.call(request.identity(), () -> {
            if (!request.identity().actorNo().equals(request.actorNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return serviceOperationRecoveryService.recoverOperation(request.entityNo(),
                    request.currentStoreNo(), request.actorNo(), request.operationNo());
        }));
    }
    private ResponseEntity<?> invoke(String given, Supplier<?> operation) {
        try { return InternalServiceResponses.invoke(token, given, operation); }
        catch (RuntimeException unavailable) { return ResponseEntity.status(503).build(); }
    }
    /** Internal decode failures use real status codes even when the host has public HTTP200 error advice. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformedRequest(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest().build();
    }
}

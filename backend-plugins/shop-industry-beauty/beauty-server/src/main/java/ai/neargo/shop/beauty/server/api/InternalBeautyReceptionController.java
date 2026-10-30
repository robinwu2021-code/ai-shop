package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.beauty.service.ReceptionService;
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
public class InternalBeautyReceptionController {
    private final BoundDelegatedCalls calls;
    private final ReceptionService receptionService;

    /** Production dependencies are mandatory; there is no empty provider fallback. */
    public InternalBeautyReceptionController(
            ReceptionService receptionService,
            @Value("${shop.services.internal-token:}") String token) {
        calls = new BoundDelegatedCalls(token);
        this.receptionService = receptionService;
    }

    /** The fixed route chooses CHECK_IN; the caller cannot dispatch another action. */
    @PostMapping("/internal/beauty/reception/checkIn")
    public ResponseEntity<?> checkIn(
            @RequestBody InternalCall<BeautyReceptionInternalApi.CheckIn> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-047",
                "CHECK_IN",
                BoundDelegatedCalls.Mode.MERCHANT,
                request.request().req().appointmentNo(),
                () -> {
                    return receptionService.checkIn(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().req(),
                            request.request().idempotencyKey());
                });
    }

    /** The fixed route chooses RECEIVE_WALK_IN; the caller cannot dispatch another action. */
    @PostMapping("/internal/beauty/reception/receiveWalkIn")
    public ResponseEntity<?> receiveWalkIn(
            @RequestBody InternalCall<BeautyReceptionInternalApi.ReceiveWalkIn> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-048",
                "RECEIVE_WALK_IN",
                BoundDelegatedCalls.Mode.MERCHANT,
                null,
                () -> {
                    if (!request.binding().storeNo().equals(request.request().req().storeNo())) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    return receptionService.receiveWalkIn(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().req(),
                            request.request().idempotencyKey());
                });
    }

    /** The fixed route chooses COORDINATE_EXCEPTION; the caller cannot dispatch another action. */
    @PostMapping("/internal/beauty/reception/coordinateReceptionException")
    public ResponseEntity<?> coordinateReceptionException(
            @RequestBody InternalCall<BeautyReceptionInternalApi.CoordinateException> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-053",
                "COORDINATE_EXCEPTION",
                BoundDelegatedCalls.Mode.MERCHANT,
                request.request().req().appointmentNo(),
                () -> {
                    return receptionService.coordinateReceptionException(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().req(),
                            request.request().idempotencyKey());
                });
    }

    /**
     * The fixed route chooses ISSUE_ARRIVAL_CHALLENGE; the caller cannot dispatch another action.
     */
    @PostMapping("/internal/beauty/reception/issueArrivalChallenge")
    public ResponseEntity<?> issueArrivalChallenge(
            @RequestBody InternalCall<BeautyReceptionInternalApi.IssueArrivalChallenge> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BM04-ARRIVAL-CHALLENGE",
                "ISSUE_ARRIVAL_CHALLENGE",
                BoundDelegatedCalls.Mode.MERCHANT,
                null,
                () -> {
                    return receptionService.issueArrivalChallenge(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().idempotencyKey());
                });
    }

    /**
     * The fixed route chooses CHECK_IN_MY_APPOINTMENT; the caller cannot dispatch another action.
     */
    @PostMapping("/internal/beauty/reception/checkInMyAppointment")
    public ResponseEntity<?> checkInMyAppointment(
            @RequestBody InternalCall<BeautyReceptionInternalApi.CheckInMine> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-154",
                "CHECK_IN_MY_APPOINTMENT",
                BoundDelegatedCalls.Mode.CONSUMER,
                request.request().req().appointmentNo(),
                () -> {
                    return receptionService.checkInMyAppointment(
                            request.binding().identity().actorNo(),
                            request.request().req(),
                            request.request().idempotencyKey());
                });
    }

    /** Pure JSON decoding can precede authentication; malformed data never reaches an owner. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformed() {
        return ResponseEntity.badRequest().build();
    }
}

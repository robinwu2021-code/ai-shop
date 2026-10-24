package ai.neargo.shop.beauty.spi;

import ai.neargo.shop.beauty.dto.ArrivalChallengeVO;
import ai.neargo.shop.beauty.dto.ArrivalVO;
import ai.neargo.shop.beauty.dto.BusinessTraceVO;
import ai.neargo.shop.beauty.dto.CheckInReq;
import ai.neargo.shop.beauty.dto.ClaimGuestReq;
import ai.neargo.shop.beauty.dto.GuestClaimVO;
import ai.neargo.shop.beauty.dto.GuestRegistrationVO;
import ai.neargo.shop.beauty.dto.MyArrivalVO;
import ai.neargo.shop.beauty.dto.MyCheckInReq;
import ai.neargo.shop.beauty.dto.MyWorkOrderVO;
import ai.neargo.shop.beauty.dto.OpenAppointmentWorkOrderReq;
import ai.neargo.shop.beauty.dto.OpenWorkOrderReq;
import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.dto.ReceiveWalkInReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionVO;
import ai.neargo.shop.beauty.dto.RecoverSubmissionReq;
import ai.neargo.shop.beauty.dto.RecoveryRequestVO;
import ai.neargo.shop.beauty.dto.RegisterGuestReq;
import ai.neargo.shop.beauty.dto.ServiceSubmissionVO;
import ai.neargo.shop.beauty.dto.SubmitServicesReq;
import ai.neargo.shop.beauty.dto.WalkInVO;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;
import ai.neargo.shop.beauty.dto.WorkOrderDetailVO;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Exact typed reception routes bind the current identity without transporting consumer sessions.
 */
public interface BeautyReceptionInternalApi {
    /**
     * Uses the existing GuestRegistrationService owner; binding never grants business permission.
     */
    @PostExchange("/internal/beauty/reception/registerGuest")
    InternalReply<GuestRegistrationVO> registerGuest(
            @RequestBody InternalCall<RegisterGuest> request);

    /** Private request fields stay on the transient wire and are redacted from diagnostics. */
    record RegisterGuest(
            @JsonProperty(required = true) RegisterGuestReq req,
            @JsonProperty(required = true) String idempotencyKey) {
        /** Reject malformed keys and references before any owner operation can run. */
        public RegisterGuest {
            if (req == null) {
                throw invalid();
            }
            bounded(idempotencyKey, 128);
        }

        /** Unknown identity or payment assertions must never be silently discarded. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "RegisterGuest[REDACTED]";
        }
    }

    /**
     * Uses the existing GuestRegistrationService owner; binding never grants business permission.
     */
    @PostExchange("/internal/beauty/reception/claimGuest")
    InternalReply<GuestClaimVO> claimGuest(@RequestBody InternalCall<ClaimGuest> request);

    /** Private request fields stay on the transient wire and are redacted from diagnostics. */
    record ClaimGuest(
            @JsonProperty(required = true) String guestNo,
            @JsonProperty(required = true) ClaimGuestReq req,
            @JsonProperty(required = true) String idempotencyKey) {
        /** Reject malformed keys and references before any owner operation can run. */
        public ClaimGuest {
            bounded(guestNo, 64);
            if (req == null) {
                throw invalid();
            }
            bounded(idempotencyKey, 128);
        }

        /** Unknown identity or payment assertions must never be silently discarded. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "ClaimGuest[REDACTED]";
        }
    }

    /** Uses the existing ReceptionService owner; binding never grants business permission. */
    @PostExchange("/internal/beauty/reception/checkIn")
    InternalReply<ArrivalVO> checkIn(@RequestBody InternalCall<CheckIn> request);

    /** Private request fields stay on the transient wire and are redacted from diagnostics. */
    record CheckIn(
            @JsonProperty(required = true) CheckInReq req,
            @JsonProperty(required = true) String idempotencyKey) {
        /** Reject malformed keys and references before any owner operation can run. */
        public CheckIn {
            if (req == null) {
                throw invalid();
            }
            bounded(idempotencyKey, 128);
        }

        /** Unknown identity or payment assertions must never be silently discarded. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "CheckIn[REDACTED]";
        }
    }

    /** Uses the existing ReceptionService owner; binding never grants business permission. */
    @PostExchange("/internal/beauty/reception/receiveWalkIn")
    InternalReply<WalkInVO> receiveWalkIn(@RequestBody InternalCall<ReceiveWalkIn> request);

    /** Private request fields stay on the transient wire and are redacted from diagnostics. */
    record ReceiveWalkIn(
            @JsonProperty(required = true) ReceiveWalkInReq req,
            @JsonProperty(required = true) String idempotencyKey) {
        /** Reject malformed keys and references before any owner operation can run. */
        public ReceiveWalkIn {
            if (req == null) {
                throw invalid();
            }
            bounded(idempotencyKey, 128);
        }

        /** Unknown identity or payment assertions must never be silently discarded. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "ReceiveWalkIn[REDACTED]";
        }
    }

    /** Uses the existing ReceptionService owner; binding never grants business permission. */
    @PostExchange("/internal/beauty/reception/coordinateReceptionException")
    InternalReply<ReceptionExceptionVO> coordinateReceptionException(
            @RequestBody InternalCall<CoordinateException> request);

    /** Private request fields stay on the transient wire and are redacted from diagnostics. */
    record CoordinateException(
            @JsonProperty(required = true) ReceptionExceptionReq req,
            @JsonProperty(required = true) String idempotencyKey) {
        /** Reject malformed keys and references before any owner operation can run. */
        public CoordinateException {
            if (req == null) {
                throw invalid();
            }
            bounded(idempotencyKey, 128);
        }

        /** Unknown identity or payment assertions must never be silently discarded. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "CoordinateException[REDACTED]";
        }
    }

    /** Uses the existing ReceptionService owner; binding never grants business permission. */
    @PostExchange("/internal/beauty/reception/issueArrivalChallenge")
    InternalReply<ArrivalChallengeVO> issueArrivalChallenge(
            @RequestBody InternalCall<IssueArrivalChallenge> request);

    /** Private request fields stay on the transient wire and are redacted from diagnostics. */
    record IssueArrivalChallenge(@JsonProperty(required = true) String idempotencyKey) {
        /** Reject malformed keys and references before any owner operation can run. */
        public IssueArrivalChallenge {
            bounded(idempotencyKey, 128);
        }

        /** Unknown identity or payment assertions must never be silently discarded. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "IssueArrivalChallenge[REDACTED]";
        }
    }

    /** Uses the existing AppointmentService owner; binding never grants business permission. */
    @PostExchange("/internal/beauty/reception/openWorkOrderFromAppointment")
    InternalReply<WorkOrderVO> openWorkOrderFromAppointment(
            @RequestBody InternalCall<OpenAppointment> request);

    /** Private request fields stay on the transient wire and are redacted from diagnostics. */
    record OpenAppointment(
            @JsonProperty(required = true) String appointmentNo,
            @JsonProperty(required = true) OpenAppointmentWorkOrderReq req,
            @JsonProperty(required = true) String idempotencyKey) {
        /** Reject malformed keys and references before any owner operation can run. */
        public OpenAppointment {
            bounded(appointmentNo, 64);
            if (req == null) {
                throw invalid();
            }
            bounded(idempotencyKey, 128);
        }

        /** Unknown identity or payment assertions must never be silently discarded. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "OpenAppointment[REDACTED]";
        }
    }

    /** Uses the existing WorkOrderService owner; binding never grants business permission. */
    @PostExchange("/internal/beauty/reception/openWorkOrder")
    InternalReply<WorkOrderVO> openWorkOrder(@RequestBody InternalCall<OpenWorkOrder> request);

    /** Private request fields stay on the transient wire and are redacted from diagnostics. */
    record OpenWorkOrder(
            @JsonProperty(required = true) OpenWorkOrderReq req,
            @JsonProperty(required = true) String idempotencyKey) {
        /** Reject malformed keys and references before any owner operation can run. */
        public OpenWorkOrder {
            if (req == null) {
                throw invalid();
            }
            bounded(idempotencyKey, 128);
        }

        /** Unknown identity or payment assertions must never be silently discarded. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "OpenWorkOrder[REDACTED]";
        }
    }

    /** Uses the existing WorkOrderService owner; binding never grants business permission. */
    @PostExchange("/internal/beauty/reception/getWorkOrder")
    InternalReply<WorkOrderDetailVO> getWorkOrder(@RequestBody InternalCall<WorkOrderRef> request);

    /** Private request fields stay on the transient wire and are redacted from diagnostics. */
    record WorkOrderRef(@JsonProperty(required = true) String workOrderNo) {
        /** Reject malformed keys and references before any owner operation can run. */
        public WorkOrderRef {
            bounded(workOrderNo, 64);
        }

        /** Unknown identity or payment assertions must never be silently discarded. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "WorkOrderRef[REDACTED]";
        }
    }

    /** Uses the existing WorkOrderService owner; binding never grants business permission. */
    @PostExchange("/internal/beauty/reception/getMyWorkOrder")
    InternalReply<MyWorkOrderVO> getMyWorkOrder(@RequestBody InternalCall<WorkOrderRef> request);

    /** Uses the existing ReceptionService owner; binding never grants business permission. */
    @PostExchange("/internal/beauty/reception/checkInMyAppointment")
    InternalReply<MyArrivalVO> checkInMyAppointment(@RequestBody InternalCall<CheckInMine> request);

    /** Private request fields stay on the transient wire and are redacted from diagnostics. */
    record CheckInMine(
            @JsonProperty(required = true) MyCheckInReq req,
            @JsonProperty(required = true) String idempotencyKey) {
        /** Reject malformed keys and references before any owner operation can run. */
        public CheckInMine {
            if (req == null) {
                throw invalid();
            }
            bounded(idempotencyKey, 128);
        }

        /** Unknown identity or payment assertions must never be silently discarded. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "CheckInMine[REDACTED]";
        }
    }

    /** Uses the existing WorkOrderService owner; binding never grants business permission. */
    @PostExchange("/internal/beauty/reception/submitServices")
    InternalReply<ServiceSubmissionVO> submitServices(
            @RequestBody InternalCall<SubmitServices> request);

    /** Private request fields stay on the transient wire and are redacted from diagnostics. */
    record SubmitServices(
            @JsonProperty(required = true) String workOrderNo,
            @JsonProperty(required = true) SubmitServicesReq req,
            @JsonProperty(required = true) String idempotencyKey) {
        /** Reject malformed keys and references before any owner operation can run. */
        public SubmitServices {
            bounded(workOrderNo, 64);
            if (req == null) {
                throw invalid();
            }
            bounded(idempotencyKey, 128);
        }

        /** Unknown identity or payment assertions must never be silently discarded. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "SubmitServices[REDACTED]";
        }
    }

    /** Uses the existing WorkOrderService owner; binding never grants business permission. */
    @PostExchange("/internal/beauty/reception/getBill")
    InternalReply<WorkOrderBillVO> getBill(@RequestBody InternalCall<WorkOrderRef> request);

    /**
     * Uses the existing ServiceSubmissionService owner; binding never grants business permission.
     */
    @PostExchange("/internal/beauty/reception/getSubmission")
    InternalReply<ServiceSubmissionVO> getSubmission(
            @RequestBody InternalCall<SubmissionRef> request);

    /** Private request fields stay on the transient wire and are redacted from diagnostics. */
    record SubmissionRef(@JsonProperty(required = true) String submissionNo) {
        /** Reject malformed keys and references before any owner operation can run. */
        public SubmissionRef {
            bounded(submissionNo, 64);
        }

        /** Unknown identity or payment assertions must never be silently discarded. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "SubmissionRef[REDACTED]";
        }
    }

    /**
     * Uses the existing ServiceSubmissionService owner; binding never grants business permission.
     */
    @PostExchange("/internal/beauty/reception/requestSubmissionRecovery")
    InternalReply<RecoveryRequestVO> requestSubmissionRecovery(
            @RequestBody InternalCall<RequestSubmissionRecovery> request);

    /** Private request fields stay on the transient wire and are redacted from diagnostics. */
    record RequestSubmissionRecovery(
            @JsonProperty(required = true) String submissionNo,
            @JsonProperty(required = true) RecoverSubmissionReq req,
            @JsonProperty(required = true) String idempotencyKey) {
        /** Reject malformed keys and references before any owner operation can run. */
        public RequestSubmissionRecovery {
            bounded(submissionNo, 64);
            if (req == null) {
                throw invalid();
            }
            bounded(idempotencyKey, 128);
        }

        /** Unknown identity or payment assertions must never be silently discarded. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "RequestSubmissionRecovery[REDACTED]";
        }
    }

    /**
     * Uses the existing ServiceSubmissionService owner; binding never grants business permission.
     */
    @PostExchange("/internal/beauty/reception/recoverPending")
    InternalReply<ServiceSubmissionVO> recoverPending(
            @RequestBody InternalCall<SubmissionRef> request);

    /** Uses the existing WorkOrderBillService owner; binding never grants business permission. */
    @PostExchange("/internal/beauty/reception/getTrace")
    InternalReply<BusinessTraceVO> getTrace(@RequestBody InternalCall<BusinessRef> request);

    /** Private request fields stay on the transient wire and are redacted from diagnostics. */
    record BusinessRef(@JsonProperty(required = true) String businessNo) {
        /** Reject malformed keys and references before any owner operation can run. */
        public BusinessRef {
            bounded(businessNo, 64);
        }

        /** Unknown identity or payment assertions must never be silently discarded. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "BusinessRef[REDACTED]";
        }
    }

    /**
     * Uses the existing ServiceOperationQueryService owner; binding never grants business
     * permission.
     */
    @PostExchange("/internal/beauty/reception/getOperation")
    InternalReply<OperationVO> getOperation(@RequestBody InternalCall<OperationRef> request);

    /** Private request fields stay on the transient wire and are redacted from diagnostics. */
    record OperationRef(@JsonProperty(required = true) String operationNo) {
        /** Reject malformed keys and references before any owner operation can run. */
        public OperationRef {
            bounded(operationNo, 64);
        }

        /** Unknown identity or payment assertions must never be silently discarded. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "OperationRef[REDACTED]";
        }
    }

    /**
     * Uses the existing ServiceOperationRecoveryService owner; binding never grants business
     * permission.
     */
    @PostExchange("/internal/beauty/reception/recoverOperation")
    InternalReply<OperationVO> recoverOperation(@RequestBody InternalCall<OperationRef> request);

    private static void bounded(String value, int limit) {
        if (value == null
                || value.isBlank()
                || value.length() > limit
                || !value.equals(value.strip())) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid reception call");
    }
}

package ai.neargo.shop.beauty.client;

import ai.neargo.shop.beauty.dto.ArrivalChallengeVO;
import ai.neargo.shop.beauty.dto.ArrivalVO;
import ai.neargo.shop.beauty.dto.BusinessTraceVO;
import ai.neargo.shop.beauty.dto.GuestClaimVO;
import ai.neargo.shop.beauty.dto.GuestRegistrationVO;
import ai.neargo.shop.beauty.dto.MyArrivalVO;
import ai.neargo.shop.beauty.dto.MyWorkOrderVO;
import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.dto.ReceptionExceptionVO;
import ai.neargo.shop.beauty.dto.RecoveryRequestVO;
import ai.neargo.shop.beauty.dto.ServiceSubmissionVO;
import ai.neargo.shop.beauty.dto.SourceObservationVO;
import ai.neargo.shop.beauty.dto.WalkInVO;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;
import ai.neargo.shop.beauty.dto.WorkOrderDetailVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskVO;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.spi.security.InternalCall;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Typed binding does not excuse missing or mismatched owner business identities. */
final class BeautyReceptionResponses {
    private BeautyReceptionResponses() {}

    static <T> T require(InternalCall<?> call, T result) {
        check(call != null && result != null);
        String target = call.binding().targetNo();
        switch (result) {
            case GuestRegistrationVO value -> registration(value);
            case GuestClaimVO value -> {
                same(target, value.guestNo());
                text(value.operationNo());
                if ("SUCCEEDED".equals(value.operationState())) {
                    text(value.userNo());
                    check(value.claimRevision() != null && value.claimRevision() > 0);
                } else {
                    check(
                            "PENDING".equals(value.operationState())
                                    && value.claimRevision() == null);
                    text(value.recoveryAction());
                }
            }
            case ArrivalChallengeVO value -> {
                same(call.binding().storeNo(), value.storeNo());
                text(value.challengeNo());
                check(value.code() != null && value.code().matches("[A-Za-z0-9_-]{43}"));
                check(
                        value.issuedAt() != null
                                && value.expiresAt() != null
                                && Duration.between(value.issuedAt(), value.expiresAt())
                                        .equals(Duration.ofSeconds(90)));
            }
            case ArrivalVO value -> {
                same(target, value.appointmentNo());
                check(value.revision() >= 0 && value.status() != null && value.timing() != null);
                check(!"CHECKED_IN".equals(value.status()) || value.checkedInAt() != null);
            }
            case MyArrivalVO value -> {
                same(target, value.appointmentNo());
                check(value.revision() >= 0 && value.status() != null && value.timing() != null);
                check(!"CHECKED_IN".equals(value.status()) || value.checkedInAt() != null);
            }
            case WalkInVO value -> {
                text(value.operationNo());
                if ("SUCCEEDED".equals(value.operationState())) {
                    text(value.appointmentNo());
                    workOrder(value.workOrder());
                    same(value.appointmentNo(), value.workOrder().appointmentNo());
                } else {
                    check(Set.of("PENDING", "REJECTED").contains(value.operationState()));
                    check(value.workOrder() == null);
                }
            }
            case ReceptionExceptionVO value -> {
                same(target, value.appointmentNo());
                text(value.requestNo());
                text(value.operationNo());
                check("PENDING_REVIEW".equals(value.state()) && value.affectedTaskNos() != null);
            }
            case WorkOrderVO value -> {
                workOrder(value);
                if (call.binding().action().startsWith("OPEN_")) {
                    check(Objects.equals(target, value.appointmentNo()));
                }
            }
            case WorkOrderDetailVO value -> {
                workOrder(value.workOrder());
                same(target, value.workOrder().workOrderNo());
                check(value.customer() != null && value.originalScopeRevision() > 0);
                tasks(value.workOrder(), value.tasks());
            }
            case MyWorkOrderVO value -> {
                workOrder(value.workOrder());
                same(target, value.workOrder().workOrderNo());
                check(value.originalScopeRevision() > 0);
                tasks(value.workOrder(), value.tasks());
            }
            case ServiceSubmissionVO value -> {
                submission(value);
                if ("SUBMIT_SERVICES".equals(call.binding().action())) {
                    same(target, value.workOrderNo());
                } else {
                    same(target, value.submissionNo());
                }
            }
            case WorkOrderBillVO value -> {
                same(target, value.workOrderNo());
                bill(value);
            }
            case BusinessTraceVO value -> {
                same(target, value.businessNo());
                text(value.workOrderNo());
                check(
                        value.scopeRevision() > 0
                                && value.submissions() != null
                                && value.bill() != null);
                same(value.workOrderNo(), value.bill().workOrderNo());
                bill(value.bill());
                value.submissions()
                        .forEach(
                                item -> {
                                    same(value.workOrderNo(), item.workOrderNo());
                                    submission(item);
                                });
            }
            case RecoveryRequestVO value -> {
                same(target, value.submissionNo());
                text(value.recoveryNo());
                text(value.operationNo());
                text(value.state());
                check(value.acceptedRevision() >= 0);
            }
            case OperationVO value -> {
                same(target, value.operationNo());
                text(value.state());
                check(
                        Set.of(
                                        "CREATE",
                                        "CHANGE",
                                        "CANCEL",
                                        "NO_SHOW",
                                        "RECEPTION_CHANGE",
                                        "CLAIM_GUEST",
                                        "CHECK_IN",
                                        "RECEIVE_WALK_IN",
                                        "OPEN_WORK_ORDER",
                                        "RECEPTION_EXCEPTION",
                                        "SUBMIT_SOURCE",
                                        "ADD_SOURCE")
                                .contains(value.action()));
                if (Set.of("SUBMIT_SOURCE", "ADD_SOURCE").contains(value.action())) {
                    text(value.workOrderNo());
                }
            }
            default -> throw invalid();
        }
        return result;
    }

    private static void registration(GuestRegistrationVO value) {
        if ("SUCCEEDED".equals(value.operationState())) {
            text(value.guestNo());
            check("REGISTERED".equals(value.state()) && Long.valueOf(1).equals(value.revision()));
            check(value.claimToken() != null && value.claimToken().matches("[A-Za-z0-9_-]{43}"));
        } else {
            check(
                    "PENDING".equals(value.operationState())
                            && value.operationNo() == null
                            && value.guestNo() == null
                            && value.claimToken() == null
                            && value.state() == null
                            && value.revision() == null
                            && "REQUIRES_ORIGINAL_ACTOR".equals(value.recoveryAction()));
        }
    }

    private static void workOrder(WorkOrderVO value) {
        check(value != null && value.taskNos() != null);
        text(value.status());
        if (value.workOrderNo() == null) {
            check(
                    "PENDING".equals(value.status())
                            && value.scopeRevision() == 0
                            && value.taskNos().isEmpty());
            text(value.operationNo());
        } else {
            text(value.workOrderNo());
            check(value.scopeRevision() > 0 && !value.taskNos().isEmpty());
            check(value.taskNos().stream().distinct().count() == value.taskNos().size());
            value.taskNos().forEach(BeautyReceptionResponses::text);
        }
    }

    private static void tasks(WorkOrderVO root, List<WorkOrderTaskVO> values) {
        check(
                values != null
                        && values.stream().map(WorkOrderTaskVO::taskNo).distinct().count()
                                == values.size());
        check(root.taskNos().equals(values.stream().map(WorkOrderTaskVO::taskNo).toList()));
        values.forEach(
                value -> {
                    text(value.taskNo());
                    text(value.skuNo());
                    check(
                            !value.canStart()
                                    && value.scopeRevision() > 0
                                    && value.bindingRevision() >= 0
                                    && value.qty() != null
                                    && value.qty().signum() > 0
                                    && value.taskSnapshot() != null);
                });
    }

    private static void submission(ServiceSubmissionVO value) {
        text(value.submissionNo());
        text(value.workOrderNo());
        check(Set.of("SUBMIT_SOURCE", "ADD_SOURCE").contains(value.action()));
        check(
                value.revision() >= 0
                        && value.originalScopeRevision() > 0
                        && value.targetScopeRevision() > 0);
        text(value.status());
        text(value.phase());
        if (value.source() != null) {
            observation(value.source());
        }
        if (value.quote() != null) {
            check(value.quote().operation() != null);
            same(value.checkoutOperationNo(), value.quote().operation().operationNo());
            check(value.quote().expiresAt() != null && value.quote().quoteRevision() > 0);
        }
    }

    private static void bill(WorkOrderBillVO value) {
        check(
                value != null
                        && value.scopeRevision() > 0
                        && value.observedAt() != null
                        && value.sources() != null
                        && value.currencies() != null
                        && value.blockingReasons() != null);
        text(value.workOrderNo());
        text(value.localWatermark());
        value.sources()
                .forEach(
                        source -> {
                            text(source.checkoutOperationNo());
                            text(source.amountScope());
                            check(
                                    source.receipts() != null
                                            && source.tasks() != null
                                            && source.blockingReasons() != null);
                            if (source.observation() != null) {
                                observation(source.observation());
                            }
                        });
    }

    private static void observation(SourceObservationVO value) {
        text(value.state());
        check(!value.canStart() && value.observationEpoch() >= 0);
        check((value.sourceRevision() == null) == (value.sourceHash() == null));
        check((value.lastVerifiedRevision() == null) == (value.lastVerifiedHash() == null));
    }

    private static void same(String expected, String actual) {
        text(expected);
        check(expected.equals(actual));
    }

    private static void text(String value) {
        check(value != null && !value.isBlank());
    }

    private static void check(boolean valid) {
        if (!valid) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid reception reply");
    }
}

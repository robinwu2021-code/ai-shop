package ai.neargo.shop.pay.points.admin;

import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
import ai.neargo.shop.pay.points.model.PoolRequest.Action;
import ai.neargo.shop.pay.points.pool.TopupPlanService.Plan;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;

import java.time.LocalDate;
import java.util.List;

public interface PointsTreasuryService {
    OperatorResult submit(Submission request, String actor);

    OperatorResult review(Review request, String actor);

    RequestPage requests(String typeCode, long after, int limit);

    OperatorResult savePlan(PlanChange request, String actor);

    OperatorResult generatePeriods(GeneratePeriods request, String actor);

    OperatorResult declareReceipt(PlanReceipt request, String actor);

    OperatorResult cancelPeriod(PeriodCancellation request, String actor);

    PlanPage plans(String typeCode, long after, int limit);

    Execution executeWithdrawal(Withdrawal request, String actor);

    record Submission(
            String requestId,
            String requestNo,
            String typeCode,
            Action action,
            long amountMinor,
            String evidenceNo,
            FundsContext fundsContext,
            String reason) {}

    record Review(
            String requestId,
            String typeCode,
            String requestNo,
            long expectedVersion,
            boolean approve,
            String reason) {}

    record RequestView(
            long id,
            String requestNo,
            String typeCode,
            Action action,
            long amountMinor,
            String state,
            String submittedBy,
            String approvedBy,
            long submittedAt,
            long reviewedAt,
            String submittedReason,
            String reviewReason,
            boolean hasEvidence,
            String ticketNo,
            long version) {}

    record RequestPage(List<RequestView> rows, long next, boolean hasMore) {
        public RequestPage {
            rows = List.copyOf(rows);
        }
    }

    record PlanChange(String requestId, Plan plan, long expectedVersion, String reason) {}

    record GeneratePeriods(String requestId, String typeCode, int limit, String reason) {}

    record PlanReceipt(
            String requestId,
            String typeCode,
            String planNo,
            LocalDate period,
            String evidenceNo,
            long expectedVersion,
            String reason) {}

    record PeriodCancellation(
            String requestId,
            String typeCode,
            String planNo,
            LocalDate period,
            long expectedVersion,
            String reason) {}

    record PlanView(
            long id,
            String planNo,
            String period,
            String typeCode,
            Action action,
            long amountMinor,
            LocalDate anchor,
            int everyDays,
            boolean enabled,
            LocalDate nextPeriod,
            String state,
            String requestNo,
            long version) {}

    record PlanPage(List<PlanView> rows, long next, boolean hasMore) {
        public PlanPage {
            rows = List.copyOf(rows);
        }
    }

    record Withdrawal(String requestId, String typeCode, String ticketNo, String reason) {}

    record Execution(String ticketNo, FundsStatus status, long confirmedMinor, boolean replayed) {}
}

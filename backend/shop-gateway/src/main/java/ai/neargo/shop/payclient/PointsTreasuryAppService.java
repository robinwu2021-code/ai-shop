package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService.Execution;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService.GeneratePeriods;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService.PeriodCancellation;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService.PlanChange;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService.PlanPage;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService.PlanReceipt;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService.RequestPage;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService.Review;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService.Submission;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService.Withdrawal;

public interface PointsTreasuryAppService {
    OperatorResult submit(Submission request, String actor);

    OperatorResult review(Review request, String actor);

    RequestPage requests(String typeCode, long after, int limit);

    OperatorResult savePlan(PlanChange request, String actor);

    OperatorResult generatePeriods(GeneratePeriods request, String actor);

    OperatorResult declareReceipt(PlanReceipt request, String actor);

    OperatorResult cancelPeriod(PeriodCancellation request, String actor);

    PlanPage plans(String typeCode, long after, int limit);

    Execution executeWithdrawal(Withdrawal request, String actor);
}

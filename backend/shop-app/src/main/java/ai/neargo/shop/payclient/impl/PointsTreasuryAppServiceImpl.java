package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService;
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
import ai.neargo.shop.payclient.PointsOpsAccess;
import ai.neargo.shop.payclient.PointsOpsErrors;
import ai.neargo.shop.payclient.PointsTreasuryAppService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PointsTreasuryAppServiceImpl implements PointsTreasuryAppService {
    @Autowired(required = false)
    private StoreDirectoryPort stores;

    private final PointsTreasuryService service;

    public PointsTreasuryAppServiceImpl(PointsTreasuryService service) {
        this.service = service;
    }

    @Override
    public OperatorResult submit(Submission request, String actor) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.submit(request, actor));
    }

    @Override
    public OperatorResult review(Review request, String actor) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.review(request, actor));
    }

    @Override
    public RequestPage requests(String typeCode, long after, int limit) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.requests(typeCode, after, limit));
    }

    @Override
    public OperatorResult savePlan(PlanChange request, String actor) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.savePlan(request, actor));
    }

    @Override
    public OperatorResult generatePeriods(GeneratePeriods request, String actor) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.generatePeriods(request, actor));
    }

    @Override
    public OperatorResult declareReceipt(PlanReceipt request, String actor) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.declareReceipt(request, actor));
    }

    @Override
    public OperatorResult cancelPeriod(PeriodCancellation request, String actor) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.cancelPeriod(request, actor));
    }

    @Override
    public PlanPage plans(String typeCode, long after, int limit) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.plans(typeCode, after, limit));
    }

    @Override
    public Execution executeWithdrawal(Withdrawal request, String actor) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.executeWithdrawal(request, actor));
    }
}

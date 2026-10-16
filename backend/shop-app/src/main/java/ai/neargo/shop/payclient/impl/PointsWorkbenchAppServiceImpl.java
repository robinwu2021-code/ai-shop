package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.EventPage;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.EventRetry;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.Execution;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.FundsReference;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.FundsView;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.ReceivableRecovery;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.Recovery;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.Reference;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.RefundView;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.Revision;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.StatementChange;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.StatementDraft;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.StatementPage;
import ai.neargo.shop.payclient.PointsOpsErrors;
import ai.neargo.shop.payclient.PointsWorkbenchAppService;

import org.springframework.stereotype.Service;

@Service
public class PointsWorkbenchAppServiceImpl implements PointsWorkbenchAppService {
    private final PointsWorkbenchService service;

    public PointsWorkbenchAppServiceImpl(PointsWorkbenchService service) {
        this.service = service;
    }

    @Override
    public StatementPage statements(String typeCode, String storeNo, long after, int limit) {
        return PointsOpsErrors.call(() -> service.statements(typeCode, storeNo, after, limit));
    }

    @Override
    public OperatorResult draftStatements(StatementDraft request, String actor) {
        return PointsOpsErrors.call(() -> service.draftStatements(request, actor));
    }

    @Override
    public OperatorResult changeStatement(StatementChange request, String actor) {
        return PointsOpsErrors.call(() -> service.changeStatement(request, actor));
    }

    @Override
    public RefundView refund(Reference request) {
        return PointsOpsErrors.call(() -> service.refund(request));
    }

    @Override
    public OperatorResult reviseRefund(Revision request, String actor) {
        return PointsOpsErrors.call(() -> service.reviseRefund(request, actor));
    }

    @Override
    public OperatorResult prepareReceivable(ReceivableRecovery request, String actor) {
        return PointsOpsErrors.call(() -> service.prepareReceivable(request, actor));
    }

    @Override
    public FundsView funds(FundsReference request) {
        return PointsOpsErrors.call(() -> service.funds(request));
    }

    @Override
    public Execution executeRecovery(Recovery request, String actor) {
        return PointsOpsErrors.call(() -> service.executeRecovery(request, actor));
    }

    @Override
    public EventPage events(String typeCode, long after, int limit) {
        return PointsOpsErrors.call(() -> service.events(typeCode, after, limit));
    }

    @Override
    public OperatorResult retryEvent(EventRetry request, String actor) {
        return PointsOpsErrors.call(() -> service.retryEvent(request, actor));
    }
}

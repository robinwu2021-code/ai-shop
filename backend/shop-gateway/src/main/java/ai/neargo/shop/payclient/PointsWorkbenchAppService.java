package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
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

public interface PointsWorkbenchAppService {
    StatementPage statements(String typeCode, String storeNo, long after, int limit);

    OperatorResult draftStatements(StatementDraft request, String actor);

    OperatorResult changeStatement(StatementChange request, String actor);

    RefundView refund(Reference request);

    OperatorResult reviseRefund(Revision request, String actor);

    OperatorResult prepareReceivable(ReceivableRecovery request, String actor);

    FundsView funds(FundsReference request);

    Execution executeRecovery(Recovery request, String actor);

    EventPage events(String typeCode, long after, int limit);

    OperatorResult retryEvent(EventRetry request, String actor);
}

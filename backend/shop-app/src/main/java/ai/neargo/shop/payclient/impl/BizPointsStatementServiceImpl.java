package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.StatementAction;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.StatementChange;
import ai.neargo.shop.payclient.BizPointsStatementService;
import ai.neargo.shop.payclient.PointsOpsErrors;
import org.springframework.stereotype.Service;

@Service
public class BizPointsStatementServiceImpl implements BizPointsStatementService {
    private final PointsWorkbenchService workbench;
    public BizPointsStatementServiceImpl(PointsWorkbenchService workbench) {
        this.workbench = workbench;
    }
    @Override
    public View statement(String type, String no) {
        String store = BizContext.requireStoreNo();
        return PointsOpsErrors.call(() -> project(workbench.statement(
                new PointsWorkbenchService.Reference(type, store, no)), type, store));
    }
    @Override
    public PointsWorkbenchService.StatementLinePage lines(String type, String no, long revision, long after, int limit) {
        String store = BizContext.requireStoreNo();
        return PointsOpsErrors.call(() -> workbench.statementLines(
                new PointsWorkbenchService.StatementLines(type, store, no, revision, after, limit)));
    }
    private View project(PointsWorkbenchService.StatementView row, String type, String store) {
        var s = row.snapshot();
        if (!store.equals(s.period().storeNo()) || !type.equals(s.period().typeCode())) {
            throw new IllegalStateException("POINTS_STATEMENT_SCOPE_INVALID");
        }
        return new View(row.id(), s.statementNo(), type, store, s.period().fromMillis(),
                s.period().toMillis(), s.state(), s.current().number(), s.current().points(),
                s.disputeReason(), s.revisions().stream()
                        .map(r -> new Revision(r.number(), r.points(), r.reason())).toList());
    }
    @Override
    public Page statements(String type, long after, int limit) {
        String store = BizContext.requireStoreNo();
        return PointsOpsErrors.call(() -> {
            var page = workbench.statements(type, store, after, limit);
            return new Page(page.rows().stream().map(row -> project(row, type, store)).toList(),
                    page.next(), page.hasMore());
        });
    }
    @Override public OperatorResult confirm(Change request, String actor) {
        return change(request, actor, StatementAction.CONFIRM);
    }
    @Override public OperatorResult dispute(Change request, String actor) {
        return change(request, actor, StatementAction.DISPUTE);
    }
    private OperatorResult change(Change r, String actor, StatementAction action) {
        String store = BizContext.requireStoreNo();
        return PointsOpsErrors.call(() -> {
            if (r == null || r.revision() < 0 || r.reason() == null || r.reason().isBlank()) {
                throw new IllegalArgumentException("POINTS_STATEMENT_INPUT_INVALID");
            }
            return workbench.changeStatement(new StatementChange(r.requestId(), r.typeCode(), store,
                    r.statementNo(), r.revision(), action, r.reason()), actor);
        });
    }
}

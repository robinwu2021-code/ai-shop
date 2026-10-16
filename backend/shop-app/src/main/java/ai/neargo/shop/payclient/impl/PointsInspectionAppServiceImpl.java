package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.points.admin.PointsInspectionService;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.AccountView;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.EntryPage;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.FlowPage;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.Freeze;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.PositionView;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.ReconRun;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.StatsRebuild;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.StatsScope;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.VoucherPage;
import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.query.PointsStatsService.Snapshot;
import ai.neargo.shop.pay.points.recon.PointsReconService.Report;
import ai.neargo.shop.payclient.PointsInspectionAppService;
import ai.neargo.shop.payclient.PointsOpsErrors;

import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class PointsInspectionAppServiceImpl implements PointsInspectionAppService {
    private final PointsInspectionService service;

    public PointsInspectionAppServiceImpl(PointsInspectionService service) {
        this.service = service;
    }

    @Override
    public AccountView account(String typeCode, HolderType holderType, String holderNo) {
        return PointsOpsErrors.call(() -> service.account(typeCode, holderType, holderNo));
    }

    @Override
    public OperatorResult freeze(Freeze request, String actor) {
        return PointsOpsErrors.call(() -> service.freeze(request, actor));
    }

    @Override
    public VoucherPage vouchers(String typeCode, long after, int limit) {
        return PointsOpsErrors.call(() -> service.vouchers(typeCode, after, limit));
    }

    @Override
    public EntryPage entries(
            String typeCode,
            HolderType holderType,
            String holderNo,
            String voucherNo,
            long after,
            int limit) {
        return PointsOpsErrors.call(
                () -> service.entries(typeCode, holderType, holderNo, voucherNo, after, limit));
    }

    @Override
    public FlowPage flows(String typeCode, long after, int limit) {
        return PointsOpsErrors.call(() -> service.flows(typeCode, after, limit));
    }

    @Override
    public PositionView position(String typeCode, PositionMove.Kind kind, String reference) {
        return PointsOpsErrors.call(() -> service.position(typeCode, kind, reference));
    }

    @Override
    public Snapshot stats(String typeCode, LocalDate day, StatsScope scope, String subjectNo) {
        return PointsOpsErrors.call(() -> service.stats(typeCode, day, scope, subjectNo));
    }

    @Override
    public OperatorResult rebuildStats(StatsRebuild request, String actor) {
        return PointsOpsErrors.call(() -> service.rebuildStats(request, actor));
    }

    @Override
    public Report reconciliation(String typeCode, LocalDate day) {
        return PointsOpsErrors.call(() -> service.reconciliation(typeCode, day));
    }

    @Override
    public OperatorResult reconcile(ReconRun request, String actor) {
        return PointsOpsErrors.call(() -> service.reconcile(request, actor));
    }
}

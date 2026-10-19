package ai.neargo.shop.payclient;

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

import java.time.LocalDate;

public interface PointsInspectionAppService {
    AccountView account(String typeCode, HolderType holderType, String holderNo);

    OperatorResult freeze(Freeze request, String actor);

    VoucherPage vouchers(String typeCode, long after, int limit);

    EntryPage entries(
            String typeCode,
            HolderType holderType,
            String holderNo,
            String voucherNo,
            long after,
            int limit);

    FlowPage flows(String typeCode, long after, int limit);

    PositionView position(String typeCode, PositionMove.Kind kind, String reference);

    Snapshot stats(String typeCode, LocalDate day, StatsScope scope, String subjectNo);

    OperatorResult rebuildStats(StatsRebuild request, String actor);

    Report reconciliation(String typeCode, LocalDate day);

    OperatorResult reconcile(ReconRun request, String actor);
}

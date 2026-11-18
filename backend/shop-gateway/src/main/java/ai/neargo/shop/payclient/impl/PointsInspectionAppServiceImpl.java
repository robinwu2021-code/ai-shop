package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.points.admin.PointsInspectionService;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.AccountView;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.AccountPage;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.TransactionDirection;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.TransactionPage;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.TransactionDetail;
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
import ai.neargo.shop.pay.points.query.PointsStatsService.PeriodSnapshot;
import ai.neargo.shop.pay.points.recon.PointsReconService.Report;
import ai.neargo.shop.payclient.PointsInspectionAppService;
import ai.neargo.shop.payclient.PointsOpsAccess;
import ai.neargo.shop.payclient.PointsOpsErrors;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class PointsInspectionAppServiceImpl implements PointsInspectionAppService {
    @Autowired(required = false)
    private StoreDirectoryPort stores;

    private final PointsInspectionService service;

    public PointsInspectionAppServiceImpl(PointsInspectionService service) {
        this.service = service;
    }

    @Override
    public AccountView account(String typeCode, HolderType holderType, String holderNo) {
        if (holderType == HolderType.STORE) PointsOpsAccess.store(stores, holderNo);
        else PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.account(typeCode, holderType, holderNo));
    }

    @Override
    public AccountPage accounts(String typeCode, HolderType holderType, String holderNo, long after, int limit) {
        if (holderType == HolderType.STORE && holderNo != null && !holderNo.isBlank())
            PointsOpsAccess.store(stores, holderNo);
        else PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.accounts(typeCode, holderType, holderNo, after, limit));
    }

    @Override
    public TransactionPage transactions(String typeCode, TransactionDirection direction, LocalDate from,
            LocalDate to, String storeNo, String userNo, long after, int limit) {
        if (storeNo != null && userNo == null) PointsOpsAccess.store(stores, storeNo);
        else PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.transactions(typeCode, direction, from, to, storeNo, userNo, after, limit));
    }

    @Override
    public TransactionDetail transaction(String typeCode, String voucherNo) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.transaction(typeCode, voucherNo));
    }

    @Override
    public OperatorResult freeze(Freeze request, String actor) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.freeze(request, actor));
    }

    @Override
    public VoucherPage vouchers(String typeCode, long after, int limit) {
        PointsOpsAccess.platform();
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
        if (holderType == HolderType.STORE && voucherNo == null)
            PointsOpsAccess.store(stores, holderNo);
        else PointsOpsAccess.platform();
        return PointsOpsErrors.call(
                () -> service.entries(typeCode, holderType, holderNo, voucherNo, after, limit));
    }

    @Override
    public FlowPage flows(String typeCode, long after, int limit) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.flows(typeCode, after, limit));
    }

    @Override
    public PositionView position(String typeCode, PositionMove.Kind kind, String reference) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.position(typeCode, kind, reference));
    }

    @Override
    public Snapshot stats(String typeCode, LocalDate day, StatsScope scope, String subjectNo) {
        if (scope == StatsScope.STORE) PointsOpsAccess.store(stores, subjectNo);
        else PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.stats(typeCode, day, scope, subjectNo));
    }

    @Override
    public PeriodSnapshot periodStats(String typeCode, LocalDate from, LocalDate to) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.periodStats(typeCode, from, to));
    }

    @Override
    public OperatorResult rebuildStats(StatsRebuild request, String actor) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.rebuildStats(request, actor));
    }

    @Override
    public Report reconciliation(String typeCode, LocalDate day) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.reconciliation(typeCode, day));
    }

    @Override
    public OperatorResult reconcile(ReconRun request, String actor) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.reconcile(request, actor));
    }
}

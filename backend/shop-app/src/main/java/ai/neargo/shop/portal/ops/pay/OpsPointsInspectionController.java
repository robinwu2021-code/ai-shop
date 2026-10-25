package ai.neargo.shop.portal.ops.pay;
import org.springframework.web.bind.annotation.PathVariable;


import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
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

import jakarta.validation.Valid;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Profile("ops")
@RestController
public class OpsPointsInspectionController {
    private final PointsInspectionAppService service;

    public OpsPointsInspectionController(PointsInspectionAppService service) {
        this.service = service;
    }

    @GetMapping("/ops/points/account")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public AccountView account(
            @RequestParam String typeCode,
            @RequestParam HolderType holderType,
            @RequestParam String holderNo) {
        return service.account(typeCode, holderType, holderNo);
    }

    @GetMapping("/ops/points/accounts")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public AccountPage accounts(
            @RequestParam String typeCode,
            @RequestParam HolderType holderType,
            @RequestParam(required = false) String holderNo,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return service.accounts(typeCode, holderType, holderNo, after, limit);
    }

    @GetMapping("/ops/points/transactions")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public TransactionPage transactions(
            @RequestParam String typeCode,
            @RequestParam TransactionDirection direction,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) String storeNo,
            @RequestParam(required = false) String userNo,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return service.transactions(typeCode, direction, from, to, storeNo, userNo, after, limit);
    }

    @GetMapping("/ops/points/transactions/{voucherNo}")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public TransactionDetail transaction(
            @RequestParam String typeCode, @PathVariable String voucherNo) {
        return service.transaction(typeCode, voucherNo);
    }

    @PostMapping("/ops/points/account-freeze")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public OperatorResult freeze(@Valid @RequestBody Freeze request) {
        return service.freeze(request, SecurityUtils.currentUserNo());
    }

    @GetMapping("/ops/points/vouchers")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public VoucherPage vouchers(
            @RequestParam String typeCode,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return service.vouchers(typeCode, after, limit);
    }

    @GetMapping("/ops/points/entries")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public EntryPage entries(
            @RequestParam String typeCode,
            @RequestParam(required = false) HolderType holderType,
            @RequestParam(required = false) String holderNo,
            @RequestParam(required = false) String voucherNo,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return service.entries(typeCode, holderType, holderNo, voucherNo, after, limit);
    }

    @GetMapping("/ops/points/flows")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public FlowPage flows(
            @RequestParam String typeCode,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return service.flows(typeCode, after, limit);
    }

    @GetMapping("/ops/points/position")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public PositionView position(
            @RequestParam String typeCode,
            @RequestParam PositionMove.Kind kind,
            @RequestParam String reference) {
        return service.position(typeCode, kind, reference);
    }

    @GetMapping("/ops/points/stats")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public Snapshot stats(
            @RequestParam String typeCode,
            @RequestParam LocalDate day,
            @RequestParam(defaultValue = "TOTAL") StatsScope scope,
            @RequestParam(required = false) String subjectNo) {
        return service.stats(typeCode, day, scope, subjectNo);
    }

    @GetMapping("/ops/points/stats-period")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public PeriodSnapshot periodStats(
            @RequestParam String typeCode, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return service.periodStats(typeCode, from, to);
    }

    @PostMapping("/ops/points/stats-rebuild")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public OperatorResult rebuildStats(@Valid @RequestBody StatsRebuild request) {
        return service.rebuildStats(request, SecurityUtils.currentUserNo());
    }

    @GetMapping("/ops/points/reconciliation")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_RECON_READ + "')")
    public Report reconciliation(@RequestParam String typeCode, @RequestParam LocalDate day) {
        return service.reconciliation(typeCode, day);
    }

    @PostMapping("/ops/points/reconcile")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_RECON_RESOLVE + "')")
    public OperatorResult reconcile(@Valid @RequestBody ReconRun request) {
        return service.reconcile(request, SecurityUtils.currentUserNo());
    }
}

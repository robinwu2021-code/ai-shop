package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
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
import ai.neargo.shop.payclient.PointsTreasuryAppService;

import jakarta.validation.Valid;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("ops")
@RestController
public class OpsPointsTreasuryController {
    private final PointsTreasuryAppService service;

    public OpsPointsTreasuryController(PointsTreasuryAppService service) {
        this.service = service;
    }

    @PostMapping("/ops/points/requests")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public OperatorResult submit(@Valid @RequestBody Submission request) {
        return service.submit(request, SecurityUtils.currentUserNo());
    }

    @PostMapping("/ops/points/request-review")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_PAYOUT_EXECUTE + "')")
    public OperatorResult review(@Valid @RequestBody Review request) {
        return service.review(request, SecurityUtils.currentUserNo());
    }

    @GetMapping("/ops/points/requests")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public RequestPage requests(
            @RequestParam String typeCode,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return service.requests(typeCode, after, limit);
    }

    @PostMapping("/ops/points/plans")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public OperatorResult savePlan(@Valid @RequestBody PlanChange request) {
        return service.savePlan(request, SecurityUtils.currentUserNo());
    }

    @PostMapping("/ops/points/plan-generate")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public OperatorResult generatePeriods(@Valid @RequestBody GeneratePeriods request) {
        return service.generatePeriods(request, SecurityUtils.currentUserNo());
    }

    @PostMapping("/ops/points/plan-receipt")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public OperatorResult declareReceipt(@Valid @RequestBody PlanReceipt request) {
        return service.declareReceipt(request, SecurityUtils.currentUserNo());
    }

    @PostMapping("/ops/points/plan-cancel")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public OperatorResult cancelPeriod(@Valid @RequestBody PeriodCancellation request) {
        return service.cancelPeriod(request, SecurityUtils.currentUserNo());
    }

    @GetMapping("/ops/points/plans")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public PlanPage plans(
            @RequestParam String typeCode,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return service.plans(typeCode, after, limit);
    }

    @PostMapping("/ops/points/withdrawal-execute")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_PAYOUT_EXECUTE + "')")
    public Execution executeWithdrawal(@Valid @RequestBody Withdrawal request) {
        return service.executeWithdrawal(request, SecurityUtils.currentUserNo());
    }
}

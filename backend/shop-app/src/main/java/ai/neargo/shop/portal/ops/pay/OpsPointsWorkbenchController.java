package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
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
import ai.neargo.shop.pay.points.job.PointsRecoveryService.Operation;
import ai.neargo.shop.payclient.PointsWorkbenchAppService;

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
public class OpsPointsWorkbenchController {
    private final PointsWorkbenchAppService service;

    public OpsPointsWorkbenchController(PointsWorkbenchAppService service) {
        this.service = service;
    }

    @GetMapping("/ops/points/statements")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public StatementPage statements(
            @RequestParam String typeCode,
            @RequestParam String storeNo,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return service.statements(typeCode, storeNo, after, limit);
    }

    @PostMapping("/ops/points/statement-draft")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public OperatorResult draftStatements(@Valid @RequestBody StatementDraft request) {
        return service.draftStatements(request, SecurityUtils.currentUserNo());
    }

    @PostMapping("/ops/points/statement-change")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public OperatorResult changeStatement(@Valid @RequestBody StatementChange request) {
        return service.changeStatement(request, SecurityUtils.currentUserNo());
    }

    @GetMapping("/ops/points/refund")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public RefundView refund(
            @RequestParam String typeCode,
            @RequestParam String storeNo,
            @RequestParam String reference) {
        return service.refund(new Reference(typeCode, storeNo, reference));
    }

    @PostMapping("/ops/points/refund-revise")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public OperatorResult reviseRefund(@Valid @RequestBody Revision request) {
        return service.reviseRefund(request, SecurityUtils.currentUserNo());
    }

    @PostMapping("/ops/points/receivable-recovery")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public OperatorResult prepareReceivable(@Valid @RequestBody ReceivableRecovery request) {
        return service.prepareReceivable(request, SecurityUtils.currentUserNo());
    }

    @GetMapping("/ops/points/funds")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public FundsView funds(
            @RequestParam String typeCode,
            @RequestParam String storeNo,
            @RequestParam Operation operation,
            @RequestParam String reference) {
        return service.funds(new FundsReference(typeCode, storeNo, operation, reference));
    }

    @PostMapping("/ops/points/funds-recovery")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_PAYOUT_EXECUTE + "')")
    public Execution executeRecovery(@Valid @RequestBody Recovery request) {
        return service.executeRecovery(request, SecurityUtils.currentUserNo());
    }

    @GetMapping("/ops/points/events")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public EventPage events(
            @RequestParam String typeCode,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return service.events(typeCode, after, limit);
    }

    @PostMapping("/ops/points/event-retry")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public OperatorResult retryEvent(@Valid @RequestBody EventRetry request) {
        return service.retryEvent(request, SecurityUtils.currentUserNo());
    }
}

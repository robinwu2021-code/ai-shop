package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.pay.points.query.PointsOverviewService.Overview;
import ai.neargo.shop.pay.points.query.PointsOverviewService.ReceivablePage;
import ai.neargo.shop.payclient.PointsOpsAppService;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Finance sees each liability-backed pool separately; receivables are never presented as cash. */
@Profile("ops")
@RestController
@Validated
public class OpsPointsController {
    private final PointsOpsAppService points;

    public OpsPointsController(PointsOpsAppService points) {
        this.points = points;
    }

    @GetMapping("/ops/points/receivables")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public ReceivablePage receivables(
            @RequestParam String typeCode,
            @RequestParam String storeNo,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return points.receivables(typeCode, storeNo, after, limit);
    }

    @GetMapping("/ops/points/overview")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public Overview overview(
            @RequestParam(required = false) String typeCode,
            @RequestParam(defaultValue = "CN") String market) {
        return points.overview(typeCode, market);
    }
}

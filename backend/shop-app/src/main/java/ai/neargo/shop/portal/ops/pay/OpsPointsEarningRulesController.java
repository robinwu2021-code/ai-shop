package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.payclient.PointsOpsAccess;
import ai.neargo.shop.payclient.PointsOpsErrors;
import ai.neargo.shop.spi.points.PointsEarnPort.*;
import ai.neargo.shop.spi.product.PointsEarningCatalogPort;
import ai.neargo.shop.trade.order.points.earning.PointsEarningRules;
import ai.neargo.shop.trade.order.points.earning.PointsEarningRules.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Profile("ops")
@RestController
public class OpsPointsEarningRulesController {
    private final PointsEarningRules rules;
    private final PointsAdministrationService points;
    private final PointsEarningCatalogPort catalog;
    private final ai.neargo.shop.trade.order.points.OrderPointsBindingService bindings;
    private final ai.neargo.shop.pay.points.admin.PointsInspectionService inspection;
    public OpsPointsEarningRulesController(PointsEarningRules rules, PointsAdministrationService points, PointsEarningCatalogPort catalog, ai.neargo.shop.trade.order.points.OrderPointsBindingService bindings, ai.neargo.shop.pay.points.admin.PointsInspectionService inspection) {
        this.rules=rules; this.points=points; this.catalog=catalog; this.bindings=bindings; this.inspection=inspection;
    }
    public record Context(PointType type, List<PointsEarningCatalogPort.Category> categories) {}
    public record Preview(String categoryNo, long cashMinor) {}
    public record Calculation(Matched matched, long cashMinor, long points) {}
    @GetMapping("/ops/points/earning-context")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public Context context() {
        PointsOpsAccess.platform();
        return new Context(points.orderDefault("CN"),catalog.categories());
    }
    @GetMapping("/ops/points/earning-rules")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public Page list(@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="50") int size) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> {
            var type = defaultType(); migrate(type);
            return rules.page(type.typeCode(),page,size);
        });
    }
    @GetMapping("/ops/points/earning-rule-history")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public List<Rule> history(@RequestParam String scope) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> rules.history(defaultType().typeCode(),scope));
    }
    @PostMapping("/ops/points/earning-rules")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public Rule change(@RequestBody Change request) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> {
            var type = defaultType();
            if (request == null || !type.typeCode().equals(request.typeCode()) || type.status()!=PointType.Status.ACTIVE)
                throw new IllegalArgumentException("POINTS_EARN_DEFAULT_TYPE_REQUIRED");
            migrate(type);
            return rules.change(request, SecurityUtils.currentUserNo());
        });
    }
    @PostMapping("/ops/points/earning-preview")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public Calculation preview(@RequestBody Preview request) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> {
            if (request == null || request.cashMinor()<0) throw new IllegalArgumentException("POINTS_EARN_INPUT_INVALID");
            var type=defaultType(); var old=points.fallback(type.typeCode());
            var matched=rules.resolve(type.typeCode(),request.categoryNo(),new EarnRule(old.mode(),old.value()),old.revision());
            if (matched.rule().mode()!=RuleMode.RATIO) throw new IllegalArgumentException("POINTS_EARN_LEGACY_REQUIRES_RATIO");
            return new Calculation(matched,request.cashMinor(),PointsEarningRules.calculate(request.cashMinor(),matched.rule().value(),type.rate().pointsUnit(),type.rate().minorUnit()));
        });
    }
    public record OrderEarning(String orderNo, String subOrderNo, String typeCode, String currency,
            ai.neargo.shop.trade.order.points.PointsOrderPlacementCoordinator.EarningSnapshot snapshot,
            ai.neargo.shop.pay.points.admin.PointsInspectionService.EarningView ledger) {}
    @GetMapping("/ops/points/order-earning")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public OrderEarning orderEarning(@RequestParam String subOrderNo) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> {
            var binding=bindings.find(subOrderNo);
            if(binding==null) throw new IllegalArgumentException("POINTS_BINDING_UNKNOWN");
            var admission=binding.admission();
            var snapshot=new tools.jackson.databind.ObjectMapper().readValue(binding.snapshot().ruleSnapshot(),
                    ai.neargo.shop.trade.order.points.PointsOrderPlacementCoordinator.EarningSnapshot.class);
            return new OrderEarning(admission.orderNo(),subOrderNo,admission.typeCode(),admission.currency(),snapshot,
                    inspection.earning(admission.typeCode(),subOrderNo));
        });
    }

    private PointType defaultType() {
        var type=points.orderDefault("CN");
        if(type==null) throw new IllegalArgumentException("POINTS_EARN_DEFAULT_TYPE_REQUIRED");
        return type;
    }
    private void migrate(PointType type) {
        var old=points.fallback(type.typeCode());
        rules.ensureDefault(type.typeCode(),new EarnRule(old.mode(),old.value()),old.revision());
    }
}

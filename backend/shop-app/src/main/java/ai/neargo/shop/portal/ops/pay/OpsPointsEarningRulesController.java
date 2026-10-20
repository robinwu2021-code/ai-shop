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
    private final ai.neargo.shop.points.earning.PointsEarningRuleService service;
    private final ai.neargo.shop.trade.order.points.OrderPointsBindingService bindings;
    private final ai.neargo.shop.pay.points.admin.PointsInspectionService inspection;
    public OpsPointsEarningRulesController(PointsEarningRules rules, PointsAdministrationService points, PointsEarningCatalogPort catalog, ai.neargo.shop.points.earning.PointsEarningRuleService service, ai.neargo.shop.trade.order.points.OrderPointsBindingService bindings, ai.neargo.shop.pay.points.admin.PointsInspectionService inspection) {
        this.service=service; this.rules=rules; this.points=points; this.catalog=catalog; this.bindings=bindings; this.inspection=inspection;
    }
    public record Context(PointType type, List<PointsEarningCatalogPort.Category> categories, List<PointType> types) {}
    public record Preview(String categoryNo, long cashMinor, String typeCode, String currency) {}
    public record Calculation(Matched matched, long cashMinor, long points, ai.neargo.shop.spi.points.PointsEarningRulePort.Type type) {}
    @GetMapping("/ops/points/earning-context")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public Context context() {
        PointsOpsAccess.platform();
        return new Context(points.orderDefault("CN"),catalog.categories(),points.types());
    }
    @GetMapping("/ops/points/earning-rules")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public Page list(@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) String typeCode,@RequestParam(defaultValue="false") boolean all) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> {
            if (all) {
                points.types().stream().filter(t->t.status()==PointType.Status.ACTIVE).forEach(service::initialize);
                return rules.page(null,page,size);
            }
            var type = selectedType(typeCode,false); service.initialize(type);
            return rules.page(type.typeCode(),page,size);
        });
    }
    @GetMapping("/ops/points/earning-rule-history")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public List<Rule> history(@RequestParam String scope,@RequestParam(required=false) String typeCode) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> rules.history(selectedType(typeCode,false).typeCode(),scope));
    }
    @PostMapping("/ops/points/earning-rules")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public Rule change(@RequestBody Change request) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> {
            if(request==null) throw new IllegalArgumentException("POINTS_EARN_INPUT_INVALID");
            var type=service.type(request.typeCode(),true);
            service.initialize(type);
            return rules.change(request, SecurityUtils.currentUserNo());
        });
    }
    @PostMapping("/ops/points/earning-preview")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public Calculation preview(@RequestBody Preview request) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> {
            if (request == null || request.cashMinor()<0) throw new IllegalArgumentException("POINTS_EARN_INPUT_INVALID");
            var type=selectedType(request.typeCode(),true);
            var result=service.calculate(new ai.neargo.shop.spi.points.PointsEarningRulePort.Query(type.typeCode(),request.currency()==null?type.currency():request.currency(),request.categoryNo(),request.cashMinor()));
            return new Calculation(new Matched(result.matchedScope(),result.revision(),new EarnRule(RuleMode.RATIO,result.ratioBp())),result.cashMinor(),result.points(),result.type());
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
    private PointType selectedType(String code, boolean active) {
        return code==null||code.isBlank()?service.type(defaultType().typeCode(),active):service.type(code,active);
    }
}

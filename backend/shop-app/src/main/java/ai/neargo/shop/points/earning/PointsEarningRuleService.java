package ai.neargo.shop.points.earning;

import ai.neargo.shop.trade.order.points.earning.PointsEarningRules;

import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.spi.points.PointsEarnPort.*;
import ai.neargo.shop.spi.points.PointsEarningRulePort;
import ai.neargo.shop.spi.product.PointsEarningCatalogPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Shared configuration and read/compute service; business consumers opt in explicitly. */
@Service
@Transactional("transactionManager")
public class PointsEarningRuleService implements PointsEarningRulePort {
    private final PointsEarningRules rules;
    private final PointsAdministrationService points;
    private final PointsEarningCatalogPort catalog;
    public PointsEarningRuleService(PointsEarningRules rules, PointsAdministrationService points, PointsEarningCatalogPort catalog) {
        this.rules=rules;this.points=points;this.catalog=catalog;
    }
    public PointType type(String code, boolean activeRequired) {
        if(code==null||code.isBlank()) throw new IllegalArgumentException("POINTS_EARN_TYPE_REQUIRED");
        var type=points.types().stream().filter(t->code.equals(t.typeCode())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("POINTS_TYPE_UNKNOWN"));
        if(activeRequired&&type.status()!=PointType.Status.ACTIVE) throw new IllegalArgumentException("POINTS_EARN_TYPE_NOT_ACTIVE");
        return type;
    }
    public void initialize(PointType type) {
        // Only the pre-existing order default imports legacy earning promises.
        var orderDefault=points.orderDefault(type.market());
        if(orderDefault!=null&&orderDefault.typeCode().equals(type.typeCode())) {
            var legacy=points.fallback(type.typeCode());
            rules.ensureDefault(type.typeCode(),new EarnRule(legacy.mode(),legacy.value()),legacy.revision());
        } else if(type.status()==PointType.Status.ACTIVE) rules.ensureZeroDefault(type.typeCode());
    }
    @Override public List<Type> availableTypes() {
        return points.types().stream().filter(t->t.status()==PointType.Status.ACTIVE).map(PointsEarningRuleService::describe).toList();
    }
    @Override public List<PublishedRule> published(String typeCode) {
        var type=type(typeCode,false);initialize(type);
        return rules.all(typeCode).stream().filter(r->r.activeRevision()!=null)
                .map(r->new PublishedRule(r.scope(),r.activeRevision(),r.activeMode(),r.activeValue())).toList();
    }
    @Override public Calculation calculate(Query query) {
        if(query==null||query.cashMinor()<0) throw new IllegalArgumentException("POINTS_EARN_INPUT_INVALID");
        var type=type(query.typeCode(),true);
        if(!type.currency().equals(query.currency())) throw new IllegalArgumentException("POINTS_EARN_CURRENCY_MISMATCH");
        if(query.categoryNo()!=null&&!catalog.categories().stream().anyMatch(c->c.categoryNo().equals(query.categoryNo())))
            throw new IllegalArgumentException("POINTS_CATEGORY_NOT_FOUND");
        initialize(type);
        var matched=PointsEarningRules.match(rules.all(type.typeCode()),query.categoryNo()==null?List.of():catalog.ancestry(query.categoryNo()));
        if(matched.rule().mode()!=RuleMode.RATIO) throw new IllegalArgumentException("POINTS_EARN_LEGACY_REQUIRES_RATIO");
        long amount;
        try {amount=PointsEarningRules.calculate(query.cashMinor(),matched.rule().value(),type.rate().pointsUnit(),type.rate().minorUnit());}
        catch(ArithmeticException overflow){throw new IllegalArgumentException("POINTS_EARN_AMOUNT_OVERFLOW");}
        return new Calculation(describe(type),query.categoryNo(),query.cashMinor(),matched.scope(),matched.revision(),matched.rule().value(),amount);
    }
    private static Type describe(PointType t) {return new Type(t.typeCode(),t.name(),t.market(),t.currency(),t.scale(),t.rate().pointsUnit(),t.rate().minorUnit());}
}

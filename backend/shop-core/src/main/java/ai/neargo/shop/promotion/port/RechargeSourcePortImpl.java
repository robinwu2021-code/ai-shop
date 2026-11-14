package ai.neargo.shop.promotion.port;

import ai.neargo.shop.promotion.service.RechargeIntentService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.storedvalue.RechargeSourcePort;
import org.springframework.stereotype.Component;

@Component
public class RechargeSourcePortImpl implements RechargeSourcePort {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<ai.neargo.shop.spi.storedvalue.StoredValuePolicyFactsPort> policyFacts;
    @org.springframework.beans.factory.annotation.Value("${shop.pay.stored-value.governance-enabled:false}")
    private boolean governance;
    private final RechargeIntentService intents;
    private ai.neargo.shop.spi.user.PersonPort people;
    private ai.neargo.shop.spi.user.MemberIdentityFactsPort identities;
    @org.springframework.beans.factory.annotation.Autowired
    public RechargeSourcePortImpl(RechargeIntentService intents,ai.neargo.shop.spi.user.PersonPort people,ai.neargo.shop.spi.user.MemberIdentityFactsPort identities){this.intents=intents;this.people=people;this.identities=identities;}
    public RechargeSourcePortImpl(RechargeIntentService intents) { this.intents = intents; }
    @Override public SourceFact resolve(EntitlementContext ctx, SourceRef source) {
        var plan = intents.find(ctx.tenantNo(), source.sourceNo()); intents.requireSource(plan, source);
        return fact(plan.fact());
    }
    @Override public SourceFact byRechargeNo(EntitlementContext ctx, String no) {
        return fact(intents.byRechargeNo(ctx.tenantNo(), no).fact());
    }

    private SourceFact fact(SourceFact source){
        if(governance){
            var facts=policyFacts.getIfAvailable();
            var scope=facts==null?null:facts.current(source.context().tenantNo(),source.permit().entityNo(),source.permit().storeNo(),source.permit().versionNo());
            source=new SourceFact(source.context(),source.source(),source.rechargeNo(),source.participation(),source.permit(),source.storeName(),source.createdAt(),source.expiresAt(),source.customerNo(),scope);
        }
        if(people==null)return source;
        var person=people.findByUser(source.permit().userNo());
        if(person.isEmpty())return source;
        var p=person.get();
        String expectedUser=source.permit().userNo();
        var identity=identities.find(source.context().tenantNo(),p.personNo()).filter(i->i.consistent()&&p.personNo().equals(i.customerNo())&&expectedUser.equals(i.userNo())&&expectedUser.equals(p.userNo())).orElseThrow(()->ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.CONFLICT));
        return new SourceFact(source.context(),source.source(),source.rechargeNo(),source.participation(),source.permit(),source.storeName(),source.createdAt(),source.expiresAt(),identity.customerNo(),source.policyScope());
    }
}

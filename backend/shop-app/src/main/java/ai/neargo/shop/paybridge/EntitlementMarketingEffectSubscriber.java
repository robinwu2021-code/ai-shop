package ai.neargo.shop.paybridge;
import org.springframework.beans.factory.annotation.Autowired;


import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.promotion.dto.BenefitGrantPlan;
import ai.neargo.shop.promotion.dto.RechargeIntentPlan;
import ai.neargo.shop.promotion.service.ActivityBenefitIntentService;
import ai.neargo.shop.promotion.service.RechargeIntentService;
import ai.neargo.shop.promotion.service.MarketingEffectProjector;
import ai.neargo.shop.spi.entitlement.DecisionRef.DecisionType;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementGranted;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementUsed;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementUseReversed;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueCredited;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueRechargeReceived;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort.UsageEffects;
import ai.neargo.shop.spi.entitlement.PayEntitlementEvent;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventCodec;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Fact queries precede independent core projection writes; this subscriber cannot execute financial commands. */
@Component
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class EntitlementMarketingEffectSubscriber implements OutboxConsumer {
    @Override public String handlerId() { return "entitlement-marketing-effect"; }
    // Original owner/action ledgers provide replay safety across PAY calls and independent core writes.
    @Override public boolean externalWorkflow() { return true; }

    private final ai.neargo.shop.payclient.StoredValuePlanRechargeAppService direct;
    private final RechargeIntentService recharges;
    private final ActivityBenefitIntentService benefits;
    private final EntitlementMarketingFactsPort pay;
    private final MarketingEffectProjector projection;
    private final ObjectMapper json;
    public EntitlementMarketingEffectSubscriber(RechargeIntentService recharges,ActivityBenefitIntentService benefits,
            EntitlementMarketingFactsPort pay,MarketingEffectProjector projection,ObjectMapper json) {
        this(recharges,benefits,pay,projection,json,null);
    }
    @Autowired
    public EntitlementMarketingEffectSubscriber(RechargeIntentService recharges,ActivityBenefitIntentService benefits,
            EntitlementMarketingFactsPort pay,MarketingEffectProjector projection,ObjectMapper json,
            ai.neargo.shop.payclient.StoredValuePlanRechargeAppService direct){
        this.recharges=recharges;this.benefits=benefits;this.pay=pay;this.projection=projection;this.json=json;this.direct=direct;
    }
    private boolean directRecharge(String entity,String no){
        if(direct==null)return false;
        try{return direct.query(new EntitlementContext("MAIN","OUTBOX_READ",no,"0".repeat(64)),entity,no)!=null;}
        catch(ai.neargo.shop.common.BizException e){if(e.errorCode()==ai.neargo.shop.common.ErrorCode.NOT_FOUND)return false;throw e;}
    }
    @Override public boolean supports(String type) {
        return Set.of("STORED_VALUE_RECHARGE_RECEIVED","STORED_VALUE_CREDITED","ENTITLEMENT_GRANTED",
                "ENTITLEMENT_USED","ENTITLEMENT_USE_REVERSED").contains(type);
    }
    @Override public void consume(SysOutbox row) {
        PayEntitlementEvent event=switch(row.getEventType()) {
            case "STORED_VALUE_RECHARGE_RECEIVED" -> json.readValue(row.getPayload(),StoredValueRechargeReceived.class);
            case "STORED_VALUE_CREDITED" -> json.readValue(row.getPayload(),StoredValueCredited.class);
            case "ENTITLEMENT_GRANTED" -> json.readValue(row.getPayload(),EntitlementGranted.class);
            case "ENTITLEMENT_USED" -> json.readValue(row.getPayload(),EntitlementUsed.class);
            case "ENTITLEMENT_USE_REVERSED" -> json.readValue(row.getPayload(),EntitlementUseReversed.class);
            default -> throw new IllegalArgumentException("Unsupported marketing source event");
        };
        PayEntitlementEventCodec.validate(event);
        require(event.eventNo().equals(row.getEventNo()) && event.aggregateId().equals(row.getAggregateId())
                && event.aggregateType().equals(row.getAggregateType()));
        if(event instanceof StoredValueRechargeReceived e) received(e);
        else if(event instanceof StoredValueCredited e) credited(e);
        else if(event instanceof EntitlementGranted e) granted(e);
        else if(event instanceof EntitlementUsed e) used(e);
        else if(event instanceof EntitlementUseReversed e) reversed(e);
    }
    private void received(StoredValueRechargeReceived event) {
        if(directRecharge(event.entityNo(),event.rechargeNo()))return;
        var plan=recharges.byRechargeNo(event.tenantNo(),event.rechargeNo());
        require(event.entityNo().equals(plan.permit().entityNo()));
        rechargeEffect(event,plan,"RECHARGE",plan.permit().payAmountMinor());
    }
    private void credited(StoredValueCredited event) {
        if(directRecharge(event.entityNo(),event.rechargeNo()))return;
        var plan=recharges.byRechargeNo("MAIN",event.rechargeNo()); var permit=plan.permit();
        require(event.entityNo().equals(permit.entityNo()) && event.storeNo().equals(permit.storeNo())
                && event.userNo().equals(permit.userNo()) && event.currency().equals(permit.currency())
                && event.paidAmountMinor()==permit.payAmountMinor()
                && event.creditedAmountMinor()==Math.addExact(permit.payAmountMinor(),permit.bonusAmountMinor()));
        rechargeEffect(event,plan,"CREDITED",event.creditedAmountMinor());
    }
    private void rechargeEffect(PayEntitlementEvent event,RechargeIntentPlan plan,String kind,long amount) {
        projection.recordPayEffect("MAIN",event.eventNo(),plan.permit().activityNo(),plan.permit().entityNo(),
                plan.permit().storeNo(),kind,amount,plan.permit().currency(),null,at(event));
    }
    private void granted(EntitlementGranted event) {
        if(!"ACTIVITY".equals(event.sourceType())) return;
        BenefitGrantPlan plan=benefits.find("MAIN",event.sourceNo());
        var child=plan.grants().stream().filter(g -> g.grantNo().equals(event.grantNo())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown activity grant contribution"));
        var decision=plan.decision(child.sourceRef());
        require(plan.permit()!=null && decision!=null && decision.decisionType()==DecisionType.GRANT_COMMIT
                && event.entityNo().equals(child.entityNo()) && event.userNo().equals(child.recipientUserNo())
                && event.templateVersionNo().equals(child.templateVersionNo()));
        projection.recordPayEffect("MAIN",event.eventNo(),plan.permit().activityNo(),child.entityNo(),
                plan.permit().storeNo(),"COUPON_ISSUED",null,null,(long)child.quantity(),at(event));
    }
    private void used(EntitlementUsed event) {
        String after="";
        do {
            var facts=usage(event.entityNo(),event.usageNo(),after);
            sameUse(facts,event.userNo(),event.entityNo(),event.storeNo(),event.assetNo());
            require(event.usageNo().equals(facts.usageNo()) && event.assetKind().equals(facts.assetKind()) && event.occurredAt()==facts.occurredAt()
                    && Objects.equals(event.units(),facts.units())
                    && (!"STORED_VALUE".equals(event.assetKind()) || Objects.equals(event.amountMinor(),facts.amountMinor())));
            for(var part:facts.records()) {
                if("STORED_VALUE".equals(event.assetKind())) {
                    projection.recordPayEffect("MAIN",event.eventNo(),part.activityNo(),event.entityNo(),event.storeNo(),
                            "CONSUMED",part.amountMinor(),facts.currency(),null,at(event));
                } else {
                    projection.recordPayEffect("MAIN",event.eventNo(),part.activityNo(),event.entityNo(),event.storeNo(),
                            "RIGHTS_USED",null,null,part.units(),at(event));
                    if(part.amountMinor()!=null) projection.recordPayEffect("MAIN",event.eventNo(),part.activityNo(),
                            event.entityNo(),event.storeNo(),"COUPON_DISCOUNT",part.amountMinor(),facts.currency(),null,at(event));
                }
            }
            require(facts.nextCursor()==null || !facts.nextCursor().equals(after));
            after=facts.nextCursor();
        } while(after!=null);
    }
    private void reversed(EntitlementUseReversed event) {
        var facts=usage(event.entityNo(),event.usageNo(),"");
        sameUse(facts,event.userNo(),event.entityNo(),event.storeNo(),event.instanceNo());
        require(event.usageNo().equals(facts.usageNo()) && "ORDER_COUPON".equals(facts.assetKind()) && event.currency().equals(facts.currency())
                && facts.amountMinor()!=null && event.discountMinor()<=facts.amountMinor()
                && facts.units()!=null && event.restoredUnits()<=facts.units()
                && facts.nextCursor()==null && facts.records().size()<=1);
        for(var part:facts.records()) projection.recordPayEffect("MAIN",event.eventNo(),part.activityNo(),
                event.entityNo(),event.storeNo(),"COUPON_RESTORED",event.discountMinor(),event.currency(),
                event.restoredUnits()==0?null:(long)event.restoredUnits(),at(event));
    }
    private UsageEffects usage(String entity,String no,String after) {
        return pay.usage(new EntitlementContext("MAIN","MARKETING",no,"0".repeat(64)),entity,no,after,100);
    }
    private static void sameUse(UsageEffects facts,String user,String entity,String store,String asset) {
        require("MAIN".equals(facts.tenantNo()) && user.equals(facts.userNo()) && entity.equals(facts.entityNo())
                && store.equals(facts.storeNo()) && asset.equals(facts.assetNo()));
    }
    private static Instant at(PayEntitlementEvent event) { return Instant.ofEpochMilli(event.occurredAt()); }
    private static void require(boolean valid) {
        if(!valid) throw new IllegalStateException("Marketing event does not match its original source facts");
    }
}

package ai.neargo.shop.paybridge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.promotion.service.ActivityBenefitIntentService;
import ai.neargo.shop.promotion.service.RechargeIntentService;
import ai.neargo.shop.promotion.service.MarketingEffectProjector;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementUsed;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementUseReversed;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort.UsageEffects;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort.Attribution;
import ai.neargo.shop.spi.entitlement.PayEntitlementEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Projection dimensions and original-use bindings remain strict even if a remote owner reply is confused. */
class EntitlementMarketingEffectSubscriberTest {
    private final EntitlementMarketingFactsPort pay=mock(EntitlementMarketingFactsPort.class);
    private final MarketingEffectProjector projection=mock(MarketingEffectProjector.class);
    private final ObjectMapper json=new ObjectMapper();
    private final EntitlementMarketingEffectSubscriber subscriber=new EntitlementMarketingEffectSubscriber(
            mock(RechargeIntentService.class),mock(ActivityBenefitIntentService.class),pay,projection,json);
    private final long at=1_797_000_000_000L;
    @Test void couponUsageAndPartialRestorationKeepAmountsAndUnitsSeparate() {
        var facts=new UsageEffects("MAIN","EU1","U1","M1","S1","ORDER_COUPON","I1","CNY",100L,1,at,
                List.of(new Attribution("ACT1",100L,1L)),null);
        when(pay.usage(any(),eq("M1"),eq("EU1"),eq(""),eq(100))).thenReturn(facts);
        subscriber.consume(row(new EntitlementUsed("EV1","EU1","U1","M1","S1","ORDER_COUPON","I1",null,1,at)));
        verify(projection).recordPayEffect("MAIN","EV1","ACT1","M1","S1","RIGHTS_USED",null,null,1L,
                Instant.ofEpochMilli(at));
        verify(projection).recordPayEffect("MAIN","EV1","ACT1","M1","S1","COUPON_DISCOUNT",100L,"CNY",null,
                Instant.ofEpochMilli(at));
        subscriber.consume(row(new EntitlementUseReversed("ER1","R1","EU1","U1","M1","S1","I1",0,30,"CNY",at+1)));
        verify(projection).recordPayEffect("MAIN","ER1","ACT1","M1","S1","COUPON_RESTORED",30L,"CNY",null,
                Instant.ofEpochMilli(at+1));
    }
    @Test void anotherOwnerOrOverRestorationCannotWriteProjection() {
        var foreign=new UsageEffects("MAIN","EU1","U1","OTHER","S1","ORDER_COUPON","I1","CNY",100L,1,at,
                List.of(new Attribution("ACT1",100L,1L)),null);
        when(pay.usage(any(),any(),any(),any(),eq(100))).thenReturn(foreign);
        assertThatThrownBy(() -> subscriber.consume(row(new EntitlementUsed("EV1","EU1","U1","M1","S1",
                "ORDER_COUPON","I1",null,1,at)))).isInstanceOf(IllegalStateException.class);
        var actual=new UsageEffects("MAIN","EU1","U1","M1","S1","ORDER_COUPON","I1","CNY",100L,1,at,
                List.of(new Attribution("ACT1",100L,1L)),null);
        when(pay.usage(any(),any(),any(),any(),eq(100))).thenReturn(actual);
        assertThatThrownBy(() -> subscriber.consume(row(new EntitlementUseReversed("ER1","R1","EU1","U1","M1","S1",
                "I1",1,101,"CNY",at+1)))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(projection);
    }
    private SysOutbox row(PayEntitlementEvent event) {
        var row=new SysOutbox(); row.setEventNo(event.eventNo()); row.setEventType(event.eventType());
        row.setAggregateId(event.aggregateId()); row.setAggregateType(event.aggregateType());
        row.setPayload(json.writeValueAsString(event)); return row;
    }
}

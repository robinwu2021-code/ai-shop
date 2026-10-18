package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.marketing.CampaignPort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UnifiedOrderActivityPricingServiceTest {
    private static final Instant NOW=Instant.parse("2026-10-19T04:00:00Z");
    @Test void amountThresholdAndCapUseExactMinorUnits() {
        var d=definition("ORDER","AMOUNT",1000L,null,"CUT",2000L,List.of("S1"));
        assertEquals(0,price(d,999,"S1"));
        assertEquals(1500,price(d,1500,"S1"));
        assertEquals(0,price(d,1500,"S2"));
        assertEquals(0,price(d,1500,null));
    }
    @Test void quantityTriggerAndRecurringBoundaryAreEnforced() {
        var d=definition("ORDER","QTY",null,3,"CUT",100L,List.of("S1"));
        assertEquals(0,UnifiedOrderActivityPricingServiceImpl.discount(d,new CampaignPort.MerchantAmount("M1",1000,2,"S1"),NOW));
        assertEquals(100,price(d,1000,"S1"));
        assertEquals(0,UnifiedOrderActivityPricingServiceImpl.discount(d,new CampaignPort.MerchantAmount("M1",1000,3,"S1"),NOW.plusSeconds(3600)));
    }
    @Test void entitlementAndUnimplementedEffectsCannotLeakIntoOrderPricing() {
        assertEquals(0,price(definition("RECHARGE","NONE",null,null,"CUT",100L,List.of("S1")),1000,"S1"));
        assertEquals(0,price(definition("ORDER","NONE",null,null,"PRICE",100L,List.of("S1")),1000,"S1"));
        assertEquals(0,price(definition("ORDER","GOODS",null,null,"CUT",100L,List.of("S1")),1000,"S1"));
    }
    private static long price(UnifiedActivityDefinition d,long amount,String store) {
        return UnifiedOrderActivityPricingServiceImpl.discount(d,new CampaignPort.MerchantAmount("M1",amount,3,store),NOW);
    }
    private static UnifiedActivityDefinition definition(String scene,String trigger,Long threshold,Integer qty,
            String benefit,Long amount,List<String> stores) {
        return new UnifiedActivityDefinition("优惠",scene,"AP002",1,new RedemptionScope("STORES",stores),null,
                new UnifiedActivityDefinition.Schedule("RECURRING",NOW.minusSeconds(1),NOW.plusSeconds(7200),
                        "Asia/Shanghai",List.of(1),"12:00","13:00"),
                new UnifiedActivityDefinition.Rule(1,new UnifiedActivityDefinition.Trigger(trigger,threshold,qty),
                        List.of(new UnifiedActivityDefinition.Benefit(benefit,amount,null,null,null)),List.of()),
                10L,10000L,1,"CNY");
    }
}

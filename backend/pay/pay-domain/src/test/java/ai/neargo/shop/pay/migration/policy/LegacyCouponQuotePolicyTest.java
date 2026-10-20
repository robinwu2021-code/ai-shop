package ai.neargo.shop.pay.migration.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.pay.migration.policy.LegacyCouponQuotePolicy;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.MerchantGroup;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyCouponQuotePolicyTest {
    private EntitlementDefinition rule(String benefit,long value,Long cap,long minimum) {
        return new EntitlementDefinition("legacy","ORDER_COUPON","MERCHANT","CNY",benefit,value,cap,null,minimum,null,
                "COUNT",3,null,new Validity("ABSOLUTE",Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-01-01T00:00:00Z"),null,"GRANTED","ROLLING_DAYS","Asia/Shanghai"),
                new RedemptionScope("ALL",List.of()),List.of("CENTER","TARGETED"));
    }
    @Test void originalMerchantAndStoreSplitIsConservedWithoutChargingOtherMerchants() {
        var groups=List.of(new MerchantGroup("S1","M1","S1",101),new MerchantGroup("SX","M2","SX",500),
                new MerchantGroup("S2","M1","S2",199));
        var quote=new LegacyCouponQuotePolicy().calculate(rule("CASH",100,1L,300),"M1",groups);
        assertThat(quote.discountMinor()).isEqualTo(100); // CASH never uses the percent-only cap.
        assertThat(quote.allocations()).extracting(a->a.groupKey()).containsExactly("S1","S2");
        assertThat(quote.allocations()).extracting(a->a.discountMinor()).containsExactly(34L,66L);
    }
    @Test void percentCapsAndPostActivityThresholdUseTheSameIntegerAlgorithmAsTheOldOrder() {
        var groups=List.of(new MerchantGroup("S1","M1","S1",201),new MerchantGroup("S2","M1","S2",99));
        var quote=new LegacyCouponQuotePolicy().calculate(rule("PERCENT",8500,40L,300),"M1",groups);
        assertThat(quote.discountMinor()).isEqualTo(40);
        assertThat(quote.allocations()).extracting(a->a.discountMinor()).containsExactly(27L,13L);
        assertThatThrownBy(()->new LegacyCouponQuotePolicy().calculate(rule("CASH",100,null,301),"M1",groups))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }
    @Test void zeroBenefitUnboundedPercentAndOverflowCannotCreateAUseCommitment() {
        var zero=List.of(new MerchantGroup("S1","M1","S1",0));
        assertThatThrownBy(()->new LegacyCouponQuotePolicy().calculate(rule("CASH",100,null,0),"M1",zero))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
        var groups=List.of(new MerchantGroup("S1","M1","S1",100));
        assertThatThrownBy(()->new LegacyCouponQuotePolicy().calculate(rule("PERCENT",8500,null,0),"M1",groups))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThatThrownBy(()->new LegacyCouponQuotePolicy().calculate(rule("CASH",100,null,0),"M1",
                List.of(new MerchantGroup("S1","M1","S1",Long.MAX_VALUE),new MerchantGroup("S2","M1","S2",1))))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }
}

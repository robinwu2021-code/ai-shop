package ai.neargo.shop.pay.svc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.svc.client.port.CoreLegacyCouponCheckoutSourcePortImpl;
import ai.neargo.shop.spi.entitlement.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Transport adapter contract only; authoritative checkout production is verified separately. */
class CoreLegacyCouponCheckoutClientTest {
    private final EntitlementContext ctx=new EntitlementContext("MAIN","PAY","SOURCE","a".repeat(64));
    private final CoreEntitlementInternalApi api=mock(CoreEntitlementInternalApi.class);
    private CoreLegacyCouponCheckoutSourcePortImpl client() {
        var beans=new StaticListableBeanFactory(); beans.addBean("coreApi",api);
        return new CoreLegacyCouponCheckoutSourcePortImpl(beans.getBeanProvider(CoreEntitlementInternalApi.class));
    }
    @Test void onlyAPresentEmptyEnvelopeMeansUndecided() {
        var query=new EntitlementCoreInternal.LegacyCheckoutQuery(ctx,"LC1");
        when(api.legacyCheckoutDecision(query)).thenReturn(new EntitlementCoreInternal.LegacyCheckoutDecision(null));
        when(api.legacyCheckoutReversal(query)).thenReturn(new EntitlementCoreInternal.LegacyCheckoutReversal(null));
        assertThat(client().decision(ctx,"LC1")).isEmpty();
        assertThat(client().reversal(ctx,"LC1")).isEmpty();
        when(api.legacyCheckoutDecision(query)).thenReturn(null);
        assertThatThrownBy(()->client().decision(ctx,"LC1")).isInstanceOfSatisfying(BizException.class,
                e->assertThat(e.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
        when(api.legacyCheckoutReversal(query)).thenReturn(null);
        assertThatThrownBy(()->client().reversal(ctx,"LC1")).isInstanceOfSatisfying(BizException.class,
                e->assertThat(e.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
    }
    @Test void terminalSourceDecisionIsReturnedWithoutGuessingOrRetrying() {
        var decision=new LegacyCouponCheckoutSourcePort.Decision("LC1","OP1","D1",
                LegacyCouponCheckoutSourcePort.Outcome.ABORT,null,"b".repeat(64),null,Instant.now());
        var query=new EntitlementCoreInternal.LegacyCheckoutQuery(ctx,"LC1");
        when(api.legacyCheckoutDecision(query)).thenReturn(new EntitlementCoreInternal.LegacyCheckoutDecision(decision));
        assertThat(client().decision(ctx,"LC1")).contains(decision);
        verify(api).legacyCheckoutDecision(query); verifyNoMoreInteractions(api);
    }
}

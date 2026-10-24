package ai.neargo.shop.pay.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.pay.entitlement.port.EntitlementCapabilityPortImpl;
import ai.neargo.shop.pay.storedvalue.port.StoredValueCapabilityPortImpl;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Scope;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class FundingCapabilityTest {
    private final PayEntitlementProperties properties = new PayEntitlementProperties();
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final Scope coupon = new Scope("MAIN", "E", "S", "CORE", "ORDER_USE", "CNY");
    private final Scope stored = new Scope("MAIN", "E", "S", "CORE", "SERVICE_USE", "CNY");
    private EntitlementCapabilityPortImpl coupons() {
        return new EntitlementCapabilityPortImpl(properties, beans.getBeanProvider(EntitlementUsePort.class));
    }
    private StoredValueCapabilityPortImpl stored() {
        return new StoredValueCapabilityPortImpl(properties, beans.getBeanProvider(EntitlementUsePort.class),
                beans.getBeanProvider(StoredValueUseQuotePort.class));
    }
    private void enabled() {
        properties.getEntitlement().getUse().setEnabled(true); properties.getStoredValue().setEnabled(true);
    }
    @Test void oldReadyBooleansDoNotProveExecutionOwnersForPricing() {
        enabled();
        assertThat(new EntitlementCapabilityPortImpl(properties).status("MAIN").useReady()).isTrue();
        assertThat(coupons().pricing(coupon).status()).isEqualTo(Status.OWNER_UNAVAILABLE);
        assertThat(new StoredValueCapabilityPortImpl(properties).status("MAIN").redemptionReady()).isTrue();
        assertThat(stored().pricing(stored).status()).isEqualTo(Status.OWNER_UNAVAILABLE);
    }
    @Test void uniqueActualPortsExposeOnlyVerifiedIndependentLimitsWithoutCallingMoneyOperations() {
        enabled(); var use = mock(EntitlementUsePort.class); var quote = mock(StoredValueUseQuotePort.class);
        beans.registerSingleton("use", use); beans.registerSingleton("quote", quote);
        assertThat(coupons().pricing(coupon).capabilities().maxCouponsPerOrder()).isEqualTo(1);
        assertThat(coupons().pricing(coupon).capabilities().stacking()).isFalse();
        assertThat(stored().pricing(stored).capabilities().storedValue()).isTrue();
        assertThat(stored().pricing(stored).capabilities().maxCouponsPerOrder()).isZero();
        verifyNoInteractions(use, quote);
    }
    @Test void enabledOwnersStillCannotAcceptDiningCartOrUnverifiedCurrency() {
        enabled(); beans.registerSingleton("use", mock(EntitlementUsePort.class));
        beans.registerSingleton("quote", mock(StoredValueUseQuotePort.class));
        var food = new Scope("MAIN", "E", "S", "FOOD", "CART", "CNY");
        assertThat(coupons().pricing(food).status()).isEqualTo(Status.SOURCE_UNSUPPORTED);
        assertThat(stored().pricing(food).capabilities()).isNull();
        assertThat(coupons().pricing(new Scope("MAIN", "E", "S", "CORE", "ORDER_USE", "AED")).status())
                .isEqualTo(Status.CURRENCY_UNSUPPORTED);
    }
    @Test void featureRevocationChangesStableReceiptAndNeverRetainsCapability() {
        enabled(); beans.registerSingleton("use", mock(EntitlementUsePort.class));
        var first = coupons().pricing(coupon); assertThat(coupons().pricing(coupon)).isEqualTo(first);
        properties.getEntitlement().getUse().setEnabled(false);
        assertThat(coupons().pricing(coupon).status()).isEqualTo(Status.DISABLED);
        assertThat(coupons().pricing(coupon).snapshotHash()).isNotEqualTo(first.snapshotHash());
        assertThat(coupons().pricing(coupon).capabilities()).isNull();
    }
    @Test void ambiguousOwnersAndMissingStoredQuotePortNeverProveReadiness() {
        enabled(); beans.registerSingleton("use1", mock(EntitlementUsePort.class));
        assertThat(stored().pricing(stored).status()).isEqualTo(Status.OWNER_UNAVAILABLE);
        beans.registerSingleton("use2", mock(EntitlementUsePort.class));
        assertThat(coupons().pricing(coupon).status()).isEqualTo(Status.OWNER_UNAVAILABLE);
    }
}

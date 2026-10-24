package ai.neargo.shop.spi.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Family;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Scope;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Snapshot;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Status;
import org.junit.jupiter.api.Test;

class PricingFundingCapabilitiesTest {
    private final Scope scope = new Scope("MAIN", "E", "S", "CORE", "ORDER_USE", "CNY");
    @Test void exactSupportedProtocolHasStableScopeBoundDigest() {
        var value = PricingFundingCapabilities.freeze(Family.ORDER_COUPON, scope, Status.SUPPORTED);
        PricingFundingCapabilities.validate(value, Family.ORDER_COUPON, scope);
        assertThat(PricingFundingCapabilities.freeze(Family.ORDER_COUPON, scope, Status.SUPPORTED)).isEqualTo(value);
        assertThat(value.capabilities()).isEqualTo(new PricingCapabilities(1, 1, false, false, false));
    }
    @Test void unsupportedCannotCarryEvenZeroOrMaximumCapabilities() {
        for (var caps : new PricingCapabilities[] {new PricingCapabilities(0, 0, false, false, false),
                new PricingCapabilities(8, 4, true, true, true)}) {
            assertThatThrownBy(() -> new Snapshot(1, Family.ORDER_COUPON, scope, Status.DISABLED,
                    "ORDER_COUPON_SINGLE_V1", caps, "a".repeat(64))).isInstanceOf(RuntimeException.class);
        }
        assertThat(PricingFundingCapabilities.freeze(Family.ORDER_COUPON, scope, Status.DISABLED).capabilities())
                .isNull();
    }
    @Test void supportedCannotWidenSourcesCurrencyOrStacking() {
        for (var other : new Scope[] {new Scope("MAIN", "E", "S", "FOOD", "CART", "CNY"),
                new Scope("MAIN", "E", "S", "CORE", "ORDER_USE", "AED")}) {
            assertThatThrownBy(() -> PricingFundingCapabilities.freeze(Family.ORDER_COUPON, other, Status.SUPPORTED))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> new Snapshot(1, Family.ORDER_COUPON, scope, Status.SUPPORTED,
                "ORDER_COUPON_SINGLE_V1", new PricingCapabilities(2, 2, true, true, false), "a".repeat(64)))
                .isInstanceOf(RuntimeException.class);
    }
    @Test void foreignFamilyScopeAndTamperedHashAreRejected() {
        var value = PricingFundingCapabilities.freeze(Family.ORDER_COUPON, scope, Status.SUPPORTED);
        assertThatThrownBy(() -> PricingFundingCapabilities.validate(value, Family.STORED_VALUE, scope))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> PricingFundingCapabilities.validate(value, Family.ORDER_COUPON,
                new Scope("MAIN", "OTHER", "S", "CORE", "ORDER_USE", "CNY"))).isInstanceOf(RuntimeException.class);
        var changed = new Snapshot(1, value.family(), scope, value.status(), value.protocol(), value.capabilities(),
                "0".repeat(64));
        assertThatThrownBy(() -> PricingFundingCapabilities.validate(changed, Family.ORDER_COUPON, scope))
                .isInstanceOf(RuntimeException.class);
    }
}

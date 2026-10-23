package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.payclient.port.RemoteEntitlementCapabilityPort;
import ai.neargo.shop.payclient.port.RemoteStoredValueCapabilityPort;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Family;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Scope;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Snapshot;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Status;
import org.junit.jupiter.api.Test;

class PricingFundingRemoteTest {
    private final PayInternalApi api = mock(PayInternalApi.class);
    private final Scope scope = new Scope("MAIN", "E", "S", "CORE", "ORDER_USE", "CNY");
    @Test void remoteResponseMustMatchExactScopeFamilyAndDigest() {
        var port = new RemoteEntitlementCapabilityPort(api);
        assertThatThrownBy(() -> port.pricing(scope)).isInstanceOf(BizException.class);
        var value = PricingFundingCapabilities.freeze(Family.ORDER_COUPON, scope, Status.SUPPORTED);
        when(api.couponPricingCapabilities(any())).thenReturn(value);
        assertThat(port.pricing(scope)).isEqualTo(value);
        when(api.couponPricingCapabilities(any())).thenReturn(PricingFundingCapabilities.freeze(Family.ORDER_COUPON,
                new Scope("MAIN", "OTHER", "S", "CORE", "ORDER_USE", "CNY"), Status.SUPPORTED));
        assertThatThrownBy(() -> port.pricing(scope)).isInstanceOf(BizException.class);
        when(api.couponPricingCapabilities(any())).thenReturn(new Snapshot(1, value.family(), scope,
                value.status(), value.protocol(), value.capabilities(), "0".repeat(64)));
        assertThatThrownBy(() -> port.pricing(scope)).isInstanceOf(BizException.class);
    }
    @Test void storedTransportCannotReuseCouponOwnerEvidenceOrConvertDenialIntoZeroCapacity() {
        var port = new RemoteStoredValueCapabilityPort(api);
        when(api.storedPricingCapabilities(any())).thenReturn(
                PricingFundingCapabilities.freeze(Family.ORDER_COUPON, scope, Status.SUPPORTED));
        assertThatThrownBy(() -> port.pricing(scope)).isInstanceOf(BizException.class);
        var denied = PricingFundingCapabilities.freeze(Family.STORED_VALUE, scope, Status.SOURCE_UNSUPPORTED);
        when(api.storedPricingCapabilities(any())).thenReturn(denied);
        assertThat(port.pricing(scope)).isEqualTo(denied); assertThat(port.pricing(scope).capabilities()).isNull();
    }
}

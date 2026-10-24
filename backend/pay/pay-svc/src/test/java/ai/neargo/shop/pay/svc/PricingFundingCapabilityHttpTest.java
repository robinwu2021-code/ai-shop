package ai.neargo.shop.pay.svc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.pay.client.PayCapabilityRequests;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.pay.entitlement.port.EntitlementCapabilityPortImpl;
import ai.neargo.shop.pay.storedvalue.port.StoredValueCapabilityPortImpl;
import ai.neargo.shop.spi.entitlement.EntitlementCapabilityPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.storedvalue.StoredValueCapabilityPort;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Family;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Scope;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Status;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real typed HTTP and token handling; production local policies supply the response, not hand-written JSON. */
@SpringBootTest(classes = PayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shop.services.internal-token=tk")
@ActiveProfiles("svctest")
class PricingFundingCapabilityHttpTest {
    @LocalServerPort private int port;
    @MockitoBean private EntitlementCapabilityPort coupons;
    @MockitoBean private StoredValueCapabilityPort stored;
    private EntitlementCapabilityPortImpl localCoupons;
    private StoredValueCapabilityPortImpl localStored;
    @BeforeEach void owners() {
        var properties = new PayEntitlementProperties(); properties.getEntitlement().getUse().setEnabled(true);
        properties.getStoredValue().setEnabled(true);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("use", mock(EntitlementUsePort.class));
        beans.registerSingleton("quote", mock(StoredValueUseQuotePort.class));
        localCoupons = new EntitlementCapabilityPortImpl(properties, beans.getBeanProvider(EntitlementUsePort.class));
        localStored = new StoredValueCapabilityPortImpl(properties, beans.getBeanProvider(EntitlementUsePort.class),
                beans.getBeanProvider(StoredValueUseQuotePort.class));
        when(coupons.pricing(any())).thenAnswer(call -> localCoupons.pricing(call.getArgument(0)));
        when(stored.pricing(any())).thenAnswer(call -> localStored.pricing(call.getArgument(0)));
    }
    private PayInternalApi api(String token) {
        var locator = new ConfigServiceLocator(); locator.getTargets().put(ServiceName.PAY, "http://127.0.0.1:" + port);
        return new InternalHttp(locator, token).client(ServiceName.PAY, PayInternalApi.class, Duration.ofSeconds(5));
    }
    @Test void realHttpMatchesLocalScopeProtocolLimitsAndDigestWithoutResponseEnvelope() {
        var coupon = new Scope("MAIN", "E", "S", "CORE", "ORDER_USE", "CNY");
        var value = api("tk").couponPricingCapabilities(new PayCapabilityRequests.Pricing(coupon));
        assertThat(value).isEqualTo(localCoupons.pricing(coupon));
        PricingFundingCapabilities.validate(value, Family.ORDER_COUPON, coupon);
        var service = new Scope("MAIN", "E", "S", "CORE", "SERVICE_USE", "CNY");
        assertThat(api("tk").storedPricingCapabilities(new PayCapabilityRequests.Pricing(service)))
                .isEqualTo(localStored.pricing(service));
    }
    @Test void diningSourceRemainsExplicitlyUnsupportedAcrossBothOwnerEndpoints() {
        var request = new PayCapabilityRequests.Pricing(new Scope("MAIN", "E", "S", "FOOD", "CART", "CNY"));
        var coupon = api("tk").couponPricingCapabilities(request);
        assertThat(coupon.status()).isEqualTo(Status.SOURCE_UNSUPPORTED); assertThat(coupon.capabilities()).isNull();
        assertThat(api("tk").storedPricingCapabilities(request).status()).isEqualTo(Status.SOURCE_UNSUPPORTED);
    }
    @Test void wrongTokenIsRejectedBeforeEitherCapabilityOwnerRuns() {
        var request = new PayCapabilityRequests.Pricing(new Scope("MAIN", "E", "S", "CORE", "ORDER_USE", "CNY"));
        assertThatThrownBy(() -> ServiceCalls.call(ServiceName.PAY,
                () -> api("wrong").couponPricingCapabilities(request)))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(401));
        assertThatThrownBy(() -> ServiceCalls.call(ServiceName.PAY,
                () -> api("wrong").storedPricingCapabilities(request)))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(401));
        verifyNoInteractions(coupons, stored);
    }
}

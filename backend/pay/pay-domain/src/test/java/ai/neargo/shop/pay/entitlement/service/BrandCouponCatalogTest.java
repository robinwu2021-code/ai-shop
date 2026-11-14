package ai.neargo.shop.pay.entitlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.impl.EntitlementCatalogServiceImpl;
import ai.neargo.shop.pay.mapper.entitlement.EntTemplateMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntTemplateVersionMapper;
import ai.neargo.shop.spi.entitlement.BrandCouponSourcePort;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

class BrandCouponCatalogTest {
    @Test void lowestCatalogWriteRequiresBrandSourceEvenWithoutAppPreflight() {
        var scope = new CatalogScope("MAIN", "ISSUER", "ACTOR", false);
        var d = new EntitlementDefinition("coupon", "ORDER_COUPON", "MERCHANT", "CNY", "CASH", 100L,
                null, null, 0L, null, "COUNT", 1, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("BRAND_STORES", List.of("SOURCE")), List.of("TARGETED"));
        var heads = mock(EntTemplateMapper.class);
        var commands = mock(EntitlementCommandDeduplicator.class);
        var beans = new StaticListableBeanFactory();
        var catalog = new EntitlementCatalogServiceImpl(heads, mock(EntTemplateVersionMapper.class), commands,
                new ObjectMapper(), mock(PayEntitlementFeatureGate.class),
                beans.getBeanProvider(BrandCouponSourcePort.class));
        assertThatThrownBy(() -> catalog.create(scope, d, "KEY")).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
        verifyNoInteractions(heads);
        verify(commands).replay(scope, "CATALOG_CREATE", "KEY", d,
                ai.neargo.shop.pay.entitlement.dto.CatalogViews.TemplateView.class);
        org.mockito.Mockito.verifyNoMoreInteractions(commands);
        var source = mock(BrandCouponSourcePort.class); beans.addBean("source", source);
        catalog.create(scope, d, "KEY");
        verify(source).validateTemplate("MAIN", "ISSUER", d);
    }
}

package ai.neargo.shop.pay.entitlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.impl.EntitlementUseServiceImpl;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntPeriodBucketMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseProposalMapper;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRedemptionService;
import ai.neargo.shop.spi.entitlement.BrandCouponSourcePort;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

class BrandCouponQuoteTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EntInstanceMapper instances = mock(EntInstanceMapper.class);
    private final EntitlementUseWriter writer = mock(EntitlementUseWriter.class);
    private final BrandCouponSourcePort brand = mock(BrandCouponSourcePort.class);
    private final EntitlementUseSourcePort source = mock(EntitlementUseSourcePort.class);
    private final EntitlementContext context = new EntitlementContext("MAIN", "ACTOR", "REQUEST", "a".repeat(64));
    private final SourceRef sourceRef = new SourceRef("CORE", "ORDER", "ORDER-1", null, null, null, 1);
    private final List<UseItem> items = List.of(new UseItem("L1", "G1", "goods", 1, 1000));
    private final QuoteCommand command = new QuoteCommand(sourceRef, "U1", "CONSUMER", "S2", "CNY", 1000,
            0, items, List.of("I1"), "a".repeat(64));

    @Test void onlyBrandOrderCouponCanQuoteAtAnotherEntity() {
        var d = definition("BRAND_STORES");
        var service = fixture(d, "ORDER_COUPON", true);
        when(brand.permitsUse("MAIN", "ISSUER", d, "VERSION", "CONSUMER", "S2")).thenReturn(true);
        assertThat(service.quote(context, command).discountMinor()).isEqualTo(100);
        verify(brand).permitsUse("MAIN", "ISSUER", d, "VERSION", "CONSUMER", "S2");
        fixture(definition("STORES"), "ORDER_COUPON", true);
        assertThatThrownBy(() -> service.quote(context, command)).isInstanceOf(BizException.class);
        fixture(d, "PAID_CARD", true);
        assertThatThrownBy(() -> service.quote(context, command)).isInstanceOf(BizException.class);
    }

    @Test void unavailableOrDeniedBrandFactsNeverQuoteAcrossEntities() {
        var d = definition("BRAND_STORES");
        assertThatThrownBy(() -> fixture(d, "ORDER_COUPON", false).quote(context, command))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> fixture(d, "ORDER_COUPON", true).quote(context, command))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(writer);
    }

    @Test void copiedDefinitionOnAnotherNativeVersionCannotQuote() {
        var d = definition("BRAND_STORES");
        when(brand.permitsUse("MAIN", "ISSUER", d, "VERSION", "CONSUMER", "S2")).thenReturn(true);
        var copied = fixture(d, "ORDER_COUPON", true, "COPIED-VERSION");
        assertThatThrownBy(() -> copied.quote(context, command)).isInstanceOf(BizException.class);
        verify(brand).permitsUse("MAIN", "ISSUER", d, "COPIED-VERSION", "CONSUMER", "S2");
        verifyNoInteractions(writer);
    }

    private EntitlementUseServiceImpl fixture(EntitlementDefinition d, String kind, boolean withBrand) {
        return fixture(d, kind, withBrand, "VERSION");
    }

    private EntitlementUseServiceImpl fixture(EntitlementDefinition d, String kind, boolean withBrand, String version) {
        var asset = new EntInstance(); asset.setInstanceNo("I1"); asset.setUserNo("U1");
        asset.setTemplateVersionNo(version);
        asset.setEntityNo("ISSUER"); asset.setKind(kind); asset.setStatus("ACTIVE"); asset.setVersion(0L);
        asset.setRemainingUnits(1); asset.setReservedUnits(0);
        asset.setEffectiveFrom(LocalDateTime.now().minusDays(1));
        asset.setEffectiveUntil(LocalDateTime.now().plusDays(1));
        asset.setRuleSnapshot(json.writeValueAsString(d));
        when(instances.selectOne(any())).thenReturn(asset);
        when(source.resolve(context, sourceRef)).thenReturn(new UseSourceFact("MAIN", sourceRef, "U1", "CONSUMER",
                "S2", "CNY", items, "a".repeat(64), "store", null, null));
        var beans = new StaticListableBeanFactory(); beans.addBean("source", source);
        if (withBrand) beans.addBean("brand", brand);
        return new EntitlementUseServiceImpl(instances, mock(EntUseIntentMapper.class),
                mock(EntUseProposalMapper.class),
                mock(EntPeriodBucketMapper.class), beans.getBeanProvider(EntitlementUseSourcePort.class),
                beans.getBeanProvider(SourceDecisionPort.class), mock(PayEntitlementFeatureGate.class), writer,
                mock(EntitlementSourceFence.class), mock(EntitlementReversalService.class),
                beans.getBeanProvider(StoredValueRedemptionService.class), json,
                beans.getBeanProvider(BrandCouponSourcePort.class));
    }

    private EntitlementDefinition definition(String mode) {
        return new EntitlementDefinition("coupon", "ORDER_COUPON", "MERCHANT", "CNY", "CASH", 100L,
                null, null, 0L, null, "COUNT", 1, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope(mode, List.of("SOURCE")), List.of("TARGETED"));
    }
}

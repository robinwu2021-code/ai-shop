package ai.neargo.shop.pay.entitlement.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.BrandCouponSourcePort;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrandCouponUseProofTest {
    @Test void brandScopeRequiresOneOpaqueSourceAndAnOrderCoupon() {
        var validator = new EntitlementRuleValidator();
        validator.validate(definition("ORDER_COUPON", "BRAND_STORES", List.of("SOURCE-1")));
        for (var refs : List.of(List.<String>of(), List.of("S1", "S2"))) {
            assertThatThrownBy(() -> validator.validate(definition("ORDER_COUPON", "BRAND_STORES", refs)))
                    .isInstanceOf(BizException.class);
        }
        for (String kind : List.of("STORE_EXCHANGE", "PAID_CARD")) {
            validator.validate(definition(kind, "STORES", List.of("S1")));
            assertThatThrownBy(() -> validator.validate(definition(kind, "BRAND_STORES", List.of("SOURCE-1"))))
                    .isInstanceOf(BizException.class);
        }
    }

    @Test void missingOrDeniedSourceCannotMintAProof() {
        var d = definition("ORDER_COUPON", "BRAND_STORES", List.of("SOURCE-1"));
        assertThatThrownBy(() -> BrandCouponUseProof.authorize(null, "MAIN", "ISSUER", d, "VERSION", "CONSUMER", "S2"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
        assertThatThrownBy(() -> BrandCouponUseProof.authorize(mock(BrandCouponSourcePort.class),
                "MAIN", "ISSUER", d, "VERSION", "CONSUMER", "S2")).isInstanceOf(BizException.class);
    }

    @Test void proofBindsFullDefinitionIssuerAndConsumerScope() {
        var d = definition("ORDER_COUPON", "BRAND_STORES", List.of("SOURCE-1"));
        var source = mock(BrandCouponSourcePort.class);
        when(source.permitsUse("MAIN", "ISSUER", d, "VERSION", "CONSUMER", "S2")).thenReturn(true);
        var proof = BrandCouponUseProof.authorize(source, "MAIN", "ISSUER", d, "VERSION", "CONSUMER", "S2");
        assertThat(proof.matches("MAIN", "ISSUER", d, "VERSION", "CONSUMER", "S2")).isTrue();
        assertThat(proof.matches("MAIN", "ISSUER", d, "COPIED-VERSION", "CONSUMER", "S2")).isFalse();
        assertThatThrownBy(() -> BrandCouponUseProof.authorize(source, "MAIN", "ISSUER", d,
                "COPIED-VERSION", "CONSUMER", "S2")).isInstanceOf(BizException.class);
        assertThat(proof.matches("MAIN", "OTHER", d, "VERSION", "CONSUMER", "S2")).isFalse();
        assertThat(proof.matches("MAIN", "ISSUER", d, "VERSION", "OTHER", "S2")).isFalse();
        assertThat(proof.matches("MAIN", "ISSUER", d, "VERSION", "CONSUMER", "S3")).isFalse();
        assertThat(proof.matches("MAIN", "ISSUER", definition("ORDER_COUPON", "BRAND_STORES", List.of("SOURCE-2")),
                "VERSION", "CONSUMER", "S2")).isFalse();
        assertThatThrownBy(() -> BrandCouponUseProof.authorize(source, "MAIN", "ISSUER",
                definition("PAID_CARD", "BRAND_STORES", List.of("SOURCE-1")), "VERSION", "CONSUMER", "S2"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> BrandCouponUseProof.authorize(source, "MAIN", "ISSUER",
                definition("ORDER_COUPON", "STORES", List.of("S2")), "VERSION", "CONSUMER", "S2"))
                .isInstanceOf(BizException.class);
    }

    private static EntitlementDefinition definition(String kind, String mode, List<String> refs) {
        return new EntitlementDefinition("coupon", kind, "MERCHANT", "CNY",
                "ORDER_COUPON".equals(kind) ? "CASH" : "SERVICE_UNITS", 100L, null, null,
                0L, null, "COUNT", 1, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30,
                        "PAID_CARD".equals(kind) ? "PAID" : "GRANTED", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope(mode, refs),
                List.of("PAID_CARD".equals(kind) ? "PAID_ORDER" : "TARGETED"));
    }
}

package ai.neargo.shop.pay.entitlement.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.BrandCouponSourcePort;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;

/** In-process proof only: caller JSON cannot construct the authority checked against the locked asset. */
public final class BrandCouponUseProof {
    private final String tenantNo;
    private final String issuerEntityNo;
    private final EntitlementDefinition definition;
    private final String templateVersionNo;
    private final String consumerEntityNo;
    private final String storeNo;

    private BrandCouponUseProof(String tenantNo, String issuerEntityNo, EntitlementDefinition definition,
                                String templateVersionNo, String consumerEntityNo, String storeNo) {
        this.tenantNo = tenantNo;
        this.issuerEntityNo = issuerEntityNo;
        this.definition = definition;
        this.templateVersionNo = templateVersionNo;
        this.consumerEntityNo = consumerEntityNo;
        this.storeNo = storeNo;
    }

    /** Obtain once outside the Pay transaction; immutable Core scope is rechecked locally under the asset lock. */
    public static BrandCouponUseProof authorize(BrandCouponSourcePort source, String tenantNo,
            String issuerEntityNo, EntitlementDefinition definition, String templateVersionNo,
            String consumerEntityNo, String storeNo) {
        if (!"ORDER_COUPON".equals(definition.kind()) || !"BRAND_STORES".equals(definition.redemptionScope().mode())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (source == null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if (!source.permitsUse(tenantNo, issuerEntityNo, definition, templateVersionNo, consumerEntityNo, storeNo)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return new BrandCouponUseProof(tenantNo, issuerEntityNo, definition, templateVersionNo, consumerEntityNo, storeNo);
    }

    public boolean matches(String tenant, String issuer, EntitlementDefinition rule, String version,
                           String consumer, String store) {
        return tenantNo.equals(tenant) && issuerEntityNo.equals(issuer) && definition.equals(rule)
                && templateVersionNo.equals(version) && consumerEntityNo.equals(consumer) && storeNo.equals(store);
    }
}

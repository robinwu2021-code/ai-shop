package ai.neargo.shop.pay.svc.client.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.client.EntitlementRemoteCalls;
import ai.neargo.shop.spi.entitlement.BrandCouponSourceInternal;
import ai.neargo.shop.spi.entitlement.BrandCouponSourcePort;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.AudienceSnapshot;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.beans.factory.ObjectProvider;

/** Standalone Pay resolves brand source authority through the existing authenticated Core endpoints. */
public final class CoreBrandCouponSourcePortImpl implements BrandCouponSourcePort {
    private final ObjectProvider<CoreEntitlementInternalApi> api;

    public CoreBrandCouponSourcePortImpl(ObjectProvider<CoreEntitlementInternalApi> api) {
        this.api = api;
    }

    @Override
    public void validateTemplate(String tenantNo, String issuerEntityNo, EntitlementDefinition definition) {
        var reply = EntitlementRemoteCalls.call(ServiceName.PLATFORM,
                () -> api.getObject().validateBrandCouponTemplate(
                        new BrandCouponSourceInternal.TemplateValidation(tenantNo, issuerEntityNo, definition)));
        if (!reply.valid()) throw BizException.of(ErrorCode.CONFLICT);
    }

    @Override
    public boolean permitsUse(String tenantNo, String issuerEntityNo, EntitlementDefinition definition,
                              String templateVersionNo, String consumerEntityNo, String storeNo) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,
                () -> api.getObject().validateBrandCouponUse(new BrandCouponSourceInternal.UseValidation(
                        tenantNo, issuerEntityNo, definition, templateVersionNo, consumerEntityNo, storeNo)))
                .permitted();
    }

    @Override
    public AudienceSnapshot audience(String tenantNo, String issuerEntityNo, EntitlementDefinition definition,
                                     String templateVersionNo, String audienceFactRef, String actorNo,
                                     String commandNo) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,
                () -> api.getObject().brandCouponAudience(new BrandCouponSourceInternal.AudienceQuery(
                        tenantNo, issuerEntityNo, definition, templateVersionNo, audienceFactRef, actorNo, commandNo)));
    }
}

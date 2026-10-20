package ai.neargo.shop.portal.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Authenticated read-only bridge; absent source implementation is unavailable, never an abort decision. */
@RestController
public class InternalLegacyCouponCheckoutEndpoint {
    private final ObjectProvider<LegacyCouponCheckoutSourcePort> sources;
    private final String token;
    public InternalLegacyCouponCheckoutEndpoint(ObjectProvider<LegacyCouponCheckoutSourcePort> sources,
            @Value("${shop.services.internal-token:}") String token) { this.sources=sources;this.token=token; }
    private LegacyCouponCheckoutSourcePort source() {
        var source=sources.getIfAvailable();
        if(source==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return source;
    }
    @PostMapping(EntitlementCoreInternal.LEGACY_CHECKOUT_SOURCE)
    public ResponseEntity<?> resolve(@RequestBody EntitlementCoreInternal.LegacyCheckoutQuery r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->source().resolve(r.context(),r.checkoutNo()));
    }
    @PostMapping(EntitlementCoreInternal.LEGACY_CHECKOUT_DECISION)
    public ResponseEntity<?> decision(@RequestBody EntitlementCoreInternal.LegacyCheckoutQuery r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->new EntitlementCoreInternal.LegacyCheckoutDecision(
                source().decision(r.context(),r.checkoutNo()).orElse(null)));
    }
    @PostMapping(EntitlementCoreInternal.LEGACY_CHECKOUT_REVERSAL)
    public ResponseEntity<?> reversal(@RequestBody EntitlementCoreInternal.LegacyCheckoutQuery r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->new EntitlementCoreInternal.LegacyCheckoutReversal(
                source().reversal(r.context(),r.checkoutNo()).orElse(null)));
    }
}

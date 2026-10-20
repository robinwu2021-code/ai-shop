package ai.neargo.shop.portal.internal;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class InternalLegacyCouponAudienceEndpoint {
    private final LegacyCouponAudiencePort audience;private final String token;
    public InternalLegacyCouponAudienceEndpoint(LegacyCouponAudiencePort audience,@Value("${shop.services.internal-token:}") String token) {this.audience=audience;this.token=token;}
    @PostMapping(EntitlementCoreInternal.LEGACY_COUPON_AUDIENCE)
    public ResponseEntity<?> resolve(@RequestBody EntitlementCoreInternal.LegacyAudience r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->audience.resolve(r.context(),r.query()));
    }
}

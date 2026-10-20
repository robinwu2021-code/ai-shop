package ai.neargo.shop.portal.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.spi.entitlement.LegacyCouponSourceCommitPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Read-only durable source proof. This endpoint cannot commit a source or import target assets. */
@RestController
public class InternalLegacyCouponSourceCommitEndpoint {
    private final ObjectProvider<LegacyCouponSourceCommitPort> source;
    private final String token;
    public InternalLegacyCouponSourceCommitEndpoint(ObjectProvider<LegacyCouponSourceCommitPort> source,
            @Value("${shop.services.internal-token:}") String token) { this.source=source;this.token=token; }
    @PostMapping(EntitlementCoreInternal.LEGACY_MIGRATION_COMMIT)
    public ResponseEntity<?> read(@RequestBody EntitlementCoreInternal.MigrationCommitQuery request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->{
            var owner=source.getIfAvailable();
            if(owner==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            var proof=owner.decided(request.context(),request.template());
            if(proof==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            return new EntitlementCoreInternal.MigrationCommitReply(proof.orElse(null));
        });
    }
}

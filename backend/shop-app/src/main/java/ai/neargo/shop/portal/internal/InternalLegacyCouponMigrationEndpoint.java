package ai.neargo.shop.portal.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort;
import ai.neargo.shop.spi.entitlement.LegacyCouponMigrationManifestPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Internal source evidence only; transport exposes neither an import nor an ownership activation operation. */
@RestController
public class InternalLegacyCouponMigrationEndpoint {
    private final ObjectProvider<LegacyCouponFrozenSnapshotPort> source;
    private final String token;
    private final ObjectProvider<LegacyCouponMigrationManifestPort> manifests;
    public InternalLegacyCouponMigrationEndpoint(ObjectProvider<LegacyCouponFrozenSnapshotPort> source,
            @Value("${shop.services.internal-token:}") String token,
            ObjectProvider<LegacyCouponMigrationManifestPort> manifests) {
        this.source=source; this.token=token; this.manifests=manifests;
    }
    @PostMapping(EntitlementCoreInternal.FROZEN_LEGACY_SNAPSHOT)
    public ResponseEntity<?> snapshot(@RequestBody EntitlementCoreInternal.FrozenSnapshotQuery request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,() -> {
            var owner=source.getIfAvailable();
            if(owner==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            return owner.exportFrozen(request.context(),request.query());
        });
    }
    @PostMapping(EntitlementCoreInternal.FROZEN_LEGACY_MANIFEST)
    public ResponseEntity<?> manifest(@RequestBody EntitlementCoreInternal.FrozenSnapshotQuery request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,() -> {
            var owner=manifests.getIfAvailable();
            if(owner==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            return owner.exportManifest(request.context(),request.query());
        });
    }
}

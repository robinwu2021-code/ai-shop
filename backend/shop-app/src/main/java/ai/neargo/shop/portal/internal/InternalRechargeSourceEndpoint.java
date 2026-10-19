package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal.RechargeQuery;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal.SourceQuery;
import ai.neargo.shop.spi.storedvalue.RechargeSourcePort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalRechargeSourceEndpoint {
    private final RechargeSourcePort sources;
    private final String token;
    public InternalRechargeSourceEndpoint(RechargeSourcePort sources,
                                         @Value("${shop.services.internal-token:}") String token) {
        this.sources = sources; this.token = token;
    }
    @PostMapping(EntitlementCoreInternal.RECHARGE_SOURCE)
    public ResponseEntity<?> source(@RequestBody SourceQuery r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> sources.resolve(r.context(), r.source()));
    }
    @PostMapping(EntitlementCoreInternal.RECHARGE_BY_NO)
    public ResponseEntity<?> byRecharge(@RequestBody RechargeQuery r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> sources.byRechargeNo(r.context(), r.rechargeNo()));
    }
}

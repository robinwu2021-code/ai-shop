package ai.neargo.shop.portal.internal;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.spi.entitlement.EntitlementSaleSourcePort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
/** Core is the sole owner of frozen sale identity and pending/cancelled order truth. */
@RestController
public class InternalSaleSourceEndpoint {
    private final List<EntitlementSaleSourcePort> sources;
    private final String token;
    public InternalSaleSourceEndpoint(List<EntitlementSaleSourcePort> sources,
            @Value("${shop.services.internal-token:}") String token) { this.sources=sources; this.token=token; }
    @PostMapping(EntitlementCoreInternal.SALE_SOURCE)
    public ResponseEntity<?> source(@RequestBody EntitlementCoreInternal.SaleSourceQuery r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER, required=false) String given) {
        return InternalServiceResponses.invoke(token,given, () -> {
            var owners=sources.stream().filter(p -> p.supports(r.command().sourceRef())).toList();
            if (owners.size()!=1) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            return owners.getFirst().saleSource(r.context(),r.command());
        });
    }
}

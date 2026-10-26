package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.trade.PricingFeeScopeInternalApi;
import ai.neargo.shop.spi.trade.PricingFeeScopePort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token admission precedes any read: the body names an arbitrary entity+store, so without the
 * shared internal token anyone reaching shop-app could list that store's fee adjustments.
 * Body checks run after the token (no {@code @Valid}), so an unauthenticated caller always sees 401.
 */
@RestController
public class InternalPricingFeeScopeEndpoint {
    private final PricingFeeScopePort fees;
    private final String token;

    public InternalPricingFeeScopeEndpoint(PricingFeeScopePort fees,
            @Value("${shop.services.internal-token:}") String token) {
        this.fees = fees;
        this.token = token;
    }

    @PostMapping("/internal/trade/pricing-fees/pending")
    public ResponseEntity<?> pending(@RequestBody PricingFeeScopeInternalApi.Query q,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> {
            require(q != null && q.scope() != null && q.scopeNo() != null && !q.scopeNo().isBlank());
            return fees.pendingAdjustments(q.scope(), q.scopeNo());
        });
    }

    @PostMapping("/internal/trade/pricing-fees/original")
    public ResponseEntity<?> original(@RequestBody PricingFeeScopeInternalApi.OriginalQuery q,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> {
            require(q != null && q.scope() != null && q.applicationNo() != null && !q.applicationNo().isBlank());
            return fees.originalReference(q.scope(), q.applicationNo());
        });
    }

    private static void require(boolean ok) {
        if (!ok) throw new IllegalArgumentException("invalid pricing-fee query");
    }
}

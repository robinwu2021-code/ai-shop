package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.trade.IndustryFulfillmentPort;
import ai.neargo.shop.spi.trade.PricingFeeScopeInternalApi;
import ai.neargo.shop.spi.trade.PricingFeeScopePort;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class InternalPricingFeeScopeEndpoint {
    private final PricingFeeScopePort fees;

    public InternalPricingFeeScopeEndpoint(PricingFeeScopePort fees) {
        this.fees = fees;
    }

    @PostMapping("/internal/trade/pricing-fees/pending")
    public List<PricingFeeScopePort.AdjustmentAuthority> pending(
            @Valid @RequestBody PricingFeeScopeInternalApi.Query q) {
        return fees.pendingAdjustments(q.scope(), q.scopeNo());
    }

    @PostMapping("/internal/trade/pricing-fees/original")
    public IndustryFulfillmentPort.Reference original(
            @Valid @RequestBody PricingFeeScopeInternalApi.OriginalQuery q) {
        return fees.originalReference(q.scope(), q.applicationNo());
    }
}

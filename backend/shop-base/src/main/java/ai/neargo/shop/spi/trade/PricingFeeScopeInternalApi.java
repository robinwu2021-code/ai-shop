package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

public interface PricingFeeScopeInternalApi {
    record Query(@Valid @NotNull Scope scope, @NotBlank String scopeNo) {}

    record OriginalQuery(@Valid @NotNull Scope scope, @NotBlank String applicationNo) {}

    @PostExchange("/internal/trade/pricing-fees/pending")
    List<PricingFeeScopePort.AdjustmentAuthority> pending(@RequestBody Query query);

    @PostExchange("/internal/trade/pricing-fees/original")
    IndustryFulfillmentPort.Reference original(@RequestBody OriginalQuery query);
}

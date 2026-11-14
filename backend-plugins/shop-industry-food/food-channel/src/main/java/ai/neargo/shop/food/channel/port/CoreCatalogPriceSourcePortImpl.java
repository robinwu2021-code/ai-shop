package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourceInternalApi;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.time.Clock;
import java.util.function.Supplier;

/**
 * One original read per attempt; missing authority or ambiguous transport never falls back to
 * CN/local offers.
 */
public class CoreCatalogPriceSourcePortImpl implements CatalogPriceSourcePort {
    private final Supplier<CatalogPriceSourceInternalApi> api;
    private final Clock clock;

    public CoreCatalogPriceSourcePortImpl(Supplier<CatalogPriceSourceInternalApi> api) {
        this(api, Clock.systemUTC());
    }

    public CoreCatalogPriceSourcePortImpl(
            Supplier<CatalogPriceSourceInternalApi> api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @Override
    public ScopeFacts scope(MarketScope scope) {
        CatalogPriceSourceFacts.validateMarketScope(scope);
        var facts = ServiceCalls.call(ServiceName.PLATFORM, () -> api.get().scope(scope));
        CatalogPriceSourceFacts.validateScopeFacts(scope, facts, clock.instant());
        return facts;
    }

    @Override
    public MarketFacts market(MarketScope scope) {
        CatalogPriceSourceFacts.validateMarketScope(scope);
        try {
            var result = ServiceCalls.call(ServiceName.PLATFORM, () -> api.get().market(scope));
            CatalogPriceSourceFacts.validateMarketFacts(scope, result, clock.instant());
            return result;
        } catch (ServiceCallException failure) {
            var code =
                    switch (failure.statusCode()) {
                        case 400 -> ErrorCode.BAD_REQUEST;
                        case 403 -> ErrorCode.FORBIDDEN;
                        case 404 -> ErrorCode.NOT_FOUND;
                        case 409 -> ErrorCode.CONFLICT;
                        case 501, 503 -> ErrorCode.PRICING_OWNER_UNSUPPORTED;
                        default -> null;
                    };
            if (code != null) throw BizException.of(code);
            throw failure;
        }
    }

    @Override
    public Facts capture(Request request) {
        CatalogPriceSourceFacts.validateRequest(request);
        try {
            var result = ServiceCalls.call(ServiceName.PLATFORM, () -> api.get().capture(request));
            CatalogPriceSourceFacts.validate(request, result, clock.instant());
            return result;
        } catch (ServiceCallException failure) {
            ErrorCode code =
                    switch (failure.statusCode()) {
                        case 400 -> ErrorCode.BAD_REQUEST;
                        case 403 -> ErrorCode.FORBIDDEN;
                        case 404 -> ErrorCode.NOT_FOUND;
                        case 501, 503 -> ErrorCode.PRICING_OWNER_UNSUPPORTED;
                        case 409 -> ErrorCode.CONFLICT;
                        default -> null;
                    };
            if (code != null) throw BizException.of(code);
            throw failure;
        }
    }
}

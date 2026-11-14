package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.Facts;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.Query;
import ai.neargo.shop.food.policy.MenuCatalogPricePolicy;
import ai.neargo.shop.food.service.MenuCatalogPriceService;
import ai.neargo.shop.food.spi.MenuCatalogPriceInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * One authenticated read per capture; original proof and complete prices are revalidated, never
 * locally defaulted.
 */
public class RemoteMenuCatalogPriceService implements MenuCatalogPriceService {
    private final MenuCatalogPriceInternalApi api;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public RemoteMenuCatalogPriceService(MenuCatalogPriceInternalApi api) {
        this(api, Clock.systemUTC());
    }

    public RemoteMenuCatalogPriceService(MenuCatalogPriceInternalApi api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @Override
    public Facts capture(Query query) {
        MenuCatalogPricePolicy.validateQuery(query);
        try {
            var facts = ServiceCalls.call(ServiceName.FOOD, () -> api.capture(query));
            MenuCatalogPricePolicy.validate(json, query, facts, clock.instant());
            return facts;
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
}

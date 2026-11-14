package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Facts;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Query;
import ai.neargo.shop.food.policy.StoreCalendarSourcePolicy;
import ai.neargo.shop.food.service.StoreCalendarSourceService;
import ai.neargo.shop.food.spi.StoreCalendarSourceInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.time.Clock;

/**
 * One authenticated read per capture; original calendar and effective policy timeline are
 * revalidated, never locally defaulted.
 */
public class RemoteStoreCalendarSourceService implements StoreCalendarSourceService {
    private final StoreCalendarSourceInternalApi api;
    private final Clock clock;

    public RemoteStoreCalendarSourceService(StoreCalendarSourceInternalApi api) {
        this(api, Clock.systemUTC());
    }

    public RemoteStoreCalendarSourceService(StoreCalendarSourceInternalApi api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @Override
    public Facts capture(Query query) {
        StoreCalendarSourcePolicy.validateQuery(query);
        try {
            var facts = ServiceCalls.call(ServiceName.FOOD, () -> api.capture(query));
            StoreCalendarSourcePolicy.validate(query, facts, clock.instant());
            return facts;
        } catch (ServiceCallException failure) {
            var code =
                    switch (failure.statusCode()) {
                        case 400 -> ErrorCode.BAD_REQUEST;
                        case 403 -> ErrorCode.FORBIDDEN;
                        case 404 -> ErrorCode.NOT_FOUND;
                        case 409 -> ErrorCode.CONFLICT;
                        default -> null;
                    };
            if (code != null) throw BizException.of(code);
            throw failure;
        }
    }
}

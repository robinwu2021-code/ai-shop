package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.service.FoodMaintenanceService;
import ai.neargo.shop.food.spi.FoodMaintenanceInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

/** Standalone: the platform job runtime asks food-server (the original FOOD host) to run one bounded task. */
public class RemoteFoodMaintenanceService implements FoodMaintenanceService {
    private final FoodMaintenanceInternalApi api;
    public RemoteFoodMaintenanceService(FoodMaintenanceInternalApi api) { this.api = api; }
    @Override public Report run(Task task, int limit) {
        var request = new FoodMaintenanceInternalApi.Run(task, limit);
        Report result = ServiceCalls.call(ServiceName.FOOD, () -> api.run(request));
        if (result == null || result.processed() + result.pending() > limit) {
            throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        }
        return result;
    }
}

package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.DishResultVO;
import ai.neargo.shop.food.dto.SaveDishReq;
import ai.neargo.shop.food.service.DishService;
import ai.neargo.shop.food.spi.DishInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** One typed call per attempt; uncertain writes are not automatically retried. */
public class RemoteDishService implements DishService {
    private final DishInternalApi api;
    public RemoteDishService(DishInternalApi api) { this.api = api; }

    @Override
    public DishResultVO saveDish(String entity, String store, String goods, String actor, SaveDishReq req, String key) {
        var result = invoke(() -> api.saveDish(new DishInternalApi.SaveCommand(entity, store, goods, actor, key, req)));
        if (result == null || result.dish() == null || !goods.equals(result.dish().goodsNo())
                || result.dish().configVersion() < 1 || result.dish().version() < 0
                || result.dish().presentation() == null || result.dish().measurement() == null) {
            throw new IllegalStateException("Invalid dish configuration receipt");
        }
        return result;
    }

    private <T> T invoke(Supplier<T> call) {
        try {
            return ServiceCalls.call(ServiceName.FOOD, call);
        } catch (ServiceCallException failure) {
            ErrorCode business = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if (business != null) throw BizException.of(business);
            throw failure;
        }
    }
}

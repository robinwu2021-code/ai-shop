package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.DishCatalogItemVO;
import ai.neargo.shop.food.dto.DishDetailResultVO;
import ai.neargo.shop.food.dto.ListDishesQuery;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.DishResultVO;
import ai.neargo.shop.food.dto.SaveDishReq;
import ai.neargo.shop.food.service.DishService;
import ai.neargo.shop.food.spi.DishInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;
import java.util.HashSet;

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

    @Override
    public PageData<DishCatalogItemVO> listDishes(String entity, String store, ListDishesQuery query) {
        var result = invoke(() -> api.listDishes(new DishInternalApi.Lookup(entity, store, query)));
        var seen = new HashSet<String>();
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        if (result == null || result.records() == null || result.total() < result.records().size()
                || result.page() != page || result.size() != size || result.records().size() > size
                || result.records().stream().anyMatch(r -> r == null || r.dish() == null || r.product() == null
                    || !entity.equals(r.product().entityNo()) || !store.equals(r.product().storeNo())
                    || !r.dish().goodsNo().equals(r.product().goodsNo()) || !seen.add(r.dish().goodsNo()))) {
            throw new IllegalStateException("Invalid dish list receipt");
        }
        return result;
    }

    @Override
    public DishDetailResultVO getDish(String entity, String store, String goods) {
        var result = invoke(() -> api.getDish(new DishInternalApi.Identity(entity, store, goods)));
        if (result == null || result.dish() == null || result.coreCatalog() == null
                || !goods.equals(result.dish().goodsNo()) || !goods.equals(result.coreCatalogRef())
                || !goods.equals(result.coreCatalog().goodsNo()) || !entity.equals(result.coreCatalog().entityNo())
                || !store.equals(result.coreCatalog().storeNo())) {
            throw new IllegalStateException("Invalid dish detail receipt");
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

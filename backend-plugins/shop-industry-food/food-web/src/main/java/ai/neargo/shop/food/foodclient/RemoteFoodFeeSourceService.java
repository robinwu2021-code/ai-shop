package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.FoodFeeSourceContracts;
import ai.neargo.shop.food.service.FoodFeeSourceService;
import ai.neargo.shop.food.spi.BusinessOperationsInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.List;
import java.util.function.Supplier;

public class RemoteFoodFeeSourceService implements FoodFeeSourceService {
    private final BusinessOperationsInternalApi api;

    public RemoteFoodFeeSourceService(BusinessOperationsInternalApi api) {
        this.api = api;
    }

    @Override
    public FoodFeeSourceContracts.BindingVO bind(
            String entity,
            String store,
            String actor,
            FoodFeeSourceContracts.BindReq request,
            String key) {
        return invoke(
                () ->
                        api.foodFeeSourceBind(
                                new BusinessOperationsInternalApi.FoodFeeSourceBindCommand(
                                        entity, store, actor, request, key)));
    }

    @Override
    public List<FoodFeeSourceContracts.BindingVO> bindings(
            String entity, String store, String check) {
        return invoke(
                () ->
                        api.foodFeeSourceBindings(
                                new BusinessOperationsInternalApi.FoodFeeSourceBindingsCommand(
                                        entity, store, check)));
    }

    private static <T> T invoke(Supplier<T> call) {
        try {
            T result = ServiceCalls.call(ServiceName.FOOD, call);
            if (result == null) throw new IllegalStateException("Missing FOOD operations receipt");
            return result;
        } catch (ServiceCallException failure) {
            throw switch (failure.statusCode()) {
                case 400 -> BizException.of(ErrorCode.BAD_REQUEST);
                case 403 -> BizException.of(ErrorCode.FORBIDDEN);
                case 404 -> BizException.of(ErrorCode.NOT_FOUND);
                case 409 -> BizException.of(ErrorCode.CONFLICT);
                default -> failure;
            };
        }
    }
}

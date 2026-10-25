package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ProductionContracts;
import ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO;
import ai.neargo.shop.food.dto.ProductionContracts.ListQuery;
import ai.neargo.shop.food.service.ProductionConsumptionService;
import ai.neargo.shop.food.spi.ProductionConsumptionInternalApi;
import ai.neargo.shop.food.spi.ProductionConsumptionInternalApi.Command;
import ai.neargo.shop.food.spi.ProductionConsumptionInternalApi.ListCommand;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.function.Supplier;

/** FOOD host owns durable recovery; no remote adapter manufactures inventory results. */
public class RemoteProductionConsumptionService implements ProductionConsumptionService {
    private final ProductionConsumptionInternalApi api;

    public RemoteProductionConsumptionService(ProductionConsumptionInternalApi api) {
        this.api = api;
    }

    @Override
    public ConsumptionVO consume(String entity, String store, String actor, String event) {
        return call(() -> api.consume(new Command(entity, store, actor, event)));
    }

    @Override
    public PageData<ConsumptionVO> list(
            String entity, String store, String actor, ListQuery query) {
        return call(() -> api.list(new ListCommand(entity, store, actor, query)));
    }

    @Override
    public ConsumptionVO get(String entity, String store, String actor, String event) {
        return call(() -> api.get(new Command(entity, store, actor, event)));
    }

    @Override
    public ConsumptionVO adjust(
            String e,
            String s,
            String a,
            String task,
            ProductionContracts.AdjustmentReq req,
            String key) {
        return call(
                () ->
                        api.adjust(
                                new ProductionConsumptionInternalApi.AdjustmentCommand(
                                        e, s, a, task, req, key)));
    }

    @Override
    public ConsumptionVO reverse(
            String e,
            String s,
            String a,
            String event,
            ProductionContracts.ReverseReq req,
            String key) {
        return call(
                () ->
                        api.reverse(
                                new ProductionConsumptionInternalApi.ReverseCommand(
                                        e, s, a, event, req, key)));
    }

    private <T> T call(Supplier<T> operation) {
        try {
            return ServiceCalls.call(ServiceName.FOOD, operation);
        } catch (ServiceCallException failure) {
            ErrorCode code =
                    switch (failure.statusCode()) {
                        case 400 -> ErrorCode.BAD_REQUEST;
                        case 403 -> ErrorCode.FORBIDDEN;
                        case 404 -> ErrorCode.NOT_FOUND;
                        case 409 -> ErrorCode.CONFLICT;
                        case 422 -> ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE;
                        default -> null;
                    };
            if (code != null) throw BizException.of(code);
            throw failure;
        }
    }
}

package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.ServiceSlotContracts;
import ai.neargo.shop.food.service.ServiceSlotService;
import ai.neargo.shop.food.spi.BusinessOperationsInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.function.Supplier;

public class RemoteServiceSlotService implements ServiceSlotService {
    private final BusinessOperationsInternalApi api;

    public RemoteServiceSlotService(BusinessOperationsInternalApi api) {
        this.api = api;
    }

    @Override
    public ServiceSlotContracts.SlotVO create(
            String entity,
            String store,
            String actor,
            ServiceSlotContracts.CreateReq request,
            String key) {
        return invoke(
                () ->
                        api.serviceSlotCreate(
                                new BusinessOperationsInternalApi.ServiceSlotCreateCommand(
                                        entity, store, actor, request, key)));
    }

    @Override
    public ServiceSlotContracts.SlotVO get(String entity, String store, String slot) {
        return invoke(
                () ->
                        api.serviceSlotGet(
                                new BusinessOperationsInternalApi.ServiceSlotGetCommand(
                                        entity, store, slot)));
    }

    @Override
    public ServiceSlotContracts.ClaimVO reserve(
            String entity,
            String store,
            String actor,
            ServiceSlotContracts.ClaimReq request,
            String key) {
        return invoke(
                () ->
                        api.serviceSlotReserve(
                                new BusinessOperationsInternalApi.ServiceSlotReserveCommand(
                                        entity, store, actor, request, key)));
    }

    @Override
    public ServiceSlotContracts.ClaimVO transition(
            String entity,
            String store,
            String actor,
            String claim,
            long version,
            String action,
            String key) {
        return invoke(
                () ->
                        api.serviceSlotTransition(
                                new BusinessOperationsInternalApi.ServiceSlotTransitionCommand(
                                        entity, store, actor, claim, version, action, key)));
    }

    @Override
    public boolean dispatchDue(String entity, String store, String sourceNo) {
        return invoke(
                () ->
                        api.serviceSlotDispatchDue(
                                new BusinessOperationsInternalApi.ServiceSlotDispatchDueCommand(
                                        entity, store, sourceNo)));
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

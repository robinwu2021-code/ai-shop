package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.CaseVO;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Evidence;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Source;
import ai.neargo.shop.food.dto.FoodSupportContracts.ReadinessVO;
import ai.neargo.shop.food.service.FoodSupportService;
import ai.neargo.shop.food.spi.FoodSupportInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.List;
import java.util.function.Supplier;

/** Carries only the scope already authorized by the platform protocol. */
public final class RemoteFoodSupportService implements FoodSupportService {
    private final FoodSupportInternalApi api;

    public RemoteFoodSupportService(FoodSupportInternalApi api) {
        this.api = api;
    }

    @Override
    public ReadinessVO readiness() {
        return call(() -> api.readiness(new FoodSupportInternalApi.ReadinessQuery()));
    }

    @Override
    public List<CaseVO> cases(String entityNo, String storeNo, String status) {
        return call(
                () -> api.cases(new FoodSupportInternalApi.CasesQuery(entityNo, storeNo, status)));
    }

    @Override
    public Evidence evidence(String entityNo, String storeNo, Source source) {
        return call(
                () ->
                        api.evidence(
                                new FoodSupportInternalApi.SourceQuery(entityNo, storeNo, source)));
    }

    private <T> T call(Supplier<T> action) {
        try {
            return ServiceCalls.call(ServiceName.FOOD, action);
        } catch (ServiceCallException failure) {
            ErrorCode code =
                    switch (failure.statusCode()) {
                        case 400 -> ErrorCode.BAD_REQUEST;
                        case 403 -> ErrorCode.FORBIDDEN;
                        case 404 -> ErrorCode.NOT_FOUND;
                        case 409 -> ErrorCode.CONFLICT;
                        default -> null;
                    };
            if (code != null) {
                throw BizException.of(code);
            }
            throw failure;
        }
    }
}

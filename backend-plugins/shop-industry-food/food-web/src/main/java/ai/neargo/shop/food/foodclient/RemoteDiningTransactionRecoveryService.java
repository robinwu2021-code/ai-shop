package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.service.DiningTransactionRecoveryService;
import ai.neargo.shop.food.spi.DiningTransactionRecoveryInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

/** Platform job runtime invokes the FOOD source scanner; the body contains no buyer or financial instruction. */
public class RemoteDiningTransactionRecoveryService implements DiningTransactionRecoveryService {
    private final DiningTransactionRecoveryInternalApi api;
    public RemoteDiningTransactionRecoveryService(DiningTransactionRecoveryInternalApi api) { this.api = api; }
    @Override public Report recoverDue(int limit) {
        var request = new DiningTransactionRecoveryInternalApi.Scan(limit);
        Report result = ServiceCalls.call(ServiceName.FOOD, () -> api.recoverDue(request));
        if (result == null || result.claimed() < 0 || result.claimed() > limit || result.complete() < 0
                || result.pending() < 0 || result.failed() < 0
                || result.complete() + result.pending() + result.failed() != result.claimed()) {
            throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        }
        return result;
    }
}

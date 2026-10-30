package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

final class FoodStockRemoteCalls {
    private FoodStockRemoteCalls() {}
    static <T> T invoke(Supplier<T> call) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("FOOD stock remote calls must not hold host transactions");
        try {
            T result=ServiceCalls.call(ServiceName.FOOD,call);
            if(result==null)throw new IllegalStateException("Missing FOOD stock response");
            return result;
        } catch(ServiceCallException failure) {
            throw switch(failure.statusCode()) {
                case 400 -> BizException.of(ErrorCode.BAD_REQUEST);
                case 403 -> BizException.of(ErrorCode.FORBIDDEN);
                case 404 -> BizException.of(ErrorCode.NOT_FOUND);
                case 409 -> BizException.of(ErrorCode.CONFLICT);
                default -> failure;
            };
        }
    }
}

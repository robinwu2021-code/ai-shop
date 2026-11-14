package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.KitchenReturnContracts.HandleReq;
import ai.neargo.shop.food.dto.KitchenReturnContracts.HandledVO;
import ai.neargo.shop.food.dto.KitchenReturnContracts.ReturnKitchenVO;
import ai.neargo.shop.food.service.KitchenReturnService;
import ai.neargo.shop.food.spi.KitchenReturnInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Reuses original request identity when FOOD is a remote service. */
public class RemoteKitchenReturnService implements KitchenReturnService {
    private final KitchenReturnInternalApi api;
    public RemoteKitchenReturnService(KitchenReturnInternalApi api) { this.api=api; }
    @Override public ReturnKitchenVO get(String entity,String store,String number) {
        return invoke(() -> api.get(new KitchenReturnInternalApi.Query(entity,store,number)));
    }
    @Override public HandledVO handle(String entity,String store,String actor,String number,HandleReq req,String key) {
        return invoke(() -> api.handle(new KitchenReturnInternalApi.Command(entity,store,actor,number,key,req)));
    }
    private static <T> T invoke(Supplier<T> call) {
        try {
            var result=ServiceCalls.call(ServiceName.FOOD,call);
            if (result==null) throw new IllegalStateException("Missing FOOD kitchen return receipt"); return result;
        } catch (ServiceCallException failure) {
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

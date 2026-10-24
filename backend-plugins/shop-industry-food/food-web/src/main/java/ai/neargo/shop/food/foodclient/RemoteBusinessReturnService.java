package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BusinessReturnContracts.CancelReq;
import ai.neargo.shop.food.dto.BusinessReturnContracts.CreateReq;
import ai.neargo.shop.food.dto.BusinessReturnContracts.ReturnVO;
import ai.neargo.shop.food.service.BusinessReturnService;
import ai.neargo.shop.food.spi.BusinessReturnInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Protocol adapter forwards the original business key and preserves source/quantity conflicts. */
public class RemoteBusinessReturnService implements BusinessReturnService {
    private final BusinessReturnInternalApi api;
    public RemoteBusinessReturnService(BusinessReturnInternalApi api) { this.api=api; }
    @Override public ReturnVO create(String entity,String store,String actor,String batch,CreateReq request,String key) {
        return invoke(() -> api.create(new BusinessReturnInternalApi.CreateCommand(entity,store,actor,batch,request,key)));
    }
    @Override public ReturnVO get(String entity,String store,String number) {
        return invoke(() -> api.get(new BusinessReturnInternalApi.Lookup(entity,store,number)));
    }
    @Override public ReturnVO cancel(String entity,String store,String actor,String number,CancelReq request,String key) {
        return invoke(() -> api.cancel(new BusinessReturnInternalApi.CancelCommand(entity,store,actor,number,request,key)));
    }
    private static <T> T invoke(Supplier<T> call) {
        try {
            T result=ServiceCalls.call(ServiceName.FOOD,call);
            if (result==null) throw new IllegalStateException("Missing FOOD business return response");return result;
        } catch (ServiceCallException failure) {
            throw switch(failure.statusCode()) {
                case 400 -> BizException.of(ErrorCode.BAD_REQUEST);
                case 403 -> BizException.of(ErrorCode.FORBIDDEN);
                case 404 -> BizException.of(ErrorCode.NOT_FOUND);
                case 409 -> BizException.of(ErrorCode.CONFLICT);
                case 422 -> BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
                default -> failure;
            };
        }
    }
}

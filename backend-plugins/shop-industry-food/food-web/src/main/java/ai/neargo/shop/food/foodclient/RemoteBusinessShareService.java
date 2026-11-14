package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BusinessShareContracts.CancelReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.ConfirmReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.CreateReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO;
import ai.neargo.shop.food.service.BusinessShareService;
import ai.neargo.shop.food.spi.BusinessShareInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Remote transport never substitutes business confirmation for a core financial receipt. */
public class RemoteBusinessShareService implements BusinessShareService {
    private final BusinessShareInternalApi api;
    public RemoteBusinessShareService(BusinessShareInternalApi api) { this.api=api; }
    @Override public PlanVO create(String entity,String store,String actor,String check,CreateReq request,String key) {
        return invoke(() -> api.create(new BusinessShareInternalApi.CreateCommand(entity,store,actor,check,request,key)));
    }
    @Override public PlanVO get(String entity,String store,String check,String plan) {
        return invoke(() -> api.get(new BusinessShareInternalApi.Lookup(entity,store,check,plan)));
    }
    @Override public PlanVO confirm(String entity,String store,String actor,String check,String plan,
            ConfirmReq request,String key) {
        return invoke(() -> api.confirm(new BusinessShareInternalApi.ConfirmCommand(
                entity,store,actor,check,plan,request,key)));
    }
    @Override public PlanVO cancel(String entity,String store,String actor,String check,String plan,
            CancelReq request,String key) {
        return invoke(() -> api.cancel(new BusinessShareInternalApi.CancelCommand(
                entity,store,actor,check,plan,request,key)));
    }
    private static <T> T invoke(Supplier<T> call) {
        try {
            T result=ServiceCalls.call(ServiceName.FOOD,call);
            if (result==null) throw new IllegalStateException("Missing FOOD business share response");return result;
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

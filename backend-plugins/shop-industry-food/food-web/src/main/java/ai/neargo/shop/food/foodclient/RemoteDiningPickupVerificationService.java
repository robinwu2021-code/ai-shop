package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.DiningPickupContracts.PickupVerificationVO;
import ai.neargo.shop.food.dto.DiningPickupContracts.VerifyPickupReq;
import ai.neargo.shop.food.service.DiningPickupVerificationService;
import ai.neargo.shop.food.spi.DiningPickupVerificationInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Remote FOOD transport preserves command identity and never retries with a replacement pickup operation. */
public class RemoteDiningPickupVerificationService implements DiningPickupVerificationService {
    private final DiningPickupVerificationInternalApi api;
    public RemoteDiningPickupVerificationService(DiningPickupVerificationInternalApi api) { this.api=api; }
    @Override public PickupVerificationVO verify(String entity,String store,String actor,String batch,
            VerifyPickupReq req,String key) {
        return invoke(batch,() -> api.verify(new DiningPickupVerificationInternalApi.Command(entity,store,actor,
                batch,key,req)));
    }
    @Override public PickupVerificationVO get(String entity,String store,String actor,String batch,String key) {
        return invoke(batch,() -> api.get(new DiningPickupVerificationInternalApi.Query(entity,store,actor,batch,key)));
    }
    private static PickupVerificationVO invoke(String batch,Supplier<PickupVerificationVO> call) {
        try {
            var result=ServiceCalls.call(ServiceName.FOOD,call);
            if (result==null || !batch.equals(result.batchNo()) || result.originalFulfillment()==null) {
                throw new IllegalStateException("Invalid FOOD pickup receipt");
            }
            return result;
        } catch (ServiceCallException failure) {
            throw switch(failure.statusCode()) {
                case 400 -> BizException.of(ErrorCode.BAD_REQUEST);
                case 403 -> BizException.of(ErrorCode.FORBIDDEN);
                case 404 -> BizException.of(ErrorCode.NOT_FOUND);
                case 409 -> BizException.of(ErrorCode.CONFLICT);
                case 503 -> BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
                default -> failure;
            };
        }
    }
}

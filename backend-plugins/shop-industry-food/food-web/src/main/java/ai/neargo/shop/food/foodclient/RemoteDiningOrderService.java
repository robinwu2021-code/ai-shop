package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CreateDiningOrderReq;
import ai.neargo.shop.food.dto.DiningOrderBatchVO;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.spi.DiningOrderInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Ambiguous placement is returned to the original source intent; this client never invents a second key. */
public class RemoteDiningOrderService implements DiningOrderService {
    private final DiningOrderInternalApi api;
    public RemoteDiningOrderService(DiningOrderInternalApi api) { this.api = api; }
    @Override public DiningOrderBatchVO recoverOriginal(String entity, String store, String number) {
        var result = invoke(() -> api.recoverOriginal(new DiningOrderInternalApi.Original(entity, store, number)));
        if (!store.equals(result.storeNo()) || !number.equals(result.batchNo())) throw malformed();
        return result;
    }
    @Override public DiningOrderBatchVO customerPlace(String store, CartAccess access, CreateDiningOrderReq r,
            String key) {
        var result = invoke(() -> api.customerPlace(new DiningOrderInternalApi.Place(store, access, r, key)));
        if (!store.equals(result.storeNo()) || !r.cartNo().equals(result.cartNo())
                || r.version() != result.cartVersion() || !r.quoteNo().equals(result.quoteNo())) throw malformed();
        return result;
    }
    @Override public DiningOrderBatchVO customerGet(CartAccess access, String batch) {
        var result = invoke(() -> api.customerGet(new DiningOrderInternalApi.CustomerGet(access, batch)));
        if (!batch.equals(result.batchNo())) throw malformed(); return result;
    }
    @Override public DiningOrderBatchVO staffGet(String entity, String store, String actor, String batch) {
        var result = invoke(() -> api.staffGet(new DiningOrderInternalApi.StaffGet(entity, store, actor, batch)));
        if (!store.equals(result.storeNo()) || !batch.equals(result.batchNo())) throw malformed(); return result;
    }
    private static DiningOrderBatchVO invoke(Supplier<DiningOrderBatchVO> call) {
        try {
            var result = ServiceCalls.call(ServiceName.FOOD, call::get);
            if (result == null || result.batchNo() == null || result.cartNo() == null || result.status() == null
                    || result.cartVersion() < 0 || result.version() < 0) throw malformed();
            return result;
        } catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                case 503 -> ErrorCode.PRICING_OWNER_UNSUPPORTED;
                default -> null;
            };
            if (code != null) throw BizException.of(code); throw failure;
        }
    }
    private static BizException malformed() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}

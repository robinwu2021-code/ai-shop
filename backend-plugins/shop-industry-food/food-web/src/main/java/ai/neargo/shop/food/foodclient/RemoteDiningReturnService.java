package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningReturnApplyReq;
import ai.neargo.shop.food.dto.DiningReturnVO;
import ai.neargo.shop.food.service.DiningReturnService;
import ai.neargo.shop.food.spi.DiningReturnInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** No retry converts an uncertain refund to success; the FOOD source intent exposes its original return reference. */
public class RemoteDiningReturnService implements DiningReturnService {
    private final DiningReturnInternalApi api;
    public RemoteDiningReturnService(DiningReturnInternalApi api) { this.api = api; }
    @Override public DiningReturnVO recoverOriginal(String entity, String store, String number) {
        return call(() -> api.recoverOriginal(new DiningReturnInternalApi.Original(entity, store, number)),
                number, store, null);
    }
    @Override public DiningReturnVO customerApply(String store, CartAccess access, DiningReturnApplyReq r, String key) {
        return call(() -> api.customerApply(new DiningReturnInternalApi.CustomerApply(store, access, r, key)),
                null, store, r.batchNo());
    }
    @Override public DiningReturnVO customerGet(CartAccess access, String number) {
        return call(() -> api.customerGet(new DiningReturnInternalApi.CustomerReference(access, number)), number, null, null);
    }
    @Override public DiningReturnVO customerCancel(CartAccess access, String number) {
        return call(() -> api.customerCancel(new DiningReturnInternalApi.CustomerReference(access, number)), number, null, null);
    }
    @Override public DiningReturnVO staffGet(String entity, String store, String actor, String number) {
        return call(() -> api.staffGet(new DiningReturnInternalApi.StaffReference(entity, store, actor, number)),
                number, store, null);
    }
    private DiningReturnVO call(Supplier<DiningReturnVO> operation, String number, String store, String batch) {
        try {
            DiningReturnVO result = ServiceCalls.call(ServiceName.FOOD, operation::get);
            if (result == null || result.returnNo() == null || result.returnNo().isBlank()
                    || number != null && !number.equals(result.returnNo()) || store != null && !store.equals(result.storeNo())
                    || batch != null && !batch.equals(result.batchNo()) || result.status() == null
                    || result.financialStatus() == null || result.kitchenStatus() == null || result.lines().isEmpty()
                    || result.financialCase() != null && !result.financialCase().afterSaleNo().equals(result.coreCaseRef())) {
                throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
            }
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
            if (code != null) throw BizException.of(code);
            throw failure;
        }
    }
}

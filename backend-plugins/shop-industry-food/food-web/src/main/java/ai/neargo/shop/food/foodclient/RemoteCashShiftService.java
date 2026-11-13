package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CashShiftContracts.CashShiftVO;
import ai.neargo.shop.food.dto.CashShiftContracts.SubmitReq;
import ai.neargo.shop.food.service.CashShiftService;
import ai.neargo.shop.food.spi.CashShiftInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** 拆分部署：宿主里的交班控制器经内部接口调 food-server。 */
public class RemoteCashShiftService implements CashShiftService {
    private final CashShiftInternalApi api;

    public RemoteCashShiftService(CashShiftInternalApi api) {
        this.api = api;
    }

    @Override
    public CashShiftVO submit(String entity, String store, String actor, SubmitReq req, String key) {
        return invoke(() -> api.submit(new CashShiftInternalApi.Submit(entity, store, actor, req, key)), null);
    }

    @Override
    public CashShiftVO get(String entity, String store, String shiftNo) {
        return invoke(() -> api.get(new CashShiftInternalApi.Reference(entity, store, shiftNo)), shiftNo);
    }

    private static CashShiftVO invoke(Supplier<CashShiftVO> call, String shiftNo) {
        try {
            var result = ServiceCalls.call(ServiceName.FOOD, call::get);
            if (result == null || result.shiftNo() == null || shiftNo != null && !shiftNo.equals(result.shiftNo()))
                throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
            return result;
        } catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST; case 403 -> ErrorCode.FORBIDDEN; case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT; case 503 -> ErrorCode.PRICING_OWNER_UNSUPPORTED; default -> null;
            };
            if (code != null) throw BizException.of(code);
            throw failure;
        }
    }
}

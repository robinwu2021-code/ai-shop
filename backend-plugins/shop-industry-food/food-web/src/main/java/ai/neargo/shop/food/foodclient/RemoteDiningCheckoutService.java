package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CreateDiningSettlementReq;
import ai.neargo.shop.food.dto.PayDiningSettlementReq;
import ai.neargo.shop.food.dto.DiningSettlementVersionReq;
import ai.neargo.shop.food.dto.DiningSettlementVO;
import ai.neargo.shop.food.service.DiningCheckoutService;
import ai.neargo.shop.food.spi.DiningCheckoutInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** No transport retry invents another charge leg or promotes an unknown original payment to success. */
public class RemoteDiningCheckoutService implements DiningCheckoutService {
    private final DiningCheckoutInternalApi api;
    public RemoteDiningCheckoutService(DiningCheckoutInternalApi api) { this.api = api; }
    @Override public DiningSettlementVO recoverOriginal(String entity, String store, String number) {
        return invoke(() -> api.recoverOriginal(new DiningCheckoutInternalApi.Original(entity, store, number)),
                number, store, null);
    }
    @Override public DiningSettlementVO customerCreate(CartAccess a, String batch, CreateDiningSettlementReq r, String key) {
        return invoke(() -> api.customerCreate(new DiningCheckoutInternalApi.Create(a, batch, r, key)), null, null, batch);
    }
    @Override public DiningSettlementVO customerGet(CartAccess a, String no) {
        return invoke(() -> api.customerGet(new DiningCheckoutInternalApi.Reference(a, no)), no, null, null);
    }
    @Override public DiningSettlementVO customerPay(CartAccess a, String no, PayDiningSettlementReq r, String key) {
        return invoke(() -> api.customerPay(new DiningCheckoutInternalApi.Pay(a, no, r, key)), no, null, null);
    }
    @Override public DiningSettlementVO customerQuery(CartAccess a, String no) {
        return invoke(() -> api.customerQuery(new DiningCheckoutInternalApi.Reference(a, no)), no, null, null);
    }
    @Override public DiningSettlementVO customerFinalize(CartAccess a, String no) {
        return invoke(() -> api.customerFinalize(new DiningCheckoutInternalApi.Reference(a, no)), no, null, null);
    }
    @Override public DiningSettlementVO customerCancel(CartAccess a, String no, DiningSettlementVersionReq r) {
        return invoke(() -> api.customerCancel(new DiningCheckoutInternalApi.Cancel(a, no, r)), no, null, null);
    }
    @Override public DiningSettlementVO staffGet(String entity, String store, String actor, String no) {
        var result = invoke(() -> api.staffGet(new DiningCheckoutInternalApi.StaffGet(entity, store, actor, no)), no, store, null);
        if (result.payment() != null) throw malformed(); return result;
    }
    private static DiningSettlementVO invoke(Supplier<DiningSettlementVO> operation, String no, String store, String batch) {
        try {
            var result = ServiceCalls.call(ServiceName.FOOD, operation::get);
            if (result == null || result.settlementNo() == null || result.status() == null || result.orderNo() == null
                    || result.subOrderNo() == null || result.currency() == null || result.dueMinor() < 0 || result.version() < 0
                    || no != null && !no.equals(result.settlementNo()) || store != null && !store.equals(result.storeNo())
                    || batch != null && !batch.equals(result.batchNo())) throw malformed();
            return result;
        } catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST; case 403 -> ErrorCode.FORBIDDEN; case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT; case 503 -> ErrorCode.PRICING_OWNER_UNSUPPORTED; default -> null;
            };
            if (code != null) throw BizException.of(code); throw failure;
        }
    }
    private static BizException malformed() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}

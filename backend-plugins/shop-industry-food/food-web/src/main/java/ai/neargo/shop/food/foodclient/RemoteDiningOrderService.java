package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CancelDiningOrderReq;
import ai.neargo.shop.food.dto.CreateDiningOrderReq;
import ai.neargo.shop.food.dto.CreateStaffDiningOrderReq;
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
    @Override public Snapshot authorizeCancellation(Command command, String key, String fulfillment) {
        try {
            var result = ServiceCalls.call(ServiceName.FOOD, () -> api.authorizeCancellation(
                    new DiningOrderInternalApi.CancellationSource(command, key, fulfillment)));
            var actor = command.identity();
            try {
                return PricingSourceFacts.expected(result,
                        new Scope(actor.tenantNo(), actor.entityNo(), actor.storeNo()), command.source(), null);
            } catch (IllegalArgumentException | ArithmeticException | NullPointerException invalid) {
                throw malformed();
            }
        } catch (ServiceCallException failure) { throw remoteFailure(failure); }
    }
    @Override public DiningOrderBatchVO staffCancel(String entity, String store, String actor, String batch,
            CancelDiningOrderReq request, String key) {
        var result = invoke(() -> api.staffCancel(new DiningOrderInternalApi.StaffCancel(
                entity, store, actor, batch, request, key)));
        if (!store.equals(result.storeNo()) || !batch.equals(result.batchNo())
                || !"POSTPAY".equals(result.payMode())) throw malformed();
        return result;
    }
    @Override public DiningOrderBatchVO staffPlace(String entity, String store, String actor,
            CreateStaffDiningOrderReq request, String key) {
        var result = invoke(() -> api.staffPlace(new DiningOrderInternalApi.StaffPlace(
                entity, store, actor, request, key)));
        if (!store.equals(result.storeNo()) || !request.cartNo().equals(result.cartNo())
                || request.version() != result.cartVersion() || !request.quoteNo().equals(result.quoteNo())
                || !request.payMode().equals(result.payMode())) throw malformed();
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
        } catch (ServiceCallException failure) { throw remoteFailure(failure); }
    }
    private static RuntimeException remoteFailure(ServiceCallException failure) {
        ErrorCode code = switch (failure.statusCode()) {
            case 400 -> ErrorCode.BAD_REQUEST;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 409 -> ErrorCode.CONFLICT;
            case 503 -> ErrorCode.PRICING_OWNER_UNSUPPORTED;
            default -> null;
        };
        return code == null ? failure : BizException.of(code);
    }
    private static BizException malformed() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}

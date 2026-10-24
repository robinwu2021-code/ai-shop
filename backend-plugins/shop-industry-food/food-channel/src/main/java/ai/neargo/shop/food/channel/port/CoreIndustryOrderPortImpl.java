package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.IndustryOrderInternalApi;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;
import java.util.Set;

/** Original placement keys survive a timeout; never open a replacement order to recover an uncertain result. */
public class CoreIndustryOrderPortImpl implements IndustryOrderPort {
    private final Supplier<IndustryOrderInternalApi> api;
    public CoreIndustryOrderPortImpl(Supplier<IndustryOrderInternalApi> api) { this.api = api; }
    @Override public Result acceptUnpaid(Identity identity,String order) {
        var result=invoke(() -> api.get().acceptUnpaid(new IndustryOrderInternalApi.Get(identity,order)),order);
        if(result==null || !result.acceptedUnpaid() || result.inventoryLifecycleVersion()!=3
                || !"POSTPAY".equals(result.orderMode()) || !"WAIT_PAY".equals(result.status())
                || result.fulfillmentStatus()==null || !Set.of("WAIT_FULFILL","FULFILLING","COMPLETED").contains(result.fulfillmentStatus())) {
            throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        }
        return result;
    }
    @Override public Result cancelForStore(Command command, String key, String fulfillment) {
        return invoke(() -> api.get().cancelForStore(
                new IndustryOrderInternalApi.StoreCancel(command, key, fulfillment)), null);
    }
    @Override public Result cancel(Command command, String key) {
        return invoke(() -> api.get().cancel(new IndustryOrderInternalApi.Place(command, key)), null);
    }
    @Override public Result getForStore(Scope scope, String actor, String order) {
        return invoke(() -> api.get().getForStore(new IndustryOrderInternalApi.StoreGet(scope, actor, order)), order);
    }
    @Override public Result placeForDiningRoom(Command command, String key) {
        return invoke(() -> api.get().placeForDiningRoom(new IndustryOrderInternalApi.Place(command, key)), null);
    }
    @Override public Result placeForStore(Command command, String key) {
        return invoke(() -> api.get().placeForStore(new IndustryOrderInternalApi.Place(command, key)), null);
    }
    @Override public Result queryForStore(Identity identity, String key) {
        return invoke(() -> api.get().queryForStore(new IndustryOrderInternalApi.Query(identity, key)), null);
    }
    @Override public Result place(Command command, String key) {
        return invoke(() -> api.get().place(new IndustryOrderInternalApi.Place(command, key)), null);
    }
    @Override public Result query(Identity identity, String key) {
        return invoke(() -> api.get().query(new IndustryOrderInternalApi.Query(identity, key)), null);
    }
    @Override public Result get(Identity identity, String order) {
        return invoke(() -> api.get().get(new IndustryOrderInternalApi.Get(identity, order)), order);
    }
    private static Result invoke(Supplier<Result> call, String order) {
        try {
            var result = ServiceCalls.call(ServiceName.PLATFORM, call::get);
            if (result != null && (result.orderNo() == null || result.subOrderNo() == null
                    || order != null && !order.equals(result.orderNo()) || result.status() == null)) {
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

package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.IndustryAfterSaleInternalApi;
import ai.neargo.shop.spi.trade.IndustryAfterSalePort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Original PLATFORM after-sale policy remains authoritative; missing or uncertain responses are never completion. */
public class CoreIndustryAfterSalePortImpl implements IndustryAfterSalePort {
    private final Supplier<IndustryAfterSaleInternalApi> api;
    public CoreIndustryAfterSalePortImpl(Supplier<IndustryAfterSaleInternalApi> api) { this.api = api; }
    @Override public Result apply(Apply request) {
        return result(call(() -> api.get().apply(request)), request.binding(), null);
    }
    @Override public Lookup queryApply(QueryApply query) {
        Lookup found = call(() -> api.get().queryApply(query));
        if (found == null || found.found() != (found.result() != null)) throw unsupported();
        if (found.found()) result(found.result(), query.binding(), null);
        return found;
    }
    @Override public Result get(Reference reference) {
        return result(call(() -> api.get().get(reference)), reference.binding(), reference.afterSaleNo());
    }
    @Override public Result cancel(Reference reference) {
        return result(call(() -> api.get().cancel(reference)), reference.binding(), reference.afterSaleNo());
    }
    private Result result(Result value, Binding binding, String number) {
        if (value == null || binding == null || value.afterSaleNo() == null || value.afterSaleNo().isBlank()
                || !binding.orderNo().equals(value.orderNo()) || !binding.subOrderNo().equals(value.subOrderNo())
                || number != null && !number.equals(value.afterSaleNo()) || value.status() == null
                || value.status().isBlank() || value.refundMinor() < 0 || value.currency() == null
                || !value.currency().matches("[A-Z]{3}")) throw unsupported();
        return value;
    }
    private static <T> T call(Supplier<T> operation) {
        try { return ServiceCalls.call(ServiceName.PLATFORM, operation::get); }
        catch (ServiceCallException failure) {
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
    private static BizException unsupported() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}

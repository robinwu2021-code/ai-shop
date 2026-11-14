package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningQuoteReq;
import ai.neargo.shop.food.dto.DiningQuoteConfirmReq;
import ai.neargo.shop.food.service.DiningPricingService;
import ai.neargo.shop.food.spi.DiningPricingInternalApi;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Gateway calls the same restaurant service remotely without resending successful writes. */
public class RemoteDiningPricingService implements DiningPricingService {
    private final DiningPricingInternalApi api;
    public RemoteDiningPricingService(DiningPricingInternalApi api) { this.api = api; }
    @Override public QuoteVO staffQuote(String entity, String store, String actor, DiningQuoteReq r, String key) {
        return call(() -> api.staffQuote(new DiningPricingInternalApi.StaffQuote(entity, store, actor, r, key)),
                r.version(), null);
    }
    @Override public QuoteVO customerQuote(String store, CartAccess access, DiningQuoteReq r, String key) {
        return call(() -> api.customerQuote(new DiningPricingInternalApi.CustomerQuote(store, access, r, key)),
                r.version(), null);
    }
    @Override public QuoteVO staffGet(String entity, String store, String actor, String cart, long version,
            String quote) {
        return call(() -> api.staffGet(new DiningPricingInternalApi.StaffGet(
                entity, store, actor, cart, version, quote)), version, quote);
    }
    @Override public QuoteVO customerGet(CartAccess access, String cart, long version, String quote) {
        return call(() -> api.customerGet(new DiningPricingInternalApi.CustomerGet(access, cart, version, quote)),
                version, quote);
    }
    @Override public QuoteVO staffConfirm(String entity, String store, String actor, String quote,
            DiningQuoteConfirmReq r) {
        return call(() -> api.staffConfirm(new DiningPricingInternalApi.StaffConfirm(
                entity, store, actor, quote, r)), r.version(), quote);
    }
    @Override public QuoteVO customerConfirm(CartAccess access, String quote, DiningQuoteConfirmReq r) {
        return call(() -> api.customerConfirm(new DiningPricingInternalApi.CustomerConfirm(access, quote, r)),
                r.version(), quote);
    }
    private static QuoteVO call(Supplier<QuoteVO> invoke, long version, String quote) {
        try {
            var result = ServiceCalls.call(ServiceName.FOOD, invoke::get);
            if (result == null || result.quoteNo() == null || result.sourceVersion() != version
                    || quote != null && !quote.equals(result.quoteNo()) || result.totals() == null
                    || result.confirmationDigest() == null || !result.confirmationDigest().matches("[0-9a-f]{64}")) {
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

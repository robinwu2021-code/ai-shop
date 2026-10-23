package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.food.policy.CartInputPolicy;
import ai.neargo.shop.food.policy.DiningPriceFactsPolicy;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.food.spi.PricingSourceInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Fail-closed receipt validation; no local price reconstruction, retry or authority fallback. */
public class RemoteDiningPricingFactsService implements DiningPricingFactsService {
    private final PricingSourceInternalApi api;
    public RemoteDiningPricingFactsService(PricingSourceInternalApi api) { this.api = api; }
    @Override
    public DiningPriceFacts staff(String entity, String store, String cart, long version) {
        CartInputPolicy.key(entity); CartInputPolicy.key(store); CartInputPolicy.key(cart);
        CartInputPolicy.version(version);
        var result = invoke(() -> api.staff(new PricingSourceInternalApi.StaffCommand(entity, store, cart, version)));
        return receipt(result, entity, store, cart, version, null);
    }
    @Override
    public DiningPriceFacts customer(String cart, CartAccess access, long version) {
        CartInputPolicy.key(cart); CartInputPolicy.version(version);
        if (access == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var result = invoke(() -> api.customer(new PricingSourceInternalApi.CustomerCommand(cart, access, version)));
        if (result == null || result.payload() == null || result.payload().cart() == null) throw malformed();
        var actual = result.payload().cart();
        if (actual.buyerNo() != null && !actual.buyerNo().equals(access.buyerNo())) throw malformed();
        String subject = access.sessionNo() != null ? access.sessionNo()
                : actual.sessionNo() != null ? actual.sessionNo() : access.buyerNo();
        if (subject == null) throw malformed();
        return receipt(result, null, null, cart, version, subject);
    }
    private static DiningPriceFacts receipt(DiningPriceFacts result, String entity, String store,
            String cart, long version, String subject) {
        try { return DiningPriceFactsPolicy.validate(result, entity, store, cart, version, subject); }
        catch (RuntimeException invalid) { throw malformed(); }
    }
    private <T> T invoke(Supplier<T> call) {
        try { return ServiceCalls.call(ServiceName.FOOD, call); }
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
    private static BizException malformed() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}

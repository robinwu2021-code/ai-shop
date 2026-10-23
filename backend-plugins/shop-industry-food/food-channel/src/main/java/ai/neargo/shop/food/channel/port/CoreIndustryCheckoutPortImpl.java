package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.IndustryCheckoutInternalApi;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Calls the same original core source-owned checkout contract in standalone FOOD. */
public class CoreIndustryCheckoutPortImpl implements IndustryCheckoutPort {
    private final Supplier<IndustryCheckoutInternalApi> api;
    public CoreIndustryCheckoutPortImpl(Supplier<IndustryCheckoutInternalApi> api) { this.api = api; }
    @Override public View get(Identity identity, String no) { return invoke(() -> api.get().get(new IndustryCheckoutInternalApi.Reference(identity, no)), no); }
    @Override public View pay(PayCommand command) { return invoke(() -> api.get().pay(command), command.orderNo()); }
    @Override public View queryPayment(Identity identity, String no) { return invoke(() -> api.get().queryPayment(new IndustryCheckoutInternalApi.Reference(identity, no)), no); }
    @Override public View finalizeCheckout(Identity identity, String no) { return invoke(() -> api.get().finalizeCheckout(new IndustryCheckoutInternalApi.Reference(identity, no)), no); }
    private static View invoke(Supplier<View> operation, String no) {
        try {
            var value = ServiceCalls.call(ServiceName.PLATFORM, operation::get);
            if (value == null || !no.equals(value.orderNo()) || value.subOrderNo() == null || value.quoteNo() == null
                    || value.sourceVersion() < 0 || value.sourceHash() == null || value.economicsHash() == null
                    || value.orderStatus() == null || value.currency() == null || value.amountMinor() < 0 || value.payment() == null
                    || value.readyToAccept() && (!"PAID".equals(value.orderStatus()) || !"SUCCESS".equals(value.payment().status())
                            || value.payment().outTradeNo() == null)) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
            return value;
        } catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST; case 403 -> ErrorCode.FORBIDDEN; case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT; case 503 -> ErrorCode.PRICING_OWNER_UNSUPPORTED; default -> null;
            };
            if (code != null) throw BizException.of(code); throw failure;
        }
    }
}

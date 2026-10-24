package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.spi.trade.PricingCheckoutInternalApi;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;
import ai.neargo.svc.client.ServiceCallException;
import java.util.function.Supplier;

/** One authenticated call per operation; no remote failure is converted to an estimated payable amount. */
public class CorePricingCheckoutPortImpl implements PricingCheckoutPort {
    private final Supplier<PricingCheckoutInternalApi> api;
    public CorePricingCheckoutPortImpl(Supplier<PricingCheckoutInternalApi> api) { this.api = api; }
    @Override public QuoteVO quoteStaff(Scope scope, String actor, PricingSourceRef source, Selection selection,
            String key) {
        return invoke(() -> api.get().quoteStaff(new PricingCheckoutInternalApi.StaffQuote(
                scope, actor, source, selection, key)), source, null);
    }
    @Override public QuoteVO quoteCustomer(PricingSourceRef source, Credentials access, Selection selection,
            String key) {
        return invoke(() -> api.get().quoteCustomer(new PricingCheckoutInternalApi.CustomerQuote(
                source, access, selection, key)), source, null);
    }
    @Override public QuoteVO getStaff(Scope scope, String actor, PricingSourceRef source, String quote) {
        return invoke(() -> api.get().getStaff(new PricingCheckoutInternalApi.StaffRead(
                scope, actor, source, quote, null)), source, quote);
    }
    @Override public QuoteVO getCustomer(PricingSourceRef source, Credentials access, String quote) {
        return invoke(() -> api.get().getCustomer(new PricingCheckoutInternalApi.CustomerRead(
                source, access, quote, null)), source, quote);
    }
    @Override public QuoteVO revalidateStaff(Scope scope, String actor, PricingSourceRef source, String quote,
            String digest) {
        return invoke(() -> api.get().revalidateStaff(new PricingCheckoutInternalApi.StaffRead(
                scope, actor, source, quote, digest)), source, quote);
    }
    @Override public QuoteVO revalidateCustomer(PricingSourceRef source, Credentials access, String quote,
            String digest) {
        return invoke(() -> api.get().revalidateCustomer(new PricingCheckoutInternalApi.CustomerRead(
                source, access, quote, digest)), source, quote);
    }
    private static QuoteVO invoke(Supplier<QuoteVO> call, PricingSourceRef source, String quote) {
        try {
            var result = ServiceCalls.call(ServiceName.PLATFORM, call::get);
            if (result == null || result.quoteNo() == null || result.sourceVersion() != source.sourceVersion()
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

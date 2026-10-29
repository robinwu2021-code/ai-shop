package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.IndustryChannelOrderInternalApi;
import ai.neargo.shop.spi.trade.IndustryChannelOrderInternalApi.Request;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.function.Supplier;

/**
 * Standalone food calls the same original core admission; uncertain responses never open another
 * order.
 */
public final class CoreIndustryChannelOrderPortImpl implements IndustryChannelOrderPort {
    private final Supplier<IndustryChannelOrderInternalApi> api;

    public CoreIndustryChannelOrderPortImpl(Supplier<IndustryChannelOrderInternalApi> api) {
        this.api = api;
    }

    @Override
    public Preparation prepare(Reference reference) {
        return preparation(
                invoke(() -> api.get().prepare(new Request(reference))), reference, false);
    }

    @Override
    public Preparation get(Reference reference) {
        return preparation(invoke(() -> api.get().get(new Request(reference))), reference, true);
    }

    @Override
    public Acceptance confirm(Reference reference) {
        var result = invoke(() -> api.get().confirm(new Request(reference)));
        require(
                result != null
                        && reference.equals(result.reference())
                        && result.orderNo() != null
                        && result.subOrderNo() != null
                        && result.acceptanceReceiptRef() != null
                        && !result.allocations().isEmpty());
        return result;
    }

    @Override
    public Cancellation cancel(Reference reference) {
        var result = invoke(() -> api.get().cancel(new Request(reference)));
        require(
                result != null
                        && reference.equals(result.reference())
                        && result.cancellationReceiptRef() != null);
        return result;
    }

    private static Preparation preparation(
            Preparation result, Reference reference, boolean optional) {
        if (result == null && optional) return null;
        require(result != null && reference.equals(result.reference()) && result.status() != null);
        if (result.status() == Status.CANCELLED
                && result.orderNo() == null
                && result.subOrderNo() == null
                && result.quoteNo() == null) return result;
        require(
                result.orderNo() != null
                        && result.subOrderNo() != null
                        && result.quoteNo() != null);
        if (result.status() == Status.PREPARED)
            require(
                    result.validUntil() != null
                            && result.preparationReceiptRef() != null
                            && result.inventoryPlan() != null);
        return result;
    }

    private static <T> T invoke(Supplier<T> call) {
        try {
            return ServiceCalls.call(ServiceName.PLATFORM, call::get);
        } catch (ServiceCallException failure) {
            var code =
                    switch (failure.statusCode()) {
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

    private static void require(boolean condition) {
        if (!condition) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
}

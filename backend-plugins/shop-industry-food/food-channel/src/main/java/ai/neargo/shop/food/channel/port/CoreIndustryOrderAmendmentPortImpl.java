package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentInternalApi;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.function.Supplier;

public class CoreIndustryOrderAmendmentPortImpl implements IndustryOrderAmendmentPort {
    private final Supplier<IndustryOrderAmendmentInternalApi> api;

    public CoreIndustryOrderAmendmentPortImpl(Supplier<IndustryOrderAmendmentInternalApi> api) {
        this.api = api;
    }

    @Override
    public Receipt amend(Command c, String key) {
        return call(
                c.reference(),
                () -> api.get().amend(new IndustryOrderAmendmentInternalApi.Amend(c, key)));
    }

    @Override
    public Receipt query(Reference r, String actor, String key) {
        return call(
                r,
                () -> api.get().query(new IndustryOrderAmendmentInternalApi.Query(r, actor, key)));
    }

    @Override
    public Receipt adjustFee(FeeScopeAdjustment c, String key) {
        return call(
                c.reference(),
                () -> api.get().fee(new IndustryOrderAmendmentInternalApi.Fee(c, key)));
    }

    private Receipt call(Reference ref, Supplier<Receipt> work) {
        try {
            return ServiceCalls.call(
                    ServiceName.PLATFORM,
                    () -> {
                        var r = work.get();
                        if (r == null
                                || !ref.equals(r.reference())
                                || r.status() == null
                                || r.effectiveReceivableMinor() == null
                                || r.effectiveReceivableMinor() < 0
                                || !r.pending()
                                        && (!"CONFIRMED".equals(r.status())
                                                || r.amendmentNo() == null))
                            throw new ServiceCallException(
                                    CallOutcome.REMOTE_ERROR,
                                    ServiceName.PLATFORM,
                                    0,
                                    "Invalid original amendment receipt");
                        return r;
                    });
        } catch (ServiceCallException failure) {
            throw switch (failure.statusCode()) {
                case 400 -> BizException.of(ErrorCode.BAD_REQUEST);
                case 403 -> BizException.of(ErrorCode.FORBIDDEN);
                case 404 -> BizException.of(ErrorCode.NOT_FOUND);
                case 409 -> BizException.of(ErrorCode.CONFLICT);
                default -> failure;
            };
        }
    }
}

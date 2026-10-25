package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.spi.trade.IndustryAmendmentSourceInternalApi;
import ai.neargo.shop.spi.trade.IndustryAmendmentSourcePort;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.function.Supplier;

public class RemoteIndustryAmendmentSourcePortImpl implements IndustryAmendmentSourcePort {
    private final Supplier<IndustryAmendmentSourceInternalApi> api;

    public RemoteIndustryAmendmentSourcePortImpl(Supplier<IndustryAmendmentSourceInternalApi> api) {
        this.api = api;
    }

    @Override
    public Evidence get(Reference r, String number) {
        return ServiceCalls.call(
                ServiceName.FOOD,
                () -> {
                    var e = api.get().get(new IndustryAmendmentSourceInternalApi.Query(r, number));
                    if (e == null || !r.equals(e.reference()) || !number.equals(e.returnNo()))
                        throw new ServiceCallException(
                                CallOutcome.REMOTE_ERROR,
                                ServiceName.FOOD,
                                0,
                                "Invalid original kitchen return evidence");
                    return e;
                });
    }
}

package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.DishOfferFacts;
import ai.neargo.shop.spi.product.DishOfferInternalApi;
import ai.neargo.shop.spi.product.DishOfferQueryPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

/** Carries original LOCAL offers without retries, channel price substitution or invented inventory. */
public class CoreDishOfferQueryPortImpl implements DishOfferQueryPort {
    private final Supplier<DishOfferInternalApi> api;

    public CoreDishOfferQueryPortImpl(Supplier<DishOfferInternalApi> api) { this.api = api; }

    @Override
    public List<Offer> getOffers(String entity, String store, List<String> numbers) {
        DishOfferQueryPort.validateLookup(entity, store, numbers);
        try {
            return ServiceCalls.call(ServiceName.PLATFORM, () -> {
                var facts = api.get().lookupBatch(new DishOfferInternalApi.BatchLookup(entity, store, numbers));
                var seen = new HashSet<String>();
                if (facts == null || facts.size() > numbers.size() || facts.stream().anyMatch(fact ->
                        !DishOfferFacts.valid(fact, entity, store, numbers) || !seen.add(fact.skuNo()))) {
                    throw new ServiceCallException(CallOutcome.REMOTE_ERROR, ServiceName.PLATFORM, 0,
                            "Invalid original offer response");
                }
                return List.copyOf(facts);
            });
        } catch (ServiceCallException failure) {
            if (failure.statusCode() == 400) throw BizException.of(ErrorCode.BAD_REQUEST);
            if (failure.statusCode() == 403) throw BizException.of(ErrorCode.FORBIDDEN);
            throw failure;
        }
    }
}

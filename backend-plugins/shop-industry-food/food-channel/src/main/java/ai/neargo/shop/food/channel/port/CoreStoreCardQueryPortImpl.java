package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.StoreCardFacts;
import ai.neargo.shop.spi.user.StoreCardInternalApi;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Standalone food resolves original store identity and hours through one authenticated typed platform call. */
public class CoreStoreCardQueryPortImpl implements StoreCardQueryPort {
    private final Supplier<StoreCardInternalApi> api;
    public CoreStoreCardQueryPortImpl(Supplier<StoreCardInternalApi> api) { this.api = api; }
    @Override public Map<String, StoreDirectoryPort.StoreCard> cards(Collection<String> numbers) {
        StoreCardQueryPort.validateLookup(numbers);
        var keys = List.copyOf(numbers);
        try {
            return ServiceCalls.call(ServiceName.PLATFORM, () -> {
                var received = api.get().lookupBatch(new StoreCardInternalApi.BatchLookup(keys));
                try {
                    if (received == null || received.size() > keys.size()) throw new IllegalStateException();
                    var result = new HashMap<String, StoreDirectoryPort.StoreCard>();
                    for (var receipt : received) {
                        if (receipt == null) throw new IllegalStateException();
                        var card = receipt.card();
                        if (result.put(card.storeNo(), card) != null) throw new IllegalStateException();
                    }
                    StoreCardFacts.response(result, keys);
                    return Map.copyOf(result);
                } catch (RuntimeException invalid) {
                    throw new ServiceCallException(CallOutcome.REMOTE_ERROR, ServiceName.PLATFORM, 0,
                            "Invalid original store-card response");
                }
            });
        } catch (ServiceCallException failure) {
            var code = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if (code != null) throw BizException.of(code);
            throw failure;
        }
    }
}

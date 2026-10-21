package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.media.MediaAssetInternalApi;
import ai.neargo.shop.spi.media.MediaAssetQueryPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

/** Bounded typed platform lookup; an unavailable media service never grants ownership. */
public class CoreMediaAssetQueryPortImpl implements MediaAssetQueryPort {
    private final Supplier<MediaAssetInternalApi> api;

    public CoreMediaAssetQueryPortImpl(Supplier<MediaAssetInternalApi> api) { this.api = api; }

    @Override
    public List<AssetFact> getAssets(String entity, String store, List<String> keys) {
        MediaAssetQueryPort.validateLookup(entity, store, keys);
        try {
            return ServiceCalls.call(ServiceName.PLATFORM, () -> {
                var facts = api.get().lookupBatch(new MediaAssetInternalApi.BatchLookup(entity, store, keys));
                var seen = new HashSet<String>();
                if (facts == null || facts.size() > keys.size() || facts.stream().anyMatch(fact ->
                        !MediaAssetQueryPort.validFact(fact, entity, store, keys) || !seen.add(fact.assetKey()))) {
                    throw new ServiceCallException(CallOutcome.REMOTE_ERROR, ServiceName.PLATFORM, 0,
                            "Invalid media asset response");
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

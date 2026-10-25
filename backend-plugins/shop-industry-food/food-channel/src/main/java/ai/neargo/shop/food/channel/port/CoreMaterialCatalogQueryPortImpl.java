package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogInternalApi;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

/** Uses the actual inventory host once; a missing route is an outage, not a missing material. */
public class CoreMaterialCatalogQueryPortImpl implements MaterialCatalogQueryPort {
    private final Supplier<MaterialCatalogInternalApi> api;

    public CoreMaterialCatalogQueryPortImpl(Supplier<MaterialCatalogInternalApi> api) {
        this.api = api;
    }

    @Override
    public List<SkuMaterialFact> getSkuMaterials(
            String entityNo, String storeNo, List<String> skuNos) {
        MaterialCatalogQueryPort.validateLookup(entityNo, skuNos);
        if (!MaterialCatalogFacts.validKey(storeNo, 32))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        return ServiceCalls.call(
                ServiceName.PLATFORM,
                () -> {
                    var r =
                            api.get()
                                    .lookupSkus(
                                            new MaterialCatalogInternalApi.SkuLookup(
                                                    entityNo, storeNo, skuNos));
                    var seen = new HashSet<String>();
                    if (r == null
                            || r.size() > skuNos.size()
                            || r.stream()
                                    .anyMatch(
                                            f ->
                                                    f == null
                                                            || !skuNos.contains(f.skuNo())
                                                            || !seen.add(f.skuNo())
                                                            || !MaterialCatalogFacts.validFact(
                                                                    f.material())
                                                            || !entityNo.equals(
                                                                    f.material().entityNo())))
                        throw new ServiceCallException(
                                CallOutcome.REMOTE_ERROR,
                                ServiceName.PLATFORM,
                                0,
                                "Invalid SKU material definition response");
                    return r;
                });
    }

    @Override
    public List<MaterialFact> getMaterials(String entityNo, List<String> itemIds) {
        MaterialCatalogQueryPort.validateLookup(entityNo, itemIds);
        try {
            return ServiceCalls.call(
                    ServiceName.PLATFORM,
                    () -> {
                        var result =
                                api.get()
                                        .lookupBatch(
                                                new MaterialCatalogInternalApi.BatchLookup(
                                                        entityNo, itemIds));
                        var seen = new HashSet<String>();
                        if (result == null
                                || result.size() > itemIds.size()
                                || result.stream()
                                        .anyMatch(
                                                fact ->
                                                        !MaterialCatalogFacts.validFact(fact)
                                                                || !entityNo.equals(fact.entityNo())
                                                                || !itemIds.contains(fact.itemId())
                                                                || !seen.add(fact.itemId()))) {
                            throw new ServiceCallException(
                                    CallOutcome.REMOTE_ERROR,
                                    ServiceName.PLATFORM,
                                    0,
                                    "Invalid inventory material definition response");
                        }
                        return result.stream()
                                .sorted(Comparator.comparing(MaterialFact::itemId))
                                .toList();
                    });
        } catch (ServiceCallException failure) {
            if (failure.statusCode() == 400) throw BizException.of(ErrorCode.BAD_REQUEST);
            if (failure.statusCode() == 403) throw BizException.of(ErrorCode.FORBIDDEN);
            throw failure;
        }
    }
}

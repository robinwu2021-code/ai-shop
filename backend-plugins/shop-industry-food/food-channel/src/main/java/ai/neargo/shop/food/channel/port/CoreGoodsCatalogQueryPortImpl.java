package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.GoodsCatalogInternalApi;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.CatalogImageContracts;
import ai.neargo.shop.spi.product.CatalogImageFacts;
import ai.neargo.shop.spi.product.CatalogMarketContracts;
import ai.neargo.shop.spi.product.CatalogMarketFacts;
import java.util.function.Consumer;
import java.time.Instant;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.Objects;
import java.util.List;
import java.util.HashSet;
import java.util.function.Supplier;

/** Independent restaurant reads metadata from the original product authority, without fallback or retry. */
public class CoreGoodsCatalogQueryPortImpl implements GoodsCatalogQueryPort {
    private final Supplier<GoodsCatalogInternalApi> api;

    public CoreGoodsCatalogQueryPortImpl(Supplier<GoodsCatalogInternalApi> api) { this.api = api; }

    @Override
    public CatalogMarketContracts.GoodsFacts getCatalogGoods(CatalogMarketContracts.GoodsQuery query) {
        CatalogMarketFacts.validateQuery(query);
        return marketCall(() -> api.get().lookupMarketGoods(query),
                f -> CatalogMarketFacts.validate(query, f, Instant.now()));
    }

    @Override
    public CatalogMarketContracts.SkuFacts getCatalogSku(CatalogMarketContracts.SkuQuery query) {
        CatalogMarketFacts.validateQuery(query);
        return marketCall(() -> api.get().lookupMarketSku(query),
                f -> CatalogMarketFacts.validate(query, f, Instant.now()));
    }

    private <T> T marketCall(Supplier<T> operation, Consumer<T> validation) {
        try {
            return ServiceCalls.call(ServiceName.PLATFORM, () -> {
                T facts = operation.get();
                try { validation.accept(facts); }
                catch (IllegalArgumentException | IllegalStateException invalid) {
                    throw new ServiceCallException(CallOutcome.REMOTE_ERROR, ServiceName.PLATFORM, 0,
                            "Invalid original market metadata response");
                }
                return facts;
            });
        } catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
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

    @Override
    public CatalogImageContracts.Facts resolveCatalogImages(CatalogImageContracts.Query query) {
        CatalogImageFacts.validateQuery(query);
        try {
            return ServiceCalls.call(ServiceName.PLATFORM, () -> {
                var facts = api.get().resolveImages(query);
                try { CatalogImageFacts.validate(query, facts, Instant.now()); }
                catch (IllegalStateException invalid) {
                    throw new ServiceCallException(CallOutcome.REMOTE_ERROR, ServiceName.PLATFORM, 0,
                            "Invalid original catalog image response");
                }
                return facts;
            });
        } catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
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

    @Override
    public SkuIdentityFact getSku(String entity, String store, String sku) {
        try {
            return ServiceCalls.call(ServiceName.PLATFORM, () -> {
                var fact = api.get().lookupSku(new GoodsCatalogInternalApi.SkuLookup(entity, store, sku));
                if (fact == null || !Objects.equals(entity, fact.entityNo()) || !Objects.equals(store, fact.storeNo())
                        || !Objects.equals(sku, fact.skuNo()) || fact.goodsNo() == null || fact.goodsNo().isBlank()
                        || fact.goodsNo().length() > 32 || !fact.goodsNo().equals(fact.goodsNo().trim())
                        || fact.version() < 0) {
                    throw new ServiceCallException(CallOutcome.REMOTE_ERROR, ServiceName.PLATFORM, 0,
                            "Invalid SKU identity response");
                }
                return fact;
            });
        } catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
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

    @Override
    public List<GoodsSummary> getGoodsBatch(String entity, String store, List<String> numbers) {
        try {
            return ServiceCalls.call(ServiceName.PLATFORM, () -> {
                var result = api.get().lookupBatch(new GoodsCatalogInternalApi.BatchLookup(entity, store, numbers));
                var seen = new HashSet<String>();
                if (result == null || result.size() > numbers.size() || result.stream().anyMatch(f -> f == null
                        || !Objects.equals(entity, f.entityNo()) || !Objects.equals(store, f.storeNo())
                        || !numbers.contains(f.goodsNo()) || !seen.add(f.goodsNo()))) {
                    throw new ServiceCallException(CallOutcome.REMOTE_ERROR, ServiceName.PLATFORM, 0,
                            "Invalid product batch metadata response");
                }
                return List.copyOf(result);
            });
        } catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
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

    @Override
    public GoodsFact getGoods(String entityNo, String storeNo, String goodsNo) {
        try {
            return ServiceCalls.call(ServiceName.PLATFORM, () -> {
                var fact = api.get().lookup(new GoodsCatalogInternalApi.Lookup(entityNo, storeNo, goodsNo));
                if (fact == null || !Objects.equals(entityNo, fact.entityNo())
                        || !Objects.equals(storeNo, fact.storeNo()) || !Objects.equals(goodsNo, fact.goodsNo())) {
                    throw new ServiceCallException(CallOutcome.REMOTE_ERROR, ServiceName.PLATFORM, 0,
                            "Invalid product metadata response");
                }
                return fact;
            });
        } catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
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

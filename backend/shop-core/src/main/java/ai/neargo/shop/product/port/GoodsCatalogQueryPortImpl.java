package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.CatalogMarketContracts;
import ai.neargo.shop.spi.product.CatalogMarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import java.util.Comparator;
import ai.neargo.shop.spi.product.CatalogImageContracts;
import ai.neargo.shop.spi.product.CatalogImageFacts;
import ai.neargo.shop.spi.media.MediaReferenceQueryPort;
import ai.neargo.shop.spi.media.MediaReferenceFacts;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.HashSet;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Pure product metadata: explicit owner predicates also protect service-token and consumer callers. */
@Component
public class GoodsCatalogQueryPortImpl implements GoodsCatalogQueryPort {
    private final GoodsMapper goods;
    private final SkuMapper skus;

    private final Supplier<MediaReferenceQueryPort> media;
    private final Supplier<CatalogPriceSourcePort> markets;
    private final TransactionTemplate read;
    private final Clock clock;

    /** Legacy metadata construction remains supported without inventing image capability. */
    public GoodsCatalogQueryPortImpl(GoodsMapper goods, SkuMapper skus) {
        this(goods, skus, () -> null, null, Clock.systemUTC());
    }

    /** Lazy media lookup avoids a product-to-adapter dependency and preserves optional owners. */
    public GoodsCatalogQueryPortImpl(GoodsMapper goods, SkuMapper skus,
            ObjectProvider<MediaReferenceQueryPort> media, PlatformTransactionManager manager) {
        this(goods, skus, media::getIfAvailable, manager, Clock.systemUTC());
    }

    /** Original market and media owners remain lazy; metadata must never instantiate an industry bridge. */
    @Autowired
    public GoodsCatalogQueryPortImpl(GoodsMapper goods, SkuMapper skus,
            ObjectProvider<MediaReferenceQueryPort> media, ObjectProvider<CatalogPriceSourcePort> markets,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this(goods, skus, media::getIfAvailable, markets::getIfAvailable, manager, Clock.systemUTC());
    }

    /** Independent clock and RR boundaries keep external owner reads outside database locks. */
    public GoodsCatalogQueryPortImpl(GoodsMapper goods, SkuMapper skus, Supplier<MediaReferenceQueryPort> media,
            PlatformTransactionManager manager, Clock clock) {
        this(goods, skus, media, () -> null, manager, clock);
    }

    /** Explicit owner suppliers and clock let consistency checks be exercised without changing old constructors. */
    public GoodsCatalogQueryPortImpl(GoodsMapper goods, SkuMapper skus, Supplier<MediaReferenceQueryPort> media,
            Supplier<CatalogPriceSourcePort> markets, PlatformTransactionManager manager, Clock clock) {
        this.goods = goods;
        this.skus = skus;
        this.media = media;
        this.markets = markets;
        this.clock = clock;
        read = manager == null ? null : new TransactionTemplate(manager);
        if (read != null) {
            read.setReadOnly(true);
            read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        }
    }

    @Override
    public CatalogMarketContracts.GoodsFacts getCatalogGoods(CatalogMarketContracts.GoodsQuery query) {
        CatalogMarketFacts.validateQuery(query);
        marketRequire(read != null && !TransactionSynchronizationManager.isActualTransactionActive());
        var started = clock.instant();
        var owner = marketOwner();
        var first = market(owner, query.entityNo(), query.storeNo(), query.expectedMarket());
        var before = marketGoods(query, first.market().market());
        var second = market(owner, query.entityNo(), query.storeNo(), query.expectedMarket());
        marketRequire(first.market().equals(second.market()));
        marketRequire(before.equals(marketGoods(query, first.market().market())));
        var facts = CatalogMarketFacts.seal(new CatalogMarketContracts.GoodsFacts(1, "CATALOG_GOODS", query,
                first, before, clock.instant(), minimum(started.plusSeconds(30),
                        minimum(first.validUntil(), second.validUntil())), null));
        try { CatalogMarketFacts.validate(query, facts, clock.instant()); }
        catch (IllegalArgumentException | IllegalStateException invalid) { throw BizException.of(ErrorCode.CONFLICT); }
        return facts;
    }

    @Override
    public CatalogMarketContracts.SkuFacts getCatalogSku(CatalogMarketContracts.SkuQuery query) {
        CatalogMarketFacts.validateQuery(query);
        marketRequire(read != null && !TransactionSynchronizationManager.isActualTransactionActive());
        var started = clock.instant();
        var owner = marketOwner();
        var first = market(owner, query.entityNo(), query.storeNo(), query.expectedMarket());
        var before = marketSku(query, first.market().market());
        var second = market(owner, query.entityNo(), query.storeNo(), query.expectedMarket());
        marketRequire(first.market().equals(second.market()));
        marketRequire(before.equals(marketSku(query, first.market().market())));
        var facts = CatalogMarketFacts.seal(new CatalogMarketContracts.SkuFacts(1, "CATALOG_SKU", query,
                first, before.sku(), before.goodsVersion(), clock.instant(), minimum(started.plusSeconds(30),
                        minimum(first.validUntil(), second.validUntil())), null));
        try { CatalogMarketFacts.validate(query, facts, clock.instant()); }
        catch (IllegalArgumentException | IllegalStateException invalid) { throw BizException.of(ErrorCode.CONFLICT); }
        return facts;
    }

    private CatalogPriceSourcePort marketOwner() {
        var owner = markets.get();
        if (owner == null) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        return owner;
    }

    private CatalogPriceSourcePort.MarketFacts market(CatalogPriceSourcePort owner, String entity, String store,
            String expected) {
        var scope = new CatalogPriceSourcePort.MarketScope(entity, store);
        var facts = owner.market(scope);
        try { CatalogPriceSourceFacts.validateMarketFacts(scope, facts, clock.instant()); }
        catch (IllegalArgumentException | IllegalStateException invalid) { throw BizException.of(ErrorCode.CONFLICT); }
        marketRequire(expected == null || expected.equals(facts.market().market()));
        return facts;
    }

    private GoodsFact marketGoods(CatalogMarketContracts.GoodsQuery query, String market) {
        return DataScopeContext.executeWithoutScope(() -> read.execute(tx -> {
            var row = marketGoodsOwner(query.entityNo(), query.storeNo(), query.goodsNo());
            var variants = skus.selectList(Wrappers.<PrdSku>lambdaQuery()
                    .select(PrdSku::getSkuNo, PrdSku::getSpec, PrdSku::getVersion,
                            PrdSku::getEntityNo, PrdSku::getGoodsNo, PrdSku::getMarket)
                    .eq(PrdSku::getEntityNo, query.entityNo()).eq(PrdSku::getGoodsNo, query.goodsNo())
                    .eq(PrdSku::getMarket, market).orderByAsc(PrdSku::getSkuNo).last("LIMIT 257"));
            marketRequire(variants.size() <= CatalogMarketFacts.MAX_SKUS);
            for (var sku : variants) {
                marketRequire(query.entityNo().equals(sku.getEntityNo())
                        && query.goodsNo().equals(sku.getGoodsNo()) && market.equals(sku.getMarket()));
            }
            return new GoodsFact(row.getGoodsNo(), row.getEntityNo(), row.getStoreNo(), row.getTitle(), row.getCover(),
                    Boolean.TRUE.equals(row.getOnSale()), row.getAuditStatus(), row.getVersion(),
                    variants.stream().map(sku -> new SkuFact(sku.getSkuNo(), sku.getSpec(), sku.getVersion()))
                            .sorted(Comparator.comparing(SkuFact::skuNo)).toList());
        }));
    }

    private SkuRow marketSku(CatalogMarketContracts.SkuQuery query, String market) {
        return DataScopeContext.executeWithoutScope(() -> read.execute(tx -> {
            var sku = skus.selectOne(Wrappers.<PrdSku>lambdaQuery()
                    .select(PrdSku::getSkuNo, PrdSku::getGoodsNo, PrdSku::getEntityNo,
                            PrdSku::getMarket, PrdSku::getVersion)
                    .eq(PrdSku::getEntityNo, query.entityNo()).eq(PrdSku::getSkuNo, query.skuNo())
                    .eq(PrdSku::getMarket, market));
            if (sku == null || !query.entityNo().equals(sku.getEntityNo()) || !query.skuNo().equals(sku.getSkuNo())
                    || !market.equals(sku.getMarket())) throw BizException.of(ErrorCode.NOT_FOUND);
            var goodsOwner = marketGoodsOwner(query.entityNo(), query.storeNo(), sku.getGoodsNo());
            return new SkuRow(new SkuIdentityFact(sku.getSkuNo(), sku.getGoodsNo(), query.entityNo(), query.storeNo(),
                    sku.getVersion()), goodsOwner.getVersion());
        }));
    }

    private PrdGoods marketGoodsOwner(String entity, String store, String number) {
        var row = goods.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                .select(PrdGoods::getGoodsNo, PrdGoods::getEntityNo, PrdGoods::getStoreNo, PrdGoods::getTitle,
                        PrdGoods::getCover, PrdGoods::getOnSale, PrdGoods::getAuditStatus, PrdGoods::getVersion)
                .eq(PrdGoods::getEntityNo, entity).eq(PrdGoods::getStoreNo, store).eq(PrdGoods::getGoodsNo, number));
        if (row == null || !entity.equals(row.getEntityNo()) || !store.equals(row.getStoreNo())
                || !number.equals(row.getGoodsNo())) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private static void marketRequire(boolean valid) {
        if (!valid) throw BizException.of(ErrorCode.CONFLICT);
    }

    private record SkuRow(SkuIdentityFact sku, long goodsVersion) { }

    @Override
    public CatalogImageContracts.Facts resolveCatalogImages(CatalogImageContracts.Query query) {
        CatalogImageFacts.validateQuery(query);
        imageRequire(read != null && !TransactionSynchronizationManager.isActualTransactionActive());
        Instant started = clock.instant();
        Instant until = started.plusSeconds(30);
        var before = imageRows(query);
        var items = new ArrayList<CatalogImageContracts.Item>();
        for (int i = 0; i < before.size(); i++) {
            var row = before.get(i);
            var expected = query.expectations().get(i);
            String ref = row.cover();
            if (ref == null || ref.isEmpty()) {
                imageRequire("NONE".equals(expected.expectedCoverKind()));
                items.add(new CatalogImageContracts.Item(row.goodsNo(), "NONE", PricingSnapshotHash.of(""),
                        null, row.version(), null, null));
            } else {
                imageRequire("REFERENCE".equals(expected.expectedCoverKind()));
                var resolver = media.get();
                if (resolver == null) throw BizException.of(ErrorCode.NOT_FOUND);
                var currentQuery = new MediaReferenceQueryPort.Query(query.entityNo(), query.storeNo(), ref);
                var current = resolver.resolve(currentQuery);
                var expectedQuery = new MediaReferenceQueryPort.Query(query.entityNo(), query.storeNo(),
                        expected.expectedCoverRef());
                var frozen = ref.equals(expected.expectedCoverRef()) ? current : resolver.resolve(expectedQuery);
                try {
                    MediaReferenceFacts.validate(currentQuery, current, clock.instant());
                    MediaReferenceFacts.validate(expectedQuery, frozen, clock.instant());
                } catch (IllegalStateException invalid) { throw BizException.of(ErrorCode.CONFLICT); }
                imageRequire(current.assetKey().equals(frozen.assetKey())
                        && current.assetIdentityHash().equals(frozen.assetIdentityHash()));
                until = minimum(until, minimum(current.validUntil(), frozen.validUntil()));
                items.add(new CatalogImageContracts.Item(row.goodsNo(), "REFERENCE", PricingSnapshotHash.of(ref),
                        current.assetKey(), row.version(), current.assetIdentityHash(), current.receiptNo()));
            }
        }
        imageRequire(before.equals(imageRows(query)));
        var facts = CatalogImageFacts.seal(new CatalogImageContracts.Facts(1, "CATALOG_IMAGE_IDENTITY", query,
                items, clock.instant(), until, null));
        try { CatalogImageFacts.validate(query, facts, clock.instant()); }
        catch (IllegalStateException invalid) { throw BizException.of(ErrorCode.CONFLICT); }
        return facts;
    }

    private List<GoodsSummary> imageRows(CatalogImageContracts.Query query) {
        var numbers = query.expectations().stream().map(CatalogImageContracts.Expectation::goodsNo).toList();
        var rows = read.execute(tx -> getGoodsBatch(query.entityNo(), query.storeNo(), numbers));
        if (rows == null || rows.size() != numbers.size()) throw BizException.of(ErrorCode.NOT_FOUND);
        return numbers.stream().map(number -> rows.stream().filter(row -> number.equals(row.goodsNo()))
                .findFirst().orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND))).toList();
    }

    private static Instant minimum(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static void imageRequire(boolean valid) {
        if (!valid) throw BizException.of(ErrorCode.CONFLICT);
    }

    @Override
    @Transactional(transactionManager = "transactionManager", readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public SkuIdentityFact getSku(String entity, String store, String number) {
        key(entity); key(store); key(number);
        return DataScopeContext.executeWithoutScope(() -> {
            var sku = skus.selectOne(Wrappers.<PrdSku>lambdaQuery()
                    .select(PrdSku::getSkuNo, PrdSku::getGoodsNo, PrdSku::getEntityNo,
                            PrdSku::getMarket, PrdSku::getVersion).eq(PrdSku::getEntityNo, entity)
                    .eq(PrdSku::getSkuNo, number).eq(PrdSku::getMarket, "CN"));
            if (sku == null || !entity.equals(sku.getEntityNo()) || !number.equals(sku.getSkuNo())
                    || !"CN".equals(sku.getMarket())) throw BizException.of(ErrorCode.NOT_FOUND);
            var owner = goods.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                    .select(PrdGoods::getGoodsNo, PrdGoods::getEntityNo, PrdGoods::getStoreNo)
                    .eq(PrdGoods::getGoodsNo, sku.getGoodsNo())
                    .eq(PrdGoods::getEntityNo, entity).eq(PrdGoods::getStoreNo, store));
            if (owner == null || !entity.equals(owner.getEntityNo()) || !store.equals(owner.getStoreNo())
                    || !sku.getGoodsNo().equals(owner.getGoodsNo())) throw BizException.of(ErrorCode.NOT_FOUND);
            return new SkuIdentityFact(sku.getSkuNo(), owner.getGoodsNo(), entity, store, sku.getVersion());
        });
    }

    @Override
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public GoodsFact getGoods(String entityNo, String storeNo, String goodsNo) {
        key(entityNo); key(storeNo); key(goodsNo);
        return DataScopeContext.executeWithoutScope(() -> {
            var row = goods.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                    .select(PrdGoods::getGoodsNo, PrdGoods::getEntityNo, PrdGoods::getStoreNo,
                            PrdGoods::getTitle, PrdGoods::getCover, PrdGoods::getOnSale,
                            PrdGoods::getAuditStatus, PrdGoods::getVersion)
                    .eq(PrdGoods::getEntityNo, entityNo).eq(PrdGoods::getStoreNo, storeNo)
                    .eq(PrdGoods::getGoodsNo, goodsNo));
            if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
            var variants = skus.selectList(Wrappers.<PrdSku>lambdaQuery()
                    .select(PrdSku::getSkuNo, PrdSku::getSpec, PrdSku::getVersion)
                    .eq(PrdSku::getEntityNo, entityNo).eq(PrdSku::getGoodsNo, goodsNo)
                    .eq(PrdSku::getMarket, "CN").orderByAsc(PrdSku::getSkuNo));
            return new GoodsFact(row.getGoodsNo(), row.getEntityNo(), row.getStoreNo(), row.getTitle(),
                    row.getCover(), Boolean.TRUE.equals(row.getOnSale()), row.getAuditStatus(), row.getVersion(),
                    variants.stream().map(s -> new SkuFact(s.getSkuNo(), s.getSpec(), s.getVersion())).toList());
        });
    }

    @Override
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<GoodsSummary> getGoodsBatch(String entity, String store, List<String> numbers) {
        key(entity); key(store);
        if (numbers == null || numbers.isEmpty() || numbers.size() > 256
                || new HashSet<>(numbers).size() != numbers.size()) throw BizException.of(ErrorCode.BAD_REQUEST);
        numbers.forEach(GoodsCatalogQueryPortImpl::key);
        return DataScopeContext.executeWithoutScope(() -> goods.selectList(Wrappers.<PrdGoods>lambdaQuery()
                .select(PrdGoods::getGoodsNo, PrdGoods::getEntityNo, PrdGoods::getStoreNo, PrdGoods::getTitle,
                        PrdGoods::getCover, PrdGoods::getOnSale, PrdGoods::getAuditStatus, PrdGoods::getVersion)
                .eq(PrdGoods::getEntityNo, entity).eq(PrdGoods::getStoreNo, store)
                .in(PrdGoods::getGoodsNo, numbers).orderByAsc(PrdGoods::getGoodsNo))
                .stream().map(row -> new GoodsSummary(row.getGoodsNo(), row.getEntityNo(), row.getStoreNo(),
                        row.getTitle(), row.getCover(), Boolean.TRUE.equals(row.getOnSale()), row.getAuditStatus(),
                        row.getVersion())).toList());
    }

    private static void key(String value) {
        if (value == null || value.isBlank() || value.length() > 32) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}

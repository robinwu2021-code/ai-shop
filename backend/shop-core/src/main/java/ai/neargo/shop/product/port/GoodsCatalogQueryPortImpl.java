package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
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
    private final TransactionTemplate read;
    private final Clock clock;

    /** Legacy metadata construction remains supported without inventing image capability. */
    public GoodsCatalogQueryPortImpl(GoodsMapper goods, SkuMapper skus) {
        this(goods, skus, () -> null, null, Clock.systemUTC());
    }

    /** Lazy media lookup avoids a product-to-adapter dependency and preserves optional owners. */
    @Autowired
    public GoodsCatalogQueryPortImpl(GoodsMapper goods, SkuMapper skus,
            ObjectProvider<MediaReferenceQueryPort> media,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this(goods, skus, media::getIfAvailable, manager, Clock.systemUTC());
    }

    /** Independent clock and RR boundaries keep external owner reads outside database locks. */
    public GoodsCatalogQueryPortImpl(GoodsMapper goods, SkuMapper skus, Supplier<MediaReferenceQueryPort> media,
            PlatformTransactionManager manager, Clock clock) {
        this.goods = goods;
        this.skus = skus;
        this.media = media;
        this.clock = clock;
        read = manager == null ? null : new TransactionTemplate(manager);
        if (read != null) {
            read.setReadOnly(true);
            read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        }
    }

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

package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStoreGoods;
import ai.neargo.shop.product.entity.PrdStorePrice;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreGoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StorePriceMapper;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Original product base/overlay facts; remote owner lookups never run inside this domain's read
 * transaction.
 */
@Component
public class CatalogPriceSourcePortImpl implements CatalogPriceSourcePort {
    private final SkuMapper skus;
    private final GoodsMapper goods;
    private final StorePriceMapper prices;
    private final StoreGoodsMapper visibility;
    private final StoreDirectoryPort stores;
    private final MerchantQueryPort merchants;
    private final MarketPort markets;
    private final PricingTaxSettingPort taxes;
    private final TransactionTemplate read;
    private final Clock clock;

    @Autowired
    public CatalogPriceSourcePortImpl(
            SkuMapper skus,
            GoodsMapper goods,
            StorePriceMapper prices,
            StoreGoodsMapper visibility,
            StoreDirectoryPort stores,
            MerchantQueryPort merchants,
            MarketPort markets,
            PricingTaxSettingPort taxes,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this(
                skus,
                goods,
                prices,
                visibility,
                stores,
                merchants,
                markets,
                taxes,
                manager,
                Clock.systemUTC());
    }

    public CatalogPriceSourcePortImpl(
            SkuMapper skus,
            GoodsMapper goods,
            StorePriceMapper prices,
            StoreGoodsMapper visibility,
            StoreDirectoryPort stores,
            MerchantQueryPort merchants,
            MarketPort markets,
            PricingTaxSettingPort taxes,
            PlatformTransactionManager manager,
            Clock clock) {
        this.skus = skus;
        this.goods = goods;
        this.prices = prices;
        this.visibility = visibility;
        this.stores = stores;
        this.merchants = merchants;
        this.markets = markets;
        this.taxes = taxes;
        this.clock = clock;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public MarketFacts market(MarketScope scope) {
        CatalogPriceSourceFacts.validateMarketScope(scope);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Catalog market capture requires an outside transaction");
        var at = clock.instant();
        var first = storeMarket(scope, false);
        if (!first.equals(storeMarket(scope, false))) throw BizException.of(ErrorCode.CONFLICT);
        var result =
                CatalogPriceSourceFacts.sealMarket(
                        new MarketFacts(
                                1,
                                "CATALOG_MARKET",
                                scope,
                                first.market(),
                                at,
                                at.plusSeconds(30),
                                null));
        CatalogPriceSourceFacts.validateMarketFacts(scope, result, clock.instant());
        return result;
    }

    @Override
    public ScopeFacts scope(MarketScope scope) {
        CatalogPriceSourceFacts.validateMarketScope(scope);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Catalog scope capture requires no local transaction");
        var at = clock.instant();
        var before = storeMarket(scope, false);
        var taxScope = new PricingTaxSettingPort.Scope("MAIN", scope.entityNo(), scope.storeNo());
        var tax = taxes.resolve(taxScope);
        if (tax == null
                || !"MAIN".equals(tax.tenantNo())
                || !scope.entityNo().equals(tax.entityNo())
                || !scope.storeNo().equals(tax.storeNo())) throw unsupported();
        if (!before.equals(storeMarket(scope, false)) || !tax.equals(taxes.resolve(taxScope)))
            throw BizException.of(ErrorCode.CONFLICT);
        var result =
                CatalogPriceSourceFacts.sealScope(
                        new ScopeFacts(
                                1,
                                "CATALOG_SCOPE",
                                scope,
                                before.market(),
                                CatalogPriceSourceFacts.taxFact(tax),
                                at,
                                at.plusSeconds(30),
                                null));
        CatalogPriceSourceFacts.validateScopeFacts(scope, result, clock.instant());
        return result;
    }

    @Override
    public Facts capture(Request request) {
        CatalogPriceSourceFacts.validateRequest(request);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Catalog source capture cannot join an existing transaction");
        var at = clock.instant();
        var before = context(request);
        var rows =
                DataScopeContext.executeWithoutScope(
                        () -> read.execute(tx -> productFacts(request, before.market())));
        if (!before.equals(context(request))) throw BizException.of(ErrorCode.CONFLICT);
        var result =
                new Facts(
                        1,
                        CatalogPriceSourceFacts.PURPOSE,
                        request,
                        before.market(),
                        CatalogPriceSourceFacts.taxFact(before.tax()),
                        rows,
                        at,
                        at.plusSeconds(30),
                        null);
        var captured =
                new Facts(
                        1,
                        result.purpose(),
                        request,
                        result.market(),
                        result.tax(),
                        rows,
                        at,
                        result.validUntil(),
                        CatalogPriceSourceFacts.receipt(result));
        CatalogPriceSourceFacts.validate(request, captured, clock.instant());
        return captured;
    }

    private StoreMarket storeMarket(MarketScope q, boolean activeRequired) {
        var cards = stores.cards(List.of(q.storeNo()));
        var card = cards == null ? null : cards.get(q.storeNo());
        if (card == null
                || !q.storeNo().equals(card.storeNo())
                || !q.entityNo().equals(card.entityNo())
                || !(card.active()
                        || !activeRequired
                                && StoreDirectoryPort.STORE_READONLY.equals(card.status())))
            throw BizException.of(ErrorCode.FORBIDDEN);
        String marketCode = merchants.marketOf(q.entityNo());
        if (marketCode == null || !marketCode.matches("[A-Z]{2}")) throw unsupported();
        var market = markets.find(marketCode).orElseThrow(CatalogPriceSourcePortImpl::unsupported);
        if (!marketCode.equals(market.market()) || !market.enabled()) throw unsupported();
        var descriptor = new Market(marketCode, market.currency(), market.currencyScale());
        try {
            CatalogPriceSourceFacts.validateMarket(descriptor);
        } catch (IllegalArgumentException invalid) {
            throw unsupported();
        }
        return new StoreMarket(q, card.status(), descriptor);
    }

    private Context context(Request q) {
        var descriptor = storeMarket(new MarketScope(q.entityNo(), q.storeNo()), true).market();
        var tax = taxes.resolve(new PricingTaxSettingPort.Scope("MAIN", q.entityNo(), q.storeNo()));
        if (tax == null
                || !"MAIN".equals(tax.tenantNo())
                || !q.entityNo().equals(tax.entityNo())
                || !q.storeNo().equals(tax.storeNo())) throw unsupported();
        return new Context(descriptor, tax);
    }

    private List<Price> productFacts(Request q, Market market) {
        var selected =
                skus.selectList(
                        Wrappers.<PrdSku>lambdaQuery()
                                .eq(PrdSku::getEntityNo, q.entityNo())
                                .eq(PrdSku::getMarket, market.market())
                                .in(PrdSku::getSkuNo, q.skuNos()));
        var bySku = new HashMap<String, PrdSku>();
        for (var sku : selected) {
            if (!q.entityNo().equals(sku.getEntityNo())
                    || !market.market().equals(sku.getMarket())
                    || !q.skuNos().contains(sku.getSkuNo())
                    || bySku.putIfAbsent(sku.getSkuNo(), sku) != null)
                throw new IllegalStateException("Ambiguous original SKU facts");
        }
        if (bySku.size() != q.skuNos().size()) throw notFound();
        var goodsNos = selected.stream().map(PrdSku::getGoodsNo).distinct().toList();
        var heads =
                goods.selectList(
                        Wrappers.<PrdGoods>lambdaQuery()
                                .eq(PrdGoods::getEntityNo, q.entityNo())
                                .in(PrdGoods::getGoodsNo, goodsNos));
        var byGoods = new HashMap<String, PrdGoods>();
        for (var head : heads) {
            if (!q.entityNo().equals(head.getEntityNo())
                    || !Boolean.TRUE.equals(head.getOnSale())
                    || !"APPROVED".equals(head.getAuditStatus())
                    || head.getStoreNo() != null
                            && !head.getStoreNo().isBlank()
                            && !q.storeNo().equals(head.getStoreNo())) throw notFound();
            if (byGoods.putIfAbsent(head.getGoodsNo(), head) != null)
                throw new IllegalStateException("Ambiguous original goods facts");
        }
        if (byGoods.size() != goodsNos.size()) throw notFound();
        var overlays =
                visibility.selectList(
                        Wrappers.<PrdStoreGoods>lambdaQuery()
                                .eq(PrdStoreGoods::getEntityNo, q.entityNo())
                                .in(PrdStoreGoods::getGoodsNo, goodsNos));
        var managed = new HashSet<String>();
        var here = new HashMap<String, PrdStoreGoods>();
        for (var overlay : overlays) {
            if (!q.entityNo().equals(overlay.getEntityNo())
                    || !goodsNos.contains(overlay.getGoodsNo()))
                throw new IllegalStateException("Foreign original visibility facts");
            managed.add(overlay.getGoodsNo());
            if (q.storeNo().equals(overlay.getStoreNo())
                    && here.putIfAbsent(overlay.getGoodsNo(), overlay) != null)
                throw new IllegalStateException("Ambiguous original visibility facts");
        }
        var storePrices =
                prices.selectList(
                        Wrappers.<PrdStorePrice>lambdaQuery()
                                .eq(PrdStorePrice::getEntityNo, q.entityNo())
                                .eq(PrdStorePrice::getStoreNo, q.storeNo())
                                .eq(PrdStorePrice::getMarket, market.market())
                                .in(PrdStorePrice::getSkuNo, q.skuNos()));
        var byPrice = new HashMap<String, PrdStorePrice>();
        for (var price : storePrices) {
            if (!q.entityNo().equals(price.getEntityNo())
                    || !q.storeNo().equals(price.getStoreNo())
                    || !market.market().equals(price.getMarket())
                    || !q.skuNos().contains(price.getSkuNo()))
                throw new IllegalStateException("Foreign original store price facts");
            if (byPrice.putIfAbsent(price.getSkuNo(), price) != null)
                throw new IllegalStateException("Ambiguous original store price facts");
        }
        var result = new ArrayList<Price>();
        for (String number : q.skuNos()) {
            var sku = bySku.get(number);
            var head = byGoods.get(sku.getGoodsNo());
            var localVisibility = here.get(head.getGoodsNo());
            if (managed.contains(head.getGoodsNo())
                    && (localVisibility == null
                            || !Boolean.TRUE.equals(localVisibility.getOnSale()))) throw notFound();
            var storePrice = byPrice.get(number);
            boolean override = storePrice != null && storePrice.getPrice() != null;
            Long amount = override ? storePrice.getPrice() : sku.getPrice();
            var versions =
                    new Versions(
                            rowVersion(head),
                            rowVersion(sku),
                            rowVersion(storePrice),
                            rowVersion(localVisibility));
            var row =
                    new Price(
                            number,
                            head.getGoodsNo(),
                            amount == null ? "UNAVAILABLE" : "KNOWN",
                            amount,
                            override ? "STORE_PRICE" : "ENTITY_SKU",
                            versions,
                            null);
            result.add(
                    new Price(
                            row.skuNo(),
                            row.goodsNo(),
                            row.status(),
                            amount,
                            row.sourceKind(),
                            versions,
                            CatalogPriceSourceFacts.revision(q, market, row)));
        }
        return List.copyOf(result);
    }

    private static Long rowVersion(BaseEntity row) {
        if (row == null) return null;
        var version = row.getVersion();
        if (version == null || version < 0 || version > MoneyMinor.MAX_JSON_SAFE_MINOR)
            throw new IllegalStateException("Missing or invalid original row version");
        return version;
    }

    private record StoreMarket(MarketScope scope, String status, Market market) {}

    private record Context(Market market, MerchantTaxSnapshot tax) {}

    private static BizException notFound() {
        return BizException.of(ErrorCode.NOT_FOUND);
    }

    private static BizException unsupported() {
        return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
}

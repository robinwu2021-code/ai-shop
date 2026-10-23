package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.service.StoreStockReader;
import ai.neargo.shop.spi.product.DishOfferQueryPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Uses original price and stock readers, with explicit ownership before any unscoped legacy lookup. */
@Component
public class DishOfferQueryPortImpl implements DishOfferQueryPort {
    private final SkuMapper skus;
    private final GoodsMapper goods;
    private final GoodsQueryPort prices;
    private final StoreStockReader stock;
    private final String authority;

    public DishOfferQueryPortImpl(SkuMapper skus, GoodsMapper goods, GoodsQueryPort prices, StoreStockReader stock,
            @Value("${shop.inventory.stock-authority:PLATFORM}") String authority) {
        this.skus = skus; this.goods = goods; this.prices = prices; this.stock = stock; this.authority = authority;
    }

    @Override
    @Transactional(transactionManager="transactionManager", readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public List<Offer> getOffers(String entity, String store, List<String> numbers) {
        DishOfferQueryPort.validateLookup(entity, store, numbers);
        return DataScopeContext.executeWithoutScope(() -> read(entity, store, numbers));
    }

    private List<Offer> read(String entity, String store, List<String> numbers) {
        var owned = skus.selectList(Wrappers.<PrdSku>lambdaQuery()
                .select(PrdSku::getSkuNo, PrdSku::getGoodsNo, PrdSku::getEntityNo, PrdSku::getPrice)
                .eq(PrdSku::getEntityNo, entity).eq(PrdSku::getMarket, "CN").in(PrdSku::getSkuNo, numbers));
        if (owned.isEmpty()) return List.of();
        var heads = goods.selectList(Wrappers.<PrdGoods>lambdaQuery()
                .select(PrdGoods::getGoodsNo).eq(PrdGoods::getEntityNo, entity).eq(PrdGoods::getStoreNo, store)
                .eq(PrdGoods::getOnSale, true).eq(PrdGoods::getAuditStatus, "APPROVED")
                .in(PrdGoods::getGoodsNo, owned.stream().map(PrdSku::getGoodsNo).distinct().toList()))
                .stream().map(PrdGoods::getGoodsNo).collect(Collectors.toSet());
        var bySku = owned.stream().filter(s -> heads.contains(s.getGoodsNo()))
                .collect(Collectors.toMap(PrdSku::getSkuNo, Function.identity()));
        if (bySku.isEmpty()) return List.of();
        var authorized = bySku.keySet().stream().sorted().toList();
        var snapshots = prices.snapshot(authorized, Map.of(entity, store));
        var configured = prices.storePrices(Map.of(entity, store), authorized);
        if (snapshots == null || configured == null) throw new IllegalStateException("Missing original offer facts");
        // Legacy stock readers iterate all markets. Ambiguous identities cannot provide scoped availability.
        var variants = skus.selectList(Wrappers.<PrdSku>lambdaQuery().select(PrdSku::getSkuNo)
                .in(PrdSku::getSkuNo, authorized)).stream()
                .collect(Collectors.groupingBy(PrdSku::getSkuNo, Collectors.counting()));
        var safeStock = "PLATFORM".equals(authority) ? authorized.stream()
                .filter(n -> variants.getOrDefault(n, 0L) == 1L).toList() : List.<String>of();
        var quantities = safeStock.isEmpty() ? Map.<String,Integer>of() : stock.available(safeStock, store);
        if (quantities == null) throw new IllegalStateException("Missing original stock response");
        var result = new ArrayList<Offer>();
        for (String number : authorized) {
            var source = bySku.get(number);
            var snapshot = snapshots.get(number);
            if (snapshot == null) throw new IllegalStateException("Missing original price snapshot");
            if (!number.equals(snapshot.skuNo()) || !source.getGoodsNo().equals(snapshot.goodsNo())
                    || !entity.equals(snapshot.merchantNo()) || !store.equals(snapshot.storeNo())) {
                throw new IllegalStateException("Original offer ownership changed");
            }
            if (!snapshot.onSale()) continue;
            boolean knownPrice = snapshot.price() >= 0 && (source.getPrice() != null
                    || configured.containsKey(number) || snapshot.price() > 0);
            Integer available = safeStock.contains(number) ? quantities.get(number) : null;
            if (available != null && available < 0) throw new IllegalStateException("Invalid original stock quantity");
            result.add(new Offer(number, source.getGoodsNo(), entity, store,
                    knownPrice ? snapshot.price() : null, knownPrice ? "CNY" : null,
                    knownPrice ? "KNOWN" : "UNAVAILABLE", available, available == null ? "UNKNOWN" : "KNOWN", true));
        }
        return List.copyOf(result);
    }
}

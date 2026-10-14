package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStoreStock;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper;
import ai.neargo.shop.product.service.AiGoodsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 见 {@link AiGoodsService}。 */
@Service
public class AiGoodsServiceImpl implements AiGoodsService {

    private static final int CHUNK = 500;

    private final GoodsMapper goodsMapper;
    private final SkuMapper skuMapper;
    /** 覆盖层规则的唯一一份实现 —— 店级库存怎么算只在它里面 */
    private final ai.neargo.shop.product.service.StoreStockReader storeStockReader;

    public AiGoodsServiceImpl(GoodsMapper goodsMapper, SkuMapper skuMapper,
                              ai.neargo.shop.product.service.StoreStockReader storeStockReader) {
        this.goodsMapper = goodsMapper;
        this.skuMapper = skuMapper;
        this.storeStockReader = storeStockReader;
    }

    @Override
    public List<GoodsRow> cumulativeRanking(String merchantNo, String storeNo, boolean ascending, int limit) {
        List<PrdGoods> goods = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(
                Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getEntityNo, merchantNo)
                        .eq(PrdGoods::getOnSale, true)));
        Comparator<PrdGoods> bySales = Comparator.comparingInt(g -> g.getSales() == null ? 0 : g.getSales());
        if (!ascending) {
            bySales = bySales.reversed();
        }
        List<PrdGoods> top = goods.stream().sorted(bySales.thenComparing(PrdGoods::getGoodsNo))
                .limit(Math.max(1, limit)).toList();
        return rows(merchantNo, storeNo, top);
    }

    @Override
    public Page list(String merchantNo, String storeNo, String categoryNo, String keyword,
                     int pageNum, int pageSize) {
        var w = Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getEntityNo, merchantNo);
        if (categoryNo != null && !categoryNo.isBlank()) {
            w.eq(PrdGoods::getCategoryNo, categoryNo);
        }
        if (keyword != null && !keyword.isBlank()) {
            w.like(PrdGoods::getTitle, keyword.trim());
        }
        w.orderByDesc(PrdGoods::getId);
        // 全限定：本接口有个同名的 Page 记录
        var req = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<PrdGoods>(
                Math.max(1, pageNum), Math.min(100, Math.max(1, pageSize)));
        var page = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectPage(req, w));
        return new Page(rows(merchantNo, storeNo, page.getRecords()), page.getTotal());
    }

    @Override
    public Optional<Detail> detail(String merchantNo, String storeNo, String goodsNo) {
        PrdGoods g = goods(merchantNo, goodsNo);
        if (g == null) {
            return Optional.empty();
        }
        List<PrdSku> skus = skus(merchantNo, List.of(goodsNo));
        Map<String, Long> stock = stock(merchantNo, storeNo, skus);
        List<SkuRow> skuRows = skus.stream().map(s -> skuRow(s, g, stock)).toList();
        return Optional.of(new Detail(row(g, skus, stock), g.getCategoryNo(), g.getSubtitle(), skuRows));
    }

    @Override
    public Optional<SkuRow> sku(String merchantNo, String storeNo, String skuNo) {
        PrdSku s = DataScopeContext.executeWithoutScope(() -> skuMapper.selectOne(
                Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getEntityNo, merchantNo).eq(PrdSku::getSkuNo, skuNo)
                        .last("LIMIT 1")));
        if (s == null) {
            return Optional.empty();
        }
        PrdGoods g = goods(merchantNo, s.getGoodsNo());
        return Optional.of(skuRow(s, g, stock(merchantNo, storeNo, List.of(s))));
    }

    @Override
    public List<SkuRow> lowStock(String merchantNo, String storeNo, int threshold, int limit) {
        Map<String, PrdGoods> onSale = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(
                        Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getEntityNo, merchantNo)
                                .eq(PrdGoods::getOnSale, true)))
                .stream().collect(Collectors.toMap(PrdGoods::getGoodsNo, g -> g, (a, b) -> a));
        if (onSale.isEmpty()) {
            return List.of();
        }
        List<PrdSku> skus = skus(merchantNo, new ArrayList<>(onSale.keySet()));
        Map<String, Long> stock = stock(merchantNo, storeNo, skus);
        return skus.stream().map(s -> skuRow(s, onSale.get(s.getGoodsNo()), stock))
                .filter(r -> r.stock() <= threshold)
                .sorted(Comparator.comparingLong(SkuRow::stock).thenComparing(SkuRow::skuNo))
                .limit(Math.max(1, limit)).toList();
    }

    // ---------------------------------------------------------------- 组装

    private List<GoodsRow> rows(String merchantNo, String storeNo, List<PrdGoods> goods) {
        if (goods.isEmpty()) {
            return List.of();
        }
        List<PrdSku> skus = skus(merchantNo, goods.stream().map(PrdGoods::getGoodsNo).toList());
        Map<String, Long> stock = stock(merchantNo, storeNo, skus);
        Map<String, List<PrdSku>> byGoods = skus.stream().collect(Collectors.groupingBy(PrdSku::getGoodsNo));
        return goods.stream().map(g -> row(g, byGoods.getOrDefault(g.getGoodsNo(), List.of()), stock)).toList();
    }

    private static GoodsRow row(PrdGoods g, List<PrdSku> skus, Map<String, Long> stock) {
        long min = skus.stream().mapToLong(s -> nz(s.getPrice())).min().orElse(0);
        long max = skus.stream().mapToLong(s -> nz(s.getPrice())).max().orElse(0);
        long total = skus.stream().mapToLong(s -> stock.getOrDefault(s.getSkuNo(), 0L)).sum();
        return new GoodsRow(g.getGoodsNo(), g.getTitle(), g.getCover(), min, max, total,
                g.getSales() == null ? 0 : g.getSales(), Boolean.TRUE.equals(g.getOnSale()));
    }

    private static SkuRow skuRow(PrdSku s, PrdGoods g, Map<String, Long> stock) {
        return new SkuRow(s.getSkuNo(), s.getGoodsNo(), g == null ? null : g.getTitle(), s.getSpec(),
                nz(s.getPrice()), s.getCostPrice(), stock.getOrDefault(s.getSkuNo(), 0L),
                g != null && Boolean.TRUE.equals(g.getOnSale()));
    }

    private PrdGoods goods(String merchantNo, String goodsNo) {
        if (goodsNo == null) {
            return null;
        }
        return DataScopeContext.executeWithoutScope(() -> goodsMapper.selectOne(
                Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getEntityNo, merchantNo)
                        .eq(PrdGoods::getGoodsNo, goodsNo).last("LIMIT 1")));
    }

    private List<PrdSku> skus(String merchantNo, List<String> goodsNos) {
        List<PrdSku> out = new ArrayList<>();
        for (List<String> chunk : chunks(goodsNos)) {
            out.addAll(DataScopeContext.executeWithoutScope(() -> skuMapper.selectList(
                    Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getEntityNo, merchantNo)
                            .in(PrdSku::getGoodsNo, chunk))));
        }
        return out;
    }

    /**
     * SKU → 可售库存。**规则不在这里，在 {@link StoreStockReader}** ——
     * 覆盖层（没有店级行 → 主体总量；有了任意一条 → 没行的店视为 0）此前在三处各有一份，
     * 而 {@code StockPortImpl} 的注释早写着「两处判据不一致会出现半边账，
     * 而两个数都还是正的，没有任何地方会报错」。2026-09-30 并成一份。
     *
     * <p>这里只剩两件本地的事：转成 long（问答那边的行是 long），
     * 以及**按已经查出来的 SKU 过一遍** —— 读取器按 skuNo 查，
     * 而这条路的 skus 已经按主体筛过了。
     */
    private Map<String, Long> stock(String merchantNo, String storeNo, Collection<PrdSku> skus) {
        if (skus.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, Integer> avail = storeStockReader.available(
                skus.stream().map(PrdSku::getSkuNo).toList(), storeNo);
        Map<String, Long> out = new HashMap<>();
        for (PrdSku s : skus) {
            out.put(s.getSkuNo(), (long) avail.getOrDefault(s.getSkuNo(), 0));
        }
        return out;
    }

    private static <T> List<List<T>> chunks(List<T> all) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += CHUNK) {
            out.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
        }
        return out;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nzi(Integer v) {
        return v == null ? 0 : v;
    }
}

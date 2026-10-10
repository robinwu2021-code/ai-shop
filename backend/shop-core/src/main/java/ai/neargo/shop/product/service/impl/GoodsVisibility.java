package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdStoreGoods;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreGoodsMapper;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 买家在某个小区（或某个区）<b>能看到哪些货、由哪家店提供</b> —— 查询时现算
 * （方案-商品可见性改查询时关联 §2.2）。
 *
 * <p>两步，各归各的域：
 * <ol>
 *   <li>商家域回答「哪些门店服务这里」（{@link MerchantQueryPort#servingStores}，主体与门店都是 ACTIVE、且送得到）；</li>
 *   <li>这里回答「这些门店在卖什么」：在架，且（这件货一条店级行都没有 → 主体下所有门店都算在卖；
 *       有店级行 → 只有在架的那几家店）。</li>
 * </ol>
 *
 * <p>此前这件事靠一张预先算好的社区池（每个小区 × 每件货 × 每家店一行，线上 53 万行），
 * 每改一项设置就要重算一遍，而且漏了重算的地方（运营审核范围、主体停业）就静默地错着。
 * 现在设置只改它自己那一行，答案在这里现算，不存在「忘了同步」。
 *
 * <p><b>不写跨域 join</b>：门店号由商家域算好作为参数传进来；两张商品表也分两次查、在内存里合，
 * 不在 SQL 里 join（两张表的字符集不一致，生产上 join 直接报 1267）。
 */
@Component
public class GoodsVisibility {

    private final MerchantQueryPort merchantPort;
    private final GoodsMapper goodsMapper;
    private final StoreGoodsMapper storeGoodsMapper;

    private final ai.neargo.shop.community.service.ConsumerProfileResolver profileResolver;

    public GoodsVisibility(MerchantQueryPort merchantPort, GoodsMapper goodsMapper,
                           StoreGoodsMapper storeGoodsMapper,
                           ai.neargo.shop.community.service.ConsumerProfileResolver profileResolver) {
        this.merchantPort = merchantPort;
        this.goodsMapper = goodsMapper;
        this.storeGoodsMapper = storeGoodsMapper;
        this.profileResolver = profileResolver;
    }

    /**
     * 这个小区（没给小区就按区）能看到的在架商品号。
     *
     * @return {@code null} = 小区和区都没给，不筛；<b>空列表 = 筛出来一件都没有</b>。
     *         两者混在一起，就是「没铺货的区看到全平台商品」
     */
    public List<String> goodsNos(String communityNo, String regionCode) {
        return goodsNos(communityNo, regionCode, null, null);
    }

    /**
     * 同上，但带消费者坐标（ADR-034）：有坐标才能命中商家画的多边形范围，
     * 也才能在「只有定位、没落到任何小区」时按省市区匹配。
     */
    public List<String> goodsNos(String communityNo, String regionCode, Integer latE6, Integer lngE6) {
        Map<String, Set<String>> serving = serving(communityNo, regionCode, latE6, lngE6);
        if (serving == null) {
            return null;
        }
        if (serving.isEmpty()) {
            return List.of();
        }
        List<PrdGoods> goods = onSaleOf(serving.keySet());
        Map<String, List<PrdStoreGoods>> shelf = shelfOf(goods.stream().map(PrdGoods::getGoodsNo).toList());
        return goods.stream()
                .filter(g -> !sellingAt(g, shelf, serving.get(g.getEntityNo())).isEmpty())
                .map(PrdGoods::getGoodsNo)
                .toList();
    }

    /**
     * 每件货<b>由哪家门店提供</b>（TDD-C端商品归属门店与库存校验 AC1/AC2）：
     * 服务这里、且在架卖它的门店里，<b>默认店优先，否则门店号最小</b>。
     * 必须确定 —— 同一件货刷两次不能显示两家店。
     */
    public Map<String, String> providingStores(String communityNo, String regionCode, Collection<String> goodsNos) {
        return providingStores(communityNo, regionCode, null, null, goodsNos);
    }

    /** 同上，带消费者坐标（ADR-034） */
    public Map<String, String> providingStores(String communityNo, String regionCode,
                                               Integer latE6, Integer lngE6, Collection<String> goodsNos) {
        Map<String, Set<String>> serving = serving(communityNo, regionCode, latE6, lngE6);
        if (serving == null || serving.isEmpty() || goodsNos == null || goodsNos.isEmpty()) {
            return Map.of();
        }
        List<PrdGoods> goods = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(
                Wrappers.<PrdGoods>lambdaQuery()
                        .select(PrdGoods::getGoodsNo, PrdGoods::getEntityNo, PrdGoods::getStoreNo)
                        .in(PrdGoods::getGoodsNo, goodsNos)
                        .eq(PrdGoods::getOnSale, true)));
        Map<String, List<PrdStoreGoods>> shelf = shelfOf(goods.stream().map(PrdGoods::getGoodsNo).toList());
        Map<String, String> defaults = merchantPort.defaultStoreNos(
                goods.stream().map(PrdGoods::getEntityNo).distinct().toList());
        Map<String, String> out = new HashMap<>();
        for (PrdGoods g : goods) {
            TreeSet<String> stores = sellingAt(g, shelf, serving.get(g.getEntityNo()));
            if (stores.isEmpty()) {
                continue;
            }
            String d = defaults.get(g.getEntityNo());
            out.put(g.getGoodsNo(), d != null && stores.contains(d) ? d : stores.first());
        }
        return out;
    }

    /** 这件货送不送得到这个小区。没给小区返回 {@code null}（端上据此不显示这一行） */
    public Boolean deliverable(String goodsNo, String communityNo) {
        if (communityNo == null || communityNo.isBlank()) {
            return null;
        }
        PrdGoods g = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectOne(
                Wrappers.<PrdGoods>lambdaQuery()
                        .select(PrdGoods::getGoodsNo, PrdGoods::getEntityNo, PrdGoods::getStoreNo, PrdGoods::getOnSale)
                        .eq(PrdGoods::getGoodsNo, goodsNo).last("limit 1")));
        if (g == null || !Boolean.TRUE.equals(g.getOnSale())) {
            return false;
        }
        Set<String> stores = merchantPort.servingStores(communityNo).get(g.getEntityNo());
        return stores != null && !sellingAt(g, shelfOf(List.of(goodsNo)), stores).isEmpty();
    }

    /** 一个小区的供给：多少家商家、多少件货在这里能被买到 */
    public record Supply(int merchants, int goods) {
    }

    /**
     * 每个小区的供给（运营端「供给分布」）。每家 ACTIVE 门店算一次可达，再按「这家店在卖什么」聚合。
     * 一家商家只有在这里真有货卖才算一家 —— 与此前按池行计数同一口径。
     */
    public Map<String, Supply> supplyByCommunity() {
        List<MerchantQueryPort.StoreCoverage> coverage = merchantPort.storeCoverage();
        if (coverage.isEmpty()) {
            return Map.of();
        }
        List<PrdGoods> goods = onSaleOf(coverage.stream().map(MerchantQueryPort.StoreCoverage::entityNo)
                .distinct().toList());
        Map<String, List<PrdStoreGoods>> shelf = shelfOf(goods.stream().map(PrdGoods::getGoodsNo).toList());
        Map<String, Set<String>> merchants = new HashMap<>();
        Map<String, Set<String>> goodsHere = new HashMap<>();
        for (MerchantQueryPort.StoreCoverage c : coverage) {
            List<String> sold = goods.stream()
                    .filter(g -> c.entityNo().equals(g.getEntityNo()))
                    .filter(g -> !sellingAt(g, shelf, Set.of(c.storeNo())).isEmpty())
                    .map(PrdGoods::getGoodsNo)
                    .toList();
            if (sold.isEmpty()) {
                continue;
            }
            for (String communityNo : c.communityNos()) {
                merchants.computeIfAbsent(communityNo, k -> new java.util.HashSet<>()).add(c.entityNo());
                goodsHere.computeIfAbsent(communityNo, k -> new java.util.HashSet<>()).addAll(sold);
            }
        }
        Map<String, Supply> out = new HashMap<>();
        merchants.forEach((no, ms) -> out.put(no, new Supply(ms.size(), goodsHere.get(no).size())));
        return out;
    }

    /** 小区优先；没有小区按区；两者都没有返回 {@code null}（= 不筛） */
    private Map<String, Set<String>> serving(String communityNo, String regionCode,
                                             Integer latE6, Integer lngE6) {
        /*
         * 三个入参合成一个消费者画像，一次算完行政五级、聚落、地图多边形、「不限」与各级排除（ADR-034）。
         * 此前是「有小区走小区、否则按区里的开放小区逐个判」—— 那条路让「所在区没有运营开过小区」的
         * 消费者一片空白，而商家明明框了整个市。
         */
        if ((communityNo == null || communityNo.isBlank())
                && (regionCode == null || regionCode.isBlank())
                && !ai.neargo.shop.spi.reach.ConsumerProfile.validCoords(latE6, lngE6)) {
            return null;   // 什么都没给：不按地址筛（与改造前逐字相同）
        }
        return merchantPort.servingStores(profileResolver.resolve(communityNo, regionCode, latE6, lngE6));
    }


    /**
     * 服务这里的门店里，在架卖这件货的那几家（有序，取第一家要确定）。
     * 这件货一条店级行都没有 → 主体下服务这里的门店都算在卖（单店时代的商品全在这一支）。
     *
     * <p>★ <b>商品只属于一家门店</b>（V384，ADR-031）：有归属时候选只剩它自己那家 ——
     * 同主体的别家店服务这里也不算，它们卖的是各自的商品。没有归属（只有测试种子会这样）
     * 沿用投影规则。
     */
    private static TreeSet<String> sellingAt(PrdGoods g, Map<String, List<PrdStoreGoods>> shelf, Set<String> serving) {
        TreeSet<String> out = new TreeSet<>();
        if (serving == null || serving.isEmpty()) {
            return out;
        }
        String own = g.getStoreNo();
        if (own != null && !own.isBlank()) {
            if (!serving.contains(own)) {
                return out;
            }
            serving = Set.of(own);
        }
        List<PrdStoreGoods> rows = shelf.getOrDefault(g.getGoodsNo(), List.of());
        if (rows.isEmpty()) {
            out.addAll(serving);
            return out;
        }
        for (PrdStoreGoods r : rows) {
            if (Boolean.TRUE.equals(r.getOnSale()) && serving.contains(r.getStoreNo())) {
                out.add(r.getStoreNo());
            }
        }
        return out;
    }

    private List<PrdGoods> onSaleOf(Collection<String> entityNos) {
        return DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(
                Wrappers.<PrdGoods>lambdaQuery()
                        .select(PrdGoods::getGoodsNo, PrdGoods::getEntityNo, PrdGoods::getStoreNo)
                        .in(PrdGoods::getEntityNo, entityNos)
                        .eq(PrdGoods::getOnSale, true)));
    }

    private Map<String, List<PrdStoreGoods>> shelfOf(Collection<String> goodsNos) {
        Map<String, List<PrdStoreGoods>> out = new HashMap<>();
        if (goodsNos.isEmpty()) {
            return out;
        }
        DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.selectList(
                        Wrappers.<PrdStoreGoods>lambdaQuery()
                                .select(PrdStoreGoods::getGoodsNo, PrdStoreGoods::getStoreNo, PrdStoreGoods::getOnSale)
                                .in(PrdStoreGoods::getGoodsNo, goodsNos)))
                .forEach(r -> out.computeIfAbsent(r.getGoodsNo(), k -> new java.util.ArrayList<>()).add(r));
        return out;
    }
}

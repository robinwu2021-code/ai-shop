package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockLockMapper;
import ai.neargo.shop.product.service.InvManagedService;
import ai.neargo.shop.spi.product.InventoryProductPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 库存对接读写商品的出口（第 4 批）。判据仍在 {@link InvManagedService} 一处，这里只转成事实记录。
 *
 * <p>都绕过数据域：库存对接是跑批与运营全平台视图，没有商家上下文 —— 不绕过的话拿到的是空集，
 * 而空集在孤儿清理里意味着「全判成孤儿」，不报错。
 */
@Component
public class InventoryProductPortImpl implements InventoryProductPort {

    private final InvManagedService invManaged;
    private final GoodsMapper goodsMapper;
    private final SkuMapper skuMapper;
    private final StockLockMapper lockMapper;

    public InventoryProductPortImpl(InvManagedService invManaged, GoodsMapper goodsMapper, SkuMapper skuMapper,
                                    StockLockMapper lockMapper) {
        this.invManaged = invManaged;
        this.goodsMapper = goodsMapper;
        this.skuMapper = skuMapper;
        this.lockMapper = lockMapper;
    }

    @Override
    public GoodsFact goods(String goodsNo) {
        PrdGoods g = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectOne(
                Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getGoodsNo, goodsNo).last("limit 1")));
        return fact(g);
    }

    @Override
    public GoodsFact goodsOf(String entityNo, String goodsNo) {
        return fact(invManaged.goodsOf(entityNo, goodsNo));
    }

    @Override
    public Map<String, GoodsFact> goodsOfBatch(String entityNo, Collection<String> goodsNos) {
        Map<String, GoodsFact> out = new LinkedHashMap<>();
        invManaged.goodsOfBatch(entityNo, goodsNos).forEach((no, g) -> out.put(no, fact(g)));
        return out;
    }

    @Override
    public List<GoodsFact> onSaleGoods(String entityNo) {
        if (entityNo == null) {
            return List.of();
        }
        return DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(
                        Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getEntityNo, entityNo)
                                .eq(PrdGoods::getOnSale, true)))
                .stream().map(InventoryProductPortImpl::fact).toList();
    }

    @Override
    public List<GoodsFact> managedGoods(String entityNo) {
        return invManaged.managedGoods(entityNo).stream().map(InventoryProductPortImpl::fact).toList();
    }

    @Override
    public List<GoodsFact> goodsAffectedByCategory(String entityNo, String categoryNo, boolean managed) {
        return invManaged.goodsAffectedByCategory(entityNo, categoryNo, managed).stream()
                .map(InventoryProductPortImpl::fact).toList();
    }

    @Override
    public List<GoodsFact> unmanagedGoods() {
        return DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(
                        Wrappers.<PrdGoods>lambdaQuery()
                                .select(PrdGoods::getId, PrdGoods::getGoodsNo, PrdGoods::getEntityNo,
                                        PrdGoods::getCategoryNo, PrdGoods::getInvMode)))
                .stream()
                .filter(g -> g.getEntityNo() != null && !invManaged.isManaged(g))
                .map(InventoryProductPortImpl::fact)
                .toList();
    }

    @Override
    public boolean isManaged(String entityNo, String goodsNo) {
        PrdGoods g = invManaged.goodsOf(entityNo, goodsNo);
        return g != null && invManaged.isManaged(g);
    }

    @Override
    public void saveGoodsMode(String entityNo, String goodsNo, String mode, String operator) {
        invManaged.saveGoodsMode(require(entityNo, goodsNo), mode, operator);
    }

    @Override
    public void publishModeChanged(String entityNo, String goodsNo, boolean managed) {
        invManaged.publishModeChanged(require(entityNo, goodsNo), managed);
    }

    /** 本市场那一行，一个 skuNo 一条（与 {@link InvManagedService#skusOf} 同口径） */
    @Override
    public List<SkuFact> skusOfGoods(Collection<String> goodsNos) {
        return invManaged.skusOf(goodsNos).stream().map(InventoryProductPortImpl::fact).toList();
    }

    @Override
    public List<SkuFact> skus(String entityNo, Collection<String> skuNos) {
        if (entityNo == null || skuNos == null || skuNos.isEmpty()) {
            return List.of();
        }
        Map<String, SkuFact> bySku = new LinkedHashMap<>();
        DataScopeContext.executeWithoutScope(() -> skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getEntityNo, entityNo).in(PrdSku::getSkuNo, new HashSet<>(skuNos))))
                .forEach(s -> bySku.putIfAbsent(s.getSkuNo(), fact(s)));
        return List.copyOf(bySku.values());
    }

    @Override
    public Set<String> aliveSkuNos(Collection<String> skuNos) {
        Set<String> alive = new HashSet<>();
        if (skuNos == null || skuNos.isEmpty()) {
            return alive;
        }
        DataScopeContext.executeWithoutScope(() -> skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .select(PrdSku::getSkuNo).in(PrdSku::getSkuNo, new HashSet<>(skuNos))))
                .forEach(s -> alive.add(s.getSkuNo()));
        return alive;
    }

    @Override
    public Map<String, GoodsStats> goodsStats(Set<String> entityNos) {
        Map<String, GoodsStats> out = new HashMap<>();
        if (entityNos == null || entityNos.isEmpty()) {
            return out;
        }
        List<Map<String, Object>> rows = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectMaps(
                Wrappers.<PrdGoods>query()
                        .select("entity_no",
                                "COUNT(*) AS total",
                                "SUM(CASE WHEN audit_status = '" + PrdGoods.AUDIT_AUDITING + "' THEN 1 ELSE 0 END) AS auditing",
                                "SUM(CASE WHEN on_sale = 1 THEN 1 ELSE 0 END) AS on_sale_cnt")
                        .eq("deleted", 0)
                        .in("entity_no", entityNos)
                        .groupBy("entity_no")));
        for (Map<String, Object> r : rows) {
            Object entity = r.get("entity_no");
            out.put(entity == null ? null : entity.toString(),
                    new GoodsStats(num(r.get("total")), num(r.get("auditing")), num(r.get("on_sale_cnt"))));
        }
        return out;
    }

    @Override
    public Set<String> lockNosHoldingSku(String skuNo, Collection<String> lockNos) {
        if (lockNos == null || lockNos.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(DataScopeContext.executeWithoutScope(() -> lockMapper.lockNosHoldingSku(skuNo, lockNos)));
    }

    private PrdGoods require(String entityNo, String goodsNo) {
        PrdGoods g = invManaged.goodsOf(entityNo, goodsNo);
        if (g == null) {
            throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.NOT_FOUND);
        }
        return g;
    }

    private static GoodsFact fact(PrdGoods g) {
        return g == null ? null : new GoodsFact(g.getId(), g.getGoodsNo(), g.getEntityNo(), g.getStoreNo(),
                g.getCategoryNo(), g.getTitle(), g.getInvMode(), Boolean.TRUE.equals(g.getOnSale()),
                g.getAuditStatus());
    }

    private static SkuFact fact(PrdSku s) {
        return new SkuFact(s.getId(), s.getSkuNo(), s.getGoodsNo(), s.getEntityNo(), s.getSpec(),
                s.getSaleUnit(), s.getSoldCount() == null ? 0 : s.getSoldCount());
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}

package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.product.dto.CategoryVO;
import ai.neargo.shop.product.entity.PrdEntityCategoryInv;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.EntityCategoryInvMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.service.CategoryService;
import ai.neargo.shop.product.service.InvManagedService;
import ai.neargo.shop.spi.product.ProductEvents;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class InvManagedServiceImpl implements InvManagedService {

    private static final String HOME_MARKET = "CN";
    private static final Set<String> MODES = Set.of(PrdGoods.INV_INHERIT, PrdGoods.INV_ON, PrdGoods.INV_OFF);
    /** 平台默认记库存的品类：有实物、会进货的那两类 */
    private static final Set<String> TRACKED_TYPES = Set.of(PrdGoods.TYPE_NORMAL, PrdGoods.TYPE_FRESH);

    private final CategoryService categoryService;
    private final EntityCategoryInvMapper categoryInvMapper;
    private final GoodsMapper goodsMapper;
    private final SkuMapper skuMapper;
    private final OutboxEventBus events;

    public InvManagedServiceImpl(CategoryService categoryService, EntityCategoryInvMapper categoryInvMapper,
                                 GoodsMapper goodsMapper, SkuMapper skuMapper, OutboxEventBus events) {
        this.categoryService = categoryService;
        this.categoryInvMapper = categoryInvMapper;
        this.goodsMapper = goodsMapper;
        this.skuMapper = skuMapper;
        this.events = events;
    }

    // ------------------------------------------------------------------ 判

    @Override
    public boolean platformDefault(String categoryNo) {
        String type = categoryService.categoryTypeOf(categoryNo);
        return type == null || TRACKED_TYPES.contains(type);
    }

    @Override
    public boolean categoryManaged(String entityNo, String categoryNo) {
        return categoryManaged(entityNo, categoryNo, categoryRows(entityNo));
    }

    private boolean categoryManaged(String entityNo, String categoryNo, Map<String, Boolean> rows) {
        Boolean set = rows.get(categoryNo);
        return set != null ? set : platformDefault(categoryNo);
    }

    @Override
    public boolean isManaged(PrdGoods g) {
        return effective(g, categoryRows(g.getEntityNo()));
    }

    private boolean effective(PrdGoods g, Map<String, Boolean> rows) {
        String mode = g.getInvMode();
        if (PrdGoods.INV_ON.equals(mode)) {
            return true;
        }
        if (PrdGoods.INV_OFF.equals(mode)) {
            return false;
        }
        return categoryManaged(g.getEntityNo(), g.getCategoryNo(), rows);
    }

    @Override
    public Set<String> managedSkus(Collection<String> skuNos) {
        Set<String> out = new HashSet<>();
        if (skuNos == null || skuNos.isEmpty()) {
            return out;
        }
        Set<String> wanted = new HashSet<>(skuNos);
        wanted.remove(null);
        if (wanted.isEmpty()) {
            return out;
        }
        List<PrdSku> skus = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .select(PrdSku::getSkuNo, PrdSku::getGoodsNo)
                        .in(PrdSku::getSkuNo, wanted)));
        Map<String, String> goodsOfSku = new HashMap<>();
        for (PrdSku s : skus) {
            goodsOfSku.putIfAbsent(s.getSkuNo(), s.getGoodsNo());
        }
        Map<String, PrdGoods> goods = new HashMap<>();
        if (!goodsOfSku.isEmpty()) {
            DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                            .select(PrdGoods::getGoodsNo, PrdGoods::getEntityNo, PrdGoods::getCategoryNo,
                                    PrdGoods::getInvMode)
                            .in(PrdGoods::getGoodsNo, new HashSet<>(goodsOfSku.values()))))
                    .forEach(g -> goods.put(g.getGoodsNo(), g));
        }
        Map<String, Map<String, Boolean>> rowsByEntity = new HashMap<>();
        for (String skuNo : wanted) {
            PrdGoods g = goods.get(goodsOfSku.get(skuNo));
            // 查不到的算「记」：见 InvManagedPort 的约定
            if (g == null || effective(g, rowsByEntity.computeIfAbsent(g.getEntityNo(), this::categoryRows))) {
                out.add(skuNo);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 品类

    @Override
    public List<CategorySetting> categorySettings(String entityNo, Collection<String> categoryNos) {
        Map<String, Boolean> rows = categoryRows(entityNo);
        Map<String, String> names = new HashMap<>();
        collectNames(categoryService.tree(), names);
        Map<String, Long> counts = new HashMap<>();
        for (PrdGoods g : goodsOfEntity(entityNo)) {
            if (g.getCategoryNo() != null) {
                counts.merge(g.getCategoryNo(), 1L, Long::sum);
            }
        }
        List<CategorySetting> out = new ArrayList<>();
        for (String c : new java.util.LinkedHashSet<>(categoryNos)) {
            Boolean set = rows.get(c);
            out.add(new CategorySetting(c, names.getOrDefault(c, c),
                    set != null ? set : platformDefault(c), set == null, counts.getOrDefault(c, 0L)));
        }
        return out;
    }

    @Override
    public Set<String> categoryNosWithGoods(String entityNo) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (PrdGoods g : goodsOfEntity(entityNo)) {
            if (g.getCategoryNo() != null && !g.getCategoryNo().isBlank()) {
                out.add(g.getCategoryNo());
            }
        }
        return out;
    }

    private static void collectNames(List<CategoryVO> nodes, Map<String, String> out) {
        if (nodes == null) {
            return;
        }
        for (CategoryVO n : nodes) {
            out.put(n.categoryNo(), n.name());
            collectNames(n.children(), out);
        }
    }

    @Override
    public List<PrdGoods> goodsAffectedByCategory(String entityNo, String categoryNo, boolean managed) {
        Map<String, Boolean> rows = categoryRows(entityNo);
        return goodsOfEntity(entityNo).stream()
                .filter(g -> categoryNo.equals(g.getCategoryNo()))
                .filter(g -> g.getInvMode() == null || PrdGoods.INV_INHERIT.equals(g.getInvMode()))
                .filter(g -> effective(g, rows) != managed)
                .toList();
    }

    @Override
    @Transactional
    public void saveCategory(String entityNo, String categoryNo, boolean managed, String operator) {
        PrdEntityCategoryInv row = DataScopeContext.executeWithoutScope(() ->
                categoryInvMapper.selectOne(Wrappers.<PrdEntityCategoryInv>lambdaQuery()
                        .eq(PrdEntityCategoryInv::getEntityNo, entityNo)
                        .eq(PrdEntityCategoryInv::getCategoryNo, categoryNo)
                        .last("limit 1")));
        if (row == null) {
            PrdEntityCategoryInv n = new PrdEntityCategoryInv();
            n.setEntityNo(entityNo);
            n.setCategoryNo(categoryNo);
            n.setManaged(managed ? 1 : 0);
            n.setCreatedBy(operator);
            n.setUpdatedBy(operator);
            DataScopeContext.executeWithoutScope(() -> categoryInvMapper.insert(n));
            return;
        }
        PrdEntityCategoryInv u = new PrdEntityCategoryInv();
        u.setId(row.getId());
        u.setManaged(managed ? 1 : 0);
        u.setUpdatedBy(operator);
        DataScopeContext.executeWithoutScope(() -> categoryInvMapper.updateById(u));
    }

    /** 本主体设过的品类：类目号 → 记不记 */
    private Map<String, Boolean> categoryRows(String entityNo) {
        Map<String, Boolean> out = new HashMap<>();
        if (entityNo == null) {
            return out;
        }
        DataScopeContext.executeWithoutScope(() -> categoryInvMapper.selectList(
                        Wrappers.<PrdEntityCategoryInv>lambdaQuery()
                                .eq(PrdEntityCategoryInv::getEntityNo, entityNo)))
                .forEach(r -> out.put(r.getCategoryNo(), r.getManaged() != null && r.getManaged() == 1));
        return out;
    }

    // ------------------------------------------------------------------ 单品

    @Override
    public PrdGoods goodsOf(String entityNo, String goodsNo) {
        if (entityNo == null || goodsNo == null) {
            return null;
        }
        return DataScopeContext.executeWithoutScope(() -> goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                .eq(PrdGoods::getEntityNo, entityNo)
                .eq(PrdGoods::getGoodsNo, goodsNo)
                .last("limit 1")));
    }

    @Override
    @Transactional
    public void saveGoodsMode(PrdGoods g, String mode, String operator) {
        if (!MODES.contains(mode)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 用条件更新而不是 updateById(new PrdGoods)：商品表列多，部分列是 ALWAYS 策略，
         * 只 set 这一列最不容易把别的字段顺手清空
         */
        DataScopeContext.executeWithoutScope(() -> goodsMapper.update(null, Wrappers.<PrdGoods>lambdaUpdate()
                .set(PrdGoods::getInvMode, mode)
                .set(PrdGoods::getUpdatedBy, operator)
                .eq(PrdGoods::getId, g.getId())));
        g.setInvMode(mode);
    }

    @Override
    public List<PrdSku> skusOf(Collection<String> goodsNos) {
        if (goodsNos == null || goodsNos.isEmpty()) {
            return List.of();
        }
        List<PrdSku> rows = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .in(PrdSku::getGoodsNo, new HashSet<>(goodsNos))
                        .eq(PrdSku::getMarket, HOME_MARKET)));
        Map<String, PrdSku> bySku = new LinkedHashMap<>();
        for (PrdSku r : rows) {
            if (r.getSkuNo() != null) {
                bySku.putIfAbsent(r.getSkuNo(), r);
            }
        }
        return new ArrayList<>(bySku.values());
    }

    @Override
    public void publishModeChanged(PrdGoods g, boolean managed) {
        for (PrdSku s : skusOf(List.of(g.getGoodsNo()))) {
            events.publish(new ProductEvents.SkuInvModeChanged(
                    s.getSkuNo(), g.getEntityNo(), g.getGoodsNo(),
                    g.getTitle() == null ? "" : g.getTitle(),
                    s.getSpec(), s.getBarcode(), s.getMerchantSkuCode(), s.getSaleUnit(), managed));
        }
    }

    @Override
    public Map<String, GoodsInvMode> modesOf(String entityNo, Collection<String> goodsNos) {
        Map<String, GoodsInvMode> out = new LinkedHashMap<>();
        if (entityNo == null || goodsNos == null || goodsNos.isEmpty()) {
            return out;
        }
        Map<String, Boolean> rows = categoryRows(entityNo);
        DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                        .select(PrdGoods::getGoodsNo, PrdGoods::getEntityNo, PrdGoods::getCategoryNo,
                                PrdGoods::getInvMode)
                        .eq(PrdGoods::getEntityNo, entityNo)
                        .in(PrdGoods::getGoodsNo, new HashSet<>(goodsNos))))
                .forEach(g -> out.put(g.getGoodsNo(), new GoodsInvMode(g.getGoodsNo(),
                        g.getInvMode() == null ? PrdGoods.INV_INHERIT : g.getInvMode(),
                        effective(g, rows), categoryManaged(entityNo, g.getCategoryNo(), rows))));
        return out;
    }

    @Override
    public List<PrdGoods> managedGoods(String entityNo) {
        Map<String, Boolean> rows = categoryRows(entityNo);
        return goodsOfEntity(entityNo).stream().filter(g -> effective(g, rows)).toList();
    }

    private List<PrdGoods> goodsOfEntity(String entityNo) {
        if (entityNo == null) {
            return List.of();
        }
        return DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                .eq(PrdGoods::getEntityNo, entityNo)
                .orderByDesc(PrdGoods::getId)));
    }
}

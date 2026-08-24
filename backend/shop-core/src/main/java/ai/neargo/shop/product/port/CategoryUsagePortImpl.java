package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.spi.product.CategoryUsagePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@link CategoryUsagePort} 实现。 */
@Component
public class CategoryUsagePortImpl implements CategoryUsagePort {

    private static final String ACTIVE = "ACTIVE";

    private final CategoryMapper categoryMapper;
    private final GoodsMapper goodsMapper;

    public CategoryUsagePortImpl(CategoryMapper categoryMapper, GoodsMapper goodsMapper) {
        this.categoryMapper = categoryMapper;
        this.goodsMapper = goodsMapper;
    }

    @Override
    public long countByRequiredCode(String requiredCode) {
        if (requiredCode == null || requiredCode.isBlank()) {
            return 0;
        }
        // 类目是全平台主数据，调用方是运营 —— 不该被数据域裁掉
        return DataScopeContext.executeWithoutScope(() ->
                categoryMapper.selectCount(Wrappers.<PrdCategory>lambdaQuery()
                        .eq(PrdCategory::getRequiredCode, requiredCode)
                        .eq(PrdCategory::getStatus, ACTIVE)));
    }

    @Override
    public java.util.Map<String, CategoryStat> statsOf(String entityNo,
                                                       java.util.Collection<String> categoryNos) {
        if (entityNo == null || entityNo.isBlank() || categoryNos == null || categoryNos.isEmpty()) {
            return java.util.Map.of();
        }
        /*
         * 一次查完再在内存里分组。**不是 N 次 count** —— 「我的类目」那一页
         * 正常有十几个类目，逐个 count 就是十几次往返，而这些商品本来就属于同一家店，
         * 一次就能全捞出来。
         *
         * 豁免数据域的理由与 countGoodsInCategory 那条相同：调用方是 B 端会话
         * （维度 SELF），而 prd_goods 只有 MERCHANT 锚点，接上就是 1=0。
         */
        List<PrdGoods> rows = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getEntityNo, entityNo)
                        .in(PrdGoods::getCategoryNo, categoryNos)));
        java.util.Map<String, int[]> acc = new java.util.HashMap<>();
        for (PrdGoods g : rows) {
            int[] a = acc.computeIfAbsent(g.getCategoryNo(), k -> new int[3]);
            a[0]++;
            if (Boolean.TRUE.equals(g.getOnSale())) {
                a[1]++;
            }
            // 库里那列叫 AUDITING，对外叫 PENDING（词典 §11）—— 两个名字指同一件事
            if ("AUDITING".equals(g.getAuditStatus())) {
                a[2]++;
            }
        }
        java.util.Map<String, CategoryStat> out = new java.util.LinkedHashMap<>();
        acc.forEach((no, a) -> out.put(no, new CategoryStat(a[0], a[1], a[2])));
        return out;
    }

    @Override
    public long countGoodsInCategory(String entityNo, String categoryNo) {
        if (entityNo == null || entityNo.isBlank() || categoryNo == null || categoryNo.isBlank()) {
            return 0;
        }
        /*
         * 豁免数据域：调用方是 B 端会话（维度 SELF），而 prd_goods 只有 MERCHANT 锚点 ——
         * 接上就是 1=0，于是「这个货架上有几件商品」永远答 0，
         * 而那正是「删货架前要拦一下」所依赖的那个数。
         */
        Long n = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectCount(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getEntityNo, entityNo)
                        .eq(PrdGoods::getCategoryNo, categoryNo)));
        return n == null ? 0 : n;
    }

    @Override
    public long countOnShelfGoodsRequiring(String entityNo, java.util.Collection<String> codes) {
        if (entityNo == null || entityNo.isBlank() || codes == null || codes.isEmpty()) {
            return 0;
        }
        /*
         * 两步查而不是 join：类目总量以百计，一次全表比一条跨表 SQL 更好读，
         * 也不用把 prd_category 的列名写进一段手写 SQL 里（改列时不会静默失配）。
         */
        java.util.List<String> categoryNos = DataScopeContext.executeWithoutScope(() ->
                        categoryMapper.selectList(Wrappers.<PrdCategory>lambdaQuery()
                                .select(PrdCategory::getCategoryNo)
                                .in(PrdCategory::getRequiredCode, codes)))
                .stream().map(PrdCategory::getCategoryNo).toList();
        if (categoryNos.isEmpty()) {
            return 0;
        }
        // **只算在架的**：草稿与已下架的商品此刻不对任何人可见，撤码不改变他们的处境
        Long n = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectCount(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getEntityNo, entityNo)
                        .eq(PrdGoods::getOnSale, true)
                        .in(PrdGoods::getCategoryNo, categoryNos)));
        return n == null ? 0 : n;
    }

    @Override
    public String requiredCodeOf(String categoryNo) {
        PrdCategory c = row(categoryNo);
        return c == null ? null : c.getRequiredCode();
    }

    @Override
    public String nameOf(String categoryNo) {
        PrdCategory c = row(categoryNo);
        return c == null ? null : c.getName();
    }

    @Override
    public boolean isActive(String categoryNo) {
        PrdCategory c = row(categoryNo);
        return c != null && ACTIVE.equals(c.getStatus());
    }

    private PrdCategory row(String categoryNo) {
        if (categoryNo == null || categoryNo.isBlank()) {
            return null;
        }
        return DataScopeContext.executeWithoutScope(() ->
                categoryMapper.selectOne(Wrappers.<PrdCategory>lambdaQuery()
                        .eq(PrdCategory::getCategoryNo, categoryNo).last("limit 1")));
    }
}

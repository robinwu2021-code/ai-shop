package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.spi.product.CategoryUsagePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

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

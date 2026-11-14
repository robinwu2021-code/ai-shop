package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.spi.product.PointsEarningCatalogPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class PointsEarningCatalogPortImpl implements PointsEarningCatalogPort {
    private final CategoryMapper categories;
    private final GoodsMapper goods;
    public PointsEarningCatalogPortImpl(CategoryMapper categories, GoodsMapper goods) {
        this.categories = categories; this.goods = goods;
    }
    public List<Category> categories() {
        return DataScopeContext.executeWithoutScope(() -> {
            var all = categories.selectList(Wrappers.<PrdCategory>lambdaQuery().orderByAsc(PrdCategory::getSort));
            Map<String, PrdCategory> byNo = new HashMap<>();
            all.forEach(c -> byNo.put(c.getCategoryNo(), c));
            return all.stream().filter(c -> "ACTIVE".equals(c.getStatus())).map(c -> {
                var names = new ArrayList<String>();
                var seen = new HashSet<String>();
                for (var n = c; n != null; n = byNo.get(n.getParentNo())) {
                    if (!seen.add(n.getCategoryNo())) throw new IllegalArgumentException("POINTS_CATEGORY_CYCLE");
                    names.addFirst(n.getName());
                }
                return new Category(c.getCategoryNo(), c.getParentNo(), c.getName(), String.join(" / ", names));
            }).toList();
        });
    }
    public String goodsCategory(String goodsNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            var g = goods.selectOne(Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getGoodsNo, goodsNo));
            if (g == null) throw new IllegalArgumentException("POINTS_GOODS_NOT_FOUND");
            return g.getCategoryNo();
        });
    }
    public List<String> ancestry(String categoryNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            var result = new ArrayList<String>();
            String next = categoryNo;
            while (next != null && !next.isBlank()) {
                if (result.contains(next) || result.size() >= 32) throw new IllegalArgumentException("POINTS_CATEGORY_CYCLE");
                var c = categories.selectOne(Wrappers.<PrdCategory>lambdaQuery().eq(PrdCategory::getCategoryNo, next));
                if (c == null) throw new IllegalArgumentException("POINTS_CATEGORY_NOT_FOUND");
                result.add(next); next = c.getParentNo();
            }
            return List.copyOf(result);
        });
    }
}

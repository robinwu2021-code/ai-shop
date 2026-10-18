package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.spi.product.ProductScopeFactsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Facts stay in product and use explicit tenant predicates even when ambient data-scope filtering is bypassed. */
@Component
public class ProductScopeFactsPortImpl implements ProductScopeFactsPort {
    private static final int BATCH_SIZE = 200;
    private final GoodsMapper goods;
    private final CategoryMapper categories;

    public ProductScopeFactsPortImpl(GoodsMapper goods, CategoryMapper categories) {
        this.goods = goods;
        this.categories = categories;
    }

    @Override
    public boolean goodsOwned(String tenantNo, String entityNo, Set<String> goodsNos) {
        if (goodsNos == null || goodsNos.isEmpty()) {
            return false;
        }
        List<String> ids = new ArrayList<>(goodsNos);
        for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
            var batch = ids.subList(from, Math.min(from + BATCH_SIZE, ids.size()));
            long count = DataScopeContext.executeWithoutScope(() -> goods.selectCount(
                    Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getTenantNo, tenantNo)
                            .eq(PrdGoods::getEntityNo, entityNo).in(PrdGoods::getGoodsNo, batch)));
            if (count != batch.size()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean categoriesActive(String tenantNo, Set<String> categoryNos) {
        if (categoryNos == null || categoryNos.isEmpty()) {
            return false;
        }
        List<String> ids = new ArrayList<>(categoryNos);
        for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
            var batch = ids.subList(from, Math.min(from + BATCH_SIZE, ids.size()));
            long count = DataScopeContext.executeWithoutScope(() -> categories.selectCount(
                    Wrappers.<PrdCategory>lambdaQuery().eq(PrdCategory::getTenantNo, tenantNo)
                            .eq(PrdCategory::getStatus, "ACTIVE").in(PrdCategory::getCategoryNo, batch)));
            if (count != batch.size()) {
                return false;
            }
        }
        return true;
    }
}

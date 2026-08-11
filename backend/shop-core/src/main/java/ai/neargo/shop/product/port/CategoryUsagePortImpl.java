package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryMapper;
import ai.neargo.shop.spi.product.CategoryUsagePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/** {@link CategoryUsagePort} 实现。 */
@Component
public class CategoryUsagePortImpl implements CategoryUsagePort {

    private static final String ACTIVE = "ACTIVE";

    private final CategoryMapper categoryMapper;

    public CategoryUsagePortImpl(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
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
}

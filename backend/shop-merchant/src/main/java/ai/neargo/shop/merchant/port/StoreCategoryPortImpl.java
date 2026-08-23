package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchStoreCategory;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreCategoryMapper;
import ai.neargo.shop.spi.user.StoreCategoryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@link StoreCategoryPort} 实现。 */
@Component
public class StoreCategoryPortImpl implements StoreCategoryPort {

    private final MchStoreCategoryMapper mapper;

    public StoreCategoryPortImpl(MchStoreCategoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<String> categoryNosOf(String storeNo) {
        if (storeNo == null || storeNo.isBlank()) {
            return List.of();
        }
        /*
         * 豁免数据域：调用方是 B 端会话（维度 SELF），而这张表按 MERCHANT/entity_no 登记 ——
         * 接上就是 1=0，商家自己的货架当场全空。归属校验由调用链上游的
         * BizContext.requireMerchantNo() + storeNos 完成，不靠这一句。
         */
        return DataScopeContext.executeWithoutScope(() -> mapper.selectList(
                        Wrappers.<MchStoreCategory>lambdaQuery()
                                .eq(MchStoreCategory::getStoreNo, storeNo)
                                .eq(MchStoreCategory::getEnabled, true)
                                .orderByAsc(MchStoreCategory::getSort)))
                .stream().map(MchStoreCategory::getCategoryNo).toList();
    }

    @Override
    public List<Shelf> shelvesOf(String storeNo) {
        if (storeNo == null || storeNo.isBlank()) {
            return List.of();
        }
        /*
         * 同样豁免数据域，理由与 categoryNosOf 一样 —— 但这一条的调用方是**买家侧**
         * （门店主页，游客也能进）：那时根本没有商家会话，接上数据域就是 1=0，
         * 店铺页的类目行会永远为空，而页面照常渲染，没有任何报错。
         */
        return DataScopeContext.executeWithoutScope(() -> mapper.selectList(
                        Wrappers.<MchStoreCategory>lambdaQuery()
                                .eq(MchStoreCategory::getStoreNo, storeNo)
                                .eq(MchStoreCategory::getEnabled, true)
                                .orderByAsc(MchStoreCategory::getSort)))
                .stream()
                .map(r -> new Shelf(r.getCategoryNo(), r.getDisplayName(),
                        r.getSort() == null ? 999 : r.getSort()))
                .toList();
    }

    @Override
    public void ensure(String entityNo, String storeNo, String categoryNo) {
        if (storeNo == null || storeNo.isBlank() || categoryNo == null || categoryNo.isBlank()) {
            return;
        }
        Long exists = DataScopeContext.executeWithoutScope(() -> mapper.selectCount(
                Wrappers.<MchStoreCategory>lambdaQuery()
                        .eq(MchStoreCategory::getStoreNo, storeNo)
                        .eq(MchStoreCategory::getCategoryNo, categoryNo)));
        if (exists != null && exists > 0) {
            return;
        }
        MchStoreCategory row = new MchStoreCategory();
        row.setStoreNo(storeNo);
        row.setEntityNo(entityNo);
        row.setCategoryNo(categoryNo);
        // 自动加入的排在最后：商家自己拖过的顺序不该被一次建品打乱
        row.setSort(999);
        row.setEnabled(true);
        DataScopeContext.executeWithoutScope(() -> mapper.insert(row));
    }
}

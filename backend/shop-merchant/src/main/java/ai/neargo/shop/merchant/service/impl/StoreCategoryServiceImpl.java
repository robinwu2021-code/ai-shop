package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchStoreCategory;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreCategoryMapper;
import ai.neargo.shop.merchant.service.StoreCategoryService;
import ai.neargo.shop.spi.product.CategoryUsagePort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** {@link StoreCategoryService} 实现。 */
@Service
public class StoreCategoryServiceImpl implements StoreCategoryService {

    private final MchStoreCategoryMapper mapper;
    private final CategoryUsagePort categoryPort;
    private final MerchantQueryPort merchantPort;

    public StoreCategoryServiceImpl(MchStoreCategoryMapper mapper,
                                    CategoryUsagePort categoryPort,
                                    MerchantQueryPort merchantPort) {
        this.mapper = mapper;
        this.categoryPort = categoryPort;
        this.merchantPort = merchantPort;
    }

    @Override
    public List<StoreCategoryVO> list(String merchantNo, String storeNo) {
        return rows(storeNo).stream().map(r -> toVO(merchantNo, r)).toList();
    }

    @Override
    @Transactional
    public List<StoreCategoryVO> replace(String merchantNo, String storeNo, List<Item> items) {
        if (storeNo == null || storeNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<Item> want = items == null ? List.of() : items;

        /*
         * 每一条都要落在**主体的授权范围内**。这一步在这里拦而不是等到上架 ——
         * 它不是「商家还没做的事」，是「他做不了的事」，让他勾完一屏再告诉他不行
         * 是最差的一种拒绝。
         */
        for (Item it : want) {
            requireSelectable(merchantNo, it.categoryNo());
        }

        List<MchStoreCategory> existing = rows(storeNo);
        Set<String> keep = new LinkedHashSet<>(want.stream().map(Item::categoryNo).toList());

        /*
         * 删掉一个**底下还有商品**的货架 → 拒绝。
         *
         * 不拦的话那些商品会挂在一个这家店已经不存在的货架上：店铺页里就此消失，
         * 而商家在商品列表里还看得到它们 —— 两个页面对同一批货给出相反的答案。
         */
        for (MchStoreCategory row : existing) {
            if (keep.contains(row.getCategoryNo())) {
                continue;
            }
            if (categoryPort.countGoodsInCategory(merchantNo, row.getCategoryNo()) > 0) {
                throw BizException.of(ErrorCode.STORE_CATEGORY_IN_USE);
            }
            DataScopeContext.executeWithoutScope(() -> mapper.deleteById(row.getId()));
        }

        int i = 0;
        for (Item it : want) {
            MchStoreCategory row = existing.stream()
                    .filter(x -> x.getCategoryNo().equals(it.categoryNo()))
                    .findFirst().orElse(null);
            boolean fresh = row == null;
            if (fresh) {
                row = new MchStoreCategory();
                row.setStoreNo(storeNo);
                row.setEntityNo(merchantNo);
                row.setCategoryNo(it.categoryNo());
                row.setEnabled(true);
            }
            // 显示名空串归一为 null：留着空串的话「用平台名」这条判断要在三处各写一遍
            row.setDisplayName(it.displayName() == null || it.displayName().isBlank()
                    ? null : it.displayName().trim());
            row.setSort(it.sort() == null ? i : it.sort());
            MchStoreCategory toSave = row;
            DataScopeContext.executeWithoutScope(() ->
                    fresh ? mapper.insert(toSave) : mapper.updateById(toSave));
            i++;
        }
        return list(merchantNo, storeNo);
    }

    @Override
    @Transactional
    public void initForNewStore(String merchantNo, String storeNo,
                                List<String> categoryNos, String copyFromStoreNo) {
        List<String> nos = categoryNos == null ? List.of() : categoryNos;
        if (nos.isEmpty() && copyFromStoreNo != null && !copyFromStoreNo.isBlank()) {
            /*
             * **第二家店默认复制默认店的**：多门店商家开分店卖的多半是同一批货，
             * 从零勾选是纯负担。复制的是货架，不是商品 —— 商品本来就是主体共用的。
             */
            nos = rows(copyFromStoreNo).stream().map(MchStoreCategory::getCategoryNo).toList();
        }
        if (nos.isEmpty()) {
            /*
             * **一个都不选是合法的**：这家店还没想好卖什么。
             * 要求建店时先想清楚，是把决定提前到他还没想好的时候 ——
             * 建品时会自动加入（见 StoreCategoryPort#ensure）。
             */
            return;
        }
        List<Item> items = new ArrayList<>();
        int i = 0;
        for (String no : nos) {
            items.add(new Item(no, null, i++));
        }
        replace(merchantNo, storeNo, items);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 这个类目这家主体能不能选。
     *
     * <p>两道判据：
     * <ol>
     *   <li><b>类目必须启用</b> —— 已归档的不该还能被选进货架（降二级之后三级类目
     *       全是归档态，这条从「理论问题」变成了「现在就能踩」）</li>
     *   <li><b>无门槛，或主体持有那张码</b> —— 报错要说得出缺哪张证</li>
     * </ol>
     */
    private void requireSelectable(String merchantNo, String categoryNo) {
        if (categoryNo == null || categoryNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (!categoryPort.isActive(categoryNo)) {
            throw BizException.of(ErrorCode.CATEGORY_NOT_FOUND);
        }
        String required = categoryPort.requiredCodeOf(categoryNo);
        if (required == null || required.isBlank()) {
            return;   // 无门槛类目：谁都能摆
        }
        if (!merchantPort.authorizedCategoryCodes(merchantNo).contains(required)) {
            throw BizException.of(ErrorCode.CATEGORY_NOT_AUTHORIZED);
        }
    }

    private List<MchStoreCategory> rows(String storeNo) {
        if (storeNo == null || storeNo.isBlank()) {
            return List.of();
        }
        /*
         * 豁免数据域：调用方是 B 端会话（维度 SELF），这张表按 MERCHANT/entity_no 登记 ——
         * 接上就是 1=0，商家自己的货架当场全空。归属由 requireMerchantNo + storeNos 保证。
         */
        return DataScopeContext.executeWithoutScope(() -> mapper.selectList(
                Wrappers.<MchStoreCategory>lambdaQuery()
                        .eq(MchStoreCategory::getStoreNo, storeNo)
                        .orderByAsc(MchStoreCategory::getSort)));
    }

    private StoreCategoryVO toVO(String merchantNo, MchStoreCategory r) {
        String platform = categoryPort.nameOf(r.getCategoryNo());
        return new StoreCategoryVO(
                r.getCategoryNo(),
                // 显示名有就用它 —— 它只是皮，categoryNo 不变，跨店聚合照常成立
                r.getDisplayName() != null && !r.getDisplayName().isBlank()
                        ? r.getDisplayName() : platform,
                platform, r.getDisplayName(),
                r.getSort() == null ? 0 : r.getSort(),
                (int) categoryPort.countGoodsInCategory(merchantNo, r.getCategoryNo()));
    }
}

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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(StoreCategoryServiceImpl.class);

    /** 与 {@code SysConfig.CATEGORY_GATE_ENFORCE} 同值。常量在平台域，这里不跨域引它 */
    private static final String SWITCH_CATEGORY_GATE = "category.gate.enforce";

    private final MchStoreCategoryMapper mapper;
    private final CategoryUsagePort categoryPort;
    private final MerchantQueryPort merchantPort;
    private final ai.neargo.shop.spi.platform.PlatformSwitchPort switchPort;

    /** 读门店的经营模式：自营门店加经营类目不判资质（TDD-门店经营类目） */
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper entityMapper;

    public StoreCategoryServiceImpl(MchStoreCategoryMapper mapper,
                                    CategoryUsagePort categoryPort,
                                    MerchantQueryPort merchantPort,
                                    ai.neargo.shop.spi.platform.PlatformSwitchPort switchPort,
                                    ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper,
                                    ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper entityMapper) {
        this.storeMapper = storeMapper;
        this.entityMapper = entityMapper;
        this.mapper = mapper;
        this.categoryPort = categoryPort;
        this.merchantPort = merchantPort;
        this.switchPort = switchPort;
    }

    @Override
    public List<StoreCategoryVO> list(String merchantNo, String storeNo) {
        List<MchStoreCategory> rows = rows(storeNo);
        // 一次把这些类目的商品情况查完 —— 逐个 count 是 N 次往返，而一页正常十几个类目
        var stats = categoryPort.statsOf(merchantNo,
                rows.stream().map(MchStoreCategory::getCategoryNo).toList());
        return rows.stream().map(r -> toVO(r, stats)).toList();
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
        boolean selfOperated = isSelfOperated(storeNo);
        for (Item it : want) {
            requireSelectable(merchantNo, it.categoryNo(), selfOperated);
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
    private void requireSelectable(String merchantNo, String categoryNo, boolean selfOperated) {
        if (categoryNo == null || categoryNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (!categoryPort.isActive(categoryNo)) {
            throw BizException.of(ErrorCode.CATEGORY_NOT_FOUND);
        }
        /*
         * **平台自营主体不判资质**（TDD-门店经营类目 规则 2，§10 修订）：平台不向自己提交资料。
         * 判据是主体的 self_operated（V329），不是门店的 business_mode —— 后者每家新店
         * 默认都是 SELF_OPERATED，按它判等于第三方新店全部免资质。见 isSelfOperated。
         * 上面「类目必须启用」照判：归档类目谁都不该再摆。
         */
        if (selfOperated) {
            return;
        }
        String required = categoryPort.requiredCodeOf(categoryNo);
        if (required == null || required.isBlank()) {
            return;   // 无门槛类目：谁都能摆
        }
        if (!merchantPort.authorizedCategoryCodes(merchantNo).contains(required)) {
            /*
             * **这条路此前不受任何开关控制。**上一轮把「暂时别拦资质」做成
             * shop.category.gate.enforce 时只接了商品上架，漏了摆货架 ——
             * 于是出现过「同一个类目，商品能上架却摆不上货架」这种说不通的状态，
             * 而两条报错分别来自两个域，看日志也对不起来。
             *
             * 现在两条读同一个开关（sys_config 的 category.gate.enforce）。
             * 关着时判据照跑、命中打 WARN —— 那是开闸前估影响面的依据。
             */
            if (!switchPort.bool(SWITCH_CATEGORY_GATE, false)) {
                log.warn("[类目闸] 放行未授权的货架：merchant={} category={} 需要码={}（enforce=false）",
                        merchantNo, categoryNo, required);
                return;
            }
            throw BizException.of(ErrorCode.CATEGORY_NOT_AUTHORIZED);
        }
    }

    /**
     * 这家店所属<b>主体</b>是不是平台自营（{@code mch_entity.self_operated}，V329）。
     *
     * <p><b>不看门店的 business_mode</b>（TDD-门店经营类目 §10）：那一列建表默认就是
     * SELF_OPERATED，入驻不改它 —— 按它判，每一家第三方新店都会被当成自营而免资质，
     * 闸一打开就是整片放行，且没有任何报错。V329 的迁移注释点名说过这一条。
     */
    private boolean isSelfOperated(String storeNo) {
        var st = DataScopeContext.executeWithoutScope(() -> storeMapper.selectOne(
                Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getStoreNo, storeNo)
                        .last("limit 1")));
        if (st == null || st.getEntityNo() == null) {
            return false;
        }
        var e = DataScopeContext.executeWithoutScope(() -> entityMapper.selectOne(
                Wrappers.<ai.neargo.shop.merchant.entity.MchEntity>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchEntity::getEntityNo, st.getEntityNo())
                        .last("limit 1")));
        return e != null && Integer.valueOf(1).equals(e.getSelfOperated());
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

    private StoreCategoryVO toVO(MchStoreCategory r,
                                 java.util.Map<String, CategoryUsagePort.CategoryStat> stats) {
        String platform = categoryPort.nameOf(r.getCategoryNo());
        // 没有商品的类目不在 map 里 —— 按 0 处理，而不是让它变成 null 打到端上
        var st = stats.getOrDefault(r.getCategoryNo(),
                new CategoryUsagePort.CategoryStat(0, 0, 0));
        return new StoreCategoryVO(
                r.getCategoryNo(),
                // 显示名有就用它 —— 它只是皮，categoryNo 不变，跨店聚合照常成立
                r.getDisplayName() != null && !r.getDisplayName().isBlank()
                        ? r.getDisplayName() : platform,
                platform, r.getDisplayName(),
                r.getSort() == null ? 0 : r.getSort(),
                st.total(), st.onSale(), st.pending());
    }
}

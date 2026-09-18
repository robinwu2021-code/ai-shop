package ai.neargo.shop.invbridge.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.inventory.entity.InvItemRef;
import ai.neargo.shop.inventory.entity.InvOwner;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemRefMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.invbridge.InventoryOrphanSweepService;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 空壳物料清理的实现。 */
@Service
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public class InventoryOrphanSweepServiceImpl implements InventoryOrphanSweepService {

    /** 一次查多少个 skuNo。给得小一点：{@code IN} 太长会把执行计划拖成全表扫 */
    private static final int SKU_BATCH = 500;

    private final OwnerMapper ownerMapper;
    private final ItemRefMapper itemRefMapper;
    private final SkuMapper skuMapper;
    private final InventoryAclService acl;

    public InventoryOrphanSweepServiceImpl(OwnerMapper ownerMapper, ItemRefMapper itemRefMapper,
                                           SkuMapper skuMapper, InventoryAclService acl) {
        this.ownerMapper = ownerMapper;
        this.itemRefMapper = itemRefMapper;
        this.skuMapper = skuMapper;
        this.acl = acl;
    }

    @Override
    public Report sweep(boolean dryRun, int limit, String entityNo) {
        int scanned = 0;
        int orphans = 0;
        int retired = 0;
        int kept = 0;
        boolean complete = true;

        for (InvOwner owner : ownerMapper.selectList(Wrappers.emptyWrapper())) {
            String ownerEntity = owner.getExternalRef();
            if (ownerEntity == null) {
                // 独立交付时的外部主体：这边认不出它对应平台哪个商家，一个字都不该动
                continue;
            }
            if (entityNo != null && !entityNo.equals(ownerEntity)) {
                continue;
            }
            if (scanned >= limit) {
                complete = false;
                break;
            }
            List<InvItemRef> refs = itemRefMapper.selectList(Wrappers.<InvItemRef>lambdaQuery()
                    .eq(InvItemRef::getOwnerId, owner.getOwnerId())
                    .eq(InvItemRef::getRefSystem, InvEnums.RefSystem.AISHOP)
                    .last("limit " + Math.max(1, limit - scanned)));
            scanned += refs.size();

            List<String> skuNos = refs.stream().map(InvItemRef::getRef).filter(java.util.Objects::nonNull).toList();
            Set<String> alive = aliveSkus(skuNos);
            for (String skuNo : skuNos) {
                if (alive.contains(skuNo)) {
                    continue;
                }
                orphans++;
                /*
                 * **判与写分开**：先用同一个方法只判不写，拿到「会不会归档」，
                 * 再决定写什么。不在这儿另写一份判据 —— 「会归档几件」与
                 * 「孤儿有几条」差的正是有库存那一批，而那一批恰恰是最要紧的。
                 */
                boolean empty = acl.retireItemIfEmpty(ownerEntity, skuNo, false);
                if (!dryRun) {
                    /*
                     * ★ **有库存的也要标记**，不是跳过（2026-09-18 真机查得）。
                     *
                     * SkuRetired 那条信号是**向前的**：它只在退休发生的那一刻触发。
                     * 而线上已经躺着的那些 —— 比如香梨，加规格时旧 SKU 被软删、
                     * 旧物料带着 1 件库存留下 —— 退休早就发生过了，事件永远不会补发，
                     * 于是它一个标记都不会有，在挑货弹层里与旁边同名那行完全一样。
                     * 店主看到的正是这两行。
                     *
                     * `retireItem` 自己会判：空的归档、有货的只标记。
                     * 传 null 接位者 —— 事后补登判不出谁顶了谁，而记一个猜的比不记更坏。
                     */
                    acl.retireItem(ownerEntity, skuNo, null);
                }
                if (empty) {
                    retired++;
                } else {
                    kept++;
                }
            }
        }
        return new Report(scanned, orphans, retired, kept, complete);
    }


    /** 这批 skuNo 里哪些在平台侧还活着。**软删的算不活** —— 商品库里看不到它了 */
    private Set<String> aliveSkus(List<String> skuNos) {
        Set<String> alive = new HashSet<>();
        for (int i = 0; i < skuNos.size(); i += SKU_BATCH) {
            List<String> batch = new ArrayList<>(skuNos.subList(i, Math.min(i + SKU_BATCH, skuNos.size())));
            if (batch.isEmpty()) {
                continue;
            }
            /*
             * executeWithoutScope：这是个跑批，没有用户上下文，而 prd_* 带数据域 ——
             * 不绕过的话这里拿到的是空集，于是**每一条引用都会被判成孤儿**，
             * 一次跑批把全平台的物料归档掉。它不会报错。
             */
            for (PrdSku s : DataScopeContext.executeWithoutScope(() ->
                    skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                            .in(PrdSku::getSkuNo, batch)
                            .eq(PrdSku::getDeleted, 0)))) {
                alive.add(s.getSkuNo());
            }
        }
        return alive;
    }
}

package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvItem;
import ai.neargo.shop.inventory.entity.InvItemRef;
import ai.neargo.shop.inventory.entity.InvLedger;
import ai.neargo.shop.inventory.entity.InvLocation;
import ai.neargo.shop.inventory.entity.InvOwner;
import ai.neargo.shop.inventory.entity.InvStockBalance;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemRefMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LocationMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.inventory.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 防腐层实现。 */
@ConditionalOnInventory
@Service
public class InventoryAclServiceImpl implements InventoryAclService {

    /** 平台默认单位。{@code sale_unit} 是可空的，而计量单位不能空 —— 空了就没法相加。 */
    private static final String DEFAULT_UOM = "PIECE";

    private final OwnerMapper ownerMapper;
    private final LocationMapper locationMapper;
    private final ItemMapper itemMapper;
    private final ItemRefMapper refMapper;
    private final LedgerMapper ledgerMapper;
    private final BalanceMapper balanceMapper;
    private final LocationService locations;

    public InventoryAclServiceImpl(OwnerMapper ownerMapper, LocationMapper locationMapper,
                                   ItemMapper itemMapper, ItemRefMapper refMapper,
                                   LedgerMapper ledgerMapper, BalanceMapper balanceMapper,
                                   LocationService locations) {
        this.ownerMapper = ownerMapper;
        this.locationMapper = locationMapper;
        this.itemMapper = itemMapper;
        this.refMapper = refMapper;
        this.ledgerMapper = ledgerMapper;
        this.balanceMapper = balanceMapper;
        this.locations = locations;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String ownerIdOf(String entityNo) {
        InvOwner row = ownerMapper.selectOne(Wrappers.<InvOwner>lambdaQuery()
                .eq(InvOwner::getExternalRef, entityNo));
        if (row != null) {
            return row.getOwnerId();
        }
        InvOwner created = new InvOwner();
        created.setOwnerId(InvKeys.next(InvKeys.OWNER));
        created.setName(entityNo);
        created.setExternalRef(entityNo);
        created.setStatus(InvEnums.MasterStatus.ACTIVE);
        created.setCreatedBy("SYSTEM");
        ownerMapper.insert(created);
        return created.getOwnerId();
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String locationIdOf(String entityNo, String storeNo) {
        String ownerId = ownerIdOf(entityNo);
        if (storeNo == null || storeNo.isBlank()) {
            return locations.defaultLocation(ownerId);
        }
        InvLocation row = locationMapper.selectOne(Wrappers.<InvLocation>lambdaQuery()
                .eq(InvLocation::getOwnerId, ownerId).eq(InvLocation::getExternalRef, storeNo));
        if (row != null) {
            return row.getLocationId();
        }
        InvLocation created = new InvLocation();
        created.setLocationId(InvKeys.next(InvKeys.LOCATION));
        created.setOwnerId(ownerId);
        created.setName(storeNo);
        created.setKind(InvEnums.LocationKind.STORE);
        created.setExternalRef(storeNo);
        created.setIsDefault(0);
        /*
         * **新门店默认从主体默认仓发货。**
         *
         * 不设的话 `resolveStockLocation` 会解析到门店自己这个**刚建出来的空库位**，
         * 于是商家点进「库存」看到的是一片空 —— 而货就在默认仓里，一件没少。
         * 2026-08-27 线上实测：两个默认仓 204 + 2 件，门店库位 0 条余额。
         *
         * 这不是权宜之计，是与平台侧语义对齐：`StockPortImpl` 按 SKU 判定 ——
         * 该 SKU 一行门店库存都没有时就是主体级，**每个门店都从主体池卖**。
         * 门店确实是从那个仓取货的。
         *
         * 商家真要给这家店单独备货时，在「仓库与库位」里改发货源即可（setSource）。
         */
        created.setSourceLocationId(locations.defaultLocation(ownerId));
        created.setStatus(InvEnums.MasterStatus.ACTIVE);
        created.setCreatedBy("SYSTEM");
        locationMapper.insert(created);
        return created.getLocationId();
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String itemIdOf(String entityNo, String skuNo) {
        String ownerId = ownerIdOf(entityNo);
        InvItemRef ref = findRef(ownerId, InvEnums.RefSystem.AISHOP, skuNo);
        return ref != null ? ref.getItemId() : upsertItem(entityNo, skuNo, skuNo, null, null, null, null);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String upsertItem(String entityNo, String skuNo, String name, String specText,
                             String barcode, String merchantSkuCode, String saleUnit) {
        String ownerId = ownerIdOf(entityNo);
        InvItemRef ref = findRef(ownerId, InvEnums.RefSystem.AISHOP, skuNo);
        InvItem item;
        if (ref == null) {
            item = new InvItem();
            item.setItemId(InvKeys.next(InvKeys.ITEM));
            item.setOwnerId(ownerId);
            item.setBaseUom(saleUnit == null || saleUnit.isBlank() ? DEFAULT_UOM : saleUnit);
            item.setCostMethod(InvEnums.CostMethod.LATEST);
            item.setDataSource(InvEnums.DataSource.SYNCED);
            item.setStatus(InvEnums.MasterStatus.ACTIVE);
            item.setWeighed(0);
            item.setTrackBatch(0);
            item.setSafetyStock(0);
            item.setName(name);
            item.setSpecText(specText);
            item.setItemCode(merchantSkuCode);
            item.setCreatedBy("SYSTEM");
            itemMapper.insert(item);
            addRef(ownerId, InvEnums.RefSystem.AISHOP, skuNo, item.getItemId());
        } else {
            item = itemMapper.selectOne(Wrappers.<InvItem>lambdaQuery()
                    .eq(InvItem::getOwnerId, ownerId).eq(InvItem::getItemId, ref.getItemId()));
            item.setName(name);
            item.setSpecText(specText);
            item.setItemCode(merchantSkuCode);
            /*
             * **计量单位只在还没有流水时才跟着改。**
             *
             * 从「件」改成「斤」，历史那些数字一个不变而含义全变了，
             * 且没有任何地方会报错 —— 报表照常出，只是每个数都错了。
             */
            if (saleUnit != null && !saleUnit.isBlank() && !saleUnit.equals(item.getBaseUom())
                    && !hasLedger(ownerId, item.getItemId())) {
                item.setBaseUom(saleUnit);
            }
            itemMapper.updateById(item);
        }
        // 条码与商家货号各是一条引用：一个物料可以有多个条码（换包装还是同一件货）
        if (barcode != null && !barcode.isBlank()) {
            addRef(ownerId, InvEnums.RefSystem.BARCODE, barcode, item.getItemId());
        }
        if (merchantSkuCode != null && !merchantSkuCode.isBlank()) {
            addRef(ownerId, InvEnums.RefSystem.ERP, merchantSkuCode, item.getItemId());
        }
        return item.getItemId();
    }

    /**
     * 只改一列，不碰名字规格 —— 商品下架时那些字段一个字都不变。
     *
     * <p><b>找不到就静静返回。</b> 商品的上架状态变了而它还没投影到进销存，
     * 那是「还没同步」不是错误；这时候建一条空壳物料，只会让清单里
     * 多出一件没有名字的货，而没有任何人能解释它是什么。
     */
    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void markItemOnSale(String entityNo, String skuNo, boolean onSale) {
        String ownerId = ownerIdOf(entityNo);
        InvItemRef ref = findRef(ownerId, InvEnums.RefSystem.AISHOP, skuNo);
        if (ref == null) {
            return;
        }
        InvItem item = itemMapper.selectOne(Wrappers.<InvItem>lambdaQuery()
                .eq(InvItem::getOwnerId, ownerId).eq(InvItem::getItemId, ref.getItemId()));
        if (item == null) {
            return;
        }
        item.setSourceOnSale(onSale ? 1 : 0);
        itemMapper.updateById(item);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public boolean retireItemIfEmpty(String entityNo, String skuNo, boolean apply) {
        String ownerId = ownerIdOf(entityNo);
        InvItemRef ref = findRef(ownerId, InvEnums.RefSystem.AISHOP, skuNo);
        if (ref == null) {
            return false;
        }
        InvItem item = itemMapper.selectOne(Wrappers.<InvItem>lambdaQuery()
                .eq(InvItem::getOwnerId, ownerId).eq(InvItem::getItemId, ref.getItemId()));
        if (item == null || InvEnums.MasterStatus.ARCHIVED.equals(item.getStatus())) {
            return false;   // 幂等：已经归档过的重跑一遍什么也不做
        }
        /*
         * **一个库位有数就不归档**，不是「合计为 0」就行。
         *
         * 合计会把「A 库位 +5、B 库位 -5」算成 0 —— 那正是最该有人去看的一种账，
         * 而归档掉它等于把两笔错都藏起来。预留也算有数：货被订单占着，
         * 归档之后那笔单去扣减时找不到这件货。
         */
        Long busy = balanceMapper.selectCount(Wrappers.<InvStockBalance>lambdaQuery()
                .eq(InvStockBalance::getOwnerId, ownerId)
                .eq(InvStockBalance::getItemId, item.getItemId())
                .and(w -> w.ne(InvStockBalance::getOnHand, 0).or().ne(InvStockBalance::getReserved, 0)));
        if (busy != null && busy > 0) {
            return false;
        }
        if (apply) {
            item.setStatus(InvEnums.MasterStatus.ARCHIVED);
            itemMapper.updateById(item);
        }
        return true;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void retireItem(String entityNo, String skuNo, String succeededBy) {
        String ownerId = ownerIdOf(entityNo);
        InvItemRef ref = findRef(ownerId, InvEnums.RefSystem.AISHOP, skuNo);
        if (ref == null) {
            /*
             * **找不到就静静返回**，与 markItemOnSale 同一条处置：
             * 这个 SKU 还没投影到进销存就被删了，那是「没来得及同步」不是错误。
             * 这时建一条空壳物料只会让清单里多出一件没人解释得了的货。
             */
            return;
        }
        InvItem item = itemMapper.selectOne(Wrappers.<InvItem>lambdaQuery()
                .eq(InvItem::getOwnerId, ownerId).eq(InvItem::getItemId, ref.getItemId()));
        if (item == null) {
            return;
        }
        /*
         * **接位者先记下来，不管归不归档。**
         *
         * 记在归档分支里的话，有库存那一类（也就是真正需要店主去决定的那一类）
         * 反而一个线索都没有 —— 而它正是这一列存在的理由。
         */
        /*
         * **只盖一次。** 每次都重新戳时间的话，事件重投、或跑批每天扫一遍，
         * 都会让「什么时候退休的」一天天往后漂 —— 而那个时间是给人看的，
         * 漂了之后「上周退的」会一直显示成「今天退的」。
         */
        if (item.getRetiredAt() == null) {
            item.setRetiredAt(java.time.LocalDateTime.now());
        }
        item.setSucceededBy(succeededBy);
        /*
         * 零库存就当场归档，与跑批 retireItemIfEmpty 同一个答案；
         * 有库存的留在 ACTIVE 上等人处置。判据走同一个方法，别在这儿再写一遍。
         */
        if (retireItemIfEmpty(entityNo, skuNo, false)) {
            item.setStatus(InvEnums.MasterStatus.ARCHIVED);
        }
        itemMapper.updateById(item);
    }

    // ────────────────────────────────────────────────────────────────────

    private boolean hasLedger(String ownerId, String itemId) {
        Long n = ledgerMapper.selectCount(Wrappers.<InvLedger>lambdaQuery()
                .eq(InvLedger::getOwnerId, ownerId).eq(InvLedger::getItemId, itemId));
        return n != null && n > 0;
    }

    private InvItemRef findRef(String ownerId, String system, String ref) {
        return refMapper.selectOne(Wrappers.<InvItemRef>lambdaQuery()
                .eq(InvItemRef::getOwnerId, ownerId)
                .eq(InvItemRef::getRefSystem, system)
                .eq(InvItemRef::getRef, ref));
    }

    private void addRef(String ownerId, String system, String ref, String itemId) {
        if (findRef(ownerId, system, ref) != null) {
            return;   // 幂等；唯一键兜底
        }
        InvItemRef row = new InvItemRef();
        row.setOwnerId(ownerId);
        row.setRefSystem(system);
        row.setRef(ref);
        row.setItemId(itemId);
        row.setCreatedBy("SYSTEM");
        refMapper.insert(row);
    }

    @Override
    public String ownerOfSku(String skuNo) {
        InvItemRef ref = refBySku(skuNo);
        return ref == null ? null : ref.getOwnerId();
    }

    @Override
    public String itemIdOfSku(String skuNo) {
        InvItemRef ref = refBySku(skuNo);
        return ref == null ? null : ref.getItemId();
    }

    @Override
    public String locationOfStore(String ownerId, String storeNo) {
        if (storeNo == null || storeNo.isBlank()) {
            return locations.defaultLocation(ownerId);
        }
        InvLocation row = locationMapper.selectOne(Wrappers.<InvLocation>lambdaQuery()
                .eq(InvLocation::getOwnerId, ownerId)
                .eq(InvLocation::getExternalRef, storeNo).last("LIMIT 1"));
        return row == null ? locations.defaultLocation(ownerId) : row.getLocationId();
    }

    /** SKU 在平台内全局唯一，所以按 (system=AISHOP, ref) 反查是确定的一条。 */
    private InvItemRef refBySku(String skuNo) {
        return refMapper.selectOne(Wrappers.<InvItemRef>lambdaQuery()
                .eq(InvItemRef::getRefSystem, InvEnums.RefSystem.AISHOP)
                .eq(InvItemRef::getRef, skuNo).last("LIMIT 1"));
    }
}

package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.entity.InvItem;
import ai.neargo.shop.inventory.entity.InvItemRef;
import ai.neargo.shop.inventory.entity.InvLedger;
import ai.neargo.shop.inventory.entity.InvLocation;
import ai.neargo.shop.inventory.entity.InvOwner;
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
@Service
public class InventoryAclServiceImpl implements InventoryAclService {

    /** 平台默认单位。{@code sale_unit} 是可空的，而计量单位不能空 —— 空了就没法相加。 */
    private static final String DEFAULT_UOM = "PIECE";

    private final OwnerMapper ownerMapper;
    private final LocationMapper locationMapper;
    private final ItemMapper itemMapper;
    private final ItemRefMapper refMapper;
    private final LedgerMapper ledgerMapper;
    private final LocationService locations;

    public InventoryAclServiceImpl(OwnerMapper ownerMapper, LocationMapper locationMapper,
                                   ItemMapper itemMapper, ItemRefMapper refMapper,
                                   LedgerMapper ledgerMapper, LocationService locations) {
        this.ownerMapper = ownerMapper;
        this.locationMapper = locationMapper;
        this.itemMapper = itemMapper;
        this.refMapper = refMapper;
        this.ledgerMapper = ledgerMapper;
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
}

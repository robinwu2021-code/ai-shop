package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.entity.InvItemRef;
import ai.neargo.inventory.domain.entity.InvLocation;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ItemMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ItemRefMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LocationMapper;
import ai.neargo.inventory.domain.service.HostReferenceService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@ConditionalOnInventory
@Service
public class HostReferenceServiceImpl implements HostReferenceService {

    private final ItemMapper itemMapper;
    private final ItemRefMapper refMapper;
    private final LocationMapper locationMapper;
    private final LocationService locations;

    public HostReferenceServiceImpl(ItemMapper itemMapper, ItemRefMapper refMapper, LocationMapper locationMapper,
                                    LocationService locations) {
        this.itemMapper = itemMapper;
        this.refMapper = refMapper;
        this.locationMapper = locationMapper;
        this.locations = locations;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager", readOnly = true)
    public String itemIdOf(String ownerId, String itemRef) {
        if (itemRef == null || itemRef.isBlank()) {
            return null;
        }
        InvItem item = itemMapper.selectOne(Wrappers.<InvItem>lambdaQuery()
                .eq(InvItem::getOwnerId, ownerId).eq(InvItem::getExternalRef, itemRef));
        if (item != null) {
            return item.getItemId();
        }
        // 商城内嵌时期建的物料：SKU 映射在 inv_item_ref 里
        InvItemRef ref = refMapper.selectOne(Wrappers.<InvItemRef>lambdaQuery()
                .eq(InvItemRef::getOwnerId, ownerId).eq(InvItemRef::getRefSystem, InvEnums.RefSystem.AISHOP)
                .eq(InvItemRef::getRef, itemRef).last("LIMIT 1"));
        return ref == null ? null : ref.getItemId();
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager", readOnly = true)
    public String itemRefOf(String ownerId, String itemId) {
        InvItem item = itemMapper.selectOne(Wrappers.<InvItem>lambdaQuery()
                .eq(InvItem::getOwnerId, ownerId).eq(InvItem::getItemId, itemId));
        if (item != null && item.getExternalRef() != null) {
            return item.getExternalRef();
        }
        var refs = refMapper.selectList(Wrappers.<InvItemRef>lambdaQuery()
                .eq(InvItemRef::getOwnerId, ownerId).eq(InvItemRef::getRefSystem, InvEnums.RefSystem.AISHOP)
                .eq(InvItemRef::getItemId, itemId));
        // 一个物料挂多个 SKU 时反查不唯一：不猜
        return refs.size() == 1 ? refs.getFirst().getRef() : null;
    }

    @Override
    // 不是只读：默认库位第一次用到时才建（LocationService.defaultLocation）
    @Transactional(transactionManager = "invTransactionManager")
    public String stockLocationOf(String ownerId, String locationRef) {
        if (locationRef == null || locationRef.isBlank()) {
            // 不指定门店 = 商家级库存，落在默认库位（与商城内嵌时期一致）
            return locations.resolveStockLocation(ownerId, locations.defaultLocation(ownerId));
        }
        InvLocation location = locationMapper.selectOne(Wrappers.<InvLocation>lambdaQuery()
                .eq(InvLocation::getOwnerId, ownerId).eq(InvLocation::getExternalRef, locationRef));
        return location == null ? null : locations.resolveStockLocation(ownerId, location.getLocationId());
    }
}

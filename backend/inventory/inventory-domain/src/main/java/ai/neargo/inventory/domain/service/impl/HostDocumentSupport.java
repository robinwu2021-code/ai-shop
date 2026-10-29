package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.Capability;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.entity.InvLocation;
import ai.neargo.inventory.domain.entity.InvSerial;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ItemMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LocationMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.SerialMapper;
import ai.neargo.inventory.domain.service.BusinessProfileService;
import ai.neargo.inventory.domain.service.BusinessProfileService.Versioned;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 宿主单据的公共校验：档案、库位、物料、SN 加锁。入库与出库共用，保证两边规则一字不差。 */
@ConditionalOnInventory
@Component
class HostDocumentSupport {

    private final BusinessProfileService profiles;
    private final LocationMapper locationMapper;
    private final ItemMapper itemMapper;
    private final SerialMapper serialMapper;

    HostDocumentSupport(BusinessProfileService profiles, LocationMapper locationMapper, ItemMapper itemMapper,
                        SerialMapper serialMapper) {
        this.profiles = profiles;
        this.locationMapper = locationMapper;
        this.itemMapper = itemMapper;
        this.serialMapper = serialMapper;
    }

    Versioned profile(String ownerId) {
        return profiles.current(ownerId)
                .orElseThrow(() -> InventoryException.of(InventoryError.CAPABILITY_DISABLED, "profile"));
    }

    void requireCapability(String ownerId, Capability capability) {
        profiles.requireCapability(ownerId, capability);
    }

    void requireActiveLocation(String ownerId, String locationId) {
        InvLocation location = locationMapper.selectOne(Wrappers.<InvLocation>lambdaQuery()
                .eq(InvLocation::getOwnerId, ownerId).eq(InvLocation::getLocationId, locationId));
        if (location == null || !InvEnums.MasterStatus.ACTIVE.equals(location.getStatus())) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "locationId");
        }
    }

    /** 本业主、在用的物料；不在的一次列全。 */
    Map<String, InvItem> activeItems(String ownerId, Collection<String> itemIds) {
        Map<String, InvItem> found = new HashMap<>();
        if (!itemIds.isEmpty()) {
            for (InvItem i : itemMapper.selectList(Wrappers.<InvItem>lambdaQuery()
                    .eq(InvItem::getOwnerId, ownerId).in(InvItem::getItemId, itemIds))) {
                if (InvEnums.MasterStatus.ACTIVE.equals(i.getStatus())) {
                    found.put(i.getItemId(), i);
                }
            }
        }
        List<String> missing = itemIds.stream().filter(id -> !found.containsKey(id)).distinct().toList();
        if (!missing.isEmpty()) {
            throw InventoryException.of(InventoryError.NOT_FOUND, missing.toArray());
        }
        return found;
    }

    static boolean serial(InvItem item) {
        return InvEnums.TrackingMode.SERIAL.equals(item.getTrackingMode());
    }

    /** 加锁读一件 SN。调用方负责按 SN 号升序调用 —— 固定锁序，两张单互相等不死。 */
    InvSerial lockSerial(String ownerId, String serialNo) {
        return serialMapper.selectOne(Wrappers.<InvSerial>lambdaQuery()
                .eq(InvSerial::getOwnerId, ownerId).eq(InvSerial::getSerialNo, serialNo).last("FOR UPDATE"));
    }

    static String operator(Caller caller) {
        return caller.actor() == null ? caller.systemCode() : caller.actor();
    }
}

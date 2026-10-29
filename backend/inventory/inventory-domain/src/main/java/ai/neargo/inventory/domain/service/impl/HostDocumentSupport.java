package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.BusinessProfile;
import ai.neargo.inventory.api.profile.BusinessProfile.OutboundPurpose;
import ai.neargo.inventory.api.profile.Capability;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.entity.InvLocation;
import ai.neargo.inventory.domain.entity.InvSerial;
import ai.neargo.inventory.domain.entity.InvTargetRef;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ItemMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LocationMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.SerialMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.TargetRefMapper;
import ai.neargo.inventory.domain.service.BusinessProfileService;
import ai.neargo.inventory.domain.service.BusinessProfileService.Versioned;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final TargetRefMapper targetMapper;

    HostDocumentSupport(BusinessProfileService profiles, LocationMapper locationMapper, ItemMapper itemMapper,
                        SerialMapper serialMapper, TargetRefMapper targetMapper) {
        this.profiles = profiles;
        this.locationMapper = locationMapper;
        this.itemMapper = itemMapper;
        this.serialMapper = serialMapper;
        this.targetMapper = targetMapper;
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

    /** 按档案的收货方规则：必填的都要给、只许给登记过的类型、可选目标须挂在所选的上级目标下。 */
    Map<String, InvTargetRef> resolveTargets(Caller caller, OutboundPurpose purpose, Map<String, String> given) {
        List<String> required = purpose.requiredTargets() == null ? List.of() : purpose.requiredTargets();
        List<String> optional = purpose.optionalTargets() == null ? List.of() : purpose.optionalTargets();
        for (String type : required) {
            if (!given.containsKey(type)) {
                throw InventoryException.of(InventoryError.BAD_REQUEST, "target.required:" + type);
            }
        }
        Map<String, InvTargetRef> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : given.entrySet()) {
            if (!required.contains(e.getKey()) && !optional.contains(e.getKey())) {
                throw InventoryException.of(InventoryError.BAD_REQUEST, "target.type:" + e.getKey());
            }
            InvTargetRef t = target(caller, e.getKey(), e.getValue());
            if (t == null || !InvEnums.MasterStatus.ACTIVE.equals(t.getStatus())) {
                throw InventoryException.of(InventoryError.BAD_REQUEST, "target:" + e.getKey());
            }
            resolved.put(e.getKey(), t);
        }
        for (InvTargetRef t : resolved.values()) {
            if (optional.contains(t.getObjectType()) && t.getParentType() != null) {
                InvTargetRef parent = resolved.get(t.getParentType());
                if (parent == null || !parent.getObjectRef().equals(t.getParentRef())) {
                    // 例：站点必须属于所选商家
                    throw InventoryException.of(InventoryError.BAD_REQUEST, "target.parent:" + t.getObjectType());
                }
            }
        }
        return resolved;
    }

    InvTargetRef target(Caller caller, String type, String ref) {
        return targetMapper.selectOne(Wrappers.<InvTargetRef>lambdaQuery()
                .eq(InvTargetRef::getOwnerId, caller.ownerId())
                .eq(InvTargetRef::getSystemCode, caller.systemCode())
                .eq(InvTargetRef::getObjectType, type)
                .eq(InvTargetRef::getObjectRef, ref));
    }

    /** 用途的定义（档案里没有就拒绝）。 */
    static OutboundPurpose outboundPurpose(BusinessProfile profile, String code) {
        return (profile.outboundPurposes() == null ? List.<OutboundPurpose>of() : profile.outboundPurposes())
                .stream().filter(p -> p.code().equals(code)).findFirst()
                .orElseThrow(() -> InventoryException.of(InventoryError.BAD_REQUEST, "purpose"));
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

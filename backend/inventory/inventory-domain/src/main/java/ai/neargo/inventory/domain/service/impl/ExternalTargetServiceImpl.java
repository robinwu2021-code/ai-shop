package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.BusinessProfile;
import ai.neargo.inventory.api.profile.BusinessProfile.TargetType;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvLocation;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.domain.entity.InvTargetRef;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LocationMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.TargetRefMapper;
import ai.neargo.inventory.domain.service.AuditService;
import ai.neargo.inventory.domain.service.BusinessProfileService;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.ExternalTargetService;
import ai.neargo.inventory.domain.service.ServicePlatformService;
import ai.neargo.inventory.domain.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@ConditionalOnInventory
@Service
public class ExternalTargetServiceImpl implements ExternalTargetService {

    private final TargetRefMapper targetMapper;
    private final LocationMapper locationMapper;
    private final BusinessProfileService profiles;
    private final ServicePlatformService platform;
    private final AuditService audit;

    public ExternalTargetServiceImpl(TargetRefMapper targetMapper, LocationMapper locationMapper,
                                     BusinessProfileService profiles, ServicePlatformService platform,
                                     AuditService audit) {
        this.targetMapper = targetMapper;
        this.locationMapper = locationMapper;
        this.profiles = profiles;
        this.platform = platform;
        this.audit = audit;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public InvTargetRef upsert(Caller caller, String type, String ref, String name, String parentRef,
                               String status, long sourceVersion, Link link) {
        if (ref == null || ref.isBlank() || ref.length() > 64 || name == null || name.isBlank()
                || name.length() > 128) {
            throw InventoryException.of(InventoryError.BAD_REQUEST);
        }
        String state = status == null ? InvEnums.MasterStatus.ACTIVE : status;
        if (!InvEnums.MasterStatus.ACTIVE.equals(state) && !InvEnums.MasterStatus.DISABLED.equals(state)) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "status");
        }
        TargetType registered = registered(caller.ownerId(), type);
        String parentType = registered.parentType();
        if (parentType == null) {
            if (parentRef != null) {
                throw InventoryException.of(InventoryError.BAD_REQUEST, "parentRef");
            }
        } else if (parentRef == null || find(caller, parentType, parentRef, false) == null) {
            // 站点必须挂在一个已同步的商家下：「这单发给谁」靠这条链说清
            throw InventoryException.of(InventoryError.BAD_REQUEST, "parentRef");
        }

        InvLocation linked = link == null ? null : linkedLocation(caller, link);

        InvTargetRef row = find(caller, type, ref, true);
        String operator = caller.actor() == null ? caller.systemCode() : caller.actor();
        if (row == null) {
            row = new InvTargetRef();
            row.setOwnerId(caller.ownerId());
            row.setSystemCode(caller.systemCode());
            row.setObjectType(type);
            row.setObjectRef(ref);
            fill(row, name, parentType, parentRef, state, sourceVersion, linked);
            row.setCreatedBy(operator);
            try {
                targetMapper.insert(row);
            } catch (DuplicateKeyException race) {
                // 并发首推：另一边先建了，按「已存在」走一遍版本比较
                return upsert(caller, type, ref, name, parentRef, status, sourceVersion, link);
            }
            audit.record(caller, "target.create", "TARGET", type + ":" + ref, null, row, null);
            return row;
        }
        if (sourceVersion < row.getSourceVersion()) {
            // 旧版本到达忽略：宿主重放或乱序推送，不能把新名字改回旧名字
            return row;
        }
        String before = row.getName() + "|" + row.getStatus() + "|" + row.getParentRef() + "|"
                + row.getLinkedOwnerId();
        fill(row, name, parentType, parentRef, state, sourceVersion, linked);
        row.setUpdatedBy(operator);
        targetMapper.updateById(row);
        // 解除关联要写 null：updateById 跳过 null 字段
        targetMapper.update(null, Wrappers.<InvTargetRef>lambdaUpdate()
                .set(InvTargetRef::getLinkedOwnerId, row.getLinkedOwnerId())
                .set(InvTargetRef::getLinkedLocationId, row.getLinkedLocationId())
                .eq(InvTargetRef::getId, row.getId()));
        audit.record(caller, "target.update", "TARGET", type + ":" + ref, before, row, null);
        return row;
    }

    @Override
    public List<InvTargetRef> list(String ownerId, String type, String parentRef) {
        return targetMapper.selectList(Wrappers.<InvTargetRef>lambdaQuery()
                .eq(InvTargetRef::getOwnerId, ownerId)
                .eq(type != null, InvTargetRef::getObjectType, type)
                .eq(parentRef != null, InvTargetRef::getParentRef, parentRef)
                .orderByAsc(InvTargetRef::getObjectType, InvTargetRef::getObjectRef));
    }

    private TargetType registered(String ownerId, String type) {
        BusinessProfile profile = profiles.current(ownerId)
                .orElseThrow(() -> InventoryException.of(InventoryError.CAPABILITY_DISABLED, "profile"))
                .profile();
        return (profile.targetTypes() == null ? List.<TargetType>of() : profile.targetTypes()).stream()
                .filter(t -> t.code().equals(type))
                .findFirst()
                .orElseThrow(() -> InventoryException.of(InventoryError.BAD_REQUEST, "type"));
    }

    private InvTargetRef find(Caller caller, String type, String ref, boolean lock) {
        var query = Wrappers.<InvTargetRef>lambdaQuery()
                .eq(InvTargetRef::getOwnerId, caller.ownerId())
                .eq(InvTargetRef::getSystemCode, caller.systemCode())
                .eq(InvTargetRef::getObjectType, type)
                .eq(InvTargetRef::getObjectRef, ref);
        return targetMapper.selectOne(lock ? query.last("FOR UPDATE") : query);
    }

    /** 对方账本须同系统、不能是自己；收货库位按宿主编号在对方账本里找。 */
    private InvLocation linkedLocation(Caller caller, Link link) {
        InvOwner owner = platform.ownerByRef(caller.systemCode(), link.ownerType(), link.ownerRef());
        if (owner == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "linkedOwner");
        }
        if (owner.getOwnerId().equals(caller.ownerId())) {
            // 同一本账里的门店是库位，货过去走调拨，不是出库对象
            throw InventoryException.of(InventoryError.BAD_REQUEST, "linkedOwner.self");
        }
        InvLocation location = link.locationRef() == null ? null
                : locationMapper.selectOne(Wrappers.<InvLocation>lambdaQuery()
                        .eq(InvLocation::getOwnerId, owner.getOwnerId())
                        .eq(InvLocation::getExternalRef, link.locationRef()));
        if (location == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "linkedLocation");
        }
        return location;
    }

    private static void fill(InvTargetRef row, String name, String parentType, String parentRef, String status,
                             long sourceVersion, InvLocation linked) {
        row.setName(name);
        row.setParentType(parentType);
        row.setParentRef(parentRef);
        row.setStatus(status);
        row.setSourceVersion(sourceVersion);
        row.setLinkedOwnerId(linked == null ? null : linked.getOwnerId());
        row.setLinkedLocationId(linked == null ? null : linked.getLocationId());
    }
}

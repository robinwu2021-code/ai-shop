package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.BusinessProfile;
import ai.neargo.inventory.api.profile.BusinessProfile.TargetType;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvTargetRef;
import ai.neargo.inventory.domain.mapper.InventoryMappers.TargetRefMapper;
import ai.neargo.inventory.domain.service.AuditService;
import ai.neargo.inventory.domain.service.BusinessProfileService;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.ExternalTargetService;
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
    private final BusinessProfileService profiles;
    private final AuditService audit;

    public ExternalTargetServiceImpl(TargetRefMapper targetMapper, BusinessProfileService profiles,
                                     AuditService audit) {
        this.targetMapper = targetMapper;
        this.profiles = profiles;
        this.audit = audit;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public InvTargetRef upsert(Caller caller, String type, String ref, String name, String parentRef,
                               String status, long sourceVersion) {
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

        InvTargetRef row = find(caller, type, ref, true);
        String operator = caller.actor() == null ? caller.systemCode() : caller.actor();
        if (row == null) {
            row = new InvTargetRef();
            row.setOwnerId(caller.ownerId());
            row.setSystemCode(caller.systemCode());
            row.setObjectType(type);
            row.setObjectRef(ref);
            fill(row, name, parentType, parentRef, state, sourceVersion);
            row.setCreatedBy(operator);
            try {
                targetMapper.insert(row);
            } catch (DuplicateKeyException race) {
                // 并发首推：另一边先建了，按「已存在」走一遍版本比较
                return upsert(caller, type, ref, name, parentRef, status, sourceVersion);
            }
            audit.record(caller, "target.create", "TARGET", type + ":" + ref, null, row, null);
            return row;
        }
        if (sourceVersion < row.getSourceVersion()) {
            // 旧版本到达忽略：宿主重放或乱序推送，不能把新名字改回旧名字
            return row;
        }
        String before = row.getName() + "|" + row.getStatus() + "|" + row.getParentRef();
        fill(row, name, parentType, parentRef, state, sourceVersion);
        row.setUpdatedBy(operator);
        targetMapper.updateById(row);
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

    private static void fill(InvTargetRef row, String name, String parentType, String parentRef, String status,
                             long sourceVersion) {
        row.setName(name);
        row.setParentType(parentType);
        row.setParentRef(parentRef);
        row.setStatus(status);
        row.setSourceVersion(sourceVersion);
    }
}

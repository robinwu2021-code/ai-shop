package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.BusinessProfile;
import ai.neargo.inventory.api.profile.Capability;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvBusinessProfile;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.domain.mapper.InventoryMappers.BusinessProfileMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.inventory.domain.service.BusinessProfileService;
import ai.neargo.inventory.domain.support.Digests;
import ai.neargo.inventory.domain.support.ProfileCodec;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@ConditionalOnInventory
@Service
public class BusinessProfileServiceImpl implements BusinessProfileService {

    private final BusinessProfileMapper profileMapper;
    private final OwnerMapper ownerMapper;

    public BusinessProfileServiceImpl(BusinessProfileMapper profileMapper, OwnerMapper ownerMapper) {
        this.profileMapper = profileMapper;
        this.ownerMapper = ownerMapper;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public int save(String ownerId, BusinessProfile profile, String reason, String operator) {
        ProfileCodec.validate(profile);
        // 锁住业主行：两个管理员同时改档案时版本号不能撞，也不能后写的被先写的覆盖成「当前」
        InvOwner owner = ownerMapper.selectOne(Wrappers.<InvOwner>lambdaQuery()
                .eq(InvOwner::getOwnerId, ownerId).last("FOR UPDATE"));
        if (owner == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND);
        }
        int next = owner.getProfileVersion() == null ? 1 : owner.getProfileVersion() + 1;
        String body = ProfileCodec.write(profile);
        InvBusinessProfile row = new InvBusinessProfile();
        row.setOwnerId(ownerId);
        row.setVersion(next);
        row.setBody(body);
        row.setBodyHash(Digests.sha256(body));
        row.setReason(reason);
        row.setCreatedBy(operator);
        profileMapper.insert(row);
        owner.setProfileVersion(next);
        owner.setUpdatedBy(operator);
        ownerMapper.updateById(owner);
        return next;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager", readOnly = true)
    public Optional<Versioned> current(String ownerId) {
        InvOwner owner = ownerMapper.selectOne(Wrappers.<InvOwner>lambdaQuery().eq(InvOwner::getOwnerId, ownerId));
        if (owner == null || owner.getProfileVersion() == null) {
            return Optional.empty();
        }
        InvBusinessProfile row = profileMapper.selectOne(Wrappers.<InvBusinessProfile>lambdaQuery()
                .eq(InvBusinessProfile::getOwnerId, ownerId)
                .eq(InvBusinessProfile::getVersion, owner.getProfileVersion()));
        return row == null ? Optional.empty()
                : Optional.of(new Versioned(row.getVersion(), ProfileCodec.parse(row.getBody())));
    }

    @Override
    public void requireCapability(String ownerId, Capability capability) {
        boolean enabled = current(ownerId)
                .map(v -> v.profile().capabilities() != null && v.profile().capabilities().contains(capability))
                .orElse(false);
        if (!enabled) {
            throw InventoryException.of(InventoryError.CAPABILITY_DISABLED, capability.name());
        }
    }
}

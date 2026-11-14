package ai.neargo.inventory.domain.service;

import ai.neargo.inventory.api.profile.BusinessProfile;
import ai.neargo.inventory.api.profile.Capability;

import java.util.Optional;

/**
 * 业态档案（TDD §2.5）：业主级、带版本、只追加。
 *
 * <p>档案由平台管理员经运维接口改，宿主只读（TDD §7.2 Q4）。
 */
public interface BusinessProfileService {

    /**
     * 存一个新版本并设为当前。
     *
     * @return 新版本号（从 1 起）
     * @throws ai.neargo.inventory.api.InventoryException {@code PROFILE_INVALID} —— 带第一条违反的规则
     */
    int save(String ownerId, BusinessProfile profile, String reason, String operator);

    /** 当前生效的档案；没装过档案为空。 */
    Optional<Versioned> current(String ownerId);

    /**
     * 业主没开通这个能力包就拒绝。
     *
     * @throws ai.neargo.inventory.api.InventoryException {@code CAPABILITY_DISABLED}
     */
    void requireCapability(String ownerId, Capability capability);

    record Versioned(int version, BusinessProfile profile) {
    }
}

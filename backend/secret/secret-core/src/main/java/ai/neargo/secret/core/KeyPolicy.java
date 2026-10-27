package ai.neargo.secret.core;

import ai.neargo.secret.store.entity.SecKey;
import ai.neargo.secret.store.entity.SecKeyPolicy;
import ai.neargo.secret.store.mapper.SecretMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * 生效的策略：{@code sec_key.sec_key_policy_id}，空则按 {@code namespace + owner_type + env} 取 {@code sec_key_policy}，
 * 再没有就用 DDL 上的默认值（不强制最长使用期、提前 30 天提醒、重叠 7 天、启用前必须验证）。
 */
record KeyPolicy(int maxAgeDays, int warnBeforeDays, int overlapDays, boolean verifyBeforeEnable) {

    static final KeyPolicy DEFAULT = new KeyPolicy(0, 30, 7, true);

    static KeyPolicy of(SecretMappers.KeyPolicyMapper policies, SecKey key) {
        SecKeyPolicy p = key.getSecKeyPolicyId() != null
                ? policies.selectById(key.getSecKeyPolicyId())
                : policies.selectOne(Wrappers.<SecKeyPolicy>lambdaQuery()
                        .eq(SecKeyPolicy::getNamespace, key.getNamespace())
                        .eq(SecKeyPolicy::getOwnerType, key.getOwnerType())
                        .eq(SecKeyPolicy::getEnv, key.getEnv()));
        if (p == null) {
            return DEFAULT;
        }
        return new KeyPolicy(p.getMaxAgeDays(), p.getWarnBeforeDays(), p.getOverlapDays(),
                !Boolean.FALSE.equals(p.getVerifyBeforeEnable()));
    }
}

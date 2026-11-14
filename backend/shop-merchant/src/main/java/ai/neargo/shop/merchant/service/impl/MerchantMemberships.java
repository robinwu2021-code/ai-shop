package ai.neargo.shop.merchant.service.impl;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.merchant.entity.MchAccount;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/** A signed subject selects one account identifier column. Unauthenticated legacy callers
 * supply a bare principal and keep the original two-column lookup. */
final class MerchantMemberships {
    private MerchantMemberships() {}

    static LambdaQueryWrapper<MchAccount> active(String principal, SubjectKind kind) {
        var query = Wrappers.<MchAccount>lambdaQuery();
        switch (kind) {
            case USR -> query.eq(MchAccount::getUserNo, principal);
            case MCH -> query.eq(MchAccount::getMchAccountNo, principal);
            case null -> query.and(q -> q.eq(MchAccount::getUserNo, principal)
                    .or().eq(MchAccount::getMchAccountNo, principal));
            default -> throw new IllegalArgumentException("Merchant subject required");
        }
        return query.eq(MchAccount::getStatus, MchAccount.ACTIVE)
                .orderByDesc(MchAccount::getIsPrimary).orderByAsc(MchAccount::getId);
    }
}

package ai.neargo.shop.pay.storedvalue.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.mapper.storedvalue.SvAccountMapper;

/** Unassigned historical money is never silently hidden or copied into a new store wallet. */
public final class StoredValueAccountScope {
    private StoredValueAccountScope() { }

    public static void requireMigrated(SvAccountMapper accounts, String tenant, String user,
            String entity, String currency) {
        if (accounts.hasLegacyAccount(tenant, user, entity, currency)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
}

package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.common.StoreStatus;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Keeps legacy member queries entity-scoped without exposing an unassigned new-model store. */
@Component
@Profile("api")
public final class MemberQueryScopeAuthority {
    private final ShopCommerceStoreAdapter stores;
    private final BizIdentityResolver identities;

    public MemberQueryScopeAuthority(ShopCommerceStoreAdapter stores, BizIdentityResolver identities) {
        this.stores = stores;
        this.identities = identities;
    }

    /** A missing filter is the existing entity aggregate, including every new-model target. */
    public void require(String targetStoreNo) {
        var user = SecurityUtils.requireUser();
        String entity = BizContext.requireMerchantNo();
        for (var target : stores.memberQueryTargets(user.tenantNo(), entity, targetStoreNo)) {
            String no = target.store().storeNo();
            BizContext.requireStoreAccess(no);
            var actual = identities.resolveStrict(user, no);
            if (target.status() != StoreStatus.ACTIVE
                    || !entity.equals(actual.merchantNo())
                    || !no.equals(actual.currentStoreNo())
                    || !actual.can(BizPerms.CUSTOMER))
                throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }
}

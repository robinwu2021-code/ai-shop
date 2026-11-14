package ai.neargo.shop.commerce.api;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import java.util.List;

/** The platform's original operator realm, tenant and merchant data scope remain authoritative. */
public final class CommerceOperatorScope {
    private final StoreDirectoryPort stores;

    public CommerceOperatorScope(StoreDirectoryPort stores) {
        this.stores = stores;
    }

    public String entity(String entity, String store) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.OPERATOR || !"MAIN".equals(user.tenantNo()))
            throw BizException.of(ErrorCode.FORBIDDEN);
        CommerceValidation.text(entity, 32);
        var scope = user.dataScope();
        if (scope == null
                || !scope.all()
                        && scope.rules().stream()
                                .noneMatch(
                                        rule ->
                                                ScopeDim.MERCHANT.equals(rule.dim())
                                                        && rule.refs() != null
                                                        && rule.refs().contains(entity)))
            throw BizException.of(ErrorCode.FORBIDDEN);
        if (store != null) {
            CommerceValidation.text(store, 32);
            var card = stores.cards(List.of(store)).get(store);
            if (card == null || !entity.equals(card.entityNo()) || !store.equals(card.storeNo()))
                throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return entity;
    }

    public String platform() {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.OPERATOR
                || !"MAIN".equals(user.tenantNo())
                || user.dataScope() == null
                || !user.dataScope().all()) throw BizException.of(ErrorCode.FORBIDDEN);
        return user.userNo();
    }
}

package ai.neargo.shop.product.policy;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.StoreRequestScopeValidator;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.util.List;
import org.springframework.stereotype.Component;

/** One public boundary resolves real actor/store scope; body never selects another ledger owner. */
@Component
public class LocalStockAccessPolicy {
    private final StoreDirectoryPort stores;
    public LocalStockAccessPolicy(StoreDirectoryPort stores) { this.stores = stores; }
    public Scope context(String requestedStore) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.MERCHANT || !"MAIN".equals(user.tenantNo()))
            throw BizException.of(ErrorCode.FORBIDDEN);
        String entity = BizContext.requireMerchantNo();
        String store = BizContext.requireStoreNo();
        var card = stores.cards(List.of(store)).get(store);
        if (card == null) throw BizException.of(ErrorCode.NOT_FOUND);
        StoreRequestScopeValidator.requireStoreTarget(entity, store, card.entityNo(), card.storeNo(),
                requestedStore, BizContext.current().storeNos());
        return new Scope(user.tenantNo(), entity, store);
    }
}

package ai.neargo.shop.product.policy;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.Context;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Scope;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.util.List;
import org.springframework.stereotype.Component;

/** Tax classification edits require the merchant owner, just like merchant tax settings. */
@Component
public class GoodsPricingPolicyAccessPolicy {
    private final StoreDirectoryPort stores;
    public GoodsPricingPolicyAccessPolicy(StoreDirectoryPort stores) { this.stores = stores; }
    public Context context(boolean write) {
        var user = SecurityUtils.requireUser();
        if (!"MAIN".equals(user.tenantNo()) || user.realm() != Realm.MERCHANT
                || (write && !BizContext.current().owner())) throw forbidden();
        String entity = BizContext.requireMerchantNo(); String store = BizContext.requireStoreNo();
        if (!BizContext.current().storeNos().contains(store)) throw forbidden();
        var card = stores.cards(List.of(store)).get(store);
        if (card == null || !entity.equals(card.entityNo()) || !store.equals(card.storeNo())) throw forbidden();
        return new Context(new Scope(user.tenantNo(), entity, store), user.userNo());
    }
    private static BizException forbidden() { return BizException.of(ErrorCode.FORBIDDEN); }
}

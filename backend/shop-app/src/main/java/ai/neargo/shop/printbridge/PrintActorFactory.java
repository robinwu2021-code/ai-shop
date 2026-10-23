package ai.neargo.shop.printbridge;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class PrintActorFactory {
    private static final Set<String> PRINT_PERMS =
            Set.of(
                    "biz:print:read",
                    "biz:print:submit",
                    "biz:print:reprint",
                    "biz:print:extra",
                    "biz:print:device",
                    "biz:print:config",
                    "biz:print:resolve",
                    "biz:print:simulate",
                    "biz:print:export");
    private final MerchantScopeFactsPort merchants;

    public PrintActorFactory(MerchantScopeFactsPort m) {
        merchants = m;
    }

    public PrintActor merchant(String key) {
        var user = SecurityUtils.requireUser();
        var ctx = BizContext.current();
        String store = BizContext.requireStoreNo();
        if (user.realm() != Realm.MERCHANT
                || ctx.storeNos() == null
                || !ctx.storeNos().contains(store)) throw new PrintFailure("print.forbidden");
        var granted = new HashSet<String>();
        for (var p : PRINT_PERMS) if (ctx.can(p)) granted.add(p);
        if (ctx.can(BizPerms.ORDER_VIEW) && !BizPerms.onlyCourierOrderView(ctx.staffRoles()))
            granted.add("SOURCE_ORDER_READ");
        return new PrintActor(
                user.tenantNo(),
                BizContext.requireMerchantNo(),
                store,
                "MERCHANT",
                user.userNo(),
                key,
                granted);
    }

    public PrintOpsActor operator(String key, String tenant, String entity, String store) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.OPERATOR
                || !Objects.equals(user.tenantNo(), tenant)
                || entity == null
                || store == null
                || !merchants.storesOwned(tenant, entity, Set.of(store)))
            throw new PrintFailure("print.forbidden");
        var scope = user.dataScope();
        boolean allowed =
                scope != null
                        && (scope.all()
                                || scope.rules().stream()
                                        .anyMatch(
                                                r ->
                                                        ScopeDim.MERCHANT.equals(r.dim())
                                                                && r.refs() != null
                                                                && r.refs().contains(entity)));
        if (!allowed) throw new PrintFailure("print.forbidden");
        var permissions = new HashSet<String>();
        permissions.add("ops:print:read");
        permissions.add("biz:print:resolve");
        return new PrintOpsActor(
                tenant, user.userNo(), Set.of(entity), Set.of(store), permissions, key);
    }
}

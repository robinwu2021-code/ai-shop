package ai.neargo.shop.printbridge;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

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
    private final MchStoreMapper stores;

    public PrintActorFactory(MchStoreMapper stores) {
        this.stores = stores;
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
        if (ctx.can(BizPerms.SV_RECHARGE_READ) && ctx.can(BizPerms.SV_ACCOUNT_READ))
            granted.add("SOURCE_RECHARGE_READ");
        if (ctx.can(BizPerms.AFTERSALE)) granted.add("SOURCE_REFUND_READ");
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
                || entity == null || entity.isBlank()
                || store == null || store.isBlank())
            throw new PrintFailure("print.forbidden");
        var scope = user.dataScope();
        boolean allowed =
                scope != null
                        && (scope.all()
                                || (scope.rules() != null && scope.rules().stream()
                                        .filter(Objects::nonNull)
                                        .anyMatch(
                                                r ->
                                                        ScopeDim.MERCHANT.equals(r.dim())
                                                                && r.refs() != null
                                                                && r.refs().contains(entity))));
        if (!allowed) throw new PrintFailure("print.forbidden");

        // Request fields only select a candidate. This registered-table read keeps the
        // operator's DataScope active and verifies tenant, owner and store together.
        // Do not use storesOwned: that write-preflight port deliberately bypasses scope.
        var authorized = stores.selectOne(Wrappers.<MchStore>lambdaQuery()
                .eq(MchStore::getTenantNo, user.tenantNo())
                .eq(MchStore::getEntityNo, entity)
                .eq(MchStore::getStoreNo, store)
                .in(MchStore::getStatus, MchStore.ACTIVE, MchStore.READONLY));
        if (authorized == null
                || !Objects.equals(user.tenantNo(), authorized.getTenantNo())
                || !Objects.equals(entity, authorized.getEntityNo())
                || !Objects.equals(store, authorized.getStoreNo()))
            throw new PrintFailure("print.forbidden");
        var permissions = new HashSet<String>();
        permissions.add("ops:print:read");
        permissions.add("biz:print:resolve");
        return new PrintOpsActor(
                user.tenantNo(), user.userNo(), Set.of(authorized.getEntityNo()),
                Set.of(authorized.getStoreNo()), permissions, key);
    }
}

package ai.neargo.shop.merchant.port;

import ai.neargo.shop.spi.printing.*;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.print.api.*;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class PrintNotificationRecipientsPortImpl implements PrintNotificationRecipientsPort {
    private final MchAccountMapper accounts;
    private final BizIdentityResolver identities;

    public PrintNotificationRecipientsPortImpl(MchAccountMapper a, BizIdentityResolver i) {
        accounts = a;
        identities = i;
    }

    public List<String> recipients(PrintScope scope, Set<String> roleKeys) {
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var active =
                            accounts.selectList(
                                    Wrappers.<MchAccount>lambdaQuery()
                                            .eq(MchAccount::getTenantNo, scope.tenantNo())
                                            .eq(MchAccount::getEntityNo, scope.entityNo())
                                            .eq(MchAccount::getStatus, MchAccount.ACTIVE)
                                            .last("LIMIT 501"));
                    if (active.size() > 500) throw new PrintFailure("print.recipientLimit");
                    var roles = new HashSet<String>();
                    for (var role : roleKeys)
                        roles.add(role.equals("STORE_MANAGER") ? "MANAGER" : role);
                    var receivers = new LinkedHashSet<String>();
                    for (var a : active) {
                        String receiver =
                                a.getUserNo() == null || a.getUserNo().isBlank()
                                        ? a.getMchAccountNo()
                                        : a.getUserNo();
                        var context = identities.resolve(receiver, scope.storeNo());
                        if (context == null
                                || !scope.entityNo().equals(context.merchantNo())
                                || !context.storeNos().contains(scope.storeNo())) continue;
                        var store = context.withStore(scope.storeNo());
                        if (store.can("biz:print:read")
                                && (store.owner()
                                        || store.staffRoles().stream().anyMatch(roles::contains)))
                            receivers.add(receiver);
                    }
                    return List.copyOf(receivers);
                });
    }
}

package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.entity.MchStoreRole;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreRoleMapper;
import ai.neargo.shop.spi.user.MerchantStaffPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link MerchantStaffPort} 实现：店主 + 持角色员工 → userNo。
 *
 * <p>调用方在 Outbox 消费线程里（没有登录上下文），查询一律绕开数据域拦截 ——
 * 这里回答的是「事实上谁在这家店任职」，不是「当前登录者能看到谁」。
 */
@Component
public class MerchantStaffPortImpl implements MerchantStaffPort {

    private final MchAccountMapper accountMapper;
    private final MchStoreRoleMapper storeRoleMapper;

    public MerchantStaffPortImpl(MchAccountMapper accountMapper, MchStoreRoleMapper storeRoleMapper) {
        this.accountMapper = accountMapper;
        this.storeRoleMapper = storeRoleMapper;
    }

    @Override
    public List<String> staffUserNos(String entityNo, Set<String> roles) {
        if (entityNo == null || entityNo.isBlank()) {
            return List.of();
        }
        return DataScopeContext.executeWithoutScope(() -> {
            List<MchAccount> accounts = accountMapper.selectList(Wrappers.<MchAccount>lambdaQuery()
                    .eq(MchAccount::getEntityNo, entityNo)
                    .eq(MchAccount::getStatus, MchAccount.ACTIVE));
            if (accounts.isEmpty()) {
                return List.of();
            }

            // LinkedHashSet：店主排前面（多数通知场景他是唯一收件人），顺带去重 ——
            // 老板同时给自己配了店长角色时不能收到两条
            Set<String> userNos = new LinkedHashSet<>();
            accounts.stream()
                    .filter(a -> Boolean.TRUE.equals(a.getIsOwner()))
                    .map(MchAccount::getUserNo)
                    .forEach(userNos::add);

            if (roles != null && !roles.isEmpty()) {
                List<String> accountNos = accounts.stream()
                        .map(MchAccount::getMchAccountNo).toList();
                Set<String> hit = storeRoleMapper.selectList(Wrappers.<MchStoreRole>lambdaQuery()
                                .in(MchStoreRole::getMchAccountNo, accountNos)
                                .in(MchStoreRole::getRole, roles)).stream()
                        .map(MchStoreRole::getMchAccountNo)
                        .collect(java.util.stream.Collectors.toSet());
                accounts.stream()
                        .filter(a -> hit.contains(a.getMchAccountNo()))
                        .map(MchAccount::getUserNo)
                        .forEach(userNos::add);
            }
            userNos.remove(null);
            return List.copyOf(userNos);
        });
    }
}

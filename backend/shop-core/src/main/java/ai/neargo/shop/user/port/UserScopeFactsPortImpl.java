package ai.neargo.shop.user.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Risk-limited users may identify themselves; spending eligibility belongs to the transaction owner. */
@Component
public class UserScopeFactsPortImpl implements UserScopeFactsPort {
    private final UserMapper users;

    public UserScopeFactsPortImpl(UserMapper users) {
        this.users = users;
    }

    @Override
    public Optional<ActiveUser> findActive(String tenantNo, String userNo) {
        var row = DataScopeContext.executeWithoutScope(() -> users.selectOne(
                Wrappers.<UsrAccount>lambdaQuery().select(UsrAccount::getUserNo, UsrAccount::getNickname)
                        .eq(UsrAccount::getTenantNo, tenantNo)
                        .eq(UsrAccount::getUserNo, userNo).in(UsrAccount::getStatus, "NORMAL", "RISK_LIMITED")));
        return Optional.ofNullable(row).map(user -> new ActiveUser(user.getUserNo(), user.getNickname()));
    }
}

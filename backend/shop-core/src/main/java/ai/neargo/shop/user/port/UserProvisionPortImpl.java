package ai.neargo.shop.user.port;

import ai.neargo.shop.spi.user.UserProvisionPort;
import ai.neargo.shop.user.service.AuthService;
import ai.neargo.shop.user.service.EmailOtpService;
import ai.neargo.shop.user.IdentityType;
import ai.neargo.shop.user.entity.UsrIdentity;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.mapper.UserMappers.IdentityMapper;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** trade → user：按手机号确保有账号（{@link UserProvisionPort}）。 */
@Component
public class UserProvisionPortImpl implements UserProvisionPort {

    private final AuthService authService;
    private final IdentityMapper identities;
    private final UserMapper users;

    public UserProvisionPortImpl(AuthService authService, IdentityMapper identities, UserMapper users) {
        this.identities=identities;
        this.users=users;
        this.authService = authService;
    }

    @Override
    public Optional<String> registeredUserByEmail(String email) {
        UsrIdentity identity = identities.selectOne(Wrappers.<UsrIdentity>lambdaQuery()
                .select(UsrIdentity::getUserNo)
                .eq(UsrIdentity::getTenantNo, "MAIN")
                .eq(UsrIdentity::getIdentityType,IdentityType.EMAIL)
                .eq(UsrIdentity::getIdentityValue,EmailOtpService.normalize(email))
                .last("LIMIT 1"));
        if (identity == null) return Optional.empty();
        return Optional.ofNullable(users.selectOne(Wrappers.<UsrAccount>lambdaQuery()
                .select(UsrAccount::getUserNo)
                .eq(UsrAccount::getTenantNo, "MAIN")
                .eq(UsrAccount::getUserNo, identity.getUserNo())
                .eq(UsrAccount::getStatus, "NORMAL"))).map(UsrAccount::getUserNo);
    }

    @Override
    public String ensureUserByPhone(String phone) {
        return authService.ensureAccountByPhone(phone);
    }
}

package ai.neargo.shop.user.port;

import ai.neargo.shop.spi.user.UserProvisionPort;
import ai.neargo.shop.user.service.AuthService;
import ai.neargo.shop.user.service.EmailOtpService;
import ai.neargo.shop.user.IdentityType;
import ai.neargo.shop.user.entity.UsrIdentity;
import ai.neargo.shop.user.mapper.UserMappers.IdentityMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** trade → user：按手机号确保有账号（{@link UserProvisionPort}）。 */
@Component
public class UserProvisionPortImpl implements UserProvisionPort {

    private final AuthService authService;
    private final IdentityMapper identities;

    public UserProvisionPortImpl(AuthService authService, IdentityMapper identities) {
        this.identities=identities;
        this.authService = authService;
    }

    @Override
    public Optional<String> registeredUserByEmail(String email) {
        return Optional.ofNullable(identities.selectOne(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getIdentityType,IdentityType.EMAIL)
                .eq(UsrIdentity::getIdentityValue,EmailOtpService.normalize(email))
                .isNotNull(UsrIdentity::getVerifiedAt).last("LIMIT 1"))).map(UsrIdentity::getUserNo);
    }

    @Override
    public String ensureUserByPhone(String phone) {
        return authService.ensureAccountByPhone(phone);
    }
}

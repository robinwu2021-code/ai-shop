package ai.neargo.shop.user.port;

import ai.neargo.shop.spi.user.UserProvisionPort;
import ai.neargo.shop.user.service.AuthService;
import org.springframework.stereotype.Component;

/** trade → user：按手机号确保有账号（{@link UserProvisionPort}）。 */
@Component
public class UserProvisionPortImpl implements UserProvisionPort {

    private final AuthService authService;

    public UserProvisionPortImpl(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public String ensureUserByPhone(String phone) {
        return authService.ensureAccountByPhone(phone);
    }
}

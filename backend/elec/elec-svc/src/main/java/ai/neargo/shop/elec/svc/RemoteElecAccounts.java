package ai.neargo.shop.elec.svc;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.elec.gateway.ElecAccounts;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 手机号问主系统。<b>调不通时向上抛</b>（全局异常处理回 10500）—— 不能当成「没绑手机号」，
 * 那会让端上弹手机号闸，用户绑完发现还是不行。
 */
@Component
public class RemoteElecAccounts implements ElecAccounts {

    private final MainSystemApi main;

    public RemoteElecAccounts(MainSystemApi main) {
        this.main = main;
    }

    @Override
    public Optional<String> phone(String userNo) {
        ElecInternal.User u = ServiceCalls.call(ServiceName.PLATFORM, () -> main.user(userNo));
        return Optional.ofNullable(u == null ? null : u.phone()).filter(p -> !p.isBlank());
    }
}

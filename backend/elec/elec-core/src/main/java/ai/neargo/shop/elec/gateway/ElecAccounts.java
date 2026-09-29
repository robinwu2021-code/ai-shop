package ai.neargo.shop.elec.gateway;

import java.util.Optional;

/**
 * 「这个人绑的手机号是多少」。账号在主系统，元器件只借这一个事实。
 *
 * <p>独立进程里的实现是 HTTP 调主系统（{@code ElecInternal.USER}）；
 * 将来元器件有了自己的账号体系，换掉实现即可，服务层一行不动。
 */
public interface ElecAccounts {

    /** @return 验证过的手机号；没绑为空（调用方据此让端上弹手机号闸，不是错误） */
    Optional<String> phone(String userNo);
}

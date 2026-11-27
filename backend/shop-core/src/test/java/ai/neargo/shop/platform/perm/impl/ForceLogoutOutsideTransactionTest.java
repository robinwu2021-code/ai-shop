package ai.neargo.shop.platform.perm.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 强制下线不包在基础能力库的事务里（TDD-基础能力库对齐 2a）。
 *
 * <p>它在基础能力库里只读角色成员；真正的写是吊销平台基础库里的运营会话。包在 common 事务里的话，
 * 两个库各自绑定之后，吊销会落在一个与之无关的事务里，回滚也收不回已经踢掉的会话。
 * 审计已改为业务提交后写（1i），不需要这个事务。
 */
class ForceLogoutOutsideTransactionTest {

    @Test
    @DisplayName("forceLogoutRole 不开事务：先读成员，再逐个吊销会话")
    void forceLogoutRoleIsNotTransactional() throws NoSuchMethodException {
        var method = PermConfigServiceImpl.class.getMethod("forceLogoutRole", String.class, String.class);

        assertThat(method.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(PermConfigServiceImpl.class.isAnnotationPresent(Transactional.class)).isFalse();
    }
}

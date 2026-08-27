package ai.neargo.shop.merchant.service;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.service.impl.MerchantStaffServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.mockito.Mockito.*;

/**
 * <b>停用员工要踢掉他的在线会话。</b>
 *
 * <p>此前没有这一步：只改 {@code status}，而他手里那个令牌在 30 天过期之前照常能用 ——
 * 按下停用的老板以为立刻生效了。运营端（{@code OpsServiceImpl}）与 C 端
 * （{@code UserServiceImpl}）早就这么做，<b>只有 B 端一直没有</b>。
 */
class StaffDisableKicksSessionTest {

    private MerchantMappers.MchAccountMapper staffMapper;
    private TokenStore consumerPool;
    private TokenStores tokenStores;
    private MerchantStaffServiceImpl service;

    @BeforeEach
    void setUp() {
        staffMapper = mock(MerchantMappers.MchAccountMapper.class);
        consumerPool = mock(TokenStore.class);
        tokenStores = mock(TokenStores.class);
        when(tokenStores.of(ArgumentMatchers.any())).thenReturn(consumerPool);

        service = new MerchantStaffServiceImpl(
                staffMapper, tokenStores,
                mock(MerchantMappers.MchStoreMapper.class),
                mock(MerchantMappers.MchStoreRoleMapper.class),
                mock(MerchantMappers.MchStaffLogMapper.class),
                mock(MerchantMappers.MchRoleMapper.class),
                mock(ai.neargo.shop.merchant.service.impl.StaffAuditLogger.class),
                mock(TokenStore.class),
                mock(ai.neargo.shop.common.OtpStore.class));
    }

    private void staffExists(String no, boolean owner) {
        MchAccount a = new MchAccount();
        a.setMchAccountNo(no);
        a.setEntityNo("M0001");
        a.setStatus(MchAccount.ACTIVE);
        a.setIsOwner(owner);
        a.setDisplayName("小张");
        // require() 走的是 selectList + 内存过滤（见 accounts()），不是 selectOne
        when(staffMapper.selectList(ArgumentMatchers.any())).thenReturn(java.util.List.of(a));
    }

    @Test
    @DisplayName("★★ 停用 → 踢会话")
    void disableKicks() {
        staffExists("SF-M0001-1", false);

        service.setStatus("M0001", "SF-M0001-1", false);

        verify(consumerPool).revokeUser("SF-M0001-1");
    }

    @Test
    @DisplayName("★ 踢的是**这个员工**的会话，不是别人的")
    void kicksOnlyThatStaff() {
        staffExists("SF-M0001-1", false);

        service.setStatus("M0001", "SF-M0001-1", false);

        verify(consumerPool, never()).revokeUser("SF-M0001-2");
        verify(consumerPool, times(1)).revokeUser(anyString());
    }

    @Test
    @DisplayName("★ 启用不踢 —— 那是让人回来，不是把人赶走")
    void enableDoesNotKick() {
        staffExists("SF-M0001-1", false);

        service.setStatus("M0001", "SF-M0001-1", true);

        verify(consumerPool, never()).revokeUser(anyString());
    }

    @Test
    @DisplayName("★ 过渡期踢的是 C 端池 —— B 端此刻仍签发 ctk_，会话装在 usr_session 里")
    void kicksTheConsumerPoolDuringTransition() {
        staffExists("SF-M0001-1", false);

        service.setStatus("M0001", "SF-M0001-1", false);

        verify(tokenStores).of(Realm.CONSUMER);
        verify(tokenStores, never()).of(Realm.MERCHANT);
    }
}

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
    private TokenStore merchantPool;
    private TokenStores tokenStores;
    private MerchantStaffServiceImpl service;

    @BeforeEach
    void setUp() {
        staffMapper = mock(MerchantMappers.MchAccountMapper.class);
        consumerPool = mock(TokenStore.class);
        merchantPool = mock(TokenStore.class);
        tokenStores = mock(TokenStores.class);
        /*
         * **两个池给两个不同的 mock。**
         *
         * 原来是 any() → 同一个 mock，于是「踢了哪个池」这件事在断言里根本分不出来 ——
         * 而 A7 之后踢错池正是那个缺陷。同一个替身盖住了要测的那条差别。
         */
        when(tokenStores.of(Realm.CONSUMER)).thenReturn(consumerPool);
        when(tokenStores.of(Realm.MERCHANT)).thenReturn(merchantPool);

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

        // A7 之后会话在 B 端池，那一侧才是真正生效的一次
        verify(merchantPool).revokeUser("SF-M0001-1");
        verify(consumerPool).revokeUser("SF-M0001-1");
    }

    @Test
    @DisplayName("★ 踢的是**这个员工**的会话，不是别人的")
    void kicksOnlyThatStaff() {
        staffExists("SF-M0001-1", false);

        service.setStatus("M0001", "SF-M0001-1", false);

        verify(merchantPool, never()).revokeUser("SF-M0001-2");
        verify(merchantPool, times(1)).revokeUser(anyString());
        verify(consumerPool, never()).revokeUser("SF-M0001-2");
    }

    @Test
    @DisplayName("★ 启用不踢 —— 那是让人回来，不是把人赶走")
    void enableDoesNotKick() {
        staffExists("SF-M0001-1", false);

        service.setStatus("M0001", "SF-M0001-1", true);

        verify(merchantPool, never()).revokeUser(anyString());
        verify(consumerPool, never()).revokeUser(anyString());
    }

    @Test
    @DisplayName("★★★ 必须踢 B 端池 —— 这条断言之前钉的是 C 端池，于是替坏掉的行为背了书")
    void kicksTheMerchantPool() {
        /*
         * **这条测试原来叫 kicksTheConsumerPoolDuringTransition。**
         *
         * 它钉死了「过渡期踢 C 端池」。A7 把店员会话挪进 B 端池时，
         * 生产代码那一行没跟着改 —— 而这条测试<b>照样是绿的</b>，
         * 因为它验的正是那个已经过时的事实。停用从此变成一次空吊销，
         * 接口返回成功、状态确实改了、日志里什么都没有，人还在线上。
         *
         * 记在这里的教训：钉「当前实现是什么」的断言，在实现该变的时候
         * 不会提醒你，只会拦住你。要钉的是**不变量** ——
         * 「被停用的人手里那个令牌必须失效」，而不是「调了哪个池」。
         * 真正守住它的是 StoreAndStaffFlowTest#disablingStaffKicksTheirLiveSession：
         * 那条拿真令牌复打一次，池错了当场就红。
         */
        staffExists("SF-M0001-1", false);

        service.setStatus("M0001", "SF-M0001-1", false);

        verify(tokenStores).of(Realm.MERCHANT);
    }
}

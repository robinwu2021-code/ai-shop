package ai.neargo.shop.user;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.dto.UserVO;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.entity.UsrIdentity;
import ai.neargo.shop.user.mapper.UserMappers.IdentityMapper;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import ai.neargo.shop.user.service.UserService;
import ai.neargo.shop.user.service.impl.AuthServiceImpl;
import ai.neargo.shop.user.service.impl.UserServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C 端个人资料与密码（C-AC-08，TDD-C端个人资料与密码 §5）。
 *
 * <p>守的是四件事：建户给的占位名不带编号（AC1）、「还没设过昵称」判得出来（AC2）、
 * 昵称的空白与超长在服务端被拦（AC3）、<b>没绑手机号不许设密码</b>（AC6）。
 *
 * <p>AC6 是这里最该小心的一条 —— 它是「拒绝型」判据，一个恒抛异常的实现
 * 也能让「被拒」那个用例变绿。所以它<b>两个方向都测</b>：有手机号必须放行、
 * 没手机号必须拒绝。只留后者的话这个测试就是个假绿。
 */
class ProfileAndPasswordTest {

    private static final String USER_NO = "U202609161449430000150";

    // ------------------------------------------------------------------ AC1

    @Test
    @DisplayName("★★★ AC1 建户给的占位名不带编号，也不是空串")
    void defaultNicknameHasNoSerialAndIsNotBlank() {
        /*
         * 不带编号：原来是「邻居」+ userNo 后四位。那串数字对用户没有意义，
         * 却长得像个真名字，于是没人意识到可以改。
         *
         * 不是空串：nickname 被 B 端订单列表、参团邻居墙、履约查询三处当买家
         * 展示名用，留空会让那三处一起变空。
         */
        assertThat(UsrAccount.DEFAULT_NICKNAME).isNotBlank();
        assertThat(UsrAccount.DEFAULT_NICKNAME).doesNotMatch(".*\\d.*");
    }

    @Test
    @DisplayName("★★★ AC1 新账号写进库的昵称就是那个占位名（不是另一处拼接）")
    void createAccountWritesTheDefaultNickname() {
        UserMapper userMapper = mock(UserMapper.class);
        IdentityMapper identityMapper = mock(IdentityMapper.class);
        var wxAuthPort = mock(ai.neargo.shop.spi.user.WxAuthPort.class);
        var tokenStore = mock(ai.neargo.shop.auth.TokenStore.class);
        var personService = mock(ai.neargo.shop.user.service.PersonService.class);
        var fissionPort = mock(ai.neargo.shop.spi.marketing.FissionPort.class);

        // 凭证查不到人 → 走建户那一支
        when(identityMapper.selectOne(any())).thenReturn(null);
        when(wxAuthPort.codeToSession(any()))
                .thenReturn(new ai.neargo.shop.spi.user.WxAuthPort.WxSession("openid-1", null));
        when(tokenStore.issue(any())).thenReturn("tk");

        AuthServiceImpl auth = new AuthServiceImpl(
                userMapper, identityMapper, tokenStore,
                mock(ai.neargo.shop.common.OtpStore.class),
                mock(ai.neargo.shop.common.ratelimit.OtpSendGuard.class),
                mock(ai.neargo.shop.spi.notify.SmsPort.class),
                wxAuthPort,
                mock(ai.neargo.shop.auth.PasswordHasher.class),
                mock(ai.neargo.shop.common.ratelimit.RateLimiter.class),
                personService, "", fissionPort,
                mock(ai.neargo.shop.user.service.OtpTestPhoneService.class));

        auth.login(new ai.neargo.shop.user.service.AuthService.LoginCommand(
                "WX_MINI", "code-1", null, null, null, Boolean.TRUE));

        ArgumentCaptor<UsrAccount> cap = ArgumentCaptor.forClass(UsrAccount.class);
        verify(userMapper).insert(cap.capture());
        assertThat(cap.getValue().getNickname()).isEqualTo(UsrAccount.DEFAULT_NICKNAME);
    }

    // ------------------------------------------------------------------ AC2

    @Test
    @DisplayName("★★★ AC2 占位名算「还没设过」，用户自己设的算「已设过」")
    void nicknameSetDistinguishesPlaceholderFromRealName() {
        assertThat(UserVO.of(account(UsrAccount.DEFAULT_NICKNAME)).nicknameSet()).isFalse();
        assertThat(UserVO.of(account("老王")).nicknameSet()).isTrue();
        // 空与空白也算没设过 —— 否则界面上那一行既没有名字、也没有入口
        assertThat(UserVO.of(account(null)).nicknameSet()).isFalse();
        assertThat(UserVO.of(account("   ")).nicknameSet()).isFalse();
    }

    // ------------------------------------------------------------------ AC3

    @Test
    @DisplayName("★★★ AC3 昵称空白被拒，而不是静默忽略")
    void blankNicknameIsRejectedNotIgnored() {
        UserMapper userMapper = mock(UserMapper.class);
        UserServiceImpl svc = userService(userMapper, mock(IdentityMapper.class), account("老王"));

        assertThatThrownBy(() -> svc.updateProfile("   ", null))
                .isInstanceOf(BizException.class);
        /*
         * 关键是这一句：原来空白是静默忽略 —— 接口 200、界面提示「已保存」，
         * 而名字一个字都没变。用户会以为自己手滑，再试一次，再成功一次。
         */
        verify(userMapper, never()).updateById(any(UsrAccount.class));
    }

    @Test
    @DisplayName("★★★ AC3 昵称超长被拒；刚好到上限放行")
    void overlongNicknameIsRejectedAtTheBoundary() {
        UserMapper userMapper = mock(UserMapper.class);
        UserServiceImpl svc = userService(userMapper, mock(IdentityMapper.class), account("老王"));

        assertThatThrownBy(() -> svc.updateProfile("名".repeat(UserService.NICKNAME_MAX_LEN + 1), null))
                .isInstanceOf(BizException.class);
        // 上界本身必须放行 —— 只测「超了被拒」的话，一个把上限写成 0 的实现也绿
        assertThatCode(() -> svc.updateProfile("名".repeat(UserService.NICKNAME_MAX_LEN), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC3 传 null 仍然是「不改这个字段」")
    void nullNicknameStillMeansNoChange() {
        UserMapper userMapper = mock(UserMapper.class);
        UsrAccount acc = account("老王");
        UserServiceImpl svc = userService(userMapper, mock(IdentityMapper.class), acc);

        svc.updateProfile(null, "https://x/a.png");

        assertThat(acc.getNickname()).isEqualTo("老王");
        assertThat(acc.getAvatar()).isEqualTo("https://x/a.png");
    }

    // ------------------------------------------------------------------ AC6

    @Test
    @DisplayName("★★★ AC6 没绑手机号时不许设密码（那条密码永远登不进来）")
    void passwordNotSettableWithoutPhone() {
        IdentityMapper identityMapper = mock(IdentityMapper.class);
        when(identityMapper.selectCount(any())).thenReturn(0L);
        UserServiceImpl svc = userService(mock(UserMapper.class), identityMapper, account("老王"));

        assertThatThrownBy(svc::assertPasswordSettable)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PHONE_REQUIRED_FOR_PASSWORD);
    }

    @Test
    @DisplayName("★★★ AC6 反方向：绑了手机号必须放行")
    void passwordSettableOncePhoneBound() {
        /*
         * **这一条是上一条的量具。** 没有它的话，一个「永远抛
         * PHONE_REQUIRED_FOR_PASSWORD」的实现也能让上面那个用例变绿 ——
         * 而那样所有人都设不了密码，功能等于没做。
         */
        IdentityMapper identityMapper = mock(IdentityMapper.class);
        when(identityMapper.selectCount(any())).thenReturn(1L);
        UserServiceImpl svc = userService(mock(UserMapper.class), identityMapper, account("老王"));

        assertThatCode(svc::assertPasswordSettable).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★★★ AC6 判据是 usr_identity 的 PHONE 凭证，不是 usr_account.phone 那个旧列")
    void phoneJudgementReadsIdentityNotLegacyColumn() {
        /*
         * usr_account.phone 是过渡期的旧列，由 syncLegacyColumn 单向同步，
         * 而登录那条路认的是 usr_identity。拿旧列当判据的话，两者一旦不一致，
         * 这道闸就会放过一个登不进来的人 —— 正是它要挡的事。
         */
        UsrAccount acc = account("老王");
        acc.setPhone("13800000000");          // 旧列有号
        IdentityMapper identityMapper = mock(IdentityMapper.class);
        when(identityMapper.selectCount(any())).thenReturn(0L);   // 凭证表没有
        UserServiceImpl svc = userService(mock(UserMapper.class), identityMapper, acc);

        assertThatThrownBy(svc::assertPasswordSettable).isInstanceOf(BizException.class);
    }

    // ------------------------------------------------------------------ 脚手架

    private static UsrAccount account(String nickname) {
        UsrAccount a = new UsrAccount();
        a.setId(1L);
        a.setUserNo(USER_NO);
        a.setNickname(nickname);
        return a;
    }

    /**
     * 当前登录人固定成 {@code acc}。
     *
     * <p>直接写 {@code SecurityContextHolder} 而不起 Spring 上下文：
     * 这些用例验的是判据，不是鉴权链。鉴权本身在 {@code MpEndpointAuthTest} 里。
     */
    private static UserServiceImpl userService(UserMapper userMapper,
                                               IdentityMapper identityMapper,
                                               UsrAccount acc) {
        when(userMapper.selectOne(any())).thenReturn(acc);
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                ai.neargo.shop.auth.LoginUser.consumer(acc.getUserNo(), acc.getNickname()),
                null, java.util.List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(auth);
        return new UserServiceImpl(
                userMapper, identityMapper,
                mock(ai.neargo.shop.spi.user.PickupQueryPort.class),
                mock(ai.neargo.shop.common.OtpStore.class),
                mock(ai.neargo.shop.spi.trade.OpenOrderPort.class),
                mock(ai.neargo.shop.auth.TokenStore.class),
                mock(ai.neargo.shop.auth.TokenStores.class),
                mock(ai.neargo.shop.user.service.PersonService.class));
    }

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        // 不清的话这个身份会漏给同一个 JVM 里后跑的测试 —— 共享种子那一类坑
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
}

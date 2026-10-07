package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.IdentityType;
import ai.neargo.shop.user.entity.UsrIdentity;
import ai.neargo.shop.user.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * C 端免登录「切到商家端」：{@link AuthService#switchToMerchant}。见 TDD-C端免登录切商家端 §5。
 *
 * <p>只 mock {@link BizIdentityResolver}（经营身份解析这一个 SPI），其余真实 ——
 * 验的是 switchToMerchant 的分支：解析到店主签 btk_、解析不到抛 NOT_A_MERCHANT。
 */
@SpringBootTest(properties = {
        // 另开一个库：@MockitoBean 会让这个类拿到新的 Spring 上下文，
        // 跑在共用的 jdbc:h2:mem:shop 上会把 schema-test.sql 再执行一遍 ——
        // 那条 `UPDATE mch_admission_policy SET legal_form='NATURAL_PERSON' WHERE legal_form='MICRO'`
        // 第二次跑就撞 uk_admission_legal_form 唯一键，**连累同库的别的测试类整个起不来**
        // （与 PlaceResolveChainTest / PayScenePassedThroughTest 同因）。
        "spring.datasource.url=jdbc:h2:mem:switch-merchant;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
@ActiveProfiles("test")
@DisplayName("C 端免登录切商家端：凭 user_no 换 btk_")
class SwitchToMerchantTest {

    @Autowired
    private AuthService authService;

    @MockitoBean
    private BizIdentityResolver bizResolver;

    @MockitoBean
    private ai.neargo.shop.spi.user.StaffSessionPort staffSessionPort;

    @Autowired
    private ai.neargo.shop.user.mapper.UserMappers.IdentityMapper identityMapper;

    @Test
    @DisplayName("★ 店主的 C 端 user_no 换得 btk_ 商家令牌")
    void ownerGetsMerchantToken() {
        when(bizResolver.resolve("U-OWNER")).thenReturn(new BizContext(
                "M1", Set.of(), Set.of(), Set.of(), null, true, Map.of(), Map.of()));

        String token = authService.switchToMerchant("U-OWNER");

        // btk_ = MERCHANT 池；若错签成 ctk_，b-app 下一个请求必 401
        assertThat(token).startsWith("btk_");
    }

    @Test
    @DisplayName("★ 非商家 → NOT_A_MERCHANT（消融：去掉判空会误签一个空商家会话）")
    void nonMerchantThrows() {
        // 有号、但号不在任何 mch_account.login_phone 里 —— 这才是「确实不是商家」。
        // 不插号的话会先撞 PHONE_REQUIRED_FOR_MERCHANT，测的就不是这条了。
        UsrIdentity phoneId = new UsrIdentity();
        phoneId.setUserNo("U-PLAIN");
        phoneId.setIdentityType(IdentityType.PHONE);
        phoneId.setIdentityValue("13900000002");
        identityMapper.insert(phoneId);

        when(bizResolver.resolve("U-PLAIN")).thenReturn(BizContext.NONE);
        when(staffSessionPort.issueStaffSession("13900000002")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> authService.switchToMerchant("U-PLAIN"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_A_MERCHANT);
    }

    /**
     * 店员那一支：按 user_no 解析不到（店员行往往没有 user_no），
     * 回落到用**本人手机号**匹配 {@code mch_account.login_phone}。
     */
    @Test
    @DisplayName("★ 店员：按 user_no 解析不到时，用本人手机号匹配到店员会话")
    void staffFallsBackToPhoneMatch() {
        /*
         * 真的往 usr_identity 插一条 PHONE 凭证 —— 不能省。
         * 店员那一支的前提就是「这个 C 端账号有已验证的手机号」，
         * 把它 mock 掉就等于没测到那个前提（没有号时代码会直接落到 NOT_A_MERCHANT）。
         */
        UsrIdentity phoneId = new UsrIdentity();
        phoneId.setUserNo("U-STAFF");
        phoneId.setIdentityType(IdentityType.PHONE);
        phoneId.setIdentityValue("13900000001");
        identityMapper.insert(phoneId);

        when(bizResolver.resolve("U-STAFF")).thenReturn(BizContext.NONE);
        // 店员的号在 mch_account.login_phone 里 —— port 命中就回一个 B 端会话。
        // 这里钉**完整号**：拿脱敏号去匹配永远查不到（见 StaffSessionPort 的安全边界）
        when(staffSessionPort.issueStaffSession("13900000001"))
                .thenReturn(java.util.Optional.of("btk_staff_session"));

        assertThat(authService.switchToMerchant("U-STAFF")).isEqualTo("btk_staff_session");
    }

    /**
     * 微信登录没授权手机号的人：判不了店员身份。
     * 这时必须报「去绑号」而不是「你还不是商家」—— 后者会把一个已经是店员的人劝去开店。
     */
    @Test
    @DisplayName("★ 没绑手机号 → PHONE_REQUIRED_FOR_MERCHANT（不是 NOT_A_MERCHANT）")
    void noPhoneAsksToBindRatherThanApply() {
        when(bizResolver.resolve("U-NOPHONE")).thenReturn(BizContext.NONE);
        // U-NOPHONE 在 usr_identity 里没有 PHONE 凭证 —— 不插就是这个前提

        assertThatThrownBy(() -> authService.switchToMerchant("U-NOPHONE"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PHONE_REQUIRED_FOR_MERCHANT);

        // 没号时根本不该去查店员表（查了也只能拿空号去匹配）
        org.mockito.Mockito.verify(staffSessionPort, org.mockito.Mockito.never())
                .issueStaffSession(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("★ 店主优先于店员：两个身份都有时走自己的店，不去问手机号")
    void ownerWinsOverStaff() {
        when(bizResolver.resolve("U-BOTH")).thenReturn(new BizContext(
                "M1", Set.of(), Set.of(), Set.of(), null, true, Map.of(), Map.of()));

        String token = authService.switchToMerchant("U-BOTH");

        assertThat(token).startsWith("btk_");
        // 判定顺序与 /biz/auth/login 一致：命中店主就不该再走店员那一支
        org.mockito.Mockito.verify(staffSessionPort, org.mockito.Mockito.never())
                .issueStaffSession(org.mockito.ArgumentMatchers.anyString());
    }
}

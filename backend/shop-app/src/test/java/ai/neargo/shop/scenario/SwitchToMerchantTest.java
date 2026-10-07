package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
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
        when(bizResolver.resolve("U-PLAIN")).thenReturn(BizContext.NONE);

        assertThatThrownBy(() -> authService.switchToMerchant("U-PLAIN"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_A_MERCHANT);
    }
}

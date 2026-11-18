package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.biz.PayBizFacade;
import ai.neargo.shop.pay.client.PayBizInternalApi;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.portal.biz.pay.BizPaymentController.MethodChoiceReq;
import ai.neargo.shop.portal.biz.pay.BizPaymentController.MethodPreferencesReq;
import ai.neargo.shop.spi.platform.AuditLogPort;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 门店付款方式偏好 {@code /biz/payment/store-method-preference} 在 ai-shop 这一层的约定（TDD-门店付款方式-App设置 §9）。
 *
 * <p>ai-shop 只做格式校验；模式取值、「OVERRIDE 至少一种」「只能选 editable」「INHERIT 须为空」由 near-pay
 * {@code StoreCollectionMethodService#saveMethods} 判定，拒绝后经 {@code RemoteBizPaymentAppService#unwrap} 还原成同一个业务码。
 */
class StoreMethodPreferenceContractTest {

    /** near-pay 的资格摘要是 SHA-256 十六进制，固定 64 位 */
    private static final String REVISION = "a".repeat(64);

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static MethodPreferencesReq req(String requestNo, String revision, String mode, List<MethodChoiceReq> methods) {
        return new MethodPreferencesReq(requestNo, 3L, revision, mode, methods);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("★★★ INHERIT + 空数组通过校验 —— App 回到「跟随商户」时就是这么发的")
    void inheritWithNoMethodsIsValid() {
        assertThat(validator.validate(req("0b6f5f1e-7a39-4d0e-9c84-7d2c1d4f0a11", REVISION, "INHERIT", List.of()))).isEmpty();
    }

    @Test
    @DisplayName("★★ OVERRIDE 带方式通过；OVERRIDE + 空数组在 ai-shop 只是格式合法，「至少一种」由 near-pay 拒绝")
    void overrideFormatIsValidatedHereAndTheMinimumIsLeftToNearPay() {
        assertThat(validator.validate(req("R1", REVISION, "OVERRIDE", List.of(new MethodChoiceReq("LINE_PAY", false))))).isEmpty();
        assertThat(validator.validate(req("R2", REVISION, "OVERRIDE", List.of()))).isEmpty();
    }

    @Test
    @DisplayName("★★★ 格式不对在进支付服务之前就被拒：幂等号过长、资格摘要不是 64 位、方式码为空、缺 test、方式列表缺失")
    void malformedRequestsAreRejected() {
        var line = List.of(new MethodChoiceReq("LINE_PAY", false));
        assertThat(validator.validate(req("R".repeat(49), REVISION, "OVERRIDE", line))).isNotEmpty();
        assertThat(validator.validate(req("R1", "rev-1", "OVERRIDE", line))).isNotEmpty();
        assertThat(validator.validate(req("R1", REVISION, "OVERRIDE", List.of(new MethodChoiceReq("", true))))).isNotEmpty();
        assertThat(validator.validate(req("R1", REVISION, "OVERRIDE", List.of(new MethodChoiceReq("LINE_PAY", null))))).isNotEmpty();
        assertThat(validator.validate(req("R1", REVISION, "INHERIT", null))).isNotEmpty();
    }

    @Test
    @DisplayName("★★★ 版本或资格摘要过期：near-pay 的 CONFLICT 还原成同一个 10409，且不留保存痕迹 —— App 靠它重新读取")
    void staleVersionOrRevisionBecomesConflict() {
        var api = mock(PayBizInternalApi.class);
        var audit = mock(AuditLogPort.class);
        var remote = new RemoteBizPaymentAppService(api, audit);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(LoginUser.merchant("MCH-A", "店主甲"), null, List.of()));
        when(api.saveMethodPreferences(eq("S-1"), any())).thenReturn(PortResult.rejected("CONFLICT", List.of()));

        assertThatThrownBy(() -> remote.saveMethodPreferences("S-1", "R1", 1L, REVISION, "INHERIT", List.of()))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(e.errorCode().code()).isEqualTo(10409);
                });
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("★★ 内嵌形态没有门店收款策略服务：读写都回 PAY_CHANNEL_UNAVAILABLE，与旧接口一致")
    void embeddedFormHasNoPreferences() {
        var embedded = new BizPaymentAppServiceImpl(mock(PayBizFacade.class), mock(AuditLogPort.class));

        assertThatThrownBy(() -> embedded.methodPreferences("S-1"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAY_CHANNEL_UNAVAILABLE));
        assertThatThrownBy(() -> embedded.saveMethodPreferences("S-1", "R1", 1L, REVISION, "INHERIT", List.of()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAY_CHANNEL_UNAVAILABLE));
    }
}

package ai.neargo.shop.portal.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.payclient.PayServiceApplyRequests;
import ai.neargo.shop.payclient.PaySourceLinkApi;
import ai.neargo.shop.payclient.PayServiceApplicationService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** TDD-运营端申请支付服务 A6。 */
class OpsPayServiceControllerTest {

    @Test
    @DisplayName("★★ 内嵌部署没有独立支付服务：明确报依赖不可用，不碰主体、不假装成功")
    void embeddedDeploymentReportsUnavailable() {
        var reader = mock(StoreSnapshotReader.class);
        var none = new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(PaySourceLinkApi.class);
        var c = new OpsPayServiceController(reader, mock(StoreSyncProperties.class), none, mock(PayServiceApplicationService.class), "");
        assertThatThrownBy(() -> c.apply("M1", new PayServiceApplyRequests.ApplyReq("x", "1", null, null, null, null)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE));
        org.mockito.Mockito.verifyNoInteractions(reader);
    }

    @Test void statusUsesCanonicalSourceAndReturnsOnlyRealReviewFactsWithEncodedOpsLink() {
        var reader = mock(StoreSnapshotReader.class);
        var api = mock(PaySourceLinkApi.class);
        var props = mock(StoreSyncProperties.class);
        when(reader.entity("entity")).thenReturn(Optional.of(new StoreSnapshotReader.Entity("M1", "shop", "TW", 1)));
        when(props.sourceScope()).thenReturn("UME");
        when(api.status("UME", "M1")).thenReturn(new PaySourceLinkApi.Status("NEED_MORE", "PM /?&", "VERIFIED", "NEED_MORE", "补登记资料"));
        var c = new OpsPayServiceController(reader, props, new StaticListableBeanFactory(Map.of("pay", api)).getBeanProvider(PaySourceLinkApi.class), mock(PayServiceApplicationService.class), "https://pay.example/ops/");
        var result = c.status("entity");
        assertThat(result.status()).isEqualTo("NEED_MORE");
        assertThat(result.reviewReason()).isEqualTo("补登记资料");
        assertThat(result.linkStatus()).isEqualTo("VERIFIED");
        assertThat(result.profileStatus()).isEqualTo("NEED_MORE");
        assertThat(result.merchantOpsUrl()).isEqualTo("https://pay.example/ops/merchants?keyword=PM+%2F%3F%26");
        assertThat(result.onboardingOpsUrl()).isEqualTo("https://pay.example/ops/merchants/onboarding-review?tab=fiat&keyword=PM+%2F%3F%26");
        verify(api).status("UME", "M1");
    }

    @Test void missingRequestNoIsRejectedAtMvcBoundary() throws Exception {
        var c = new OpsPayServiceController(mock(StoreSnapshotReader.class), mock(StoreSyncProperties.class),
                new StaticListableBeanFactory().getBeanProvider(PaySourceLinkApi.class), mock(PayServiceApplicationService.class), "");
        MockMvcBuilders.standaloneSetup(c).build().perform(post("/ops/merchant/M1/pay-service/apply")
                .contentType(MediaType.APPLICATION_JSON).content("{\"legalName\":\"Test\",\"registrationNo\":\"TESTONLY\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void statusHasSamePermissionAndMissingEntityNeverCallsPay() throws Exception {
        var reader = mock(StoreSnapshotReader.class);
        var api = mock(PaySourceLinkApi.class);
        when(reader.entity("absent")).thenReturn(Optional.empty());
        var c = new OpsPayServiceController(reader, mock(StoreSyncProperties.class), new StaticListableBeanFactory(Map.of("pay", api)).getBeanProvider(PaySourceLinkApi.class), mock(PayServiceApplicationService.class), "");
        assertThatThrownBy(() -> c.status("absent")).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        org.mockito.Mockito.verifyNoInteractions(api);
        assertThat(OpsPayServiceController.class.getMethod("status", String.class).getAnnotation(PreAuthorize.class).value())
                .isEqualTo(OpsPayServiceController.class.getMethod("apply", String.class, PayServiceApplyRequests.ApplyReq.class).getAnnotation(PreAuthorize.class).value());
    }

    @Test void configuredOpsBaseNeverAcceptsCredentialsOrQueryStrings() {
        for (String value : java.util.List.of("http://pay.example", "https://user:secret@pay.example", "https://pay.example?token=example", "https://pay.example/#fragment")) {
            assertThatThrownBy(() -> new OpsPayServiceController(mock(StoreSnapshotReader.class), mock(StoreSyncProperties.class),
                    new StaticListableBeanFactory().getBeanProvider(PaySourceLinkApi.class), mock(PayServiceApplicationService.class), value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

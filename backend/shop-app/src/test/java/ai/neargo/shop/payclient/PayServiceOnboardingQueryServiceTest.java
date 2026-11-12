package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.CallOutcome;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.shop.payclient.impl.PayServiceOnboardingQueryServiceImpl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class PayServiceOnboardingQueryServiceTest {
    private final MerchantGovernService merchants = mock(MerchantGovernService.class);
    private final PaySourceLinkApi api = mock(PaySourceLinkApi.class);
    private final StoreSyncProperties props = mock(StoreSyncProperties.class);

    @Test void onlyVisiblePageIsSentToPayAndReviewNeverBecomesCollectionReadiness() {
        var entity = mock(MerchantGovernService.MerchantProfileVO.class);
        when(entity.merchantNo()).thenReturn("M1");
        when(entity.name()).thenReturn("测试门店");
        when(merchants.list(null, null, "测试", null, false, 2, 10))
                .thenReturn(PageData.of(List.of(entity), 31, 2, 10));
        when(props.sourceScope()).thenReturn("UME");
        var channel = new PaySourceLinkApi.Onboarding("OB1", "NEWEBPAY", "CHANNEL_REVIEW", "APPROVED",
                "UNKNOWN", "AMR10014", "PENDING", "PENDING", "待核对原店", 100L);
        when(api.onboardings("UME", "M1")).thenReturn(new PaySourceLinkApi.OnboardingProgress(
                "APPROVED", "PM /?&", "VERIFIED", "APPROVED", null, List.of(channel)));
        var result = service("https://pay.example/oms/").list("测试", 2, 10);
        assertThat(result.total()).isEqualTo(31);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.records()).singleElement().satisfies(row -> {
            assertThat(row.entityNo()).isEqualTo("M1");
            assertThat(row.status()).isEqualTo("APPROVED");
            assertThat(row.onboardings()).containsExactly(channel);
            assertThat(row.onboardingOpsUrl()).isEqualTo("https://pay.example/oms/merchants/onboarding-review?tab=fiat&keyword=PM+%2F%3F%26");
        });
        verify(api).onboardings("UME", "M1");
        verifyNoMoreInteractions(api);
    }

    @Test void emptyAuthorizedPageNeverQueriesAnotherSource() {
        when(merchants.list(null, null, null, null, false, 1, 20)).thenReturn(PageData.empty(1, 20));
        assertThat(service("").list(null, 1, 20).records()).isEmpty();
        verifyNoInteractions(api);
    }

    @Test void failedRemoteReadIsNotAnUnsubmittedOrSuccessfulApplication() {
        var entity = mock(MerchantGovernService.MerchantProfileVO.class);
        when(entity.merchantNo()).thenReturn("M1");
        when(merchants.list(null, null, null, null, false, 1, 20)).thenReturn(PageData.of(List.of(entity), 1, 1, 20));
        when(props.sourceScope()).thenReturn("UME");
        var failure = new ServiceCallException(CallOutcome.TIMEOUT, "PAY", 0, "controlled read timeout");
        when(api.onboardings("UME", "M1")).thenThrow(failure);
        assertThatThrownBy(() -> service("").list(null, 1, 20)).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE));
    }

    @Test void embeddedDeploymentReportsDependencyUnavailable() {
        var svc = new PayServiceOnboardingQueryServiceImpl(merchants, props,
                new StaticListableBeanFactory().getBeanProvider(PaySourceLinkApi.class), "");
        assertThatThrownBy(() -> svc.list(null, 1, 20)).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE));
        verifyNoInteractions(merchants);
    }

    @Test void noMerchantOrNoPublicBaseNeverBuildsAnUnscopedOnboardingLink() {
        assertThat(PayOpsLinks.onboarding("https://pay.example/oms", null)).isNull();
        assertThat(PayOpsLinks.onboarding("", "PM1")).isNull();
    }

    private PayServiceOnboardingQueryService service(String base) {
        return new PayServiceOnboardingQueryServiceImpl(merchants, props,
                new StaticListableBeanFactory(Map.of("pay", api)).getBeanProvider(PaySourceLinkApi.class), base);
    }
}

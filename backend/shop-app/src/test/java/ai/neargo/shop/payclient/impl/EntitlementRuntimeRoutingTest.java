package ai.neargo.shop.payclient.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayEntitlementResponses;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementOutboxRelay;
import ai.neargo.shop.pay.entitlement.service.EntitlementCodeService;
import ai.neargo.shop.pay.entitlement.service.EntitlementGrantService;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssueBatchService;
import ai.neargo.shop.pay.entitlement.service.EntitlementQueryService;
import ai.neargo.shop.pay.entitlement.service.EntitlementSourceFence;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseService;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseWriter;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.pay.mapper.entitlement.PayEntOutboxMapper;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventBridgePort;
import ai.neargo.shop.payclient.EntitlementBatchAppService;
import ai.neargo.shop.payclient.EntitlementCodeAppService;
import ai.neargo.shop.payclient.EntitlementRuntimeRouting;
import ai.neargo.shop.payclient.port.RemoteEntitlementGrantPort;
import ai.neargo.shop.payclient.port.RemoteEntitlementQueryPort;
import ai.neargo.shop.payclient.port.RemoteEntitlementUseFactsPort;
import ai.neargo.shop.payclient.port.RemoteEntitlementUsePort;
import ai.neargo.shop.payclient.port.RemotePaymentAttemptFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.entitlement.PaymentAttemptFactsPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class EntitlementRuntimeRoutingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EntitlementRuntimeRouting.class, RemoteEntitlementGrantPort.class,
                    RemoteEntitlementUsePort.class, RemoteEntitlementQueryPort.class,
                    RemoteEntitlementUseFactsPort.class, RemotePaymentAttemptFactsPort.class,
                    EntitlementCodeAppServiceImpl.class, RemoteEntitlementCodeAppService.class,
                    EntitlementBatchAppServiceImpl.class, RemoteEntitlementBatchAppService.class)
            .withBean(EntitlementGrantService.class, () -> mock(EntitlementGrantService.class))
            .withBean(EntitlementUseService.class, () -> mock(EntitlementUseService.class))
            .withBean(EntitlementQueryService.class, () -> mock(EntitlementQueryService.class))
            .withBean(EntitlementCodeService.class, () -> mock(EntitlementCodeService.class))
            .withBean(EntitlementIssueBatchService.class, () -> mock(EntitlementIssueBatchService.class))
            .withBean(EntUseIntentMapper.class, () -> mock(EntUseIntentMapper.class))
            .withBean(PaymentMapper.class, () -> mock(PaymentMapper.class))
            .withBean(EntitlementUseWriter.class, () -> mock(EntitlementUseWriter.class))
            .withBean(EntitlementSourceFence.class, () -> mock(EntitlementSourceFence.class))
            .withBean(PayEntOutboxMapper.class, () -> mock(PayEntOutboxMapper.class))
            .withBean(PayEntitlementEventBridgePort.class, () -> mock(PayEntitlementEventBridgePort.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(PayInternalApi.class, () -> mock(PayInternalApi.class));

    @Test void embeddedAndStandaloneEachSelectExactlyOneOwnerAdapter() {
        runner.run(c -> {
            assertThat(c).hasSingleBean(EntitlementGrantPort.class).hasSingleBean(EntitlementUsePort.class)
                    .hasSingleBean(EntitlementQueryPort.class).hasSingleBean(EntitlementUseFactsPort.class)
                    .hasSingleBean(PaymentAttemptFactsPort.class).hasSingleBean(EntitlementCodeAppService.class)
                    .hasSingleBean(EntitlementBatchAppService.class);
            assertThat(c).hasSingleBean(PayEntitlementOutboxRelay.class);
            assertThat(c.getBean(EntitlementUsePort.class)).isNotInstanceOf(RemoteEntitlementUsePort.class);
        });
        runner.withPropertyValues("shop.pay.deployment=standalone").run(c -> {
            assertThat(c).hasSingleBean(EntitlementGrantPort.class).hasSingleBean(EntitlementUsePort.class)
                    .hasSingleBean(EntitlementQueryPort.class).hasSingleBean(EntitlementUseFactsPort.class)
                    .hasSingleBean(PaymentAttemptFactsPort.class).hasSingleBean(EntitlementCodeAppService.class)
                    .hasSingleBean(EntitlementBatchAppService.class);
            assertThat(c).doesNotHaveBean(PayEntitlementOutboxRelay.class);
            assertThat(c.getBean(EntitlementGrantPort.class)).isInstanceOf(RemoteEntitlementGrantPort.class);
            assertThat(c.getBean(EntitlementUsePort.class)).isInstanceOf(RemoteEntitlementUsePort.class);
            assertThat(c.getBean(EntitlementQueryPort.class)).isInstanceOf(RemoteEntitlementQueryPort.class);
            assertThat(c.getBean(EntitlementUseFactsPort.class)).isInstanceOf(RemoteEntitlementUseFactsPort.class);
            assertThat(c.getBean(PaymentAttemptFactsPort.class)).isInstanceOf(RemotePaymentAttemptFactsPort.class);
            assertThat(c.getBean(EntitlementCodeAppService.class)).isInstanceOf(RemoteEntitlementCodeAppService.class);
            assertThat(c.getBean(EntitlementBatchAppService.class)).isInstanceOf(RemoteEntitlementBatchAppService.class);
        });
    }
    @Test void missingOrWrongPaymentFactsCannotBeInterpretedAsNoPriorCharge() {
        var api = mock(PayInternalApi.class);
        var port = new RemotePaymentAttemptFactsPort(api);
        assertThatThrownBy(() -> port.hasAnyAttempt("MAIN", "O1")).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
        when(api.entPaymentAttempts(any())).thenReturn(new PayEntitlementResponses.PaymentAttemptView(
                "MAIN", "OTHER", false));
        assertThatThrownBy(() -> port.hasAnyAttempt("MAIN", "O1")).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        when(api.entPaymentAttempts(any())).thenReturn(new PayEntitlementResponses.PaymentAttemptView(
                "MAIN", "O1", false));
        assertThat(port.hasAnyAttempt("MAIN", "O1")).isFalse();
    }
}

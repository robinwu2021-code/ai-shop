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
import ai.neargo.shop.pay.entitlement.service.EntitlementExceptionService;
import ai.neargo.shop.pay.entitlement.service.EntitlementExceptionRecoveryService;
import ai.neargo.shop.payclient.EntitlementExceptionAppService;
import ai.neargo.shop.payclient.EntitlementMigrationAppService;
import ai.neargo.shop.payclient.EntitlementReconciliationAppService;
import ai.neargo.shop.payclient.EntitlementLifecycleAppService;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import ai.neargo.shop.spi.entitlement.EntitlementCapabilityPort;
import ai.neargo.shop.payclient.port.RemoteEntitlementCapabilityPort;
import ai.neargo.shop.pay.migration.service.EntitlementOwnerRegistryService;
import ai.neargo.shop.payclient.port.RemoteEntitlementOwnerRegistryPort;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort;
import ai.neargo.shop.pay.entitlement.service.EntitlementMarketingFactsService;
import ai.neargo.shop.payclient.port.RemoteEntitlementMarketingFactsPort;
import ai.neargo.shop.spi.storedvalue.StoredValueCapabilityPort;
import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.payclient.port.RemoteStoredValueCapabilityPort;
import ai.neargo.shop.pay.entitlement.service.EntitlementLifecycleService;
import ai.neargo.shop.pay.entitlement.service.EntitlementReconciliationService;
import ai.neargo.shop.pay.entitlement.service.EntitlementSaleReservationService;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort;
import ai.neargo.shop.payclient.port.RemoteEntitlementSaleReservationPort;
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
import ai.neargo.shop.pay.mapper.storedvalue.SvRechargeMapper;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRechargeService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCreditService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueQueryService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRedemptionService;
import ai.neargo.shop.payclient.StoredValueRechargeAppService;
import ai.neargo.shop.payclient.port.RemoteStoredValueRechargeFactsPort;
import ai.neargo.shop.payclient.port.RemoteStoredValueUseQuotePort;
import ai.neargo.shop.spi.storedvalue.StoredValueRechargeFactsPort;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class EntitlementRuntimeRoutingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ai.neargo.shop.payclient.port.RemoteLegacyCouponHistoricalReturnPort.class,ai.neargo.shop.payclient.port.RemoteLegacyCouponUsePort.class,EntitlementMigrationAppServiceImpl.class,RemoteEntitlementMigrationAppService.class,RemoteEntitlementCapabilityPort.class, RemoteEntitlementOwnerRegistryPort.class,RemoteEntitlementMarketingFactsPort.class,RemoteStoredValueCapabilityPort.class,EntitlementLifecycleAppServiceImpl.class,RemoteEntitlementLifecycleAppService.class,
                    EntitlementReconciliationAppServiceImpl.class,
                    RemoteEntitlementReconciliationAppService.class,EntitlementExceptionAppServiceImpl.class, RemoteEntitlementExceptionAppService.class, RemoteEntitlementSaleReservationPort.class, EntitlementRuntimeRouting.class, RemoteEntitlementGrantPort.class,
                    RemoteEntitlementUsePort.class, RemoteEntitlementQueryPort.class,
                    RemoteEntitlementUseFactsPort.class, RemotePaymentAttemptFactsPort.class, RemoteStoredValueRechargeFactsPort.class,
                    EntitlementCodeAppServiceImpl.class, RemoteEntitlementCodeAppService.class,
                    EntitlementBatchAppServiceImpl.class, RemoteEntitlementBatchAppService.class,
                    StoredValueRechargeAppServiceImpl.class, RemoteStoredValueRechargeAppService.class,
                    RemoteStoredValueUseQuotePort.class)
            .withBean(ai.neargo.shop.pay.migration.service.EntitlementMigrationService.class,
                    () -> mock(ai.neargo.shop.pay.migration.service.EntitlementMigrationService.class))
            .withBean(ai.neargo.shop.pay.migration.service.EntitlementMigrationPlanService.class,
                    () -> mock(ai.neargo.shop.pay.migration.service.EntitlementMigrationPlanService.class))
            .withBean(ai.neargo.shop.pay.migration.service.LegacyCouponMigrationImportService.class,
                    () -> mock(ai.neargo.shop.pay.migration.service.LegacyCouponMigrationImportService.class))
            .withBean(ai.neargo.shop.pay.migration.service.LegacyCouponHistoricalReturnService.class,
                    () -> mock(ai.neargo.shop.pay.migration.service.LegacyCouponHistoricalReturnService.class))
            .withBean(EntitlementLifecycleService.class, () -> mock(EntitlementLifecycleService.class))
            .withBean(EntitlementReconciliationService.class, () -> mock(EntitlementReconciliationService.class))
            .withBean(ai.neargo.shop.pay.entitlement.service.EntitlementEventDeliveryService.class,
                    () -> mock(ai.neargo.shop.pay.entitlement.service.EntitlementEventDeliveryService.class))
                .withBean(EntitlementExceptionService.class, () -> mock(EntitlementExceptionService.class))
            .withBean(EntitlementExceptionRecoveryService.class, () -> mock(EntitlementExceptionRecoveryService.class))
            .withBean(StoredValueRechargeService.class, () -> mock(StoredValueRechargeService.class))
            .withBean(StoredValueCreditService.class, () -> mock(StoredValueCreditService.class))
            .withBean(StoredValueQueryService.class, () -> mock(StoredValueQueryService.class))
            .withBean(StoredValueRedemptionService.class, () -> mock(StoredValueRedemptionService.class))
            .withBean(EntitlementSaleReservationService.class, () -> mock(EntitlementSaleReservationService.class))
            .withBean(EntitlementGrantService.class, () -> mock(EntitlementGrantService.class))
            .withBean(EntitlementUseService.class, () -> mock(EntitlementUseService.class))
            .withBean(ai.neargo.shop.pay.migration.service.LegacyCouponUseService.class,
                    () -> mock(ai.neargo.shop.pay.migration.service.LegacyCouponUseService.class))
            .withBean(EntitlementQueryService.class, () -> mock(EntitlementQueryService.class))
            .withBean(EntitlementCodeService.class, () -> mock(EntitlementCodeService.class))
            .withBean(EntitlementIssueBatchService.class, () -> mock(EntitlementIssueBatchService.class))
            .withBean(EntUseIntentMapper.class, () -> mock(EntUseIntentMapper.class))
            .withBean(SvRechargeMapper.class, () -> mock(SvRechargeMapper.class))
            .withBean(PaymentMapper.class, () -> mock(PaymentMapper.class))
            .withBean(EntitlementUseWriter.class, () -> mock(EntitlementUseWriter.class))
            .withBean(EntitlementSourceFence.class, () -> mock(EntitlementSourceFence.class))
            .withBean(PayEntOutboxMapper.class, () -> mock(PayEntOutboxMapper.class))
            .withBean(PayEntitlementEventBridgePort.class, () -> mock(PayEntitlementEventBridgePort.class))
            .withBean(EntitlementOwnerRegistryService.class, () -> mock(EntitlementOwnerRegistryService.class))
            .withBean(EntitlementMarketingFactsService.class, () -> mock(EntitlementMarketingFactsService.class))
            .withBean(PayEntitlementProperties.class, PayEntitlementProperties::new)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(PayInternalApi.class, () -> mock(PayInternalApi.class));

    @Test void embeddedAndStandaloneEachSelectExactlyOneOwnerAdapter() {
        runner.run(c -> {
            assertThat(c).hasSingleBean(EntitlementLifecycleAppService.class)
                    .hasSingleBean(EntitlementCapabilityPort.class).hasSingleBean(EntitlementOwnerRegistryPort.class).hasSingleBean(EntitlementMarketingFactsPort.class).hasSingleBean(StoredValueCapabilityPort.class)
                    .hasSingleBean(EntitlementReconciliationAppService.class)
                    .hasSingleBean(EntitlementMigrationAppService.class).hasSingleBean(EntitlementExceptionAppService.class).hasSingleBean(EntitlementSaleReservationPort.class).hasSingleBean(EntitlementGrantPort.class).hasSingleBean(EntitlementUsePort.class)
                    .hasSingleBean(EntitlementQueryPort.class).hasSingleBean(EntitlementUseFactsPort.class)
                    .hasSingleBean(PaymentAttemptFactsPort.class).hasSingleBean(StoredValueUseQuotePort.class)
                    .hasSingleBean(EntitlementCodeAppService.class)
                    .hasSingleBean(EntitlementBatchAppService.class).hasSingleBean(StoredValueRechargeFactsPort.class).hasSingleBean(StoredValueRechargeAppService.class);
            assertThat(c.getBean(EntitlementMigrationAppService.class)).isInstanceOf(EntitlementMigrationAppServiceImpl.class);
            assertThat(c).hasSingleBean(PayEntitlementOutboxRelay.class);
            assertThat(c.getBean(EntitlementUsePort.class)).isNotInstanceOf(RemoteEntitlementUsePort.class);
        });
        runner.withPropertyValues("shop.pay.deployment=standalone").run(c -> {
            assertThat(c).hasSingleBean(EntitlementLifecycleAppService.class)
                    .hasSingleBean(EntitlementCapabilityPort.class).hasSingleBean(EntitlementOwnerRegistryPort.class).hasSingleBean(EntitlementMarketingFactsPort.class).hasSingleBean(StoredValueCapabilityPort.class)
                    .hasSingleBean(EntitlementReconciliationAppService.class)
                    .hasSingleBean(EntitlementMigrationAppService.class).hasSingleBean(EntitlementExceptionAppService.class).hasSingleBean(EntitlementSaleReservationPort.class).hasSingleBean(EntitlementGrantPort.class).hasSingleBean(EntitlementUsePort.class)
                    .hasSingleBean(EntitlementQueryPort.class).hasSingleBean(EntitlementUseFactsPort.class)
                    .hasSingleBean(PaymentAttemptFactsPort.class).hasSingleBean(EntitlementCodeAppService.class)
                    .hasSingleBean(EntitlementBatchAppService.class).hasSingleBean(StoredValueRechargeFactsPort.class).hasSingleBean(StoredValueRechargeAppService.class);
            assertThat(c.getBean(EntitlementMigrationAppService.class)).isInstanceOf(RemoteEntitlementMigrationAppService.class);
            assertThat(c).doesNotHaveBean(PayEntitlementOutboxRelay.class);
            assertThat(c.getBean(EntitlementGrantPort.class)).isInstanceOf(RemoteEntitlementGrantPort.class);
            assertThat(c.getBean(EntitlementUsePort.class)).isInstanceOf(RemoteEntitlementUsePort.class);
            assertThat(c.getBean(EntitlementQueryPort.class)).isInstanceOf(RemoteEntitlementQueryPort.class);
            assertThat(c.getBean(EntitlementUseFactsPort.class)).isInstanceOf(RemoteEntitlementUseFactsPort.class);
            assertThat(c.getBean(PaymentAttemptFactsPort.class)).isInstanceOf(RemotePaymentAttemptFactsPort.class);
            assertThat(c.getBean(EntitlementCodeAppService.class)).isInstanceOf(RemoteEntitlementCodeAppService.class);
            assertThat(c.getBean(EntitlementBatchAppService.class)).isInstanceOf(RemoteEntitlementBatchAppService.class);
            assertThat(c.getBean(StoredValueRechargeAppService.class))
                    .isInstanceOf(RemoteStoredValueRechargeAppService.class);
            assertThat(c.getBean(StoredValueRechargeFactsPort.class))
                    .isInstanceOf(RemoteStoredValueRechargeFactsPort.class);
            assertThat(c.getBean(StoredValueUseQuotePort.class))
                    .isInstanceOf(RemoteStoredValueUseQuotePort.class);
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
    @Test void legacyCouponPortSelectsOneLocalOrRemoteOwner() {
        runner.run(c -> assertThat(c).hasSingleBean(ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.class));
        runner.withPropertyValues("shop.pay.deployment=standalone").run(c -> {
            assertThat(c).hasSingleBean(ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.class);
            assertThat(c.getBean(ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.class))
                    .isInstanceOf(ai.neargo.shop.payclient.port.RemoteLegacyCouponUsePort.class);
        });
    }
    @Test void missingOrForeignStoredValueCapabilityResponseNeverAttestsReadiness() {
        var api=mock(PayInternalApi.class);
        var port=new RemoteStoredValueCapabilityPort(api);
        assertThatThrownBy(() -> port.status("MAIN")).isInstanceOf(BizException.class);
        when(api.storedValueCapabilities(any())).thenReturn(new StoredValueCapabilityPort.Status("OTHER",true,true));
        assertThatThrownBy(() -> port.status("MAIN")).isInstanceOf(BizException.class);
        when(api.storedValueCapabilities(any())).thenReturn(new StoredValueCapabilityPort.Status("MAIN",true,false));
        assertThat(port.status("MAIN").redemptionReady()).isFalse();
    }
    @Test void historicalReturnSelectsExactlyOneOwnerAndMissingReplyIsUnavailable() {
        runner.run(c->assertThat(c).hasSingleBean(ai.neargo.shop.spi.entitlement.LegacyCouponHistoricalReturnPort.class));
        runner.withPropertyValues("shop.pay.deployment=standalone").run(c->{
            assertThat(c).hasSingleBean(ai.neargo.shop.spi.entitlement.LegacyCouponHistoricalReturnPort.class);
            assertThat(c.getBean(ai.neargo.shop.spi.entitlement.LegacyCouponHistoricalReturnPort.class))
                    .isInstanceOf(ai.neargo.shop.payclient.port.RemoteLegacyCouponHistoricalReturnPort.class);
        });
        var api=mock(PayInternalApi.class);
        var port=new ai.neargo.shop.payclient.port.RemoteLegacyCouponHistoricalReturnPort(api);
        assertThatThrownBy(()->port.reverse(new ai.neargo.shop.spi.entitlement.EntitlementContext("MAIN","OP1","K1","a".repeat(64)),"M1","HR1"))
                .isInstanceOfSatisfying(BizException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
    }
}

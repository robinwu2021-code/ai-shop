package ai.neargo.shop.payclient.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayEntitlementRequests.GetTemplate;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.service.EntitlementManagementService;
import ai.neargo.shop.payclient.EntitlementCatalogRouting;
import ai.neargo.shop.payclient.port.RemoteEntitlementCatalogPort;
import ai.neargo.shop.payclient.EntitlementManagementAppService;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EntitlementManagementRoutingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EntitlementCatalogRouting.class, RemoteEntitlementCatalogPort.class,
                    EntitlementManagementAppServiceImpl.class, RemoteEntitlementManagementAppService.class)
            .withBean(EntitlementCatalogService.class, () -> mock(EntitlementCatalogService.class))
            .withBean(PayEntitlementFeatureGate.class, () -> mock(PayEntitlementFeatureGate.class))
            .withBean(EntitlementManagementService.class, () -> mock(EntitlementManagementService.class))
            .withBean(PayInternalApi.class, () -> mock(PayInternalApi.class));

    @Test
    void deploymentSelectsExactlyOneManagementAndCatalogAdapter() {
        runner.run(c -> {
            assertThat(c).hasSingleBean(EntitlementManagementAppService.class);
            assertThat(c).hasSingleBean(EntitlementCatalogPort.class);
            assertThat(c.getBean(EntitlementManagementAppService.class))
                    .isInstanceOf(EntitlementManagementAppServiceImpl.class);
        });
        runner.withPropertyValues("shop.pay.deployment=standalone").run(c -> {
            assertThat(c).hasSingleBean(EntitlementManagementAppService.class);
            assertThat(c).hasSingleBean(EntitlementCatalogPort.class);
            assertThat(c.getBean(EntitlementManagementAppService.class))
                    .isInstanceOf(RemoteEntitlementManagementAppService.class);
            assertThat(c.getBean(EntitlementCatalogPort.class)).isInstanceOf(RemoteEntitlementCatalogPort.class);
        });
    }

    @Test
    void failuresNeverBecomeEmptyResultsAndHttpMeaningIsPreserved() {
        var api = mock(PayInternalApi.class);
        var service = new RemoteEntitlementManagementAppService(api);
        var scope = new CatalogScope("MAIN", "M1", "U1", false);
        int[] statuses = {400, 403, 404, 409, 503, 401, 0};
        ErrorCode[] codes = {ErrorCode.BAD_REQUEST, ErrorCode.FORBIDDEN, ErrorCode.NOT_FOUND,
                ErrorCode.CONFLICT, ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE,
                ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR};
        for (int i = 0; i < statuses.length; i++) {
            doThrow(new ServiceCallException(CallOutcome.REMOTE_ERROR, "pay", statuses[i], "failed"))
                    .when(api).entTemplate(new GetTemplate(scope, "T1"));
            ErrorCode expected = codes[i];
            assertThatThrownBy(() -> service.get(scope, "T1")).isInstanceOfSatisfying(BizException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(expected));
        }
    }
}

package ai.neargo.shop.pay.svc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayEntitlementRequests.CatalogVersion;
import ai.neargo.shop.pay.client.PayEntitlementRequests.ConfigureIssue;
import ai.neargo.shop.pay.client.PayEntitlementRequests.CreateTemplate;
import ai.neargo.shop.pay.client.PayEntitlementRequests.DraftTemplate;
import ai.neargo.shop.pay.client.PayEntitlementRequests.GetTemplate;
import ai.neargo.shop.pay.client.PayEntitlementRequests.ListTemplates;
import ai.neargo.shop.pay.client.PayEntitlementRequests.PublishTemplate;
import ai.neargo.shop.pay.client.PayEntitlementRequests.StatusTemplate;
import ai.neargo.shop.pay.client.PayEntitlementRequests.ValidateCatalogReference;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.PublishedVersionView;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.PolicyView;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.pay.entitlement.dto.ManagedTemplateView;
import ai.neargo.shop.pay.entitlement.service.EntitlementManagementService;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort.PublishedDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort.ReferenceQuery;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = PayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shop.services.internal-token=tk")
@ActiveProfiles("svctest")
class EntitlementInternalApiRoundTripTest {
    @LocalServerPort private int port;
    @MockitoBean private EntitlementManagementService management;
    @MockitoBean private EntitlementCatalogPort catalog;
    private final CatalogScope scope = new CatalogScope("MAIN", "M1", "U1", false);
    private final EntitlementContext context = new EntitlementContext("MAIN", "U1", "read1", "a".repeat(64));
    private final EntitlementDefinition definition = new EntitlementDefinition("Care coupon", "ORDER_COUPON",
            "MERCHANT", "CNY", "CASH", 500L, null, null, 0L, null, "COUNT", 1, null,
            new Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "Asia/Shanghai"),
            new RedemptionScope("ALL", List.of()), List.of("CENTER"));

    private PayInternalApi api(String token) {
        var locator = new ConfigServiceLocator();
        locator.getTargets().put(ServiceName.PAY, "http://127.0.0.1:" + port);
        return new InternalHttp(locator, token).client(ServiceName.PAY, PayInternalApi.class, Duration.ofSeconds(5));
    }

    @Test
    void typedCatalogAndManagementBodiesRoundTripWithoutEnvelope() {
        var view = new ManagedTemplateView("T1", "DRAFT", 1L, null, definition, null);
        var version = new PublishedVersionView("V1", "T1", 1, definition, "b".repeat(64), Instant.now());
        var command = new SavePolicy(0, 20L, 10000L, 2, true);
        var policy = new PolicyView("T1", 20L, 0, 0, 10000L, 0, 0, 2, true, 1);
        var published = new PublishedDefinition("T1", "V1", definition, "b".repeat(64));
        var ref = new ReferenceQuery("MAIN", "M1", "V1", "CNY", "ACTIVITY_GRANT");
        when(management.create(scope, definition, "create")).thenReturn(view);
        when(management.get(scope, "T1")).thenReturn(view);
        when(management.list(scope, "ORDER_COUPON", "DRAFT", 2, 10))
                .thenReturn(PageData.of(List.of(view), 1, 2, 10));
        when(management.draft(scope, "T1", 1, definition, "draft")).thenReturn(view);
        when(management.publish(scope, "T1", 1, "publish")).thenReturn(version);
        when(management.status(scope, "T1", 1, "PAUSED", "pause")).thenReturn(view);
        when(management.policy(scope, "T1", command, "policy")).thenReturn(policy);
        when(catalog.getPublishedVersion("MAIN", "M1", "V1")).thenReturn(published);
        when(catalog.validateReference(ref)).thenReturn(published);
        var api = api("tk");
        assertThat(api.entCreate(new CreateTemplate(scope, definition, "create"))).isEqualTo(view);
        assertThat(api.entTemplate(new GetTemplate(scope, "T1"))).isEqualTo(view);
        assertThat(api.entTemplates(new ListTemplates(scope, "ORDER_COUPON", "DRAFT", 2, 10)).records())
                .containsExactly(view);
        assertThat(api.entDraft(new DraftTemplate(scope, "T1", 1, definition, "draft"))).isEqualTo(view);
        assertThat(api.entPublish(new PublishTemplate(scope, "T1", 1, "publish"))).isEqualTo(version);
        assertThat(api.entStatus(new StatusTemplate(scope, "T1", 1, "PAUSED", "pause"))).isEqualTo(view);
        assertThat(api.entIssuePolicy(new ConfigureIssue(scope, "T1", command, "policy"))).isEqualTo(policy);
        assertThat(api.entVersion(new CatalogVersion(context, "M1", "V1"))).isEqualTo(published);
        assertThat(api.entReference(new ValidateCatalogReference(context, ref))).isEqualTo(published);
    }

    @Test
    void authenticationAndDomainFailuresKeepHttpMeaningAndDoNotReturnFakeSuccess() {
        when(management.get(scope, "T1")).thenThrow(BizException.of(ErrorCode.CONFLICT));
        assertStatus(409, () -> api("tk").entTemplate(new GetTemplate(scope, "T1")));
        assertStatus(401, () -> api("wrong").entTemplate(new GetTemplate(scope, "T1")));
        assertStatus(400, () -> api("tk").entReference(new ValidateCatalogReference(context,
                new ReferenceQuery("OTHER", "M1", "V1", "CNY", "ACTIVITY_GRANT"))));
        doThrow(BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE)).when(management).get(scope, "T1");
        assertStatus(503, () -> api("tk").entTemplate(new GetTemplate(scope, "T1")));
    }

    private void assertStatus(int expected, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ServiceCallException.class,
                e -> assertThat(e.statusCode()).isEqualTo(expected));
    }
}

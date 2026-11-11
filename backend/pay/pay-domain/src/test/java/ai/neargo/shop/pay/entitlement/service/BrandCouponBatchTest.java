package ai.neargo.shop.pay.entitlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.PublishedVersionView;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchSnapshot;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchView;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.CreateBatch;
import ai.neargo.shop.pay.entitlement.service.impl.EntitlementIssueBatchServiceImpl;
import ai.neargo.shop.spi.entitlement.BrandCouponSourcePort;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

class BrandCouponBatchTest {
    private final CatalogScope scope = new CatalogScope("MAIN", "ISSUER", "ACTOR", false);
    private final EntitlementCatalogService catalog = mock(EntitlementCatalogService.class);
    private final EntitlementCommandDeduplicator commands = mock(EntitlementCommandDeduplicator.class);
    private final EntitlementIssueBatchWriter writer = mock(EntitlementIssueBatchWriter.class);
    private final EntitlementIssueAudiencePort legacy = mock(EntitlementIssueAudiencePort.class);
    private final BrandCouponSourcePort brand = mock(BrandCouponSourcePort.class);
    private final EntitlementDefinition d = new EntitlementDefinition("coupon", "ORDER_COUPON", "MERCHANT", "CNY",
            "CASH", 100L, null, null, 0L, null, "COUNT", 1, null,
            new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "UTC"),
            new EntitlementDefinition.RedemptionScope("BRAND_STORES", List.of("SOURCE")), List.of("TARGETED"));
    private final CreateBatch request = new CreateBatch("VERSION", "FROZEN-AUDIENCE");

    @Test void usesFrozenBrandAudienceAndReplaysBeforeAnyAudienceLookup() {
        var service = service(true);
        when(commands.replay(scope, "ISSUE_BATCH", "KEY", request, BatchView.class))
                .thenReturn(Optional.empty());
        when(catalog.getVersion(scope, "VERSION"))
                .thenReturn(new PublishedVersionView("VERSION", "TEMPLATE", 1, d, "a".repeat(64), Instant.now()));
        var fact = new EntitlementIssueAudiencePort.AudienceSnapshot("MAIN", "ISSUER", "FROZEN-AUDIENCE",
                List.of(new EntitlementIssueAudiencePort.Recipient("U1", true, null),
                        new EntitlementIssueAudiencePort.Recipient("U2", false, "BLOCKED")));
        when(brand.audience("MAIN", "ISSUER", d, "VERSION", "FROZEN-AUDIENCE", "ACTOR", "KEY")).thenReturn(fact);
        var result = new BatchView("BATCH", "PROCESSING", 2, 0, 0, 0, 2, List.of());
        when(writer.create(eq(scope), eq(request), eq("KEY"), any(), any())).thenReturn(result);
        assertThat(service.create(scope, request, "KEY")).isEqualTo(result);
        var snapshot = ArgumentCaptor.forClass(BatchSnapshot.class);
        verify(writer).create(eq(scope), eq(request), eq("KEY"), any(), snapshot.capture());
        assertThat(snapshot.getValue().audience()).isEqualTo(fact);
        verifyNoInteractions(legacy);
        when(commands.replay(scope, "ISSUE_BATCH", "KEY", request, BatchView.class))
                .thenReturn(Optional.of(result));
        clearInvocations(brand, legacy, catalog, writer);
        assertThat(service.create(scope, request, "KEY")).isEqualTo(result);
        verifyNoInteractions(brand, legacy, catalog, writer);
    }

    @Test void brandSourceUnavailableCannotFallBackToEntityAudience() {
        when(commands.replay(scope, "ISSUE_BATCH", "KEY", request, BatchView.class)).thenReturn(Optional.empty());
        when(catalog.getVersion(scope, "VERSION"))
                .thenReturn(new PublishedVersionView("VERSION", "TEMPLATE", 1, d, "a".repeat(64), Instant.now()));
        assertThatThrownBy(() -> service(false).create(scope, request, "KEY"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
        verifyNoInteractions(legacy, writer);
    }

    private EntitlementIssueBatchServiceImpl service(boolean withSource) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("legacy", legacy);
        if (withSource) beans.addBean("brand", brand);
        return new EntitlementIssueBatchServiceImpl(catalog, commands, writer,
                beans.getBeanProvider(EntitlementIssueAudiencePort.class), new ObjectMapper(),
                beans.getBeanProvider(BrandCouponSourcePort.class));
    }
}

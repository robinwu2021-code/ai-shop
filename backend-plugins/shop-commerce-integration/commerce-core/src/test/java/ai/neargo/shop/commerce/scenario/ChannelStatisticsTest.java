package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.dto.CatalogContracts.CreateCatalogScopeReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.PreviewCatalogReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SourceReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SubmitSyncRunReq;
import ai.neargo.shop.commerce.dto.ChannelStatisticsContracts.Query;
import ai.neargo.shop.commerce.service.ChannelInboundService;
import ai.neargo.shop.commerce.service.ChannelStatisticsService;
import ai.neargo.shop.spi.commerce.ChannelInboundGateway;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class ChannelStatisticsTest {
    @Test
    void syncCohortsUseRunCreationTimeAndBoundProviderFiltersWithoutLoadingBusinessSales() {
        new CatalogSynchronizationTest()
                .run(
                        s -> {
                            var scope =
                                    s.scopes()
                                            .createScope(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    new CreateCatalogScopeReq(
                                                            "CON",
                                                            "STORE",
                                                            "Menu",
                                                            List.of(
                                                                    new SourceReq(
                                                                            Owner.FOOD,
                                                                            "MENU",
                                                                            "R1",
                                                                            0))),
                                                    "scope");
                            var snapshot =
                                    s.previews()
                                            .preview(
                                                    "E",
                                                    "S",
                                                    scope.scopeNo(),
                                                    "A",
                                                    new PreviewCatalogReq(scope.version()),
                                                    "preview");
                            s.runs()
                                    .submit(
                                            "E",
                                            "S",
                                            "A",
                                            new SubmitSyncRunReq(
                                                    scope.scopeNo(),
                                                    snapshot.snapshotNo(),
                                                    snapshot.hash(),
                                                    scope.version(),
                                                    "test"),
                                            "submit");
                            var service = s.context().getBean(ChannelStatisticsService.class);
                            var query =
                                    new Query(
                                            Instant.now().minusSeconds(3600),
                                            Instant.now().plusSeconds(10),
                                            "P",
                                            "DELIVERY",
                                            null,
                                            "CON");
                            var total = service.syncStatistics("E", "S", query);
                            assertThat(total.total()).isOne();
                            assertThat(total.statuses().getFirst().status()).isEqualTo("QUEUED");
                            var providers = service.providerStatistics("E", "S", query, 1, 20);
                            assertThat(providers.total()).isOne();
                            assertThat(providers.records().getFirst().pending()).isOne();
                            assertThat(service.syncStatistics("E", "OTHER", query).total())
                                    .isZero();
                            assertThat(
                                            service.syncStatistics(
                                                            "E",
                                                            "S",
                                                            new Query(
                                                                    query.from(),
                                                                    query.to(),
                                                                    "P' OR 1=1 --",
                                                                    null,
                                                                    null,
                                                                    null))
                                                    .total())
                                    .isZero();
                            s.sql()
                                    .execute(
                                            "UPDATE cim_sync_run SET created_at='2000-01-01"
                                                + " 00:00:00'");
                            assertThat(service.syncStatistics("E", "S", query).total()).isZero();
                        });
    }

    @Test
    void duplicateCallbackReceiptsStayDistinctFromEventsAndMissingOriginalOwnerIsNotHealthy() {
        var gateway = new ChannelInboundPersistenceTest.Gateway();
        new CatalogSynchronizationTest()
                .runWith(
                        r -> r.withBean(ChannelInboundGateway.class, () -> gateway),
                        s -> {
                            var inbound = s.context().getBean(ChannelInboundService.class);
                            var input = new ChannelInboundPersistenceTest().message("original");
                            inbound.receive("TEST", "CON", input);
                            inbound.receive("TEST", "CON", input);
                            var service = s.context().getBean(ChannelStatisticsService.class);
                            var imports =
                                    service.importStatistics(
                                            "E",
                                            "S",
                                            new Query(null, null, null, null, null, null));
                            assertThat(imports.orders()).isEmpty();
                            assertThat(imports.callbackEvents())
                                    .singleElement()
                                    .satisfies(row -> assertThat(row.count()).isOne());
                            assertThat(imports.callbackReceipts())
                                    .hasSize(2)
                                    .allSatisfy(row -> assertThat(row.count()).isOne());
                            var health = service.health("E", "S", null, null);
                            assertThat(health.connections())
                                    .singleElement()
                                    .satisfies(
                                            row -> {
                                                assertThat(row.missingSources())
                                                        .contains("VALIDATION:FOOD");
                                                assertThat(row.health())
                                                        .isEqualTo("SOURCE_UNAVAILABLE");
                                            });
                            assertThat(service.health("E", "OTHER", null, null).totalConnections())
                                    .isZero();
                            assertThatThrownBy(
                                            () ->
                                                    service.syncStatistics(
                                                            "E",
                                                            "S",
                                                            new Query(
                                                                    Instant.now(),
                                                                    Instant.now().minusSeconds(1),
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    null)))
                                    .isInstanceOf(RuntimeException.class);
                        });
    }
}

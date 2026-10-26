package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.Query;
import ai.neargo.shop.food.foodclient.RemoteMenuCatalogPriceService;
import ai.neargo.shop.food.policy.MenuCatalogPricePolicy;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.spi.MenuCatalogPriceInternalApi;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Price;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class RemoteMenuCatalogPriceServiceTest {
    final RemoteMenuSourceVerificationTest fixture = new RemoteMenuSourceVerificationTest();
    final Query query = new Query("E", "A", "M", "R1");
    final Clock clock =
            Clock.fixed(RemoteMenuSourceVerificationTest.NOW.toInstant(), ZoneOffset.UTC);

    MenuCatalogPriceContracts.Facts facts() {
        var q = MenuCatalogPricePolicy.sourceQuery(query);
        var source = fixture.document();
        var proof =
                fixture.proof(
                        q,
                        source,
                        MenuSourcePolicy.project(fixture.json, q, source),
                        RemoteMenuSourceVerificationTest.NOW,
                        RemoteMenuSourceVerificationTest.NOW.plusSeconds(30));
        var request = new CatalogPriceSourcePort.Request("E", "A", List.of("S1"));
        var market = new CatalogPriceSourcePort.Market("AE", "AED", 2);
        var definition = new TaxSettingDefinition(true, 500, PriceTaxMode.EXCLUSIVE);
        var tax =
                new MerchantTaxSnapshot(
                        "MAIN",
                        "E",
                        "A",
                        1,
                        MerchantTaxSnapshot.Source.MERCHANT_CUSTOM,
                        "E",
                        1,
                        500,
                        PriceTaxMode.EXCLUSIVE,
                        definition.snapshotHash("MERCHANT_CUSTOM:E", 1));
        var row =
                new Price(
                        "S1",
                        "G",
                        "KNOWN",
                        1250L,
                        "ENTITY_SKU",
                        new CatalogPriceSourcePort.Versions(0L, 0L, null, null),
                        null);
        row =
                new Price(
                        row.skuNo(),
                        row.goodsNo(),
                        row.status(),
                        row.amountMinor(),
                        row.sourceKind(),
                        row.versions(),
                        CatalogPriceSourceFacts.revision(request, market, row));
        var f =
                new CatalogPriceSourcePort.Facts(
                        1,
                        "CATALOG_BASE",
                        request,
                        market,
                        CatalogPriceSourceFacts.taxFact(tax),
                        List.of(row),
                        clock.instant(),
                        clock.instant().plusSeconds(30),
                        null);
        var base =
                new CatalogPriceSourcePort.Facts(
                        1,
                        f.purpose(),
                        request,
                        market,
                        f.tax(),
                        f.prices(),
                        f.verifiedAt(),
                        f.validUntil(),
                        CatalogPriceSourceFacts.receipt(f));
        return MenuCatalogPricePolicy.seal(fixture.json, query, proof, base, clock.instant());
    }

    @Test
    void originalPriceEvidenceSurvivesTypedRemoteCaptureAndRejectsTamperedOrMissingFacts() {
        var api = mock(MenuCatalogPriceInternalApi.class);
        var source = new RemoteMenuCatalogPriceService(api, clock);
        var valid = facts();
        when(api.capture(query)).thenReturn(valid);
        assertThat(source.capture(query).prices().getFirst().unitPriceMinor()).isEqualTo(1250L);
        var bad =
                new MenuCatalogPriceContracts.Facts(
                        1,
                        query,
                        valid.menuProof(),
                        valid.productFacts(),
                        List.of(),
                        valid.verifiedAt(),
                        valid.validUntil(),
                        valid.receiptNo());
        when(api.capture(query)).thenReturn(bad);
        assertThatThrownBy(() -> source.capture(query)).isInstanceOf(IllegalStateException.class);
        when(api.capture(query)).thenReturn(null);
        assertThatThrownBy(() -> source.capture(query)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void absentOrFailedProviderNeverFallsBackOrReturnsAnEmptySuccessfulCatalog() {
        var api = mock(MenuCatalogPriceInternalApi.class);
        var source = new RemoteMenuCatalogPriceService(api, clock);
        for (int status : List.of(400, 403, 404, 409, 501, 503)) {
            doThrow(
                            new ServiceCallException(
                                    CallOutcome.REMOTE_ERROR, ServiceName.FOOD, status, "safe"))
                    .when(api)
                    .capture(query);
            assertThatThrownBy(() -> source.capture(query)).isInstanceOf(BizException.class);
        }
    }

    @Test
    void actualInternalHttpWireCarriesOnlyOriginalScopeAndRejectsCoercedPriceOutput()
            throws Exception {
        var valid = fixture.json.writeValueAsString(facts());
        var response = new AtomicReference<>(valid);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                MenuCatalogPriceInternalApi.CAPTURE_PATH,
                ex -> {
                    calls.incrementAndGet();
                    assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER))
                            .isEqualTo("wire");
                    var body =
                            new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(body).contains("\"menuNo\":\"M\"", "\"revisionNo\":\"R1\"");
                    assertThat(body).doesNotContain("currency", "priceMinor", "discount");
                    var bytes = response.get().getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, bytes.length);
                    ex.getResponseBody().write(bytes);
                    ex.close();
                });
        server.start();
        try {
            new ApplicationContextRunner()
                    .withUserConfiguration(Wire.class)
                    .withPropertyValues(
                            "shop.services.internal-token=wire",
                            "shop.services.targets.FOOD=http://127.0.0.1:"
                                    + server.getAddress().getPort())
                    .run(
                            c -> {
                                var api =
                                        c.getBean(InternalHttp.class)
                                                .client(
                                                        ServiceName.FOOD,
                                                        MenuCatalogPriceInternalApi.class,
                                                        Duration.ofSeconds(5));
                                var source = new RemoteMenuCatalogPriceService(api, clock);
                                assertThat(
                                                source.capture(query)
                                                        .prices()
                                                        .getFirst()
                                                        .unitPriceMinor())
                                        .isEqualTo(1250L);
                                for (String bad :
                                        List.of(
                                                valid.replace(
                                                        "\"unitPriceMinor\":1250",
                                                        "\"unitPriceMinor\":\"1250\""),
                                                valid.replace(
                                                        "\"unitsPerPick\":\"1\"",
                                                        "\"unitsPerPick\":1"),
                                                valid.replace(
                                                        "\"unitPriceMinor\":1250",
                                                        "\"unitPriceMinor\":1"))) {
                                    response.set(bad);
                                    assertThatThrownBy(() -> source.capture(query))
                                            .isInstanceOf(RuntimeException.class);
                                }
                            });
            assertThat(calls.get()).isEqualTo(4);
        } finally {
            server.stop(0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConfigServiceLocator.class)
    @Import(InternalHttp.class)
    static class Wire {}
}

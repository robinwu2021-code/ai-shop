package ai.neargo.shop.food.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.food.channel.config.FoodOfferClientConfiguration;
import ai.neargo.shop.food.channel.port.CoreCatalogPriceSourcePortImpl;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourceInternalApi;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Facts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Market;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Price;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Request;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.TaxFact;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Versions;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class CoreCatalogPriceSourcePortTest {
    static final Request REQUEST = new Request("E", "S", List.of("SKU"));

    static Facts facts(Instant at) {
        var market = new Market("AE", "AED", 2);
        var definition = new TaxSettingDefinition(true, 500, PriceTaxMode.EXCLUSIVE);
        var tax =
                new TaxFact(
                        "MERCHANT_CUSTOM",
                        "E",
                        2L,
                        2L,
                        500,
                        "EXCLUSIVE",
                        definition.snapshotHash("MERCHANT_CUSTOM:E", 2));
        var row =
                new Price(
                        "SKU",
                        "G",
                        "KNOWN",
                        900L,
                        "ENTITY_SKU",
                        new Versions(3L, 8L, null, null),
                        null);
        var priced =
                new Price(
                        row.skuNo(),
                        row.goodsNo(),
                        row.status(),
                        row.amountMinor(),
                        row.sourceKind(),
                        row.versions(),
                        CatalogPriceSourceFacts.revision(REQUEST, market, row));
        var f =
                new Facts(
                        1,
                        "CATALOG_BASE",
                        REQUEST,
                        market,
                        tax,
                        List.of(priced),
                        at,
                        at.plusSeconds(30),
                        null);
        return new Facts(
                1,
                f.purpose(),
                REQUEST,
                market,
                tax,
                f.prices(),
                at,
                f.validUntil(),
                CatalogPriceSourceFacts.receipt(f));
    }

    @Test
    void actualTokenWireRejectsCoercionAndPreservesOptionalMissingOverlayVersions()
            throws Exception {
        var json = JsonMapper.builder().findAndAddModules().build();
        var valid = json.writeValueAsString(facts(Instant.now().minusSeconds(1)));
        var response = new AtomicReference<>(valid);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                CatalogPriceSourceInternalApi.CAPTURE_PATH,
                ex -> {
                    calls.incrementAndGet();
                    assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER))
                            .isEqualTo("wire");
                    String request =
                            new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(request).contains("\"entityNo\":\"E\"", "\"skuNos\":[\"SKU\"]");
                    assertThat(request).doesNotContain("currency", "priceMinor", "market");
                    byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, bytes.length);
                    ex.getResponseBody().write(bytes);
                    ex.close();
                });
        server.start();
        try {
            new ApplicationContextRunner()
                    .withUserConfiguration(FoodOfferClientConfiguration.class)
                    .withPropertyValues(
                            "shop.food.enabled=true",
                            "shop.services.internal-token=wire",
                            "shop.services.targets.PLATFORM=http://127.0.0.1:"
                                    + server.getAddress().getPort())
                    .run(
                            c -> {
                                assertThat(c).hasNotFailed();
                                var source = c.getBean(CatalogPriceSourcePort.class);
                                assertThat(source.capture(REQUEST).market().currency())
                                        .isEqualTo("AED");
                                for (String invalid :
                                        List.of(
                                                valid.replace(
                                                        "\"amountMinor\":900",
                                                        "\"amountMinor\":900.5"),
                                                valid.replace("\"rateBp\":500", "\"rateBp\":500.5"),
                                                valid.replace(
                                                        "\"currencyScale\":2",
                                                        "\"currencyScale\":\"2\""),
                                                valid.replace(
                                                        "\"storePrice\":null",
                                                        "\"storePrice\":1.5"),
                                                valid.replace(
                                                        "\"entityNo\":\"E\"",
                                                        "\"entityNo\":\"FOREIGN\""))) {
                                    response.set(invalid);
                                    assertThatThrownBy(() -> source.capture(REQUEST))
                                            .isInstanceOf(RuntimeException.class);
                                }
                            });
            assertThat(calls).hasValue(6);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oneAttemptPreservesUnknownOutcomeAndDoesNotFallbackToOldCnOffers() {
        var api = mock(CatalogPriceSourceInternalApi.class);
        when(api.capture(REQUEST))
                .thenThrow(
                        new ServiceCallException(
                                CallOutcome.TIMEOUT, ServiceName.PLATFORM, 0, "safe"));
        var source = new CoreCatalogPriceSourcePortImpl(() -> api);
        assertThatThrownBy(() -> source.capture(REQUEST)).isInstanceOf(ServiceCallException.class);
        verify(api).capture(REQUEST);
        verifyNoMoreInteractions(api);
    }

    @Test
    void missingCapabilityNotFoundAndConflictsRemainClosedWithTheirOwnStatus() {
        for (int status : List.of(404, 409, 501, 503)) {
            var api = mock(CatalogPriceSourceInternalApi.class);
            when(api.capture(REQUEST))
                    .thenThrow(
                            new ServiceCallException(
                                    CallOutcome.REMOTE_ERROR,
                                    ServiceName.PLATFORM,
                                    status,
                                    "safe"));
            var source = new CoreCatalogPriceSourcePortImpl(() -> api);
            var expected =
                    status == 404
                            ? ErrorCode.NOT_FOUND
                            : status == 409
                                    ? ErrorCode.CONFLICT
                                    : ErrorCode.PRICING_OWNER_UNSUPPORTED;
            assertThatThrownBy(() -> source.capture(REQUEST))
                    .isInstanceOfSatisfying(
                            BizException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(expected));
        }
        assertThatThrownBy(() -> new CatalogPriceSourcePort() {}.capture(REQUEST))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.PRICING_OWNER_UNSUPPORTED));
    }

    @Test
    void remoteReceiptScopeAndExpiryAreRevalidatedAfterTheTypedCall() {
        var now = Instant.parse("2026-10-26T08:00:00Z");
        var f = facts(now);
        var api = mock(CatalogPriceSourceInternalApi.class);
        var source =
                new CoreCatalogPriceSourcePortImpl(() -> api, Clock.fixed(now, ZoneOffset.UTC));
        when(api.capture(REQUEST)).thenReturn(f);
        assertThat(source.capture(REQUEST)).isEqualTo(f);
        for (var bad :
                List.of(
                        facts(now.minusSeconds(30)),
                        new Facts(
                                1,
                                f.purpose(),
                                REQUEST,
                                f.market(),
                                f.tax(),
                                List.of(),
                                now,
                                now.plusSeconds(30),
                                f.receiptNo()),
                        new Facts(
                                1,
                                f.purpose(),
                                REQUEST,
                                f.market(),
                                f.tax(),
                                f.prices(),
                                now,
                                now.plusSeconds(30),
                                "0".repeat(64)))) {
            when(api.capture(REQUEST)).thenReturn(bad);
            assertThatThrownBy(() -> source.capture(REQUEST))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}

package ai.neargo.shop.food.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.channel.config.FoodCatalogClientConfiguration;
import ai.neargo.shop.food.channel.port.CoreSpecValueCatalogQueryPortImpl;
import ai.neargo.shop.spi.product.SpecValueCatalogInternalApi;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort.SpecValueFact;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CoreSpecValueCatalogQueryPortTest {
    private static SpecValueFact fact(String value, String valueScope, String valueOwner,
            String dimScope, String dimOwner) {
        return new SpecValueFact(value, "D", "半糖", "糖度", "ENUM", "PROP", valueScope, valueOwner,
                dimScope, dimOwner, 2, 1);
    }

    @Test void actualStandaloneConfigurationReadsOriginalPlatformWithTokenAndImmutableFacts() throws Exception {
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(SpecValueCatalogInternalApi.BATCH_PATH, ex -> {
            count.incrementAndGet();
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            assertThat(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"entityNo\":\"E\"", "\"valueNos\":[\"V\"]");
            byte[] bytes = """
                    [{"valueNo":"V","dimNo":"D","label":"半糖","dimName":"糖度",
                      "valueType":"ENUM","usageType":"PROP","valueScope":"MERCHANT","valueEntityNo":"E",
                      "dimScope":"PLATFORM","dimEntityNo":"PLATFORM","valueVersion":2,"dimVersion":1}]
                    """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(FoodCatalogClientConfiguration.class)
                    .withPropertyValues("shop.food.enabled=true", "shop.services.internal-token=wire",
                            "shop.services.targets.PLATFORM=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(c -> {
                        assertThat(c).hasNotFailed().hasSingleBean(SpecValueCatalogQueryPort.class);
                        var facts = c.getBean(SpecValueCatalogQueryPort.class).getValues("E", List.of("V"));
                        assertThat(facts).containsExactly(fact("V", "MERCHANT", "E", "PLATFORM", "PLATFORM"));
                        assertThatThrownBy(facts::clear).isInstanceOf(UnsupportedOperationException.class);
                    });
            assertThat(count).hasValue(1);
        } finally { server.stop(0); }
    }

    @Test void refusesNullExtraDuplicateAndIndependentlyForeignScopeFacts() {
        var normal = fact("V", "PLATFORM", "PLATFORM", "PLATFORM", "PLATFORM");
        for (List<SpecValueFact> response : Arrays.asList(null, Arrays.asList((SpecValueFact) null),
                List.of(normal, normal), List.of(fact("EXTRA", "PLATFORM", null, "PLATFORM", null)),
                List.of(fact("V", "MERCHANT", "OTHER", "PLATFORM", null)),
                List.of(fact("V", "PLATFORM", null, "MERCHANT", "OTHER")),
                List.of(fact("V", "UNKNOWN", null, "PLATFORM", null)),
                List.of(new SpecValueFact("V", "D", "半糖", "糖度", "TEXT", "PROP",
                        "PLATFORM", null, "PLATFORM", null, 0, 0)),
                List.of(new SpecValueFact("V", "D", null, "糖度", "ENUM", "PROP",
                        "PLATFORM", null, "PLATFORM", null, 0, 0)),
                List.of(new SpecValueFact("V", "D", "半糖", "糖度", "ENUM", "PROP",
                        "PLATFORM", null, "PLATFORM", null, -1, 0)))) {
            var port = new CoreSpecValueCatalogQueryPortImpl(() -> query -> response);
            assertThatThrownBy(() -> port.getValues("E", List.of("V"))).isInstanceOf(ServiceCallException.class);
        }
        var port = new CoreSpecValueCatalogQueryPortImpl(() -> query -> List.of());
        assertThat(port.getValues("E", List.of("MISSING"))).isEmpty();
    }

    @Test void businessAndTransportFailuresArePreservedWithoutRetries() {
        for (int status : List.of(400, 403, 404, 409, 503, 0)) {
            var count = new AtomicInteger();
            var port = new CoreSpecValueCatalogQueryPortImpl(() -> query -> {
                count.incrementAndGet();
                throw new ServiceCallException(status == 0 ? CallOutcome.TIMEOUT : CallOutcome.REMOTE_ERROR,
                        ServiceName.PLATFORM, status, "safe");
            });
            if (status >= 400 && status < 500) {
                var code = switch (status) {
                    case 400 -> ErrorCode.BAD_REQUEST;
                    case 403 -> ErrorCode.FORBIDDEN;
                    case 404 -> ErrorCode.NOT_FOUND;
                    default -> ErrorCode.CONFLICT;
                };
                assertThatThrownBy(() -> port.getValues("E", List.of("V")))
                        .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
            } else {
                assertThatThrownBy(() -> port.getValues("E", List.of("V"))).isInstanceOf(ServiceCallException.class);
            }
            assertThat(count).hasValue(1);
        }
    }

    @Test void noNetworkForInvalidInputAndNoFakeFactsForMissingConfiguration() {
        var count = new AtomicInteger();
        var port = new CoreSpecValueCatalogQueryPortImpl(() -> {
            count.incrementAndGet(); return query -> List.of();
        });
        assertThatThrownBy(() -> port.getValues(" E", List.of("V"))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> port.getValues("E", List.of("V", "V"))).isInstanceOf(BizException.class);
        assertThat(count).hasValue(0);
        new ApplicationContextRunner().withUserConfiguration(FoodCatalogClientConfiguration.class)
                .withPropertyValues("shop.food.enabled=true").run(c -> {
                    assertThat(c).hasNotFailed().hasSingleBean(SpecValueCatalogQueryPort.class);
                    assertThatThrownBy(() -> c.getBean(SpecValueCatalogQueryPort.class).getValues("E", List.of("V")))
                            .isInstanceOf(ServiceCallException.class);
                });
    }

    @Test void disabledRestaurantDoesNotRegisterExternalCatalogAdapters() {
        new ApplicationContextRunner().withUserConfiguration(FoodCatalogClientConfiguration.class)
                .withPropertyValues("shop.food.enabled=false").run(c -> assertThat(c).hasNotFailed()
                        .doesNotHaveBean(SpecValueCatalogQueryPort.class)
                        .doesNotHaveBean(SpecValueCatalogInternalApi.class));
    }
}

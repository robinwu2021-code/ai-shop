package ai.neargo.shop.food.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.channel.config.FoodCatalogClientConfiguration;
import ai.neargo.shop.food.channel.port.CoreMaterialCatalogQueryPortImpl;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogInternalApi;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort.MaterialFact;
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
import tools.jackson.databind.json.JsonMapper;

class CoreMaterialCatalogQueryPortTest {
    private static MaterialFact fact(String id, String entity, String name, String unit) {
        return new MaterialFact(id, entity, name, unit, 1, "OWN", "BASE_ONLY", "INTEGER_ONLY",
                MaterialCatalogFacts.definitionHash(id, entity, name, unit, 1, "OWN"));
    }

    @Test void actualStandaloneConfigurationReadsPlatformInventoryOnceWithTokenAndImmutableFacts() throws Exception {
        var count = new AtomicInteger();
        var expected = fact("MILK", "E", "牛奶", "ML");
        byte[] body = JsonMapper.builder().build().writeValueAsBytes(List.of(expected));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(MaterialCatalogInternalApi.BATCH_PATH, ex -> {
            count.incrementAndGet();
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            assertThat(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"entityNo\":\"E\"", "\"itemIds\":[\"MILK\"]");
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(FoodCatalogClientConfiguration.class)
                    .withPropertyValues("shop.food.enabled=true", "shop.services.internal-token=wire",
                            "shop.services.targets.PLATFORM=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(c -> {
                        assertThat(c).hasNotFailed().hasSingleBean(MaterialCatalogQueryPort.class);
                        var facts = c.getBean(MaterialCatalogQueryPort.class).getMaterials("E", List.of("MILK"));
                        assertThat(facts).containsExactly(expected);
                        assertThatThrownBy(facts::clear).isInstanceOf(UnsupportedOperationException.class);
                    });
            assertThat(count).hasValue(1);
        } finally { server.stop(0); }
    }

    @Test void rejectsNullDuplicateExtraForeignMalformedDefinitionsAndForgedHashes() {
        var normal = fact("MILK", "E", "牛奶", "ML");
        for (List<MaterialFact> response : Arrays.asList(null, Arrays.asList((MaterialFact) null),
                List.of(normal, normal), List.of(fact("EXTRA", "E", "牛奶", "ML")),
                List.of(fact("MILK", "OTHER", "牛奶", "ML")),
                List.of(new MaterialFact("MILK", "E", "牛奶", "ML", 1, "OWN", "CONVERTIBLE", "INTEGER_ONLY",
                        normal.definitionHash())),
                List.of(new MaterialFact("MILK", "E", "牛奶", "ML", 1, "OWN", "BASE_ONLY", "DECIMAL",
                        normal.definitionHash())),
                List.of(new MaterialFact("MILK", "E", "牛奶", "KG", 1, "OWN", "BASE_ONLY", "INTEGER_ONLY",
                        normal.definitionHash())),
                List.of(new MaterialFact("MILK", "E", "牛奶", "ML", null, "OWN", "BASE_ONLY", "INTEGER_ONLY",
                        normal.definitionHash())),
                List.of(new MaterialFact("MILK", "E", "牛奶", "ML", 1, "OTHER", "BASE_ONLY", "INTEGER_ONLY",
                        normal.definitionHash())))) {
            var port = new CoreMaterialCatalogQueryPortImpl(() -> query -> response);
            assertThatThrownBy(() -> port.getMaterials("E", List.of("MILK")))
                    .isInstanceOf(ServiceCallException.class);
        }
        var port = new CoreMaterialCatalogQueryPortImpl(() -> query -> List.of());
        assertThat(port.getMaterials("E", List.of("MISSING"))).isEmpty();
    }

    @Test void missingRouteAndInfrastructureRemainFailuresAndNeverRetry() {
        for (int status : List.of(400, 403, 404, 500, 503, 0)) {
            var count = new AtomicInteger();
            var failure = new ServiceCallException(status == 0 ? CallOutcome.TIMEOUT : CallOutcome.REMOTE_ERROR,
                    ServiceName.PLATFORM, status, "safe");
            var port = new CoreMaterialCatalogQueryPortImpl(() -> query -> {
                count.incrementAndGet();
                throw failure;
            });
            if (status == 400 || status == 403) {
                assertThatThrownBy(() -> port.getMaterials("E", List.of("MILK")))
                        .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode())
                                .isEqualTo(status == 400 ? ErrorCode.BAD_REQUEST : ErrorCode.FORBIDDEN));
            } else {
                assertThatThrownBy(() -> port.getMaterials("E", List.of("MILK"))).isSameAs(failure);
            }
            assertThat(count).hasValue(1);
        }
    }

    @Test void fractionalStringOrMissingWeighedFlagCannotBecomeAValidFactDuringDeserialization() {
        var mapper = JsonMapper.builder().build();
        String normal = mapper.writeValueAsString(fact("MILK", "E", "牛奶", "ML"));
        for (String flag : List.of("1.5", "\"1\"", "true", "2147483648")) {
            String body = normal.replace("\"weighed\":1", "\"weighed\":" + flag);
            assertThatThrownBy(() -> mapper.readValue(body, MaterialFact.class)).isInstanceOf(RuntimeException.class);
        }
        var absent = mapper.readValue(normal.replace("\"weighed\":1,", ""), MaterialFact.class);
        assertThat(MaterialCatalogFacts.validFact(absent)).isFalse();
    }

    @Test void everyAuthorityTextFieldRequiresAStringTokenBeforeHashValidation() {
        var mapper = JsonMapper.builder().build();
        String normal = mapper.writeValueAsString(fact("123", "456", "789", "1"));
        for (String field : List.of("itemId", "entityNo", "name", "baseUom", "dataSource", "unitSupport",
                "postingSupport", "definitionHash")) {
            for (String value : List.of("123", "true")) {
                String invalid = normal.replaceFirst("\"" + field + "\":\"[^\"]*\"",
                        "\"" + field + "\":" + value);
                assertThat(invalid).isNotEqualTo(normal);
                assertThatThrownBy(() -> mapper.readValue(invalid, MaterialFact.class))
                        .as(field + "=" + value).isInstanceOf(RuntimeException.class);
            }
        }
    }

    @Test void invalidInputNeverCallsRemoteAndMissingConfigurationFailsClosed() {
        var calls = new AtomicInteger();
        var port = new CoreMaterialCatalogQueryPortImpl(() -> {
            calls.incrementAndGet();
            return query -> List.of();
        });
        assertThatThrownBy(() -> port.getMaterials(" E", List.of("MILK"))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> port.getMaterials("E", List.of("MILK", "MILK"))).isInstanceOf(BizException.class);
        assertThat(calls).hasValue(0);
        new ApplicationContextRunner().withUserConfiguration(FoodCatalogClientConfiguration.class)
                .withPropertyValues("shop.food.enabled=true").run(c -> {
                    assertThat(c).hasNotFailed().hasSingleBean(MaterialCatalogQueryPort.class);
                    assertThatThrownBy(() -> c.getBean(MaterialCatalogQueryPort.class)
                            .getMaterials("E", List.of("MILK"))).isInstanceOf(ServiceCallException.class);
                });
    }

    @Test void disabledRestaurantDoesNotRegisterMaterialRemote() {
        new ApplicationContextRunner().withUserConfiguration(FoodCatalogClientConfiguration.class)
                .withPropertyValues("shop.food.enabled=false").run(c -> assertThat(c).hasNotFailed()
                        .doesNotHaveBean(MaterialCatalogQueryPort.class)
                        .doesNotHaveBean(MaterialCatalogInternalApi.class));
    }
}

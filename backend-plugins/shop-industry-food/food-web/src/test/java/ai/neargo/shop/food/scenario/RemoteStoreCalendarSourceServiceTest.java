package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.ExceptionDay;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Facts;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Policy;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Query;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Store;
import ai.neargo.shop.food.foodclient.RemoteStoreCalendarSourceService;
import ai.neargo.shop.food.policy.StoreCalendarSourcePolicy;
import ai.neargo.shop.food.service.StoreCalendarSourceService;
import ai.neargo.shop.food.spi.StoreCalendarSourceInternalApi;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class RemoteStoreCalendarSourceServiceTest {
    static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Query QUERY =
            new Query("E", "A", LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 21));
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    Facts facts() {
        return StoreCalendarSourcePolicy.seal(
                new Facts(
                        1,
                        "STORE_CALENDAR",
                        QUERY,
                        new Store("E", "A", "ACTIVE", "22:00-03:00"),
                        List.of(
                                new Policy(
                                        "P1",
                                        "Asia/Shanghai",
                                        240,
                                        Instant.parse("2026-10-01T00:00:00Z"),
                                        1L)),
                        List.of(
                                new ExceptionDay(
                                        "C1", QUERY.fromDate(), "CLOSED", List.of(), "OLD", 1L)),
                        NOW,
                        NOW.plusSeconds(30),
                        null));
    }

    @Test
    void originalFactsMustBeFreshCompleteAndUntampered() {
        var api = mock(StoreCalendarSourceInternalApi.class);
        var remote = new RemoteStoreCalendarSourceService(api, CLOCK);
        when(api.capture(QUERY)).thenReturn(facts());
        assertThat(remote.capture(QUERY)).isEqualTo(facts());
        assertThat(
                        StoreCalendarSourcePolicy.acceptsAt(
                                facts(), Instant.parse("2026-10-20T18:00:00Z"), NOW))
                .isFalse();
        assertThatThrownBy(
                        () ->
                                StoreCalendarSourcePolicy.acceptsAt(
                                        facts(), NOW, NOW.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        var f = facts();
        for (var invalid :
                List.of(
                        new Facts(
                                1,
                                f.purpose(),
                                QUERY,
                                f.store(),
                                f.policies(),
                                List.of(),
                                NOW,
                                f.validUntil(),
                                f.receiptNo()),
                        StoreCalendarSourcePolicy.seal(
                                new Facts(
                                        1,
                                        f.purpose(),
                                        QUERY,
                                        f.store(),
                                        f.policies(),
                                        f.exceptions(),
                                        NOW,
                                        NOW.plusSeconds(31),
                                        null)),
                        StoreCalendarSourcePolicy.seal(
                                new Facts(
                                        1,
                                        f.purpose(),
                                        QUERY,
                                        f.store(),
                                        f.policies(),
                                        List.of(
                                                f.exceptions().getFirst(),
                                                f.exceptions().getFirst()),
                                        NOW,
                                        f.validUntil(),
                                        null)))) {
            when(api.capture(QUERY)).thenReturn(invalid);
            assertThatThrownBy(() -> remote.capture(QUERY))
                    .isInstanceOf(IllegalStateException.class);
        }
        when(api.capture(QUERY)).thenReturn(null);
        assertThatThrownBy(() -> remote.capture(QUERY)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failuresArePreservedWithoutFallbackOrInventedAvailability() {
        var api = mock(StoreCalendarSourceInternalApi.class);
        var remote = new RemoteStoreCalendarSourceService(api, CLOCK);
        for (var code : List.of(400, 403, 404, 409, 501, 503)) {
            when(api.capture(QUERY))
                    .thenThrow(
                            new ServiceCallException(
                                    CallOutcome.REMOTE_ERROR, ServiceName.FOOD, code, "safe"));
            assertThatThrownBy(() -> remote.capture(QUERY))
                    .isInstanceOf(code < 500 ? BizException.class : ServiceCallException.class);
            reset(api);
        }
    }

    @Test
    void actualTypedHttpUsesTokenAndRejectsCoercedCalendarFields() throws Exception {
        var valid = json.writeValueAsString(facts());
        var response = new AtomicReference<>(valid);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                StoreCalendarSourceInternalApi.CAPTURE_PATH,
                ex -> {
                    calls.incrementAndGet();
                    assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER))
                            .isEqualTo("wire");
                    assertThat(
                                    new String(
                                            ex.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8))
                            .contains("\"fromDate\":\"2026-10-20\"")
                            .doesNotContain("openHours", "cutover");
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
                                                        StoreCalendarSourceInternalApi.class,
                                                        Duration.ofSeconds(5));
                                var remote = new RemoteStoreCalendarSourceService(api, CLOCK);
                                assertThat(remote.capture(QUERY)).isEqualTo(facts());
                                for (var bad :
                                        List.of(
                                                valid.replace(
                                                        "\"cutoverMinute\":240",
                                                        "\"cutoverMinute\":\"240\""),
                                                valid.replace("\"version\":1", "\"version\":1.0"),
                                                valid.replace(
                                                        "\"fromDate\":\"2026-10-20\"",
                                                        "\"fromDate\":[2026,10,20]"),
                                                valid.replace(
                                                        "\"status\":\"ACTIVE\"", "\"status\":1"),
                                                valid.substring(0, valid.length() - 1)
                                                        + ",\"alwaysOpen\":true}",
                                                valid.replace("22:00-03:00", "00:00-00:00"))) {
                                    response.set(bad);
                                    assertThatThrownBy(() -> remote.capture(QUERY))
                                            .isInstanceOf(RuntimeException.class);
                                }
                            });
            assertThat(calls).hasValue(7);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void standaloneGatewayKeepsLocalOwnerPriorityAndDefaultDisabled() {
        var runner =
                new WebApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(
                                        JacksonAutoConfiguration.class,
                                        FoodWebAutoConfiguration.class))
                        .withBean(
                                InternalHttp.class,
                                () -> new InternalHttp(name -> Optional.empty(), "wire"))
                        .withBean(
                                StoreDirectoryPort.class,
                                () ->
                                        new FoodPolicyApiTest.PolicyHost()
                                                .stores(new FoodPolicyApiTest.Calls()));
        runner.run(c -> assertThat(c).doesNotHaveBean(StoreCalendarSourceService.class));
        var enabled =
                runner.withUserConfiguration(FoodStoreScopeTest.Host.class)
                        .withPropertyValues(
                                "shop.food.enabled=true",
                                "shop.food.deployment=standalone",
                                "spring.profiles.active=api");
        enabled.run(
                c -> {
                    assertThat(c).hasNotFailed().hasSingleBean(StoreCalendarSourceService.class);
                    assertThat(c.getBean(StoreCalendarSourceService.class))
                            .isInstanceOf(RemoteStoreCalendarSourceService.class);
                });
        var original = mock(StoreCalendarSourceService.class);
        enabled.withBean(StoreCalendarSourceService.class, () -> original)
                .run(
                        c ->
                                assertThat(c.getBean(StoreCalendarSourceService.class))
                                        .isSameAs(original));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConfigServiceLocator.class)
    @Import(InternalHttp.class)
    static class Wire {}
}

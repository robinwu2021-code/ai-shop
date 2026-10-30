package ai.neargo.shop.beauty.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.config.BeautyMerchantClientConfiguration;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.LinkState;
import ai.neargo.shop.spi.personnel.PersonnelOperatorInternalApi;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Actual InternalHttp HTTP/1.1 loopback transport; malformed success and errors never become
 * absence.
 */
class RemotePersonnelOperatorFactsPortTest {
    private static final String TOKEN = "task62-loopback-key";
    private static final Instant AT = Instant.parse("2026-10-25T00:00:00.123Z");
    private final InternalBindings bindings = new InternalBindings(TOKEN);
    private final DelegatedIdentity identity =
            new DelegatedIdentity("MAIN", "MERCHANT", "USR", "200");
    private final PersonnelOperatorFactsPort.Query query =
            new PersonnelOperatorFactsPort.Query(
                    new MerchantOperatorAccessPort.Query(
                            "MAIN", "E", "S", "200", "MERCHANT", "USR", "biz:service:task:self"));
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private RemotePersonnelOperatorFactsPort port;
    private final AtomicReference<String> wire = new AtomicReference<>();
    private UnaryOperator<String> change = value -> value;
    private int status = 200;
    private boolean unlink;
    private boolean disconnect;
    private String error;

    @BeforeEach
    void server() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                PersonnelOperatorInternalApi.PATH,
                exchange -> {
                    try {
                        assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                        assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER))
                                .isEqualTo(TOKEN);
                        assertThat(exchange.getProtocol()).isEqualTo("HTTP/1.1");
                        var request =
                                json.readValue(
                                        exchange.getRequestBody(),
                                        new TypeReference<
                                                InternalCall<
                                                        PersonnelOperatorFactsPort.Query>>() {});
                        assertThat(request.request()).isEqualTo(query);
                        assertThat(request.binding())
                                .isEqualTo(
                                        new InternalRequestBinding(
                                                1,
                                                PersonnelOperatorInternalApi.ENDPOINT,
                                                "CURRENT",
                                                identity,
                                                "E",
                                                "S",
                                                "200"));
                        if (disconnect) {
                            exchange.close();
                            return;
                        }
                        var fact =
                                new PersonnelOperatorFactsPort.OperatorPersonnelFact(
                                        "P", "A", 7, 11, 13, AT.minusSeconds(1), null);
                        var observation =
                                new PersonnelOperatorFactsPort.Observation(
                                        unlink ? LinkState.UNLINKED : LinkState.LINKED,
                                        AT,
                                        unlink ? null : fact);
                        String body =
                                error == null
                                        ? json.writeValueAsString(
                                                bindings.reply(request, observation))
                                        : "{\"code\":\""
                                                + error
                                                + "\",\"requestDigest\":\""
                                                + bindings.digest(request)
                                                + "\"}";
                        if (error != null) {
                            exchange.getResponseHeaders().set("X-Internal-Error-Code", error);
                            exchange.getResponseHeaders()
                                    .set(
                                            "X-Internal-Request-Digest",
                                            change.apply(bindings.digest(request)));
                        }
                        body = change.apply(body);
                        wire.set(body);
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        var env = new StandardEnvironment();
        env.getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "task62",
                                java.util.Map.of(
                                        "shop.services.targets.PLATFORM",
                                        "http://127.0.0.1:" + server.getAddress().getPort())));
        var http =
                new InternalHttp(
                        service ->
                                java.util.Optional.of(
                                        "http://127.0.0.1:" + server.getAddress().getPort()),
                        TOKEN);
        port =
                new RemotePersonnelOperatorFactsPort(
                        http.client(
                                "PLATFORM",
                                PersonnelOperatorInternalApi.class,
                                Duration.ofSeconds(5)),
                        bindings);
    }

    @AfterEach
    void close() {
        server.stop(0);
    }

    private PersonnelOperatorFactsPort.Observation current() {
        return DelegatedIdentityContext.call(identity, () -> port.current(query));
    }

    private void unavailable() {
        assertThatThrownBy(this::current)
                .isInstanceOfSatisfying(
                        BizException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE));
    }

    @Test
    void explicitNullValidToRoundTripsAsLinked() throws Exception {
        var r = current();
        assertThat(wire.get()).contains("\"validTo\":null");
        assertThat(r.state()).isEqualTo(LinkState.LINKED);
        assertThat(r.observedAt()).isEqualTo(AT);
        assertThat(r.personnel())
                .isEqualTo(
                        new PersonnelOperatorFactsPort.OperatorPersonnelFact(
                                "P", "A", 7, 11, 13, AT.minusSeconds(1), null));
        assertThat(wire.get()).doesNotContain("accountNo", "contact", "role");
        var sourceRoot = Path.of(System.getProperty("personnel.operator.test.source-root"));
        assertThat(
                        Path.of(
                                        port.getClass()
                                                .getProtectionDomain()
                                                .getCodeSource()
                                                .getLocation()
                                                .getPath())
                                .toRealPath())
                .isEqualTo(
                        sourceRoot
                                .resolve(
                                        "backend-plugins/shop-industry-beauty/beauty-channel/target/classes")
                                .toRealPath());
        assertThat(
                        Path.of(
                                        PersonnelOperatorFactsPort.class
                                                .getProtectionDomain()
                                                .getCodeSource()
                                                .getLocation()
                                                .getPath())
                                .toRealPath())
                .isEqualTo(sourceRoot.resolve("backend/shop-base/target/classes").toRealPath());
        System.out.println(
                "TASK62_ORIGIN beauty-channel="
                        + port.getClass().getProtectionDomain().getCodeSource().getLocation()
                        + " shop-base="
                        + PersonnelOperatorFactsPort.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation());
    }

    @Test
    void linkedAndUnlinkedRoundTripPreserveExactTypes() {
        assertThat(current().state()).isEqualTo(LinkState.LINKED);
        unlink = true;
        var r = current();
        assertThat(r.state()).isEqualTo(LinkState.UNLINKED);
        assertThat(r.personnel()).isNull();
        assertThat(r.observedAt()).isEqualTo(AT);
    }

    @Test
    void missingValidToAndInvalidIntervalObservationsAreUnavailable() {
        for (String replacement :
                new String[] {
                    "",
                    ",\"validTo\":\"2026-10-24T23:59:59.123Z\"",
                    ",\"validTo\":\"2026-10-24T23:59:58.123Z\"",
                    ",\"validTo\":\"2026-10-25T00:00:00.123Z\"",
                    ",\"validTo\":\"2026-10-24T23:59:59.999Z\""
                }) {
            change = b -> b.replace(",\"validTo\":null", replacement);
            unavailable();
        }
        for (String field : new String[] {"observedAt", "validFrom"}) {
            for (String value : new String[] {"null", "\"malformed\""}) {
                change =
                        b ->
                                b.replaceAll(
                                        "\"" + field + "\":\"[^\"]+\"",
                                        "\"" + field + "\":" + value);
                unavailable();
            }
            change = b -> b.replaceAll("\"" + field + "\":\"[^\"]+\",", "");
            unavailable();
        }
        change = b -> b.replace("2026-10-24T23:59:59.123Z", "2026-10-25T00:00:01.123Z");
        unavailable();
        change = b -> b.replace("\"personnelRevision\":7", "\"personnelRevision\":-1");
        unavailable();
    }

    @Test
    void wrongDigestOrEchoNeverReturnsLinked() {
        change =
                b ->
                        b.replaceAll(
                                "\"requestDigest\":\"[0-9a-f]+\"",
                                "\"requestDigest\":\"" + "0".repeat(64) + "\"");
        unavailable();
        change = b -> b.replace("\"targetNo\":\"200\"", "\"targetNo\":\"other\"");
        unavailable();
    }

    @Test
    void wrongStatusCodeErrorIdentityIsUnavailable() {
        for (String code :
                new String[] {
                    "UNAUTHORIZED",
                    "TOKEN_EXPIRED",
                    "BAD_REQUEST",
                    "FORBIDDEN",
                    "NOT_FOUND",
                    "CONFLICT"
                }) {
            error = code;
            status =
                    switch (code) {
                        case "UNAUTHORIZED", "TOKEN_EXPIRED" -> 401;
                        case "BAD_REQUEST" -> 400;
                        case "FORBIDDEN" -> 403;
                        case "NOT_FOUND" -> 404;
                        default -> 409;
                    };
            assertThatThrownBy(this::current)
                    .isInstanceOfSatisfying(
                            BizException.class,
                            e -> assertThat(e.errorCode().name()).isEqualTo(code));
            status = 503;
            unavailable();
        }
        status = 401;
        error = null;
        unavailable();
        error = "FORBIDDEN";
        status = 403;
        change = b -> b.replaceAll("[0-9a-f]{64}", "0".repeat(64));
        unavailable();
    }

    @Test
    void providerWiringUsesOnlyConfiguredTransportAndNeverReplacesLocalOwner() {
        var configured =
                new ApplicationContextRunner()
                        .withUserConfiguration(BeautyMerchantClientConfiguration.class)
                        .withBean(
                                InternalHttp.class,
                                () ->
                                        new InternalHttp(
                                                service ->
                                                        java.util.Optional.of(
                                                                "http://127.0.0.1:"
                                                                        + server.getAddress()
                                                                                .getPort()),
                                                TOKEN))
                        .withPropertyValues("shop.services.internal-token=" + TOKEN);
        configured.run(
                c -> {
                    assertThat(c.getBeansOfType(PersonnelOperatorFactsPort.class)).hasSize(1);
                    assertThat(c.getBean(PersonnelOperatorFactsPort.class))
                            .isExactlyInstanceOf(RemotePersonnelOperatorFactsPort.class);
                });
        var local = mock(PersonnelOperatorFactsPort.class);
        configured
                .withBean(PersonnelOperatorFactsPort.class, () -> local)
                .run(
                        c -> {
                            assertThat(c.getBeansOfType(PersonnelOperatorFactsPort.class))
                                    .hasSize(1);
                            assertThat(c.getBean(PersonnelOperatorFactsPort.class)).isSameAs(local);
                        });
        new ApplicationContextRunner()
                .withUserConfiguration(BeautyMerchantClientConfiguration.class)
                .run(c -> assertThat(c.getStartupFailure()).isNotNull());
    }

    @Test
    void transportFailureIsNotUnlinked() {
        disconnect = true;
        unavailable();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> port.current(query)).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThatThrownBy(
                        () ->
                                DelegatedIdentityContext.call(
                                        new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "200"),
                                        () -> port.current(query)))
                .isInstanceOf(BizException.class);
    }
}

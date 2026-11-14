package ai.neargo.shop.portal.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.CheckoutInternalApi;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.svc.client.ServiceCallException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real HOST ingress and Advice with owner doubles prove wire authority, not business acceptance.
 */
class CheckoutBoundOwnerTransportTest {
    private static final String TOKEN = "test-owner-secret";
    private static final CheckoutScope SCOPE = new CheckoutScope("MAIN", "E", "S", "actor");
    private static final OperationRef OPERATION = new OperationRef("BEAUTY", "CO");
    private static final Instant TIME = Instant.parse("2020-01-01T00:00:00Z");

    private static CheckoutPort.PrepareCheckoutReq prepare() {
        return new CheckoutPort.PrepareCheckoutReq(
                OPERATION,
                "original-key",
                "cr1_private",
                1,
                "a".repeat(64),
                List.of(new CheckoutPort.CheckoutLineReq("L", "SKU", 1)),
                "ONLINE",
                null,
                Map.of(),
                null);
    }

    private static CheckoutPort.CheckoutQuote quote() {
        return new CheckoutPort.CheckoutQuote(
                OPERATION,
                "Q",
                1,
                "b".repeat(64),
                TIME,
                "BUYER",
                "E",
                "S",
                "CNY",
                List.of(
                        new CheckoutPort.QuoteLine(
                                "L", "SKU", "G", 1, "COUNT", 100, 100, 0, 100, "c".repeat(64))),
                100,
                0,
                100,
                "ONLINE",
                "SERVICE",
                "MERCHANT",
                List.of(),
                List.of(),
                "CREATED",
                4);
    }

    private static <T> InternalCall<T> call(
            String endpoint, String action, String kind, T payload) {
        return new InternalCall<>(
                new InternalRequestBinding(
                        1,
                        endpoint,
                        action,
                        new DelegatedIdentity("MAIN", "MERCHANT", kind, "actor"),
                        "E",
                        "S",
                        "CO"),
                payload);
    }

    @Test
    void fourMerchantRoutesBindCurrentAuthorityAndRejectConsumerWrites() throws Exception {
        try (var host =
                        new SpringApplicationBuilder(Host.class)
                                .web(WebApplicationType.SERVLET)
                                .run(
                                        "--server.port=0",
                                        "--server.address=127.0.0.1",
                                        "--shop.services.internal-token=" + TOKEN);
                var client = HttpClient.newHttpClient()) {
            String url =
                    "http://127.0.0.1:"
                            + ((WebServerApplicationContext) host).getWebServer().getPort();
            var api =
                    new InternalHttp(service -> Optional.of(url), TOKEN)
                            .client("PLATFORM", CheckoutInternalApi.class, Duration.ofSeconds(2));
            var bindings = new InternalBindings(TOKEN);
            for (String kind : List.of("MCH", "USR")) {
                assertThatCode(
                                () -> {
                                    var prepare =
                                            call(
                                                    "CHECKOUT_PREPARE",
                                                    "PREPARE",
                                                    kind,
                                                    new CheckoutInternalApi.BoundPrepare(
                                                            SCOPE, prepare()));
                                    bindings.require(prepare, api.prepareBound(prepare));
                                    var read =
                                            call(
                                                    "CHECKOUT_READ_QUOTE",
                                                    "READ_QUOTE",
                                                    kind,
                                                    new CheckoutInternalApi.BoundOperation(
                                                            SCOPE, OPERATION));
                                    bindings.require(read, api.readQuoteBound(read));
                                    var find =
                                            call(
                                                    "CHECKOUT_FIND_OPERATION",
                                                    "FIND_OPERATION",
                                                    kind,
                                                    new CheckoutInternalApi.BoundOperation(
                                                            SCOPE, OPERATION));
                                    bindings.require(find, api.findOperationBound(find));
                                    var source =
                                            call(
                                                    "CHECKOUT_SOURCE",
                                                    "SOURCE",
                                                    kind,
                                                    new CheckoutInternalApi.BoundSource(
                                                            SCOPE,
                                                            new CheckoutPort.SourceLookup(
                                                                    OPERATION, "SO")));
                                    bindings.require(source, api.sourceBound(source));
                                })
                        .doesNotThrowAnyException();
            }
            assertThat(host.getBean(State.class).calls).hasValue(8);
            var json = JsonMapper.builder().build();
            var valid =
                    call(
                            "CHECKOUT_READ_QUOTE",
                            "READ_QUOTE",
                            "USR",
                            new CheckoutInternalApi.BoundOperation(SCOPE, OPERATION));
            String body = json.writeValueAsString(valid);
            assertThat(status(client, url, "bound/readQuote", "wrong", body)).isEqualTo(401);
            var mismatch =
                    call(
                            "CHECKOUT_READ_QUOTE",
                            "READ_QUOTE",
                            "USR",
                            new CheckoutInternalApi.BoundOperation(
                                    new CheckoutScope("MAIN", "E", "other", "actor"), OPERATION));
            assertThat(
                            status(
                                    client,
                                    url,
                                    "bound/readQuote",
                                    TOKEN,
                                    json.writeValueAsString(mismatch)))
                    .isEqualTo(400);
            var consumer =
                    new InternalCall<>(
                            new InternalRequestBinding(
                                    1,
                                    "CHECKOUT_READ_QUOTE",
                                    "READ_QUOTE",
                                    new DelegatedIdentity("MAIN", "CONSUMER", "USR", "actor"),
                                    null,
                                    null,
                                    "CO"),
                            valid.request());
            assertThat(
                            status(
                                    client,
                                    url,
                                    "bound/readQuote",
                                    TOKEN,
                                    json.writeValueAsString(consumer)))
                    .isEqualTo(400);
            for (String route :
                    List.of(
                            "bound/confirm",
                            "bound/place",
                            "bound/initiatePayment",
                            "bound/cash")) {
                assertThat(status(client, url, route, TOKEN, body)).isEqualTo(404);
            }
            assertThat(host.getBean(State.class).calls).hasValue(8);
        }
    }

    @Test
    void boundAbsenceDiffersFromRouteFailureAndUnknownPreservesNullableFacts() {
        try (var host =
                new SpringApplicationBuilder(Host.class)
                        .web(WebApplicationType.SERVLET)
                        .run(
                                "--server.port=0",
                                "--server.address=127.0.0.1",
                                "--shop.services.internal-token=" + TOKEN)) {
            String url =
                    "http://127.0.0.1:"
                            + ((WebServerApplicationContext) host).getWebServer().getPort();
            var api =
                    new InternalHttp(service -> Optional.of(url), TOKEN)
                            .client("PLATFORM", CheckoutInternalApi.class, Duration.ofSeconds(2));
            var bindings = new InternalBindings(TOKEN);
            var find =
                    call(
                            "CHECKOUT_FIND_OPERATION",
                            "FIND_OPERATION",
                            "USR",
                            new CheckoutInternalApi.BoundOperation(SCOPE, OPERATION));
            host.getBean(State.class).failure.set(ErrorCode.NOT_FOUND);
            assertThatThrownBy(() -> api.findOperationBound(find))
                    .isInstanceOfSatisfying(
                            ServiceCallException.class,
                            failure -> {
                                assertThat(failure.statusCode()).isEqualTo(404);
                                assertThat(failure.remoteErrorCode()).isEqualTo("NOT_FOUND");
                                assertThat(failure.requestDigest())
                                        .isEqualTo(bindings.digest(find));
                            });
            var missing =
                    new InternalHttp(service -> Optional.of(url + "/absent"), TOKEN)
                            .client("PLATFORM", CheckoutInternalApi.class, Duration.ofSeconds(2));
            assertThatThrownBy(() -> missing.findOperationBound(find))
                    .isInstanceOfSatisfying(
                            ServiceCallException.class,
                            failure -> assertThat(failure.remoteErrorCode()).isNull());
            host.getBean(State.class).failure.set(null);
            var source =
                    call(
                            "CHECKOUT_SOURCE",
                            "SOURCE",
                            "USR",
                            new CheckoutInternalApi.BoundSource(
                                    SCOPE, new CheckoutPort.SourceLookup(OPERATION, "SO")));
            var result = api.sourceBound(source);
            bindings.require(source, result);
            assertThat(result.result().receivedMinor()).isNull();
            assertThat(result.result().outstandingMinor()).isNull();
            assertThat(result.result().lines()).isEmpty();
            assertThat(result.result().sourceRevision()).isEqualTo(3);
            assertThat(result.result().sourceHash()).isEqualTo("d".repeat(64));
        }
    }

    @Test
    void historicalQuoteExpiryIsPreservedAcrossOriginalReadsAndRecovery() {
        try (var host =
                new SpringApplicationBuilder(Host.class)
                        .web(WebApplicationType.SERVLET)
                        .run(
                                "--server.port=0",
                                "--server.address=127.0.0.1",
                                "--shop.services.internal-token=" + TOKEN)) {
            String url =
                    "http://127.0.0.1:"
                            + ((WebServerApplicationContext) host).getWebServer().getPort();
            var api =
                    new InternalHttp(service -> Optional.of(url), TOKEN)
                            .client("PLATFORM", CheckoutInternalApi.class, Duration.ofSeconds(2));
            var read =
                    call(
                            "CHECKOUT_READ_QUOTE",
                            "READ_QUOTE",
                            "USR",
                            new CheckoutInternalApi.BoundOperation(SCOPE, OPERATION));
            assertThatCode(
                            () ->
                                    assertThat(api.readQuoteBound(read).result().expiresAt())
                                            .isEqualTo(TIME))
                    .doesNotThrowAnyException();
            var legacy =
                    new CheckoutInternalApi.Scoped<>(
                            1, read.binding().identity(), SCOPE, OPERATION);
            assertThat(api.readQuote(legacy).result().expiresAt()).isEqualTo(TIME);
        }
    }

    @Test
    void blankDefaultTokenConstructsButRejectsBeforeOwnerEffects() {
        var state = new State();
        var controller = new InternalCheckoutController(owner(state), "");
        assertThat(
                        controller
                                .readQuoteBound(
                                        call(
                                                "CHECKOUT_READ_QUOTE",
                                                "READ_QUOTE",
                                                "USR",
                                                new CheckoutInternalApi.BoundOperation(
                                                        SCOPE, OPERATION)),
                                        "")
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        assertThat(state.calls).hasValue(0);
    }

    private static int status(
            HttpClient client, String url, String route, String token, String body)
            throws Exception {
        return client.send(
                        HttpRequest.newBuilder(URI.create(url + CheckoutInternalApi.BASE + route))
                                .header("Content-Type", "application/json")
                                .header(InternalHttp.TOKEN_HEADER, token)
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    static class State {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<ErrorCode> failure = new AtomicReference<>();
    }

    private static CheckoutPort owner(State state) {
        return mock(
                CheckoutPort.class,
                invocation -> {
                    state.calls.incrementAndGet();
                    assertThat(SecurityUtils.requireUser().userNo()).isEqualTo("actor");
                    assertThat(SecurityUtils.requireUser().realm().name()).isEqualTo("MERCHANT");
                    if (state.failure.get() != null) {
                        throw BizException.of(state.failure.get());
                    }
                    return switch (invocation.getMethod().getName()) {
                        case "prepare", "readQuote" -> quote();
                        case "findOperation" ->
                                new CheckoutPort.CheckoutResult(
                                        OPERATION,
                                        "CREATED",
                                        4,
                                        "ORDER",
                                        "SO",
                                        "d".repeat(64),
                                        null,
                                        TIME);
                        case "source" ->
                                new CheckoutPort.CheckoutSourceFacts(
                                        OPERATION,
                                        "ORDER",
                                        "SO",
                                        "BUYER",
                                        "E",
                                        "S",
                                        3,
                                        "d".repeat(64),
                                        "WAIT_FULFILL",
                                        "CNY",
                                        100,
                                        null,
                                        null,
                                        "UNKNOWN",
                                        List.of(),
                                        List.of(),
                                        "BLOCKED",
                                        TIME);
                        default -> throw new AssertionError("consumer method was reached");
                    };
                });
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        TomcatServletWebServerAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        JacksonAutoConfiguration.class
    })
    @Import({
        InternalCheckoutController.class,
        GlobalExceptionHandler.class,
        ApiResponseWrapper.class
    })
    static class Host {
        @Bean
        State state() {
            return new State();
        }

        @Bean
        CheckoutPort checkoutPort(State state) {
            return owner(state);
        }
    }
}

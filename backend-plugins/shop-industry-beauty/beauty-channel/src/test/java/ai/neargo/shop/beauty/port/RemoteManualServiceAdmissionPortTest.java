package ai.neargo.shop.beauty.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi.Scoped;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ConfirmManualReceipt;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualAdmissionView;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualMethod;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualSourceFacts;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualSourceLine;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/** Actual bounded loopback HTTP validates bound originals and rejects malformed/unbound success. */
class RemoteManualServiceAdmissionPortTest {
    final InternalBindings bindings = new InternalBindings("task64-loopback-key");
    final JsonMapper json = JsonMapper.builder().build();
    final CheckoutScope scope = new CheckoutScope("MAIN", "E", "S", "A");
    final OperationRef operation = new OperationRef("BEAUTY", "OP");
    final ManualLookup lookup = new ManualLookup(operation, null, "original-key");
    final DelegatedIdentity identity = new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "A");
    final AtomicInteger reads = new AtomicInteger();
    final AtomicInteger responses = new AtomicInteger();
    UnaryOperator<String> change = value -> value;
    HttpServer server;
    RemoteManualServiceAdmissionPort port;
    boolean disconnect;

    @BeforeEach
    void server() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                ManualServiceAdmissionInternalApi.BASE + "query",
                exchange -> {
                    try {
                        var request =
                                json.readValue(
                                        exchange.getRequestBody(),
                                        new TypeReference<InternalCall<Scoped<ManualLookup>>>() {});
                        assertThat(request.request().scope()).isEqualTo(scope);
                        assertThat(request.request().request().operation()).isEqualTo(operation);
                        if (request.request().request().requestKey() != null) {
                            assertThat(request.request().request()).isEqualTo(lookup);
                        } else {
                            assertThat(request.request().request().declarationNo())
                                    .isEqualTo("DECL");
                        }
                        assertThat(request.binding().action()).isEqualTo("QUERY");
                        assertThat(request.binding().targetNo()).isEqualTo("OP");
                        reads.incrementAndGet();
                        if (disconnect) {
                            exchange.close();
                            return;
                        }
                        Instant at = Instant.parse("2026-10-25T00:00:00.123Z");
                        var view =
                                new ManualAdmissionView(
                                        operation,
                                        "O",
                                        "SO",
                                        2,
                                        1,
                                        "ADMITTED",
                                        "MANUAL_DECLARATION",
                                        "DECL",
                                        ManualMethod.CASH,
                                        101L,
                                        "CNY",
                                        at,
                                        at,
                                        "ADMITTED",
                                        null);
                        byte[] body =
                                change.apply(json.writeValueAsString(bindings.reply(request, view)))
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        responses.incrementAndGet();
                        exchange.getResponseBody().write(body);
                    } finally {
                        exchange.close();
                    }
                });
        server.createContext(
                ManualServiceAdmissionInternalApi.BASE + "source",
                exchange -> {
                    try {
                        var request =
                                json.readValue(
                                        exchange.getRequestBody(),
                                        new TypeReference<InternalCall<Scoped<SourceLookup>>>() {});
                        Instant at = Instant.parse("2026-10-25T00:00:00.123Z");
                        var source =
                                new ManualSourceFacts(
                                        operation,
                                        "O",
                                        "SO",
                                        new CustomerParty("USER", "BUYER", null, 1),
                                        "E",
                                        "S",
                                        "MANUAL_OFFLINE",
                                        1,
                                        "a".repeat(64),
                                        2,
                                        1,
                                        "ADMITTED",
                                        List.of(
                                                new ManualSourceLine(
                                                        "LINE", "CLIENT", "GOODS", "SKU", 1, 1,
                                                        "COUNT")),
                                        at);
                        byte[] body =
                                change.apply(
                                                json.writeValueAsString(
                                                        bindings.reply(request, source)))
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        responses.incrementAndGet();
                        exchange.getResponseBody().write(body);
                    } finally {
                        exchange.close();
                    }
                });
        server.createContext(
                ManualServiceAdmissionInternalApi.BASE + "confirm",
                exchange -> {
                    try {
                        var request =
                                json.readValue(
                                        exchange.getRequestBody(),
                                        new TypeReference<
                                                InternalCall<Scoped<ConfirmManualReceipt>>>() {});
                        var receipt = request.request().request();
                        var view =
                                new ManualAdmissionView(
                                        operation,
                                        "O",
                                        "SO",
                                        2,
                                        1,
                                        "ADMITTED",
                                        "MANUAL_DECLARATION",
                                        "DECL",
                                        receipt.method(),
                                        receipt.declaredMinor(),
                                        receipt.currency(),
                                        receipt.receivedAt(),
                                        receipt.receivedAt(),
                                        "ADMITTED",
                                        null);
                        if (awaitingConfirm) {
                            view =
                                    new ManualAdmissionView(
                                            operation,
                                            "O",
                                            "SO",
                                            2,
                                            0,
                                            "AWAITING_RECEIPT",
                                            null,
                                            null,
                                            null,
                                            null,
                                            "CNY",
                                            null,
                                            null,
                                            "AWAITING_RECEIPT",
                                            null);
                        }
                        byte[] body =
                                change.apply(json.writeValueAsString(bindings.reply(request, view)))
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        responses.incrementAndGet();
                        exchange.getResponseBody().write(body);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        var http =
                new InternalHttp(
                        service -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()),
                        "task64-loopback-key");
        port =
                new RemoteManualServiceAdmissionPort(
                        http.client(
                                "PLATFORM",
                                ManualServiceAdmissionInternalApi.class,
                                Duration.ofSeconds(2)),
                        bindings);
    }

    @AfterEach
    void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    private boolean awaitingConfirm;

    @Test
    void boundManualFactsRoundTrip() {
        var result = query();
        assertThat(result.manualDeclarationNo()).isEqualTo("DECL");
        assertThat(result.receiptProvenance()).isEqualTo("MANUAL_DECLARATION");
        assertThat(result.declaredMinor()).isEqualTo(101);
    }

    @Test
    void wrongDigestOrMalformedResultIsUnavailable() {
        change = value -> value.replace("\"requestDigest\":\"", "\"requestDigest\":\"wrong");
        assertThatThrownBy(this::query)
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE));
        change = value -> value.replace("\"admissionState\":\"ADMITTED\",", "");
        assertThatThrownBy(this::query)
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE));
        // The request digest remains valid: response domain facts must still be checked.
        change =
                value ->
                        value.replace(
                                "\"admissionState\":\"ADMITTED\"",
                                "\"admissionState\":\"INVENTED\"");
        assertThatThrownBy(this::query)
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE));
        change =
                value ->
                        value.replace(
                                "\"receiptProvenance\":\"MANUAL_DECLARATION\"",
                                "\"receiptProvenance\":null");
        assertThatThrownBy(this::query)
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE));
        change = value -> value.replace("\"declaredMinor\":101", "\"declaredMinor\":0");
        assertThatThrownBy(this::query)
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE));
        change = value -> resultOnly(value, "\"operationNo\":\"OP\"", "\"operationNo\":\"WRONG\"");
        assertThatThrownBy(this::query)
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE));
        change =
                value ->
                        resultOnly(
                                value,
                                "\"manualDeclarationNo\":\"DECL\"",
                                "\"manualDeclarationNo\":\"OTHER\"");
        assertThatThrownBy(
                        () ->
                                DelegatedIdentityContext.call(
                                        identity,
                                        () ->
                                                port.query(
                                                        scope,
                                                        new ManualLookup(operation, "DECL", null))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE));
        for (String field : List.of("entityNo", "storeNo", "subOrderNo")) {
            String old = field.equals("entityNo") ? "E" : field.equals("storeNo") ? "S" : "SO";
            change =
                    value ->
                            resultOnly(
                                    value,
                                    "\"" + field + "\":\"" + old + "\"",
                                    "\"" + field + "\":\"FOREIGN\"");
            assertThatThrownBy(
                            () ->
                                    DelegatedIdentityContext.call(
                                            identity,
                                            () ->
                                                    port.source(
                                                            scope,
                                                            new SourceLookup(operation, "SO"))))
                    .isInstanceOfSatisfying(
                            BizException.class,
                            failure ->
                                    assertThat(failure.errorCode())
                                            .isEqualTo(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE));
        }
        var receipt =
                new ConfirmManualReceipt(
                        operation,
                        "KEY",
                        1,
                        ManualMethod.CASH,
                        101,
                        "CNY",
                        Instant.parse("2026-10-25T00:00:00.123Z"),
                        null);
        change = value -> resultOnly(value, "\"declaredMinor\":101", "\"declaredMinor\":102");
        assertThatThrownBy(
                        () ->
                                DelegatedIdentityContext.call(
                                        identity, () -> port.confirm(scope, receipt)))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE));
        change = value -> value;
        awaitingConfirm = true;
        assertThatThrownBy(
                        () ->
                                DelegatedIdentityContext.call(
                                        identity, () -> port.confirm(scope, receipt)))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE));
        assertThat(responses).as("all malformed cases returned real HTTP 200 bodies").hasValue(12);
    }

    @Test
    void lostReplyUsesOriginalQuery() {
        disconnect = true;
        assertThatThrownBy(this::query)
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE));
        disconnect = false;
        assertThat(query().manualDeclarationNo()).isEqualTo("DECL");
        assertThat(reads.get()).isGreaterThanOrEqualTo(2);
    }

    static String resultOnly(String value, String from, String to) {
        int start = value.indexOf("\"result\":");
        assertThat(start).isGreaterThanOrEqualTo(0);
        return value.substring(0, start) + value.substring(start).replace(from, to);
    }

    ManualAdmissionView query() {
        return DelegatedIdentityContext.call(identity, () -> port.query(scope, lookup));
    }
}

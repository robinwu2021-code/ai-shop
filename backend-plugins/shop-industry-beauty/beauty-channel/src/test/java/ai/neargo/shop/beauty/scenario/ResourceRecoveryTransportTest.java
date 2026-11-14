package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.beauty.port.RemoteMerchantOperatorAccessPort;
import ai.neargo.shop.beauty.port.RemoteResourceCommandFactsPort;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.resource.ResourceCommandFactsPort;
import ai.neargo.shop.spi.resource.ResourceRecoveryInternalApi;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.svc.InternalHttp;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** Real TCP and typed wire contracts; fixture owner bodies are explicitly not deployment verification. */
class ResourceRecoveryTransportTest {
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"code\":10500,\"msg\":\"owner failed\",\"data\":null}"})
    void http200WithoutExplicitCommandFieldCannotBecomeAbsence(String body) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ResourceRecoveryInternalApi.COMMAND_PATH, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var http = new InternalHttp(service -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()),
                    "facts-key");
            var adapter = new RemoteResourceCommandFactsPort(
                    http.client("PLATFORM", ResourceRecoveryInternalApi.class, Duration.ofSeconds(2)));
            var binding = new ResourceSourceAuthorityPort.Binding("MAIN", "E", "S", "C", "BEAUTY_APPOINTMENT",
                    "A", 0, "RESERVE", "a".repeat(64), "b".repeat(64));
            var request = new ResourceSourceAuthorityPort.Request(binding, "ACTOR", "MERCHANT", "MCH");
            assertThatThrownBy(() -> adapter.lookup(request)).isInstanceOf(RuntimeException.class);
        } finally { server.stop(0); }
    }

    @Test
    void operatorAndCommandReadsUsePlatformCredentialsAndNeverTurnFailureIntoAbsence() throws Exception {
        var status = new AtomicInteger(200);
        var count = new AtomicInteger();
        var commandBody = new AtomicReference<>("{\"command\":null}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/merchant/recovery/", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("facts-key");
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("entityNo", "E", "storeNo", "S", "operatorNo", "ACTOR", "MERCHANT", "MCH");
            count.incrementAndGet();
            String body = exchange.getRequestURI().getPath().endsWith("operator-access")
                    ? "true" : commandBody.get();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var http = new InternalHttp(service -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()),
                    "facts-key");
            var client = http.client("PLATFORM", ResourceRecoveryInternalApi.class, Duration.ofSeconds(2));
            var access = new RemoteMerchantOperatorAccessPort(client);
            var commands = new RemoteResourceCommandFactsPort(client);
            var query = new MerchantOperatorAccessPort.Query("MAIN", "E", "S", "ACTOR", "MERCHANT", "MCH",
                    "biz:order:view");
            var binding = new ResourceSourceAuthorityPort.Binding("MAIN", "E", "S", "C", "BEAUTY_APPOINTMENT",
                    "A", 0, "RESERVE", "a".repeat(64), "b".repeat(64));
            var request = new ResourceSourceAuthorityPort.Request(binding, "ACTOR", "MERCHANT", "MCH");
            assertThat(access.allowed(query)).isTrue();
            assertThat(commands.lookup(request).command()).isNull();
            String payload = " {\"message\":\"成功😀\"} ";
            var state = ResourceCommandFactsPort.Status.SUCCEEDED;
            String hash = ResourceCommandFactsPort.fingerprint(binding, state, 1, payload, null);
            var terminal = new ResourceCommandFactsPort.Fact(binding, state, 1, payload, null, hash);
            commandBody.set(new ObjectMapper().writeValueAsString(new ResourceCommandFactsPort.Lookup(terminal)));
            assertThat(commands.lookup(request).command()).isEqualTo(terminal);
            var wrongBinding = new ResourceSourceAuthorityPort.Binding("MAIN", "E", "S", "C", "BEAUTY_APPOINTMENT",
                    "OTHER", 0, "RESERVE", "a".repeat(64), "b".repeat(64));
            var wrong = new ResourceSourceAuthorityPort.Request(wrongBinding, "ACTOR", "MERCHANT", "MCH");
            assertThatThrownBy(() -> commands.lookup(wrong)).isInstanceOf(IllegalStateException.class);
            for (int error : new int[] {403, 409, 503}) {
                status.set(error);
                assertThatThrownBy(() -> access.allowed(query))
                        .isInstanceOf(error == 503 ? RuntimeException.class : BizException.class);
                assertThatThrownBy(() -> commands.lookup(request))
                        .isInstanceOf(error == 503 ? RuntimeException.class : BizException.class);
            }
            assertThat(count.get()).isEqualTo(10);
        } finally { server.stop(0); }
    }
}

package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;
import ai.neargo.shop.beauty.dto.StorePolicyQuery;
import ai.neargo.shop.beauty.service.StorePolicyService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceLocator;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** The real shared HTTP transport carries typed commands, identity coordinates and durable keys. */
class BeautyRemoteServiceTest {
    private HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();

    private StorePolicyService client() throws Exception {
        Class<?> api = assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty.spi.BeautyPolicyInternalApi"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/beauty/policies", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("test-secret");
            var body = json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(body.get("entityNo").asText()).isEqualTo("E");
            assertThat(body.get("storeNo").asText()).isEqualTo("S");
            assertThat(body.get("actorNo").asText()).isEqualTo("ACTOR");
            String response;
            if (exchange.getRequestURI().getPath().endsWith("/publish")) {
                assertThat(body.get("idempotencyKey").asText()).isEqualTo("ORIGINAL-K");
                response = "{\"policyNo\":\"P\",\"policyVersionNo\":\"PV\",\"revision\":1,"
                        + "\"validFrom\":\"2026-10-20T10:00:00Z\",\"snapshotHash\":\"H\"}";
            } else {
                response = "{\"policyNo\":\"P\",\"storeNo\":\"S\",\"revision\":2,"
                        + "\"policyVersionNo\":\"PV\",\"effectiveRevision\":1,"
                        + "\"validFrom\":\"2026-10-20T10:00:00Z\",\"policy\":{\"bookingAheadDays\":7,"
                        + "\"cancelRule\":{\"freeCancelBeforeMinutes\":60,\"allowCustomerCancel\":true},"
                        + "\"allowedModes\":[\"PREPAID_SINGLE\"]}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ServiceLocator locator = service -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort());
        var http = new InternalHttp(locator, "test-secret");
        Object proxy = http.client("BEAUTY", api, Duration.ofSeconds(2));
        Class<?> remote = assertDoesNotThrow(() ->
                Class.forName("ai.neargo.shop.beauty.client.RemoteStorePolicyService"));
        return (StorePolicyService) remote.getConstructor(api).newInstance(proxy);
    }

    @AfterEach
    void close() { if (server != null) { server.stop(0); } }

    @Test
    void transportsQueryAndOriginalPublishIdentityWithoutChangingPublicContract() throws Exception {
        var client = client();
        assertThat(client.getPolicy("E", "S", "ACTOR", new StorePolicyQuery("S", null)).effectiveRevision()).isOne();
        var policy = new PublishStorePolicyVersionReq.Policy(7,
                new PublishStorePolicyVersionReq.CancellationRule(60, true), Set.of("PREPAID_SINGLE"));
        assertThat(client.publishPolicyVersion("E", "S", "ACTOR", new PublishStorePolicyVersionReq(0L,
                OffsetDateTime.parse("2026-10-20T10:00:00Z"), policy), "ORIGINAL-K").policyVersionNo()).isEqualTo("PV");
        assertThat(calls).hasValue(2);
    }

    @Test
    void preservesBusinessFailuresWithoutRetryingAWrite() throws Exception {
        var client = client();
        for (int code : new int[]{400, 403, 404, 409}) {
            status.set(code);
            ErrorCode expected = switch (code) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                default -> ErrorCode.CONFLICT;
            };
            assertThatThrownBy(() -> client.getPolicy("E", "S", "ACTOR", new StorePolicyQuery("S", null)))
                    .isInstanceOfSatisfying(BizException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(expected));
        }
        assertThat(calls).hasValue(4);
    }
}

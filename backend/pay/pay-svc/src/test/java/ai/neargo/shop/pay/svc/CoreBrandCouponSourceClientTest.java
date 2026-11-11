package ai.neargo.shop.pay.svc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.BrandCouponSourceInternal;
import ai.neargo.shop.spi.entitlement.BrandCouponSourcePort;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.AudienceSnapshot;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.Recipient;
import ai.neargo.shop.svc.InternalHttp;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

/** The actual configured typed client preserves all three request contracts and fails closed on owner failure. */
class CoreBrandCouponSourceClientTest {
    private static final EntitlementDefinition DEFINITION = new EntitlementDefinition("coupon", "ORDER_COUPON",
            "MERCHANT", "CNY", "CASH", 100L, null, null, 0L, null, "COUNT", 1, null,
            new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "UTC"),
            new EntitlementDefinition.RedemptionScope("BRAND_STORES", List.of("SOURCE")), List.of("TARGETED"));

    @Test void threeActualHttpRequestsKeepExactSourceAudienceAndCallerCoordinates() throws Exception {
        try (var f = new Fixture()) {
            f.client.validateTemplate("MAIN", "ISSUER", DEFINITION);
            assertThat(f.client.permitsUse("MAIN", "ISSUER", DEFINITION, "V1", "CONSUMER", "S1")).isTrue();
            assertThat(f.client.audience("MAIN", "ISSUER", DEFINITION, "V1", "SNAPSHOT", "ACTOR", "COMMAND"))
                    .isEqualTo(f.audience);
            assertThat(f.requests).extracting(Request::path).containsExactly(
                    BrandCouponSourceInternal.TEMPLATE_VALIDATION, BrandCouponSourceInternal.USE_VALIDATION,
                    BrandCouponSourceInternal.AUDIENCE);
            assertThat(f.requests).allSatisfy(request -> {
                assertThat(request.method()).isEqualTo("POST");
                assertThat(request.token()).isEqualTo("test-token");
            });
            assertThat(f.json.readTree(f.requests.get(0).body())).isEqualTo(f.json.readTree(f.json.writeValueAsString(
                    new BrandCouponSourceInternal.TemplateValidation("MAIN", "ISSUER", DEFINITION))));
            assertThat(f.json.readTree(f.requests.get(1).body())).isEqualTo(f.json.readTree(f.json.writeValueAsString(
                    new BrandCouponSourceInternal.UseValidation("MAIN", "ISSUER", DEFINITION, "V1", "CONSUMER", "S1"))));
            assertThat(f.json.readTree(f.requests.get(2).body())).isEqualTo(f.json.readTree(f.json.writeValueAsString(
                    new BrandCouponSourceInternal.AudienceQuery("MAIN", "ISSUER", DEFINITION, "V1", "SNAPSHOT",
                            "ACTOR", "COMMAND"))));
        }
    }

    @Test void ownerRejectionMissingRepliesAndUnavailableTransportNeverBecomePermissionOrEmptyAudience() throws Exception {
        try (var f = new Fixture()) {
            f.status = 403;
            assertThatThrownBy(() -> f.client.validateTemplate("MAIN", "ISSUER", DEFINITION))
                    .isInstanceOfSatisfying(BizException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            f.status = 503;
            assertThatThrownBy(() -> f.client.permitsUse("MAIN", "ISSUER", DEFINITION, "V1", "CONSUMER", "S1"))
                    .isInstanceOfSatisfying(BizException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
            f.status = 200;
            f.body = "";
            assertThatThrownBy(() -> f.client.audience("MAIN", "ISSUER", DEFINITION, "V1", "SNAPSHOT", "ACTOR", "COMMAND"))
                    .isInstanceOfSatisfying(BizException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
            f.body = "{\"valid\":false}";
            assertThatThrownBy(() -> f.client.validateTemplate("MAIN", "ISSUER", DEFINITION))
                    .isInstanceOfSatisfying(BizException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            f.body = "{\"permitted\":false}";
            assertThat(f.client.permitsUse("MAIN", "ISSUER", DEFINITION, "V1", "CONSUMER", "S1")).isFalse();
            assertThat(f.requests).hasSize(5); // No transport retries or substitute lookups.
        }
    }

    private record Request(String method, String path, String token, String body) { }

    private static final class Fixture implements AutoCloseable {
        final ObjectMapper json = new ObjectMapper();
        final AudienceSnapshot audience = new AudienceSnapshot("MAIN", "ISSUER", "SNAPSHOT",
                List.of(new Recipient("USER", true, null)));
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final HttpServer server;
        final BrandCouponSourcePort client;
        volatile int status = 200;
        volatile String body;

        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                requests.add(new Request(exchange.getRequestMethod(), path,
                        exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                String result = body;
                if (result == null) result = path.equals(BrandCouponSourceInternal.AUDIENCE)
                        ? json.writeValueAsString(audience) : path.equals(BrandCouponSourceInternal.TEMPLATE_VALIDATION)
                        ? "{\"valid\":true}" : "{\"permitted\":true}";
                byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var configuration = new EntitlementCoreClientConfiguration();
            var api = configuration.coreEntitlementInternalApi(new InternalHttp(service -> Optional.of(base), "test-token"));
            var beans = new StaticListableBeanFactory();
            beans.addBean("coreApi", api);
            client = configuration.coreBrandCouponSourcePort(beans.getBeanProvider(CoreEntitlementInternalApi.class));
        }

        @Override public void close() {
            server.stop(0);
        }
    }
}

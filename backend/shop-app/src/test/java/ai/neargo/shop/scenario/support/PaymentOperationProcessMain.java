package ai.neargo.shop.scenario.support;

import ai.neargo.shop.ShopApplication;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore.SessionData;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.pay.channel.TestPayGateway;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Real independently launched JVM using public current-C HTTP and the actual persistent financial
 * owners.
 */
public final class PaymentOperationProcessMain {
    private PaymentOperationProcessMain() {}

    /** Start or recover only the test runner's explicitly supplied owned payment. */
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getProperty("checkout.test.native"))) {
            throw new IllegalStateException("native isolated process required");
        }
        Path control = Path.of(System.getenv("TASK57_PROCESS_CONTROL"));
        boolean recover = "RECOVER".equals(System.getenv("TASK57_PROCESS_MODE"));
        var json = new ObjectMapper();
        try (var app =
                new SpringApplicationBuilder(ShopApplication.class, LoopbackConfig.class)
                        .profiles("test")
                        .web(WebApplicationType.SERVLET)
                        .run(
                                "--server.address=127.0.0.1",
                                "--server.port=0",
                                "--spring.main.allow-bean-definition-overriding=true",
                                "--spring.security.user.password=isolated-process-test-only",
                                "--shop.member-code.enabled=true",
                                "--shop.catalog.publication.archive-only=true")) {
            if (!(app.getBean(TestPayGateway.class) instanceof LoopbackTestGateway)) {
                throw new IllegalStateException("isolated loopback gateway is not active");
            }
            var pool = app.getBean(TokenStores.class).of(Realm.CONSUMER);
            String token =
                    pool.issue(
                            new SessionData(
                                    LoginUser.consumer(System.getenv("TASK57_BUYER"), "buyer")));
            try {
                String port = app.getEnvironment().getProperty("local.server.port");
                String endpoint =
                        "http://127.0.0.1:"
                                + port
                                + "/mp/checkout/"
                                + System.getenv("TASK57_CHECKOUT_OPERATION")
                                + "/payments"
                                + (recover ? "/lookup" : "");
                var request =
                        HttpRequest.newBuilder(URI.create(endpoint))
                                .timeout(Duration.ofSeconds(150))
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", System.getenv("TASK57_ORIGINAL_KEY"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                recover ? "" : "{\"channel\":\"TEST\"}"))
                                .build();
                var response =
                        HttpClient.newHttpClient()
                                .send(request, HttpResponse.BodyHandlers.ofString());
                var body = json.readTree(response.body());
                var result = json.createObjectNode();
                result.put("pid", ProcessHandle.current().pid());
                result.put("httpStatus", response.statusCode());
                result.put("code", body.path("code").asInt());
                var data = body.path("data");
                for (String field :
                        new String[] {"phase", "paymentOperationNo", "outTradeNo", "receiptNo"}) {
                    if (!data.path(field).isMissingNode() && !data.path(field).isNull()) {
                        result.put(field, data.path(field).asString());
                    }
                }
                result.put("hasParameters", data.path("payParams").size() > 0);
                Files.writeString(control.resolve("result.json"), json.writeValueAsString(result));
            } finally {
                pool.revoke(token);
            }
        }
    }

    /**
     * Replace only the third-party TEST boundary; trade/pay/token/SQL beans remain actual
     * production beans.
     */
    @TestConfiguration(proxyBeanMethods = false)
    public static class LoopbackConfig {
        /** Keep the exact existing test gateway bean name and type to avoid a second TEST route. */
        @Bean("testPayGateway")
        TestPayGateway testPayGateway() {
            return new LoopbackTestGateway();
        }
    }

    private static final class LoopbackTestGateway extends TestPayGateway {
        private final ObjectMapper json = new ObjectMapper();
        private final HttpClient client = HttpClient.newHttpClient();

        @Override
        public PrepayResult prepay(PrepayCommand request) {
            outside();
            try {
                Path control = Path.of(System.getenv("TASK57_PROCESS_CONTROL"));
                Files.writeString(
                        control.resolve("sending.json"),
                        json.writeValueAsString(
                                Map.of(
                                        "pid",
                                        ProcessHandle.current().pid(),
                                        "outTradeNo",
                                        request.outTradeNo())));
                if ("BEFORE_SEND".equals(System.getenv("TASK57_PROCESS_MODE"))) {
                    new CountDownLatch(1).await();
                }
                send(
                        "/prepay",
                        Map.of(
                                "outTradeNo",
                                request.outTradeNo(),
                                "amountMinor",
                                request.amountMinor()));
                return PrepayResult.ok(
                        Map.of("testChannel", "true", "outTradeNo", request.outTradeNo()), null);
            } catch (Exception uncertain) {
                if (uncertain instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return PrepayResult.fail("ISOLATED_RESPONSE_UNAVAILABLE");
            }
        }

        @Override
        public QueryResult query(String number) {
            outside();
            try {
                var result = json.readTree(send("/query", Map.of("outTradeNo", number)));
                if (!result.path("found").asBoolean()) {
                    return QueryResult.notFound();
                }
                if (!number.equals(result.path("outTradeNo").asString())) {
                    return QueryResult.failed();
                }
                return new QueryResult(
                        true,
                        result.path("paid").asBoolean(),
                        true,
                        result.path("amountMinor").asLong(),
                        result.path("tradeNo").asString(),
                        result.path("currency").asString());
            } catch (Exception unavailable) {
                if (unavailable instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return QueryResult.failed();
            }
        }

        private String send(String path, Object body) throws Exception {
            String base = System.getenv("TASK57_FAULT_BASE");
            if (base == null || !base.matches("http://127[.]0[.]0[.]1:[0-9]+")) {
                throw new IllegalStateException("loopback TEST provider required");
            }
            var request =
                    HttpRequest.newBuilder(URI.create(base + path))
                            .timeout(Duration.ofSeconds(120))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(body)))
                            .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        }

        private static void outside() {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("external gateway called under SQL transaction");
            }
        }
    }
}

package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ai.neargo.shop.common.RequestLogContext;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.food.scope.FoodScopeCredentials.Claims;
import ai.neargo.shop.food.scope.FoodScopeCredentials.Realm;
import ai.neargo.shop.food.scope.FoodScopeTestSigning;
import ai.neargo.shop.food.server.config.FoodInternalSecurityConfiguration;
import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSourceCodec;
import ai.neargo.shop.spi.pay.InternalHeaders;
import ai.neargo.shop.svc.InternalHttp;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.helper.CompressionMode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

class FoodStandaloneLoggingTest {
    private static final String TOKEN = "synthetic-internal-credential";
    private static final String SLOW_PATH = "/internal/food/log-test/private-path-segment";
    private static final String REJECTED_PATH = "/internal/food/synthetic-secret-path?proof=synthetic-secret-query";
    private static final String BODY = "{\"storeNo\":\"synthetic-private-store\"}";
    private static final String TRACE = "cafecafecafecafecafecafecafecafe";

    @TempDir Path logDir;

    @Test
    void logsTrustedStandaloneRequestsRejectionsAndSlowCallsWithBoundedGzipRolling() throws Exception {
        Path file = logDir.resolve("food-server.log");
        try (var context = new SpringApplicationBuilder(Host.class).web(WebApplicationType.SERVLET).run(
                "--server.port=0", "--server.address=127.0.0.1",
                "--shop.services.internal-token=" + TOKEN, "--" + FoodScopeTestSigning.KEY_PROPERTY,
                "--shop.obs.slow-ms=180", "--FOOD_LOG_FILE=" + file,
                "--logging.logback.rollingpolicy.max-file-size=1KB")) {
            var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            var appender = (RollingFileAppender<?>) root.getAppender("FILE");
            assertThat(appender.getFile()).isEqualTo(file.toString());
            var rolling = (SizeAndTimeBasedRollingPolicy<?>) appender.getRollingPolicy();
            assertThat(rolling.getMaxHistory()).isEqualTo(14);
            assertThat(rolling.getCompressionMode()).isEqualTo(CompressionMode.GZ);
            assertThat(rolling.getFileNamePattern()).isEqualTo(file + ".%d{yyyy-MM-dd}.%i.gz");
            assertThat(context.getEnvironment().getProperty("logging.logback.rollingpolicy.total-size-cap"))
                    .isEqualTo("200MB");
            assertThat(context.getEnvironment().getProperty("logging.logback.rollingpolicy.clean-history-on-start"))
                    .isEqualTo("true");

            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            try (var client = HttpClient.newHttpClient()) {
                var unsigned = request(port, REJECTED_PATH);
                var withoutToken = client.send(unsigned.build(), HttpResponse.BodyHandlers.discarding());
                assertThat(withoutToken.statusCode()).isEqualTo(401);
                String generatedId = requestId(withoutToken);
                assertThat(generatedId).matches("[a-f0-9]{32}").isNotEqualTo(TRACE);

                var missingScope = client.send(request(port, REJECTED_PATH).header(InternalHttp.TOKEN_HEADER, TOKEN)
                        .build(), HttpResponse.BodyHandlers.discarding());
                assertThat(missingScope.statusCode()).isEqualTo(401);
                assertThat(requestId(missingScope)).isEqualTo(TRACE);

                var forbidden = sendSigned(client, port, Realm.STAFF, "E", List.of("different-store"), "A");
                assertThat(forbidden.statusCode()).isEqualTo(403);
                var success = sendSigned(client, port, Realm.SYSTEM, null, List.of(), null);
                assertThat(success.statusCode()).isEqualTo(200);
                assertThat(requestId(success)).isEqualTo(TRACE);

                // Cross the small test rollover boundary and cause the next request to close the archive.
                LoggerFactory.getLogger("food.test.rollover").info("{}", "x".repeat(2048));
                client.send(request(port, REJECTED_PATH).build(), HttpResponse.BodyHandlers.discarding());
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    var archives = archivedLogs();
                    assertThat(archives).contains("api m=POST");
                    String logs = archives + Files.readString(file);
                    assertThat(logs)
                            .contains("api.access", "api.slow", "p=/internal/food/log-test/{id} s=200",
                                    "s=401", "rid=" + generatedId, "rid=" + TRACE,
                                    "food.scope.rejected route=/internal/food method=POST status=401 reasonCode=FOOD_SCOPE_MISSING",
                                    "food.scope.rejected route=/internal/food method=POST status=403 reasonCode=FOOD_SCOPE_STORE")
                            .doesNotContain(TOKEN, "synthetic-secret-path", "synthetic-secret-query",
                                    "private-path-segment", "synthetic-private-store");
                    assertThat(logs.lines().filter(line -> line.contains("api.slow")))
                            .anyMatch(line -> line.contains("p=/internal/food/log-test/{id} s=200")
                                    && line.contains("rid=" + TRACE));
                });
            }
        }
    }

    private static HttpRequest.Builder request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header(InternalHeaders.CALL_SOURCE, CallSourceCodec.encode(new CallSource("shop-app", "FOOD", null, null, TRACE, null, null, null, null, null, null)))
                .POST(HttpRequest.BodyPublishers.ofString(BODY));
    }

    private static HttpResponse<Void> sendSigned(HttpClient client, int port, Realm realm,
                                                 String entity, List<String> stores, String actor)
            throws IOException, InterruptedException {
        var claims = new Claims(realm, entity, stores, actor, Instant.now().plusSeconds(60).getEpochSecond(),
                FoodScopeCredentials.digest("POST", SLOW_PATH, BODY.getBytes(StandardCharsets.UTF_8)));
        var credential = FoodScopeCredentials.sign(claims, FoodScopeCredentials.key(FoodScopeTestSigning.KEY));
        return client.send(request(port, SLOW_PATH).header(InternalHttp.TOKEN_HEADER, TOKEN)
                .header(FoodScopeCredentials.HEADER, credential).build(), HttpResponse.BodyHandlers.discarding());
    }

    private static String requestId(HttpResponse<?> response) {
        return response.headers().firstValue(RequestLogContext.REQUEST_ID_HEADER).orElseThrow();
    }

    private String archivedLogs() throws IOException {
        var contents = new StringBuilder();
        try (var paths = Files.list(logDir)) {
            for (var path : paths.filter(file -> file.getFileName().toString().endsWith(".gz")).toList()) {
                try (var gzip = new GZIPInputStream(Files.newInputStream(path))) {
                    contents.append(new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return contents.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
    @Import({FoodInternalSecurityConfiguration.class, SlowController.class})
    static class Host { }

    @RestController
    static class SlowController {
        @PostMapping("/internal/food/log-test/{id}")
        String slow() throws InterruptedException {
            Thread.sleep(220);
            return "ok";
        }
    }
}

package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ai.neargo.shop.beauty.server.config.BeautyInternalSecurityConfiguration;
import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSourceCodec;
import ai.neargo.shop.spi.pay.InternalHeaders;
import ai.neargo.shop.svc.InternalHttp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class BeautyStandaloneLoggingTest {
    private static final String TRACE = "cafecafecafecafecafecafecafecafe";
    private static final String TOKEN = "synthetic-token-canary";

    @TempDir Path logDirectory;

    @Test
    void standaloneLogsSafeCorrelationAndRollsToGzip() throws Exception {
        Path file = logDirectory.resolve("beauty-server.log");
        try (var context = new SpringApplicationBuilder(Host.class).web(WebApplicationType.SERVLET).run(
                "--server.port=0", "--server.address=127.0.0.1", "--BEAUTY_LOG_FILE=" + file,
                "--shop.services.internal-token=" + TOKEN, "--shop.obs.slow-ms=50",
                "--logging.logback.rollingpolicy.max-file-size=1KB")) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            URI uri = URI.create("http://127.0.0.1:" + port + "/internal/beauty/log-test/path-canary?q=query-canary");
            String source = CallSourceCodec.encode(
                    new CallSource("shop-app", null, null, null, TRACE, null, null, null, null, null, null));
            var request = HttpRequest.newBuilder(uri).header(InternalHeaders.CALL_SOURCE, source);
            try (var client = HttpClient.newHttpClient()) {
                var rejected = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
                assertThat(rejected.statusCode()).isEqualTo(401);
                String generated = rejected.headers().firstValue("X-Request-Id").orElseThrow();
                assertThat(generated).matches("[0-9a-f]{32}").isNotEqualTo(TRACE);

                var success = client.send(request.header(InternalHttp.TOKEN_HEADER, TOKEN).build(),
                        HttpResponse.BodyHandlers.discarding());
                assertThat(success.statusCode()).isEqualTo(200);
                assertThat(success.headers().firstValue("X-Request-Id")).contains(TRACE);

                LoggerFactory.getLogger("beauty.test.rollover").info("{}", "x".repeat(2048));
                client.send(request.build(), HttpResponse.BodyHandlers.discarding());
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    var archiveText = new StringBuilder();
                    try (var files = Files.list(logDirectory)) {
                        for (var archive : files.filter(p -> p.toString().endsWith(".gz")).toList()) {
                            try (var gzip = new GZIPInputStream(Files.newInputStream(archive))) {
                                archiveText.append(new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
                            }
                        }
                    }
                    assertThat(archiveText).contains("api m=GET");
                    String logs = archiveText + Files.readString(file);
                    assertThat(logs).contains("api.access", "api.slow",
                                    "p=/internal/SECURITY_REJECTED s=401", "rid=" + generated,
                                    "p=/internal/beauty/log-test/{id} s=200", "rid=" + TRACE)
                            .doesNotContain(TOKEN, "path-canary", "query-canary");
                });
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
    @Import({BeautyInternalSecurityConfiguration.class, ProbeController.class})
    static class Host {}

    @RestController
    static class ProbeController {
        @GetMapping("/internal/beauty/log-test/{id}")
        String slow() throws InterruptedException {
            Thread.sleep(80);
            return "ok";
        }
    }
}

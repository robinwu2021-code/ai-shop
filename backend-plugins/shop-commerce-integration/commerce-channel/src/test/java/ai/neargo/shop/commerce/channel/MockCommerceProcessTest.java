package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.commerce.channel.mock.MockCallbackSignatures;
import ai.neargo.shop.commerce.channel.mock.MockCommerceGateway;
import ai.neargo.shop.commerce.channel.mock.MockCommercePlatform;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Real packaged peer in a second JVM; no Spring context or business database in that JVM. */
@EnabledIfEnvironmentVariable(named = "COMMERCE_MOCK_TEST_JAR", matches = ".+")
class MockCommerceProcessTest {
    @TempDir Path directory;
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final String token = "process-local-mock-token-32-characters";
    final MockCommercePlatformTest fixtures = new MockCommercePlatformTest();

    @Test @Timeout(60)
    void packagedPeerSeedsOrdersSignsCallbacksAndRetainsUncertainEffectsAfterRestart() throws Exception {
        var sourceJar = Path.of(System.getenv("COMMERCE_MOCK_TEST_JAR")).toAbsolutePath();
        var jar = Files.copy(sourceJar, directory.resolve("immutable-commerce.jar"));
        ChannelCatalogGateway.Publication publication;
        ChannelOrderGateway.Decision decision;
        try (var peer = start(jar)) {
            post(peer, "admin/fixtures", new MockCommercePlatform.Fixture("E", "S", "MOCK_PROFILE",
                    "EA", "ES", "MOCK:TW", Set.of("CATALOG", "ORDER", "FULFILLMENT"),
                    MockCommercePlatform.Scenario.PERSIST_THEN_TIMEOUT));
            var gateway = gateway(peer);
            var credential = fixtures.authorize(gateway);
            publication = fixtures.publication("PROCESS_MENU", credential);
            assertThat(gateway.publish(publication).outcome()).isEqualTo(ChannelCatalogGateway.Outcome.UNKNOWN);
            var document = fixtures.externalOrder("PROCESS_ORDER");
            post(peer, "admin/orders", new MockCommercePlatform.OrderSeed("MOCK_PROFILE", document,
                    MockCommercePlatform.Scenario.PERSIST_THEN_TIMEOUT));
            var query = new ChannelOrderGateway.Query("E", "S", "CON", "MOCK_PROFILE", "EA", "ES",
                    "PROCESS_ORDER", "R1", "AUTH", credential);
            assertThat(gateway.readOrder(query).document()).isEqualTo(document);
            decision = new ChannelOrderGateway.Decision("PROCESS_ACCEPT", query,
                    PricingSnapshotHash.of(document), "R1", ChannelOrderGateway.Action.ACCEPT, null, null);
            assertThat(gateway.decide(decision).status()).isEqualTo(ChannelOrderGateway.DecisionStatus.UNKNOWN);

            // The receiver verifies raw-byte signatures; business dedup belongs to CIM, not this peer test.
            var received = java.util.Collections.synchronizedList(new ArrayList<String>());
            var receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            receiver.createContext("/callback/commerce/MOCK_PROFILE/ENDPOINT", exchange -> {
                byte[] bytes = exchange.getRequestBody().readAllBytes();
                var headers = exchange.getRequestHeaders();
                String signature = MockCallbackSignatures.sign(token, headers.getFirst("X-Mock-Timestamp"),
                        headers.getFirst("X-Mock-Credential-Ref"), bytes);
                boolean valid = signature.equals(headers.getFirst("X-Mock-Signature"));
                if (valid) received.add(json.readValue(bytes, MockCommercePlatform.MockEvent.class).eventRef());
                exchange.sendResponseHeaders(valid ? 200 : 401, -1);
                exchange.close();
            });
            receiver.start();
            try {
                var event = new MockCommercePlatform.MockEvent("EVENT1", "ORDER_CREATED", "ES",
                        "PROCESS_ORDER", "R1", Instant.now());
                String callback = "http://127.0.0.1:" + receiver.getAddress().getPort()
                        + "/callback/commerce/MOCK_PROFILE/ENDPOINT";
                assertThat(post(peer, "admin/events/emit",
                        new MockCommercePlatform.Emission(callback, credential, event, 2, false)))
                        .isEqualTo("[200,200]");
                assertThat(post(peer, "admin/events/emit",
                        new MockCommercePlatform.Emission(callback, credential, event, 1, true)))
                        .isEqualTo("[401]");
                assertThat(received).containsExactly("EVENT1", "EVENT1");
            } finally { receiver.stop(0); }
        }
        try (var peer = start(jar)) {
            var gateway = gateway(peer);
            assertThat(gateway.query(publication, publication.operationNo()).outcome())
                    .isEqualTo(ChannelCatalogGateway.Outcome.SUCCEEDED);
            assertThat(gateway.queryDecision(decision).status())
                    .isEqualTo(ChannelOrderGateway.DecisionStatus.CONFIRMED);
            assertThat(gateway.queryDecision(decision).status())
                    .isEqualTo(ChannelOrderGateway.DecisionStatus.CONFIRMED);
        }
    }

    MockCommerceGateway gateway(Peer peer) {
        return new MockCommerceGateway("MOCK_PROFILE", URI.create(peer.base()), token, json);
    }

    String post(Peer peer, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(peer.base() + "/mock/v1/" + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return response.body();
    }

    Peer start(Path jar) throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dloader.main=ai.neargo.shop.commerce.channel.mock.CommerceMockApplication",
                "-cp", jar.toString(), "org.springframework.boot.loader.launch.PropertiesLauncher");
        builder.environment().put("COMMERCE_MOCK_DIRECTORY", directory.resolve("state").toString());
        builder.environment().put("COMMERCE_MOCK_TOKEN", token);
        builder.environment().put("COMMERCE_MOCK_PORT", "0");
        builder.redirectError(directory.resolve("mock-stderr.log").toFile());
        var process = builder.start();
        var output = new BufferedReader(new InputStreamReader(process.getInputStream()));
        try (var reader = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            try {
                String line = reader.submit(output::readLine).get(20, TimeUnit.SECONDS);
                assertThat(line).startsWith("Mock commerce listening on ");
                return new Peer(process, output, "http://127.0.0.1:"
                        + Integer.parseInt(line.substring(line.lastIndexOf(' ') + 1)));
            } catch (Exception | AssertionError failure) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                output.close();
                throw failure;
            }
        }
    }

    record Peer(Process process, BufferedReader output, String base) implements AutoCloseable {
        @Override public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
            output.close();
        }
    }
}

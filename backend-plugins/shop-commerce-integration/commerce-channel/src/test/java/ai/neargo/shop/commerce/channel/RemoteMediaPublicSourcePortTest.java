package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.media.MediaPublicSourceFacts;
import ai.neargo.shop.spi.media.MediaPublicSourceInternalApi;
import ai.neargo.shop.spi.media.MediaPublicSourcePort;
import ai.neargo.shop.spi.media.MediaPublicSourcePort.Asset;
import ai.neargo.shop.spi.media.MediaPublicSourcePort.Facts;
import ai.neargo.shop.spi.media.MediaPublicSourcePort.Request;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class RemoteMediaPublicSourcePortTest {
    static final String KEY = "E/S/goods/a.jpg";
    static final Request REQUEST = new Request("E", "S", List.of(KEY));
    static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    Facts facts() {
        return MediaPublicSourceFacts.seal(
                new Facts(
                        1,
                        "CATALOG_PUBLIC_MEDIA",
                        REQUEST,
                        List.of(
                                new Asset(
                                        KEY,
                                        "https://img.example/" + KEY,
                                        "image/jpeg",
                                        1234L,
                                        640,
                                        480,
                                        "a".repeat(64))),
                        NOW,
                        NOW.plusSeconds(30),
                        null));
    }

    @Test
    void remoteFactsMustBeCompleteFreshScopedAndUntampered() {
        var api = mock(MediaPublicSourceInternalApi.class);
        var port = new RemoteMediaPublicSourcePort(api, CLOCK);
        when(api.capture(REQUEST)).thenReturn(facts());
        assertThat(port.capture(REQUEST)).isEqualTo(facts());
        var f = facts();
        for (var bad :
                List.of(
                        new Facts(
                                1,
                                f.purpose(),
                                REQUEST,
                                f.assets(),
                                NOW,
                                f.validUntil(),
                                "b".repeat(64)),
                        MediaPublicSourceFacts.seal(
                                new Facts(
                                        1,
                                        f.purpose(),
                                        REQUEST,
                                        List.of(),
                                        NOW,
                                        f.validUntil(),
                                        null)),
                        MediaPublicSourceFacts.seal(
                                new Facts(
                                        1,
                                        f.purpose(),
                                        REQUEST,
                                        f.assets(),
                                        NOW,
                                        NOW.plusSeconds(31),
                                        null)))) {
            when(api.capture(REQUEST)).thenReturn(bad);
            assertThatThrownBy(() -> port.capture(REQUEST))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(
                        () ->
                                MediaPublicSourceFacts.validate(
                                        new Request("OTHER", "S", List.of(KEY)), f, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MediaPublicSourceFacts.validate(REQUEST, f, NOW.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        when(api.capture(REQUEST)).thenReturn(null);
        assertThatThrownBy(() -> port.capture(REQUEST)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void remoteFailuresNeverFallbackOrReturnPartialSuccessfulMedia() {
        var api = mock(MediaPublicSourceInternalApi.class);
        var port = new RemoteMediaPublicSourcePort(api, CLOCK);
        for (var status : List.of(400, 403, 404, 409, 501, 503)) {
            when(api.capture(REQUEST))
                    .thenThrow(
                            new ServiceCallException(
                                    CallOutcome.REMOTE_ERROR,
                                    ServiceName.PLATFORM,
                                    status,
                                    "safe"));
            assertThatThrownBy(() -> port.capture(REQUEST))
                    .isInstanceOf(status < 500 ? BizException.class : ServiceCallException.class);
            reset(api);
        }
    }

    @Test
    void typedHttpPreservesCanonicalImageAndRejectsCoercedOrUnknownMetadata() throws Exception {
        var valid = json.writeValueAsString(facts());
        var response = new AtomicReference<>(valid);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                MediaPublicSourceInternalApi.CAPTURE_PATH,
                ex -> {
                    calls.incrementAndGet();
                    assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER))
                            .isEqualTo("wire");
                    assertThat(
                                    new String(
                                            ex.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8))
                            .contains("\"assetKeys\"")
                            .doesNotContain("publicUrl", "bytes");
                    var bytes = response.get().getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, bytes.length);
                    ex.getResponseBody().write(bytes);
                    ex.close();
                });
        server.start();
        try {
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(CommerceMediaClientAutoConfiguration.class))
                    .withPropertyValues(
                            "shop.commerce.media-client.enabled=true",
                            "shop.services.internal-token=wire",
                            "shop.services.targets.PLATFORM=http://127.0.0.1:"
                                    + server.getAddress().getPort())
                    .run(
                            c -> {
                                assertThat(c).hasNotFailed();
                                var port =
                                        new RemoteMediaPublicSourcePort(
                                                c.getBean(MediaPublicSourceInternalApi.class),
                                                CLOCK);
                                assertThat(port.capture(REQUEST)).isEqualTo(facts());
                                for (var bad :
                                        List.of(
                                                valid.replace(
                                                        "\"bytes\":1234", "\"bytes\":\"1234\""),
                                                valid.replace("\"width\":640", "\"width\":640.0"),
                                                valid.replace(
                                                        "\"assetKey\":\"" + KEY + "\"",
                                                        "\"assetKey\":12"),
                                                valid.replace(
                                                        "\"contentType\":\"image/jpeg\"",
                                                        "\"contentType\":12"),
                                                valid.substring(0, valid.length() - 1)
                                                        + ",\"privateUrl\":\"secret\"}",
                                                valid.replace(
                                                        "https://img.example/",
                                                        "http://img.example/"))) {
                                    response.set(bad);
                                    assertThatThrownBy(() -> port.capture(REQUEST))
                                            .isInstanceOf(RuntimeException.class);
                                }
                            });
            assertThat(calls).hasValue(7);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitClientOptInDoesNotEnableCoreAndLocalOwnerWins() {
        var runner =
                new ApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(CommerceMediaClientAutoConfiguration.class));
        runner.run(c -> assertThat(c).doesNotHaveBean(MediaPublicSourcePort.class));
        var local = mock(MediaPublicSourcePort.class);
        runner.withBean(MediaPublicSourcePort.class, () -> local)
                .withPropertyValues("shop.commerce.media-client.enabled=true")
                .run(
                        c -> {
                            assertThat(c.getBean(MediaPublicSourcePort.class)).isSameAs(local);
                            assertThat(c).doesNotHaveBean(MediaPublicSourceInternalApi.class);
                        });
    }
}

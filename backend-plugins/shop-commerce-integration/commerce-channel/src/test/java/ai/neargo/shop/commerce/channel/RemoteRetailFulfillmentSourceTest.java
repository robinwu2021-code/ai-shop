package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort;
import ai.neargo.shop.spi.commerce.OriginalRetailChannelFulfillmentSourceAdapter;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourceInternalApi;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourcePort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;
import ai.neargo.shop.svc.InternalHttp;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class RemoteRetailFulfillmentSourceTest {
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void typedHttpPreservesExactOriginalScopeAndRejectsAValidHashForAnotherStore()
            throws Exception {
        var foreign = new AtomicBoolean();
        var seen = new AtomicReference<IndustryChannelFulfillmentSourcePort.Query>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                IndustryChannelFulfillmentSourceInternalApi.READ_PATH,
                exchange -> {
                    assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER))
                            .isEqualTo("wire");
                    var query =
                            json.readValue(
                                    exchange.getRequestBody().readAllBytes(),
                                    IndustryChannelFulfillmentSourcePort.Query.class);
                    seen.set(query);
                    var actual =
                            foreign.get()
                                    ? new IndustryChannelFulfillmentSourcePort.Query(
                                            new Scope("MAIN", "E", "OTHER"),
                                            query.sourceOrderRef(),
                                            query.coreOrderRef(),
                                            query.fulfillmentNo(),
                                            query.revision())
                                    : query;
                    var data =
                            new IndustryChannelFulfillmentSourcePort.Data(
                                    actual,
                                    "HANDED_OVER",
                                    List.of(
                                            new IndustryChannelFulfillmentSourcePort.Line(
                                                    "SOURCE_LINE", "COMP", "OPAQUE", "0.75", "KG")),
                                    now.minusSeconds(1),
                                    "ORIGINAL_ACTION",
                                    "a".repeat(64),
                                    "b".repeat(64),
                                    "c".repeat(64),
                                    "d".repeat(64));
                    var proof =
                            new IndustryChannelFulfillmentSourcePort.Facts(
                                    data,
                                    PricingSnapshotHash.of(data),
                                    now,
                                    now.plusSeconds(25),
                                    "NATIVE_READ");
                    byte[] bytes = json.writeValueAsBytes(proof);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    CommerceRetailFulfillmentSourceClientAutoConfiguration.class))
                    .withBean(Clock.class, () -> Clock.fixed(now, ZoneOffset.UTC))
                    .withPropertyValues(
                            "shop.commerce.enabled=true",
                            "shop.retail.commerce.fulfillment.enabled=true",
                            "shop.commerce.retail-fulfillment.source-mode=remote",
                            "shop.services.internal-token=wire",
                            "shop.services.targets.PLATFORM=http://127.0.0.1:"
                                    + server.getAddress().getPort())
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                var adapter =
                                        context.getBean(
                                                OriginalRetailChannelFulfillmentSourceAdapter
                                                        .class);
                                var q =
                                        new ChannelFulfillmentSourcePort.Query(
                                                "E", "S", "SOURCE", "ORDER", "F", "1");
                                assertThat(adapter.capture(q).data().lines().getFirst().quantity())
                                        .isEqualTo("0.75");
                                assertThat(seen.get().scope())
                                        .isEqualTo(new Scope("MAIN", "E", "S"));
                                assertThat(seen.get().fulfillmentNo()).isEqualTo("F");
                                foreign.set(true);
                                assertThatThrownBy(() -> adapter.capture(q))
                                        .isInstanceOf(RuntimeException.class);
                            });
        } finally {
            server.stop(0);
        }
    }
}

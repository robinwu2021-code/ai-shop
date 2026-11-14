package ai.neargo.shop.food.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.channel.config.FoodAfterSaleClientConfiguration;
import ai.neargo.shop.spi.trade.IndustryAfterSaleInternalApi;
import ai.neargo.shop.spi.trade.IndustryAfterSalePort;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class CoreIndustryAfterSalePortTest {
    @Test void realPlatformTransportPreservesOriginalCaseKeyMoneyAndRejectsForeignBinding() throws Exception {
        var identity = new IndustryAfterSalePort.Identity("MAIN", "E", "A", "BUYER", "BUYER");
        var binding = new IndustryAfterSalePort.Binding(new PricingSourceRef("FOOD", "CART", "C", 7, null, null),
                "ORDER", "SUB");
        JsonMapper json = JsonMapper.builder().build();
        AtomicInteger mode = new AtomicInteger(); AtomicInteger calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/trade/industry-after-sales", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("\"requestKey\":\"RETURN\"", "\"buyerNo\":\"BUYER\"")
                    .doesNotContain("refundMinor", "orderItemId");
            var result = new IndustryAfterSalePort.Result("AFTERSALE", mode.get() == 1 ? "FOREIGN" : "ORDER", "SUB",
                    "REFUNDING", 1000L, "CNY", null, "PENDING");
            Object response = exchange.getRequestURI().getPath().equals(IndustryAfterSaleInternalApi.QUERY_APPLY)
                    ? new IndustryAfterSalePort.Lookup(true, result) : result;
            byte[] body = json.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var http = new InternalHttp(name -> {
                assertThat(name).isEqualTo(ServiceName.PLATFORM);
                return Optional.of("http://127.0.0.1:" + server.getAddress().getPort());
            }, "wire");
            new ApplicationContextRunner().withUserConfiguration(FoodAfterSaleClientConfiguration.class)
                    .withBean(InternalHttp.class, () -> http).run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(IndustryAfterSalePort.class);
                        var port = context.getBean(IndustryAfterSalePort.class);
                        var request = new IndustryAfterSalePort.Apply(identity, binding, "RETURN", "REFUND_ONLY", "reason",
                                null, List.of(), List.of(new IndustryAfterSalePort.LineQty("SRC", PricingSnapshotHash.of(List.of("MAIN", "SKU")), 2)));
                        assertThat(port.apply(request).status()).isEqualTo("REFUNDING");
                        var found = port.queryApply(new IndustryAfterSalePort.QueryApply(identity, binding, "RETURN"));
                        assertThat(found.found()).isTrue(); assertThat(found.result().refundMinor()).isEqualTo(1000);
                        mode.set(1);
                        assertThatThrownBy(() -> port.queryApply(new IndustryAfterSalePort.QueryApply(identity, binding, "RETURN")))
                                .isInstanceOf(BizException.class);
                    });
            assertThat(calls).hasValue(3);
        } finally { server.stop(0); }
    }
}

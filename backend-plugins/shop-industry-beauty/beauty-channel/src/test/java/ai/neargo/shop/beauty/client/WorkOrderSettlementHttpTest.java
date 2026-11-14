package ai.neargo.shop.beauty.client;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.PreviewWorkOrderSettlementReq;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;
import ai.neargo.shop.beauty.dto.WorkOrderSettlementPreviewVO;
import ai.neargo.shop.beauty.dto.WorkOrderSettlementVO;
import ai.neargo.shop.beauty.spi.BeautySettlementInternalApi;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class WorkOrderSettlementHttpTest {
    @Test void realTypedReadsPreserveScopeAndRejectForeignRootOrForgedExecutablePermission() throws Exception {
        var json = JsonMapper.builder().build();
        var bindings = new InternalBindings("settlement-wire");
        var mode = new AtomicInteger();
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/beauty/work-orders/settlement/", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("settlement-wire");
            var bytes = exchange.getRequestBody().readAllBytes();
            var state = new WorkOrderSettlementVO(new WorkOrderBillVO(mode.get() == 1 ? "OTHER" : "WO", 1,
                    "local", Instant.parse("2026-11-11T00:00:00Z"), List.of(), List.of(), false, List.of("NO_SOURCE")),
                    "a".repeat(64), List.of(), false, mode.get() == 2, List.of(), List.of("SOURCE_INCOMPLETE"));
            byte[] result;
            if (exchange.getRequestURI().getPath().equals(BeautySettlementInternalApi.READ_PATH)) {
                var call = json.readValue(bytes, new TypeReference<InternalCall<BeautySettlementInternalApi.ReadReq>>() {});
                assertThat(call.binding().identity().actorNo()).isEqualTo("A");
                assertThat(call.binding().entityNo()).isEqualTo("E");
                assertThat(call.binding().storeNo()).isEqualTo("S");
                assertThat(call.binding().targetNo()).isEqualTo("WO");
                result = json.writeValueAsBytes(bindings.reply(call, state));
            } else {
                var call = json.readValue(bytes, new TypeReference<InternalCall<BeautySettlementInternalApi.PreviewReq>>() {});
                var req = call.request().preview();
                result = json.writeValueAsBytes(bindings.reply(call, new WorkOrderSettlementPreviewVO(state,
                        req.currency(), req.walletMinor(), req.externalMinor(), req.method(), false, List.of("CLOSED"))));
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, result.length);
            exchange.getResponseBody().write(result);
            exchange.close();
        });
        server.start();
        try {
            var http = new InternalHttp(name -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()), "settlement-wire");
            var client = new RemoteWorkOrderSettlementService(http.client("BEAUTY", BeautySettlementInternalApi.class,
                    Duration.ofSeconds(2)), bindings);
            DelegatedIdentityContext.call(new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "A"), () -> {
                assertThat(client.get("E", "S", "A", "WO").canSettle()).isFalse();
                assertThat(client.preview("E", "S", "A", "WO", new PreviewWorkOrderSettlementReq(
                        "a".repeat(64), "CNY", 0L, 10L, "CASH")).executable()).isFalse();
                mode.set(1);
                assertThatThrownBy(() -> client.get("E", "S", "A", "WO")).isInstanceOf(RuntimeException.class);
                mode.set(2);
                assertThatThrownBy(() -> client.get("E", "S", "A", "WO")).isInstanceOf(RuntimeException.class);
                return null;
            });
            assertThat(calls.get()).isEqualTo(4);
        } finally { server.stop(0); }
    }
}

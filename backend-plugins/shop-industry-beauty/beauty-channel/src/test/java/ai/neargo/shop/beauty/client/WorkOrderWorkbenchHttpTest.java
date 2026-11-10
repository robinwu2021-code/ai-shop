package ai.neargo.shop.beauty.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.WorkOrderQueryReq;
import ai.neargo.shop.beauty.dto.WorkOrderSummaryVO;
import ai.neargo.shop.beauty.spi.BeautyWorkOrderWorkbenchInternalApi;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class WorkOrderWorkbenchHttpTest {
    @Test
    void realTypedHttpRetainsCurrentIdentityPaginationAndRejectsForeignOwnerReply()
            throws Exception {
        var json = JsonMapper.builder().build();
        var bindings = new InternalBindings("queue-test-key");
        var seen = new AtomicReference<InternalCall<WorkOrderQueryReq>>();
        var foreign = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                BeautyWorkOrderWorkbenchInternalApi.PATH,
                exchange -> {
                    assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER))
                            .isEqualTo("queue-test-key");
                    var call =
                            json.readValue(
                                    exchange.getRequestBody().readAllBytes(),
                                    new TypeReference<InternalCall<WorkOrderQueryReq>>() {});
                    seen.set(call);
                    var row =
                            new WorkOrderSummaryVO(
                                    "E",
                                    foreign.get() ? "FOREIGN" : "S",
                                    "W",
                                    null,
                                    "OPEN",
                                    1,
                                    0,
                                    OffsetDateTime.parse("2026-11-10T00:00:00Z"),
                                    null,
                                    null,
                                    null);
                    var data =
                            json.writeValueAsBytes(
                                    bindings.reply(call, new PageData<>(List.of(row), 21, 2, 20)));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.start();
        try {
            var http =
                    new InternalHttp(
                            name ->
                                    Optional.of(
                                            "http://127.0.0.1:" + server.getAddress().getPort()),
                            "queue-test-key");
            var service =
                    new RemoteWorkOrderWorkbenchService(
                            http.client(
                                    "BEAUTY",
                                    BeautyWorkOrderWorkbenchInternalApi.class,
                                    Duration.ofSeconds(2)),
                            bindings);
            DelegatedIdentityContext.call(
                    new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "ACTOR"),
                    () -> {
                        var query = new WorkOrderQueryReq("S", "OPEN", null, null, null, 2, 20);
                        var result = service.list("E", "S", "ACTOR", query);
                        assertThat(result.records())
                                .extracting(WorkOrderSummaryVO::workOrderNo)
                                .containsExactly("W");
                        assertThat(seen.get().request()).isEqualTo(query);
                        assertThat(seen.get().binding().identity().actorNo()).isEqualTo("ACTOR");
                        assertThat(seen.get().binding().action()).isEqualTo("LIST_WORK_ORDERS");
                        foreign.set(true);
                        assertThatThrownBy(() -> service.list("E", "S", "ACTOR", query))
                                .isInstanceOf(RuntimeException.class);
                        return null;
                    });
        } finally {
            server.stop(0);
        }
    }
}

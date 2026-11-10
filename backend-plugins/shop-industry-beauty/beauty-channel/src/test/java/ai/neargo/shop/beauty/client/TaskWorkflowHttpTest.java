package ai.neargo.shop.beauty.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.StartServiceTaskReq;
import ai.neargo.shop.beauty.dto.TaskWorkflowVO;
import ai.neargo.shop.beauty.spi.BeautyTaskWorkflowInternalApi;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class TaskWorkflowHttpTest {
    @Test
    void typedStartKeepsExactCurrentIdentityVersionsAndKeyAndRejectsWrongTask() throws Exception {
        var json = JsonMapper.builder().build();
        var bindings = new InternalBindings("workflow-key");
        var seen = new AtomicReference<InternalCall<BeautyTaskWorkflowInternalApi.Start>>();
        var foreign = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                BeautyTaskWorkflowInternalApi.PATH + "/start",
                exchange -> {
                    assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER))
                            .isEqualTo("workflow-key");
                    var call =
                            json.readValue(
                                    exchange.getRequestBody().readAllBytes(),
                                    new TypeReference<
                                            InternalCall<
                                                    BeautyTaskWorkflowInternalApi.Start>>() {});
                    seen.set(call);
                    var value =
                            new TaskWorkflowVO(
                                    "OP",
                                    "W",
                                    foreign.get() ? "FOREIGN" : "T",
                                    "IN_SERVICE",
                                    4,
                                    "O",
                                    1,
                                    "OPEN",
                                    false,
                                    "NOT_REQUIRED",
                                    null,
                                    List.of());
                    var data = json.writeValueAsBytes(bindings.reply(call, value));
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
                            "workflow-key");
            var service =
                    new RemoteServiceTaskWorkflowService(
                            http.client(
                                    "BEAUTY",
                                    BeautyTaskWorkflowInternalApi.class,
                                    Duration.ofSeconds(2)),
                            bindings);
            DelegatedIdentityContext.call(
                    new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "A"),
                    () -> {
                        var request =
                                new StartServiceTaskReq(
                                        1,
                                        3,
                                        1,
                                        List.of(new StartServiceTaskReq.Slot("ROLE", 0, 2)));
                        assertThat(
                                        service.start(
                                                        "E",
                                                        "S",
                                                        "A",
                                                        "W",
                                                        "T",
                                                        request,
                                                        "original-key")
                                                .taskStatus())
                                .isEqualTo("IN_SERVICE");
                        assertThat(seen.get().request().request()).isEqualTo(request);
                        assertThat(seen.get().request().key()).isEqualTo("original-key");
                        assertThat(seen.get().binding().identity().actorNo()).isEqualTo("A");
                        assertThat(seen.get().binding().targetNo()).isEqualTo("T");
                        assertThat(seen.get().binding().action()).isEqualTo("START");
                        foreign.set(true);
                        assertThatThrownBy(
                                        () ->
                                                service.start(
                                                        "E",
                                                        "S",
                                                        "A",
                                                        "W",
                                                        "T",
                                                        request,
                                                        "original-key"))
                                .isInstanceOf(RuntimeException.class);
                        return null;
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancellationKeepsOriginalOperationAndCurrentCoordinatorAcrossRealTypedHttp()
            throws Exception {
        var json = JsonMapper.builder().build();
        var bindings = new InternalBindings("workflow-key");
        var seen = new AtomicReference<InternalCall<BeautyTaskWorkflowInternalApi.Cancel>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                BeautyTaskWorkflowInternalApi.PATH + "/cancel",
                exchange -> {
                    var call =
                            json.readValue(
                                    exchange.getRequestBody().readAllBytes(),
                                    new TypeReference<
                                            InternalCall<
                                                    BeautyTaskWorkflowInternalApi.Cancel>>() {});
                    seen.set(call);
                    var value =
                            new TaskWorkflowVO(
                                    "ORIGINAL",
                                    "W",
                                    "T",
                                    "PAUSED",
                                    5,
                                    "O",
                                    2,
                                    "PAUSED",
                                    false,
                                    "NOT_REQUIRED",
                                    "CANCELLED_BEFORE_EFFECT",
                                    List.of());
                    var bytes = json.writeValueAsBytes(bindings.reply(call, value));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            var http =
                    new InternalHttp(
                            name ->
                                    Optional.of(
                                            "http://127.0.0.1:" + server.getAddress().getPort()),
                            "workflow-key");
            var service =
                    new RemoteServiceTaskWorkflowService(
                            http.client(
                                    "BEAUTY",
                                    BeautyTaskWorkflowInternalApi.class,
                                    Duration.ofSeconds(2)),
                            bindings);
            DelegatedIdentityContext.call(
                    new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "COORDINATOR"),
                    () -> {
                        assertThat(
                                        service.cancelPending(
                                                        "E",
                                                        "S",
                                                        "COORDINATOR",
                                                        "W",
                                                        "T",
                                                        "ORIGINAL")
                                                .operationNo())
                                .isEqualTo("ORIGINAL");
                        assertThat(seen.get().request().operationNo()).isEqualTo("ORIGINAL");
                        assertThat(seen.get().binding().action()).isEqualTo("CANCEL_PENDING");
                        assertThat(seen.get().binding().identity().actorNo())
                                .isEqualTo("COORDINATOR");
                        return null;
                    });
        } finally {
            server.stop(0);
        }
    }
}

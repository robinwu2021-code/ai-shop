package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.AuthorizeCartSessionReq;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartVersionReq;
import ai.neargo.shop.food.dto.CreateCartReq;
import ai.neargo.shop.food.dto.CreateCustomerCartReq;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.ReplaceCartLineReq;
import ai.neargo.shop.food.foodclient.RemoteCheckCartService;
import ai.neargo.shop.food.spi.CartInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RemoteCartWireTest {
    @Test void tenTypedRoutesKeepOriginalCredentialsActorAndCasWithoutRetries() throws Exception {
        var calls = new ArrayList<String>(); var conflict = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String cart = "{\"cart\":{\"cartNo\":\"C\",\"storeNo\":\"A\",\"checkNo\":\"CHECK\","
                + "\"sessionNo\":\"G\",\"status\":\"OPEN\",\"orderSource\":\"QR\",\"fulfillment\":\"DINE_IN\","
                + "\"version\":0,\"lines\":[]}}";
        for (String path : List.of(CartInternalApi.CREATE_PATH, CartInternalApi.GET_PATH,
                CartInternalApi.REPLACE_PATH, CartInternalApi.ARCHIVE_LINE_PATH, CartInternalApi.ARCHIVE_PATH,
                CartInternalApi.AUTHORIZE_PATH, CartInternalApi.CUSTOMER_CREATE_PATH, CartInternalApi.CUSTOMER_GET_PATH,
                CartInternalApi.CUSTOMER_REPLACE_PATH, CartInternalApi.CUSTOMER_ARCHIVE_LINE_PATH)) {
            server.createContext(path, ex -> {
                calls.add(path + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
                String response = switch (path) {
                    case CartInternalApi.CREATE_PATH, CartInternalApi.GET_PATH,
                            CartInternalApi.CUSTOMER_CREATE_PATH, CartInternalApi.CUSTOMER_GET_PATH -> cart;
                    case CartInternalApi.ARCHIVE_PATH -> "{\"operation\":{\"operationNo\":\"OP\","
                            + "\"status\":\"SUCCEEDED\",\"version\":1,\"resultRef\":\"C\"}}";
                    case CartInternalApi.AUTHORIZE_PATH -> "{\"sessionNo\":\"G\",\"checkNo\":\"CHECK\",\"version\":1}";
                    default -> "{\"cartNo\":\"C\",\"version\":1}";
                };
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(conflict.get() ? 409 : 200, body.length);
                ex.getResponseBody().write(body); ex.close();
            });
        }
        server.start();
        try {
            var http = new InternalHttp(name -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()),
                    "wire");
            var service = new RemoteCheckCartService(http.client(ServiceName.FOOD, CartInternalApi.class,
                    Duration.ofSeconds(5)));
            var access = new CartAccess("G", "secret", "BUYER");
            var version = new CartVersionReq(0L);
            var line = new ReplaceCartLineReq(new OrderLineInput("L", "M", "MI", "R", "GOODS", "SKU",
                    BigDecimal.ONE, null, null, List.of(), List.of()), 0L);
            service.createCart("E", "A", "staff", new CreateCartReq("CHECK", "G", null, "POS", "DINE_IN", null),
                    "original");
            service.getCart("E", "A", "C");
            service.replaceLine("E", "A", "C", "L", "staff", line, "original");
            service.archiveLine("E", "A", "C", "L", "staff", version, "original");
            service.archiveCart("E", "A", "C", "staff", version, "original");
            service.authorizeSession("E", "A", "CHECK", "staff", new AuthorizeCartSessionReq("G", 0L), "original");
            service.customerCreateCart("A", access, new CreateCustomerCartReq("CHECK", "G", "DINE_IN", null),
                    "original");
            service.customerGetCart("C", access);
            service.customerReplaceLine("C", "L", access, line, "original");
            service.customerArchiveLine("C", "L", access, version, "original");
            conflict.set(true);
            assertThatThrownBy(() -> service.customerArchiveLine("C", "L", access, version, "original"))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(calls).hasSize(11);
            assertThat(calls.getFirst()).contains("\"actorNo\":\"staff\"", "\"idempotencyKey\":\"original\"");
            assertThat(calls.get(8)).contains("\"sourceLineRef\":\"L\"", "\"buyerNo\":\"BUYER\"",
                    "\"accessToken\":\"secret\"", "\"version\":0");
        } finally { server.stop(0); }
    }
}

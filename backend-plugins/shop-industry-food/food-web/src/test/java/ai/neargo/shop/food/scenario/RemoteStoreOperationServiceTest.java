package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.biz.BizServiceCalendarController;
import ai.neargo.shop.food.api.biz.BizStoreOperationPolicyController;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.CreatePolicyReq;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.SaveCalendarReq;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.foodclient.RemoteStoreOperationService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.spi.ServiceCalendarInternalApi;
import ai.neargo.shop.food.spi.StoreOperationInternalApi;
import ai.neargo.shop.food.spi.StorePolicyPublicationInternalApi;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class RemoteStoreOperationServiceTest {
    @Test void sharedHttpClientUsesRealWireContractAndCredentials() throws Exception {
        var requestBody = new AtomicReference<String>();
        var credential = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(StoreOperationInternalApi.POLICY_PATH, exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            credential.set(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER));
            byte[] body = ("{\"policyNo\":\"P1\",\"timezone\":\"Asia/Shanghai\",\"cutoverMinute\":240,"
                    + "\"payPolicy\":\"POSTPAY\",\"capabilities\":[\"TABLE_CONFIG\"],\"version\":3}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var http = new InternalHttp(name -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()),
                    "wire-test");
            var service = new RemoteStoreOperationService(http.client("FOOD", StoreOperationInternalApi.class,
                    Duration.ofSeconds(2)), request -> { throw new AssertionError("Unexpected write"); },
                request -> { throw new AssertionError("Unexpected calendar"); });
            var result = service.getPolicy("E", "A", new GetPolicyQuery(Instant.parse("2026-10-20T12:00:00Z")));
            assertThat(result.policyNo()).isEqualTo("P1");
            assertThat(result.payPolicy()).isEqualTo("POSTPAY");
            assertThat(credential).hasValue("wire-test");
            assertThat(requestBody.get()).contains("\"entityNo\":\"E\"", "\"storeNo\":\"A\"",
                    "2026-10-20T12:00:00Z");
        } finally {
            server.stop(0);
        }
    }

    @Test void publicationUsesRealTypedHttpAndNeverRetriesConflicts() throws Exception {
        var calls = new AtomicInteger();
        var content = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(StorePolicyPublicationInternalApi.POLICY_CREATE_PATH, exchange -> {
            int attempt = calls.incrementAndGet();
            content.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"operationNo\":\"OP1\",\"status\":\"SUCCEEDED\",\"version\":1,\"resultRef\":\"P1\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(attempt == 1 ? 200 : 409, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var http = new InternalHttp(
                    name -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()), "wire");
            var service = new RemoteStoreOperationService(request -> { throw new AssertionError("Unexpected read"); },
                    http.client("FOOD", StorePolicyPublicationInternalApi.class, Duration.ofSeconds(2)),
                request -> { throw new AssertionError("Unexpected calendar"); });
            var request = new CreatePolicyReq("Asia/Shanghai", 240, Instant.parse("2026-10-22T12:00:00Z"),
                    "POSTPAY", List.of(), null, 0L);
            assertThat(service.createPolicy("E", "A", "actor", request, "original"))
                    .isEqualTo(new OperationVO("OP1", "SUCCEEDED", 1, "P1"));
            assertThat(content.get()).contains("\"actorNo\":\"actor\"", "\"idempotencyKey\":\"original\"",
                    "\"version\":0", "2026-10-22T12:00:00Z");
            assertThatThrownBy(() -> service.createPolicy("E", "A", "actor", request, "original"))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(calls).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test void calendarUsesTypedDateOriginalKeyAndDoesNotRetry() throws Exception {
        var calls = new AtomicInteger();
        var content = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ServiceCalendarInternalApi.SAVE_PATH, exchange -> {
            content.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"operationNo\":\"CA1\",\"status\":\"SUCCEEDED\",\"version\":2,\"resultRef\":\"CAL1\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(calls.incrementAndGet()==1 ? 200 : 409, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var http = new InternalHttp(name -> Optional.of("http://127.0.0.1:"+server.getAddress().getPort()),"wire");
            var service = new RemoteStoreOperationService(request -> { throw new AssertionError("read"); },
                    request -> { throw new AssertionError("publish"); },
                    http.client("FOOD",ServiceCalendarInternalApi.class,Duration.ofSeconds(2)));
            var req = new SaveCalendarReq("CLOSED",List.of(),1L);
            assertThat(service.saveCalendar("E","A",LocalDate.of(2026,10,20),"staff",req,"original").version())
                    .isEqualTo(2);
            assertThat(content.get()).contains("\"localDate\":\"2026-10-20\"", "\"actorNo\":\"staff\"",
                    "\"idempotencyKey\":\"original\"");
            assertThatThrownBy(() -> service.saveCalendar("E","A",LocalDate.of(2026,10,20),"staff",req,"original"))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(calls).hasValue(2);
        } finally { server.stop(0); }
    }

    @Test void standaloneGatewayAssemblesTypedServiceAndActualControllerWithoutCore() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
                .withUserConfiguration(FoodStoreScopeTest.Host.class)
                .withBean(InternalHttp.class, () -> new InternalHttp(name -> Optional.empty(), "test-token"))
                .withBean(StoreDirectoryPort.class,
                        () -> new FoodPolicyApiTest.PolicyHost().stores(new FoodPolicyApiTest.Calls()))
                .withPropertyValues("shop.food.enabled=true", "shop.food.deployment=standalone",
                        "spring.profiles.active=api")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(StoreOperationService.class)
                            .hasSingleBean(BizStoreOperationPolicyController.class)
                            .hasSingleBean(BizServiceCalendarController.class);
                    assertThat(context.getBean(StoreOperationService.class))
                            .isInstanceOf(RemoteStoreOperationService.class);
                    assertThatThrownBy(() -> Class.forName("ai.neargo.shop.food.entity.FnbStorePolicy"))
                            .isInstanceOf(ClassNotFoundException.class);
                });
    }

    @Test void forwardsScopeAndInstantThroughTypedContract() {
        var at = Instant.parse("2026-10-20T12:00:00Z");
        var expected = new StoreOperationPolicyResultVO("P1", "Asia/Shanghai", 240, "POSTPAY", List.of(), 3);
        StoreOperationInternalApi api = request -> {
            assertThat(request).isEqualTo(new StoreOperationInternalApi.Lookup("E", "A", at));
            return expected;
        };
        var service = new RemoteStoreOperationService(api,
                request -> { throw new AssertionError("Unexpected write"); },
                request -> { throw new AssertionError("Unexpected calendar"); });
        assertThat(service.getPolicy("E", "A", new GetPolicyQuery(at)))
                .isEqualTo(expected);
    }

    @Test void preservesBusinessFailuresWithoutRetryOrFallback() {
        for (int status : new int[] {400, 403, 404, 409}) {
            var calls = new AtomicInteger();
            var service = new RemoteStoreOperationService(request -> {
                calls.incrementAndGet();
                throw new ServiceCallException(CallOutcome.REMOTE_ERROR, "FOOD", status, "remote failure");
            }, request -> { throw new AssertionError("Unexpected write"); },
                request -> { throw new AssertionError("Unexpected calendar"); });
            ErrorCode expected = switch (status) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                default -> ErrorCode.CONFLICT;
            };
            assertThatThrownBy(() -> service.getPolicy("E", "A", null))
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(expected));
            assertThat(calls).hasValue(1);
        }
    }

    @Test void unavailableServiceRemainsAnExplicitFailure() {
        var failure = new ServiceCallException(CallOutcome.UNREACHABLE, "FOOD", 0, "unavailable");
        var service = new RemoteStoreOperationService(request -> { throw failure; },
                request -> { throw new AssertionError("Unexpected write"); },
                request -> { throw new AssertionError("Unexpected calendar"); });
        assertThatThrownBy(() -> service.getPolicy("E", "A", null)).isSameAs(failure);
    }
}

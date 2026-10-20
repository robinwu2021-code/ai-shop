package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.foodclient.RemoteStoreOperationService;
import ai.neargo.shop.food.spi.StoreOperationInternalApi;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.time.Instant;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import com.sun.net.httpserver.HttpServer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.api.biz.BizStoreOperationPolicyController;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

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
                    Duration.ofSeconds(2)));
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
                            .hasSingleBean(BizStoreOperationPolicyController.class);
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
        assertThat(new RemoteStoreOperationService(api).getPolicy("E", "A", new GetPolicyQuery(at)))
                .isEqualTo(expected);
    }

    @Test void preservesBusinessFailuresWithoutRetryOrFallback() {
        for (int status : new int[] {400, 403, 404, 409}) {
            var calls = new AtomicInteger();
            var service = new RemoteStoreOperationService(request -> {
                calls.incrementAndGet();
                throw new ServiceCallException(CallOutcome.REMOTE_ERROR, "FOOD", status, "remote failure");
            });
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
        var service = new RemoteStoreOperationService(request -> { throw failure; });
        assertThatThrownBy(() -> service.getPolicy("E", "A", null)).isSameAs(failure);
    }
}

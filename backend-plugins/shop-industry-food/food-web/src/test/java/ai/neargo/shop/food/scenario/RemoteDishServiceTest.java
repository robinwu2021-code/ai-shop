package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.DishResultVO;
import tools.jackson.databind.ObjectMapper;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.SaveDishReq;
import ai.neargo.shop.food.foodclient.RemoteDishService;
import ai.neargo.shop.food.spi.DishInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RemoteDishServiceTest {
    private SaveDishReq request() {
        return new SaveDishReq("SINGLE", 0, new DishPresentation(null, List.of(), List.of(), List.of(), List.of()),
                new MeasurementPolicy("EA", "COUNT", 0, null), null);
    }

    @Test void originalTypedTransportPreservesTrustedInputsAndDoesNotRetryAConflict() throws Exception {
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(DishInternalApi.SAVE_PATH, ex -> {
            count.incrementAndGet();
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            assertThat(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"entityNo\":\"E\"", "\"storeNo\":\"A\"", "\"goodsNo\":\"G\"",
                            "\"actorNo\":\"trusted\"", "\"idempotencyKey\":\"stable\"");
            ex.sendResponseHeaders(409, -1);
            ex.close();
        });
        server.start();
        try {
            var http = new InternalHttp(name -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()), "wire");
            var service = new RemoteDishService(http.client(ServiceName.FOOD, DishInternalApi.class, Duration.ofSeconds(2)));
            assertThatThrownBy(() -> service.saveDish("E", "A", "G", "trusted", request(), "stable"))
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(count).hasValue(1);
        } finally { server.stop(0); }
    }

    @Test void rejectsEmptyOrWrongResourceReceiptsRatherThanReportingSuccess() {
        for (String body : List.of("null", "{}", "{\"dish\":{\"goodsNo\":\"OTHER\",\"prepSeconds\":0,\"version\":0,\"configVersion\":1}}")) {
            var json = new ObjectMapper();
            DishInternalApi api = mock(DishInternalApi.class);
            when(api.saveDish(any())).thenAnswer(inv -> json.readValue(body, DishResultVO.class));
            assertThatThrownBy(() -> new RemoteDishService(api)
                    .saveDish("E", "A", "G", "trusted", request(), "stable"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}

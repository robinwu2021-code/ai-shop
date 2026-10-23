package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.RevokeTableTokenReq;
import ai.neargo.shop.food.dto.RotateTableTokenReq;
import ai.neargo.shop.food.dto.StartDiningSessionReq;
import ai.neargo.shop.food.service.DiningSessionService;
import ai.neargo.shop.food.spi.DiningSessionInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class RemoteDiningSessionTest {
    @Test void originalTransportCarriesFiveTypedCallsAndNeverRetriesSecretIssuance() throws Exception {
        var calls=new ArrayList<String>();var conflict=new AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        for(String path:List.of(DiningSessionInternalApi.ROTATE_PATH,DiningSessionInternalApi.REVOKE_PATH,
                DiningSessionInternalApi.RESOLVE_PATH,DiningSessionInternalApi.START_PATH,DiningSessionInternalApi.END_PATH)) {
            server.createContext(path,ex -> {
                calls.add(path+new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
                String response=switch(path) {
                    case DiningSessionInternalApi.ROTATE_PATH -> "{\"token\":\"raw\",\"expiresAt\":\"2026-12-01T00:00:00Z\"}";
                    case DiningSessionInternalApi.RESOLVE_PATH -> "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"tableNo\":\"T1\",\"expiresAt\":\"2026-12-01T00:00:00Z\"}";
                    case DiningSessionInternalApi.START_PATH -> "{\"sessionNo\":\"S1\",\"accessToken\":\"short\",\"expiresAt\":\"2026-12-01T00:00:00Z\"}";
                    default -> "{\"operation\":{\"operationNo\":\"OP\",\"status\":\"SUCCEEDED\",\"version\":1,\"resultRef\":\"T1\"}}";
                };
                byte[] body=response.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().add("Content-Type","application/json");
                ex.sendResponseHeaders(conflict.get()?409:200,body.length);ex.getResponseBody().write(body);ex.close();
            });
        }
        server.start();
        try {
            var http=new InternalHttp(name -> Optional.of("http://127.0.0.1:"+server.getAddress().getPort()),"wire");
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
                    .withBean(InternalHttp.class,() -> http).withPropertyValues("shop.food.enabled=true",
                            "shop.food.deployment=standalone","spring.profiles.active=api").run(c -> {
                        var s=c.getBean(DiningSessionService.class);
                        var req=new RotateTableTokenReq(Instant.parse("2026-12-01T00:00:00Z"),0L);
                        assertThat(s.rotateTableToken("E","A","T1","staff",req,"original").token()).isEqualTo("raw");
                        assertThat(s.resolveTableToken("raw").entityNo()).isEqualTo("E");
                        assertThat(s.startSession("E","A","buyer",new StartDiningSessionReq("raw",null),"start").accessToken()).isEqualTo("short");
                        s.endSession("S1","short","buyer","end");
                        s.revokeTableToken("E","A","T1","staff",new RevokeTableTokenReq(1L,"撤销"),"revoke");
                        conflict.set(true);
                        assertThatThrownBy(() -> s.rotateTableToken("E","A","T1","staff",req,"original"))
                                .isInstanceOfSatisfying(BizException.class,e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
                    });
            assertThat(calls).hasSize(6);assertThat(calls.get(0)).contains("\"actorNo\":\"staff\"","\"idempotencyKey\":\"original\"");
            assertThat(calls.get(2)).contains("\"buyerNo\":\"buyer\"");
        } finally { server.stop(0); }
    }
}

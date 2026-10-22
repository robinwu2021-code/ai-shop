package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.ClearCheckReq;
import ai.neargo.shop.food.dto.CombineTablesReq;
import ai.neargo.shop.food.dto.OpenCheckReq;
import ai.neargo.shop.food.dto.OpenSharedCheckReq;
import ai.neargo.shop.food.dto.SaveCheckSeatsReq;
import ai.neargo.shop.food.dto.TableVersionReq;
import ai.neargo.shop.food.dto.TransferCheckReq;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.spi.CheckInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class RemoteCheckServiceTest {
    @Test void tenTypedCallsKeepActorKeyAndNeverRetryWrites() throws Exception {
        var calls=new ArrayList<String>();var conflict=new AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String check="{\"checkNo\":\"C1\",\"storeNo\":\"A\",\"businessDate\":\"2026-10-21\",\"status\":\"OPEN\",\"payMode\":\"PREPAY\",\"partySize\":2,\"tableNos\":[\"T1\"],\"version\":0}";
        String op="{\"operationNo\":\"OP1\",\"status\":\"SUCCEEDED\",\"version\":1,\"resultRef\":\"C1\"}";
        for(String path:List.of(CheckInternalApi.OPEN_PATH,CheckInternalApi.LIST_PATH,CheckInternalApi.GET_PATH,
                CheckInternalApi.SEATS_PATH,CheckInternalApi.CLEAR_PATH,CheckInternalApi.TRANSFER_PATH,CheckInternalApi.COMBINE_PATH,
                CheckInternalApi.SHARE_PATH,CheckInternalApi.OPERATION_PATH,CheckInternalApi.ACTIONS_PATH)) server.createContext(path,ex -> {
            calls.add(path+new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            String response=switch(path) {
                case CheckInternalApi.LIST_PATH -> "{\"records\":["+check+"],\"total\":1,\"page\":1,\"size\":20}";
                case CheckInternalApi.GET_PATH -> "{\"check\":"+check+",\"seats\":[],\"occupancies\":[]}";
                case CheckInternalApi.ACTIONS_PATH -> "{\"records\":[],\"total\":0,\"page\":1,\"size\":20}";
                case CheckInternalApi.CLEAR_PATH -> op;
                case CheckInternalApi.TRANSFER_PATH,CheckInternalApi.COMBINE_PATH,CheckInternalApi.OPERATION_PATH -> "{\"operation\":"+op+"}";
                default -> "{\"check\":"+check+"}";
            };
            byte[] body=response.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().add("Content-Type","application/json");
            ex.sendResponseHeaders(conflict.get()?409:200,body.length);ex.getResponseBody().write(body);ex.close();
        });
        server.start();
        try {
            var http=new InternalHttp(name -> Optional.of("http://127.0.0.1:"+server.getAddress().getPort()),"wire");
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
                    .withBean(InternalHttp.class,() -> http).withPropertyValues("shop.food.enabled=true","shop.food.deployment=standalone",
                            "spring.profiles.active=api").run(c -> {
                        var s=c.getBean(CheckService.class);var versions=List.of(new TableVersionReq("T1",0L));
                        var req=new OpenCheckReq(List.of("T1"),2,"PREPAY",null,versions,"SHARED",List.of(),null);
                        assertThat(s.openCheck("E","A","staff",req,"original").check().checkNo()).isEqualTo("C1");
                        assertThat(s.listChecks("E","A",null).total()).isEqualTo(1);
                        assertThat(s.getCheck("E","A","C1").check().tableNos()).containsExactly("T1");
                        s.saveSeats("E","A","C1","staff",new SaveCheckSeatsReq(0L,2,List.of(),"人数"),"original");
                        assertThat(s.clearCheck("E","A","C1","staff",new ClearCheckReq(0L,"清台",null,List.of("O1")),"original").operationNo()).isEqualTo("OP1");
                        s.transferCheck("E","A","C1","staff",new TransferCheckReq(0L,"换台",null,List.of("T1"),versions),"original");
                        s.combineTables("E","A","C1","staff",new CombineTablesReq(0L,versions,"并桌"),"original");
                        s.openSharedCheck("E","A","T1","staff",new OpenSharedCheckReq(0L,1,"PREPAY"),"original");
                        s.getTableOperation("E","A","OP1");s.listActions("E","A","C1",null);
                        conflict.set(true);
                        assertThatThrownBy(() -> s.openCheck("E","A","staff",req,"original"))
                                .isInstanceOfSatisfying(BizException.class,e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
                    });
            assertThat(calls).hasSize(11);assertThat(calls.getFirst()).contains("\"actorNo\":\"staff\"","\"idempotencyKey\":\"original\"","\"sharingMode\":\"SHARED\"");
        } finally { server.stop(0); }
    }
}

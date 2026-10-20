package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.biz.BizTableAreaController;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.CreateTableAreaReq;
import ai.neargo.shop.food.dto.CreateTableReq;
import ai.neargo.shop.food.dto.SaveTableReq;
import ai.neargo.shop.food.dto.SetStatusTableReq;
import ai.neargo.shop.food.dto.ArchiveTableReq;
import ai.neargo.shop.food.foodclient.RemoteTableService;
import java.time.Duration;
import ai.neargo.shop.food.dto.SaveTableAreaReq;
import ai.neargo.shop.food.dto.SetStatusTableAreaReq;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.food.spi.TableInternalApi;
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

class RemoteTableServiceTest {
    @Test void sixTableCallsPreservePayloadAndNeverRetryArchiveConflict() throws Exception {
        var requests = new ArrayList<String>();
        var conflict = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String table = "{\"tableNo\":\"T1\",\"storeNo\":\"A\",\"areaNo\":\"AREA1\",\"code\":\"T1\","
                + "\"name\":\"四人桌\",\"capacity\":4,\"sortNo\":0,\"status\":\"ACTIVE\",\"turnoverState\":\"IDLE\",\"version\":0}";
        for(String path:List.of(TableInternalApi.TABLE_LIST_PATH,TableInternalApi.TABLE_GET_PATH,
                TableInternalApi.TABLE_CREATE_PATH,TableInternalApi.TABLE_SAVE_PATH,
                TableInternalApi.TABLE_STATUS_PATH,TableInternalApi.TABLE_ARCHIVE_PATH)) {
            server.createContext(path, exchange -> {
                requests.add(path+new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
                String response=path.equals(TableInternalApi.TABLE_LIST_PATH)
                        ? "{\"records\":["+table+"],\"total\":1,\"page\":1,\"size\":20}"
                        : path.equals(TableInternalApi.TABLE_ARCHIVE_PATH)
                        ? "{\"operation\":{\"operationNo\":\"OP1\",\"status\":\"SUCCEEDED\",\"version\":1,\"resultRef\":\"T1\"}}"
                        : "{\"table\":"+table+"}";
                byte[] body=response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(conflict.get()?409:200,body.length);
                exchange.getResponseBody().write(body);exchange.close();
            });
        }
        server.start();
        try {
            var http=new InternalHttp(name -> Optional.of("http://127.0.0.1:"+server.getAddress().getPort()),"wire");
            var service=new RemoteTableService(http.client("FOOD",TableInternalApi.class,Duration.ofSeconds(2)));
            assertThat(service.listTables("E","A",null)).isNotNull();
            assertThat(service.getTable("E","A","T1").table().tableNo()).isEqualTo("T1");
            service.createTable("E","A","staff",new CreateTableReq("AREA1","T1","四人桌",4,0,null),"original");
            service.saveTable("E","A","T1","staff",new SaveTableReq("AREA1","四人桌",4,0,0L),"save");
            service.setStatusTable("E","A","T1","staff",new SetStatusTableReq("DISABLED",0L,"调整"),"off");
            assertThat(service.archiveTable("E","A","T1","staff",new ArchiveTableReq(0L,"调整"),"ar")
                    .operation().version()).isEqualTo(1);
            conflict.set(true);
            assertThatThrownBy(() -> service.archiveTable("E","A","T1","staff",new ArchiveTableReq(0L,"调整"),"ar"))
                    .isInstanceOfSatisfying(BizException.class,e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(requests).hasSize(7);
            assertThat(requests.get(2)).contains("\"actorNo\":\"staff\"","\"idempotencyKey\":\"original\"", "\"capacity\":4");
            assertThat(requests.get(3)).contains("\"tableNo\":\"T1\"","\"version\":0");
        } finally { server.stop(0); }
    }

    @Test void allFourCallsUseTypedWireAndConflictIsNeverRetried() throws Exception {
        var requests = new ArrayList<String>();
        var conflict = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String area = "{\"areaNo\":\"A1\",\"storeNo\":\"S1\",\"code\":\"H\",\"name\":\"大厅\","
                + "\"sortNo\":0,\"status\":\"ACTIVE\",\"version\":0}";
        for (String path : List.of(TableInternalApi.LIST_PATH, TableInternalApi.CREATE_PATH,
                TableInternalApi.SAVE_PATH, TableInternalApi.STATUS_PATH)) {
            server.createContext(path, exchange -> {
                requests.add(path + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
                String response = path.equals(TableInternalApi.LIST_PATH)
                        ? "{\"records\":["+area+"],\"total\":1,\"page\":1,\"size\":20}" : "{\"area\":"+area+"}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(conflict.get() ? 409 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
        }
        server.start();
        try {
            var http = new InternalHttp(name -> Optional.of("http://127.0.0.1:"+server.getAddress().getPort()), "wire");
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
                    .withBean(InternalHttp.class, () -> http)
                    .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class)
                    .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api",
                            "shop.food.deployment=standalone").run(c -> {
                        assertThat(c).hasSingleBean(TableService.class).hasSingleBean(BizTableAreaController.class);
                        var s = c.getBean(TableService.class);
                        assertThat(s.listTableAreas("E","S1",null).records().getFirst().areaNo()).isEqualTo("A1");
                        assertThat(s.createTableArea("E","S1","staff",new CreateTableAreaReq("H","大厅",0),"key")
                                .area().code()).isEqualTo("H");
                        assertThat(s.saveTableArea("E","S1","A1","staff",new SaveTableAreaReq("大厅",0,0L),"save")
                                .area().version()).isZero();
                        s.setStatusTableArea("E","S1","A1","staff",
                                new SetStatusTableAreaReq("DISABLED",0L,"停用"),"status");
                        conflict.set(true);
                        assertThatThrownBy(() -> s.createTableArea("E","S1","staff",
                                new CreateTableAreaReq("H","大厅",0),"key"))
                                .isInstanceOfSatisfying(BizException.class,
                                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
                        assertThat(requests).hasSize(5);
                        assertThat(requests.get(1)).contains("\"entityNo\":\"E\"", "\"storeNo\":\"S1\"",
                                "\"actorNo\":\"staff\"", "\"idempotencyKey\":\"key\"");
                        assertThat(requests.get(2)).contains("\"areaNo\":\"A1\"", "\"version\":0");
                    });
        } finally { server.stop(0); }
    }
}

package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ArchiveTableReq;
import ai.neargo.shop.food.dto.CreateTableReq;
import ai.neargo.shop.food.dto.ListTablesQuery;
import ai.neargo.shop.food.dto.SaveTableReq;
import ai.neargo.shop.food.dto.SetStatusTableReq;
import ai.neargo.shop.food.dto.TableVO;
import ai.neargo.shop.food.dto.TableResultVO;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.CreateTableAreaReq;
import ai.neargo.shop.food.dto.ListTableAreasQuery;
import ai.neargo.shop.food.dto.SaveTableAreaReq;
import ai.neargo.shop.food.dto.SetStatusTableAreaReq;
import ai.neargo.shop.food.dto.TableAreaResultVO;
import ai.neargo.shop.food.dto.TableAreaVO;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.food.spi.TableInternalApi;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import ai.neargo.shop.food.server.api.InternalTableController;
import ai.neargo.shop.food.server.config.FoodInternalSecurityConfiguration;
import ai.neargo.shop.svc.InternalHttp;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class InternalTableConfigurationApiTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(Host.class).withPropertyValues("shop.food.enabled=true",
                    "shop.services.internal-token=table-test", ai.neargo.shop.food.scope.FoodScopeTestSigning.KEY_PROPERTY);
    @Test void allSixTypedRoutesReturnUnwrappedResponses() {
        runner.run(c -> {
            var mvc=mvc(c);
            String scope="\"entityNo\":\"E\",\"storeNo\":\"A\"";
            String actor=",\"actorNo\":\"staff\",\"idempotencyKey\":\"original\"";
            String id=",\"tableNo\":\"T1\"";
            for(String[] spec:List.of(
                    new String[]{TableInternalApi.TABLE_LIST_PATH,"{"+scope+"}","$.total"},
                    new String[]{TableInternalApi.TABLE_GET_PATH,"{"+scope+id+"}","$.table.capacity"},
                    new String[]{TableInternalApi.TABLE_CREATE_PATH,"{"+scope+actor+",\"table\":{\"areaNo\":\"AREA1\",\"code\":\"T1\",\"name\":\"四人桌\",\"capacity\":4,\"sortNo\":0}}","$.table.capacity"},
                    new String[]{TableInternalApi.TABLE_SAVE_PATH,"{"+scope+id+actor+",\"table\":{\"areaNo\":\"AREA1\",\"name\":\"四人桌\",\"capacity\":4,\"sortNo\":0,\"version\":0}}","$.table.capacity"},
                    new String[]{TableInternalApi.TABLE_STATUS_PATH,"{"+scope+id+actor+",\"table\":{\"status\":\"DISABLED\",\"reason\":\"调整\",\"version\":0}}","$.table.capacity"},
                    new String[]{TableInternalApi.TABLE_ARCHIVE_PATH,"{"+scope+id+actor+",\"table\":{\"reason\":\"调整\",\"version\":0}}","$.operation.version"})) {
                mvc.perform(post(spec[0]).header(InternalHttp.TOKEN_HEADER,"table-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                .contentType(MediaType.APPLICATION_JSON).content(spec[1]))
                        .andExpect(status().isOk()).andExpect(jsonPath(spec[2]).exists())
                        .andExpect(jsonPath("$.data").doesNotExist());
            }
        });
    }
    @Test void serviceTokenValidationAndNotFoundStayDistinct() {
        runner.run(c -> {
            var mvc=mvc(c);String body="{\"entityNo\":\"E\",\"storeNo\":\"A\",\"tableNo\":\"FOREIGN\"}";
            mvc.perform(post(TableInternalApi.TABLE_GET_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(TableInternalApi.TABLE_GET_PATH).header(InternalHttp.TOKEN_HEADER,"table-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
            mvc.perform(post(TableInternalApi.TABLE_GET_PATH).header(InternalHttp.TOKEN_HEADER,"table-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        });
    }
    private MockMvc mvc(WebApplicationContext c) {
        return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
    }
    @Configuration(proxyBeanMethods=false)
    @EnableWebMvc
    @Import({InternalTableController.class,FoodInternalSecurityConfiguration.class})
    static class Host {
        @Bean TableService tableService() {
            return new TableService() {
                private TableResultVO table(String e,String s,String n) {
                    assertThat(e).isEqualTo("E");assertThat(s).isEqualTo("A");
                    if("FOREIGN".equals(n)) throw BizException.of(ErrorCode.NOT_FOUND);
                    return new TableResultVO(new TableVO("T1","A","AREA1","T1","四人桌",4,0,"ACTIVE","IDLE",null,0));
                }
                private void actor(String actor,String key) {
                    assertThat(actor).isEqualTo("staff");assertThat(key).isEqualTo("original");
                }
                public PageData<TableVO> listTables(String e,String s,ListTablesQuery q) {
                    return PageData.of(List.of(table(e,s,"T1").table()),1,1,20);
                }
                public TableResultVO getTable(String e,String s,String n) { return table(e,s,n); }
                public TableResultVO createTable(String e,String s,String a,CreateTableReq r,String k) {
                    actor(a,k);return table(e,s,"T1");
                }
                public TableResultVO saveTable(String e,String s,String n,String a,SaveTableReq r,String k) {
                    actor(a,k);return table(e,s,n);
                }
                public TableResultVO setStatusTable(String e,String s,String n,String a,SetStatusTableReq r,String k) {
                    actor(a,k);return table(e,s,n);
                }
                public OperationResultVO archiveTable(String e,String s,String n,String a,ArchiveTableReq r,String k) {
                    actor(a,k);table(e,s,n);
                    return new OperationResultVO(new OperationVO("OP1","SUCCEEDED",1,"T1"));
                }
                public PageData<TableAreaVO> listTableAreas(String e,String s,ListTableAreasQuery q) {
                    throw new AssertionError("Unexpected area");
                }
                public TableAreaResultVO createTableArea(String e,String s,String a,CreateTableAreaReq r,String k) {
                    throw new AssertionError("Unexpected area");
                }
                public TableAreaResultVO saveTableArea(String e,String s,String n,String a,SaveTableAreaReq r,String k) {
                    throw new AssertionError("Unexpected area");
                }
                public TableAreaResultVO setStatusTableArea(String e,String s,String n,String a,SetStatusTableAreaReq r,String k) {
                    throw new AssertionError("Unexpected area");
                }
            };
        }
    }
}

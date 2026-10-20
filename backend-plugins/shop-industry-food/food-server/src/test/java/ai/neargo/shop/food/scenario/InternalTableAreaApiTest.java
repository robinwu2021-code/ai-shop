package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateTableReq;
import ai.neargo.shop.food.dto.SaveTableReq;
import ai.neargo.shop.food.dto.SetStatusTableReq;
import ai.neargo.shop.food.dto.ArchiveTableReq;
import ai.neargo.shop.food.dto.ListTablesQuery;
import ai.neargo.shop.food.dto.TableVO;
import ai.neargo.shop.food.dto.TableResultVO;
import ai.neargo.shop.food.dto.OperationResultVO;

import ai.neargo.shop.food.dto.CreateTableAreaReq;
import ai.neargo.shop.food.dto.ListTableAreasQuery;
import ai.neargo.shop.food.dto.SaveTableAreaReq;
import ai.neargo.shop.food.dto.SetStatusTableAreaReq;
import ai.neargo.shop.food.dto.TableAreaResultVO;
import ai.neargo.shop.food.dto.TableAreaVO;
import ai.neargo.shop.food.server.api.InternalTableAreaController;
import ai.neargo.shop.food.server.config.FoodInternalSecurityConfiguration;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.food.spi.TableInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class InternalTableAreaApiTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(Host.class).withPropertyValues("shop.food.enabled=true",
                    "shop.services.internal-token=area-token");
    private static final String SCOPE = "\"entityNo\":\"E\",\"storeNo\":\"A\"";
    private static final String WRITE = SCOPE+",\"actorNo\":\"staff\",\"idempotencyKey\":\"original\"";
    @Test void fourInternalRoutesReturnPlainTypedData() {
        runner.run(c -> {
            var mvc = mvc(c);
            mvc.perform(post(TableInternalApi.LIST_PATH).header(InternalHttp.TOKEN_HEADER,"area-token")
                            .contentType(MediaType.APPLICATION_JSON).content("{"+SCOPE+"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.data").doesNotExist());
            for (String[] spec : List.of(
                    new String[]{TableInternalApi.CREATE_PATH,"", "{\"code\":\"H\",\"name\":\"大厅\",\"sortNo\":0}"},
                    new String[]{TableInternalApi.SAVE_PATH,",\"areaNo\":\"A1\"",
                            "{\"name\":\"大厅\",\"sortNo\":0,\"version\":0}"},
                    new String[]{TableInternalApi.STATUS_PATH,",\"areaNo\":\"A1\"",
                            "{\"status\":\"DISABLED\",\"version\":0,\"reason\":\"停用\"}"})) {
                mvc.perform(post(spec[0]).header(InternalHttp.TOKEN_HEADER,"area-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{"+WRITE+spec[1]+",\"area\":"+spec[2]+"}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.area.areaNo").value("A1"));
            }
        });
    }
    @Test void serviceTokenNestedValidationAndBusinessStatusArePreserved() {
        runner.run(c -> {
            var mvc = mvc(c);
            mvc.perform(post(TableInternalApi.LIST_PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{"+SCOPE+"}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(TableInternalApi.STATUS_PATH).header(InternalHttp.TOKEN_HEADER,"area-token")
                            .contentType(MediaType.APPLICATION_JSON).content("{"+WRITE+",\"areaNo\":\"A1\","
                                    + "\"area\":{\"status\":\"IDLE\",\"version\":0,\"reason\":\"停用\"}}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post(TableInternalApi.LIST_PATH).header(InternalHttp.TOKEN_HEADER,"area-token")
                            .contentType(MediaType.APPLICATION_JSON).content("{"+SCOPE.replace("\"E\"","\"OTHER\"")+"}"))
                    .andExpect(status().isNotFound());
        });
    }
    private MockMvc mvc(WebApplicationContext c) {
        return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
    }
    @Configuration(proxyBeanMethods=false)
    @EnableWebMvc
    @Import({InternalTableAreaController.class,FoodInternalSecurityConfiguration.class})
    static class Host {
        @Bean TableService service() {
            return new TableService() {
                public PageData<TableVO> listTables(String entityNo, String storeNo, ListTablesQuery query) {
                    throw new AssertionError("Unexpected table operation");
                }
                public TableResultVO getTable(String entityNo, String currentStoreNo, String tableNo) {
                    throw new AssertionError("Unexpected table operation");
                }
                public TableResultVO createTable(String entityNo, String storeNo, String actorNo, CreateTableReq req, String idempotencyKey) {
                    throw new AssertionError("Unexpected table operation");
                }
                public TableResultVO saveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, SaveTableReq req, String idempotencyKey) {
                    throw new AssertionError("Unexpected table operation");
                }
                public TableResultVO setStatusTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, SetStatusTableReq req, String idempotencyKey) {
                    throw new AssertionError("Unexpected table operation");
                }
                public OperationResultVO archiveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, ArchiveTableReq req, String idempotencyKey) {
                    throw new AssertionError("Unexpected table operation");
                }

                private TableAreaResultVO check(String e,String s,String actor,String key) {
                    assertThat(e).isEqualTo("E");assertThat(s).isEqualTo("A");
                    assertThat(actor).isEqualTo("staff");assertThat(key).isEqualTo("original");
                    return new TableAreaResultVO(new TableAreaVO("A1","A","H","大厅",0,"ACTIVE",0));
                }
                public PageData<TableAreaVO> listTableAreas(String e,String s,ListTableAreasQuery q) {
                    if (!"E".equals(e)) throw BizException.of(ErrorCode.NOT_FOUND);
                    return PageData.of(List.of(check(e,s,"staff","original").area()),1,1,20);
                }
                public TableAreaResultVO createTableArea(String e,String s,String actor,CreateTableAreaReq r,String k) {
                    return check(e,s,actor,k);
                }
                public TableAreaResultVO saveTableArea(String e,String s,String n,String actor,SaveTableAreaReq r,String k) {
                    assertThat(n).isEqualTo("A1");return check(e,s,actor,k);
                }
                public TableAreaResultVO setStatusTableArea(String e,String s,String n,String actor,
                        SetStatusTableAreaReq r,String k) {
                    assertThat(n).isEqualTo("A1");return check(e,s,actor,k);
                }
            };
        }
    }
}

package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.ArchiveTableReq;
import ai.neargo.shop.food.dto.CreateTableAreaReq;
import ai.neargo.shop.food.dto.CreateTableReq;
import ai.neargo.shop.food.dto.ListTableAreasQuery;
import ai.neargo.shop.food.dto.ListTablesQuery;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.SaveTableAreaReq;
import ai.neargo.shop.food.dto.SaveTableReq;
import ai.neargo.shop.food.dto.SetStatusTableAreaReq;
import ai.neargo.shop.food.dto.SetStatusTableReq;
import ai.neargo.shop.food.dto.TableAreaResultVO;
import ai.neargo.shop.food.dto.TableAreaVO;
import ai.neargo.shop.food.dto.TableResultVO;
import ai.neargo.shop.food.dto.TableVO;
import ai.neargo.shop.food.service.TableService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class TableConfigurationApiTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class, FoodPolicyApiTest.PolicySecurity.class,Host.class)
            .withPropertyValues("shop.food.enabled=true","spring.profiles.active=api");
    @Test void allSixRoutesUseTrustedContextAndOriginalEnvelope() {
        runner.run(c -> {
            var mvc=mvc(c);String auth=token(c,"staff");
            mvc.perform(get("/biz/x/food/stores/A/tables").header("Authorization",auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
            mvc.perform(get("/biz/x/food/tables/T1").header("Authorization",auth))
                    .andExpect(jsonPath("$.data.table.tableNo").value("T1"));
            mvc.perform(post("/biz/x/food/stores/A/tables").header("Authorization",auth)
                            .header("Idempotency-Key","original").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"areaNo\":\"AREA1\",\"code\":\"T1\",\"name\":\"四人桌\",\"capacity\":4,\"sortNo\":0}"))
                    .andExpect(jsonPath("$.data.table.capacity").value(4));
            mvc.perform(put("/biz/x/food/tables/T1").header("Authorization",auth)
                            .header("Idempotency-Key","original").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"areaNo\":\"AREA1\",\"name\":\"四人桌\",\"capacity\":4,\"sortNo\":0,\"version\":0}"))
                    .andExpect(jsonPath("$.data.table.tableNo").value("T1"));
            for(String action:List.of("set-status","archive")) {
                String body=action.equals("archive")?"{\"version\":0,\"reason\":\"调整\"}"
                        :"{\"status\":\"DISABLED\",\"version\":0,\"reason\":\"调整\"}";
                mvc.perform(post("/biz/x/food/tables/T1:"+action).header("Authorization",auth)
                                .header("Idempotency-Key","original").contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
            }
        });
    }
    @Test void rejectsPermissionHeaderForeignObjectAndForgedState() {
        runner.run(c -> {
            var mvc=mvc(c);String auth=token(c,"staff");
            mvc.perform(get("/biz/x/food/tables/T1").header("Authorization",token(c,"cashier")))
                    .andExpect(jsonPath("$.code").value(70006));
            mvc.perform(get("/biz/x/food/tables/T1").header("Authorization",auth)
                            .header(BizContextFilter.STORE_HEADER,"OTHER"))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc.perform(get("/biz/x/food/tables/FOREIGN").header("Authorization",auth))
                    .andExpect(jsonPath("$.code").value(10404));
            mvc.perform(get("/biz/x/food/stores/A/tables").header("Authorization",auth).param("size","101"))
                    .andExpect(jsonPath("$.code").value(10400));
            String body="{\"areaNo\":\"AREA1\",\"code\":\"T1\",\"name\":\"四人桌\",\"capacity\":4,\"sortNo\":0}";
            mvc.perform(post("/biz/x/food/stores/A/tables").header("Authorization",auth)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.code").value(10400));
            for(String field:List.of("actorNo","entityNo","turnoverState")) {
                mvc.perform(post("/biz/x/food/stores/A/tables").header("Authorization",auth)
                                .header("Idempotency-Key","original").contentType(MediaType.APPLICATION_JSON)
                                .content(body.replace("{","{\""+field+"\":\"forged\",")))
                        .andExpect(jsonPath("$.code").value(10400));
            }
        });
    }
    private String token(WebApplicationContext c,String actor) {
        return "Bearer "+c.getBean(MemoryTokenStore.class)
                .issue(TokenStore.SessionData.of(LoginUser.merchant(actor,actor)));
    }
    private MockMvc mvc(WebApplicationContext c) {
        return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
    }
    @Configuration(proxyBeanMethods=false)
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
                    assertThat(e).isEqualTo("E");assertThat(s).isEqualTo("A");
                    return PageData.of(List.of(),0,1,20);
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

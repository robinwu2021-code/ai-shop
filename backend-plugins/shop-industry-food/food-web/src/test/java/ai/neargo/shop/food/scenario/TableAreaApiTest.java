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
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.api.biz.BizTableAreaController;
import ai.neargo.shop.food.dto.CreateTableAreaReq;
import ai.neargo.shop.food.dto.ListTableAreasQuery;
import ai.neargo.shop.food.dto.SaveTableAreaReq;
import ai.neargo.shop.food.dto.SetStatusTableAreaReq;
import ai.neargo.shop.food.dto.TableAreaResultVO;
import ai.neargo.shop.food.dto.TableAreaVO;
import ai.neargo.shop.food.service.TableService;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

class TableAreaApiTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class, FoodPolicyApiTest.PolicySecurity.class,
                    Host.class).withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api");
    static final String CREATE = "{\"code\":\"HALL\",\"name\":\"大厅\",\"sortNo\":0}";
    @Test void fourRoutesUseOriginalIdentityScopeAndEnvelope() {
        runner.run(c -> {
            var mvc = mvc(c);
            String token = token(c, "staff");
            mvc.perform(get("/biz/x/food/stores/A/table-areas").header("Authorization", token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.records[0].areaNo").value("AREA1"));
            mvc.perform(post("/biz/x/food/stores/A/table-areas").header("Authorization", token)
                            .header("Idempotency-Key", "original").contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.area.code").value("HALL"));
            mvc.perform(put("/biz/x/food/table-areas/AREA1").header("Authorization", token)
                            .header("Idempotency-Key", "original").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"包间\",\"sortNo\":1,\"version\":0}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.area.name").value("包间"));
            mvc.perform(post("/biz/x/food/table-areas/AREA1:set-status").header("Authorization", token)
                            .header("Idempotency-Key", "original").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"DISABLED\",\"version\":1,\"reason\":\"停用\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.area.status").value("DISABLED"));
            assertThat(c.getBean(Calls.class).count).hasValue(4);
        });
    }
    @Test void deniesForeignHeaderStorePermissionAndDirectoryMismatchBeforeCallingService() {
        runner.run(c -> {
            mvc(c).perform(post("/biz/x/food/stores/A/table-areas").header("Authorization",token(c,"staff"))
                            .header(BizContextFilter.STORE_HEADER,"OTHER").header("Idempotency-Key","original")
                            .contentType(MediaType.APPLICATION_JSON).content(CREATE))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc(c).perform(get("/biz/x/food/stores/A/table-areas").header("Authorization",token(c,"cashier")))
                    .andExpect(jsonPath("$.code").value(70006));
            c.getBean(FoodPolicyApiTest.Calls.class).owner.set("OTHER");
            mvc(c).perform(put("/biz/x/food/table-areas/AREA1").header("Authorization",token(c,"staff"))
                            .header("Idempotency-Key","original").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"大厅\",\"sortNo\":0,\"version\":0}"))
                    .andExpect(jsonPath("$.code").value(10403));
            assertThat(c.getBean(Calls.class).count).hasValue(0);
        });
    }
    @Test void rejectsMissingKeyInvalidQueryAndUnknownIdentityBody() {
        runner.run(c -> {
            var mvc = mvc(c);
            String token = token(c,"staff");
            mvc.perform(post("/biz/x/food/stores/A/table-areas").header("Authorization",token)
                            .contentType(MediaType.APPLICATION_JSON).content(CREATE))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(post("/biz/x/food/stores/A/table-areas").header("Authorization",token)
                            .header("Idempotency-Key","original").contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE.replace("{","{\"actorNo\":\"forged\",")))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(get("/biz/x/food/stores/A/table-areas").header("Authorization",token).param("size","101"))
                    .andExpect(jsonPath("$.code").value(10400));
            assertThat(c.getBean(Calls.class).count).hasValue(0);
        });
    }
    @Test void offAndDisabledDoNotExposeBController() {
        runner.withPropertyValues("shop.food.migration.mode=off").run(c ->
                assertThat(c).doesNotHaveBean(BizTableAreaController.class));
        runner.withPropertyValues("shop.food.enabled=false").run(c ->
                assertThat(c).doesNotHaveBean(BizTableAreaController.class));
    }
    private MockMvc mvc(WebApplicationContext c) {
        return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
    }
    private String token(WebApplicationContext c, String actor) {
        return "Bearer " + c.getBean(MemoryTokenStore.class)
                .issue(TokenStore.SessionData.of(LoginUser.merchant(actor,actor)));
    }
    static class Calls { final AtomicInteger count = new AtomicInteger(); }
    @Configuration(proxyBeanMethods=false)
    static class Host {
        @Bean Calls areaCalls() { return new Calls(); }
        @Bean TableService tableService(Calls calls) {
            return new TableService() {
                private void called(String e, String s, String actor, String key) {
                    assertThat(e).isEqualTo("E"); assertThat(s).isEqualTo("A");
                    assertThat(actor).isEqualTo("staff"); assertThat(key).isEqualTo("original");
                    calls.count.incrementAndGet();
                }
                public PageData<TableAreaVO> listTableAreas(String e,String s,ListTableAreasQuery q) {
                    called(e,s,"staff","original");
                    return PageData.of(List.of(area("大厅","ACTIVE",0)),1,1,20);
                }
                public TableAreaResultVO createTableArea(String e,String s,String actor,CreateTableAreaReq r,String k) {
                    called(e,s,actor,k); return new TableAreaResultVO(area(r.name(),"ACTIVE",0));
                }
                public TableAreaResultVO saveTableArea(String e,String s,String n,String actor,SaveTableAreaReq r,String k) {
                    assertThat(n).isEqualTo("AREA1");called(e,s,actor,k);
                    return new TableAreaResultVO(area(r.name(),"ACTIVE",1));
                }
                public TableAreaResultVO setStatusTableArea(String e,String s,String n,String actor,
                        SetStatusTableAreaReq r,String k) {
                    assertThat(n).isEqualTo("AREA1");called(e,s,actor,k);
                    return new TableAreaResultVO(area("包间",r.status(),2));
                }
            };
        }
        private TableAreaVO area(String name,String status,long version) {
            return new TableAreaVO("AREA1","A","HALL",name,0,status,version);
        }
    }
}
